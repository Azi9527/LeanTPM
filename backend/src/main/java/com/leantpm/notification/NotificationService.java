package com.leantpm.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.attachment.AttachmentService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class NotificationService {
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETED", "CANCELLED", "VOIDED"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AttachmentService attachmentService;

    public NotificationService(
            JdbcTemplate jdbc, ObjectMapper objectMapper, AttachmentService attachmentService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.attachmentService = attachmentService;
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.RuleRow> rules() {
        return rules(SecurityUtils.currentUser().tenantId(), false);
    }

    @Transactional
    public long createRule(NotificationDtos.SaveRuleRequest request) {
        long tenantId = SecurityUtils.currentUser().tenantId();
        int changed;
        try {
            changed = jdbc.update("""
                    INSERT INTO notification_rule
                        (tenant_id, rule_code, rule_name, business_type, trigger_type,
                         advance_minutes, repeat_minutes, escalation_level, recipient_type,
                         severity, channels_json, acknowledge_required, enabled,
                         created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?)
                    """,
                    tenantId, request.ruleCode(), request.ruleName(), request.businessType(),
                    request.triggerType(), request.advanceMinutes(), request.repeatMinutes(),
                    request.escalationLevel(), request.recipientType(), request.severity(),
                    json(request.channels()), request.acknowledgeRequired(), request.enabled(),
                    SecurityUtils.currentUser().userId(), SecurityUtils.currentUser().userId()
            );
        } catch (RuntimeException exception) {
            throw new BusinessException("NOTIFICATION_RULE_DUPLICATE", "提醒规则编码已存在");
        }
        if (changed != 1) {
            throw new BusinessException("NOTIFICATION_RULE_CREATE_FAILED", "提醒规则创建失败");
        }
        return jdbc.queryForObject(
                "SELECT id FROM notification_rule WHERE tenant_id = ? AND rule_code = ?",
                Long.class, tenantId, request.ruleCode()
        );
    }

    @Transactional
    public void updateRule(long id, NotificationDtos.SaveRuleRequest request) {
        long tenantId = SecurityUtils.currentUser().tenantId();
        int changed = jdbc.update("""
                UPDATE notification_rule
                SET rule_name = ?, business_type = ?, trigger_type = ?,
                    advance_minutes = ?, repeat_minutes = ?, escalation_level = ?,
                    recipient_type = ?, severity = ?, channels_json = CAST(? AS JSON),
                    acknowledge_required = ?, enabled = ?, updated_by = ?,
                    version = version + 1
                WHERE tenant_id = ? AND id = ? AND rule_code = ?
                  AND version = ? AND deleted = 0
                """,
                request.ruleName(), request.businessType(), request.triggerType(),
                request.advanceMinutes(), request.repeatMinutes(), request.escalationLevel(),
                request.recipientType(), request.severity(), json(request.channels()),
                request.acknowledgeRequired(), request.enabled(),
                SecurityUtils.currentUser().userId(), tenantId, id, request.ruleCode(),
                request.version()
        );
        if (changed != 1) {
            throw new BusinessException(
                    "NOTIFICATION_RULE_CONFLICT", "提醒规则不存在或已被其他用户修改",
                    HttpStatus.CONFLICT
            );
        }
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationDtos.MessageRow> messages(
            boolean unreadOnly, int page, int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        String unread = unreadOnly ? " AND read_time IS NULL" : "";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_message
                WHERE tenant_id = ? AND recipient_user_id = ? AND deleted = 0
                """ + unread, Long.class, current.tenantId(), current.userId());
        List<NotificationDtos.MessageRow> rows = jdbc.query("""
                SELECT id, message_type, severity, title, content, business_type,
                       business_id, business_code, route_path, acknowledge_required,
                       read_time, acknowledged_time, occurred_time
                FROM notification_message
                WHERE tenant_id = ? AND recipient_user_id = ? AND deleted = 0
                """ + unread + " ORDER BY occurred_time DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNumber) -> new NotificationDtos.MessageRow(
                        rs.getLong("id"), rs.getString("message_type"),
                        rs.getString("severity"), rs.getString("title"),
                        rs.getString("content"), rs.getString("business_type"),
                        rs.getLong("business_id"), rs.getString("business_code"),
                        rs.getString("route_path"), rs.getBoolean("acknowledge_required"),
                        timestamp(rs.getTimestamp("read_time")),
                        timestamp(rs.getTimestamp("acknowledged_time")),
                        rs.getTimestamp("occurred_time").toLocalDateTime()
                ), current.tenantId(), current.userId(), pageSize, (page - 1) * pageSize
        );
        return PageResult.of(rows, total == null ? 0 : total, page, pageSize);
    }

    /**
     * Returns a read-only task snapshot for a message recipient.  Authorization is deliberately
     * based on ownership of the notification instead of the operational task data scope: an
     * escalated supervisor must be able to understand a warning without receiving permission to
     * execute or dispatch the underlying task.
     */
    @Transactional(readOnly = true)
    public NotificationDtos.BusinessDetail businessDetail(long messageId) {
        var current = SecurityUtils.currentUser();
        MessageTarget target = messageTarget(messageId);
        return switch (target.businessType()) {
            case "INSPECTION" -> inspectionBusinessDetail(current.tenantId(), target);
            case "MAINTENANCE" -> maintenanceBusinessDetail(current.tenantId(), target);
            default -> throw new BusinessException(
                    "NOTIFICATION_BUSINESS_UNSUPPORTED", "该消息暂不支持业务详情查看",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        };
    }

    @Transactional(readOnly = true)
    public AttachmentService.DownloadedAttachment businessAttachmentContent(
            long messageId, long attachmentId
    ) {
        var current = SecurityUtils.currentUser();
        MessageTarget target = messageTarget(messageId);
        String relationTable;
        String taskTable;
        String activeRelation;
        if ("INSPECTION".equals(target.businessType())) {
            relationTable = "inspection_attachment";
            taskTable = "inspection_task";
            activeRelation = " AND relation.deleted = 0";
        } else if ("MAINTENANCE".equals(target.businessType())) {
            relationTable = "maintenance_attachment";
            taskTable = "maintenance_task";
            activeRelation = "";
        } else {
            throw new BusinessException(
                    "NOTIFICATION_BUSINESS_UNSUPPORTED", "该消息暂不支持附件查看",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM %s relation
                JOIN %s task
                  ON task.tenant_id = relation.tenant_id
                 AND task.id = relation.task_id AND task.deleted = 0
                JOIN system_attachment attachment
                  ON attachment.tenant_id = relation.tenant_id
                 AND attachment.id = relation.attachment_id AND attachment.deleted = 0
                WHERE relation.tenant_id = ? AND relation.task_id = ?
                  AND relation.attachment_id = ?%s
                """.formatted(relationTable, taskTable, activeRelation), Integer.class,
                current.tenantId(), target.businessId(), attachmentId);
        if (count == null || count < 1) {
            throw new BusinessException(
                    "NOTIFICATION_ATTACHMENT_NOT_FOUND", "任务图片不存在或无权查看",
                    HttpStatus.NOT_FOUND
            );
        }
        return attachmentService.load(attachmentId);
    }

    private MessageTarget messageTarget(long messageId) {
        var current = SecurityUtils.currentUser();
        List<MessageTarget> targets = jdbc.query("""
                SELECT id, business_type, business_id, business_code
                FROM notification_message
                WHERE tenant_id = ? AND id = ? AND recipient_user_id = ? AND deleted = 0
                """, (rs, rowNumber) -> new MessageTarget(
                        rs.getLong("id"), rs.getString("business_type"),
                        rs.getLong("business_id"), rs.getString("business_code")
                ), current.tenantId(), messageId, current.userId());
        if (targets.isEmpty()) {
            throw new BusinessException(
                    "NOTIFICATION_MESSAGE_NOT_FOUND", "消息不存在或无权查看",
                    HttpStatus.NOT_FOUND
            );
        }
        return targets.getFirst();
    }

    private NotificationDtos.BusinessDetail inspectionBusinessDetail(
            long tenantId, MessageTarget target
    ) {
        List<BusinessHeader> headers = jdbc.query("""
                SELECT task.task_code, task.scheme_name_snapshot,
                       equipment.equipment_code, equipment.equipment_name,
                       organization.organization_name, location.location_name,
                       task.planned_date, task.due_time, task.task_status, task.source_type,
                       COALESCE((
                           SELECT GROUP_CONCAT(user.real_name
                                      ORDER BY relation.primary_flag DESC, relation.sort_order, relation.id
                                      SEPARATOR '、')
                           FROM inspection_task_assignee relation
                           JOIN system_user user
                             ON user.tenant_id = relation.tenant_id
                            AND user.id = relation.user_id AND user.deleted = 0
                           WHERE relation.tenant_id = task.tenant_id
                             AND relation.task_id = task.id AND relation.deleted = 0
                       ), assignee.real_name, '未派工') AS assignee_names,
                       task.started_time, task.submitted_time, task.completed_time
                FROM inspection_task task
                JOIN equipment
                  ON equipment.tenant_id = task.tenant_id
                 AND equipment.id = task.equipment_id AND equipment.deleted = 0
                JOIN organization
                  ON organization.tenant_id = task.tenant_id
                 AND organization.id = task.organization_id AND organization.deleted = 0
                LEFT JOIN location
                  ON location.tenant_id = task.tenant_id
                 AND location.id = task.location_id AND location.deleted = 0
                LEFT JOIN system_user assignee
                  ON assignee.tenant_id = task.tenant_id
                 AND assignee.id = task.assignee_user_id AND assignee.deleted = 0
                WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                """, this::businessHeader, tenantId, target.businessId());
        BusinessHeader header = requireBusinessHeader(headers);

        List<NotificationDtos.BusinessItemDetail> items = jdbc.query("""
                SELECT item.id, item.item_code, item.item_name,
                       item.inspection_part AS item_part,
                       item.inspection_content AS item_content,
                       item.inspection_standard AS item_standard,
                       item.result_type, item.unit, result.result_code,
                       result.numeric_value, result.text_value, result.selected_value,
                       COALESCE(result.abnormal_flag, 0) AS abnormal_flag,
                       result.abnormal_description,
                       COALESCE(result.skipped_flag, 0) AS skipped_flag,
                       result.skip_reason, executor.real_name AS executed_by_name,
                       result.executed_time
                FROM inspection_task_item item
                LEFT JOIN inspection_task_result result
                  ON result.tenant_id = item.tenant_id
                 AND result.task_id = item.task_id
                 AND result.task_item_id = item.id AND result.deleted = 0
                LEFT JOIN system_user executor
                  ON executor.tenant_id = result.tenant_id
                 AND executor.id = result.executed_by AND executor.deleted = 0
                WHERE item.tenant_id = ? AND item.task_id = ? AND item.deleted = 0
                ORDER BY item.sort_order, item.id
                """, this::businessItem, tenantId, target.businessId());
        return businessDetail(target, header, items, inspectionAttachments(tenantId, target));
    }

    private NotificationDtos.BusinessDetail maintenanceBusinessDetail(
            long tenantId, MessageTarget target
    ) {
        List<BusinessHeader> headers = jdbc.query("""
                SELECT task.task_code, task.scheme_name_snapshot,
                       equipment.equipment_code, equipment.equipment_name,
                       organization.organization_name, location.location_name,
                       task.planned_date, task.due_time, task.task_status, task.source_type,
                       CONCAT_WS('、', assignee.real_name, (
                           SELECT GROUP_CONCAT(user.real_name ORDER BY relation.id SEPARATOR '、')
                           FROM maintenance_task_collaborator relation
                           JOIN system_user user
                             ON user.tenant_id = relation.tenant_id
                            AND user.id = relation.user_id AND user.deleted = 0
                           WHERE relation.tenant_id = task.tenant_id
                             AND relation.task_id = task.id
                             AND (task.assignee_user_id IS NULL OR relation.user_id != task.assignee_user_id)
                       )) AS assignee_names,
                       task.started_time, task.submitted_time, task.completed_time
                FROM maintenance_task task
                JOIN equipment
                  ON equipment.tenant_id = task.tenant_id
                 AND equipment.id = task.equipment_id AND equipment.deleted = 0
                JOIN organization
                  ON organization.tenant_id = task.tenant_id
                 AND organization.id = task.organization_id AND organization.deleted = 0
                LEFT JOIN location
                  ON location.tenant_id = task.tenant_id
                 AND location.id = task.location_id AND location.deleted = 0
                LEFT JOIN system_user assignee
                  ON assignee.tenant_id = task.tenant_id
                 AND assignee.id = task.assignee_user_id AND assignee.deleted = 0
                WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                """, this::businessHeader, tenantId, target.businessId());
        BusinessHeader header = requireBusinessHeader(headers);

        List<NotificationDtos.BusinessItemDetail> items = jdbc.query("""
                SELECT item.id, item.item_code, item.item_name,
                       item.maintenance_part AS item_part,
                       item.maintenance_content AS item_content,
                       item.maintenance_standard AS item_standard,
                       item.result_type, item.unit, result.result_code,
                       result.numeric_value, result.text_value, result.selected_value,
                       COALESCE(result.abnormal_flag, 0) AS abnormal_flag,
                       result.abnormal_description,
                       COALESCE(result.skipped_flag, 0) AS skipped_flag,
                       result.skip_reason, executor.real_name AS executed_by_name,
                       result.executed_time
                FROM maintenance_task_item item
                LEFT JOIN maintenance_task_result result
                  ON result.tenant_id = item.tenant_id
                 AND result.task_id = item.task_id
                 AND result.task_item_id = item.id
                LEFT JOIN system_user executor
                  ON executor.tenant_id = result.tenant_id
                 AND executor.id = result.executed_by AND executor.deleted = 0
                WHERE item.tenant_id = ? AND item.task_id = ?
                ORDER BY item.sort_order, item.id
                """, this::businessItem, tenantId, target.businessId());
        return businessDetail(target, header, items, maintenanceAttachments(tenantId, target));
    }

    private List<NotificationDtos.BusinessAttachmentDetail> inspectionAttachments(
            long tenantId, MessageTarget target
    ) {
        return jdbc.query("""
                SELECT attachment.id, relation.task_result_id, result.task_item_id,
                       item.item_name, attachment.original_name, attachment.content_type,
                       attachment.extension, attachment.file_size, relation.attachment_type,
                       attachment.created_time
                FROM inspection_attachment relation
                JOIN system_attachment attachment
                  ON attachment.tenant_id = relation.tenant_id
                 AND attachment.id = relation.attachment_id AND attachment.deleted = 0
                LEFT JOIN inspection_task_result result
                  ON result.tenant_id = relation.tenant_id
                 AND result.id = relation.task_result_id AND result.deleted = 0
                LEFT JOIN inspection_task_item item
                  ON item.tenant_id = result.tenant_id
                 AND item.id = result.task_item_id AND item.deleted = 0
                WHERE relation.tenant_id = ? AND relation.task_id = ? AND relation.deleted = 0
                ORDER BY item.sort_order, relation.id
                """, this::businessAttachment, tenantId, target.businessId());
    }

    private List<NotificationDtos.BusinessAttachmentDetail> maintenanceAttachments(
            long tenantId, MessageTarget target
    ) {
        return jdbc.query("""
                SELECT attachment.id, relation.task_result_id, result.task_item_id,
                       item.item_name, attachment.original_name, attachment.content_type,
                       attachment.extension, attachment.file_size, relation.attachment_type,
                       attachment.created_time
                FROM maintenance_attachment relation
                JOIN system_attachment attachment
                  ON attachment.tenant_id = relation.tenant_id
                 AND attachment.id = relation.attachment_id AND attachment.deleted = 0
                LEFT JOIN maintenance_task_result result
                  ON result.tenant_id = relation.tenant_id
                 AND result.id = relation.task_result_id
                LEFT JOIN maintenance_task_item item
                  ON item.tenant_id = result.tenant_id AND item.id = result.task_item_id
                WHERE relation.tenant_id = ? AND relation.task_id = ?
                ORDER BY item.sort_order, relation.id
                """, this::businessAttachment, tenantId, target.businessId());
    }

    private NotificationDtos.BusinessAttachmentDetail businessAttachment(
            java.sql.ResultSet rs, int rowNumber
    ) throws java.sql.SQLException {
        return new NotificationDtos.BusinessAttachmentDetail(
                rs.getLong("id"), nullableLong(rs, "task_result_id"),
                nullableLong(rs, "task_item_id"), rs.getString("item_name"),
                rs.getString("original_name"), rs.getString("content_type"),
                rs.getString("extension"), rs.getLong("file_size"),
                rs.getString("attachment_type"),
                timestamp(rs.getTimestamp("created_time"))
        );
    }

    private BusinessHeader businessHeader(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new BusinessHeader(
                rs.getString("task_code"), rs.getString("scheme_name_snapshot"),
                rs.getString("equipment_code"), rs.getString("equipment_name"),
                rs.getString("organization_name"), rs.getString("location_name"),
                rs.getDate("planned_date").toLocalDate(),
                timestamp(rs.getTimestamp("due_time")), rs.getString("task_status"),
                rs.getString("source_type"), rs.getString("assignee_names"),
                timestamp(rs.getTimestamp("started_time")),
                timestamp(rs.getTimestamp("submitted_time")),
                timestamp(rs.getTimestamp("completed_time"))
        );
    }

    private NotificationDtos.BusinessItemDetail businessItem(
            java.sql.ResultSet rs, int rowNumber
    ) throws java.sql.SQLException {
        return new NotificationDtos.BusinessItemDetail(
                rs.getLong("id"), rs.getString("item_code"), rs.getString("item_name"),
                rs.getString("item_part"), rs.getString("item_content"),
                rs.getString("item_standard"), rs.getString("result_type"),
                rs.getString("unit"), rs.getString("result_code"),
                rs.getBigDecimal("numeric_value"), rs.getString("text_value"),
                rs.getString("selected_value"), rs.getBoolean("abnormal_flag"),
                rs.getString("abnormal_description"), rs.getBoolean("skipped_flag"),
                rs.getString("skip_reason"), rs.getString("executed_by_name"),
                timestamp(rs.getTimestamp("executed_time"))
        );
    }

    private BusinessHeader requireBusinessHeader(List<BusinessHeader> headers) {
        if (headers.isEmpty()) {
            throw new BusinessException(
                    "NOTIFICATION_BUSINESS_NOT_FOUND", "关联任务不存在或已删除",
                    HttpStatus.NOT_FOUND
            );
        }
        return headers.getFirst();
    }

    private NotificationDtos.BusinessDetail businessDetail(
            MessageTarget target, BusinessHeader header,
            List<NotificationDtos.BusinessItemDetail> items,
            List<NotificationDtos.BusinessAttachmentDetail> attachments
    ) {
        return new NotificationDtos.BusinessDetail(
                target.messageId(), target.businessType(), target.businessId(),
                target.businessCode(), header.taskCode(), header.schemeName(),
                header.equipmentCode(), header.equipmentName(), header.organizationName(),
                header.locationName(), header.plannedDate(), header.dueTime(),
                header.taskStatus(), header.sourceType(), header.assigneeNames(),
                header.startedTime(), header.submittedTime(), header.completedTime(),
                items, attachments
        );
    }

    @Transactional
    public void read(long id) {
        var current = SecurityUtils.currentUser();
        int changed = jdbc.update("""
                UPDATE notification_message SET read_time = COALESCE(read_time, CURRENT_TIMESTAMP(3))
                WHERE tenant_id = ? AND id = ? AND recipient_user_id = ? AND deleted = 0
                """, current.tenantId(), id, current.userId());
        if (changed != 1) {
            throw new BusinessException(
                    "NOTIFICATION_MESSAGE_NOT_FOUND", "消息不存在或无权操作",
                    HttpStatus.NOT_FOUND
            );
        }
    }

    @Transactional
    public void acknowledge(long id) {
        var current = SecurityUtils.currentUser();
        int changed = jdbc.update("""
                UPDATE notification_message
                SET read_time = COALESCE(read_time, CURRENT_TIMESTAMP(3)),
                    acknowledged_time = COALESCE(acknowledged_time, CURRENT_TIMESTAMP(3))
                WHERE tenant_id = ? AND id = ? AND recipient_user_id = ?
                  AND acknowledge_required = 1 AND deleted = 0
                """, current.tenantId(), id, current.userId());
        if (changed != 1) {
            throw new BusinessException(
                    "NOTIFICATION_ACK_NOT_ALLOWED", "消息不存在、无权操作或无需确认",
                    HttpStatus.CONFLICT
            );
        }
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationDtos.DeliveryRow> deliveries(
            String status, int page, int pageSize
    ) {
        long tenantId = SecurityUtils.currentUser().tenantId();
        String filter = status == null || status.isBlank()
                ? "" : " AND delivery.delivery_status = ?";
        Object[] countParameters = status == null || status.isBlank()
                ? new Object[]{tenantId} : new Object[]{tenantId, status};
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notification_delivery delivery
                WHERE delivery.tenant_id = ?
                """ + filter, Long.class, countParameters);
        List<Object> parameters = new ArrayList<>(List.of(countParameters));
        parameters.add(pageSize);
        parameters.add((page - 1) * pageSize);
        List<NotificationDtos.DeliveryRow> rows = jdbc.query("""
                SELECT delivery.id, delivery.message_id, user.real_name AS recipient_name,
                       message.title, delivery.channel_code, delivery.delivery_status,
                       delivery.sent_time, delivery.failure_reason, delivery.retry_count,
                       delivery.next_retry_time, delivery.created_time
                FROM notification_delivery delivery
                JOIN notification_message message
                  ON message.tenant_id = delivery.tenant_id
                 AND message.id = delivery.message_id
                JOIN system_user user
                  ON user.tenant_id = message.tenant_id
                 AND user.id = message.recipient_user_id
                WHERE delivery.tenant_id = ?
                """ + filter + " ORDER BY delivery.created_time DESC LIMIT ? OFFSET ?",
                (rs, rowNumber) -> new NotificationDtos.DeliveryRow(
                        rs.getLong("id"), rs.getLong("message_id"),
                        rs.getString("recipient_name"), rs.getString("title"),
                        rs.getString("channel_code"), rs.getString("delivery_status"),
                        timestamp(rs.getTimestamp("sent_time")), rs.getString("failure_reason"),
                        rs.getInt("retry_count"), timestamp(rs.getTimestamp("next_retry_time")),
                        rs.getTimestamp("created_time").toLocalDateTime()
                ), parameters.toArray()
        );
        return PageResult.of(rows, total == null ? 0 : total, page, pageSize);
    }

    @Transactional(readOnly = true)
    public List<Long> tenantIds() {
        return jdbc.queryForList(
                "SELECT id FROM system_tenant WHERE status = 1 AND deleted = 0",
                Long.class
        );
    }

    @Transactional
    public NotificationDtos.ScanResult scanCurrentTenant() {
        return scanTenant(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public NotificationDtos.ScanResult scanTenant(long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        NotificationDtos.ScanResult total = new NotificationDtos.ScanResult(0, 0, 0, 0, 0);
        for (NotificationDtos.RuleRow rule : rules(tenantId, true)) {
            total = total.plus(scanRule(tenantId, rule, now));
        }
        int stopped = stopClosedEscalations(tenantId, "INSPECTION", "inspection_task");
        stopped += stopClosedEscalations(tenantId, "MAINTENANCE", "maintenance_task");
        return new NotificationDtos.ScanResult(
                total.scannedTasks(), total.createdMessages(), total.duplicateMessages(),
                total.missingRecipients(), stopped
        );
    }

    private NotificationDtos.ScanResult scanRule(
            long tenantId, NotificationDtos.RuleRow rule, LocalDateTime now
    ) {
        List<NotificationDtos.TaskCandidate> tasks = candidates(tenantId, rule, now);
        int created = 0;
        int duplicate = 0;
        int missing = 0;
        for (NotificationDtos.TaskCandidate task : tasks) {
            List<Long> recipients = recipients(tenantId, rule, task);
            if (recipients.isEmpty()) {
                missing++;
                if ("OVERDUE".equals(rule.triggerType())) {
                    updateEscalation(tenantId, rule, task, now, false);
                }
                continue;
            }
            for (Long recipientId : recipients) {
                String dedupe = dedupe(rule, task, recipientId, now);
                int inserted = jdbc.update("""
                        INSERT IGNORE INTO notification_message
                            (tenant_id, rule_id, recipient_user_id, message_type,
                             title, content, business_type, business_id, business_code,
                             severity, route_path, acknowledge_required, dedupe_key,
                             occurred_time, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                        tenantId, rule.id(), recipientId, rule.ruleCode(),
                        title(rule, task), content(rule, task), rule.businessType(),
                        task.id(), task.taskCode(), rule.severity(), route(rule, task),
                        rule.acknowledgeRequired(), dedupe, now
                );
                if (inserted == 1) {
                    created++;
                    Long messageId = jdbc.queryForObject("""
                            SELECT id FROM notification_message
                            WHERE tenant_id = ? AND dedupe_key = ?
                            """, Long.class, tenantId, dedupe);
                    createDeliveries(tenantId, messageId, rule.channels(), now);
                } else {
                    duplicate++;
                }
            }
            if ("OVERDUE".equals(rule.triggerType())) {
                updateEscalation(tenantId, rule, task, now, true);
            }
        }
        return new NotificationDtos.ScanResult(tasks.size(), created, duplicate, missing, 0);
    }

    private List<NotificationDtos.RuleRow> rules(long tenantId, boolean enabledOnly) {
        String enabled = enabledOnly ? " AND enabled = 1" : "";
        return jdbc.query("""
                SELECT id, rule_code, rule_name, business_type, trigger_type,
                       advance_minutes, repeat_minutes, escalation_level, recipient_type,
                       severity, CAST(channels_json AS CHAR) AS channels_json,
                       acknowledge_required, enabled, version
                FROM notification_rule
                WHERE tenant_id = ? AND deleted = 0
                """ + enabled + " ORDER BY business_type, trigger_type, escalation_level, id",
                (rs, rowNumber) -> new NotificationDtos.RuleRow(
                        rs.getLong("id"), rs.getString("rule_code"),
                        rs.getString("rule_name"), rs.getString("business_type"),
                        rs.getString("trigger_type"), rs.getInt("advance_minutes"),
                        rs.getInt("repeat_minutes"), rs.getInt("escalation_level"),
                        rs.getString("recipient_type"), rs.getString("severity"),
                        channels(rs.getString("channels_json")),
                        rs.getBoolean("acknowledge_required"), rs.getBoolean("enabled"),
                        rs.getInt("version")
                ), tenantId
        );
    }

    private List<NotificationDtos.TaskCandidate> candidates(
            long tenantId, NotificationDtos.RuleRow rule, LocalDateTime now
    ) {
        String table = "INSPECTION".equals(rule.businessType())
                ? "inspection_task" : "maintenance_task";
        String trigger;
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if ("DUE_SOON".equals(rule.triggerType())) {
            trigger = " AND task.due_time >= ? AND task.due_time <= ?";
            parameters.add(now);
            parameters.add(now.plusMinutes(rule.advanceMinutes()));
        } else if ("MANUAL_CREATED".equals(rule.triggerType())) {
            trigger = " AND task.source_type IN ('MANUAL', 'QUICK_ENTRY')";
        } else {
            trigger = " AND task.due_time <= ?";
            parameters.add(now.minusMinutes(rule.advanceMinutes()));
        }
        return jdbc.query("""
                SELECT task.id, task.task_code, task.organization_id, task.team_code,
                       task.assignee_user_id, task.source_type, task.task_status,
                       task.due_time, equipment.equipment_name
                FROM %s task
                JOIN equipment
                  ON equipment.tenant_id = task.tenant_id
                 AND equipment.id = task.equipment_id
                 AND equipment.deleted = 0
                WHERE task.tenant_id = ? AND task.deleted = 0
                  AND task.task_status NOT IN ('COMPLETED', 'CANCELLED', 'VOIDED')
                %s
                """.formatted(table, trigger),
                (rs, rowNumber) -> new NotificationDtos.TaskCandidate(
                        rs.getLong("id"), rs.getString("task_code"),
                        rs.getLong("organization_id"), rs.getString("team_code"),
                        rs.getObject("assignee_user_id", Long.class),
                        rs.getString("source_type"), rs.getString("task_status"),
                        rs.getTimestamp("due_time").toLocalDateTime(),
                        rs.getString("equipment_name")
                ), parameters.toArray()
        );
    }

    private List<Long> recipients(
            long tenantId, NotificationDtos.RuleRow rule,
        NotificationDtos.TaskCandidate task
    ) {
        if ("ASSIGNEE".equals(rule.recipientType())) {
            return assigneeUsers(tenantId, rule, task);
        }
        if ("TEAM_LEADER".equals(rule.recipientType())) {
            LinkedHashSet<Long> teamOrganizationIds = new LinkedHashSet<>();
            if (task.teamCode() != null && !task.teamCode().isBlank()) {
                teamOrganizationIds.addAll(jdbc.queryForList("""
                        SELECT id FROM organization
                        WHERE tenant_id = ? AND organization_code = ?
                          AND organization_type = 'TEAM' AND status = 1 AND deleted = 0
                        """, Long.class, tenantId, task.teamCode()));
            }
            teamOrganizationIds.addAll(jdbc.queryForList("""
                    SELECT id FROM organization
                    WHERE tenant_id = ? AND id = ? AND organization_type = 'TEAM'
                      AND status = 1 AND deleted = 0
                    """, Long.class, tenantId, task.organizationId()));

            List<Long> assigneeUserIds = assigneeUsers(tenantId, rule, task);
            if (!assigneeUserIds.isEmpty()) {
                teamOrganizationIds.addAll(jdbc.queryForList("""
                        SELECT DISTINCT organization.id
                        FROM system_user user
                        JOIN organization
                          ON organization.tenant_id = user.tenant_id
                         AND organization.id = user.organization_id
                         AND organization.organization_type = 'TEAM'
                         AND organization.status = 1 AND organization.deleted = 0
                        WHERE user.tenant_id = ? AND user.id IN (%s)
                          AND user.status = 1 AND user.deleted = 0
                        """.formatted(placeholders(assigneeUserIds.size())), Long.class,
                        prepend(tenantId, assigneeUserIds)));
                teamOrganizationIds.addAll(jdbc.queryForList("""
                        SELECT DISTINCT membership.team_organization_id
                        FROM system_user_team_membership membership
                        JOIN organization
                          ON organization.tenant_id = membership.tenant_id
                         AND organization.id = membership.team_organization_id
                         AND organization.organization_type = 'TEAM'
                         AND organization.status = 1 AND organization.deleted = 0
                        WHERE membership.tenant_id = ?
                          AND membership.user_id IN (%s)
                          AND membership.deleted = 0
                        ORDER BY membership.team_organization_id
                        """.formatted(placeholders(assigneeUserIds.size())), Long.class,
                        prepend(tenantId, assigneeUserIds)));
            }
            if (teamOrganizationIds.isEmpty()) {
                return List.of();
            }

            List<Long> organizationIds = new ArrayList<>(teamOrganizationIds);
            LinkedHashSet<Long> result = new LinkedHashSet<>(jdbc.queryForList("""
                    SELECT user_id FROM organization_manager_relation
                    WHERE tenant_id = ? AND organization_id IN (%s) AND deleted = 0
                    ORDER BY organization_id, sort_order, id
                    """.formatted(placeholders(organizationIds.size())), Long.class,
                    prepend(tenantId, organizationIds)));
            result.addAll(jdbc.queryForList("""
                    SELECT manager_user_id FROM organization
                    WHERE tenant_id = ? AND id IN (%s) AND organization_type = 'TEAM'
                      AND manager_user_id IS NOT NULL AND status = 1 AND deleted = 0
                    """.formatted(placeholders(organizationIds.size())), Long.class,
                    prepend(tenantId, organizationIds)));
            result.addAll(roleUsers(tenantId, "TEAM_LEADER", organizationIds));
            return activeUsers(tenantId, result);
        }
        List<Long> workshopIds = jdbc.queryForList("""
                WITH RECURSIVE ancestors AS (
                    SELECT id, parent_id, organization_type
                    FROM organization
                    WHERE tenant_id = ? AND id = ? AND deleted = 0
                    UNION ALL
                    SELECT parent.id, parent.parent_id, parent.organization_type
                    FROM organization parent
                    JOIN ancestors child ON child.parent_id = parent.id
                    WHERE parent.tenant_id = ? AND parent.deleted = 0
                )
                SELECT id FROM ancestors WHERE organization_type = 'WORKSHOP'
                """, Long.class, tenantId, task.organizationId(), tenantId);
        LinkedHashSet<Long> result = new LinkedHashSet<>(
                roleUsers(tenantId, "WORKSHOP_MANAGER", workshopIds)
        );
        if (!workshopIds.isEmpty()) {
            result.addAll(jdbc.queryForList("""
                    SELECT user_id FROM organization_manager_relation
                    WHERE tenant_id = ? AND organization_id IN (%s) AND deleted = 0
                    ORDER BY organization_id, sort_order, id
                    """.formatted(placeholders(workshopIds.size())), Long.class,
                    prepend(tenantId, workshopIds)));
            result.addAll(jdbc.queryForList("""
                    SELECT manager_user_id FROM organization
                    WHERE tenant_id = ? AND id IN (%s)
                      AND manager_user_id IS NOT NULL AND deleted = 0
                    """.formatted(placeholders(workshopIds.size())), Long.class,
                    prepend(tenantId, workshopIds)));
        }
        return activeUsers(tenantId, result);
    }

    private List<Long> assigneeUsers(
            long tenantId, NotificationDtos.RuleRow rule,
            NotificationDtos.TaskCandidate task
    ) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (task.assigneeUserId() != null) {
            ids.add(task.assigneeUserId());
        }
        String table = "INSPECTION".equals(rule.businessType())
                ? "inspection_task_assignee" : "maintenance_task_collaborator";
        String activePredicate = "INSPECTION".equals(rule.businessType())
                ? " AND deleted = 0" : "";
        ids.addAll(jdbc.queryForList("""
                SELECT user_id FROM %s
                WHERE tenant_id = ? AND task_id = ?%s
                """.formatted(table, activePredicate), Long.class, tenantId, task.id()));
        return activeUsers(tenantId, ids);
    }

    private List<Long> roleUsers(long tenantId, String roleCode, List<Long> organizationIds) {
        if (organizationIds.isEmpty()) {
            return List.of();
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(roleCode);
        parameters.addAll(organizationIds);
        return jdbc.queryForList("""
                SELECT DISTINCT user.id
                FROM system_user user
                JOIN system_user_role relation
                  ON relation.tenant_id = user.tenant_id
                 AND relation.user_id = user.id AND relation.deleted = 0
                JOIN system_role role
                  ON role.tenant_id = relation.tenant_id
                 AND role.id = relation.role_id AND role.deleted = 0
                WHERE user.tenant_id = ? AND role.role_code = ?
                  AND user.organization_id IN (%s)
                  AND user.status = 1 AND user.deleted = 0
                """.formatted(placeholders(organizationIds.size())), Long.class,
                parameters.toArray());
    }

    private List<Long> activeUsers(long tenantId, Iterable<Long> candidates) {
        List<Long> ids = new ArrayList<>();
        candidates.forEach(ids::add);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT id FROM system_user
                WHERE tenant_id = ? AND id IN (%s) AND status = 1 AND deleted = 0
                """.formatted(placeholders(ids.size())), Long.class, prepend(tenantId, ids));
    }

    private void createDeliveries(
            long tenantId, long messageId, List<String> channels, LocalDateTime now
    ) {
        for (String channel : new LinkedHashSet<>(channels)) {
            String status;
            LocalDateTime sentTime = null;
            String reason = null;
            if ("SYSTEM".equals(channel)) {
                status = "SENT";
                sentTime = now;
            } else if ("ANDROID".equals(channel)) {
                status = "READY";
            } else {
                status = "SKIPPED";
                reason = "渠道未配置，不影响业务任务";
            }
            jdbc.update("""
                    INSERT IGNORE INTO notification_delivery
                        (tenant_id, message_id, channel_code, delivery_status,
                         sent_time, failure_reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, tenantId, messageId, channel, status, sentTime, reason);
        }
    }

    private void updateEscalation(
            long tenantId, NotificationDtos.RuleRow rule,
            NotificationDtos.TaskCandidate task, LocalDateTime now, boolean delivered
    ) {
        String status = delivered ? "ACTIVE" : "NO_RECIPIENT";
        String reason = delivered ? null : switch (rule.recipientType()) {
            case "TEAM_LEADER" -> "未找到任务班组的启用班组长账号";
            case "WORKSHOP_MANAGER" -> "未找到任务所属车间的启用车间主任账号";
            default -> "任务未设置启用执行人";
        };
        jdbc.update("""
                INSERT INTO notification_escalation
                    (tenant_id, business_type, business_id, current_level,
                     next_escalation_time, last_notification_time,
                     escalation_status, status_reason)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    current_level = GREATEST(current_level, VALUES(current_level)),
                    last_notification_time = VALUES(last_notification_time),
                    escalation_status = VALUES(escalation_status),
                    status_reason = VALUES(status_reason)
                """, tenantId, rule.businessType(), task.id(), rule.escalationLevel(),
                delivered ? now : null, status, reason);
    }

    private int stopClosedEscalations(long tenantId, String type, String table) {
        return jdbc.update("""
                UPDATE notification_escalation escalation
                LEFT JOIN %s task
                  ON task.tenant_id = escalation.tenant_id
                 AND task.id = escalation.business_id
                SET escalation.escalation_status = 'STOPPED',
                    escalation.next_escalation_time = NULL,
                    escalation.status_reason = '任务已完成、取消、作废或删除'
                WHERE escalation.tenant_id = ?
                  AND escalation.business_type = ?
                  AND escalation.escalation_status != 'STOPPED'
                  AND (task.id IS NULL OR task.deleted = 1
                       OR task.task_status IN ('COMPLETED', 'CANCELLED', 'VOIDED'))
                """.formatted(table), tenantId, type);
    }

    private String dedupe(
            NotificationDtos.RuleRow rule, NotificationDtos.TaskCandidate task,
            long recipientId, LocalDateTime now
    ) {
        String base = rule.ruleCode() + ":" + task.id() + ":" + recipientId;
        if (rule.repeatMinutes() < 1) {
            return base;
        }
        long minute = now.toEpochSecond(ZoneOffset.UTC) / 60;
        return base + ":" + (minute / rule.repeatMinutes());
    }

    private String title(NotificationDtos.RuleRow rule, NotificationDtos.TaskCandidate task) {
        String business = "INSPECTION".equals(rule.businessType()) ? "点检" : "维保";
        String trigger = switch (rule.triggerType()) {
            case "DUE_SOON" -> "即将到期";
            case "MANUAL_CREATED" -> "临时任务待确认";
            default -> rule.escalationLevel() > 0
                    ? "逾期升级 L" + rule.escalationLevel() : "已逾期";
        };
        return business + "任务" + trigger + "：" + task.taskCode();
    }

    private String content(NotificationDtos.RuleRow rule, NotificationDtos.TaskCandidate task) {
        return task.equipmentName() + " · 截止 " + task.dueTime()
                + (rule.acknowledgeRequired() ? " · 请确认并及时处理" : "");
    }

    private String route(NotificationDtos.RuleRow rule, NotificationDtos.TaskCandidate task) {
        return "INSPECTION".equals(rule.businessType())
                ? "/mobile/inspection?taskId=" + task.id()
                : "/mobile/maintenance?taskId=" + task.id();
    }

    private List<String> channels(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "NOTIFICATION_RULE_CORRUPTED", "提醒规则渠道数据损坏",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "NOTIFICATION_RULE_SERIALIZE_FAILED", "提醒规则渠道数据无法保存",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private Object[] prepend(long first, List<Long> values) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(values);
        return result.toArray();
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private LocalDateTime timestamp(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record MessageTarget(
            long messageId, String businessType, long businessId, String businessCode
    ) {
    }

    private record BusinessHeader(
            String taskCode, String schemeName, String equipmentCode, String equipmentName,
            String organizationName, String locationName, java.time.LocalDate plannedDate,
            LocalDateTime dueTime, String taskStatus, String sourceType, String assigneeNames,
            LocalDateTime startedTime, LocalDateTime submittedTime, LocalDateTime completedTime
    ) {
    }
}
