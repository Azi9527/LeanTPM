INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in, status, created_by, updated_by)
SELECT tenant.id,
       'mobile.photo-allow-album-selection',
       '允许从手机相册选择现场照片',
       'false',
       'BOOLEAN',
       'MOBILE_WATERMARK',
       '关闭时 APP 的项目照片和整单现场图片仅允许打开相机',
       1,
       1,
       0,
       0
FROM system_tenant tenant
WHERE tenant.status = 1
  AND tenant.deleted = 0
ON DUPLICATE KEY UPDATE
    built_in = 1,
    status = 1,
    deleted = 0,
    updated_by = 0,
    version = system_parameter.version + 1;

INSERT INTO equipment_category
    (tenant_id, parent_id, category_code, category_name, tree_level,
     default_oee_mode, sort_order, status, description, created_by, updated_by)
SELECT tenant.id,
       0,
       defaults.category_code,
       defaults.category_name,
       1,
       'STANDARD',
       defaults.sort_order,
       1,
       defaults.description,
       0,
       0
FROM system_tenant tenant
JOIN (
    SELECT 'PRODUCTION' AS category_code, '生产设备' AS category_name,
           10 AS sort_order, '生产制造主设备' AS description
    UNION ALL SELECT 'ENVIRONMENTAL_EQUIPMENT', '环保设备', 20, '环境保护相关设备'
    UNION ALL SELECT 'AUXILIARY_EQUIPMENT', '辅助设备', 30, '生产辅助设备'
    UNION ALL SELECT 'TRANSPORT_EQUIPMENT', '运输设备', 40, '物料及人员运输设备'
    UNION ALL SELECT 'OTHER_EQUIPMENT', '其它设备', 50, '未归入以上分类的设备'
) defaults ON 1 = 1
WHERE tenant.status = 1
  AND tenant.deleted = 0
ON DUPLICATE KEY UPDATE
    status = 1,
    deleted = 0,
    updated_by = 0,
    version = equipment_category.version + 1;
