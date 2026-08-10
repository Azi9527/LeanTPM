package com.leantpm.security.session;

import com.leantpm.auth.dto.TokenPair;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.IssuedTokenPair;
import com.leantpm.security.session.domain.RefreshRotationResult;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseAuthSessionServiceTest {
    private final AuthSessionMapper mapper = mock(AuthSessionMapper.class);
    private final AuthSessionTransactions transactions = mock(AuthSessionTransactions.class);
    private DatabaseAuthSessionService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseAuthSessionService(mapper, transactions);
    }

    @Test
    void failsClosedWhenPersistentSessionStateCannotBeRead() {
        Claims claims = claims("session-1", "refresh-jti", 1L, 7L);
        when(transactions.validateAndTouch(anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.validateAccess(claims))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("AUTH_STATE_UNAVAILABLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void reportsRefreshReuseOnlyAfterTheTransactionalRotationReturns() {
        Claims claims = claims("session-1", "old-refresh-jti", 1L, 7L);
        IssuedTokenPair issued = new IssuedTokenPair(
                new TokenPair(
                        "access-token",
                        "refresh-token",
                        Instant.parse("2026-08-08T12:30:00Z"),
                        Instant.parse("2026-08-15T12:00:00Z")
                ),
                "session-1",
                "next-access-jti",
                "next-refresh-jti"
        );
        when(transactions.rotateRefresh(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any()
        )).thenReturn(RefreshRotationResult.REUSED);

        assertThatThrownBy(() -> service.rotate(claims, issued))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("REFRESH_TOKEN_REUSED");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verify(transactions).rotateRefresh(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any()
        );
    }

    private Claims claims(String sessionId, String tokenId, long tenantId, long userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("sid", String.class)).thenReturn(sessionId);
        when(claims.get("tid", Number.class)).thenReturn(tenantId);
        when(claims.get("uid", Number.class)).thenReturn(userId);
        when(claims.get("uv", Number.class)).thenReturn(3L);
        when(claims.getId()).thenReturn(tokenId);
        return claims;
    }
}
