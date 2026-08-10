package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentBundleMaterializerTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void materializesOnlyTheFourVerifiedArtifactsAndSupportsExactReplay() throws Exception {
        Path uploadRoot = Files.createDirectory(temporaryRoot.resolve("uploads"));
        Path approvalRoot = Files.createDirectory(temporaryRoot.resolve("approvals"));
        Fixture fixture = fixture(uploadRoot, Map.of());
        FileDeploymentBundleMaterializer materializer = new FileDeploymentBundleMaterializer(
            uploadRoot, approvalRoot
        );

        MaterializedDeployment first = materializer.materialize(
            fixture.bundle(), fixture.verification()
        );
        MaterializedDeployment replay = materializer.materialize(
            fixture.bundle(), fixture.verification()
        );

        assertThat(first).isEqualTo(replay);
        assertThat(first.releasePackage().path()).isEqualTo(
            uploadRoot.resolve("releases")
                .resolve(fixture.verification().releasePackageSha256())
                .resolve("release-package.zip")
                .toRealPath()
        );
        assertThat(Files.readAllBytes(first.releasePackage().path()))
            .containsExactly(1, 2, 3, 4);
        assertThat(first.deploymentPlanPath()).hasFileName("deployment-plan.json");
        assertThat(first.requesterSignaturePath())
            .hasFileName("deployment-plan.requester.p7s");
        assertThat(first.approverSignaturePath())
            .hasFileName("deployment-plan.approver.p7s");
        try (var packageEntries = Files.list(first.releasePackage().path().getParent());
             var approvalEntries = Files.list(first.deploymentPlanPath().getParent())) {
            assertThat(packageEntries).hasSize(1);
            assertThat(approvalEntries).hasSize(3);
        }
    }

    @Test
    void rejectsUnexpectedEntryOrDigestDriftBeforePublishingTargets() throws Exception {
        Path uploadRoot = Files.createDirectory(temporaryRoot.resolve("uploads-invalid"));
        Path approvalRoot = Files.createDirectory(temporaryRoot.resolve("approvals-invalid"));
        FileDeploymentBundleMaterializer materializer = new FileDeploymentBundleMaterializer(
            uploadRoot, approvalRoot
        );
        Fixture extra = fixture(uploadRoot, Map.of("unexpected.txt", new byte[] {9}));

        assertThatThrownBy(() -> materializer.materialize(extra.bundle(), extra.verification()))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("exact");
        assertThat(uploadRoot.resolve("releases")).doesNotExist();
        assertThat(approvalRoot.resolve(extra.verification().approvalId())).doesNotExist();

        Fixture valid = fixture(uploadRoot, Map.of());
        DeploymentBundleVerification drifted = new DeploymentBundleVerification(
            true,
            valid.verification().releaseId(),
            valid.verification().productVersion(),
            valid.verification().databaseSchemaVersion(),
            valid.verification().environmentId(),
            valid.verification().hostId(),
            valid.verification().hostSnapshotSha256(),
            valid.verification().bundleSha256(),
            valid.verification().releasePackageBytes(),
            "9".repeat(64),
            valid.verification().manifestSha256(),
            valid.verification().deploymentPlanSha256(),
            valid.verification().requesterSignatureSha256(),
            valid.verification().approverSignatureSha256(),
            valid.verification().approvalId(),
            valid.verification().nonce(),
            valid.verification().requestedBy(),
            valid.verification().approvedBy(),
            valid.verification().issuedAt(),
            valid.verification().expiresAt()
        );
        assertThatThrownBy(() -> materializer.materialize(valid.bundle(), drifted))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("digest");
        assertThat(uploadRoot.resolve("releases").resolve("9".repeat(64))).doesNotExist();
    }

    private Fixture fixture(Path uploadRoot, Map<String, byte[]> additions) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("deployment-bundle.json", "{}".getBytes());
        entries.put("deployment-bundle.schema.json", "{}".getBytes());
        entries.put("release-package.zip", new byte[] {1, 2, 3, 4});
        entries.put("deployment-plan.json", "signed-plan".getBytes());
        entries.put("deployment-plan.requester.p7s", "requester".getBytes());
        entries.put("deployment-plan.approver.p7s", "approver".getBytes());
        entries.putAll(additions);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Path incoming = uploadRoot.resolve("incoming-" + System.nanoTime());
        Files.createDirectory(incoming);
        Path bundlePath = Files.write(incoming.resolve("deployment-bundle.zip"), bytes.toByteArray());
        StoredPackage bundle = new StoredPackage(
            bundlePath,
            "deployment-bundle.zip",
            Files.size(bundlePath),
            digest(bundlePath)
        );
        DeploymentBundleVerification verification = new DeploymentBundleVerification(
            true,
            "1.0.2-abcdef123456",
            "1.0.2",
            51,
            "production",
            "host-001",
            "c".repeat(64),
            bundle.sha256(),
            entries.get("release-package.zip").length,
            digest(entries.get("release-package.zip")),
            "e".repeat(64),
            digest(entries.get("deployment-plan.json")),
            digest(entries.get("deployment-plan.requester.p7s")),
            digest(entries.get("deployment-plan.approver.p7s")),
            "approval-001",
            "01234567-89ab-cdef-0123-456789abcdef",
            "release-requester",
            "release-approver",
            Instant.parse("2026-08-09T08:00:00Z"),
            Instant.parse("2026-08-09T08:15:00Z")
        );
        return new Fixture(bundle, verification);
    }

    private static String digest(Path path) throws Exception {
        return digest(Files.readAllBytes(path));
    }

    private static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value)
        );
    }

    private record Fixture(
        StoredPackage bundle,
        DeploymentBundleVerification verification
    ) {
    }
}
