package com.leantpm.opscontrol.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PushPlusNotifierTest {

    @Test
    void sendsReadableMarkdownToEveryEnabledRecipientAndReportsPartialAcceptance() {
        PushPlusProperties properties = new PushPlusProperties();
        properties.setEnabled(true);
        properties.setRecipients(List.of(
            recipient("owner", "项目负责人", "token-owner-1234567890"),
            recipient("ops", "值班运维", "token-ops-123456789012")
        ));
        List<PushPlusDeliveryRequest> requests = new ArrayList<>();
        PushPlusTransport transport = request -> {
            requests.add(request);
            return request.destinationId().equals("owner")
                ? new PushPlusTransportResult(true, "accepted-owner", "accepted")
                : new PushPlusTransportResult(false, null, "rejected");
        };
        PushPlusNotifier notifier = new PushPlusNotifier(
            properties,
            transport,
            new ReadableNotificationFormatter()
        );

        PushPlusDeliverySummary summary = notifier.publish(notification());

        assertThat(requests).hasSize(2);
        assertThat(requests).extracting(PushPlusDeliveryRequest::destinationId)
            .containsExactly("owner", "ops");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.template()).isEqualTo("markdown");
            assertThat(request.title()).contains("LeanTPM").contains("服务异常");
            assertThat(request.content())
                .contains("## 影响范围")
                .contains("## 自动处理")
                .contains("## 当前状态")
                .contains("## 建议操作")
                .contains("## 发生时间");
        });
        assertThat(summary.status()).isEqualTo(PushPlusDispatchStatus.PARTIAL);
        assertThat(summary.configuredRecipients()).isEqualTo(2);
        assertThat(summary.acceptedRecipients()).isEqualTo(1);
        assertThat(summary.failedRecipients()).isEqualTo(1);
        assertThat(summary.toString()).doesNotContain("token-owner").doesNotContain("token-ops");
        assertThat(notifier.status().recipients())
            .extracting(PushPlusRecipientView::name)
            .containsExactly("项目负责人", "值班运维");
        assertThat(notifier.status().toString()).doesNotContain("token-owner");
    }

    @Test
    void disabledNotificationsNeverCallTransport() {
        PushPlusProperties properties = new PushPlusProperties();
        properties.setEnabled(false);
        properties.setRecipients(List.of(
            recipient("owner", "项目负责人", "token-owner-1234567890")
        ));
        List<PushPlusDeliveryRequest> requests = new ArrayList<>();
        PushPlusNotifier notifier = new PushPlusNotifier(
            properties,
            request -> {
                requests.add(request);
                return new PushPlusTransportResult(true, "unexpected", "unexpected");
            },
            new ReadableNotificationFormatter()
        );

        PushPlusDeliverySummary summary = notifier.publish(notification());

        assertThat(summary.status()).isEqualTo(PushPlusDispatchStatus.DISABLED);
        assertThat(requests).isEmpty();
    }

    private static PushPlusProperties.Recipient recipient(
        String id,
        String name,
        String token
    ) {
        PushPlusProperties.Recipient recipient = new PushPlusProperties.Recipient();
        recipient.setId(id);
        recipient.setName(name);
        recipient.setToken(token);
        recipient.setChannel("wechat");
        recipient.setEnabled(true);
        return recipient;
    }

    private static OpsNotification notification() {
        return new OpsNotification(
            "INCIDENT_OPENED",
            NotificationSeverity.CRITICAL,
            "LeanTPM Backend 服务异常",
            "PC/API 登录暂时不可用",
            "达到阈值后自动启动固定 Backend 服务",
            "LeanTPM.Backend = STOPPED",
            "检查最近修复记录；若未恢复，请通过 RDP 检查服务日志",
            Instant.parse("2026-08-10T01:00:00Z"),
            "service:backend:down"
        );
    }
}
