package com.leantpm.auth.dto;

import java.time.Instant;

public record CaptchaChallenge(
        boolean enabled,
        String captchaId,
        String imageDataUrl,
        Instant expiresAt
) {
    public static CaptchaChallenge disabled() {
        return new CaptchaChallenge(false, null, null, null);
    }
}
