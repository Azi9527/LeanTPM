package com.leantpm.foundation.service;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.mapper.FoundationMapper;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class NumberRuleService {
    private static final String TIMEZONE_PARAMETER = "system.timezone";

    private final FoundationMapper mapper;
    private final ParameterService parameterService;
    private final ChangeLogService changeLogService;

    public NumberRuleService(
            FoundationMapper mapper,
            ParameterService parameterService,
            ChangeLogService changeLogService
    ) {
        this.mapper = mapper;
        this.parameterService = parameterService;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public List<FoundationDtos.NumberRuleRow> list(String keyword) {
        var current = SecurityUtils.currentUser();
        LocalDate today = today(current.tenantId());
        return mapper.findNumberRules(current.tenantId(), clean(keyword)).stream()
                .map(rule -> rule.withPreview(format(rule, today, 1)))
                .toList();
    }

    @Transactional
    public long create(FoundationDtos.SaveNumberRuleRequest request) {
        var current = SecurityUtils.currentUser();
        FoundationDtos.SaveNumberRuleRequest normalized = normalize(request);
        validatePattern(normalized.datePattern());
        if (mapper.countNumberRuleCode(current.tenantId(), normalized.ruleCode()) > 0) {
            throw new BusinessException("NUMBER_RULE_CODE_EXISTS", "编号规则编码已存在", HttpStatus.CONFLICT);
        }
        mapper.insertNumberRule(current.tenantId(), normalized, current.userId());
        long id = mapper.findNumberRuleIdByCode(current.tenantId(), normalized.ruleCode());
        changeLogService.record(
                "NUMBER_RULE",
                id,
                "CREATE",
                null,
                mapper.findNumberRuleById(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void update(long id, FoundationDtos.SaveNumberRuleRequest request) {
        var current = SecurityUtils.currentUser();
        FoundationDtos.NumberRuleRow existing = mapper.findNumberRuleById(current.tenantId(), id);
        if (existing == null) {
            throw new BusinessException("NUMBER_RULE_NOT_FOUND", "编号规则不存在", HttpStatus.NOT_FOUND);
        }
        if (!existing.ruleCode().equals(request.ruleCode().trim().toUpperCase())) {
            throw new BusinessException("NUMBER_RULE_CODE_IMMUTABLE", "编号规则编码创建后不可修改");
        }
        FoundationDtos.SaveNumberRuleRequest normalized = normalize(request);
        validatePattern(normalized.datePattern());
        if (normalized.version() == null
                || mapper.updateNumberRule(current.tenantId(), id, normalized, current.userId()) == 0) {
            throw new BusinessException(
                    "OPTIMISTIC_LOCK_CONFLICT",
                    "数据不存在或已被其他用户修改，请刷新后重试",
                    HttpStatus.CONFLICT
            );
        }
        changeLogService.record(
                "NUMBER_RULE",
                id,
                "UPDATE",
                existing,
                mapper.findNumberRuleById(current.tenantId(), id)
        );
    }

    @Transactional
    public FoundationDtos.GeneratedNumber generate(String ruleCode) {
        var current = SecurityUtils.currentUser();
        return generate(current.tenantId(), current.userId(), ruleCode);
    }

    @Transactional
    public FoundationDtos.GeneratedNumber generate(long tenantId, long operatorId, String ruleCode) {
        String normalizedCode = ruleCode.trim().toUpperCase();
        FoundationDtos.NumberRuleRow rule = mapper.findNumberRuleByCode(tenantId, normalizedCode);
        if (rule == null) {
            throw new BusinessException("NUMBER_RULE_NOT_FOUND", "编号规则不存在", HttpStatus.NOT_FOUND);
        }
        if (rule.status() != 1) {
            throw new BusinessException("NUMBER_RULE_DISABLED", "编号规则已停用");
        }
        LocalDate today = today(tenantId);
        String periodKey = periodKey(rule.resetPeriod(), today);
        mapper.advanceSequence(tenantId, rule.id(), periodKey, operatorId);
        long sequence = mapper.findCurrentSequence(tenantId, rule.id(), periodKey);
        return new FoundationDtos.GeneratedNumber(rule.ruleCode(), format(rule, today, sequence), sequence);
    }

    String format(FoundationDtos.NumberRuleRow rule, LocalDate date, long sequence) {
        List<String> segments = new ArrayList<>();
        if (rule.prefix() != null && !rule.prefix().isBlank()) {
            segments.add(rule.prefix());
        }
        if (rule.datePattern() != null && !rule.datePattern().isBlank()) {
            segments.add(date.format(DateTimeFormatter.ofPattern(rule.datePattern())));
        }
        segments.add(String.format("%0" + rule.sequenceLength() + "d", sequence));
        return String.join(rule.separatorValue() == null ? "" : rule.separatorValue(), segments);
    }

    private String periodKey(String resetPeriod, LocalDate date) {
        return switch (resetPeriod) {
            case "DAILY" -> date.format(DateTimeFormatter.BASIC_ISO_DATE);
            case "MONTHLY" -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "YEARLY" -> Integer.toString(date.getYear());
            case "NEVER" -> "ALL";
            default -> throw new BusinessException("NUMBER_RULE_RESET_INVALID", "编号规则重置周期无效");
        };
    }

    private LocalDate today(long tenantId) {
        String timezone = parameterService.getString(tenantId, TIMEZONE_PARAMETER, "Asia/Shanghai");
        try {
            return LocalDate.now(ZoneId.of(timezone));
        } catch (Exception exception) {
            throw new BusinessException("SYSTEM_TIMEZONE_INVALID", "系统时区参数配置无效");
        }
    }

    private FoundationDtos.SaveNumberRuleRequest normalize(FoundationDtos.SaveNumberRuleRequest request) {
        return new FoundationDtos.SaveNumberRuleRequest(
                request.ruleCode().trim().toUpperCase(),
                request.ruleName().trim(),
                request.prefix() == null ? "" : request.prefix().trim().toUpperCase(),
                request.datePattern() == null ? "" : request.datePattern().trim(),
                request.separatorValue() == null ? "" : request.separatorValue(),
                request.sequenceLength(),
                request.resetPeriod().trim().toUpperCase(),
                request.enabled(),
                clean(request.description()),
                request.version()
        );
    }

    private void validatePattern(String pattern) {
        try {
            if (!pattern.isBlank()) {
                LocalDate.of(2026, 7, 30).format(DateTimeFormatter.ofPattern(pattern));
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("NUMBER_RULE_PATTERN_INVALID", "日期格式不合法");
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
