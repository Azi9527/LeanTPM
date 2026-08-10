package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface ReleaseAgent {
    String enqueue(DeployReleaseCommand command);
}
