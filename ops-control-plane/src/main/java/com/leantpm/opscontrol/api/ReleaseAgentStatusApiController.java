package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.release.ReleaseAgentStatus;
import com.leantpm.opscontrol.release.ReleaseAgentStatusReader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class ReleaseAgentStatusApiController {

    private final ReleaseAgentStatusReader statusReader;

    public ReleaseAgentStatusApiController(ReleaseAgentStatusReader statusReader) {
        this.statusReader = statusReader;
    }

    @GetMapping
    public ReleaseAgentStatus status() {
        return statusReader.status();
    }
}
