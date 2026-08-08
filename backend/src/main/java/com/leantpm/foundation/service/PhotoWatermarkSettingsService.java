package com.leantpm.foundation.service;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PhotoWatermarkSettingsService {
    public static final String DEFAULT_TEMPLATE = "{brand}\n{equipmentName} ({equipmentCode})\n{taskCode} · {itemName}\n位置/部位 {location}\n{capturedAt} · 执行人 {executor}";

    private final ParameterService parameterService;

    public PhotoWatermarkSettingsService(ParameterService parameterService) {
        this.parameterService = parameterService;
    }

    @Transactional(readOnly = true)
    public FoundationDtos.PhotoWatermarkSettings settings() {
        long tenantId = SecurityUtils.currentUser().tenantId();
        return new FoundationDtos.PhotoWatermarkSettings(
                parameterService.getBoolean(tenantId, "mobile.photo-watermark-enabled", true),
                parameterService.getBoolean(tenantId, "mobile.photo-save-original", true),
                parameterService.getBoolean(tenantId, "mobile.photo-save-watermarked", true),
                parameterService.getString(tenantId, "mobile.photo-watermark-template", DEFAULT_TEMPLATE),
                parameterService.getString(tenantId, "mobile.photo-watermark-position", "BOTTOM"),
                integer(tenantId, "mobile.photo-watermark-background-opacity", 74, 0, 100),
                parameterService.getString(tenantId, "mobile.photo-watermark-font-color", "#ffffff"),
                parameterService.getString(tenantId, "mobile.photo-watermark-background-color", "#031922")
        );
    }

    @Transactional
    public void update(FoundationDtos.SavePhotoWatermarkSettingsRequest request) {
        validate(request);
        Map<String, FoundationDtos.ParameterRow> rows = parameterService.list(null, null).stream()
                .collect(Collectors.toMap(FoundationDtos.ParameterRow::parameterKey, Function.identity()));
        save(rows, "mobile.photo-watermark-enabled", Boolean.toString(request.watermarkEnabled()));
        save(rows, "mobile.photo-save-original", Boolean.toString(request.saveOriginal()));
        save(rows, "mobile.photo-save-watermarked", Boolean.toString(request.saveWatermarked()));
        save(rows, "mobile.photo-watermark-template", request.template().trim());
        save(rows, "mobile.photo-watermark-position", request.position().trim().toUpperCase());
        save(rows, "mobile.photo-watermark-background-opacity", Integer.toString(request.backgroundOpacity()));
        save(rows, "mobile.photo-watermark-font-color", request.fontColor().trim().toLowerCase());
        save(rows, "mobile.photo-watermark-background-color", request.backgroundColor().trim().toLowerCase());
    }

    private void validate(FoundationDtos.SavePhotoWatermarkSettingsRequest request) {
        if (!request.watermarkEnabled() && request.saveWatermarked()) {
            throw new BusinessException("PHOTO_WATERMARK_POLICY_INVALID", "未启用水印时不能保存水印图");
        }
        if (!request.saveOriginal() && !request.saveWatermarked()) {
            throw new BusinessException("PHOTO_WATERMARK_POLICY_INVALID", "原图和水印图至少需要保留一种");
        }
    }

    private int integer(long tenantId, String key, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(parameterService.getString(tenantId, key, Integer.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void save(Map<String, FoundationDtos.ParameterRow> rows, String key, String value) {
        FoundationDtos.ParameterRow row = rows.get(key);
        if (row == null) {
            throw new IllegalStateException("Required photo watermark parameter is missing: " + key);
        }
        parameterService.update(row.id(), new FoundationDtos.SaveParameterRequest(
                row.parameterKey(), row.parameterName(), value, row.valueType(), row.groupCode(),
                row.description(), true, row.version()
        ));
    }
}
