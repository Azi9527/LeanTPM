package com.leantpm.oee;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class OeeDtos {
    private OeeDtos() {
    }

    public record ShiftRow(
            long id,
            String shiftCode,
            String shiftName,
            LocalTime startTime,
            LocalTime endTime,
            boolean crossDayFlag,
            int breakMinutes,
            int standardWorkMinutes,
            int sortOrder,
            int status,
            String description,
            int version
    ) {
    }

    public record SaveShiftRequest(
            @NotBlank @Size(max = 64) String shiftCode,
            @NotBlank @Size(max = 100) String shiftName,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull Boolean crossDayFlag,
            @NotNull @Min(0) @Max(1440) Integer breakMinutes,
            @NotNull @Min(1) @Max(1440) Integer standardWorkMinutes,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record CalendarRow(
            long id,
            long organizationId,
            String organizationName,
            LocalDate workDate,
            long shiftId,
            String shiftName,
            String dayType,
            int plannedWorkMinutes,
            int plannedDowntimeMinutes,
            String calendarStatus,
            String remark,
            int version
    ) {
    }

    public record SaveCalendarRequest(
            @NotNull @Min(1) Long organizationId,
            @NotNull LocalDate workDate,
            @NotNull @Min(1) Long shiftId,
            @NotBlank @Pattern(regexp = "WORKDAY|HOLIDAY|OVERTIME") String dayType,
            @NotNull @Min(0) @Max(1440) Integer plannedWorkMinutes,
            @NotNull @Min(0) @Max(1440) Integer plannedDowntimeMinutes,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String calendarStatus,
            @Size(max = 500) String remark,
            @Min(0) Integer version
    ) {
    }

    public record TargetRow(
            long id,
            String targetName,
            String targetLevel,
            Long organizationId,
            String organizationName,
            Long equipmentId,
            String equipmentCode,
            String equipmentName,
            BigDecimal availabilityTarget,
            BigDecimal performanceTarget,
            BigDecimal qualityTarget,
            BigDecimal oeeTarget,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            int status,
            String description,
            int version
    ) {
    }

    public record SaveTargetRequest(
            @NotBlank @Size(max = 100) String targetName,
            @NotBlank
            @Pattern(regexp = "ENTERPRISE|FACTORY|WORKSHOP|LINE|EQUIPMENT")
            String targetLevel,
            @Min(1) Long organizationId,
            @Min(1) Long equipmentId,
            @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal availabilityTarget,
            @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal performanceTarget,
            @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 6)
            BigDecimal qualityTarget,
            @NotNull LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record LossReasonRow(
            long id,
            long parentId,
            String reasonCode,
            String reasonName,
            String lossCategory,
            String affectsMetric,
            boolean plannedFlag,
            String color,
            int sortOrder,
            int status,
            String description,
            int referenceCount,
            int version
    ) {
    }

    public record SaveLossReasonRequest(
            @NotNull @Min(0) Long parentId,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 100) String reasonName,
            @NotBlank
            @Pattern(regexp = "BREAKDOWN|SETUP_ADJUSTMENT|MINOR_STOPPAGE|REDUCED_SPEED|PROCESS_DEFECT|STARTUP_REJECT|PLANNED_STOP|OTHER")
            String lossCategory,
            @NotBlank
            @Pattern(regexp = "AVAILABILITY|PERFORMANCE|QUALITY|EXCLUDED")
            String affectsMetric,
            @NotNull Boolean plannedFlag,
            @Size(max = 20) String color,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record OutputRow(
            long id,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            LocalDate productionDate,
            long shiftId,
            String shiftName,
            BigDecimal plannedQuantity,
            BigDecimal actualQuantity,
            BigDecimal goodQuantity,
            BigDecimal defectiveQuantity,
            String sourceType,
            String sourceReference,
            String remark,
            int version
    ) {
    }

    public record SaveOutputRequest(
            @NotNull @Min(1) Long equipmentId,
            @NotNull LocalDate productionDate,
            @NotNull @Min(1) Long shiftId,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal plannedQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal actualQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal goodQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal defectiveQuantity,
            @NotBlank @Pattern(regexp = "MANUAL|EXCEL|MES|IOT") String sourceType,
            @Size(max = 200) String sourceReference,
            @Size(max = 500) String remark,
            @Min(0) Integer version
    ) {
    }

    public record DowntimeRow(
            long id,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            LocalDate productionDate,
            long shiftId,
            String shiftName,
            long lossReasonId,
            String reasonCode,
            String reasonName,
            String lossCategory,
            String affectsMetric,
            LocalDateTime startedTime,
            LocalDateTime endedTime,
            BigDecimal durationMinutes,
            boolean plannedFlag,
            String sourceType,
            String sourceReference,
            String description,
            int version
    ) {
    }

    public record SaveDowntimeRequest(
            @NotNull @Min(1) Long equipmentId,
            @NotNull LocalDate productionDate,
            @NotNull @Min(1) Long shiftId,
            @NotNull @Min(1) Long lossReasonId,
            LocalDateTime startedTime,
            LocalDateTime endedTime,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 9, fraction = 3)
            BigDecimal durationMinutes,
            @NotNull Boolean plannedFlag,
            @NotBlank @Pattern(regexp = "MANUAL|EXCEL|MES|IOT|STATUS") String sourceType,
            @Size(max = 200) String sourceReference,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record OeeRecordRow(
            long id,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            LocalDate productionDate,
            long shiftId,
            String shiftName,
            BigDecimal standardCycleSeconds,
            BigDecimal plannedWorkMinutes,
            BigDecimal plannedDowntimeMinutes,
            BigDecimal loadingTimeMinutes,
            BigDecimal unplannedDowntimeMinutes,
            BigDecimal runTimeMinutes,
            BigDecimal plannedQuantity,
            BigDecimal actualQuantity,
            BigDecimal goodQuantity,
            BigDecimal defectiveQuantity,
            BigDecimal availabilityRate,
            BigDecimal performanceRate,
            BigDecimal qualityRate,
            BigDecimal oeeRate,
            BigDecimal targetOeeRate,
            String dataStatus,
            boolean anomalyFlag,
            String anomalyMessage,
            String sourceType,
            LocalDateTime calculatedTime,
            String approvedByName,
            LocalDateTime approvedTime,
            String lockedByName,
            LocalDateTime lockedTime,
            int version
    ) {
    }

    public record SaveOeeRecordRequest(
            @NotNull @Min(1) Long equipmentId,
            @NotNull LocalDate productionDate,
            @NotNull @Min(1) Long shiftId,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 10, fraction = 6)
            BigDecimal standardCycleSeconds,
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3)
            BigDecimal plannedWorkMinutes,
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3)
            BigDecimal plannedDowntimeMinutes,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal plannedQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal actualQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal goodQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 6)
            BigDecimal defectiveQuantity,
            @NotBlank @Pattern(regexp = "MANUAL|EXCEL|MES|IOT") String sourceType,
            @Min(0) Integer version
    ) {
    }

    public record WorkflowRequest(
            @NotBlank
            @Pattern(regexp = "SUBMIT|APPROVE|LOCK|UNLOCK")
            String action,
            @NotNull @Min(0) Integer version,
            @Size(max = 500) String comment
    ) {
    }

    public record CalculationLogRow(
            long id,
            long oeeRecordId,
            int calculationVersion,
            String triggerType,
            String formulaVersion,
            String inputSnapshot,
            String outputSnapshot,
            String validationMessage,
            String calculatedByName,
            LocalDateTime calculatedTime
    ) {
    }

    public record OeeCalculation(
            BigDecimal loadingTimeMinutes,
            BigDecimal unplannedDowntimeMinutes,
            BigDecimal runTimeMinutes,
            BigDecimal availabilityRate,
            BigDecimal performanceRate,
            BigDecimal qualityRate,
            BigDecimal oeeRate,
            BigDecimal targetOeeRate,
            boolean anomalyFlag,
            String anomalyMessage
    ) {
    }

    public record ImportResult(
            int totalRows,
            int successRows,
            int failureRows,
            List<String> errors
    ) {
    }

    public record AnalysisSummary(
            BigDecimal availabilityRate,
            BigDecimal performanceRate,
            BigDecimal qualityRate,
            BigDecimal oeeRate,
            BigDecimal targetOeeRate,
            long recordCount,
            long belowTargetCount,
            BigDecimal plannedWorkMinutes,
            BigDecimal runTimeMinutes,
            BigDecimal actualQuantity,
            BigDecimal goodQuantity
    ) {
    }

    public record TrendPoint(
            String period,
            BigDecimal availabilityRate,
            BigDecimal performanceRate,
            BigDecimal qualityRate,
            BigDecimal oeeRate,
            BigDecimal targetOeeRate,
            long recordCount
    ) {
    }

    public record RankingRow(
            long scopeId,
            String scopeCode,
            String scopeName,
            String scopeType,
            BigDecimal availabilityRate,
            BigDecimal performanceRate,
            BigDecimal qualityRate,
            BigDecimal oeeRate,
            BigDecimal targetOeeRate,
            long recordCount
    ) {
    }

    public record LossAnalysisRow(
            long lossReasonId,
            String reasonCode,
            String reasonName,
            String lossCategory,
            String affectsMetric,
            BigDecimal durationMinutes,
            BigDecimal proportion,
            long occurrenceCount
    ) {
    }

    public record AnalysisResult(
            AnalysisSummary summary,
            List<TrendPoint> trend,
            List<RankingRow> ranking,
            List<LossAnalysisRow> losses,
            List<OeeRecordRow> records
    ) {
    }

    public record EquipmentRef(
            long id,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            boolean oeeEnabled,
            int status
    ) {
    }
}
