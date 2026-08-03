package com.leantpm.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.attachment.AttachmentService;
import com.leantpm.system.audit.ChangeLogService;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class InspectionTaskService {
    private static final int EXPORT_TASK_LIMIT = 5_000;
    private static final int EXPORT_RESULT_LIMIT = 100_000;
    private static final Set<String> EDITABLE_STATUSES =
            Set.of("PENDING", "IN_PROGRESS", "OVERDUE");
    private static final Set<String> TIME_FIELDS =
            Set.of("PLANNED_DATE", "STARTED_TIME", "SUBMITTED_TIME", "COMPLETED_TIME");
    private static final Set<String> ABNORMAL_SEVERITIES =
            Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final InspectionMapper mapper;
    private final InspectionCalendarMapper calendarMapper;
    private final NumberRuleService numberRuleService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;
    private final EquipmentService equipmentService;
    private final AttachmentService attachmentService;

    public InspectionTaskService(
            InspectionMapper mapper,
            InspectionCalendarMapper calendarMapper,
            NumberRuleService numberRuleService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper,
            EquipmentService equipmentService,
            AttachmentService attachmentService
    ) {
        this.mapper = mapper;
        this.calendarMapper = calendarMapper;
        this.numberRuleService = numberRuleService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
        this.equipmentService = equipmentService;
        this.attachmentService = attachmentService;
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
        return tasks(new InspectionDtos.TaskQuery(
                keyword, taskStatus, plannedDate, "PLANNED_DATE", null, null,
                null, null, null, null, null, false, null, mineOnly
        ), page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.TaskRow> tasks(
            InspectionDtos.TaskQuery requestedQuery,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        InspectionDtos.TaskQuery query = normalizeTaskQuery(requestedQuery);
        return PageResult.of(
                mapper.findTasks(
                        current.tenantId(), scope, query, offset, pageSize
                ),
                mapper.countTasks(
                        current.tenantId(), scope, query
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportResults(InspectionDtos.TaskQuery requestedQuery) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        InspectionDtos.TaskQuery query = normalizeTaskQuery(requestedQuery);
        long taskCount = mapper.countTasks(current.tenantId(), scope, query);
        if (taskCount > EXPORT_TASK_LIMIT) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_TOO_LARGE",
                    "点检任务超过 " + EXPORT_TASK_LIMIT + " 条，请缩小筛选范围"
            );
        }
        List<InspectionDtos.TaskRow> tasks = mapper.findTasks(
                current.tenantId(), scope, query, 0, EXPORT_TASK_LIMIT
        );
        List<InspectionDtos.TaskResultExportRow> results = mapper.findTaskResultExportRows(
                current.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
        );
        if (results.size() > EXPORT_RESULT_LIMIT) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_DETAIL_TOO_LARGE",
                    "点检明细达到 " + EXPORT_RESULT_LIMIT + " 条，请缩小筛选范围"
            );
        }
        List<InspectionDtos.TaskAbnormalExportRow> abnormalities =
                mapper.findTaskAbnormalExportRows(
                        current.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
                );
        List<InspectionDtos.TaskAttachmentExportRow> attachments =
                mapper.findTaskAttachmentExportRows(
                        current.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
                );
        if (abnormalities.size() > EXPORT_RESULT_LIMIT
                || attachments.size() > EXPORT_RESULT_LIMIT) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_RELATION_TOO_LARGE",
                    "点检异常或附件索引超过 " + EXPORT_RESULT_LIMIT + " 条，请缩小筛选范围"
            );
        }
        return exportWorkbook(tasks, results, abnormalities, attachments);
    }

    @Transactional(readOnly = true)
    public InspectionDtos.TaskDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        return taskDetail(current.tenantId(), task);
    }

    @Transactional(readOnly = true)
    public List<InspectionDtos.InspectionAttachmentRow> taskAttachments(long id) {
        var current = SecurityUtils.currentUser();
        requireTask(current.tenantId(), id, dataPermissionService.current());
        return mapper.findTaskAttachments(current.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public AttachmentService.DownloadedAttachment taskAttachmentContent(
            long taskId,
            long attachmentId
    ) {
        var current = SecurityUtils.currentUser();
        requireTask(current.tenantId(), taskId, dataPermissionService.current());
        if (mapper.countTaskAttachment(current.tenantId(), taskId, attachmentId) == 0) {
            throw new BusinessException(
                    "INSPECTION_ATTACHMENT_NOT_FOUND",
                    "点检任务附件不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return attachmentService.load(attachmentId);
    }

    @Transactional(readOnly = true)
    public List<InspectionDtos.InspectionAttachmentRow> abnormalAttachments(long id) {
        var current = SecurityUtils.currentUser();
        requireAbnormal(current.tenantId(), id, dataPermissionService.current());
        return mapper.findAbnormalAttachments(current.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public AttachmentService.DownloadedAttachment abnormalAttachmentContent(
            long abnormalId,
            long attachmentId
    ) {
        var current = SecurityUtils.currentUser();
        requireAbnormal(current.tenantId(), abnormalId, dataPermissionService.current());
        if (mapper.countAbnormalAttachment(
                current.tenantId(), abnormalId, attachmentId
        ) == 0) {
            throw new BusinessException(
                    "INSPECTION_ATTACHMENT_NOT_FOUND",
                    "点检异常附件不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return attachmentService.load(attachmentId);
    }

    @Transactional
    public InspectionDtos.GenerationResult generateDueTasks() {
        var current = SecurityUtils.currentUser();
        return generateScheduled(current.tenantId(), current.userId());
    }

    @Transactional
    public InspectionDtos.GenerationResult generateScheduled(long tenantId, long operatorId) {
        return generateReady(tenantId, operatorId, LocalDateTime.now());
    }

    @Transactional
    public InspectionDtos.GenerationResult generate(
            long tenantId,
            long operatorId,
            LocalDate throughDate
    ) {
        return generateReady(tenantId, operatorId, throughDate.atTime(23, 59, 59));
    }

    public InspectionDtos.GenerationResult generateReady(
            long tenantId,
            long operatorId,
            LocalDateTime cutoff
    ) {
        List<InspectionMapper.GenerationPlan> plans =
                mapper.findGenerationPlans(tenantId, cutoff.toLocalDate().plusDays(30));
        List<String> taskCodes = new ArrayList<>();
        int generated = 0;
        int skipped = 0;
        for (InspectionMapper.GenerationPlan plan : plans) {
            LocalDate occurrence = plan.nextGenerationDate();
            while (true) {
                if (plan.expiryDate() != null && occurrence.isAfter(plan.expiryDate())) {
                    break;
                }
                LocalDateTime start = occurrence.atTime(
                        plan.scheduledTime() == null ? LocalTime.of(8, 0)
                                : plan.scheduledTime()
                );
                if (start.minusMinutes(plan.generationLeadMinutes()).isAfter(cutoff)) {
                    break;
                }
                if (occurrence.isBefore(plan.effectiveDate())) {
                    LocalDate completedOccurrence = occurrence;
                    occurrence = nextOccurrence(plan, occurrence);
                    mapper.updatePlanGeneration(
                            tenantId, plan.id(), completedOccurrence, occurrence, operatorId
                    );
                    continue;
                }
                if (!isWorkday(tenantId, plan, occurrence)
                        || (occurrence.isBefore(cutoff.toLocalDate())
                        && !plan.backfillAllowed())) {
                    skipped++;
                    LocalDate completedOccurrence = occurrence;
                    occurrence = nextOccurrence(plan, occurrence);
                    mapper.updatePlanGeneration(
                            tenantId, plan.id(), completedOccurrence, occurrence, operatorId
                    );
                    continue;
                }
                String occurrenceKey = start.toString();
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
                            if (plan.assigneeUserId() != null) {
                                mapper.insertTaskAssignee(
                                        tenantId, taskId, plan.assigneeUserId(),
                                        true, 0, operatorId
                                );
                            }
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
        return createManualTask(request, null);
    }

    @Transactional
    public long createManualTask(
            InspectionDtos.ManualTaskRequest request,
            String idempotencyKey
    ) {
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
        boolean applicable = mapper.findApplicableEquipment(
                current.tenantId(), version.id(), scope
        ).stream().anyMatch(item -> item.id() == request.equipmentId());
        if (!applicable) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NOT_APPLICABLE",
                    "所选点检方案不适用于该设备",
                    HttpStatus.CONFLICT
            );
        }
        List<Long> assigneeUserIds = normalizeAssigneeUserIds(request.assigneeUserIds());
        validateAssigneeUsers(current.tenantId(), assigneeUserIds);
        Long primaryAssigneeUserId = assigneeUserIds.isEmpty()
                ? null : assigneeUserIds.getFirst();
        String code = numberRuleService.generate(
                current.tenantId(), current.userId(), "INSPECTION_TASK"
        ).businessNumber();
        String normalizedKey = clean(idempotencyKey);
        String requestHash = requestHash(request);
        int inserted = mapper.insertManualTask(
                current.tenantId(), code, scheme, version, equipment, request,
                primaryAssigneeUserId, normalizedKey, requestHash, current.userId()
        );
        if (inserted == 0 && normalizedKey != null) {
            InspectionMapper.ManualTaskIdentity existing =
                    mapper.findManualTaskByIdempotencyKey(
                            current.tenantId(), normalizedKey
                    );
            if (existing != null && requestHash.equals(existing.requestHash())) {
                return existing.id();
            }
            throw new BusinessException(
                    "INSPECTION_TASK_IDEMPOTENCY_CONFLICT",
                    "同一幂等键不能用于不同的点检任务创建请求",
                    HttpStatus.CONFLICT
            );
        }
        Long taskId = mapper.findTaskIdByCode(current.tenantId(), code);
        if (taskId == null) {
            throw new BusinessException(
                    "INSPECTION_TASK_CREATE_FAILED", "点检任务创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        replaceTaskAssignees(
                current.tenantId(), taskId, assigneeUserIds, current.userId()
        );
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
        List<Long> assigneeUserIds =
                normalizeAssigneeUserIds(request.assigneeUserIds());
        if (assigneeUserIds.isEmpty()) {
            throw new BusinessException(
                    "INSPECTION_ASSIGNEE_REQUIRED", "请至少选择一名执行人"
            );
        }
        validateAssigneeUsers(current.tenantId(), assigneeUserIds);
        if (mapper.assignTask(
                current.tenantId(), id, assigneeUserIds.getFirst(),
                clean(request.teamCode()), request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        replaceTaskAssignees(
                current.tenantId(), id, assigneeUserIds, current.userId()
        );
        InspectionDtos.TaskRow updated = mapper.findTask(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        mapper.insertTaskEvent(
                current.tenantId(), id, "ASSIGNED", before.taskStatus(), before.taskStatus(),
                "任务已派给：" + updated.assigneeName(), current.userId()
        );
        changeLogService.record(
                "INSPECTION_TASK", id, "ASSIGN", before, updated
        );
    }

    @Transactional
    public void saveDraft(long id, InspectionDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        InspectionDtos.TaskRow task = requireTask(current.tenantId(), id, scope);
        assertCanExecute(current, task);
        persistDraft(current, task, request, true);
    }

    @Transactional
    public void submit(long id, InspectionDtos.SaveTaskResultsRequest request) {
        CurrentUser current = SecurityUtils.currentUser();
        InspectionDtos.TaskRow authorizedTask = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        assertCanExecute(current, authorizedTask);
        InspectionMapper.SubmissionState state = mapper.lockSubmission(
                current.tenantId(), id
        );
        if (state == null) {
            throw new BusinessException(
                    "INSPECTION_TASK_NOT_FOUND", "点检任务不存在", HttpStatus.NOT_FOUND
            );
        }
        if (state.submittedTime() != null
                || Set.of("PENDING_REVIEW", "COMPLETED").contains(state.taskStatus())) {
            throw alreadySubmitted(state);
        }
        InspectionDtos.TaskRow task = requireTask(
                current.tenantId(), id, dataPermissionService.current()
        );
        persistDraft(current, task, request, false);
        task = requireTask(
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
        applyAbnormalStop(current, task);
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

    private void persistDraft(
            CurrentUser current,
            InspectionDtos.TaskRow task,
            InspectionDtos.SaveTaskResultsRequest request,
            boolean recordDraftEvent
    ) {
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
                current.tenantId(), task.id(), task.version(),
                clean(request.executionRemark()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        if (recordDraftEvent) {
            mapper.insertTaskEvent(
                    current.tenantId(), task.id(), "DRAFT_SAVED",
                    task.taskStatus(), "IN_PROGRESS", "保存点检草稿", current.userId()
            );
        }
    }

    private BusinessException alreadySubmitted(InspectionMapper.SubmissionState state) {
        String submitter = state.submittedByName() == null
                ? "其他执行人" : state.submittedByName();
        String time = state.submittedTime() == null
                ? "此前"
                : state.submittedTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                );
        return new BusinessException(
                "INSPECTION_TASK_ALREADY_SUBMITTED",
                "任务已由" + submitter + "于" + time + "提交完成，当前结果未被覆盖",
                HttpStatus.CONFLICT
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
                equipmentService.changeStatusFromBusiness(
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
        InspectionDtos.SaveResultRequest normalized = normalizeStopDecision(item, request);
        validateResult(item, normalized);
        InspectionDtos.ResultRow existing =
                mapper.findResult(current.tenantId(), request.taskItemId());
        if (existing == null) {
            mapper.insertResult(
                    current.tenantId(), task.id(), normalized,
                    json(normalized.selectedValues()), current.userId()
            );
        } else if (mapper.updateResult(
                current.tenantId(), task.id(), normalized,
                json(normalized.selectedValues()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        InspectionDtos.ResultRow saved =
                mapper.findResult(current.tenantId(), request.taskItemId());
        mapper.deleteResultAttachments(current.tenantId(), saved.id());
        String attachmentType = "IMAGE".equals(item.resultType())
                || Boolean.TRUE.equals(item.photoRequiredFlag())
                ? "RESULT_PHOTO" : "RESULT_ATTACHMENT";
        for (Long attachmentId : normalized.attachmentIds() == null
                ? List.<Long>of() : normalized.attachmentIds()) {
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

    private InspectionDtos.SaveResultRequest normalizeStopDecision(
            InspectionMapper.TaskItemData item,
            InspectionDtos.SaveResultRequest request
    ) {
        boolean abnormal = Boolean.TRUE.equals(request.abnormal())
                && !Boolean.TRUE.equals(request.skipped());
        if (!abnormal) {
            return new InspectionDtos.SaveResultRequest(
                    request.taskItemId(), request.resultCode(), request.numericValue(),
                    request.textValue(), request.selectedValue(), request.selectedValues(),
                    request.abnormal(), request.abnormalDescription(), false, null,
                    request.skipped(), request.skipReason(), request.attachmentIds(),
                    request.version()
            );
        }
        boolean defaultStop = Boolean.TRUE.equals(item.abnormalDefaultStopFlag());
        boolean actualStop = request.equipmentStopRequired() == null
                ? defaultStop : request.equipmentStopRequired();
        String overrideReason = clean(request.stopOverrideReason());
        if (actualStop != defaultStop && overrideReason == null) {
            throw new BusinessException(
                    "INSPECTION_STOP_OVERRIDE_REASON_REQUIRED",
                    "异常停机选择与项目默认规则不一致时，必须填写调整原因"
            );
        }
        return new InspectionDtos.SaveResultRequest(
                request.taskItemId(), request.resultCode(), request.numericValue(),
                request.textValue(), request.selectedValue(), request.selectedValues(),
                request.abnormal(), request.abnormalDescription(), actualStop,
                actualStop == defaultStop ? null : overrideReason,
                request.skipped(), request.skipReason(), request.attachmentIds(),
                request.version()
        );
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
                        result.equipmentStopRequired(),
                        current.userId()
                );
            }
        }
    }

    private void applyAbnormalStop(CurrentUser current, InspectionDtos.TaskRow task) {
        if (mapper.countStopRequiredResults(current.tenantId(), task.id()) == 0) {
            return;
        }
        InspectionMapper.EquipmentStatusData equipment = mapper.findEquipmentStatus(
                current.tenantId(), task.equipmentId()
        );
        if (equipment == null) {
            throw new BusinessException(
                    "CURRENT_STATUS_NOT_FOUND", "设备当前状态不存在", HttpStatus.NOT_FOUND
            );
        }
        String reasonDetail = clean(mapper.findStopReason(current.tenantId(), task.id()));
        String reason = "点检任务 " + task.taskCode() + " 异常要求停机"
                + (reasonDetail == null ? "" : "：" + reasonDetail);
        if (!"STOPPED".equals(equipment.statusCode())) {
            equipmentService.changeStatusFromBusiness(
                    task.equipmentId(),
                    new EquipmentDtos.ChangeStatusRequest(
                            "STOPPED", reason, "INSPECTION",
                            equipment.version()
                    )
            );
        }
        mapper.markAbnormalEquipmentStatusChanged(
                current.tenantId(), task.id(), current.userId()
        );
        mapper.insertTaskEvent(
                current.tenantId(), task.id(), "EQUIPMENT_STOPPED",
                task.taskStatus(), task.taskStatus(), reason, current.userId()
        );
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
                    result.abnormalDescription(), result.equipmentStopRequired(),
                    result.stopOverrideReason(), result.skippedFlag(), result.skipReason(),
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
                item.abnormalDefaultStopFlag(), item.standardMinutes(), item.safetyNotes(),
                item.sortOrder(), result
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

    private boolean isWorkday(
            long tenantId,
            InspectionMapper.GenerationPlan plan,
            LocalDate date
    ) {
        String override = calendarMapper.findEffectiveDayType(
                tenantId, plan.workCalendarId(), date
        );
        if (override != null) {
            return "WORKDAY".equals(override);
        }
        return csvContains(plan.workDays(), date.getDayOfWeek().getValue());
    }

    private boolean csvContains(String csv, int value) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String token : csv.split(",")) {
            if (Integer.parseInt(token.trim()) == value) {
                return true;
            }
        }
        return false;
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
        int assigneeCount = mapper.countTaskAssignees(current.tenantId(), task.id());
        boolean assigned = assigneeCount > 0
                ? mapper.countTaskAssignee(
                        current.tenantId(), task.id(), current.userId()
                ) > 0
                : task.assigneeUserId() != null
                        && task.assigneeUserId() == current.userId();
        if ((assigneeCount > 0 || task.assigneeUserId() != null)
                && !assigned
                && !current.permissions().contains("inspection:task:assign")) {
            throw new BusinessException(
                    "INSPECTION_TASK_ASSIGNEE_ONLY", "只能由任务执行人录入结果",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private List<Long> normalizeAssigneeUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream().distinct().toList();
    }

    private void validateAssigneeUsers(long tenantId, List<Long> userIds) {
        for (Long userId : userIds) {
            if (mapper.countActiveUser(tenantId, userId) == 0) {
                throw new BusinessException(
                        "USER_NOT_FOUND",
                        "执行人不存在或已停用：" + userId,
                        HttpStatus.NOT_FOUND
                );
            }
        }
    }

    private void replaceTaskAssignees(
            long tenantId,
            long taskId,
            List<Long> userIds,
            long operatorId
    ) {
        mapper.deleteTaskAssignees(tenantId, taskId);
        for (int index = 0; index < userIds.size(); index++) {
            mapper.insertTaskAssignee(
                    tenantId, taskId, userIds.get(index), index == 0, index, operatorId
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

    private InspectionDtos.TaskQuery normalizeTaskQuery(
            InspectionDtos.TaskQuery query
    ) {
        InspectionDtos.TaskQuery source = query == null
                ? new InspectionDtos.TaskQuery(
                        null, null, null, "PLANNED_DATE", null, null,
                        null, null, null, null, null, false, null, false
                ) : query;
        String timeField = upper(source.timeField());
        if (timeField == null) {
            timeField = "PLANNED_DATE";
        }
        if (!TIME_FIELDS.contains(timeField)) {
            throw new BusinessException(
                    "INSPECTION_TIME_FIELD_INVALID", "点检时间查询口径不正确"
            );
        }
        if (source.startDate() != null && source.endDate() != null
                && source.startDate().isAfter(source.endDate())) {
            throw new BusinessException(
                    "INSPECTION_DATE_RANGE_INVALID", "开始日期不能晚于结束日期"
            );
        }
        String abnormalSeverity = upper(source.abnormalSeverity());
        if (abnormalSeverity != null && !ABNORMAL_SEVERITIES.contains(abnormalSeverity)) {
            throw new BusinessException(
                    "INSPECTION_ABNORMAL_SEVERITY_INVALID", "异常等级查询条件不正确"
            );
        }
        return new InspectionDtos.TaskQuery(
                clean(source.keyword()), upper(source.taskStatus()), source.plannedDate(),
                timeField, source.startDate(), source.endDate(), source.organizationId(),
                clean(source.teamCode()), source.assigneeUserId(), source.equipmentId(),
                source.schemeId(), source.abnormalOnly(), abnormalSeverity,
                source.mineOnly()
        );
    }

    private byte[] exportWorkbook(
            List<InspectionDtos.TaskRow> tasks,
            List<InspectionDtos.TaskResultExportRow> results,
            List<InspectionDtos.TaskAbnormalExportRow> abnormalities,
            List<InspectionDtos.TaskAttachmentExportRow> attachments
    ) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.getCreationHelper()
                    .createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            CellStyle dateOnly = workbook.createCellStyle();
            dateOnly.setDataFormat(workbook.getCreationHelper()
                    .createDataFormat().getFormat("yyyy-mm-dd"));

            Sheet summary = workbook.createSheet("任务汇总");
            String[] summaryHeaders = {
                    "任务编号", "计划日期", "截止时间", "完成时间", "状态",
                    "设备编号", "设备名称", "组织", "位置", "班组", "执行人",
                    "点检方案", "项目数", "已完成", "异常数"
            };
            writeHeader(summary, summaryHeaders, header);
            int rowIndex = 1;
            for (InspectionDtos.TaskRow task : tasks) {
                Row row = summary.createRow(rowIndex++);
                int column = 0;
                text(row, column++, task.taskCode());
                date(row, column++, task.plannedDate(), dateOnly);
                date(row, column++, task.dueTime(), dateTime);
                date(row, column++, task.completedTime(), dateTime);
                text(row, column++, task.taskStatus());
                text(row, column++, task.equipmentCode());
                text(row, column++, task.equipmentName());
                text(row, column++, task.organizationName());
                text(row, column++, task.locationName());
                text(row, column++, task.teamCode());
                text(row, column++, task.assigneeName());
                text(row, column++, task.schemeNameSnapshot());
                number(row, column++, task.itemCount());
                number(row, column++, task.completedItemCount());
                number(row, column, task.abnormalItemCount());
            }
            finishSheet(summary, summaryHeaders.length);

            Sheet detail = workbook.createSheet("逐项结果");
            String[] detailHeaders = {
                    "任务编号", "计划日期", "截止时间", "完成时间", "任务状态",
                    "设备编号", "设备名称", "组织", "位置", "班组", "执行人",
                    "点检方案", "项目编码", "项目名称", "部位", "点检标准", "单位",
                    "结果状态", "结果编码", "数值结果", "文本结果", "选择结果",
                    "多选结果", "是否异常", "异常说明", "实际执行人", "执行时间"
            };
            writeHeader(detail, detailHeaders, header);
            rowIndex = 1;
            for (InspectionDtos.TaskResultExportRow result : results) {
                Row row = detail.createRow(rowIndex++);
                int column = 0;
                text(row, column++, result.taskCode());
                date(row, column++, result.plannedDate(), dateOnly);
                date(row, column++, result.dueTime(), dateTime);
                date(row, column++, result.completedTime(), dateTime);
                text(row, column++, result.taskStatus());
                text(row, column++, result.equipmentCode());
                text(row, column++, result.equipmentName());
                text(row, column++, result.organizationName());
                text(row, column++, result.locationName());
                text(row, column++, result.teamCode());
                text(row, column++, result.assigneeName());
                text(row, column++, result.schemeName());
                text(row, column++, result.itemCode());
                text(row, column++, result.itemName());
                text(row, column++, result.inspectionPart());
                text(row, column++, result.inspectionStandard());
                text(row, column++, result.unit());
                text(row, column++, result.resultStatus());
                text(row, column++, result.resultCode());
                decimal(row, column++, result.numericValue());
                text(row, column++, result.textValue());
                text(row, column++, result.selectedValue());
                text(row, column++, result.selectedValuesJson());
                text(row, column++, Boolean.TRUE.equals(result.abnormalFlag()) ? "是" : "否");
                text(row, column++, result.abnormalDescription());
                text(row, column++, result.executedByName());
                date(row, column, result.executedTime(), dateTime);
            }
            finishSheet(detail, detailHeaders.length);

            Sheet abnormalSheet = workbook.createSheet("异常记录");
            String[] abnormalHeaders = {
                    "异常编号", "任务编号", "设备编号", "设备名称", "点检项目",
                    "异常标题", "异常说明", "严重度", "状态", "责任人", "处理期限",
                    "临时措施", "最终结果", "关闭人", "关闭时间", "验证人", "验证时间",
                    "验证意见", "创建时间"
            };
            writeHeader(abnormalSheet, abnormalHeaders, header);
            rowIndex = 1;
            for (InspectionDtos.TaskAbnormalExportRow abnormal : abnormalities) {
                Row row = abnormalSheet.createRow(rowIndex++);
                int column = 0;
                text(row, column++, abnormal.abnormalCode());
                text(row, column++, abnormal.taskCode());
                text(row, column++, abnormal.equipmentCode());
                text(row, column++, abnormal.equipmentName());
                text(row, column++, abnormal.itemName());
                text(row, column++, abnormal.abnormalTitle());
                text(row, column++, abnormal.abnormalDescription());
                text(row, column++, abnormal.severity());
                text(row, column++, abnormal.abnormalStatus());
                text(row, column++, abnormal.responsibleUserName());
                date(row, column++, abnormal.dueTime(), dateTime);
                text(row, column++, abnormal.temporaryAction());
                text(row, column++, abnormal.finalResult());
                text(row, column++, abnormal.closedByName());
                date(row, column++, abnormal.closedTime(), dateTime);
                text(row, column++, abnormal.verifiedByName());
                date(row, column++, abnormal.verifiedTime(), dateTime);
                text(row, column++, abnormal.verificationComment());
                date(row, column, abnormal.createdTime(), dateTime);
            }
            finishSheet(abnormalSheet, abnormalHeaders.length);

            Sheet attachmentSheet = workbook.createSheet("附件索引");
            String[] attachmentHeaders = {
                    "任务编号", "设备编号", "设备名称", "点检项目", "附件ID",
                    "文件名", "内容类型", "扩展名", "文件大小（字节）", "附件类型", "上传时间"
            };
            writeHeader(attachmentSheet, attachmentHeaders, header);
            rowIndex = 1;
            for (InspectionDtos.TaskAttachmentExportRow attachment : attachments) {
                Row row = attachmentSheet.createRow(rowIndex++);
                int column = 0;
                text(row, column++, attachment.taskCode());
                text(row, column++, attachment.equipmentCode());
                text(row, column++, attachment.equipmentName());
                text(row, column++, attachment.itemName());
                number(row, column++, attachment.attachmentId());
                text(row, column++, attachment.originalName());
                text(row, column++, attachment.contentType());
                text(row, column++, attachment.extension());
                number(row, column++, attachment.fileSize());
                text(row, column++, attachment.attachmentType());
                date(row, column, attachment.createdTime(), dateTime);
            }
            finishSheet(attachmentSheet, attachmentHeaders.length);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_FAILED", "点检结果导出失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(style);
        }
    }

    private void finishSheet(Sheet sheet, int columns) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, Math.max(0, sheet.getLastRowNum()), 0, columns - 1
        ));
        for (int index = 0; index < columns; index++) {
            if (sheet.getLastRowNum() <= 5_000) {
                sheet.autoSizeColumn(index);
                sheet.setColumnWidth(
                        index, Math.min(sheet.getColumnWidth(index) + 512, 12_000)
                );
            } else {
                sheet.setColumnWidth(index, 5_000);
            }
        }
    }

    private void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(safeExcel(value));
    }

    private void number(Row row, int column, Number value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private void decimal(Row row, int column, BigDecimal value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private void date(Row row, int column, Object value, CellStyle style) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(column);
        if (value instanceof LocalDate localDate) {
            cell.setCellValue(localDate);
            if (style != null) {
                cell.setCellStyle(style);
            }
        } else if (value instanceof LocalDateTime localDateTime) {
            cell.setCellValue(localDateTime);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private String safeExcel(String value) {
        String cleaned = value == null ? "" : value;
        if (!cleaned.isEmpty() && "=+-@".indexOf(cleaned.charAt(0)) >= 0) {
            return "'" + cleaned;
        }
        return cleaned;
    }

    private String requestHash(InspectionDtos.ManualTaskRequest request) {
        try {
            byte[] payload = objectMapper.writeValueAsString(request)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload)
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    "INSPECTION_TASK_REQUEST_HASH_FAILED",
                    "点检任务创建请求摘要计算失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
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
