package com.leantpm.mobile;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MobileMapper {
    Boolean mobileEnabled(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    Integer integerParameter(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    String stringParameter(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    MobileDtos.WorkCount inspectionCount(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    MobileDtos.EquipmentStatusCount equipmentStatusCount(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope
    );

    List<MobileDtos.EquipmentStatusRow> equipmentStatusRows(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentStatusCode") String currentStatusCode,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long equipmentStatusTotal(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentStatusCode") String currentStatusCode
    );

    List<Long> inspectionScanOrganizationIds(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    MobileDtos.AbnormalCount inspectionAbnormalCount(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    MobileDtos.PersonalInspectionReport personalInspectionReport(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("startTime") java.time.LocalDateTime startTime,
            @Param("endExclusive") java.time.LocalDateTime endExclusive
    );

    List<MobileDtos.InspectionReportOrganization> inspectionReportOrganizations(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope
    );

    List<MobileDtos.InspectionReportEmployee> inspectionReportEmployees(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId
    );

    MobileDtos.InspectionPerformanceSummary inspectionPerformanceSummary(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query
    );

    MobileDtos.QuickInspectionSummary quickInspectionSummary(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query
    );

    List<MobileDtos.InspectionEmployeePerformance> inspectionEmployeePerformance(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query
    );

    List<MobileDtos.InspectionOrganizationPerformance> inspectionOrganizationPerformance(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query
    );

    List<MobileDtos.InspectionPerformanceTask> inspectionPerformanceTasks(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query,
            @Param("metric") String metric,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long inspectionPerformanceTaskTotal(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query,
            @Param("metric") String metric
    );

    List<MobileDtos.InspectionPerformanceDetail> inspectionPerformanceTaskItems(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query,
            @Param("taskId") long taskId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long inspectionPerformanceTaskItemTotal(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("currentUserId") long currentUserId,
            @Param("query") MobileDtos.InspectionPerformanceQuery query,
            @Param("taskId") long taskId
    );

    MobileDtos.WorkCount maintenanceCount(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    List<MobileDtos.MessageItem> messages(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("limit") int limit
    );

    MobileDtos.EquipmentBase equipmentByToken(
            @Param("tenantId") long tenantId,
            @Param("token") String token,
            @Param("scope") DataPermission scope
    );

    MobileDtos.EquipmentAccessProbe equipmentAccessProbe(
            @Param("tenantId") long tenantId,
            @Param("token") String token
    );

    List<MobileDtos.TaskLink> activeTasks(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("userId") long userId
    );

    List<MobileDtos.ApplicableInspectionScheme> applicableInspectionSchemes(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    List<MobileDtos.TodayInspectionRecord> todayInspections(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    List<MobileDtos.AssigneeOption> assignees(@Param("tenantId") long tenantId);

    List<MobileDtos.TeamOption> teams(@Param("tenantId") long tenantId);
}
