package com.leantpm.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.session.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokenService;
    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtTokenService tokenService,
            AuthSessionService sessionService,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                var claims = tokenService.parse(authorization.substring(7), "access");
                sessionService.validateAccess(claims);
                CurrentUser user = tokenService.toCurrentUser(claims);
                var authorities = new ArrayList<SimpleGrantedAuthority>();
                user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
                user.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BusinessException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(exception.getStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(
                        response.getWriter(),
                        ApiResponse.error(exception.getCode(), exception.getMessage())
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
