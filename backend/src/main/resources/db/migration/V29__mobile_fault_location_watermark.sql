ALTER TABLE mobile_photo_evidence
    ADD COLUMN fault_location_text VARCHAR(300) NULL
        COMMENT '设备故障位置或部位文字，不是 GPS 坐标'
        AFTER address_text;

ALTER TABLE inspection_item
    ADD COLUMN photo_min_count INT NOT NULL DEFAULT 0 AFTER photo_required_flag,
    ADD COLUMN photo_max_count INT NOT NULL DEFAULT 9 AFTER photo_min_count,
    ADD COLUMN photo_max_size_mb INT NOT NULL DEFAULT 10 AFTER photo_max_count,
    ADD COLUMN photo_allowed_types VARCHAR(200) NOT NULL DEFAULT 'image/jpeg,image/png'
        AFTER photo_max_size_mb,
    ADD COLUMN photo_compression_quality INT NOT NULL DEFAULT 82
        AFTER photo_allowed_types;

UPDATE inspection_item
SET photo_min_count = CASE WHEN photo_required_flag = 1 THEN 1 ELSE 0 END;

ALTER TABLE inspection_task_item
    ADD COLUMN photo_min_count INT NOT NULL DEFAULT 0 AFTER photo_required_flag,
    ADD COLUMN photo_max_count INT NOT NULL DEFAULT 9 AFTER photo_min_count,
    ADD COLUMN photo_max_size_mb INT NOT NULL DEFAULT 10 AFTER photo_max_count,
    ADD COLUMN photo_allowed_types VARCHAR(200) NOT NULL DEFAULT 'image/jpeg,image/png'
        AFTER photo_max_size_mb,
    ADD COLUMN photo_compression_quality INT NOT NULL DEFAULT 82
        AFTER photo_allowed_types;

UPDATE inspection_task_item
SET photo_min_count = CASE WHEN photo_required_flag = 1 THEN 1 ELSE 0 END;

UPDATE system_parameter
SET parameter_value = 'false',
    parameter_name = '现场照片 GPS 定位（已停用）',
    description = '兼容历史配置保留；当前版本不申请定位权限，也不采集经纬度',
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'mobile.photo-location-required';
