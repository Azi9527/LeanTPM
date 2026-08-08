INSERT IGNORE INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in, status, created_by, updated_by)
VALUES
    (1, 'mobile.android-attachment-id', 'Android APK 附件标识', '0', 'INTEGER',
     'MOBILE', '当前发布 APK 对应的附件标识', 1, 1, 0, 0),
    (1, 'mobile.android-download-enabled', '登录页展示 Android 下载', 'false', 'BOOLEAN',
     'MOBILE', '控制登录页是否展示 Android APP 下载入口', 1, 1, 0, 0),
    (1, 'mobile.android-latest-version-code', 'Android 最新版本号', '1', 'INTEGER',
     'MOBILE', '当前可下载 Android 内部版本号', 1, 1, 0, 0);

INSERT IGNORE INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (103, 1, 90, 'MENU', 'APP 发布管理', 'SystemAppReleases', '/system/app-releases',
     'views/system/app-releases/AppReleaseView.vue', 'system:app-release:view', 'UploadFilled', 103),
    (1031, 1, 103, 'BUTTON', '上传和发布 APP', NULL, NULL,
     NULL, 'system:app-release:manage', NULL, 1);

INSERT IGNORE INTO system_role_menu (tenant_id, role_id, menu_id, created_by)
SELECT role.tenant_id, role.id, menu.id, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.id IN (103, 1031)
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'SUPER_ADMIN')
  AND role.deleted = 0;
