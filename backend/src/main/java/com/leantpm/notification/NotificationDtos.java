package com.leantpm.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record RuleRow(
            long id,
            String ruleCode,
            String ruleName,
            String businessType,
            String triggerType,
            int advanceMinutes,
            int repeatMinutes,
            int escalationLevel,
            String recipientType,
            String severity,
            List<String> channels,
            boolean acknowledgeRequired,
            boolean enabled,
            int version
    ) {
    }

    public record SaveRuleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String ruleCode,
            @NotBlank @Size(max = 120) String ruleName,
            @NotBlank @Pattern(regexp = "^(INSPECTION|MAINTENANCE)$") String businessType,
            @NotBlank @Pattern(regexp = "^(DUE_SOON|MANUAL_CREATED|OVERDUE)$") String triggerType,
            @NotNull @Min(0) @Max(525600) Integer advanceMinutes,
            @NotNull @Min(0) @Max(525600) Integer repeatMinutes,
            @NotNull @Min(0) @Max(9) Integer escalationLevel,
            @NotBlank @Pattern(regexp = "^(ASSIGNEE|TEAM_LEADER|WORKSHOP_MANAGER)$")
            String recipientType,
            @NotBlank @Pattern(regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$") String severity,
            @NotEmpty List<@Pattern(regexp = "^(SYSTEM|ANDROID|SMS|WECHAT|EMAIL)$") String> channels,
            boolean acknowledgeRequired,
            boolean enabled,
            @NotNull @Min(0) Integer version
    ) {
    }

    public record MessageRow(
            long id,
            String messageType,
            String severity,
            String title,
            String content,
            String businessType,
            long businessId,
            String businessCode,
            String routePath,
            boolean acknowledgeRequired,
            LocalDateTime readTime,
            LocalDateTime acknowledgedTime,
            LocalDateTime occurredTime
    ) {
    }

    public record DeliveryRow(
            long id,
            long messageId,
            String recipientName,
            String title,
            String channelCode,
            String deliveryStatus,
            LocalDateTime sentTime,
            String failureReason,
            int retryCount,
            LocalDateTime nextRetryTime,
            LocalDateTime createdTime
    ) {
    }

    public record ScanResult(
            int scannedTasks,
            int createdMessages,
            int duplicateMessages,
            int missingRecipients,
            int stoppedEscalations
    ) {
        public ScanResult plus(ScanResult other) {
            return new ScanResult(
                    scannedTasks + other.scannedTasks,
                    createdMessages + other.createdMessages,
                    duplicateMessages + other.duplicateMessages,
                    missingRecipients + other.missingRecipients,
                    stoppedEscalations + other.stoppedEscalations
            );
        }
    }

    record TaskCandidate(
            long id,
            String taskCode,
            long organizationId,
            String teamCode,
            Long assigneeUserId,
            String sourceType,
            String taskStatus,
            LocalDateTime dueTime,
            String equipmentName
    ) {
    }
}
