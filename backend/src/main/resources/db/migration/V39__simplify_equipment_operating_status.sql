-- 设备运行状态只保留：空闲、运行、停机、报废。
-- 点检、保养、故障、维修仍由各自业务单据表达，不再占用设备运行状态。

UPDATE system_dictionary_item item
JOIN system_dictionary_type type
  ON type.tenant_id = item.tenant_id
 AND type.id = item.dict_type_id
 AND type.dict_code = 'equipment_status'
SET item.status = CASE
        WHEN item.item_value IN ('IDLE', 'RUNNING', 'STOPPED', 'SCRAPPED') THEN 1
        ELSE 0
    END,
    item.item_label = CASE item.item_value
        WHEN 'IDLE' THEN '空闲'
        WHEN 'RUNNING' THEN '运行'
        WHEN 'STOPPED' THEN '停机'
        WHEN 'SCRAPPED' THEN '报废'
        ELSE item.item_label
    END,
    item.sort_order = CASE item.item_value
        WHEN 'IDLE' THEN 10
        WHEN 'RUNNING' THEN 20
        WHEN 'STOPPED' THEN 30
        WHEN 'SCRAPPED' THEN 40
        ELSE item.sort_order + 100
    END,
    item.is_default = CASE WHEN item.item_value = 'IDLE' THEN 1 ELSE 0 END,
    item.updated_by = 1,
    item.version = item.version + 1
WHERE item.deleted = 0;

-- 仅收敛当前状态；历史履历保留原始业务语义，供审计追溯。
UPDATE equipment_current_status
SET status_code = CASE
        WHEN status_code IN ('IDLE', 'RUNNING', 'STOPPED', 'SCRAPPED') THEN status_code
        WHEN status_code = 'RUNNING' THEN 'RUNNING'
        WHEN status_code = 'SCRAPPED' THEN 'SCRAPPED'
        WHEN status_code IN ('NOT_ENABLED') THEN 'IDLE'
        ELSE 'STOPPED'
    END,
    reason = CONCAT_WS('；', NULLIF(reason, ''), 'V39 状态模型收敛为四种运行状态'),
    source_type = 'SYSTEM',
    updated_by = 1,
    version = version + 1;

UPDATE visualization_status_color
SET status = CASE
        WHEN status_code IN ('IDLE', 'RUNNING', 'STOPPED', 'SCRAPPED') THEN 1
        ELSE 0
    END,
    status_name = CASE status_code
        WHEN 'IDLE' THEN '空闲'
        WHEN 'RUNNING' THEN '运行'
        WHEN 'STOPPED' THEN '停机'
        WHEN 'SCRAPPED' THEN '报废'
        ELSE status_name
    END,
    display_color = CASE status_code
        WHEN 'IDLE' THEN '#38BDF8'
        WHEN 'RUNNING' THEN '#22C55E'
        WHEN 'STOPPED' THEN '#F59E0B'
        WHEN 'SCRAPPED' THEN '#475569'
        ELSE display_color
    END,
    emissive_color = CASE status_code
        WHEN 'IDLE' THEN '#0EA5E9'
        WHEN 'RUNNING' THEN '#16A34A'
        WHEN 'STOPPED' THEN '#D97706'
        WHEN 'SCRAPPED' THEN '#1F2937'
        ELSE emissive_color
    END,
    pulse_flag = CASE WHEN status_code IN ('RUNNING', 'STOPPED') THEN 1 ELSE 0 END,
    sort_order = CASE status_code
        WHEN 'IDLE' THEN 10
        WHEN 'RUNNING' THEN 20
        WHEN 'STOPPED' THEN 30
        WHEN 'SCRAPPED' THEN 40
        ELSE sort_order + 100
    END,
    updated_by = 1,
    version = version + 1
WHERE deleted = 0;
