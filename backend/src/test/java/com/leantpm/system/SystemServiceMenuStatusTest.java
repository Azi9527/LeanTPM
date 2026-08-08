package com.leantpm.system;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.security.session.RedisAuthSessionService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.mapper.SystemMapper;
import com.leantpm.system.service.SystemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemServiceMenuStatusTest {
    @Mock private SystemMapper mapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DataPermissionService dataPermissionService;
    @Mock private RedisAuthSessionService sessionService;

    private SystemService service;

    @BeforeEach
    void setUp() {
        service = new SystemService(mapper, passwordEncoder, dataPermissionService, sessionService);
        var administrator = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of("system:menu:manage"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(administrator, null, Set.of())
        );
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recursivelyUpdatesSelectedMenuAndAllDescendants() {
        when(mapper.findMenus(1L)).thenReturn(List.of(
                menu(10L, 0L), menu(11L, 10L), menu(12L, 11L), menu(20L, 0L)
        ));
        when(mapper.updateMenuStatuses(1L, List.of(10L, 11L, 12L), false, 1L))
                .thenReturn(3);

        assertThat(service.updateMenuStatus(
                10L, new SystemDtos.MenuStatusRequest(false)
        )).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(mapper).updateMenuStatuses(
                org.mockito.ArgumentMatchers.eq(1L), ids.capture(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(1L)
        );
        assertThat(ids.getValue()).containsExactly(10L, 11L, 12L);
    }

    @Test
    void rejectsUnknownMenuWithoutWriting() {
        when(mapper.findMenus(1L)).thenReturn(List.of(menu(10L, 0L)));
        assertThatThrownBy(() -> service.updateMenuStatus(
                99L, new SystemDtos.MenuStatusRequest(true)
        )).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("MENU_NOT_FOUND");
    }

    private SystemDtos.MenuRow menu(long id, long parentId) {
        return new SystemDtos.MenuRow(
                id, parentId, "MENU", "菜单" + id, null, null, null,
                "test:" + id, null, 1, 1, 0
        );
    }
}
