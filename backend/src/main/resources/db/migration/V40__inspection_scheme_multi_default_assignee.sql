CREATE TABLE inspection_scheme_default_assignee (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    primary_flag TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_default_assignee
        (tenant_id, scheme_version_id, user_id),
    KEY idx_inspection_scheme_default_user
        (tenant_id, user_id, scheme_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案版本默认执行人员';

INSERT INTO inspection_scheme_default_assignee
    (tenant_id, scheme_version_id, user_id, primary_flag, sort_order, created_by)
SELECT version.tenant_id, version.id, version.default_assignee_user_id,
       1, 0, version.updated_by
FROM inspection_scheme_version version
WHERE version.default_assignee_user_id IS NOT NULL;
