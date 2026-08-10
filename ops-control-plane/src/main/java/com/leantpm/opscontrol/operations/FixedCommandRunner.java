package com.leantpm.opscontrol.operations;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface FixedCommandRunner {
    FixedCommandResult run(List<String> command, Duration timeout);
}
