package com.leantpm.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.idempotency")
public class IdempotencyProperties {
    private int processingSeconds = 300;
    private int completedHours = 24;
    private int maxResponseBytes = 8 * 1024 * 1024;
    private int cleanupBatchSize = 500;

    public int getProcessingSeconds() {
        return processingSeconds;
    }

    public void setProcessingSeconds(int processingSeconds) {
        this.processingSeconds = processingSeconds;
    }

    public int getCompletedHours() {
        return completedHours;
    }

    public void setCompletedHours(int completedHours) {
        this.completedHours = completedHours;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
