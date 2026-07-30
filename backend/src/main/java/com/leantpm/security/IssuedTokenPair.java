package com.leantpm.security;

import com.leantpm.auth.dto.TokenPair;

public record IssuedTokenPair(
        TokenPair tokens,
        String sessionId,
        String accessTokenId,
        String refreshTokenId
) {
}
