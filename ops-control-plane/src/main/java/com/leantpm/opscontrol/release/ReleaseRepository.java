package com.leantpm.opscontrol.release;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository {
    Optional<ReleaseRecord> find(String releaseId);

    List<ReleaseRecord> findAll();

    void save(ReleaseRecord record);

    Optional<IdempotencyBinding> findIdempotency(String operation, String key);

    void bindIdempotency(String operation, String key, IdempotencyBinding binding);

    void saveAndBindIdempotency(
        ReleaseRecord record,
        String operation,
        String key,
        IdempotencyBinding binding
    );
}
