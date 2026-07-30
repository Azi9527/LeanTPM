package com.leantpm.inspection;

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
public class InspectionTaskService {
    private static final Set<String> EDITABLE_STATUSES =
            Set.of("PENDING", "IN_PROGRESS", "OVERDUE");

    private final InspectionMapper mapper;
    private final NumberRuleService numberRuleService;
    private final ParameterService parameterService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;
    private final EquipmentService equipmentService;

    public InspectionTaskService(
            InspectionMapper mapper,
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
    public PageResult<InspectionDtos.TaskRow> tasks(
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
    public InspectionDtos.TaskDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        return taskDetail(current.tenantId(), task);
    }

    @Transactional
    public InspectionDtos.GenerationResult generateDueTasks() {
        var current = SecurityUtils.currentUser();
        return generateScheduled(current.tenantId(), current.userId());
    }

    @Transactional
    public InspectionDtos.GenerationResult generateScheduled(long tenantId, long operatorId) {
        int lookahead = parseLookahead(tenantId);
        return generate(tenantId, operatorId, LocalDate.now().plusDays(lookahead));
    }

    @Transactional
    public InspectionDtos.GenerationResult generate(
            long tenantId,
            long operatorId,
            LocalDate throughDate
    ) {
        List<InspectionMapper.GenerationPlan> plans =
                mapper.findGenerationPlans(tenantId, throughDate);
        List<String> taskCodes = new ArrayList<>();
        int generated = 0;
        int skipped = 0;
        for (InspectionMapper.GenerationPlan plan : plans) {
            LocalDate occurrence = plan.nextGenerationDate();
            while (!occurrence.isAfter(throughDate)) {
                if (plan.expiryDate() != null && occurrence.isAfter(plan.expiryDate())) {
                    break;
                }
                if (occurrence.isBefore(plan.effectiveDate())) {
                    occurrence = nextOccurrence(plan, occurrence);
                    continue;
                }
                String occurrenceKey = occurrence.toString();
                Long existing = mapper.findTaskIdByOccurrence(tenantId, plan.id(), occurrenceKey);
                if (existing == null) {
                    InspectionMapper.EquipmentSnapshot equipment =
                            mapper.findEquipmentSnapshot(
                                    tenantId, plan.equipmentId(), DataPermission.all(operatorId)
                            );
                    if (equipment == null) {
                        skipped++;
                    } else {
                        String code = numberRuleService.generate(
                                tenantId, operatorId, "INSPECTION_TASK"
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
                            mapper.insertTaskEvent(
                                    tenantId, taskId, "GENERATED", null, "PENDING",
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
                occurrence = nextOccurrence(plan, occurrence);
                mapper.updatePlanGeneration(
                        tenantId, plan.id(), completedOccurrence, occurrence, operatorId
                );
            }
        }
        return new InspectionDtos.GenerationResult(
                plans.size(), generated, skipped, List.copyOf(taskCodes)
        );
    }

    @Transactional
    public long createManualTask(InspectionDtos.ManualTaskRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        InspectionDtos.SchemeVersionRow version =
                mapper.findSchemeVersion(current.tenantId(), request.schemeVersionId());
        if (version == null || !"PUBLISHED".equals(version.versionStatus())) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_NOT_PUBLISHED", "只能按已发布方案创建任务"
            );
        }
        if (Boolean.TRUE.equals(request.backfill())
                && !Boolean.TRUE.equals(version.backfillAllowedFlag())) {
            throw new BusinessException(
                    "INSPECTION_BACKFILL_NOT_ALLOWED", "该方案不允许补录任务"
            );
        }
        if (request.dueTime().isBefore(
                request.plannedStartTime() == null
                        ? request.plannedDate().atStartOfDay()
                        : request.plannedStartTime()
        )) {
            throw new BusinessException(
                    "INSPECTION_TASK_DUE_INVALID", "截止时间不能早于计划开始时间"
            );
        }
        InspectionDtos.SchemeRow scheme =
                mapper.findScheme(current.tenantId(), version.schemeId());
        if (scheme == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NOT_FOUND", "点检方案不存在", HttpStatus.NOT_FOUND
            );
        }
        InspectionMapper.EquipmentSnapshot equipment = mapper.findEquipmentSnapshot(
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
                current.tenantId(), current.userId(), "INSPECTION_TASK"
        ).businessNumber();
        mapper.insertManualTask(
                current.tenantId(), code, scheme, version, equipment, request, current.userId()
        );
        Long taskId = mapper.findTaskIdByCode(current.tenantId(), code);
        if (taskId == null) {
            throw new BusinessException(
                    "INSPECTION_TASK_CREATE_FAILED", "点检任务创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        mapper.copyTaskItems(current.tenantId(), taskId, version.id());
        mapper.insertTaskEvent(
                current.tenantId(), taskId, "CREATED", null, "PENDING",
                clean(request.remark()), current.userId()
        );
        changeLogService.record(
                "INSPECTION_TASK", taskId, "CREATE", null,
                mapper.findTask(current.tenantId(), taskId, DataPermission.all(current.userId()))
        );
        return taskId;
    }

    @Transactional
    public void assign(long id, InspectionDtos.AssignTaskRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.TaskRow before = requireTask(
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
        mapper.insertTaskEvent(
                current.tenantId(), id, "ASSIGNED", before.taskStatus(), before.taskStatus(),
                "任务已派给用户 " + request.assigneeUserId(), current.userId()
        );
        changeLogService.record(
                "INSPECTION_TASK", id, "ASSIGN", before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void saveDraft(long id, InspectionDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        InspectionDtos.TaskRow task = requireTask(current.tenantId(), id, scope);
        assertCanExecute(current, task);
        if (!EDITABLE_STATUSES.contains(task.taskStatus())) {
            throw new BusinessException(
                    "INSPECTION_TASK_NOT_EDITABLE", "当前任务状态不允许录入结果",
                    HttpStatus.CONFLICT
            );
        }
        if (task.version() != request.taskVersion()) {
            throw optimisticConflict();
        }
        for (InspectionDtos.SaveResultRequest resultRequest : request.results()) {
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
                "保存点检草稿", current.userId()
        );
    }

    @Transactional
    public void submit(long id, InspectionDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        saveDraft(id, request);
        InspectionDtos.TaskRow task = requireTask(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        if (mapper.countMissingRequiredResults(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "INSPECTION_RESULT_REQUIRED_MISSING", "必填点检项目尚未完整填写"
            );
        }
        if (mapper.countInvalidResultAttachments(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "INSPECTION_RESULT_PHOTO_REQUIRED", "存在未上传必需照片的点检项目"
            );
        }
        mapper.submitResults(current.tenantId(), id);
        createAbnormalities(current, task);
        String targetStatus = Boolean.TRUE.equals(task.reviewRequiredFlag())
                ? "PENDING_REVIEW" : "COMPLETED";
        if (mapper.updateTaskStatus(
                current.tenantId(), id, task.version(), "IN_PROGRESS", targetStatus,
                clean(request.executionRemark()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id, "SUBMITTED", "IN_PROGRESS", targetStatus,
                "提交点检结果", current.userId()
        );
        changeLogService.record(
                "INSPECTION_TASK", id, "SUBMIT", task,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void review(long id, InspectionDtos.ReviewTaskRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.TaskRow before = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (!"PENDING_REVIEW".equals(before.taskStatus())) {
            throw new BusinessException(
                    "INSPECTION_TASK_NOT_REVIEWABLE", "当前任务状态不允许复核",
                    HttpStatus.CONFLICT
            );
        }
        if (!Boolean.TRUE.equals(request.approved()) && clean(request.comment()) == null) {
            throw new BusinessException(
                    "INSPECTION_REVIEW_COMMENT_REQUIRED", "驳回时必须填写原因"
            );
        }
        String target = Boolean.TRUE.equals(request.approved()) ? "COMPLETED" : "IN_PROGRESS";
        if (mapper.reviewTask(
                current.tenantId(), id, request, target, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTaskEvent(
                current.tenantId(), id,
                Boolean.TRUE.equals(request.approved()) ? "REVIEW_APPROVED" : "REVIEW_REJECTED",
                "PENDING_REVIEW", target, clean(request.comment()), current.userId()
        );
        changeLogService.record(
                "INSPECTION_TASK", id, "REVIEW", before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void close(long id, String targetStatus, InspectionDtos.CloseTaskRequest request) {
        var current = SecurityUtils.currentUser();
        String target = upper(targetStatus);
        if (!Set.of("CANCELLED", "VOIDED").contains(target)) {
            throw new BusinessException("INSPECTION_TASK_CLOSE_STATUS_INVALID", "任务关闭状态不正确");
        }
        InspectionDtos.TaskRow before = requireTask(
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
        changeLogService.record(
                "INSPECTION_TASK", id, target, before,
                mapper.findTask(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.AbnormalRow> abnormalities(
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
    public void handleAbnormal(long id, InspectionDtos.HandleAbnormalRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.AbnormalRow before = requireAbnormal(
                current.tenantId(), id, dataPermissionService.current()
        );
        if ("PENDING_VERIFY".equals(request.targetStatus())
                && clean(request.finalResult()) == null) {
            throw new BusinessException(
                    "INSPECTION_ABNORMAL_RESULT_REQUIRED", "提交验证前必须填写最终处理结果"
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
                equipmentService.changeStatus(
                        before.equipmentId(),
                        new EquipmentDtos.ChangeStatusRequest(
                                requestedStatus,
                                "点检异常 " + before.abnormalCode() + " 触发设备状态联动",
                                "INSPECTION",
                                equipment.equipment().currentStatusVersion()
                        )
                );
            }
        }
        changeLogService.record(
                "INSPECTION_ABNORMAL", id, "HANDLE", before,
                mapper.findAbnormal(
                        current.tenantId(), id, DataPermission.all(current.userId())
                )
        );
    }

    @Transactional
    public void verifyAbnormal(long id, InspectionDtos.VerifyAbnormalRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.AbnormalRow before = requireAbnormal(
                current.tenantId(), id, dataPermissionService.current()
        );
        String target = Boolean.TRUE.equals(request.passed()) ? "CLOSED" : "PROCESSING";
        if (mapper.verifyAbnormal(
                current.tenantId(), id, request, target, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_ABNORMAL", id, "VERIFY", before,
                mapper.findAbnormal(
                        current.tenantId(), id, DataPermission.all(current.userId())
                )
        );
    }

    @Transactional(readOnly = true)
    public InspectionDtos.Statistics statistics() {
        var current = SecurityUtils.currentUser();
        InspectionDtos.Statistics value = mapper.statistics(
                current.tenantId(), dataPermissionService.current(), LocalDate.now()
        );
        if (value != null) {
            return value;
        }
        return new InspectionDtos.Statistics(
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
            InspectionDtos.TaskRow task,
            InspectionDtos.SaveResultRequest request
    ) {
        InspectionMapper.TaskItemData item =
                mapper.findTaskItem(current.tenantId(), task.id(), request.taskItemId());
        if (item == null) {
            throw new BusinessException(
                    "INSPECTION_TASK_ITEM_NOT_FOUND", "任务点检项目不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        validateResult(item, request);
        InspectionDtos.ResultRow existing =
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
        InspectionDtos.ResultRow saved =
                mapper.findResult(current.tenantId(), request.taskItemId());
        mapper.deleteResultAttachments(current.tenantId(), saved.id());
        String attachmentType = "IMAGE".equals(item.resultType())
                || Boolean.TRUE.equals(item.photoRequiredFlag())
                ? "RESULT_PHOTO" : "RESULT_ATTACHMENT";
        for (Long attachmentId : request.attachmentIds() == null
                ? List.<Long>of() : request.attachmentIds()) {
            if (mapper.countAvailableAttachment(current.tenantId(), attachmentId) == 0) {
                throw new BusinessException(
                        "ATTACHMENT_NOT_FOUND", "附件不存在或不可用", HttpStatus.NOT_FOUND
                );
            }
            mapper.replaceResultAttachments(
                    current.tenantId(), task.id(), saved.id(), attachmentId,
                    attachmentType, current.userId()
            );
        }
    }

    private void validateResult(
            InspectionMapper.TaskItemData item,
            InspectionDtos.SaveResultRequest request
    ) {
        if (Boolean.TRUE.equals(request.skipped())) {
            if (!Boolean.TRUE.equals(item.skipAllowedFlag())) {
                throw new BusinessException(
                        "INSPECTION_RESULT_SKIP_NOT_ALLOWED", item.itemName() + " 不允许跳过"
                );
            }
            if (clean(request.skipReason()) == null) {
                throw new BusinessException(
                        "INSPECTION_RESULT_SKIP_REASON_REQUIRED", "跳过项目必须填写原因"
                );
            }
            return;
        }
        if (Boolean.TRUE.equals(request.abnormal())
                && clean(request.abnormalDescription()) == null) {
            throw new BusinessException(
                    "INSPECTION_ABNORMAL_DESCRIPTION_REQUIRED", "异常结果必须填写异常说明"
            );
        }
        String normalizedResultCode = upper(request.resultCode());
        if (normalizedResultCode != null
                && Set.of("ABNORMAL", "FAIL").contains(normalizedResultCode)
                && !Boolean.TRUE.equals(request.abnormal())) {
            throw new BusinessException(
                    "INSPECTION_ABNORMAL_FLAG_REQUIRED",
                    item.itemName() + " 的异常结果必须标记异常"
            );
        }
        if ("NUMBER".equals(item.resultType())) {
            if (request.numericValue() == null) {
                throw new BusinessException(
                        "INSPECTION_RESULT_NUMBER_REQUIRED", item.itemName() + " 必须填写数值"
                );
            }
            boolean outOfRange =
                    item.minimumValue() != null
                            && request.numericValue().compareTo(item.minimumValue()) < 0
                    || item.maximumValue() != null
                            && request.numericValue().compareTo(item.maximumValue()) > 0;
            if (outOfRange && !Boolean.TRUE.equals(request.abnormal())) {
                throw new BusinessException(
                        "INSPECTION_RESULT_RANGE_ABNORMAL",
                        item.itemName() + " 超出标准范围，必须标记异常"
                );
            }
        }
        if ("TEXT".equals(item.resultType()) && clean(request.textValue()) == null) {
            throw new BusinessException(
                    "INSPECTION_RESULT_TEXT_REQUIRED", item.itemName() + " 必须填写文本结果"
            );
        }
        if ("SINGLE_CHOICE".equals(item.resultType())
                && clean(request.selectedValue()) == null) {
            throw new BusinessException(
                    "INSPECTION_RESULT_CHOICE_REQUIRED", item.itemName() + " 必须选择结果"
            );
        }
        if ("MULTIPLE_CHOICE".equals(item.resultType())
                && (request.selectedValues() == null || request.selectedValues().isEmpty())) {
            throw new BusinessException(
                    "INSPECTION_RESULT_CHOICE_REQUIRED", item.itemName() + " 必须选择结果"
            );
        }
    }

    private void createAbnormalities(CurrentUser current, InspectionDtos.TaskRow task) {
        for (InspectionMapper.TaskItemData item : mapper.findTaskItems(
                current.tenantId(), task.id()
        )) {
            InspectionDtos.ResultRow result =
                    mapper.findResult(current.tenantId(), item.id());
            if (result != null && Boolean.TRUE.equals(result.abnormalFlag())) {
                String code = numberRuleService.generate(
                        current.tenantId(), current.userId(), "INSPECTION_ABNORMAL"
                ).businessNumber();
                mapper.insertAbnormal(
                        current.tenantId(), code, task, item, result.id(),
                        clean(result.abnormalDescription()) == null
                                ? item.itemName() + "点检异常" : result.abnormalDescription(),
                        current.userId()
                );
            }
        }
    }

    private InspectionDtos.TaskDetail taskDetail(long tenantId, InspectionDtos.TaskRow task) {
        List<InspectionDtos.TaskItemRow> items = mapper.findTaskItems(tenantId, task.id())
                .stream()
                .map(item -> withResult(tenantId, item))
                .toList();
        return new InspectionDtos.TaskDetail(
                task,
                items,
                mapper.findTaskEvents(tenantId, task.id()),
                mapper.findTaskAbnormalities(tenantId, task.id())
        );
    }

    private InspectionDtos.TaskItemRow withResult(
            long tenantId,
            InspectionMapper.TaskItemData item
    ) {
        InspectionDtos.ResultRow result = mapper.findResult(tenantId, item.id());
        if (result != null) {
            result = new InspectionDtos.ResultRow(
                    result.id(), result.resultStatus(), result.resultCode(),
                    result.numericValue(), result.textValue(), result.selectedValue(),
                    result.selectedValuesJson(), result.abnormalFlag(),
                    result.abnormalDescription(), result.skippedFlag(), result.skipReason(),
                    result.executedBy(), result.executedByName(), result.executedTime(),
                    result.submittedTime(), result.version(),
                    mapper.findResultAttachmentIds(tenantId, result.id())
            );
        }
        return new InspectionDtos.TaskItemRow(
                item.id(), item.taskId(), item.sourceItemId(), item.itemCode(),
                item.itemName(), item.itemCategory(), item.inspectionPart(),
                item.inspectionContent(), item.inspectionMethod(), item.inspectionTool(),
                item.inspectionStandard(), item.standardValue(), item.minimumValue(),
                item.maximumValue(), item.unit(), item.resultType(), item.resultOptionsJson(),
                item.requiredFlag(), item.photoRequiredFlag(), item.numericRequiredFlag(),
                item.skipAllowedFlag(), item.abnormalSeverity(), item.abnormalAdvice(),
                item.standardMinutes(), item.safetyNotes(), item.sortOrder(), result
        );
    }

    private LocalDate nextOccurrence(InspectionMapper.GenerationPlan plan, LocalDate current) {
        return switch (plan.cycleType()) {
            case "DAILY", "INTERVAL_DAYS" -> current.plusDays(plan.cycleInterval());
            case "WEEKLY" -> nextMatchingDay(
                    current, plan.weekDays(), true, plan.cycleInterval()
            );
            case "MONTHLY" -> nextMatchingDay(
                    current, plan.monthDays(), false, plan.cycleInterval()
            );
            default -> throw new BusinessException(
                    "INSPECTION_PLAN_CYCLE_INVALID", "点检计划周期不正确"
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
                "INSPECTION_PLAN_SCHEDULE_INVALID", "无法计算下一次点检日期"
        );
    }

    private void assertCanExecute(CurrentUser current, InspectionDtos.TaskRow task) {
        if (task.assigneeUserId() != null
                && task.assigneeUserId() != current.userId()
                && !current.permissions().contains("inspection:task:assign")) {
            throw new BusinessException(
                    "INSPECTION_TASK_ASSIGNEE_ONLY", "只能由任务执行人录入结果",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private InspectionDtos.TaskRow requireTask(long tenantId, long id, DataPermission scope) {
        InspectionDtos.TaskRow task = mapper.findTask(tenantId, id, scope);
        if (task == null) {
            throw new BusinessException(
                    "INSPECTION_TASK_NOT_FOUND", "点检任务不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return task;
    }

    private InspectionDtos.AbnormalRow requireAbnormal(
            long tenantId,
            long id,
            DataPermission scope
    ) {
        InspectionDtos.AbnormalRow abnormal = mapper.findAbnormal(tenantId, id, scope);
        if (abnormal == null) {
            throw new BusinessException(
                    "INSPECTION_ABNORMAL_NOT_FOUND", "点检异常不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return abnormal;
    }

    private int parseLookahead(long tenantId) {
        String value = parameterService.getString(
                tenantId, "inspection.generation.lookahead-days", "7"
        );
        try {
            return Math.max(0, Math.min(90, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    "INSPECTION_LOOKAHEAD_INVALID", "点检任务提前生成天数配置无效"
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "INSPECTION_JSON_INVALID", "点检结果序列化失败",
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
