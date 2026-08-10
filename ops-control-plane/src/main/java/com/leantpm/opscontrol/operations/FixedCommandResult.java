package com.leantpm.opscontrol.operations;

public record FixedCommandResult(int exitCode, String output) {
    public FixedCommandResult {
        output = output == null ? "" : output;
    }
}
