package com.leantpm.masterdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final Map<String, Set<String>> LOCATION_PARENTS = Map.of(
            "AREA", Set.of("AREA"),
            "BUILDING", Set.of("AREA"),
            "FLOOR", Set.of("AREA", "BUILDING"),
            "ZONE", Set.of("AREA", "BUILDING", "FLOOR", "ZONE"),
            "SPOT", Set.of("AREA", "BUILDING", "FLOOR", "ZONE")
    );

    private final MasterDataMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public MasterDataService(
            MasterDataMapper mapper,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
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

    @Transactional(readOnly = true)
    public MasterDataDtos.OrganizationDeleteImpact organizationDeleteImpact(long id) {
        var current = SecurityUtils.currentUser();
        var existing = requireOrganization(current.tenantId(), id);
        assertOrganizationAccess(existing.id());
        if (existing.parentId() == 0) {
            throw new BusinessException("ORGANIZATION_ROOT_PROTECTED", "根组织不可删除");
        }
        return organizationDeleteImpact(current.tenantId(), id);
    }

    @Transactional
    public void deleteOrganization(long id, int version, boolean cascadeRelations) {
        var current = SecurityUtils.currentUser();
        var existing = requireOrganization(current.tenantId(), id);
        assertOrganizationAccess(existing.id());
        if (existing.parentId() == 0) {
            throw new BusinessException("ORGANIZATION_ROOT_PROTECTED", "根组织不可删除");
        }
        MasterDataDtos.OrganizationDeleteImpact impact = organizationDeleteImpact(
                current.tenantId(), id
        );
        if (impact.childOrganizations() > 0) {
            throw new BusinessException(
                    "ORGANIZATION_HAS_CHILDREN",
                    "该组织存在 " + impact.childOrganizations()
                            + " 个下级组织，请先删除或调整下级组织",
                    HttpStatus.CONFLICT
            );
        }
        if (impact.totalReferences() > 0 && !cascadeRelations) {
            throw new BusinessException(
                    "ORGANIZATION_IN_USE",
                    deleteImpactMessage(impact),
                    HttpStatus.CONFLICT
            );
        }
        if (impact.totalReferences() > 0) {
            reassignOrganizationRelations(
                    current.tenantId(), id, existing.parentId(), current.userId()
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
        changeLogService.record(
                "ORGANIZATION",
                id,
                "DELETE",
                Map.of("organization", existing, "removedRelations", impact),
                null
        );
    }

    private MasterDataDtos.OrganizationDeleteImpact organizationDeleteImpact(
            long tenantId,
            long organizationId
    ) {
        int children = countActive("organization", "parent_id", tenantId, organizationId);
        int users = countActive("system_user", "organization_id", tenantId, organizationId);
        int locations = countActive("location", "organization_id", tenantId, organizationId);
        int equipment = countActive("equipment", "organization_id", tenantId, organizationId);
        int teamMemberships = countActive(
                "system_user_team_membership", "team_organization_id", tenantId, organizationId
        );
        int dataScopes = countActive(
                "system_role_data_scope", "organization_id", tenantId, organizationId
        );
        int businessRecords = List.of(
                "equipment_calendar",
                "equipment_downtime_record",
                "equipment_fault_report",
                "equipment_oee_record",
                "equipment_oee_target",
                "equipment_output_record",
                "equipment_repair_order",
                "inspection_task",
                "maintenance_task"
        ).stream().mapToInt(table -> countActive(
                table, "organization_id", tenantId, organizationId
        )).sum();
        businessRecords += countAll(
                "equipment_transfer_record", "from_organization_id", tenantId, organizationId
        );
        businessRecords += countAll(
                "equipment_transfer_record", "to_organization_id", tenantId, organizationId
        );
        int visualizationRecords = countActive(
                "visualization_scene", "organization_id", tenantId, organizationId
        ) + countVisualizationNodes(tenantId, organizationId);
        int total = children + users + locations + equipment + teamMemberships
                + dataScopes + businessRecords + visualizationRecords;
        return new MasterDataDtos.OrganizationDeleteImpact(
                children,
                users,
                locations,
                equipment,
                teamMemberships,
                dataScopes,
                businessRecords,
                visualizationRecords,
                total
        );
    }

    private int countActive(String table, String column, long tenantId, long organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = ? AND " + column + " = ? AND deleted = 0",
                Integer.class,
                tenantId,
                organizationId
        );
        return count == null ? 0 : count;
    }

    private int countAll(String table, String column, long tenantId, long organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = ? AND " + column + " = ?",
                Integer.class,
                tenantId,
                organizationId
        );
        return count == null ? 0 : count;
    }

    private int countVisualizationNodes(long tenantId, long organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visualization_scene_node node"
                        + " WHERE node.tenant_id = ? AND node.deleted = 0"
                        + " AND (node.organization_id = ?"
                        + " OR EXISTS (SELECT 1 FROM visualization_scene scene"
                        + " WHERE scene.tenant_id = node.tenant_id"
                        + " AND scene.id = node.scene_id AND scene.organization_id = ?"
                        + " AND scene.deleted = 0)"
                        + " OR EXISTS (SELECT 1 FROM visualization_scene target_scene"
                        + " WHERE target_scene.tenant_id = node.tenant_id"
                        + " AND target_scene.id = node.target_scene_id"
                        + " AND target_scene.organization_id = ?"
                        + " AND target_scene.deleted = 0))",
                Integer.class,
                tenantId,
                organizationId,
                organizationId,
                organizationId
        );
        return count == null ? 0 : count;
    }

    private String deleteImpactMessage(MasterDataDtos.OrganizationDeleteImpact impact) {
        List<String> parts = new ArrayList<>();
        if (impact.users() > 0) parts.add("用户 " + impact.users() + " 个");
        if (impact.locations() > 0) parts.add("位置 " + impact.locations() + " 个");
        if (impact.equipment() > 0) parts.add("设备 " + impact.equipment() + " 台");
        if (impact.teamMemberships() > 0) {
            parts.add("班组任职关系 " + impact.teamMemberships() + " 条");
        }
        if (impact.dataScopes() > 0) parts.add("数据权限关系 " + impact.dataScopes() + " 条");
        if (impact.businessRecords() > 0) parts.add("业务记录 " + impact.businessRecords() + " 条");
        if (impact.visualizationRecords() > 0) {
            parts.add("可视化配置 " + impact.visualizationRecords() + " 条");
        }
        return "该组织存在关联关系：" + String.join("、", parts)
                + "。确认后系统会将业务数据转移到上级组织，并删除班组任职及数据权限关系。";
    }

    private void reassignOrganizationRelations(
            long tenantId,
            long organizationId,
            long parentOrganizationId,
            long operatorId
    ) {
        mergeEquipmentCalendars(
                tenantId, organizationId, parentOrganizationId, operatorId
        );
        mergeVisualizationRelations(
                tenantId, organizationId, parentOrganizationId, operatorId
        );
        List<String> versionedTables = List.of(
                "system_user",
                "location",
                "equipment",
                "equipment_downtime_record",
                "equipment_fault_report",
                "equipment_oee_record",
                "equipment_oee_target",
                "equipment_output_record",
                "equipment_repair_order",
                "inspection_task",
                "maintenance_task"
        );
        for (String table : versionedTables) {
            jdbc.update(
                    "UPDATE " + table
                            + " SET organization_id = ?, updated_by = ?, version = version + 1"
                            + " WHERE tenant_id = ? AND organization_id = ? AND deleted = 0",
                    parentOrganizationId,
                    operatorId,
                    tenantId,
                    organizationId
            );
        }
        jdbc.update(
                "UPDATE equipment_transfer_record SET from_organization_id = ?"
                        + " WHERE tenant_id = ? AND from_organization_id = ?",
                parentOrganizationId,
                tenantId,
                organizationId
        );
        jdbc.update(
                "UPDATE equipment_transfer_record SET to_organization_id = ?"
                        + " WHERE tenant_id = ? AND to_organization_id = ?",
                parentOrganizationId,
                tenantId,
                organizationId
        );
        jdbc.update(
                "DELETE FROM system_user_team_membership"
                        + " WHERE tenant_id = ? AND team_organization_id = ?",
                tenantId,
                organizationId
        );
        jdbc.update(
                "UPDATE system_role_data_scope"
                        + " SET deleted = 1, updated_by = ?, version = version + 1"
                        + " WHERE tenant_id = ? AND organization_id = ? AND deleted = 0",
                operatorId,
                tenantId,
                organizationId
        );
    }

    private void mergeEquipmentCalendars(
            long tenantId,
            long organizationId,
            long parentOrganizationId,
            long operatorId
    ) {
        jdbc.update(
                "UPDATE equipment_calendar source"
                        + " JOIN equipment_calendar target"
                        + " ON target.tenant_id = source.tenant_id"
                        + " AND target.organization_id = ?"
                        + " AND target.work_date = source.work_date"
                        + " AND target.shift_id = source.shift_id"
                        + " AND target.deleted = 0"
                        + " SET source.deleted = 1, source.calendar_status = 'DISABLED',"
                        + " source.updated_by = ?, source.version = source.version + 1"
                        + " WHERE source.tenant_id = ? AND source.organization_id = ?"
                        + " AND source.deleted = 0",
                parentOrganizationId,
                operatorId,
                tenantId,
                organizationId
        );
        jdbc.update(
                "UPDATE equipment_calendar"
                        + " SET organization_id = ?, updated_by = ?, version = version + 1"
                        + " WHERE tenant_id = ? AND organization_id = ? AND deleted = 0",
                parentOrganizationId,
                operatorId,
                tenantId,
                organizationId
        );
    }

    private void mergeVisualizationRelations(
            long tenantId,
            long organizationId,
            long parentOrganizationId,
            long operatorId
    ) {
        Long sourceSceneId = activeSceneId(tenantId, organizationId);
        Long parentSceneId = activeSceneId(tenantId, parentOrganizationId);
        if (sourceSceneId != null && parentSceneId != null) {
            jdbc.update(
                    "UPDATE visualization_scene_node source"
                            + " JOIN visualization_scene_node target"
                            + " ON target.tenant_id = source.tenant_id"
                            + " AND target.scene_id = ?"
                            + " AND target.node_code = source.node_code"
                            + " AND target.deleted = 0"
                            + " SET source.deleted = 1, source.updated_by = ?,"
                            + " source.version = source.version + 1"
                            + " WHERE source.tenant_id = ? AND source.scene_id = ?"
                            + " AND source.deleted = 0",
                    parentSceneId,
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
            jdbc.update(
                    "UPDATE visualization_scene_node"
                            + " SET scene_id = ?,"
                            + " organization_id = CASE WHEN organization_id = ? THEN ?"
                            + " ELSE organization_id END,"
                            + " target_scene_id = CASE WHEN target_scene_id = ? THEN ?"
                            + " ELSE target_scene_id END,"
                            + " updated_by = ?, version = version + 1"
                            + " WHERE tenant_id = ? AND scene_id = ? AND deleted = 0",
                    parentSceneId,
                    organizationId,
                    parentOrganizationId,
                    sourceSceneId,
                    parentSceneId,
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
            jdbc.update(
                    "UPDATE visualization_scene_node"
                            + " SET target_scene_id = ?, updated_by = ?, version = version + 1"
                            + " WHERE tenant_id = ? AND target_scene_id = ? AND deleted = 0",
                    parentSceneId,
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
            jdbc.update(
                    "UPDATE visualization_scene"
                            + " SET parent_scene_id = ?, updated_by = ?, version = version + 1"
                            + " WHERE tenant_id = ? AND parent_scene_id = ? AND deleted = 0",
                    parentSceneId,
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
            jdbc.update(
                    "UPDATE visualization_scene"
                            + " SET deleted = 1, status = 0, updated_by = ?,"
                            + " version = version + 1"
                            + " WHERE tenant_id = ? AND id = ? AND deleted = 0",
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
        } else if (sourceSceneId != null) {
            jdbc.update(
                    "UPDATE visualization_scene"
                            + " SET organization_id = ?, updated_by = ?, version = version + 1"
                            + " WHERE tenant_id = ? AND id = ? AND deleted = 0",
                    parentOrganizationId,
                    operatorId,
                    tenantId,
                    sourceSceneId
            );
        }
        jdbc.update(
                "UPDATE visualization_scene_node"
                        + " SET organization_id = ?, updated_by = ?, version = version + 1"
                        + " WHERE tenant_id = ? AND organization_id = ? AND deleted = 0",
                parentOrganizationId,
                operatorId,
                tenantId,
                organizationId
        );
    }

    private Long activeSceneId(long tenantId, long organizationId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM visualization_scene"
                        + " WHERE tenant_id = ? AND organization_id = ? AND deleted = 0"
                        + " ORDER BY id LIMIT 1",
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                tenantId,
                organizationId
        );
        return ids == null || ids.isEmpty() ? null : ids.getFirst();
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
        List<Long> descendantIds = existing.organizationId() == normalized.organizationId()
                ? List.of()
                : descendants(id, locationParents(mapper.findLocations(current.tenantId())));
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
        if (!descendantIds.isEmpty()) {
            mapper.updateLocationOrganizations(
                    current.tenantId(),
                    descendantIds,
                    normalized.organizationId(),
                    current.userId()
            );
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
        if (request.parentId() == 0) {
            if (currentId == null && !dataPermissionService.current().allData()) {
                throw dataScopeDenied();
            }
            return;
        }
        var parent = requireOrganization(tenantId, request.parentId());
        assertOrganizationAccess(parent.id());
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
        if (request.parentId() == 0) {
            return;
        }
        var parent = requireLocation(tenantId, request.parentId());
        assertOrganizationAccess(parent.organizationId());
        if (parent.organizationId() != request.organizationId()) {
            throw hierarchyError("上级物理位置必须属于同一组织");
        }
        if (!LOCATION_PARENTS.getOrDefault(type, Set.of()).contains(parent.locationType())) {
            throw hierarchyError("当前物理位置类型不能位于所选上级类型下");
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
                    "组织编码已存在或曾被使用，请更换编码",
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
