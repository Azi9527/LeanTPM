-- Use concise employee-style login accounts for the five seeded demo operators.
-- Task assignment and historical business data reference system_user.id, so the
-- username change does not break existing assignee/collaborator relationships.
UPDATE system_user AS source
LEFT JOIN system_user AS target
  ON target.tenant_id = source.tenant_id
 AND target.deleted = 0
 AND target.id <> source.id
 AND target.username = CASE source.username
     WHEN 'operator01' THEN '001'
     WHEN 'operator02' THEN '002'
     WHEN 'operator03' THEN '003'
     WHEN 'operator04' THEN '004'
     WHEN 'operator05' THEN '005'
 END
SET source.username = CASE source.username
    WHEN 'operator01' THEN '001'
    WHEN 'operator02' THEN '002'
    WHEN 'operator03' THEN '003'
    WHEN 'operator04' THEN '004'
    WHEN 'operator05' THEN '005'
END,
    source.updated_by = 0,
    source.updated_time = CURRENT_TIMESTAMP(3),
    source.version = source.version + 1
WHERE source.tenant_id = 1
  AND source.deleted = 0
  AND source.username IN ('operator01', 'operator02', 'operator03', 'operator04', 'operator05')
  AND target.id IS NULL;
