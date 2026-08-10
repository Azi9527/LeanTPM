package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class FileReleaseAgentResultReader implements ReleaseAgentResultReader {

    private static final long MAXIMUM_RESULT_BYTES = 64L * 1024L;
    private static final Duration MAXIMUM_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern AGENT_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final Pattern VERSION = Pattern.compile(
        "^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$"
    );
    private static final Pattern RELEASE_ID = Pattern.compile(
        "^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$"
    );
    private static final Pattern APPROVAL_ID = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$"
    );
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .build();
    private static final ObjectMapper CORE_MAPPER = new ObjectMapper();

    private final Path queueRoot;
    private final Path resultsRoot;
    private final Clock clock;

    public FileReleaseAgentResultReader(Path queueRoot, Clock clock) {
        Path supplied = Objects.requireNonNull(queueRoot, "queueRoot");
        if (!supplied.isAbsolute()) {
            throw new IllegalArgumentException("Release queue root must be absolute");
        }
        this.queueRoot = supplied.normalize();
        this.resultsRoot = this.queueRoot.resolve("results");
        this.clock = Objects.requireNonNull(clock, "clock");
        initialize();
    }

    @Override
    public Optional<ReleaseAgentVerificationResult> find(String commandId) {
        String normalizedCommandId = requireSha256(commandId, "Agent command id");
        requireDirectory(queueRoot, "release queue root");
        requireDirectory(resultsRoot, "release Agent result root");
        Path resultPath = resultsRoot.resolve(normalizedCommandId + ".json").normalize();
        if (!resultPath.getParent().equals(resultsRoot)) {
            throw new ReleaseWorkflowException("Release Agent result escaped its fixed root");
        }
        if (!Files.exists(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        ResultEnvelope envelope = read(resultPath);
        validate(envelope, normalizedCommandId);
        Instant verifiedAt = parseTimestamp(envelope.verifiedAt());
        if (verifiedAt.isAfter(clock.instant().plus(MAXIMUM_CLOCK_SKEW))) {
            throw new ReleaseWorkflowException("Release Agent result timestamp is in the future");
        }
        return Optional.of(new ReleaseAgentVerificationResult(
            envelope.agentId(),
            envelope.agentVersion(),
            envelope.commandId(),
            envelope.databaseSchemaVersion(),
            envelope.hostSnapshotSha256(),
            envelope.manifestSha256(),
            envelope.packageSha256(),
            envelope.planSha256(),
            envelope.productionExecutionEnabled(),
            envelope.productVersion(),
            envelope.releaseId(),
            envelope.schemaVersion(),
            envelope.status(),
            envelope.approvalId(),
            envelope.deploymentStatus(),
            envelope.deploymentReportSha256(),
            verifiedAt,
            envelope.resultSha256()
        ));
    }

    private void initialize() {
        try {
            Files.createDirectories(resultsRoot);
            requireDirectory(queueRoot, "release queue root");
            requireDirectory(resultsRoot, "release Agent result root");
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to initialize release Agent result reader", exception
            );
        }
    }

    private static ResultEnvelope read(Path resultPath) {
        try {
            BasicFileAttributes before = requireRegularFile(
                resultPath,
                "release Agent result"
            );
            if (before.size() <= 0 || before.size() > MAXIMUM_RESULT_BYTES) {
                throw new ReleaseWorkflowException("Release Agent result size is invalid");
            }
            byte[] bytes = Files.readAllBytes(resultPath);
            BasicFileAttributes after = requireRegularFile(
                resultPath,
                "release Agent result"
            );
            if (bytes.length != before.size()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !sameFileKey(before.fileKey(), after.fileKey())) {
                throw new ReleaseWorkflowException("Release Agent result changed while being read");
            }
            JsonNode tree = MAPPER.readTree(strictUtf8(bytes));
            if (tree == null || !tree.isObject()) {
                throw new ReleaseWorkflowException(
                    "Release Agent result must be one JSON object"
                );
            }
            int schemaVersion = tree.path("schemaVersion").asInt(-1);
            if (schemaVersion == 1) {
                VerifyOnlyEnvelope value = MAPPER.treeToValue(
                    tree,
                    VerifyOnlyEnvelope.class
                );
                return new ResultEnvelope(
                    value.agentId(),
                    value.agentVersion(),
                    null,
                    value.commandId(),
                    value.databaseSchemaVersion(),
                    null,
                    null,
                    value.hostSnapshotSha256(),
                    value.manifestSha256(),
                    value.packageSha256(),
                    value.planSha256(),
                    value.productionExecutionEnabled(),
                    value.productVersion(),
                    value.releaseId(),
                    value.resultSha256(),
                    value.schemaVersion(),
                    value.status(),
                    value.verifiedAt()
                );
            }
            if (schemaVersion == 2) {
                DeploymentEnvelope value = MAPPER.treeToValue(
                    tree,
                    DeploymentEnvelope.class
                );
                return new ResultEnvelope(
                    value.agentId(),
                    value.agentVersion(),
                    value.approvalId(),
                    value.commandId(),
                    value.databaseSchemaVersion(),
                    value.deploymentReportSha256(),
                    value.deploymentStatus(),
                    value.hostSnapshotSha256(),
                    value.manifestSha256(),
                    value.packageSha256(),
                    value.planSha256(),
                    value.productionExecutionEnabled(),
                    value.productVersion(),
                    value.releaseId(),
                    value.resultSha256(),
                    value.schemaVersion(),
                    value.status(),
                    value.verifiedAt()
                );
            }
            throw new ReleaseWorkflowException(
                "Release Agent result schema is unsupported"
            );
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Release Agent result contains invalid JSON", exception);
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Release Agent result is unavailable", exception);
        }
    }

    private static void validate(ResultEnvelope value, String expectedCommandId) {
        boolean verifyOnly = value.schemaVersion() == 1
            && "VERIFIED_ONLY".equals(value.status())
            && !value.productionExecutionEnabled()
            && value.approvalId() == null
            && value.deploymentStatus() == null
            && value.deploymentReportSha256() == null;
        boolean deployed = value.schemaVersion() == 2
            && "DEPLOYED".equals(value.status())
            && value.productionExecutionEnabled()
            && value.approvalId() != null
            && APPROVAL_ID.matcher(value.approvalId()).matches()
            && ("SUCCEEDED".equals(value.deploymentStatus())
                || "ALREADY_SUCCEEDED".equals(value.deploymentStatus()))
            && value.deploymentReportSha256() != null
            && SHA256.matcher(value.deploymentReportSha256()).matches();
        if (!verifyOnly && !deployed) {
            throw new ReleaseWorkflowException("Release Agent result mode is unsupported");
        }
        if (!expectedCommandId.equals(value.commandId())) {
            throw new ReleaseWorkflowException("Release Agent result command id does not match");
        }
        if (value.agentId() == null || !AGENT_ID.matcher(value.agentId()).matches()
            || value.agentVersion() == null || !VERSION.matcher(value.agentVersion()).matches()) {
            throw new ReleaseWorkflowException("Release Agent result identity is invalid");
        }
        if (value.releaseId() == null || !RELEASE_ID.matcher(value.releaseId()).matches()
            || value.productVersion() == null || !VERSION.matcher(value.productVersion()).matches()
            || value.databaseSchemaVersion() < 1) {
            throw new ReleaseWorkflowException("Release Agent result release identity is invalid");
        }
        for (String digest : new String[] {
            value.commandId(),
            value.hostSnapshotSha256(),
            value.manifestSha256(),
            value.packageSha256(),
            value.planSha256(),
            value.resultSha256()
        }) {
            requireSha256(digest, "Release Agent result digest");
        }
        if (value.verifiedAt() == null || value.verifiedAt().isBlank()) {
            throw new ReleaseWorkflowException("Release Agent result timestamp is missing");
        }
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("agentId", value.agentId());
        core.put("agentVersion", value.agentVersion());
        if (deployed) {
            core.put("approvalId", value.approvalId());
        }
        core.put("commandId", value.commandId());
        core.put("databaseSchemaVersion", value.databaseSchemaVersion());
        if (deployed) {
            core.put("deploymentReportSha256", value.deploymentReportSha256());
            core.put("deploymentStatus", value.deploymentStatus());
        }
        core.put("hostSnapshotSha256", value.hostSnapshotSha256());
        core.put("manifestSha256", value.manifestSha256());
        core.put("packageSha256", value.packageSha256());
        core.put("planSha256", value.planSha256());
        core.put("productionExecutionEnabled", value.productionExecutionEnabled());
        core.put("productVersion", value.productVersion());
        core.put("releaseId", value.releaseId());
        core.put("schemaVersion", value.schemaVersion());
        core.put("status", value.status());
        core.put("verifiedAt", value.verifiedAt());
        if (!value.resultSha256().equals(digest(json(core)))) {
            throw new ReleaseWorkflowException("Release Agent result digest does not match");
        }
    }

    private static Instant parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw new ReleaseWorkflowException("Release Agent result timestamp is invalid", exception);
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
            throw new ReleaseWorkflowException(
                "Release Agent result is not strict UTF-8", exception
            );
        }
    }

    private static String requireSha256(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return value;
    }

    private static byte[] json(Object value) {
        try {
            return CORE_MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException(
                "Unable to serialize release Agent result", exception
            );
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean sameFileKey(Object before, Object after) {
        return before == null || after == null || before.equals(after);
    }

    private record ResultEnvelope(
        String agentId,
        String agentVersion,
        String approvalId,
        String commandId,
        int databaseSchemaVersion,
        String deploymentReportSha256,
        String deploymentStatus,
        String hostSnapshotSha256,
        String manifestSha256,
        String packageSha256,
        String planSha256,
        boolean productionExecutionEnabled,
        String productVersion,
        String releaseId,
        String resultSha256,
        int schemaVersion,
        String status,
        String verifiedAt
    ) {
    }

    private record VerifyOnlyEnvelope(
        String agentId,
        String agentVersion,
        String commandId,
        int databaseSchemaVersion,
        String hostSnapshotSha256,
        String manifestSha256,
        String packageSha256,
        String planSha256,
        boolean productionExecutionEnabled,
        String productVersion,
        String releaseId,
        String resultSha256,
        int schemaVersion,
        String status,
        String verifiedAt
    ) {
    }

    private record DeploymentEnvelope(
        String agentId,
        String agentVersion,
        String approvalId,
        String commandId,
        int databaseSchemaVersion,
        String deploymentReportSha256,
        String deploymentStatus,
        String hostSnapshotSha256,
        String manifestSha256,
        String packageSha256,
        String planSha256,
        boolean productionExecutionEnabled,
        String productVersion,
        String releaseId,
        String resultSha256,
        int schemaVersion,
        String status,
        String verifiedAt
    ) {
    }
}
