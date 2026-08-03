package com.leantpm.inspection;

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

public final class InspectionDtos {
    private InspectionDtos() {
    }

    public record ItemRow(
            long id,
            String itemCode,
            String itemName,
            String itemCategory,
            String inspectionPart,
            String inspectionContent,
            String inspectionMethod,
            String inspectionTool,
            String inspectionStandard,
            String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Integer photoMinCount,
            Integer photoMaxCount,
            Integer photoMaxSizeMb,
            String photoAllowedTypes,
            Integer photoCompressionQuality,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Boolean abnormalDefaultStopFlag,
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
            @Size(max = 150) String inspectionPart,
            @NotBlank @Size(max = 500) String inspectionContent,
            @Size(max = 500) String inspectionMethod,
            @Size(max = 200) String inspectionTool,
            @NotBlank @Size(max = 500) String inspectionStandard,
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
            @NotNull @Min(0) Integer photoMinCount,
            @NotNull @Min(1) Integer photoMaxCount,
            @NotNull @Min(1) Integer photoMaxSizeMb,
            @NotBlank @Size(max = 200) String photoAllowedTypes,
            @NotNull @Min(40) @jakarta.validation.constraints.Max(95) Integer photoCompressionQuality,
            @NotNull Boolean numericRequired,
            @NotNull Boolean skipAllowed,
            @NotBlank
            @Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$", message = "异常等级不正确")
            String abnormalSeverity,
            @Size(max = 500) String abnormalAdvice,
            @NotNull Boolean abnormalDefaultStop,
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
            String inspectionType,
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
            String weekDays,
            String monthDays,
            LocalTime scheduledTime,
            Integer generationLeadMinutes,
            Long workCalendarId,
            String shiftCode,
            Long defaultAssigneeUserId,
            String defaultAssigneeName,
            String defaultTeamCode,
            Boolean reviewRequiredFlag,
            Boolean backfillAllowedFlag,
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
            long inspectionItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String resultType,
            String unit,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Boolean skipAllowedFlag,
            Boolean abnormalStopFlag,
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
            @NotNull @Min(1) Long inspectionItemId,
            @NotNull @Min(0) Integer sortOrder,
            Boolean required,
            Boolean photoRequired,
            Boolean skipAllowed,
            Boolean abnormalStop
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
            @NotBlank @Size(max = 32) String inspectionType,
            @NotBlank
            @Pattern(
                    regexp = "^(DAILY|WEEKLY|MONTHLY|INTERVAL_DAYS)$",
                    message = "点检周期类型不正确"
            )
            String cycleType,
            @NotNull @Min(1) Integer cycleInterval,
            @Size(max = 32) String weekDays,
            @Size(max = 64) String monthDays,
            LocalTime scheduledTime,
            @NotNull @Min(0) Integer generationLeadMinutes,
            Long workCalendarId,
            @Size(max = 32) String shiftCode,
            Long defaultAssigneeUserId,
            @Size(max = 64) String defaultTeamCode,
            @NotNull Boolean reviewRequired,
            @NotNull Boolean backfillAllowed,
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
            Integer generationLeadMinutes,
            Long workCalendarId,
            Long assigneeUserId,
            String assigneeName,
            LocalDate nextGenerationDate,
            LocalDate lastGenerationDate,
            String planStatus,
            String pausedReason,
            int version
    ) {
    }

    public record CreatePlansRequest(
            @NotNull @Min(1) Long schemeId,
            @NotEmpty @Size(max = 200) List<@NotNull @Min(1) Long> equipmentIds
    ) {
    }

