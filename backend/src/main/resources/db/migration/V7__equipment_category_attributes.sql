CREATE TABLE equipment_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    category_code VARCHAR(64) NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    tree_level INT NOT NULL DEFAULT 1,
    default_inspection_template_id BIGINT NULL,
    default_maintenance_template_id BIGINT NULL,
    default_fault_type_id BIGINT NULL,
    default_oee_mode VARCHAR(32) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_category_code (tenant_id, category_code),
    KEY idx_equipment_category_parent (tenant_id, parent_id, sort_order),
    KEY idx_equipment_category_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备分类树';

CREATE TABLE equipment_attribute_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    attribute_code VARCHAR(64) NOT NULL,
    attribute_name VARCHAR(100) NOT NULL,
    data_type VARCHAR(20) NOT NULL
        COMMENT 'STRING/INTEGER/DECIMAL/BOOLEAN/DATE/ENUM',
    unit VARCHAR(32) NULL,
    required_flag TINYINT NOT NULL DEFAULT 0,
    default_value VARCHAR(500) NULL,
    validation_pattern VARCHAR(500) NULL,
    minimum_value DECIMAL(20, 6) NULL,
    maximum_value DECIMAL(20, 6) NULL,
    enum_options JSON NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_attribute_code (tenant_id, category_id, attribute_code),
    KEY idx_equipment_attribute_category
        (tenant_id, category_id, status, deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备分类属性定义';

INSERT INTO equipment_category
    (id, tenant_id, parent_id, category_code, category_name, tree_level,
     default_oee_mode, sort_order, description)
VALUES
    (1, 1, 0, 'PRODUCTION', '生产设备', 1, 'STANDARD', 10, '生产制造主设备'),
    (2, 1, 1, 'MACHINING', '机械加工设备', 2, 'STANDARD', 10, '机械加工类设备'),
    (3, 1, 2, 'CNC', '数控加工设备', 3, 'STANDARD', 10, '数控机床及加工中心');

INSERT INTO equipment_attribute_definition
    (tenant_id, category_id, attribute_code, attribute_name, data_type,
     unit, required_flag, minimum_value, sort_order)
VALUES
    (1, 3, 'RATED_POWER', '额定功率', 'DECIMAL', 'kW', 0, 0, 10),
    (1, 3, 'SPINDLE_SPEED', '最高主轴转速', 'INTEGER', 'rpm', 0, 0, 20),
    (1, 3, 'POSITIONING_ACCURACY', '定位精度', 'DECIMAL', 'mm', 0, 0, 30);

INSERT INTO system_dictionary_type
    (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (3, 1, 'equipment_attribute_data_type', '设备属性数据类型', '分类属性模板的数据类型');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, sort_order)
VALUES
    (1, 3, 'STRING', '文本', 1),
    (1, 3, 'INTEGER', '整数', 2),
    (1, 3, 'DECIMAL', '小数', 3),
    (1, 3, 'BOOLEAN', '布尔', 4),
    (1, 3, 'DATE', '日期', 5),
    (1, 3, 'ENUM', '枚举', 6);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (83, 1, 80, 'MENU', '设备分类', 'MasterDataEquipmentCategories',
     '/master-data/equipment-categories',
     'views/master-data/equipment-categories/EquipmentCategoryView.vue',
     'master-data:equipment-category:view', 'SetUp', 83),
    (8301, 1, 83, 'BUTTON', '维护设备分类', NULL, NULL, NULL,
     'master-data:equipment-category:manage', NULL, 1),
    (8302, 1, 83, 'BUTTON', '删除设备分类', NULL, NULL, NULL,
     'master-data:equipment-category:delete', NULL, 2),
    (8303, 1, 83, 'BUTTON', '维护属性模板', NULL, NULL, NULL,
     'master-data:equipment-attribute:manage', NULL, 3);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1 AND id IN (83, 8301, 8302, 8303)
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1 AND id IN (83, 8301, 8303)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
