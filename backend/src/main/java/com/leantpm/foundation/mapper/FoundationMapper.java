package com.leantpm.foundation.mapper;

import com.leantpm.foundation.dto.FoundationDtos;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FoundationMapper {
    List<FoundationDtos.ParameterRow> findParameters(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("groupCode") String groupCode
    );

    FoundationDtos.ParameterRow findParameterByKey(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    FoundationDtos.ParameterRow findParameterById(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    long countParameterKey(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    int insertParameter(
            @Param("tenantId") long tenantId,
            @Param("request") FoundationDtos.SaveParameterRequest request,
            @Param("operatorId") long operatorId
    );

    long findParameterIdByKey(
            @Param("tenantId") long tenantId,
            @Param("parameterKey") String parameterKey
    );

    int updateParameter(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") FoundationDtos.SaveParameterRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteParameter(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("operatorId") long operatorId
    );

    List<FoundationDtos.NumberRuleRow> findNumberRules(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword
    );

    FoundationDtos.NumberRuleRow findNumberRuleByCode(
            @Param("tenantId") long tenantId,
            @Param("ruleCode") String ruleCode
    );

    FoundationDtos.NumberRuleRow findNumberRuleById(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    long countNumberRuleCode(
            @Param("tenantId") long tenantId,
            @Param("ruleCode") String ruleCode
    );

    int insertNumberRule(
            @Param("tenantId") long tenantId,
            @Param("request") FoundationDtos.SaveNumberRuleRequest request,
            @Param("operatorId") long operatorId
    );

    long findNumberRuleIdByCode(
            @Param("tenantId") long tenantId,
            @Param("ruleCode") String ruleCode
    );

    int updateNumberRule(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") FoundationDtos.SaveNumberRuleRequest request,
            @Param("operatorId") long operatorId
    );

    int advanceSequence(
            @Param("tenantId") long tenantId,
            @Param("ruleId") long ruleId,
            @Param("periodKey") String periodKey,
            @Param("operatorId") long operatorId
    );

    long findCurrentSequence(
            @Param("tenantId") long tenantId,
            @Param("ruleId") long ruleId,
            @Param("periodKey") String periodKey
    );
}
