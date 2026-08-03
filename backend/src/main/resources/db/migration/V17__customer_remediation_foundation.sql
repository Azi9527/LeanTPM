-- Customer remediation permissions. Business tables are introduced by later focused migrations.
INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (2103, 1, 21, 'BUTTON', '导入点检配置', NULL, NULL, NULL,
     'inspection:import', NULL, 3),
    (2405, 1, 24, 'BUTTON', '导出点检结果', NULL, NULL, NULL,
     'inspection:task:export', NULL, 5),
    (915, 1, 91, 'BUTTON', '导入用户', NULL, NULL, NULL,
     'system:user:import', NULL, 5);

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT 1, grants.role_id, grants.menu_id, 1, 1, 0
FROM (
    SELECT 1 AS role_id, 2103 AS menu_id
    UNION ALL SELECT 1, 2405
    UNION ALL SELECT 1, 915
    UNION ALL SELECT 2, 2103
    UNION ALL SELECT 2, 2405
    UNION ALL SELECT 3, 2405
) grants
ON DUPLICATE KEY UPDATE
    deleted = 0,
    updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3);
