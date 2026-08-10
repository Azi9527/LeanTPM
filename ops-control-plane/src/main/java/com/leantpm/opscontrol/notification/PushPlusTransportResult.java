package com.leantpm.opscontrol.notification;

public record PushPlusTransportResult(
    boolean accepted,
    String requestId,
    String summary
) {
}
