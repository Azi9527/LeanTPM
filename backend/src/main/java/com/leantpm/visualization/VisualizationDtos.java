package com.leantpm.visualization;

import com.leantpm.oee.OeeDtos;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class VisualizationDtos {
    private VisualizationDtos() {
    }

    public record CoreMetrics(
            long total,
            long running,
            long stopped,
            long fault,
            long repair,
            long maintenance,
            long offline,
            long inspection,
            long other,
            long idle,
            long scrapped
    ) {
    }

    public record StatusMetric(
            String statusCode,
            String statusName,
            String displayColor,
            long equipmentCount,
            BigDecimal proportion
    ) {
    }

    public record OrganizationMetric(
            long organizationId,
            String organizationCode,
            String organizationName,
            String organizationType,
            long equipmentCount,
            long runningCount,
            long faultCount,
            long stoppedCount,
            long offlineCount,
            long idleCount,
            long scrappedCount
    ) {
    }

    public record LiveEquipment(
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            String organizationName,
            String statusCode,
            String statusName,
            String displayColor,
            LocalDateTime statusSince,
            long durationSeconds,
            BigDecimal todayOee,
            boolean longDuration
    ) {
    }

    public record WorkflowMetrics(
            String workflowType,
            long due,
            long completed,
            long pending,
            long overdue,
            long abnormal,
            BigDecimal completionRate,
            BigDecimal onTimeRate
    ) {
    }

    public record WorkflowTrend(
            LocalDate statisticDate,
            String workflowType,
            long due,
            long completed,
            long overdue,
            long abnormal
    ) {
    }

    public record ReliabilityMetrics(
            BigDecimal mttrSeconds,
            BigDecimal mtbfSeconds,
            BigDecimal mttfSeconds,
            long faultCount,
            long completedRepairCount,
            BigDecimal runTimeHours
    ) {
    }

    public record InspectionRegistrationMetrics(
            long registered,
            long quickRegistered,
            long equipmentCovered,
            long abnormalRegistered
    ) {
    }

    public record RecentInspectionRegistration(
            long taskId,
            String taskCode,
            String equipmentCode,
            String equipmentName,
            String schemeName,
            String organizationName,
            String sourceType,
            String executorName,
            LocalDateTime completedTime,
            long abnormalCount
    ) {
    }

    public record DashboardResult(
            LocalDateTime generatedAt,
            LocalDate startDate,
            LocalDate endDate,
            Long organizationId,
            String periodType,
            int refreshSeconds,
            CoreMetrics core,
            List<StatusMetric> statusDistribution,
            List<OrganizationMetric> organizationDistribution,
            List<LiveEquipment> liveEquipment,
            WorkflowMetrics inspection,
            WorkflowMetrics maintenance,
            List<WorkflowTrend> workflowTrend,
            ReliabilityMetrics reliability,
            InspectionRegistrationMetrics inspectionRegistration,
            List<RecentInspectionRegistration> recentInspectionRegistrations,
            OeeDtos.AnalysisResult oee
    ) {
    }

    public record ModelResource(
            long id,
            String resourceCode,
            String resourceName,
            String resourceLevel,
            Long attachmentId,
            String modelFormat,
            String primitiveType,
            String fallbackColor,
            Long thumbnailAttachmentId,
            String description,
            int status,
            int version
    ) {
    }

    public record StatusColor(
            long id,
            String statusCode,
            String statusName,
            String displayColor,
            String emissiveColor,
            boolean pulseFlag,
            int sortOrder,
            int status,
            String description,
            int version
    ) {
    }

    public record SceneSummary(
            long id,
            long parentSceneId,
            String sceneCode,
            String sceneName,
            String sceneLevel,
            long organizationId,
            String organizationName,
            Long modelResourceId,
            int sortOrder,
            int status,
            int nodeCount,
            int version
    ) {
    }

    public record SceneConfig(
            long id,
            long parentSceneId,
            String sceneCode,
            String sceneName,
            String sceneLevel,
            long organizationId,
            String organizationName,
            Long modelResourceId,
            String backgroundColor,
            String gridColor,
            BigDecimal cameraX,
            BigDecimal cameraY,
            BigDecimal cameraZ,
            BigDecimal targetX,
            BigDecimal targetY,
            BigDecimal targetZ,
            boolean autoRotateFlag,
            int sortOrder,
            int status,
            String description,
            int version
    ) {
    }

    public record SceneNode(
            long id,
            long sceneId,
            String nodeCode,
            String displayName,
            String nodeType,
            Long organizationId,
            Long equipmentId,
            Long targetSceneId,
            Long modelResourceId,
            String modelFormat,
            String primitiveType,
            Long attachmentId,
            String fallbackColor,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal positionZ,
            BigDecimal rotationX,
            BigDecimal rotationY,
            BigDecimal rotationZ,
            BigDecimal scaleX,
            BigDecimal scaleY,
            BigDecimal scaleZ,
            boolean labelVisibleFlag,
            boolean visibleFlag,
            int sortOrder,
            String statusCode,
            String statusName,
            String displayColor,
            boolean pulseFlag,
            int version
    ) {
    }

    public record SceneDetail(
            SceneConfig scene,
            List<SceneSummary> breadcrumb,
            List<SceneNode> nodes,
            List<StatusColor> statusColors
    ) {
    }

    public record EquipmentSnapshot(
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            String categoryName,
            String organizationName,
            String locationName,
            String statusCode,
            String statusName,
            String statusColor,
            LocalDateTime statusSince,
            long statusDurationSeconds,
            String responsibleName,
            BigDecimal todayRunMinutes,
            BigDecimal todayStopMinutes,
            BigDecimal todayOee,
            long todayInspectionDue,
            long todayInspectionCompleted,
            long todayMaintenanceDue,
            long todayMaintenanceCompleted,
            long openAbnormalCount,
            List<EquipmentEvent> recentEvents
    ) {
    }

    public record EquipmentSnapshotBase(
            long equipmentId,
            String equipmentCode,
            String equipmentName,
            String categoryName,
            String organizationName,
            String locationName,
            String statusCode,
            String statusName,
            String statusColor,
            LocalDateTime statusSince,
            long statusDurationSeconds,
            String responsibleName,
            BigDecimal todayRunMinutes,
            BigDecimal todayStopMinutes,
            BigDecimal todayOee,
            long todayInspectionDue,
            long todayInspectionCompleted,
            long todayMaintenanceDue,
            long todayMaintenanceCompleted,
            long openAbnormalCount
    ) {
    }

    public record EquipmentEvent(
            String eventType,
            String eventCode,
            String eventStatus,
            String description,
            LocalDateTime eventTime
    ) {
    }

    public record SaveModelRequest(
            @NotBlank @Size(max = 64) String resourceCode,
            @NotBlank @Size(max = 100) String resourceName,
            @NotBlank
            @Pattern(regexp = "FACTORY|PLANT_AREA|WORKSHOP|LINE|EQUIPMENT")
            String resourceLevel,
            @Min(1) Long attachmentId,
            @NotBlank @Pattern(regexp = "PRIMITIVE|GLB|GLTF") String modelFormat,
            @Size(max = 32) String primitiveType,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String fallbackColor,
            @Min(1) Long thumbnailAttachmentId,
            @Size(max = 500) String description,
            @NotNull @Min(0) @Max(1) Integer status,
            @Min(0) Integer version
    ) {
    }

    public record SaveSceneRequest(
            @NotNull @Min(0) Long parentSceneId,
            @NotBlank @Size(max = 64) String sceneCode,
            @NotBlank @Size(max = 100) String sceneName,
            @NotBlank
            @Pattern(regexp = "ENTERPRISE|FACTORY|PLANT_AREA|WORKSHOP|LINE")
            String sceneLevel,
            @NotNull @Min(1) Long organizationId,
            @Min(1) Long modelResourceId,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String backgroundColor,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String gridColor,
            @NotNull BigDecimal cameraX,
            @NotNull BigDecimal cameraY,
            @NotNull BigDecimal cameraZ,
            @NotNull BigDecimal targetX,
            @NotNull BigDecimal targetY,
            @NotNull BigDecimal targetZ,
            @NotNull Boolean autoRotateFlag,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record SaveNodeRequest(
            @NotBlank @Size(max = 64) String nodeCode,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Pattern(regexp = "ORGANIZATION|EQUIPMENT|DECORATION")
            String nodeType,
            @Min(1) Long organizationId,
            @Min(1) Long equipmentId,
            @Min(1) Long targetSceneId,
            @Min(1) Long modelResourceId,
            @NotNull BigDecimal positionX,
            @NotNull BigDecimal positionY,
            @NotNull BigDecimal positionZ,
            @NotNull BigDecimal rotationX,
            @NotNull BigDecimal rotationY,
            @NotNull BigDecimal rotationZ,
            @NotNull @DecimalMin("0.0001") BigDecimal scaleX,
            @NotNull @DecimalMin("0.0001") BigDecimal scaleY,
            @NotNull @DecimalMin("0.0001") BigDecimal scaleZ,
            @NotNull Boolean labelVisibleFlag,
            @NotNull Boolean visibleFlag,
            @NotNull @Min(0) Integer sortOrder,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record SaveStatusColorRequest(
            @NotBlank @Size(max = 64) String statusName,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String displayColor,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String emissiveColor,
            @NotNull Boolean pulseFlag,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @NotNull @Min(0) Integer version
    ) {
    }
}
