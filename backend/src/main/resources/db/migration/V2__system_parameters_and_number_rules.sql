CREATE TABLE system_parameter (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parameter_key VARCHAR(128) NOT NULL,
    parameter_name VARCHAR(100) NOT NULL,
    parameter_value VARCHAR(2000) NOT NULL,
    value_type VARCHAR(20) NOT NULL DEFAULT 'STRING' COMMENT 'STRING/BOOLEAN/INTEGER/DECIMAL',
    group_code VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    description VARCHAR(500) NULL,
    built_in TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_parameter_tenant_key (tenant_id, parameter_key),
    KEY idx_parameter_group (tenant_id, group_code, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数';

CREATE TABLE system_number_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    prefix VARCHAR(30) NOT NULL DEFAULT '',
    date_pattern VARCHAR(20) NOT NULL DEFAULT 'yyyyMMdd',
    separator_value VARCHAR(5) NOT NULL DEFAULT '-',
    sequence_length INT NOT NULL DEFAULT 4,
    reset_period VARCHAR(16) NOT NULL DEFAULT 'DAILY' COMMENT 'DAILY/MONTHLY/YEARLY/NEVER',
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_number_rule_tenant_code (tenant_id, rule_code),
    KEY idx_number_rule_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务编号规则';

CREATE TABLE system_number_sequence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    period_key VARCHAR(20) NOT NULL,
    current_value BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_number_sequence_period (tenant_id, rule_id, period_key),
    KEY idx_number_sequence_rule (tenant_id, rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务编号流水';

INSERT INTO system_parameter
    (id, tenant_id, parameter_key, parameter_name, parameter_value, value_type, group_code, description, built_in)
VALUES
    (1, 1, 'system.name', '系统名称', 'LeanTPM 精益设备管理系统', 'STRING', 'SYSTEM', '系统页面展示名称', 1),
    (2, 1, 'system.timezone', '系统时区', 'Asia/Shanghai', 'STRING', 'SYSTEM', '日期时间计算使用的时区', 1),
    (3, 1, 'security.captcha.enabled', '登录验证码开关', 'false', 'BOOLEAN', 'SECURITY', '开启后登录接口要求验证码', 1);

INSERT INTO system_number_rule
    (id, tenant_id, rule_code, rule_name, prefix, date_pattern, separator_value, sequence_length, reset_period, description)
VALUES
    (1, 1, 'EQUIPMENT', '设备编号', 'EQP', 'yyyyMMdd', '-', 4, 'DAILY', '设备台账编号'),
    (2, 1, 'INSPECTION_TASK', '点检任务编号', 'DJ', 'yyyyMMdd', '-', 5, 'DAILY', '点检任务业务编号'),
    (3, 1, 'MAINTENANCE_TASK', '维保任务编号', 'WB', 'yyyyMMdd', '-', 5, 'DAILY', '维保任务业务编号');

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path, component_path, permission_code, icon, sort_order)
VALUES
    (98, 1, 90, 'MENU', '系统参数', 'SystemParameters', '/system/parameters', 'views/system/parameters/ParameterView.vue', 'system:parameter:view', 'Operation', 98),
    (99, 1, 90, 'MENU', '编号规则', 'SystemNumberRules', '/system/number-rules', 'views/system/number-rules/NumberRuleView.vue', 'system:number-rule:view', 'Tickets', 99),
    (981, 1, 98, 'BUTTON', '维护系统参数', NULL, NULL, NULL, 'system:parameter:manage', NULL, 1),
    (982, 1, 98, 'BUTTON', '删除系统参数', NULL, NULL, NULL, 'system:parameter:delete', NULL, 2),
    (991, 1, 99, 'BUTTON', '维护编号规则', NULL, NULL, NULL, 'system:number-rule:manage', NULL, 1),
    (992, 1, 99, 'BUTTON', '生成业务编号', NULL, NULL, NULL, 'system:number-rule:generate', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 1, id
FROM system_menu
WHERE tenant_id = 1
  AND id IN (98, 99, 981, 982, 991, 992);
