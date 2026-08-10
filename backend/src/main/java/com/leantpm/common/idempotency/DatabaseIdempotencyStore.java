package com.leantpm.common.idempotency;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DatabaseIdempotencyStore implements IdempotencyStore {
    private static final long INITIAL_FENCING_TOKEN = 1L;

    private final IdempotencyMapper mapper;
    private final Clock clock;

    @Autowired
    public DatabaseIdempotencyStore(IdempotencyMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    DatabaseIdempotencyStore(IdempotencyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcquireResult acquire(
            long tenantId,
            String keyHash,
            String fingerprint,
            String ownerToken,
            int processingSeconds,
            int completedHours
    ) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plus(processingSeconds, ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(completedHours, ChronoUnit.HOURS);
        if (mapper.insertProcessing(
                tenantId,
                keyHash,
                fingerprint,
                ownerToken,
                leaseExpiresAt,
                expiresAt
        ) == 1) {
            return AcquireResult.acquired(INITIAL_FENCING_TOKEN);
        }

        IdempotencyRecord existing = mapper.findForUpdate(tenantId, keyHash);
        if (existing == null) {
            throw new IllegalStateException("Idempotency record disappeared during acquisition");
        }
        if ("COMPLETED".equals(existing.state())) {
            if (!existing.expiresAt().isAfter(now)) {
                mapper.deleteExpired(tenantId, keyHash, now);
                if (mapper.insertProcessing(
                        tenantId,
                        keyHash,
                        fingerprint,
                        ownerToken,
                        leaseExpiresAt,
                        expiresAt
                ) != 1) {
                    throw new IllegalStateException("Expired completed record could not be replaced");
                }
                return AcquireResult.acquired(INITIAL_FENCING_TOKEN);
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                return AcquireResult.outcome(Outcome.CONFLICT);
            }
            return AcquireResult.completed(existing);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return AcquireResult.outcome(Outcome.CONFLICT);
        }
        if ("UNKNOWN".equals(existing.state())) {
            return AcquireResult.outcome(Outcome.UNKNOWN);
        }
        if (!"PROCESSING".equals(existing.state())) {
            return AcquireResult.outcome(Outcome.UNKNOWN);
        }
        if (existing.leaseExpiresAt().isAfter(now)) {
            return AcquireResult.outcome(Outcome.IN_PROGRESS);
        }
        mapper.markUnknown(
                tenantId,
                keyHash,
                existing.ownerToken(),
                existing.fencingToken(),
                now
        );
        return AcquireResult.outcome(Outcome.UNKNOWN);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(
            long tenantId,
            String keyHash,
            String ownerToken,
            long fencingToken,
            int responseStatus,
            String responseContentType,
            byte[] responsePayload,
            int completedHours
    ) {
        Instant now = clock.instant();
        return mapper.complete(
                tenantId,
                keyHash,
                ownerToken,
                fencingToken,
                responseStatus,
                responseContentType,
                responsePayload,
                now,
                now.plus(completedHours, ChronoUnit.HOURS)
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markUnknown(
            long tenantId,
            String keyHash,
            String ownerToken,
            long fencingToken
    ) {
        return mapper.markUnknown(
                tenantId,
                keyHash,
                ownerToken,
                fencingToken,
                clock.instant()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupExpired(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Idempotency cleanup batch size is out of range");
        }
        return mapper.deleteExpiredBatch(clock.instant(), batchSize);
    }
}
