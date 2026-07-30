package com.leantpm.oee;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/oee")
public class OeeController {
    private final OeeCatalogService catalogService;
    private final OeeService oeeService;
    private final OeeImportService importService;

    public OeeController(
            OeeCatalogService catalogService,
            OeeService oeeService,
            OeeImportService importService
    ) {
        this.catalogService = catalogService;
        this.oeeService = oeeService;
        this.importService = importService;
    }

    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('oee:calendar:view')")
    public ApiResponse<PageResult<OeeDtos.ShiftRow>> shifts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.shifts(keyword, status, page, pageSize));
    }

    @GetMapping("/shifts/{id}")
    @PreAuthorize("hasAuthority('oee:calendar:view')")
    public ApiResponse<OeeDtos.ShiftRow> shift(@PathVariable long id) {
        return ApiResponse.success(catalogService.shift(id));
    }

    @PostMapping("/shifts")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:shift:manage')")
    public ApiResponse<Map<String, Long>> createShift(
            @Valid @RequestBody OeeDtos.SaveShiftRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createShift(request)));
    }

    @PutMapping("/shifts/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:shift:manage')")
    public ApiResponse<Void> updateShift(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveShiftRequest request
    ) {
        catalogService.updateShift(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/shifts/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:shift:manage')")
    public ApiResponse<Void> deleteShift(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteShift(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/calendars")
    @PreAuthorize("hasAuthority('oee:calendar:view')")
    public ApiResponse<PageResult<OeeDtos.CalendarRow>> calendars(
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.calendars(
                organizationId, startDate, endDate, page, pageSize
        ));
    }

    @GetMapping("/calendars/{id}")
    @PreAuthorize("hasAuthority('oee:calendar:view')")
    public ApiResponse<OeeDtos.CalendarRow> calendar(@PathVariable long id) {
        return ApiResponse.success(catalogService.calendar(id));
    }

    @PostMapping("/calendars")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:calendar:manage')")
    public ApiResponse<Map<String, Long>> createCalendar(
            @Valid @RequestBody OeeDtos.SaveCalendarRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createCalendar(request)));
    }

    @PutMapping("/calendars/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:calendar:manage')")
    public ApiResponse<Void> updateCalendar(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveCalendarRequest request
    ) {
        catalogService.updateCalendar(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/calendars/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:calendar:manage')")
    public ApiResponse<Void> deleteCalendar(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteCalendar(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/targets")
    @PreAuthorize("hasAuthority('oee:target:view')")
    public ApiResponse<PageResult<OeeDtos.TargetRow>> targets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String targetLevel,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.targets(
                keyword, targetLevel, status, page, pageSize
        ));
    }

    @GetMapping("/targets/{id}")
    @PreAuthorize("hasAuthority('oee:target:view')")
    public ApiResponse<OeeDtos.TargetRow> target(@PathVariable long id) {
        return ApiResponse.success(catalogService.target(id));
    }

    @PostMapping("/targets")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:target:manage')")
    public ApiResponse<Map<String, Long>> createTarget(
            @Valid @RequestBody OeeDtos.SaveTargetRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createTarget(request)));
    }

    @PutMapping("/targets/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:target:manage')")
    public ApiResponse<Void> updateTarget(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveTargetRequest request
    ) {
        catalogService.updateTarget(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/targets/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:target:manage')")
    public ApiResponse<Void> deleteTarget(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteTarget(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/loss-reasons")
    @PreAuthorize("hasAnyAuthority('oee:loss-reason:view','oee:production:view')")
    public ApiResponse<PageResult<OeeDtos.LossReasonRow>> lossReasons(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String lossCategory,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.lossReasons(
                keyword, lossCategory, status, page, pageSize
        ));
    }

    @GetMapping("/loss-reasons/{id}")
    @PreAuthorize("hasAnyAuthority('oee:loss-reason:view','oee:production:view')")
    public ApiResponse<OeeDtos.LossReasonRow> lossReason(@PathVariable long id) {
        return ApiResponse.success(catalogService.lossReason(id));
    }

    @PostMapping("/loss-reasons")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:loss-reason:manage')")
    public ApiResponse<Map<String, Long>> createLossReason(
            @Valid @RequestBody OeeDtos.SaveLossReasonRequest request
    ) {
        return ApiResponse.success(Map.of(
                "id", catalogService.createLossReason(request)
        ));
    }

    @PutMapping("/loss-reasons/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:loss-reason:manage')")
    public ApiResponse<Void> updateLossReason(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveLossReasonRequest request
    ) {
        catalogService.updateLossReason(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/loss-reasons/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:loss-reason:manage')")
    public ApiResponse<Void> deleteLossReason(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteLossReason(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/outputs")
    @PreAuthorize("hasAuthority('oee:production:view')")
    public ApiResponse<PageResult<OeeDtos.OutputRow>> outputs(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(oeeService.outputs(
                equipmentId, startDate, endDate, page, pageSize
        ));
    }

    @PostMapping("/outputs")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:output:manage')")
    public ApiResponse<Map<String, Long>> createOutput(
            @Valid @RequestBody OeeDtos.SaveOutputRequest request
    ) {
        return ApiResponse.success(Map.of("id", oeeService.createOutput(request)));
    }

    @PutMapping("/outputs/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:output:manage')")
    public ApiResponse<Void> updateOutput(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveOutputRequest request
    ) {
        oeeService.updateOutput(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/outputs/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:output:manage')")
    public ApiResponse<Void> deleteOutput(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        oeeService.deleteOutput(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/downtimes")
    @PreAuthorize("hasAuthority('oee:production:view')")
    public ApiResponse<PageResult<OeeDtos.DowntimeRow>> downtimes(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long lossReasonId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(oeeService.downtimes(
                equipmentId, startDate, endDate, lossReasonId, page, pageSize
        ));
    }

    @PostMapping("/downtimes")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:downtime:manage')")
    public ApiResponse<Map<String, Long>> createDowntime(
            @Valid @RequestBody OeeDtos.SaveDowntimeRequest request
    ) {
        return ApiResponse.success(Map.of("id", oeeService.createDowntime(request)));
    }

    @PutMapping("/downtimes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:downtime:manage')")
    public ApiResponse<Void> updateDowntime(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveDowntimeRequest request
    ) {
        oeeService.updateDowntime(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/downtimes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:downtime:manage')")
    public ApiResponse<Void> deleteDowntime(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        oeeService.deleteDowntime(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('oee:record:view')")
    public ApiResponse<PageResult<OeeDtos.OeeRecordRow>> records(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String dataStatus,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(oeeService.records(
                equipmentId, organizationId, dataStatus, startDate, endDate,
                page, pageSize
        ));
    }

    @GetMapping("/records/{id}")
    @PreAuthorize("hasAuthority('oee:record:view')")
    public ApiResponse<OeeDtos.OeeRecordRow> record(@PathVariable long id) {
        return ApiResponse.success(oeeService.record(id));
    }

    @PostMapping("/records")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:record:manage')")
    public ApiResponse<Map<String, Long>> createRecord(
            @Valid @RequestBody OeeDtos.SaveOeeRecordRequest request
    ) {
        return ApiResponse.success(Map.of("id", oeeService.createRecord(request)));
    }

    @PutMapping("/records/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:record:manage')")
    public ApiResponse<Void> updateRecord(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.SaveOeeRecordRequest request
    ) {
        oeeService.updateRecord(id, request);
        return ApiResponse.success();
    }

    @PostMapping(
            value = "/records/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Idempotent
    @PreAuthorize("hasAuthority('oee:record:import')")
    public ApiResponse<OeeDtos.ImportResult> importRecords(
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(importService.importWorkbook(file));
    }

    @GetMapping("/records/import-template")
    @PreAuthorize("hasAuthority('oee:record:import')")
    public ResponseEntity<byte[]> importTemplate() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(
                        "LeanTPM-OEE-import-template.xlsx",
                        StandardCharsets.UTF_8
                )
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
                .body(importService.template());
    }

    @PostMapping("/records/{id}/recalculate")
    @Idempotent
    @PreAuthorize("hasAuthority('oee:record:recalculate')")
    public ApiResponse<OeeDtos.OeeRecordRow> recalculate(@PathVariable long id) {
        return ApiResponse.success(oeeService.recalculate(id));
    }

    @PutMapping("/records/{id}/workflow")
    @Idempotent
    @PreAuthorize(
            "(#request.action == 'SUBMIT' and "
                    + "hasAnyAuthority('oee:record:manage','oee:record:approve')) or "
                    + "(#request.action == 'APPROVE' and "
                    + "hasAuthority('oee:record:approve')) or "
                    + "((#request.action == 'LOCK' or #request.action == 'UNLOCK') and "
                    + "hasAuthority('oee:record:lock'))"
    )
    public ApiResponse<Void> workflow(
            @PathVariable long id,
            @Valid @RequestBody OeeDtos.WorkflowRequest request
    ) {
        oeeService.workflow(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/records/{id}/calculation-logs")
    @PreAuthorize("hasAuthority('oee:record:view')")
    public ApiResponse<List<OeeDtos.CalculationLogRow>> calculationLogs(
            @PathVariable long id
    ) {
        return ApiResponse.success(oeeService.calculationLogs(id));
    }

    @GetMapping("/analysis")
    @PreAuthorize("hasAuthority('oee:analysis:view')")
    public ApiResponse<OeeDtos.AnalysisResult> analysis(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(defaultValue = "DAY") String period,
            @RequestParam(defaultValue = "EQUIPMENT") String rankingType,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.success(oeeService.analysis(
                startDate, endDate, organizationId, equipmentId,
                period, rankingType, limit
        ));
    }
}
