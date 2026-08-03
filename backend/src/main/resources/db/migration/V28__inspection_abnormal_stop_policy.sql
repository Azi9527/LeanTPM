ALTER TABLE inspection_item
    ADD COLUMN abnormal_default_stop_flag TINYINT NOT NULL DEFAULT 1
        COMMENT '异常时默认要求设备停机' AFTER abnormal_advice;

ALTER TABLE inspection_scheme_item
    ADD COLUMN abnormal_stop_override TINYINT NULL
        COMMENT '方案项目覆盖异常停机规则' AFTER skip_allowed_override;

ALTER TABLE inspection_task_item
    ADD COLUMN abnormal_default_stop_flag TINYINT NOT NULL DEFAULT 1
        COMMENT '任务生成时固化的异常停机规则' AFTER abnormal_advice;

ALTER TABLE inspection_task_result
    ADD COLUMN equipment_stop_required TINYINT NULL
        COMMENT '异常结果是否要求设备停机' AFTER abnormal_description,
    ADD COLUMN stop_override_reason VARCHAR(500) NULL
        COMMENT '偏离默认停机规则的原因' AFTER equipment_stop_required;

ALTER TABLE inspection_abnormal
    ADD COLUMN equipment_stop_required TINYINT NOT NULL DEFAULT 0
        COMMENT '该异常是否要求设备停机' AFTER requested_equipment_status,
    ADD COLUMN equipment_status_changed TINYINT NOT NULL DEFAULT 0
        COMMENT '是否已触发设备状态变更' AFTER equipment_stop_required,
    ADD COLUMN equipment_status_changed_time DATETIME(3) NULL
        AFTER equipment_status_changed;

UPDATE inspection_task_item task_item
JOIN inspection_item source_item
  ON source_item.tenant_id = task_item.tenant_id
 AND source_item.id = task_item.source_item_id
SET task_item.abnormal_default_stop_flag = source_item.abnormal_default_stop_flag;
