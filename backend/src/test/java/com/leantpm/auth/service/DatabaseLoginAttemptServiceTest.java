package com.leantpm.auth.service;

import com.leantpm.auth.domain.UserAccount;
import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.security.JwtProperties;
import com.leantpm.security.session.domain.LoginSecurityState;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseLoginAttemptServiceTest {
    private final AuthMapper authMapper = mock(AuthMapper.class);
    private final AuthSessionMapper sessionMapper = mock(AuthSessionMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtProperties properties = new JwtProperties();
    private DatabaseLoginAttemptService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-password-hash");
        properties.setJwtSecret("test-only-login-gate-hmac-secret");
        properties.setMaxLoginFailures(5);
        properties.setFailureWindowMinutes(10);
        service = new DatabaseLoginAttemptService(
                authMapper, sessionMapper, passwordEncoder, properties
        );
    }

    @Test
    void credentialVerificationRunsInsideAnIndependentDatabaseTransaction() throws Exception {
        Method method = DatabaseLoginAttemptService.class.getMethod(
                "verify", long.class, String.class, String.class, String.class, String.class
        );

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void locksPersistentGateBeforeCheckingPassword() {
        UserAccount account = activeAccount(7L, "operator", "password-hash");
        when(authMapper.findByUsername(1L, "operator")).thenReturn(account);
        when(sessionMapper.findLoginSecurityStateForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(
                        invocation.getArgument(1),
                        "U:7".equals(invocation.getArgument(1)) ? 7L : null
                ));
        when(passwordEncoder.matches("correct", "password-hash")).thenReturn(true);

        LoginAttemptResult result = service.verify(
                1L, "operator", "correct", "203.0.113.7", "test-agent"
        );

        assertThat(result.decision()).isEqualTo(LoginAttemptDecision.AUTHENTICATED);
        assertThat(result.user()).isSameAs(account);
        InOrder order = inOrder(authMapper, sessionMapper, passwordEncoder);
        order.verify(authMapper).findByUsername(1L, "operator");
        order.verify(sessionMapper).ensureLoginSecurityState(
                anyLong(), org.mockito.ArgumentMatchers.startsWith("I:"), isNull(), any()
        );
        order.verify(sessionMapper).findLoginSecurityStateForUpdate(
                anyLong(), org.mockito.ArgumentMatchers.startsWith("I:")
        );
        order.verify(sessionMapper).ensureLoginSecurityState(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("U:7"),
                org.mockito.ArgumentMatchers.eq(7L),
                any()
        );
        order.verify(sessionMapper).findLoginSecurityStateForUpdate(1L, "U:7");
        order.verify(passwordEncoder).matches("correct", "password-hash");
        order.verify(sessionMapper).deleteLoginSecurityState(1L, "U:7");
    }

    @Test
    void lockedPrincipalStillPerformsConstantCostPasswordVerification() {
        UserAccount account = activeAccount(7L, "operator", "password-hash");
        when(authMapper.findByUsername(1L, "operator")).thenReturn(account);
        when(sessionMapper.findLoginSecurityStateForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> locked(invocation.getArgument(1), null));

        LoginAttemptResult result = service.verify(
                1L, "operator", "guess", "203.0.113.7", "test-agent"
        );

        assertThat(result.decision()).isEqualTo(LoginAttemptDecision.LOCKED);
        verify(passwordEncoder).matches("guess", "dummy-password-hash");
    }

    @Test
    void unknownUsernamesFromOneAddressShareOneBoundedSecurityBucket() {
        when(authMapper.findByUsername(anyLong(), anyString())).thenReturn(null);
        when(sessionMapper.findLoginSecurityStateForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), null));
        when(sessionMapper.findLoginSecurityState(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), null));

        LoginAttemptResult first = service.verify(
                1L, "random-one", "guess", "203.0.113.9", "test-agent"
        );
        LoginAttemptResult second = service.verify(
                1L, "random-two", "guess", "203.0.113.9", "test-agent"
        );

        assertThat(first.decision()).isEqualTo(LoginAttemptDecision.FAILED);
        assertThat(second.decision()).isEqualTo(LoginAttemptDecision.FAILED);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper, org.mockito.Mockito.times(2)).ensureLoginSecurityState(
                anyLong(), key.capture(), isNull(), any()
        );
        assertThat(key.getAllValues()).hasSize(2).allMatch(value -> value.startsWith("I:"));
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
    }

    @Test
    void knownUserConsumesAddressBucketBeforeUserBucket() {
        UserAccount account = activeAccount(7L, "operator", "password-hash");
        when(authMapper.findByUsername(1L, "operator")).thenReturn(account);
        when(sessionMapper.findLoginSecurityStateForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), 7L));
        when(sessionMapper.findLoginSecurityState(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), 7L));
        when(passwordEncoder.matches("guess", "password-hash")).thenReturn(false);

        service.verify(1L, "operator", "guess", "203.0.113.7", "test-agent");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper, org.mockito.Mockito.times(2)).ensureLoginSecurityState(
                anyLong(), key.capture(), any(), any()
        );
        assertThat(key.getAllValues().get(0)).startsWith("I:");
        assertThat(key.getAllValues().get(1)).isEqualTo("U:7");
        verify(sessionMapper, org.mockito.Mockito.times(2)).upsertLoginFailure(
                anyLong(), anyString(), any(), any(), any(), any(), anyInt()
        );
        InOrder failureOrder = inOrder(sessionMapper);
        failureOrder.verify(sessionMapper).upsertLoginFailure(
                anyLong(), org.mockito.ArgumentMatchers.startsWith("I:"),
                org.mockito.ArgumentMatchers.isNull(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(50)
        );
        failureOrder.verify(sessionMapper).upsertLoginFailure(
                anyLong(), org.mockito.ArgumentMatchers.eq("U:7"),
                org.mockito.ArgumentMatchers.eq(7L), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(5)
        );
    }

    @Test
    void unknownAndDisabledUsersStillPerformConstantCostPasswordVerification() {
        UserAccount disabled = activeAccount(8L, "disabled", "disabled-hash");
        disabled.setStatus(0);
        when(authMapper.findByUsername(1L, "disabled")).thenReturn(disabled);
        when(authMapper.findByUsername(1L, "missing")).thenReturn(null);
        when(sessionMapper.findLoginSecurityStateForUpdate(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), null));
        when(sessionMapper.findLoginSecurityState(anyLong(), anyString()))
                .thenAnswer(invocation -> unlocked(invocation.getArgument(1), null));

        service.verify(1L, "disabled", "guess", "203.0.113.8", "test-agent");
        service.verify(1L, "missing", "guess", "203.0.113.8", "test-agent");

        verify(passwordEncoder, org.mockito.Mockito.times(2))
                .matches(org.mockito.ArgumentMatchers.eq("guess"), anyString());
    }

    private UserAccount activeAccount(long id, String username, String passwordHash) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setTenantId(1L);
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setStatus(1);
        return account;
    }

    private LoginSecurityState unlocked(String key, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return new LoginSecurityState(1L, key, userId, 0, now, null, now, 0L);
    }

    private LoginSecurityState locked(String key, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return new LoginSecurityState(
                1L, key, userId, 5, now.minusMinutes(1), now.plusMinutes(5), now, 5L
        );
    }
}
