package com.leantpm.opscontrol.notification;

import java.util.List;

public record PushPlusNotificationStatus(
    boolean enabled,
    List<PushPlusRecipientView> recipients,
    PushPlusDeliverySummary lastDispatch
) {
    public PushPlusNotificationStatus {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
