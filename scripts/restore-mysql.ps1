param(
    [Parameter(Mandatory)]
    [string]$BackupFile,
    [Parameter(Mandatory)]
    [string]$Database,
    [Parameter(Mandatory)]
    [string]$ConfirmDatabase,
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = '',
    [switch]$AllowNonEmpty
)

$ErrorActionPreference = 'Stop'
if ($Database -ne $ConfirmDatabase) {
    throw 'ConfirmDatabase must exactly match Database'
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Database contains unsupported characters'
}
$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile).Path
if ($resolvedBackup -match '[\r\n"]') {
    throw 'Backup path contains unsupported characters'
}
$sourcePath = $resolvedBackup.Replace('\', '/')

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    $exists = & mysql.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        '--batch' `
        '--skip-column-names' `
        -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$Database';"
    if ($LASTEXITCODE -ne 0 -or [int]$exists -ne 1) {
        throw "Target database does not exist: $Database"
    }
    $tableCount = & mysql.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        '--batch' `
        '--skip-column-names' `
        -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database';"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to inspect target database'
    }
    if ([int]$tableCount -gt 0 -and -not $AllowNonEmpty) {
        throw "Target database is not empty: $Database"
    }

    & mysql.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        "--database=$Database" `
        -e "source $sourcePath"
    if ($LASTEXITCODE -ne 0) {
        throw 'mysql restore failed'
    }
}
finally {
    $env:MYSQL_PWD = $previousPassword
}

[pscustomobject]@{
    Database = $Database
    Backup = $resolvedBackup
    RestoredAt = Get-Date
}
