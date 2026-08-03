ALTER TABLE system_parameter
    MODIFY COLUMN parameter_value MEDIUMTEXT NOT NULL;

UPDATE system_parameter
SET parameter_value = '宝山矿业设备管理系统',
    parameter_name = '系统名称',
    description = '浏览器标题和系统展示名称',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'system.name'
  AND parameter_value = 'LeanTPM 精益设备管理系统'
  AND deleted = 0;

INSERT IGNORE INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in, status, created_by, updated_by)
VALUES
    (1, 'branding.short-name', '品牌简称', '宝山矿业', 'STRING',
     'BRANDING', '导航和登录页使用的品牌简称', 1, 1, 0, 0),
    (1, 'branding.subtitle', '品牌副标题', '精益设备管理', 'STRING',
     'BRANDING', '品牌标识下方的业务副标题', 1, 1, 0, 0),
    (1, 'branding.logo-url', '品牌 Logo', '/branding/baoshan-mining-logo.png', 'STRING',
     'BRANDING', '系统内置路径或上传图片的 Data URL', 1, 1, 0, 0),
    (1, 'branding.primary-color', '主品牌色', '#c4000a', 'STRING',
     'BRANDING', '宝山朱红，按钮、链接和重点状态使用', 1, 1, 0, 0),
    (1, 'branding.secondary-color', '辅助品牌色', '#1c7d50', 'STRING',
     'BRANDING', '宝山翠绿，成功状态和辅助强调使用', 1, 1, 0, 0),
    (1, 'branding.neutral-color', '中性品牌色', '#3e3a39', 'STRING',
     'BRANDING', '宝山墨灰，文字和导航背景使用', 1, 1, 0, 0);

UPDATE system_parameter
SET group_code = 'BRANDING',
    built_in = 1,
    status = 1
WHERE tenant_id = 1
  AND parameter_key IN (
    'branding.short-name',
    'branding.subtitle',
    'branding.logo-url',
    'branding.primary-color',
    'branding.secondary-color',
    'branding.neutral-color'
  )
  AND deleted = 0;
