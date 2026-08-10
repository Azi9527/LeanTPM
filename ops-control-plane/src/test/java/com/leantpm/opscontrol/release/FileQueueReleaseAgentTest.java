package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileQueueReleaseAgentTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC
    );

    @TempDir
    Path temporaryRoot;

    private Path uploadRoot;
    private Path approvalRoot;
    private Path packagePath;
    private String packageSha256;

    @BeforeEach
    void setUp() throws Exception {
        uploadRoot = Files.createDirectory(temporaryRoot.resolve("uploads"));
        approvalRoot = Files.createDirectory(temporaryRoot.resolve("approvals"));
        Path upload = Files.createDirectory(uploadRoot.resolve("upload-001"));
        packagePath = Files.write(upload.resolve("package.zip"), new byte[] {1, 2, 3});
        packageSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(packagePath))
        );
    }

    @Test
    void queuesOneDurableTypedCommandAndReplaysItIdempotently() throws Exception {
        Path queueRoot = temporaryRoot.resolve("queue");
        FileQueueReleaseAgent agent = new FileQueueReleaseAgent(
            queueRoot, uploadRoot, CLOCK
        );
        DeployReleaseCommand command = command("a".repeat(64), packagePath, packageSha256);

        String first = agent.enqueue(command);
        String replay = new FileQueueReleaseAgent(queueRoot, uploadRoot, CLOCK).enqueue(command);

        assertThat(first).isEqualTo(command.commandId());
        assertThat(replay).isEqualTo(first);
        try (var jobs = Files.list(queueRoot.resolve("pending"))) {
            assertThat(jobs).hasSize(1);
        }
        String json = Files.readString(queueRoot.resolve("pending").resolve(first + ".json"));
        assertThat(json).contains("\"action\":\"DEPLOY_RELEASE\"");
        assertThat(json).contains("\"manifestSha256\":\"1" + "1".repeat(63) + "\"");
        assertThat(json).contains(
            "\"packagePath\":\"" + packagePath.toString().replace("\\", "\\\\") + "\""
        );
        assertThat(json).doesNotContain("file:///");
        assertThat(json).doesNotContain("shell", "sql", "serviceName", "url");
    }

    @Test
    void rejectsCommandIdReplayWithDifferentContentWithoutReplacingQueueFile() throws Exception {
        Path queueRoot = temporaryRoot.resolve("conflict");
        FileQueueReleaseAgent agent = new FileQueueReleaseAgent(queueRoot, uploadRoot, CLOCK);
        DeployReleaseCommand original = command("b".repeat(64), packagePath, packageSha256);
        agent.enqueue(original);
        Path job = queueRoot.resolve("pending").resolve(original.commandId() + ".json");
        String before = Files.readString(job);
        DeployReleaseCommand conflict = new DeployReleaseCommand(
            original.schemaVersion(),
            original.commandId(),
            "1.0.2-conflict",
            "1.0.2",
            original.databaseSchemaVersion(),
            original.packagePath(),
            original.packageSha256(),
            original.manifestSha256(),
            original.planSha256(),
            original.hostSnapshotSha256(),
            original.expiresAt()
        );

        assertThatThrownBy(() -> agent.enqueue(conflict))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("different command");
        assertThat(Files.readString(job)).isEqualTo(before);
    }

    @Test
    void rejectsPackageOutsideUploadRootOrBytesChangedBeforeQueueing() throws Exception {
        FileQueueReleaseAgent agent = new FileQueueReleaseAgent(
            temporaryRoot.resolve("restricted"), uploadRoot, CLOCK
        );
        Path outside = Files.write(temporaryRoot.resolve("outside.zip"), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> agent.enqueue(command(
            "c".repeat(64), outside, packageSha256
        ))).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("upload root");

        Files.write(packagePath, new byte[] {9, 9, 9});
        assertThatThrownBy(() -> agent.enqueue(command(
            "d".repeat(64), packagePath, packageSha256
        ))).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");
    }

    @Test
    void queuesAHostBoundSignedCommandWithExactPlanAndTwoDetachedSignatures()
        throws Exception {
        Path approvalDirectory = Files.createDirectory(
            approvalRoot.resolve("approval-001")
        );
        Path plan = Files.writeString(
            approvalDirectory.resolve("deployment-plan.json"), "signed-plan"
        );
        Path requester = Files.writeString(
            approvalDirectory.resolve("deployment-plan.requester.p7s"), "requester"
        );
        Path approver = Files.writeString(
            approvalDirectory.resolve("deployment-plan.approver.p7s"), "approver"
        );
        SignedDeploymentMaterial material = new SignedDeploymentMaterial(
            "approval-001",
            plan,
            digest(plan),
            requester,
            digest(requester),
            approver,
            digest(approver)
        );
        DeployReleaseCommand command = new DeployReleaseCommand(
            1,
            "9".repeat(64),
            "1.0.2-abcdef123456",
            "1.0.2",
            50,
            packagePath,
            packageSha256,
            "1".repeat(64),
            material.deploymentPlanSha256(),
            "f".repeat(64),
            CLOCK.instant().plusSeconds(600),
            material
        );
        Path queueRoot = temporaryRoot.resolve("signed-queue");
        FileQueueReleaseAgent agent = new FileQueueReleaseAgent(
            queueRoot, uploadRoot, approvalRoot, CLOCK
        );

        String commandId = agent.enqueue(command);
        String json = Files.readString(
            queueRoot.resolve("pending").resolve(commandId + ".json")
        );

        assertThat(json).contains(
            "\"schemaVersion\":2",
            "\"action\":\"DEPLOY_SIGNED_RELEASE\"",
            "\"approvalId\":\"approval-001\"",
            "\"deploymentPlanSha256\":\"" + digest(plan) + "\"",
            "\"requesterSignatureSha256\":\"" + digest(requester) + "\"",
            "\"approverSignatureSha256\":\"" + digest(approver) + "\""
        );
        assertThat(json).contains(
            "\"deploymentPlanPath\":\"" + escaped(plan) + "\"",
            "\"requesterSignaturePath\":\"" + escaped(requester) + "\"",
            "\"approverSignaturePath\":\"" + escaped(approver) + "\""
        );
        assertThat(json).doesNotContain("shell", "sql", "serviceName", "url");

        SignedDeploymentMaterial escaped = new SignedDeploymentMaterial(
            material.approvalId(),
            Files.writeString(temporaryRoot.resolve("outside-plan.json"), "signed-plan"),
            material.deploymentPlanSha256(),
            material.requesterSignaturePath(),
            material.requesterSignatureSha256(),
            material.approverSignaturePath(),
            material.approverSignatureSha256()
        );
        DeployReleaseCommand outside = new DeployReleaseCommand(
            1,
            "8".repeat(64),
            command.releaseId(),
            command.productVersion(),
            command.databaseSchemaVersion(),
            command.packagePath(),
            command.packageSha256(),
            command.manifestSha256(),
            command.planSha256(),
            command.hostSnapshotSha256(),
            command.expiresAt(),
            escaped
        );
        assertThatThrownBy(() -> agent.enqueue(outside))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("approval root");
    }

    private DeployReleaseCommand command(String commandId, Path path, String sha256) {
        return new DeployReleaseCommand(
            1,
            commandId,
            "1.0.1-abcdef123456",
            "1.0.1",
            50,
            path,
            sha256,
            "1".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            CLOCK.instant().plusSeconds(600)
        );
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }

    private static String escaped(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
