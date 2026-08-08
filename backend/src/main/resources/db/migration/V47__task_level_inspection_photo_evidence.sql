ALTER TABLE mobile_photo_evidence
    MODIFY COLUMN task_item_id BIGINT NULL
        COMMENT 'Task item id; null means task-level submission photo';

ALTER TABLE inspection_attachment
    MODIFY COLUMN attachment_type VARCHAR(32) NOT NULL
        COMMENT 'RESULT_PHOTO/RESULT_ATTACHMENT/TASK_PHOTO/ABNORMAL_PHOTO/ABNORMAL_ATTACHMENT';
