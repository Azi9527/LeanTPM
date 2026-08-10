package com.leantpm.opscontrol.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseWorkflowServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC
    );

    @TempDir
    Path temporaryRoot;

    @Test
    void uploadVerifyPlanAndTwoDistinctConfirmationsQueueOnlyExactTypedCommand() {
        RecordingAgent agent = new RecordingAgent();
        ReleaseWorkflowService service = service(agent, 2);

        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            "LeanTPM-1.0.1.zip",
            3,
            "operator-a",
            "import-001"
        );
        assertThat(imported.state()).isEqualTo(ReleaseState.VERIFIED);

        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator-a");
        assertThat(plan.packageSha256()).isEqualTo(imported.packageSha256());
        assertThat(plan.hostSnapshotSha256()).hasSize(64);
        assertThat(plan.planSha256()).hasSize(64);

        ConfirmationResult first = service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-a", "deploy 1.0.1", "confirm-001"
        );
        assertThat(first.state()).isEqualTo(ReleaseState.AWAITING_CONFIRMATION);
        assertThat(first.approvals()).isEqualTo(1);
        assertThat(agent.commands).isEmpty();

        ConfirmationResult second = service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-b", "deploy 1.0.1", "confirm-002"
        );
        assertThat(second.state()).isEqualTo(ReleaseState.QUEUED);
        assertThat(second.approvals()).isEqualTo(2);
        assertThat(agent.commands).hasSize(1);
        assertThat(agent.commands.getFirst().releaseId()).isEqualTo(imported.releaseId());
        assertThat(agent.commands.getFirst().planSha256()).isEqualTo(plan.planSha256());
        assertThat(agent.commands.getFirst().packagePath()).isEqualTo(imported.packagePath());
    }

    @Test
    void rejectsWrongPlanHashAndDuplicateApprover() {
        ReleaseWorkflowService service = service(new RecordingAgent(), 2);
        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {7}), "release.zip", 1, "operator", "import-002"
        );
        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator");

        assertThatThrownBy(() -> service.confirm(
            imported.releaseId(), "f".repeat(64), "approver-a", "wrong", "confirm-003"
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("plan digest");

        service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-a", "first", "confirm-004"
        );
        assertThatThrownBy(() -> service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-a", "again", "confirm-005"
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("distinct");
    }

    @Test
    void oneApprovalPolicySupportsUploadThenSingleConfirmation() {
        RecordingAgent agent = new RecordingAgent();
        ReleaseWorkflowService service = service(agent, 1);
        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {9}), "release.zip", 1, "operator", "import-006"
        );
        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator");

        ConfirmationResult result = service.confirm(
            imported.releaseId(), plan.planSha256(), "approver", "confirmed", "confirm-006"
        );

        assertThat(result.state()).isEqualTo(ReleaseState.QUEUED);
        assertThat(agent.commands).hasSize(1);
    }

    @Test
    void signedDeploymentBundleNeedsOneFinalConfirmationAndQueuesItsMaterializedPackage()
        throws Exception {
        RecordingAgent agent = new RecordingAgent();
        Path uploadRoot = temporaryRoot.resolve("signed-bundle-uploads");
        PackageStorage storage = new PackageStorage(uploadRoot, 4096);
        DeploymentBundleVerifier bundleVerifier = (stored, snapshot, hostDigest) ->
            new DeploymentBundleVerification(
                true,
                "1.0.2-abcdef123456",
                "1.0.2",
                51,
                snapshot.environmentId(),
                snapshot.hostId(),
                hostDigest,
                stored.sha256(),
                4,
                "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                "e".repeat(64),
                "f".repeat(64),
                "6".repeat(64),
                "7".repeat(64),
                "approval-001",
                "01234567-89ab-cdef-0123-456789abcdef",
                "release-requester",
                "release-approver",
                CLOCK.instant(),
                CLOCK.instant().plusSeconds(900)
            );
        DeploymentBundleMaterializer materializer = (stored, verification) ->
            materializeFixture(uploadRoot, verification);
        MutableResultReader results = new MutableResultReader();
        JournalReleaseRepository repository = new JournalReleaseRepository(
            temporaryRoot.resolve("signed-state")
        );
        HostSnapshotProvider host = () -> new HostSnapshot(
            "production", "host-001", "1.0.1", "b".repeat(64)
        );
        ReleaseWorkflowService service = new ReleaseWorkflowService(
            storage,
            stored -> {
                throw new AssertionError("Direct package verifier must not run for a signed bundle");
            },
            bundleVerifier,
            materializer,
            host,
            agent,
            results,
            repository,
            2,
            CLOCK
        );

        ReleaseRecord imported = service.importDeploymentBundle(
            new ByteArrayInputStream(new byte[] {9, 8, 7}),
            "LeanTPM-1.0.2-deployment-bundle.zip",
            3,
            "operator-a",
            "import-bundle-001"
        );

        assertThat(imported.state()).isEqualTo(ReleaseState.AWAITING_CONFIRMATION);
        assertThat(imported.plan().action()).isEqualTo("DEPLOY_SIGNED_RELEASE");
        assertThat(imported.plan().planSha256()).isEqualTo("f".repeat(64));
        assertThat(imported.deploymentMaterial()).isNotNull();
        assertThat(imported.deploymentMaterial().approvalId()).isEqualTo("approval-001");
        assertThat(imported.deploymentMaterial().deploymentPlanSha256())
            .isEqualTo(imported.plan().planSha256());
        assertThat(imported.packagePath()).isEqualTo(
            uploadRoot.resolve("releases").resolve(
                "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
            )
                .resolve("release-package.zip").toRealPath()
        );

        ConfirmationResult confirmed = service.confirm(
            imported.releaseId(),
            imported.plan().planSha256(),
            "operator-a",
            "final production confirmation",
            "confirm-bundle-001"
        );

        assertThat(confirmed.state()).isEqualTo(ReleaseState.QUEUED);
        assertThat(confirmed.approvals()).isEqualTo(1);
        assertThat(confirmed.requiredApprovals()).isEqualTo(1);
        assertThat(agent.commands).singleElement().satisfies(command -> {
            assertThat(command.packagePath()).isEqualTo(imported.packagePath());
            assertThat(command.planSha256()).isEqualTo(imported.plan().planSha256());
            assertThat(command.deploymentMaterial()).isEqualTo(imported.deploymentMaterial());
        });
        DeployReleaseCommand signedCommand = agent.commands.getFirst();
        results.result = Optional.of(new ReleaseAgentVerificationResult(
            "release-agent-01",
            "1.0.1",
            signedCommand.commandId(),
            imported.databaseSchemaVersion(),
            imported.plan().hostSnapshotSha256(),
            imported.manifestSha256(),
            imported.packageSha256(),
            imported.plan().planSha256(),
            true,
            imported.productVersion(),
            imported.releaseId(),
            2,
            "DEPLOYED",
            imported.deploymentMaterial().approvalId(),
            "SUCCEEDED",
            "8".repeat(64),
            CLOCK.instant(),
            "7".repeat(64)
        ));
        assertThat(service.get(imported.releaseId()).state())
            .isEqualTo(ReleaseState.DEPLOYED);
        assertThat(service.get(imported.releaseId()).state())
            .isEqualTo(ReleaseState.DEPLOYED);
        assertThat(repository.audit(0, 100).events())
            .filteredOn(event -> event.state() == ReleaseState.DEPLOYED)
            .hasSize(1);
        assertThat(uploadRoot.resolve("releases")).exists();
        try (var incoming = Files.list(uploadRoot)) {
            assertThat(incoming)
                .noneMatch(path -> Files.isDirectory(path) && !path.getFileName().toString()
                    .equals("releases"));
        }
    }

    @Test
    void importIdempotencyKeyCannotBeReusedForDifferentPackageBytes() {
        ReleaseWorkflowService service = service(new RecordingAgent(), 1);
        service.importRelease(
            new ByteArrayInputStream(new byte[] {1}), "release.zip", 1, "operator", "import-007"
        );

        assertThatThrownBy(() -> service.importRelease(
            new ByteArrayInputStream(new byte[] {2}), "release.zip", 1, "operator", "import-007"
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("Idempotency key");
    }

    @Test
    void confirmationIdempotencyKeyIsBoundToTheExactRequest() {
        ReleaseWorkflowService service = service(new RecordingAgent(), 2);
        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {3}), "release.zip", 1, "operator", "import-008"
        );
        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator");
        service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-a", "first reason", "confirm-008"
        );

        assertThatThrownBy(() -> service.confirm(
            imported.releaseId(), plan.planSha256(), "approver-a", "changed reason", "confirm-008"
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("Idempotency key");
    }

    @Test
    void twoPersonPolicyRejectsTheRequestingOperatorAsApprover() {
        ReleaseWorkflowService service = service(new RecordingAgent(), 2);
        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {4}), "release.zip", 1, "operator", "import-009"
        );
        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator");

        assertThatThrownBy(() -> service.confirm(
            imported.releaseId(), plan.planSha256(), "operator", "self approval", "confirm-009"
        )).isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("requesting operator");
    }

    @Test
    void listsImportedReleasesNewestFirstWithoutLosingDurableIdentity() {
        ReleaseWorkflowService service = service(new RecordingAgent(), 1);
        ReleaseRecord first = service.importRelease(
            new ByteArrayInputStream(new byte[] {4}), "first.zip", 1, "operator", "import-010"
        );
        ReleaseRecord second = service.importRelease(
            new ByteArrayInputStream(new byte[] {5}), "second.zip", 1, "operator", "import-011"
        );

        assertThat(service.list())
            .extracting(ReleaseRecord::releaseId)
            .containsExactly(second.releaseId(), first.releaseId());
    }

    @Test
    void reconcilesOneExactAgentVerificationResultAndRejectsBindingDrift() {
        RecordingAgent agent = new RecordingAgent();
        MutableResultReader results = new MutableResultReader();
        JournalReleaseRepository repository = new JournalReleaseRepository(
            temporaryRoot.resolve("state")
        );
        ReleaseWorkflowService service = service(agent, results, repository, 1);
        ReleaseRecord imported = service.importRelease(
            new ByteArrayInputStream(new byte[] {6}), "release.zip", 1, "operator", "import-012"
        );
        DeploymentPlan plan = service.createPlan(imported.releaseId(), "operator");
        service.confirm(
            imported.releaseId(), plan.planSha256(), "approver", "confirmed", "confirm-012"
        );
        DeployReleaseCommand command = agent.commands.getFirst();

        assertThat(service.get(imported.releaseId()).state()).isEqualTo(ReleaseState.QUEUED);

        results.result = Optional.of(agentResult(imported, command, plan, imported.packageSha256()));
        assertThat(service.get(imported.releaseId()).state())
            .isEqualTo(ReleaseState.AGENT_VERIFIED);
        assertThat(service.get(imported.releaseId()).state())
            .isEqualTo(ReleaseState.AGENT_VERIFIED);
        assertThat(repository.audit(0, 100).events())
            .filteredOn(event -> event.state() == ReleaseState.AGENT_VERIFIED)
            .hasSize(1);

        RecordingAgent driftAgent = new RecordingAgent();
        MutableResultReader driftResults = new MutableResultReader();
        InMemoryReleaseRepository driftRepository = new InMemoryReleaseRepository();
        ReleaseWorkflowService driftService = service(
            driftAgent,
            driftResults,
            driftRepository,
            1
        );
        ReleaseRecord driftImported = driftService.importRelease(
            new ByteArrayInputStream(new byte[] {7}), "release.zip", 1, "operator", "import-013"
        );
        DeploymentPlan driftPlan = driftService.createPlan(driftImported.releaseId(), "operator");
        driftService.confirm(
            driftImported.releaseId(), driftPlan.planSha256(), "approver", "confirmed", "confirm-013"
        );
        driftResults.result = Optional.of(agentResult(
            driftImported,
            driftAgent.commands.getFirst(),
            driftPlan,
            "f".repeat(64)
        ));

        assertThatThrownBy(() -> driftService.get(driftImported.releaseId()))
            .isInstanceOf(ReleaseWorkflowException.class)
            .hasMessageContaining("does not match");
        assertThat(driftRepository.find(driftImported.releaseId()).orElseThrow().state())
            .isEqualTo(ReleaseState.QUEUED);
    }

    private ReleaseWorkflowService service(RecordingAgent agent, int approvals) {
        return service(
            agent,
            commandId -> Optional.empty(),
            new InMemoryReleaseRepository(),
            approvals
        );
    }

    private MaterializedDeployment materializeFixture(
        Path uploadRoot,
        DeploymentBundleVerification verification
    ) {
        try {
            Path target = uploadRoot.resolve("releases")
                .resolve(verification.releasePackageSha256())
                .resolve("release-package.zip");
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[] {1, 2, 3, 4});
            Path approval = Files.createDirectories(
                temporaryRoot.resolve("signed-approvals").resolve(verification.approvalId())
            );
            return new MaterializedDeployment(
                new StoredPackage(
                    target.toRealPath(),
                    "release-package.zip",
                    verification.releasePackageBytes(),
                    verification.releasePackageSha256()
                ),
                Files.writeString(approval.resolve("deployment-plan.json"), "plan"),
                Files.writeString(
                    approval.resolve("deployment-plan.requester.p7s"), "requester"
                ),
                Files.writeString(
                    approval.resolve("deployment-plan.approver.p7s"), "approver"
                )
            );
        } catch (java.io.IOException exception) {
            throw new ReleaseWorkflowException("Unable to create test deployment material", exception);
        }
    }

    private ReleaseWorkflowService service(
        RecordingAgent agent,
        ReleaseAgentResultReader resultReader,
        ReleaseRepository repository,
        int approvals
    ) {
        PackageStorage storage = new PackageStorage(temporaryRoot.resolve("uploads"), 1024);
        ReleasePackageVerifier verifier = stored -> new VerificationReport(
            true,
            "1.0.1",
            50,
            "a".repeat(64),
            stored.sha256()
        );
        HostSnapshotProvider host = () -> new HostSnapshot(
            "production", "host-001", "1.0.0", "b".repeat(64)
        );
        return new ReleaseWorkflowService(
            storage,
            verifier,
            host,
            agent,
            resultReader,
            repository,
            approvals,
            CLOCK
        );
    }

    private static ReleaseAgentVerificationResult agentResult(
        ReleaseRecord record,
        DeployReleaseCommand command,
        DeploymentPlan plan,
        String packageSha256
    ) {
        return new ReleaseAgentVerificationResult(
            "release-agent-01",
            "1.0.1",
            command.commandId(),
            record.databaseSchemaVersion(),
            plan.hostSnapshotSha256(),
            record.manifestSha256(),
            packageSha256,
            plan.planSha256(),
            false,
            record.productVersion(),
            record.releaseId(),
            1,
            "VERIFIED_ONLY",
            CLOCK.instant(),
            "f".repeat(64)
        );
    }

    private static final class RecordingAgent implements ReleaseAgent {
        private final List<DeployReleaseCommand> commands = new ArrayList<>();

        @Override
        public String enqueue(DeployReleaseCommand command) {
            commands.add(command);
            return command.commandId();
        }
    }

    private static final class MutableResultReader implements ReleaseAgentResultReader {
        private Optional<ReleaseAgentVerificationResult> result = Optional.empty();

        @Override
        public Optional<ReleaseAgentVerificationResult> find(String commandId) {
            return result;
        }
    }
}
