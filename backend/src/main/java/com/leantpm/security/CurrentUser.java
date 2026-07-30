package com.leantpm.security;

import java.util.Set;

public record CurrentUser(
        long userId,
        long tenantId,
        String username,
        String realName,
        boolean mustChangePassword,
        Set<String> roles,
        Set<String> permissions
) {
}
