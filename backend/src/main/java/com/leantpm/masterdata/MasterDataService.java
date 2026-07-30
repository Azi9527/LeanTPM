package com.leantpm.masterdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class MasterDataService {
    private static final Map<String, Set<String>> ORGANIZATION_PARENTS = Map.of(
            "ENTERPRISE", Set.of(),
            "FACTORY", Set.of("ENTERPRISE"),
            "DEPARTMENT", Set.of("ENTERPRISE", "FACTORY", "DEPARTMENT"),
            "WORKSHOP", Set.of("FACTORY", "DEPARTMENT"),
            "LINE", Set.of("WORKSHOP"),
            "TEAM", Set.of("DEPARTMENT", "WORKSHOP", "LINE")
    );
    private static final Map<String, Set<String>> LOCATION_PARENTS = Map.of(
            "ENTERPRISE", Set.of(),
            "FACTORY", Set.of("ENTERPRISE"),
            "PLANT_AREA", Set.of("FACTORY"),
            "WORKSHOP", Set.of("FACTORY", "PLANT_AREA"),
            "LINE", Set.of("WORKSHOP"),
            "WORKSTATION", Set.of("LINE")
    );

    private final MasterDataMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;

    public MasterDataService(
            MasterDataMapper mapper,
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
    public List<MasterDataDtos.OrganizationRow> organizations() {
        var current = SecurityUtils.currentUser();
        List<MasterDataDtos.OrganizationRow> all = mapper.findOrganizations(current.tenantId());
        DataPermission scope = dataPermissionService.current();
        if (scope.allData()) {
            return all;
        }
        Set<Long> included = withAncestors(
                all.stream().collect(java.util.stream.Collectors.toMap(
                        MasterDataDtos.OrganizationRow::id,
                        MasterDataDtos.OrganizationRow::parentId
                )),
                scope.organizationIds()
        );
        return all.stream().filter(row -> included.contains(row.id())).toList();
    }

    @Transactional
    public long createOrganization(MasterDataDtos.SaveOrganizationRequest request) {
        var current = SecurityUtils.currentUser();
        var normalized = normalize(request);
        assertOrganizationCodeAvailable(current.tenantId(), normalized.organizationCode(), null);
        assertManager(current.tenantId(), normalized.managerUserId());
        assertOrganizationParent(current.tenantId(), null, normalized);
        mapper.insertOrganization(current.tenantId(), normalized, current.userId());
        long id = mapper.findOrganizationIdByCode(
                current.tenantId(),
                normalized.organizationCode()
        );
        changeLogService.record(
                "ORGANIZATION",
                id,
                "CREATE",
                null,
                mapper.findOrganization(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateOrganization(long id, MasterDataDtos.SaveOrganizationRequest request) {
        var current = SecurityUtils.currentUser();
        var existing = requireOrganization(current.tenantId(), id);
        assertOrganizationAccess(existing.id());
        var normalized = normalize(request);
        requireVersion(normalized.version());
        if (!existing.organizationCode().equals(normalized.organizationCode())) {
            throw new BusinessException("IMMUTABLE_CODE", "组织编码创建后不可修改");
        }
        assertManager(current.tenantId(), normalized.managerUserId());
        assertOrganizationParent(current.tenantId(), id, normalized);
        if (!normalized.enabled()) {
            assertOrganizationCanDisable(current.tenantId(), id);
        }
        if (mapper.updateOrganization(
                current.tenantId(),
                id,
                normalized,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "ORGANIZATION",
                id,
                "UPDATE",
                existing,
                mapper.findOrganization(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteOrganization(long id, int version) {
        var current = SecurityUtils.currentUser();
        var existing = requireOrganization(current.tenantId(), id);
        assertOrganizationAccess(existing.id());
        if (existing.parentId() == 0) {
            throw new BusinessException("ORGANIZATION_ROOT_PROTECTED", "根组织不可删除");
        }
        if (mapper.countOrganizationChildren(current.tenantId(), id, false) > 0
                || mapper.countOrganizationReferences(current.tenantId(), id, false) > 0) {
            throw new BusinessException(
                    "ORGANIZATION_IN_USE",
                    "组织存在下级或业务引用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteOrganization(
                current.tenantId(),
                id,
                version,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("ORGANIZATION", id, "DELETE", existing, null);
    }

    @Transactional(readOnly = true)
    public List<MasterDataDtos.LocationRow> locations() {
        var current = SecurityUtils.currentUser();
        List<MasterDataDtos.LocationRow> all = mapper.findLocations(current.tenantId());
        DataPermission scope = dataPermissionService.current();
        if (scope.allData()) {
            return all;
        }
        Map<Long, Long> parents = all.stream().collect(java.util.stream.Collectors.toMap(
                MasterDataDtos.LocationRow::id,
                MasterDataDtos.LocationRow::parentId
        ));
        Set<Long> directlyVisible = all.stream()
                .filter(row -> scope.organizationIds().contains(row.organizationId()))
                .map(MasterDataDtos.LocationRow::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> included = withAncestors(parents, directlyVisible);
        return all.stream().filter(row -> included.contains(row.id())).toList();
    }

    @Transactional
    public long createLocation(MasterDataDtos.SaveLocationRequest request) {
        var current = SecurityUtils.currentUser();
        var normalized = normalize(request);
        assertLocationCodeAvailable(current.tenantId(), normalized.locationCode(), null);
        assertManager(current.tenantId(), normalized.managerUserId());
        assertOrganizationAccess(normalized.organizationId());
        assertLocationParent(current.tenantId(), null, normalized);
        mapper.insertLocation(current.tenantId(), normalized, current.userId());
        long id = mapper.findLocationIdByCode(current.tenantId(), normalized.locationCode());
        changeLogService.record(
                "LOCATION",
                id,
                "CREATE",
                null,
                mapper.findLocation(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateLocation(long id, MasterDataDtos.SaveLocationRequest request) {
        var current = SecurityUtils.currentUser();
        var existing = requireLocation(current.tenantId(), id);
        assertOrganizationAccess(existing.organizationId());
        var normalized = normalize(request);
        requireVersion(normalized.version());
        if (!existing.locationCode().equals(normalized.locationCode())) {
            throw new BusinessException("IMMUTABLE_CODE", "位置编码创建后不可修改");
        }
        assertManager(current.tenantId(), normalized.managerUserId());
        assertOrganizationAccess(normalized.organizationId());
        assertLocationParent(current.tenantId(), id, normalized);
        if (!normalized.enabled()
                && (mapper.countLocationChildren(current.tenantId(), id, true) > 0
                || mapper.countLocationEquipmentReferences(current.tenantId(), id, true) > 0)) {
            throw new BusinessException(
                    "LOCATION_IN_USE",
                    "位置存在启用的下级或设备，不能停用",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.updateLocation(
                current.tenantId(),
                id,
                normalized,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "LOCATION",
                id,
                "UPDATE",
                existing,
                mapper.findLocation(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteLocation(long id, int version) {
        var current = SecurityUtils.currentUser();
        var existing = requireLocation(current.tenantId(), id);
        assertOrganizationAccess(existing.organizationId());
        if (existing.parentId() == 0) {
            throw new BusinessException("LOCATION_ROOT_PROTECTED", "根位置不可删除");
        }
        if (mapper.countLocationChildren(current.tenantId(), id, false) > 0
                || mapper.countLocationEquipmentReferences(current.tenantId(), id, false) > 0) {
            throw new BusinessException(
                    "LOCATION_IN_USE",
                    "位置存在下级或设备引用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteLocation(
                current.tenantId(),
                id,
                version,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("LOCATION", id, "DELETE", existing, null);
    }

    @Transactional(readOnly = true)
    public List<MasterDataDtos.EquipmentCategoryRow> categories() {
        return mapper.findCategories(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public long createCategory(MasterDataDtos.SaveEquipmentCategoryRequest request) {
        var current = SecurityUtils.currentUser();
        var normalized = normalize(request);
        assertCategoryCodeAvailable(current.tenantId(), normalized.categoryCode(), null);
        int level = categoryLevel(current.tenantId(), normalized.parentId(), null);
        mapper.insertCategory(current.tenantId(), level, normalized, current.userId());
        long id = mapper.findCategoryIdByCode(current.tenantId(), normalized.categoryCode());
        changeLogService.record(
                "EQUIPMENT_CATEGORY",
                id,
                "CREATE",
                null,
                mapper.findCategory(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateCategory(long id, MasterDataDtos.SaveEquipmentCategoryRequest request) {
        var current = SecurityUtils.currentUser();
        var existing = requireCategory(current.tenantId(), id);
        var normalized = normalize(request);
        requireVersion(normalized.version());
        if (!existing.categoryCode().equals(normalized.categoryCode())) {
            throw new BusinessException("IMMUTABLE_CODE", "分类编码创建后不可修改");
        }
        int newLevel = categoryLevel(current.tenantId(), normalized.parentId(), id);
        List<MasterDataDtos.EquipmentCategoryRow> all = mapper.findCategories(current.tenantId());
        assertNoCycle(id, normalized.parentId(), categoryParents(all), "设备分类");
        if (!normalized.enabled()
                && (mapper.countCategoryChildren(current.tenantId(), id) > 0
                || mapper.countCategoryEquipmentReferences(current.tenantId(), id) > 0)) {
            throw new BusinessException(
                    "EQUIPMENT_CATEGORY_IN_USE",
                    "分类存在下级或设备，不能停用",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.updateCategory(
                current.tenantId(),
                id,
                newLevel,
                normalized,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        int levelDelta = newLevel - existing.treeLevel();
        if (levelDelta != 0) {
            descendants(id, categoryParents(all)).forEach(descendantId -> {
                var descendant = requireCategory(current.tenantId(), descendantId);
                mapper.updateCategoryTreeLevel(
                        current.tenantId(),
                        descendantId,
                        descendant.treeLevel() + levelDelta,
                        current.userId()
                );
            });
        }
        changeLogService.record(
                "EQUIPMENT_CATEGORY",
                id,
                "UPDATE",
                existing,
                mapper.findCategory(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteCategory(long id, int version) {
        var current = SecurityUtils.currentUser();
        var existing = requireCategory(current.tenantId(), id);
        if (mapper.countCategoryChildren(current.tenantId(), id) > 0
                || mapper.countCategoryEquipmentReferences(current.tenantId(), id) > 0
                || mapper.countCategoryAttributeDefinitions(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "EQUIPMENT_CATEGORY_IN_USE",
                    "分类存在下级、属性模板或设备引用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteCategory(
                current.tenantId(),
                id,
                version,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("EQUIPMENT_CATEGORY", id, "DELETE", existing, null);
    }

    @Transactional(readOnly = true)
    public List<MasterDataDtos.AttributeDefinitionRow> attributes(
            long categoryId,
            boolean includeInherited
    ) {
        var current = SecurityUtils.currentUser();
        requireCategory(current.tenantId(), categoryId);
        return mapper.findCategoryAttributes(
                current.tenantId(),
                categoryId,
                includeInherited
        );
    }

    @Transactional
    public long createAttribute(
            long categoryId,
            MasterDataDtos.SaveAttributeDefinitionRequest request
    ) {
        var current = SecurityUtils.currentUser();
        requireCategory(current.tenantId(), categoryId);
        var normalized = normalize(request);
        validateAttribute(normalized);
        assertAttributeCodeAvailable(
                current.tenantId(),
                categoryId,
                normalized.attributeCode(),
                null
        );
        mapper.insertAttribute(
                current.tenantId(),
                categoryId,
                normalized,
                enumJson(normalized),
                current.userId()
        );
        long id = mapper.findAttributeIdByCode(
                current.tenantId(),
                categoryId,
                normalized.attributeCode()
        );
        changeLogService.record(
                "EQUIPMENT_ATTRIBUTE_DEFINITION",
                id,
                "CREATE",
                null,
                mapper.findAttribute(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void updateAttribute(
            long id,
            MasterDataDtos.SaveAttributeDefinitionRequest request
    ) {
        var current = SecurityUtils.currentUser();
        var existing = requireAttribute(current.tenantId(), id);
        var normalized = normalize(request);
        requireVersion(normalized.version());
        if (!existing.attributeCode().equals(normalized.attributeCode())) {
            throw new BusinessException("IMMUTABLE_CODE", "属性编码创建后不可修改");
        }
        validateAttribute(normalized);
        if (mapper.updateAttribute(
                current.tenantId(),
                id,
                normalized,
                enumJson(normalized),
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "EQUIPMENT_ATTRIBUTE_DEFINITION",
                id,
                "UPDATE",
                existing,
                mapper.findAttribute(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteAttribute(long id, int version) {
        var current = SecurityUtils.currentUser();
        var existing = requireAttribute(current.tenantId(), id);
        if (mapper.countAttributeValueReferences(current.tenantId(), id) > 0) {
            throw new BusinessException(
                    "EQUIPMENT_ATTRIBUTE_IN_USE",
                    "属性已有设备值，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteAttribute(
                current.tenantId(),
                id,
                version,
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "EQUIPMENT_ATTRIBUTE_DEFINITION",
                id,
                "DELETE",
                existing,
                null
        );
    }

    @Transactional(readOnly = true)
    public List<MasterDataDtos.ReferenceUser> referenceUsers() {
        var current = SecurityUtils.currentUser();
        List<MasterDataDtos.ReferenceUser> users = mapper.findReferenceUsers(current.tenantId());
        DataPermission scope = dataPermissionService.current();
        if (scope.allData()) {
            return users;
        }
        return users.stream()
                .filter(user -> (user.organizationId() != null
                        && scope.organizationIds().contains(user.organizationId()))
                        || (scope.selfData() && user.id() == current.userId()))
                .toList();
    }

    private void assertOrganizationParent(
            long tenantId,
            Long currentId,
            MasterDataDtos.SaveOrganizationRequest request
    ) {
        String type = request.organizationType();
        if ("ENTERPRISE".equals(type)) {
            if (request.parentId() != 0) {
                throw hierarchyError("企业必须为根组织");
            }
            if (currentId == null && !dataPermissionService.current().allData()) {
                throw dataScopeDenied();
            }
            return;
        }
        if (request.parentId() == 0) {
            throw hierarchyError("非企业组织必须选择上级");
        }
        var parent = requireOrganization(tenantId, request.parentId());
        assertOrganizationAccess(parent.id());
        if (!ORGANIZATION_PARENTS.getOrDefault(type, Set.of())
                .contains(parent.organizationType())) {
            throw hierarchyError(type + " 不能位于 " + parent.organizationType() + " 下");
        }
        if (currentId != null) {
            assertNoCycle(
                    currentId,
                    request.parentId(),
                    organizationParents(mapper.findOrganizations(tenantId)),
                    "组织"
            );
        }
    }

    private void assertLocationParent(
            long tenantId,
            Long currentId,
            MasterDataDtos.SaveLocationRequest request
    ) {
        var organization = requireOrganization(tenantId, request.organizationId());
        if (organization.status() != 1) {
            throw new BusinessException("ORGANIZATION_DISABLED", "所属组织已停用");
        }
        String type = request.locationType();
        if ("ENTERPRISE".equals(type)) {
            if (request.parentId() != 0
                    || !"ENTERPRISE".equals(organization.organizationType())) {
                throw hierarchyError("企业位置必须为根节点并绑定企业组织");
            }
            return;
        }
        if (request.parentId() == 0) {
            throw hierarchyError("非企业位置必须选择上级");
        }
        var parent = requireLocation(tenantId, request.parentId());
        assertOrganizationAccess(parent.organizationId());
        if (!LOCATION_PARENTS.getOrDefault(type, Set.of()).contains(parent.locationType())) {
            throw hierarchyError(type + " 不能位于 " + parent.locationType() + " 下");
        }
        if (currentId != null) {
            assertNoCycle(
                    currentId,
                    request.parentId(),
                    locationParents(mapper.findLocations(tenantId)),
                    "位置"
            );
        }
    }

    private int categoryLevel(long tenantId, long parentId, Long currentId) {
        if (parentId == 0) {
            return 1;
        }
        if (currentId != null && parentId == currentId) {
            throw hierarchyError("设备分类不能以自身为上级");
        }
        var parent = requireCategory(tenantId, parentId);
        if (parent.status() != 1) {
            throw new BusinessException("EQUIPMENT_CATEGORY_DISABLED", "上级分类已停用");
        }
        if (parent.treeLevel() >= 8) {
            throw hierarchyError("设备分类最多支持 8 级");
        }
        return parent.treeLevel() + 1;
    }

    private void assertOrganizationCanDisable(long tenantId, long id) {
        if (mapper.countOrganizationChildren(tenantId, id, true) > 0
                || mapper.countOrganizationReferences(tenantId, id, true) > 0) {
            throw new BusinessException(
                    "ORGANIZATION_IN_USE",
                    "组织存在启用的下级或业务引用，不能停用",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void assertManager(long tenantId, Long managerUserId) {
        if (managerUserId != null && mapper.countActiveUser(tenantId, managerUserId) == 0) {
            throw new BusinessException("MANAGER_USER_INVALID", "负责人不存在或已停用");
        }
    }

    private void assertOrganizationAccess(long organizationId) {
        if (!dataPermissionService.current().canCreateIn(organizationId)) {
            throw dataScopeDenied();
        }
    }

    private MasterDataDtos.OrganizationRow requireOrganization(long tenantId, long id) {
        var value = mapper.findOrganization(tenantId, id);
        if (value == null) {
            throw new BusinessException(
                    "ORGANIZATION_NOT_FOUND",
                    "组织不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return value;
    }

    private MasterDataDtos.LocationRow requireLocation(long tenantId, long id) {
        var value = mapper.findLocation(tenantId, id);
        if (value == null) {
            throw new BusinessException("LOCATION_NOT_FOUND", "位置不存在", HttpStatus.NOT_FOUND);
        }
        return value;
    }

    private MasterDataDtos.EquipmentCategoryRow requireCategory(long tenantId, long id) {
        var value = mapper.findCategory(tenantId, id);
        if (value == null) {
            throw new BusinessException(
                    "EQUIPMENT_CATEGORY_NOT_FOUND",
                    "设备分类不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return value;
    }

    private MasterDataDtos.AttributeDefinitionRow requireAttribute(long tenantId, long id) {
        var value = mapper.findAttribute(tenantId, id);
        if (value == null) {
            throw new BusinessException(
                    "EQUIPMENT_ATTRIBUTE_NOT_FOUND",
                    "设备属性定义不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return value;
    }

    private void assertOrganizationCodeAvailable(long tenantId, String code, Long excludeId) {
        if (mapper.countOrganizationCode(tenantId, code, excludeId) > 0) {
            throw new BusinessException(
                    "ORGANIZATION_CODE_EXISTS",
                    "组织编码已存在",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void assertLocationCodeAvailable(long tenantId, String code, Long excludeId) {
        if (mapper.countLocationCode(tenantId, code, excludeId) > 0) {
            throw new BusinessException(
                    "LOCATION_CODE_EXISTS",
                    "位置编码已存在",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void assertCategoryCodeAvailable(long tenantId, String code, Long excludeId) {
        if (mapper.countCategoryCode(tenantId, code, excludeId) > 0) {
            throw new BusinessException(
                    "EQUIPMENT_CATEGORY_CODE_EXISTS",
                    "设备分类编码已存在",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void assertAttributeCodeAvailable(
            long tenantId,
            long categoryId,
            String code,
            Long excludeId
    ) {
        if (mapper.countAttributeCode(tenantId, categoryId, code, excludeId) > 0) {
            throw new BusinessException(
                    "EQUIPMENT_ATTRIBUTE_CODE_EXISTS",
                    "当前分类已存在此属性编码",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateAttribute(MasterDataDtos.SaveAttributeDefinitionRequest request) {
        if (request.minimumValue() != null
                && request.maximumValue() != null
                && request.minimumValue().compareTo(request.maximumValue()) > 0) {
            throw new BusinessException(
                    "ATTRIBUTE_RANGE_INVALID",
                    "属性最小值不能大于最大值"
            );
        }
        if (request.validationPattern() != null) {
            try {
                Pattern.compile(request.validationPattern());
            } catch (PatternSyntaxException exception) {
                throw new BusinessException(
                        "ATTRIBUTE_PATTERN_INVALID",
                        "属性校验正则表达式无效"
                );
            }
        }
        if ("ENUM".equals(request.dataType())) {
            if (request.enumOptions() == null || request.enumOptions().isEmpty()) {
                throw new BusinessException(
                        "ATTRIBUTE_ENUM_OPTIONS_REQUIRED",
                        "枚举属性必须配置选项"
                );
            }
            if (new HashSet<>(request.enumOptions()).size() != request.enumOptions().size()) {
                throw new BusinessException(
                        "ATTRIBUTE_ENUM_OPTIONS_DUPLICATED",
                        "枚举选项不能重复"
                );
            }
        }
        if (Set.of("INTEGER", "DECIMAL").contains(request.dataType())) {
            validateNumericDefault(request);
        }
    }

    private void validateNumericDefault(MasterDataDtos.SaveAttributeDefinitionRequest request) {
        if (request.defaultValue() == null || request.defaultValue().isBlank()) {
            return;
        }
        try {
            BigDecimal value = new BigDecimal(request.defaultValue());
            if ("INTEGER".equals(request.dataType())
                    && value.stripTrailingZeros().scale() > 0) {
                throw new NumberFormatException();
            }
            if (request.minimumValue() != null
                    && value.compareTo(request.minimumValue()) < 0) {
                throw new BusinessException(
                        "ATTRIBUTE_DEFAULT_OUT_OF_RANGE",
                        "属性默认值小于最小值"
                );
            }
            if (request.maximumValue() != null
                    && value.compareTo(request.maximumValue()) > 0) {
                throw new BusinessException(
                        "ATTRIBUTE_DEFAULT_OUT_OF_RANGE",
                        "属性默认值大于最大值"
                );
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    "ATTRIBUTE_DEFAULT_INVALID",
                    "属性默认值与数据类型不匹配"
            );
        }
    }

    private String enumJson(MasterDataDtos.SaveAttributeDefinitionRequest request) {
        if (!"ENUM".equals(request.dataType())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.enumOptions());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "ATTRIBUTE_ENUM_SERIALIZE_FAILED",
                    "枚举选项序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private MasterDataDtos.SaveOrganizationRequest normalize(
            MasterDataDtos.SaveOrganizationRequest request
    ) {
        return new MasterDataDtos.SaveOrganizationRequest(
                request.parentId(),
                upper(request.organizationCode()),
                text(request.organizationName()),
                upper(request.organizationType()),
                request.managerUserId(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.enabled(),
                nullableText(request.description()),
                request.version()
        );
    }

    private MasterDataDtos.SaveLocationRequest normalize(
            MasterDataDtos.SaveLocationRequest request
    ) {
        return new MasterDataDtos.SaveLocationRequest(
                request.parentId(),
                upper(request.locationCode()),
                text(request.locationName()),
                upper(request.locationType()),
                request.organizationId(),
                request.managerUserId(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.enabled(),
                nullableText(request.description()),
                request.version()
        );
    }

    private MasterDataDtos.SaveEquipmentCategoryRequest normalize(
            MasterDataDtos.SaveEquipmentCategoryRequest request
    ) {
        return new MasterDataDtos.SaveEquipmentCategoryRequest(
                request.parentId(),
                upper(request.categoryCode()),
                text(request.categoryName()),
                request.defaultInspectionTemplateId(),
                request.defaultMaintenanceTemplateId(),
                request.defaultFaultTypeId(),
                upperNullable(request.defaultOeeMode()),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.enabled(),
                nullableText(request.description()),
                request.version()
        );
    }

    private MasterDataDtos.SaveAttributeDefinitionRequest normalize(
            MasterDataDtos.SaveAttributeDefinitionRequest request
    ) {
        List<String> options = request.enumOptions() == null
                ? List.of()
                : request.enumOptions().stream()
                .map(this::text)
                .toList();
        return new MasterDataDtos.SaveAttributeDefinitionRequest(
                upper(request.attributeCode()),
                text(request.attributeName()),
                upper(request.dataType()),
                nullableText(request.unit()),
                request.required(),
                nullableText(request.defaultValue()),
                nullableText(request.validationPattern()),
                request.minimumValue(),
                request.maximumValue(),
                options,
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.enabled(),
                nullableText(request.description()),
                request.version()
        );
    }

    private Map<Long, Long> organizationParents(
            List<MasterDataDtos.OrganizationRow> rows
    ) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                MasterDataDtos.OrganizationRow::id,
                MasterDataDtos.OrganizationRow::parentId
        ));
    }

    private Map<Long, Long> locationParents(List<MasterDataDtos.LocationRow> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                MasterDataDtos.LocationRow::id,
                MasterDataDtos.LocationRow::parentId
        ));
    }

    private Map<Long, Long> categoryParents(
            List<MasterDataDtos.EquipmentCategoryRow> rows
    ) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                MasterDataDtos.EquipmentCategoryRow::id,
                MasterDataDtos.EquipmentCategoryRow::parentId
        ));
    }

    private Set<Long> withAncestors(Map<Long, Long> parents, Set<Long> ids) {
        Set<Long> result = new HashSet<>();
        for (Long id : ids) {
            Long cursor = id;
            while (cursor != null && cursor != 0 && result.add(cursor)) {
                cursor = parents.get(cursor);
            }
        }
        return result;
    }

    private void assertNoCycle(
            long currentId,
            long parentId,
            Map<Long, Long> parents,
            String label
    ) {
        Long cursor = parentId;
        Set<Long> visited = new HashSet<>();
        while (cursor != null && cursor != 0 && visited.add(cursor)) {
            if (cursor == currentId) {
                throw hierarchyError(label + "上级不能选择自身或其下级");
            }
            cursor = parents.get(cursor);
        }
    }

    private List<Long> descendants(long id, Map<Long, Long> parents) {
        List<Long> result = new ArrayList<>();
        Set<Long> frontier = Set.of(id);
        while (!frontier.isEmpty()) {
            Set<Long> next = new LinkedHashSet<>();
            for (Map.Entry<Long, Long> entry : parents.entrySet()) {
                if (frontier.contains(entry.getValue()) && entry.getKey() != id) {
                    if (!result.contains(entry.getKey())) {
                        result.add(entry.getKey());
                        next.add(entry.getKey());
                    }
                }
            }
            frontier = next;
        }
        return result;
    }

    private void requireVersion(Integer version) {
        if (version == null) {
            throw new BusinessException("VERSION_REQUIRED", "更新操作必须提供数据版本");
        }
    }

    private String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String upperNullable(String value) {
        return value == null || value.isBlank() ? null : upper(value);
    }

    private String text(String value) {
        return value.trim();
    }

    private String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException hierarchyError(String message) {
        return new BusinessException("HIERARCHY_INVALID", message);
    }

    private BusinessException dataScopeDenied() {
        return new BusinessException(
                "DATA_SCOPE_DENIED",
                "目标组织不在当前数据范围内",
                HttpStatus.FORBIDDEN
        );
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT",
                "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }
}
