package com.leantpm.opscontrol.operations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FixedLogProbe implements OperationsProbe {

    private static final Pattern ERROR = Pattern.compile(
        "(?im)(?:^|\\s)(?:ERROR|FATAL)(?:\\s|:)|Exception(?:\\s|:)"
    );

    private final Path root;
    private final List<Path> files;
    private final int maximumTailBytes;

    public FixedLogProbe(Path root, List<Path> files, int maximumTailBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(this.root)) {
            throw new IllegalArgumentException("fixed log root must be a regular directory");
        }
        if (files == null || files.isEmpty() || files.size() > 20) {
            throw new IllegalArgumentException("one to twenty fixed log paths are required");
        }
        this.files = files.stream().map(this::validatedRelative).toList();
        if (maximumTailBytes < 4096 || maximumTailBytes > 1024 * 1024) {
            throw new IllegalArgumentException("log tail size must be between 4 KiB and 1 MiB");
        }
        this.maximumTailBytes = maximumTailBytes;
    }

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        int readable = 0;
        int missing = 0;
        int errorMatches = 0;
        for (Path relative : files) {
            Path path = root.resolve(relative).normalize();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                missing++;
                continue;
            }
            if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("fixed log path is not a regular file");
            }
            try {
                String tail = readTail(path);
                readable++;
                Matcher matcher = ERROR.matcher(tail);
                while (matcher.find()) {
                    errorMatches++;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("fixed log file cannot be read", exception);
            }
        }
        OperationsHealth status = errorMatches > 0 || missing > 0
            ? OperationsHealth.DEGRADED
            : OperationsHealth.HEALTHY;
        String summary = errorMatches > 0
            ? "固定日志尾部检测到 " + errorMatches + " 个错误标记（不展示原始日志）"
            : missing > 0
                ? "有 " + missing + " 个固定日志尚未生成"
                : "固定日志未检测到错误标记";
        return List.of(new OperationsComponent(
            "logs:fixed", "固定服务日志", OperationsComponentKind.LOG,
            status, summary, observedAt,
            Map.of(
                "configuredFiles", Integer.toString(files.size()),
                "readableFiles", Integer.toString(readable),
                "missingFiles", Integer.toString(missing),
                "errorMatches", Integer.toString(errorMatches)
            ),
            null
        ));
    }

    private Path validatedRelative(Path value) {
        if (value == null || value.isAbsolute()) {
            throw new IllegalArgumentException("fixed log path must be relative");
        }
        Path normalized = value.normalize();
        if (normalized.toString().isBlank() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("fixed log path escapes the configured root");
        }
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("fixed log path escapes the configured root");
        }
        return normalized;
    }

    private String readTail(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maximumTailBytes);
            channel.position(size - length);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // bounded read
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }
}
