package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OperationsRuntimeState(
    int schemaVersion,
    Map<String, String> componentStatuses,
    Map<String, Integer> consecutiveFailures,
    Map<String, Instant> lastRemediationAt,
    Map<String, List<Instant>> remediationAttempts,
    Map<String, String> releaseStatuses,
    List<RemediationEvent> recentRemediations
) {
    public OperationsRuntimeState {
        componentStatuses = copy(componentStatuses);
        consecutiveFailures = copy(consecutiveFailures);
        lastRemediationAt = copy(lastRemediationAt);
        releaseStatuses = copy(releaseStatuses);
        if (remediationAttempts == null) {
            remediationAttempts = Map.of();
        } else {
            Map<String, List<Instant>> copiedAttempts = new LinkedHashMap<>();
            remediationAttempts.forEach((key, values) ->
                copiedAttempts.put(key, List.copyOf(values))
            );
            remediationAttempts = Collections.unmodifiableMap(copiedAttempts);
        }
        recentRemediations = recentRemediations == null
            ? List.of()
            : List.copyOf(recentRemediations);
    }

    public static OperationsRuntimeState empty() {
        return new OperationsRuntimeState(1, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    private static <K, V> Map<K, V> copy(Map<K, V> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }
}
