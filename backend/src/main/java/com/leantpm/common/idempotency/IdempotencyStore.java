package com.leantpm.common.idempotency;

public interface IdempotencyStore {
    AcquireResult acquire(
            long tenantId,
            String keyHash,
            String fingerprint,
            String ownerToken,
            int processingSeconds,
            int completedHours
    );

    boolean complete(
            long tenantId,
            String keyHash,
            String ownerToken,
            long fencingToken,
            int responseStatus,
            String responseContentType,
            byte[] responsePayload,
            int completedHours
    );

    boolean markUnknown(
            long tenantId,
            String keyHash,
            String ownerToken,
            long fencingToken
    );

    int cleanupExpired(int batchSize);

    enum Outcome {
        ACQUIRED,
        COMPLETED,
        CONFLICT,
        IN_PROGRESS,
        UNKNOWN
    }

    record AcquireResult(
            Outcome outcome,
            long fencingToken,
            Integer responseStatus,
            String responseContentType,
            byte[] responsePayload
    ) {
        public AcquireResult {
            responsePayload = responsePayload == null ? null : responsePayload.clone();
        }

        @Override
        public byte[] responsePayload() {
            return responsePayload == null ? null : responsePayload.clone();
        }

        public static AcquireResult acquired(long fencingToken) {
            return new AcquireResult(Outcome.ACQUIRED, fencingToken, null, null, null);
        }

        public static AcquireResult completed(IdempotencyRecord record) {
            return new AcquireResult(
                    Outcome.COMPLETED,
                    record.fencingToken(),
                    record.responseStatus(),
                    record.responseContentType(),
                    record.responsePayload()
            );
        }

        public static AcquireResult outcome(Outcome outcome) {
            return new AcquireResult(outcome, 0L, null, null, null);
        }
    }
}
