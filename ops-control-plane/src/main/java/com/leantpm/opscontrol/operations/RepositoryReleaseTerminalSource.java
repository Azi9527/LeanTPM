package com.leantpm.opscontrol.operations;

import com.leantpm.opscontrol.release.ReleaseState;
import com.leantpm.opscontrol.release.ReleaseWorkflowService;
import java.util.List;
import java.util.Objects;

public final class RepositoryReleaseTerminalSource implements ReleaseTerminalSource {

    private final ReleaseWorkflowService releases;

    public RepositoryReleaseTerminalSource(ReleaseWorkflowService releases) {
        this.releases = Objects.requireNonNull(releases, "releases");
    }

    @Override
    public List<ReleaseTerminal> terminalReleases() {
        return releases.list().stream()
            .filter(record -> record.state() == ReleaseState.DEPLOYED
                || record.state() == ReleaseState.FAILED)
            .map(record -> new ReleaseTerminal(
                record.releaseId(), record.productVersion(), record.state().name()
            ))
            .toList();
    }
}
