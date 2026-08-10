package com.leantpm.opscontrol.release;

import java.time.Instant;

public record ReleaseAgentStatus(
    ReleaseAgentConnectionState state,
    String agentId,
    String agentVersion,
    Instant lastSeenAt,
    int pendingJobs,
    boolean productionExecutionEnabled
) {
}
