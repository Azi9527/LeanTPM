ALTER TABLE organization
    ADD COLUMN description VARCHAR(500) NULL AFTER organization_type;

CREATE TABLE location (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    location_code VARCHAR(64) NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    location_type VARCHAR(32) NOT NULL
        COMMENT 'ENTERPRISE/FACTORY/PLANT_AREA/WORKSHOP/LINE/WORKSTATION',
    organization_id BIGINT NOT NULL,
    manager_user_id BIGINT NULL,
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
    UNIQUE KEY uk_location_code (tenant_id, location_code),
    KEY idx_location_parent (tenant_id, parent_id, sort_order),
    KEY idx_location_organization (tenant_id, organization_id, status, deleted),
    KEY idx_location_manager (tenant_id, manager_user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备物理位置树';

INSERT INTO location
    (id, tenant_id, parent_id, location_code, location_name, location_type,
     organization_id, sort_order)
VALUES
    (1, 1, 0, 'LEANTPM-SITE', 'LeanTPM 企业园区', 'ENTERPRISE', 1, 1),
    (2, 1, 1, 'FACTORY-A-SITE', '示范工厂', 'FACTORY', 2, 10),
    (3, 1, 2, 'WORKSHOP-A-SITE', '装配车间', 'WORKSHOP', 3, 10),
    (4, 1, 3, 'LINE-A-SITE', '装配一线', 'LINE', 4, 10),
    (5, 1, 3, 'LINE-B-SITE', '装配二线', 'LINE', 5, 20);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (81, 1, 80, 'MENU', '组织管理', 'MasterDataOrganizations',
     '/master-data/organizations',
     'views/master-data/organizations/OrganizationView.vue',
     'master-data:organization:view', 'OfficeBuilding', 81),
    (82, 1, 80, 'MENU', '位置管理', 'MasterDataLocations',
     '/master-data/locations',
     'views/master-data/locations/LocationView.vue',
     'master-data:location:view', 'Location', 82),
    (8101, 1, 81, 'BUTTON', '维护组织', NULL, NULL, NULL,
     'master-data:organization:manage', NULL, 1),
    (8102, 1, 81, 'BUTTON', '删除组织', NULL, NULL, NULL,
     'master-data:organization:delete', NULL, 2),
    (8201, 1, 82, 'BUTTON', '维护位置', NULL, NULL, NULL,
     'master-data:location:manage', NULL, 1),
    (8202, 1, 82, 'BUTTON', '删除位置', NULL, NULL, NULL,
     'master-data:location:delete', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1 AND id IN (80, 81, 82, 8101, 8102, 8201, 8202)
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1 AND id IN (80, 81, 82, 8101, 8201)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
