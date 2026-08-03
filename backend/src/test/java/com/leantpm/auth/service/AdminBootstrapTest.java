package com.leantpm.auth.service;

import com.leantpm.auth.domain.UserAccount;
import com.leantpm.auth.mapper.AuthMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {
    private final AuthMapper authMapper = mock(AuthMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    void shouldCreateAdminEvenWhenDemoUsersAlreadyExist() {
        var admin = new UserAccount();
        admin.setId(10L);
        when(authMapper.findByUsername(AuthService.DEFAULT_TENANT_ID, "admin"))
                .thenReturn(null, admin);
        when(passwordEncoder.encode("admin123!@#")).thenReturn("encoded");
        var bootstrap = new AdminBootstrap(authMapper, passwordEncoder, "admin123!@#");

        bootstrap.run(new DefaultApplicationArguments());

        verify(authMapper).insertBootstrapAdmin(
                AuthService.DEFAULT_TENANT_ID,
                "admin",
                "系统管理员",
                "encoded"
        );
        verify(authMapper).assignRole(AuthService.DEFAULT_TENANT_ID, 10L, 1L);
    }

    @Test
    void shouldLeaveExistingAdminUntouched() {
        var admin = new UserAccount();
        admin.setId(1L);
        when(authMapper.findByUsername(AuthService.DEFAULT_TENANT_ID, "admin"))
                .thenReturn(admin);
        var bootstrap = new AdminBootstrap(authMapper, passwordEncoder, "admin123!@#");

        bootstrap.run(new DefaultApplicationArguments());

        verify(authMapper, never()).insertBootstrapAdmin(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}
