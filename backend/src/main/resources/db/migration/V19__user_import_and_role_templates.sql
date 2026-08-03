CREATE TABLE system_user_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    batch_code CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    import_status VARCHAR(24) NOT NULL,
    import_strategy VARCHAR(24) NOT NULL,
    payload_json JSON NULL,
    errors_json JSON NULL,
    result_json JSON NULL,
    total_rows INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    committed_by BIGINT NULL,
    committed_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_user_import_batch_code (tenant_id, batch_code),
    KEY idx_system_user_import_batch_status (tenant_id, import_status, created_time),
    CONSTRAINT fk_system_user_import_batch_tenant
        FOREIGN KEY (tenant_id) REFERENCES system_tenant(id),
    CONSTRAINT fk_system_user_import_batch_creator
        FOREIGN KEY (created_by) REFERENCES system_user(id),
    CONSTRAINT fk_system_user_import_batch_committer
        FOREIGN KEY (committed_by) REFERENCES system_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO system_role
    (tenant_id, role_code, role_name, data_scope, status, sort_order, remark,
     created_by, updated_by, deleted)
SELECT 1, 'WORKSHOP_MANAGER', '车间主任', 'ORGANIZATION_AND_CHILDREN', 1, 12,
       '岗位权限模板：管理所属车间及下级组织的设备、任务、异常和看板', 1, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM system_role
    WHERE tenant_id = 1 AND role_code = 'WORKSHOP_MANAGER' AND deleted = 0
);

INSERT INTO system_role
    (tenant_id, role_code, role_name, data_scope, status, sort_order, remark,
     created_by, updated_by, deleted)
SELECT 1, 'TEAM_LEADER', '班组长', 'ORGANIZATION', 1, 16,
       '岗位权限模板：负责本班组派工、结果、异常、逾期和看板', 1, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM system_role
    WHERE tenant_id = 1 AND role_code = 'TEAM_LEADER' AND deleted = 0
);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT manager_role.tenant_id, manager_role.id, planner_menu.menu_id, 1, 1, 0
FROM system_role manager_role
JOIN system_role planner_role
  ON planner_role.tenant_id = manager_role.tenant_id
 AND planner_role.role_code = 'PLANNER'
 AND planner_role.deleted = 0
JOIN system_role_menu planner_menu
  ON planner_menu.tenant_id = planner_role.tenant_id
 AND planner_menu.role_id = planner_role.id
 AND planner_menu.deleted = 0
WHERE manager_role.tenant_id = 1
  AND manager_role.role_code = 'WORKSHOP_MANAGER'
  AND manager_role.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT leader_role.tenant_id, leader_role.id, menu.id, 1, 1, 0
FROM system_role leader_role
JOIN system_menu menu
  ON menu.tenant_id = leader_role.tenant_id
 AND menu.deleted = 0
 AND menu.status = 1
WHERE leader_role.tenant_id = 1
  AND leader_role.role_code = 'TEAM_LEADER'
  AND leader_role.deleted = 0
  AND menu.permission_code IN (
      'dashboard:view',
      'equipment:view', 'equipment:ledger:view', 'equipment:status:view',
      'inspection:view', 'inspection:task:view', 'inspection:task:assign',
      'inspection:task:export', 'inspection:abnormal:view',
      'inspection:abnormal:handle', 'inspection:abnormal:verify',
      'inspection:statistics:view',
      'maintenance:view', 'maintenance:task:view', 'maintenance:task:assign',
      'maintenance:task:collaborate', 'maintenance:task:confirm',
      'maintenance:abnormal:view', 'maintenance:abnormal:handle',
      'maintenance:abnormal:verify', 'maintenance:statistics:view',
      'visualization:view', 'visualization:cockpit:view',
      'visualization:inspection:view', 'visualization:maintenance:view',
      'mobile:access', 'mobile:workbench:view', 'mobile:task:view',
      'mobile:message:view', 'mobile:scan', 'mobile:profile:view'
  )
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3);

INSERT INTO system_role_data_scope
    (tenant_id, role_id, data_scope_id, organization_id,
     created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, scope.id, NULL, 1, 1, 0
FROM system_role role
JOIN system_data_scope scope
  ON scope.tenant_id = role.tenant_id
 AND scope.scope_code = CASE role.role_code
     WHEN 'WORKSHOP_MANAGER' THEN 'ORGANIZATION_AND_CHILDREN'
     WHEN 'TEAM_LEADER' THEN 'ORGANIZATION'
 END
 AND scope.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code IN ('WORKSHOP_MANAGER', 'TEAM_LEADER')
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3);
