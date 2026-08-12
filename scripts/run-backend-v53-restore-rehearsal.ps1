[CmdletBinding()]
param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = $env:LEANTPM_TEST_DB_PASSWORD,
    [Parameter(Mandatory)][string]$ExpectedServerUuid,
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$MySqlSslTrustStorePath = $env:LEANTPM_MYSQL_SSL_TRUST_STORE_PATH,
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE,
    [switch]$ConfirmIsolatedDatabase
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

if (-not $ConfirmIsolatedDatabase) {
    throw 'ConfirmIsolatedDatabase is required before creating or deleting rehearsal databases'
}
if ($MySqlHost -notmatch '^[A-Za-z0-9.-]+$' -or $MySqlPort -lt 1 -or $MySqlPort -gt 65535 -or
        $MySqlUser -notmatch '^[A-Za-z0-9_.-]{1,64}$' -or
        $ExpectedServerUuid -notmatch '^[A-Fa-f0-9-]{16,64}$') {
    throw 'V53 restore rehearsal target contract is invalid'
}
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
$resolvedTrustStore = (Resolve-Path -LiteralPath $MySqlSslTrustStorePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf) -or
        -not (Test-Path -LiteralPath $resolvedTrustStore -PathType Leaf)) {
    throw 'V53 restore rehearsal requires CLI CA and Java trust store files'
}
$mysqlExecutable = (Get-Command mysql.exe -ErrorAction Stop).Source
$mysqldumpExecutable = (Get-Command mysqldump.exe -ErrorAction Stop).Source
if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $MavenExecutable = (Get-Command mvn.cmd -ErrorAction Stop).Source
}
$MavenExecutable = (Resolve-Path -LiteralPath $MavenExecutable -ErrorAction Stop).Path

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$token = '{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), [Guid]::NewGuid().ToString('N').Substring(0, 8)
$sourceDatabase = "leantpm_v53_rehearsal_${token}_source"
$restoredDatabase = "leantpm_v53_rehearsal_${token}_restored"
$databasePattern = '^leantpm_v53_rehearsal_\d{14}_[a-f0-9]{8}_(source|restored)$'
foreach ($database in @($sourceDatabase, $restoredDatabase)) {
    if ($database -notmatch $databasePattern -or $database.Length -gt 64) {
        throw "Generated V53 rehearsal database is outside the exact allowlist: $database"
    }
}
$evidenceRoot = Join-Path $repositoryRoot "runtime\backend-v53-restore-rehearsal\$token"
$backupPath = Join-Path $evidenceRoot 'v52.sql'
$evidencePath = Join-Path $evidenceRoot 'evidence.json'
$cleanupCandidates = [Collections.Generic.List[string]]::new()
$cleanupFailures = [Collections.Generic.List[string]]::new()
$phaseResults = [Collections.Generic.List[object]]::new()
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Invoke-MySqlScalar {
    param([string]$Sql, [string]$Database = '')
    $arguments = @(
        "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUser",
        '--ssl-mode=VERIFY_IDENTITY', "--ssl-ca=$resolvedSslCa",
        '--batch', '--skip-column-names'
    )
    if (-not [string]::IsNullOrWhiteSpace($Database)) { $arguments += "--database=$Database" }
    $arguments += "--execute=$Sql"
    $output = @(& $mysqlExecutable @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('MySQL command failed: ' + ($output -join '; ')) }
    return ([string]($output -join "`n")).Trim()
}

