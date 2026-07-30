package com.leantpm.security.datascope;

import java.util.Set;

/**
 * 已解析的数据权限。业务查询只能消费结构化字段，禁止拼接来自请求的 SQL。
 */
public record DataPermission(
        boolean allData,
        long userId,
        boolean selfData,
        Set<Long> organizationIds
) {
    public DataPermission {
        organizationIds = organizationIds == null ? Set.of() : Set.copyOf(organizationIds);
    }

    public static DataPermission all(long userId) {
        return new DataPermission(true, userId, true, Set.of());
    }

    public static DataPermission restricted(long userId, boolean selfData, Set<Long> organizationIds) {
        return new DataPermission(false, userId, selfData, organizationIds);
    }

    public boolean canAccess(long ownerUserId, Long organizationId) {
        return allData
                || (selfData && ownerUserId == userId)
                || (organizationId != null && organizationIds.contains(organizationId));
    }

    public boolean canCreateIn(Long organizationId) {
        return allData || (organizationId != null && organizationIds.contains(organizationId));
    }
}
