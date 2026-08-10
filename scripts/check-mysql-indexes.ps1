param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = '',
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$Database = 'leantpm'
)

$ErrorActionPreference = 'Stop'
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf)) {
    throw 'MySqlSslCaPath must identify the host-owned MySQL CA certificate'
}
$expected = @(
    'equipment.uk_equipment_code',
    'equipment.idx_equipment_organization',
    'equipment_barcode.uk_equipment_barcode_token',
    'inspection_task.idx_inspection_task_assignee',
    'inspection_task.idx_inspection_task_due',
    'maintenance_task.idx_maintenance_task_assignee',
    'maintenance_task.idx_maintenance_task_due',
    'equipment_oee_record.idx_equipment_oee_org',
    'equipment_oee_record.idx_equipment_oee_rate',
    'visualization_scene_node.idx_visualization_node_equipment',
    'system_login_log.idx_login_log_user_time',
    'system_operation_log.idx_operation_log_user_time',
    'system_attachment.idx_attachment_business'
)
$tableNames = $expected |
    ForEach-Object { ($_ -split '\.')[0] } |
    Sort-Object -Unique
$quotedTables = ($tableNames | ForEach-Object { "'$_'" }) -join ','
$sql = @"
SELECT CONCAT(table_name, '.', index_name)
FROM information_schema.statistics
WHERE table_schema = '$Database'
  AND table_name IN ($quotedTables)
GROUP BY table_name, index_name
ORDER BY table_name, index_name;
"@

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    $actual = & mysql.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        '--ssl-mode=VERIFY_IDENTITY' `
        "--ssl-ca=$resolvedSslCa" `
        '--batch' `
        '--skip-column-names' `
        -e $sql
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to query MySQL indexes'
    }
}
finally {
    $env:MYSQL_PWD = $previousPassword
}

$missing = $expected | Where-Object { $_ -notin $actual }
if ($missing.Count -gt 0) {
    throw "Missing release indexes: $($missing -join ', ')"
}
[pscustomobject]@{
    Database = $Database
    RequiredIndexes = $expected.Count
    Result = 'PASS'
}
