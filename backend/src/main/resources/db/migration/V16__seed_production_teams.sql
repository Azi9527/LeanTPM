INSERT INTO organization
    (tenant_id, parent_id, organization_code, organization_name,
     organization_type, description, sort_order, status, created_by, updated_by)
SELECT 1, parent.id, team.organization_code, team.organization_name,
       'TEAM', '点检和维保任务可选生产班组', team.sort_order, 1, 1, 1
FROM (
    SELECT 'LINE-A' AS parent_code, 'TEAM-A-1' AS organization_code,
           '装配一线一班' AS organization_name, 10 AS sort_order
    UNION ALL
    SELECT 'LINE-A', 'TEAM-A-2', '装配一线二班', 20
    UNION ALL
    SELECT 'LINE-B', 'TEAM-B-1', '装配二线一班', 10
    UNION ALL
    SELECT 'LINE-B', 'TEAM-B-2', '装配二线二班', 20
    UNION ALL
    SELECT 'LINE-C', 'TEAM-C-1', '机加一线一班', 10
    UNION ALL
    SELECT 'LINE-C', 'TEAM-C-2', '机加一线二班', 20
    UNION ALL
    SELECT 'LINE-D', 'TEAM-D-1', '机加二线一班', 10
    UNION ALL
    SELECT 'LINE-D', 'TEAM-D-2', '机加二线二班', 20
) team
JOIN organization parent
  ON parent.tenant_id = 1
 AND parent.organization_code = team.parent_code
 AND parent.deleted = 0
WHERE NOT EXISTS (
    SELECT 1
    FROM organization existing
    WHERE existing.tenant_id = 1
      AND existing.organization_code = team.organization_code
);

UPDATE system_user user
JOIN (
    SELECT 'operator01' AS username, 'TEAM-A-1' AS team_code
    UNION ALL
    SELECT 'operator02', 'TEAM-A-2'
    UNION ALL
    SELECT 'operator03', 'TEAM-B-1'
    UNION ALL
    SELECT 'operator04', 'TEAM-C-1'
    UNION ALL
    SELECT 'operator05', 'TEAM-D-1'
) assignment
  ON assignment.username = user.username
JOIN organization team
  ON team.tenant_id = user.tenant_id
 AND team.organization_code = assignment.team_code
 AND team.organization_type = 'TEAM'
 AND team.deleted = 0
SET user.organization_id = team.id,
    user.updated_by = 1,
    user.updated_time = CURRENT_TIMESTAMP(3),
    user.version = user.version + 1
WHERE user.tenant_id = 1
  AND user.deleted = 0;
