package com.leantpm.inspection;

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
@RequestMapping("/api/v1/inspection")
public class InspectionController {
    private final InspectionCatalogService catalogService;
    private final InspectionTaskService taskService;

    public InspectionController(
            InspectionCatalogService catalogService,
            InspectionTaskService taskService
    ) {
        this.catalogService = catalogService;
        this.taskService = taskService;
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
            @RequestParam(defaultValue = "false") boolean mineOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(taskService.tasks(
                keyword, taskStatus, plannedDate, mineOnly, page, pageSize
        ));
    }

    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAnyAuthority('inspection:task:view','inspection:my-task:view')")
    public ApiResponse<InspectionDtos.TaskDetail> task(@PathVariable long id) {
        return ApiResponse.success(taskService.detail(id));
    }

    @PostMapping("/tasks")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:task:create')")
    public ApiResponse<Map<String, Long>> createTask(
            @Valid @RequestBody InspectionDtos.ManualTaskRequest request
    ) {
        return ApiResponse.success(Map.of("id", taskService.createManualTask(request)));
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
}
