package com.leantpm.opscontrol.release;

public record HostSnapshot(
    String environmentId,
    String hostId,
    String currentReleaseId,
    String currentPackageSha256
) {
}
