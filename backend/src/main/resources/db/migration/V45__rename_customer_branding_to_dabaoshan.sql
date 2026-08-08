UPDATE system_parameter
SET parameter_value = '大宝山矿业设备管理系统',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'system.name'
  AND parameter_value = '宝山矿业设备管理系统'
  AND deleted = 0;

UPDATE system_parameter
SET parameter_value = '大宝山矿业',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'branding.short-name'
  AND parameter_value = '宝山矿业'
  AND deleted = 0;

UPDATE system_parameter
SET parameter_value = 'http://localhost:15173/m/e',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'equipment.barcode.public-base-url'
  AND parameter_value = 'http://localhost:5173/m/e'
  AND deleted = 0;
