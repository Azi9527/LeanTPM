[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseRoot,
    [Parameter(Mandatory)][string]$MySqlHost,
    [Parameter(Mandatory)][int]$MySqlPort,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$MySqlUser,
    [string]$MySqlPassword = $env:LEANTPM_MIGRATOR_DB_PASSWORD,
    [Parameter(Mandatory)][string]$ExpectedServerUuid,
    [string]$MySqlSslTrustStorePath = $env:LEANTPM_MYSQL_SSL_TRUST_STORE_PATH,
    [string]$JavaExecutable = 'C:\Program Files\Java\jdk-21\bin\java.exe',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($Database -notmatch '^[A-Za-z0-9_]+$' -or
        $MySqlHost -notmatch '^[A-Za-z0-9.-]+$' -or
        $MySqlPort -lt 1 -or $MySqlPort -gt 65535 -or
        $MySqlUser -notmatch '^[A-Za-z0-9_.-]{1,64}$' -or
        $ExpectedServerUuid -notmatch '^[A-Fa-f0-9-]{16,64}$') {
    throw 'Migrator target contract is invalid or lacks a pinned server UUID'
}
$root = (Resolve-Path -LiteralPath $ReleaseRoot).Path.TrimEnd('\', '/')
$java = (Resolve-Path -LiteralPath $JavaExecutable).Path
$sslTrustStore = (Resolve-Path -LiteralPath $MySqlSslTrustStorePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $sslTrustStore -PathType Leaf)) {
    throw 'MySqlSslTrustStorePath must identify the host-owned Java MySQL trust store'
}
$manifest = Get-Content -LiteralPath (Join-Path $root 'release-manifest.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$catalogPath = Join-Path $root 'payload\database\migrations.json'
$catalog = Get-Content -LiteralPath $catalogPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$catalog.schemaFrom -ne [int]$manifest.components.database.schemaFrom -or
        [int]$catalog.schemaTo -ne [int]$manifest.components.database.schemaTo -or
        [string]$catalog.phase -cne [string]$manifest.components.database.phase) {
    throw 'Migrator catalog contradicts the verified release manifest'
}
$jarPath = Join-Path $root 'payload\backend\leantpm-backend.jar'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw 'Verified release has no backend JAR for the isolated migrator entry point'
}
$payloadReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmMigratorPayload.ps1') `
    -ReleaseRoot $root -OutputFormat Json | ConvertFrom-Json
if ([string]$payloadReport.status -cne 'PASS') {
    throw 'Backend JAR migration payload was not bound to the reviewed catalog'
}

$previousMigratorEnvironment = @{}
foreach ($name in @(
        'LEANTPM_MIGRATOR_JDBC_URL', 'LEANTPM_MIGRATOR_DB_USERNAME',
        'LEANTPM_MIGRATOR_DB_PASSWORD', 'LEANTPM_MIGRATOR_SCHEMA_FROM',
        'LEANTPM_MIGRATOR_SCHEMA_TO', 'LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID'
    )) {
    $previousMigratorEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $jdbcUrl = "jdbc:mysql://$MySqlHost`:$MySqlPort/$Database" +
        '?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&sslMode=VERIFY_IDENTITY'
    [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_JDBC_URL', $jdbcUrl, 'Process')
    [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_DB_USERNAME', $MySqlUser, 'Process')
    [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_DB_PASSWORD', $MySqlPassword, 'Process')
    [Environment]::SetEnvironmentVariable(
        'LEANTPM_MIGRATOR_SCHEMA_FROM',
        [string]$catalog.schemaFrom,
        'Process'
    )
    [Environment]::SetEnvironmentVariable(
        'LEANTPM_MIGRATOR_SCHEMA_TO',
        [string]$catalog.schemaTo,
        'Process'
    )
    [Environment]::SetEnvironmentVariable(
        'LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID',
        $ExpectedServerUuid,
        'Process'
    )
    $output = & $java "-Djavax.net.ssl.trustStore=$sslTrustStore" `
        '-Dloader.main=com.leantpm.ops.MigrationMain' '-cp' $jarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher'
    if ($LASTEXITCODE -ne 0) { throw 'The isolated Flyway migrator failed' }
}
finally {
    foreach ($name in $previousMigratorEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $previousMigratorEnvironment[$name],
            'Process'
        )
    }
}
$report = [pscustomobject]@{
    status = 'PASS'
    schemaFrom = [int]$catalog.schemaFrom
    schemaTo = [int]$catalog.schemaTo
    phase = [string]$catalog.phase
    serverUuid = $ExpectedServerUuid
    output = @($output)
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
