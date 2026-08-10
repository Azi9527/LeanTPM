package com.leantpm.security.session;

import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.security.session.domain.AuthSessionRecord;
import com.leantpm.security.session.domain.RefreshRotationResult;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionTransactionsTest {
    private final AuthSessionMapper mapper = mock(AuthSessionMapper.class);
    private final AuthMapper authMapper = mock(AuthMapper.class);
    private final AuthSessionTransactions transactions = new AuthSessionTransactions(mapper, authMapper);

    @Test
    void locksAndRechecksUserSecurityVersionBeforeRegisteringSession() {
        AuthSessionRecord session = session(9L);
        when(mapper.findActiveUserVersionForUpdate(1L, 7L)).thenReturn(9L);

        transactions.register(session);

        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).findActiveUserVersionForUpdate(1L, 7L);
        order.verify(mapper).insertSession(session);
    }

    @Test
    void refusesSessionWhenUserSecurityVersionChangedAfterCredentialCheck() {
        AuthSessionRecord session = session(9L);
        when(mapper.findActiveUserVersionForUpdate(1L, 7L)).thenReturn(10L);

        assertThatThrownBy(() -> transactions.register(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security version changed");

        verify(mapper, never()).insertSession(session);
    }

    @Test
    void revokesSessionInsideRotationTransactionWhenOldRefreshJtiIsReused() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        AuthSessionRecord session = new AuthSessionRecord(
                "session-1", 1L, 7L, "operator", "Operator",
                "127.0.0.1", "test-agent", now.minusHours(1), now.minusMinutes(1),
                now.plusDays(7), "current-hash", "ACTIVE", null, null, 3L
        );
        when(mapper.findSessionForUpdate("session-1")).thenReturn(session);
        when(mapper.revokeSession(
                "session-1", "REFRESH_TOKEN_REUSED", now, 3L
        )).thenReturn(1);

        RefreshRotationResult result = transactions.rotateRefresh(
                "session-1", 1L, 7L, 0L, "reused-hash", "next-hash", now.plusDays(7), now
        );

        assertThat(result).isEqualTo(RefreshRotationResult.REUSED);
        verify(mapper).revokeSession(
                "session-1", "REFRESH_TOKEN_REUSED", now, 3L
        );
    }

    @Test
    void failsClosedWhenARefreshReuseCannotBePersistentlyRevoked() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        AuthSessionRecord session = new AuthSessionRecord(
                "session-1", 1L, 7L, "operator", "Operator",
                "127.0.0.1", "test-agent", now.minusHours(1), now.minusMinutes(1),
                now.plusDays(7), "current-hash", "ACTIVE", null, null, 3L
        );
        when(mapper.findSessionForUpdate("session-1")).thenReturn(session);
        when(mapper.revokeSession(
                "session-1", "REFRESH_TOKEN_REUSED", now, 3L
        )).thenReturn(0);

        assertThatThrownBy(() -> transactions.rotateRefresh(
                "session-1", 1L, 7L, 0L, "reused-hash", "next-hash", now.plusDays(7), now
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be revoked");
    }

    @Test
    void rejectsRefreshWhenTheJwtSecurityVersionDoesNotMatchTheSession() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        AuthSessionRecord session = new AuthSessionRecord(
                "session-1", 1L, 7L, 9L, "operator", "Operator",
                "127.0.0.1", "test-agent", now.minusHours(1), now.minusMinutes(1),
                now.plusDays(7), "current-hash", "ACTIVE", null, null, 3L
        );
        when(mapper.findSessionForUpdate("session-1")).thenReturn(session);

        RefreshRotationResult result = transactions.rotateRefresh(
                "session-1", 1L, 7L, 8L, "current-hash", "next-hash",
                now.plusDays(7), now
        );

        assertThat(result).isEqualTo(RefreshRotationResult.MISMATCH);
    }

    private AuthSessionRecord session(long userVersion) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        return new AuthSessionRecord(
                "session-register", 1L, 7L, userVersion, "operator", "Operator",
                "127.0.0.1", "test-agent", now, now, now.plusDays(7),
                "refresh-hash", "ACTIVE", null, null, 0L
        );
    }
}
