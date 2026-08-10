package com.leantpm.opscontrol.release;

import java.time.Instant;

public record DeploymentBundleVerification(
    boolean valid,
    String releaseId,
    String productVersion,
    int databaseSchemaVersion,
    String environmentId,
    String hostId,
    String hostSnapshotSha256,
    String bundleSha256,
    long releasePackageBytes,
    String releasePackageSha256,
    String manifestSha256,
    String deploymentPlanSha256,
    String requesterSignatureSha256,
    String approverSignatureSha256,
    String approvalId,
    String nonce,
    String requestedBy,
    String approvedBy,
    Instant issuedAt,
    Instant expiresAt
) {
}