function New-RehearsalDatabase {
    param([string]$Database)
    if ($Database -notmatch $databasePattern) { throw 'Database create target escaped the rehearsal allowlist' }
    $exists = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$Database';"
    if ($exists -cne '0') { throw "Rehearsal database already exists: $Database" }
    [void]$cleanupCandidates.Add($Database)
    [void](Invoke-MySqlScalar -Sql "CREATE DATABASE $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
}

function Invoke-RehearsalPhase {
    param([string]$Database, [ValidateSet('PREPARE_V52','UPGRADE_V53','VERIFY_RESTORED_V52')][string]$Phase)
    $env:LEANTPM_TEST_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${Database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY"
    $env:LEANTPM_TEST_DB_USERNAME = $MySqlUser
    $env:LEANTPM_TEST_DB_PASSWORD = $MySqlPassword
    $env:LEANTPM_V53_REHEARSAL_PHASE = $Phase
    $started = [DateTime]::UtcNow
    & $MavenExecutable '-Dleantpm.build.directory=target-codex-v53-rehearsal' '-Dtest=BackendV53RestoreRehearsalMySqlIntegrationTest' test -f (Join-Path $backendRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) { throw "V53 restore rehearsal phase failed: $Phase" }
    [void]$phaseResults.Add([ordered]@{
        phase = $Phase
        databaseRole = if ($Database -ceq $sourceDatabase) { 'source' } else { 'restored' }
        elapsedMilliseconds = [long]([DateTime]::UtcNow - $started).TotalMilliseconds
    })
}

function Backup-RehearsalV52 {
    $arguments = @(
        "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUser",
        '--ssl-mode=VERIFY_IDENTITY', "--ssl-ca=$resolvedSslCa",
        '--single-transaction', '--routines', '--triggers', '--events', '--hex-blob',
        '--set-gtid-purged=OFF', '--default-character-set=utf8mb4',
        "--result-file=$backupPath", $sourceDatabase
    )
    & $mysqldumpExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw 'V52 rehearsal mysqldump failed' }
    $backup = Get-Item -LiteralPath $backupPath -ErrorAction Stop
    if ($backup.Length -lt 100000) { throw 'V52 rehearsal backup is unexpectedly small' }
    return [ordered]@{
        path = $backup.FullName
        bytes = [long]$backup.Length
        backupSha256 = (Get-FileHash -LiteralPath $backup.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Restore-RehearsalV52 {
    New-RehearsalDatabase -Database $restoredDatabase
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $mysqlExecutable
    $startInfo.Arguments = "--host=$MySqlHost --port=$MySqlPort --user=$MySqlUser --ssl-mode=VERIFY_IDENTITY --ssl-ca=`"$resolvedSslCa`" --database=$restoredDatabase"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $input = $null
    try {
        if (-not $process.Start()) { throw 'Failed to start the isolated V52 restore client' }
        $input = [IO.File]::OpenRead($backupPath)
        $input.CopyTo($process.StandardInput.BaseStream)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Isolated V52 restore client failed' }
    } finally {
        if ($null -ne $input) { $input.Dispose() }
        $process.Dispose()
    }
}

$previousPassword = $env:MYSQL_PWD
$previousMavenOpts = $env:MAVEN_OPTS
$previousEnvironment = @{}
foreach ($name in @('LEANTPM_TEST_DB_URL','LEANTPM_TEST_DB_USERNAME','LEANTPM_TEST_DB_PASSWORD','LEANTPM_V53_REHEARSAL_PHASE')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    [IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $env:MYSQL_PWD = $MySqlPassword
    $serverUuid = Invoke-MySqlScalar -Sql 'SELECT @@server_uuid;'
    if (-not $serverUuid.Equals($ExpectedServerUuid, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'MySQL server UUID does not match ExpectedServerUuid'
    }
    $env:MAVEN_OPTS = (([string]$previousMavenOpts).Trim() + " -Djavax.net.ssl.trustStore=`"$resolvedTrustStore`"").Trim()

    New-RehearsalDatabase -Database $sourceDatabase
    Invoke-RehearsalPhase -Database $sourceDatabase -Phase 'PREPARE_V52'
    $backup = Backup-RehearsalV52
    Invoke-RehearsalPhase -Database $sourceDatabase -Phase 'UPGRADE_V53'
    Restore-RehearsalV52
    Invoke-RehearsalPhase -Database $restoredDatabase -Phase 'VERIFY_RESTORED_V52'
    Invoke-RehearsalPhase -Database $restoredDatabase -Phase 'UPGRADE_V53'

    $evidence = [ordered]@{
        status = 'PASS'
        serverUuid = $serverUuid
        tlsMode = 'VERIFY_IDENTITY'
        sourceDatabase = $sourceDatabase
        restoredDatabase = $restoredDatabase
        backup = $backup
        phases = @($phaseResults)
        cleanupRequired = $true
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($evidencePath, ($evidence | ConvertTo-Json -Depth 7), $utf8NoBom)
    $evidence | ConvertTo-Json -Depth 7
}
finally {
    foreach ($database in @($cleanupCandidates)) {
        if ($database -match $databasePattern) {
            try { [void](Invoke-MySqlScalar -Sql "DROP DATABASE IF EXISTS $database;") }
            catch { [void]$cleanupFailures.Add($database) }
        }
    }
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if ($null -eq $previousMavenOpts) { Remove-Item Env:MAVEN_OPTS -ErrorAction SilentlyContinue }
    else { $env:MAVEN_OPTS = $previousMavenOpts }
    if ($null -eq $previousPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    else { $env:MYSQL_PWD = $previousPassword }
    if ($cleanupFailures.Count -gt 0) {
        throw ('Failed to remove isolated V53 rehearsal database(s): ' + ($cleanupFailures -join ', '))
    }
}
