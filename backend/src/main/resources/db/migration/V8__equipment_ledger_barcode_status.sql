CREATE TABLE equipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_code VARCHAR(64) NOT NULL,
    equipment_name VARCHAR(150) NOT NULL,
    category_id BIGINT NOT NULL,
    model VARCHAR(100) NULL,
    specification VARCHAR(200) NULL,
    brand VARCHAR(100) NULL,
    manufacturer VARCHAR(150) NULL,
    factory_serial_number VARCHAR(100) NULL,
    production_date DATE NULL,
    commissioning_date DATE NULL,
    organization_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    primary_responsible_user_id BIGINT NULL,
    asset_number VARCHAR(100) NULL,
    lifecycle_stage VARCHAR(32) NOT NULL DEFAULT 'IN_SERVICE'
        COMMENT 'PLANNING/INSTALLATION/COMMISSIONING/IN_SERVICE/IDLE/SEALED/SCRAPPED',
    critical_flag TINYINT NOT NULL DEFAULT 0,
    special_flag TINYINT NOT NULL DEFAULT 0,
    oee_enabled TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled',
    description VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_code (tenant_id, equipment_code),
    KEY idx_equipment_category (tenant_id, category_id, status, deleted),
    KEY idx_equipment_organization (tenant_id, organization_id, status, deleted),
    KEY idx_equipment_location (tenant_id, location_id, status, deleted),
    KEY idx_equipment_responsible
        (tenant_id, primary_responsible_user_id, status, deleted),
    KEY idx_equipment_lifecycle (tenant_id, lifecycle_stage, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备电子台账';

CREATE TABLE equipment_attribute_value (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    string_value VARCHAR(1000) NULL,
    integer_value BIGINT NULL,
    decimal_value DECIMAL(20, 6) NULL,
    boolean_value TINYINT NULL,
    date_value DATE NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_attribute_value
        (tenant_id, equipment_id, attribute_definition_id),
    KEY idx_equipment_attribute_definition
        (tenant_id, attribute_definition_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备扩展属性值';

CREATE TABLE equipment_responsible_person (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    responsibility_type VARCHAR(32) NOT NULL
        COMMENT 'PRIMARY/OPERATOR/INSPECTOR/MAINTAINER',
    start_date DATE NULL,
    end_date DATE NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_responsible
        (tenant_id, equipment_id, user_id, responsibility_type),
    KEY idx_equipment_responsible_user
        (tenant_id, user_id, responsibility_type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备责任人';

CREATE TABLE equipment_transfer_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    from_organization_id BIGINT NULL,
    to_organization_id BIGINT NOT NULL,
    from_location_id BIGINT NULL,
    to_location_id BIGINT NOT NULL,
    from_responsible_user_id BIGINT NULL,
    to_responsible_user_id BIGINT NULL,
    transfer_reason VARCHAR(500) NOT NULL,
    transferred_by BIGINT NOT NULL,
    transferred_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_equipment_transfer_equipment
        (tenant_id, equipment_id, transferred_time),
    KEY idx_equipment_transfer_target
        (tenant_id, to_organization_id, to_location_id, transferred_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备转移记录';

CREATE TABLE equipment_barcode (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    access_token CHAR(64) NOT NULL,
    barcode_type VARCHAR(16) NOT NULL DEFAULT 'QR' COMMENT 'QR/CODE128',
    active_slot TINYINT NULL COMMENT '有效条码固定为1，失效条码为NULL',
    generated_by BIGINT NOT NULL,
    generated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    invalidated_by BIGINT NULL,
    invalidated_time DATETIME(3) NULL,
    invalidation_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_barcode_token (tenant_id, access_token),
    UNIQUE KEY uk_equipment_active_barcode (tenant_id, equipment_id, active_slot),
    KEY idx_equipment_barcode_equipment (tenant_id, equipment_id, generated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备安全访问条码';

CREATE TABLE equipment_current_status (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    status_since DATETIME(3) NOT NULL,
    reason VARCHAR(500) NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/INSPECTION/MAINTENANCE/IOT/SYSTEM',
    updated_by BIGINT NOT NULL,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_current_status (tenant_id, equipment_id),
    KEY idx_equipment_current_status_code
        (tenant_id, status_code, status_since)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备当前状态';

CREATE TABLE equipment_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    from_status_code VARCHAR(32) NULL,
    to_status_code VARCHAR(32) NOT NULL,
    started_time DATETIME(3) NOT NULL,
    ended_time DATETIME(3) NULL,
    duration_seconds BIGINT NULL,
    reason VARCHAR(500) NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    changed_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_equipment_status_history
        (tenant_id, equipment_id, started_time),
    KEY idx_equipment_status_code
        (tenant_id, to_status_code, started_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备状态履历';

INSERT INTO system_dictionary_type
    (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (4, 1, 'equipment_lifecycle_stage', '设备生命周期阶段', '设备台账生命周期阶段');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, sort_order)
VALUES
    (1, 4, 'PLANNING', '规划', 1),
    (1, 4, 'INSTALLATION', '安装', 2),
    (1, 4, 'COMMISSIONING', '调试', 3),
    (1, 4, 'IN_SERVICE', '在役', 4),
    (1, 4, 'IDLE', '闲置', 5),
    (1, 4, 'SEALED', '封存', 6),
    (1, 4, 'SCRAPPED', '报废', 7);

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'equipment.barcode.public-base-url', '设备二维码访问地址',
     'http://localhost:5173/m/e', 'STRING', 'EQUIPMENT',
     '二维码仅拼接不可推断的访问令牌，不包含设备敏感信息', 1),
    (1, 'equipment.import.max-rows', '设备单次导入上限',
     '1000', 'INTEGER', 'EQUIPMENT', '设备 Excel 单次导入最大数据行数', 1);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (11, 1, 10, 'MENU', '设备台账', 'EquipmentLedger', '/equipment/ledger',
     'views/equipment/ledger/EquipmentLedgerView.vue',
     'equipment:ledger:view', 'Monitor', 11),
    (12, 1, 10, 'MENU', '设备条码', 'EquipmentBarcodes', '/equipment/barcodes',
     'views/equipment/barcodes/EquipmentBarcodeView.vue',
     'equipment:barcode:view', 'Postcard', 12),
    (13, 1, 10, 'MENU', '设备状态', 'EquipmentStatuses', '/equipment/statuses',
     'views/equipment/statuses/EquipmentStatusView.vue',
     'equipment:status:view', 'DataLine', 13),
    (1101, 1, 11, 'BUTTON', '新增设备', NULL, NULL, NULL,
     'equipment:ledger:create', NULL, 1),
    (1102, 1, 11, 'BUTTON', '编辑设备', NULL, NULL, NULL,
     'equipment:ledger:update', NULL, 2),
    (1103, 1, 11, 'BUTTON', '导入设备', NULL, NULL, NULL,
     'equipment:ledger:import', NULL, 3),
    (1104, 1, 11, 'BUTTON', '导出设备', NULL, NULL, NULL,
     'equipment:ledger:export', NULL, 4),
    (1105, 1, 11, 'BUTTON', '复制设备', NULL, NULL, NULL,
     'equipment:ledger:copy', NULL, 5),
    (1106, 1, 11, 'BUTTON', '转移设备', NULL, NULL, NULL,
     'equipment:ledger:transfer', NULL, 6),
    (1107, 1, 11, 'BUTTON', '启停设备', NULL, NULL, NULL,
     'equipment:ledger:status', NULL, 7),
    (1108, 1, 11, 'BUTTON', '删除设备', NULL, NULL, NULL,
     'equipment:ledger:delete', NULL, 8),
    (1201, 1, 12, 'BUTTON', '维护设备条码', NULL, NULL, NULL,
     'equipment:barcode:manage', NULL, 1),
    (1202, 1, 12, 'BUTTON', '打印设备条码', NULL, NULL, NULL,
     'equipment:barcode:print', NULL, 2),
    (1301, 1, 13, 'BUTTON', '更新设备状态', NULL, NULL, NULL,
     'equipment:status:update', NULL, 1);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (10, 11, 12, 13, 1101, 1102, 1103, 1104, 1105,
                 1106, 1107, 1108, 1201, 1202, 1301)
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (10, 11, 12, 13, 1101, 1102, 1103, 1104, 1105,
                 1106, 1107, 1201, 1202, 1301)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
