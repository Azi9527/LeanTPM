package com.leantpm.opscontrol.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JournalReleaseRepository implements ReleaseRepository, ReleaseAuditReader {

    private static final int SCHEMA_VERSION = 1;
    private static final String EMPTY_HASH = "0".repeat(64);
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Path stateRoot;
    private final Path journalPath;
    private final Map<String, ReleaseRecord> releases = new HashMap<>();
    private final Map<String, IdempotencyBinding> idempotency = new HashMap<>();
    private final List<ReleaseAuditEvent> auditEvents = new ArrayList<>();
    private long sequence;
    private long journalSize;
    private String lastEventSha256 = EMPTY_HASH;

    public JournalReleaseRepository(Path stateRoot) {
        this.stateRoot = Objects.requireNonNull(stateRoot, "stateRoot")
            .toAbsolutePath()
            .normalize();
        this.journalPath = this.stateRoot.resolve("release-events.jsonl");
        initialize();
    }

    @Override
    public synchronized Optional<ReleaseRecord> find(String releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    @Override
    public synchronized List<ReleaseRecord> findAll() {
        return releases.values().stream()
            .sorted(java.util.Comparator.comparing(ReleaseRecord::importedAt)
                .thenComparing(ReleaseRecord::releaseId)
                .reversed())
            .toList();
    }

    @Override
    public synchronized ReleaseAuditPage audit(long afterSequence, int limit) {
        if (afterSequence < 0) {
            throw new ReleaseWorkflowException("Audit cursor cannot be negative");
        }
        if (limit < 1 || limit > 100) {
            throw new ReleaseWorkflowException("Audit limit must be between 1 and 100");
        }
        List<ReleaseAuditEvent> candidates = auditEvents.stream()
            .filter(event -> event.sequence() > afterSequence)
            .limit((long) limit + 1)
            .toList();
        boolean hasMore = candidates.size() > limit;
        List<ReleaseAuditEvent> page = hasMore
            ? candidates.subList(0, limit)
            : candidates;
        long nextCursor = page.isEmpty()
            ? afterSequence
            : page.getLast().sequence();
        return new ReleaseAuditPage(nextCursor, hasMore, page);
    }

    @Override
    public synchronized void save(ReleaseRecord record) {
        validateRelease(record);
        append(EventType.SAVE_RELEASE, record, null, null, null);
    }

    @Override
    public synchronized Optional<IdempotencyBinding> findIdempotency(
        String operation,
        String key
    ) {
        return Optional.ofNullable(idempotency.get(bindingKey(operation, key)));
    }

    @Override
    public synchronized void bindIdempotency(
        String operation,
        String key,
        IdempotencyBinding binding
    ) {
        validateBinding(operation, key, binding, null);
        append(EventType.BIND_IDEMPOTENCY, null, operation, key, binding);
    }

    @Override
    public synchronized void saveAndBindIdempotency(
        ReleaseRecord record,
        String operation,
        String key,
        IdempotencyBinding binding
    ) {
        validateRelease(record);
        validateBinding(operation, key, binding, record.releaseId());
        append(EventType.SAVE_AND_BIND, record, operation, key, binding);
    }

    Path journalPath() {
        return journalPath;
    }

    private void initialize() {
        try {
            Files.createDirectories(stateRoot);
            if (Files.isSymbolicLink(stateRoot)) {
                throw new ReleaseWorkflowException("Release state root cannot be a symbolic link");
            }
            if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(journalPath) || !Files.isRegularFile(
                    journalPath, LinkOption.NOFOLLOW_LINKS
                )) {
                    throw new ReleaseWorkflowException("Release journal must be a regular file");
                }
                loadJournal();
            }
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to initialize durable release state", exception);
        }
    }

    private void loadJournal() throws IOException {
        byte[] bytes = Files.readAllBytes(journalPath);
        journalSize = bytes.length;
        if (bytes.length == 0) {
            return;
        }
        if (bytes[bytes.length - 1] != (byte) '\n') {
            throw new ReleaseWorkflowException("Release journal has a truncated tail");
        }

        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new ReleaseWorkflowException("Release journal is not strict UTF-8", exception);
        }

        String[] lines = content.split("\n", -1);
        for (int index = 0; index < lines.length - 1; index++) {
            if (lines[index].isBlank()) {
                throw new ReleaseWorkflowException("Release journal contains an empty event");
            }
            JournalEvent event;
            try {
                event = MAPPER.readValue(lines[index], JournalEvent.class);
            } catch (JsonProcessingException exception) {
                throw new ReleaseWorkflowException("Release journal contains invalid JSON", exception);
            }
            validateLoadedEvent(event);
            apply(event);
            sequence = event.sequence();
            lastEventSha256 = event.eventSha256();
        }
    }

    private void append(
        EventType eventType,
        ReleaseRecord release,
        String operation,
        String key,
        IdempotencyBinding binding
    ) {
        long nextSequence = sequence + 1;
        JournalEventCore core = new JournalEventCore(
            SCHEMA_VERSION,
            nextSequence,
            eventType,
            release,
            operation,
            key,
            binding,
            lastEventSha256
        );
        String eventSha256 = digest(json(core));
        JournalEvent event = new JournalEvent(
            core.schemaVersion(),
            core.sequence(),
            core.eventType(),
            core.release(),
            core.operation(),
            core.key(),
            core.binding(),
            core.previousEventSha256(),
            eventSha256
        );
        byte[] payload = (jsonText(event) + "\n").getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(
            journalPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        ); FileLock ignored = channel.lock()) {
            if (channel.size() != journalSize) {
                throw new ReleaseWorkflowException(
                    "Release journal changed through a concurrent writer"
                );
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            journalSize = channel.size();
        } catch (ReleaseWorkflowException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to durably append release state", exception);
        }

        apply(event);
        sequence = nextSequence;
        lastEventSha256 = eventSha256;
    }

    private void validateLoadedEvent(JournalEvent event) {
        if (event == null || event.schemaVersion() != SCHEMA_VERSION) {
            throw new ReleaseWorkflowException("Release journal schema is unsupported");
        }
        if (event.sequence() != sequence + 1) {
            throw new ReleaseWorkflowException("Release journal sequence is not contiguous");
        }
        if (!Objects.equals(event.previousEventSha256(), lastEventSha256)) {
            throw new ReleaseWorkflowException("Release journal previous hash does not match");
        }
        String expected = digest(json(new JournalEventCore(
            event.schemaVersion(),
            event.sequence(),
            event.eventType(),
            event.release(),
            event.operation(),
            event.key(),
            event.binding(),
            event.previousEventSha256()
        )));
        if (!Objects.equals(event.eventSha256(), expected)) {
            throw new ReleaseWorkflowException("Release journal event hash does not match");
        }
        validateSemantics(event);
    }

    private void validateSemantics(JournalEvent event) {
        if (event.eventType() == null) {
            throw new ReleaseWorkflowException("Release journal event type is missing");
        }
        switch (event.eventType()) {
            case SAVE_RELEASE -> {
                validateRelease(event.release());
                requireNoBindingFields(event);
            }
            case BIND_IDEMPOTENCY -> {
                if (event.release() != null) {
                    throw new ReleaseWorkflowException("Binding event cannot contain a release");
                }
                validateBinding(event.operation(), event.key(), event.binding(), null);
            }
            case SAVE_AND_BIND -> {
                validateRelease(event.release());
                validateBinding(
                    event.operation(),
                    event.key(),
                    event.binding(),
                    event.release().releaseId()
                );
            }
        }
    }

    private void requireNoBindingFields(JournalEvent event) {
        if (event.operation() != null || event.key() != null || event.binding() != null) {
            throw new ReleaseWorkflowException("Release-only event contains binding fields");
        }
    }

    private void validateRelease(ReleaseRecord record) {
        if (record == null || record.releaseId() == null || record.releaseId().isBlank()) {
            throw new ReleaseWorkflowException("Durable release record is invalid");
        }
    }

    private void validateBinding(
        String operation,
        String key,
        IdempotencyBinding binding,
        String expectedReleaseId
    ) {
        String mapKey = bindingKey(operation, key);
        if (binding == null || binding.releaseId() == null || binding.releaseId().isBlank()
            || binding.requestSha256() == null
            || !SHA256.matcher(binding.requestSha256()).matches()) {
            throw new ReleaseWorkflowException("Durable idempotency binding is invalid");
        }
        if (expectedReleaseId != null && !expectedReleaseId.equals(binding.releaseId())) {
            throw new ReleaseWorkflowException("Idempotency binding release does not match record");
        }
        IdempotencyBinding previous = idempotency.get(mapKey);
        if (previous != null && !previous.equals(binding)) {
            throw new ReleaseWorkflowException("Idempotency key is already bound");
        }
    }

    private void apply(JournalEvent event) {
        validateSemantics(event);
        if (event.release() != null) {
            releases.put(event.release().releaseId(), event.release());
        }
        if (event.binding() != null) {
            idempotency.put(bindingKey(event.operation(), event.key()), event.binding());
        }
        ReleaseRecord release = event.release();
        String auditReleaseId = release != null
            ? release.releaseId()
            : event.binding().releaseId();
        ReleaseRecord current = releases.get(auditReleaseId);
        auditEvents.add(new ReleaseAuditEvent(
            event.sequence(),
            event.eventType().name(),
            auditReleaseId,
            current == null ? null : current.state(),
            event.operation(),
            event.previousEventSha256(),
            event.eventSha256()
        ));
    }

    private static String bindingKey(String operation, String key) {
        if (operation == null || operation.isBlank() || key == null || key.isBlank()) {
            throw new ReleaseWorkflowException("Idempotency operation and key are required");
        }
        return operation + '\u0000' + key;
    }

    private static byte[] json(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Unable to serialize release journal event", exception);
        }
    }

    private static String jsonText(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ReleaseWorkflowException("Unable to serialize release journal event", exception);
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

    private enum EventType {
        SAVE_RELEASE,
        BIND_IDEMPOTENCY,
        SAVE_AND_BIND
    }

    private record JournalEventCore(
        int schemaVersion,
        long sequence,
        EventType eventType,
        ReleaseRecord release,
        String operation,
        String key,
        IdempotencyBinding binding,
        String previousEventSha256
    ) {
    }

    private record JournalEvent(
        int schemaVersion,
        long sequence,
        EventType eventType,
        ReleaseRecord release,
        String operation,
        String key,
        IdempotencyBinding binding,
        String previousEventSha256,
        String eventSha256
    ) {
    }
}
