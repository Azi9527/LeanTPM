package com.leantpm.opscontrol.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class WindowsScServiceOperations implements RemediationExecutor {

    private static final Pattern RUNNING = Pattern.compile("(?m)STATE\\s*:\\s*4\\b");
    private static final Pattern STOPPED = Pattern.compile("(?m)STATE\\s*:\\s*1\\b");

    private final Path scExecutable;
    private final Duration timeout;
    private final FixedCommandRunner runner;

    public WindowsScServiceOperations(Path scExecutable, Duration timeout, FixedCommandRunner runner) {
        this.scExecutable = Objects.requireNonNull(scExecutable, "scExecutable")
            .toAbsolutePath().normalize();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.runner = Objects.requireNonNull(runner, "runner");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("service command timeout must be between 1ms and 30s");
        }
    }

    public ServiceObservation query(ServiceTarget target) {
        Objects.requireNonNull(target, "target");
        FixedCommandResult result = runner.run(
            List.of(scExecutable.toString(), "query", target.scmName()),
            timeout
        );
        String output = result.output();
        if (result.exitCode() == 0 && RUNNING.matcher(output).find()) {
            return new ServiceObservation(target, OperationsHealth.HEALTHY, "服务正在运行");
        }
        if (STOPPED.matcher(output).find()) {
            return new ServiceObservation(target, OperationsHealth.DOWN, "服务已停止");
        }
        if (result.exitCode() == 1060 || output.contains("1060")) {
            return new ServiceObservation(target, OperationsHealth.DOWN, "服务未安装");
        }
        return new ServiceObservation(target, OperationsHealth.UNKNOWN, "无法确认服务状态");
    }

    public RemediationResult start(ServiceTarget target) {
        Objects.requireNonNull(target, "target");
        FixedCommandResult start = runner.run(
            List.of(scExecutable.toString(), "start", target.scmName()),
            timeout
        );
        if (start.exitCode() != 0 && !start.output().contains("1056")) {
            return new RemediationResult(false, "固定服务启动命令未成功");
        }
        ServiceObservation observation = query(target);
        return observation.status() == OperationsHealth.HEALTHY
            ? new RemediationResult(true, "固定服务已启动并确认运行")
            : new RemediationResult(false, "固定服务启动后仍未确认运行");
    }

    @Override
    public RemediationResult execute(RemediationAction action) {
        return start(ServiceTarget.forAction(action));
    }

    public static WindowsScServiceOperations systemDefault(Duration timeout) {
        Path windows = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"));
        Path sc = windows.resolve("System32").resolve("sc.exe").toAbsolutePath().normalize();
        if (!Files.isRegularFile(sc)) {
            throw new IllegalStateException("Windows service controller is unavailable");
        }
        return new WindowsScServiceOperations(sc, timeout, new BoundedSystemCommandRunner());
    }
}
