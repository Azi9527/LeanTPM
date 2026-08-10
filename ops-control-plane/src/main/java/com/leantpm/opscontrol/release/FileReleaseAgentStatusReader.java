package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FileReleaseAgentStatusReader implements ReleaseAgentStatusReader {

    private static final long MAXIMUM_HEARTBEAT_BYTES = 64L * 1024L;
    private static final Duration MAXIMUM_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Pattern AGENT_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final Pattern VERSION = Pattern.compile(
        "^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$"
    );
    private static final Pattern PENDING_JOB = Pattern.compile("^[a-f0-9]{64}\\.json$");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final Path queueRoot;
    private final Path pendingRoot;
    private final Path heartbeatPath;
    private final Duration maximumAge;
    private final Clock clock;

    public FileReleaseAgentStatusReader(
        Path queueRoot,
        Duration maximumAge,
        Clock clock
    ) {
        this.queueRoot = Objects.requireNonNull(queueRoot, "queueRoot")
            .toAbsolutePath()
            .normalize();
        this.pendingRoot = this.queueRoot.resolve("pending");
        this.heartbeatPath = this.queueRoot.resolve("agent-heartbeat.json");
        this.maximumAge = Objects.requireNonNull(maximumAge, "maximumAge");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumAge.isNegative() || maximumAge.isZero()
            || maximumAge.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                "Agent heartbeat maximum age must be between 1 millisecond and 5 minutes"
            );
        }
        initializeQueue();
    }

    @Override
    public ReleaseAgentStatus status() {
        int pendingJobs = countPendingJobs();
        if (!Files.exists(heartbeatPath, LinkOption.NOFOLLOW_LINKS)) {
            return new ReleaseAgentStatus(
                ReleaseAgentConnectionState.NOT_CONNECTED,
                null,
                null,
                null,
                pendingJobs,
                false
            );
        }

        AgentHeartbeat heartbeat = readHeartbeat();
        validateHeartbeat(heartbeat);
        Instant now = clock.instant();
        if (heartbeat.lastSeenAt().isAfter(now.plus(MAXIMUM_CLOCK_SKEW))) {
            throw new ReleaseWorkflowException("Agent heartbeat timestamp is in the future");
        }
        boolean fresh = !heartbeat.lastSeenAt().isBefore(now.minus(maximumAge));
        ReleaseAgentConnectionState state = fresh
            ? ReleaseAgentConnectionState.ONLINE
            : ReleaseAgentConnectionState.STALE;
        boolean productionEnabled = fresh
            && heartbeat.mode() == AgentMode.PRODUCTION_ENABLED;
        return new ReleaseAgentStatus(
            state,
            heartbeat.agentId(),
            heartbeat.agentVersion(),
            heartbeat.lastSeenAt(),
            pendingJobs,
            productionEnabled
        );
    }

    private void initializeQueue() {
        try {
            Files.createDirectories(pendingRoot);
            requireDirectory(queueRoot, "release queue root");
            requireDirectory(pendingRoot, "release pending queue");
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to initialize release agent status reader", exception
            );
        }
    }

    private int countPendingJobs() {
        requireDirectory(queueRoot, "release queue root");
        requireDirectory(pendingRoot, "release pending queue");
        try (Stream<Path> entries = Files.list(pendingRoot)) {
            long count = entries
                .filter(path -> PENDING_JOB.matcher(path.getFileName().toString()).matches())
                .peek(path -> requireRegularFile(path, "pending release job"))
                .count();
            if (count > Integer.MAX_VALUE) {
                throw new ReleaseWorkflowException("Pending release job count exceeds limit");
            }
            return (int) count;
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to inspect pending release jobs", exception);
        }
    }

    private AgentHeartbeat readHeartbeat() {
        try {
            BasicFileAttributes before = requireRegularFile(
                heartbeatPath,
                "release agent heartbeat"
            );
            if (before.size() <= 0 || before.size() > MAXIMUM_HEARTBEAT_BYTES) {
                throw new ReleaseWorkflowException("Agent heartbeat size is invalid");
            }
            byte[] bytes = Files.readAllBytes(heartbeatPath);
            BasicFileAttributes after = requireRegularFile(
                heartbeatPath,
                "release agent heartbeat"
            );
            if (bytes.length != before.size()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !sameFileKey(before.fileKey(), after.fileKey())) {
                throw new ReleaseWorkflowException("Agent heartbeat changed while being read");
            }
            String json = strictUtf8(bytes);
            return MAPPER.readValue(json, AgentHeartbeat.class);
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Agent heartbeat is invalid JSON", exception);
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Agent heartbeat is unavailable", exception);
        }
    }

    private static void validateHeartbeat(AgentHeartbeat heartbeat) {
        if (heartbeat == null || heartbeat.schemaVersion() != 1) {
            throw new ReleaseWorkflowException("Agent heartbeat schema is unsupported");
        }
        if (heartbeat.agentId() == null || !AGENT_ID.matcher(heartbeat.agentId()).matches()) {
            throw new ReleaseWorkflowException("Agent heartbeat identity is invalid");
        }
        if (heartbeat.agentVersion() == null
            || !VERSION.matcher(heartbeat.agentVersion()).matches()) {
            throw new ReleaseWorkflowException("Agent heartbeat version is invalid");
        }
        if (heartbeat.mode() == null || heartbeat.lastSeenAt() == null) {
            throw new ReleaseWorkflowException("Agent heartbeat fields are incomplete");
        }
    }

    private static void requireDirectory(Path path, String label) {
        if (Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReleaseWorkflowException(label + " is not a regular directory");
        }
    }

    private static BasicFileAttributes requireRegularFile(Path path, String label) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new ReleaseWorkflowException(label + " is not a regular file");
            }
            return attributes;
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(label + " is unavailable", exception);
        }
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new ReleaseWorkflowException("Agent heartbeat is not strict UTF-8", exception);
        }
    }

    private static boolean sameFileKey(Object before, Object after) {
        return before == null || after == null || before.equals(after);
    }

    private enum AgentMode {
        VERIFY_ONLY,
        PRODUCTION_ENABLED
    }

    private record AgentHeartbeat(
        int schemaVersion,
        String agentId,
        String agentVersion,
        AgentMode mode,
        Instant lastSeenAt
    ) {
    }
}
