package com.leantpm.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class InspectionImportDtos {
    private InspectionImportDtos() {
    }

    public record ImportError(
            String sheet,
            int rowNumber,
            String column,
            String message
    ) {
    }

    public record ImportResult(
            String batchId,
            String status,
            int itemRows,
            int schemeRows,
            int relationRows,
            int newItems,
            int updatedItems,
            int newSchemes,
            int newSchemeVersions,
            List<ImportError> errors,
            LocalDateTime committedTime
    ) {
    }

    record ImportPayload(
            List<ItemInput> items,
            List<SchemeInput> schemes,
            List<SchemeItemInput> schemeItems,
            List<ApplicabilityInput> applicability
    ) {
    }

    record ItemInput(
            int rowNumber,
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
            List<String> resultOptions,
            boolean required,
            boolean photoRequired,
            boolean numericRequired,
            boolean skipAllowed,
            String abnormalSeverity,
            String abnormalAdvice,
            int standardMinutes,
            String safetyNotes,
            boolean enabled,
            String description
    ) {
    }

    record SchemeInput(
            int rowNumber,
            String schemeCode,
            String schemeName,
            String inspectionType,
            String cycleType,
            int cycleInterval,
            String weekDays,
            String monthDays,
            LocalTime scheduledTime,
            String shiftCode,
            String defaultAssigneeUsername,
            String defaultTeamCode,
            boolean reviewRequired,
            boolean backfillAllowed,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            boolean enabled,
            String description,
            String changeSummary
    ) {
    }

    record SchemeItemInput(
            int rowNumber,
            String schemeCode,
            String itemCode,
            int sortOrder,
            Boolean required,
            Boolean photoRequired,
            Boolean skipAllowed
    ) {
    }

    record ApplicabilityInput(
            int rowNumber,
            String schemeCode,
            String equipmentCode,
            String categoryCode
    ) {
    }

    record ImportCounts(
            int newItems,
            int updatedItems,
            int newSchemes,
            int newSchemeVersions
    ) {
    }

    record BatchRow(
            String batchId,
            String status,
            String payloadJson,
            String errorsJson,
            String resultJson,
            int itemRows,
            int schemeRows,
            int relationRows,
            LocalDateTime committedTime
    ) {
    }
}
