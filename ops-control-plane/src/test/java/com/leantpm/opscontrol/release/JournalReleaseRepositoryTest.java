package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalReleaseRepositoryTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void releaseAndIdempotencyBindingSurviveRepositoryRestart() {
        Path stateRoot = temporaryRoot.resolve("state");
        JournalReleaseRepository first = new JournalReleaseRepository(stateRoot);
        ReleaseRecord record = release("release-a");
        IdempotencyBinding binding = new IdempotencyBinding("a".repeat(64), record.releaseId());

        first.saveAndBindIdempotency(record, "import", "import-001", binding);

        JournalReleaseRepository restarted = new JournalReleaseRepository(stateRoot);
        assertThat(restarted.find(record.releaseId())).contains(record);
        assertThat(restarted.findIdempotency("import", "import-001")).contains(binding);
    }

    @Test
    void truncatedJournalTailFailsClosed() throws Exception {
        Path stateRoot = temporaryRoot.resolve("truncated");
        JournalReleaseRepository repository = new JournalReleaseRepository(stateRoot);
        repository.save(release("release-a"));
        Files.writeString(
            repository.journalPath(),
            "{\"truncated\"",
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND
        );

        assertThatThrownBy(() -> new JournalReleaseRepository(stateRoot))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("truncated");
    }

    @Test
    void rewrittenJournalEventFailsHashChainValidation() throws Exception {
        Path stateRoot = temporaryRoot.resolve("tampered");
        JournalReleaseRepository repository = new JournalReleaseRepository(stateRoot);
        repository.save(release("release-a"));
        String journal = Files.readString(repository.journalPath(), StandardCharsets.UTF_8);
        String tampered = journal.replace("release-a", "release-b");
        assertThat(tampered).isNotEqualTo(journal);
        Files.writeString(repository.journalPath(), tampered, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new JournalReleaseRepository(stateRoot))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("hash");
    }

    @Test
    void conflictingBindingDoesNotAppendOrReplaceDurableState() throws Exception {
        Path stateRoot = temporaryRoot.resolve("conflict");
        JournalReleaseRepository repository = new JournalReleaseRepository(stateRoot);
        ReleaseRecord original = release("release-a");
        repository.saveAndBindIdempotency(
            original,
            "confirm",
            "confirm-001",
            new IdempotencyBinding("b".repeat(64), original.releaseId())
        );
        long sizeBefore = Files.size(repository.journalPath());

        assertThatThrownBy(() -> repository.saveAndBindIdempotency(
            release("release-b"),
            "confirm",
            "confirm-001",
            new IdempotencyBinding("c".repeat(64), "release-b")
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("already bound");

        assertThat(Files.size(repository.journalPath())).isEqualTo(sizeBefore);
        JournalReleaseRepository restarted = new JournalReleaseRepository(stateRoot);
        assertThat(restarted.find("release-a")).contains(original);
        assertThat(restarted.find("release-b")).isEmpty();
    }

    @Test
    void exposesSanitizedHashChainedAuditWithStableCursorAfterRestart() {
        Path stateRoot = temporaryRoot.resolve("audit");
        JournalReleaseRepository repository = new JournalReleaseRepository(stateRoot);
        ReleaseRecord first = release("release-a");
        ReleaseRecord second = release("release-b");

        repository.save(first);
        repository.saveAndBindIdempotency(
            second,
            "import",
            "import-002",
            new IdempotencyBinding("f".repeat(64), second.releaseId())
        );

        ReleaseAuditPage firstPage = repository.audit(0, 1);
        assertThat(firstPage.events()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.events().getFirst().sequence()).isEqualTo(1);
        assertThat(firstPage.events().getFirst().releaseId()).isEqualTo("release-a");
        assertThat(firstPage.events().getFirst().eventSha256()).hasSize(64);

        JournalReleaseRepository restarted = new JournalReleaseRepository(stateRoot);
        ReleaseAuditPage secondPage = restarted.audit(firstPage.nextCursor(), 10);
        assertThat(secondPage.events()).hasSize(1);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.events().getFirst().sequence()).isEqualTo(2);
        assertThat(secondPage.events().getFirst().releaseId()).isEqualTo("release-b");
        assertThat(secondPage.events().getFirst().operation()).isEqualTo("import");
        assertThat(secondPage.events().getFirst().state()).isEqualTo(ReleaseState.VERIFIED);
        assertThat(secondPage.nextCursor()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidAuditPaginationInsteadOfGuessing() {
        JournalReleaseRepository repository = new JournalReleaseRepository(
            temporaryRoot.resolve("audit-pagination")
        );

        assertThatThrownBy(() -> repository.audit(-1, 10))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("cursor");
        assertThatThrownBy(() -> repository.audit(0, 101))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("limit");
    }

    private ReleaseRecord release(String releaseId) {
        return new ReleaseRecord(
            releaseId,
            "1.0.1",
            50,
            "LeanTPM-1.0.1.zip",
            temporaryRoot.resolve(releaseId + ".zip"),
            3,
            "d".repeat(64),
            "e".repeat(64),
            ReleaseState.VERIFIED,
            null,
            List.of(),
            null,
            "operator-a",
            Instant.parse("2026-08-09T08:00:00Z")
        );
    }
}
