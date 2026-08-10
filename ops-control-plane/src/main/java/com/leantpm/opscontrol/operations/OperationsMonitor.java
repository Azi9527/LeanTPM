package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface OperationsMonitor {
    List<OperationsComponent> observe(Instant observedAt);

    default boolean enabled() {
        return true;
    }
}
