package com.leantpm.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.idempotency")
public class IdempotencyProperties {
    private int processingSeconds = 300;
    private int completedHours = 24;

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
}
