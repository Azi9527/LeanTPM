package com.leantpm.foundation.controller;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.service.ParameterService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system/parameters")
public class ParameterController {
    private final ParameterService service;

    public ParameterController(ParameterService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:parameter:view')")
    public ApiResponse<List<FoundationDtos.ParameterRow>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String groupCode
    ) {
        return ApiResponse.success(service.list(keyword, groupCode));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:parameter:manage')")
    public ApiResponse<Map<String, Long>> create(
            @Valid @RequestBody FoundationDtos.SaveParameterRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:parameter:manage')")
    public ApiResponse<Void> update(
            @PathVariable long id,
            @Valid @RequestBody FoundationDtos.SaveParameterRequest request
    ) {
        service.update(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:parameter:delete')")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
