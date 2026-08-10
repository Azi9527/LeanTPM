package com.leantpm.opscontrol.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.ops.notifications.pushplus")
public class PushPlusProperties {

    private boolean enabled;
    private boolean allowPaidChannels;
    private Duration timeout = Duration.ofSeconds(8);
    private List<Recipient> recipients = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowPaidChannels() {
        return allowPaidChannels;
    }

    public void setAllowPaidChannels(boolean allowPaidChannels) {
        this.allowPaidChannels = allowPaidChannels;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public List<Recipient> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<Recipient> recipients) {
        this.recipients = recipients == null ? new ArrayList<>() : new ArrayList<>(recipients);
    }

    public static class Recipient {
        private String id;
        private String name;
        private String token;
        private String channel = "wechat";
        private String topic;
        private String option;
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getOption() { return option; }
        public void setOption(String option) { this.option = option; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
