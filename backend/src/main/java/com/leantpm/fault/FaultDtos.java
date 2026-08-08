package com.leantpm.fault;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class FaultDtos {
    private FaultDtos() {
    }

    public record ReportRow(
            long id, String reportCode, long equipmentId, String equipmentCode,
            String equipmentName, long organizationId, String organizationName,
            LocalDateTime faultTime, String faultTitle, String faultDescription,
            String severity, String sourceType, Long sourceBusinessId,
            long reporterUserId, String reporterName, String reportStatus,
            String rejectedReason, LocalDateTime createdTime, int version,
            Long repairOrderId, String repairCode
    ) {
    }

    public record CreateReportRequest(
            @NotNull Long equipmentId,
            @NotNull LocalDateTime faultTime,
            @NotBlank @Size(max = 200) String faultTitle,
            @NotBlank @Size(max = 2000) String faultDescription,
            @NotBlank @Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$") String severity,
            List<Long> attachmentIds
    ) {
    }

    public record RejectRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Integer version
    ) {
    }

    public record VersionRequest(@NotNull Integer version) {
    }

    public record RepairRow(
            long id, String repairCode, long faultReportId, String reportCode,
            long equipmentId, String equipmentCode, String equipmentName,
            long organizationId, String organizationName, String faultTitle,
            String severity, String repairStatus, Long primaryRepairerUserId,
            String primaryRepairerName, List<Long> collaboratorUserIds,
            LocalDateTime assignedTime, LocalDateTime startedTime,
            LocalDateTime pausedTime, LocalDateTime completedTime,
            LocalDateTime acceptedTime, long totalPausedSeconds,
            long effectiveWorkSeconds, String repairMeasure,
            String repairConclusion, String acceptanceResult,
            String acceptanceComment, String restoreStatusCode,
            LocalDateTime createdTime, int version
    ) {
    }

    public record CreateRepairRequest(
            Long primaryRepairerUserId,
            List<Long> collaboratorUserIds,
            @Pattern(regexp = "^(IDLE|RUNNING|STOPPED)$") String restoreStatusCode,
            @NotNull Integer reportVersion
    ) {
    }

    public record AssignmentRequest(
            @NotNull Long primaryRepairerUserId,
            List<Long> collaboratorUserIds,
            @NotNull Integer version
    ) {
    }

    public record ActionRequest(
            @Size(max = 1000) String remark,
            @NotNull Integer version
    ) {
    }

    public record CompleteRequest(
            @NotBlank @Size(max = 3000) String repairMeasure,
            @NotBlank @Size(max = 2000) String repairConclusion,
            List<Long> attachmentIds,
            @NotNull Integer version
    ) {
    }

    public record AcceptanceRequest(
            @NotNull Boolean passed,
            @NotBlank @Size(max = 1000) String comment,
            @Pattern(regexp = "^(IDLE|RUNNING|STOPPED)$") String restoreStatusCode,
            List<Long> attachmentIds,
            @NotNull Integer version
    ) {
    }

    public record MaterialRow(
            long id, String materialCode, String materialName, BigDecimal quantity,
            String unit, BigDecimal unitPrice, BigDecimal totalAmount,
            String remark, int version
    ) {
    }

    public record SaveMaterialRequest(
            @Size(max = 64) String materialCode,
            @NotBlank @Size(max = 150) String materialName,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @Size(max = 32) String unit,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @Size(max = 500) String remark,
            Integer version
    ) {
    }

    public record EventRow(
            long id, String eventType, String fromStatus, String toStatus,
            String eventRemark, String operatorName, LocalDateTime eventTime
    ) {
    }

    public record AttachmentRow(
            long attachmentId, String originalName, String contentType,
            String attachmentStage, LocalDateTime createdTime
    ) {
    }

    public record Statistics(
            long openReports, long activeRepairs, long pendingAcceptance,
            long closedRepairs, BigDecimal materialCost,
            BigDecimal averageRepairMinutes
    ) {
    }

    record EquipmentTarget(long id, long organizationId, String statusCode, int statusVersion) {
    }
}
