package com.leantpm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.session.AuthSessionService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class JwtAuthenticationFilterTest {

    @Test
    void preservesRevokedTokenErrorContractInsteadOfFallingThroughToGenericUnauthorized() throws Exception {
        JwtTokenService tokenService = mock(JwtTokenService.class);
        AuthSessionService sessionService = mock(AuthSessionService.class);
        Claims claims = mock(Claims.class);
        when(tokenService.parse("revoked-token", "access")).thenReturn(claims);
        doThrow(new BusinessException("TOKEN_REVOKED", "token revoked", HttpStatus.UNAUTHORIZED))
                .when(sessionService).validateAccess(claims);

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                tokenService,
                sessionService,
                new ObjectMapper().findAndRegisterModules()
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"TOKEN_REVOKED\"");
        verify(chain, never()).doFilter(request, response);
    }
}
