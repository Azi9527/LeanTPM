package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ReleaseRecord(
    String releaseId,
    String productVersion,
    int databaseSchemaVersion,
    String originalFileName,
    Path packagePath,
    long packageSize,
    String packageSha256,
    String manifestSha256,
    ReleaseState state,
    DeploymentPlan plan,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    SignedDeploymentMaterial deploymentMaterial,
    List<ReleaseApproval> approvals,
    String jobId,
    String importedBy,
    Instant importedAt
) {
    public ReleaseRecord(
        String releaseId,
        String productVersion,
        int databaseSchemaVersion,
        String originalFileName,
        Path packagePath,
        long packageSize,
        String packageSha256,
        String manifestSha256,
        ReleaseState state,
        DeploymentPlan plan,
        List<ReleaseApproval> approvals,
        String jobId,
        String importedBy,
        Instant importedAt
    ) {
        this(
            releaseId,
            productVersion,
            databaseSchemaVersion,
            originalFileName,
            packagePath,
            packageSize,
            packageSha256,
            manifestSha256,
            state,
            plan,
            null,
            approvals,
            jobId,
            importedBy,
            importedAt
        );
    }

    public ReleaseRecord {
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
    }

    public ReleaseRecord withPlan(DeploymentPlan newPlan) {
        return new ReleaseRecord(
            releaseId, productVersion, databaseSchemaVersion, originalFileName, packagePath,
            packageSize, packageSha256, manifestSha256, ReleaseState.AWAITING_CONFIRMATION,
            newPlan, deploymentMaterial, List.of(), null, importedBy, importedAt
        );
    }

    public ReleaseRecord withApproval(ReleaseApproval approval) {
        var updated = new java.util.ArrayList<>(approvals);
        updated.add(approval);
        return new ReleaseRecord(
            releaseId, productVersion, databaseSchemaVersion, originalFileName, packagePath,
            packageSize, packageSha256, manifestSha256, ReleaseState.AWAITING_CONFIRMATION,
            plan, deploymentMaterial, updated, jobId, importedBy, importedAt
        );
    }

    public ReleaseRecord queued(String newJobId) {
        return new ReleaseRecord(
            releaseId, productVersion, databaseSchemaVersion, originalFileName, packagePath,
            packageSize, packageSha256, manifestSha256, ReleaseState.QUEUED,
            plan, deploymentMaterial, approvals, newJobId, importedBy, importedAt
        );
    }

    public ReleaseRecord agentVerified() {
        return new ReleaseRecord(
            releaseId, productVersion, databaseSchemaVersion, originalFileName, packagePath,
            packageSize, packageSha256, manifestSha256, ReleaseState.AGENT_VERIFIED,
            plan, deploymentMaterial, approvals, jobId, importedBy, importedAt
        );
    }

    public ReleaseRecord deployed() {
        return new ReleaseRecord(
            releaseId, productVersion, databaseSchemaVersion, originalFileName, packagePath,
            packageSize, packageSha256, manifestSha256, ReleaseState.DEPLOYED,
            plan, deploymentMaterial, approvals, jobId, importedBy, importedAt
        );
    }
}
