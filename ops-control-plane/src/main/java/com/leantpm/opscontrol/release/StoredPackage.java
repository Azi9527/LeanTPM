package com.leantpm.opscontrol.release;

import java.nio.file.Path;

public record StoredPackage(
    Path path,
    String originalFileName,
    long size,
    String sha256
) {
}
