CREATE TABLE system_change_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/DELETE/BIND/UNBIND',
    before_data JSON NULL,
    after_data JSON NULL,
    changed_fields JSON NULL,
    operator_id BIGINT NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NULL,
    change_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_change_log_resource (tenant_id, resource_type, resource_id, change_time),
    KEY idx_change_log_operator (tenant_id, operator_id, change_time),
    KEY idx_change_log_time (tenant_id, change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务数据变更日志';

CREATE TABLE system_attachment_relation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    business_type VARCHAR(64) NOT NULL,
    business_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'DOCUMENT',
    sort_order INT NOT NULL DEFAULT 0,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_attachment_relation
        (tenant_id, attachment_id, business_type, business_id, relation_type),
    KEY idx_attachment_relation_business
        (tenant_id, business_type, business_id, relation_type, sort_order),
    KEY idx_attachment_relation_attachment (tenant_id, attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务附件关系';

INSERT INTO system_attachment_relation
    (tenant_id, attachment_id, business_type, business_id, relation_type, created_by)
SELECT tenant_id, id, business_type, business_id, 'DOCUMENT', created_by
FROM system_attachment
WHERE business_type IS NOT NULL
  AND business_id IS NOT NULL
  AND deleted = 0;

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (102, 1, 90, 'MENU', '数据变更日志', 'SystemChangeLogs', '/system/change-logs',
     'views/system/logs/ChangeLogView.vue', 'system:change-log:view', 'DocumentChecked', 102),
    (952, 1, 95, 'BUTTON', '维护附件关系', NULL, NULL, NULL,
     'system:attachment:relation', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 1, id
FROM system_menu
WHERE tenant_id = 1 AND id IN (102, 952);
