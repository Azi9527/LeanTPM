package com.leantpm.opscontrol.operations;

import java.time.Duration;

public record RemediationPolicy(
    boolean enabled,
    int failureThreshold,
    Duration cooldown,
    int maximumAttemptsPerHour
) {
    public RemediationPolicy {
        if (failureThreshold < 1 || failureThreshold > 20) {
            throw new IllegalArgumentException("failureThreshold must be between 1 and 20");
        }
        if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        if (maximumAttemptsPerHour < 1 || maximumAttemptsPerHour > 10) {
            throw new IllegalArgumentException("maximumAttemptsPerHour must be between 1 and 10");
        }
    }
}
