UPDATE system_parameter
SET parameter_value = '86400',
    parameter_name = '可视化每日自动刷新秒数',
    description = '看板每天自动刷新一次，保留手工刷新能力',
    updated_time = CURRENT_TIMESTAMP(3)
WHERE tenant_id = 1
  AND parameter_key = 'visualization.refresh-seconds'
  AND deleted = 0;
