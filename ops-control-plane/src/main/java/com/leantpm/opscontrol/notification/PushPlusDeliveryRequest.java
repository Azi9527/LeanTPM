package com.leantpm.opscontrol.notification;

public record PushPlusDeliveryRequest(
    String destinationId,
    String token,
    String title,
    String content,
    String template,
    String channel,
    String topic,
    String option,
    long timestamp
) {
}
