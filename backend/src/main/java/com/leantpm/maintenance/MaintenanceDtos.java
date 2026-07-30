package com.leantpm.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class MaintenanceDtos {
    private MaintenanceDtos() {
    }

    public record ItemRow(
            long id,
            String itemCode,
            String itemName,
            String itemCategory,
            String maintenancePart,
            String maintenanceContent,
            String maintenanceMethod,
            String maintenanceTool,
            String maintenanceStandard,
            String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Boolean attachmentRequiredFlag,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            Boolean stopRequiredFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Integer standardMinutes,
            String safetyNotes,
            Integer status,
            String description,
            Integer version
    ) {
    }

    public record SaveItemRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Z][A-Z0-9_-]*$", message = "项目编码格式不正确")
            String itemCode,
            @NotBlank @Size(max = 150) String itemName,
            @NotBlank @Size(max = 64) String itemCategory,
            @Size(max = 150) String maintenancePart,
            @NotBlank @Size(max = 500) String maintenanceContent,
            @Size(max = 500) String maintenanceMethod,
            @Size(max = 200) String maintenanceTool,
            @NotBlank @Size(max = 500) String maintenanceStandard,
            @Size(max = 200) String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            @Size(max = 32) String unit,
            @NotBlank
            @Pattern(
                    regexp = "^(NORMAL_ABNORMAL|PASS_FAIL|NUMBER|TEXT|SINGLE_CHOICE|MULTIPLE_CHOICE|IMAGE|ATTACHMENT)$",
                    message = "结果类型不正确"
            )
            String resultType,
            List<@NotBlank @Size(max = 100) String> resultOptions,
            @NotNull Boolean required,
            @NotNull Boolean photoRequired,
            @NotNull Boolean attachmentRequired,
            @NotNull Boolean numericRequired,
            @NotNull Boolean skipAllowed,
            @NotNull Boolean stopRequired,
            @NotBlank
            @Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$", message = "异常等级不正确")
            String abnormalSeverity,
            @Size(max = 500) String abnormalAdvice,
            @NotNull @Min(0) Integer standardMinutes,
            @Size(max = 1000) String safetyNotes,
            @NotNull Boolean enabled,
            @Size(max = 1000) String description,
            Integer version
    ) {
    }

    public record SchemeRow(
            long id,
            String schemeCode,
            String schemeName,
            String maintenanceType,
            Long currentVersionId,
            Integer currentVersionNumber,
            String currentVersionStatus,
            String cycleType,
            Integer cycleInterval,
            LocalTime scheduledTime,
            Integer itemCount,
            Integer applicableEquipmentCount,
            Integer activePlanCount,
            Integer status,
            String description,
            Integer version
    ) {
    }

    public record SchemeVersionRow(
            long id,
            long schemeId,
            int versionNumber,
            String versionStatus,
            String cycleType,
            int cycleInterval,
            BigDecimal triggerThreshold,
            String weekDays,
            String monthDays,
            LocalTime scheduledTime,
            int reminderDays,
            int generationLeadDays,
            String shiftCode,
            Long defaultAssigneeUserId,
            String defaultAssigneeName,
            String defaultTeamCode,
            Boolean reviewRequiredFlag,
            Boolean backfillAllowedFlag,
            Boolean stopRequiredFlag,
            String restoreStatusCode,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            Long publishedBy,
            String publishedByName,
            LocalDateTime publishedTime,
            String changeSummary,
            int version
    ) {
    }

    public record SchemeItemRow(
            long relationId,
            long maintenanceItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String resultType,
            String unit,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Boolean attachmentRequiredFlag,
            Boolean skipAllowedFlag,
            Boolean stopRequiredFlag,
            int sortOrder
    ) {
    }

    public record SchemeApplicability(
            List<Long> categoryIds,
            List<Long> equipmentIds
    ) {
    }

    public record SchemeDetail(
            SchemeRow scheme,
            SchemeVersionRow version,
            List<SchemeItemRow> items,
            SchemeApplicability applicability,
            List<SchemeVersionRow> versionHistory
    ) {
    }

    public record SaveSchemeItemRequest(
            @NotNull @Min(1) Long maintenanceItemId,
            @NotNull @Min(0) Integer sortOrder,
            Boolean required,
            Boolean photoRequired,
            Boolean attachmentRequired,
            Boolean skipAllowed,
            Boolean stopRequired
    ) {
    }

    public record SaveSchemeRequest(
            @Size(max = 64)
            @Pattern(
                    regexp = "^$|^[A-Z][A-Z0-9_-]*$",
                    message = "方案编码格式不正确"
            )
            String schemeCode,
            @NotBlank @Size(max = 150) String schemeName,
            @NotBlank @Size(max = 32) String maintenanceType,
            @NotBlank
            @Pattern(
                    regexp = "^(DAILY|WEEKLY|MONTHLY|QUARTERLY|HALF_YEARLY|YEARLY|RUNNING_HOURS|PRODUCTION_QUANTITY|MANUAL)$",
                    message = "维保周期类型不正确"
            )
            String cycleType,
            @NotNull @Min(1) Integer cycleInterval,
            BigDecimal triggerThreshold,
            @Size(max = 32) String weekDays,
            @Size(max = 64) String monthDays,
            LocalTime scheduledTime,
            @NotNull @Min(0) Integer reminderDays,
            @NotNull @Min(0) Integer generationLeadDays,
            @Size(max = 32) String shiftCode,
            Long defaultAssigneeUserId,
            @Size(max = 64) String defaultTeamCode,
            @NotNull Boolean reviewRequired,
            @NotNull Boolean backfillAllowed,
            @NotNull Boolean stopRequired,
            @Pattern(
                    regexp = "^$|^(IDLE|FAULT|STOPPED|OFFLINE)$",
                    message = "恢复设备状态不正确"
            )
            String restoreStatusCode,
            @NotNull LocalDate effectiveDate,
            LocalDate expiryDate,
            @NotEmpty List<@Valid SaveSchemeItemRequest> items,
            List<@Min(1) Long> categoryIds,
            List<@Min(1) Long> equipmentIds,
            @NotNull Boolean enabled,
            @Size(max = 1000) String description,
            @Size(max = 500) String changeSummary,
            Integer version
    ) {
    }

    public record PlanRow(
            long id,
            long schemeId,
            String schemeCode,
            String schemeName,
            int schemeVersionNumber,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            String locationName,
            String cycleType,
            int cycleInterval,
            LocalTime scheduledTime,
            Long assigneeUserId,
            String assigneeName,
            LocalDate nextGenerationDate,
            LocalDate lastGenerationDate,
            BigDecimal triggerThreshold,
            BigDecimal currentMeterValue,
            BigDecimal nextTriggerValue,
            LocalDateTime meterUpdatedTime,
            String planStatus,
            String pausedReason,
            int version
    ) {
    }

    public record UpdatePlanStatusRequest(
            @NotBlank
            @Pattern(regexp = "^(ACTIVE|PAUSED|CANCELLED)$", message = "计划状态不正确")
            String planStatus,
            @Size(max = 500) String reason,
            @NotNull Integer version
    ) {
    }

    public record UpdateMeterRequest(
            @NotNull BigDecimal currentValue,
            @NotNull Integer version
    ) {
    }

    public record ManualTaskRequest(
            @NotNull @Min(1) Long equipmentId,
            @NotNull @Min(1) Long schemeVersionId,
            @NotNull LocalDate plannedDate,
            LocalDateTime plannedStartTime,
            @NotNull LocalDateTime dueTime,
            Long assigneeUserId,
            @Size(max = 64) String teamCode,
            @NotNull Boolean backfill,
            @Size(max = 1000) String remark
    ) {
    }

    public record TaskRow(
            long id,
            String taskCode,
            Long planId,
            Long schemeId,
            Long schemeVersionId,
            String schemeCodeSnapshot,
            String schemeNameSnapshot,
            Integer schemeVersionNumber,
            String maintenanceType,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            long locationId,
            String locationName,
            LocalDate plannedDate,
            LocalDateTime plannedStartTime,
            LocalDateTime dueTime,
            Long assigneeUserId,
            String assigneeName,
            String teamCode,
            String taskStatus,
            String sourceType,
            Boolean backfillFlag,
            Boolean reviewRequiredFlag,
            Boolean stopRequiredFlag,
            String restoreStatusCode,
            String previousEquipmentStatus,
            LocalDateTime startedTime,
            LocalDateTime pausedTime,
            LocalDateTime submittedTime,
            LocalDateTime completedTime,
            LocalDateTime confirmedTime,
            long totalPausedSeconds,
            int effectiveWorkMinutes,
            String reviewerName,
            String reviewComment,
            String executionRemark,
            int itemCount,
            int completedItemCount,
            int abnormalItemCount,
            int collaboratorCount,
            BigDecimal materialCost,
            int version
    ) {
    }

    public record TaskItemRow(
            long id,
            long taskId,
            Long sourceItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String maintenancePart,
            String maintenanceContent,
            String maintenanceMethod,
            String maintenanceTool,
            String maintenanceStandard,
            String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Boolean attachmentRequiredFlag,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            Boolean stopRequiredFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Integer standardMinutes,
            String safetyNotes,
            Integer sortOrder,
            ResultRow result
    ) {
    }

    public record ResultRow(
            Long id,
            String resultStatus,
            String resultCode,
            BigDecimal numericValue,
            String textValue,
            String selectedValue,
            String selectedValuesJson,
            Boolean abnormalFlag,
            String abnormalDescription,
            Boolean skippedFlag,
            String skipReason,
            Long executedBy,
            String executedByName,
            LocalDateTime executedTime,
            LocalDateTime submittedTime,
            Integer version,
            List<Long> attachmentIds
    ) {
    }

    public record TaskEventRow(
            long id,
            String eventType,
            String fromStatus,
            String toStatus,
            String eventRemark,
            String operatorName,
            LocalDateTime eventTime
    ) {
    }

    public record TaskDetail(
            TaskRow task,
            List<TaskItemRow> items,
            List<TaskEventRow> events,
            List<AbnormalRow> abnormalities,
            List<CollaboratorRow> collaborators,
            List<PauseRow> pauses,
            List<MaterialUsageRow> materials
    ) {
    }

    public record AssignTaskRequest(
            @NotNull @Min(1) Long assigneeUserId,
            @Size(max = 64) String teamCode,
            @NotNull Integer version
    ) {
    }

    public record CollaboratorRequest(
            List<@Min(1) Long> userIds,
            @NotNull Integer version
    ) {
    }

    public record CollaboratorRow(
            long userId,
            String userName
    ) {
    }

    public record TaskActionRequest(
            @Size(max = 500) String remark,
            @NotNull Integer version
    ) {
    }

    public record PauseTaskRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull Integer version
    ) {
    }

    public record SaveResultRequest(
            @NotNull @Min(1) Long taskItemId,
            @Size(max = 32) String resultCode,
            BigDecimal numericValue,
            @Size(max = 2000) String textValue,
            @Size(max = 500) String selectedValue,
            List<@Size(max = 100) String> selectedValues,
            @NotNull Boolean abnormal,
            @Size(max = 1000) String abnormalDescription,
            @NotNull Boolean skipped,
            @Size(max = 500) String skipReason,
            List<@Min(1) Long> beforeAttachmentIds,
            List<@Min(1) Long> afterAttachmentIds,
            List<@Min(1) Long> attachmentIds,
            Integer version
    ) {
    }

    public record SaveTaskResultsRequest(
            @NotEmpty List<@Valid SaveResultRequest> results,
            @Size(max = 1000) String executionRemark,
            @NotNull Integer taskVersion
    ) {
    }

    public record ReviewTaskRequest(
            @NotNull Boolean approved,
            @Size(max = 500) String comment,
            @NotNull Integer version
    ) {
    }

    public record MaterialUsageRequest(
            Long id,
            @NotBlank @Size(max = 64) String materialCode,
            @NotBlank @Size(max = 150) String materialName,
            @Size(max = 150) String specification,
            @NotNull BigDecimal quantity,
            @NotBlank @Size(max = 32) String unit,
            BigDecimal unitCost,
            @Size(max = 64) String batchNumber,
            @Size(max = 500) String remark,
            Integer version
    ) {
    }

    public record MaterialUsageRow(
            long id,
            String materialCode,
            String materialName,
            String specification,
            BigDecimal quantity,
            String unit,
            BigDecimal unitCost,
            BigDecimal totalCost,
            String batchNumber,
            String remark,
            int version
    ) {
    }

    public record PauseRow(
            long id,
            String pauseReason,
            String pausedByName,
            LocalDateTime pausedTime,
            String resumedByName,
            LocalDateTime resumedTime,
            Long durationSeconds
    ) {
    }

    public record CloseTaskRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull Integer version
    ) {
    }

    public record AbnormalRow(
            long id,
            String abnormalCode,
            long taskId,
            String taskCode,
            Long taskResultId,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            Long taskItemId,
            String itemName,
            String abnormalTitle,
            String abnormalDescription,
            String severity,
            String abnormalStatus,
            Long responsibleUserId,
            String responsibleUserName,
            LocalDateTime dueTime,
            String temporaryAction,
            String finalResult,
            String requestedEquipmentStatus,
            String closedByName,
            LocalDateTime closedTime,
            String verifiedByName,
            LocalDateTime verifiedTime,
            String verificationComment,
            LocalDateTime createdTime,
            int version
    ) {
    }

    public record HandleAbnormalRequest(
            Long responsibleUserId,
            LocalDateTime dueTime,
            @Size(max = 2000) String temporaryAction,
            @Size(max = 2000) String finalResult,
            @Pattern(
                    regexp = "^$|^(IDLE|RUNNING|INSPECTION|MAINTENANCE|FAULT|REPAIR|STOPPED|OFFLINE)$",
                    message = "设备状态不正确"
            )
            String requestedEquipmentStatus,
            @NotBlank
            @Pattern(
                    regexp = "^(PROCESSING|PENDING_VERIFY)$",
                    message = "异常处理状态不正确"
            )
            String targetStatus,
            @NotNull Integer version
    ) {
    }

    public record VerifyAbnormalRequest(
            @NotNull Boolean passed,
            @NotBlank @Size(max = 1000) String comment,
            @NotNull Integer version
    ) {
    }

    public record GenerationResult(
            int consideredPlans,
            int generatedTasks,
            int skippedOccurrences,
            List<String> taskCodes
    ) {
    }

    public record Statistics(
            long dueToday,
            long completedToday,
            long pendingToday,
            long overdue,
            long abnormal,
            BigDecimal completionRate,
            BigDecimal onTimeRate
    ) {
    }
}
