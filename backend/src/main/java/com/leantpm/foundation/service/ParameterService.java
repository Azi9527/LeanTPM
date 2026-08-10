package com.leantpm.foundation.service;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.mapper.FoundationMapper;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ParameterService {
    private static final String BRANDING_LOGO_KEY = "branding.logo-url";
    private static final String BARCODE_CENTER_LOGO_KEY = "equipment.barcode.center-logo-url";
    private static final Set<String> LARGE_IMAGE_VALUE_KEYS = Set.of(
            BRANDING_LOGO_KEY,
            BARCODE_CENTER_LOGO_KEY
    );
    private static final Set<String> BRANDING_COLOR_KEYS = Set.of(
            "branding.primary-color",
            "branding.secondary-color",
            "branding.neutral-color"
    );
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern IMAGE_DATA_URL = Pattern.compile(
            "^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$"
    );
    private static final Pattern BARCODE_IMAGE_DATA_URL = Pattern.compile(
            "^data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+$"
    );
    private static final int STANDARD_VALUE_MAX_LENGTH = 2000;
    private static final int LOGO_VALUE_MAX_LENGTH = 700000;
    private final FoundationMapper mapper;
    private final ChangeLogService changeLogService;

    public ParameterService(FoundationMapper mapper, ChangeLogService changeLogService) {
        this.mapper = mapper;
        this.changeLogService = changeLogService;
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
        validateLengthAndBranding(normalized.parameterKey(), normalized.parameterValue());
        validateValue(normalized.valueType(), normalized.parameterValue());
        if (mapper.countParameterKey(current.tenantId(), normalized.parameterKey()) > 0) {
            throw new BusinessException("PARAMETER_KEY_EXISTS", "参数键已存在", HttpStatus.CONFLICT);
        }
        mapper.insertParameter(current.tenantId(), normalized, current.userId());
        long id = mapper.findParameterIdByKey(current.tenantId(), normalized.parameterKey());
        changeLogService.record(
                "SYSTEM_PARAMETER",
                id,
                "CREATE",
                null,
                mapper.findParameterById(current.tenantId(), id)
        );
        return id;
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
        validateLengthAndBranding(normalized.parameterKey(), normalized.parameterValue());
        validateValue(normalized.valueType(), normalized.parameterValue());
        if (normalized.version() == null
                || mapper.updateParameter(current.tenantId(), id, normalized, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "SYSTEM_PARAMETER",
                id,
                "UPDATE",
                existing,
                mapper.findParameterById(current.tenantId(), id)
        );
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
        changeLogService.record("SYSTEM_PARAMETER", id, "DELETE", existing, null);
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

    private void validateLengthAndBranding(String key, String value) {
        int maxLength = LARGE_IMAGE_VALUE_KEYS.contains(key)
                ? LOGO_VALUE_MAX_LENGTH
                : STANDARD_VALUE_MAX_LENGTH;
        if (value.length() > maxLength) {
            throw new BusinessException(
                    "PARAMETER_VALUE_TOO_LONG",
                    LARGE_IMAGE_VALUE_KEYS.contains(key)
                            ? "图片不能超过 512KB"
                            : "参数值不能超过 2000 个字符"
            );
        }
        if (BRANDING_COLOR_KEYS.contains(key) && !HEX_COLOR.matcher(value).matches()) {
            throw new BusinessException("BRANDING_COLOR_INVALID", "品牌颜色必须使用 #RRGGBB 格式");
        }
        if (BRANDING_LOGO_KEY.equals(key)
                && !value.startsWith("/")
                && !IMAGE_DATA_URL.matcher(value).matches()) {
            throw new BusinessException(
                    "BRANDING_LOGO_INVALID",
                    "Logo 仅支持系统内路径或 PNG、JPEG、WebP 图片"
            );
        }
        if (BARCODE_CENTER_LOGO_KEY.equals(key)
                && !"DEFAULT".equals(value)
                && !BARCODE_IMAGE_DATA_URL.matcher(value).matches()) {
            throw new BusinessException(
                    "EQUIPMENT_BARCODE_LOGO_INVALID",
                    "设备二维码中心图标仅支持默认图标或 PNG、JPEG 图片"
            );
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
