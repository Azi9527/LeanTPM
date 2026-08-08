-- 组织用于管理归属，位置仅描述组织内的实际物理点位；不再复制组织层级。

-- 清理早期初始化脚本中没有任何业务引用的组织镜像位置。
UPDATE location location_row
SET location_row.status = 0,
    location_row.deleted = 1,
    location_row.updated_time = CURRENT_TIMESTAMP(3),
    location_row.version = location_row.version + 1
WHERE location_row.deleted = 0
  AND location_row.location_code LIKE '%-SITE'
  AND location_row.location_type IN ('ENTERPRISE', 'FACTORY', 'PLANT_AREA', 'WORKSHOP')
  AND NOT EXISTS (
      SELECT 1 FROM equipment equipment_row
      WHERE equipment_row.tenant_id = location_row.tenant_id
        AND equipment_row.location_id = location_row.id
        AND equipment_row.deleted = 0
  )
  AND NOT EXISTS (
      SELECT 1 FROM inspection_task inspection_row
      WHERE inspection_row.tenant_id = location_row.tenant_id
        AND inspection_row.location_id = location_row.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM maintenance_task maintenance_row
      WHERE maintenance_row.tenant_id = location_row.tenant_id
        AND maintenance_row.location_id = location_row.id
  );

-- 保留有业务引用的原位置，将其转为所属组织下的独立物理区域。
UPDATE location
SET parent_id = 0,
    location_type = CASE
        WHEN location_type = 'WORKSTATION' THEN 'SPOT'
        ELSE 'AREA'
    END,
    updated_time = CURRENT_TIMESTAMP(3),
    version = version + 1
WHERE deleted = 0
  AND location_type IN (
      'ENTERPRISE', 'FACTORY', 'PLANT_AREA', 'WORKSHOP', 'LINE', 'WORKSTATION'
  );

ALTER TABLE location
    MODIFY COLUMN location_type VARCHAR(32) NOT NULL
        COMMENT 'AREA/BUILDING/FLOOR/ZONE/SPOT';

-- 设备可先归属组织，物理位置待现场确认后补录。
ALTER TABLE equipment
    MODIFY COLUMN location_id BIGINT NULL;

ALTER TABLE equipment_transfer_record
    MODIFY COLUMN to_location_id BIGINT NULL;

-- 任务保存设备当时的位置快照；设备未维护位置时允许快照为空。
ALTER TABLE inspection_task
    MODIFY COLUMN location_id BIGINT NULL;

ALTER TABLE maintenance_task
    MODIFY COLUMN location_id BIGINT NULL;
