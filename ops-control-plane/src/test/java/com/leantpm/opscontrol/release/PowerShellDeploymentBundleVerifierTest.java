package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerShellDeploymentBundleVerifierTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T08:05:00Z"),
        ZoneOffset.UTC
    );

    @TempDir
    Path temporaryRoot;

    private Path powershell;
    private Path verifierScript;
    private Path trustConfig;
    private Path approvalRoot;
    private Path uploadRoot;
    private StoredPackage storedBundle;

    @BeforeEach
    void setUp() throws Exception {
        powershell = Files.writeString(
            temporaryRoot.resolve("powershell.exe"), "fixed executable", StandardCharsets.UTF_8
        );
        verifierScript = Files.writeString(
            temporaryRoot.resolve("Test-LeanTpmDeploymentBundle.ps1"),
            "fixed bundle verifier",
            StandardCharsets.UTF_8
        );
        trustConfig = Files.writeString(
            temporaryRoot.resolve("release-trust.json"), "{}", StandardCharsets.UTF_8
        );
        approvalRoot = Files.createDirectory(temporaryRoot.resolve("approvals"));
        uploadRoot = Files.createDirectory(temporaryRoot.resolve("uploads"));
        Path incoming = Files.createDirectory(uploadRoot.resolve("incoming"));
        Path bundle = Files.write(incoming.resolve("deployment-bundle.zip"), new byte[] {1, 2, 3});
        storedBundle = new StoredPackage(bundle, "deployment-bundle.zip", 3, digest(bundle));
    }

    @Test
    void executesOnlyPinnedBundleVerifierAndBindsHostAndAllSupplyChainDigests()
        throws Exception {
        RecordingRunner runner = new RecordingRunner(successResult());
        PowerShellDeploymentBundleVerifier verifier = verifier(runner);

        DeploymentBundleVerification report = verifier.verify(
            storedBundle,
            new HostSnapshot("production", "host-001", "1.0.1-old", "1".repeat(64)),
            "c".repeat(64)
        );

        assertThat(report.valid()).isTrue();
        assertThat(report.releaseId()).isEqualTo("1.0.2-abcdef123456");
        assertThat(report.productVersion()).isEqualTo("1.0.2");
        assertThat(report.databaseSchemaVersion()).isEqualTo(51);
        assertThat(report.bundleSha256()).isEqualTo(storedBundle.sha256());
        assertThat(report.releasePackageSha256()).isEqualTo("d".repeat(64));
        assertThat(report.deploymentPlanSha256()).isEqualTo("f".repeat(64));
        assertThat(runner.commands).hasSize(1);
        assertThat(runner.commands.getFirst()).containsSubsequence(
            "-File", verifierScript.toRealPath().toString(),
            "-BundlePath", storedBundle.path().toRealPath().toString(),
            "-ExpectedHostSnapshotSha256", "c".repeat(64),
            "-ApprovalRoot", approvalRoot.toRealPath().toString(),
            "-UploadRoot", uploadRoot.toRealPath().toString(),
            "-TrustedManifestCertificateThumbprint", "A".repeat(40),
            "-ReleaseTrustConfigPath", trustConfig.toRealPath().toString(),
            "-TrustedSchemaSha256", "2".repeat(64),
            "-PackageVerifierSha256", "3".repeat(64),
            "-ApprovalVerifierSha256", "4".repeat(64),
            "-OutputFormat", "Json"
        );
        assertThat(runner.commands.getFirst()).doesNotContain(
            "Invoke-LeanTpmDeployment.ps1", "-ConfirmDeployment", "-PlanOnly"
        );
    }

    @Test
    void rejectsVerifierDriftAndAReportForDifferentBundleOrHost() throws Exception {
        RecordingRunner runner = new RecordingRunner(successResult());
        PowerShellDeploymentBundleVerifier verifier = verifier(runner);
        Files.writeString(verifierScript, "drift", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(
            storedBundle,
            new HostSnapshot("production", "host-001", "1.0.1-old", "1".repeat(64)),
            "c".repeat(64)
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");
        assertThat(runner.commands).isEmpty();

        Map<String, Object> drifted = successReport();
        drifted.put("hostId", "host-999");
        drifted.put("bundleSha256", "9".repeat(64));
        RecordingRunner reportRunner = new RecordingRunner(new ReleaseProcessResult(
            0, JsonMapper.builder().build().writeValueAsString(drifted), "", false, false
        ));
        PowerShellDeploymentBundleVerifier reportVerifier = verifier(reportRunner);

        assertThatThrownBy(() -> reportVerifier.verify(
            storedBundle,
            new HostSnapshot("production", "host-001", "1.0.1-old", "1".repeat(64)),
            "c".repeat(64)
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("bundle");
    }

    private PowerShellDeploymentBundleVerifier verifier(ReleaseProcessRunner runner)
        throws Exception {
        return new PowerShellDeploymentBundleVerifier(
            powershell,
            verifierScript,
            digest(verifierScript),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "A".repeat(40),
            trustConfig,
            approvalRoot,
            uploadRoot,
            Duration.ofSeconds(30),
            64 * 1024,
            runner,
            FIXED_CLOCK
        );
    }

    private ReleaseProcessResult successResult() throws Exception {
        return new ReleaseProcessResult(
            0,
            JsonMapper.builder().build().writeValueAsString(successReport()),
            "",
            false,
            false
        );
    }

    private Map<String, Object> successReport() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASS");
        report.put("readOnly", true);
        report.put("action", "DEPLOY_RELEASE");
        report.put("bundlePath", storedBundle.path().toRealPath().toString());
        report.put("releaseId", "1.0.2-abcdef123456");
        report.put("productVersion", "1.0.2");
        report.put("databaseSchemaVersion", 51);
        report.put("environmentId", "production");
        report.put("hostId", "host-001");
        report.put("hostSnapshotSha256", "c".repeat(64));
        report.put("bundleSha256", storedBundle.sha256());
        report.put("releasePackageBytes", 23L);
        report.put("releasePackageSha256", "d".repeat(64));
        report.put("manifestSha256", "e".repeat(64));
        report.put("deploymentPlanSha256", "f".repeat(64));
        report.put("requesterSignatureSha256", "6".repeat(64));
        report.put("approverSignatureSha256", "7".repeat(64));
        report.put("approvalId", "approval-001");
        report.put("nonce", "01234567-89ab-cdef-0123-456789abcdef");
        report.put("requestedBy", "release-requester");
        report.put("approvedBy", "release-approver");
        report.put("issuedAtUtc", "2026-08-09T08:00:00Z");
        report.put("expiresAtUtc", "2026-08-09T08:15:00Z");
        return report;
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }

    private static final class RecordingRunner implements ReleaseProcessRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final ReleaseProcessResult result;

        private RecordingRunner(ReleaseProcessResult result) {
            this.result = result;
        }

        @Override
        public ReleaseProcessResult execute(
            List<String> command,
            Duration timeout,
            int maximumOutputBytes
        ) {
            commands.add(List.copyOf(command));
            return result;
        }
    }
}
