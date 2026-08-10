package com.leantpm.opscontrol.release;

import java.time.Instant;

public record DeploymentPlan(
    int schemaVersion,
    String action,
    String releaseId,
    String packageSha256,
    String manifestSha256,
    String hostSnapshotSha256,
    String nonce,
    Instant issuedAt,
    Instant expiresAt,
    String requestedBy,
    String planSha256
) {
}
