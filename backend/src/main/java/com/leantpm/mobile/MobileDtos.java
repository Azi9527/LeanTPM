package com.leantpm.mobile;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
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
            long id,
            String messageType,
            String severity,
            String title,
            String content,
            String businessType,
            long businessId,
            boolean acknowledgeRequired,
            LocalDateTime readTime,
            LocalDateTime acknowledgedTime,
            LocalDateTime occurredTime,
            String routePath
    ) {
    }

    public record Bootstrap(
            LocalDateTime serverTime,
            int draftRetentionDays,
            int maxUploadMb,
            PhotoPolicy photoPolicy,
            AndroidVersionPolicy androidVersion,
            WorkCount inspection,
            WorkCount maintenance,
            List<MessageItem> messages
    ) {
    }

    public record PhotoPolicy(
            int clockSkewWarningSeconds
    ) {
    }

    public record AndroidVersionPolicy(
            int minimumVersionCode,
            String latestVersionName,
            String downloadUrl,
            String releaseNotes
    ) {
    }

    public record RegisterPhotoEvidenceRequest(
            @NotBlank @Pattern(regexp = "^(INSPECTION|MAINTENANCE)$") String workflowType,
            long taskId,
            long taskItemId,
            long originalAttachmentId,
            long watermarkedAttachmentId,
            @NotNull LocalDateTime capturedDeviceTime,
            @NotNull LocalDateTime serverReferenceTime,
            int deviceClockOffsetSeconds,
            @NotBlank @Size(max = 300) String faultLocationText,
            @NotBlank @Size(max = 1000) String watermarkText
    ) {
    }

    public record PhotoEvidence(
            long id,
            String workflowType,
            long taskId,
            long taskItemId,
            long originalAttachmentId,
            long watermarkedAttachmentId,
            LocalDateTime capturedDeviceTime,
            LocalDateTime receivedServerTime,
            int deviceClockOffsetSeconds,
            boolean clockSkewWarning,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal locationAccuracyMeters,
            String locationProvider,
            String addressText,
            String faultLocationText,
            String watermarkText,
            String originalSha256,
            String watermarkedSha256
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

    public record ApplicableInspectionScheme(
            long schemeId,
            long schemeVersionId,
            String schemeCode,
            String schemeName,
            String inspectionType,
            boolean backfillAllowed
    ) {
    }

    public record AssigneeOption(
            long userId,
            String username,
            String realName,
            String teamCode
    ) {
    }

    public record TeamOption(String teamCode, String teamName) {
    }

    public record EquipmentContext(
            EquipmentBase equipment,
            List<TaskLink> activeTasks,
            List<ApplicableInspectionScheme> inspectionSchemes,
            List<AssigneeOption> assignees,
            List<TeamOption> teams
    ) {
    }
}
