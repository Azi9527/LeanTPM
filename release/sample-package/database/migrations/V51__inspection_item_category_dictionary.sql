INSERT INTO system_dictionary_type
    (tenant_id, dict_code, dict_name, status, remark, created_by, updated_by)
SELECT tenant.id,
       'inspection_item_category',
       '点检项目分类',
       1,
       '点检项目维护页中文下拉选项',
       0,
       0
FROM system_tenant tenant
WHERE tenant.status = 1
  AND tenant.deleted = 0
ON DUPLICATE KEY UPDATE
    status = 1,
    deleted = 0,
    updated_by = 0,
    version = system_dictionary_type.version + 1;

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, status,
     sort_order, is_default, created_by, updated_by)
SELECT dictionary_type.tenant_id,
       dictionary_type.id,
       defaults.item_value,
       defaults.item_label,
       1,
       defaults.sort_order,
       defaults.is_default,
       0,
       0
FROM system_dictionary_type dictionary_type
JOIN (
    SELECT 'TRANSMISSION' AS item_value, '传动系统' AS item_label,
           10 AS sort_order, 1 AS is_default
    UNION ALL SELECT 'LUBRICATION', '润滑系统', 20, 0
    UNION ALL SELECT 'FASTENING', '紧固系统', 30, 0
    UNION ALL SELECT 'ELECTRICAL', '电气系统', 40, 0
    UNION ALL SELECT 'SAFETY', '安全防护', 50, 0
    UNION ALL SELECT 'OTHER', '其它', 60, 0
) defaults ON 1 = 1
WHERE dictionary_type.dict_code = 'inspection_item_category'
  AND dictionary_type.status = 1
  AND dictionary_type.deleted = 0
  AND EXISTS (
      SELECT 1
      FROM system_tenant
      WHERE system_tenant.id = dictionary_type.tenant_id
        AND system_tenant.status = 1
        AND system_tenant.deleted = 0
  )
ON DUPLICATE KEY UPDATE
    status = 1,
    deleted = 0,
    updated_by = 0,
    version = system_dictionary_item.version + 1;
