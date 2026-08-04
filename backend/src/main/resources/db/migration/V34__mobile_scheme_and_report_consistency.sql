-- Make the seeded CNC visualization equipment explicitly applicable to the
-- seeded CNC inspection scheme. The visualization category intentionally
-- remains isolated from the business category tree.
INSERT INTO inspection_scheme_equipment
    (tenant_id, scheme_version_id, equipment_id, created_by)
SELECT scheme.tenant_id, version.id, equipment.id, 0
FROM inspection_scheme scheme
JOIN inspection_scheme_version version
  ON version.tenant_id = scheme.tenant_id
 AND version.id = scheme.current_version_id
 AND version.version_status = 'PUBLISHED'
JOIN equipment
  ON equipment.tenant_id = scheme.tenant_id
 AND equipment.equipment_code LIKE 'VIZ-CNC-%'
 AND equipment.status = 1
 AND equipment.deleted = 0
WHERE scheme.scheme_code = 'ISP-DEMO-CNC-DAILY'
  AND scheme.status = 1
  AND scheme.deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM inspection_scheme_equipment existing
      WHERE existing.tenant_id = scheme.tenant_id
        AND existing.scheme_version_id = version.id
        AND existing.equipment_id = equipment.id
  );
