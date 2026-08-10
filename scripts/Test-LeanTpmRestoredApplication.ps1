[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TargetDatabase,
    [Parameter(Mandatory)][string]$RestoreRoot,
    [Parameter(Mandatory)][int]$ExpectedSchemaVersion,
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'leantpm_restore',
    [string]$MySqlPassword = $env:LEANTPM_RESTORE_DB_PASSWORD,
    [Parameter(Mandatory)][string]$MySqlSslCaPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($TargetDatabase -notmatch '^[A-Za-z0-9_]+$' -or $ExpectedSchemaVersion -lt 1) {
    throw 'Restore verification target is invalid'
}
$root = (Resolve-Path -LiteralPath $RestoreRoot).Path
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf)) {
    throw 'MySqlSslCaPath must identify the host-owned MySQL CA certificate'
}
$configPath = Join-Path $root 'config\effective-config.json'
$referencesPath = Join-Path $root 'config\secret-references.json'
$attachmentsPath = Join-Path $root 'attachments'
foreach ($requiredPath in @($configPath, $referencesPath, $attachmentsPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Restored component is missing: $requiredPath"
    }
}
$null = Get-Content -LiteralPath $configPath -Encoding utf8 -Raw | ConvertFrom-Json
$references = Get-Content -LiteralPath $referencesPath -Encoding utf8 -Raw | ConvertFrom-Json
foreach ($property in $references.PSObject.Properties) {
    if ([string]$property.Value -notmatch
            '^(vault|dpapi|wincred|azurekeyvault)://[A-Za-z0-9._/@:-]+$') {
        throw 'Restored secret references contain an inline or unsupported value'
    }
}
$requiredTables = @('flyway_schema_history', 'system_user', 'system_parameter')
$escapedNames = ($requiredTables | ForEach-Object { "'$_'" }) -join ','
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    $tableCount = (& mysql.exe "--host=$MySqlHost" "--port=$MySqlPort" `
            "--user=$MySqlUser" '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$resolvedSslCa" `
            --batch --skip-column-names `
            -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TargetDatabase' AND table_name IN ($escapedNames);").Trim()
    if ($LASTEXITCODE -ne 0 -or [int]$tableCount -ne $requiredTables.Count) {
        throw 'Restored database is missing one or more core tables'
    }
    $schemaVersion = (& mysql.exe "--host=$MySqlHost" "--port=$MySqlPort" `
            "--user=$MySqlUser" '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$resolvedSslCa" `
            --batch --skip-column-names `
            "--database=$TargetDatabase" `
            -e 'SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1;').Trim()
    if ($LASTEXITCODE -ne 0 -or [int]$schemaVersion -ne $ExpectedSchemaVersion) {
        throw 'Restored database Flyway version is incorrect'
    }
}
finally { $env:MYSQL_PWD = $previousPassword }
$report = [pscustomobject]@{
    status = 'PASS'
    targetDatabase = $TargetDatabase
    schemaVersion = $ExpectedSchemaVersion
    coreTableCount = $requiredTables.Count
    attachmentFileCount = @(Get-ChildItem -LiteralPath $attachmentsPath -Recurse -File).Count
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
