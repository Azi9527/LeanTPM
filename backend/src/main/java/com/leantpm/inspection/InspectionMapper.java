package com.leantpm.inspection;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface InspectionMapper {
    List<Long> findTenantIds();

    int countActiveUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int countActiveCategory(@Param("tenantId") long tenantId, @Param("categoryId") long categoryId);

    int countActiveEquipment(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    List<InspectionDtos.ItemRow> findItems(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("itemCategory") String itemCategory,
            @Param("resultType") String resultType,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countItems(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("itemCategory") String itemCategory,
            @Param("resultType") String resultType,
            @Param("status") Integer status
    );

    InspectionDtos.ItemRow findItem(@Param("tenantId") long tenantId, @Param("id") long id);

    int countItemCode(
            @Param("tenantId") long tenantId,
            @Param("itemCode") String itemCode,
            @Param("excludeId") Long excludeId
    );

    int insertItem(
            @Param("tenantId") long tenantId,
            @Param("request") InspectionDtos.SaveItemRequest request,
            @Param("resultOptionsJson") String resultOptionsJson,
            @Param("operatorId") long operatorId
    );

    Long findItemIdByCode(@Param("tenantId") long tenantId, @Param("itemCode") String itemCode);

    int updateItem(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.SaveItemRequest request,
            @Param("resultOptionsJson") String resultOptionsJson,
            @Param("operatorId") long operatorId
    );

    int softDeleteItem(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countPublishedItemReferences(@Param("tenantId") long tenantId, @Param("itemId") long itemId);

    List<InspectionDtos.SchemeRow> findSchemes(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("inspectionType") String inspectionType,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countSchemes(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("inspectionType") String inspectionType,
            @Param("status") Integer status
    );

    InspectionDtos.SchemeRow findScheme(@Param("tenantId") long tenantId, @Param("id") long id);

    int countSchemeCode(
            @Param("tenantId") long tenantId,
            @Param("schemeCode") String schemeCode,
            @Param("excludeId") Long excludeId
    );

    int insertScheme(
            @Param("tenantId") long tenantId,
            @Param("schemeCode") String schemeCode,
            @Param("request") InspectionDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    Long findSchemeIdByCode(@Param("tenantId") long tenantId, @Param("schemeCode") String schemeCode);

    int updateScheme(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    int nextSchemeVersionNumber(@Param("tenantId") long tenantId, @Param("schemeId") long schemeId);

    int insertSchemeVersion(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionNumber") int versionNumber,
            @Param("request") InspectionDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    Long findSchemeVersionId(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionNumber") int versionNumber
    );

    InspectionDtos.SchemeVersionRow findSchemeVersion(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId
    );

    List<InspectionDtos.SchemeVersionRow> findSchemeVersions(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId
    );

    List<InspectionDtos.SchemeItemRow> findSchemeItems(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId
    );

    List<Long> findSchemeCategoryIds(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId
    );

    List<Long> findSchemeEquipmentIds(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId
    );

    int insertSchemeItem(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId,
            @Param("item") InspectionDtos.SaveSchemeItemRequest item,
            @Param("operatorId") long operatorId
    );

    int insertSchemeCategory(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId,
            @Param("categoryId") long categoryId,
            @Param("operatorId") long operatorId
    );

    int insertSchemeEquipment(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId,
            @Param("equipmentId") long equipmentId,
            @Param("operatorId") long operatorId
    );

    int publishSchemeVersion(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId,
            @Param("operatorId") long operatorId
    );

    int retireOtherSchemeVersions(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionId") long versionId,
            @Param("operatorId") long operatorId
    );

    int setSchemeCurrentVersion(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionId") long versionId,
            @Param("operatorId") long operatorId
    );

    List<ApplicableEquipment> findApplicableEquipment(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId,
            @Param("scope") DataPermission scope
    );

    int insertPlan(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionId") long versionId,
            @Param("equipmentId") long equipmentId,
            @Param("nextGenerationDate") LocalDate nextGenerationDate,
            @Param("operatorId") long operatorId
    );

    int cancelSupersededPlans(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionId") long versionId,
            @Param("operatorId") long operatorId
    );

    List<InspectionDtos.PlanRow> findPlans(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("planStatus") String planStatus,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countPlans(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("planStatus") String planStatus
    );

    InspectionDtos.PlanRow findPlan(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int updatePlanStatus(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.UpdatePlanStatusRequest request,
            @Param("operatorId") long operatorId
    );

    List<GenerationPlan> findGenerationPlans(
            @Param("tenantId") long tenantId,
            @Param("throughDate") LocalDate throughDate
    );

    int updatePlanGeneration(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("lastGenerationDate") LocalDate lastGenerationDate,
            @Param("nextGenerationDate") LocalDate nextGenerationDate,
            @Param("operatorId") long operatorId
    );

    EquipmentSnapshot findEquipmentSnapshot(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    int insertTask(
            @Param("tenantId") long tenantId,
            @Param("taskCode") String taskCode,
            @Param("plan") GenerationPlan plan,
            @Param("equipment") EquipmentSnapshot equipment,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("plannedStartTime") LocalDateTime plannedStartTime,
            @Param("dueTime") LocalDateTime dueTime,
            @Param("occurrenceKey") String occurrenceKey,
            @Param("sourceType") String sourceType,
            @Param("backfill") boolean backfill,
            @Param("remark") String remark,
            @Param("operatorId") long operatorId
    );

    Long findTaskIdByCode(@Param("tenantId") long tenantId, @Param("taskCode") String taskCode);

    Long findTaskIdByOccurrence(
            @Param("tenantId") long tenantId,
            @Param("planId") long planId,
            @Param("occurrenceKey") String occurrenceKey
    );

    int insertManualTask(
            @Param("tenantId") long tenantId,
            @Param("taskCode") String taskCode,
            @Param("scheme") InspectionDtos.SchemeRow scheme,
            @Param("version") InspectionDtos.SchemeVersionRow version,
            @Param("equipment") EquipmentSnapshot equipment,
            @Param("request") InspectionDtos.ManualTaskRequest request,
            @Param("primaryAssigneeUserId") Long primaryAssigneeUserId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("operatorId") long operatorId
    );

    ManualTaskIdentity findManualTaskByIdempotencyKey(
            @Param("tenantId") long tenantId,
            @Param("idempotencyKey") String idempotencyKey
    );

    int copyTaskItems(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("versionId") long versionId
    );

    List<InspectionDtos.TaskRow> findTasks(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("query") InspectionDtos.TaskQuery query,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countTasks(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("query") InspectionDtos.TaskQuery query
    );

    List<InspectionDtos.TaskResultExportRow> findTaskResultExportRows(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("query") InspectionDtos.TaskQuery query,
            @Param("limit") int limit
    );

    List<InspectionDtos.TaskAbnormalExportRow> findTaskAbnormalExportRows(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("query") InspectionDtos.TaskQuery query,
            @Param("limit") int limit
    );

    List<InspectionDtos.TaskAttachmentExportRow> findTaskAttachmentExportRows(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("query") InspectionDtos.TaskQuery query,
            @Param("limit") int limit
    );

    InspectionDtos.TaskRow findTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    TaskItemData findTaskItem(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("taskItemId") long taskItemId
    );

    List<TaskItemData> findTaskItems(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    InspectionDtos.ResultRow findResult(
            @Param("tenantId") long tenantId,
            @Param("taskItemId") long taskItemId
    );

    int insertResult(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") InspectionDtos.SaveResultRequest request,
            @Param("selectedValuesJson") String selectedValuesJson,
            @Param("operatorId") long operatorId
    );

    int updateResult(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") InspectionDtos.SaveResultRequest request,
            @Param("selectedValuesJson") String selectedValuesJson,
            @Param("operatorId") long operatorId
    );

    int replaceResultAttachments(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("taskResultId") long taskResultId,
            @Param("attachmentId") long attachmentId,
            @Param("attachmentType") String attachmentType,
            @Param("operatorId") long operatorId
    );

    int deleteResultAttachments(@Param("tenantId") long tenantId, @Param("taskResultId") long taskResultId);

    List<Long> findResultAttachmentIds(
            @Param("tenantId") long tenantId,
            @Param("taskResultId") long taskResultId
    );

    List<InspectionDtos.InspectionAttachmentRow> findTaskAttachments(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    List<InspectionDtos.InspectionAttachmentRow> findAbnormalAttachments(
            @Param("tenantId") long tenantId,
            @Param("abnormalId") long abnormalId
    );

    int countTaskAttachment(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("attachmentId") long attachmentId
    );

    int countAbnormalAttachment(
            @Param("tenantId") long tenantId,
            @Param("abnormalId") long abnormalId,
            @Param("attachmentId") long attachmentId
    );

    int countAvailableAttachment(
            @Param("tenantId") long tenantId,
            @Param("attachmentId") long attachmentId
    );

    int updateTaskAfterDraft(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("fromVersion") int fromVersion,
            @Param("remark") String remark,
            @Param("operatorId") long operatorId
    );

    SubmissionState lockSubmission(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int submitResults(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int updateTaskStatus(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("fromVersion") int fromVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("remark") String remark,
            @Param("operatorId") long operatorId
    );

    int assignTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("primaryAssigneeUserId") long primaryAssigneeUserId,
            @Param("teamCode") String teamCode,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int deleteTaskAssignees(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int insertTaskAssignee(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("userId") long userId,
            @Param("primary") boolean primary,
            @Param("sortOrder") int sortOrder,
            @Param("operatorId") long operatorId
    );

    int countTaskAssignees(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int countTaskAssignee(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("userId") long userId
    );

    int reviewTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.ReviewTaskRequest request,
            @Param("targetStatus") String targetStatus,
            @Param("operatorId") long operatorId
    );

    int closeTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.CloseTaskRequest request,
            @Param("targetStatus") String targetStatus,
            @Param("operatorId") long operatorId
    );

    int markOverdueTasks(@Param("tenantId") long tenantId);

    int insertTaskEvent(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("eventType") String eventType,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("remark") String remark,
            @Param("operatorId") long operatorId
    );

    List<InspectionDtos.TaskEventRow> findTaskEvents(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int countMissingRequiredResults(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int countInvalidResultAttachments(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int insertAbnormal(
            @Param("tenantId") long tenantId,
            @Param("abnormalCode") String abnormalCode,
            @Param("task") InspectionDtos.TaskRow task,
            @Param("item") TaskItemData item,
            @Param("resultId") long resultId,
            @Param("description") String description,
            @Param("equipmentStopRequired") Boolean equipmentStopRequired,
            @Param("operatorId") long operatorId
    );

    int countStopRequiredResults(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    String findStopReason(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int markAbnormalEquipmentStatusChanged(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("operatorId") long operatorId
    );

    EquipmentStatusData findEquipmentStatus(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    List<InspectionDtos.AbnormalRow> findAbnormalities(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("abnormalStatus") String abnormalStatus,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countAbnormalities(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("abnormalStatus") String abnormalStatus
    );

    List<InspectionDtos.AbnormalRow> findTaskAbnormalities(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    InspectionDtos.AbnormalRow findAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int handleAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.HandleAbnormalRequest request,
            @Param("operatorId") long operatorId
    );

    int verifyAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionDtos.VerifyAbnormalRequest request,
            @Param("targetStatus") String targetStatus,
            @Param("operatorId") long operatorId
    );

    InspectionDtos.Statistics statistics(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("today") LocalDate today
    );

    record ApplicableEquipment(long id, long organizationId, long locationId) {
    }

    record EquipmentSnapshot(
            long id,
            String equipmentCode,
            String equipmentName,
            long organizationId,
            long locationId
    ) {
    }

    record TaskItemData(
            long id,
            long taskId,
            Long sourceItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String inspectionPart,
            String inspectionContent,
            String inspectionMethod,
            String inspectionTool,
            String inspectionStandard,
            String standardValue,
            java.math.BigDecimal minimumValue,
            java.math.BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Integer photoMinCount,
            Integer photoMaxCount,
            Integer photoMaxSizeMb,
            String photoAllowedTypes,
            Integer photoCompressionQuality,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Boolean abnormalDefaultStopFlag,
            Integer standardMinutes,
            String safetyNotes,
            Integer sortOrder
    ) {
    }

    record EquipmentStatusData(String statusCode, int version) {
    }

    record GenerationPlan(
            long id,
            long schemeId,
            long schemeVersionId,
            String schemeCode,
            String schemeName,
            int schemeVersionNumber,
            String inspectionType,
            long equipmentId,
            String cycleType,
            int cycleInterval,
            String weekDays,
            String monthDays,
            java.time.LocalTime scheduledTime,
            int generationLeadMinutes,
            long workCalendarId,
            String workDays,
            Long assigneeUserId,
            String teamCode,
            boolean reviewRequired,
            boolean backfillAllowed,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            LocalDate nextGenerationDate
    ) {
    }

    record ManualTaskIdentity(long id, String requestHash) {
    }

    record SubmissionState(
            long id,
            String taskStatus,
            int version,
            Long submittedBy,
            String submittedByName,
            LocalDateTime submittedTime
    ) {
    }
}
