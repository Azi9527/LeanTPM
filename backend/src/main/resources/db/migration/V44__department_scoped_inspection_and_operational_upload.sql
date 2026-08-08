-- Operational users must be able to upload evidence while executing field tasks.
INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.permission_code = 'system:attachment:upload'
 AND menu.status = 1
 AND menu.deleted = 0
WHERE role.role_code IN ('WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR')
  AND role.status = 1
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = VALUES(updated_by),
    updated_time = CURRENT_TIMESTAMP(3);

-- Inspection standards created after this migration can be owned by a department.
-- Existing shared standards remain NULL and stay visible for compatibility.
ALTER TABLE inspection_item
    ADD COLUMN organization_id BIGINT NULL AFTER item_name,
    ADD KEY idx_inspection_item_organization
        (tenant_id, organization_id, status, deleted);
