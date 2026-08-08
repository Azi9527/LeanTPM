package com.leantpm.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            @NotBlank @Pattern(
                    regexp = "^[A-Z][A-Z0-9_]{2,63}$",
                    message = "规则编码须以大写字母开头，只能包含大写字母、数字和下划线，长度为 3～64 位"
            ) String ruleCode,
            @NotBlank @Size(max = 120) String ruleName,
            @NotBlank @Pattern(
                    regexp = "^(INSPECTION|MAINTENANCE)$",
                    message = "业务类型只能选择点检或维保"
            ) String businessType,
            @NotBlank @Pattern(
                    regexp = "^(DUE_SOON|MANUAL_CREATED|OVERDUE)$",
                    message = "请选择系统支持的触发类型"
            ) String triggerType,
            @NotNull @Min(0) @Max(525600) Integer advanceMinutes,
            @NotNull @Min(0) @Max(525600) Integer repeatMinutes,
            @NotNull @Min(0) @Max(9) Integer escalationLevel,
            @NotBlank @Pattern(
                    regexp = "^(ASSIGNEE|TEAM_LEADER|WORKSHOP_MANAGER)$",
                    message = "接收人只能选择执行人、班组长或车间主任"
            )
            String recipientType,
            @NotBlank @Pattern(
                    regexp = "^(LOW|MEDIUM|HIGH|CRITICAL)$",
                    message = "请选择系统支持的提醒等级"
            ) String severity,
            @NotEmpty List<@Pattern(
                    regexp = "^(SYSTEM|ANDROID|SMS|WECHAT|EMAIL)$",
                    message = "请选择系统支持的发送渠道"
            ) String> channels,
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

    public record BusinessDetail(
            long messageId,
            String businessType,
            long businessId,
            String businessCode,
            String taskCode,
            String schemeName,
            String equipmentCode,
            String equipmentName,
            String organizationName,
            String locationName,
            LocalDate plannedDate,
            LocalDateTime dueTime,
            String taskStatus,
            String sourceType,
            String assigneeNames,
            LocalDateTime startedTime,
            LocalDateTime submittedTime,
            LocalDateTime completedTime,
            List<BusinessItemDetail> items,
            List<BusinessAttachmentDetail> attachments
    ) {
    }

    public record BusinessAttachmentDetail(
            long id,
            Long taskResultId,
            Long taskItemId,
            String itemName,
            String originalName,
            String contentType,
            String extension,
            long fileSize,
            String attachmentType,
            LocalDateTime createdTime
    ) {
    }

    public record BusinessItemDetail(
            long id,
            String itemCode,
            String itemName,
            String itemPart,
            String itemContent,
            String itemStandard,
            String resultType,
            String unit,
            String resultCode,
            BigDecimal numericValue,
            String textValue,
            String selectedValue,
            boolean abnormalFlag,
            String abnormalDescription,
            boolean skippedFlag,
            String skipReason,
            String executedByName,
            LocalDateTime executedTime
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
