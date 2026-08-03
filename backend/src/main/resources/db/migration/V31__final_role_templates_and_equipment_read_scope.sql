-- Customer-confirmed identities: super administrator, manager, team leader, employee.
UPDATE system_role
SET role_name = '超级管理员', data_scope = 'ALL', status = 1,
    sort_order = 1, remark = '租户内全部功能与全部数据权限',
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE tenant_id = 1 AND role_code = 'ADMIN' AND deleted = 0;

UPDATE system_role
SET role_name = '管理人员', data_scope = 'ORGANIZATION_AND_CHILDREN', status = 1,
    sort_order = 10, remark = '所属组织及下级组织全部业务权限，并可维护范围内用户',
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE tenant_id = 1 AND role_code = 'WORKSHOP_MANAGER' AND deleted = 0;

UPDATE system_role
SET role_name = '班组长', data_scope = 'ORGANIZATION', status = 1,
    sort_order = 20, remark = '本班组除系统管理外的全部业务权限',
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE tenant_id = 1 AND role_code = 'TEAM_LEADER' AND deleted = 0;

UPDATE system_role
SET role_name = '员工', data_scope = 'SELF', status = 1,
    sort_order = 30, remark = '本人任务反馈、本人相关报表和全部设备只读权限',
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE tenant_id = 1 AND role_code = 'OPERATOR' AND deleted = 0;

-- Migrate every legacy planner assignment to the final manager template.
INSERT INTO system_user_role
    (tenant_id, user_id, role_id, created_by, updated_by, deleted)
SELECT legacy.tenant_id, legacy.user_id, manager.id,
       legacy.created_by, legacy.updated_by, 0
FROM system_user_role legacy
JOIN system_role planner
  ON planner.tenant_id = legacy.tenant_id
 AND planner.id = legacy.role_id
 AND planner.role_code = 'PLANNER'
JOIN system_role manager
  ON manager.tenant_id = planner.tenant_id
 AND manager.role_code = 'WORKSHOP_MANAGER'
 AND manager.deleted = 0
WHERE legacy.tenant_id = 1 AND legacy.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_time = CURRENT_TIMESTAMP(3);

DELETE relation
FROM system_user_role relation
JOIN system_role planner
  ON planner.tenant_id = relation.tenant_id
 AND planner.id = relation.role_id
WHERE relation.tenant_id = 1 AND planner.role_code = 'PLANNER';

UPDATE system_role
SET status = 0, deleted = 1, updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE tenant_id = 1 AND role_code = 'PLANNER' AND deleted = 0;

-- Rebuild role grants from the final contract instead of accumulating old templates.
UPDATE system_role_menu relation
JOIN system_role role
  ON role.tenant_id = relation.tenant_id AND role.id = relation.role_id
SET relation.deleted = 1, relation.updated_time = CURRENT_TIMESTAMP(3)
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR');

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id AND menu.status = 1 AND menu.deleted = 0
WHERE role.tenant_id = 1 AND role.role_code = 'ADMIN' AND role.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id AND menu.status = 1 AND menu.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code IN ('WORKSHOP_MANAGER', 'TEAM_LEADER')
  AND role.deleted = 0
  AND (menu.permission_code IS NULL OR menu.permission_code NOT LIKE 'system:%')
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);

-- Managers may maintain users in their resolved organization scope, but cannot edit roles.
INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id AND menu.status = 1 AND menu.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code = 'WORKSHOP_MANAGER'
  AND role.deleted = 0
  AND menu.permission_code IN (
      'system:view', 'system:user:view', 'system:user:create', 'system:user:update',
      'system:user:status', 'system:user:reset-password', 'system:user:import'
  )
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id AND menu.status = 1 AND menu.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code = 'OPERATOR'
  AND role.deleted = 0
  AND menu.permission_code IN (
      'dashboard:view',
      'equipment:view', 'equipment:ledger:view', 'equipment:status:view',
      'equipment:barcode:view',
      'inspection:view', 'inspection:my-task:view', 'inspection:task:execute',
      'inspection:abnormal:view', 'inspection:statistics:view',
      'notification:view', 'notification:message:view', 'notification:scan',
      'mobile:access', 'mobile:workbench:view', 'mobile:task:view',
      'mobile:message:view', 'mobile:scan', 'mobile:profile:view'
  )
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);

UPDATE system_role_data_scope relation
JOIN system_role role
  ON role.tenant_id = relation.tenant_id AND role.id = relation.role_id
SET relation.deleted = 1, relation.updated_time = CURRENT_TIMESTAMP(3)
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR');

INSERT INTO system_role_data_scope
    (tenant_id, role_id, data_scope_id, organization_id,
     created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, scope.id, NULL, 1, 1, 0
FROM system_role role
JOIN system_data_scope scope
  ON scope.tenant_id = role.tenant_id
 AND scope.scope_code = CASE role.role_code
     WHEN 'ADMIN' THEN 'ALL'
     WHEN 'WORKSHOP_MANAGER' THEN 'ORGANIZATION_AND_CHILDREN'
     WHEN 'TEAM_LEADER' THEN 'ORGANIZATION'
     WHEN 'OPERATOR' THEN 'SELF'
 END
 AND scope.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR')
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);
