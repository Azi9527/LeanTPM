package com.leantpm.opscontrol.release;

import java.time.Instant;

public record ReleaseApproval(String actor, String reason, Instant confirmedAt) {
}
