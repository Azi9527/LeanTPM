package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record OperationsComponent(
    String id,
    String label,
    OperationsComponentKind kind,
    OperationsHealth status,
    String summary,
    Instant observedAt,
    Map<String, String> metrics,
    RemediationAction remediationAction
) {
    public OperationsComponent {
        id = required(id, "id");
        label = required(label, "label");
        kind = Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        summary = required(summary, "summary");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
