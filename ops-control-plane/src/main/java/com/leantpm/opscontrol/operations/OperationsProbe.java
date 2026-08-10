package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.List;

public interface OperationsProbe {
    String id();

    List<OperationsComponent> observe(Instant observedAt);
}
