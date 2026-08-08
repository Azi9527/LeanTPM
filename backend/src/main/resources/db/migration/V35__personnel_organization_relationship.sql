-- Personnel hierarchy: workshop director -> team leader -> employees.
-- A user may work in multiple teams; one membership can be marked primary.
CREATE TABLE system_user_team_membership (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    team_organization_id BIGINT NOT NULL,
    primary_flag TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_team_membership (tenant_id, user_id, team_organization_id),
    KEY idx_user_team_team (tenant_id, team_organization_id, deleted),
    KEY idx_user_team_user (tenant_id, user_id, primary_flag, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员多班组任职关系';

-- Preserve current users whose primary organization is already a team.
INSERT INTO system_user_team_membership
    (tenant_id, user_id, team_organization_id, primary_flag, created_by, updated_by)
SELECT user.tenant_id, user.id, organization.id, 1, 1, 1
FROM system_user user
JOIN organization
  ON organization.tenant_id = user.tenant_id
 AND organization.id = user.organization_id
 AND organization.organization_type = 'TEAM'
 AND organization.deleted = 0
WHERE user.deleted = 0
ON DUPLICATE KEY UPDATE
    primary_flag = VALUES(primary_flag), deleted = 0,
    updated_time = CURRENT_TIMESTAMP(3);

-- Customer terminology for the explicit reporting hierarchy.
UPDATE system_role
SET role_name = '车间主任', remark = '所在车间及下级组织全部业务权限',
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE role_code = 'WORKSHOP_MANAGER' AND deleted = 0;
