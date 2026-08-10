package com.leantpm.opscontrol.notification;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class PushPlusNotifier implements NotificationPublisher {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$");
    private static final Pattern TOKEN = Pattern.compile("^[^\\s]{16,256}$");
    private static final Set<String> CHANNELS = Set.of(
        "wechat", "app", "extension", "webhook", "clawbot", "cp", "mail", "sms", "voice"
    );
    private static final Set<String> PAID_CHANNELS = Set.of("sms", "voice");

    private final PushPlusProperties properties;
    private final PushPlusTransport transport;
    private final ReadableNotificationFormatter formatter;
    private final List<PushPlusProperties.Recipient> recipients;
    private final AtomicReference<PushPlusDeliverySummary> lastDispatch = new AtomicReference<>();

    public PushPlusNotifier(
        PushPlusProperties properties,
        PushPlusTransport transport,
        ReadableNotificationFormatter formatter
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.recipients = validate(properties);
    }

    @Override
    public PushPlusDeliverySummary publish(OpsNotification notification) {
        Objects.requireNonNull(notification, "notification");
        Instant now = Instant.now();
        if (!properties.isEnabled()) {
            return remember(new PushPlusDeliverySummary(
                PushPlusDispatchStatus.DISABLED, recipients.size(), 0, 0, now,
                "PushPlus 已关闭"
            ));
        }
        List<PushPlusProperties.Recipient> enabled = recipients.stream()
            .filter(PushPlusProperties.Recipient::isEnabled)
            .toList();
        if (enabled.isEmpty()) {
            return remember(new PushPlusDeliverySummary(
                PushPlusDispatchStatus.NOT_CONFIGURED, 0, 0, 0, now,
                "未配置启用的 PushPlus 接收方"
            ));
        }
        var formatted = formatter.format(notification);
        int accepted = 0;
        int failed = 0;
        for (PushPlusProperties.Recipient recipient : enabled) {
            try {
                PushPlusTransportResult result = transport.send(new PushPlusDeliveryRequest(
                    recipient.getId(),
                    recipient.getToken(),
                    formatted.title(),
                    formatted.content(),
                    "markdown",
                    normalizedChannel(recipient),
                    emptyToNull(recipient.getTopic()),
                    emptyToNull(recipient.getOption()),
                    notification.occurredAt().toEpochMilli()
                ));
                if (result != null && result.accepted()) {
                    accepted++;
                } else {
                    failed++;
                }
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        PushPlusDispatchStatus status = accepted == enabled.size()
            ? PushPlusDispatchStatus.ACCEPTED
            : accepted == 0
                ? PushPlusDispatchStatus.FAILED
                : PushPlusDispatchStatus.PARTIAL;
        return remember(new PushPlusDeliverySummary(
            status,
            enabled.size(),
            accepted,
            failed,
            now,
            status == PushPlusDispatchStatus.ACCEPTED
                ? "PushPlus API 已接受全部接收方请求"
                : status == PushPlusDispatchStatus.PARTIAL
                    ? "部分 PushPlus 请求未被 API 接受"
                    : "PushPlus 请求均未被 API 接受"
        ));
    }

    @Override
    public PushPlusNotificationStatus status() {
        return new PushPlusNotificationStatus(
            properties.isEnabled(),
            recipients.stream().map(recipient -> new PushPlusRecipientView(
                recipient.getId(),
                recipient.getName(),
                normalizedChannel(recipient),
                recipient.isEnabled()
            )).toList(),
            lastDispatch.get()
        );
    }

    private PushPlusDeliverySummary remember(PushPlusDeliverySummary summary) {
        lastDispatch.set(summary);
        return summary;
    }

    private static List<PushPlusProperties.Recipient> validate(PushPlusProperties properties) {
        if (properties.getTimeout() == null
            || properties.getTimeout().isNegative()
            || properties.getTimeout().isZero()
            || properties.getTimeout().compareTo(java.time.Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("PushPlus timeout must be between 1ms and 30s");
        }
        List<PushPlusProperties.Recipient> values = List.copyOf(properties.getRecipients());
        if (values.size() > 20) {
            throw new IllegalArgumentException("PushPlus supports at most 20 configured recipients");
        }
        Set<String> ids = new HashSet<>();
        for (PushPlusProperties.Recipient recipient : values) {
            if (recipient == null
                || recipient.getId() == null
                || !ID.matcher(recipient.getId()).matches()
                || !ids.add(recipient.getId())) {
                throw new IllegalArgumentException("PushPlus recipient id is invalid or duplicated");
            }
            if (recipient.getName() == null || recipient.getName().isBlank()
                || recipient.getName().length() > 80) {
                throw new IllegalArgumentException("PushPlus recipient name is invalid");
            }
            if (recipient.isEnabled() && (recipient.getToken() == null
                || !TOKEN.matcher(recipient.getToken()).matches())) {
                throw new IllegalArgumentException("Enabled PushPlus recipient token is invalid");
            }
            String channel = normalizedChannel(recipient);
            if (!CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("PushPlus recipient channel is unsupported");
            }
            if (PAID_CHANNELS.contains(channel) && !properties.isAllowPaidChannels()) {
                throw new IllegalArgumentException("Paid PushPlus channels require explicit opt-in");
            }
            boundedOptional(recipient.getTopic(), "topic");
            boundedOptional(recipient.getOption(), "option");
        }
        return values;
    }

    private static void boundedOptional(String value, String label) {
        if (value != null && value.length() > 128) {
            throw new IllegalArgumentException("PushPlus recipient " + label + " is too long");
        }
    }

    private static String normalizedChannel(PushPlusProperties.Recipient recipient) {
        String value = recipient.getChannel();
        return value == null || value.isBlank()
            ? "wechat"
            : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
