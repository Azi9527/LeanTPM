param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = $env:LEANTPM_TEST_DB_PASSWORD,
    [string]$TestPattern = '*MySqlIntegrationTest',
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE,
    [string]$BuildDirectory = 'target-codex',
    [switch]$ConfirmIsolatedDatabase,
    [string]$ExpectedServerUuid = '',
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$MySqlSslTrustStorePath = $env:LEANTPM_MYSQL_SSL_TRUST_STORE_PATH
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmIsolatedDatabase) {
    throw 'ConfirmIsolatedDatabase is required before creating or deleting a test database'
}
if ([string]::IsNullOrWhiteSpace($ExpectedServerUuid)) {
    throw 'ExpectedServerUuid is required for every MySQL write target, including loopback'
}
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
$resolvedSslTrustStore = (Resolve-Path -LiteralPath $MySqlSslTrustStorePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf) -or
        -not (Test-Path -LiteralPath $resolvedSslTrustStore -PathType Leaf)) {
    throw 'MySQL integration requires host-owned CLI CA and Java trust store files'
}
$databaseStem = 'leantpm_it_{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $PID
$migrationDatabase = "${databaseStem}_migration"
$integrationDatabase = "${databaseStem}_suite"
$cleanupCandidates = [System.Collections.Generic.List[string]]::new()
$cleanupFailures = [System.Collections.Generic.List[string]]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenCommand) {
        $MavenExecutable = $mavenCommand.Source
    }
    else {
        $MavenExecutable = Join-Path $repositoryRoot 'runtime\apache-maven-3.9.11\bin\mvn.cmd'
    }
}
if (-not (Test-Path -LiteralPath $MavenExecutable)) {
    throw "Maven executable was not found at $MavenExecutable"
}

$customTestNames = @()
if ($TestPattern -cne '*MySqlIntegrationTest') {
    $customTestNames = @($TestPattern -csplit ',')
    if ($customTestNames.Count -eq 0 -or
            @($customTestNames | Where-Object {
                $_ -cnotmatch '^[A-Za-z][A-Za-z0-9]*IntegrationTest$'
            }).Count -gt 0) {
        throw 'Custom TestPattern must contain only comma-separated exact MySqlIntegrationTest class names'
    }
    if ($customTestNames -ccontains 'MySqlMigrationIntegrationTest' -and
            $customTestNames.Count -ne 1) {
        throw 'MySqlMigrationIntegrationTest must run alone in its dedicated migration database'
    }
    $availableTestNames = @(
        Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src\test\java') `
            -Recurse -File -Filter '*MySqlIntegrationTest.java' |
            ForEach-Object { $_.BaseName } |
            Sort-Object -Unique
    )
    foreach ($customTestName in $customTestNames) {
        if ($availableTestNames -cnotcontains $customTestName) {
            throw "Unknown MySQL integration test class: $customTestName"
        }
    }
}

function New-IsolatedTestDatabase([string]$DatabaseName) {
    if ($DatabaseName -notmatch '^leantpm_it_\d{14}_\d+_(migration|suite)$') {
        throw 'Generated MySQL integration database name is outside the exact allowlist'
    }
    $existingDatabaseCount = (& mysql.exe @mysqlArguments `
        -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$DatabaseName';").Trim()
    if ($LASTEXITCODE -ne 0 -or $existingDatabaseCount -cne '0') {
        throw "Generated MySQL integration database target is not proven absent: $DatabaseName"
    }
    [void]$cleanupCandidates.Add($DatabaseName)
    & mysql.exe @mysqlArguments `
        -e "CREATE DATABASE $DatabaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create isolated MySQL integration database $DatabaseName"
    }
}

function Invoke-MySqlTestGroup([string]$DatabaseName, [string]$Pattern) {
    $env:LEANTPM_TEST_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${DatabaseName}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY"
    $env:LEANTPM_TEST_DB_USERNAME = $MySqlUser
    $env:LEANTPM_TEST_DB_PASSWORD = $MySqlPassword
    & $MavenExecutable "-Dleantpm.build.directory=$BuildDirectory" `
        "-Dtest=$Pattern" test `
        -f (Join-Path $backendRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL integration test group failed: $Pattern"
    }
}

