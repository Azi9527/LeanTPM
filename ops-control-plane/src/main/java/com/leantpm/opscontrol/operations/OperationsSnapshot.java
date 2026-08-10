package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.List;

public record OperationsSnapshot(
    boolean enabled,
    OperationsHealth overallStatus,
    Instant observedAt,
    List<OperationsComponent> components,
    List<RemediationEvent> recentRemediations
) {
    public OperationsSnapshot {
        components = components == null ? List.of() : List.copyOf(components);
        recentRemediations = recentRemediations == null
            ? List.of()
            : List.copyOf(recentRemediations);
    }
}
