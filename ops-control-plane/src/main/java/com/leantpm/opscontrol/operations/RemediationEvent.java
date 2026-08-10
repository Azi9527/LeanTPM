package com.leantpm.opscontrol.operations;

import java.time.Instant;

public record RemediationEvent(
    String eventId,
    String componentId,
    RemediationAction action,
    RemediationOutcome outcome,
    Instant occurredAt,
    String summary
) {
}
