CREATE TABLE visualization_model_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    resource_code VARCHAR(64) NOT NULL,
    resource_name VARCHAR(100) NOT NULL,
    resource_level VARCHAR(32) NOT NULL
        COMMENT 'FACTORY/PLANT_AREA/WORKSHOP/LINE/EQUIPMENT',
    attachment_id BIGINT NULL,
    model_format VARCHAR(16) NOT NULL DEFAULT 'PRIMITIVE'
        COMMENT 'PRIMITIVE/GLB/GLTF',
    primitive_type VARCHAR(32) NULL
        COMMENT 'FACTORY/WORKSHOP/LINE/CNC/ROBOT/PRESS/PUMP/BOX',
    fallback_color VARCHAR(20) NOT NULL DEFAULT '#3B82F6',
    thumbnail_attachment_id BIGINT NULL,
    description VARCHAR(500) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_visualization_model_code
        (tenant_id, resource_code, active_marker),
    KEY idx_visualization_model_level
        (tenant_id, resource_level, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三维模型统一资源';

CREATE TABLE visualization_scene (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parent_scene_id BIGINT NOT NULL DEFAULT 0,
    scene_code VARCHAR(64) NOT NULL,
    scene_name VARCHAR(100) NOT NULL,
    scene_level VARCHAR(32) NOT NULL
        COMMENT 'ENTERPRISE/FACTORY/PLANT_AREA/WORKSHOP/LINE',
    organization_id BIGINT NOT NULL,
    model_resource_id BIGINT NULL,
    background_color VARCHAR(20) NOT NULL DEFAULT '#07111F',
    grid_color VARCHAR(20) NOT NULL DEFAULT '#1E3A5F',
    camera_x DECIMAL(12, 4) NOT NULL DEFAULT 18,
    camera_y DECIMAL(12, 4) NOT NULL DEFAULT 16,
    camera_z DECIMAL(12, 4) NOT NULL DEFAULT 24,
    target_x DECIMAL(12, 4) NOT NULL DEFAULT 0,
    target_y DECIMAL(12, 4) NOT NULL DEFAULT 0,
    target_z DECIMAL(12, 4) NOT NULL DEFAULT 0,
    auto_rotate_flag TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_visualization_scene_code
        (tenant_id, scene_code, active_marker),
    UNIQUE KEY uk_visualization_scene_org
        (tenant_id, organization_id, active_marker),
    KEY idx_visualization_scene_parent
        (tenant_id, parent_scene_id, status, deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三维运行场景';

CREATE TABLE visualization_scene_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scene_id BIGINT NOT NULL,
    node_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    node_type VARCHAR(32) NOT NULL
        COMMENT 'ORGANIZATION/EQUIPMENT/DECORATION',
    organization_id BIGINT NULL,
    equipment_id BIGINT NULL,
    target_scene_id BIGINT NULL,
    model_resource_id BIGINT NULL,
    position_x DECIMAL(12, 4) NOT NULL DEFAULT 0,
    position_y DECIMAL(12, 4) NOT NULL DEFAULT 0,
    position_z DECIMAL(12, 4) NOT NULL DEFAULT 0,
    rotation_x DECIMAL(12, 6) NOT NULL DEFAULT 0,
    rotation_y DECIMAL(12, 6) NOT NULL DEFAULT 0,
    rotation_z DECIMAL(12, 6) NOT NULL DEFAULT 0,
    scale_x DECIMAL(12, 4) NOT NULL DEFAULT 1,
    scale_y DECIMAL(12, 4) NOT NULL DEFAULT 1,
    scale_z DECIMAL(12, 4) NOT NULL DEFAULT 1,
    label_visible_flag TINYINT NOT NULL DEFAULT 1,
    visible_flag TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_visualization_scene_node
        (tenant_id, scene_id, node_code, active_marker),
    KEY idx_visualization_node_equipment
        (tenant_id, equipment_id, deleted),
    KEY idx_visualization_node_organization
        (tenant_id, organization_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三维场景业务节点';

CREATE TABLE visualization_status_color (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    status_name VARCHAR(64) NOT NULL,
    display_color VARCHAR(20) NOT NULL,
    emissive_color VARCHAR(20) NOT NULL,
    pulse_flag TINYINT NOT NULL DEFAULT 0,
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
    UNIQUE KEY uk_visualization_status_color (tenant_id, status_code),
    KEY idx_visualization_status_sort
        (tenant_id, status, deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三维设备状态颜色配置';

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'visualization.refresh-seconds', '可视化自动刷新秒数',
     '15', 'INTEGER', 'VISUALIZATION', '大屏与三维场景默认自动刷新频率', 1),
    (1, 'visualization.long-stop-minutes', '长时间停机阈值',
     '120', 'INTEGER', 'VISUALIZATION', '设备状态大屏长停设备判定分钟数', 1),
    (1, 'visualization.long-offline-minutes', '长时间离线阈值',
     '60', 'INTEGER', 'VISUALIZATION', '设备状态大屏长离线判定分钟数', 1);

INSERT INTO visualization_status_color
    (tenant_id, status_code, status_name, display_color, emissive_color,
     pulse_flag, sort_order, description)
VALUES
    (1, 'NOT_ENABLED', '未启用', '#94A3B8', '#475569', 0, 10, '未启用设备'),
    (1, 'IDLE', '待机', '#38BDF8', '#0EA5E9', 0, 20, '可运行但当前待机'),
    (1, 'RUNNING', '运行', '#22C55E', '#16A34A', 1, 30, '设备正常运行'),
    (1, 'COMMISSIONING', '调试', '#06B6D4', '#0891B2', 1, 40, '安装或调试'),
    (1, 'CHANGEOVER', '换型', '#F59E0B', '#D97706', 1, 50, '换型与调整'),
    (1, 'MAINTENANCE', '保养', '#A855F7', '#7E22CE', 1, 60, '计划维保'),
    (1, 'INSPECTION', '点检', '#14B8A6', '#0F766E', 1, 70, '设备点检'),
    (1, 'FAULT', '故障', '#EF4444', '#B91C1C', 1, 80, '设备故障'),
    (1, 'REPAIR', '维修', '#F97316', '#C2410C', 1, 90, '故障维修'),
    (1, 'STOPPED', '停机', '#64748B', '#334155', 0, 100, '主动停机'),
    (1, 'OFFLINE', '离线', '#475569', '#1E293B', 0, 110, '数据离线'),
    (1, 'SCRAPPED', '报废', '#1F2937', '#111827', 0, 120, '设备报废');

INSERT INTO organization
    (tenant_id, parent_id, organization_code, organization_name,
     organization_type, sort_order, description)
SELECT 1, factory.id, 'WORKSHOP-B', '机加车间', 'WORKSHOP', 20,
       'M5 三维可视化示例车间'
FROM organization factory
WHERE factory.tenant_id = 1
  AND factory.organization_code = 'FACTORY-A'
  AND factory.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM organization existing
      WHERE existing.tenant_id = 1
        AND existing.organization_code = 'WORKSHOP-B'
  );

INSERT INTO organization
    (tenant_id, parent_id, organization_code, organization_name,
     organization_type, sort_order, description)
SELECT 1, workshop.id, code_value, name_value, 'LINE', sort_value,
       'M5 三维可视化示例产线'
FROM organization workshop
CROSS JOIN (
    SELECT 'LINE-C' AS code_value, '机加一线' AS name_value, 10 AS sort_value
    UNION ALL
    SELECT 'LINE-D', '机加二线', 20
) seed
WHERE workshop.tenant_id = 1
  AND workshop.organization_code = 'WORKSHOP-B'
  AND workshop.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM organization existing
      WHERE existing.tenant_id = 1
        AND existing.organization_code = seed.code_value
  );

INSERT INTO location
    (tenant_id, parent_id, location_code, location_name, location_type,
     organization_id, sort_order, description)
SELECT 1, factory_site.id, 'WORKSHOP-B-SITE', '机加车间', 'WORKSHOP',
       workshop.id, 20, 'M5 三维可视化示例位置'
FROM location factory_site
JOIN organization workshop
  ON workshop.tenant_id = 1
 AND workshop.organization_code = 'WORKSHOP-B'
 AND workshop.deleted = 0
WHERE factory_site.tenant_id = 1
  AND factory_site.location_code = 'FACTORY-A-SITE'
  AND factory_site.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM location existing
      WHERE existing.tenant_id = 1
        AND existing.location_code = 'WORKSHOP-B-SITE'
  );

INSERT INTO location
    (tenant_id, parent_id, location_code, location_name, location_type,
     organization_id, sort_order, description)
SELECT 1, workshop_site.id, CONCAT(seed.code_value, '-SITE'),
       seed.name_value, 'LINE', line_org.id, seed.sort_value,
       'M5 三维可视化示例位置'
FROM location workshop_site
CROSS JOIN (
    SELECT 'LINE-C' AS code_value, '机加一线' AS name_value, 10 AS sort_value
    UNION ALL
    SELECT 'LINE-D', '机加二线', 20
) seed
JOIN organization line_org
  ON line_org.tenant_id = 1
 AND line_org.organization_code = seed.code_value
 AND line_org.deleted = 0
WHERE workshop_site.tenant_id = 1
  AND workshop_site.location_code = 'WORKSHOP-B-SITE'
  AND workshop_site.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM location existing
      WHERE existing.tenant_id = 1
        AND existing.location_code = CONCAT(seed.code_value, '-SITE')
  );

INSERT INTO equipment_category
    (tenant_id, parent_id, category_code, category_name, tree_level,
     default_oee_mode, sort_order, description)
SELECT 1, 0, 'VISUALIZATION-DEMO', '可视化演示设备', 1,
       'STANDARD', 90, 'M5 可视化演示专用分类，避免影响业务方案适用范围'
WHERE NOT EXISTS (
    SELECT 1
    FROM equipment_category existing
    WHERE existing.tenant_id = 1
      AND existing.category_code = 'VISUALIZATION-DEMO'
);

INSERT INTO equipment
    (tenant_id, equipment_code, equipment_name, category_id, model,
     specification, brand, manufacturer, factory_serial_number,
     commissioning_date, organization_id, location_id,
     primary_responsible_user_id, asset_number, lifecycle_stage,
     critical_flag, oee_enabled, status, description, created_by, updated_by)
SELECT
    1, seed.equipment_code, seed.equipment_name, category.id, seed.model,
    'M5 三维示例设备', 'LeanTPM', 'LeanTPM Demo',
    CONCAT('VIZ-', seed.equipment_code), '2026-01-01',
    organization.id, location.id, NULL,
    CONCAT('ASSET-', seed.equipment_code), 'IN_SERVICE',
    seed.critical_flag, 1, 1, 'M5 可视化真实演示设备', 0, 0
FROM (
    SELECT 'VIZ-CNC-01' AS equipment_code, '一号数控加工中心' AS equipment_name,
           'LINE-A' AS organization_code, 'LINE-A-SITE' AS location_code,
           'CNC-850' AS model, 1 AS critical_flag
    UNION ALL SELECT 'VIZ-CNC-02', '二号数控加工中心', 'LINE-A', 'LINE-A-SITE', 'CNC-850', 1
    UNION ALL SELECT 'VIZ-ROBOT-01', '装配机器人一号', 'LINE-B', 'LINE-B-SITE', 'ROBOT-R6', 0
    UNION ALL SELECT 'VIZ-ROBOT-02', '装配机器人二号', 'LINE-B', 'LINE-B-SITE', 'ROBOT-R6', 0
    UNION ALL SELECT 'VIZ-CNC-03', '三号数控加工中心', 'LINE-C', 'LINE-C-SITE', 'CNC-1160', 1
    UNION ALL SELECT 'VIZ-CNC-04', '四号数控加工中心', 'LINE-C', 'LINE-C-SITE', 'CNC-1160', 1
    UNION ALL SELECT 'VIZ-PRESS-01', '液压压力机一号', 'LINE-D', 'LINE-D-SITE', 'PRESS-400', 1
    UNION ALL SELECT 'VIZ-PUMP-01', '循环泵站一号', 'LINE-D', 'LINE-D-SITE', 'PUMP-X2', 0
) seed
JOIN equipment_category category
  ON category.tenant_id = 1
 AND category.category_code = 'VISUALIZATION-DEMO'
 AND category.deleted = 0
JOIN organization
  ON organization.tenant_id = 1
 AND organization.organization_code = seed.organization_code
 AND organization.deleted = 0
JOIN location
  ON location.tenant_id = 1
 AND location.location_code = seed.location_code
 AND location.deleted = 0
WHERE NOT EXISTS (
    SELECT 1 FROM equipment existing
    WHERE existing.tenant_id = 1
      AND existing.equipment_code = seed.equipment_code
);

INSERT INTO equipment_current_status
    (tenant_id, equipment_id, status_code, status_since, reason,
     source_type, updated_by)
SELECT 1, equipment.id, seed.status_code,
       DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL seed.duration_minutes MINUTE),
       'M5 可视化演示状态', 'SYSTEM', 0
