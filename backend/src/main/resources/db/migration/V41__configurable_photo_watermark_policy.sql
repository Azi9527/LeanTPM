ALTER TABLE mobile_photo_evidence
    MODIFY original_attachment_id BIGINT NULL,
    MODIFY watermarked_attachment_id BIGINT NULL,
    MODIFY original_sha256 CHAR(64) NULL,
    MODIFY watermarked_sha256 CHAR(64) NULL;

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'mobile.photo-watermark-enabled', '现场照片补充水印',
     'true', 'BOOLEAN', 'MOBILE_WATERMARK', '开启后由移动端按模板生成水印图', 1),
    (1, 'mobile.photo-save-original', '保存现场原图',
     'true', 'BOOLEAN', 'MOBILE_WATERMARK', '是否保留拍照或相册选择的原始图片', 1),
    (1, 'mobile.photo-save-watermarked', '保存现场水印图',
     'true', 'BOOLEAN', 'MOBILE_WATERMARK', '是否保留按规则生成的水印图片', 1),
    (1, 'mobile.photo-watermark-template', '现场照片水印模板',
     '{brand}\n{equipmentName} ({equipmentCode})\n{taskCode} · {itemName}\n位置/部位 {location}\n{capturedAt} · 执行人 {executor}',
     'STRING', 'MOBILE_WATERMARK', '支持品牌、设备、任务、点检项、时间、执行人和位置占位符', 1),
    (1, 'mobile.photo-watermark-position', '现场照片水印位置',
     'BOTTOM', 'STRING', 'MOBILE_WATERMARK', 'TOP 表示顶部，BOTTOM 表示底部', 1),
    (1, 'mobile.photo-watermark-background-opacity', '水印背景透明度',
     '74', 'INTEGER', 'MOBILE_WATERMARK', '0 到 100，数值越大背景越不透明', 1),
    (1, 'mobile.photo-watermark-font-color', '水印文字颜色',
     '#ffffff', 'STRING', 'MOBILE_WATERMARK', '使用 #RRGGBB 格式', 1),
    (1, 'mobile.photo-watermark-background-color', '水印背景颜色',
     '#031922', 'STRING', 'MOBILE_WATERMARK', '使用 #RRGGBB 格式', 1);
