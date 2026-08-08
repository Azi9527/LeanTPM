package com.leantpm.security.datascope;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DataPermissionMapper {
    List<RoleScopeGrant> findRoleScopeGrants(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    Long findUserOrganizationId(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    List<Long> findManagedOrganizationIds(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    List<Long> findOrganizationAndDescendantIds(
            @Param("tenantId") long tenantId,
            @Param("rootIds") List<Long> rootIds
    );

    record RoleScopeGrant(String scopeType, Long organizationId) {
    }
}
