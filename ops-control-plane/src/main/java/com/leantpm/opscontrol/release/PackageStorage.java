package com.leantpm.opscontrol.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class PackageStorage {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Path root;
    private final long maximumBytes;

    public PackageStorage(Path root, long maximumBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    public StoredPackage store(InputStream input, String originalFileName, long declaredSize) {
        Objects.requireNonNull(input, "input");
        String safeOriginalName = safeFileName(originalFileName);
        if (!safeOriginalName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new ReleaseWorkflowException("Only ZIP release packages are accepted");
        }
        if (declaredSize > maximumBytes) {
            throw new ReleaseWorkflowException("Release package exceeds the configured size limit");
        }

        Path uploadDirectory = null;
        Path packagePath = null;
        try {
            Files.createDirectories(root);
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(realRoot) || !Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReleaseWorkflowException("Release upload root is not a regular directory");
            }

            uploadDirectory = realRoot.resolve(UUID.randomUUID().toString());
            Files.createDirectory(uploadDirectory);
            packagePath = uploadDirectory.resolve("package.zip");

            MessageDigest digest = sha256();
            long written = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (OutputStream output = Files.newOutputStream(
                packagePath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > maximumBytes) {
                        throw new ReleaseWorkflowException(
                            "Release package exceeds the configured size limit"
                        );
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            } finally {
                java.util.Arrays.fill(buffer, (byte) 0);
            }

            if (declaredSize >= 0 && declaredSize != written) {
                throw new ReleaseWorkflowException("Release package size changed during upload");
            }
            if (!packagePath.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRoot)) {
                throw new ReleaseWorkflowException("Stored package escaped the upload root");
            }
            return new StoredPackage(
                packagePath,
                safeOriginalName,
                written,
                HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException exception) {
            cleanup(packagePath, uploadDirectory);
            throw new ReleaseWorkflowException("Release package could not be stored", exception);
        } catch (RuntimeException exception) {
            cleanup(packagePath, uploadDirectory);
            throw exception;
        }
    }

    public void discard(StoredPackage storedPackage) {
        if (storedPackage == null) {
            return;
        }
        Path packagePath = storedPackage.path().toAbsolutePath().normalize();
        if (!packagePath.startsWith(root)) {
            throw new ReleaseWorkflowException("Refusing to discard a package outside the upload root");
        }
        cleanup(packagePath, packagePath.getParent());
    }

    private static String safeFileName(String value) {
        String normalized = Objects.requireNonNullElse(value, "release.zip").replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > 255 || name.indexOf('\0') >= 0) {
            throw new ReleaseWorkflowException("Release package file name is invalid");
        }
        return name;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void cleanup(Path packagePath, Path uploadDirectory) {
        try {
            if (packagePath != null) {
                Files.deleteIfExists(packagePath);
            }
        } catch (IOException ignored) {
            // The caller receives the original failure; startup cleanup audits leftovers.
        }
        try {
            if (uploadDirectory != null) {
                Files.deleteIfExists(uploadDirectory);
            }
        } catch (IOException ignored) {
            // The caller receives the original failure; startup cleanup audits leftovers.
        }
    }
}
