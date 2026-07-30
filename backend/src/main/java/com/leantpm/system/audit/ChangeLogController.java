package com.leantpm.system.audit;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/system/change-logs")
@Tag(name = "数据变更日志", description = "查询关键业务数据变更前后快照")
public class ChangeLogController {
    private final ChangeLogService service;

    public ChangeLogController(ChangeLogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:change-log:view')")
    @Operation(summary = "分页查询数据变更日志")
    public ApiResponse<PageResult<ChangeLogDtos.ChangeLogRow>> list(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(
                service.list(resourceType, keyword, startDate, endDate, page, pageSize)
        );
    }
}
