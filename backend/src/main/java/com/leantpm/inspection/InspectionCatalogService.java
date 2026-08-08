package com.leantpm.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.common.query.TableQuery;
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
public class InspectionCatalogService {
    private static final Set<String> CHOICE_TYPES =
            Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE");

    private final InspectionMapper mapper;
    private final InspectionCalendarMapper calendarMapper;
    private final NumberRuleService numberRuleService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;
    private final InspectionTaskService taskService;

    public InspectionCatalogService(
            InspectionMapper mapper,
            InspectionCalendarMapper calendarMapper,
            NumberRuleService numberRuleService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper,
            InspectionTaskService taskService
    ) {
        this.mapper = mapper;
        this.calendarMapper = calendarMapper;
        this.numberRuleService = numberRuleService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.ItemRow> items(
            String keyword,
            Long organizationId,
            String category,
            String resultType,
            Integer status,
            int page,
            int pageSize
    ) {
        return items(keyword, organizationId, category, resultType, status,
                TableQuery.empty(), page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.ItemRow> items(
            String keyword,
            Long organizationId,
            String category,
            String resultType,
            Integer status,
            TableQuery tableQuery,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        String normalizedCategory = upper(category);
        String normalizedResultType = upper(resultType);
        return PageResult.of(
                mapper.findItems(
                        current.tenantId(), scope, clean(keyword), organizationId,
                        normalizedCategory,
                        normalizedResultType, status, tableQuery, offset, pageSize
                ),
                mapper.countItems(
                        current.tenantId(), scope, clean(keyword), organizationId,
                        normalizedCategory,
                        normalizedResultType, status, tableQuery
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public InspectionDtos.ItemRow item(long id) {
        var current = SecurityUtils.currentUser();
        return requireItem(current.tenantId(), id, dataPermissionService.current());
    }

    @Transactional
    public long createItem(InspectionDtos.SaveItemRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SaveItemRequest normalized = normalizeItem(request);
        requireOrganizationAccess(normalized.organizationId());
        validateItem(normalized);
        if (mapper.countItemCode(current.tenantId(), normalized.itemCode(), null) > 0) {
            throw new BusinessException(
                    "INSPECTION_ITEM_CODE_EXISTS", "点检项目编码已存在", HttpStatus.CONFLICT
            );
        }
        mapper.insertItem(
                current.tenantId(), normalized, json(normalized.resultOptions()), current.userId()
        );
        Long id = mapper.findItemIdByCode(current.tenantId(), normalized.itemCode());
        if (id == null) {
            throw new BusinessException(
                    "INSPECTION_ITEM_CREATE_FAILED", "点检项目创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        changeLogService.record(
                "INSPECTION_ITEM", id, "CREATE", null, mapper.findItem(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateItem(long id, InspectionDtos.SaveItemRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.ItemRow before = requireItem(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
        InspectionDtos.SaveItemRequest normalized = normalizeItem(request);
        requireOrganizationAccess(normalized.organizationId());
        validateItem(normalized);
        if (!before.itemCode().equals(normalized.itemCode())) {
            throw new BusinessException("INSPECTION_ITEM_CODE_IMMUTABLE", "点检项目编码不可修改");
        }
        if (mapper.updateItem(
                current.tenantId(), id, normalized, json(normalized.resultOptions()), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_ITEM", id, "UPDATE", before, mapper.findItem(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteItem(long id, int version) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.ItemRow before = requireItem(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (mapper.countPublishedItemReferences(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "INSPECTION_ITEM_IN_USE", "点检项目已被已发布方案引用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteItem(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("INSPECTION_ITEM", id, "DELETE", before, null);
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.SchemeRow> schemes(
            String keyword,
            String inspectionType,
            Integer status,
            int page,
            int pageSize
    ) {
        return schemes(keyword, inspectionType, status, TableQuery.empty(), page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.SchemeRow> schemes(
            String keyword,
            String inspectionType,
            Integer status,
            TableQuery tableQuery,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findSchemes(
                        current.tenantId(), clean(keyword), upper(inspectionType),
                        status, tableQuery, offset, pageSize
                ),
                mapper.countSchemes(
                        current.tenantId(), clean(keyword), upper(inspectionType), status,
                        tableQuery
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public InspectionDtos.SchemeDetail scheme(long id, Long versionId) {
        long tenantId = SecurityUtils.currentUser().tenantId();
        InspectionDtos.SchemeRow scheme = requireScheme(tenantId, id);
        Long selectedVersionId = versionId == null ? scheme.currentVersionId() : versionId;
        if (selectedVersionId == null) {
            List<InspectionDtos.SchemeVersionRow> history =
                    mapper.findSchemeVersions(tenantId, id);
            if (history.isEmpty()) {
                throw new BusinessException(
                        "INSPECTION_SCHEME_VERSION_NOT_FOUND", "点检方案尚无版本",
                        HttpStatus.NOT_FOUND
                );
            }
            selectedVersionId = history.getFirst().id();
        }
        InspectionDtos.SchemeVersionRow version =
                requireVersion(tenantId, selectedVersionId);
        if (version.schemeId() != id) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_MISMATCH", "方案版本不属于当前方案"
            );
        }
        return new InspectionDtos.SchemeDetail(
                scheme,
                version,
                mapper.findSchemeItems(tenantId, selectedVersionId),
                new InspectionDtos.SchemeApplicability(
                        mapper.findSchemeCategoryIds(tenantId, selectedVersionId),
                        mapper.findSchemeEquipmentIds(tenantId, selectedVersionId)
                ),
                mapper.findSchemeVersions(tenantId, id)
        );
    }

    @Transactional(readOnly = true)
    public List<Long> applicableEquipmentIds(long schemeId) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SchemeRow scheme = requireScheme(current.tenantId(), schemeId);
        if (scheme.currentVersionId() == null
                || !"PUBLISHED".equals(scheme.currentVersionStatus())) {
            return List.of();
        }
        return mapper.findApplicableEquipment(
                current.tenantId(), scheme.currentVersionId(), dataPermissionService.current()
        ).stream().map(InspectionMapper.ApplicableEquipment::id).toList();
    }

    @Transactional
    public long createScheme(InspectionDtos.SaveSchemeRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SaveSchemeRequest normalized = normalizeScheme(request);
        validateScheme(current.tenantId(), normalized, dataPermissionService.current(), false);
        String code = normalized.schemeCode();
        if (code == null) {
            code = numberRuleService.generate(
                    current.tenantId(), current.userId(), "INSPECTION_SCHEME"
            ).businessNumber();
        }
        if (mapper.countSchemeCode(current.tenantId(), code, null) > 0) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_CODE_EXISTS", "点检方案编码已存在", HttpStatus.CONFLICT
            );
        }
        mapper.insertScheme(current.tenantId(), code, normalized, current.userId());
        Long schemeId = mapper.findSchemeIdByCode(current.tenantId(), code);
        if (schemeId == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_CREATE_FAILED", "点检方案创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        createDraftVersion(current.tenantId(), current.userId(), schemeId, normalized);
        changeLogService.record(
                "INSPECTION_SCHEME", schemeId, "CREATE", null,
                mapper.findScheme(current.tenantId(), schemeId)
        );
        return schemeId;
    }

    @Transactional
    public long createSchemeVersion(long schemeId, InspectionDtos.SaveSchemeRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SchemeRow before = requireScheme(current.tenantId(), schemeId);
        if (request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少方案数据版本");
        }
        InspectionDtos.SaveSchemeRequest normalized = normalizeScheme(request);
        validateScheme(current.tenantId(), normalized, dataPermissionService.current(), true);
        if (normalized.schemeCode() != null
                && !before.schemeCode().equals(normalized.schemeCode())) {
            throw new BusinessException("INSPECTION_SCHEME_CODE_IMMUTABLE", "点检方案编码不可修改");
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
                "INSPECTION_SCHEME", schemeId, "NEW_VERSION", before,
                mapper.findScheme(current.tenantId(), schemeId)
        );
        return versionId;
    }

    @Transactional
    public long createAndPublishScheme(InspectionDtos.SaveSchemeRequest request) {
        long schemeId = createScheme(request);
        List<InspectionDtos.SchemeVersionRow> versions = mapper.findSchemeVersions(
                SecurityUtils.currentUser().tenantId(), schemeId
        );
        InspectionDtos.SchemeVersionRow draft = versions.stream()
                .filter(version -> "DRAFT".equals(version.versionStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "INSPECTION_SCHEME_VERSION_NOT_FOUND", "点检方案版本创建失败"
                ));
        publish(schemeId, draft.id());
        return schemeId;
    }

    @Transactional
    public long createAndPublishSchemeVersion(
            long schemeId,
            InspectionDtos.SaveSchemeRequest request
    ) {
        long versionId = createSchemeVersion(schemeId, request);
        publish(schemeId, versionId);
        return versionId;
    }

    @Transactional
    public void updateSchemeStatus(
            long schemeId,
            InspectionDtos.UpdateSchemeStatusRequest request
    ) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SchemeRow before = requireScheme(current.tenantId(), schemeId);
        if (mapper.updateSchemeStatus(
                current.tenantId(), schemeId, request.enabled(), request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_SCHEME", schemeId,
                request.enabled() ? "ENABLE" : "DISABLE", before,
                mapper.findScheme(current.tenantId(), schemeId)
        );
    }

    @Transactional
    public void publish(long schemeId, long versionId) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.SchemeRow before = requireScheme(current.tenantId(), schemeId);
        InspectionDtos.SchemeVersionRow version = requireVersion(current.tenantId(), versionId);
        if (version.schemeId() != schemeId) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_MISMATCH", "方案版本不属于当前方案"
            );
        }
        if (!"DRAFT".equals(version.versionStatus())) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_IMMUTABLE", "只有草稿版本可以发布",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.findSchemeItems(current.tenantId(), versionId).isEmpty()) {
            throw new BusinessException("INSPECTION_SCHEME_ITEMS_EMPTY", "方案至少需要一个点检项目");
        }
        List<InspectionMapper.ApplicableEquipment> equipment =
                mapper.findApplicableEquipment(
                        current.tenantId(), versionId, dataPermissionService.current()
                );
        if (equipment.isEmpty()) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NO_EQUIPMENT", "方案没有可生成计划的启用设备"
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
        for (InspectionMapper.ApplicableEquipment item : equipment) {
            mapper.insertPlan(
                    current.tenantId(), schemeId, versionId, item.id(),
                    firstOccurrence(version), current.userId()
            );
        }
        changeLogService.record(
                "INSPECTION_SCHEME", schemeId, "PUBLISH", before,
                mapper.findScheme(current.tenantId(), schemeId)
        );
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.PlanRow> plans(
            String keyword,
            String planStatus,
            int page,
            int pageSize
    ) {
        return plans(keyword, planStatus, TableQuery.empty(), page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResult<InspectionDtos.PlanRow> plans(
            String keyword,
            String planStatus,
            TableQuery tableQuery,
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
                        tableQuery, offset, pageSize
                ),
                mapper.countPlans(
                        current.tenantId(), scope, clean(keyword), normalizedStatus,
                        tableQuery
                ),
                page,
                pageSize
        );
    }

    @Transactional
    public InspectionDtos.CreatePlansResult createPlans(
            InspectionDtos.CreatePlansRequest request
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        InspectionDtos.SchemeRow scheme = requireScheme(
                current.tenantId(), request.schemeId()
        );
        if (scheme.status() == null || scheme.status() != 1
                || scheme.currentVersionId() == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NOT_PUBLISHED", "只能使用已启用并发布的点检方案"
            );
        }
        InspectionDtos.SchemeVersionRow version = requireVersion(
                current.tenantId(), scheme.currentVersionId()
        );
        if (!"PUBLISHED".equals(version.versionStatus())) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NOT_PUBLISHED", "只能使用已发布的点检方案"
            );
        }

        Set<Long> equipmentIds = distinct(request.equipmentIds());
        Set<Long> applicableEquipmentIds = mapper.findApplicableEquipment(
                current.tenantId(), version.id(), scope
        ).stream().map(InspectionMapper.ApplicableEquipment::id).collect(java.util.stream.Collectors.toSet());
        for (Long equipmentId : equipmentIds) {
            if (mapper.countActiveEquipment(
                    current.tenantId(), equipmentId, scope
            ) == 0) {
                throw new BusinessException(
                        "INSPECTION_PLAN_EQUIPMENT_INVALID",
                        "所选设备不存在、未启用或超出当前数据权限"
                );
            }
            if (!applicableEquipmentIds.contains(equipmentId)) {
                throw new BusinessException(
                        "INSPECTION_PLAN_SCHEME_NOT_APPLICABLE",
                        "所选点检方案不适用于该设备，请重新选择方案或设备"
                );
            }
        }

        LocalDate nextGenerationDate = firstOccurrence(version);
        int processed = 0;
        for (Long equipmentId : equipmentIds) {
            if (mapper.insertPlan(
                    current.tenantId(), scheme.id(), version.id(), equipmentId,
                    nextGenerationDate, current.userId()
            ) > 0) {
                processed++;
            }
        }
        changeLogService.record(
                "INSPECTION_SCHEME", scheme.id(), "MANUAL_PLAN_CREATE",
                null, request
        );
        return new InspectionDtos.CreatePlansResult(processed, nextGenerationDate);
    }

    @Transactional
    public void updatePlanStatus(long id, InspectionDtos.UpdatePlanStatusRequest request) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.PlanRow before = requirePlan(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (("PAUSED".equals(request.planStatus()) || "CANCELLED".equals(request.planStatus()))
                && clean(request.reason()) == null) {
            throw new BusinessException("INSPECTION_PLAN_REASON_REQUIRED", "暂停或取消计划必须填写原因");
        }
        if (mapper.updatePlanStatus(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_PLAN", id, "STATUS_CHANGE", before,
                mapper.findPlan(current.tenantId(), id, DataPermission.all(current.userId()))
        );
    }

    @Transactional
    public void deletePlan(long id, int version) {
        var current = SecurityUtils.currentUser();
        InspectionDtos.PlanRow before = requirePlan(
                current.tenantId(), id, dataPermissionService.current()
        );
        for (Long taskId : mapper.findTaskIdsByPlan(current.tenantId(), id)) {
            taskService.softDeleteTaskCascade(
                    current.tenantId(), taskId, null, current.userId()
            );
        }
        if (mapper.softDeletePlan(
                current.tenantId(), id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("INSPECTION_PLAN", id, "DELETE", before, null);
    }

    private long createDraftVersion(
            long tenantId,
            long operatorId,
            long schemeId,
            InspectionDtos.SaveSchemeRequest request
    ) {
        int number = mapper.nextSchemeVersionNumber(tenantId, schemeId);
        mapper.insertSchemeVersion(tenantId, schemeId, number, request, operatorId);
        Long versionId = mapper.findSchemeVersionId(tenantId, schemeId, number);
        if (versionId == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_CREATE_FAILED", "方案版本创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        List<Long> assigneeIds = request.defaultAssigneeUserIds();
        for (int index = 0; index < assigneeIds.size(); index++) {
            mapper.insertSchemeDefaultAssignee(
                    tenantId, versionId, assigneeIds.get(index),
                    index == 0, index * 10, operatorId
            );
        }
        for (InspectionDtos.SaveSchemeItemRequest item : request.items()) {
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

    private void validateItem(InspectionDtos.SaveItemRequest request) {
        if ("NUMBER".equals(request.resultType())
                && request.minimumValue() == null && request.maximumValue() == null) {
            throw new BusinessException(
                    "INSPECTION_ITEM_NUMBER_RANGE_REQUIRED", "数值结果请至少填写数值下限或数值上限"
            );
        }
        if (request.minimumValue() != null && request.maximumValue() != null
                && request.minimumValue().compareTo(request.maximumValue()) > 0) {
            throw new BusinessException(
                    "INSPECTION_ITEM_RANGE_INVALID", "点检项目下限不能大于上限"
            );
        }
        if (Boolean.TRUE.equals(request.numericRequired())
                && !"NUMBER".equals(request.resultType())) {
            throw new BusinessException(
                    "INSPECTION_ITEM_NUMERIC_TYPE_INVALID", "要求数值时结果类型必须为数值"
            );
        }
        if (CHOICE_TYPES.contains(request.resultType())
                && (request.resultOptions() == null || request.resultOptions().isEmpty())) {
            throw new BusinessException(
                    "INSPECTION_ITEM_OPTIONS_REQUIRED", "选择型结果必须配置选项"
            );
        }
        if (request.photoMinCount() > request.photoMaxCount()) {
            throw new BusinessException(
                    "INSPECTION_ITEM_PHOTO_COUNT_INVALID", "最少照片数不能大于最多照片数"
            );
        }
        if (Boolean.TRUE.equals(request.photoRequired()) && request.photoMinCount() < 1) {
            throw new BusinessException(
                    "INSPECTION_ITEM_PHOTO_REQUIRED_INVALID", "必须拍照时最少照片数不能小于 1"
            );
        }
    }

    private void validateScheme(
            long tenantId,
            InspectionDtos.SaveSchemeRequest request,
            DataPermission scope,
            boolean updating
    ) {
        if (request.expiryDate() != null
                && request.effectiveDate().isAfter(request.expiryDate())) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_DATE_INVALID", "方案生效日期不能晚于失效日期"
            );
        }
        if ("WEEKLY".equals(request.cycleType()) && clean(request.weekDays()) == null) {
            throw new BusinessException("INSPECTION_SCHEME_WEEK_DAYS_REQUIRED", "周计划必须选择星期");
        }
        if ("MONTHLY".equals(request.cycleType()) && clean(request.monthDays()) == null) {
            throw new BusinessException("INSPECTION_SCHEME_MONTH_DAYS_REQUIRED", "月计划必须选择日期");
        }
        Set<Long> itemIds = new LinkedHashSet<>();
        for (InspectionDtos.SaveSchemeItemRequest item : request.items()) {
            if (!itemIds.add(item.inspectionItemId())) {
                throw new BusinessException("INSPECTION_SCHEME_ITEM_DUPLICATE", "方案项目不能重复");
            }
            InspectionDtos.ItemRow definition = requireItem(
                    tenantId, item.inspectionItemId(), scope
            );
            if (definition.status() != 1) {
                throw new BusinessException(
                        "INSPECTION_SCHEME_ITEM_DISABLED", "方案不能引用停用点检项目"
                );
            }
        }
        Set<Long> categories = distinct(request.categoryIds());
        Set<Long> equipment = distinct(request.equipmentIds());
        if (categories.isEmpty() && equipment.isEmpty()) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_SCOPE_REQUIRED", "至少选择一个设备分类或具体设备"
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
        for (Long userId : request.defaultAssigneeUserIds()) {
            if (mapper.countActiveUser(tenantId, userId) == 0) {
            throw new BusinessException(
                    "USER_NOT_FOUND", "默认执行人不存在或已停用", HttpStatus.NOT_FOUND
            );
            }
        }
        if (request.generationLeadMinutes() > 43_200) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_GENERATION_LEAD_INVALID",
                    "提前生成时间不能超过 30 天"
            );
        }
        if (request.workCalendarId() == null
                || calendarMapper.countActiveCalendar(
                tenantId, request.workCalendarId()
        ) == 0) {
            throw new BusinessException(
                    "INSPECTION_WORK_CALENDAR_NOT_FOUND",
                    "请选择有效的点检工作日历",
                    HttpStatus.NOT_FOUND
            );
        }
        if (updating && request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少方案数据版本");
        }
    }

    private InspectionDtos.SaveItemRequest normalizeItem(
            InspectionDtos.SaveItemRequest request
    ) {
        String resultType = request.resultType().trim().toUpperCase(Locale.ROOT);
        boolean numeric = "NUMBER".equals(resultType);
        boolean normalAbnormal = "NORMAL_ABNORMAL".equals(resultType);
        boolean passFail = "PASS_FAIL".equals(resultType);
        String standardValue = clean(request.standardValue());
        List<String> resultOptions = request.resultOptions() == null ? List.of()
                : request.resultOptions().stream().map(String::trim).distinct().toList();
        if (normalAbnormal) {
            standardValue = "ABNORMAL".equalsIgnoreCase(standardValue) ? "ABNORMAL" : "NORMAL";
            resultOptions = List.of("NORMAL", "ABNORMAL");
        } else if (passFail) {
            standardValue = "FAIL".equalsIgnoreCase(standardValue) ? "FAIL" : "PASS";
            resultOptions = List.of("PASS", "FAIL");
        }
        return new InspectionDtos.SaveItemRequest(
                request.itemCode().trim().toUpperCase(Locale.ROOT),
                request.itemName().trim(),
                request.organizationId(),
                request.itemCategory().trim().toUpperCase(Locale.ROOT),
                clean(request.inspectionPart()),
                request.inspectionContent().trim(),
                clean(request.inspectionMethod()),
                clean(request.inspectionTool()),
                request.inspectionStandard().trim(),
                numeric ? null : standardValue,
                numeric ? request.minimumValue() : null,
                numeric ? request.maximumValue() : null,
                numeric ? clean(request.unit()) : null,
                resultType,
                resultOptions,
                request.required(),
                request.photoRequired(),
                request.photoMinCount(),
                request.photoMaxCount(),
                request.photoMaxSizeMb(),
                request.photoAllowedTypes().trim().toLowerCase(Locale.ROOT),
                request.photoCompressionQuality(),
                numeric,
                request.skipAllowed(),
                request.abnormalSeverity().trim().toUpperCase(Locale.ROOT),
                clean(request.abnormalAdvice()),
                request.abnormalDefaultStop(),
                request.standardMinutes(),
                clean(request.safetyNotes()),
                request.enabled(),
                clean(request.description()),
                request.version()
        );
    }

    private InspectionDtos.SaveSchemeRequest normalizeScheme(
            InspectionDtos.SaveSchemeRequest request
    ) {
        List<Long> defaultAssigneeUserIds = List.copyOf(distinct(
                request.defaultAssigneeUserIds() == null
                        || request.defaultAssigneeUserIds().isEmpty()
                        ? (request.defaultAssigneeUserId() == null
                        ? List.of() : List.of(request.defaultAssigneeUserId()))
                        : request.defaultAssigneeUserIds()
        ));
        Long primaryAssigneeUserId = defaultAssigneeUserIds.isEmpty()
                ? null : defaultAssigneeUserIds.get(0);
        return new InspectionDtos.SaveSchemeRequest(
                upper(request.schemeCode()),
                request.schemeName().trim(),
                request.inspectionType().trim().toUpperCase(Locale.ROOT),
                request.cycleType().trim().toUpperCase(Locale.ROOT),
                request.cycleInterval(),
                clean(request.weekDays()),
                clean(request.monthDays()),
                request.scheduledTime(),
                request.generationLeadMinutes(),
                request.workCalendarId() == null
                        ? calendarMapper.findDefaultCalendarId(
                        SecurityUtils.currentUser().tenantId()
                ) : request.workCalendarId(),
                upper(request.shiftCode()),
                primaryAssigneeUserId,
                defaultAssigneeUserIds,
                upper(request.defaultTeamCode()),
                false,
                request.backfillAllowed(),
                request.submissionPhotoRequired(),
                request.submissionPhotoMaxCount(),
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

    private LocalDate firstOccurrence(InspectionDtos.SchemeVersionRow version) {
        LocalDate date = version.effectiveDate().isAfter(LocalDate.now())
                ? version.effectiveDate() : LocalDate.now();
        for (int i = 0; i < 370; i++) {
            if (matchesSchedule(version.cycleType(), version.weekDays(), version.monthDays(), date)) {
                return date;
            }
            date = date.plusDays(1);
        }
        throw new BusinessException("INSPECTION_SCHEME_SCHEDULE_INVALID", "无法计算方案首次执行日期");
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
                        "INSPECTION_SCHEME_SCHEDULE_INVALID", "方案周期日期格式不正确"
                );
            }
        }
        return false;
    }

    private InspectionDtos.ItemRow requireItem(long tenantId, long id, DataPermission scope) {
        InspectionDtos.ItemRow item = mapper.findItem(tenantId, id);
        if (item == null || (item.organizationId() != null
                && !scope.canCreateIn(item.organizationId()))) {
            throw new BusinessException(
                    "INSPECTION_ITEM_NOT_FOUND", "点检项目不存在", HttpStatus.NOT_FOUND
            );
        }
        return item;
    }

    private void requireOrganizationAccess(Long organizationId) {
        if (!dataPermissionService.current().canCreateIn(organizationId)) {
            throw new BusinessException(
                    "DATA_SCOPE_FORBIDDEN", "无权维护所选部门的点检项目", HttpStatus.FORBIDDEN
            );
        }
    }

    private InspectionDtos.SchemeRow requireScheme(long tenantId, long id) {
        InspectionDtos.SchemeRow scheme = mapper.findScheme(tenantId, id);
        if (scheme == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_NOT_FOUND", "点检方案不存在", HttpStatus.NOT_FOUND
            );
        }
        return scheme;
    }

    private InspectionDtos.SchemeVersionRow requireVersion(long tenantId, long id) {
        InspectionDtos.SchemeVersionRow version = mapper.findSchemeVersion(tenantId, id);
        if (version == null) {
            throw new BusinessException(
                    "INSPECTION_SCHEME_VERSION_NOT_FOUND", "点检方案版本不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return version;
    }

    private InspectionDtos.PlanRow requirePlan(long tenantId, long id, DataPermission scope) {
        InspectionDtos.PlanRow plan = mapper.findPlan(tenantId, id, scope);
        if (plan == null) {
            throw new BusinessException(
                    "INSPECTION_PLAN_NOT_FOUND", "点检计划不存在或无权访问",
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
                    "INSPECTION_JSON_INVALID", "点检配置序列化失败",
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