$previousPassword = $env:MYSQL_PWD
$previousMavenOpts = $env:MAVEN_OPTS
try {
    $env:MYSQL_PWD = $MySqlPassword
    $mysqlArguments = @(
        "--host=$MySqlHost",
        "--port=$MySqlPort",
        "--user=$MySqlUser",
        '--ssl-mode=VERIFY_IDENTITY',
        "--ssl-ca=$resolvedSslCa",
        '--batch',
        '--skip-column-names'
    )
    $serverUuid = (& mysql.exe @mysqlArguments -e 'SELECT @@server_uuid;').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverUuid)) {
        throw 'Failed to verify the isolated MySQL server identity'
    }
    if (-not $serverUuid.Equals(
            $ExpectedServerUuid,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'MySQL server UUID does not match ExpectedServerUuid'
    }

    $env:MAVEN_OPTS = (([string]$previousMavenOpts).Trim() +
        " -Djavax.net.ssl.trustStore=`"$resolvedSslTrustStore`"").Trim()

    if ($TestPattern -ceq '*MySqlIntegrationTest') {
        New-IsolatedTestDatabase -DatabaseName $migrationDatabase
        Invoke-MySqlTestGroup `
            -DatabaseName $migrationDatabase `
            -Pattern 'MySqlMigrationIntegrationTest'

        $moduleTests = @(
            Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src\test\java') `
                -Recurse -File -Filter '*MySqlIntegrationTest.java' |
                Where-Object { $_.BaseName -cne 'MySqlMigrationIntegrationTest' } |
                ForEach-Object { $_.BaseName } |
                Sort-Object -Unique
        )
        if ($moduleTests.Count -eq 0) {
            throw 'No module MySQL integration tests were discovered'
        }
        New-IsolatedTestDatabase -DatabaseName $integrationDatabase
        Invoke-MySqlTestGroup `
            -DatabaseName $integrationDatabase `
            -Pattern ($moduleTests -join ',')
    }
    elseif ($customTestNames.Count -eq 1 -and
            $customTestNames[0] -ceq 'MySqlMigrationIntegrationTest') {
        New-IsolatedTestDatabase -DatabaseName $migrationDatabase
        Invoke-MySqlTestGroup `
            -DatabaseName $migrationDatabase `
            -Pattern $customTestNames[0]
    }
    else {
        New-IsolatedTestDatabase -DatabaseName $integrationDatabase
        Invoke-MySqlTestGroup `
            -DatabaseName $integrationDatabase `
            -Pattern ($customTestNames -join ',')
    }
}
finally {
    foreach ($cleanupCandidate in @($cleanupCandidates)) {
        if ($cleanupCandidate -match '^leantpm_it_\d{14}_\d+_(migration|suite)$') {
            & mysql.exe @mysqlArguments -e "DROP DATABASE IF EXISTS $cleanupCandidate;"
            if ($LASTEXITCODE -ne 0) {
                [void]$cleanupFailures.Add($cleanupCandidate)
            }
        }
    }
    Remove-Item Env:LEANTPM_TEST_DB_URL -ErrorAction SilentlyContinue
    Remove-Item Env:LEANTPM_TEST_DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:LEANTPM_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($null -eq $previousMavenOpts) { Remove-Item Env:MAVEN_OPTS -ErrorAction SilentlyContinue }
    else { $env:MAVEN_OPTS = $previousMavenOpts }
    if ($null -eq $previousPassword) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    else {
        $env:MYSQL_PWD = $previousPassword
    }
    if ($cleanupFailures.Count -gt 0) {
        throw ('Failed to remove isolated MySQL integration database(s): ' +
            ($cleanupFailures -join ', '))
    }
}
