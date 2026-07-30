param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = 'root'
)

$ErrorActionPreference = 'Stop'
$database = 'leantpm_it_{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $PID
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'

try {
    & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -e "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create the MySQL integration database'
    }

    $env:LEANTPM_TEST_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $env:LEANTPM_TEST_DB_USERNAME = $MySqlUser
    $env:LEANTPM_TEST_DB_PASSWORD = $MySqlPassword

    & mvn.cmd '-Dleantpm.build.directory=target-codex' `
        '-Dtest=MySqlMigrationIntegrationTest' test `
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
