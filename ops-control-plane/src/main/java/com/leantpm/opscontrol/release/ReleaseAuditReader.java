package com.leantpm.opscontrol.release;

public interface ReleaseAuditReader {
    ReleaseAuditPage audit(long afterSequence, int limit);
}
