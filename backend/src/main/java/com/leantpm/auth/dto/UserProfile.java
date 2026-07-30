package com.leantpm.auth.dto;

import java.util.List;
import java.util.Set;

public record UserProfile(
        long id,
        long tenantId,
        String username,
        String realName,
        boolean mustChangePassword,
        Set<String> roles,
        Set<String> permissions,
        List<MenuItem> menus
) {
    public record MenuItem(
            long id,
            long parentId,
            String menuType,
            String menuName,
            String routeName,
            String routePath,
            String componentPath,
            String permissionCode,
            String icon,
            int sortOrder
    ) {
    }
}
