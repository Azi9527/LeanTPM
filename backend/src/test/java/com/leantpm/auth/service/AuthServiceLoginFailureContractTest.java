package com.leantpm.auth.service;

import com.leantpm.auth.dto.LoginRequest;
import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.JwtTokenService;
import com.leantpm.security.session.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceLoginFailureContractTest {

    @Test
    void lockedAndUnknownPrincipalsExposeTheSameAuthenticationFailure() {
        BusinessException locked = failure(LoginAttemptResult.locked());
        BusinessException unknown = failure(LoginAttemptResult.failed());

        assertThat(locked.getCode()).isEqualTo(unknown.getCode());
        assertThat(locked.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(locked.getMessage()).isEqualTo(unknown.getMessage());
    }

    private BusinessException failure(LoginAttemptResult attempt) {
        DatabaseLoginAttemptService loginAttempts = mock(DatabaseLoginAttemptService.class);
        when(loginAttempts.verify(
                anyLong(), anyString(), anyString(), anyString(), nullable(String.class)
        ))
                .thenReturn(attempt);
        AuthService service = new AuthService(
                mock(AuthMapper.class),
                mock(PasswordEncoder.class),
                mock(JwtTokenService.class),
                mock(AuthSessionService.class),
                loginAttempts
        );
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRemoteAddr()).thenReturn("203.0.113.20");

        Throwable thrown = catchThrowable(
                () -> service.login(new LoginRequest("candidate", "guess"), servletRequest)
        );
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }
}
