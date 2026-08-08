package com.leantpm.system;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.security.session.RedisAuthSessionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemServiceRoleBoundaryTest {
    @Mock
    private SystemMapper mapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private DataPermissionService dataPermissionService;
    @Mock
    private RedisAuthSessionService sessionService;

    private SystemService service;

    @BeforeEach
    void setUp() {
        service = new SystemService(mapper, passwordEncoder, dataPermissionService, sessionService);
        var manager = new CurrentUser(
                51L, 1L, "manager", "管理人员", false,
                Set.of("WORKSHOP_MANAGER"), Set.of("system:user:view"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(manager, null, Set.of())
        );
        lenient().when(mapper.findRoles(1L)).thenReturn(List.of(
                role(1L, "ADMIN", "超级管理员", "ALL"),
                role(5L, "WORKSHOP_MANAGER", "管理人员", "ORGANIZATION_AND_CHILDREN"),
                role(6L, "TEAM_LEADER", "班组长", "ORGANIZATION"),
                role(3L, "OPERATOR", "员工", "SELF")
        ));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerSeesOnlyAssignableBusinessRoles() {
        assertThat(service.roles()).extracting(SystemDtos.RoleRow::roleCode)
                .containsExactly("WORKSHOP_MANAGER", "TEAM_LEADER", "OPERATOR");
    }

    @Test
    void managerCannotAssignOrModifyAdministrator() {
        when(dataPermissionService.current()).thenReturn(
                DataPermission.restricted(51L, false, Set.of(4L))
        );
        assertThatThrownBy(() -> service.createUser(new SystemDtos.CreateUserRequest(
                "unsafe", "越权用户", null, null, null, 4L, true,
                List.of(1L), "888888"
        ))).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ROLE_ASSIGNMENT_FORBIDDEN");

        when(mapper.countUserAdminRole(1L, 1L)).thenReturn(1);
        assertThatThrownBy(() -> service.resetPassword(
                1L, new SystemDtos.ResetPasswordRequest("888888")
        )).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ADMIN_USER_PROTECTED");
    }

    @Test
    void disablingUserRevokesExistingSessions() {
        DataPermission scope = DataPermission.restricted(51L, false, Set.of(4L));
        when(dataPermissionService.current()).thenReturn(scope);
        when(mapper.findUserScopeTarget(1L, 77L))
                .thenReturn(new SystemMapper.UserScopeTarget(77L, 4L));
        when(mapper.updateUserStatus(1L, 77L, false, 3, scope, 51L)).thenReturn(1);

        service.updateUserStatus(77L, new SystemDtos.StatusRequest(false, 3));

        verify(sessionService).revokeAllUserSessions(1L, 77L);
    }

    private SystemDtos.RoleRow role(long id, String code, String name, String scope) {
        return new SystemDtos.RoleRow(
                id, code, name, scope, 1, (int) id, null, 0, List.of(), List.of()
        );
    }
}
