package com.leantpm.mobile;

import java.time.LocalDateTime;
import java.util.List;

public final class MobileDtos {
    private MobileDtos() {
    }

    public record WorkCount(
            long dueToday,
            long pending,
            long overdue,
            long completedToday
    ) {
    }

    public record MessageItem(
            String messageType,
            String severity,
            String title,
            String content,
            LocalDateTime occurredTime,
            String routePath
    ) {
    }

    public record Bootstrap(
            LocalDateTime serverTime,
            int draftRetentionDays,
            int maxUploadMb,
            WorkCount inspection,
            WorkCount maintenance,
            List<MessageItem> messages
    ) {
    }

    public record EquipmentBase(
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            String categoryName,
            String organizationName,
            String locationName,
            String statusCode,
            String statusName,
            String statusColor,
            LocalDateTime statusSince,
            String responsibleName
    ) {
    }

    public record TaskLink(
            long taskId,
            String taskCode,
            String workflowType,
            String schemeName,
            String taskStatus,
            LocalDateTime dueTime,
            String routePath
    ) {
    }

    public record EquipmentContext(
            EquipmentBase equipment,
            List<TaskLink> activeTasks
    ) {
    }
}
