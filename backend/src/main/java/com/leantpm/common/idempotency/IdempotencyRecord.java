package com.leantpm.common.idempotency;

import java.time.Instant;

public record IdempotencyRecord(
        long tenantId,
        String keyHash,
        String fingerprint,
        String state,
        String ownerToken,
        long fencingToken,
        Instant leaseExpiresAt,
        Integer responseStatus,
        String responseContentType,
        byte[] responsePayload,
        Instant completedAt,
        Instant expiresAt
) {
    public IdempotencyRecord {
        responsePayload = responsePayload == null ? null : responsePayload.clone();
    }

    @Override
    public byte[] responsePayload() {
        return responsePayload == null ? null : responsePayload.clone();
    }
}
