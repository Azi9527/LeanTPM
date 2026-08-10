package com.leantpm.opscontrol.api;

import com.leantpm.opscontrol.operations.OperationsDashboard;
import com.leantpm.opscontrol.operations.OperationsStatusService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsApiController {

    private final OperationsStatusService operations;

    public OperationsApiController(OperationsStatusService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @GetMapping("/status")
    public OperationsDashboard status() {
        return operations.status();
    }

    @PostMapping("/refresh")
    public OperationsDashboard refresh() {
        return operations.refresh();
    }
}