FROM (
    SELECT 'VIZ-CNC-01' AS equipment_code, 'RUNNING' AS status_code, 42 AS duration_minutes
    UNION ALL SELECT 'VIZ-CNC-02', 'IDLE', 18
    UNION ALL SELECT 'VIZ-ROBOT-01', 'RUNNING', 96
    UNION ALL SELECT 'VIZ-ROBOT-02', 'INSPECTION', 12
    UNION ALL SELECT 'VIZ-CNC-03', 'FAULT', 35
    UNION ALL SELECT 'VIZ-CNC-04', 'REPAIR', 74
    UNION ALL SELECT 'VIZ-PRESS-01', 'STOPPED', 185
    UNION ALL SELECT 'VIZ-PUMP-01', 'OFFLINE', 130
) seed
JOIN equipment
  ON equipment.tenant_id = 1
 AND equipment.equipment_code = seed.equipment_code
 AND equipment.deleted = 0
WHERE NOT EXISTS (
    SELECT 1 FROM equipment_current_status current_status
    WHERE current_status.tenant_id = 1
      AND current_status.equipment_id = equipment.id
);

INSERT INTO equipment_status_history
    (tenant_id, equipment_id, from_status_code, to_status_code,
     started_time, reason, source_type, changed_by)
SELECT current_status.tenant_id, current_status.equipment_id, NULL,
       current_status.status_code, current_status.status_since,
       current_status.reason, current_status.source_type, current_status.updated_by
