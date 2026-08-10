package com.leantpm.integration;

import com.leantpm.auth.service.DatabaseLoginAttemptService;
import com.leantpm.auth.service.LoginAttemptDecision;
import com.leantpm.auth.service.LoginAttemptResult;
import com.leantpm.common.idempotency.IdempotencyStore;
import com.leantpm.security.session.AuthSessionTransactions;
import com.leantpm.security.session.domain.AuthSessionRecord;
import com.leantpm.security.session.domain.RefreshRotationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${LEANTPM_TEST_DB_URL}",
                "spring.datasource.username=${LEANTPM_TEST_DB_USERNAME:root}",
                "spring.datasource.password=${LEANTPM_TEST_DB_PASSWORD:}",
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password="
        }
)
class AuthSecurityMySqlIntegrationTest {
    private static final String USERNAME = "login_gate_integration";
    private static final String PASSWORD = "Correct#Password1";

    @Autowired
    private DatabaseLoginAttemptService loginAttempts;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthSessionTransactions sessionTransactions;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void prepareUserAndState() {
        jdbc.update("DELETE FROM auth_login_security_state WHERE tenant_id = 1");
        jdbc.update("DELETE FROM auth_session WHERE tenant_id = 1");
        jdbc.update("DELETE FROM request_idempotency WHERE tenant_id = 1");
        jdbc.update("DELETE FROM system_login_log WHERE tenant_id = 1 AND username LIKE 'login_gate_%'");
        jdbc.update("DELETE FROM system_user WHERE tenant_id = 1 AND username = ?", USERNAME);
        jdbc.update("""
                INSERT INTO system_user
                    (tenant_id, username, password_hash, real_name, status,
                     mobile_enabled, must_change_password, created_by, updated_by)
                VALUES (1, ?, ?, 'Login gate integration', 1, 1, 0, 0, 0)
                """, USERNAME, passwordEncoder.encode(PASSWORD));
    }

    @Test
    void fiftyConcurrentBadPasswordsCannotCrossTheFiveAttemptAdmissionGate() throws Exception {
        int attempts = 50;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoginAttemptResult>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return loginAttempts.verify(
                            1L, USERNAME, "wrong-password", "203.0.113.40", "mysql-test"
                    );
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LoginAttemptDecision> decisions = new ArrayList<>();
            for (Future<LoginAttemptResult> future : futures) {
                decisions.add(future.get(90, TimeUnit.SECONDS).decision());
            }

            assertThat(decisions).filteredOn(LoginAttemptDecision.FAILED::equals).hasSize(4);
            assertThat(decisions).filteredOn(LoginAttemptDecision.LOCKED::equals).hasSize(46);
        }
        Integer failureCount = jdbc.queryForObject("""
                SELECT failure_count
                FROM auth_login_security_state s
                JOIN system_user u ON u.tenant_id = s.tenant_id AND u.id = s.user_id
                WHERE u.tenant_id = 1 AND u.username = ?
                """, Integer.class, USERNAME);
        assertThat(failureCount).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_login_log
                WHERE tenant_id = 1 AND username = ? AND success = 0
                """, Integer.class, USERNAME)).isEqualTo(5);
    }

    @Test
    void randomUnknownUsernamesFromOneAddressDoNotCreateUnboundedPrincipalRows() {
        LoginAttemptResult first = loginAttempts.verify(
                1L, "login_gate_unknown_one", "wrong", "203.0.113.41", "mysql-test"
        );
        LoginAttemptResult second = loginAttempts.verify(
                1L, "login_gate_unknown_two", "wrong", "203.0.113.41", "mysql-test"
        );

        assertThat(first.decision()).isEqualTo(LoginAttemptDecision.FAILED);
        assertThat(second.decision()).isEqualTo(LoginAttemptDecision.FAILED);
        Integer rowCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_login_security_state
                WHERE tenant_id = 1 AND principal_key LIKE 'I:%'
                """, Integer.class);
        Integer failureCount = jdbc.queryForObject("""
                SELECT MAX(failure_count) FROM auth_login_security_state
                WHERE tenant_id = 1 AND principal_key LIKE 'I:%'
                """, Integer.class);
        assertThat(rowCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(2);
    }

    @Test
    void concurrentRefreshReplayRotatesOnceThenRevokesTheSession() throws Exception {
        long userId = jdbc.queryForObject(
                "SELECT id FROM system_user WHERE tenant_id = 1 AND username = ?",
                Long.class,
                USERNAME
        );
        long authEpoch = jdbc.queryForObject(
                "SELECT auth_epoch FROM system_user WHERE tenant_id = 1 AND id = ?",
                Long.class,
                userId
        );
        LocalDateTime now = LocalDateTime.now();
        String previousHash = "a".repeat(64);
        var session = new AuthSessionRecord(
                "concurrent-refresh-session", 1L, userId, authEpoch, USERNAME,
                "Login gate integration", "203.0.113.42", "mysql-test",
                now, now, now.plusDays(7), previousHash, "ACTIVE", null, null, 0L
        );
        sessionTransactions.register(session);

        int attempts = 50;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefreshRotationResult>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                String nextHash = String.format("%064x", index + 1);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sessionTransactions.rotateRefresh(
                            session.sessionId(), 1L, userId, authEpoch, previousHash, nextHash,
                            now.plusDays(7), now.plusSeconds(1)
                    );
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<RefreshRotationResult> results = new ArrayList<>();
            for (Future<RefreshRotationResult> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(RefreshRotationResult.ROTATED::equals).hasSize(1);
            assertThat(results).filteredOn(RefreshRotationResult.REUSED::equals).hasSize(1);
            assertThat(results).filteredOn(RefreshRotationResult.REVOKED::equals).hasSize(48);
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM auth_session WHERE session_id = ?",
                String.class,
                session.sessionId()
        )).isEqualTo("REVOKED");
    }

    @Test
    void concurrentIdempotencyAcquisitionHasOneOwnerAndNoDuplicateExecutionLease() throws Exception {
        int attempts = 50;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotencyStore.AcquireResult>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return idempotencyStore.acquire(
                            1L, "b".repeat(64), "c".repeat(64),
                            UUID.randomUUID().toString(), 60, 24
                    );
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<IdempotencyStore.Outcome> outcomes = new ArrayList<>();
            for (Future<IdempotencyStore.AcquireResult> future : futures) {
                outcomes.add(future.get(90, TimeUnit.SECONDS).outcome());
            }
            assertThat(outcomes).filteredOn(IdempotencyStore.Outcome.ACQUIRED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(IdempotencyStore.Outcome.IN_PROGRESS::equals).hasSize(49);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM request_idempotency
                WHERE tenant_id = 1 AND key_hash = ?
                """, Integer.class, "b".repeat(64))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT fencing_token FROM request_idempotency
                WHERE tenant_id = 1 AND key_hash = ?
                """, Long.class, "b".repeat(64))).isEqualTo(1L);
    }
}
