package com.leantpm.opscontrol.release;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ReleaseWorkflowService {

    private static final Pattern ACTOR = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9@._-]{1,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern RELEASE_ID = Pattern.compile(
        "^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$"
    );
    private static final Duration PLAN_LIFETIME = Duration.ofMinutes(15);

    private final PackageStorage storage;
    private final ReleasePackageVerifier verifier;
    private final DeploymentBundleVerifier bundleVerifier;
    private final DeploymentBundleMaterializer bundleMaterializer;
    private final HostSnapshotProvider hostSnapshotProvider;
    private final ReleaseAgent agent;
    private final ReleaseAgentResultReader agentResultReader;
    private final ReleaseRepository repository;
    private final int requiredApprovals;
    private final Clock clock;

    public ReleaseWorkflowService(
        PackageStorage storage,
        ReleasePackageVerifier verifier,
        HostSnapshotProvider hostSnapshotProvider,
        ReleaseAgent agent,
        ReleaseAgentResultReader agentResultReader,
        ReleaseRepository repository,
        int requiredApprovals,
        Clock clock
    ) {
        this(
            storage,
            verifier,
            (stored, snapshot, hostDigest) -> {
                throw new ReleaseWorkflowException(
                    "Signed deployment bundle import is not configured"
                );
            },
            (stored, verification) -> {
                throw new ReleaseWorkflowException(
                    "Signed deployment bundle materialization is not configured"
                );
            },
            hostSnapshotProvider,
            agent,
            agentResultReader,
            repository,
            requiredApprovals,
            clock
        );
    }

    public ReleaseWorkflowService(
        PackageStorage storage,
        ReleasePackageVerifier verifier,
        DeploymentBundleVerifier bundleVerifier,
        DeploymentBundleMaterializer bundleMaterializer,
        HostSnapshotProvider hostSnapshotProvider,
        ReleaseAgent agent,
        ReleaseAgentResultReader agentResultReader,
        ReleaseRepository repository,
        int requiredApprovals,
        Clock clock
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.bundleVerifier = Objects.requireNonNull(bundleVerifier, "bundleVerifier");
        this.bundleMaterializer = Objects.requireNonNull(
            bundleMaterializer, "bundleMaterializer"
        );
        this.hostSnapshotProvider = Objects.requireNonNull(hostSnapshotProvider, "hostSnapshotProvider");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.agentResultReader = Objects.requireNonNull(agentResultReader, "agentResultReader");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (requiredApprovals < 1 || requiredApprovals > 2) {
            throw new IllegalArgumentException("requiredApprovals must be 1 or 2");
        }
        this.requiredApprovals = requiredApprovals;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized ReleaseRecord importDeploymentBundle(
        InputStream input,
        String originalFileName,
        long declaredSize,
        String actor,
        String idempotencyKey
    ) {
        String validatedActor = actor(actor);
        String key = idempotencyKey(idempotencyKey);
        StoredPackage storedBundle = storage.store(input, originalFileName, declaredSize);
        try {
            String requestDigest = digest(String.join("\n",
                "IMPORT_DEPLOYMENT_BUNDLE",
                validatedActor,
                storedBundle.sha256(),
                Long.toString(storedBundle.size())
            ));
            var existing = repository.findIdempotency("import-bundle", key);
            if (existing.isPresent()) {
                if (!existing.get().requestSha256().equals(requestDigest)) {
                    throw new ReleaseWorkflowException(
                        "Idempotency key is bound to a different deployment bundle"
                    );
                }
                return requireRelease(existing.get().releaseId());
            }

            HostSnapshot snapshot = Objects.requireNonNull(
                hostSnapshotProvider.snapshot(), "host snapshot"
            );
            String hostDigest = hostSnapshotDigest(snapshot);
            DeploymentBundleVerification verification = bundleVerifier.verify(
                storedBundle, snapshot, hostDigest
            );
            validateBundleVerification(
                storedBundle, snapshot, hostDigest, verification
            );
            MaterializedDeployment materialized = bundleMaterializer.materialize(
                storedBundle, verification
            );
            validateMaterializedDeployment(materialized, verification);

            DeploymentPlan plan = new DeploymentPlan(
                1,
                "DEPLOY_SIGNED_RELEASE",
                verification.releaseId(),
                verification.releasePackageSha256(),
                verification.manifestSha256(),
                hostDigest,
                verification.nonce(),
                verification.issuedAt(),
                verification.expiresAt(),
                verification.requestedBy(),
                verification.deploymentPlanSha256()
            );
            StoredPackage releasePackage = materialized.releasePackage();
            ReleaseRecord record = new ReleaseRecord(
                verification.releaseId(),
                verification.productVersion(),
                verification.databaseSchemaVersion(),
                releasePackage.originalFileName(),
                releasePackage.path(),
                releasePackage.size(),
                releasePackage.sha256(),
                verification.manifestSha256(),
                ReleaseState.AWAITING_CONFIRMATION,
                plan,
                new SignedDeploymentMaterial(
                    verification.approvalId(),
                    materialized.deploymentPlanPath(),
                    verification.deploymentPlanSha256(),
                    materialized.requesterSignaturePath(),
                    verification.requesterSignatureSha256(),
                    materialized.approverSignaturePath(),
                    verification.approverSignatureSha256()
                ),
                List.of(),
                null,
                validatedActor,
                clock.instant()
            );
            repository.saveAndBindIdempotency(
                record,
                "import-bundle",
                key,
                new IdempotencyBinding(requestDigest, record.releaseId())
            );
            return record;
        } finally {
            storage.discard(storedBundle);
        }
    }

    public synchronized ReleaseRecord importRelease(
        InputStream input,
        String originalFileName,
        long declaredSize,
        String actor,
        String idempotencyKey
    ) {
        String validatedActor = actor(actor);
        String key = idempotencyKey(idempotencyKey);
        StoredPackage stored = storage.store(input, originalFileName, declaredSize);
        try {
            String requestDigest = digest(String.join("\n",
                "IMPORT_RELEASE", validatedActor, stored.sha256(), Long.toString(stored.size())
            ));
            var existing = repository.findIdempotency("import", key);
            if (existing.isPresent()) {
                if (!existing.get().requestSha256().equals(requestDigest)) {
                    throw new ReleaseWorkflowException(
                        "Idempotency key is bound to a different import request"
                    );
                }
                ReleaseRecord replayed = requireRelease(existing.get().releaseId());
                storage.discard(stored);
                return replayed;
            }
            VerificationReport report = verifier.verify(stored);
            validateVerification(stored, report);
            String releaseId = releaseId(report.productVersion(), stored.sha256());
            ReleaseRecord record = new ReleaseRecord(
                releaseId,
                report.productVersion(),
                report.databaseSchemaVersion(),
                stored.originalFileName(),
                stored.path(),
                stored.size(),
                stored.sha256(),
                report.manifestSha256(),
                ReleaseState.VERIFIED,
                null,
                java.util.List.of(),
                null,
                validatedActor,
                clock.instant()
            );
            repository.saveAndBindIdempotency(
                record,
                "import",
                key,
                new IdempotencyBinding(requestDigest, releaseId)
            );
            return record;
        } catch (RuntimeException exception) {
            storage.discard(stored);
            throw exception;
        }
    }

    public synchronized DeploymentPlan createPlan(String releaseId, String actor) {
        ReleaseRecord record = requireRelease(releaseId);
        String validatedActor = actor(actor);
        if (record.plan() != null) {
            return record.plan();
        }
        if (record.state() != ReleaseState.VERIFIED) {
            throw new ReleaseWorkflowException("Only a verified release can be planned");
        }

        HostSnapshot snapshot = Objects.requireNonNull(
            hostSnapshotProvider.snapshot(), "host snapshot"
        );
        String hostDigest = hostSnapshotDigest(snapshot);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(PLAN_LIFETIME);
        String nonce = UUID.randomUUID().toString();
        String planCore = String.join("\n",
            "1",
            "DEPLOY_RELEASE",
            record.releaseId(),
            record.packageSha256(),
            record.manifestSha256(),
            hostDigest,
            nonce,
            issuedAt.toString(),
            expiresAt.toString(),
            validatedActor
        );
        DeploymentPlan plan = new DeploymentPlan(
            1,
            "DEPLOY_RELEASE",
            record.releaseId(),
            record.packageSha256(),
            record.manifestSha256(),
            hostDigest,
            nonce,
            issuedAt,
            expiresAt,
            validatedActor,
            digest(planCore)
        );
        repository.save(record.withPlan(plan));
        return plan;
    }

    public synchronized ConfirmationResult confirm(
        String releaseId,
        String expectedPlanSha256,
        String actor,
        String reason,
        String idempotencyKey
    ) {
        String validatedActor = actor(actor);
        String key = idempotencyKey(idempotencyKey);
        String normalizedPlanSha256 = sha256(expectedPlanSha256, "expected plan digest");
        String validatedReason = required(reason, "reason").trim();
        if (validatedReason.length() < 3 || validatedReason.length() > 500) {
            throw new ReleaseWorkflowException("Confirmation reason must contain 3 to 500 characters");
        }
        String requestDigest = digest(String.join("\n",
            "CONFIRM_DEPLOYMENT",
            required(releaseId, "releaseId"),
            normalizedPlanSha256,
            validatedActor,
            validatedReason
        ));
        var replay = repository.findIdempotency("confirm", key);
        if (replay.isPresent()) {
            if (!replay.get().requestSha256().equals(requestDigest)) {
                throw new ReleaseWorkflowException(
                    "Idempotency key is bound to a different confirmation request"
                );
            }
            return result(requireRelease(replay.get().releaseId()));
        }

        ReleaseRecord record = requireRelease(releaseId);
        DeploymentPlan plan = record.plan();
        if (plan == null || record.state() != ReleaseState.AWAITING_CONFIRMATION) {
            throw new ReleaseWorkflowException("Release is not awaiting confirmation");
        }
        if (!plan.planSha256().equals(normalizedPlanSha256)) {
            throw new ReleaseWorkflowException("Confirmation plan digest does not match");
        }
        if (!clock.instant().isBefore(plan.expiresAt())) {
            throw new ReleaseWorkflowException("Deployment plan has expired");
        }
        if (record.approvals().stream().anyMatch(item -> item.actor().equals(validatedActor))) {
            throw new ReleaseWorkflowException("Production approvals must use distinct actors");
        }
        int confirmationThreshold = confirmationThreshold(record);
        if (confirmationThreshold > 1 && (
            validatedActor.equals(plan.requestedBy()) || validatedActor.equals(record.importedBy())
        )) {
            throw new ReleaseWorkflowException(
                "The requesting operator cannot approve the same production deployment"
            );
        }

        ReleaseRecord approved = record.withApproval(
            new ReleaseApproval(validatedActor, validatedReason, clock.instant())
        );
        if (approved.approvals().size() >= confirmationThreshold) {
            String commandId = digest("DEPLOY_RELEASE\n" + plan.planSha256());
            String jobId = agent.enqueue(new DeployReleaseCommand(
                1,
                commandId,
                approved.releaseId(),
                approved.productVersion(),
                approved.databaseSchemaVersion(),
                approved.packagePath(),
                approved.packageSha256(),
                plan.manifestSha256(),
                plan.planSha256(),
                plan.hostSnapshotSha256(),
                plan.expiresAt(),
                approved.deploymentMaterial()
            ));
            if (jobId == null || jobId.isBlank()) {
                throw new ReleaseWorkflowException("ReleaseAgent returned no durable job identity");
            }
            approved = approved.queued(jobId);
        }
        repository.saveAndBindIdempotency(
            approved,
            "confirm",
            key,
            new IdempotencyBinding(requestDigest, approved.releaseId())
        );
        return result(approved);
    }

    public synchronized ReleaseRecord get(String releaseId) {
        return reconcileAgentResult(requireRelease(releaseId));
    }

    public synchronized List<ReleaseRecord> list() {
        return repository.findAll().stream().map(this::reconcileAgentResult).toList();
    }

    private ReleaseRecord reconcileAgentResult(ReleaseRecord record) {
        if (record.state() != ReleaseState.QUEUED) {
            return record;
        }
        String jobId = required(record.jobId(), "queued Agent job id");
        var result = agentResultReader.find(jobId);
        if (result.isEmpty()) {
            return record;
        }
        ReleaseAgentVerificationResult verified = result.get();
        DeploymentPlan plan = Objects.requireNonNull(record.plan(), "queued deployment plan");
        if (!jobId.equals(verified.commandId())
            || !record.releaseId().equals(verified.releaseId())
            || !record.productVersion().equals(verified.productVersion())
            || record.databaseSchemaVersion() != verified.databaseSchemaVersion()
            || !record.packageSha256().equals(verified.packageSha256())
            || !record.manifestSha256().equals(verified.manifestSha256())
            || !plan.planSha256().equals(verified.planSha256())
            || !plan.hostSnapshotSha256().equals(verified.hostSnapshotSha256())) {
            throw new ReleaseWorkflowException(
                "Release Agent verification result does not match the queued release"
            );
        }
        boolean verifyOnly = verified.schemaVersion() == 1
            && "VERIFIED_ONLY".equals(verified.status())
            && !verified.productionExecutionEnabled()
            && verified.approvalId() == null
            && verified.deploymentStatus() == null
            && verified.deploymentReportSha256() == null;
        SignedDeploymentMaterial material = record.deploymentMaterial();
        boolean deployed = verified.schemaVersion() == 2
            && "DEPLOYED".equals(verified.status())
            && verified.productionExecutionEnabled()
            && "DEPLOY_SIGNED_RELEASE".equals(plan.action())
            && material != null
            && material.approvalId().equals(verified.approvalId())
            && ("SUCCEEDED".equals(verified.deploymentStatus())
                || "ALREADY_SUCCEEDED".equals(verified.deploymentStatus()))
            && verified.deploymentReportSha256() != null
            && verified.deploymentReportSha256().matches("^[a-f0-9]{64}$");
        if (!verifyOnly && !deployed) {
            throw new ReleaseWorkflowException(
                "Release Agent result mode does not match the queued release"
            );
        }
        ReleaseRecord reconciled = deployed ? record.deployed() : record.agentVerified();
        repository.save(reconciled);
        return reconciled;
    }

    private ConfirmationResult result(ReleaseRecord record) {
        return new ConfirmationResult(
            record.releaseId(),
            record.state(),
            record.approvals().size(),
            confirmationThreshold(record),
            record.jobId()
        );
    }

    private int confirmationThreshold(ReleaseRecord record) {
        DeploymentPlan plan = record.plan();
        if (plan != null && "DEPLOY_SIGNED_RELEASE".equals(plan.action())) {
            return 1;
        }
        return requiredApprovals;
    }

    private static String hostSnapshotDigest(HostSnapshot snapshot) {
        return digest(String.join("\n",
            required(snapshot.environmentId(), "environmentId"),
            required(snapshot.hostId(), "hostId"),
            required(snapshot.currentReleaseId(), "currentReleaseId"),
            sha256(snapshot.currentPackageSha256(), "currentPackageSha256")
        ));
    }

    private void validateBundleVerification(
        StoredPackage storedBundle,
        HostSnapshot snapshot,
        String hostDigest,
        DeploymentBundleVerification verification
    ) {
        if (verification == null || !verification.valid()) {
            throw new ReleaseWorkflowException("Deployment bundle verification failed");
        }
        if (!storedBundle.sha256().equals(
            sha256(verification.bundleSha256(), "verified bundle digest")
        )) {
            throw new ReleaseWorkflowException(
                "Deployment bundle digest differs from stored bytes"
            );
        }
        if (!required(snapshot.environmentId(), "environmentId").equals(
            required(verification.environmentId(), "verified environmentId")
        ) || !required(snapshot.hostId(), "hostId").equals(
            required(verification.hostId(), "verified hostId")
        ) || !hostDigest.equals(
            sha256(verification.hostSnapshotSha256(), "verified host snapshot digest")
        )) {
            throw new ReleaseWorkflowException(
                "Deployment bundle is bound to a different host snapshot"
            );
        }
        String packageSha256 = sha256(
            verification.releasePackageSha256(), "verified release package digest"
        );
        String productVersion = required(
            verification.productVersion(), "verified productVersion"
        );
        if (!productVersion.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")) {
            throw new ReleaseWorkflowException(
                "Verified deployment bundle product version is invalid"
            );
        }
        String verifiedReleaseId = required(
            verification.releaseId(), "verified releaseId"
        );
        if (!RELEASE_ID.matcher(verifiedReleaseId).matches()) {
            throw new ReleaseWorkflowException(
                "Deployment bundle release identity is invalid"
            );
        }
        if (verification.databaseSchemaVersion() < 1
            || verification.releasePackageBytes() < 1) {
            throw new ReleaseWorkflowException(
                "Deployment bundle version or package size is invalid"
            );
        }
        sha256(verification.manifestSha256(), "verified manifest digest");
        sha256(verification.deploymentPlanSha256(), "verified deployment plan digest");
        String nonce = required(verification.nonce(), "verified deployment nonce");
        if (!nonce.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$")) {
            throw new ReleaseWorkflowException("Verified deployment nonce is invalid");
        }
        actor(verification.requestedBy());
        actor(verification.approvedBy());
        if (verification.requestedBy().equals(verification.approvedBy())) {
            throw new ReleaseWorkflowException(
                "Deployment bundle cryptographic approvers must be distinct"
            );
        }
        Instant now = clock.instant();
        Instant issuedAt = Objects.requireNonNull(verification.issuedAt(), "issuedAt");
        Instant expiresAt = Objects.requireNonNull(verification.expiresAt(), "expiresAt");
        if (issuedAt.isAfter(now.plus(Duration.ofMinutes(5)))
            || !now.isBefore(expiresAt)
            || !issuedAt.isBefore(expiresAt)
            || expiresAt.isAfter(issuedAt.plus(Duration.ofHours(24)))) {
            throw new ReleaseWorkflowException(
                "Deployment bundle approval window is invalid"
            );
        }
    }

    private static void validateMaterializedDeployment(
        MaterializedDeployment materialized,
        DeploymentBundleVerification verification
    ) {
        if (materialized == null || materialized.releasePackage() == null
            || materialized.deploymentPlanPath() == null
            || materialized.requesterSignaturePath() == null
            || materialized.approverSignaturePath() == null) {
            throw new ReleaseWorkflowException(
                "Deployment bundle materialization is incomplete"
            );
        }
        StoredPackage releasePackage = materialized.releasePackage();
        String expectedDigest = sha256(
            verification.releasePackageSha256(), "materialized package digest"
        );
        if (releasePackage.size() != verification.releasePackageBytes()
            || !expectedDigest.equals(
                sha256(releasePackage.sha256(), "stored materialized package digest")
            )
            || releasePackage.size() != fileSize(releasePackage.path())
            || !expectedDigest.equals(digest(releasePackage.path()))) {
            throw new ReleaseWorkflowException(
                "Materialized release package differs from verified bytes"
            );
        }
    }

    private ReleaseRecord requireRelease(String releaseId) {
        return repository.find(required(releaseId, "releaseId"))
            .orElseThrow(() -> new ReleaseWorkflowException("Release was not found"));
    }

    private static void validateVerification(StoredPackage stored, VerificationReport report) {
        if (report == null || !report.valid()) {
            throw new ReleaseWorkflowException("Release package verification failed");
        }
        if (!stored.sha256().equals(sha256(report.packageSha256(), "verified package digest"))) {
            throw new ReleaseWorkflowException("Verifier package digest differs from stored bytes");
        }
        sha256(report.manifestSha256(), "manifest digest");
        if (report.databaseSchemaVersion() < 1) {
            throw new ReleaseWorkflowException("Verified database schema version is invalid");
        }
        String productVersion = required(report.productVersion(), "productVersion");
        if (!productVersion.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")) {
            throw new ReleaseWorkflowException("Verified product version is invalid");
        }
    }

    private static String releaseId(String productVersion, String packageSha256) {
        return productVersion.toLowerCase(Locale.ROOT) + '-' + packageSha256.substring(0, 12);
    }

    private static String actor(String value) {
        String actor = required(value, "actor");
        if (!ACTOR.matcher(actor).matches()) {
            throw new ReleaseWorkflowException("Actor identity is invalid");
        }
        return actor;
    }

    private static String idempotencyKey(String value) {
        String key = required(value, "idempotency key");
        if (!key.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")) {
            throw new ReleaseWorkflowException("Idempotency key is invalid");
        }
        return key;
    }

    private static String sha256(String value, String label) {
        String normalized = required(value, label).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new ReleaseWorkflowException(label + " is invalid");
        }
        return normalized;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ReleaseWorkflowException(label + " is required");
        }
        return value;
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String digest(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                return HexFormat.of().formatHex(digest.digest());
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
            }
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to hash materialized release package", exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to read materialized release package size", exception
            );
        }
    }
}