    public record CreatePlansResult(
            int processedPlans,
            LocalDate nextGenerationDate
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

    public record ManualTaskRequest(
            @NotNull @Min(1) Long equipmentId,
            @NotNull @Min(1) Long schemeVersionId,
            @NotNull LocalDate plannedDate,
            LocalDateTime plannedStartTime,
            @NotNull LocalDateTime dueTime,
            @Size(max = 20) List<@Min(1) Long> assigneeUserIds,
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
            String inspectionType,
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
            String assigneeUserIdsCsv,
            String teamCode,
            String taskStatus,
            String sourceType,
            Boolean backfillFlag,
            Boolean reviewRequiredFlag,
            LocalDateTime startedTime,
            LocalDateTime submittedTime,
            LocalDateTime completedTime,
            String reviewerName,
            String reviewComment,
            String executionRemark,
            int itemCount,
            int completedItemCount,
            int abnormalItemCount,
            int version
    ) {
    }

    public record TaskQuery(
            String keyword,
            String taskStatus,
            LocalDate plannedDate,
            String timeField,
            LocalDate startDate,
            LocalDate endDate,
            Long organizationId,
            String teamCode,
            Long assigneeUserId,
            Long equipmentId,
            Long schemeId,
            boolean abnormalOnly,
            String abnormalSeverity,
            boolean mineOnly
    ) {
    }

    public record TaskResultExportRow(
            String taskCode,
            LocalDate plannedDate,
            LocalDateTime dueTime,
            LocalDateTime completedTime,
            String taskStatus,
            String equipmentCode,
            String equipmentName,
            String organizationName,
            String locationName,
            String teamCode,
            String assigneeName,
            String schemeName,
            String itemCode,
            String itemName,
            String inspectionPart,
            String inspectionStandard,
            String unit,
            String resultStatus,
            String resultCode,
            BigDecimal numericValue,
            String textValue,
            String selectedValue,
            String selectedValuesJson,
            Boolean abnormalFlag,
            String abnormalDescription,
            String executedByName,
            LocalDateTime executedTime
    ) {
    }

    public record TaskAbnormalExportRow(
            String abnormalCode,
            String taskCode,
            String equipmentCode,
            String equipmentName,
            String itemName,
            String abnormalTitle,
            String abnormalDescription,
            String severity,
            String abnormalStatus,
            String responsibleUserName,
            LocalDateTime dueTime,
            String temporaryAction,
            String finalResult,
            String closedByName,
            LocalDateTime closedTime,
            String verifiedByName,
            LocalDateTime verifiedTime,
            String verificationComment,
            LocalDateTime createdTime
    ) {
    }

    public record TaskAttachmentExportRow(
            String taskCode,
            String equipmentCode,
            String equipmentName,
            String itemName,
            Long attachmentId,
            String originalName,
            String contentType,
            String extension,
            Long fileSize,
            String attachmentType,
            LocalDateTime createdTime
    ) {
    }

    public record TaskItemRow(
            long id,
            long taskId,
            Long sourceItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String inspectionPart,
            String inspectionContent,
            String inspectionMethod,
            String inspectionTool,
            String inspectionStandard,
            String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Integer photoMinCount,
            Integer photoMaxCount,
            Integer photoMaxSizeMb,
            String photoAllowedTypes,
            Integer photoCompressionQuality,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Boolean abnormalDefaultStopFlag,
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
            Boolean equipmentStopRequired,
            String stopOverrideReason,
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

    public record InspectionAttachmentRow(
            long id,
            Long taskResultId,
            Long taskItemId,
            String itemName,
            String originalName,
            String contentType,
            String extension,
            long fileSize,
            String attachmentType,
            LocalDateTime createdTime
    ) {
    }

    public record TaskDetail(
            TaskRow task,
            List<TaskItemRow> items,
            List<TaskEventRow> events,
            List<AbnormalRow> abnormalities
    ) {
    }

    public record AssignTaskRequest(
            @NotEmpty @Size(max = 20) List<@Min(1) Long> assigneeUserIds,
            @Size(max = 64) String teamCode,
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
            Boolean equipmentStopRequired,
            @Size(max = 500) String stopOverrideReason,
            @NotNull Boolean skipped,
            @Size(max = 500) String skipReason,
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
            Boolean equipmentStopRequired,
            Boolean equipmentStatusChanged,
            Long repairOrderId,
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
                    regexp = "^$|^(IDLE|RUNNING|MAINTENANCE|INSPECTION|FAULT|REPAIR|STOPPED|OFFLINE)$",
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
