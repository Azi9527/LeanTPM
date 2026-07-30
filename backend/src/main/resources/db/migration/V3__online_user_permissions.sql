INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (100, 1, 90, 'MENU', '在线用户', 'SystemOnlineUsers', '/system/online-users',
     'views/system/online-users/OnlineUserView.vue', 'system:online-user:view', 'Connection', 100),
    (1001, 1, 100, 'BUTTON', '强制下线', NULL, NULL, NULL,
     'system:online-user:kickout', NULL, 1);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 1, id
FROM system_menu
WHERE tenant_id = 1
  AND id IN (100, 1001);
