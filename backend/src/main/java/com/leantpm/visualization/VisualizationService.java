package com.leantpm.visualization;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.oee.OeeService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class VisualizationService {
    private static final int MAX_RANGE_DAYS = 366;

    private final VisualizationMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final OeeService oeeService;
    private final ChangeLogService changeLogService;

    public VisualizationService(
            VisualizationMapper mapper,
            DataPermissionService dataPermissionService,
            OeeService oeeService,
            ChangeLogService changeLogService
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.oeeService = oeeService;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public VisualizationDtos.DashboardResult dashboard(
            LocalDate startDate,
            LocalDate endDate,
            Long organizationId,
            String periodType
    ) {
        String normalizedPeriodType = normalizePeriodType(periodType);
        LocalDate normalizedEnd = endDate == null ? LocalDate.now() : endDate;
        LocalDate normalizedStart = startDate == null
                ? normalizedEnd.minusDays(6)
                : startDate;
        validateRange(normalizedStart, normalizedEnd);
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        List<Long> organizationIds = organizationId == null
                ? List.of()
                : mapper.organizationAndDescendantIds(current.tenantId(), organizationId);
        if (organizationId != null && organizationIds.isEmpty()) {
            throw notFound("VISUALIZATION_ORGANIZATION_NOT_FOUND", "组织不存在或已停用");
        }

        int refreshSeconds = parameter(
                current.tenantId(), "visualization.refresh-seconds", 86400, 60, 86400
        );
        int longStopMinutes = parameter(
                current.tenantId(), "visualization.long-stop-minutes", 120, 1, 10080
        );
        int longOfflineMinutes = parameter(
                current.tenantId(), "visualization.long-offline-minutes", 60, 1, 10080
        );
        List<VisualizationDtos.WorkflowTrend> workflowTrend = new ArrayList<>();
        workflowTrend.addAll(mapper.workflowTrend(
                current.tenantId(), scope, organizationIds,
                normalizedStart, normalizedEnd, "INSPECTION", normalizedPeriodType
        ));
        workflowTrend.addAll(mapper.workflowTrend(
                current.tenantId(), scope, organizationIds,
                normalizedStart, normalizedEnd, "MAINTENANCE", normalizedPeriodType
        ));

        return new VisualizationDtos.DashboardResult(
                LocalDateTime.now(),
                normalizedStart,
                normalizedEnd,
                organizationId,
                normalizedPeriodType,
                refreshSeconds,
                mapper.coreMetrics(current.tenantId(), scope, organizationIds),
                mapper.statusDistribution(current.tenantId(), scope, organizationIds),
                mapper.organizationDistribution(current.tenantId(), scope, organizationIds),
                mapper.liveEquipment(
                        current.tenantId(), scope, organizationIds,
                        longStopMinutes, longOfflineMinutes, 100
                ),
                mapper.workflowMetrics(
                        current.tenantId(), scope, organizationIds,
                        normalizedStart, normalizedEnd, "INSPECTION"
                ),
                mapper.workflowMetrics(
                        current.tenantId(), scope, organizationIds,
                        normalizedStart, normalizedEnd, "MAINTENANCE"
                ),
                List.copyOf(workflowTrend),
                mapper.reliabilityMetrics(
                        current.tenantId(), scope, organizationIds,
                        normalizedStart, normalizedEnd
                ),
                oeeService.analysis(
                        normalizedStart, normalizedEnd, organizationId, null,
                        normalizedPeriodType, "EQUIPMENT", 20
                )
        );
    }

    private String normalizePeriodType(String periodType) {
        String normalized = periodType == null
                ? "DAY"
                : periodType.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DAY", "WEEK", "MONTH").contains(normalized)) {
            throw new BusinessException(
                    "VISUALIZATION_PERIOD_TYPE_INVALID", "统计周期仅支持日、周、月"
            );
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public List<VisualizationDtos.SceneSummary> scenes() {
        var current = SecurityUtils.currentUser();
        return mapper.scenes(current.tenantId(), dataPermissionService.current());
    }

    @Transactional(readOnly = true)
    public VisualizationDtos.SceneDetail scene(long id) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        VisualizationDtos.SceneConfig scene =
                mapper.scene(current.tenantId(), id, scope);
        if (scene == null) {
            throw notFound("VISUALIZATION_SCENE_NOT_FOUND", "三维场景不存在或无权访问");
        }
        return new VisualizationDtos.SceneDetail(
                scene,
                mapper.sceneBreadcrumb(current.tenantId(), id),
                mapper.sceneNodes(current.tenantId(), id, scope),
                mapper.statusColors(current.tenantId())
        );
    }

    @Transactional(readOnly = true)
    public VisualizationDtos.EquipmentSnapshot equipmentSnapshot(long equipmentId) {
        var current = SecurityUtils.currentUser();
        VisualizationDtos.EquipmentSnapshotBase base = mapper.equipmentSnapshot(
                current.tenantId(), equipmentId, dataPermissionService.current()
        );
        if (base == null) {
            throw notFound("VISUALIZATION_EQUIPMENT_NOT_FOUND", "设备不存在或无权访问");
        }
        return new VisualizationDtos.EquipmentSnapshot(
                base.equipmentId(),
                base.equipmentCode(),
                base.equipmentName(),
                base.categoryName(),
                base.organizationName(),
                base.locationName(),
                base.statusCode(),
                base.statusName(),
                base.statusColor(),
                base.statusSince(),
                base.statusDurationSeconds(),
                base.responsibleName(),
                base.todayRunMinutes(),
                base.todayStopMinutes(),
                base.todayOee(),
                base.todayInspectionDue(),
                base.todayInspectionCompleted(),
                base.todayMaintenanceDue(),
                base.todayMaintenanceCompleted(),
                base.openAbnormalCount(),
                mapper.equipmentEvents(current.tenantId(), equipmentId, 20)
        );
    }

    @Transactional(readOnly = true)
    public List<VisualizationDtos.ModelResource> models() {
        return mapper.models(SecurityUtils.currentUser().tenantId());
    }

    @Transactional(readOnly = true)
    public VisualizationDtos.ModelResource model(long id) {
        return requireModel(SecurityUtils.currentUser().tenantId(), id);
    }

    @Transactional(readOnly = true)
    public List<VisualizationDtos.StatusColor> statusColors() {
        return mapper.statusColors(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public long createModel(VisualizationDtos.SaveModelRequest request) {
        var current = SecurityUtils.currentUser();
        validateModel(request);
        String code = code(request.resourceCode());
        if (mapper.countModelCode(current.tenantId(), code, null) > 0) {
            throw conflict("VISUALIZATION_MODEL_CODE_EXISTS", "模型资源编码已存在");
        }
        VisualizationDtos.SaveModelRequest normalized = normalizeModel(request, code);
        mapper.insertModel(current.tenantId(), normalized, current.userId());
        long id = mapper.modelIdByCode(current.tenantId(), code);
        changeLogService.record("VISUALIZATION_MODEL", id, "CREATE", null, mapper.model(
                current.tenantId(), id
        ));
        return id;
    }

    @Transactional
    public void updateModel(long id, VisualizationDtos.SaveModelRequest request) {
        var current = SecurityUtils.currentUser();
        VisualizationDtos.ModelResource before = requireModel(current.tenantId(), id);
        validateModel(request);
        String code = code(request.resourceCode());
        if (!before.resourceCode().equals(code)) {
            throw new BusinessException(
                    "VISUALIZATION_MODEL_CODE_IMMUTABLE", "模型资源编码创建后不可修改"
            );
        }
        if (request.version() == null || mapper.updateModel(
                current.tenantId(), id, normalizeModel(request, code), current.userId()
        ) == 0) {
            throw conflict("VISUALIZATION_MODEL_CONCURRENT_UPDATE", "模型资源已被其他用户修改");
        }
        changeLogService.record(
                "VISUALIZATION_MODEL", id, "UPDATE", before,
                mapper.model(current.tenantId(), id)
        );
    }

    @Transactional
    public void deleteModel(long id, int version) {
        var current = SecurityUtils.currentUser();
        VisualizationDtos.ModelResource before = requireModel(current.tenantId(), id);
        if (mapper.countModelReferences(current.tenantId(), id) > 0) {
            throw conflict("VISUALIZATION_MODEL_IN_USE", "模型资源已被场景或节点引用");
        }
        if (mapper.deleteModel(current.tenantId(), id, version, current.userId()) == 0) {
            throw conflict("VISUALIZATION_MODEL_CONCURRENT_UPDATE", "模型资源已被其他用户修改");
        }
        changeLogService.record("VISUALIZATION_MODEL", id, "DELETE", before, null);
    }

    @Transactional
    public long createScene(VisualizationDtos.SaveSceneRequest request) {
        var current = SecurityUtils.currentUser();
        validateScene(request, null);
        requireOrganizationAccess(request.organizationId());
        if (request.parentSceneId() > 0) {
            requireScene(current.tenantId(), request.parentSceneId());
        }
        if (request.modelResourceId() != null) {
            requireModel(current.tenantId(), request.modelResourceId());
        }
        String code = code(request.sceneCode());
        if (mapper.countSceneCode(current.tenantId(), code, null) > 0) {
            throw conflict("VISUALIZATION_SCENE_CODE_EXISTS", "场景编码已存在");
        }
        VisualizationDtos.SaveSceneRequest normalized = normalizeScene(request, code);
        mapper.insertScene(current.tenantId(), normalized, current.userId());
        long id = mapper.sceneIdByCode(current.tenantId(), code);
        changeLogService.record("VISUALIZATION_SCENE", id, "CREATE", null, normalized);
        return id;
    }

    @Transactional
    public void updateScene(long id, VisualizationDtos.SaveSceneRequest request) {
        var current = SecurityUtils.currentUser();
        VisualizationDtos.SceneConfig before = mapper.scene(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (before == null) {
            throw notFound("VISUALIZATION_SCENE_NOT_FOUND", "三维场景不存在或无权访问");
        }
        validateScene(request, id);
        requireOrganizationAccess(request.organizationId());
        if (request.parentSceneId() > 0) {
            requireScene(current.tenantId(), request.parentSceneId());
        }
        if (request.modelResourceId() != null) {
            requireModel(current.tenantId(), request.modelResourceId());
        }
        String code = code(request.sceneCode());
        if (!before.sceneCode().equals(code)) {
            throw new BusinessException(
                    "VISUALIZATION_SCENE_CODE_IMMUTABLE", "场景编码创建后不可修改"
            );
        }
        if (request.version() == null || mapper.updateScene(
                current.tenantId(), id, normalizeScene(request, code), current.userId()
        ) == 0) {
            throw conflict("VISUALIZATION_SCENE_CONCURRENT_UPDATE", "场景已被其他用户修改");
        }
        changeLogService.record("VISUALIZATION_SCENE", id, "UPDATE", before, request);
    }

    @Transactional
    public void deleteScene(long id, int version) {
        var current = SecurityUtils.currentUser();
        VisualizationDtos.SceneConfig before = mapper.scene(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (before == null) {
            throw notFound("VISUALIZATION_SCENE_NOT_FOUND", "三维场景不存在或无权访问");
        }
        if (mapper.countSceneReferences(current.tenantId(), id) > 0) {
            throw conflict("VISUALIZATION_SCENE_IN_USE", "场景仍有子场景或节点引用");
        }
        if (mapper.deleteScene(current.tenantId(), id, version, current.userId()) == 0) {
            throw conflict("VISUALIZATION_SCENE_CONCURRENT_UPDATE", "场景已被其他用户修改");
        }
        changeLogService.record("VISUALIZATION_SCENE", id, "DELETE", before, null);
    }

    @Transactional
    public long createNode(long sceneId, VisualizationDtos.SaveNodeRequest request) {
        var current = SecurityUtils.currentUser();
        requireScene(current.tenantId(), sceneId);
        validateNode(request);
        validateNodeReferences(current.tenantId(), request);
        String code = code(request.nodeCode());
        if (mapper.countNodeCode(current.tenantId(), sceneId, code, null) > 0) {
            throw conflict("VISUALIZATION_NODE_CODE_EXISTS", "当前场景内节点编码已存在");
        }
        VisualizationDtos.SaveNodeRequest normalized = normalizeNode(request, code);
        mapper.insertNode(current.tenantId(), sceneId, normalized, current.userId());
        long id = mapper.nodeIdByCode(current.tenantId(), sceneId, code);
        changeLogService.record("VISUALIZATION_SCENE_NODE", id, "CREATE", null, normalized);
        return id;
    }

    @Transactional
    public void updateNode(long id, VisualizationDtos.SaveNodeRequest request) {
        var current = SecurityUtils.currentUser();
        Long sceneId = mapper.nodeSceneId(current.tenantId(), id);
        if (sceneId == null) {
            throw notFound("VISUALIZATION_NODE_NOT_FOUND", "场景节点不存在");
        }
        requireScene(current.tenantId(), sceneId);
        validateNode(request);
        validateNodeReferences(current.tenantId(), request);
        if (request.version() == null || mapper.updateNode(
                current.tenantId(), id, normalizeNode(request, code(request.nodeCode())),
                current.userId()
        ) == 0) {
            throw conflict("VISUALIZATION_NODE_CONCURRENT_UPDATE", "节点不存在或已被修改");
        }
        changeLogService.record("VISUALIZATION_SCENE_NODE", id, "UPDATE", null, request);
    }

    @Transactional
    public void deleteNode(long id, int version) {
        var current = SecurityUtils.currentUser();
        Long sceneId = mapper.nodeSceneId(current.tenantId(), id);
        if (sceneId == null) {
            throw notFound("VISUALIZATION_NODE_NOT_FOUND", "场景节点不存在");
        }
        requireScene(current.tenantId(), sceneId);
        if (mapper.deleteNode(current.tenantId(), id, version, current.userId()) == 0) {
            throw conflict("VISUALIZATION_NODE_CONCURRENT_UPDATE", "节点不存在或已被修改");
        }
        changeLogService.record("VISUALIZATION_SCENE_NODE", id, "DELETE", null, null);
    }

    @Transactional
    public void updateStatusColor(
            String statusCode,
            VisualizationDtos.SaveStatusColorRequest request
    ) {
        var current = SecurityUtils.currentUser();
        String normalized = code(statusCode);
        if (mapper.updateStatusColor(
                current.tenantId(), normalized, request, current.userId()
        ) == 0) {
            throw conflict(
                    "VISUALIZATION_STATUS_COLOR_CONCURRENT_UPDATE",
                    "状态颜色不存在或已被其他用户修改"
            );
        }
        changeLogService.record(
                "VISUALIZATION_STATUS_COLOR", normalized, "UPDATE", null, request
        );
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException("VISUALIZATION_DATE_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_RANGE_DAYS) {
            throw new BusinessException(
                    "VISUALIZATION_DATE_RANGE_TOO_LARGE", "分析日期范围不能超过 366 天"
            );
        }
    }

    private void validateModel(VisualizationDtos.SaveModelRequest request) {
        if (!"PRIMITIVE".equals(request.modelFormat()) && request.attachmentId() == null) {
            throw new BusinessException(
                    "VISUALIZATION_MODEL_FILE_REQUIRED", "GLB/GLTF 模型必须关联上传文件"
            );
        }
        if ("PRIMITIVE".equals(request.modelFormat())
                && (request.primitiveType() == null || request.primitiveType().isBlank())) {
            throw new BusinessException(
                    "VISUALIZATION_PRIMITIVE_REQUIRED", "程序化模型必须选择基础形状"
            );
        }
    }

    private void validateScene(VisualizationDtos.SaveSceneRequest request, Long currentId) {
        if (currentId != null && request.parentSceneId().equals(currentId)) {
            throw new BusinessException("VISUALIZATION_SCENE_SELF_PARENT", "场景不能以自身为父级");
        }
    }

    private void validateNode(VisualizationDtos.SaveNodeRequest request) {
        int bindings = (request.organizationId() == null ? 0 : 1)
                + (request.equipmentId() == null ? 0 : 1);
        if ("ORGANIZATION".equals(request.nodeType()) && request.organizationId() == null) {
            throw new BusinessException(
                    "VISUALIZATION_NODE_ORGANIZATION_REQUIRED", "组织节点必须绑定组织"
            );
        }
        if ("EQUIPMENT".equals(request.nodeType()) && request.equipmentId() == null) {
            throw new BusinessException(
                    "VISUALIZATION_NODE_EQUIPMENT_REQUIRED", "设备节点必须绑定设备"
            );
        }
        if ("DECORATION".equals(request.nodeType()) && bindings > 0) {
            throw new BusinessException(
                    "VISUALIZATION_DECORATION_BINDING_INVALID", "装饰节点不能绑定业务对象"
            );
        }
    }

    private void validateNodeReferences(
            long tenantId,
            VisualizationDtos.SaveNodeRequest request
    ) {
        if (request.organizationId() != null) {
            requireOrganizationAccess(request.organizationId());
        }
        if (request.equipmentId() != null && mapper.equipmentSnapshot(
                tenantId, request.equipmentId(), dataPermissionService.current()
        ) == null) {
            throw notFound(
                    "VISUALIZATION_NODE_EQUIPMENT_NOT_FOUND",
                    "节点绑定设备不存在或无权访问"
            );
        }
        if (request.targetSceneId() != null) {
            requireScene(tenantId, request.targetSceneId());
        }
        if (request.modelResourceId() != null) {
            requireModel(tenantId, request.modelResourceId());
        }
    }

    private void requireOrganizationAccess(long organizationId) {
        if (!dataPermissionService.current().canCreateIn(organizationId)) {
            throw new BusinessException(
                    "VISUALIZATION_ORGANIZATION_FORBIDDEN",
                    "无权在所选组织下维护可视化场景",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private VisualizationDtos.ModelResource requireModel(long tenantId, long id) {
        VisualizationDtos.ModelResource model = mapper.model(tenantId, id);
        if (model == null) {
            throw notFound("VISUALIZATION_MODEL_NOT_FOUND", "模型资源不存在");
        }
        return model;
    }

    private void requireScene(long tenantId, long id) {
        if (mapper.scene(tenantId, id, dataPermissionService.current()) == null) {
            throw notFound("VISUALIZATION_SCENE_NOT_FOUND", "三维场景不存在或无权访问");
        }
    }

    private int parameter(long tenantId, String key, int fallback, int min, int max) {
        Integer value = mapper.integerParameter(tenantId, key);
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }

    private String code(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private VisualizationDtos.SaveModelRequest normalizeModel(
            VisualizationDtos.SaveModelRequest request,
            String code
    ) {
        return new VisualizationDtos.SaveModelRequest(
                code, request.resourceName().trim(), request.resourceLevel(),
                request.attachmentId(), request.modelFormat(),
                clean(request.primitiveType()), request.fallbackColor(),
                request.thumbnailAttachmentId(), clean(request.description()),
                request.status(), request.version()
        );
    }

    private VisualizationDtos.SaveSceneRequest normalizeScene(
            VisualizationDtos.SaveSceneRequest request,
            String code
    ) {
        return new VisualizationDtos.SaveSceneRequest(
                request.parentSceneId(), code, request.sceneName().trim(),
                request.sceneLevel(), request.organizationId(),
                request.modelResourceId(), request.backgroundColor(), request.gridColor(),
                request.cameraX(), request.cameraY(), request.cameraZ(),
                request.targetX(), request.targetY(), request.targetZ(),
                request.autoRotateFlag(), request.sortOrder(), request.status(),
                clean(request.description()), request.version()
        );
    }

    private VisualizationDtos.SaveNodeRequest normalizeNode(
            VisualizationDtos.SaveNodeRequest request,
            String code
    ) {
        return new VisualizationDtos.SaveNodeRequest(
                code, request.displayName().trim(), request.nodeType(),
                request.organizationId(), request.equipmentId(), request.targetSceneId(),
                request.modelResourceId(), request.positionX(), request.positionY(),
                request.positionZ(), request.rotationX(), request.rotationY(),
                request.rotationZ(), request.scaleX(), request.scaleY(), request.scaleZ(),
                request.labelVisibleFlag(), request.visibleFlag(), request.sortOrder(),
                clean(request.description()), request.version()
        );
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(code, message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
