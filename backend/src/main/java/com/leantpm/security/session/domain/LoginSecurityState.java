package com.leantpm.security.session.domain;

import java.time.LocalDateTime;

public record LoginSecurityState(
        long tenantId,
        String principalKey,
        Long userId,
        int failureCount,
        LocalDateTime windowStartedAt,
        LocalDateTime lockedUntil,
        LocalDateTime lastFailureAt,
        long version
) {
}
