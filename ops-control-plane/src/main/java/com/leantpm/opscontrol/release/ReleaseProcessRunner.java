package com.leantpm.opscontrol.release;

import java.time.Duration;
import java.util.List;

interface ReleaseProcessRunner {
    ReleaseProcessResult execute(
        List<String> command,
        Duration timeout,
        int maximumOutputBytes
    );
}
