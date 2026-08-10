package com.leantpm.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyAspectTest {
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdempotencyProperties properties = properties();
    private final IdempotencyAspect aspect = new IdempotencyAspect(store, objectMapper, properties);
    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

    @BeforeEach
    void setUpRequestAndPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/equipment");
        request.addHeader("Idempotency-Key", "request-key-0001");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        CurrentUser user = new CurrentUser(
                9L, 1L, "admin", "Administrator", false, Set.of("ADMIN"), Set.of(), "sid"
        );
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, Set.of())
        );

        MethodSignature signature = mock(MethodSignature.class);
        Method method = DummyOperations.class.getDeclaredMethod("operation");
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
    }

    @AfterEach
    void clearContexts() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void executesAndCompletesAnAcquiredRequest() throws Throwable {
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(IdempotencyStore.AcquireResult.acquired(4L));
        when(joinPoint.proceed()).thenReturn("created");
        when(store.complete(
                anyLong(), anyString(), anyString(), anyLong(), anyInt(), anyString(), any(), anyInt()
        )).thenReturn(true);

        Object result = aspect.execute(joinPoint);

        assertThat(result).isEqualTo("created");
        verify(store).complete(
                anyLong(), anyString(), anyString(), anyLong(), anyInt(), anyString(), any(), anyInt()
        );
    }

    @Test
    void replaysACompletedResponseWithoutExecutingBusinessCode() throws Throwable {
        byte[] payload = objectMapper.writeValueAsBytes("created");
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new IdempotencyStore.AcquireResult(
                        IdempotencyStore.Outcome.COMPLETED,
                        4L,
                        200,
                        "application/json",
                        payload
                ));

        Object result = aspect.execute(joinPoint);

        assertThat(result).isEqualTo("created");
        verify(joinPoint, never()).proceed();
    }

    @Test
    void failsClosedWhenTheStateDatabaseIsUnavailable() {
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> aspect.execute(joinPoint))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_UNAVAILABLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void marksTheLeaseUnknownWhenBusinessOutcomeIsUncertain() throws Throwable {
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(IdempotencyStore.AcquireResult.acquired(4L));
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("failed after side effect"));
        when(store.markUnknown(anyLong(), anyString(), anyString(), anyLong())).thenReturn(true);

        assertThatThrownBy(() -> aspect.execute(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed after side effect");
        verify(store).markUnknown(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void rejectsUnknownStateWithoutExecutingBusinessCode() {
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(IdempotencyStore.AcquireResult.outcome(IdempotencyStore.Outcome.UNKNOWN));

        assertThatThrownBy(() -> aspect.execute(joinPoint))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_RESULT_UNKNOWN");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void scopesTheSameClientKeyToTheAuthenticatedUser() throws Throwable {
        when(store.acquire(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(IdempotencyStore.AcquireResult.acquired(4L));
        when(store.complete(
                anyLong(), anyString(), anyString(), anyLong(), anyInt(), anyString(), any(), anyInt()
        )).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("created");

        aspect.execute(joinPoint);
        setPrincipal(10L);
        aspect.execute(joinPoint);

        ArgumentCaptor<String> keyHashes = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).acquire(
                eq(1L), keyHashes.capture(), anyString(), anyString(), anyInt(), anyInt()
        );
        assertThat(keyHashes.getAllValues()).doesNotHaveDuplicates();
    }

    private IdempotencyProperties properties() {
        IdempotencyProperties value = new IdempotencyProperties();
        value.setProcessingSeconds(300);
        value.setCompletedHours(24);
        value.setMaxResponseBytes(8 * 1024 * 1024);
        return value;
    }

    private void setPrincipal(long userId) {
        CurrentUser user = new CurrentUser(
                userId, 1L, "admin", "Administrator", false, Set.of("ADMIN"), Set.of(), "sid"
        );
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, Set.of())
        );
    }

    private static final class DummyOperations {
        private String operation() {
            return "created";
        }
    }
}
