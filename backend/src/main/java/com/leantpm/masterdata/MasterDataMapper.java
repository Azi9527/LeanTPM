package com.leantpm.masterdata;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MasterDataMapper {
    List<MasterDataDtos.OrganizationRow> findOrganizations(@Param("tenantId") long tenantId);

    MasterDataDtos.OrganizationRow findOrganization(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countOrganizationCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId
    );

    int insertOrganization(
            @Param("tenantId") long tenantId,
            @Param("request") MasterDataDtos.SaveOrganizationRequest request,
            @Param("operatorId") long operatorId
    );

    Long findOrganizationIdByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    int updateOrganization(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MasterDataDtos.SaveOrganizationRequest request,
            @Param("operatorId") long operatorId
    );

    int softDeleteOrganization(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countOrganizationChildren(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("enabledOnly") boolean enabledOnly
    );

    int countOrganizationReferences(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("enabledOnly") boolean enabledOnly
    );

    List<MasterDataDtos.LocationRow> findLocations(@Param("tenantId") long tenantId);

    MasterDataDtos.LocationRow findLocation(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countLocationCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId
    );

    int insertLocation(
            @Param("tenantId") long tenantId,
            @Param("request") MasterDataDtos.SaveLocationRequest request,
            @Param("operatorId") long operatorId
    );

    Long findLocationIdByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    int updateLocation(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MasterDataDtos.SaveLocationRequest request,
            @Param("operatorId") long operatorId
    );

    int softDeleteLocation(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countLocationChildren(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("enabledOnly") boolean enabledOnly
    );

    int countLocationEquipmentReferences(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("enabledOnly") boolean enabledOnly
    );

    List<MasterDataDtos.EquipmentCategoryRow> findCategories(@Param("tenantId") long tenantId);

    MasterDataDtos.EquipmentCategoryRow findCategory(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countCategoryCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId
    );

    int insertCategory(
            @Param("tenantId") long tenantId,
            @Param("treeLevel") int treeLevel,
            @Param("request") MasterDataDtos.SaveEquipmentCategoryRequest request,
            @Param("operatorId") long operatorId
    );

    Long findCategoryIdByCode(
            @Param("tenantId") long tenantId,
            @Param("code") String code
    );

    int updateCategory(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("treeLevel") int treeLevel,
            @Param("request") MasterDataDtos.SaveEquipmentCategoryRequest request,
            @Param("operatorId") long operatorId
    );

    int softDeleteCategory(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countCategoryChildren(@Param("tenantId") long tenantId, @Param("id") long id);

    int countCategoryEquipmentReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int countCategoryAttributeDefinitions(@Param("tenantId") long tenantId, @Param("id") long id);

    int updateCategoryTreeLevel(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("treeLevel") int treeLevel,
            @Param("operatorId") long operatorId
    );

    List<MasterDataDtos.AttributeDefinitionRow> findCategoryAttributes(
            @Param("tenantId") long tenantId,
            @Param("categoryId") long categoryId,
            @Param("includeInherited") boolean includeInherited
    );

    MasterDataDtos.AttributeDefinitionRow findAttribute(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countAttributeCode(
            @Param("tenantId") long tenantId,
            @Param("categoryId") long categoryId,
            @Param("code") String code,
            @Param("excludeId") Long excludeId
    );

    int insertAttribute(
            @Param("tenantId") long tenantId,
            @Param("categoryId") long categoryId,
            @Param("request") MasterDataDtos.SaveAttributeDefinitionRequest request,
            @Param("enumOptionsJson") String enumOptionsJson,
            @Param("operatorId") long operatorId
    );

    Long findAttributeIdByCode(
            @Param("tenantId") long tenantId,
            @Param("categoryId") long categoryId,
            @Param("code") String code
    );

    int updateAttribute(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") MasterDataDtos.SaveAttributeDefinitionRequest request,
            @Param("enumOptionsJson") String enumOptionsJson,
            @Param("operatorId") long operatorId
    );

    int softDeleteAttribute(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int countAttributeValueReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int countActiveUser(@Param("tenantId") long tenantId, @Param("id") long id);

    List<MasterDataDtos.ReferenceUser> findReferenceUsers(@Param("tenantId") long tenantId);
}
