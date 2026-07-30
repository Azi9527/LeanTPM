package com.leantpm.equipment;

import com.leantpm.security.datascope.DataPermission;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface EquipmentMapper {
    List<EquipmentDtos.EquipmentRow> findEquipmentPage(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            @Param("organizationId") Long organizationId,
            @Param("locationId") Long locationId,
            @Param("currentStatusCode") String currentStatusCode,
            @Param("lifecycleStage") String lifecycleStage,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countEquipment(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            @Param("organizationId") Long organizationId,
            @Param("locationId") Long locationId,
            @Param("currentStatusCode") String currentStatusCode,
            @Param("lifecycleStage") String lifecycleStage,
            @Param("status") Integer status
    );

    EquipmentDtos.EquipmentRow findEquipment(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("scope") DataPermission scope
    );

    int countEquipmentCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId
    );

    int insertEquipment(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("request") EquipmentDtos.SaveEquipmentRequest request,
            @Param("operatorId") long operatorId
    );

    Long findEquipmentIdByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    int updateEquipment(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") EquipmentDtos.SaveEquipmentRequest request,
            @Param("operatorId") long operatorId
    );

    int softDeleteEquipment(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countOperationalHistory(@Param("tenantId") long tenantId, @Param("id") long id);

    List<EquipmentDtos.AttributeValueRow> findAttributeValues(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("categoryId") long categoryId
    );

    int deleteAttributeValues(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("operatorId") long operatorId
    );

    int insertAttributeValue(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("definitionId") long definitionId,
            @Param("dataType") String dataType,
            @Param("value") String value,
            @Param("operatorId") long operatorId
    );

    List<EquipmentDtos.ResponsiblePersonRow> findResponsiblePersons(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    int deleteResponsiblePersons(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("operatorId") long operatorId
    );

    int insertResponsiblePerson(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("request") EquipmentDtos.SaveResponsiblePersonRequest request,
            @Param("operatorId") long operatorId
    );

    int insertInitialStatus(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("statusCode") String statusCode,
            @Param("now") LocalDateTime now,
            @Param("operatorId") long operatorId
    );

    CurrentStatus findCurrentStatus(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    int updateCurrentStatus(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("statusCode") String statusCode,
            @Param("statusSince") LocalDateTime statusSince,
            @Param("reason") String reason,
            @Param("sourceType") String sourceType,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int closeOpenStatusHistory(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("endedTime") LocalDateTime endedTime
    );

    int insertStatusHistory(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("fromStatusCode") String fromStatusCode,
            @Param("toStatusCode") String toStatusCode,
            @Param("startedTime") LocalDateTime startedTime,
            @Param("reason") String reason,
            @Param("sourceType") String sourceType,
            @Param("operatorId") long operatorId
    );

    List<EquipmentDtos.StatusHistoryRow> findStatusHistory(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    int countStatusCode(@Param("tenantId") long tenantId, @Param("statusCode") String statusCode);

    int insertTransfer(
            @Param("tenantId") long tenantId,
            @Param("equipment") EquipmentDtos.EquipmentRow equipment,
            @Param("request") EquipmentDtos.TransferRequest request,
            @Param("operatorId") long operatorId
    );

    List<EquipmentDtos.TransferRow> findTransfers(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    int updateEquipmentTransfer(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("request") EquipmentDtos.TransferRequest request,
            @Param("operatorId") long operatorId
    );

    List<EquipmentDtos.BarcodeRow> findBarcodes(
            @Param("tenantId") long tenantId,
            @Param("scope") DataPermission scope,
            @Param("equipmentId") Long equipmentId,
            @Param("activeOnly") boolean activeOnly
    );

    EquipmentDtos.BarcodeRow findBarcode(
            @Param("tenantId") long tenantId,
            @Param("barcodeId") long barcodeId
    );

    EquipmentDtos.BarcodeRow findActiveBarcode(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    int invalidateActiveBarcode(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("reason") String reason,
            @Param("operatorId") long operatorId
    );

    int insertBarcode(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("accessToken") String accessToken,
            @Param("barcodeType") String barcodeType,
            @Param("operatorId") long operatorId
    );

    EquipmentDtos.PublicEquipmentView findPublicEquipment(
            @Param("accessToken") String accessToken
    );

    List<EquipmentDtos.DocumentRow> findDocuments(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId
    );

    LookupRow findCategoryByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    LookupRow findOrganizationByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    LocationLookup findLocationByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    UserLookup findUserByUsername(
            @Param("tenantId") long tenantId,
            @Param("username") String username
    );

    record CurrentStatus(
            long id,
            String statusCode,
            LocalDateTime statusSince,
            String reason,
            String sourceType,
            int version
    ) {
    }

    record LookupRow(long id, String name, int status) {
    }

    record LocationLookup(long id, String name, long organizationId, int status) {
    }

    record UserLookup(long id, String name, Long organizationId, int status) {
    }
}
