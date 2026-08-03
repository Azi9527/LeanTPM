package com.leantpm.fault;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/faults")
public class FaultController {
    private final FaultService service;

    public FaultController(FaultService service) {
        this.service = service;
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('fault:report:view')")
    public ApiResponse<PageResult<FaultDtos.ReportRow>> reports(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.reports(keyword, status, page, pageSize));
    }

    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAuthority('fault:report:view')")
    public ApiResponse<FaultDtos.ReportRow> report(@PathVariable long id) {
        return ApiResponse.success(service.report(id));
    }

    @PostMapping("/reports")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:report:create')")
    public ApiResponse<Map<String, Long>> createReport(
            @Valid @RequestBody FaultDtos.CreateReportRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createReport(request)));
    }

    @PostMapping("/reports/{id}/accept")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:report:accept')")
    public ApiResponse<Void> acceptReport(
            @PathVariable long id, @Valid @RequestBody FaultDtos.VersionRequest request
    ) {
        service.acceptReport(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/reports/{id}/reject")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:report:accept')")
    public ApiResponse<Void> rejectReport(
            @PathVariable long id, @Valid @RequestBody FaultDtos.RejectRequest request
    ) {
        service.rejectReport(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/reports/{id}/cancel")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:report:cancel')")
    public ApiResponse<Void> cancelReport(
            @PathVariable long id, @Valid @RequestBody FaultDtos.RejectRequest request
    ) {
        service.cancelReport(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/reports/{id}/repair-order")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:create')")
    public ApiResponse<Map<String, Long>> createRepair(
            @PathVariable long id, @Valid @RequestBody FaultDtos.CreateRepairRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createRepair(id, request)));
    }

    @GetMapping("/repairs")
    @PreAuthorize("hasAuthority('fault:repair:view') or hasAuthority('fault:repair:execute')")
    public ApiResponse<PageResult<FaultDtos.RepairRow>> repairs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean mineOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.repairs(keyword, status, mineOnly, page, pageSize));
    }

    @GetMapping("/repairs/{id}")
    @PreAuthorize("hasAuthority('fault:repair:view') or hasAuthority('fault:repair:execute')")
    public ApiResponse<FaultDtos.RepairRow> repair(@PathVariable long id) {
        return ApiResponse.success(service.repair(id));
    }

    @PutMapping("/repairs/{id}/assignment")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:assign')")
    public ApiResponse<Void> assign(
            @PathVariable long id, @Valid @RequestBody FaultDtos.AssignmentRequest request
    ) {
        service.assign(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/repairs/{id}/start")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:execute')")
    public ApiResponse<Void> start(@PathVariable long id, @Valid @RequestBody FaultDtos.ActionRequest request) {
        service.start(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/repairs/{id}/pause")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:execute')")
    public ApiResponse<Void> pause(@PathVariable long id, @Valid @RequestBody FaultDtos.ActionRequest request) {
        service.pause(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/repairs/{id}/resume")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:execute')")
    public ApiResponse<Void> resume(@PathVariable long id, @Valid @RequestBody FaultDtos.ActionRequest request) {
        service.resume(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/repairs/{id}/complete")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:execute')")
    public ApiResponse<Void> complete(@PathVariable long id, @Valid @RequestBody FaultDtos.CompleteRequest request) {
        service.complete(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/repairs/{id}/acceptance")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:repair:accept')")
    public ApiResponse<Void> acceptance(@PathVariable long id, @Valid @RequestBody FaultDtos.AcceptanceRequest request) {
        service.acceptance(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/repairs/{id}/materials")
    @PreAuthorize("hasAuthority('fault:repair:view') or hasAuthority('fault:repair:execute')")
    public ApiResponse<List<FaultDtos.MaterialRow>> materials(@PathVariable long id) {
        return ApiResponse.success(service.materials(id));
    }

    @PostMapping("/repairs/{id}/materials")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:material:manage')")
    public ApiResponse<Map<String, Long>> addMaterial(
            @PathVariable long id, @Valid @RequestBody FaultDtos.SaveMaterialRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.addMaterial(id, request)));
    }

    @PutMapping("/repairs/{id}/materials/{materialId}")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:material:manage')")
    public ApiResponse<Void> updateMaterial(
            @PathVariable long id, @PathVariable long materialId,
            @Valid @RequestBody FaultDtos.SaveMaterialRequest request
    ) {
        service.updateMaterial(id, materialId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/repairs/{id}/materials/{materialId}")
    @Idempotent
    @PreAuthorize("hasAuthority('fault:material:manage')")
    public ApiResponse<Void> deleteMaterial(
            @PathVariable long id, @PathVariable long materialId,
            @RequestParam @Min(0) int version
    ) {
        service.deleteMaterial(id, materialId, version);
        return ApiResponse.success();
    }

    @GetMapping("/repairs/{id}/events")
    @PreAuthorize("hasAuthority('fault:repair:view') or hasAuthority('fault:repair:execute')")
    public ApiResponse<List<FaultDtos.EventRow>> events(@PathVariable long id) {
        return ApiResponse.success(service.events(id));
    }

    @GetMapping("/reports/{id}/attachments")
    @PreAuthorize("hasAuthority('fault:report:view')")
    public ApiResponse<List<FaultDtos.AttachmentRow>> attachments(@PathVariable long id) {
        return ApiResponse.success(service.attachments(id));
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('fault:statistics:view')")
    public ApiResponse<FaultDtos.Statistics> statistics() {
        return ApiResponse.success(service.statistics());
    }
}
