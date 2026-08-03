package com.leantpm.foundation.controller;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.service.BrandingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;

@RestController
public class BrandingController {
    private final BrandingService service;

    public BrandingController(BrandingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/public/branding")
    public ApiResponse<FoundationDtos.BrandingSettings> settings() {
        return ApiResponse.success(service.settings());
    }

    @PutMapping("/api/v1/system/branding")
    @Idempotent
    @PreAuthorize("hasAuthority('system:parameter:manage')")
    public ApiResponse<Void> update(@Valid @RequestBody FoundationDtos.SaveBrandingRequest request) {
        service.update(request);
        return ApiResponse.success();
    }
}
