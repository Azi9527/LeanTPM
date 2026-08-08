package com.leantpm.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasterDataServiceOrganizationDeleteTest {
    private final MasterDataMapper mapper = mock(MasterDataMapper.class);
    private final DataPermissionService dataPermissionService = mock(DataPermissionService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MasterDataService service = new MasterDataService(
            mapper,
            dataPermissionService,
            changeLogService,
            new ObjectMapper(),
            jdbc
    );

    @BeforeEach
    void setUp() {
        CurrentUser admin = new CurrentUser(
                1L,
                1L,
                "admin",
                "系统管理员",
                false,
                Set.of("ADMIN"),
                Set.of("master-data:organization:delete"),
                "organization-delete-unit"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(1L));
        when(mapper.findOrganization(1L, 20L)).thenReturn(new MasterDataDtos.OrganizationRow(
                20L,
                10L,
                "TEAM-20",
                "测试班组",
                "TEAM",
                null,
                null,
                1,
                1,
                null,
                3
        ));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("FROM system_user ")) return 1;
                    if (sql.contains("FROM system_user_team_membership ")) return 2;
                    return 0;
                });
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportsRelationsBeforeDeleting() {
        var impact = service.organizationDeleteImpact(20L);

        assertThat(impact.users()).isEqualTo(1);
        assertThat(impact.teamMemberships()).isEqualTo(2);
        assertThat(impact.totalReferences()).isEqualTo(3);
        assertThatThrownBy(() -> service.deleteOrganization(20L, 3, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户 1 个")
                .hasMessageContaining("班组任职关系 2 条");
        verify(mapper, never()).softDeleteOrganization(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void reassignsRelationsAndDeletesAfterConfirmation() {
        when(mapper.softDeleteOrganization(1L, 20L, 3, 1L)).thenReturn(1);

        service.deleteOrganization(20L, 3, true);

        verify(jdbc, atLeastOnce()).update(anyString(), any(Object[].class));
        verify(mapper).softDeleteOrganization(1L, 20L, 3, 1L);
        verify(changeLogService).record(eq("ORGANIZATION"), eq(20L), eq("DELETE"), any(), isNull());
    }
}
