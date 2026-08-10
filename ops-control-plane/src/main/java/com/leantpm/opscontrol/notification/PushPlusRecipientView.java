package com.leantpm.opscontrol.notification;

public record PushPlusRecipientView(
    String id,
    String name,
    String channel,
    boolean enabled
) {
}
