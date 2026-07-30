package com.leantpm.maintenance;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface MaintenanceMapper {
    List<Long> findTenantIds();

    int countActiveUser(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int countActiveCategory(@Param("tenantId") long tenantId, @Param("categoryId") long categoryId);

    int countActiveEquipment(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    List<MaintenanceDtos.ItemRow> findItems(
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

    MaintenanceDtos.ItemRow findItem(@Param("tenantId") long tenantId, @Param("id") long id);

    int countItemCode(
            @Param("tenantId") long tenantId,
            @Param("itemCode") String itemCode,
            @Param("excludeId") Long excludeId
    );

    int insertItem(
            @Param("tenantId") long tenantId,
            @Param("request") MaintenanceDtos.SaveItemRequest request,
            @Param("resultOptionsJson") String resultOptionsJson,
            @Param("operatorId") long operatorId
    );

    Long findItemIdByCode(@Param("tenantId") long tenantId, @Param("itemCode") String itemCode);

    int updateItem(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.SaveItemRequest request,
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

    List<MaintenanceDtos.SchemeRow> findSchemes(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("maintenanceType") String maintenanceType,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countSchemes(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("maintenanceType") String maintenanceType,
            @Param("status") Integer status
    );

    MaintenanceDtos.SchemeRow findScheme(@Param("tenantId") long tenantId, @Param("id") long id);

    int countSchemeCode(
            @Param("tenantId") long tenantId,
            @Param("schemeCode") String schemeCode,
            @Param("excludeId") Long excludeId
    );

    int insertScheme(
            @Param("tenantId") long tenantId,
            @Param("schemeCode") String schemeCode,
            @Param("request") MaintenanceDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    Long findSchemeIdByCode(@Param("tenantId") long tenantId, @Param("schemeCode") String schemeCode);

    int updateScheme(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    int nextSchemeVersionNumber(@Param("tenantId") long tenantId, @Param("schemeId") long schemeId);

    int insertSchemeVersion(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionNumber") int versionNumber,
            @Param("request") MaintenanceDtos.SaveSchemeRequest request,
            @Param("operatorId") long operatorId
    );

    Long findSchemeVersionId(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId,
            @Param("versionNumber") int versionNumber
    );

    MaintenanceDtos.SchemeVersionRow findSchemeVersion(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId
    );

    List<MaintenanceDtos.SchemeVersionRow> findSchemeVersions(
            @Param("tenantId") long tenantId,
            @Param("schemeId") long schemeId
    );

    List<MaintenanceDtos.SchemeItemRow> findSchemeItems(
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
            @Param("item") MaintenanceDtos.SaveSchemeItemRequest item,
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

    List<MaintenanceDtos.PlanRow> findPlans(
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

    MaintenanceDtos.PlanRow findPlan(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int updatePlanStatus(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.UpdatePlanStatusRequest request,
            @Param("operatorId") long operatorId
    );

    int updatePlanMeter(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.UpdateMeterRequest request,
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

    int updateMeterPlanGeneration(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("lastGenerationDate") LocalDate lastGenerationDate,
            @Param("operatorId") long operatorId
    );

    EquipmentSnapshot findEquipmentSnapshot(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("scope") DataPermission scope
    );

    EquipmentRuntime findEquipmentRuntime(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
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
            @Param("scheme") MaintenanceDtos.SchemeRow scheme,
            @Param("version") MaintenanceDtos.SchemeVersionRow version,
            @Param("equipment") EquipmentSnapshot equipment,
            @Param("request") MaintenanceDtos.ManualTaskRequest request,
            @Param("operatorId") long operatorId
    );

    int copyTaskItems(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("versionId") long versionId
    );

    List<MaintenanceDtos.TaskRow> findTasks(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("taskStatus") String taskStatus,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("mineOnly") boolean mineOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countTasks(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("taskStatus") String taskStatus,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("mineOnly") boolean mineOnly
    );

    MaintenanceDtos.TaskRow findTask(
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

    MaintenanceDtos.ResultRow findResult(
            @Param("tenantId") long tenantId,
            @Param("taskItemId") long taskItemId
    );

    int insertResult(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") MaintenanceDtos.SaveResultRequest request,
            @Param("selectedValuesJson") String selectedValuesJson,
            @Param("operatorId") long operatorId
    );

    int updateResult(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") MaintenanceDtos.SaveResultRequest request,
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

    int submitResults(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int assignTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.AssignTaskRequest request,
            @Param("operatorId") long operatorId
    );

    int countTaskCollaborator(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("userId") long userId
    );

    List<MaintenanceDtos.CollaboratorRow> findTaskCollaborators(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int deleteTaskCollaborators(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int insertTaskCollaborator(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("userId") long userId,
            @Param("operatorId") long operatorId
    );

    int touchTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int startTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("version") int version,
            @Param("previousEquipmentStatus") String previousEquipmentStatus,
            @Param("operatorId") long operatorId
    );

    int pauseTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int insertTaskPause(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("reason") String reason,
            @Param("operatorId") long operatorId
    );

    int resumeTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int closeTaskPause(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("operatorId") long operatorId
    );

    List<MaintenanceDtos.PauseRow> findTaskPauses(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int finishTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("remark") String remark,
            @Param("operatorId") long operatorId
    );

    int confirmTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.ReviewTaskRequest request,
            @Param("operatorId") long operatorId
    );

    int returnTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.ReviewTaskRequest request,
            @Param("operatorId") long operatorId
    );

    List<MaintenanceDtos.MaterialUsageRow> findTaskMaterials(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    MaintenanceDtos.MaterialUsageRow findMaterial(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("id") long id
    );

    int insertMaterial(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") MaintenanceDtos.MaterialUsageRequest request,
            @Param("operatorId") long operatorId
    );

    int updateMaterial(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("request") MaintenanceDtos.MaterialUsageRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteMaterial(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int closeTask(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.CloseTaskRequest request,
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

    List<MaintenanceDtos.TaskEventRow> findTaskEvents(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    int countMissingRequiredResults(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int countInvalidResultAttachments(@Param("tenantId") long tenantId, @Param("taskId") long taskId);

    int insertAbnormal(
            @Param("tenantId") long tenantId,
            @Param("abnormalCode") String abnormalCode,
            @Param("task") MaintenanceDtos.TaskRow task,
            @Param("item") TaskItemData item,
            @Param("resultId") long resultId,
            @Param("description") String description,
            @Param("operatorId") long operatorId
    );

    List<MaintenanceDtos.AbnormalRow> findAbnormalities(
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

    List<MaintenanceDtos.AbnormalRow> findTaskAbnormalities(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId
    );

    MaintenanceDtos.AbnormalRow findAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int handleAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.HandleAbnormalRequest request,
            @Param("operatorId") long operatorId
    );

    int verifyAbnormal(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MaintenanceDtos.VerifyAbnormalRequest request,
            @Param("targetStatus") String targetStatus,
            @Param("operatorId") long operatorId
    );

    MaintenanceDtos.Statistics statistics(
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

    record EquipmentRuntime(String statusCode, int statusVersion) {
    }

    record TaskItemData(
            long id,
            long taskId,
            Long sourceItemId,
            String itemCode,
            String itemName,
            String itemCategory,
            String maintenancePart,
            String maintenanceContent,
            String maintenanceMethod,
            String maintenanceTool,
            String maintenanceStandard,
            String standardValue,
            java.math.BigDecimal minimumValue,
            java.math.BigDecimal maximumValue,
            String unit,
            String resultType,
            String resultOptionsJson,
            Boolean requiredFlag,
            Boolean photoRequiredFlag,
            Boolean attachmentRequiredFlag,
            Boolean numericRequiredFlag,
            Boolean skipAllowedFlag,
            Boolean stopRequiredFlag,
            String abnormalSeverity,
            String abnormalAdvice,
            Integer standardMinutes,
            String safetyNotes,
            Integer sortOrder
    ) {
    }

    record GenerationPlan(
            long id,
            long schemeId,
            long schemeVersionId,
            String schemeCode,
            String schemeName,
            int schemeVersionNumber,
            String maintenanceType,
            long equipmentId,
            String cycleType,
            int cycleInterval,
            java.math.BigDecimal triggerThreshold,
            String weekDays,
            String monthDays,
            java.time.LocalTime scheduledTime,
            int reminderDays,
            int generationLeadDays,
            Long assigneeUserId,
            String teamCode,
            boolean reviewRequired,
            boolean backfillAllowed,
            boolean stopRequired,
            String restoreStatusCode,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            LocalDate nextGenerationDate,
            java.math.BigDecimal currentMeterValue,
            java.math.BigDecimal nextTriggerValue
    ) {
    }
}
