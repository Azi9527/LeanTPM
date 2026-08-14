package com.leantpm.mobile;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/mobile")
public class MobileController {
    private final MobileService service;

    public MobileController(MobileService service) {
        this.service = service;
    }

    @GetMapping("/bootstrap")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<MobileDtos.Bootstrap> bootstrap() {
        return ApiResponse.success(service.bootstrap());
    }

    @GetMapping("/personal-inspection-report")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<MobileDtos.PersonalInspectionReport> personalInspectionReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ApiResponse.success(service.personalInspectionReport(startDate, endDate));
    }

    @GetMapping("/inspection-performance-report")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<MobileDtos.ManagementInspectionReport> inspectionPerformanceReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long userId
    ) {
        return ApiResponse.success(service.inspectionPerformanceReport(
                startDate, endDate, organizationId, userId
        ));
    }

    @GetMapping("/inspection-performance-tasks")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<PageResult<MobileDtos.InspectionPerformanceTask>> inspectionPerformanceTasks(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "DUE")
            @Pattern(
                    regexp = "^(DUE|COMPLETED|PENDING|OVERDUE|ON_TIME|LATE|ABNORMAL|QUICK|QUICK_ABNORMAL)$",
                    message = "点检报表明细类型不正确"
            ) String metric,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.inspectionPerformanceTasks(
                startDate, endDate, organizationId, userId, metric, page, pageSize
        ));
    }

    @GetMapping("/inspection-performance-tasks/{taskId}/items")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<PageResult<MobileDtos.InspectionPerformanceDetail>> inspectionPerformanceTaskItems(
            @PathVariable @Min(1) long taskId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.inspectionPerformanceTaskItems(
                taskId, startDate, endDate, page, pageSize
        ));
    }

    @GetMapping("/equipment-status")
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:scan')")
    public ApiResponse<PageResult<MobileDtos.EquipmentStatusRow>> equipmentStatus(
            @RequestParam(required = false) String currentStatusCode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.equipmentStatus(currentStatusCode, page, pageSize));
    }

    @GetMapping("/equipment/{token}")
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:scan')")
    public ApiResponse<MobileDtos.EquipmentContext> equipment(
            @PathVariable
            @Pattern(
                    regexp = "^[a-fA-F0-9]{64}$",
                    message = "设备访问令牌格式不正确"
            )
            String token
    ) {
        return ApiResponse.success(service.equipment(token));
    }

    @PostMapping("/equipment/{token}/inspection-reports")
    @Idempotent
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:scan') and hasAuthority('inspection:task:execute')")
    public ApiResponse<Map<String, Long>> createDirectInspectionReport(
            @PathVariable
            @Pattern(
                    regexp = "^[a-fA-F0-9]{64}$",
                    message = "设备访问令牌格式不正确"
            )
            String token,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MobileDtos.DirectInspectionReportRequest request
    ) {
        return ApiResponse.success(Map.of(
                "id", service.createDirectInspectionReport(token, request, idempotencyKey)
        ));
    }

    @PostMapping("/photo-evidence")
    @Idempotent
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:task:view')")
    public ApiResponse<MobileDtos.PhotoEvidence> registerPhotoEvidence(
            @Valid @RequestBody MobileDtos.RegisterPhotoEvidenceRequest request
    ) {
        return ApiResponse.success(service.registerPhotoEvidence(request));
    }

    @GetMapping("/photo-evidence/{id}")
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:task:view')")
    public ApiResponse<MobileDtos.PhotoEvidence> photoEvidence(@PathVariable long id) {
        return ApiResponse.success(service.photoEvidence(id));
    }
}
