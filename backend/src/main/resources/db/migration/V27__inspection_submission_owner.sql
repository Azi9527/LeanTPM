ALTER TABLE inspection_task
    ADD COLUMN submitted_by BIGINT NULL
        COMMENT '首次成功提交人' AFTER submitted_time,
    ADD KEY idx_inspection_task_submitter
        (tenant_id, submitted_by, submitted_time);

UPDATE inspection_task
SET submitted_by = updated_by
WHERE submitted_time IS NOT NULL AND submitted_by IS NULL;
