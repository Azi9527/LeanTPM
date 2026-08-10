package com.leantpm.opscontrol.operations;

import java.util.List;

@FunctionalInterface
public interface ReleaseTerminalSource {
    List<ReleaseTerminal> terminalReleases();
}
