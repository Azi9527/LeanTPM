package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Path;
import java.time.Instant;

public record DeployReleaseCommand(
    int schemaVersion,
    String commandId,
    String releaseId,
    String productVersion,
    int databaseSchemaVersion,
    Path packagePath,
    String packageSha256,
    String manifestSha256,
    String planSha256,
    String hostSnapshotSha256,
    Instant expiresAt,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    SignedDeploymentMaterial deploymentMaterial
) {
    public DeployReleaseCommand(
        int schemaVersion,
        String commandId,
        String releaseId,
        String productVersion,
        int databaseSchemaVersion,
        Path packagePath,
        String packageSha256,
        String manifestSha256,
        String planSha256,
        String hostSnapshotSha256,
        Instant expiresAt
    ) {
        this(
            schemaVersion,
            commandId,
            releaseId,
            productVersion,
            databaseSchemaVersion,
            packagePath,
            packageSha256,
            manifestSha256,
            planSha256,
            hostSnapshotSha256,
            expiresAt,
            null
        );
    }
}
