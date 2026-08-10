package com.leantpm.security.session;

import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.security.session.domain.AuthSessionRecord;
import com.leantpm.security.session.domain.RefreshRotationResult;
import com.leantpm.security.session.domain.SessionValidationResult;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthSessionTransactions {
    private static final String ACTIVE = "ACTIVE";

    private final AuthSessionMapper mapper;
    private final AuthMapper authMapper;

    public AuthSessionTransactions(AuthSessionMapper mapper, AuthMapper authMapper) {
        this.mapper = mapper;
        this.authMapper = authMapper;
    }

    @Transactional
    public void register(AuthSessionRecord session) {
        assertActiveUserVersion(session);
        mapper.insertSession(session);
    }

    @Transactional
    public void registerSuccessfulLogin(AuthSessionRecord session, String principalKey) {
        assertActiveUserVersion(session);
        mapper.insertSession(session);
        mapper.deleteLoginSecurityState(session.tenantId(), principalKey);
        authMapper.updateLastLogin(session.tenantId(), session.userId());
        authMapper.insertLoginLog(
                session.tenantId(),
                session.userId(),
                session.username(),
                session.loginIp(),
                session.userAgent(),
                true,
                null
        );
    }

    @Transactional
    public SessionValidationResult validateAndTouch(
            String sessionId,
            long tenantId,
            long userId,
            long userVersion,
            LocalDateTime now
    ) {
        if (mapper.touchActiveSession(sessionId, tenantId, userId, userVersion, now) == 1) {
            return SessionValidationResult.ACTIVE;
        }
        AuthSessionRecord session = mapper.findSession(sessionId);
        if (session == null || !session.expiresAt().isAfter(now)) {
            return SessionValidationResult.INVALID;
        }
        if (!ACTIVE.equals(session.status())) {
            return SessionValidationResult.REVOKED;
        }
        if (session.tenantId() != tenantId || session.userId() != userId) {
            return SessionValidationResult.MISMATCH;
        }
        return SessionValidationResult.INVALID;
    }

    private void assertActiveUserVersion(AuthSessionRecord session) {
        Long activeVersion = mapper.findActiveUserVersionForUpdate(
                session.tenantId(), session.userId()
        );
        if (activeVersion == null || activeVersion.longValue() != session.userVersion()) {
            throw new IllegalStateException("User security version changed before session registration");
        }
    }

    @Transactional
    public RefreshRotationResult rotateRefresh(
            String sessionId,
            long tenantId,
            long userId,
            long userVersion,
            String previousJtiHash,
            String nextJtiHash,
            LocalDateTime nextExpiresAt,
            LocalDateTime now
    ) {
        AuthSessionRecord session = mapper.findSessionForUpdate(sessionId);
        if (session == null || !session.expiresAt().isAfter(now)) {
            return RefreshRotationResult.INVALID;
        }
        if (!ACTIVE.equals(session.status())) {
            return RefreshRotationResult.REVOKED;
        }
        if (session.tenantId() != tenantId || session.userId() != userId) {
            return RefreshRotationResult.MISMATCH;
        }
        if (session.userVersion() != userVersion) {
            return RefreshRotationResult.MISMATCH;
        }
        if (!session.refreshJtiHash().equals(previousJtiHash)) {
            int revoked = mapper.revokeSession(
                    sessionId, "REFRESH_TOKEN_REUSED", now, session.version()
            );
            if (revoked != 1) {
                throw new IllegalStateException("Reused refresh session could not be revoked");
            }
            return RefreshRotationResult.REUSED;
        }
        int rotated = mapper.rotateRefresh(
                sessionId, nextJtiHash, nextExpiresAt, now, session.version()
        );
        if (rotated != 1) {
            throw new IllegalStateException("Refresh session could not be atomically rotated");
        }
        return RefreshRotationResult.ROTATED;
    }

    @Transactional
    public void revoke(String sessionId, String reason, LocalDateTime now) {
        mapper.revokeSessionById(sessionId, reason, now);
    }

    @Transactional
    public void revokeAllUserSessions(long tenantId, long userId, String reason, LocalDateTime now) {
        mapper.revokeAllUserSessions(tenantId, userId, reason, now);
    }

    @Transactional
    public boolean revokeTenantSession(
            long tenantId,
            String sessionId,
            String reason,
            LocalDateTime now
    ) {
        return mapper.revokeTenantSession(tenantId, sessionId, reason, now) == 1;
    }

}
