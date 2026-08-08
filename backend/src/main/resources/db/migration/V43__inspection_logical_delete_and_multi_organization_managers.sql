-- Inspection aggregate logical deletion and multi-manager organization relationships.

ALTER TABLE inspection_task_item
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER sort_order;

ALTER TABLE inspection_task_result
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER version;

ALTER TABLE inspection_attachment
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER created_time;

ALTER TABLE inspection_task_event
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER event_time;

ALTER TABLE inspection_task_assignee
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 AFTER created_time;

CREATE TABLE organization_manager_relation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    manager_type VARCHAR(32) NOT NULL COMMENT 'WORKSHOP_MANAGER/TEAM_LEADER',
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_organization_manager_relation
        (tenant_id, organization_id, user_id),
    KEY idx_organization_manager_user (tenant_id, user_id, deleted),
    KEY idx_organization_manager_org (tenant_id, organization_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织多负责人关系';

INSERT INTO organization_manager_relation
    (tenant_id, organization_id, user_id, manager_type, sort_order,
     created_by, updated_by, deleted)
SELECT organization.tenant_id, organization.id, organization.manager_user_id,
       CASE WHEN organization.organization_type = 'WORKSHOP'
            THEN 'WORKSHOP_MANAGER' ELSE 'TEAM_LEADER' END,
       0, organization.updated_by, organization.updated_by, 0
FROM organization
JOIN system_user manager
  ON manager.tenant_id = organization.tenant_id
 AND manager.id = organization.manager_user_id
 AND manager.deleted = 0
WHERE organization.deleted = 0
  AND organization.organization_type IN ('WORKSHOP', 'TEAM')
  AND organization.manager_user_id IS NOT NULL
ON DUPLICATE KEY UPDATE deleted = 0, manager_type = VALUES(manager_type);

-- Remove the five temporary operator accounts and all active relationship rows.
UPDATE organization_manager_relation relation
JOIN system_user user
  ON user.tenant_id = relation.tenant_id AND user.id = relation.user_id
SET relation.deleted = 1, relation.updated_by = 1,
    relation.updated_time = CURRENT_TIMESTAMP(3), relation.version = relation.version + 1
WHERE user.username IN ('001', '002', '003', '004', '005')
  AND user.deleted = 0 AND relation.deleted = 0;

UPDATE organization organization_row
JOIN system_user user
  ON user.tenant_id = organization_row.tenant_id
 AND user.id = organization_row.manager_user_id
SET organization_row.manager_user_id = NULL, organization_row.updated_by = 1,
    organization_row.updated_time = CURRENT_TIMESTAMP(3),
    organization_row.version = organization_row.version + 1
WHERE user.username IN ('001', '002', '003', '004', '005')
  AND user.deleted = 0 AND organization_row.deleted = 0;

UPDATE system_user_team_membership membership
JOIN system_user user
  ON user.tenant_id = membership.tenant_id AND user.id = membership.user_id
SET membership.deleted = 1, membership.primary_flag = 0,
    membership.updated_by = 1, membership.updated_time = CURRENT_TIMESTAMP(3)
WHERE user.username IN ('001', '002', '003', '004', '005')
  AND user.deleted = 0 AND membership.deleted = 0;

UPDATE system_user_role relation
JOIN system_user user
  ON user.tenant_id = relation.tenant_id AND user.id = relation.user_id
SET relation.deleted = 1, relation.updated_by = 1,
    relation.updated_time = CURRENT_TIMESTAMP(3)
WHERE user.username IN ('001', '002', '003', '004', '005')
  AND user.deleted = 0 AND relation.deleted = 0;

UPDATE system_user
SET deleted = 1, status = 0, updated_by = 1,
    updated_time = CURRENT_TIMESTAMP(3), version = version + 1
WHERE username IN ('001', '002', '003', '004', '005') AND deleted = 0;
