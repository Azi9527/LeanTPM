package com.leantpm.opscontrol.operations;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SystemResourceProbe implements OperationsProbe {

    private final Path diskPath;
    private final int degradedPercent;
    private final int downPercent;

    public SystemResourceProbe(Path diskPath, int degradedPercent, int downPercent) {
        this.diskPath = Objects.requireNonNull(diskPath, "diskPath").toAbsolutePath().normalize();
        if (!Files.exists(this.diskPath, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(this.diskPath)) {
            throw new IllegalArgumentException("resource monitoring path must exist and not be a link");
        }
        if (degradedPercent < 1 || downPercent > 100 || degradedPercent >= downPercent) {
            throw new IllegalArgumentException("disk thresholds are invalid");
        }
        this.degradedPercent = degradedPercent;
        this.downPercent = downPercent;
    }

    @Override
    public String id() {
        return "server";
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        try {
            var store = Files.getFileStore(diskPath);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            int diskUsed = total <= 0 ? 100 : (int) Math.round((total - usable) * 100D / total);
            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            long heapMax = runtime.maxMemory();
            int heapUsedPercent = heapMax <= 0 ? 0 : (int) Math.round(heapUsed * 100D / heapMax);
            OperatingSystemMXBean operatingSystem = operatingSystem();
            String cpuUsed = percentage(operatingSystem.getCpuLoad());
            long totalMemory = operatingSystem.getTotalMemorySize();
            long freeMemory = operatingSystem.getFreeMemorySize();
            String memoryUsed = totalMemory <= 0
                ? "unknown"
                : percentage((totalMemory - freeMemory) / (double) totalMemory);
            OperationsHealth status = diskUsed >= downPercent
                ? OperationsHealth.DOWN
                : diskUsed >= degradedPercent
                    ? OperationsHealth.DEGRADED
                    : OperationsHealth.HEALTHY;
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("hostName", hostName());
            metrics.put("osName", operatingSystem.getName() + " " + operatingSystem.getVersion());
            metrics.put("osArchitecture", operatingSystem.getArch());
            metrics.put("cpuUsedPercent", cpuUsed);
            metrics.put("systemMemoryUsedPercent", memoryUsed);
            metrics.put("systemMemoryAvailableGiB", gibibytes(Math.max(freeMemory, 0)));
            metrics.put("diskUsedPercent", Integer.toString(diskUsed));
            metrics.put("diskUsableGiB", gibibytes(usable));
            metrics.put("jvmHeapUsedPercent", Integer.toString(heapUsedPercent));
            metrics.put("availableProcessors", Integer.toString(runtime.availableProcessors()));
            metrics.put(
                "runtimeUptimeSeconds",
                Long.toString(ManagementFactory.getRuntimeMXBean().getUptime() / 1000)
            );
            return List.of(new OperationsComponent(
                "server:resources", "服务器资源", OperationsComponentKind.SERVER,
                status,
                status == OperationsHealth.HEALTHY
                    ? "当前主机 CPU、内存、磁盘和运维控制台 JVM 资源正常"
                    : "当前主机资源使用率需要关注：CPU " + display(cpuUsed)
                        + "，内存 " + display(memoryUsed) + "，磁盘 " + diskUsed + "%",
                observedAt,
                metrics,
                null
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("server resource information cannot be read", exception);
        }
    }

    private static OperatingSystemMXBean operatingSystem() {
        var value = ManagementFactory.getOperatingSystemMXBean();
        if (!(value instanceof OperatingSystemMXBean supported)) {
            throw new IllegalStateException("host resource metrics are not supported by this JDK");
        }
        return supported;
    }

    private static String percentage(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0D) {
            return "unknown";
        }
        return Integer.toString((int) Math.round(Math.clamp(ratio, 0D, 1D) * 100D));
    }

    private static String display(String value) {
        return "unknown".equals(value) ? "等待有效采样" : value + "%";
    }

    private static String gibibytes(long bytes) {
        return Long.toString(bytes / 1024 / 1024 / 1024);
    }

    private static String hostName() {
        for (String variable : List.of("COMPUTERNAME", "HOSTNAME")) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        try {
            String value = InetAddress.getLocalHost().getHostName();
            return value == null || value.isBlank() ? "unknown" : value.trim();
        } catch (UnknownHostException ignored) {
            return "unknown";
        }
    }
}
