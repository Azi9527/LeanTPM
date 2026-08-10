package com.leantpm.opscontrol.operations;

public interface OperationsStateStore {
    OperationsRuntimeState load();

    void save(OperationsRuntimeState state);
}
