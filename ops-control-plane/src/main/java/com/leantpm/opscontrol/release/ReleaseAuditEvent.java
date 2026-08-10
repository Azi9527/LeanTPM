package com.leantpm.opscontrol.release;

public record ReleaseAuditEvent(
    long sequence,
    String eventType,
    String releaseId,
    ReleaseState state,
    String operation,
    String previousEventSha256,
    String eventSha256
) {
}
