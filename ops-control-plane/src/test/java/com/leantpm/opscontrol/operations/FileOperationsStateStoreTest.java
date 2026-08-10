package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsStateStoreTest {

    @TempDir
    Path root;

    @Test
    void persistsRemediationLimitsAndReloadsWithoutSecrets() throws Exception {
        FileOperationsStateStore store = new FileOperationsStateStore(root);
        Instant now = Instant.parse("2026-08-10T01:00:00Z");
        OperationsRuntimeState expected = new OperationsRuntimeState(
            1,
            Map.of("service:backend", "DOWN"),
            Map.of("service:backend", 2),
            Map.of("service:backend", now),
            Map.of("service:backend", List.of(now)),
            Map.of("release-001", "DEPLOYED"),
            List.of(new RemediationEvent(
                "event-001",
                "service:backend",
                RemediationAction.START_BACKEND,
                RemediationOutcome.SUCCEEDED,
                now,
                "固定服务已启动"
            ))
        );

        store.save(expected);

        assertThat(new FileOperationsStateStore(root).load()).isEqualTo(expected);
        assertThat(Files.readString(root.resolve("operations-runtime-state.json")))
            .doesNotContain("password")
            .doesNotContain("token");
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .containsExactly("operations-runtime-state.json");
        }
    }

    @Test
    void corruptedStateFailsClosed() throws Exception {
        Files.writeString(root.resolve("operations-runtime-state.json"), "{broken");

        assertThatThrownBy(() -> new FileOperationsStateStore(root))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("operations runtime state");
    }
}
