package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixedLogProbeTest {

    @TempDir
    Path root;

    @Test
    void reportsCountsWithoutReturningRawLogText() throws Exception {
        Files.writeString(root.resolve("backend.log"), "INFO ready\nERROR db password=hidden\n");
        FixedLogProbe probe = new FixedLogProbe(
            root,
            List.of(Path.of("backend.log")),
            64 * 1024
        );

        OperationsComponent result = probe.observe(Instant.parse("2026-08-10T02:00:00Z"))
            .getFirst();

        assertThat(result.status()).isEqualTo(OperationsHealth.DEGRADED);
        assertThat(result.metrics()).containsEntry("errorMatches", "1");
        assertThat(result.summary()).doesNotContain("password").doesNotContain("hidden");
    }

    @Test
    void rejectsTraversalOutsideFixedRoot() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FixedLogProbe(
            root,
            List.of(Path.of("..", "outside.log")),
            64 * 1024
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
