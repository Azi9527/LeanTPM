package com.leantpm.opscontrol.operations;

public record ServiceObservation(
    ServiceTarget target,
    OperationsHealth status,
    String summary
) {
}
