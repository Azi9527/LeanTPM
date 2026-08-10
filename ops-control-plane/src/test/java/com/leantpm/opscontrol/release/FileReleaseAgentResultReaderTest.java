package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReleaseAgentResultReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T08:00:10Z"), ZoneOffset.UTC
    );

    @TempDir
    Path temporaryRoot;

    @Test
    void returnsEmptyWhenTheExactCommandHasNoDurableResult() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        Files.createDirectories(queueRoot.resolve("results"));

        assertThat(reader(queueRoot).find("a".repeat(64))).isEmpty();
    }

    @Test
    void readsOnlyAHashBoundVerifyOnlyResult() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        Path resultRoot = Files.createDirectories(queueRoot.resolve("results"));
        String commandId = "a".repeat(64);
        Files.writeString(
            resultRoot.resolve(commandId + ".json"),
            resultJson(commandId, Map.of()),
            StandardCharsets.UTF_8
        );

        ReleaseAgentVerificationResult result = reader(queueRoot).find(commandId).orElseThrow();

        assertThat(result.commandId()).isEqualTo(commandId);
        assertThat(result.status()).isEqualTo("VERIFIED_ONLY");
        assertThat(result.productionExecutionEnabled()).isFalse();
        assertThat(result.verifiedAt()).isEqualTo(Instant.parse("2026-08-09T08:00:00Z"));
    }

    @Test
    void readsOnlyAHashBoundSuccessfulProductionDeploymentResult() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        Path resultRoot = Files.createDirectories(queueRoot.resolve("results"));
        String commandId = "9".repeat(64);
        Files.writeString(
            resultRoot.resolve(commandId + ".json"),
            deploymentResultJson(commandId, Map.of()),
            StandardCharsets.UTF_8
        );

        ReleaseAgentVerificationResult result = reader(queueRoot).find(commandId).orElseThrow();

        assertThat(result.status()).isEqualTo("DEPLOYED");
        assertThat(result.productionExecutionEnabled()).isTrue();
        assertThat(result.approvalId()).isEqualTo("approval-001");
        assertThat(result.deploymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.deploymentReportSha256()).isEqualTo("8".repeat(64));
    }

    @Test
    void rejectsUnknownFieldsTamperedHashesAndFutureResults() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        Path resultRoot = Files.createDirectories(queueRoot.resolve("results"));
        String commandId = "a".repeat(64);
        Path resultPath = resultRoot.resolve(commandId + ".json");

        Files.writeString(
            resultPath,
            resultJson(commandId, Map.of("shell", "whoami")),
            StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> reader(queueRoot).find(commandId))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("invalid JSON");

        Map<String, Object> tampered = resultCore(commandId);
        tampered.put("resultSha256", "0".repeat(64));
        Files.writeString(resultPath, MAPPER.writeValueAsString(tampered), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader(queueRoot).find(commandId))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");

        Files.writeString(
            resultPath,
            resultJson(commandId, Map.of("verifiedAt", "2026-08-09T08:00:16.0000000+00:00")),
            StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> reader(queueRoot).find(commandId))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("future");
    }

    private FileReleaseAgentResultReader reader(Path queueRoot) {
        return new FileReleaseAgentResultReader(queueRoot, CLOCK);
    }

    private static String resultJson(String commandId, Map<String, Object> overrides)
        throws Exception {
        Map<String, Object> core = resultCore(commandId);
        for (var entry : overrides.entrySet()) {
            if (core.containsKey(entry.getKey())) {
                core.put(entry.getKey(), entry.getValue());
            }
        }
        String resultSha256 = digest(MAPPER.writeValueAsBytes(core));
        Map<String, Object> result = new LinkedHashMap<>(core);
        result.put("resultSha256", resultSha256);
        for (var entry : overrides.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return MAPPER.writeValueAsString(result);
    }

    private static Map<String, Object> resultCore(String commandId) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("agentId", "release-agent-01");
        core.put("agentVersion", "1.0.1");
        core.put("commandId", commandId);
        core.put("databaseSchemaVersion", 50);
        core.put("hostSnapshotSha256", "b".repeat(64));
        core.put("manifestSha256", "c".repeat(64));
        core.put("packageSha256", "d".repeat(64));
        core.put("planSha256", "e".repeat(64));
        core.put("productionExecutionEnabled", false);
        core.put("productVersion", "1.0.1");
        core.put("releaseId", "1.0.1-dddddddddddd");
        core.put("schemaVersion", 1);
        core.put("status", "VERIFIED_ONLY");
        core.put("verifiedAt", "2026-08-09T08:00:00.0000000+00:00");
        return core;
    }

    private static String deploymentResultJson(
        String commandId,
        Map<String, Object> overrides
    ) throws Exception {
        Map<String, Object> core = deploymentResultCore(commandId);
        for (var entry : overrides.entrySet()) {
            if (core.containsKey(entry.getKey())) {
                core.put(entry.getKey(), entry.getValue());
            }
        }
        String resultSha256 = digest(MAPPER.writeValueAsBytes(core));
        Map<String, Object> result = new LinkedHashMap<>(core);
        result.put("resultSha256", resultSha256);
        for (var entry : overrides.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return MAPPER.writeValueAsString(result);
    }

    private static Map<String, Object> deploymentResultCore(String commandId) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("agentId", "release-agent-01");
        core.put("agentVersion", "1.0.1");
        core.put("approvalId", "approval-001");
        core.put("commandId", commandId);
        core.put("databaseSchemaVersion", 50);
        core.put("deploymentReportSha256", "8".repeat(64));
        core.put("deploymentStatus", "SUCCEEDED");
        core.put("hostSnapshotSha256", "b".repeat(64));
        core.put("manifestSha256", "c".repeat(64));
        core.put("packageSha256", "d".repeat(64));
        core.put("planSha256", "e".repeat(64));
        core.put("productionExecutionEnabled", true);
        core.put("productVersion", "1.0.1");
        core.put("releaseId", "1.0.1-dddddddddddd");
        core.put("schemaVersion", 2);
        core.put("status", "DEPLOYED");
        core.put("verifiedAt", "2026-08-09T08:00:00.0000000+00:00");
        return core;
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
