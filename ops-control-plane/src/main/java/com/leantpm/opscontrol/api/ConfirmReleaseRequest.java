package com.leantpm.opscontrol.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConfirmReleaseRequest(
    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    String expectedPlanSha256,
    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
