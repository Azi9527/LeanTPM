package com.leantpm.security.session.domain;

import java.time.LocalDateTime;

public record AuthSessionRecord(
        String sessionId,
        long tenantId,
        long userId,
        long userVersion,
        String username,
        String realName,
        String loginIp,
        String userAgent,
        LocalDateTime loginTime,
        LocalDateTime lastActiveTime,
        LocalDateTime expiresAt,
        String refreshJtiHash,
        String status,
        LocalDateTime revokedAt,
        String revocationReason,
        long version
) {
    public AuthSessionRecord(
            String sessionId,
            long tenantId,
            long userId,
            String username,
            String realName,
            String loginIp,
            String userAgent,
            LocalDateTime loginTime,
            LocalDateTime lastActiveTime,
            LocalDateTime expiresAt,
            String refreshJtiHash,
            String status,
            LocalDateTime revokedAt,
            String revocationReason,
            long version
    ) {
        this(
                sessionId, tenantId, userId, 0L, username, realName, loginIp, userAgent,
                loginTime, lastActiveTime, expiresAt, refreshJtiHash, status,
                revokedAt, revocationReason, version
        );
    }
}
