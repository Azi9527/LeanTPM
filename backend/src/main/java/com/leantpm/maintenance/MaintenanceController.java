package com.leantpm.maintenance;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {
    private final MaintenanceCatalogService catalogService;
    private final MaintenanceTaskService taskService;

    public MaintenanceController(
            MaintenanceCatalogService catalogService,
            MaintenanceTaskService taskService
    ) {
        this.catalogService = catalogService;
        this.taskService = taskService;
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('maintenance:item:view')")
    public ApiResponse<PageResult<MaintenanceDtos.ItemRow>> items(
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
    @PreAuthorize("hasAuthority('maintenance:item:view')")
    public ApiResponse<MaintenanceDtos.ItemRow> item(@PathVariable long id) {
        return ApiResponse.success(catalogService.item(id));
    }

    @PostMapping("/items")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:item:manage')")
    public ApiResponse<Map<String, Long>> createItem(
            @Valid @RequestBody MaintenanceDtos.SaveItemRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createItem(request)));
    }

    @PutMapping("/items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:item:manage')")
    public ApiResponse<Void> updateItem(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.SaveItemRequest request
    ) {
        catalogService.updateItem(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:item:delete')")
    public ApiResponse<Void> deleteItem(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        catalogService.deleteItem(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/schemes")
    @PreAuthorize("hasAuthority('maintenance:scheme:view')")
    public ApiResponse<PageResult<MaintenanceDtos.SchemeRow>> schemes(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String maintenanceType,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.schemes(
                keyword, maintenanceType, status, page, pageSize
        ));
    }

    @GetMapping("/schemes/{id}")
    @PreAuthorize("hasAuthority('maintenance:scheme:view')")
    public ApiResponse<MaintenanceDtos.SchemeDetail> scheme(
            @PathVariable long id,
            @RequestParam(required = false) Long versionId
    ) {
        return ApiResponse.success(catalogService.scheme(id, versionId));
    }

    @PostMapping("/schemes")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:scheme:manage')")
    public ApiResponse<Map<String, Long>> createScheme(
            @Valid @RequestBody MaintenanceDtos.SaveSchemeRequest request
    ) {
        return ApiResponse.success(Map.of("id", catalogService.createScheme(request)));
    }

    @PostMapping("/schemes/{id}/versions")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:scheme:manage')")
    public ApiResponse<Map<String, Long>> createSchemeVersion(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.SaveSchemeRequest request
    ) {
        return ApiResponse.success(Map.of(
                "versionId", catalogService.createSchemeVersion(id, request)
        ));
    }

    @PostMapping("/schemes/{id}/versions/{versionId}/publish")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:scheme:publish')")
    public ApiResponse<Void> publish(
            @PathVariable long id,
            @PathVariable long versionId
    ) {
        catalogService.publish(id, versionId);
        return ApiResponse.success();
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('maintenance:plan:view')")
    public ApiResponse<PageResult<MaintenanceDtos.PlanRow>> plans(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String planStatus,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(catalogService.plans(
                keyword, planStatus, page, pageSize
        ));
    }

    @PutMapping("/plans/{id}/status")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:plan:manage')")
    public ApiResponse<Void> updatePlanStatus(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.UpdatePlanStatusRequest request
    ) {
        catalogService.updatePlanStatus(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/plans/{id}/meter")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:plan:meter')")
    public ApiResponse<Void> updatePlanMeter(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.UpdateMeterRequest request
    ) {
        catalogService.updatePlanMeter(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/plans/generate")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:plan:generate')")
    public ApiResponse<MaintenanceDtos.GenerationResult> generate() {
        return ApiResponse.success(taskService.generateDueTasks());
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('maintenance:task:view','maintenance:my-task:view')")
    public ApiResponse<PageResult<MaintenanceDtos.TaskRow>> tasks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String taskStatus,
            @RequestParam(required = false) LocalDate plannedDate,
            @RequestParam(defaultValue = "false") boolean mineOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(taskService.tasks(
                keyword, taskStatus, plannedDate, mineOnly, page, pageSize
        ));
    }

    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAnyAuthority('maintenance:task:view','maintenance:my-task:view')")
    public ApiResponse<MaintenanceDtos.TaskDetail> task(@PathVariable long id) {
        return ApiResponse.success(taskService.detail(id));
    }

    @PostMapping("/tasks")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:create')")
    public ApiResponse<Map<String, Long>> createTask(
            @Valid @RequestBody MaintenanceDtos.ManualTaskRequest request
    ) {
        return ApiResponse.success(Map.of("id", taskService.createManualTask(request)));
    }

    @PutMapping("/tasks/{id}/assignment")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:assign')")
    public ApiResponse<Void> assign(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.AssignTaskRequest request
    ) {
        taskService.assign(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/tasks/{id}/collaborators")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:collaborate')")
    public ApiResponse<Void> collaborators(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.CollaboratorRequest request
    ) {
        taskService.replaceCollaborators(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/start")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> start(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.TaskActionRequest request
    ) {
        taskService.start(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/pause")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> pause(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.PauseTaskRequest request
    ) {
        taskService.pause(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/resume")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> resume(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.TaskActionRequest request
    ) {
        taskService.resume(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/tasks/{id}/draft")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> saveDraft(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.SaveTaskResultsRequest request
    ) {
        taskService.saveDraft(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/submit")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> submit(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.SaveTaskResultsRequest request
    ) {
        taskService.submit(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/confirm")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:confirm')")
    public ApiResponse<Void> confirm(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.ReviewTaskRequest request
    ) {
        taskService.review(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/tasks/{id}/materials")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> saveMaterial(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.MaterialUsageRequest request
    ) {
        taskService.saveMaterial(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/tasks/{id}/materials/{materialId}")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:execute')")
    public ApiResponse<Void> deleteMaterial(
            @PathVariable long id,
            @PathVariable long materialId,
            @RequestParam @Min(0) int version
    ) {
        taskService.deleteMaterial(id, materialId, version);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/close")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:task:cancel')")
    public ApiResponse<Void> close(
            @PathVariable long id,
            @RequestParam
            @Pattern(regexp = "^(CANCELLED|VOIDED)$", message = "任务关闭状态不正确")
            String targetStatus,
            @Valid @RequestBody MaintenanceDtos.CloseTaskRequest request
    ) {
        taskService.close(id, targetStatus, request);
        return ApiResponse.success();
    }

    @GetMapping("/abnormalities")
    @PreAuthorize("hasAuthority('maintenance:abnormal:view')")
    public ApiResponse<PageResult<MaintenanceDtos.AbnormalRow>> abnormalities(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String abnormalStatus,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(taskService.abnormalities(
                keyword, abnormalStatus, page, pageSize
        ));
    }

    @PutMapping("/abnormalities/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:abnormal:handle')")
    public ApiResponse<Void> handleAbnormal(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.HandleAbnormalRequest request
    ) {
        taskService.handleAbnormal(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/abnormalities/{id}/verify")
    @Idempotent
    @PreAuthorize("hasAuthority('maintenance:abnormal:verify')")
    public ApiResponse<Void> verifyAbnormal(
            @PathVariable long id,
            @Valid @RequestBody MaintenanceDtos.VerifyAbnormalRequest request
    ) {
        taskService.verifyAbnormal(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('maintenance:statistics:view')")
    public ApiResponse<MaintenanceDtos.Statistics> statistics() {
        return ApiResponse.success(taskService.statistics());
    }
}
