package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.release.ReleaseAuditPage;
import com.leantpm.opscontrol.release.ReleaseAuditReader;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditApiController {

    private final ReleaseAuditReader auditReader;

    public AuditApiController(ReleaseAuditReader auditReader) {
        this.auditReader = Objects.requireNonNull(auditReader, "auditReader");
    }

    @GetMapping
    public ReleaseAuditPage audit(
        @RequestParam(defaultValue = "0") long after,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return auditReader.audit(after, limit);
    }
}
