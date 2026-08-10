package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.release.ConfirmationResult;
import com.leantpm.opscontrol.release.DeploymentPlan;
import com.leantpm.opscontrol.release.ReleaseWorkflowException;
import com.leantpm.opscontrol.release.ReleaseWorkflowService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseApiController {

    private final ReleaseWorkflowService workflow;

    public ReleaseApiController(ReleaseWorkflowService workflow) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView importRelease(
        @RequestPart("package") MultipartFile releasePackage,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Principal principal
    ) {
        if (releasePackage.isEmpty()) {
            throw new ReleaseWorkflowException("Release package is empty");
        }
        try {
            return ReleaseView.from(workflow.importRelease(
                releasePackage.getInputStream(),
                releasePackage.getOriginalFilename(),
                releasePackage.getSize(),
                actor(principal),
                idempotencyKey
            ));
        } catch (IOException exception) {
            throw new ReleaseWorkflowException("Unable to read uploaded release package", exception);
        }
    }

    @PostMapping(path = "/import-bundle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView importDeploymentBundle(
        @RequestPart("bundle") MultipartFile deploymentBundle,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Principal principal
    ) {
        if (deploymentBundle.isEmpty()) {
            throw new ReleaseWorkflowException("Deployment bundle is empty");
        }
        try {
            return ReleaseView.from(workflow.importDeploymentBundle(
                deploymentBundle.getInputStream(),
                deploymentBundle.getOriginalFilename(),
                deploymentBundle.getSize(),
                actor(principal),
                idempotencyKey
            ));
        } catch (IOException exception) {
            throw new ReleaseWorkflowException(
                "Unable to read uploaded deployment bundle", exception
            );
        }
    }

    @PostMapping("/{releaseId}/plan")
    public DeploymentPlan createPlan(
        @PathVariable String releaseId,
        Principal principal
    ) {
        return workflow.createPlan(releaseId, actor(principal));
    }

    @PostMapping(path = "/{releaseId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfirmationResult confirm(
        @PathVariable String releaseId,
        @Valid @RequestBody ConfirmReleaseRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        Principal principal
    ) {
        return workflow.confirm(
            releaseId,
            request.expectedPlanSha256(),
            actor(principal),
            request.reason(),
            idempotencyKey
        );
    }

    @GetMapping
    public List<ReleaseView> list() {
        return workflow.list().stream().map(ReleaseView::from).toList();
    }

    @GetMapping("/{releaseId}")
    public ReleaseView get(@PathVariable String releaseId) {
        return ReleaseView.from(workflow.get(releaseId));
    }

    private static String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ReleaseWorkflowException("Authenticated operator identity is missing");
        }
        return principal.getName();
    }
}
