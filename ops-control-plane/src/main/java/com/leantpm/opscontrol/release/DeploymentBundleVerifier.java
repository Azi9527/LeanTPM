package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface DeploymentBundleVerifier {
    DeploymentBundleVerification verify(
        StoredPackage storedBundle,
        HostSnapshot hostSnapshot,
        String hostSnapshotSha256
    );
}
