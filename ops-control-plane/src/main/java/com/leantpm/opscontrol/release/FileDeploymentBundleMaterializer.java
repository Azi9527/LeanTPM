package com.leantpm.opscontrol.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class FileDeploymentBundleMaterializer implements DeploymentBundleMaterializer {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> EXACT_ENTRIES = Set.of(
        "deployment-bundle.json",
        "deployment-bundle.schema.json",
        "release-package.zip",
        "deployment-plan.json",
        "deployment-plan.requester.p7s",
        "deployment-plan.approver.p7s"
    );
    private static final Map<String, Long> LIMITS = Map.of(
        "release-package.zip", 5L * 1024L * 1024L * 1024L,
        "deployment-plan.json", 1024L * 1024L,
        "deployment-plan.requester.p7s", 16L * 1024L * 1024L,
        "deployment-plan.approver.p7s", 16L * 1024L * 1024L
    );

    private final Path uploadRoot;
    private final Path approvalRoot;

    public FileDeploymentBundleMaterializer(Path uploadRoot, Path approvalRoot) {
        this.uploadRoot = realDirectory(uploadRoot, "upload root");
        this.approvalRoot = realDirectory(approvalRoot, "approval root");
        if (this.uploadRoot.startsWith(this.approvalRoot)
            || this.approvalRoot.startsWith(this.uploadRoot)) {
            throw new IllegalArgumentException("Upload and approval roots must not overlap");
        }
    }

    @Override
    public synchronized MaterializedDeployment materialize(
        StoredPackage storedBundle,
        DeploymentBundleVerification verification
    ) {
        Objects.requireNonNull(storedBundle, "storedBundle");
        Objects.requireNonNull(verification, "verification");
        if (!verification.valid()) {
            throw new ReleaseWorkflowException("Deployment bundle was not verified");
        }
        Path bundlePath = realFile(storedBundle.path(), "stored deployment bundle");
        if (!bundlePath.startsWith(uploadRoot)) {
            throw new ReleaseWorkflowException("Stored deployment bundle is outside upload root");
        }
        String bundleSha256 = sha256(verification.bundleSha256(), "bundle digest");
        if (!bundleSha256.equals(sha256(storedBundle.sha256(), "stored bundle digest"))
            || storedBundle.size() != size(bundlePath)
            || !bundleSha256.equals(digest(bundlePath))) {
            throw new ReleaseWorkflowException("Deployment bundle digest or size changed");
        }

        String packageSha256 = sha256(
            verification.releasePackageSha256(), "release package digest"
        );
        String planSha256 = sha256(
            verification.deploymentPlanSha256(), "deployment plan digest"
        );
        String requesterSha256 = sha256(
            verification.requesterSignatureSha256(), "requester signature digest"
        );
        String approverSha256 = sha256(
            verification.approverSignatureSha256(), "approver signature digest"
        );
        String approvalId = windowsId(verification.approvalId(), "approvalId");

        Path packageParent = uploadRoot.resolve("releases");
        Path finalPackageDirectory = packageParent.resolve(packageSha256);
        Path finalApprovalDirectory = approvalRoot.resolve(approvalId);
        Path stagedPackageDirectory = packageParent.resolve(
            "." + packageSha256 + "." + UUID.randomUUID() + ".tmp"
        );
        Path stagedApprovalDirectory = approvalRoot.resolve(
            "." + approvalId + "." + UUID.randomUUID() + ".tmp"
        );

        try {
            try (ZipFile archive = new ZipFile(bundlePath.toFile())) {
                Map<String, ZipEntry> entries = exactEntries(archive);
                Files.createDirectories(packageParent);
                requireRegularDirectory(packageParent, "release package parent");
                Files.createDirectory(stagedPackageDirectory);
                Files.createDirectory(stagedApprovalDirectory);

                copyVerifiedEntry(
                    archive,
                    entries.get("release-package.zip"),
                    stagedPackageDirectory.resolve("release-package.zip"),
                    LIMITS.get("release-package.zip"),
                    verification.releasePackageBytes(),
                    packageSha256
                );
                copyVerifiedEntry(
                    archive,
                    entries.get("deployment-plan.json"),
                    stagedApprovalDirectory.resolve("deployment-plan.json"),
                    LIMITS.get("deployment-plan.json"),
                    -1,
                    planSha256
                );
                copyVerifiedEntry(
                    archive,
                    entries.get("deployment-plan.requester.p7s"),
                    stagedApprovalDirectory.resolve("deployment-plan.requester.p7s"),
                    LIMITS.get("deployment-plan.requester.p7s"),
                    -1,
                    requesterSha256
                );
                copyVerifiedEntry(
                    archive,
                    entries.get("deployment-plan.approver.p7s"),
                    stagedApprovalDirectory.resolve("deployment-plan.approver.p7s"),
                    LIMITS.get("deployment-plan.approver.p7s"),
                    -1,
                    approverSha256
                );
            }

            publishDirectory(
                stagedPackageDirectory,
                finalPackageDirectory,
                Map.of("release-package.zip", packageSha256)
            );
            publishDirectory(
                stagedApprovalDirectory,
                finalApprovalDirectory,
                Map.of(
                    "deployment-plan.json", planSha256,
                    "deployment-plan.requester.p7s", requesterSha256,
                    "deployment-plan.approver.p7s", approverSha256
                )
            );

            Path releasePackage = realFile(
                finalPackageDirectory.resolve("release-package.zip"),
                "materialized release package"
            );
            Path deploymentPlan = realFile(
                finalApprovalDirectory.resolve("deployment-plan.json"),
                "materialized deployment plan"
            );
            Path requesterSignature = realFile(
                finalApprovalDirectory.resolve("deployment-plan.requester.p7s"),
                "materialized requester signature"
            );
            Path approverSignature = realFile(
                finalApprovalDirectory.resolve("deployment-plan.approver.p7s"),
                "materialized approver signature"
            );
            return new MaterializedDeployment(
                new StoredPackage(
                    releasePackage,
                    "release-package.zip",
                    verification.releasePackageBytes(),
                    packageSha256
                ),
                deploymentPlan,
                requesterSignature,
                approverSignature
            );
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to materialize deployment bundle", exception);
        } finally {
            cleanupStaging(stagedPackageDirectory, packageParent);
            cleanupStaging(stagedApprovalDirectory, approvalRoot);
        }
    }

    private static Map<String, ZipEntry> exactEntries(ZipFile archive) {
        Map<String, ZipEntry> entries = new HashMap<>();
        var enumeration = archive.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || name.contains("/") || name.contains("\\")
                || name.contains(":") || !EXACT_ENTRIES.contains(name)
                || entries.putIfAbsent(name, entry) != null) {
                throw new ReleaseWorkflowException(
                    "Deployment bundle does not have the exact six-file layout"
                );
            }
        }
        if (!entries.keySet().equals(EXACT_ENTRIES)) {
            throw new ReleaseWorkflowException(
                "Deployment bundle does not have the exact six-file layout"
            );
        }
        return entries;
    }

    private static void copyVerifiedEntry(
        ZipFile archive,
        ZipEntry entry,
        Path target,
        long maximumBytes,
        long expectedBytes,
        String expectedSha256
    ) {
        if (entry == null || entry.getSize() < 1 || entry.getSize() > maximumBytes
            || (expectedBytes >= 0 && entry.getSize() != expectedBytes)) {
            throw new ReleaseWorkflowException("Deployment bundle entry size is invalid");
        }
        MessageDigest digest = newDigest();
        long written = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = archive.getInputStream(entry);
             FileChannel output = FileChannel.open(
                 target,
                 StandardOpenOption.CREATE_NEW,
                 StandardOpenOption.WRITE
             )) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > maximumBytes) {
                    throw new ReleaseWorkflowException("Deployment bundle entry exceeds limit");
                }
                digest.update(buffer, 0, count);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
            output.force(true);
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to copy deployment bundle entry", exception);
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
        if ((expectedBytes >= 0 && written != expectedBytes)
            || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw new ReleaseWorkflowException("Deployment bundle entry digest does not match");
        }
    }

    private static void publishDirectory(
        Path staged,
        Path target,
        Map<String, String> expectedFiles
    ) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireExactDirectory(target, expectedFiles);
            return;
        }
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ReleaseWorkflowException("Deployment materialization requires atomic move", exception);
        } catch (IOException exception) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireExactDirectory(target, expectedFiles);
                return;
            }
            throw new ReleaseWorkflowException("Unable to publish deployment material", exception);
        }
        requireExactDirectory(target, expectedFiles);
    }

    private static void requireExactDirectory(Path directory, Map<String, String> expectedFiles) {
        requireRegularDirectory(directory, "published deployment directory");
        Set<String> actual = new HashSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!expectedFiles.containsKey(name)) {
                    throw new ReleaseWorkflowException(
                        "Published deployment directory contains an unexpected file"
                    );
                }
                Path regular = realFile(child, "published deployment file");
                if (!digest(regular).equals(expectedFiles.get(name))) {
                    throw new ReleaseWorkflowException(
                        "Published deployment file digest does not match"
                    );
                }
                actual.add(name);
            }
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to inspect published deployment files", exception);
        }
        if (!actual.equals(expectedFiles.keySet())) {
            throw new ReleaseWorkflowException("Published deployment directory is incomplete");
        }
    }

    private static void cleanupStaging(Path staging, Path expectedParent) {
        if (!staging.toAbsolutePath().normalize().getParent().equals(
            expectedParent.toAbsolutePath().normalize()
        ) || !staging.getFileName().toString().startsWith(".")) {
            throw new ReleaseWorkflowException("Refusing to clean unexpected staging path");
        }
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(staging)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to clean deployment staging path", exception);
        }
    }

    private static void requireRegularDirectory(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReleaseWorkflowException(label + " is not a regular directory");
        }
    }

    private static Path realDirectory(Path path, String label) {
        try {
            Path real = Objects.requireNonNull(path, label).toAbsolutePath().normalize()
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
            requireRegularDirectory(real, label);
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static Path realFile(Path path, String label) {
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

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to read deployment file size", exception);
        }
    }

    private static String digest(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = newDigest();
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
            throw new ReleaseWorkflowException("Unable to hash deployment file", exception);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
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

    private static String windowsId(String value, String label) {
        String normalized = required(value, label);
        if (!normalized.matches("^[A-Za-z0-9._-]{3,128}$")
            || normalized.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)")
            || normalized.endsWith(".")) {
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
}
