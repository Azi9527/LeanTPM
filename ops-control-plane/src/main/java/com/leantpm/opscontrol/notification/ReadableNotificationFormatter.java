package com.leantpm.opscontrol.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ReadableNotificationFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.of("Asia/Shanghai"));

    public FormattedNotification format(OpsNotification notification) {
        String severity = switch (notification.severity()) {
            case INFO -> "提示";
            case WARNING -> "警告";
            case CRITICAL -> "严重";
        };
        String title = "[" + severity + "] " + safe(notification.title(), 120);
        String content = String.join("\n\n",
            "# " + title,
            "## 影响范围\n" + safe(notification.impact(), 1000),
            "## 自动处理\n" + safe(notification.automaticAction(), 1000),
            "## 当前状态\n" + safe(notification.currentStatus(), 1000),
            "## 建议操作\n" + safe(notification.recommendedAction(), 1000),
            "## 发生时间\n" + TIME.format(notification.occurredAt()),
            "> 事件类型：`" + safe(notification.eventType(), 80) + "`"
        );
        return new FormattedNotification(title, content);
    }

    private static String safe(String value, int maximum) {
        String normalized = value == null ? "" : value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("`", "'")
            .trim();
        return normalized.length() <= maximum
            ? normalized
            : normalized.substring(0, maximum) + "…";
    }

    public record FormattedNotification(String title, String content) {
    }
}
