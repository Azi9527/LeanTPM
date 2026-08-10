package com.leantpm.system;

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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemServiceSectionManagerTest {
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
                Set.of("ADMIN"), Set.of("system:user:update"), "section-manager-test"
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
    void sectionUsesOneUnifiedManagerWithSectionLeaderSemantics() {
        var section = new SystemDtos.PersonnelOrganizationRow(
                30L, 20L, "SECTION-FX", "浮选工段", "SECTION",
                null, null, 1, 3, List.of(), List.of(), ""
        );
        when(mapper.findPersonnelOrganization(1L, 30L)).thenReturn(section);
        when(mapper.countActiveUsers(1L, List.of(7L))).thenReturn(1);
        when(mapper.updateOrganizationManager(1L, 30L, 7L, 3, 1L)).thenReturn(1);

        service.updateOrganizationManager(
                30L, new SystemDtos.UpdateOrganizationManagerRequest(List.of(7L), 3)
        );

        verify(mapper).updateOrganizationManager(1L, 30L, 7L, 3, 1L);
        verify(mapper).deleteOrganizationManagers(1L, 30L, 1L);
        verify(mapper).insertOrganizationManager(
                1L, 30L, 7L, "LINE_LEADER", 0, 1L
        );
    }
}
