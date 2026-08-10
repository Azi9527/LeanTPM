package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PowerShellReleasePackageVerifier implements ReleasePackageVerifier {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern THUMBPRINT = Pattern.compile("^[A-F0-9]{40}$");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Path powershellExecutable;
    private final Path verifierScript;
    private final String expectedVerifierSha256;
    private final String trustedCertificateThumbprint;
    private final Duration timeout;
    private final int maximumOutputBytes;
    private final ReleaseProcessRunner runner;

    public PowerShellReleasePackageVerifier(
        Path powershellExecutable,
        Path verifierScript,
        String expectedVerifierSha256,
        String trustedCertificateThumbprint,
        Duration timeout,
        int maximumOutputBytes
    ) {
        this(
            powershellExecutable,
            verifierScript,
            expectedVerifierSha256,
            trustedCertificateThumbprint,
            timeout,
            maximumOutputBytes,
            new BoundedReleaseProcessRunner()
        );
    }

    PowerShellReleasePackageVerifier(
        Path powershellExecutable,
        Path verifierScript,
        String expectedVerifierSha256,
        String trustedCertificateThumbprint,
        Duration timeout,
        int maximumOutputBytes,
        ReleaseProcessRunner runner
    ) {
        this.powershellExecutable = fixedFile(powershellExecutable, "PowerShell executable");
        this.verifierScript = fixedFile(verifierScript, "release verifier script");
        if (!this.verifierScript.getFileName().toString().equals("Test-ReleasePackage.ps1")) {
            throw new IllegalArgumentException("Verifier script name is not approved");
        }
        this.expectedVerifierSha256 = sha256(expectedVerifierSha256, "verifier script digest");
        String thumbprint = Objects.requireNonNullElse(
            trustedCertificateThumbprint, ""
        ).replace(" ", "").toUpperCase(Locale.ROOT);
        if (!THUMBPRINT.matcher(thumbprint).matches()) {
            throw new IllegalArgumentException("Trusted certificate thumbprint is invalid");
        }
        this.trustedCertificateThumbprint = thumbprint;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Verifier timeout must be within 1 ms and 10 minutes");
        }
        if (maximumOutputBytes < 1024 || maximumOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("Verifier output limit must be within 1 KiB and 1 MiB");
        }
        this.maximumOutputBytes = maximumOutputBytes;
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public VerificationReport verify(StoredPackage storedPackage) {
        Objects.requireNonNull(storedPackage, "storedPackage");
        Path packagePath = fixedFile(storedPackage.path(), "stored release package");
        String beforeSha256 = digest(packagePath);
        if (fileSize(packagePath) != storedPackage.size()
            || !beforeSha256.equals(sha256(storedPackage.sha256(), "stored package digest"))) {
            throw new ReleaseWorkflowException("Stored package bytes changed before verification");
        }
        if (!digest(verifierScript).equals(expectedVerifierSha256)) {
            throw new ReleaseWorkflowException("Pinned verifier script digest does not match");
        }

        List<String> command = List.of(
            powershellExecutable.toString(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            verifierScript.toString(),
            "-PackagePath",
            packagePath.toString(),
            "-TrustedCertificateThumbprint",
            trustedCertificateThumbprint,
            "-OutputFormat",
            "Json"
        );
        ReleaseProcessResult result = Objects.requireNonNull(
            runner.execute(command, timeout, maximumOutputBytes),
            "verifier process result"
        );
        if (result.timedOut()) {
            throw new ReleaseWorkflowException("Release package verifier timed out");
        }
        if (result.outputLimitExceeded()) {
            throw new ReleaseWorkflowException("Release package verifier exceeded its output limit");
        }
        if (result.exitCode() != 0) {
            throw new ReleaseWorkflowException("Release package verifier failed with a non-zero exit code");
        }
        if (result.standardError() != null && !result.standardError().isBlank()) {
            throw new ReleaseWorkflowException("Release package verifier emitted unexpected error output");
        }

        VerifierReport report;
        try {
            report = MAPPER.readValue(result.standardOutput(), VerifierReport.class);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Release package verifier returned invalid JSON", exception);
        }
        String afterSha256 = digest(packagePath);
        String reportSha256 = sha256(report.sha256(), "verified package digest");
        if (!"PASS".equals(report.status())
            || !packagePath.equals(fixedFile(Path.of(report.packagePath()), "verified package path"))
            || report.bytes() != storedPackage.size()
            || !beforeSha256.equals(afterSha256)
            || !beforeSha256.equals(reportSha256)) {
            throw new ReleaseWorkflowException("Verifier report does not describe the stored package");
        }
        if (report.databaseSchemaVersion() < 1) {
            throw new ReleaseWorkflowException("Verifier returned an invalid database schema version");
        }
        String productVersion = required(report.productVersion(), "verified product version");
        if (!productVersion.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")) {
            throw new ReleaseWorkflowException("Verifier returned an invalid product version");
        }
        return new VerificationReport(
            true,
            productVersion,
            report.databaseSchemaVersion(),
            sha256(report.manifestSha256(), "manifest digest"),
            reportSha256
        );
    }

    private static Path fixedFile(Path path, String label) {
        try {
            Path real = Objects.requireNonNull(path, label).toAbsolutePath().normalize()
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(real) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a regular file");
            }
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static String digest(Path path) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            );
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to hash a pinned verifier input", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to read stored package size", exception);
        }
    }

    private static String sha256(String value, String label) {
        String normalized = required(value, label).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ReleaseWorkflowException(label + " is required");
        }
        return value;
    }

    private record VerifierReport(
        String status,
        String releaseId,
        String releaseTier,
        String productVersion,
        int databaseSchemaFrom,
        int databaseSchemaVersion,
        int artifactCount,
        @JsonProperty("package") String packagePath,
        long bytes,
        long expandedBytes,
        String sha256,
        String manifestSha256,
        String schemaSha256
    ) {
    }
}
