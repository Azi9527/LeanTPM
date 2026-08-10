package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class FileHostSnapshotProvider implements HostSnapshotProvider {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ENVIRONMENT = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,127}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{2,255}$");
    private static final Pattern RELEASE = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path hostLayoutPath;
    private final String expectedHostLayoutSha256;
    private final Path currentReleasePointer;

    public FileHostSnapshotProvider(
        Path hostLayoutPath,
        String expectedHostLayoutSha256,
        Path currentReleasePointer
    ) {
        this.hostLayoutPath = fixedPath(hostLayoutPath, "host layout");
        this.expectedHostLayoutSha256 = sha256(
            expectedHostLayoutSha256, "host layout digest"
        );
        this.currentReleasePointer = fixedPath(
            currentReleasePointer, "current release pointer"
        );
    }

    @Override
    public synchronized HostSnapshot snapshot() {
        byte[] layoutBytes = readRegularFile(hostLayoutPath, "host layout");
        byte[] pointerBytes = readRegularFile(currentReleasePointer, "current release pointer");
        byte[] layoutStable = readRegularFile(hostLayoutPath, "host layout");
        byte[] pointerStable = readRegularFile(currentReleasePointer, "current release pointer");
        if (!Arrays.equals(layoutBytes, layoutStable) || !Arrays.equals(pointerBytes, pointerStable)) {
            throw new ReleaseWorkflowException("Host snapshot files changed during collection");
        }
        if (!digest(layoutBytes).equals(expectedHostLayoutSha256)) {
            throw new ReleaseWorkflowException("Host layout digest does not match the approved bytes");
        }

        JsonNode layout = json(layoutBytes, "host layout");
        if (layout.path("schemaVersion").asInt(-1) != 1
            || !"PRODUCTION".equals(text(layout, "environmentKind"))) {
            throw new ReleaseWorkflowException("Host layout is not a production schema v1 document");
        }
        if (!"READY".equals(text(layout, "readiness"))) {
            throw new ReleaseWorkflowException("Host layout readiness must be READY");
        }
        String environmentId = text(layout, "environmentId");
        String hostId = text(layout, "hostId");
        if (!ENVIRONMENT.matcher(environmentId).matches() || !HOST.matcher(hostId).matches()) {
            throw new ReleaseWorkflowException("Host layout identity fields are invalid");
        }

        Path dataRoot = existingDirectory(Path.of(text(layout, "dataRoot")), "host data root");
        Path expectedPointer = dataRoot.resolve("pointers").resolve("current-release.json")
            .toAbsolutePath()
            .normalize();
        if (!currentReleasePointer.equals(expectedPointer)) {
            throw new ReleaseWorkflowException(
                "Current release pointer path is not bound to the host data root"
            );
        }

        JsonNode pointer = json(pointerBytes, "current release pointer");
        if (pointer.path("schemaVersion").asInt(-1) != 1) {
            throw new ReleaseWorkflowException("Current release pointer schema is unsupported");
        }
        String releaseId = text(pointer, "releaseId");
        if (!RELEASE.matcher(releaseId).matches()) {
            throw new ReleaseWorkflowException("Current release pointer releaseId is invalid");
        }
        String packageSha256 = sha256(
            text(pointer, "packageSha256"), "current package digest"
        );
        return new HostSnapshot(environmentId, hostId, releaseId, packageSha256);
    }

    private static Path fixedPath(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    private static Path existingDirectory(Path path, String label) {
        try {
            Path real = fixedPath(path, label).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(real) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a regular directory");
            }
            return real;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static byte[] readRegularFile(Path path, String label) {
        try {
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.equals(path) || Files.isSymbolicLink(real)
                || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException(label + " is not a fixed regular file");
            }
            return Files.readAllBytes(real);
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static JsonNode json(byte[] bytes, String label) {
        try {
            JsonNode value = MAPPER.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new ReleaseWorkflowException(label + " must be a JSON object");
            }
            return value;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is invalid JSON", exception);
        }
    }

    private static String text(JsonNode value, String property) {
        JsonNode node = value.get(property);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new ReleaseWorkflowException(property + " is required in host snapshot input");
        }
        return node.textValue();
    }

    private static String sha256(String value, String label) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
