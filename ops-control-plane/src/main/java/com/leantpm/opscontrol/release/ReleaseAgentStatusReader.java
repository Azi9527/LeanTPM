package com.leantpm.opscontrol.release;

@FunctionalInterface
public interface ReleaseAgentStatusReader {

    ReleaseAgentStatus status();
}
