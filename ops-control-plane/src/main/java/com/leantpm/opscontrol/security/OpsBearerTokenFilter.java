package com.leantpm.opscontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OpsBearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final OperatorTokenAuthenticator authenticator;

    OpsBearerTokenFilter(OperatorTokenAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(PREFIX)) {
            String token = authorization.substring(PREFIX.length());
            authenticator.authenticate(token).ifPresent(actor -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                    actor,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_RELEASE_OPERATOR"))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
