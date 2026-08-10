package com.leantpm.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.ApiResponse;
import com.leantpm.security.CurrentUser;
import com.leantpm.system.log.OperationLogFilter;
import com.leantpm.system.mapper.SystemMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionOperationLogTest {
    private SystemMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(SystemMapper.class);
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new OperationLogFilter(mapper))
                .build();

        CurrentUser admin = new CurrentUser(
                1L,
                1L,
                "admin",
                "系统管理员",
                false,
                Set.of("ADMIN"),
                Set.of(),
                "operation-log-unit"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsHandledBusinessFailureWithSafeCorrelationId() throws Exception {
        MvcResult result = mvc.perform(post("/business-failure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_CODE_EXISTS"))
                .andExpect(jsonPath("$.message")
                        .value("组织编码已存在或曾被使用，请更换编码"))
                .andReturn();

        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
        assertThat(capturedError("/business-failure"))
                .isEqualTo(
                        "ORGANIZATION_CODE_EXISTS [错误编号：" + correlationId
                                + "]：组织编码已存在或曾被使用，请更换编码"
                );
    }

    @Test
    void correlatesUnexpectedFailureWithoutLeakingTechnicalMessage() throws Exception {
        MvcResult result = mvc.perform(post("/unexpected-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();

        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .contains(correlationId)
                .doesNotContain("database-password=do-not-leak");
        assertThat(capturedError("/unexpected-failure"))
                .isEqualTo(
                        "INTERNAL_ERROR [错误编号：" + correlationId
                                + "]：系统处理失败，请稍后重试"
                )
                .doesNotContain("database-password=do-not-leak");
    }

    @Test
    void recordsSuccessfulMutationWithoutAnError() throws Exception {
        MvcResult result = mvc.perform(post("/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        verify(mapper).insertOperationLog(
                eq(1L),
                eq(1L),
                eq("admin"),
                eq("POST"),
                eq("/success"),
                anyString(),
                eq(true),
                eq(null),
                anyLong()
        );
    }

    @Test
    void doesNotCreateAnAttributedLogWithoutAnAuthenticatedUser() throws Exception {
        SecurityContextHolder.clearContext();

        mvc.perform(post("/success"))
                .andExpect(status().isOk());

        verify(mapper, never()).insertOperationLog(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                eq(true),
                eq(null),
                anyLong()
        );
    }

    private String capturedError(String path) {
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOperationLog(
                eq(1L),
                eq(1L),
                eq("admin"),
                eq("POST"),
                eq(path),
                anyString(),
                eq(false),
                error.capture(),
                anyLong()
        );
        return error.getValue();
    }

    @RestController
    static class FailureController {
        @PostMapping("/business-failure")
        ApiResponse<Void> businessFailure() {
            throw new BusinessException(
                    "ORGANIZATION_CODE_EXISTS",
                    "组织编码已存在或曾被使用，请更换编码",
                    HttpStatus.CONFLICT
            );
        }

        @PostMapping("/unexpected-failure")
        Map<String, String> unexpectedFailure() {
            throw new IllegalStateException("database-password=do-not-leak");
        }

        @PostMapping("/success")
        Map<String, String> success() {
            return Map.of("status", "OK");
        }
    }
}
