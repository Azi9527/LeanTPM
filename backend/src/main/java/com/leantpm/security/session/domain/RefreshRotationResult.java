package com.leantpm.security.session.domain;

public enum RefreshRotationResult {
    ROTATED,
    REUSED,
    INVALID,
    REVOKED,
    MISMATCH
}
