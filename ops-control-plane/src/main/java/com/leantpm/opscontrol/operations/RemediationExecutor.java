package com.leantpm.opscontrol.operations;

@FunctionalInterface
public interface RemediationExecutor {
    RemediationResult execute(RemediationAction action);
}
