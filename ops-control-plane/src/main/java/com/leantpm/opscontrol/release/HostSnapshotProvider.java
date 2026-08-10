package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface HostSnapshotProvider {
    HostSnapshot snapshot();
}
