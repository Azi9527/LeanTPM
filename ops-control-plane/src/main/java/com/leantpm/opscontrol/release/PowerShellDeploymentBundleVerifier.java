package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PowerShellDeploymentBundleVerifier implements DeploymentBundleVerifier {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern THUMBPRINT = Pattern.compile("^[A-F0-9]{40}$");
    private static final Pattern RELEASE_ID = Pattern.compile(
        "^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$"
    );
    private static final Pattern IDENTITY = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9@._:-]{1,127}$"
    );
    private static final Pattern HOST_ID = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9._:-]{2,255}$"
    );
    private static final Pattern ENVIRONMENT_ID = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$"
    );
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Path powershellExecutable;
    private final Path verifierScript;
    private final String expectedVerifierSha256;
    private final String trustedSchemaSha256;
    private final String packageVerifierSha256;
    private final String approvalVerifierSha256;
    private final String trustedCertificateThumbprint;
    private final Path releaseTrustConfigPath;
    private final Path approvalRoot;
    private final Path uploadRoot;
    private final Duration timeout;
    private final int maximumOutputBytes;
    private final ReleaseProcessRunner runner;
    private final Clock clock;

    public PowerShellDeploymentBundleVerifier(
        Path powershellExecutable,
        Path verifierScript,
        String expectedVerifierSha256,
        String trustedSchemaSha256,
        String packageVerifierSha256,
        String approvalVerifierSha256,
        String trustedCertificateThumbprint,
        Path releaseTrustConfigPath,
        Path approvalRoot,
        Path uploadRoot,
        Duration timeout,
        int maximumOutputBytes,
        Clock clock
    ) {
        this(
            powershellExecutable,
            verifierScript,
            expectedVerifierSha256,
            trustedSchemaSha256,
            packageVerifierSha256,
            approvalVerifierSha256,
            trustedCertificateThumbprint,
            releaseTrustConfigPath,
            approvalRoot,
            uploadRoot,
            timeout,
            maximumOutputBytes,
            new BoundedReleaseProcessRunner(),
            clock
        );
    }

    PowerShellDeploymentBundleVerifier(
        Path powershellExecutable,
        Path verifierScript,
        String expectedVerifierSha256,
        String trustedSchemaSha256,
        String packageVerifierSha256,
        String approvalVerifierSha256,
        String trustedCertificateThumbprint,
        Path releaseTrustConfigPath,
        Path approvalRoot,
        Path uploadRoot,
        Duration timeout,
        int maximumOutputBytes,
        ReleaseProcessRunner runner
    ) {
        this(
            powershellExecutable,
            verifierScript,
            expectedVerifierSha256,
            trustedSchemaSha256,
            packageVerifierSha256,
            approvalVerifierSha256,
            trustedCertificateThumbprint,
            releaseTrustConfigPath,
            approvalRoot,
            uploadRoot,
            timeout,
            maximumOutputBytes,
            runner,
            Clock.systemUTC()
        );
    }

    PowerShellDeploymentBundleVerifier(
        Path powershellExecutable,
        Path verifierScript,
        String expectedVerifierSha256,
        String trustedSchemaSha256,
        String packageVerifierSha256,
        String approvalVerifierSha256,
        String trustedCertificateThumbprint,
        Path releaseTrustConfigPath,
        Path approvalRoot,
        Path uploadRoot,
        Duration timeout,
        int maximumOutputBytes,
        ReleaseProcessRunner runner,
        Clock clock
    ) {
        this.powershellExecutable = fixedFile(powershellExecutable, "PowerShell executable");
        this.verifierScript = fixedFile(verifierScript, "deployment bundle verifier script");
        if (!this.verifierScript.getFileName().toString()
            .equals("Test-LeanTpmDeploymentBundle.ps1")) {
            throw new IllegalArgumentException("Deployment bundle verifier name is not approved");
        }
        this.expectedVerifierSha256 = sha256(
            expectedVerifierSha256, "deployment bundle verifier digest"
        );
        this.trustedSchemaSha256 = sha256(trustedSchemaSha256, "bundle schema digest");
        this.packageVerifierSha256 = sha256(
            packageVerifierSha256, "package verifier digest"
        );
        this.approvalVerifierSha256 = sha256(
            approvalVerifierSha256, "approval verifier digest"
        );
        String thumbprint = Objects.requireNonNullElse(
            trustedCertificateThumbprint, ""
        ).replace(" ", "").toUpperCase(Locale.ROOT);
        if (!THUMBPRINT.matcher(thumbprint).matches()) {
            throw new IllegalArgumentException("Trusted certificate thumbprint is invalid");
        }
        this.trustedCertificateThumbprint = thumbprint;
        this.releaseTrustConfigPath = fixedFile(
            releaseTrustConfigPath, "release trust configuration"
        );
        this.approvalRoot = fixedDirectory(approvalRoot, "approval root");
        this.uploadRoot = fixedDirectory(uploadRoot, "upload root");
        if (this.approvalRoot.startsWith(this.uploadRoot)
            || this.uploadRoot.startsWith(this.approvalRoot)) {
            throw new IllegalArgumentException("Approval and upload roots must not overlap");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()
            || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Verifier timeout must be within 1 ms and 10 minutes");
        }
        if (maximumOutputBytes < 1024 || maximumOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("Verifier output limit must be within 1 KiB and 1 MiB");
        }
        this.maximumOutputBytes = maximumOutputBytes;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DeploymentBundleVerification verify(
        StoredPackage storedBundle,
        HostSnapshot hostSnapshot,
        String hostSnapshotSha256
    ) {
        Objects.requireNonNull(storedBundle, "storedBundle");
        Objects.requireNonNull(hostSnapshot, "hostSnapshot");
        Path bundlePath = fixedFile(storedBundle.path(), "stored deployment bundle");
        if (!bundlePath.startsWith(uploadRoot)) {
            throw new ReleaseWorkflowException("Stored deployment bundle is outside upload root");
        }
        String beforeSha256 = digest(bundlePath);
        if (FilesSize.size(bundlePath) != storedBundle.size()
            || !beforeSha256.equals(sha256(storedBundle.sha256(), "stored bundle digest"))) {
            throw new ReleaseWorkflowException("Stored deployment bundle bytes changed");
        }
        if (!digest(verifierScript).equals(expectedVerifierSha256)) {
            throw new ReleaseWorkflowException("Pinned deployment bundle verifier digest changed");
        }
        String expectedHostSnapshot = sha256(hostSnapshotSha256, "host snapshot digest");
        String expectedEnvironment = environmentId(hostSnapshot.environmentId());
        String expectedHost = hostId(hostSnapshot.hostId());

        List<String> command = List.of(
            powershellExecutable.toString(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            verifierScript.toString(),
            "-BundlePath",
            bundlePath.toString(),
            "-ExpectedHostSnapshotSha256",
            expectedHostSnapshot,
            "-ApprovalRoot",
            approvalRoot.toString(),
            "-UploadRoot",
            uploadRoot.toString(),
            "-TrustedManifestCertificateThumbprint",
            trustedCertificateThumbprint,
            "-ReleaseTrustConfigPath",
            releaseTrustConfigPath.toString(),
            "-TrustedSchemaSha256",
            trustedSchemaSha256,
            "-PackageVerifierSha256",
            packageVerifierSha256,
            "-ApprovalVerifierSha256",
            approvalVerifierSha256,
            "-OutputFormat",
            "Json"
        );
        ReleaseProcessResult result = Objects.requireNonNull(
            runner.execute(command, timeout, maximumOutputBytes),
            "bundle verifier process result"
        );
        requireSuccessful(result);

        VerifierReport report;
        try {
            report = MAPPER.readValue(result.standardOutput(), VerifierReport.class);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException(
                "Deployment bundle verifier returned invalid JSON", exception
            );
        }
        String afterSha256 = digest(bundlePath);
        String reportBundleSha256 = sha256(report.bundleSha256(), "reported bundle digest");
        if (!"PASS".equals(report.status()) || !report.readOnly()
            || !"DEPLOY_RELEASE".equals(report.action())
            || !bundlePath.equals(fixedFile(Path.of(report.bundlePath()), "reported bundle path"))
            || !beforeSha256.equals(afterSha256)
            || !beforeSha256.equals(reportBundleSha256)
            || !expectedEnvironment.equals(report.environmentId())
            || !expectedHost.equals(report.hostId())
            || !expectedHostSnapshot.equals(
                sha256(report.hostSnapshotSha256(), "reported host snapshot digest")
            )) {
            throw new ReleaseWorkflowException(
                "Deployment bundle verifier report does not match the stored bundle and host"
            );
        }

        String releaseId = required(report.releaseId(), "releaseId");
        String productVersion = required(report.productVersion(), "productVersion");
        if (!RELEASE_ID.matcher(releaseId).matches()
            || !productVersion.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")
            || report.databaseSchemaVersion() < 1
            || report.releasePackageBytes() < 1) {
            throw new ReleaseWorkflowException("Deployment bundle release identity is invalid");
        }
        String requestedBy = identity(report.requestedBy(), "requester identity");
        String approvedBy = identity(report.approvedBy(), "approver identity");
        if (requestedBy.equals(approvedBy)) {
            throw new ReleaseWorkflowException("Deployment bundle signers must be distinct");
        }
        Instant issuedAt = instant(report.issuedAtUtc(), "bundle issue time");
        Instant expiresAt = instant(report.expiresAtUtc(), "bundle expiry time");
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(Duration.ofMinutes(5)))
            || !now.isBefore(expiresAt)
            || !issuedAt.isBefore(expiresAt)
            || expiresAt.isAfter(issuedAt.plus(Duration.ofHours(24)))) {
            throw new ReleaseWorkflowException("Deployment bundle validity window is invalid");
        }

        return new DeploymentBundleVerification(
            true,
            releaseId,
            productVersion,
            report.databaseSchemaVersion(),
            expectedEnvironment,
            expectedHost,
            expectedHostSnapshot,
            reportBundleSha256,
            report.releasePackageBytes(),
            sha256(report.releasePackageSha256(), "release package digest"),
            sha256(report.manifestSha256(), "manifest digest"),
            sha256(report.deploymentPlanSha256(), "deployment plan digest"),
            sha256(report.requesterSignatureSha256(), "requester signature digest"),
            sha256(report.approverSignatureSha256(), "approver signature digest"),
            windowsId(report.approvalId(), "approvalId"),
            nonce(report.nonce()),
            requestedBy,
            approvedBy,
            issuedAt,
            expiresAt
        );
    }

    private static void requireSuccessful(ReleaseProcessResult result) {
        if (result.timedOut()) {
            throw new ReleaseWorkflowException("Deployment bundle verifier timed out");
        }
        if (result.outputLimitExceeded()) {
            throw new ReleaseWorkflowException("Deployment bundle verifier exceeded output limit");
        }
        if (result.exitCode() != 0) {
            throw new ReleaseWorkflowException("Deployment bundle verifier failed");
        }
        if (result.standardError() != null && !result.standardError().isBlank()) {
            throw new ReleaseWorkflowException("Deployment bundle verifier emitted error output");
        }
    }

    private static Path fixedFile(Path path, String label) {
        try {
            Path real = Objects.requireNonNull(path, label).toAbsolutePath().normalize()
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(real)
                || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a regular file");
            }
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static Path fixedDirectory(Path path, String label) {
        try {
            Path real = Objects.requireNonNull(path, label).toAbsolutePath().normalize()
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(real)
                || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a regular directory");
            }
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static String digest(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                return HexFormat.of().formatHex(digest.digest());
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
            }
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to hash deployment bundle input", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(String value, String label) {
        String normalized = required(value, label).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String environmentId(String value) {
        String normalized = required(value, "environmentId");
        if (!ENVIRONMENT_ID.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException("environmentId is invalid");
        }
        return normalized;
    }

    private static String hostId(String value) {
        String normalized = required(value, "hostId");
        if (!HOST_ID.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException("hostId is invalid");
        }
        return normalized;
    }

    private static String identity(String value, String label) {
        String normalized = required(value, label);
        if (!IDENTITY.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String windowsId(String value, String label) {
        String normalized = required(value, label);
        if (!normalized.matches("^[A-Za-z0-9._-]{3,128}$")
            || normalized.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)")
            || normalized.endsWith(".")) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String nonce(String value) {
        String normalized = required(value, "nonce");
        if (!normalized.matches("^[A-Fa-f0-9-]{16,64}$")) {
            throw new ReleaseWorkflowException("nonce is invalid");
        }
        return normalized;
    }

    private static Instant instant(String value, String label) {
        String normalized = required(value, label);
        try {
            Instant result = Instant.parse(normalized);
            if (!normalized.endsWith("Z")) {
                throw new ReleaseWorkflowException(label + " must be UTC");
            }
            return result;
        } catch (DateTimeParseException exception) {
            throw new ReleaseWorkflowException(label + " is invalid", exception);
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ReleaseWorkflowException(label + " is required");
        }
        return value;
    }

    private record VerifierReport(
        String status,
        boolean readOnly,
        String action,
        String bundlePath,
        String releaseId,
        String productVersion,
        int databaseSchemaVersion,
        String environmentId,
        String hostId,
        String hostSnapshotSha256,
        String bundleSha256,
        long releasePackageBytes,
        String releasePackageSha256,
        String manifestSha256,
        String deploymentPlanSha256,
        String requesterSignatureSha256,
        String approverSignatureSha256,
        String approvalId,
        String nonce,
        String requestedBy,
        String approvedBy,
        String issuedAtUtc,
        String expiresAtUtc
    ) {
    }

    private static final class FilesSize {
        private FilesSize() {
        }

        private static long size(Path path) {
            try {
                return Files.size(path);
            } catch (IOException exception) {
                throw new ReleaseWorkflowException("Unable to read deployment bundle size", exception);
            }
        }
    }
}
