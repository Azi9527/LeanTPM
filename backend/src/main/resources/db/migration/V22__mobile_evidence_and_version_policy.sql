CREATE TABLE mobile_photo_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    workflow_type VARCHAR(32) NOT NULL,
    task_id BIGINT NOT NULL,
    task_item_id BIGINT NOT NULL,
    original_attachment_id BIGINT NOT NULL,
    watermarked_attachment_id BIGINT NOT NULL,
    captured_device_time DATETIME(3) NOT NULL,
    server_reference_time DATETIME(3) NOT NULL,
    received_server_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    device_clock_offset_seconds INT NOT NULL DEFAULT 0,
    clock_skew_warning TINYINT NOT NULL DEFAULT 0,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    location_accuracy_meters DECIMAL(10,2) NULL,
    location_provider VARCHAR(32) NULL,
    address_text VARCHAR(300) NULL,
    watermark_text VARCHAR(1000) NOT NULL,
    original_sha256 CHAR(64) NOT NULL,
    watermarked_sha256 CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mobile_evidence_watermarked
        (tenant_id, watermarked_attachment_id),
    KEY idx_mobile_evidence_task
        (tenant_id, workflow_type, task_id, task_item_id),
    KEY idx_mobile_evidence_captured
        (tenant_id, captured_device_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移动现场照片时间定位水印证据';

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'mobile.photo-location-required', '现场照片必须定位',
     'true', 'BOOLEAN', 'MOBILE', '开启后无有效定位时不允许提交水印照片', 1),
    (1, 'mobile.photo-clock-skew-warning-seconds', '设备时钟偏差告警秒数',
     '300', 'INTEGER', 'MOBILE', '设备时间与服务端时间偏差超过该值时标记告警', 1),
    (1, 'mobile.android-min-version-code', 'Android 最低版本号',
     '2', 'INTEGER', 'MOBILE', '低于该版本号时强制升级', 1),
    (1, 'mobile.android-latest-version-name', 'Android 最新版本',
     '1.0.1', 'STRING', 'MOBILE', '当前可用 Android 版本名称', 1),
    (1, 'mobile.android-download-url', 'Android 下载地址',
     '', 'STRING', 'MOBILE', '企业签名 APK 发布地址', 1),
    (1, 'mobile.android-release-notes', 'Android 升级说明',
     '新增定位时间水印、系统通知和弱网自动同步。', 'STRING', 'MOBILE',
     '移动端升级提示内容', 1);
