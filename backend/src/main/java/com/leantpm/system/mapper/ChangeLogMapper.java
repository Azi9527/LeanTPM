package com.leantpm.system.mapper;

import com.leantpm.system.audit.ChangeLogDtos;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ChangeLogMapper {
    int insertChangeLog(
            @Param("tenantId") long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("operationType") String operationType,
            @Param("beforeData") String beforeData,
            @Param("afterData") String afterData,
            @Param("changedFields") String changedFields,
            @Param("operatorId") long operatorId,
            @Param("operatorName") String operatorName,
            @Param("requestId") String requestId
    );

    List<ChangeLogDtos.ChangeLogRow> findChangeLogs(
            @Param("tenantId") long tenantId,
            @Param("resourceType") String resourceType,
            @Param("keyword") String keyword,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countChangeLogs(
            @Param("tenantId") long tenantId,
            @Param("resourceType") String resourceType,
            @Param("keyword") String keyword,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<ChangeLogDtos.ChangeLogRow> findResourceChangeLogs(
            @Param("tenantId") long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("limit") int limit
    );
}
