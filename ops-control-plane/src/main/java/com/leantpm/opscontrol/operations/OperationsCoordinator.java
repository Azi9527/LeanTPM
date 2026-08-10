package com.leantpm.opscontrol.operations;

import com.leantpm.opscontrol.notification.NotificationPublisher;
import com.leantpm.opscontrol.notification.NotificationSeverity;
import com.leantpm.opscontrol.notification.OpsNotification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OperationsCoordinator implements OperationsStatusService {

    private static final Duration ATTEMPT_WINDOW = Duration.ofHours(1);
    private static final int MAX_RECENT_REMEDIATIONS = 20;

    private final OperationsMonitor monitor;
    private final RemediationExecutor remediationExecutor;
    private final NotificationPublisher notifications;
    private final ReleaseTerminalSource releases;
    private final OperationsStateStore stateStore;
    private final RemediationPolicy policy;
    private final Clock clock;
    private OperationsRuntimeState state;
    private OperationsSnapshot latest;

    public OperationsCoordinator(
        OperationsMonitor monitor,
        RemediationExecutor remediationExecutor,
        NotificationPublisher notifications,
        ReleaseTerminalSource releases,
        OperationsStateStore stateStore,
        RemediationPolicy policy,
        Clock clock
    ) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.remediationExecutor = Objects.requireNonNull(remediationExecutor, "remediationExecutor");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = Objects.requireNonNullElse(stateStore.load(), OperationsRuntimeState.empty());
    }

    @Override
    public synchronized OperationsDashboard status() {
        if (latest == null) {
            return refresh();
        }
        return dashboard(latest);
    }

    @Override
    public synchronized OperationsDashboard refresh() {
        Instant now = clock.instant();
        if (!monitor.enabled()) {
            latest = new OperationsSnapshot(
                false, OperationsHealth.DISABLED, now, List.of(), state.recentRemediations()
            );
            return dashboard(latest);
        }

        List<OperationsComponent> components = List.copyOf(monitor.observe(now));
        validateUniqueComponents(components);
        Map<String, String> statuses = new HashMap<>(state.componentStatuses());
        Map<String, Integer> failures = new HashMap<>(state.consecutiveFailures());
        Map<String, Instant> lastRemediation = new HashMap<>(state.lastRemediationAt());
        Map<String, List<Instant>> attempts = mutableAttempts(state.remediationAttempts(), now);
        Map<String, String> releaseStatuses = new HashMap<>(state.releaseStatuses());
        List<RemediationEvent> events = new ArrayList<>(state.recentRemediations());

        for (OperationsComponent component : components) {
            String previous = statuses.get(component.id());
            boolean unhealthy = isUnhealthy(component.status());
            boolean previouslyUnhealthy = isUnhealthy(previous);
            int consecutive = unhealthy
                ? failures.getOrDefault(component.id(), 0) + 1
                : 0;
            failures.put(component.id(), consecutive);

            if (unhealthy && !previouslyUnhealthy) {
                publish(incidentOpened(component, policy.enabled(), now));
            } else if (!unhealthy && previouslyUnhealthy
                && component.status() == OperationsHealth.HEALTHY) {
                publish(incidentRecovered(component, now));
            }

            statuses.put(component.id(), component.status().name());

            if (component.status() == OperationsHealth.DOWN
                && component.remediationAction() != null
                && eligible(component.id(), consecutive, attempts, lastRemediation, now)) {
                attempts.computeIfAbsent(component.id(), ignored -> new ArrayList<>()).add(now);
                lastRemediation.put(component.id(), now);
                persistState(new OperationsRuntimeState(
                    1,
                    statuses,
                    failures,
                    lastRemediation,
                    attempts,
                    releaseStatuses,
                    events
                ));
                publish(remediationStarted(component, now));
                RemediationResult result;
                try {
                    result = remediationExecutor.execute(component.remediationAction());
                    if (result == null) {
                        result = new RemediationResult(false, "固定修复动作未返回结果");
                    }
                } catch (RuntimeException exception) {
                    result = new RemediationResult(false, "固定修复动作执行异常");
                }
                RemediationEvent event = new RemediationEvent(
                    UUID.randomUUID().toString(),
                    component.id(),
                    component.remediationAction(),
                    result.succeeded() ? RemediationOutcome.SUCCEEDED : RemediationOutcome.FAILED,
                    now,
                    safeSummary(result.summary(), result.succeeded()
                        ? "固定服务启动命令已完成"
                        : "固定服务启动命令失败")
                );
                events.add(event);
                trimEvents(events);
                publish(remediationFinished(component, event, now));
            }
        }

        for (ReleaseTerminal release : releases.terminalReleases()) {
            String previous = releaseStatuses.put(release.releaseId(), release.state());
            if (!Objects.equals(previous, release.state())) {
                publish(releaseNotification(release, now));
            }
        }

        persistState(new OperationsRuntimeState(
            1,
            statuses,
            failures,
            lastRemediation,
            attempts,
            releaseStatuses,
            events
        ));
        latest = new OperationsSnapshot(
            true,
            overall(components),
            now,
            components,
            events
        );
        return dashboard(latest);
    }

    private void persistState(OperationsRuntimeState next) {
        stateStore.save(next);
        state = next;
    }

    private void publish(OpsNotification notification) {
        try {
            notifications.publish(notification);
        } catch (RuntimeException ignored) {
            // Monitoring and bounded remediation remain authoritative when delivery fails.
        }
    }

    private OperationsDashboard dashboard(OperationsSnapshot snapshot) {
        return new OperationsDashboard(snapshot, notifications.status());
    }

    private boolean eligible(
        String componentId,
        int consecutiveFailures,
        Map<String, List<Instant>> attempts,
        Map<String, Instant> lastRemediation,
        Instant now
    ) {
        if (!policy.enabled() || consecutiveFailures < policy.failureThreshold()) {
            return false;
        }
        Instant last = lastRemediation.get(componentId);
        if (last != null && Duration.between(last, now).compareTo(policy.cooldown()) < 0) {
            return false;
        }
        return attempts.getOrDefault(componentId, List.of()).size()
            < policy.maximumAttemptsPerHour();
    }

    private static Map<String, List<Instant>> mutableAttempts(
        Map<String, List<Instant>> existing,
        Instant now
    ) {
        Instant cutoff = now.minus(ATTEMPT_WINDOW);
        Map<String, List<Instant>> result = new HashMap<>();
        existing.forEach((component, values) -> result.put(
            component,
            new ArrayList<>(values.stream().filter(value -> !value.isBefore(cutoff)).toList())
        ));
        return result;
    }

    private static void validateUniqueComponents(List<OperationsComponent> components) {
        long distinct = components.stream().map(OperationsComponent::id).distinct().count();
        if (distinct != components.size()) {
            throw new IllegalStateException("Operations monitor returned duplicate component ids");
        }
    }

    private static OperationsHealth overall(List<OperationsComponent> components) {
        if (components.stream().anyMatch(value -> value.status() == OperationsHealth.DOWN)) {
            return OperationsHealth.DOWN;
        }
        if (components.stream().anyMatch(value -> value.status() == OperationsHealth.DEGRADED)) {
            return OperationsHealth.DEGRADED;
        }
        if (components.isEmpty() || components.stream().allMatch(value ->
            value.status() == OperationsHealth.UNKNOWN
                || value.status() == OperationsHealth.DISABLED)) {
            return OperationsHealth.UNKNOWN;
        }
        return OperationsHealth.HEALTHY;
    }

    private static boolean isUnhealthy(OperationsHealth status) {
        return status == OperationsHealth.DOWN || status == OperationsHealth.DEGRADED;
    }

    private static boolean isUnhealthy(String status) {
        return "DOWN".equals(status) || "DEGRADED".equals(status);
    }

    private static void trimEvents(List<RemediationEvent> events) {
        while (events.size() > MAX_RECENT_REMEDIATIONS) {
            events.removeFirst();
        }
    }

    private static String safeSummary(String summary, String fallback) {
        if (summary == null || summary.isBlank()) {
            return fallback;
        }
        String normalized = summary.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "…";
    }

    private static OpsNotification incidentOpened(
        OperationsComponent component,
        boolean remediationEnabled,
        Instant now
    ) {
        return new OpsNotification(
            "INCIDENT_OPENED",
            component.status() == OperationsHealth.DOWN
                ? NotificationSeverity.CRITICAL
                : NotificationSeverity.WARNING,
            "LeanTPM " + component.label() + " 异常",
            impact(component),
            remediationEnabled && component.remediationAction() != null
                ? "达到连续失败阈值后，将尝试固定动作 " + component.remediationAction()
                : "自动修复未启用或该组件仅支持人工处理",
            component.summary(),
            "打开运维控制台核对组件和修复记录；若未恢复，请通过 RDP 处理",
            now,
            "component:" + component.id() + ":" + component.status()
        );
    }

    private static OpsNotification incidentRecovered(
        OperationsComponent component,
        Instant now
    ) {
        return new OpsNotification(
            "INCIDENT_RECOVERED",
            NotificationSeverity.INFO,
            "LeanTPM " + component.label() + " 已恢复",
            "相关功能恢复可用",
            "无需继续自动处理",
            component.summary(),
            "观察后续监控；若反复异常，请检查服务和日志",
            now,
            "component:" + component.id() + ":recovered"
        );
    }

    private static OpsNotification remediationStarted(
        OperationsComponent component,
        Instant now
    ) {
        return new OpsNotification(
            "REMEDIATION_STARTED",
            NotificationSeverity.WARNING,
            "LeanTPM 正在自动修复 " + component.label(),
            impact(component),
            "执行固定白名单动作 " + component.remediationAction(),
            component.summary(),
            "等待下一条修复结果消息，无需重复操作",
            now,
            "remediation:" + component.id() + ":started:" + now
        );
    }

    private static OpsNotification remediationFinished(
        OperationsComponent component,
        RemediationEvent event,
        Instant now
    ) {
        boolean succeeded = event.outcome() == RemediationOutcome.SUCCEEDED;
        return new OpsNotification(
            succeeded ? "REMEDIATION_SUCCEEDED" : "REMEDIATION_FAILED",
            succeeded ? NotificationSeverity.INFO : NotificationSeverity.CRITICAL,
            "LeanTPM 自动修复" + (succeeded ? "已完成 " : "失败 ") + component.label(),
            impact(component),
            event.action() + " · " + event.summary(),
            succeeded ? "已执行固定启动动作，等待下一轮健康复核" : "修复动作未成功",
            succeeded ? "观察下一轮监控" : "请通过 RDP 检查服务账户和服务日志",
            now,
            "remediation:" + event.eventId()
        );
    }

    private static OpsNotification releaseNotification(ReleaseTerminal release, Instant now) {
        boolean deployed = "DEPLOYED".equals(release.state());
        return new OpsNotification(
            deployed ? "RELEASE_DEPLOYED" : "RELEASE_FAILED",
            deployed ? NotificationSeverity.INFO : NotificationSeverity.CRITICAL,
            "LeanTPM " + release.productVersion() + (deployed ? " 发布成功" : " 发布失败"),
            deployed ? "PC/API 已切换到新版本" : "发布未完成，现有服务状态需核对",
            "发布由受限 ReleaseAgent 执行；通知侧未执行额外动作",
            release.releaseId() + " · " + release.state(),
            deployed ? "完成业务冒烟检查" : "查看发布审计并按恢复标记处理",
            now,
            "release:" + release.releaseId() + ":" + release.state()
        );
    }

    private static String impact(OperationsComponent component) {
        return switch (component.kind()) {
            case SERVER -> "服务器资源可能影响全部 LeanTPM 功能";
            case SERVICE -> "相关 PC/API、代理或公网入口可能不可用";
            case DATABASE -> "登录和业务读写可能失败";
            case LOG -> "检测到近期错误或日志采集异常";
        };
    }
}
