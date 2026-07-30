package com.leantpm.system.audit;

import java.time.LocalDateTime;
public final class ChangeLogDtos {
    private ChangeLogDtos() {
    }

    public record ChangeLogRow(
            long id,
            String resourceType,
            String resourceId,
            String operationType,
            String beforeData,
            String afterData,
            String changedFields,
            long operatorId,
            String operatorName,
            String requestId,
            LocalDateTime changeTime
    ) {
    }
}
