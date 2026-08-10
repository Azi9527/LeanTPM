package com.leantpm.common.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseIdempotencyStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final IdempotencyMapper mapper = mock(IdempotencyMapper.class);
    private final DatabaseIdempotencyStore store = new DatabaseIdempotencyStore(mapper, CLOCK);

    @Test
    void productionConstructorExplicitlyDefinesTheSpringInjectionBoundary() throws Exception {
        var constructor = DatabaseIdempotencyStore.class.getConstructor(IdempotencyMapper.class);

        assertThat(constructor.getAnnotation(Autowired.class)).isNotNull();
    }

    @Test
    void acquiresANewKeyWithAPersistedLease() {
        when(mapper.insertProcessing(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                NOW.plusSeconds(300),
                NOW.plusSeconds(86400)
        )).thenReturn(1);

        IdempotencyStore.AcquireResult result = store.acquire(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                300,
                24
        );

        assertThat(result.outcome()).isEqualTo(IdempotencyStore.Outcome.ACQUIRED);
        assertThat(result.fencingToken()).isEqualTo(1L);
    }

    @Test
    void replaysAMatchingCompletedResponse() {
        byte[] payload = "{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
        when(mapper.insertProcessing(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                NOW.plusSeconds(300),
                NOW.plusSeconds(86400)
        )).thenReturn(0);
        when(mapper.findForUpdate(1L, "key-hash")).thenReturn(new IdempotencyRecord(
                1L,
                "key-hash",
                "fingerprint",
                "COMPLETED",
                "previous-owner",
                7L,
                NOW.minusSeconds(1),
                200,
                "application/json",
                payload,
                NOW.minusSeconds(10),
                NOW.plusSeconds(3600)
        ));

        IdempotencyStore.AcquireResult result = store.acquire(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                300,
                24
        );

        assertThat(result.outcome()).isEqualTo(IdempotencyStore.Outcome.COMPLETED);
        assertThat(result.responseStatus()).isEqualTo(200);
        assertThat(result.responseContentType()).isEqualTo("application/json");
        assertThat(result.responsePayload()).containsExactly(payload);
    }

    @Test
    void rejectsAKeyUsedForADifferentFingerprint() {
        when(mapper.insertProcessing(
                1L,
                "key-hash",
                "new-fingerprint",
                "owner",
                NOW.plusSeconds(300),
                NOW.plusSeconds(86400)
        )).thenReturn(0);
        when(mapper.findForUpdate(1L, "key-hash")).thenReturn(new IdempotencyRecord(
                1L,
                "key-hash",
                "old-fingerprint",
                "PROCESSING",
                "previous-owner",
                3L,
                NOW.plusSeconds(30),
                null,
                null,
                null,
                null,
                NOW.plusSeconds(3600)
        ));

        IdempotencyStore.AcquireResult result = store.acquire(
                1L,
                "key-hash",
                "new-fingerprint",
                "owner",
                300,
                24
        );

        assertThat(result.outcome()).isEqualTo(IdempotencyStore.Outcome.CONFLICT);
    }

    @Test
    void movesAnExpiredProcessingLeaseToUnknownInsteadOfReExecuting() {
        when(mapper.insertProcessing(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                NOW.plusSeconds(300),
                NOW.plusSeconds(86400)
        )).thenReturn(0);
        when(mapper.findForUpdate(1L, "key-hash")).thenReturn(new IdempotencyRecord(
                1L,
                "key-hash",
                "fingerprint",
                "PROCESSING",
                "previous-owner",
                5L,
                NOW.minusMillis(1),
                null,
                null,
                null,
                null,
                NOW.plusSeconds(3600)
        ));
        when(mapper.markUnknown(1L, "key-hash", "previous-owner", 5L, NOW)).thenReturn(1);

        IdempotencyStore.AcquireResult result = store.acquire(
                1L,
                "key-hash",
                "fingerprint",
                "owner",
                300,
                24
        );

        assertThat(result.outcome()).isEqualTo(IdempotencyStore.Outcome.UNKNOWN);
        verify(mapper).markUnknown(1L, "key-hash", "previous-owner", 5L, NOW);
    }

    @Test
    void completesOnlyTheOwnedFencedLease() {
        byte[] payload = "{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
        when(mapper.complete(
                1L,
                "key-hash",
                "owner",
                9L,
                200,
                "application/json",
                payload,
                NOW,
                NOW.plusSeconds(86400)
        )).thenReturn(1);

        boolean completed = store.complete(
                1L,
                "key-hash",
                "owner",
                9L,
                200,
                "application/json",
                payload,
                24
        );

        assertThat(completed).isTrue();
    }

    @Test
    void marksAnUncertainBusinessOutcomeUnknown() {
        when(mapper.markUnknown(1L, "key-hash", "owner", 9L, NOW)).thenReturn(1);

        boolean marked = store.markUnknown(1L, "key-hash", "owner", 9L);

        assertThat(marked).isTrue();
        verify(mapper).markUnknown(1L, "key-hash", "owner", 9L, NOW);
    }

    @Test
    void cleansExpiredRowsInABoundedBatch() {
        when(mapper.deleteExpiredBatch(NOW, 500)).thenReturn(37);

        int deleted = store.cleanupExpired(500);

        assertThat(deleted).isEqualTo(37);
        verify(mapper).deleteExpiredBatch(NOW, 500);
    }

    @Test
    void neverDeletesOrReacquiresAnExpiredUnknownOutcome() {
        when(mapper.insertProcessing(
                1L, "key-hash", "fingerprint", "owner",
                NOW.plusSeconds(300), NOW.plusSeconds(86400)
        )).thenReturn(0);
        when(mapper.findForUpdate(1L, "key-hash")).thenReturn(new IdempotencyRecord(
                1L, "key-hash", "fingerprint", "UNKNOWN", "previous-owner", 5L,
                NOW.minusSeconds(3600), null, null, null, null, NOW.minusSeconds(1)
        ));

        IdempotencyStore.AcquireResult result = store.acquire(
                1L, "key-hash", "fingerprint", "owner", 300, 24
        );

        assertThat(result.outcome()).isEqualTo(IdempotencyStore.Outcome.UNKNOWN);
        verify(mapper, never()).deleteExpired(1L, "key-hash", NOW);
    }

    @Test
    void cleanupSqlOnlyDeletesCompletedOutcomes() throws Exception {
        String xml = new ClassPathResource("mapper/common/IdempotencyMapper.xml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("id=\"deleteExpiredBatch\"")
                .contains("state = 'COMPLETED'");
    }
}
