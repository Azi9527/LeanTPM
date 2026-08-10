package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.release.DeploymentPlan;
import com.leantpm.opscontrol.release.ReleaseApproval;
import com.leantpm.opscontrol.release.ReleaseRecord;
import com.leantpm.opscontrol.release.ReleaseState;
import java.time.Instant;
import java.util.List;

public record ReleaseView(
    String releaseId,
    String productVersion,
    int databaseSchemaVersion,
    String originalFileName,
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
    public static ReleaseView from(ReleaseRecord record) {
        return new ReleaseView(
            record.releaseId(),
            record.productVersion(),
            record.databaseSchemaVersion(),
            record.originalFileName(),
            record.packageSize(),
            record.packageSha256(),
            record.manifestSha256(),
            record.state(),
            record.plan(),
            record.approvals(),
            record.jobId(),
            record.importedBy(),
            record.importedAt()
        );
    }
}
