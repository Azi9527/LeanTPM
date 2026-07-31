CREATE TABLE inspection_task_assignee (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    primary_flag TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_task_assignee (tenant_id, task_id, user_id),
    KEY idx_inspection_assignee_user (tenant_id, user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务执行人员';

INSERT INTO inspection_task_assignee
    (tenant_id, task_id, user_id, primary_flag, sort_order, created_by)
SELECT task.tenant_id, task.id, task.assignee_user_id, 1, 0, task.updated_by
FROM inspection_task task
WHERE task.assignee_user_id IS NOT NULL
  AND task.deleted = 0;
