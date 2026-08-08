package com.leantpm.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.foundation.service.ParameterService;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MaintenanceTaskService {
    private static final Set<String> EDITABLE_STATUSES =
            Set.of("IN_PROGRESS");

    private final MaintenanceMapper mapper;
    private final NumberRuleService numberRuleService;
    private final ParameterService parameterService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;
    private final EquipmentService equipmentService;

    public MaintenanceTaskService(
            MaintenanceMapper mapper,
            NumberRuleService numberRuleService,
            ParameterService parameterService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper,
            EquipmentService equipmentService
    ) {
        this.mapper = mapper;
        this.numberRuleService = numberRuleService;
        this.parameterService = parameterService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
        this.equipmentService = equipmentService;
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDtos.TaskRow> tasks(
            String keyword,
            String taskStatus,
            LocalDate plannedDate,
            boolean mineOnly,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        String normalizedStatus = upper(taskStatus);
        return PageResult.of(
                mapper.findTasks(
                        current.tenantId(), scope, clean(keyword), normalizedStatus,
                        plannedDate, mineOnly, offset, pageSize
                ),
                mapper.countTasks(
                        current.tenantId(), scope, clean(keyword), normalizedStatus,
                        plannedDate, mineOnly
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public MaintenanceDtos.TaskDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        return taskDetail(current.tenantId(), task);
    }

    @Transactional
    public MaintenanceDtos.GenerationResult generateDueTasks() {
        var current = SecurityUtils.currentUser();
        return generateScheduled(current.tenantId(), current.userId());
    }

    @Transactional
    public MaintenanceDtos.GenerationResult generateScheduled(long tenantId, long operatorId) {
        int lookahead = parseLookahead(tenantId);
        return generate(tenantId, operatorId, LocalDate.now().plusDays(lookahead));
    }

    @Transactional
    public MaintenanceDtos.GenerationResult generate(
            long tenantId,
            long operatorId,
            LocalDate throughDate
    ) {
        List<MaintenanceMapper.GenerationPlan> plans =
                mapper.findGenerationPlans(tenantId, throughDate);
        List<String> taskCodes = new ArrayList<>();
        int generated = 0;
        int skipped = 0;
        for (MaintenanceMapper.GenerationPlan plan : plans) {
            boolean meterBased = Set.of(
                    "RUNNING_HOURS", "PRODUCTION_QUANTITY"
            ).contains(plan.cycleType());
            LocalDate planThroughDate = LocalDate.now().plusDays(
                    Math.max(0, plan.generationLeadDays())
            );
            if (planThroughDate.isAfter(throughDate)) {
                planThroughDate = throughDate;
            }
            LocalDate occurrence = meterBased ? LocalDate.now() : plan.nextGenerationDate();
            while (meterBased || !occurrence.isAfter(planThroughDate)) {
                if (plan.expiryDate() != null && occurrence.isAfter(plan.expiryDate())) {
                    break;
                }
                if (occurrence.isBefore(plan.effectiveDate())) {
                    occurrence = nextOccurrence(plan, occurrence);
                    continue;
                }
                String occurrenceKey = meterBased
                        ? plan.cycleType() + ":" + plan.nextTriggerValue().stripTrailingZeros()
                        : occurrence.toString();
                Long existing = mapper.findTaskIdByOccurrence(tenantId, plan.id(), occurrenceKey);
                if (existing == null) {
                    MaintenanceMapper.EquipmentSnapshot equipment =
                            mapper.findEquipmentSnapshot(
                                    tenantId, plan.equipmentId(), DataPermission.all(operatorId)
                            );
                    if (equipment == null) {
                        skipped++;
                    } else {
                        String code = numberRuleService.generate(
                                tenantId, operatorId, "MAINTENANCE_TASK"
                        ).businessNumber();
                        LocalDateTime start = occurrence.atTime(
                                plan.scheduledTime() == null ? LocalTime.of(8, 0)
                                        : plan.scheduledTime()
                        );
                        LocalDateTime due = occurrence.atTime(23, 59, 59);
                        int inserted = mapper.insertTask(
                                tenantId, code, plan, equipment, occurrence, start, due,
                                occurrenceKey, "PLAN", false, null, operatorId
                        );
                        Long taskId = inserted == 0
                                ? mapper.findTaskIdByOccurrence(tenantId, plan.id(), occurrenceKey)
                                : mapper.findTaskIdByCode(tenantId, code);
                        if (inserted > 0 && taskId != null) {
                            mapper.copyTaskItems(
                                    tenantId, taskId, plan.schemeVersionId()
                            );
                            String initialStatus = plan.assigneeUserId() == null
                                    ? "PENDING_ASSIGNMENT" : "PENDING";
                            mapper.insertTaskEvent(
                                    tenantId, taskId, "GENERATED", null, initialStatus,
                                    "计划自动生成", operatorId
                            );
                            generated++;
                            taskCodes.add(code);
                        } else {
                            skipped++;
                        }
                    }
                } else {
                    skipped++;
                }
                LocalDate completedOccurrence = occurrence;
                if (meterBased) {
                    mapper.updateMeterPlanGeneration(
                            tenantId, plan.id(), completedOccurrence, operatorId
                    );
                    break;
                }
                occurrence = nextOccurrence(plan, occurrence);
                mapper.updatePlanGeneration(
                        tenantId, plan.id(), completedOccurrence, occurrence, operatorId
                );
            }
        }
        return new MaintenanceDtos.GenerationResult(
                plans.size(), generated, skipped, List.copyOf(taskCodes)
        );
    }

    @Transactional
    public long createManualTask(MaintenanceDtos.ManualTaskRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        MaintenanceDtos.SchemeVersionRow version =
                mapper.findSchemeVersion(current.tenantId(), request.schemeVersionId());
        if (version == null || !"PUBLISHED".equals(version.versionStatus())) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_NOT_PUBLISHED", "只能按已发布方案创建任务"
            );
        }
        if (Boolean.TRUE.equals(request.backfill())
                && !Boolean.TRUE.equals(version.backfillAllowedFlag())) {
            throw new BusinessException(
                    "MAINTENANCE_BACKFILL_NOT_ALLOWED", "该方案不允许补录任务"
            );
        }
        if (request.dueTime().isBefore(
                request.plannedStartTime() == null
                        ? request.plannedDate().atStartOfDay()
                        : request.plannedStartTime()
        )) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_DUE_INVALID", "截止时间不能早于计划开始时间"
            );
        }
        MaintenanceDtos.SchemeRow scheme =
                mapper.findScheme(current.tenantId(), version.schemeId());
        if (scheme == null) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_NOT_FOUND", "维保方案不存在", HttpStatus.NOT_FOUND
            );
        }
        MaintenanceMapper.EquipmentSnapshot equipment = mapper.findEquipmentSnapshot(
                current.tenantId(), request.equipmentId(), scope
        );
        if (equipment == null) {
            throw new BusinessException(
                    "EQUIPMENT_NOT_FOUND", "设备不存在、已停用或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        if (request.assigneeUserId() != null
                && mapper.countActiveUser(current.tenantId(), request.assigneeUserId()) == 0) {
            throw new BusinessException(
                    "USER_NOT_FOUND", "执行人不存在或已停用", HttpStatus.NOT_FOUND
            );
        }
        String code = numberRuleService.generate(
                current.tenantId(), current.userId(), "MAINTENANCE_TASK"
        ).businessNumber();
        mapper.insertManualTask(
                current.tenantId(), code, scheme, version, equipment, request, current.userId()
        );
        Long taskId = mapper.findTaskIdByCode(current.tenantId(), code);
        if (taskId == null) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_CREATE_FAILED", "维保任务创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        mapper.copyTaskItems(current.tenantId(), taskId, version.id());
        mapper.insertTaskEvent(
                current.tenantId(), taskId, "CREATED", null,
                request.assigneeUserId() == null ? "PENDING_ASSIGNMENT" : "PENDING",
                clean(request.remark()), current.userId()
        );
        changeLogService.record(
                "MAINTENANCE_TASK", taskId, "CREATE", null,
                mapper.findTask(current.tenantId(), taskId, DataPermission.all(current.userId()))
        );
        return taskId;
    }

    @Transactional
    public void assign(long id, MaintenanceDtos.AssignTaskRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow before = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (mapper.countActiveUser(current.tenantId(), request.assigneeUserId()) == 0) {
            throw new BusinessException(
                    "USER_NOT_FOUND", "执行人不存在或已停用", HttpStatus.NOT_FOUND
            );
        }
        if (mapper.assignTask(current.tenantId(), id, request, current.userId()) == 0) {
            throw optimisticConflict();
        }
        MaintenanceDtos.TaskRow after = mapper.findTask(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        String event = before.assigneeUserId() == null ? "ASSIGNED" : "TRANSFERRED";
        mapper.insertTaskEvent(
                current.tenantId(), id, event, before.taskStatus(), after.taskStatus(),
                "任务已派给用户 " + request.assigneeUserId(), current.userId()
        );
        changeLogService.record(
                "MAINTENANCE_TASK", id, "ASSIGN", before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void replaceCollaborators(
            long id,
            MaintenanceDtos.CollaboratorRequest request
    ) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow before = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        Set<Long> userIds = new java.util.LinkedHashSet<>(
                request.userIds() == null ? List.of() : request.userIds()
        );
        userIds.remove(before.assigneeUserId());
        for (Long userId : userIds) {
            if (mapper.countActiveUser(current.tenantId(), userId) == 0) {
                throw new BusinessException(
                        "USER_NOT_FOUND", "协同人员不存在或已停用", HttpStatus.NOT_FOUND
                );
            }
        }
        if (mapper.touchTask(
                current.tenantId(), id, request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.deleteTaskCollaborators(current.tenantId(), id);
        for (Long userId : userIds) {
            mapper.insertTaskCollaborator(
                    current.tenantId(), id, userId, current.userId()
            );
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, "COLLABORATORS_CHANGED",
                before.taskStatus(), before.taskStatus(),
                "协同人员数量：" + userIds.size(), current.userId()
        );
    }

    @Transactional
    public void start(long id, MaintenanceDtos.TaskActionRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        assertCanExecute(current, task);
        if (!Set.of("PENDING", "OVERDUE").contains(task.taskStatus())) {
            throw invalidTransition(task.taskStatus(), "IN_PROGRESS");
        }
        MaintenanceMapper.EquipmentRuntime runtime =
                mapper.findEquipmentRuntime(current.tenantId(), task.equipmentId());
        if (runtime == null) {
            throw new BusinessException(
                    "EQUIPMENT_STATUS_NOT_FOUND", "设备当前状态不存在", HttpStatus.NOT_FOUND
            );
        }
        if (Boolean.TRUE.equals(task.stopRequiredFlag())
                && !"STOPPED".equals(runtime.statusCode())) {
            equipmentService.changeStatusFromBusiness(
                    task.equipmentId(),
                    new EquipmentDtos.ChangeStatusRequest(
                            "STOPPED",
                            "维保任务 " + task.taskCode() + " 开始，设备进入停机状态",
                            "MAINTENANCE",
                            runtime.statusVersion()
                    )
            );
        }
        if (mapper.startTask(
                current.tenantId(), id, task.taskStatus(), request.version(),
                runtime.statusCode(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, "STARTED", task.taskStatus(), "IN_PROGRESS",
                clean(request.remark()), current.userId()
        );
    }

    @Transactional
    public void pause(long id, MaintenanceDtos.PauseTaskRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        assertCanExecute(current, task);
        if (!"IN_PROGRESS".equals(task.taskStatus())) {
            throw invalidTransition(task.taskStatus(), "PAUSED");
        }
        if (mapper.pauseTask(
                current.tenantId(), id, request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskPause(
                current.tenantId(), id, request.reason().trim(), current.userId()
        );
        mapper.insertTaskEvent(
                current.tenantId(), id, "PAUSED", "IN_PROGRESS", "PAUSED",
                request.reason().trim(), current.userId()
        );
    }

    @Transactional
    public void resume(long id, MaintenanceDtos.TaskActionRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        assertCanExecute(current, task);
        if (!"PAUSED".equals(task.taskStatus())) {
            throw invalidTransition(task.taskStatus(), "IN_PROGRESS");
        }
        if (mapper.resumeTask(
                current.tenantId(), id, request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.closeTaskPause(current.tenantId(), id, current.userId());
        mapper.insertTaskEvent(
                current.tenantId(), id, "RESUMED", "PAUSED", "IN_PROGRESS",
                clean(request.remark()), current.userId()
        );
    }

    @Transactional
    public void saveDraft(long id, MaintenanceDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        MaintenanceDtos.TaskRow task = requireTask(current.tenantId(), id, scope);
        assertCanExecute(current, task);
        if (!EDITABLE_STATUSES.contains(task.taskStatus())) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_NOT_EDITABLE", "当前任务状态不允许录入结果",
                    HttpStatus.CONFLICT
            );
        }
        if (task.version() != request.taskVersion()) {
            throw optimisticConflict();
        }
        for (MaintenanceDtos.SaveResultRequest resultRequest : request.results()) {
            saveResult(current, task, resultRequest);
        }
        if (mapper.updateTaskAfterDraft(
                current.tenantId(), id, task.version(), clean(request.executionRemark()),
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, "DRAFT_SAVED", task.taskStatus(), "IN_PROGRESS",
                "保存维保草稿", current.userId()
        );
    }

    @Transactional
    public void submit(long id, MaintenanceDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        saveDraft(id, request);
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        if (mapper.countMissingRequiredResults(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_RESULT_REQUIRED_MISSING", "必填维保项目尚未完整填写"
            );
        }
        if (mapper.countInvalidResultAttachments(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_RESULT_PHOTO_REQUIRED", "存在未上传必需照片的维保项目"
            );
        }
        mapper.submitResults(current.tenantId(), id);
        createAbnormalities(current, task);
        if (mapper.finishTask(
                current.tenantId(), id, task.version(),
                clean(request.executionRemark()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, "FINISHED", "IN_PROGRESS", "PENDING_CONFIRMATION",
                "提交维保结果", current.userId()
        );
        changeLogService.record(
                "MAINTENANCE_TASK", id, "SUBMIT", task,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void review(long id, MaintenanceDtos.ReviewTaskRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow before = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (!"PENDING_CONFIRMATION".equals(before.taskStatus())) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_NOT_CONFIRMABLE", "当前任务状态不允许确认",
                    HttpStatus.CONFLICT
            );
        }
        if (!Boolean.TRUE.equals(request.approved()) && clean(request.comment()) == null) {
            throw new BusinessException(
                    "MAINTENANCE_REVIEW_COMMENT_REQUIRED", "驳回时必须填写原因"
            );
        }
        boolean approved = Boolean.TRUE.equals(request.approved());
        int updated = approved
                ? mapper.confirmTask(current.tenantId(), id, request, current.userId())
                : mapper.returnTask(current.tenantId(), id, request, current.userId());
        if (updated == 0) {
            throw optimisticConflict();
        }
        String target = approved ? "COMPLETED" : "IN_PROGRESS";
        mapper.insertTaskEvent(
                current.tenantId(), id,
                approved ? "CONFIRMED" : "RETURNED",
                "PENDING_CONFIRMATION", target, clean(request.comment()), current.userId()
        );
        if (approved) {
            restoreEquipmentStatus(current, before);
        }
        changeLogService.record(
                "MAINTENANCE_TASK", id, "REVIEW", before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void close(long id, String targetStatus, MaintenanceDtos.CloseTaskRequest request) {
        var current = SecurityUtils.currentUser();
        String target = upper(targetStatus);
        if (!Set.of("CANCELLED", "VOIDED").contains(target)) {
            throw new BusinessException("MAINTENANCE_TASK_CLOSE_STATUS_INVALID", "任务关闭状态不正确");
        }
        MaintenanceDtos.TaskRow before = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (mapper.closeTask(
                current.tenantId(), id, request, target, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, target, before.taskStatus(), target,
                request.reason().trim(), current.userId()
        );
        if ("PAUSED".equals(before.taskStatus())) {
            mapper.closeTaskPause(current.tenantId(), id, current.userId());
        }
        restoreEquipmentStatus(current, before);
        changeLogService.record(
                "MAINTENANCE_TASK", id, target, before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void saveMaterial(
            long taskId,
            MaintenanceDtos.MaterialUsageRequest request
    ) {
        CurrentUser current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), taskId, dataPermissionService.current()
        );
        assertCanExecute(current, task);
        if (!"IN_PROGRESS".equals(task.taskStatus())) {
            throw new BusinessException(
                    "MAINTENANCE_MATERIAL_NOT_EDITABLE", "只有执行中的任务可以登记备件"
            );
        }
        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0
                || request.unitCost() != null
                && request.unitCost().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    "MAINTENANCE_MATERIAL_VALUE_INVALID", "备件数量必须大于零且单价不能为负数"
            );
        }
        if (request.id() == null) {
            mapper.insertMaterial(current.tenantId(), taskId, request, current.userId());
        } else {
            if (request.version() == null || mapper.updateMaterial(
                    current.tenantId(), taskId, request, current.userId()
            ) == 0) {
                throw optimisticConflict();
            }
        }
        mapper.insertTaskEvent(
                current.tenantId(), taskId, "MATERIAL_SAVED",
                task.taskStatus(), task.taskStatus(),
                request.materialCode() + " × " + request.quantity(), current.userId()
        );
    }

    @Transactional
    public void deleteMaterial(long taskId, long materialId, int version) {
        CurrentUser current = SecurityUtils.currentUser();
        MaintenanceDtos.TaskRow task = requireTask(
                current.tenantId(), taskId, dataPermissionService.current()
        );
        assertCanExecute(current, task);
        if (!"IN_PROGRESS".equals(task.taskStatus())
                || mapper.deleteMaterial(
                current.tenantId(), taskId, materialId, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), taskId, "MATERIAL_DELETED",
                task.taskStatus(), task.taskStatus(),
                "删除备件耗用记录 " + materialId, current.userId()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDtos.AbnormalRow> abnormalities(
            String keyword,
            String status,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findAbnormalities(
                        current.tenantId(), scope, clean(keyword), upper(status),
                        offset, pageSize
                ),
                mapper.countAbnormalities(
                        current.tenantId(), scope, clean(keyword), upper(status)
                ),
                page,
                pageSize
        );
    }

    @Transactional
    public void handleAbnormal(long id, MaintenanceDtos.HandleAbnormalRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.AbnormalRow before = requireAbnormal(
                current.tenantId(), id, dataPermissionService.current()
        );
        if ("PENDING_VERIFY".equals(request.targetStatus())
                && clean(request.finalResult()) == null) {
            throw new BusinessException(
                    "MAINTENANCE_ABNORMAL_RESULT_REQUIRED", "提交验证前必须填写最终处理结果"
            );
        }
        if (request.responsibleUserId() != null
                && mapper.countActiveUser(current.tenantId(), request.responsibleUserId()) == 0) {
            throw new BusinessException(
                    "USER_NOT_FOUND", "责任人不存在或已停用", HttpStatus.NOT_FOUND
            );
        }
        if (mapper.handleAbnormal(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        String requestedStatus = upper(request.requestedEquipmentStatus());
        if (requestedStatus != null) {
            EquipmentDtos.EquipmentDetail equipment =
                    equipmentService.detail(before.equipmentId());
            if (!requestedStatus.equals(equipment.equipment().currentStatusCode())) {
                equipmentService.changeStatusFromBusiness(
                        before.equipmentId(),
                        new EquipmentDtos.ChangeStatusRequest(
                                requestedStatus,
                                "维保异常 " + before.abnormalCode() + " 触发设备状态联动",
                                "MAINTENANCE",
                                equipment.equipment().currentStatusVersion()
                        )
                );
            }
        }
        changeLogService.record(
                "MAINTENANCE_ABNORMAL", id, "HANDLE", before,
                mapper.findAbnormal(
                        current.tenantId(), id, DataPermission.all(current.userId())
                )
        );
    }

    @Transactional
    public void verifyAbnormal(long id, MaintenanceDtos.VerifyAbnormalRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.AbnormalRow before = requireAbnormal(
                current.tenantId(), id, dataPermissionService.current()
        );
        String target = Boolean.TRUE.equals(request.passed()) ? "CLOSED" : "PROCESSING";
        if (mapper.verifyAbnormal(
                current.tenantId(), id, request, target, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "MAINTENANCE_ABNORMAL", id, "VERIFY", before,
                mapper.findAbnormal(
                        current.tenantId(), id, DataPermission.all(current.userId())
                )
        );
    }

    @Transactional(readOnly = true)
    public MaintenanceDtos.Statistics statistics() {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.Statistics value = mapper.statistics(
                current.tenantId(), dataPermissionService.current(), LocalDate.now()
        );
        if (value != null) {
            return value;
        }
        return new MaintenanceDtos.Statistics(
                0, 0, 0, 0, 0, BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2)
        );
    }

    @Transactional
    public int markOverdue(long tenantId) {
        return mapper.markOverdueTasks(tenantId);
    }

    public List<Long> tenantIds() {
        return mapper.findTenantIds();
    }

    private void saveResult(
            CurrentUser current,
            MaintenanceDtos.TaskRow task,
            MaintenanceDtos.SaveResultRequest request
    ) {
        MaintenanceMapper.TaskItemData item =
                mapper.findTaskItem(current.tenantId(), task.id(), request.taskItemId());
        if (item == null) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_ITEM_NOT_FOUND", "任务维保项目不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        validateResult(item, request);
        MaintenanceDtos.ResultRow existing =
                mapper.findResult(current.tenantId(), request.taskItemId());
        if (existing == null) {
            mapper.insertResult(
                    current.tenantId(), task.id(), request,
                    json(request.selectedValues()), current.userId()
            );
        } else if (mapper.updateResult(
                current.tenantId(), task.id(), request,
                json(request.selectedValues()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        MaintenanceDtos.ResultRow saved =
                mapper.findResult(current.tenantId(), request.taskItemId());
        mapper.deleteResultAttachments(current.tenantId(), saved.id());
        saveAttachments(
                current, task, saved.id(), request.beforeAttachmentIds(), "BEFORE_PHOTO"
        );
        saveAttachments(
                current, task, saved.id(), request.afterAttachmentIds(), "AFTER_PHOTO"
        );
        saveAttachments(
                current, task, saved.id(), request.attachmentIds(), "RESULT_ATTACHMENT"
        );
    }

    private void saveAttachments(
            CurrentUser current,
            MaintenanceDtos.TaskRow task,
            long resultId,
            List<Long> attachmentIds,
            String attachmentType
    ) {
        for (Long attachmentId : attachmentIds == null ? List.<Long>of() : attachmentIds) {
            if (mapper.countAvailableAttachment(current.tenantId(), attachmentId) == 0) {
                throw new BusinessException(
                        "ATTACHMENT_NOT_FOUND", "附件不存在或不可用", HttpStatus.NOT_FOUND
                );
            }
            mapper.replaceResultAttachments(
                    current.tenantId(), task.id(), resultId, attachmentId,
                    attachmentType, current.userId()
            );
        }
    }

    private void validateResult(
            MaintenanceMapper.TaskItemData item,
            MaintenanceDtos.SaveResultRequest request
    ) {
        if (Boolean.TRUE.equals(request.skipped())) {
            if (!Boolean.TRUE.equals(item.skipAllowedFlag())) {
                throw new BusinessException(
                        "MAINTENANCE_RESULT_SKIP_NOT_ALLOWED", item.itemName() + " 不允许跳过"
                );
            }
            if (clean(request.skipReason()) == null) {
                throw new BusinessException(
                        "MAINTENANCE_RESULT_SKIP_REASON_REQUIRED", "跳过项目必须填写原因"
                );
            }
            return;
        }
        if (Boolean.TRUE.equals(request.abnormal())
                && clean(request.abnormalDescription()) == null) {
            throw new BusinessException(
                    "MAINTENANCE_ABNORMAL_DESCRIPTION_REQUIRED", "异常结果必须填写异常说明"
            );
        }
        String normalizedResultCode = upper(request.resultCode());
        if (normalizedResultCode != null
                && Set.of("ABNORMAL", "FAIL").contains(normalizedResultCode)
                && !Boolean.TRUE.equals(request.abnormal())) {
            throw new BusinessException(
                    "MAINTENANCE_ABNORMAL_FLAG_REQUIRED",
                    item.itemName() + " 的异常结果必须标记异常"
            );
        }
        if ("NUMBER".equals(item.resultType())) {
            if (request.numericValue() == null) {
                throw new BusinessException(
                        "MAINTENANCE_RESULT_NUMBER_REQUIRED", item.itemName() + " 必须填写数值"
                );
            }
            boolean outOfRange =
                    item.minimumValue() != null
                            && request.numericValue().compareTo(item.minimumValue()) < 0
                    || item.maximumValue() != null
                            && request.numericValue().compareTo(item.maximumValue()) > 0;
            if (outOfRange && !Boolean.TRUE.equals(request.abnormal())) {
                throw new BusinessException(
                        "MAINTENANCE_RESULT_RANGE_ABNORMAL",
                        item.itemName() + " 超出标准范围，必须标记异常"
                );
            }
        }
        if ("TEXT".equals(item.resultType()) && clean(request.textValue()) == null) {
            throw new BusinessException(
                    "MAINTENANCE_RESULT_TEXT_REQUIRED", item.itemName() + " 必须填写文本结果"
            );
        }
        if ("SINGLE_CHOICE".equals(item.resultType())
                && clean(request.selectedValue()) == null) {
            throw new BusinessException(
                    "MAINTENANCE_RESULT_CHOICE_REQUIRED", item.itemName() + " 必须选择结果"
            );
        }
        if ("MULTIPLE_CHOICE".equals(item.resultType())
                && (request.selectedValues() == null || request.selectedValues().isEmpty())) {
            throw new BusinessException(
                    "MAINTENANCE_RESULT_CHOICE_REQUIRED", item.itemName() + " 必须选择结果"
            );
        }
    }

    private void createAbnormalities(CurrentUser current, MaintenanceDtos.TaskRow task) {
        for (MaintenanceMapper.TaskItemData item : mapper.findTaskItems(
                current.tenantId(), task.id()
        )) {
            MaintenanceDtos.ResultRow result =
                    mapper.findResult(current.tenantId(), item.id());
            if (result != null && Boolean.TRUE.equals(result.abnormalFlag())) {
                String code = numberRuleService.generate(
                        current.tenantId(), current.userId(), "MAINTENANCE_ABNORMAL"
                ).businessNumber();
                mapper.insertAbnormal(
                        current.tenantId(), code, task, item, result.id(),
                        clean(result.abnormalDescription()) == null
                                ? item.itemName() + "维保异常" : result.abnormalDescription(),
                        current.userId()
                );
            }
        }
    }

    private MaintenanceDtos.TaskDetail taskDetail(long tenantId, MaintenanceDtos.TaskRow task) {
        List<MaintenanceDtos.TaskItemRow> items = mapper.findTaskItems(tenantId, task.id())
                .stream()
                .map(item -> withResult(tenantId, item))
                .toList();
        return new MaintenanceDtos.TaskDetail(
                task,
                items,
                mapper.findTaskEvents(tenantId, task.id()),
                mapper.findTaskAbnormalities(tenantId, task.id()),
                mapper.findTaskCollaborators(tenantId, task.id()),
                mapper.findTaskPauses(tenantId, task.id()),
                mapper.findTaskMaterials(tenantId, task.id())
        );
    }

    private MaintenanceDtos.TaskItemRow withResult(
            long tenantId,
            MaintenanceMapper.TaskItemData item
    ) {
        MaintenanceDtos.ResultRow result = mapper.findResult(tenantId, item.id());
        if (result != null) {
            result = new MaintenanceDtos.ResultRow(
                    result.id(), result.resultStatus(), result.resultCode(),
                    result.numericValue(), result.textValue(), result.selectedValue(),
                    result.selectedValuesJson(), result.abnormalFlag(),
                    result.abnormalDescription(), result.skippedFlag(), result.skipReason(),
                    result.executedBy(), result.executedByName(), result.executedTime(),
                    result.submittedTime(), result.version(),
                    mapper.findResultAttachmentIds(tenantId, result.id())
            );
        }
        return new MaintenanceDtos.TaskItemRow(
                item.id(), item.taskId(), item.sourceItemId(), item.itemCode(),
                item.itemName(), item.itemCategory(), item.maintenancePart(),
                item.maintenanceContent(), item.maintenanceMethod(), item.maintenanceTool(),
                item.maintenanceStandard(), item.standardValue(), item.minimumValue(),
                item.maximumValue(), item.unit(), item.resultType(), item.resultOptionsJson(),
                item.requiredFlag(), item.photoRequiredFlag(), item.attachmentRequiredFlag(),
                item.numericRequiredFlag(), item.skipAllowedFlag(),
                item.stopRequiredFlag(), item.abnormalSeverity(), item.abnormalAdvice(),
                item.standardMinutes(), item.safetyNotes(), item.sortOrder(), result
        );
    }

    private LocalDate nextOccurrence(MaintenanceMapper.GenerationPlan plan, LocalDate current) {
        return switch (plan.cycleType()) {
            case "DAILY" -> current.plusDays(plan.cycleInterval());
            case "WEEKLY" -> nextMatchingDay(
                    current, plan.weekDays(), true, plan.cycleInterval()
            );
            case "MONTHLY" -> nextMatchingDay(
                    current, plan.monthDays(), false, plan.cycleInterval()
            );
            case "QUARTERLY" -> current.plusMonths(3L * plan.cycleInterval());
            case "HALF_YEARLY" -> current.plusMonths(6L * plan.cycleInterval());
            case "YEARLY" -> current.plusYears(plan.cycleInterval());
            default -> throw new BusinessException(
                    "MAINTENANCE_PLAN_CYCLE_INVALID", "维保计划周期不正确"
            );
        };
    }

    private LocalDate nextMatchingDay(
            LocalDate current,
            String configuredDays,
            boolean weekDay,
            int interval
    ) {
        if (configuredDays == null || configuredDays.isBlank()) {
            return current.plusDays(Math.max(1, interval));
        }
        Set<Integer> days = java.util.Arrays.stream(configuredDays.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(java.util.stream.Collectors.toSet());
        LocalDate candidate = current.plusDays(1);
        int maximum = weekDay ? 7 * Math.max(1, interval) : 62 * Math.max(1, interval);
        for (int i = 0; i < maximum; i++) {
            int value = weekDay
                    ? candidate.getDayOfWeek().getValue() : candidate.getDayOfMonth();
            if (days.contains(value)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        throw new BusinessException(
                "MAINTENANCE_PLAN_SCHEDULE_INVALID", "无法计算下一次维保日期"
        );
    }

    private void assertCanExecute(CurrentUser current, MaintenanceDtos.TaskRow task) {
        if (task.assigneeUserId() != null
                && task.assigneeUserId() != current.userId()
                && mapper.countTaskCollaborator(
                current.tenantId(), task.id(), current.userId()
        ) == 0
                && !current.permissions().contains("maintenance:task:assign")) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_ASSIGNEE_ONLY", "只能由任务执行人录入结果",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private void restoreEquipmentStatus(
            CurrentUser current,
            MaintenanceDtos.TaskRow task
    ) {
        if (!Boolean.TRUE.equals(task.stopRequiredFlag())
                || !parameterService.getBoolean(
                current.tenantId(), "maintenance.restore-equipment-status", true
        )) {
            return;
        }
        MaintenanceMapper.EquipmentRuntime runtime =
                mapper.findEquipmentRuntime(current.tenantId(), task.equipmentId());
        if (runtime == null || !"STOPPED".equals(runtime.statusCode())) {
            return;
        }
        String target = clean(task.restoreStatusCode()) == null
                ? "IDLE" : task.restoreStatusCode();
        target = "RUNNING".equals(target) ? "RUNNING" : "STOPPED".equals(target) ? "STOPPED" : "IDLE";
        if (target.equals(runtime.statusCode())) {
            return;
        }
        equipmentService.changeStatusFromBusiness(
                task.equipmentId(),
                new EquipmentDtos.ChangeStatusRequest(
                        target,
                        "维保任务 " + task.taskCode() + " 结束，恢复设备状态",
                        "MAINTENANCE",
                        runtime.statusVersion()
                )
        );
    }

    private BusinessException invalidTransition(String from, String to) {
        return new BusinessException(
                "MAINTENANCE_TASK_TRANSITION_INVALID",
                "不允许从 " + from + " 切换到 " + to,
                HttpStatus.CONFLICT
        );
    }

    private MaintenanceDtos.TaskRow requireTask(long tenantId, long id, DataPermission scope) {
        MaintenanceDtos.TaskRow task = mapper.findTask(tenantId, id, scope);
        if (task == null) {
            throw new BusinessException(
                    "MAINTENANCE_TASK_NOT_FOUND", "维保任务不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return task;
    }

    private MaintenanceDtos.AbnormalRow requireAbnormal(
            long tenantId,
            long id,
            DataPermission scope
    ) {
        MaintenanceDtos.AbnormalRow abnormal = mapper.findAbnormal(tenantId, id, scope);
        if (abnormal == null) {
            throw new BusinessException(
                    "MAINTENANCE_ABNORMAL_NOT_FOUND", "维保异常不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return abnormal;
    }

    private int parseLookahead(long tenantId) {
        String value = parameterService.getString(
                tenantId, "maintenance.generation.lookahead-days", "7"
        );
        try {
            return Math.max(0, Math.min(90, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    "MAINTENANCE_LOOKAHEAD_INVALID", "维保任务提前生成天数配置无效"
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "MAINTENANCE_JSON_INVALID", "维保结果序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT", "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }
}
