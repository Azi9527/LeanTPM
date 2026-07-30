package com.leantpm.oee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class OeeService {
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.000000");
    private static final BigDecimal ONE_RATE = new BigDecimal("1.000000");
    private static final Set<String> WORKFLOW_ACTIONS =
            Set.of("SUBMIT", "APPROVE", "LOCK", "UNLOCK");
    private static final Set<String> RANKING_TYPES =
            Set.of("ENTERPRISE", "FACTORY", "WORKSHOP", "LINE", "EQUIPMENT");
    private static final Set<String> TREND_PERIODS = Set.of("DAY", "WEEK", "MONTH");

    private final OeeMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;

    public OeeService(
            OeeMapper mapper,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.OutputRow> outputs(
            Long equipmentId,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize
    ) {
        validateDateRange(startDate, endDate);
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findOutputs(
                        current.tenantId(), scope, equipmentId, startDate, endDate,
                        offset, pageSize
                ),
                mapper.countOutputs(
                        current.tenantId(), scope, equipmentId, startDate, endDate
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.OutputRow output(long id) {
        var current = SecurityUtils.currentUser();
        OeeDtos.OutputRow row = mapper.findOutput(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (row == null) {
            throw notFound("OEE_OUTPUT_NOT_FOUND", "产量记录不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public long createOutput(OeeDtos.SaveOutputRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveOutputRequest normalized = normalizeOutput(request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        validateOutput(normalized);
        ensureUnlockedByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (mapper.findOutputByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        ) != null) {
            throw conflict("OEE_OUTPUT_EXISTS", "该设备、日期和班次的产量记录已存在");
        }
        mapper.insertOutput(
                current.tenantId(), equipment.organizationId(), normalized, current.userId()
        );
        OeeDtos.OutputRow created = mapper.findOutputByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (created == null) {
            throw internal("OEE_OUTPUT_CREATE_FAILED", "产量记录创建失败");
        }
        mapper.syncRecordOutput(current.tenantId(), normalized, current.userId());
        recalculateByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId(),
                "UPDATE", current.userId()
        );
        changeLogService.record("OEE_OUTPUT", created.id(), "CREATE", null, created);
        return created.id();
    }

    @Transactional
    public void updateOutput(long id, OeeDtos.SaveOutputRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.OutputRow before = output(id);
        requireVersion(request.version());
        OeeDtos.SaveOutputRequest normalized = normalizeOutput(request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        validateOutput(normalized);
        ensureUnlockedByKey(
                current.tenantId(), before.equipmentId(),
                before.productionDate(), before.shiftId()
        );
        ensureUnlockedByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        OeeDtos.OutputRow duplicate = mapper.findOutputByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (duplicate != null && duplicate.id() != id) {
            throw conflict("OEE_OUTPUT_EXISTS", "该设备、日期和班次的产量记录已存在");
        }
        if (mapper.updateOutput(
                current.tenantId(), id, equipment.organizationId(),
                normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        if (!sameKey(before, normalized)) {
            mapper.clearRecordOutput(
                    current.tenantId(), before.equipmentId(), before.productionDate(),
                    before.shiftId(), current.userId()
            );
            recalculateByKey(
                    current.tenantId(), before.equipmentId(), before.productionDate(),
                    before.shiftId(), "UPDATE", current.userId()
            );
        }
        mapper.syncRecordOutput(current.tenantId(), normalized, current.userId());
        recalculateByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId(),
                "UPDATE", current.userId()
        );
        changeLogService.record(
                "OEE_OUTPUT", id, "UPDATE", before,
                mapper.findOutput(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void deleteOutput(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.OutputRow before = output(id);
        ensureUnlockedByKey(
                current.tenantId(), before.equipmentId(),
                before.productionDate(), before.shiftId()
        );
        if (mapper.deleteOutput(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        mapper.clearRecordOutput(
                current.tenantId(), before.equipmentId(), before.productionDate(),
                before.shiftId(), current.userId()
        );
        recalculateByKey(
                current.tenantId(), before.equipmentId(), before.productionDate(),
                before.shiftId(), "UPDATE", current.userId()
        );
        changeLogService.record("OEE_OUTPUT", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.DowntimeRow> downtimes(
            Long equipmentId,
            LocalDate startDate,
            LocalDate endDate,
            Long lossReasonId,
            int page,
            int pageSize
    ) {
        validateDateRange(startDate, endDate);
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findDowntimes(
                        current.tenantId(), scope, equipmentId, startDate, endDate,
                        lossReasonId, offset, pageSize
                ),
                mapper.countDowntimes(
                        current.tenantId(), scope, equipmentId, startDate,
                        endDate, lossReasonId
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.DowntimeRow downtime(long id) {
        var current = SecurityUtils.currentUser();
        OeeDtos.DowntimeRow row = mapper.findDowntime(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (row == null) {
            throw notFound("OEE_DOWNTIME_NOT_FOUND", "停机记录不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public long createDowntime(OeeDtos.SaveDowntimeRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveDowntimeRequest normalized =
                normalizeDowntime(current.tenantId(), request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        ensureUnlockedByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        mapper.insertDowntime(
                current.tenantId(), equipment.organizationId(), normalized, current.userId()
        );
        var rows = mapper.findDowntimes(
                current.tenantId(), DataPermission.all(current.userId()),
                normalized.equipmentId(), normalized.productionDate(),
                normalized.productionDate(), normalized.lossReasonId(), 0, 200
        );
        OeeDtos.DowntimeRow created = rows.stream()
                .filter(row -> row.shiftId() == normalized.shiftId())
                .max(java.util.Comparator.comparingLong(OeeDtos.DowntimeRow::id))
                .orElseThrow(() -> internal(
                        "OEE_DOWNTIME_CREATE_FAILED", "停机记录创建失败"
                ));
        recalculateByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId(),
                "UPDATE", current.userId()
        );
        changeLogService.record("OEE_DOWNTIME", created.id(), "CREATE", null, created);
        return created.id();
    }

    @Transactional
    public void updateDowntime(long id, OeeDtos.SaveDowntimeRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.DowntimeRow before = downtime(id);
        requireVersion(request.version());
        OeeDtos.SaveDowntimeRequest normalized =
                normalizeDowntime(current.tenantId(), request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        ensureUnlockedByKey(
                current.tenantId(), before.equipmentId(),
                before.productionDate(), before.shiftId()
        );
        ensureUnlockedByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (mapper.updateDowntime(
                current.tenantId(), id, equipment.organizationId(),
                normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        recalculateByKey(
                current.tenantId(), before.equipmentId(), before.productionDate(),
                before.shiftId(), "UPDATE", current.userId()
        );
        if (!sameKey(before, normalized)) {
            recalculateByKey(
                    current.tenantId(), normalized.equipmentId(),
                    normalized.productionDate(), normalized.shiftId(),
                    "UPDATE", current.userId()
            );
        }
        changeLogService.record(
                "OEE_DOWNTIME", id, "UPDATE", before,
                mapper.findDowntime(
                        current.tenantId(), id, DataPermission.all(current.userId())
                )
        );
    }

    @Transactional
    public void deleteDowntime(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.DowntimeRow before = downtime(id);
        ensureUnlockedByKey(
                current.tenantId(), before.equipmentId(),
                before.productionDate(), before.shiftId()
        );
        if (mapper.deleteDowntime(
                current.tenantId(), id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        recalculateByKey(
                current.tenantId(), before.equipmentId(), before.productionDate(),
                before.shiftId(), "UPDATE", current.userId()
        );
        changeLogService.record("OEE_DOWNTIME", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.OeeRecordRow> records(
            Long equipmentId,
            Long organizationId,
            String dataStatus,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize
    ) {
        validateDateRange(startDate, endDate);
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        String normalizedStatus = upper(dataStatus);
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findRecords(
                        current.tenantId(), scope, equipmentId, organizationId,
                        normalizedStatus, startDate, endDate, offset, pageSize
                ),
                mapper.countRecords(
                        current.tenantId(), scope, equipmentId, organizationId,
                        normalizedStatus, startDate, endDate
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.OeeRecordRow record(long id) {
        return requireRecord(
                SecurityUtils.currentUser().tenantId(), id,
                dataPermissionService.current()
        );
    }

    @Transactional
    public long createRecord(OeeDtos.SaveOeeRecordRequest request) {
        return createRecord(request, "CREATE");
    }

    @Transactional
    public long createImportedRecord(OeeDtos.SaveOeeRecordRequest request) {
        return createRecord(request, "IMPORT");
    }

    private long createRecord(
            OeeDtos.SaveOeeRecordRequest request,
            String triggerType
    ) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveOeeRecordRequest normalized = normalizeRecord(request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        validateRecordInput(normalized);
        if (mapper.findRecordByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        ) != null) {
            throw conflict(
                    "OEE_RECORD_EXISTS", "该设备、日期和班次只能存在一条有效OEE记录"
            );
        }
        upsertOutput(current.tenantId(), equipment, normalized, current.userId());
        OeeDtos.OeeCalculation calculation =
                calculate(current.tenantId(), equipment, normalized);
        mapper.insertRecord(
                current.tenantId(), equipment.organizationId(), normalized,
                calculation, current.userId()
        );
        OeeDtos.OeeRecordRow created = mapper.findRecordByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (created == null) {
            throw internal("OEE_RECORD_CREATE_FAILED", "OEE记录创建失败");
        }
        insertCalculationLog(
                current.tenantId(), created.id(), triggerType,
                normalized, calculation, current.userId()
        );
        changeLogService.record(
                "OEE_RECORD", created.id(), triggerType, null, created
        );
        return created.id();
    }

    @Transactional
    public void updateRecord(long id, OeeDtos.SaveOeeRecordRequest request) {
        updateRecord(id, request, "UPDATE");
    }

    @Transactional
    public void updateImportedRecord(
            long id,
            OeeDtos.SaveOeeRecordRequest request
    ) {
        updateRecord(id, request, "IMPORT");
    }

    private void updateRecord(
            long id,
            OeeDtos.SaveOeeRecordRequest request,
            String triggerType
    ) {
        var current = SecurityUtils.currentUser();
        OeeDtos.OeeRecordRow before = requireRecord(
                current.tenantId(), id, dataPermissionService.current()
        );
        requireVersion(request.version());
        if ("LOCKED".equals(before.dataStatus())) {
            throw conflict("OEE_RECORD_LOCKED", "OEE记录已锁定，需先解锁");
        }
        OeeDtos.SaveOeeRecordRequest normalized = normalizeRecord(request);
        OeeDtos.EquipmentRef equipment =
                requireEquipment(current.tenantId(), normalized.equipmentId());
        requireEnabledShift(current.tenantId(), normalized.shiftId());
        validateRecordInput(normalized);
        OeeDtos.OeeRecordRow duplicate = mapper.findRecordByKey(
                current.tenantId(), normalized.equipmentId(),
                normalized.productionDate(), normalized.shiftId()
        );
        if (duplicate != null && duplicate.id() != id) {
            throw conflict(
                    "OEE_RECORD_EXISTS", "该设备、日期和班次只能存在一条有效OEE记录"
            );
        }
        upsertOutput(current.tenantId(), equipment, normalized, current.userId());
        OeeDtos.OeeCalculation calculation =
                calculate(current.tenantId(), equipment, normalized);
        if (mapper.updateRecord(
                current.tenantId(), id, equipment.organizationId(), normalized,
                calculation, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        insertCalculationLog(
                current.tenantId(), id, triggerType,
                normalized, calculation, current.userId()
        );
        changeLogService.record(
                "OEE_RECORD", id, triggerType, before,
                mapper.findRecord(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public OeeDtos.OeeRecordRow recalculate(long id) {
        var current = SecurityUtils.currentUser();
        OeeDtos.OeeRecordRow before = requireRecord(
                current.tenantId(), id, dataPermissionService.current()
        );
        if ("LOCKED".equals(before.dataStatus())) {
            throw conflict("OEE_RECORD_LOCKED", "OEE记录已锁定，需先解锁");
        }
        OeeDtos.OeeRecordRow after = recalculateRecord(
                current.tenantId(), before, "RECALCULATE", current.userId()
        );
        changeLogService.record("OEE_RECORD", id, "RECALCULATE", before, after);
        return after;
    }

    @Transactional
    public void workflow(long id, OeeDtos.WorkflowRequest request) {
        var current = SecurityUtils.currentUser();
        String action = upper(request.action());
        if (!WORKFLOW_ACTIONS.contains(action)) {
            throw new BusinessException("OEE_WORKFLOW_ACTION_INVALID", "OEE流程动作无效");
        }
        requireWorkflowPermission(action, current.permissions());
        OeeDtos.OeeRecordRow before = requireRecord(
                current.tenantId(), id, dataPermissionService.current()
        );
        String target = targetStatus(before.dataStatus(), action);
        if ("SUBMITTED".equals(target) && before.anomalyFlag()) {
            throw conflict(
                    "OEE_RECORD_HAS_ANOMALY",
                    "OEE记录存在异常数据，请修正或重新计算后再提交"
            );
        }
        if (mapper.updateRecordWorkflow(
                current.tenantId(), id, before.dataStatus(), target,
                current.userId(), request.version()
        ) == 0) {
            throw optimisticConflict();
        }
        OeeDtos.OeeRecordRow after = mapper.findRecord(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        if ("APPROVE".equals(action)) {
            insertCalculationLog(
                    current.tenantId(), id, "APPROVE",
                    requestFrom(after), calculationFrom(after), current.userId()
            );
        }
        java.util.Map<String, Object> workflowSnapshot = new java.util.LinkedHashMap<>();
        workflowSnapshot.put("record", after);
        workflowSnapshot.put("comment", clean(request.comment()));
        changeLogService.record(
                "OEE_RECORD", id, action, before, workflowSnapshot
        );
    }

    @Transactional(readOnly = true)
    public List<OeeDtos.CalculationLogRow> calculationLogs(long recordId) {
        var current = SecurityUtils.currentUser();
        requireRecord(current.tenantId(), recordId, dataPermissionService.current());
        return mapper.findCalculationLogs(current.tenantId(), recordId);
    }

    @Transactional(readOnly = true)
    public OeeDtos.AnalysisResult analysis(
            LocalDate startDate,
            LocalDate endDate,
            Long organizationId,
            Long equipmentId,
            String period,
            String rankingType,
            int limit
    ) {
        if (startDate == null || endDate == null) {
            throw new BusinessException("OEE_ANALYSIS_DATE_REQUIRED", "分析开始和结束日期不能为空");
        }
        validateDateRange(startDate, endDate);
        String normalizedPeriod = upper(period);
        String normalizedRanking = upper(rankingType);
        if (!TREND_PERIODS.contains(normalizedPeriod)) {
            throw new BusinessException("OEE_TREND_PERIOD_INVALID", "趋势周期仅支持日、周、月");
        }
        if (!RANKING_TYPES.contains(normalizedRanking)) {
            throw new BusinessException("OEE_RANKING_TYPE_INVALID", "排名层级无效");
        }
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        List<Long> organizationIds = organizationId == null
                ? List.of()
                : mapper.findOrganizationAndDescendantIds(
                        current.tenantId(), organizationId
                );
        if (organizationId != null && organizationIds.isEmpty()) {
            throw notFound(
                    "ORGANIZATION_NOT_FOUND", "分析组织不存在、已停用或无权访问"
            );
        }
        boolean capPerformance = booleanParameter(
                current.tenantId(), "oee.performance-cap-enabled", true
        );
        OeeDtos.AnalysisSummary summary = mapper.analysisSummary(
                current.tenantId(), scope, startDate, endDate,
                organizationIds, equipmentId, capPerformance
        );
        return new OeeDtos.AnalysisResult(
                summary,
                mapper.analysisTrend(
                        current.tenantId(), scope, startDate, endDate,
                        organizationIds, equipmentId, normalizedPeriod, capPerformance
                ),
                mapper.analysisRanking(
                        current.tenantId(), scope, startDate, endDate,
                        organizationIds, equipmentId, normalizedRanking,
                        Math.min(Math.max(limit, 1), 100), capPerformance
                ),
                mapper.analysisLosses(
                        current.tenantId(), scope, startDate, endDate,
                        organizationIds, equipmentId
                ),
                mapper.findRecords(
                        current.tenantId(), scope, equipmentId, organizationId,
                        null, startDate, endDate, 0, 200
                )
        );
    }

    private void requireWorkflowPermission(String action, Set<String> permissions) {
        boolean allowed = switch (action) {
            case "SUBMIT" -> permissions.contains("oee:record:manage")
                    || permissions.contains("oee:record:approve");
            case "APPROVE" -> permissions.contains("oee:record:approve");
            case "LOCK", "UNLOCK" -> permissions.contains("oee:record:lock");
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(
                    "OEE_WORKFLOW_FORBIDDEN", "无权执行该OEE流程动作", HttpStatus.FORBIDDEN
            );
        }
    }

    OeeDtos.OeeCalculation calculate(
            long tenantId,
            OeeDtos.EquipmentRef equipment,
            OeeDtos.SaveOeeRecordRequest request
    ) {
        BigDecimal plannedWork = scaleMinutes(request.plannedWorkMinutes());
        BigDecimal plannedDowntime = scaleMinutes(request.plannedDowntimeMinutes());
        BigDecimal loading = plannedWork.subtract(plannedDowntime);
        BigDecimal unplanned = scaleMinutes(mapper.sumDowntime(
                tenantId, request.equipmentId(), request.productionDate(),
                request.shiftId(), false
        ));
        List<String> anomalies = new ArrayList<>();
        if (loading.signum() <= 0) {
            anomalies.add("负荷时间必须大于0");
            loading = BigDecimal.ZERO.setScale(3);
        }
        BigDecimal runTime = loading.subtract(unplanned);
        if (runTime.signum() < 0) {
            anomalies.add("非计划停机时间超过负荷时间");
            runTime = BigDecimal.ZERO.setScale(3);
        }
        BigDecimal availability = rate(runTime, loading);
        BigDecimal idealMinutes = request.standardCycleSeconds()
                .multiply(request.actualQuantity())
                .divide(new BigDecimal("60"), 9, RoundingMode.HALF_UP);
        BigDecimal rawPerformance = rate(idealMinutes, runTime);
        boolean capPerformance = booleanParameter(
                tenantId, "oee.performance-cap-enabled", true
        );
        BigDecimal performance = rawPerformance;
        if (rawPerformance.compareTo(BigDecimal.ONE) > 0) {
            anomalies.add("性能率超过100%，请检查标准节拍、运行时间或产量");
            if (capPerformance) {
                performance = ONE_RATE;
            }
        }
        BigDecimal quality = rate(request.goodQuantity(), request.actualQuantity());
        if (request.actualQuantity().signum() == 0) {
            anomalies.add("实际产量为0");
        }
        BigDecimal target = mapper.findTargetRate(
                tenantId, request.equipmentId(), equipment.organizationId(),
                request.productionDate()
        );
        if (target == null) {
            target = decimalParameter(
                    tenantId, "oee.default-target", new BigDecimal("0.850000")
            );
        }
        BigDecimal oee = availability.multiply(performance)
                .multiply(quality)
                .setScale(6, RoundingMode.HALF_UP);
        return new OeeDtos.OeeCalculation(
                loading,
                unplanned,
                runTime,
                availability,
                scaleRate(performance),
                quality,
                oee,
                scaleRate(target),
                !anomalies.isEmpty(),
                anomalies.isEmpty() ? null : String.join("；", anomalies)
        );
    }

    private void upsertOutput(
            long tenantId,
            OeeDtos.EquipmentRef equipment,
            OeeDtos.SaveOeeRecordRequest request,
            long operatorId
    ) {
        OeeDtos.OutputRow existing = mapper.findOutputByKey(
                tenantId, request.equipmentId(), request.productionDate(), request.shiftId()
        );
        OeeDtos.SaveOutputRequest output = new OeeDtos.SaveOutputRequest(
                request.equipmentId(), request.productionDate(), request.shiftId(),
                request.plannedQuantity(), request.actualQuantity(), request.goodQuantity(),
                request.defectiveQuantity(), request.sourceType(), null,
                "由OEE记录同步", existing == null ? null : existing.version()
        );
        if (existing == null) {
            mapper.insertOutput(tenantId, equipment.organizationId(), output, operatorId);
        } else if (mapper.updateOutput(
                tenantId, existing.id(), equipment.organizationId(), output, operatorId
        ) == 0) {
            throw optimisticConflict();
        }
    }

    private OeeDtos.OeeRecordRow recalculateByKey(
            long tenantId,
            long equipmentId,
            LocalDate productionDate,
            long shiftId,
            String triggerType,
            long operatorId
    ) {
        OeeDtos.OeeRecordRow row =
                mapper.findRecordByKey(tenantId, equipmentId, productionDate, shiftId);
        if (row == null) {
            return null;
        }
        return recalculateRecord(tenantId, row, triggerType, operatorId);
    }

    private OeeDtos.OeeRecordRow recalculateRecord(
            long tenantId,
            OeeDtos.OeeRecordRow row,
            String triggerType,
            long operatorId
    ) {
        if ("LOCKED".equals(row.dataStatus())) {
            throw conflict("OEE_RECORD_LOCKED", "关联OEE记录已锁定，不能修改源数据");
        }
        OeeDtos.SaveOeeRecordRequest request = requestFrom(row);
        OeeDtos.EquipmentRef equipment =
                mapper.findEquipment(tenantId, row.equipmentId(), DataPermission.all(operatorId));
        if (equipment == null) {
            throw notFound("EQUIPMENT_NOT_FOUND", "OEE设备不存在");
        }
        OeeDtos.OeeCalculation calculation = calculate(tenantId, equipment, request);
        if (mapper.updateRecordCalculation(
                tenantId, row.id(), calculation, operatorId, row.version()
        ) == 0) {
            throw optimisticConflict();
        }
        insertCalculationLog(
                tenantId, row.id(), triggerType, request, calculation, operatorId
        );
        return mapper.findRecord(tenantId, row.id(), DataPermission.all(operatorId));
    }

    private void insertCalculationLog(
            long tenantId,
            long recordId,
            String triggerType,
            OeeDtos.SaveOeeRecordRequest input,
            OeeDtos.OeeCalculation output,
            long operatorId
    ) {
        int version = mapper.nextCalculationVersion(tenantId, recordId);
        mapper.insertCalculationLog(
                tenantId, recordId, version, triggerType, json(input), json(output),
                output.anomalyMessage(), operatorId
        );
    }

    private OeeDtos.SaveOeeRecordRequest requestFrom(OeeDtos.OeeRecordRow row) {
        return new OeeDtos.SaveOeeRecordRequest(
                row.equipmentId(), row.productionDate(), row.shiftId(),
                row.standardCycleSeconds(), row.plannedWorkMinutes(),
                row.plannedDowntimeMinutes(), row.plannedQuantity(),
                row.actualQuantity(), row.goodQuantity(), row.defectiveQuantity(),
                row.sourceType(), row.version()
        );
    }

    private OeeDtos.OeeCalculation calculationFrom(OeeDtos.OeeRecordRow row) {
        return new OeeDtos.OeeCalculation(
                row.loadingTimeMinutes(), row.unplannedDowntimeMinutes(),
                row.runTimeMinutes(), row.availabilityRate(), row.performanceRate(),
                row.qualityRate(), row.oeeRate(), row.targetOeeRate(),
                row.anomalyFlag(), row.anomalyMessage()
        );
    }

    private String targetStatus(String current, String action) {
        return switch (current + ":" + action) {
            case "DRAFT:SUBMIT" -> "SUBMITTED";
            case "SUBMITTED:APPROVE" -> "APPROVED";
            case "APPROVED:LOCK" -> "LOCKED";
            case "LOCKED:UNLOCK" -> "APPROVED";
            default -> throw conflict(
                    "OEE_WORKFLOW_TRANSITION_INVALID",
                    "当前状态不允许执行该OEE流程动作"
            );
        };
    }

    private OeeDtos.EquipmentRef requireEquipment(long tenantId, long equipmentId) {
        OeeDtos.EquipmentRef equipment = mapper.findEquipment(
                tenantId, equipmentId, dataPermissionService.current()
        );
        if (equipment == null || equipment.status() != 1 || !equipment.oeeEnabled()) {
            throw notFound(
                    "EQUIPMENT_NOT_FOUND", "设备不存在、未启用OEE或无权访问"
            );
        }
        return equipment;
    }

    private OeeDtos.ShiftRow requireEnabledShift(long tenantId, long shiftId) {
        OeeDtos.ShiftRow shift = mapper.findShift(tenantId, shiftId);
        if (shift == null) {
            throw notFound("OEE_SHIFT_NOT_FOUND", "班次不存在");
        }
        if (shift.status() != 1) {
            throw conflict("OEE_SHIFT_DISABLED", "班次已停用");
        }
        return shift;
    }

    private OeeDtos.OeeRecordRow requireRecord(
            long tenantId,
            long id,
            DataPermission scope
    ) {
        OeeDtos.OeeRecordRow row = mapper.findRecord(tenantId, id, scope);
        if (row == null) {
            throw notFound("OEE_RECORD_NOT_FOUND", "OEE记录不存在或无权访问");
        }
        return row;
    }

    private void ensureUnlockedByKey(
            long tenantId,
            long equipmentId,
            LocalDate productionDate,
            long shiftId
    ) {
        OeeDtos.OeeRecordRow record =
                mapper.findRecordByKey(tenantId, equipmentId, productionDate, shiftId);
        if (record != null && "LOCKED".equals(record.dataStatus())) {
            throw conflict("OEE_RECORD_LOCKED", "关联OEE记录已锁定，不能修改源数据");
        }
    }

    private void validateOutput(OeeDtos.SaveOutputRequest request) {
        if (request.goodQuantity().add(request.defectiveQuantity())
                .compareTo(request.actualQuantity()) > 0) {
            throw new BusinessException(
                    "OEE_OUTPUT_QUANTITY_INVALID", "良品数与不良品数之和不能大于实际产量"
            );
        }
    }

    private void validateRecordInput(OeeDtos.SaveOeeRecordRequest request) {
        validateOutput(new OeeDtos.SaveOutputRequest(
                request.equipmentId(), request.productionDate(), request.shiftId(),
                request.plannedQuantity(), request.actualQuantity(), request.goodQuantity(),
                request.defectiveQuantity(), request.sourceType(), null, null, request.version()
        ));
        if (request.plannedDowntimeMinutes().compareTo(request.plannedWorkMinutes()) > 0) {
            throw new BusinessException(
                    "OEE_PLANNED_TIME_INVALID", "计划停机时间不能大于计划工作时间"
            );
        }
    }

    private OeeDtos.SaveDowntimeRequest normalizeDowntime(
            long tenantId,
            OeeDtos.SaveDowntimeRequest request
    ) {
        OeeDtos.LossReasonRow reason = mapper.findLossReason(
                tenantId, request.lossReasonId()
        );
        if (reason == null || reason.status() != 1) {
            throw notFound("OEE_LOSS_REASON_NOT_FOUND", "损失原因不存在或已停用");
        }
        if (reason.plannedFlag() != request.plannedFlag()) {
            throw new BusinessException(
                    "OEE_DOWNTIME_PLANNED_MISMATCH",
                    "停机的计划属性必须与损失原因配置一致"
            );
        }
        if ((request.startedTime() == null) != (request.endedTime() == null)) {
            throw new BusinessException(
                    "OEE_DOWNTIME_TIME_INCOMPLETE", "停机开始和结束时间必须同时填写"
            );
        }
        BigDecimal duration = request.durationMinutes();
        if (request.startedTime() != null) {
            if (!request.endedTime().isAfter(request.startedTime())) {
                throw new BusinessException(
                        "OEE_DOWNTIME_TIME_INVALID", "停机结束时间必须晚于开始时间"
                );
            }
            long seconds = Duration.between(
                    request.startedTime(), request.endedTime()
            ).toSeconds();
            duration = BigDecimal.valueOf(seconds)
                    .divide(new BigDecimal("60"), 3, RoundingMode.HALF_UP);
        }
        return new OeeDtos.SaveDowntimeRequest(
                request.equipmentId(), request.productionDate(), request.shiftId(),
                request.lossReasonId(), request.startedTime(), request.endedTime(),
                scaleMinutes(duration), request.plannedFlag(), upper(request.sourceType()),
                clean(request.sourceReference()), clean(request.description()),
                request.version()
        );
    }

    private OeeDtos.SaveOutputRequest normalizeOutput(OeeDtos.SaveOutputRequest request) {
        return new OeeDtos.SaveOutputRequest(
                request.equipmentId(), request.productionDate(), request.shiftId(),
                scaleQuantity(request.plannedQuantity()),
                scaleQuantity(request.actualQuantity()),
                scaleQuantity(request.goodQuantity()),
                scaleQuantity(request.defectiveQuantity()),
                upper(request.sourceType()), clean(request.sourceReference()),
                clean(request.remark()), request.version()
        );
    }

    private OeeDtos.SaveOeeRecordRequest normalizeRecord(
            OeeDtos.SaveOeeRecordRequest request
    ) {
        return new OeeDtos.SaveOeeRecordRequest(
                request.equipmentId(), request.productionDate(), request.shiftId(),
                request.standardCycleSeconds().setScale(6, RoundingMode.HALF_UP),
                scaleMinutes(request.plannedWorkMinutes()),
                scaleMinutes(request.plannedDowntimeMinutes()),
                scaleQuantity(request.plannedQuantity()),
                scaleQuantity(request.actualQuantity()),
                scaleQuantity(request.goodQuantity()),
                scaleQuantity(request.defectiveQuantity()),
                upper(request.sourceType()), request.version()
        );
    }

    private boolean sameKey(
            OeeDtos.OutputRow before,
            OeeDtos.SaveOutputRequest after
    ) {
        return before.equipmentId() == after.equipmentId()
                && before.productionDate().equals(after.productionDate())
                && before.shiftId() == after.shiftId();
    }

    private boolean sameKey(
            OeeDtos.DowntimeRow before,
            OeeDtos.SaveDowntimeRequest after
    ) {
        return before.equipmentId() == after.equipmentId()
                && before.productionDate().equals(after.productionDate())
                && before.shiftId() == after.shiftId();
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() <= 0
                || numerator == null || numerator.signum() <= 0) {
            return ZERO_RATE;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRate(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleMinutes(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleQuantity(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private boolean booleanParameter(long tenantId, String key, boolean defaultValue) {
        String value = mapper.findParameterValue(tenantId, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private BigDecimal decimalParameter(
            long tenantId,
            String key,
            BigDecimal defaultValue
    ) {
        String value = mapper.findParameterValue(tenantId, key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw internal("OEE_JSON_FAILED", "OEE计算快照序列化失败");
        }
    }

    private String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private void requireVersion(Integer version) {
        if (version == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
    }

    private void validateDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException("DATE_RANGE_INVALID", "结束日期不能早于开始日期");
        }
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(code, message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException internal(String code, String message) {
        return new BusinessException(code, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT", "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }
}
