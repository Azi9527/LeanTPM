package com.leantpm.opscontrol.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class OperationsScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationsScheduler.class);
    private final OperationsStatusService operations;

    public OperationsScheduler(OperationsStatusService operations) {
        this.operations = operations;
    }

    @Scheduled(
        fixedDelayString = "${leantpm.ops.monitoring.interval:30s}",
        initialDelayString = "${leantpm.ops.monitoring.initial-delay:10s}"
    )
    public void refresh() {
        try {
            operations.refresh();
        } catch (RuntimeException exception) {
            LOGGER.error("Operations monitoring cycle failed; no unbounded retry will run", exception);
        }
    }
}
