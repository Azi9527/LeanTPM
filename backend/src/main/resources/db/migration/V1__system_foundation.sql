CREATE TABLE system_tenant (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_code VARCHAR(50) NOT NULL COMMENT '租户编码',
    tenant_name VARCHAR(100) NOT NULL COMMENT '租户名称',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户';

CREATE TABLE system_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    real_name VARCHAR(100) NOT NULL,
    employee_no VARCHAR(50) NULL,
    mobile VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    organization_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    mobile_enabled TINYINT NOT NULL DEFAULT 1,
    must_change_password TINYINT NOT NULL DEFAULT 1,
    last_login_time DATETIME(3) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_organization (tenant_id, organization_id),
    KEY idx_user_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

CREATE TABLE system_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    data_scope VARCHAR(32) NOT NULL DEFAULT 'ALL',
    status TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_code (tenant_id, role_code),
    KEY idx_role_status (tenant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE system_user_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (tenant_id, user_id, role_id),
    KEY idx_user_role_role (tenant_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系';

CREATE TABLE system_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    parent_id BIGINT NOT NULL DEFAULT 0,
    menu_type VARCHAR(16) NOT NULL COMMENT 'DIRECTORY/MENU/BUTTON',
    menu_name VARCHAR(100) NOT NULL,
    route_name VARCHAR(100) NULL,
    route_path VARCHAR(200) NULL,
    component_path VARCHAR(200) NULL,
    permission_code VARCHAR(128) NULL,
    icon VARCHAR(64) NULL,
    visible TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_permission (tenant_id, permission_code),
    KEY idx_menu_parent (tenant_id, parent_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单与权限点';

CREATE TABLE system_role_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (tenant_id, role_id, menu_id),
    KEY idx_role_menu_menu (tenant_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关系';

CREATE TABLE system_dictionary_type (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    dict_code VARCHAR(64) NOT NULL,
    dict_name VARCHAR(100) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type_code (tenant_id, dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型';

CREATE TABLE system_dictionary_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    dict_type_id BIGINT NOT NULL,
    item_value VARCHAR(64) NOT NULL,
    item_label VARCHAR(100) NOT NULL,
    color VARCHAR(32) NULL,
    icon VARCHAR(64) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    is_default TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_item_value (tenant_id, dict_type_id, item_value),
    KEY idx_dict_item_sort (tenant_id, dict_type_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典项';

CREATE TABLE system_login_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    username VARCHAR(64) NOT NULL,
    user_id BIGINT NULL,
    login_ip VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    success TINYINT NOT NULL,
    failure_reason VARCHAR(200) NULL,
    login_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_login_log_username_time (tenant_id, username, login_time),
    KEY idx_login_log_user_time (tenant_id, user_id, login_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

CREATE TABLE system_operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    user_id BIGINT NULL,
    username VARCHAR(64) NULL,
    request_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(300) NOT NULL,
    operation_name VARCHAR(200) NULL,
    request_ip VARCHAR(64) NULL,
    success TINYINT NOT NULL,
    error_message VARCHAR(500) NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_operation_log_user_time (tenant_id, user_id, operation_time),
    KEY idx_operation_log_path_time (tenant_id, request_path(100), operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

CREATE TABLE system_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    business_type VARCHAR(64) NULL,
    business_id BIGINT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    content_type VARCHAR(128) NULL,
    extension VARCHAR(20) NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_attachment_business (tenant_id, business_type, business_id),
    KEY idx_attachment_sha256 (tenant_id, sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件元数据';

INSERT INTO system_tenant (id, tenant_code, tenant_name)
VALUES (1, 'DEFAULT', 'LeanTPM 演示企业');

INSERT INTO system_role (id, tenant_id, role_code, role_name, data_scope, sort_order, remark)
VALUES
    (1, 1, 'SUPER_ADMIN', '超级管理员', 'ALL', 1, '系统内置角色'),
    (2, 1, 'EQUIPMENT_MANAGER', '设备管理员', 'FACTORY', 10, '设备管理业务角色'),
    (3, 1, 'INSPECTOR', '点检员', 'SELF_TASK', 20, '移动点检业务角色'),
    (4, 1, 'MAINTAINER', '维保员', 'SELF_TASK', 30, '移动维保业务角色');

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path, component_path, permission_code, icon, sort_order)
VALUES
    (1, 1, 0, 'MENU', '工作台', 'Dashboard', '/dashboard', 'views/dashboard/DashboardView.vue', 'dashboard:view', 'DataBoard', 1),
    (10, 1, 0, 'DIRECTORY', '设备管理', NULL, '/equipment', NULL, 'equipment:view', 'Monitor', 10),
    (20, 1, 0, 'DIRECTORY', '点检管理', NULL, '/inspection', NULL, 'inspection:view', 'Finished', 20),
    (30, 1, 0, 'DIRECTORY', '维保管理', NULL, '/maintenance', NULL, 'maintenance:view', 'Tools', 30),
    (40, 1, 0, 'DIRECTORY', 'OEE管理', NULL, '/oee', NULL, 'oee:view', 'TrendCharts', 40),
    (50, 1, 0, 'DIRECTORY', '可视化中心', NULL, '/visualization', NULL, 'visualization:view', 'Histogram', 50),
    (80, 1, 0, 'DIRECTORY', '基础数据', NULL, '/master-data', NULL, 'master-data:view', 'OfficeBuilding', 80),
    (90, 1, 0, 'DIRECTORY', '系统管理', NULL, '/system', NULL, 'system:view', 'Setting', 90),
    (91, 1, 90, 'MENU', '用户管理', 'SystemUsers', '/system/users', 'views/system/users/UserListView.vue', 'system:user:view', 'User', 91),
    (92, 1, 90, 'MENU', '角色管理', 'SystemRoles', '/system/roles', 'views/system/roles/RoleListView.vue', 'system:role:view', 'Avatar', 92),
    (93, 1, 90, 'MENU', '菜单权限', 'SystemMenus', '/system/menus', 'views/system/menus/MenuListView.vue', 'system:menu:view', 'Menu', 93),
    (94, 1, 90, 'MENU', '字典管理', 'SystemDictionaries', '/system/dictionaries', 'views/system/dictionaries/DictionaryView.vue', 'system:dictionary:view', 'Collection', 94),
    (95, 1, 90, 'MENU', '附件管理', 'SystemAttachments', '/system/attachments', 'views/system/attachments/AttachmentView.vue', 'system:attachment:view', 'Paperclip', 95),
    (96, 1, 90, 'MENU', '登录日志', 'SystemLoginLogs', '/system/login-logs', 'views/system/logs/LoginLogView.vue', 'system:login-log:view', 'Document', 96),
    (97, 1, 90, 'MENU', '操作日志', 'SystemOperationLogs', '/system/operation-logs', 'views/system/logs/OperationLogView.vue', 'system:operation-log:view', 'Tickets', 97),
    (911, 1, 91, 'BUTTON', '新增用户', NULL, NULL, NULL, 'system:user:create', NULL, 1),
    (912, 1, 91, 'BUTTON', '编辑用户', NULL, NULL, NULL, 'system:user:update', NULL, 2),
    (913, 1, 91, 'BUTTON', '启停用户', NULL, NULL, NULL, 'system:user:status', NULL, 3),
    (914, 1, 91, 'BUTTON', '重置密码', NULL, NULL, NULL, 'system:user:reset-password', NULL, 4),
    (921, 1, 92, 'BUTTON', '新增角色', NULL, NULL, NULL, 'system:role:create', NULL, 1),
    (922, 1, 92, 'BUTTON', '编辑角色', NULL, NULL, NULL, 'system:role:update', NULL, 2),
    (923, 1, 92, 'BUTTON', '角色授权', NULL, NULL, NULL, 'system:role:authorize', NULL, 3),
    (941, 1, 94, 'BUTTON', '维护字典', NULL, NULL, NULL, 'system:dictionary:manage', NULL, 1),
    (951, 1, 95, 'BUTTON', '上传附件', NULL, NULL, NULL, 'system:attachment:upload', NULL, 1);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 1, id FROM system_menu WHERE tenant_id = 1;

INSERT INTO system_dictionary_type (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (1, 1, 'equipment_status', '设备状态', '设备当前状态统一字典'),
    (2, 1, 'yes_no', '是否', '通用是否字典');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, color, icon, sort_order, is_default)
VALUES
    (1, 1, 'NOT_ENABLED', '未启用', '#909399', 'CircleClose', 1, 1),
    (1, 1, 'IDLE', '待机', '#E6A23C', 'VideoPause', 2, 0),
    (1, 1, 'RUNNING', '运行', '#19A974', 'VideoPlay', 3, 0),
    (1, 1, 'COMMISSIONING', '调试', '#409EFF', 'SetUp', 4, 0),
    (1, 1, 'CHANGEOVER', '换型', '#7B61FF', 'Switch', 5, 0),
    (1, 1, 'MAINTENANCE', '保养', '#00A6A6', 'Tools', 6, 0),
    (1, 1, 'INSPECTION', '点检', '#2D7FF9', 'Finished', 7, 0),
    (1, 1, 'FAULT', '故障', '#F56C6C', 'WarningFilled', 8, 0),
    (1, 1, 'REPAIR', '维修', '#C45656', 'Tools', 9, 0),
    (1, 1, 'STOPPED', '停机', '#606266', 'CircleCloseFilled', 10, 0),
    (1, 1, 'SCRAPPED', '报废', '#303133', 'DeleteFilled', 11, 0),
    (1, 1, 'OFFLINE', '离线', '#A8ABB2', 'Connection', 12, 0),
    (1, 2, '1', '是', '#19A974', 'Select', 1, 0),
    (1, 2, '0', '否', '#909399', 'Close', 2, 1);
