package com.leantpm.opscontrol.release;

public record IdempotencyBinding(String requestSha256, String releaseId) {
}
