package com.leantpm.foundation.service;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.mapper.FoundationMapper;
import com.leantpm.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ParameterService {
    private final FoundationMapper mapper;

    public ParameterService(FoundationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<FoundationDtos.ParameterRow> list(String keyword, String groupCode) {
        return mapper.findParameters(
                SecurityUtils.currentUser().tenantId(),
                clean(keyword),
                normalizeGroup(groupCode)
        );
    }

    @Transactional
    public long create(FoundationDtos.SaveParameterRequest request) {
        var current = SecurityUtils.currentUser();
        FoundationDtos.SaveParameterRequest normalized = normalize(request);
        validateValue(normalized.valueType(), normalized.parameterValue());
        if (mapper.countParameterKey(current.tenantId(), normalized.parameterKey()) > 0) {
            throw new BusinessException("PARAMETER_KEY_EXISTS", "参数键已存在", HttpStatus.CONFLICT);
        }
        mapper.insertParameter(current.tenantId(), normalized, current.userId());
        return mapper.findParameterIdByKey(current.tenantId(), normalized.parameterKey());
    }

    @Transactional
    public void update(long id, FoundationDtos.SaveParameterRequest request) {
        var current = SecurityUtils.currentUser();
        var existing = mapper.findParameterById(current.tenantId(), id);
        if (existing == null) {
            throw new BusinessException("PARAMETER_NOT_FOUND", "系统参数不存在", HttpStatus.NOT_FOUND);
        }
        if (!existing.parameterKey().equals(request.parameterKey().trim())) {
            throw new BusinessException("PARAMETER_KEY_IMMUTABLE", "参数键创建后不可修改");
        }
        FoundationDtos.SaveParameterRequest normalized = normalize(request);
        validateValue(normalized.valueType(), normalized.parameterValue());
        if (normalized.version() == null
                || mapper.updateParameter(current.tenantId(), id, normalized, current.userId()) == 0) {
            throw optimisticConflict();
        }
    }

    @Transactional
    public void delete(long id) {
        var current = SecurityUtils.currentUser();
        var existing = mapper.findParameterById(current.tenantId(), id);
        if (existing == null) {
            throw new BusinessException("PARAMETER_NOT_FOUND", "系统参数不存在", HttpStatus.NOT_FOUND);
        }
        if (Boolean.TRUE.equals(existing.builtIn())) {
            throw new BusinessException("BUILT_IN_PARAMETER", "内置参数不能删除");
        }
        if (mapper.deleteParameter(current.tenantId(), id, current.userId()) == 0) {
            throw new BusinessException("PARAMETER_NOT_FOUND", "系统参数不存在", HttpStatus.NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public String getString(long tenantId, String key, String defaultValue) {
        var parameter = mapper.findParameterByKey(tenantId, key);
        return parameter == null || parameter.status() != 1 ? defaultValue : parameter.parameterValue();
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(long tenantId, String key, boolean defaultValue) {
        String value = getString(tenantId, key, Boolean.toString(defaultValue));
        return Boolean.parseBoolean(value);
    }

    private FoundationDtos.SaveParameterRequest normalize(FoundationDtos.SaveParameterRequest request) {
        return new FoundationDtos.SaveParameterRequest(
                request.parameterKey().trim(),
                request.parameterName().trim(),
                request.parameterValue().trim(),
                request.valueType().trim().toUpperCase(),
                normalizeGroup(request.groupCode()),
                clean(request.description()),
                request.enabled(),
                request.version()
        );
    }

    private void validateValue(String valueType, String value) {
        try {
            switch (valueType) {
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException();
                    }
                }
                case "INTEGER" -> Long.parseLong(value);
                case "DECIMAL" -> new BigDecimal(value);
                case "STRING" -> {
                    return;
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("PARAMETER_VALUE_INVALID", "参数值与所选类型不匹配");
        }
    }

    private String normalizeGroup(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT",
                "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }
}
