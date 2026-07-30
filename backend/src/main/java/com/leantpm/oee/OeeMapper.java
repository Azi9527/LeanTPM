package com.leantpm.oee;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface OeeMapper {
    String findParameterValue(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    List<Long> findOrganizationAndDescendantIds(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId
    );

    int countActiveEquipment(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    OeeDtos.EquipmentRef findEquipment(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    OeeDtos.EquipmentRef findEquipmentByCode(
            @Param("tenantId") long tenantId,
            @Param("equipmentCode") String equipmentCode,
            @Param("scope") DataPermission scope
    );

    int countActiveOrganization(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId,
            @Param("scope") DataPermission scope
    );

    String findOrganizationType(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId
    );

    List<OeeDtos.ShiftRow> findShifts(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countShifts(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("status") Integer status
    );

    OeeDtos.ShiftRow findShift(@Param("tenantId") long tenantId, @Param("id") long id);

    int countShiftCode(
            @Param("tenantId") long tenantId,
            @Param("shiftCode") String shiftCode,
            @Param("excludeId") Long excludeId
    );

    int insertShift(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveShiftRequest request,
            @Param("operatorId") long operatorId
    );

    Long findShiftIdByCode(
            @Param("tenantId") long tenantId,
            @Param("shiftCode") String shiftCode
    );

    int updateShift(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") OeeDtos.SaveShiftRequest request,
            @Param("operatorId") long operatorId
    );

    int countShiftReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int deleteShift(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.CalendarRow> findCalendars(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationId") Long organizationId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countCalendars(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("organizationId") Long organizationId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    OeeDtos.CalendarRow findCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int countCalendarKey(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveCalendarRequest request,
            @Param("excludeId") Long excludeId
    );

    int insertCalendar(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveCalendarRequest request,
            @Param("operatorId") long operatorId
    );

    Long findCalendarId(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveCalendarRequest request
    );

    int updateCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") OeeDtos.SaveCalendarRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.TargetRow> findTargets(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("targetLevel") String targetLevel,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countTargets(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("targetLevel") String targetLevel,
            @Param("status") Integer status
    );

    OeeDtos.TargetRow findTarget(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int countOverlappingTarget(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveTargetRequest request,
            @Param("excludeId") Long excludeId
    );

    int insertTarget(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveTargetRequest request,
            @Param("oeeTarget") BigDecimal oeeTarget,
            @Param("operatorId") long operatorId
    );

    Long lastInsertId();

    int updateTarget(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") OeeDtos.SaveTargetRequest request,
            @Param("oeeTarget") BigDecimal oeeTarget,
            @Param("operatorId") long operatorId
    );

    int deleteTarget(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.LossReasonRow> findLossReasons(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("lossCategory") String lossCategory,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countLossReasons(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("lossCategory") String lossCategory,
            @Param("status") Integer status
    );

    OeeDtos.LossReasonRow findLossReason(@Param("tenantId") long tenantId, @Param("id") long id);

    int countLossReasonCode(
            @Param("tenantId") long tenantId,
            @Param("reasonCode") String reasonCode,
            @Param("excludeId") Long excludeId
    );

    int insertLossReason(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveLossReasonRequest request,
            @Param("operatorId") long operatorId
    );

    Long findLossReasonIdByCode(
            @Param("tenantId") long tenantId,
            @Param("reasonCode") String reasonCode
    );

    int updateLossReason(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") OeeDtos.SaveLossReasonRequest request,
            @Param("operatorId") long operatorId
    );

    int countLossReasonChildren(@Param("tenantId") long tenantId, @Param("id") long id);

    int countLossReasonReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int deleteLossReason(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.OutputRow> findOutputs(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countOutputs(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    OeeDtos.OutputRow findOutput(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    OeeDtos.OutputRow findOutputByKey(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("productionDate") LocalDate productionDate,
            @Param("shiftId") long shiftId
    );

    int insertOutput(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveOutputRequest request,
            @Param("operatorId") long operatorId
    );

    int updateOutput(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveOutputRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteOutput(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int syncRecordOutput(
            @Param("tenantId") long tenantId,
            @Param("request") OeeDtos.SaveOutputRequest request,
            @Param("operatorId") long operatorId
    );

    int clearRecordOutput(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("productionDate") LocalDate productionDate,
            @Param("shiftId") long shiftId,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.DowntimeRow> findDowntimes(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("lossReasonId") Long lossReasonId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countDowntimes(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("lossReasonId") Long lossReasonId
    );

    OeeDtos.DowntimeRow findDowntime(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int insertDowntime(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveDowntimeRequest request,
            @Param("operatorId") long operatorId
    );

    int updateDowntime(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveDowntimeRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteDowntime(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    BigDecimal sumDowntime(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("productionDate") LocalDate productionDate,
            @Param("shiftId") long shiftId,
            @Param("plannedFlag") boolean plannedFlag
    );

    List<OeeDtos.OeeRecordRow> findRecords(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("organizationId") Long organizationId,
            @Param("dataStatus") String dataStatus,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countRecords(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("organizationId") Long organizationId,
            @Param("dataStatus") String dataStatus,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    OeeDtos.OeeRecordRow findRecord(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    OeeDtos.OeeRecordRow findRecordByKey(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("productionDate") LocalDate productionDate,
            @Param("shiftId") long shiftId
    );

    int insertRecord(
            @Param("tenantId") long tenantId,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveOeeRecordRequest request,
            @Param("calculation") OeeDtos.OeeCalculation calculation,
            @Param("operatorId") long operatorId
    );

    int updateRecord(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("organizationId") long organizationId,
            @Param("request") OeeDtos.SaveOeeRecordRequest request,
            @Param("calculation") OeeDtos.OeeCalculation calculation,
            @Param("operatorId") long operatorId
    );

    int updateRecordCalculation(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("calculation") OeeDtos.OeeCalculation calculation,
            @Param("operatorId") long operatorId,
            @Param("version") int version
    );

    int updateRecordWorkflow(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operatorId") long operatorId,
            @Param("version") int version
    );

    BigDecimal findTargetRate(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("organizationId") long organizationId,
            @Param("productionDate") LocalDate productionDate
    );

    int nextCalculationVersion(
            @Param("tenantId") long tenantId,
            @Param("recordId") long recordId
    );

    int insertCalculationLog(
            @Param("tenantId") long tenantId,
            @Param("recordId") long recordId,
            @Param("calculationVersion") int calculationVersion,
            @Param("triggerType") String triggerType,
            @Param("inputSnapshot") String inputSnapshot,
            @Param("outputSnapshot") String outputSnapshot,
            @Param("validationMessage") String validationMessage,
            @Param("operatorId") long operatorId
    );

    List<OeeDtos.CalculationLogRow> findCalculationLogs(
            @Param("tenantId") long tenantId,
            @Param("recordId") long recordId
    );

    OeeDtos.AnalysisSummary analysisSummary(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("equipmentId") Long equipmentId,
            @Param("capPerformance") boolean capPerformance
    );

    List<OeeDtos.TrendPoint> analysisTrend(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("equipmentId") Long equipmentId,
            @Param("period") String period,
            @Param("capPerformance") boolean capPerformance
    );

    List<OeeDtos.RankingRow> analysisRanking(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("equipmentId") Long equipmentId,
            @Param("rankingType") String rankingType,
            @Param("limit") int limit,
            @Param("capPerformance") boolean capPerformance
    );

    List<OeeDtos.LossAnalysisRow> analysisLosses(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("equipmentId") Long equipmentId
    );
}
