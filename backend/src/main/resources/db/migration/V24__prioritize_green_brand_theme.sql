UPDATE system_parameter
SET parameter_name = '主品牌色',
    parameter_value = CASE
        WHEN parameter_value = '#c4000a' THEN '#1c7d50'
        ELSE parameter_value
    END,
    description = '宝山翠绿，按钮、链接、导航选中和主要交互使用',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'branding.primary-color'
  AND deleted = 0;

UPDATE system_parameter
SET parameter_name = '辅助品牌色',
    parameter_value = CASE
        WHEN parameter_value = '#1c7d50' THEN '#3e3a39'
        ELSE parameter_value
    END,
    description = '宝山墨灰，文字、导航背景和次级交互使用',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'branding.secondary-color'
  AND deleted = 0;

-- Retain the existing key for API and data compatibility; its display semantics are now accent color.
UPDATE system_parameter
SET parameter_name = '强调品牌色',
    parameter_value = CASE
        WHEN parameter_value = '#3e3a39' THEN '#c4000a'
        ELSE parameter_value
    END,
    description = '宝山朱红，告警、异常、危险操作和重点提示使用',
    updated_by = 0,
    version = version + 1
WHERE tenant_id = 1
  AND parameter_key = 'branding.neutral-color'
  AND deleted = 0;
