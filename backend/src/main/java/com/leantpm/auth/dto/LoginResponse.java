package com.leantpm.auth.dto;

public record LoginResponse(
        TokenPair tokens,
        UserProfile user
) {
}
