package com.leantpm.foundation.controller;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.service.PhotoWatermarkSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PhotoWatermarkSettingsController {
    private final PhotoWatermarkSettingsService service;

    public PhotoWatermarkSettingsController(PhotoWatermarkSettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/system/photo-watermark-settings")
    @PreAuthorize("hasAuthority('system:parameter:view')")
    public ApiResponse<FoundationDtos.PhotoWatermarkSettings> settings() {
        return ApiResponse.success(service.settings());
    }

    @PutMapping("/api/v1/system/photo-watermark-settings")
    @Idempotent
    @PreAuthorize("hasAuthority('system:parameter:manage')")
    public ApiResponse<Void> update(@Valid @RequestBody FoundationDtos.SavePhotoWatermarkSettingsRequest request) {
        service.update(request);
        return ApiResponse.success();
    }
}
