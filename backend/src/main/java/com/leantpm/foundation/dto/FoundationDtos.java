package com.leantpm.foundation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class FoundationDtos {
    private FoundationDtos() {
    }

    public record ParameterRow(
            long id,
            String parameterKey,
            String parameterName,
            String parameterValue,
            String valueType,
            String groupCode,
            String description,
            Boolean builtIn,
            Integer status,
            LocalDateTime updatedTime,
            Integer version
    ) {
    }

    public record SaveParameterRequest(
            @NotBlank
            @Size(max = 128)
            @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$", message = "必须使用小写点分键名")
            String parameterKey,
            @NotBlank @Size(max = 100) String parameterName,
            @NotNull @Size(max = 700000) String parameterValue,
            @NotBlank
            @Pattern(regexp = "STRING|BOOLEAN|INTEGER|DECIMAL", message = "不支持的参数类型")
            String valueType,
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "必须使用大写字母、数字或下划线")
            String groupCode,
            @Size(max = 500) String description,
            @NotNull Boolean enabled,
            Integer version
    ) {
    }

    public record BrandingSettings(
            String systemName,
            String shortName,
            String subtitle,
            String logoUrl,
            String primaryColor,
            String secondaryColor,
            String neutralColor
    ) {
    }

    public record SaveBrandingRequest(
            @NotBlank @Size(max = 60) String systemName,
            @NotBlank @Size(max = 30) String shortName,
            @NotBlank @Size(max = 40) String subtitle,
            @NotBlank @Size(max = 700000) String logoUrl,
            @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String primaryColor,
            @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String secondaryColor,
            @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String neutralColor
    ) {
    }

    public record NumberRuleRow(
            long id,
            String ruleCode,
            String ruleName,
            String prefix,
            String datePattern,
            String separatorValue,
            Integer sequenceLength,
            String resetPeriod,
            Integer status,
            String description,
            LocalDateTime updatedTime,
            Integer version,
            String preview
    ) {
        public NumberRuleRow withPreview(String value) {
            return new NumberRuleRow(
                    id, ruleCode, ruleName, prefix, datePattern, separatorValue, sequenceLength,
                    resetPeriod, status, description, updatedTime, version, value
            );
        }
    }

    public record SaveNumberRuleRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "必须使用大写字母、数字或下划线")
            String ruleCode,
            @NotBlank @Size(max = 100) String ruleName,
            @Size(max = 30)
            @Pattern(regexp = "^[A-Za-z0-9]*$", message = "仅允许字母和数字")
            String prefix,
            @NotNull
            @Pattern(
                    regexp = "^$|yyyyMMdd|yyyyMM|yyyy|yyMMdd|yyMM",
                    message = "仅支持 yyyyMMdd、yyyyMM、yyyy、yyMMdd、yyMM 或空值"
            )
            String datePattern,
            @Size(max = 5) String separatorValue,
            @NotNull @Min(2) @Max(12) Integer sequenceLength,
            @NotBlank
            @Pattern(regexp = "DAILY|MONTHLY|YEARLY|NEVER", message = "不支持的重置周期")
            String resetPeriod,
            @NotNull Boolean enabled,
            @Size(max = 500) String description,
            Integer version
    ) {
    }

    public record GeneratedNumber(String ruleCode, String businessNumber, long sequence) {
    }
}
