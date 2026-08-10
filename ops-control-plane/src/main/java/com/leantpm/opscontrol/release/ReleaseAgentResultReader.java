package com.leantpm.opscontrol.release;

import java.util.Optional;

@FunctionalInterface
public interface ReleaseAgentResultReader {
    Optional<ReleaseAgentVerificationResult> find(String commandId);
}
