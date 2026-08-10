package com.leantpm.common.idempotency;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyCleanupJob {
    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    public IdempotencyCleanupJob(
            IdempotencyStore store,
            IdempotencyProperties properties
    ) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${leantpm.idempotency.cleanup-interval-ms:300000}",
            initialDelayString = "${leantpm.idempotency.cleanup-initial-delay-ms:300000}"
    )
    public void cleanup() {
        store.cleanupExpired(properties.getCleanupBatchSize());
    }
}
