package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsScServiceOperationsTest {

    @Test
    void mapsEnumsToExactServiceCommandsWithoutAcceptingArbitraryNames() {
        RecordingRunner runner = new RecordingRunner();
        runner.results.add(new FixedCommandResult(0, "STATE : 4 RUNNING"));
        runner.results.add(new FixedCommandResult(0, "START_PENDING"));
        runner.results.add(new FixedCommandResult(0, "STATE : 4 RUNNING"));
        Path sc = Path.of("C:\\Windows\\System32\\sc.exe");
        WindowsScServiceOperations operations = new WindowsScServiceOperations(
            sc, Duration.ofSeconds(3), runner
        );

        ServiceObservation observation = operations.query(ServiceTarget.BACKEND);
        RemediationResult started = operations.start(ServiceTarget.CADDY);

        assertThat(observation.status()).isEqualTo(OperationsHealth.HEALTHY);
        assertThat(started.succeeded()).isTrue();
        assertThat(runner.commands).containsExactly(
            List.of(sc.toString(), "query", "LeanTPM.Backend"),
            List.of(sc.toString(), "start", "caddy"),
            List.of(sc.toString(), "query", "caddy")
        );
    }

    private static final class RecordingRunner implements FixedCommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final Deque<FixedCommandResult> results = new ArrayDeque<>();

        @Override
        public FixedCommandResult run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            return results.removeFirst();
        }
    }
}
