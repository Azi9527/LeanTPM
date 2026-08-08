-- The existing planner account represents the customer's current team-leader position.
INSERT INTO system_user_role
    (tenant_id, user_id, role_id, created_by, updated_by, deleted)
SELECT user.tenant_id, user.id, role.id, 1, 1, 0
FROM system_user user
JOIN system_role role
  ON role.tenant_id = user.tenant_id
 AND role.role_code = 'TEAM_LEADER'
 AND role.deleted = 0
WHERE user.username = 'planner' AND user.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0, updated_by = 1, updated_time = CURRENT_TIMESTAMP(3);

DELETE relation
FROM system_user_role relation
JOIN system_user user
  ON user.tenant_id = relation.tenant_id
 AND user.id = relation.user_id
JOIN system_role role
  ON role.tenant_id = relation.tenant_id
 AND role.id = relation.role_id
WHERE user.username = 'planner' AND user.deleted = 0
  AND role.role_code = 'WORKSHOP_MANAGER';

UPDATE system_user user
JOIN organization team
  ON team.tenant_id = user.tenant_id
 AND team.organization_code = 'TEAM-A-1'
 AND team.organization_type = 'TEAM'
 AND team.deleted = 0
SET user.organization_id = team.id, user.updated_by = 1,
    user.updated_time = CURRENT_TIMESTAMP(3), user.version = user.version + 1
WHERE user.username = 'planner' AND user.deleted = 0;

UPDATE organization team
JOIN system_user user
  ON user.tenant_id = team.tenant_id
 AND user.username = 'planner' AND user.deleted = 0
SET team.manager_user_id = user.id, team.updated_by = 1,
    team.updated_time = CURRENT_TIMESTAMP(3), team.version = team.version + 1
WHERE team.organization_code = 'TEAM-A-1'
  AND team.organization_type = 'TEAM' AND team.deleted = 0;
