INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'mobile.draft-retention-days', '移动草稿保留天数',
     '7', 'INTEGER', 'MOBILE', '移动端本地加密草稿的默认保留时间', 1),
    (1, 'mobile.max-upload-mb', '移动附件大小上限',
     '10', 'INTEGER', 'MOBILE', '移动端单个现场附件上传大小上限（MB）', 1),
    (1, 'mobile.scan-token-length', '设备扫码令牌长度',
     '64', 'INTEGER', 'MOBILE', '设备安全访问令牌的固定长度', 1);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (60, 1, 0, 'DIRECTORY', '移动作业', NULL, '/mobile', NULL,
     'mobile:access', 'Cellphone', 60),
    (61, 1, 60, 'MENU', '移动工作台', 'MobileWorkbench', '/mobile/workbench',
     'views/mobile/workbench/MobileWorkbenchView.vue',
     'mobile:workbench:view', 'House', 61),
    (62, 1, 60, 'MENU', '设备扫码', 'MobileScan', '/mobile/scan',
     'views/mobile/scan/MobileScanView.vue',
     'mobile:scan', 'FullScreen', 62),
    (63, 1, 60, 'MENU', '现场任务', 'MobileTasks', '/mobile/tasks',
     'views/mobile/tasks/MobileTaskHubView.vue',
     'mobile:task:view', 'Finished', 63),
    (64, 1, 60, 'MENU', '现场消息', 'MobileMessages', '/mobile/messages',
     'views/mobile/messages/MobileMessagesView.vue',
     'mobile:message:view', 'Bell', 64),
    (65, 1, 60, 'MENU', '移动设置', 'MobileProfile', '/mobile/profile',
     'views/mobile/profile/MobileProfileView.vue',
     'mobile:profile:view', 'User', 65);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role.id, menu.id
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.id IN (60, 61, 62, 63, 64, 65)
WHERE role.tenant_id = 1
  AND role.role_code IN (
      'SUPER_ADMIN', 'EQUIPMENT_MANAGER', 'INSPECTOR', 'MAINTAINER'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM system_role_menu existing
      WHERE existing.tenant_id = role.tenant_id
        AND existing.role_id = role.id
        AND existing.menu_id = menu.id
  );
