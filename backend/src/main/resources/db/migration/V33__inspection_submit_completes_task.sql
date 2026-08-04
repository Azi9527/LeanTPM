INSERT INTO inspection_task_event
    (tenant_id, task_id, event_type, from_status, to_status, event_remark, operator_id)
SELECT tenant_id, id, 'AUTO_COMPLETED', 'PENDING_REVIEW', 'COMPLETED',
       '取消二次复核，已提交任务自动完成', COALESCE(updated_by, created_by, 0)
FROM inspection_task
WHERE deleted = 0 AND task_status = 'PENDING_REVIEW';

UPDATE inspection_task
SET task_status = 'COMPLETED',
    review_required_flag = 0,
    completed_time = COALESCE(completed_time, submitted_time, updated_time, CURRENT_TIMESTAMP(3)),
    reviewer_user_id = NULL,
    reviewed_time = NULL,
    review_comment = NULL,
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE deleted = 0 AND task_status = 'PENDING_REVIEW';

UPDATE inspection_task
SET review_required_flag = 0
WHERE deleted = 0 AND review_required_flag <> 0;

UPDATE inspection_scheme_version
SET review_required_flag = 0,
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE review_required_flag <> 0;