FROM equipment_current_status current_status
JOIN equipment
  ON equipment.tenant_id = current_status.tenant_id
 AND equipment.id = current_status.equipment_id
 AND equipment.equipment_code LIKE 'VIZ-%'
WHERE NOT EXISTS (
    SELECT 1 FROM equipment_status_history history
    WHERE history.tenant_id = current_status.tenant_id
      AND history.equipment_id = current_status.equipment_id
);

INSERT INTO visualization_model_resource
    (tenant_id, resource_code, resource_name, resource_level, model_format,
     primitive_type, fallback_color, description)
VALUES
    (1, 'PRIM-FACTORY', '工厂示例模型', 'FACTORY', 'PRIMITIVE',
     'FACTORY', '#0EA5E9', '无外部模型时使用程序化工厂场景'),
    (1, 'PRIM-WORKSHOP', '车间示例模型', 'WORKSHOP', 'PRIMITIVE',
     'WORKSHOP', '#38BDF8', '程序化车间建筑'),
    (1, 'PRIM-LINE', '产线示例模型', 'LINE', 'PRIMITIVE',
     'LINE', '#6366F1', '程序化生产线'),
    (1, 'PRIM-CNC', '数控设备示例模型', 'EQUIPMENT', 'PRIMITIVE',
     'CNC', '#22C55E', '程序化数控机床'),
    (1, 'PRIM-ROBOT', '机器人示例模型', 'EQUIPMENT', 'PRIMITIVE',
     'ROBOT', '#14B8A6', '程序化机器人'),
    (1, 'PRIM-PRESS', '压力机示例模型', 'EQUIPMENT', 'PRIMITIVE',
     'PRESS', '#F59E0B', '程序化压力机'),
    (1, 'PRIM-PUMP', '泵站示例模型', 'EQUIPMENT', 'PRIMITIVE',
     'PUMP', '#06B6D4', '程序化泵站');

