package com.leantpm.security.session;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.IssuedTokenPair;
import com.leantpm.security.session.domain.AuthSessionRecord;
import com.leantpm.security.session.domain.RefreshRotationResult;
import com.leantpm.security.session.domain.SessionValidationResult;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import io.jsonwebtoken.Claims;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

@Primary
@Service
public class DatabaseAuthSessionService implements AuthSessionService {
    private static final String ACTIVE = "ACTIVE";
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final AuthSessionMapper mapper;
    private final AuthSessionTransactions transactions;

    public DatabaseAuthSessionService(
            AuthSessionMapper mapper,
            AuthSessionTransactions transactions
    ) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    @Override
    public void register(
            CurrentUser user,
            IssuedTokenPair issued,
            String loginIp,
            String userAgent
    ) {
        database(() -> {
            transactions.register(session(user, issued, loginIp, userAgent));
            return null;
        });
    }

    @Override
    public void registerLogin(
            CurrentUser user,
            IssuedTokenPair issued,
            String loginIp,
            String userAgent
    ) {
        database(() -> {
            transactions.registerSuccessfulLogin(
                    session(user, issued, loginIp, userAgent), knownPrincipalKey(user.userId())
            );
            return null;
        });
    }

    @Override
    public void validateAccess(Claims claims) {
        String sessionId = requiredClaim(claims, "sid");
        long tenantId = numberClaim(claims, "tid");
        long userId = numberClaim(claims, "uid");
        long userVersion = numberClaim(claims, "uv");
        SessionValidationResult result = database(() -> transactions.validateAndTouch(
                sessionId, tenantId, userId, userVersion, now()
        ));
        switch (result) {
            case ACTIVE -> {
                return;
            }
            case REVOKED -> throw unauthorized(
                    "TOKEN_REVOKED", "登录会话已退出或被强制下线"
            );
            case MISMATCH -> throw unauthorized(
                    "TOKEN_SESSION_MISMATCH", "登录会话与令牌不匹配"
            );
            case INVALID -> throw unauthorized(
                    "TOKEN_SESSION_INVALID", "登录会话不存在或已过期"
            );
        }
    }

    @Override
    public void rotate(Claims previousClaims, IssuedTokenPair issued) {
        String sessionId = requiredClaim(previousClaims, "sid");
        String previousTokenId = previousClaims.getId();
        if (previousTokenId == null || previousTokenId.isBlank()
                || !sessionId.equals(issued.sessionId())) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "刷新令牌缺少会话标识");
        }
        long tenantId = numberClaim(previousClaims, "tid");
        long userId = numberClaim(previousClaims, "uid");
        long userVersion = numberClaim(previousClaims, "uv");
        RefreshRotationResult result = database(() -> transactions.rotateRefresh(
                sessionId,
                tenantId,
                userId,
                userVersion,
                sha256(previousTokenId),
                sha256(issued.refreshTokenId()),
                local(issued.tokens().refreshExpiresAt()),
                now()
        ));
        switch (result) {
            case ROTATED -> {
                return;
            }
            case REUSED -> throw unauthorized(
                    "REFRESH_TOKEN_REUSED", "刷新令牌已使用，会话已安全退出"
            );
            case REVOKED -> throw unauthorized(
                    "TOKEN_REVOKED", "登录会话已退出或被强制下线"
            );
            case MISMATCH -> throw unauthorized(
                    "TOKEN_SESSION_MISMATCH", "登录会话与令牌不匹配"
            );
            case INVALID -> throw unauthorized(
                    "TOKEN_SESSION_INVALID", "登录会话不存在或已过期"
            );
        }
    }

    @Override
    public void revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        database(() -> {
            transactions.revoke(sessionId, "LOGOUT", now());
            return null;
        });
    }

    @Override
    public void revokeAllUserSessions(long tenantId, long userId) {
        database(() -> {
            transactions.revokeAllUserSessions(tenantId, userId, "USER_SECURITY_CHANGE", now());
            return null;
        });
    }

    @Override
    public List<OnlineSession> list(long tenantId, String currentSessionId) {
        return database(() -> mapper.findActiveTenantSessions(tenantId, now()).stream()
                .map(value -> online(value, value.sessionId().equals(currentSessionId)))
                .toList());
    }

    @Override
    public void kickout(long tenantId, String currentSessionId, String targetSessionId) {
        if (targetSessionId.equals(currentSessionId)) {
            throw new BusinessException("CANNOT_KICKOUT_SELF", "当前会话请使用退出登录");
        }
        boolean revoked = database(() -> transactions.revokeTenantSession(
                tenantId, targetSessionId, "ADMIN_KICKOUT", now()
        ));
        if (!revoked) {
            throw new BusinessException(
                    "ONLINE_SESSION_NOT_FOUND", "在线会话不存在", HttpStatus.NOT_FOUND
            );
        }
    }

    private AuthSessionRecord session(
            CurrentUser user,
            IssuedTokenPair issued,
            String loginIp,
            String userAgent
    ) {
        LocalDateTime now = now();
        return new AuthSessionRecord(
                issued.sessionId(),
                user.tenantId(),
                user.userId(),
                user.authEpoch(),
                user.username(),
                safe(user.realName(), 100),
                safe(loginIp, 64),
                safe(userAgent, 500),
                now,
                now,
                local(issued.tokens().refreshExpiresAt()),
                sha256(issued.refreshTokenId()),
                ACTIVE,
                null,
                null,
                0L
        );
    }

    private OnlineSession online(AuthSessionRecord value, boolean currentSession) {
        return new OnlineSession(
                value.sessionId(),
                value.userId(),
                value.username(),
                value.realName(),
                value.loginIp(),
                value.userAgent(),
                instant(value.loginTime()),
                instant(value.lastActiveTime()),
                instant(value.expiresAt()),
                currentSession
        );
    }

    private String knownPrincipalKey(long userId) {
        return "U:" + userId;
    }

    private String requiredClaim(Claims claims, String name) {
        String value = claims.get(name, String.class);
        if (value == null || value.isBlank()) {
            throw unauthorized("INVALID_TOKEN", "令牌缺少会话标识");
        }
        return value;
    }

    private long numberClaim(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        if (value == null) {
            throw unauthorized("INVALID_TOKEN", "令牌缺少用户标识");
        }
        return value.longValue();
    }

    private LocalDateTime now() {
        return local(Instant.now());
    }

    private LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, DATABASE_ZONE);
    }

    private Instant instant(LocalDateTime value) {
        return value.atZone(DATABASE_ZONE).toInstant();
    }

    private String safe(String value, int maxLength) {
        return value == null ? "" : value.substring(0, Math.min(maxLength, value.length()));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    "AUTH_STATE_UNAVAILABLE",
                    "认证安全状态暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private BusinessException unauthorized(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNAUTHORIZED);
    }

}
