package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerShellReleasePackageVerifierTest {

    @TempDir
    Path temporaryRoot;

    private Path powershell;
    private Path script;
    private StoredPackage stored;

    @BeforeEach
    void setUp() throws Exception {
        powershell = Files.writeString(
            temporaryRoot.resolve("powershell.exe"), "fixed executable", StandardCharsets.UTF_8
        );
        script = Files.writeString(
            temporaryRoot.resolve("Test-ReleasePackage.ps1"), "fixed script", StandardCharsets.UTF_8
        );
        Path packagePath = Files.write(
            temporaryRoot.resolve("package.zip"), new byte[] {1, 2, 3}
        );
        stored = new StoredPackage(packagePath, "LeanTPM-1.0.1.zip", 3, digest(packagePath));
    }

    @Test
    void executesOnlyThePinnedVerifierWithFixedArgumentsAndParsesPassReport() throws Exception {
        RecordingRunner runner = new RecordingRunner(successResult());
        PowerShellReleasePackageVerifier verifier = verifier(runner);

        VerificationReport report = verifier.verify(stored);

        assertThat(report.valid()).isTrue();
        assertThat(report.productVersion()).isEqualTo("1.0.1");
        assertThat(report.databaseSchemaVersion()).isEqualTo(50);
        assertThat(report.packageSha256()).isEqualTo(stored.sha256());
        assertThat(runner.commands).hasSize(1);
        List<String> command = runner.commands.getFirst();
        assertThat(command.getFirst()).isEqualTo(powershell.toRealPath().toString());
        assertThat(command).containsSubsequence(
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.toRealPath().toString(),
            "-PackagePath",
            stored.path().toRealPath().toString(),
            "-TrustedCertificateThumbprint",
            "A".repeat(40),
            "-OutputFormat",
            "Json"
        );
        assertThat(command).doesNotContain("-AllowUnsignedTestManifest", "-ExtractTo");
    }

    @Test
    void refusesToExecuteWhenPinnedVerifierScriptBytesChanged() throws Exception {
        RecordingRunner runner = new RecordingRunner(successResult());
        PowerShellReleasePackageVerifier verifier = verifier(runner);
        Files.writeString(script, "changed script", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(stored))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void rejectsTimeoutOrOutputOverflow() throws Exception {
        RecordingRunner timeout = new RecordingRunner(
            new ReleaseProcessResult(-1, "", "", true, false)
        );
        assertThatThrownBy(() -> verifier(timeout).verify(stored))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("timed out");

        RecordingRunner overflow = new RecordingRunner(
            new ReleaseProcessResult(0, "{}", "", false, true)
        );
        assertThatThrownBy(() -> verifier(overflow).verify(stored))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("output limit");
    }

    @Test
    void rejectsAReportThatDoesNotDescribeTheStoredPackage() throws Exception {
        Map<String, Object> report = successReport();
        report.put("sha256", "f".repeat(64));
        RecordingRunner runner = new RecordingRunner(new ReleaseProcessResult(
            0,
            JsonMapper.builder().build().writeValueAsString(report),
            "",
            false,
            false
        ));

        assertThatThrownBy(() -> verifier(runner).verify(stored))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("stored package");
    }

    private PowerShellReleasePackageVerifier verifier(ReleaseProcessRunner runner) throws Exception {
        return new PowerShellReleasePackageVerifier(
            powershell,
            script,
            digest(script),
            "A".repeat(40),
            Duration.ofSeconds(30),
            64 * 1024,
            runner
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
        report.put("productVersion", "1.0.1");
        report.put("databaseSchemaVersion", 50);
        report.put("package", stored.path().toRealPath().toString());
        report.put("bytes", stored.size());
        report.put("sha256", stored.sha256());
        report.put("manifestSha256", "b".repeat(64));
        return report;
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }

    private static final class RecordingRunner implements ReleaseProcessRunner {
        private final List<List<String>> commands = new java.util.ArrayList<>();
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
