package com.leantpm.opscontrol.release;

import java.nio.file.Path;

public record MaterializedDeployment(
    StoredPackage releasePackage,
    Path deploymentPlanPath,
    Path requesterSignaturePath,
    Path approverSignaturePath
) {
}
