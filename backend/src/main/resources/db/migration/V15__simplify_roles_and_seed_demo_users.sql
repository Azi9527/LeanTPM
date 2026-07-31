-- LeanTPM uses three business roles: administrator, planner, and operator.
UPDATE system_role
SET role_code = 'ADMIN',
    role_name = '管理员',
    data_scope = 'ALL',
    sort_order = 1,
    remark = '系统管理员，拥有全部功能权限',
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE tenant_id = 1 AND id = 1 AND deleted = 0;

UPDATE system_role
SET role_code = 'PLANNER',
    role_name = '计划员',
    data_scope = 'ORGANIZATION_AND_CHILDREN',
    sort_order = 10,
    remark = '负责设备、点检、维保、OEE计划及任务管理',
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE tenant_id = 1 AND id = 2 AND deleted = 0;

-- Merge the former maintainer permissions into the former inspector role.
INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT maintainer.tenant_id, operator_role.id, relation.menu_id,
       relation.created_by, relation.updated_by, 0
FROM system_role maintainer
JOIN system_role operator_role
  ON operator_role.tenant_id = maintainer.tenant_id
 AND operator_role.id = 3
JOIN system_role_menu relation
  ON relation.tenant_id = maintainer.tenant_id
 AND relation.role_id = maintainer.id
WHERE maintainer.tenant_id = 1
  AND maintainer.id = 4
  AND relation.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_time = CURRENT_TIMESTAMP(3);

-- Preserve users that had the former maintainer role.
INSERT INTO system_user_role
    (tenant_id, user_id, role_id, created_by, updated_by, deleted)
SELECT relation.tenant_id, relation.user_id, operator_role.id,
       relation.created_by, relation.updated_by, 0
FROM system_user_role relation
JOIN system_role operator_role
  ON operator_role.tenant_id = relation.tenant_id
 AND operator_role.id = 3
WHERE relation.tenant_id = 1
  AND relation.role_id = 4
  AND relation.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_time = CURRENT_TIMESTAMP(3);

DELETE FROM system_user_role
WHERE tenant_id = 1 AND role_id = 4;

UPDATE system_role
SET role_code = 'OPERATOR',
    role_name = '操作工',
    data_scope = 'SELF',
    sort_order = 20,
    remark = '执行本人点检、维保和移动现场任务',
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE tenant_id = 1 AND id = 3 AND deleted = 0;

UPDATE system_role
SET status = 0,
    deleted = 1,
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE tenant_id = 1 AND id = 4 AND deleted = 0;

-- Seed one planner and five operators. The demo password is 888888.
INSERT INTO system_user
    (tenant_id, username, password_hash, real_name, employee_no,
     organization_id, status, mobile_enabled, must_change_password,
     created_by, updated_by, deleted)
VALUES
    (1, 'planner', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '计划员', 'PLAN-001', 1, 1, 1, 0, 1, 1, 0),
    (1, 'operator01', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '操作工01', 'OP-001', 4, 1, 1, 0, 1, 1, 0),
    (1, 'operator02', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '操作工02', 'OP-002', 4, 1, 1, 0, 1, 1, 0),
    (1, 'operator03', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '操作工03', 'OP-003', 5, 1, 1, 0, 1, 1, 0),
    (1, 'operator04', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '操作工04', 'OP-004', 7, 1, 1, 0, 1, 1, 0),
    (1, 'operator05', '$2a$12$61nBjYnx.Ibs2EP1cMjeH.92tXTLpHMIk1ZncbaDaJQA50PxRR1pq',
     '操作工05', 'OP-005', 8, 1, 1, 0, 1, 1, 0)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    real_name = VALUES(real_name),
    employee_no = VALUES(employee_no),
    organization_id = VALUES(organization_id),
    status = 1,
    mobile_enabled = 1,
    must_change_password = 0,
    updated_by = 1,
    deleted = 0,
    version = version + 1;

DELETE relation
FROM system_user_role relation
JOIN system_user user
  ON user.tenant_id = relation.tenant_id
 AND user.id = relation.user_id
WHERE relation.tenant_id = 1
  AND user.username = 'planner'
  AND relation.role_id <> 2;

DELETE relation
FROM system_user_role relation
JOIN system_user user
  ON user.tenant_id = relation.tenant_id
 AND user.id = relation.user_id
WHERE relation.tenant_id = 1
  AND user.username IN ('operator01', 'operator02', 'operator03', 'operator04', 'operator05')
  AND relation.role_id <> 3;

INSERT INTO system_user_role
    (tenant_id, user_id, role_id, created_by, updated_by, deleted)
SELECT user.tenant_id, user.id,
       CASE WHEN user.username = 'planner' THEN 2 ELSE 3 END,
       1, 1, 0
FROM system_user user
WHERE user.tenant_id = 1
  AND user.username IN (
      'planner', 'operator01', 'operator02', 'operator03', 'operator04', 'operator05'
  )
  AND user.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3);
