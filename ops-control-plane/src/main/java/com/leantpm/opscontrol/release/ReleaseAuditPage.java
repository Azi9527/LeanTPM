package com.leantpm.opscontrol.release;

import java.util.List;

public record ReleaseAuditPage(
    long nextCursor,
    boolean hasMore,
    List<ReleaseAuditEvent> events
) {
    public ReleaseAuditPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
