package com.leantpm.opscontrol.notification;

public interface NotificationPublisher {
    PushPlusDeliverySummary publish(OpsNotification notification);

    PushPlusNotificationStatus status();
}
