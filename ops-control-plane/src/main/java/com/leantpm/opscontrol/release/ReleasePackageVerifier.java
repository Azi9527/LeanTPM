package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface ReleasePackageVerifier {
    VerificationReport verify(StoredPackage storedPackage);
}
