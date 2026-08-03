CREATE TABLE notification_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(120) NOT NULL,
    business_type VARCHAR(32) NOT NULL COMMENT 'INSPECTION/MAINTENANCE',
    trigger_type VARCHAR(32) NOT NULL COMMENT 'DUE_SOON/MANUAL_CREATED/OVERDUE',
    advance_minutes INT NOT NULL DEFAULT 0 COMMENT '提前或逾期延迟分钟数',
    repeat_minutes INT NOT NULL DEFAULT 0,
    escalation_level INT NOT NULL DEFAULT 0,
    recipient_type VARCHAR(32) NOT NULL COMMENT 'ASSIGNEE/TEAM_LEADER/WORKSHOP_MANAGER',
    severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    channels_json JSON NOT NULL,
    acknowledge_required TINYINT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_rule_code (tenant_id, rule_code),
    KEY idx_notification_rule_scan (tenant_id, enabled, business_type, trigger_type, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息提醒与升级规则';

CREATE TABLE notification_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    business_code VARCHAR(64) NULL,
    severity VARCHAR(16) NOT NULL,
    route_path VARCHAR(300) NULL,
    acknowledge_required TINYINT NOT NULL DEFAULT 0,
    read_time DATETIME(3) NULL,
    acknowledged_time DATETIME(3) NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    occurred_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_message_dedupe (tenant_id, dedupe_key),
    KEY idx_notification_message_recipient
        (tenant_id, recipient_user_id, read_time, occurred_time, deleted),
    KEY idx_notification_message_business
        (tenant_id, business_type, business_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内与移动端消息';

CREATE TABLE notification_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL COMMENT 'READY/SENT/FAILED/SKIPPED',
    sent_time DATETIME(3) NULL,
    failure_reason VARCHAR(500) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_delivery_channel (tenant_id, message_id, channel_code),
    KEY idx_notification_delivery_retry
        (tenant_id, delivery_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息渠道发送记录';

CREATE TABLE notification_escalation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    current_level INT NOT NULL DEFAULT 0,
    next_escalation_time DATETIME(3) NULL,
    last_notification_time DATETIME(3) NULL,
    escalation_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STOPPED/NO_RECIPIENT',
    status_reason VARCHAR(500) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_escalation_business (tenant_id, business_type, business_id),
    KEY idx_notification_escalation_next
        (tenant_id, escalation_status, next_escalation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务逾期升级状态';

INSERT INTO notification_rule
    (tenant_id, rule_code, rule_name, business_type, trigger_type,
     advance_minutes, repeat_minutes, escalation_level, recipient_type,
     severity, channels_json, acknowledge_required, enabled, created_by, updated_by)
VALUES
    (1, 'INSPECTION_DUE_ASSIGNEE', '点检到期前提醒', 'INSPECTION', 'DUE_SOON',
     60, 0, 0, 'ASSIGNEE', 'MEDIUM', JSON_ARRAY('SYSTEM', 'ANDROID'), 0, 1, 1, 1),
    (1, 'INSPECTION_MANUAL_IMMEDIATE', '临时点检强提醒', 'INSPECTION', 'MANUAL_CREATED',
     0, 0, 0, 'ASSIGNEE', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'INSPECTION_OVERDUE_ASSIGNEE', '点检逾期本人提醒', 'INSPECTION', 'OVERDUE',
     0, 0, 0, 'ASSIGNEE', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'INSPECTION_OVERDUE_TEAM', '点检逾期一级升级', 'INSPECTION', 'OVERDUE',
     60, 0, 1, 'TEAM_LEADER', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'INSPECTION_OVERDUE_WORKSHOP', '点检逾期二级升级', 'INSPECTION', 'OVERDUE',
     240, 0, 2, 'WORKSHOP_MANAGER', 'CRITICAL', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'MAINTENANCE_DUE_ASSIGNEE', '维保到期前提醒', 'MAINTENANCE', 'DUE_SOON',
     1440, 0, 0, 'ASSIGNEE', 'MEDIUM', JSON_ARRAY('SYSTEM', 'ANDROID'), 0, 1, 1, 1),
    (1, 'MAINTENANCE_MANUAL_IMMEDIATE', '临时维保强提醒', 'MAINTENANCE', 'MANUAL_CREATED',
     0, 0, 0, 'ASSIGNEE', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'MAINTENANCE_OVERDUE_ASSIGNEE', '维保逾期本人提醒', 'MAINTENANCE', 'OVERDUE',
     0, 0, 0, 'ASSIGNEE', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'MAINTENANCE_OVERDUE_TEAM', '维保逾期一级升级', 'MAINTENANCE', 'OVERDUE',
     60, 0, 1, 'TEAM_LEADER', 'HIGH', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1),
    (1, 'MAINTENANCE_OVERDUE_WORKSHOP', '维保逾期二级升级', 'MAINTENANCE', 'OVERDUE',
     240, 0, 2, 'WORKSHOP_MANAGER', 'CRITICAL', JSON_ARRAY('SYSTEM', 'ANDROID'), 1, 1, 1, 1);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (70, 1, 0, 'DIRECTORY', '消息中心', NULL, '/notifications', NULL,
     'notification:view', 'Bell', 70),
    (71, 1, 70, 'MENU', '我的消息', 'NotificationMessages', '/notifications/messages',
     'views/notifications/NotificationCenterView.vue', 'notification:message:view', 'Message', 71),
    (72, 1, 70, 'MENU', '提醒规则', 'NotificationRules', '/notifications/rules',
     'views/notifications/NotificationRuleView.vue', 'notification:rule:view', 'AlarmClock', 72),
    (73, 1, 70, 'MENU', '发送记录', 'NotificationDeliveries', '/notifications/deliveries',
     'views/notifications/NotificationDeliveryView.vue', 'notification:delivery:view', 'Tickets', 73),
    (721, 1, 72, 'BUTTON', '维护提醒规则', NULL, NULL, NULL,
     'notification:rule:manage', NULL, 1),
    (731, 1, 73, 'BUTTON', '执行提醒扫描', NULL, NULL, NULL,
     'notification:scan', NULL, 1);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT 1, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu ON menu.tenant_id = role.tenant_id
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'PLANNER', 'WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR')
  AND menu.id IN (70, 71)
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT 1, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu ON menu.tenant_id = role.tenant_id
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'PLANNER')
  AND menu.id IN (72, 73, 721, 731)
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;
