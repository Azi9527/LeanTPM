package com.leantpm.opscontrol.notification;

import java.time.Instant;

public record PushPlusDeliverySummary(
    PushPlusDispatchStatus status,
    int configuredRecipients,
    int acceptedRecipients,
    int failedRecipients,
    Instant attemptedAt,
    String summary
) {
}
