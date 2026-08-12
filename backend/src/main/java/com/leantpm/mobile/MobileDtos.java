package com.leantpm.mobile;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
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

    public record EquipmentStatusCount(
            long total,
            long running,
            long stopped,
            long fault,
            long offline,
            long idle,
            long scrapped
    ) {
    }

    public record AbnormalCount(
            long open,
            long critical,
            long high
    ) {
    }

    public record PersonalInspectionReport(
            LocalDate startDate,
            LocalDate endDate,
            long due,
            long completed,
            long pending,
            long overdue,
            long abnormal,
            long planDue,
            long planCompleted,
            long planOverdue,
            long registered,
            long quickRegistered,
            long equipmentCovered
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
            EquipmentStatusCount equipmentStatus,
            WorkCount inspection,
            AbnormalCount inspectionAbnormal,
            PersonalInspectionReport personalInspectionReport,
            WorkCount maintenance,
            List<MessageItem> messages
    ) {
    }

    public record PhotoPolicy(
            int clockSkewWarningSeconds,
            boolean allowAlbumSelection,
            boolean watermarkEnabled,
            boolean saveOriginal,
            boolean saveWatermarked,
            String template,
            String position,
            int backgroundOpacity,
            String fontColor,
            String backgroundColor
    ) {
    }

    public record AndroidVersionPolicy(
            int minimumVersionCode,
            String latestVersionName,
            int latestVersionCode,
            boolean forceUpgrade,
            String downloadUrl,
            String releaseNotes
    ) {
        public AndroidVersionPolicy {
            if (forceUpgrade) {
                minimumVersionCode = Math.max(minimumVersionCode, latestVersionCode);
            }
        }
    }

    public record EquipmentStatusRow(
            long id,
            String equipmentCode,
            String equipmentName,
            String organizationName,
            String locationName,
            String currentStatusCode,
            String primaryResponsibleName,
            String activeBarcodeToken
    ) {
    }

    public record RegisterPhotoEvidenceRequest(
            @NotBlank @Pattern(regexp = "^(INSPECTION|MAINTENANCE)$") String workflowType,
            long taskId,
            Long taskItemId,
            Long originalAttachmentId,
            Long watermarkedAttachmentId,
            @NotNull LocalDateTime capturedDeviceTime,
            @NotNull LocalDateTime serverReferenceTime,
            int deviceClockOffsetSeconds,
            @NotBlank @Size(max = 300) String faultLocationText,
            @Size(max = 1000) String watermarkText
    ) {
    }

    public record PhotoEvidence(
            long id,
            String workflowType,
            long taskId,
            Long taskItemId,
            Long originalAttachmentId,
            Long watermarkedAttachmentId,
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
            String model,
            String specification,
            String brand,
            String manufacturer,
            String factorySerialNumber,
            LocalDate productionDate,
            LocalDate commissioningDate,
            String organizationName,
            String locationName,
            String assetNumber,
            String lifecycleStage,
            Boolean criticalFlag,
            Boolean specialFlag,
            Boolean oeeEnabled,
            String description,
            String statusCode,
            String statusName,
            String statusColor,
            LocalDateTime statusSince,
            String responsibleName,
            LocalDateTime updatedTime
    ) {
    }

    public record EquipmentAccessProbe(
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            Long organizationId,
            String organizationName,
            Integer equipmentStatus,
            Integer organizationStatus,
            boolean equipmentDeleted,
            boolean organizationDeleted,
            boolean barcodeActive
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

    public record DirectInspectionReportRequest(
            @NotNull @Min(1) Long schemeVersionId,
            @Size(max = 500) String remark,
            Boolean allowRepeat
    ) {
    }

    public record TodayInspectionRecord(
            long taskId,
            String taskCode,
            long schemeVersionId,
            String schemeName,
            String taskStatus,
            String sourceType,
            String executorName,
            LocalDateTime submittedTime,
            LocalDateTime completedTime
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
            List<TodayInspectionRecord> todayInspections,
            List<AssigneeOption> assignees,
            List<TeamOption> teams
    ) {
    }
}
