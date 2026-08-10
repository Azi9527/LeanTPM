package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface DeploymentBundleMaterializer {
    MaterializedDeployment materialize(
        StoredPackage storedBundle,
        DeploymentBundleVerification verification
    );
}
