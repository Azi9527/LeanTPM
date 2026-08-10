package com.leantpm.opscontrol.release;

import java.nio.file.Path;

public record SignedDeploymentMaterial(
    String approvalId,
    Path deploymentPlanPath,
    String deploymentPlanSha256,
    Path requesterSignaturePath,
    String requesterSignatureSha256,
    Path approverSignaturePath,
    String approverSignatureSha256
) {
}