INSERT INTO visualization_scene
    (tenant_id, parent_scene_id, scene_code, scene_name, scene_level,
     organization_id, model_resource_id, camera_x, camera_y, camera_z,
     sort_order, description)
SELECT 1, 0, 'SCENE-FACTORY-A', '示范工厂', 'FACTORY', organization.id,
       model.id, 22, 18, 28, 10, '工厂总览示例场景'
FROM organization
JOIN visualization_model_resource model
  ON model.tenant_id = 1 AND model.resource_code = 'PRIM-FACTORY' AND model.deleted = 0
WHERE organization.tenant_id = 1
  AND organization.organization_code = 'FACTORY-A'
  AND organization.deleted = 0;

INSERT INTO visualization_scene
    (tenant_id, parent_scene_id, scene_code, scene_name, scene_level,
     organization_id, model_resource_id, camera_x, camera_y, camera_z,
     sort_order, description)
SELECT 1, factory_scene.id, CONCAT('SCENE-', organization.organization_code),
       organization.organization_name, 'WORKSHOP', organization.id,
       model.id, 18, 14, 22, organization.sort_order, '车间示例场景'
FROM organization
JOIN visualization_scene factory_scene
  ON factory_scene.tenant_id = 1 AND factory_scene.scene_code = 'SCENE-FACTORY-A'
 AND factory_scene.deleted = 0
