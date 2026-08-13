package com.leantpm.equipment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class EquipmentDtos {
    private EquipmentDtos() {
    }

    public record EquipmentRow(
            long id,
            String equipmentCode,
            String equipmentName,
            long categoryId,
            String categoryCode,
            String categoryName,
            String model,
            String specification,
            String brand,
            String manufacturer,
            String factorySerialNumber,
            LocalDate productionDate,
            LocalDate commissioningDate,
            long organizationId,
            String organizationCode,
            String organizationName,
            Long locationId,
            String locationCode,
            String locationName,
            Long primaryResponsibleUserId,
            String primaryResponsibleUsername,
            String primaryResponsibleName,
            String assetNumber,
            String lifecycleStage,
            Boolean criticalFlag,
            Boolean specialFlag,
            Boolean oeeEnabled,
            Integer status,
            String description,
            String currentStatusCode,
            LocalDateTime statusSince,
            Long statusDurationSeconds,
            Integer currentStatusVersion,
            Long activeBarcodeId,
            String activeBarcodeToken,
            LocalDateTime createdTime,
            LocalDateTime updatedTime,
            Integer version
    ) {
    }

    public record AttributeValueRow(
            long definitionId,
            String attributeCode,
            String attributeName,
            String dataType,
            String unit,
            Boolean requiredFlag,
            String value
    ) {
    }

    public record SaveAttributeValueRequest(
            @NotNull @Min(1) Long definitionId,
            @Size(max = 1000) String value
    ) {
    }

    public record ResponsiblePersonRow(
            long id,
            long userId,
            String username,
            String realName,
            String responsibilityType,
            LocalDate startDate,
            LocalDate endDate,
            Integer status
    ) {
    }

    public record SaveResponsiblePersonRequest(
            @NotNull @Min(1) Long userId,
            @NotBlank
            @Pattern(
                    regexp = "^(PRIMARY|OPERATOR|INSPECTOR|MAINTAINER)$",
                    message = "responsibilityType is invalid"
            )
            String responsibilityType,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    public record SaveEquipmentRequest(
            @Size(max = 64)
            @Pattern(
                    regexp = "^$|^[\\p{L}\\p{N}][\\p{L}\\p{N}._#-]*$",
                    message = "设备编码只能包含中文、字母、数字及 . _ # -"
            )
            String equipmentCode,
            @NotBlank @Size(max = 150) String equipmentName,
            @NotNull @Min(1) Long categoryId,
            @Size(max = 100) String model,
            @Size(max = 200) String specification,
            @Size(max = 100) String brand,
            @Size(max = 150) String manufacturer,
            @Size(max = 100) String factorySerialNumber,
            LocalDate productionDate,
            LocalDate commissioningDate,
            @NotNull @Min(1) Long organizationId,
            @Min(1) Long locationId,
            Long primaryResponsibleUserId,
            @Size(max = 100) String assetNumber,
            @NotBlank
            @Pattern(
                    regexp = "^(PLANNING|INSTALLATION|COMMISSIONING|IN_SERVICE|IDLE|SEALED|SCRAPPED)$",
                    message = "lifecycleStage is invalid"
            )
            String lifecycleStage,
            @NotNull Boolean critical,
            @NotNull Boolean special,
            @NotNull Boolean oeeEnabled,
            @NotNull Boolean enabled,
            @Size(max = 1000) String description,
            List<@Valid SaveAttributeValueRequest> attributes,
            List<@Valid SaveResponsiblePersonRequest> responsiblePersons,
            Integer version
    ) {
    }

    public record EquipmentDetail(
            EquipmentRow equipment,
            List<AttributeValueRow> attributes,
            List<ResponsiblePersonRow> responsiblePersons,
            List<BarcodeRow> barcodes,
            List<StatusHistoryRow> statusHistory,
            List<TransferRow> transfers,
            List<DocumentRow> documents,
            List<com.leantpm.system.audit.ChangeLogDtos.ChangeLogRow> changeLogs
    ) {
    }

    public record TransferRequest(
            @NotNull @Min(1) Long organizationId,
            @Min(1) Long locationId,
            Long primaryResponsibleUserId,
            @NotBlank @Size(max = 500) String reason,
            @NotNull Integer version
    ) {
    }

    public record TransferRow(
            long id,
            Long fromOrganizationId,
            String fromOrganizationName,
            long toOrganizationId,
            String toOrganizationName,
            Long fromLocationId,
            String fromLocationName,
            Long toLocationId,
            String toLocationName,
            Long fromResponsibleUserId,
            String fromResponsibleName,
            Long toResponsibleUserId,
            String toResponsibleName,
            String transferReason,
            String transferredByName,
            LocalDateTime transferredTime
    ) {
    }

    public record ChangeStatusRequest(
            @NotBlank @Size(max = 32) String statusCode,
            @Size(max = 500) String reason,
            @Pattern(
                    regexp = "^(MANUAL|INSPECTION|MAINTENANCE|IOT|SYSTEM)$",
                    message = "sourceType is invalid"
            )
            String sourceType,
            @NotNull Integer version
    ) {
    }

    public record StatusHistoryRow(
            long id,
            String fromStatusCode,
            String toStatusCode,
            LocalDateTime startedTime,
            LocalDateTime endedTime,
            Long durationSeconds,
            String reason,
            String sourceType,
            String changedByName
    ) {
    }

    public record StatusSummaryRow(
            String statusCode,
            long equipmentCount
    ) {
    }

    public record BarcodeRow(
            long id,
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            String accessToken,
            String barcodeType,
            Boolean active,
            LocalDateTime generatedTime,
            LocalDateTime invalidatedTime,
            String invalidationReason
    ) {
    }

    public record GenerateBarcodeRequest(
            @Pattern(regexp = "^(QR|CODE128)$", message = "barcodeType is invalid")
            String barcodeType,
            @Size(max = 500) String reason
    ) {
    }

    public record BulkBarcodeResult(
            int equipmentCount,
            int generatedCount,
            int existingCount
    ) {
    }

    public record PublicEquipmentView(
            String accessToken,
            String equipmentCode,
            String equipmentName,
            String categoryName,
            String locationName,
            String currentStatusCode,
            LocalDateTime statusSince
    ) {
    }

    public record DocumentRow(
            long attachmentId,
            String originalName,
            String contentType,
            String relationType,
            String remark,
            LocalDateTime createdTime
    ) {
    }

    public record CopyEquipmentRequest(
            @Size(max = 64)
            @Pattern(
                    regexp = "^$|^[\\p{L}\\p{N}][\\p{L}\\p{N}._#-]*$",
                    message = "设备编码只能包含中文、字母、数字及 . _ # -"
            )
            String equipmentCode,
            @NotBlank @Size(max = 150) String equipmentName
    ) {
    }

    public record ImportError(
            int rowNumber,
            String field,
            String originalValue,
            String message
    ) {
        public ImportError(int rowNumber, String field, String message) {
            this(rowNumber, field, null, message);
        }
    }

    public record ImportResult(
            int totalRows,
            int importedRows,
            List<ImportError> errors
    ) {
    }
}
