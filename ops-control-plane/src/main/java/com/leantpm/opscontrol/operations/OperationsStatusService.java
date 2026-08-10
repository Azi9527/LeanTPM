package com.leantpm.opscontrol.operations;

public interface OperationsStatusService {
    OperationsDashboard status();

    OperationsDashboard refresh();
}
