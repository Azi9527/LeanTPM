package com.leantpm.opscontrol.notification;

import java.time.Instant;
import java.util.Objects;

public record OpsNotification(
    String eventType,
    NotificationSeverity severity,
    String title,
    String impact,
    String automaticAction,
    String currentStatus,
    String recommendedAction,
    Instant occurredAt,
    String deduplicationKey
) {
    public OpsNotification {
        eventType = required(eventType, "eventType");
        severity = Objects.requireNonNull(severity, "severity");
        title = required(title, "title");
        impact = required(impact, "impact");
        automaticAction = required(automaticAction, "automaticAction");
        currentStatus = required(currentStatus, "currentStatus");
        recommendedAction = required(recommendedAction, "recommendedAction");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        deduplicationKey = required(deduplicationKey, "deduplicationKey");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
