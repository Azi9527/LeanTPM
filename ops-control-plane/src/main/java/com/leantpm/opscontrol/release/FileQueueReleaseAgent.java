package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FileQueueReleaseAgent implements ReleaseAgent {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern RELEASE_ID = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final Path queueRoot;
    private final Path pendingRoot;
    private final Path uploadRoot;
    private final Path approvalRoot;
    private final Clock clock;

    public FileQueueReleaseAgent(Path queueRoot, Path uploadRoot, Clock clock) {
        this(queueRoot, uploadRoot, null, clock);
    }

    public FileQueueReleaseAgent(
        Path queueRoot,
        Path uploadRoot,
        Path approvalRoot,
        Clock clock
    ) {
        this.queueRoot = Objects.requireNonNull(queueRoot, "queueRoot")
            .toAbsolutePath()
            .normalize();
        this.pendingRoot = this.queueRoot.resolve("pending");
        this.uploadRoot = realDirectory(uploadRoot, "release upload root");
        this.approvalRoot = approvalRoot == null
            ? null
            : realDirectory(approvalRoot, "release approval root");
        if (this.approvalRoot != null && (
            this.approvalRoot.startsWith(this.uploadRoot)
                || this.uploadRoot.startsWith(this.approvalRoot)
        )) {
            throw new IllegalArgumentException("Release upload and approval roots must not overlap");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        initializeQueue();
    }

    @Override
    public synchronized String enqueue(DeployReleaseCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.schemaVersion() != 1) {
            throw new ReleaseWorkflowException("Release command schema is unsupported");
        }
        String commandId = sha256(command.commandId(), "command digest");
        String packageSha256 = sha256(command.packageSha256(), "package digest");
        String manifestSha256 = sha256(command.manifestSha256(), "manifest digest");
        String planSha256 = sha256(command.planSha256(), "plan digest");
        String hostSnapshotSha256 = sha256(
            command.hostSnapshotSha256(), "host snapshot digest"
        );
        String releaseId = required(command.releaseId(), "releaseId");
        String productVersion = required(command.productVersion(), "productVersion");
        int databaseSchemaVersion = command.databaseSchemaVersion();
        if (!RELEASE_ID.matcher(releaseId).matches()) {
            throw new ReleaseWorkflowException("Release command releaseId is invalid");
        }
        if (!productVersion.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
            || !releaseId.startsWith(productVersion + "-")
            || databaseSchemaVersion < 1) {
            throw new ReleaseWorkflowException("Release version or database schema is invalid");
        }
        Instant expiresAt = Objects.requireNonNull(command.expiresAt(), "expiresAt");
        if (!clock.instant().isBefore(expiresAt)) {
            throw new ReleaseWorkflowException("Release command has expired");
        }

        Path packagePath = realFile(command.packagePath(), "queued release package");
        if (!packagePath.startsWith(uploadRoot)) {
            throw new ReleaseWorkflowException("Queued package is outside the approved upload root");
        }
        if (!digest(packagePath).equals(packageSha256)) {
            throw new ReleaseWorkflowException("Queued package digest does not match current bytes");
        }

        Object queued;
        if (command.deploymentMaterial() == null) {
            queued = new QueuedReleaseCommand(
                1,
                "DEPLOY_RELEASE",
                commandId,
                databaseSchemaVersion,
                releaseId,
                productVersion,
                packagePath.toString(),
                packageSha256,
                manifestSha256,
                planSha256,
                hostSnapshotSha256,
                expiresAt
            );
        } else {
            VerifiedSignedMaterial material = verifySignedMaterial(
                command.deploymentMaterial(), planSha256
            );
            queued = new QueuedSignedReleaseCommand(
                2,
                "DEPLOY_SIGNED_RELEASE",
                commandId,
                databaseSchemaVersion,
                releaseId,
                productVersion,
                packagePath.toString(),
                packageSha256,
                manifestSha256,
                planSha256,
                hostSnapshotSha256,
                expiresAt,
                material.approvalId(),
                material.deploymentPlanPath().toString(),
                material.deploymentPlanSha256(),
                material.requesterSignaturePath().toString(),
                material.requesterSignatureSha256(),
                material.approverSignaturePath().toString(),
                material.approverSignatureSha256()
            );
        }
        byte[] payload = json(queued);
        Path jobPath = pendingRoot.resolve(commandId + ".json");
        if (Files.exists(jobPath, LinkOption.NOFOLLOW_LINKS)) {
            requireExactReplay(jobPath, payload);
            return commandId;
        }

        Path temporary = pendingRoot.resolve(
            "." + commandId + "." + UUID.randomUUID() + ".tmp"
        );
        try {
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, jobPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new ReleaseWorkflowException(
                    "Durable release queue requires atomic file replacement", exception
                );
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                requireExactReplay(jobPath, payload);
            }
            return commandId;
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to durably queue release command", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The durable command outcome is authoritative; startup audits stale temp files.
            }
        }
    }

    private void initializeQueue() {
        try {
            Files.createDirectories(pendingRoot);
            if (Files.isSymbolicLink(queueRoot) || Files.isSymbolicLink(pendingRoot)
                || !Files.isDirectory(pendingRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException("Release queue root is not a regular directory");
            }
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to initialize durable release queue", exception);
        }
    }

    private VerifiedSignedMaterial verifySignedMaterial(
        SignedDeploymentMaterial material,
        String commandPlanSha256
    ) {
        if (approvalRoot == null) {
            throw new ReleaseWorkflowException(
                "Signed deployment queue requires a fixed approval root"
            );
        }
        String approvalId = required(material.approvalId(), "approvalId");
        if (!approvalId.matches("^[A-Za-z0-9._-]{3,128}$")
            || approvalId.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)")
            || approvalId.endsWith(".")) {
            throw new ReleaseWorkflowException("Signed deployment approvalId is invalid");
        }
        Path directory = approvalRoot.resolve(approvalId).toAbsolutePath().normalize();
        requireExactApprovalDirectory(directory);
        Path deploymentPlan = exactApprovalFile(
            directory,
            material.deploymentPlanPath(),
            "deployment-plan.json",
            "deployment plan"
        );
        Path requesterSignature = exactApprovalFile(
            directory,
            material.requesterSignaturePath(),
            "deployment-plan.requester.p7s",
            "requester signature"
        );
        Path approverSignature = exactApprovalFile(
            directory,
            material.approverSignaturePath(),
            "deployment-plan.approver.p7s",
            "approver signature"
        );
        String deploymentPlanSha256 = sha256(
            material.deploymentPlanSha256(), "deployment plan digest"
        );
        String requesterSignatureSha256 = sha256(
            material.requesterSignatureSha256(), "requester signature digest"
        );
        String approverSignatureSha256 = sha256(
            material.approverSignatureSha256(), "approver signature digest"
        );
        if (!deploymentPlanSha256.equals(commandPlanSha256)
            || !deploymentPlanSha256.equals(digest(deploymentPlan))
            || !requesterSignatureSha256.equals(digest(requesterSignature))
            || !approverSignatureSha256.equals(digest(approverSignature))) {
            throw new ReleaseWorkflowException(
                "Signed deployment approval material digest does not match"
            );
        }
        return new VerifiedSignedMaterial(
            approvalId,
            deploymentPlan,
            deploymentPlanSha256,
            requesterSignature,
            requesterSignatureSha256,
            approverSignature,
            approverSignatureSha256
        );
    }

    private void requireExactApprovalDirectory(Path directory) {
        requireContainedDirectory(directory, approvalRoot, "signed approval directory");
        Set<String> expected = Set.of(
            "deployment-plan.json",
            "deployment-plan.requester.p7s",
            "deployment-plan.approver.p7s"
        );
        Set<String> actual = new HashSet<>();
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(child)) {
                    throw new ReleaseWorkflowException(
                        "Signed approval directory contains a non-regular entry"
                    );
                }
                actual.add(child.getFileName().toString());
            }
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to inspect signed approval directory", exception
            );
        }
        if (!actual.equals(expected)) {
            throw new ReleaseWorkflowException(
                "Signed approval directory does not have the exact three-file layout"
            );
        }
    }

    private static void requireContainedDirectory(
        Path directory,
        Path root,
        String label
    ) {
        try {
            Path current = directory;
            while (current != null) {
                if (Files.isSymbolicLink(current)) {
                    throw new ReleaseWorkflowException(label + " contains a reparse ancestor");
                }
                if (current.equals(root)) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw new ReleaseWorkflowException(label + " root is unavailable");
                    }
                    return;
                }
                current = current.getParent();
            }
            throw new ReleaseWorkflowException(label + " escaped the approval root");
        } catch (SecurityException exception) {
            throw new ReleaseWorkflowException("Unable to inspect " + label, exception);
        }
    }

    private static Path exactApprovalFile(
        Path directory,
        Path supplied,
        String expectedName,
        String label
    ) {
        Path expected = directory.resolve(expectedName).toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(supplied, label)
            .toAbsolutePath()
            .normalize();
        if (!normalized.equals(expected)) {
            throw new ReleaseWorkflowException(label + " escaped the approval root");
        }
        Path actual = realFile(expected, label);
        if (!actual.equals(expected)) {
            throw new ReleaseWorkflowException(label + " final path changed");
        }
        return actual;
    }

    private static void requireExactReplay(Path jobPath, byte[] expected) {
        try {
            if (Files.isSymbolicLink(jobPath)
                || !Files.isRegularFile(jobPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException("Existing release queue entry is not a regular file");
            }
            byte[] actual = Files.readAllBytes(jobPath);
            if (!Arrays.equals(actual, expected)) {
                throw new ReleaseWorkflowException(
                    "Command id is already bound to a different command"
                );
            }
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to verify existing queue entry", exception);
        }
    }

    private static Path realDirectory(Path path, String label) {
        try {
            Path real = Objects.requireNonNull(path, label).toAbsolutePath().normalize()
                .toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(real) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a regular directory");
            }
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static Path realFile(Path path, String label) {
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

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Unable to serialize typed release command", exception);
        }
    }

    private static String digest(Path path) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            );
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to hash queued release package", exception);
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

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ReleaseWorkflowException(label + " is required");
        }
        return value;
    }

    private record QueuedReleaseCommand(
        int schemaVersion,
        String action,
        String commandId,
        int databaseSchemaVersion,
        String releaseId,
        String productVersion,
        String packagePath,
        String packageSha256,
        String manifestSha256,
        String planSha256,
        String hostSnapshotSha256,
        Instant expiresAt
    ) {
    }

    private record QueuedSignedReleaseCommand(
        int schemaVersion,
        String action,
        String commandId,
        int databaseSchemaVersion,
        String releaseId,
        String productVersion,
        String packagePath,
        String packageSha256,
        String manifestSha256,
        String planSha256,
        String hostSnapshotSha256,
        Instant expiresAt,
        String approvalId,
        String deploymentPlanPath,
        String deploymentPlanSha256,
        String requesterSignaturePath,
        String requesterSignatureSha256,
        String approverSignaturePath,
        String approverSignatureSha256
    ) {
    }

    private record VerifiedSignedMaterial(
        String approvalId,
        Path deploymentPlanPath,
        String deploymentPlanSha256,
        Path requesterSignaturePath,
        String requesterSignatureSha256,
        Path approverSignaturePath,
        String approverSignatureSha256
    ) {
    }
}
