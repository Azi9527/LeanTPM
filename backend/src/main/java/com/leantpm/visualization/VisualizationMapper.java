package com.leantpm.visualization;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface VisualizationMapper {
    VisualizationDtos.CoreMetrics coreMetrics(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds
    );

    List<VisualizationDtos.StatusMetric> statusDistribution(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds
    );

    List<VisualizationDtos.OrganizationMetric> organizationDistribution(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds
    );

    List<VisualizationDtos.LiveEquipment> liveEquipment(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("longStopMinutes") int longStopMinutes,
            @Param("longOfflineMinutes") int longOfflineMinutes,
            @Param("limit") int limit
    );

    VisualizationDtos.WorkflowMetrics workflowMetrics(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("workflowType") String workflowType
    );

    List<VisualizationDtos.WorkflowTrend> workflowTrend(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("workflowType") String workflowType
    );

    Integer integerParameter(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    List<Long> organizationAndDescendantIds(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId
    );

    List<VisualizationDtos.SceneSummary> scenes(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope
    );

    VisualizationDtos.SceneConfig scene(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId,
            @Param("scope") DataPermission scope
    );

    List<VisualizationDtos.SceneSummary> sceneBreadcrumb(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId
    );

    List<VisualizationDtos.SceneNode> sceneNodes(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId,
            @Param("scope") DataPermission scope
    );

    List<VisualizationDtos.ModelResource> models(@Param("tenantId") long tenantId);

    VisualizationDtos.ModelResource model(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    List<VisualizationDtos.StatusColor> statusColors(@Param("tenantId") long tenantId);

    VisualizationDtos.EquipmentSnapshotBase equipmentSnapshot(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    List<VisualizationDtos.EquipmentEvent> equipmentEvents(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("limit") int limit
    );

    int countModelCode(
            @Param("tenantId") long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("excludeId") Long excludeId
    );

    int insertModel(
            @Param("tenantId") long tenantId,
            @Param("request") VisualizationDtos.SaveModelRequest request,
            @Param("operatorId") long operatorId
    );

    Long modelIdByCode(
            @Param("tenantId") long tenantId,
            @Param("resourceCode") String resourceCode
    );

    int updateModel(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") VisualizationDtos.SaveModelRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteModel(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countModelReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int countSceneCode(
            @Param("tenantId") long tenantId,
            @Param("sceneCode") String sceneCode,
            @Param("excludeId") Long excludeId
    );

    int insertScene(
            @Param("tenantId") long tenantId,
            @Param("request") VisualizationDtos.SaveSceneRequest request,
            @Param("operatorId") long operatorId
    );

    Long sceneIdByCode(
            @Param("tenantId") long tenantId,
            @Param("sceneCode") String sceneCode
    );

    int updateScene(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") VisualizationDtos.SaveSceneRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteScene(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countSceneReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int countNodeCode(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId,
            @Param("nodeCode") String nodeCode,
            @Param("excludeId") Long excludeId
    );

    int insertNode(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId,
            @Param("request") VisualizationDtos.SaveNodeRequest request,
            @Param("operatorId") long operatorId
    );

    Long nodeIdByCode(
            @Param("tenantId") long tenantId,
            @Param("sceneId") long sceneId,
            @Param("nodeCode") String nodeCode
    );

    Long nodeSceneId(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int updateNode(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") VisualizationDtos.SaveNodeRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteNode(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int updateStatusColor(
            @Param("tenantId") long tenantId,
            @Param("statusCode") String statusCode,
            @Param("request") VisualizationDtos.SaveStatusColorRequest request,
            @Param("operatorId") long operatorId
    );
}
