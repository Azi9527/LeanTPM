ALTER TABLE inspection_task
    ADD COLUMN request_idempotency_key VARCHAR(128) NULL
        COMMENT '手工或扫码创建请求幂等键' AFTER execution_remark,
    ADD COLUMN request_hash CHAR(64) NULL
        COMMENT '幂等请求业务参数摘要' AFTER request_idempotency_key,
    ADD UNIQUE KEY uk_inspection_task_request
        (tenant_id, request_idempotency_key);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.permission_code = 'inspection:task:create'
 AND menu.deleted = 0
WHERE role.tenant_id = 1
  AND role.role_code IN ('TEAM_LEADER', 'OPERATOR')
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;
