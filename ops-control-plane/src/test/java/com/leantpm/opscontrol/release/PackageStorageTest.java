package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageStorageTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void storesUploadUnderGeneratedContainedPathAndIgnoresClientPath() throws Exception {
        PackageStorage storage = new PackageStorage(temporaryRoot, 16);

        StoredPackage stored = storage.store(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            "..\\..\\arbitrary-name.zip",
            3
        );

        assertThat(stored.path()).startsWith(temporaryRoot.toRealPath());
        assertThat(stored.path().getFileName().toString()).isEqualTo("package.zip");
        assertThat(stored.originalFileName()).isEqualTo("arbitrary-name.zip");
        assertThat(stored.size()).isEqualTo(3);
        assertThat(stored.sha256()).hasSize(64);
    }

    @Test
    void removesPartialUploadWhenLimitIsExceeded() throws Exception {
        PackageStorage storage = new PackageStorage(temporaryRoot, 2);

        assertThatThrownBy(() -> storage.store(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            "release.zip",
            3
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("size limit");

        try (var paths = Files.walk(temporaryRoot)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }
}
