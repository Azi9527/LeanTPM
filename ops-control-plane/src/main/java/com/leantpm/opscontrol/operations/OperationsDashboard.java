package com.leantpm.opscontrol.operations;

import com.leantpm.opscontrol.notification.PushPlusNotificationStatus;

public record OperationsDashboard(
    OperationsSnapshot snapshot,
    PushPlusNotificationStatus notifications
) {
}
