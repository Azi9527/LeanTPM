package com.leantpm.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class MaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final MaintenanceTaskService taskService;

    public MaintenanceScheduler(MaintenanceTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void generateAndMarkOverdue() {
        for (Long tenantId : taskService.tenantIds()) {
            try {
                taskService.generateScheduled(tenantId, 0);
                taskService.markOverdue(tenantId);
            } catch (RuntimeException exception) {
                log.error("Maintenance scheduling failed for tenant {}", tenantId, exception);
            }
        }
    }
}
