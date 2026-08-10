package com.leantpm.opscontrol.release;

public record VerificationReport(
    boolean valid,
    String productVersion,
    int databaseSchemaVersion,
    String manifestSha256,
    String packageSha256
) {
}
