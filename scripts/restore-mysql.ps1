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
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [switch]$AllowNonEmpty
)

$ErrorActionPreference = 'Stop'
if ($Database -ne $ConfirmDatabase) {
    throw 'ConfirmDatabase must exactly match Database'
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Database contains unsupported characters'
}
if ($MySqlHost -notmatch '^[A-Za-z0-9._:-]+$' -or
        $MySqlUser -notmatch '^[A-Za-z0-9_.@-]+$' -or
        $MySqlPort -lt 1 -or $MySqlPort -gt 65535) {
    throw 'MySQL connection parameters contain unsupported characters or values'
}
$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile).Path
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf) -or
        $resolvedSslCa -match '[\r\n"]') {
    throw 'MySqlSslCaPath must identify a safe host-owned MySQL CA certificate'
}

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    $exists = & mysql.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        '--ssl-mode=VERIFY_IDENTITY' `
        "--ssl-ca=$resolvedSslCa" `
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
        '--ssl-mode=VERIFY_IDENTITY' `
        "--ssl-ca=$resolvedSslCa" `
        '--batch' `
        '--skip-column-names' `
        -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database';"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to inspect target database'
    }
    if ([int]$tableCount -gt 0 -and -not $AllowNonEmpty) {
        throw "Target database is not empty: $Database"
    }

    $mysqlExecutable = (Get-Command mysql.exe -ErrorAction Stop).Source
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $mysqlExecutable
    $startInfo.Arguments = "--host=$MySqlHost --port=$MySqlPort --user=$MySqlUser " +
        "--ssl-mode=VERIFY_IDENTITY --ssl-ca=`"$resolvedSslCa`" --database=$Database"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $dumpStream = $null
    $restoreExitCode = -1
    try {
        if (-not $process.Start()) { throw 'Failed to start mysql restore client' }
        $dumpStream = New-Object System.IO.FileStream(
            $resolvedBackup,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $dumpStream.CopyTo($process.StandardInput.BaseStream)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $restoreExitCode = $process.ExitCode
    }
    finally {
        if ($null -ne $dumpStream) { $dumpStream.Dispose() }
        $process.Dispose()
    }
    if ($restoreExitCode -ne 0) {
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
