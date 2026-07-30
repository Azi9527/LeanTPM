package com.leantpm.security.datascope;

import com.leantpm.security.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataPermissionServiceTest {
    private final DataPermissionMapper mapper = mock(DataPermissionMapper.class);
    private final DataPermissionService service = new DataPermissionService(mapper);
    private final CurrentUser user = new CurrentUser(
            20L, 1L, "tester", "测试用户", false, Set.of("TEST"), Set.of(), "sid"
    );

    @Test
    void allScopeShortCircuitsOtherRestrictions() {
        when(mapper.findRoleScopeGrants(1L, 20L)).thenReturn(List.of(
                new DataPermissionMapper.RoleScopeGrant("SELF", null),
                new DataPermissionMapper.RoleScopeGrant("ALL", null)
        ));

        DataPermission result = service.resolve(user);

        assertThat(result.allData()).isTrue();
        assertThat(result.canAccess(999L, 999L)).isTrue();
    }

    @Test
    void combinesSelfCustomAndOrganizationDescendants() {
        when(mapper.findRoleScopeGrants(1L, 20L)).thenReturn(List.of(
                new DataPermissionMapper.RoleScopeGrant("SELF", null),
                new DataPermissionMapper.RoleScopeGrant("ORGANIZATION_AND_CHILDREN", null),
                new DataPermissionMapper.RoleScopeGrant("CUSTOM", 90L)
        ));
        when(mapper.findUserOrganizationId(1L, 20L)).thenReturn(10L);
        when(mapper.findOrganizationAndDescendantIds(1L, List.of(10L)))
                .thenReturn(List.of(10L, 11L, 12L));

        DataPermission result = service.resolve(user);

        assertThat(result.allData()).isFalse();
        assertThat(result.selfData()).isTrue();
        assertThat(result.organizationIds()).containsExactlyInAnyOrder(10L, 11L, 12L, 90L);
        assertThat(result.canAccess(20L, null)).isTrue();
        assertThat(result.canAccess(99L, 11L)).isTrue();
        assertThat(result.canAccess(99L, 100L)).isFalse();
    }

    @Test
    void missingGrantDeniesEveryResource() {
        when(mapper.findRoleScopeGrants(1L, 20L)).thenReturn(List.of());
        when(mapper.findUserOrganizationId(1L, 20L)).thenReturn(10L);

        DataPermission result = service.resolve(user);

        assertThat(result.allData()).isFalse();
        assertThat(result.selfData()).isFalse();
        assertThat(result.organizationIds()).isEmpty();
        assertThat(result.canAccess(20L, 10L)).isFalse();
    }
}
