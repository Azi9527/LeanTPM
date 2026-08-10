package com.leantpm.opscontrol.operations;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class FileOperationsStateStore implements OperationsStateStore {

    private static final String FILE_NAME = "operations-runtime-state.json";
    private static final long MAX_STATE_BYTES = 1024L * 1024L;

    private final Path root;
    private final Path stateFile;
    private final ObjectMapper objectMapper;

    public FileOperationsStateStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.stateFile = this.root.resolve(FILE_NAME);
        this.objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        initializeRoot();
        load();
    }

    @Override
    public synchronized OperationsRuntimeState load() {
        try {
            assertRegularStateFileIfPresent();
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                return OperationsRuntimeState.empty();
            }
            long size = Files.size(stateFile);
            if (size <= 0 || size > MAX_STATE_BYTES) {
                throw new IllegalStateException("operations runtime state has an invalid size");
            }
            OperationsRuntimeState state = objectMapper.readValue(
                Files.readAllBytes(stateFile),
                OperationsRuntimeState.class
            );
            validate(state);
            return state;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException illegal
                && illegal.getMessage() != null
                && illegal.getMessage().contains("operations runtime state")) {
                throw illegal;
            }
            throw new IllegalStateException("operations runtime state cannot be read safely", exception);
        }
    }

    @Override
    public synchronized void save(OperationsRuntimeState state) {
        Objects.requireNonNull(state, "state");
        validate(state);
        Path temporary = null;
        try {
            assertRegularStateFileIfPresent();
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
            if (bytes.length <= 0 || bytes.length > MAX_STATE_BYTES) {
                throw new IllegalStateException("operations runtime state exceeds the size limit");
            }
            temporary = Files.createTempFile(root, ".operations-runtime-state-", ".tmp");
            if (Files.isSymbolicLink(temporary)) {
                throw new IllegalStateException("operations runtime state temporary file is unsafe");
            }
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                    temporary,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                    "operations runtime state requires an atomic filesystem move",
                    exception
                );
            }
            temporary = null;
        } catch (IOException exception) {
            throw new IllegalStateException("operations runtime state cannot be persisted safely", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary failure remains authoritative.
                }
            }
        }
    }

    private void initializeRoot() {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
                throw new IllegalStateException("operations runtime state root cannot be a link");
            }
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("operations runtime state root is not a directory");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("operations runtime state root cannot be prepared", exception);
        }
    }

    private void assertRegularStateFileIfPresent() throws IOException {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(stateFile)
            || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("operations runtime state file is unsafe");
        }
    }

    private static void validate(OperationsRuntimeState state) {
        if (state == null || state.schemaVersion() != 1) {
            throw new IllegalStateException("operations runtime state schema is unsupported");
        }
        if (state.componentStatuses().size() > 128
            || state.consecutiveFailures().size() > 128
            || state.lastRemediationAt().size() > 128
            || state.remediationAttempts().size() > 128
            || state.releaseStatuses().size() > 512
            || state.recentRemediations().size() > 100) {
            throw new IllegalStateException("operations runtime state exceeds collection limits");
        }
        state.componentStatuses().forEach((key, value) -> {
            requiredBounded(key, 128);
            if (value == null) {
                throw new IllegalStateException("operations runtime state contains a null status");
            }
            try {
                OperationsHealth.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("operations runtime state contains an invalid status", exception);
            }
        });
        state.consecutiveFailures().forEach((key, value) -> {
            requiredBounded(key, 128);
            if (value == null || value < 0 || value > 1_000_000) {
                throw new IllegalStateException("operations runtime state contains an invalid failure count");
            }
        });
        state.remediationAttempts().forEach((key, values) -> {
            requiredBounded(key, 128);
            if (values == null || values.size() > 1000 || values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("operations runtime state contains invalid attempts");
            }
        });
        state.releaseStatuses().forEach((key, value) -> {
            requiredBounded(key, 128);
            requiredBounded(value, 64);
        });
        state.recentRemediations().forEach(event -> {
            if (event == null || event.occurredAt() == null || event.action() == null
                || event.outcome() == null) {
                throw new IllegalStateException("operations runtime state contains an invalid remediation event");
            }
            requiredBounded(event.eventId(), 128);
            requiredBounded(event.componentId(), 128);
            requiredBounded(event.summary(), 1000);
        });
    }

    private static void requiredBounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalStateException("operations runtime state contains an invalid text value");
        }
    }
}
