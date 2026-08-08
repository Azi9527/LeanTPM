package com.leantpm.security.datascope;

import com.leantpm.security.CurrentUser;
import com.leantpm.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DataPermissionService {
    private final DataPermissionMapper mapper;

    public DataPermissionService(DataPermissionMapper mapper) {
        this.mapper = mapper;
    }

    public DataPermission current() {
        return resolve(SecurityUtils.currentUser());
    }

    public DataPermission resolve(CurrentUser user) {
        if (user.roles().contains("ADMIN") || user.roles().contains("SUPER_ADMIN")) {
            return DataPermission.all(user.userId());
        }
        List<DataPermissionMapper.RoleScopeGrant> grants =
                mapper.findRoleScopeGrants(user.tenantId(), user.userId());
        if (grants.stream().anyMatch(grant -> "ALL".equals(grant.scopeType()))) {
            return DataPermission.all(user.userId());
        }

        boolean selfData = grants.stream().anyMatch(grant -> "SELF".equals(grant.scopeType()));
        Set<Long> organizations = new LinkedHashSet<>();
        Long ownOrganizationId = mapper.findUserOrganizationId(user.tenantId(), user.userId());

        boolean includeOwn = grants.stream().anyMatch(grant ->
                "ORGANIZATION".equals(grant.scopeType())
                        || "ORGANIZATION_AND_CHILDREN".equals(grant.scopeType())
        );
        Set<Long> organizationRoots = new LinkedHashSet<>();
        if (includeOwn) {
            if (ownOrganizationId != null) {
                organizationRoots.add(ownOrganizationId);
            }
            organizationRoots.addAll(
                    mapper.findManagedOrganizationIds(user.tenantId(), user.userId())
            );
            organizations.addAll(organizationRoots);
        }

        grants.stream()
                .filter(grant -> "CUSTOM".equals(grant.scopeType()))
                .map(DataPermissionMapper.RoleScopeGrant::organizationId)
                .filter(java.util.Objects::nonNull)
                .forEach(organizations::add);

        boolean includeChildren = grants.stream()
                .anyMatch(grant -> "ORGANIZATION_AND_CHILDREN".equals(grant.scopeType()));
        if (includeChildren && !organizationRoots.isEmpty()) {
            organizations.addAll(
                    mapper.findOrganizationAndDescendantIds(
                            user.tenantId(),
                            List.copyOf(organizationRoots)
                    )
            );
        }
        return DataPermission.restricted(user.userId(), selfData, organizations);
    }
}
