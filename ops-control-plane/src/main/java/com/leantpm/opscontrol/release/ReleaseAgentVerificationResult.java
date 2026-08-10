package com.leantpm.opscontrol.release;

import java.time.Instant;

public record ReleaseAgentVerificationResult(
    String agentId,
    String agentVersion,
    String commandId,
    int databaseSchemaVersion,
    String hostSnapshotSha256,
    String manifestSha256,
    String packageSha256,
    String planSha256,
    boolean productionExecutionEnabled,
    String productVersion,
    String releaseId,
    int schemaVersion,
    String status,
    String approvalId,
    String deploymentStatus,
    String deploymentReportSha256,
    Instant verifiedAt,
    String resultSha256
) {
    public ReleaseAgentVerificationResult(
        String agentId,
        String agentVersion,
        String commandId,
        int databaseSchemaVersion,
        String hostSnapshotSha256,
        String manifestSha256,
        String packageSha256,
        String planSha256,
        boolean productionExecutionEnabled,
        String productVersion,
        String releaseId,
        int schemaVersion,
        String status,
        Instant verifiedAt,
        String resultSha256
    ) {
        this(
            agentId,
            agentVersion,
            commandId,
            databaseSchemaVersion,
            hostSnapshotSha256,
            manifestSha256,
            packageSha256,
            planSha256,
            productionExecutionEnabled,
            productVersion,
            releaseId,
            schemaVersion,
            status,
            null,
            null,
            null,
            verifiedAt,
            resultSha256
        );
    }
}
