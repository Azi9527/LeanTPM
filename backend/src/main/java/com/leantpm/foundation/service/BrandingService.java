package com.leantpm.foundation.service;

import com.leantpm.foundation.dto.FoundationDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BrandingService {
    private final ParameterService parameterService;
    private final long defaultTenantId;

    public BrandingService(
            ParameterService parameterService,
            @Value("${leantpm.branding.default-tenant-id:1}") long defaultTenantId
    ) {
        this.parameterService = parameterService;
        this.defaultTenantId = defaultTenantId;
    }

    @Transactional(readOnly = true)
    public FoundationDtos.BrandingSettings settings() {
        return new FoundationDtos.BrandingSettings(
                value("system.name", "宝山矿业设备管理系统"),
                value("branding.short-name", "宝山矿业"),
                value("branding.subtitle", "精益设备管理"),
                value("branding.logo-url", "/branding/baoshan-mining-logo.png"),
                value("branding.primary-color", "#c4000a"),
                value("branding.secondary-color", "#1c7d50"),
                value("branding.neutral-color", "#3e3a39")
        );
    }

    @Transactional
    public void update(FoundationDtos.SaveBrandingRequest request) {
        Map<String, FoundationDtos.ParameterRow> rows = parameterService.list(null, null).stream()
                .collect(Collectors.toMap(FoundationDtos.ParameterRow::parameterKey, Function.identity()));
        save(rows, "system.name", request.systemName());
        save(rows, "branding.short-name", request.shortName());
        save(rows, "branding.subtitle", request.subtitle());
        save(rows, "branding.logo-url", request.logoUrl());
        save(rows, "branding.primary-color", request.primaryColor().toLowerCase());
        save(rows, "branding.secondary-color", request.secondaryColor().toLowerCase());
        save(rows, "branding.neutral-color", request.neutralColor().toLowerCase());
    }

    private void save(Map<String, FoundationDtos.ParameterRow> rows, String key, String value) {
        FoundationDtos.ParameterRow row = rows.get(key);
        if (row == null) {
            throw new IllegalStateException("Required branding parameter is missing: " + key);
        }
        parameterService.update(row.id(), new FoundationDtos.SaveParameterRequest(
                row.parameterKey(),
                row.parameterName(),
                value,
                row.valueType(),
                row.groupCode(),
                row.description(),
                true,
                row.version()
        ));
    }

    private String value(String key, String fallback) {
        return parameterService.getString(defaultTenantId, key, fallback);
    }
}
