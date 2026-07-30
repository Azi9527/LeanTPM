package com.leantpm.security.session;

import java.time.Instant;

public record OnlineSession(
        String sessionId,
        long userId,
        String username,
        String realName,
        String loginIp,
        String userAgent,
        Instant loginTime,
        Instant lastActiveTime,
        Instant expiresAt,
        boolean currentSession
) {
}
