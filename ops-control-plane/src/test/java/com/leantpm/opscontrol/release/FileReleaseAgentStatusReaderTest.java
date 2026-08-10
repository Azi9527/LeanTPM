package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReleaseAgentStatusReaderTest {

    private static final Instant NOW = Instant.parse("2026-08-09T11:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryRoot;

    @Test
    void missingHeartbeatReportsNotConnectedAndCountsOnlyTypedPendingJobs() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        Path pending = Files.createDirectories(queueRoot.resolve("pending"));
        Files.writeString(pending.resolve("a".repeat(64) + ".json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(pending.resolve("unexpected.txt"), "ignored", StandardCharsets.UTF_8);

        ReleaseAgentStatus status = reader(queueRoot).status();

        assertThat(status.state()).isEqualTo(ReleaseAgentConnectionState.NOT_CONNECTED);
        assertThat(status.pendingJobs()).isEqualTo(1);
        assertThat(status.productionExecutionEnabled()).isFalse();
        assertThat(status.lastSeenAt()).isNull();
    }

    @Test
    void freshStrictHeartbeatReportsOnlineAndPreservesDeclaredMode() throws Exception {
        Path queueRoot = Files.createDirectories(temporaryRoot.resolve("fresh"));
        Files.createDirectories(queueRoot.resolve("pending"));
        Files.writeString(
            queueRoot.resolve("agent-heartbeat.json"),
            """
            {"schemaVersion":1,"agentId":"release-agent-01","agentVersion":"1.0.1","mode":"PRODUCTION_ENABLED","lastSeenAt":"2026-08-09T10:59:50Z"}
            """,
            StandardCharsets.UTF_8
        );

        ReleaseAgentStatus status = reader(queueRoot).status();

        assertThat(status.state()).isEqualTo(ReleaseAgentConnectionState.ONLINE);
        assertThat(status.agentId()).isEqualTo("release-agent-01");
        assertThat(status.agentVersion()).isEqualTo("1.0.1");
        assertThat(status.productionExecutionEnabled()).isTrue();
    }

    @Test
    void staleHeartbeatCannotClaimProductionExecutionCapability() throws Exception {
        Path queueRoot = Files.createDirectories(temporaryRoot.resolve("stale"));
        Files.createDirectories(queueRoot.resolve("pending"));
        Files.writeString(
            queueRoot.resolve("agent-heartbeat.json"),
            """
            {"schemaVersion":1,"agentId":"release-agent-01","agentVersion":"1.0.1","mode":"PRODUCTION_ENABLED","lastSeenAt":"2026-08-09T10:58:00Z"}
            """,
            StandardCharsets.UTF_8
        );

        ReleaseAgentStatus status = reader(queueRoot).status();

        assertThat(status.state()).isEqualTo(ReleaseAgentConnectionState.STALE);
        assertThat(status.productionExecutionEnabled()).isFalse();
    }

    @Test
    void malformedOrFutureHeartbeatFailsClosed() throws Exception {
        Path queueRoot = Files.createDirectories(temporaryRoot.resolve("invalid"));
        Files.createDirectories(queueRoot.resolve("pending"));
        Path heartbeat = queueRoot.resolve("agent-heartbeat.json");
        Files.writeString(heartbeat, "{\"schemaVersion\":1}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader(queueRoot).status())
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("heartbeat");

        Files.writeString(
            heartbeat,
            """
            {"schemaVersion":1,"agentId":"release-agent-01","agentVersion":"1.0.1","mode":"VERIFY_ONLY","lastSeenAt":"2026-08-09T11:02:00Z"}
            """,
            StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> reader(queueRoot).status())
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("future");
    }

    private FileReleaseAgentStatusReader reader(Path queueRoot) {
        return new FileReleaseAgentStatusReader(
            queueRoot,
            Duration.ofSeconds(30),
            CLOCK
        );
    }
}
