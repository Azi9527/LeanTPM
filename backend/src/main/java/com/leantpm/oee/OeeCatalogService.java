package com.leantpm.oee;

import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

@Service
public class OeeCatalogService {
    private final OeeMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;

    public OeeCatalogService(
            OeeMapper mapper,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.ShiftRow> shifts(
            String keyword, Integer status, int page, int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findShifts(
                        current.tenantId(), clean(keyword), status, offset, pageSize
                ),
                mapper.countShifts(current.tenantId(), clean(keyword), status),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.ShiftRow shift(long id) {
        return requireShift(SecurityUtils.currentUser().tenantId(), id);
    }

    @Transactional
    public long createShift(OeeDtos.SaveShiftRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveShiftRequest normalized = normalizeShift(request);
        validateShift(normalized);
        if (mapper.countShiftCode(current.tenantId(), normalized.shiftCode(), null) > 0) {
            throw conflict("OEE_SHIFT_CODE_EXISTS", "班次编码已存在");
        }
        mapper.insertShift(current.tenantId(), normalized, current.userId());
        Long id = mapper.findShiftIdByCode(current.tenantId(), normalized.shiftCode());
        if (id == null) {
            throw internal("OEE_SHIFT_CREATE_FAILED", "班次创建失败");
        }
        changeLogService.record(
                "OEE_SHIFT", id, "CREATE", null, mapper.findShift(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateShift(long id, OeeDtos.SaveShiftRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.ShiftRow before = requireShift(current.tenantId(), id);
        requireVersion(request.version());
        OeeDtos.SaveShiftRequest normalized = normalizeShift(request);
        validateShift(normalized);
        if (!before.shiftCode().equals(normalized.shiftCode())) {
            throw new BusinessException("OEE_SHIFT_CODE_IMMUTABLE", "班次编码不可修改");
        }
        if (mapper.updateShift(
                current.tenantId(), id, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "OEE_SHIFT", id, "UPDATE", before, mapper.findShift(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteShift(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.ShiftRow before = requireShift(current.tenantId(), id);
        if (mapper.countShiftReferences(current.tenantId(), id) > 0) {
            throw conflict("OEE_SHIFT_IN_USE", "班次已被生产日历或OEE记录引用，不能删除");
        }
        if (mapper.deleteShift(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("OEE_SHIFT", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.CalendarRow> calendars(
            Long organizationId,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            int page,
            int pageSize
    ) {
        validateDateRange(startDate, endDate);
        var current = SecurityUtils.currentUser();
        var scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findCalendars(
                        current.tenantId(), scope, organizationId, startDate, endDate,
                        offset, pageSize
                ),
                mapper.countCalendars(
                        current.tenantId(), scope, organizationId, startDate, endDate
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.CalendarRow calendar(long id) {
        var current = SecurityUtils.currentUser();
        OeeDtos.CalendarRow row = mapper.findCalendar(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (row == null) {
            throw notFound("OEE_CALENDAR_NOT_FOUND", "生产日历不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public long createCalendar(OeeDtos.SaveCalendarRequest request) {
        var current = SecurityUtils.currentUser();
        validateCalendar(current.tenantId(), request);
        if (mapper.countCalendarKey(current.tenantId(), request, null) > 0) {
            throw conflict("OEE_CALENDAR_EXISTS", "该组织、日期和班次的生产日历已存在");
        }
        mapper.insertCalendar(current.tenantId(), request, current.userId());
        Long id = mapper.findCalendarId(current.tenantId(), request);
        if (id == null) {
            throw internal("OEE_CALENDAR_CREATE_FAILED", "生产日历创建失败");
        }
        changeLogService.record(
                "OEE_CALENDAR", id, "CREATE", null,
                mapper.findCalendar(
                        current.tenantId(), id,
                        com.leantpm.security.datascope.DataPermission.all(current.userId())
                )
        );
        return id;
    }

    @Transactional
    public void updateCalendar(long id, OeeDtos.SaveCalendarRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.CalendarRow before = calendar(id);
        requireVersion(request.version());
        validateCalendar(current.tenantId(), request);
        if (mapper.countCalendarKey(current.tenantId(), request, id) > 0) {
            throw conflict("OEE_CALENDAR_EXISTS", "该组织、日期和班次的生产日历已存在");
        }
        if (mapper.updateCalendar(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "OEE_CALENDAR", id, "UPDATE", before,
                mapper.findCalendar(
                        current.tenantId(), id,
                        com.leantpm.security.datascope.DataPermission.all(current.userId())
                )
        );
    }

    @Transactional
    public void deleteCalendar(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.CalendarRow before = calendar(id);
        if (mapper.deleteCalendar(
                current.tenantId(), id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("OEE_CALENDAR", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.TargetRow> targets(
            String keyword,
            String targetLevel,
            Integer status,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        var scope = dataPermissionService.current();
        String normalizedLevel = upper(targetLevel);
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findTargets(
                        current.tenantId(), scope, clean(keyword), normalizedLevel,
                        status, offset, pageSize
                ),
                mapper.countTargets(
                        current.tenantId(), scope, clean(keyword), normalizedLevel, status
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.TargetRow target(long id) {
        var current = SecurityUtils.currentUser();
        OeeDtos.TargetRow row = mapper.findTarget(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (row == null) {
            throw notFound("OEE_TARGET_NOT_FOUND", "OEE目标不存在或无权访问");
        }
        return row;
    }

    @Transactional
    public long createTarget(OeeDtos.SaveTargetRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveTargetRequest normalized = normalizeTarget(request);
        validateTarget(current.tenantId(), normalized);
        if (mapper.countOverlappingTarget(current.tenantId(), normalized, null) > 0) {
            throw conflict("OEE_TARGET_OVERLAP", "相同范围已存在生效期重叠的OEE目标");
        }
        BigDecimal oeeTarget = targetProduct(normalized);
        mapper.insertTarget(current.tenantId(), normalized, oeeTarget, current.userId());
        Long id = mapper.lastInsertId();
        if (id == null || id <= 0) {
            throw internal("OEE_TARGET_CREATE_FAILED", "OEE目标创建失败");
        }
        changeLogService.record(
                "OEE_TARGET", id, "CREATE", null,
                mapper.findTarget(
                        current.tenantId(), id,
                        com.leantpm.security.datascope.DataPermission.all(current.userId())
                )
        );
        return id;
    }

    @Transactional
    public void updateTarget(long id, OeeDtos.SaveTargetRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.TargetRow before = target(id);
        requireVersion(request.version());
        OeeDtos.SaveTargetRequest normalized = normalizeTarget(request);
        validateTarget(current.tenantId(), normalized);
        if (mapper.countOverlappingTarget(current.tenantId(), normalized, id) > 0) {
            throw conflict("OEE_TARGET_OVERLAP", "相同范围已存在生效期重叠的OEE目标");
        }
        if (mapper.updateTarget(
                current.tenantId(), id, normalized, targetProduct(normalized),
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "OEE_TARGET", id, "UPDATE", before,
                mapper.findTarget(
                        current.tenantId(), id,
                        com.leantpm.security.datascope.DataPermission.all(current.userId())
                )
        );
    }

    @Transactional
    public void deleteTarget(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.TargetRow before = target(id);
        if (mapper.deleteTarget(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("OEE_TARGET", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<OeeDtos.LossReasonRow> lossReasons(
            String keyword,
            String lossCategory,
            Integer status,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        String normalizedCategory = upper(lossCategory);
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findLossReasons(
                        current.tenantId(), clean(keyword), normalizedCategory,
                        status, offset, pageSize
                ),
                mapper.countLossReasons(
                        current.tenantId(), clean(keyword), normalizedCategory, status
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public OeeDtos.LossReasonRow lossReason(long id) {
        OeeDtos.LossReasonRow row =
                mapper.findLossReason(SecurityUtils.currentUser().tenantId(), id);
        if (row == null) {
            throw notFound("OEE_LOSS_REASON_NOT_FOUND", "损失原因不存在");
        }
        return row;
    }

    @Transactional
    public long createLossReason(OeeDtos.SaveLossReasonRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.SaveLossReasonRequest normalized = normalizeLossReason(request);
        validateLossReason(current.tenantId(), null, normalized);
        if (mapper.countLossReasonCode(
                current.tenantId(), normalized.reasonCode(), null
        ) > 0) {
            throw conflict("OEE_LOSS_REASON_CODE_EXISTS", "损失原因编码已存在");
        }
        mapper.insertLossReason(current.tenantId(), normalized, current.userId());
        Long id = mapper.findLossReasonIdByCode(
                current.tenantId(), normalized.reasonCode()
        );
        if (id == null) {
            throw internal("OEE_LOSS_REASON_CREATE_FAILED", "损失原因创建失败");
        }
        changeLogService.record(
                "OEE_LOSS_REASON", id, "CREATE", null,
                mapper.findLossReason(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateLossReason(long id, OeeDtos.SaveLossReasonRequest request) {
        var current = SecurityUtils.currentUser();
        OeeDtos.LossReasonRow before = lossReason(id);
        requireVersion(request.version());
        OeeDtos.SaveLossReasonRequest normalized = normalizeLossReason(request);
        validateLossReason(current.tenantId(), id, normalized);
        if (!before.reasonCode().equals(normalized.reasonCode())) {
            throw new BusinessException(
                    "OEE_LOSS_REASON_CODE_IMMUTABLE", "损失原因编码不可修改"
            );
        }
        if (mapper.updateLossReason(
                current.tenantId(), id, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "OEE_LOSS_REASON", id, "UPDATE", before,
                mapper.findLossReason(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteLossReason(long id, int version) {
        var current = SecurityUtils.currentUser();
        OeeDtos.LossReasonRow before = lossReason(id);
        if (mapper.countLossReasonChildren(current.tenantId(), id) > 0) {
            throw conflict("OEE_LOSS_REASON_HAS_CHILDREN", "损失原因存在下级，不能删除");
        }
        if (mapper.countLossReasonReferences(current.tenantId(), id) > 0) {
            throw conflict("OEE_LOSS_REASON_IN_USE", "损失原因已被停机记录引用，不能删除");
        }
        if (mapper.deleteLossReason(
                current.tenantId(), id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("OEE_LOSS_REASON", id, "DELETE", before, null);
    }

    private OeeDtos.ShiftRow requireShift(long tenantId, long id) {
        OeeDtos.ShiftRow row = mapper.findShift(tenantId, id);
        if (row == null) {
            throw notFound("OEE_SHIFT_NOT_FOUND", "班次不存在");
        }
        return row;
    }

    private void validateShift(OeeDtos.SaveShiftRequest request) {
        LocalTime end = request.endTime();
        LocalDateTime startAt = LocalDateTime.of(java.time.LocalDate.of(2000, 1, 1), request.startTime());
        LocalDateTime endAt = LocalDateTime.of(
                java.time.LocalDate.of(2000, 1, request.crossDayFlag() ? 2 : 1), end
        );
        long elapsed = Duration.between(startAt, endAt).toMinutes();
        if (elapsed <= 0 || elapsed > 1440) {
            throw new BusinessException("OEE_SHIFT_TIME_INVALID", "班次起止时间无效");
        }
        if (request.breakMinutes() >= elapsed) {
            throw new BusinessException("OEE_SHIFT_BREAK_INVALID", "休息时间必须小于班次时长");
        }
        if (request.standardWorkMinutes() != elapsed - request.breakMinutes()) {
            throw new BusinessException(
                    "OEE_SHIFT_STANDARD_MINUTES_INVALID",
                    "标准工作分钟必须等于班次时长减休息时间"
            );
        }
    }

    private void validateCalendar(long tenantId, OeeDtos.SaveCalendarRequest request) {
        if (!dataPermissionService.current().canCreateIn(request.organizationId())
                && !dataPermissionService.current().allData()) {
            throw new BusinessException(
                    "DATA_SCOPE_DENIED", "无权在该组织维护生产日历", HttpStatus.FORBIDDEN
            );
        }
        if (mapper.countActiveOrganization(
                tenantId, request.organizationId(), dataPermissionService.current()
        ) == 0) {
            throw notFound("ORGANIZATION_NOT_FOUND", "组织不存在或无权访问");
        }
        OeeDtos.ShiftRow shift = requireShift(tenantId, request.shiftId());
        if (shift.status() != 1) {
            throw conflict("OEE_SHIFT_DISABLED", "班次已停用");
        }
        if (request.plannedDowntimeMinutes() > request.plannedWorkMinutes()) {
            throw new BusinessException(
                    "OEE_CALENDAR_MINUTES_INVALID", "计划停机不能大于计划工作时间"
            );
        }
        if ("HOLIDAY".equals(request.dayType()) && request.plannedWorkMinutes() > 0) {
            throw new BusinessException(
                    "OEE_HOLIDAY_MINUTES_INVALID", "休息日计划工作时间必须为0"
            );
        }
    }

    private void validateTarget(long tenantId, OeeDtos.SaveTargetRequest request) {
        if (request.effectiveEndDate() != null
                && request.effectiveEndDate().isBefore(request.effectiveStartDate())) {
            throw new BusinessException("OEE_TARGET_DATE_INVALID", "目标结束日期不能早于开始日期");
        }
        if ("EQUIPMENT".equals(request.targetLevel())) {
            if (request.equipmentId() == null || request.organizationId() != null) {
                throw new BusinessException(
                        "OEE_TARGET_SCOPE_INVALID", "设备目标必须且只能选择设备"
                );
            }
            if (mapper.countActiveEquipment(
                    tenantId, request.equipmentId(), dataPermissionService.current()
            ) == 0) {
                throw notFound("EQUIPMENT_NOT_FOUND", "设备不存在、未启用OEE或无权访问");
            }
            return;
        }
        if (request.organizationId() == null || request.equipmentId() != null) {
            throw new BusinessException(
                    "OEE_TARGET_SCOPE_INVALID", "组织目标必须且只能选择组织"
            );
        }
        if (mapper.countActiveOrganization(
                tenantId, request.organizationId(), dataPermissionService.current()
        ) == 0) {
            throw notFound("ORGANIZATION_NOT_FOUND", "组织不存在或无权访问");
        }
        String type = mapper.findOrganizationType(tenantId, request.organizationId());
        if (!request.targetLevel().equals(type)) {
            throw new BusinessException(
                    "OEE_TARGET_LEVEL_MISMATCH", "目标层级与所选组织类型不一致"
            );
        }
    }

    private void validateLossReason(
            long tenantId,
            Long id,
            OeeDtos.SaveLossReasonRequest request
    ) {
        long ancestorId = request.parentId();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        while (ancestorId > 0) {
            if ((id != null && ancestorId == id) || !visited.add(ancestorId)) {
                throw new BusinessException(
                        "OEE_LOSS_REASON_CYCLE", "损失原因层级不能形成循环"
                );
            }
            OeeDtos.LossReasonRow ancestor = mapper.findLossReason(tenantId, ancestorId);
            if (ancestor == null) {
                throw notFound(
                        "OEE_LOSS_REASON_PARENT_NOT_FOUND", "上级损失原因不存在"
                );
            }
            ancestorId = ancestor.parentId();
        }
        if (request.plannedFlag() && !"EXCLUDED".equals(request.affectsMetric())) {
            throw new BusinessException(
                    "OEE_LOSS_REASON_PLANNED_INVALID",
                    "计划停机原因必须从负荷时间中剔除"
            );
        }
        if (!request.plannedFlag() && "EXCLUDED".equals(request.affectsMetric())) {
            throw new BusinessException(
                    "OEE_LOSS_REASON_METRIC_INVALID",
                    "非计划损失不能配置为剔除项"
            );
        }
    }

    private OeeDtos.SaveShiftRequest normalizeShift(OeeDtos.SaveShiftRequest request) {
        return new OeeDtos.SaveShiftRequest(
                upper(request.shiftCode()),
                request.shiftName().trim(),
                request.startTime(),
                request.endTime(),
                request.crossDayFlag(),
                request.breakMinutes(),
                request.standardWorkMinutes(),
                request.sortOrder(),
                request.status(),
                clean(request.description()),
                request.version()
        );
    }

    private OeeDtos.SaveTargetRequest normalizeTarget(OeeDtos.SaveTargetRequest request) {
        return new OeeDtos.SaveTargetRequest(
                request.targetName().trim(),
                upper(request.targetLevel()),
                request.organizationId(),
                request.equipmentId(),
                scaleRate(request.availabilityTarget()),
                scaleRate(request.performanceTarget()),
                scaleRate(request.qualityTarget()),
                request.effectiveStartDate(),
                request.effectiveEndDate(),
                request.status(),
                clean(request.description()),
                request.version()
        );
    }

    private OeeDtos.SaveLossReasonRequest normalizeLossReason(
            OeeDtos.SaveLossReasonRequest request
    ) {
        return new OeeDtos.SaveLossReasonRequest(
                request.parentId(),
                upper(request.reasonCode()),
                request.reasonName().trim(),
                upper(request.lossCategory()),
                upper(request.affectsMetric()),
                request.plannedFlag(),
                clean(request.color()),
                request.sortOrder(),
                request.status(),
                clean(request.description()),
                request.version()
        );
    }

    private BigDecimal targetProduct(OeeDtos.SaveTargetRequest request) {
        return request.availabilityTarget()
                .multiply(request.performanceTarget())
                .multiply(request.qualityTarget())
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
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

    private void validateDateRange(java.time.LocalDate start, java.time.LocalDate end) {
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
