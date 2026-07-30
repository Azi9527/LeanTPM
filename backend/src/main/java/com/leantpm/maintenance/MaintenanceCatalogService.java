package com.leantpm.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class MaintenanceCatalogService {
    private static final Set<String> CHOICE_TYPES =
            Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE");

    private final MaintenanceMapper mapper;
    private final NumberRuleService numberRuleService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;

    public MaintenanceCatalogService(
            MaintenanceMapper mapper,
            NumberRuleService numberRuleService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.numberRuleService = numberRuleService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDtos.ItemRow> items(
            String keyword,
            String category,
            String resultType,
            Integer status,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        int offset = (page - 1) * pageSize;
        String normalizedCategory = upper(category);
        String normalizedResultType = upper(resultType);
        return PageResult.of(
                mapper.findItems(
                        current.tenantId(), clean(keyword), normalizedCategory,
                        normalizedResultType, status, offset, pageSize
                ),
                mapper.countItems(
                        current.tenantId(), clean(keyword), normalizedCategory,
                        normalizedResultType, status
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public MaintenanceDtos.ItemRow item(long id) {
        return requireItem(SecurityUtils.currentUser().tenantId(), id);
    }

    @Transactional
    public long createItem(MaintenanceDtos.SaveItemRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.SaveItemRequest normalized = normalizeItem(request);
        validateItem(normalized);
        if (mapper.countItemCode(current.tenantId(), normalized.itemCode(), null) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_CODE_EXISTS", "维保项目编码已存在", HttpStatus.CONFLICT
            );
        }
        mapper.insertItem(
                current.tenantId(), normalized, json(normalized.resultOptions()), current.userId()
        );
        Long id = mapper.findItemIdByCode(current.tenantId(), normalized.itemCode());
        if (id == null) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_CREATE_FAILED", "维保项目创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        changeLogService.record(
                "MAINTENANCE_ITEM", id, "CREATE", null, mapper.findItem(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateItem(long id, MaintenanceDtos.SaveItemRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.ItemRow before = requireItem(current.tenantId(), id);
        if (request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
        MaintenanceDtos.SaveItemRequest normalized = normalizeItem(request);
        validateItem(normalized);
        if (!before.itemCode().equals(normalized.itemCode())) {
            throw new BusinessException("MAINTENANCE_ITEM_CODE_IMMUTABLE", "维保项目编码不可修改");
        }
        if (mapper.updateItem(
                current.tenantId(), id, normalized, json(normalized.resultOptions()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "MAINTENANCE_ITEM", id, "UPDATE", before, mapper.findItem(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteItem(long id, int version) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.ItemRow before = requireItem(current.tenantId(), id);
        if (mapper.countPublishedItemReferences(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_IN_USE", "维保项目已被已发布方案引用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteItem(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("MAINTENANCE_ITEM", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDtos.SchemeRow> schemes(
            String keyword,
            String maintenanceType,
            Integer status,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findSchemes(
                        current.tenantId(), clean(keyword), upper(maintenanceType),
                        status, offset, pageSize
                ),
                mapper.countSchemes(
                        current.tenantId(), clean(keyword), upper(maintenanceType), status
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public MaintenanceDtos.SchemeDetail scheme(long id, Long versionId) {
        long tenantId = SecurityUtils.currentUser().tenantId();
        MaintenanceDtos.SchemeRow scheme = requireScheme(tenantId, id);
        Long selectedVersionId = versionId == null ? scheme.currentVersionId() : versionId;
        if (selectedVersionId == null) {
            List<MaintenanceDtos.SchemeVersionRow> history =
                    mapper.findSchemeVersions(tenantId, id);
            if (history.isEmpty()) {
                throw new BusinessException(
                        "MAINTENANCE_SCHEME_VERSION_NOT_FOUND", "维保方案尚无版本",
                        HttpStatus.NOT_FOUND
                );
            }
            selectedVersionId = history.getFirst().id();
        }
        MaintenanceDtos.SchemeVersionRow version =
                requireVersion(tenantId, selectedVersionId);
        if (version.schemeId() != id) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_MISMATCH", "方案版本不属于当前方案"
            );
        }
        return new MaintenanceDtos.SchemeDetail(
                scheme,
                version,
                mapper.findSchemeItems(tenantId, selectedVersionId),
                new MaintenanceDtos.SchemeApplicability(
                        mapper.findSchemeCategoryIds(tenantId, selectedVersionId),
                        mapper.findSchemeEquipmentIds(tenantId, selectedVersionId)
                ),
                mapper.findSchemeVersions(tenantId, id)
        );
    }

    @Transactional
    public long createScheme(MaintenanceDtos.SaveSchemeRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.SaveSchemeRequest normalized = normalizeScheme(request);
        validateScheme(current.tenantId(), normalized, dataPermissionService.current(), false);
        String code = normalized.schemeCode();
        if (code == null) {
            code = numberRuleService.generate(
                    current.tenantId(), current.userId(), "MAINTENANCE_SCHEME"
            ).businessNumber();
        }
        if (mapper.countSchemeCode(current.tenantId(), code, null) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_CODE_EXISTS", "维保方案编码已存在", HttpStatus.CONFLICT
            );
        }
        mapper.insertScheme(current.tenantId(), code, normalized, current.userId());
        Long schemeId = mapper.findSchemeIdByCode(current.tenantId(), code);
        if (schemeId == null) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_CREATE_FAILED", "维保方案创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        createDraftVersion(current.tenantId(), current.userId(), schemeId, normalized);
        changeLogService.record(
                "MAINTENANCE_SCHEME", schemeId, "CREATE", null,
                mapper.findScheme(current.tenantId(), schemeId)
        );
        return schemeId;
    }

    @Transactional
    public long createSchemeVersion(long schemeId, MaintenanceDtos.SaveSchemeRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.SchemeRow before = requireScheme(current.tenantId(), schemeId);
        if (request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少方案数据版本");
        }
        MaintenanceDtos.SaveSchemeRequest normalized = normalizeScheme(request);
        validateScheme(current.tenantId(), normalized, dataPermissionService.current(), true);
        if (normalized.schemeCode() != null
                && !before.schemeCode().equals(normalized.schemeCode())) {
            throw new BusinessException("MAINTENANCE_SCHEME_CODE_IMMUTABLE", "维保方案编码不可修改");
        }
        if (mapper.updateScheme(
                current.tenantId(), schemeId, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        long versionId = createDraftVersion(
                current.tenantId(), current.userId(), schemeId, normalized
        );
        changeLogService.record(
                "MAINTENANCE_SCHEME", schemeId, "NEW_VERSION", before,
                mapper.findScheme(current.tenantId(), schemeId)
        );
        return versionId;
    }

    @Transactional
    public void publish(long schemeId, long versionId) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.SchemeRow before = requireScheme(current.tenantId(), schemeId);
        MaintenanceDtos.SchemeVersionRow version = requireVersion(current.tenantId(), versionId);
        if (version.schemeId() != schemeId) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_MISMATCH", "方案版本不属于当前方案"
            );
        }
        if (!"DRAFT".equals(version.versionStatus())) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_IMMUTABLE", "只有草稿版本可以发布",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.findSchemeItems(current.tenantId(), versionId).isEmpty()) {
            throw new BusinessException("MAINTENANCE_SCHEME_ITEMS_EMPTY", "方案至少需要一个维保项目");
        }
        List<MaintenanceMapper.ApplicableEquipment> equipment =
                mapper.findApplicableEquipment(
                        current.tenantId(), versionId, dataPermissionService.current()
                );
        if (equipment.isEmpty()) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_NO_EQUIPMENT", "方案没有可生成计划的启用设备"
            );
        }
        if (mapper.publishSchemeVersion(
                current.tenantId(), versionId, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.retireOtherSchemeVersions(
                current.tenantId(), schemeId, versionId, current.userId()
        );
        mapper.setSchemeCurrentVersion(
                current.tenantId(), schemeId, versionId, current.userId()
        );
        mapper.cancelSupersededPlans(
                current.tenantId(), schemeId, versionId, current.userId()
        );
        for (MaintenanceMapper.ApplicableEquipment item : equipment) {
            mapper.insertPlan(
                    current.tenantId(), schemeId, versionId, item.id(),
                    firstOccurrence(version), current.userId()
            );
        }
        changeLogService.record(
                "MAINTENANCE_SCHEME", schemeId, "PUBLISH", before,
                mapper.findScheme(current.tenantId(), schemeId)
        );
    }

    @Transactional(readOnly = true)
    public PageResult<MaintenanceDtos.PlanRow> plans(
            String keyword,
            String planStatus,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        String normalizedStatus = upper(planStatus);
        return PageResult.of(
                mapper.findPlans(
                        current.tenantId(), scope, clean(keyword), normalizedStatus,
                        offset, pageSize
                ),
                mapper.countPlans(
                        current.tenantId(), scope, clean(keyword), normalizedStatus
                ),
                page,
                pageSize
        );
    }

    @Transactional
    public void updatePlanStatus(long id, MaintenanceDtos.UpdatePlanStatusRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.PlanRow before = requirePlan(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (("PAUSED".equals(request.planStatus()) || "CANCELLED".equals(request.planStatus()))
                && clean(request.reason()) == null) {
            throw new BusinessException("MAINTENANCE_PLAN_REASON_REQUIRED", "暂停或取消计划必须填写原因");
        }
        if (mapper.updatePlanStatus(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "MAINTENANCE_PLAN", id, "STATUS_CHANGE", before,
                mapper.findPlan(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void updatePlanMeter(long id, MaintenanceDtos.UpdateMeterRequest request) {
        var current = SecurityUtils.currentUser();
        MaintenanceDtos.PlanRow before = requirePlan(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (!Set.of("RUNNING_HOURS", "PRODUCTION_QUANTITY").contains(before.cycleType())) {
            throw new BusinessException(
                    "MAINTENANCE_PLAN_NOT_METER_BASED", "只有运行小时或产量计划可以维护累计值"
            );
        }
        if (request.currentValue().signum() < 0
                || request.currentValue().compareTo(before.currentMeterValue()) < 0) {
            throw new BusinessException(
                    "MAINTENANCE_METER_VALUE_INVALID", "累计值不能为负数或小于上次读数"
            );
        }
        if (mapper.updatePlanMeter(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "MAINTENANCE_PLAN", id, "METER_UPDATE", before,
                mapper.findPlan(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    private long createDraftVersion(
            long tenantId,
            long operatorId,
            long schemeId,
            MaintenanceDtos.SaveSchemeRequest request
    ) {
        int number = mapper.nextSchemeVersionNumber(tenantId, schemeId);
        mapper.insertSchemeVersion(tenantId, schemeId, number, request, operatorId);
        Long versionId = mapper.findSchemeVersionId(tenantId, schemeId, number);
        if (versionId == null) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_CREATE_FAILED", "方案版本创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        for (MaintenanceDtos.SaveSchemeItemRequest item : request.items()) {
            mapper.insertSchemeItem(tenantId, versionId, item, operatorId);
        }
        for (Long categoryId : distinct(request.categoryIds())) {
            mapper.insertSchemeCategory(tenantId, versionId, categoryId, operatorId);
        }
        for (Long equipmentId : distinct(request.equipmentIds())) {
            mapper.insertSchemeEquipment(tenantId, versionId, equipmentId, operatorId);
        }
        return versionId;
    }

    private void validateItem(MaintenanceDtos.SaveItemRequest request) {
        if (request.minimumValue() != null && request.maximumValue() != null
                && request.minimumValue().compareTo(request.maximumValue()) > 0) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_RANGE_INVALID", "维保项目下限不能大于上限"
            );
        }
        if (Boolean.TRUE.equals(request.numericRequired())
                && !"NUMBER".equals(request.resultType())) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_NUMERIC_TYPE_INVALID", "要求数值时结果类型必须为数值"
            );
        }
        if (CHOICE_TYPES.contains(request.resultType())
                && (request.resultOptions() == null || request.resultOptions().isEmpty())) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_OPTIONS_REQUIRED", "选择型结果必须配置选项"
            );
        }
    }

    private void validateScheme(
            long tenantId,
            MaintenanceDtos.SaveSchemeRequest request,
            DataPermission scope,
            boolean updating
    ) {
        if (request.expiryDate() != null
                && request.effectiveDate().isAfter(request.expiryDate())) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_DATE_INVALID", "方案生效日期不能晚于失效日期"
            );
        }
        if ("WEEKLY".equals(request.cycleType()) && clean(request.weekDays()) == null) {
            throw new BusinessException("MAINTENANCE_SCHEME_WEEK_DAYS_REQUIRED", "周计划必须选择星期");
        }
        if ("MONTHLY".equals(request.cycleType()) && clean(request.monthDays()) == null) {
            throw new BusinessException("MAINTENANCE_SCHEME_MONTH_DAYS_REQUIRED", "月计划必须选择日期");
        }
        if (Set.of("RUNNING_HOURS", "PRODUCTION_QUANTITY").contains(request.cycleType())
                && (request.triggerThreshold() == null
                || request.triggerThreshold().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_TRIGGER_REQUIRED", "累计值触发计划必须配置大于零的触发阈值"
            );
        }
        Set<Long> itemIds = new LinkedHashSet<>();
        for (MaintenanceDtos.SaveSchemeItemRequest item : request.items()) {
            if (!itemIds.add(item.maintenanceItemId())) {
                throw new BusinessException("MAINTENANCE_SCHEME_ITEM_DUPLICATE", "方案项目不能重复");
            }
            MaintenanceDtos.ItemRow definition = requireItem(tenantId, item.maintenanceItemId());
            if (definition.status() != 1) {
                throw new BusinessException(
                        "MAINTENANCE_SCHEME_ITEM_DISABLED", "方案不能引用停用维保项目"
                );
            }
        }
        Set<Long> categories = distinct(request.categoryIds());
        Set<Long> equipment = distinct(request.equipmentIds());
        if (categories.isEmpty() && equipment.isEmpty()) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_SCOPE_REQUIRED", "至少选择一个设备分类或具体设备"
            );
        }
        for (Long categoryId : categories) {
            if (mapper.countActiveCategory(tenantId, categoryId) == 0) {
                throw new BusinessException(
                        "EQUIPMENT_CATEGORY_NOT_FOUND", "设备分类不存在或已停用",
                        HttpStatus.NOT_FOUND
                );
            }
        }
        for (Long equipmentId : equipment) {
            if (mapper.countActiveEquipment(tenantId, equipmentId, scope) == 0) {
                throw new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在、已停用或无权访问",
                        HttpStatus.NOT_FOUND
                );
            }
        }
        if (request.defaultAssigneeUserId() != null
                && mapper.countActiveUser(tenantId, request.defaultAssigneeUserId()) == 0) {
            throw new BusinessException(
                    "USER_NOT_FOUND", "默认执行人不存在或已停用", HttpStatus.NOT_FOUND
            );
        }
        if (updating && request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少方案数据版本");
        }
    }

    private MaintenanceDtos.SaveItemRequest normalizeItem(
            MaintenanceDtos.SaveItemRequest request
    ) {
        return new MaintenanceDtos.SaveItemRequest(
                request.itemCode().trim().toUpperCase(Locale.ROOT),
                request.itemName().trim(),
                request.itemCategory().trim().toUpperCase(Locale.ROOT),
                clean(request.maintenancePart()),
                request.maintenanceContent().trim(),
                clean(request.maintenanceMethod()),
                clean(request.maintenanceTool()),
                request.maintenanceStandard().trim(),
                clean(request.standardValue()),
                request.minimumValue(),
                request.maximumValue(),
                clean(request.unit()),
                request.resultType().trim().toUpperCase(Locale.ROOT),
                request.resultOptions() == null ? List.of()
                        : request.resultOptions().stream().map(String::trim).distinct().toList(),
                request.required(),
                request.photoRequired(),
                request.attachmentRequired(),
                request.numericRequired(),
                request.skipAllowed(),
                request.stopRequired(),
                request.abnormalSeverity().trim().toUpperCase(Locale.ROOT),
                clean(request.abnormalAdvice()),
                request.standardMinutes(),
                clean(request.safetyNotes()),
                request.enabled(),
                clean(request.description()),
                request.version()
        );
    }

    private MaintenanceDtos.SaveSchemeRequest normalizeScheme(
            MaintenanceDtos.SaveSchemeRequest request
    ) {
        return new MaintenanceDtos.SaveSchemeRequest(
                upper(request.schemeCode()),
                request.schemeName().trim(),
                request.maintenanceType().trim().toUpperCase(Locale.ROOT),
                request.cycleType().trim().toUpperCase(Locale.ROOT),
                request.cycleInterval(),
                request.triggerThreshold(),
                clean(request.weekDays()),
                clean(request.monthDays()),
                request.scheduledTime(),
                request.reminderDays(),
                request.generationLeadDays(),
                upper(request.shiftCode()),
                request.defaultAssigneeUserId(),
                upper(request.defaultTeamCode()),
                request.reviewRequired(),
                request.backfillAllowed(),
                request.stopRequired(),
                upper(request.restoreStatusCode()),
                request.effectiveDate(),
                request.expiryDate(),
                request.items(),
                List.copyOf(distinct(request.categoryIds())),
                List.copyOf(distinct(request.equipmentIds())),
                request.enabled(),
                clean(request.description()),
                clean(request.changeSummary()),
                request.version()
        );
    }

    private LocalDate firstOccurrence(MaintenanceDtos.SchemeVersionRow version) {
        LocalDate date = version.effectiveDate().isAfter(LocalDate.now())
                ? version.effectiveDate() : LocalDate.now();
        for (int i = 0; i < 370; i++) {
            if (matchesSchedule(version.cycleType(), version.weekDays(), version.monthDays(), date)) {
                return date;
            }
            date = date.plusDays(1);
        }
        throw new BusinessException("MAINTENANCE_SCHEME_SCHEDULE_INVALID", "无法计算方案首次执行日期");
    }

    private boolean matchesSchedule(
            String cycleType,
            String weekDays,
            String monthDays,
            LocalDate date
    ) {
        return switch (cycleType) {
            case "WEEKLY" -> csvContains(weekDays, date.getDayOfWeek().getValue());
            case "MONTHLY" -> csvContains(monthDays, date.getDayOfMonth());
            default -> true;
        };
    }

    private boolean csvContains(String csv, int value) {
        if (csv == null) {
            return false;
        }
        for (String token : csv.split(",")) {
            try {
                if (Integer.parseInt(token.trim()) == value) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                throw new BusinessException(
                        "MAINTENANCE_SCHEME_SCHEDULE_INVALID", "方案周期日期格式不正确"
                );
            }
        }
        return false;
    }

    private MaintenanceDtos.ItemRow requireItem(long tenantId, long id) {
        MaintenanceDtos.ItemRow item = mapper.findItem(tenantId, id);
        if (item == null) {
            throw new BusinessException(
                    "MAINTENANCE_ITEM_NOT_FOUND", "维保项目不存在", HttpStatus.NOT_FOUND
            );
        }
        return item;
    }

    private MaintenanceDtos.SchemeRow requireScheme(long tenantId, long id) {
        MaintenanceDtos.SchemeRow scheme = mapper.findScheme(tenantId, id);
        if (scheme == null) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_NOT_FOUND", "维保方案不存在", HttpStatus.NOT_FOUND
            );
        }
        return scheme;
    }

    private MaintenanceDtos.SchemeVersionRow requireVersion(long tenantId, long id) {
        MaintenanceDtos.SchemeVersionRow version = mapper.findSchemeVersion(tenantId, id);
        if (version == null) {
            throw new BusinessException(
                    "MAINTENANCE_SCHEME_VERSION_NOT_FOUND", "维保方案版本不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return version;
    }

    private MaintenanceDtos.PlanRow requirePlan(long tenantId, long id, DataPermission scope) {
        MaintenanceDtos.PlanRow plan = mapper.findPlan(tenantId, id, scope);
        if (plan == null) {
            throw new BusinessException(
                    "MAINTENANCE_PLAN_NOT_FOUND", "维保计划不存在或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return plan;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "MAINTENANCE_JSON_INVALID", "维保配置序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private <T> Set<T> distinct(List<T> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
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