JOIN visualization_model_resource model
  ON model.tenant_id = 1 AND model.resource_code = 'PRIM-WORKSHOP' AND model.deleted = 0
WHERE organization.tenant_id = 1
  AND organization.organization_code IN ('WORKSHOP-A', 'WORKSHOP-B')
  AND organization.deleted = 0;

INSERT INTO visualization_scene
    (tenant_id, parent_scene_id, scene_code, scene_name, scene_level,
     organization_id, model_resource_id, camera_x, camera_y, camera_z,
     sort_order, description)
SELECT 1, workshop_scene.id, CONCAT('SCENE-', line_org.organization_code),
       line_org.organization_name, 'LINE', line_org.id,
       model.id, 16, 11, 20, line_org.sort_order, '产线设备示例场景'
FROM organization line_org
JOIN organization workshop_org
  ON workshop_org.tenant_id = line_org.tenant_id
 AND workshop_org.id = line_org.parent_id
 AND workshop_org.deleted = 0
JOIN visualization_scene workshop_scene
  ON workshop_scene.tenant_id = 1
 AND workshop_scene.organization_id = workshop_org.id
 AND workshop_scene.deleted = 0
JOIN visualization_model_resource model
  ON model.tenant_id = 1 AND model.resource_code = 'PRIM-LINE' AND model.deleted = 0
WHERE line_org.tenant_id = 1
  AND line_org.organization_code IN ('LINE-A', 'LINE-B', 'LINE-C', 'LINE-D')
  AND line_org.deleted = 0;

INSERT INTO visualization_scene_node
    (tenant_id, scene_id, node_code, display_name, node_type,
     organization_id, target_scene_id, model_resource_id,
     position_x, position_y, position_z, scale_x, scale_y, scale_z, sort_order)
SELECT 1, factory_scene.id, workshop.organization_code,
       workshop.organization_name, 'ORGANIZATION', workshop.id,
       target_scene.id, model.id,
       CASE workshop.organization_code WHEN 'WORKSHOP-A' THEN -8 ELSE 8 END,
       0, 0, 5, 2.6, 4, workshop.sort_order
FROM organization workshop
JOIN visualization_scene factory_scene
  ON factory_scene.tenant_id = 1 AND factory_scene.scene_code = 'SCENE-FACTORY-A'
 AND factory_scene.deleted = 0
JOIN visualization_scene target_scene
  ON target_scene.tenant_id = 1 AND target_scene.organization_id = workshop.id
 AND target_scene.deleted = 0
JOIN visualization_model_resource model
  ON model.tenant_id = 1 AND model.resource_code = 'PRIM-WORKSHOP' AND model.deleted = 0
WHERE workshop.tenant_id = 1
  AND workshop.organization_code IN ('WORKSHOP-A', 'WORKSHOP-B')
  AND workshop.deleted = 0;

INSERT INTO visualization_scene_node
    (tenant_id, scene_id, node_code, display_name, node_type,
     organization_id, target_scene_id, model_resource_id,
     position_x, position_y, position_z, scale_x, scale_y, scale_z, sort_order)
SELECT 1, workshop_scene.id, line_org.organization_code,
       line_org.organization_name, 'ORGANIZATION', line_org.id,
       line_scene.id, model.id,
       CASE WHEN line_org.sort_order = 10 THEN -6 ELSE 6 END,
       0, 0, 4.5, 1.6, 2.4, line_org.sort_order
FROM organization line_org
JOIN visualization_scene workshop_scene
  ON workshop_scene.tenant_id = 1
 AND workshop_scene.organization_id = line_org.parent_id
 AND workshop_scene.deleted = 0
JOIN visualization_scene line_scene
  ON line_scene.tenant_id = 1 AND line_scene.organization_id = line_org.id
 AND line_scene.deleted = 0
