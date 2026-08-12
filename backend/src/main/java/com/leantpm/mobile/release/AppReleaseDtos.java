package com.leantpm.mobile.release;

import java.time.LocalDateTime;

public final class AppReleaseDtos {
    private AppReleaseDtos() {
    }

    public record AndroidRelease(
            boolean available,
            boolean enabled,
            String versionName,
            Integer versionCode,
            Integer minimumVersionCode,
            boolean forceUpgrade,
            String fileName,
            Long fileSize,
            String sha256,
            String releaseNotes,
            LocalDateTime publishedTime,
            String downloadUrl,
            String qrCodeUrl
    ) {
        public static AndroidRelease unavailable() {
            return new AndroidRelease(
                    false, false, null, null, null, false, null, null, null,
                    null, null, null, null
            );
        }
    }
}
