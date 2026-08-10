package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHostSnapshotProviderTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void bindsReadyProductionHostLayoutAndCurrentReleasePointer() throws Exception {
        Path dataRoot = Files.createDirectories(temporaryRoot.resolve("Runtime"));
        Path pointers = Files.createDirectories(dataRoot.resolve("pointers"));
        Path layout = writeLayout(dataRoot, "READY");
        Path pointer = Files.writeString(
            pointers.resolve("current-release.json"),
            """
                {
                  "schemaVersion":1,
                  "releaseId":"1.0.1-abcdef123456",
                  "packageSha256":"%s"
                }
                """.formatted("b".repeat(64)),
            StandardCharsets.UTF_8
        );

        HostSnapshot snapshot = new FileHostSnapshotProvider(
            layout, digest(layout), pointer
        ).snapshot();

        assertThat(snapshot.environmentId()).isEqualTo("production-cn");
        assertThat(snapshot.hostId()).isEqualTo("host-001");
        assertThat(snapshot.currentReleaseId()).isEqualTo("1.0.1-abcdef123456");
        assertThat(snapshot.currentPackageSha256()).isEqualTo("b".repeat(64));
    }

    @Test
    void refusesModifiedOrNotReadyHostLayoutAndWrongPointerLocation() throws Exception {
        Path dataRoot = Files.createDirectories(temporaryRoot.resolve("Runtime"));
        Path pointers = Files.createDirectories(dataRoot.resolve("pointers"));
        Path layout = writeLayout(dataRoot, "INPUT_REQUIRED");
        Path pointer = Files.writeString(
            pointers.resolve("current-release.json"),
            "{\"schemaVersion\":1,\"releaseId\":\"1.0.1\",\"packageSha256\":\"%s\"}"
                .formatted("b".repeat(64)),
            StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> new FileHostSnapshotProvider(
            layout, digest(layout), pointer
        ).snapshot()).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("READY");

        String ready = Files.readString(layout).replace("INPUT_REQUIRED", "READY");
        Files.writeString(layout, ready, StandardCharsets.UTF_8);
        String approvedHash = digest(layout);
        Files.writeString(layout, ready + " ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new FileHostSnapshotProvider(
            layout, approvedHash, pointer
        ).snapshot()).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");

        Path validLayout = writeLayout(dataRoot, "READY");
        Path outsidePointer = Files.writeString(
            temporaryRoot.resolve("current-release.json"),
            Files.readString(pointer),
            StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> new FileHostSnapshotProvider(
            validLayout, digest(validLayout), outsidePointer
        ).snapshot()).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("pointer path");
    }

    private Path writeLayout(Path dataRoot, String readiness) throws Exception {
        return Files.writeString(
            temporaryRoot.resolve("host-layout.json"),
            """
                {
                  "schemaVersion":1,
                  "readiness":"%s",
                  "environmentKind":"PRODUCTION",
                  "environmentId":"production-cn",
                  "hostId":"host-001",
                  "installRoot":"D:\\\\LeanTPM\\\\App",
                  "dataRoot":"%s",
                  "volumeIdentity":"sha256:%s",
                  "proxy":{"mode":"EXTERNAL_EXISTING","serviceId":"caddy"}
                }
                """.formatted(
                    readiness,
                    dataRoot.toString().replace("\\", "\\\\"),
                    "a".repeat(64)
                ),
            StandardCharsets.UTF_8
        );
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
