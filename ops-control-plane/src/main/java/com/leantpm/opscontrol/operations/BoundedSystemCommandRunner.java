package com.leantpm.opscontrol.operations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class BoundedSystemCommandRunner implements FixedCommandRunner {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    @Override
    public FixedCommandResult run(List<String> command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("command and positive timeout are required");
        }
        try {
            Process process = new ProcessBuilder(List.copyOf(command))
                .redirectErrorStream(true)
                .start();
            byte[] output;
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new IllegalStateException("fixed system command timed out");
                }
                output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            } finally {
                process.getInputStream().close();
            }
            if (output.length > MAX_OUTPUT_BYTES) {
                throw new IllegalStateException("fixed system command output exceeded the limit");
            }
            return new FixedCommandResult(
                process.exitValue(),
                new String(output, StandardCharsets.UTF_8)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("fixed system command could not be executed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixed system command was interrupted", exception);
        }
    }
}
