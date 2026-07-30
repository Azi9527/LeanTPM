package com.leantpm.system.log;

import com.leantpm.security.CurrentUser;
import com.leantpm.system.mapper.SystemMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class OperationLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(OperationLogFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final SystemMapper mapper;

    public OperationLogFilter(SystemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING_METHODS.contains(request.getMethod())
                || request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            record(request, response, start, failure);
        }
    }

    private void record(
            HttpServletRequest request,
            HttpServletResponse response,
            long start,
            Throwable failure
    ) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return;
        }
        try {
            String error = failure == null ? null : failure.getMessage();
            if (error != null && error.length() > 500) {
                error = error.substring(0, 500);
            }
            mapper.insertOperationLog(
                    user.tenantId(),
                    user.userId(),
                    user.username(),
                    request.getMethod(),
                    request.getRequestURI(),
                    clientIp(request),
                    failure == null && response.getStatus() < 400,
                    error,
                    System.currentTimeMillis() - start
            );
        } catch (RuntimeException logFailure) {
            log.warn("记录操作日志失败：{}", logFailure.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
    }
}
