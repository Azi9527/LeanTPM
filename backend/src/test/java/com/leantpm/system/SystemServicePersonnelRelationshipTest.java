package com.leantpm.system;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.security.session.AuthSessionService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.mapper.SystemMapper;
import com.leantpm.system.service.SystemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemServicePersonnelRelationshipTest {
    @Mock
    private SystemMapper mapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private DataPermissionService dataPermissionService;
    @Mock
    private AuthSessionService sessionService;

    private SystemService service;

    @BeforeEach
    void setUp() {
        service = new SystemService(mapper, passwordEncoder, dataPermissionService, sessionService);
        var administrator = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of("system:user:update"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(administrator, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(1L));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizationManagerMustBeEnabledWithoutRoleCoupling() {
        when(mapper.findPersonnelOrganization(1L, 20L)).thenReturn(team(20L));
        when(mapper.countActiveUsers(1L, List.of(7L))).thenReturn(0);

        assertThatThrownBy(() -> service.updateOrganizationManager(
                20L, new SystemDtos.UpdateOrganizationManagerRequest(List.of(7L), 0)
        )).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ORGANIZATION_MANAGER_USER_INVALID");
    }

    @Test
    void organizationRejectsMultipleManagers() {
        when(mapper.findPersonnelOrganization(1L, 20L)).thenReturn(team(20L));

        assertThatThrownBy(() -> service.updateOrganizationManager(
                20L, new SystemDtos.UpdateOrganizationManagerRequest(List.of(7L, 8L), 0)
        )).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ORGANIZATION_MANAGER_MULTIPLE_NOT_ALLOWED");
    }

    @Test
    void lineSupportsOneUnifiedManager() {
        when(mapper.findPersonnelOrganization(1L, 21L)).thenReturn(line(21L));
        when(mapper.countActiveUsers(1L, List.of(7L))).thenReturn(1);
        when(mapper.updateOrganizationManager(1L, 21L, 7L, 0, 1L)).thenReturn(1);

        service.updateOrganizationManager(
                21L, new SystemDtos.UpdateOrganizationManagerRequest(List.of(7L), 0)
        );

        verify(mapper).updateOrganizationManager(1L, 21L, 7L, 0, 1L);
        verify(mapper).deleteOrganizationManagers(1L, 21L, 1L);
        verify(mapper).insertOrganizationManager(1L, 21L, 7L, "LINE_LEADER", 0, 1L);
    }

    @Test
    void teamMembersSupportMultipleTeamsAndKeepOnePrimaryMembership() {
        when(mapper.findPersonnelOrganization(1L, 20L)).thenReturn(team(20L));
        when(mapper.countActiveUsersWithRole(1L, List.of(31L, 32L), "OPERATOR"))
                .thenReturn(2);
        when(mapper.findUserPrimaryTeamId(1L, 31L)).thenReturn(null);
        when(mapper.findUserPrimaryTeamId(1L, 32L)).thenReturn(99L);

        service.updateOrganizationMembers(
                20L, new SystemDtos.UpdateOrganizationMembersRequest(List.of(31L, 32L, 31L))
        );

        verify(mapper).deleteTeamMembers(1L, 20L, 1L);
        verify(mapper).insertTeamMember(1L, 20L, 31L, true, 1L);
        verify(mapper).insertTeamMember(1L, 20L, 32L, false, 1L);
    }

    @Test
    void teamManagerAndMembersAreSavedAsOneRelationshipUpdate() {
        when(mapper.findPersonnelOrganization(1L, 20L)).thenReturn(team(20L));
        when(mapper.countActiveUsers(1L, List.of(7L))).thenReturn(1);
        when(mapper.countActiveUsersWithRole(1L, List.of(31L, 32L), "OPERATOR"))
                .thenReturn(2);
        when(mapper.updateOrganizationManager(1L, 20L, 7L, 4, 1L)).thenReturn(1);
        when(mapper.findUserPrimaryTeamId(1L, 31L)).thenReturn(null);
        when(mapper.findUserPrimaryTeamId(1L, 32L)).thenReturn(99L);

        service.updateTeamRelationships(
                20L,
                new SystemDtos.UpdateTeamRelationshipsRequest(
                        List.of(7L), List.of(31L, 32L), 4
                )
        );

        verify(mapper).deleteOrganizationManagers(1L, 20L, 1L);
        verify(mapper).insertOrganizationManager(1L, 20L, 7L, "TEAM_LEADER", 0, 1L);
        verify(mapper).deleteTeamMembers(1L, 20L, 1L);
        verify(mapper).insertTeamMember(1L, 20L, 31L, true, 1L);
        verify(mapper).insertTeamMember(1L, 20L, 32L, false, 1L);
    }

    private SystemDtos.PersonnelOrganizationRow team(long id) {
        return new SystemDtos.PersonnelOrganizationRow(
                id, 10L, "TEAM-A-1", "装配一线一班", "TEAM",
                null, null, 1, 0, List.of(), List.of(), ""
        );
    }

    private SystemDtos.PersonnelOrganizationRow line(long id) {
        return new SystemDtos.PersonnelOrganizationRow(
                id, 10L, "LINE-A", "装配产线", "LINE",
                null, null, 1, 0, List.of(), List.of(), ""
        );
    }
}
