package com.leantpm.foundation.controller;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.service.NumberRuleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/system/number-rules")
public class NumberRuleController {
    private final NumberRuleService service;

    public NumberRuleController(NumberRuleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:number-rule:view')")
    public ApiResponse<List<FoundationDtos.NumberRuleRow>> list(
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(service.list(keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:number-rule:manage')")
    public ApiResponse<Map<String, Long>> create(
            @Valid @RequestBody FoundationDtos.SaveNumberRuleRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:number-rule:manage')")
    public ApiResponse<Void> update(
            @PathVariable long id,
            @Valid @RequestBody FoundationDtos.SaveNumberRuleRequest request
    ) {
        service.update(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/{ruleCode}/generate")
    @PreAuthorize("hasAuthority('system:number-rule:generate')")
    public ApiResponse<FoundationDtos.GeneratedNumber> generate(@PathVariable String ruleCode) {
        return ApiResponse.success(service.generate(ruleCode));
    }
}
