package com.leantpm.opscontrol.release;

record ReleaseProcessResult(
    int exitCode,
    String standardOutput,
    String standardError,
    boolean timedOut,
    boolean outputLimitExceeded
) {
}
