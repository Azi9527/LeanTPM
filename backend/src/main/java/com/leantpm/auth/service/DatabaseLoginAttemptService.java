package com.leantpm.auth.service;

import com.leantpm.auth.domain.UserAccount;
import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.security.JwtProperties;
import com.leantpm.security.session.domain.LoginSecurityState;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

@Service
public class DatabaseLoginAttemptService {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final AuthMapper authMapper;
    private final AuthSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties properties;
    private final String dummyPasswordHash;

    public DatabaseLoginAttemptService(
            AuthMapper authMapper,
            AuthSessionMapper sessionMapper,
            PasswordEncoder passwordEncoder,
            JwtProperties properties
    ) {
        this.authMapper = authMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.dummyPasswordHash = passwordEncoder.encode("LeanTPM-login-timing-sentinel");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginAttemptResult verify(
            long tenantId,
            String username,
            String password,
            String loginIp,
            String userAgent
    ) {
        LocalDateTime now = LocalDateTime.now(DATABASE_ZONE);
        UserAccount user = authMapper.findByUsername(tenantId, username);
        String addressKey = addressKey(tenantId, loginIp);
        String userKey = user == null ? null : "U:" + user.getId();

        LoginSecurityState addressState = lockGate(tenantId, addressKey, null, now);
        LoginSecurityState userState = userKey == null
                ? null
                : lockGate(tenantId, userKey, user.getId(), now);
        if (isLocked(addressState, now) || isLocked(userState, now)) {
            passwordEncoder.matches(password, dummyPasswordHash);
            return LoginAttemptResult.locked();
        }

        boolean active = user != null
                && user.getStatus() != null
                && user.getStatus() == 1;
        String candidateHash = active && user.getPasswordHash() != null
                ? user.getPasswordHash()
                : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(password, candidateHash);
        boolean authenticated = active && passwordMatches;
        if (authenticated) {
            sessionMapper.deleteLoginSecurityState(tenantId, userKey);
            return LoginAttemptResult.authenticated(user);
        }

        authMapper.insertLoginLog(
                tenantId,
                user == null ? null : user.getId(),
                username,
                safe(loginIp, 64),
                safe(userAgent, 500),
                false,
                "INVALID_CREDENTIALS"
        );
        recordFailure(
                tenantId, addressKey, null, now, properties.getMaxLoginSourceFailures()
        );
        if (userKey != null) {
            recordFailure(
                    tenantId, userKey, user.getId(), now, properties.getMaxLoginFailures()
            );
        }
        boolean locked = isLocked(sessionMapper.findLoginSecurityState(tenantId, addressKey), now)
                || (userKey != null
                && isLocked(sessionMapper.findLoginSecurityState(tenantId, userKey), now));
        return locked ? LoginAttemptResult.locked() : LoginAttemptResult.failed();
    }

    private LoginSecurityState lockGate(
            long tenantId,
            String principalKey,
            Long userId,
            LocalDateTime now
    ) {
        sessionMapper.ensureLoginSecurityState(tenantId, principalKey, userId, now);
        LoginSecurityState state = sessionMapper.findLoginSecurityStateForUpdate(
                tenantId, principalKey
        );
        if (state == null) {
            throw new IllegalStateException("Login security gate was not persisted");
        }
        return state;
    }

    private void recordFailure(
            long tenantId,
            String principalKey,
            Long userId,
            LocalDateTime now,
            int maxFailures
    ) {
        sessionMapper.upsertLoginFailure(
                tenantId,
                principalKey,
                userId,
                now,
                now.minusMinutes(properties.getFailureWindowMinutes()),
                now.plusMinutes(properties.getFailureWindowMinutes()),
                maxFailures
        );
    }

    private boolean isLocked(LoginSecurityState state, LocalDateTime now) {
        return state != null && state.lockedUntil() != null && state.lockedUntil().isAfter(now);
    }

    private String addressKey(long tenantId, String loginIp) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is required for login security HMAC");
        }
        String normalizedAddress = loginIp == null || loginIp.isBlank()
                ? "unknown"
                : loginIp.trim().toLowerCase(java.util.Locale.ROOT);
        return "I:" + hmacSha256(secret, tenantId + ":" + normalizedAddress);
    }

    private String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String safe(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(maxLength, value.length()));
    }
}
