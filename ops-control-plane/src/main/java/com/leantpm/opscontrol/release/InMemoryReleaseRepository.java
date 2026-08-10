package com.leantpm.opscontrol.release;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReleaseRepository implements ReleaseRepository {

    private final Map<String, ReleaseRecord> releases = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyBinding> idempotency = new ConcurrentHashMap<>();

    @Override
    public Optional<ReleaseRecord> find(String releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    @Override
    public List<ReleaseRecord> findAll() {
        return releases.values().stream()
            .sorted(Comparator.comparing(ReleaseRecord::importedAt)
                .thenComparing(ReleaseRecord::releaseId)
                .reversed())
            .toList();
    }

    @Override
    public void save(ReleaseRecord record) {
        releases.put(record.releaseId(), record);
    }

    @Override
    public Optional<IdempotencyBinding> findIdempotency(String operation, String key) {
        return Optional.ofNullable(idempotency.get(operation + ':' + key));
    }

    @Override
    public void bindIdempotency(String operation, String key, IdempotencyBinding binding) {
        IdempotencyBinding previous = idempotency.putIfAbsent(operation + ':' + key, binding);
        if (previous != null && !previous.equals(binding)) {
            throw new ReleaseWorkflowException("Idempotency key is already bound");
        }
    }

    @Override
    public synchronized void saveAndBindIdempotency(
        ReleaseRecord record,
        String operation,
        String key,
        IdempotencyBinding binding
    ) {
        String bindingKey = operation + ':' + key;
        IdempotencyBinding previous = idempotency.get(bindingKey);
        if (previous != null && !previous.equals(binding)) {
            throw new ReleaseWorkflowException("Idempotency key is already bound");
        }
        releases.put(record.releaseId(), record);
        idempotency.put(bindingKey, binding);
    }
}
