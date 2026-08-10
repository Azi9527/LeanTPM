package com.leantpm.opscontrol.release;

public record ConfirmationResult(
    String releaseId,
    ReleaseState state,
    int approvals,
    int requiredApprovals,
    String jobId
) {
}
