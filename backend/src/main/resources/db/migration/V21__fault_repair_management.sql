CREATE TABLE equipment_fault_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    report_code VARCHAR(64) NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    fault_time DATETIME(3) NOT NULL,
    fault_title VARCHAR(200) NOT NULL,
    fault_description VARCHAR(2000) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/INSPECTION/MAINTENANCE',
    source_business_id BIGINT NULL,
    reporter_user_id BIGINT NOT NULL,
    report_status VARCHAR(32) NOT NULL DEFAULT 'REPORTED'
        COMMENT 'REPORTED/ACCEPTED/REJECTED/CONVERTED/CLOSED/CANCELLED',
    acceptance_comment VARCHAR(1000) NULL,
    rejected_reason VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fault_report_code (tenant_id, report_code),
    UNIQUE KEY uk_fault_report_source (tenant_id, source_type, source_business_id),
    KEY idx_fault_report_equipment (tenant_id, equipment_id, fault_time, deleted),
    KEY idx_fault_report_status (tenant_id, report_status, organization_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备故障报修单';

CREATE TABLE equipment_repair_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    repair_code VARCHAR(64) NOT NULL,
    fault_report_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    repair_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ASSIGNMENT'
        COMMENT 'PENDING_ASSIGNMENT/ASSIGNED/IN_PROGRESS/PAUSED/PENDING_ACCEPTANCE/CLOSED/CANCELLED',
    primary_repairer_user_id BIGINT NULL,
    assigned_time DATETIME(3) NULL,
    started_time DATETIME(3) NULL,
    paused_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    accepted_time DATETIME(3) NULL,
    total_paused_seconds BIGINT NOT NULL DEFAULT 0,
    effective_work_seconds BIGINT NOT NULL DEFAULT 0,
    repair_measure VARCHAR(3000) NULL,
    repair_conclusion VARCHAR(2000) NULL,
    acceptance_result VARCHAR(16) NULL COMMENT 'PASSED/REJECTED',
    acceptance_comment VARCHAR(1000) NULL,
    restore_status_code VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    cancel_reason VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_order_code (tenant_id, repair_code),
    UNIQUE KEY uk_repair_order_report (tenant_id, fault_report_id),
    KEY idx_repair_order_assignee
        (tenant_id, primary_repairer_user_id, repair_status, deleted),
    KEY idx_repair_order_status (tenant_id, repair_status, organization_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立维修工单';

CREATE TABLE equipment_repair_collaborator (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    repair_order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_repair_collaborator (tenant_id, repair_order_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修协作人员';

CREATE TABLE equipment_repair_material (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    repair_order_id BIGINT NOT NULL,
    material_code VARCHAR(64) NULL,
    material_name VARCHAR(150) NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    unit VARCHAR(32) NULL,
    unit_price DECIMAL(18,4) NOT NULL DEFAULT 0,
    total_amount DECIMAL(18,4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_repair_material_order (tenant_id, repair_order_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修备件与费用';

CREATE TABLE equipment_repair_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    repair_order_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    event_remark VARCHAR(1000) NULL,
    operator_id BIGINT NOT NULL,
    event_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_repair_event_order (tenant_id, repair_order_id, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修工单状态事件';

CREATE TABLE equipment_fault_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    fault_report_id BIGINT NOT NULL,
    repair_order_id BIGINT NULL,
    attachment_id BIGINT NOT NULL,
    attachment_stage VARCHAR(32) NOT NULL COMMENT 'REPORT/REPAIR/ACCEPTANCE',
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fault_attachment_stage
        (tenant_id, fault_report_id, attachment_id, attachment_stage),
    KEY idx_fault_attachment_order (tenant_id, repair_order_id, attachment_stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障维修证据附件';

ALTER TABLE inspection_abnormal
    ADD COLUMN repair_order_id BIGINT NULL AFTER requested_equipment_status,
    ADD UNIQUE KEY uk_inspection_abnormal_repair (tenant_id, repair_order_id);

ALTER TABLE maintenance_abnormal
    ADD COLUMN repair_order_id BIGINT NULL AFTER requested_equipment_status,
    ADD UNIQUE KEY uk_maintenance_abnormal_repair (tenant_id, repair_order_id);

INSERT INTO system_number_rule
    (id, tenant_id, rule_code, rule_name, prefix, date_pattern,
     separator_value, sequence_length, reset_period, description)
VALUES
    (8, 1, 'FAULT_REPORT', '故障报修编号', 'BX', 'yyyyMMdd', '-', 5, 'DAILY', '设备故障报修单编号'),
    (9, 1, 'REPAIR_ORDER', '维修工单编号', 'WX', 'yyyyMMdd', '-', 5, 'DAILY', '独立维修工单编号');

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (74, 1, 0, 'DIRECTORY', '故障维修', NULL, '/faults', NULL,
     'fault:view', 'Tools', 35),
    (741, 1, 74, 'MENU', '故障报修', 'FaultReports', '/faults/reports',
     'views/faults/FaultReportView.vue', 'fault:report:view', 'Warning', 351),
    (742, 1, 74, 'MENU', '维修工单', 'RepairOrders', '/faults/repairs',
     'views/faults/RepairOrderView.vue', 'fault:repair:view', 'Tools', 352),
    (743, 1, 74, 'MENU', '我的维修', 'MyRepairs', '/faults/my-repairs',
     'views/faults/RepairOrderView.vue', 'fault:repair:execute', 'User', 353),
    (744, 1, 74, 'MENU', '故障统计', 'FaultStatistics', '/faults/statistics',
     'views/faults/FaultStatisticsView.vue', 'fault:statistics:view', 'TrendCharts', 354),
    (7411, 1, 741, 'BUTTON', '创建报修', NULL, NULL, NULL, 'fault:report:create', NULL, 1),
    (7412, 1, 741, 'BUTTON', '受理报修', NULL, NULL, NULL, 'fault:report:accept', NULL, 2),
    (7413, 1, 741, 'BUTTON', '取消报修', NULL, NULL, NULL, 'fault:report:cancel', NULL, 3),
    (7421, 1, 742, 'BUTTON', '创建工单', NULL, NULL, NULL, 'fault:repair:create', NULL, 1),
    (7422, 1, 742, 'BUTTON', '维修派工', NULL, NULL, NULL, 'fault:repair:assign', NULL, 2),
    (7423, 1, 742, 'BUTTON', '维修验收', NULL, NULL, NULL, 'fault:repair:accept', NULL, 3),
    (7424, 1, 742, 'BUTTON', '维修材料', NULL, NULL, NULL, 'fault:material:manage', NULL, 4);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT 1, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu ON menu.tenant_id = role.tenant_id
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'PLANNER', 'WORKSHOP_MANAGER')
  AND menu.id IN (74,741,742,743,744,7411,7412,7413,7421,7422,7423,7424)
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT 1, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu ON menu.tenant_id = role.tenant_id
WHERE role.tenant_id = 1
  AND role.role_code IN ('TEAM_LEADER', 'OPERATOR')
  AND menu.id IN (74,741,742,743,7411)
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;
