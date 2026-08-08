INSERT INTO system_menu
    (tenant_id, parent_id, menu_type, menu_name, permission_code, visible, status,
     sort_order, created_by, updated_by)
SELECT tenant.id, menu.id, 'BUTTON', '启停菜单', 'system:menu:manage', 1, 1,
       1, 1, 1
FROM system_tenant tenant
JOIN system_menu menu
  ON menu.tenant_id = tenant.id
 AND menu.permission_code = 'system:menu:view'
 AND menu.deleted = 0
WHERE tenant.status = 1
  AND tenant.deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM system_menu existing
      WHERE existing.tenant_id = tenant.id
        AND existing.permission_code = 'system:menu:manage'
        AND existing.deleted = 0
  );

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by)
SELECT role.tenant_id, role.id, menu.id, 1, 1
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.permission_code = 'system:menu:manage'
 AND menu.deleted = 0
WHERE role.role_code IN ('ADMIN', 'SUPER_ADMIN')
  AND role.status = 1
  AND role.deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM system_role_menu relation
      WHERE relation.tenant_id = role.tenant_id
        AND relation.role_id = role.id
        AND relation.menu_id = menu.id
        AND relation.deleted = 0
  );
