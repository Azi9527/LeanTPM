package com.leantpm.inspection;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/inspection")
public class InspectionController {
    private final InspectionCatalogService catalogService;
    private final InspectionTaskService taskService;
    private final InspectionImportService importService;
    private final InspectionExportService exportService;

    public InspectionController(
            InspectionCatalogService catalogService,
            InspectionTaskService taskService,
            InspectionImportService importService,
            InspectionExportService exportService
    ) {
        this.catalogService = catalogService;
        this.taskService = taskService;
        this.importService = importService;
        this.exportService = exportService;
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('inspection:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        "LeanTPM-inspection-import-template.xlsx",
                                        StandardCharsets.UTF_8
                                )
                                .build().toString()
                )
                .body(importService.template());
    }

    @PostMapping(
            value = "/import/validate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('inspection:import')")
    public ApiResponse<InspectionImportDtos.ImportResult> validateImport(
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(importService.validate(file));
    }

    @PostMapping("/import/commit")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:import')")
    public ApiResponse<InspectionImportDtos.ImportResult> commitImport(
            @RequestParam String batchId
    ) {
        return ApiResponse.success(importService.commit(batchId));
    }

    @GetMapping("/imports/{batchId}")
    @PreAuthorize("hasAuthority('inspection:import')")
    public ApiResponse<InspectionImportDtos.ImportResult> importBatch(
            @PathVariable String batchId
    ) {
        return ApiResponse.success(importService.batch(batchId));
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('inspection:item:view')")
    public ApiResponse<PageResult<InspectionDtos.ItemRow>> items(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String itemCategory,
            @RequestParam(required = false) String resultType,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.items(
                keyword, itemCategory, resultType, status, page, pageSize
        ));
    }

    @GetMapping("/items/{id}")
    @PreAuthorize("hasAuthority('inspection:item:view')")
    public ApiResponse<InspectionDtos.ItemRow> item(@PathVariable long id) {
        return ApiResponse.success(catalogService.item(id));
    }

    @PostMapping("/items")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:item:manage')")
    public ApiResponse<Map<String, Long>> createItem(
            @Valid @RequestBody InspectionDtos.SaveItemRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createItem(request)));
    }

    @PutMapping("/items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:item:manage')")
    public ApiResponse<Void> updateItem(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.SaveItemRequest request
    ) {
        catalogService.updateItem(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:item:delete')")
    public ApiResponse<Void> deleteItem(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteItem(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/schemes")
    @PreAuthorize("hasAuthority('inspection:scheme:view')")
    public ApiResponse<PageResult<InspectionDtos.SchemeRow>> schemes(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String inspectionType,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.schemes(
                keyword, inspectionType, status, page, pageSize
        ));
    }

    @GetMapping("/schemes/{id}")
    @PreAuthorize("hasAuthority('inspection:scheme:view')")
    public ApiResponse<InspectionDtos.SchemeDetail> scheme(
            @PathVariable long id,
            @RequestParam(required = false) Long versionId
    ) {
        return ApiResponse.success(catalogService.scheme(id, versionId));
    }

    @PostMapping("/schemes")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:scheme:manage')")
    public ApiResponse<Map<String, Long>> createScheme(
            @Valid @RequestBody InspectionDtos.SaveSchemeRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createScheme(request)));
    }

    @PostMapping("/schemes/{id}/versions")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:scheme:manage')")
    public ApiResponse<Map<String, Long>> createSchemeVersion(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.SaveSchemeRequest request
    ) {
        return ApiResponse.success(Map.of(
                "versionId", catalogService.createSchemeVersion(id, request)
        ));
    }

    @PostMapping("/schemes/{id}/versions/{versionId}/publish")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:scheme:publish')")
    public ApiResponse<Void> publish(
            @PathVariable long id,
            @PathVariable long versionId
    ) {
        catalogService.publish(id, versionId);
        return ApiResponse.success();
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('inspection:plan:view')")
    public ApiResponse<PageResult<InspectionDtos.PlanRow>> plans(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String planStatus,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.plans(
                keyword, planStatus, page, pageSize
        ));
    }

    @PostMapping("/plans")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:plan:manage')")
    public ApiResponse<InspectionDtos.CreatePlansResult> createPlans(
            @Valid @RequestBody InspectionDtos.CreatePlansRequest request
    ) {
        return ApiResponse.success(catalogService.createPlans(request));
    }

    @PutMapping("/plans/{id}/status")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:plan:manage')")
    public ApiResponse<Void> updatePlanStatus(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.UpdatePlanStatusRequest request
    ) {
        catalogService.updatePlanStatus(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/plans/generate")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:plan:generate')")
    public ApiResponse<InspectionDtos.GenerationResult> generate() {
        return ApiResponse.success(taskService.generateDueTasks());
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('inspection:task:view','inspection:my-task:view')")
    public ApiResponse<PageResult<InspectionDtos.TaskRow>> tasks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String taskStatus,
            @RequestParam(required = false) LocalDate plannedDate,
            @RequestParam(defaultValue = "PLANNED_DATE") String timeField,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) Long assigneeUserId,
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Long schemeId,
            @RequestParam(defaultValue = "false") boolean abnormalOnly,
            @RequestParam(required = false) String abnormalSeverity,
            @RequestParam(defaultValue = "false") boolean mineOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(taskService.tasks(new InspectionDtos.TaskQuery(
                keyword, taskStatus, plannedDate, timeField, startDate, endDate,
                organizationId, teamCode, assigneeUserId, equipmentId, schemeId,
                abnormalOnly, abnormalSeverity, mineOnly
        ), page, pageSize));
    }

    @GetMapping("/results/export")
    @PreAuthorize("hasAuthority('inspection:task:export')")
    public ResponseEntity<byte[]> exportResults(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String taskStatus,
            @RequestParam(defaultValue = "PLANNED_DATE") String timeField,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String teamCode,
            @RequestParam(required = false) Long assigneeUserId,
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Long schemeId,
            @RequestParam(defaultValue = "false") boolean abnormalOnly,
            @RequestParam(required = false) String abnormalSeverity,
            @RequestParam(defaultValue = "false") boolean mineOnly
    ) {
        byte[] workbook = taskService.exportResults(new InspectionDtos.TaskQuery(
                keyword, taskStatus, null, timeField, startDate, endDate,
                organizationId, teamCode, assigneeUserId, equipmentId, schemeId,
                abnormalOnly, abnormalSeverity, mineOnly
        ));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
                .contentLength(workbook.length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        "LeanTPM-inspection-results.xlsx",
                                        StandardCharsets.UTF_8
                                )
                                .build().toString()
                )
                .body(workbook);
    }

    @PostMapping("/results/export-jobs")
    @PreAuthorize("hasAuthority('inspection:task:export')")
    public ApiResponse<InspectionDtos.CreateExportJobResult> createImageExportJob(
            @RequestBody InspectionDtos.TaskQuery query
    ) {
        return ApiResponse.success(exportService.createImageExportJob(query));
    }

    @GetMapping("/results/export-jobs/{jobId}")
    @PreAuthorize("hasAuthority('inspection:task:export')")
    public ApiResponse<InspectionDtos.ExportJobDetail> exportJob(@PathVariable long jobId) {
        return ApiResponse.success(exportService.exportJob(jobId));
    }

    @GetMapping("/results/export-jobs/{jobId}/files/{fileId}")
    @PreAuthorize("hasAuthority('inspection:task:export')")
    public ResponseEntity<Resource> exportFile(
            @PathVariable long jobId,
            @PathVariable long fileId
    ) {
        var download = exportService.exportFile(jobId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.file().contentType()))
                .contentLength(download.file().fileSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.file().fileName(), StandardCharsets.UTF_8)
                                .build().toString()
                )
                .body(download.resource());
    }

    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAnyAuthority('inspection:task:view','inspection:my-task:view')")
    public ApiResponse<InspectionDtos.TaskDetail> task(@PathVariable long id) {
        return ApiResponse.success(taskService.detail(id));
    }

    @GetMapping("/tasks/{id}/attachments")
    @PreAuthorize("hasAnyAuthority('inspection:task:view','inspection:my-task:view')")
    public ApiResponse<List<InspectionDtos.InspectionAttachmentRow>> taskAttachments(
            @PathVariable long id
    ) {
        return ApiResponse.success(taskService.taskAttachments(id));
    }

    @GetMapping("/tasks/{taskId}/attachments/{attachmentId}/content")
    @PreAuthorize("hasAnyAuthority('inspection:task:view','inspection:my-task:view')")
    public ResponseEntity<Resource> taskAttachmentContent(
            @PathVariable long taskId,
            @PathVariable long attachmentId
    ) {
        return attachmentResponse(
                taskService.taskAttachmentContent(taskId, attachmentId)
        );
    }

    @PostMapping("/tasks")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:create')")
    public ApiResponse<Map<String, Long>> createTask(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InspectionDtos.ManualTaskRequest request
    ) {
        return ApiResponse.success(Map.of(
                "id", taskService.createManualTask(request, idempotencyKey)
        ));
    }

    @PutMapping("/tasks/{id}/assignment")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:assign')")
    public ApiResponse<Void> assign(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.AssignTaskRequest request
    ) {
        taskService.assign(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/tasks/{id}/draft")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:execute')")
    public ApiResponse<Void> saveDraft(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.SaveTaskResultsRequest request
    ) {
        taskService.saveDraft(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/submit")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:execute')")
    public ApiResponse<Void> submit(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.SaveTaskResultsRequest request
    ) {
        taskService.submit(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/review")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:review')")
    public ApiResponse<Void> review(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.ReviewTaskRequest request
    ) {
        taskService.review(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/close")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:cancel')")
    public ApiResponse<Void> close(
            @PathVariable long id,
            @RequestParam
            @Pattern(regexp = "^(CANCELLED|VOIDED)$", message = "任务关闭状态不正确")
            String targetStatus,
            @Valid @RequestBody InspectionDtos.CloseTaskRequest request
    ) {
        taskService.close(id, targetStatus, request);
        return ApiResponse.success();
    }

    @GetMapping("/abnormalities")
    @PreAuthorize("hasAuthority('inspection:abnormal:view')")
    public ApiResponse<PageResult<InspectionDtos.AbnormalRow>> abnormalities(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String abnormalStatus,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(taskService.abnormalities(
                keyword, abnormalStatus, page, pageSize
        ));
    }

    @GetMapping("/abnormalities/{id}/attachments")
    @PreAuthorize("hasAuthority('inspection:abnormal:view')")
    public ApiResponse<List<InspectionDtos.InspectionAttachmentRow>> abnormalAttachments(
            @PathVariable long id
    ) {
        return ApiResponse.success(taskService.abnormalAttachments(id));
    }

    @GetMapping("/abnormalities/{abnormalId}/attachments/{attachmentId}/content")
    @PreAuthorize("hasAuthority('inspection:abnormal:view')")
    public ResponseEntity<Resource> abnormalAttachmentContent(
            @PathVariable long abnormalId,
            @PathVariable long attachmentId
    ) {
        return attachmentResponse(
                taskService.abnormalAttachmentContent(abnormalId, attachmentId)
        );
    }

    @PutMapping("/abnormalities/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:abnormal:handle')")
    public ApiResponse<Void> handleAbnormal(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.HandleAbnormalRequest request
    ) {
        taskService.handleAbnormal(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/abnormalities/{id}/verify")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:abnormal:verify')")
    public ApiResponse<Void> verifyAbnormal(
            @PathVariable long id,
            @Valid @RequestBody InspectionDtos.VerifyAbnormalRequest request
    ) {
        taskService.verifyAbnormal(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('inspection:statistics:view')")
    public ApiResponse<InspectionDtos.Statistics> statistics() {
        return ApiResponse.success(taskService.statistics());
    }

    private ResponseEntity<Resource> attachmentResponse(
            com.leantpm.system.attachment.AttachmentService.DownloadedAttachment download
    ) {
        String contentType = download.record().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : download.record().contentType();
        boolean image = contentType.toLowerCase().startsWith("image/");
        ContentDisposition disposition = (image
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(download.record().originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(download.record().fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.resource());
    }
}
