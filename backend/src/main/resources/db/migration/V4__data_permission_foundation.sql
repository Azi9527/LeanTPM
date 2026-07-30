CREATE TABLE organization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    organization_code VARCHAR(64) NOT NULL,
    organization_name VARCHAR(100) NOT NULL,
    organization_type VARCHAR(32) NOT NULL COMMENT 'ENTERPRISE/FACTORY/WORKSHOP/LINE/TEAM',
    manager_user_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_organization_code (tenant_id, organization_code),
    KEY idx_organization_parent (tenant_id, parent_id, sort_order),
    KEY idx_organization_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织树基础';

CREATE TABLE system_data_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scope_code VARCHAR(64) NOT NULL,
    scope_name VARCHAR(100) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    description VARCHAR(500) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_scope_code (tenant_id, scope_code),
    KEY idx_data_scope_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据范围定义';

CREATE TABLE system_role_data_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    data_scope_id BIGINT NOT NULL,
    organization_id BIGINT NULL COMMENT '仅CUSTOM范围使用',
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_role_data_scope_role (tenant_id, role_id, deleted),
    KEY idx_role_data_scope_org (tenant_id, organization_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色数据范围关系';

INSERT INTO organization
    (id, tenant_id, parent_id, organization_code, organization_name, organization_type, sort_order)
VALUES
    (1, 1, 0, 'LEANTPM', 'LeanTPM 演示企业', 'ENTERPRISE', 1),
    (2, 1, 1, 'FACTORY-A', '示范工厂', 'FACTORY', 10),
    (3, 1, 2, 'WORKSHOP-A', '装配车间', 'WORKSHOP', 10),
    (4, 1, 3, 'LINE-A', '装配一线', 'LINE', 10),
    (5, 1, 3, 'LINE-B', '装配二线', 'LINE', 20);

UPDATE system_user
SET organization_id = 1
WHERE tenant_id = 1 AND organization_id IS NULL AND deleted = 0;

INSERT INTO system_data_scope
    (id, tenant_id, scope_code, scope_name, scope_type, description, sort_order)
VALUES
    (1, 1, 'ALL', '全部数据', 'ALL', '可访问当前租户的全部业务数据', 1),
    (2, 1, 'ORGANIZATION', '本组织数据', 'ORGANIZATION', '仅当前用户所属组织', 10),
    (3, 1, 'ORGANIZATION_AND_CHILDREN', '本组织及下级', 'ORGANIZATION_AND_CHILDREN',
     '当前用户所属组织及全部下级组织', 20),
    (4, 1, 'SELF', '仅本人数据', 'SELF', '仅本人创建、负责或被分派的数据', 30),
    (5, 1, 'CUSTOM', '自定义组织', 'CUSTOM', '由角色显式选择可访问组织', 40);

UPDATE system_role
SET data_scope = CASE data_scope
    WHEN 'FACTORY' THEN 'ORGANIZATION_AND_CHILDREN'
    WHEN 'WORKSHOP' THEN 'ORGANIZATION_AND_CHILDREN'
    WHEN 'TEAM' THEN 'ORGANIZATION'
    WHEN 'RESPONSIBLE_EQUIPMENT' THEN 'SELF'
    WHEN 'SELF_TASK' THEN 'SELF'
    ELSE data_scope
END
WHERE tenant_id = 1 AND deleted = 0;

INSERT INTO system_role_data_scope
    (tenant_id, role_id, data_scope_id, created_by, updated_by)
SELECT r.tenant_id, r.id, ds.id, 0, 0
FROM system_role r
JOIN system_data_scope ds
  ON ds.tenant_id = r.tenant_id
 AND ds.scope_code = r.data_scope
 AND ds.deleted = 0
WHERE r.tenant_id = 1 AND r.deleted = 0;

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (101, 1, 90, 'MENU', '数据权限', 'SystemDataScopes', '/system/data-scopes',
     'views/system/data-scopes/DataScopeView.vue', 'system:data-scope:view', 'Key', 101),
    (1011, 1, 101, 'BUTTON', '配置数据范围', NULL, NULL, NULL,
     'system:data-scope:manage', NULL, 1);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 1, id
FROM system_menu
WHERE tenant_id = 1 AND id IN (101, 1011);
