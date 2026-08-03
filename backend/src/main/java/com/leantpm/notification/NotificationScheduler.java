package com.leantpm.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService service;

    public NotificationScheduler(NotificationService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${leantpm.notification.initial-delay-ms:90000}",
            fixedDelayString = "${leantpm.notification.scan-interval-ms:60000}"
    )
    public void scan() {
        for (Long tenantId : service.tenantIds()) {
            try {
                service.scanTenant(tenantId);
            } catch (RuntimeException exception) {
                log.error("Notification scan failed for tenant {}", tenantId, exception);
            }
        }
    }
}
