package com.leantpm.security.session.domain;

public enum SessionValidationResult {
    ACTIVE,
    INVALID,
    REVOKED,
    MISMATCH
}
