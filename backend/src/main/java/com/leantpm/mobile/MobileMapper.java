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

    MobileDtos.WorkCount inspectionCount(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
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

    List<MobileDtos.TaskLink> activeTasks(
            @Param("tenantId") long tenantId,
            @Param("equipmentId") long equipmentId,
            @Param("userId") long userId
    );
}
