package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leantpm.opscontrol.notification.NotificationPublisher;
import com.leantpm.opscontrol.notification.OpsNotification;
import com.leantpm.opscontrol.notification.PushPlusDeliverySummary;
import com.leantpm.opscontrol.notification.PushPlusDispatchStatus;
import com.leantpm.opscontrol.notification.PushPlusNotificationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationsCoordinatorTest {

    @Test
    void disabledPolicyNeverExecutesAServiceAction() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T01:00:00Z"));
        RecordingExecutor executor = new RecordingExecutor();
        RecordingPublisher publisher = new RecordingPublisher();
        OperationsCoordinator coordinator = coordinator(
            stoppedBackend(),
            executor,
            publisher,
            new RemediationPolicy(false, 2, Duration.ofMinutes(10), 2),
            clock,
            new MemoryStateStore()
        );

        coordinator.refresh();
        coordinator.refresh();

        assertThat(executor.actions).isEmpty();
        assertThat(publisher.notifications)
            .extracting(OpsNotification::eventType)
            .containsExactly("INCIDENT_OPENED");
    }

    @Test
    void executesOnlyTheBoundActionAfterThresholdAndHonorsCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T01:00:00Z"));
        RecordingExecutor executor = new RecordingExecutor();
        MemoryStateStore store = new MemoryStateStore();
        OperationsCoordinator coordinator = coordinator(
            stoppedBackend(),
            executor,
            new RecordingPublisher(),
            new RemediationPolicy(true, 2, Duration.ofMinutes(10), 2),
            clock,
            store
        );

        OperationsSnapshot first = coordinator.refresh().snapshot();
        OperationsSnapshot second = coordinator.refresh().snapshot();
        OperationsSnapshot withinCooldown = coordinator.refresh().snapshot();

        assertThat(first.recentRemediations()).isEmpty();
        assertThat(second.recentRemediations()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(RemediationAction.START_BACKEND);
            assertThat(event.outcome()).isEqualTo(RemediationOutcome.SUCCEEDED);
        });
        assertThat(withinCooldown.recentRemediations()).hasSize(1);
        assertThat(executor.actions).containsExactly(RemediationAction.START_BACKEND);

        clock.advance(Duration.ofMinutes(11));
        OperationsCoordinator afterRestart = coordinator(
            stoppedBackend(),
            executor,
            new RecordingPublisher(),
            new RemediationPolicy(true, 2, Duration.ofMinutes(10), 2),
            clock,
            store
        );
        afterRestart.refresh();
        assertThat(executor.actions)
            .containsExactly(RemediationAction.START_BACKEND, RemediationAction.START_BACKEND);

        clock.advance(Duration.ofMinutes(11));
        afterRestart.refresh();
        assertThat(executor.actions).hasSize(2);
    }

    @Test
    void publishesRecoveryAndTerminalReleaseOnlyOnStateTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T01:00:00Z"));
        RecordingPublisher publisher = new RecordingPublisher();
        List<OperationsComponent> observed = new ArrayList<>(stoppedBackend());
        ReleaseTerminalSource releases = () -> List.of(
            new ReleaseTerminal("1.0.1-abcdef", "1.0.1", "DEPLOYED")
        );
        OperationsCoordinator coordinator = new OperationsCoordinator(
            now -> List.copyOf(observed),
            action -> new RemediationResult(true, "started"),
            publisher,
            releases,
            new MemoryStateStore(),
            new RemediationPolicy(false, 2, Duration.ofMinutes(10), 2),
            clock
        );

        coordinator.refresh();
        coordinator.refresh();
        observed.set(0, healthyBackend(clock.instant()));
        coordinator.refresh();

        assertThat(publisher.notifications)
            .extracting(OpsNotification::eventType)
            .containsExactly("INCIDENT_OPENED", "RELEASE_DEPLOYED", "INCIDENT_RECOVERED");
    }

    @Test
    void reservesAttemptBeforeExecutionSoFinalPersistenceFailureCannotResetLimits() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T01:00:00Z"));
        FailsSecondSaveStore store = new FailsSecondSaveStore();
        RecordingExecutor executor = new RecordingExecutor();
        OperationsCoordinator coordinator = coordinator(
            stoppedBackend(),
            executor,
            new RecordingPublisher(),
            new RemediationPolicy(true, 1, Duration.ofMinutes(10), 1),
            clock,
            store
        );

        assertThatThrownBy(coordinator::refresh)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated final save failure");
        assertThat(executor.actions).containsExactly(RemediationAction.START_BACKEND);
        assertThat(store.state.remediationAttempts().get("service:backend")).hasSize(1);

        OperationsCoordinator afterRestart = coordinator(
            stoppedBackend(),
            executor,
            new RecordingPublisher(),
            new RemediationPolicy(true, 1, Duration.ofMinutes(10), 1),
            clock,
            store
        );
        afterRestart.refresh();
        assertThat(executor.actions).containsExactly(RemediationAction.START_BACKEND);
    }

    private static OperationsCoordinator coordinator(
        List<OperationsComponent> components,
        RemediationExecutor executor,
        NotificationPublisher publisher,
        RemediationPolicy policy,
        Clock clock,
        OperationsStateStore store
    ) {
        return new OperationsCoordinator(
            now -> components,
            executor,
            publisher,
            List::of,
            store,
            policy,
            clock
        );
    }

    private static List<OperationsComponent> stoppedBackend() {
        return List.of(new OperationsComponent(
            "service:backend",
            "LeanTPM Backend",
            OperationsComponentKind.SERVICE,
            OperationsHealth.DOWN,
            "服务已停止",
            Instant.parse("2026-08-10T01:00:00Z"),
            Map.of("serviceName", "LeanTPM.Backend"),
            RemediationAction.START_BACKEND
        ));
    }

    private static OperationsComponent healthyBackend(Instant now) {
        return new OperationsComponent(
            "service:backend",
            "LeanTPM Backend",
            OperationsComponentKind.SERVICE,
            OperationsHealth.HEALTHY,
            "服务运行中",
            now,
            Map.of("serviceName", "LeanTPM.Backend"),
            RemediationAction.START_BACKEND
        );
    }

    private static final class RecordingExecutor implements RemediationExecutor {
        private final List<RemediationAction> actions = new ArrayList<>();

        @Override
        public RemediationResult execute(RemediationAction action) {
            actions.add(action);
            return new RemediationResult(true, "固定服务已启动");
        }
    }

    private static final class RecordingPublisher implements NotificationPublisher {
        private final List<OpsNotification> notifications = new ArrayList<>();

        @Override
        public PushPlusDeliverySummary publish(OpsNotification notification) {
            notifications.add(notification);
            return new PushPlusDeliverySummary(
                PushPlusDispatchStatus.ACCEPTED, 1, 1, 0, clockInstant(), "API 已接受"
            );
        }

        @Override
        public PushPlusNotificationStatus status() {
            return new PushPlusNotificationStatus(false, List.of(), null);
        }

        private static Instant clockInstant() {
            return Instant.parse("2026-08-10T01:00:00Z");
        }
    }

    private static final class MemoryStateStore implements OperationsStateStore {
        private OperationsRuntimeState state = OperationsRuntimeState.empty();

        @Override
        public OperationsRuntimeState load() {
            return state;
        }

        @Override
        public void save(OperationsRuntimeState state) {
            this.state = state;
        }
    }

    private static final class FailsSecondSaveStore implements OperationsStateStore {
        private OperationsRuntimeState state = OperationsRuntimeState.empty();
        private int saves;

        @Override
        public OperationsRuntimeState load() {
            return state;
        }

        @Override
        public void save(OperationsRuntimeState state) {
            saves++;
            if (saves == 2) {
                throw new IllegalStateException("simulated final save failure");
            }
            this.state = state;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
