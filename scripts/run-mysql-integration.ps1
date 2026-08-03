param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = 'root',
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE
)

$ErrorActionPreference = 'Stop'
$database = 'leantpm_it_{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $PID
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

try {
    & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -e "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create the MySQL integration database'
    }

    $env:LEANTPM_TEST_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $env:LEANTPM_TEST_DB_USERNAME = $MySqlUser
    $env:LEANTPM_TEST_DB_PASSWORD = $MySqlPassword

    & $MavenExecutable '-Dleantpm.build.directory=target-codex' `
        '-Dtest=*MySqlIntegrationTest' test `
        -f (Join-Path $backendRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL integration tests failed'
    }
}
finally {
    & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -e "DROP DATABASE IF EXISTS $database;"
    Remove-Item Env:LEANTPM_TEST_DB_URL -ErrorAction SilentlyContinue
    Remove-Item Env:LEANTPM_TEST_DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:LEANTPM_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
}
