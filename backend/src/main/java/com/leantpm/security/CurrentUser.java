package com.leantpm.security;

import java.util.Set;

public record CurrentUser(
        long userId,
        long tenantId,
        String username,
        String realName,
        boolean mustChangePassword,
        Set<String> roles,
        Set<String> permissions,
        long authEpoch,
        String sessionId
) {
    public CurrentUser(
            long userId,
            long tenantId,
            String username,
            String realName,
            boolean mustChangePassword,
            Set<String> roles,
            Set<String> permissions,
            String sessionId
    ) {
        this(
                userId, tenantId, username, realName, mustChangePassword,
                roles, permissions, 0L, sessionId
        );
    }

    public CurrentUser withSessionId(String value) {
        return new CurrentUser(
                userId, tenantId, username, realName, mustChangePassword,
                roles, permissions, authEpoch, value
        );
    }
}
