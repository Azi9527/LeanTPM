package com.leantpm.masterdata;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class MasterDataDtos {
    private MasterDataDtos() {
    }

    public record OrganizationRow(
            long id,
            long parentId,
            String organizationCode,
            String organizationName,
            String organizationType,
            Long managerUserId,
            String managerName,
            Integer sortOrder,
            Integer status,
            String description,
            Integer version
    ) {
    }

    public record SaveOrganizationRequest(
            @NotNull @Min(0) Long parentId,
            @NotBlank @Size(max = 64)
            @Pattern(
                    regexp = "^[A-Z][A-Z0-9_-]*$",
                    message = "必须为大写字母开头的编码"
            )
            String organizationCode,
            @NotBlank @Size(max = 100) String organizationName,
            @NotBlank
            @Pattern(
                    regexp = "^(ENTERPRISE|FACTORY|DEPARTMENT|WORKSHOP|LINE|SECTION|TEAM)$",
                    message = "组织类型不正确"
            )
            String organizationType,
            Long managerUserId,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String description,
            Integer version
    ) {
    }

    public record OrganizationDeleteImpact(
            int childOrganizations,
            int users,
            int locations,
            int equipment,
            int teamMemberships,
            int dataScopes,
            int businessRecords,
            int visualizationRecords,
            int totalReferences
    ) {
    }

    public record LocationRow(
            long id,
            long parentId,
            String locationCode,
            String locationName,
            String locationType,
            long organizationId,
            String organizationName,
            Long managerUserId,
            String managerName,
            Integer sortOrder,
            Integer status,
            String description,
            Integer version
    ) {
    }

    public record SaveLocationRequest(
            @NotNull @Min(0) Long parentId,
            @NotBlank @Size(max = 64)
            @Pattern(
                    regexp = "^[A-Z][A-Z0-9_-]*$",
                    message = "必须为大写字母开头的编码"
            )
            String locationCode,
            @NotBlank @Size(max = 100) String locationName,
            @NotBlank
            @Pattern(
                    regexp = "^(AREA|BUILDING|FLOOR|ZONE|SPOT)$",
                    message = "位置类型不正确"
            )
            String locationType,
            @NotNull @Min(1) Long organizationId,
            Long managerUserId,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String description,
            Integer version
    ) {
    }

    public record EquipmentCategoryRow(
            long id,
            long parentId,
            String categoryCode,
            String categoryName,
            Integer treeLevel,
            Long defaultInspectionTemplateId,
            Long defaultMaintenanceTemplateId,
            Long defaultFaultTypeId,
            String defaultOeeMode,
            Integer sortOrder,
            Integer status,
            String description,
            Integer version
    ) {
    }

    public record SaveEquipmentCategoryRequest(
            @NotNull @Min(0) Long parentId,
            @NotBlank @Size(max = 64)
            @Pattern(
                    regexp = "^[A-Z][A-Z0-9_-]*$",
                    message = "必须为大写字母开头的编码"
            )
            String categoryCode,
            @NotBlank @Size(max = 100) String categoryName,
            Long defaultInspectionTemplateId,
            Long defaultMaintenanceTemplateId,
            Long defaultFaultTypeId,
            @Size(max = 32) String defaultOeeMode,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String description,
            Integer version
    ) {
    }

    public record AttributeDefinitionRow(
            long id,
            long categoryId,
            String categoryName,
            String attributeCode,
            String attributeName,
            String dataType,
            String unit,
            Boolean requiredFlag,
            String defaultValue,
            String validationPattern,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            String enumOptionsJson,
            Integer sortOrder,
            Integer status,
            String description,
            Integer version,
            Boolean inherited
    ) {
    }

    public record SaveAttributeDefinitionRequest(
            @NotBlank @Size(max = 64)
            @Pattern(
                    regexp = "^[A-Z][A-Z0-9_]*$",
                    message = "必须为大写字母开头的编码"
            )
            String attributeCode,
            @NotBlank @Size(max = 100) String attributeName,
            @NotBlank
            @Pattern(
                    regexp = "^(STRING|INTEGER|DECIMAL|BOOLEAN|DATE|ENUM)$",
                    message = "属性数据类型不正确"
            )
            String dataType,
            @Size(max = 32) String unit,
            @NotNull Boolean required,
            @Size(max = 500) String defaultValue,
            @Size(max = 500) String validationPattern,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            List<@NotBlank @Size(max = 100) String> enumOptions,
            Integer sortOrder,
            @NotNull Boolean enabled,
            @Size(max = 500) String description,
            Integer version
    ) {
    }

    public record ReferenceUser(
            long id,
            String username,
            String realName,
            Long organizationId,
            String organizationName
    ) {
    }
}