JOIN visualization_model_resource model
  ON model.tenant_id = 1 AND model.resource_code = 'PRIM-LINE' AND model.deleted = 0
WHERE line_org.tenant_id = 1
  AND line_org.organization_code IN ('LINE-A', 'LINE-B', 'LINE-C', 'LINE-D')
  AND line_org.deleted = 0;

INSERT INTO visualization_scene_node
    (tenant_id, scene_id, node_code, display_name, node_type,
     equipment_id, model_resource_id, position_x, position_y, position_z,
     scale_x, scale_y, scale_z, sort_order)
SELECT 1, scene.id, equipment.equipment_code, equipment.equipment_name,
       'EQUIPMENT', equipment.id, model.id,
       CASE WHEN MOD(ROW_NUMBER() OVER (
           PARTITION BY equipment.organization_id ORDER BY equipment.id
       ), 2) = 1 THEN -4 ELSE 4 END,
       0, 0, 2.5, 2.5, 2.5,
       ROW_NUMBER() OVER (
           PARTITION BY equipment.organization_id ORDER BY equipment.id
       ) * 10
FROM equipment
JOIN visualization_scene scene
  ON scene.tenant_id = equipment.tenant_id
 AND scene.organization_id = equipment.organization_id
 AND scene.scene_level = 'LINE'
 AND scene.deleted = 0
JOIN visualization_model_resource model
  ON model.tenant_id = 1
 AND model.resource_code = CASE
     WHEN equipment.equipment_code LIKE 'VIZ-ROBOT%' THEN 'PRIM-ROBOT'
     WHEN equipment.equipment_code LIKE 'VIZ-PRESS%' THEN 'PRIM-PRESS'
     WHEN equipment.equipment_code LIKE 'VIZ-PUMP%' THEN 'PRIM-PUMP'
     ELSE 'PRIM-CNC'
 END
 AND model.deleted = 0
WHERE equipment.tenant_id = 1
  AND equipment.equipment_code LIKE 'VIZ-%'
  AND equipment.deleted = 0;

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (51, 1, 50, 'MENU', '设备综合大屏', 'VisualizationCockpit',
     '/visualization/cockpit',
     'views/visualization/cockpit/OperationsCockpitView.vue',
     'visualization:cockpit:view', 'DataBoard', 51),
    (52, 1, 50, 'MENU', '三维运行大屏', 'VisualizationThree',
     '/visualization/three',
     'views/visualization/three/ThreeSceneView.vue',
     'visualization:3d:view', 'Platform', 52),
    (53, 1, 50, 'MENU', '设备状态大屏', 'VisualizationStatus',
     '/visualization/status',
     'views/visualization/topic/VisualizationTopicView.vue',
     'visualization:status:view', 'Monitor', 53),
    (54, 1, 50, 'MENU', '点检分析大屏', 'VisualizationInspection',
     '/visualization/inspection',
     'views/visualization/topic/VisualizationTopicView.vue',
     'visualization:inspection:view', 'CircleCheck', 54),
    (55, 1, 50, 'MENU', '维保分析大屏', 'VisualizationMaintenance',
     '/visualization/maintenance',
     'views/visualization/topic/VisualizationTopicView.vue',
     'visualization:maintenance:view', 'Tools', 55),
    (56, 1, 50, 'MENU', 'OEE分析大屏', 'VisualizationOee',
     '/visualization/oee',
     'views/visualization/topic/VisualizationTopicView.vue',
     'visualization:oee:view', 'TrendCharts', 56),
    (57, 1, 50, 'MENU', '三维场景配置', 'VisualizationScenes',
     '/visualization/scenes',
     'views/visualization/scenes/SceneManagementView.vue',
     'visualization:scene:view', 'Setting', 57),
    (5701, 1, 57, 'BUTTON', '维护模型资源', NULL, NULL, NULL,
     'visualization:model:manage', NULL, 1),
    (5702, 1, 57, 'BUTTON', '维护场景节点', NULL, NULL, NULL,
     'visualization:scene:manage', NULL, 2),
    (5703, 1, 57, 'BUTTON', '维护状态颜色', NULL, NULL, NULL,
     'visualization:status-color:manage', NULL, 3);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (50, 51, 52, 53, 54, 55, 56, 57, 5701, 5702, 5703)
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (50, 51, 52, 53, 54, 55, 56, 57, 5701, 5702, 5703)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
