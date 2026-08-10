[CmdletBinding()]
param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = $env:LEANTPM_TEST_DB_PASSWORD,
    [Parameter(Mandatory)][string]$ExpectedServerUuid,
    [Parameter(Mandatory)][string]$MySqlSslCaPath,
    [Parameter(Mandatory)][string]$MySqlSslTrustStorePath,
    [switch]$ConfirmIsolatedDatabase,
    [int]$ServerPort = 18088,
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE
)

$ErrorActionPreference = 'Stop'
$database = 'leantpm_auth_verify_{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $PID
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$version = Get-Content -LiteralPath (Join-Path $repositoryRoot 'VERSION.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$backendRoot = Join-Path $repositoryRoot 'backend'
$runtimeRoot = Join-Path $env:TEMP ('leantpm-auth-e2e-' + $PID)
$initialPassword = 'Start#' + [guid]::NewGuid().ToString('N')
$changedPassword = 'Changed#' + [guid]::NewGuid().ToString('N')
$jwtSecret = 'e2e-' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$baseUrl = "http://127.0.0.1:$ServerPort/api/v1"
$backendProcess = $null
$accessToken = $null
$previousMySqlPassword = $env:MYSQL_PWD
$previousMavenOpts = $env:MAVEN_OPTS
$databaseCreated = $false

if (-not $ConfirmIsolatedDatabase -or
        [string]::IsNullOrWhiteSpace($ExpectedServerUuid) -or
        [string]::IsNullOrWhiteSpace($MySqlPassword)) {
    throw 'Auth E2E requires isolated-database confirmation, a password and an exact server UUID'
}
if ($database -notmatch '^leantpm_auth_verify_\d{14}_\d+$' -or
        $MySqlHost -notmatch '^[A-Za-z0-9.-]+$' -or
        $MySqlPort -lt 1 -or $MySqlPort -gt 65535) {
    throw 'Auth E2E target parameters are invalid'
}
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
$resolvedSslTrustStore = (Resolve-Path -LiteralPath $MySqlSslTrustStorePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf) -or
        -not (Test-Path -LiteralPath $resolvedSslTrustStore -PathType Leaf)) {
    throw 'Auth E2E requires host-owned MySQL CA and Java trust-store files'
}
$mysqlArguments = @(
    "--host=$MySqlHost",
    "--port=$MySqlPort",
    "--user=$MySqlUser",
    '--ssl-mode=VERIFY_IDENTITY',
    "--ssl-ca=$resolvedSslCa"
)
if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenCommand) { $MavenExecutable = $mavenCommand.Source }
    else { $MavenExecutable = Join-Path $repositoryRoot 'runtime\apache-maven-3.9.11\bin\mvn.cmd' }
}
if (-not (Test-Path -LiteralPath $MavenExecutable -PathType Leaf)) {
    throw "Maven executable was not found at $MavenExecutable"
}

function Invoke-Json {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body
    )
    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        ContentType = 'application/json'
        TimeoutSec = 15
    }
    if ($null -ne $Body) { $arguments.Body = $Body | ConvertTo-Json -Depth 8 }
    Invoke-RestMethod @arguments
}

function Read-ErrorCode {
    param([Parameter(Mandatory)][System.Management.Automation.ErrorRecord]$ErrorRecord)
    if ($null -eq $ErrorRecord.Exception.Response) { throw $ErrorRecord }
    $stream = $ErrorRecord.Exception.Response.GetResponseStream()
    $reader = [IO.StreamReader]::new($stream)
    try { return ($reader.ReadToEnd() | ConvertFrom-Json).code }
    finally { $reader.Dispose() }
}

function Stop-Backend {
    if ($null -eq $script:backendProcess) { return }
    try {
        $script:backendProcess.Refresh()
        if (-not $script:backendProcess.HasExited) {
            $script:backendProcess.Kill()
            [void]$script:backendProcess.WaitForExit(10000)
        }
    }
    finally {
        $script:backendProcess.Dispose()
        $script:backendProcess = $null
    }
}

function Start-Backend {
    param([Parameter(Mandatory)][string]$JarPath)
    $script:backendProcess = Start-Process -FilePath 'java.exe' -ArgumentList @(
        "-Djavax.net.ssl.trustStore=$resolvedSslTrustStore", '-jar', $JarPath
    ) `
        -RedirectStandardOutput (Join-Path $runtimeRoot 'backend.out.log') `
        -RedirectStandardError (Join-Path $runtimeRoot 'backend.err.log') `
        -WindowStyle Hidden -PassThru
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $health = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$ServerPort/actuator/health/readiness" `
                -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        }
        catch {
            if ($script:backendProcess.HasExited) { break }
        }
    }
    throw "Backend did not become ready. See $runtimeRoot"
}

function Assert-ApiError {
    param(
        [Parameter(Mandatory)][scriptblock]$Operation,
        [Parameter(Mandatory)][string[]]$ExpectedCodes
    )
    try {
        & $Operation | Out-Null
        throw 'Request unexpectedly succeeded'
    }
    catch {
        $code = Read-ErrorCode $_
        if ($ExpectedCodes -notcontains $code) {
            throw "Unexpected API error code: $code"
        }
    }
}

function Assert-HttpStatus {
    param(
        [Parameter(Mandatory)][scriptblock]$Operation,
        [Parameter(Mandatory)][int]$ExpectedStatus
    )
    try {
        & $Operation | Out-Null
        throw 'Request unexpectedly succeeded'
    }
    catch {
        if ($null -eq $_.Exception.Response) { throw }
        $actualStatus = [int]$_.Exception.Response.StatusCode
        if ($actualStatus -ne $ExpectedStatus) {
            throw "Unexpected HTTP status: $actualStatus"
        }
    }
}

try {
    if (Get-NetTCPConnection -State Listen -LocalPort $ServerPort -ErrorAction SilentlyContinue) {
        throw "E2E port is already occupied: $ServerPort"
    }
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    $env:MYSQL_PWD = $MySqlPassword
    $actualServerUuid = (& mysql.exe @mysqlArguments -N -e 'SELECT @@server_uuid;').Trim()
    if ($LASTEXITCODE -ne 0 -or -not $actualServerUuid.Equals(
            $ExpectedServerUuid, [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Auth E2E MySQL server UUID does not match the approved isolated target'
    }
    & mysql.exe @mysqlArguments `
        -e "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create the temporary MySQL database' }
    $databaseCreated = $true
    $createdDatabase = & mysql.exe @mysqlArguments -N `
        -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$database';"
    if ($createdDatabase.Trim() -cne $database) {
        throw 'The temporary MySQL database was not visible after creation'
    }

    $env:MAVEN_OPTS = (([string]$previousMavenOpts).Trim() +
        " -Djavax.net.ssl.trustStore=`"$resolvedSslTrustStore`"").Trim()
    & $MavenExecutable '-Dleantpm.build.directory=target-codex' '-DskipTests' package `
        -f (Join-Path $backendRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }

    $env:LEANTPM_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY"
    $env:LEANTPM_DB_USERNAME = $MySqlUser
    $env:LEANTPM_DB_PASSWORD = $MySqlPassword
    $env:LEANTPM_SERVER_PORT = [string]$ServerPort
    $env:LEANTPM_BOOTSTRAP_ADMIN_PASSWORD = $initialPassword
    $env:LEANTPM_JWT_SECRET = $jwtSecret
    $env:LEANTPM_UPLOAD_DIR = Join-Path $runtimeRoot 'uploads'
    $env:LEANTPM_RELEASE_VERSION = [string]$version.productVersion
    $env:LEANTPM_DATABASE_SCHEMA_VERSION = [string]$version.databaseSchemaVersion
    $env:LEANTPM_OPENAPI_ENABLED = 'true'

    $jar = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'target-codex') `
        -File -Filter 'leantpm-backend-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw 'Backend executable JAR was not created' }
    Start-Backend -JarPath $jar

    $branding = Invoke-Json -Method Get -Uri "$baseUrl/public/branding"
    if ($branding.code -cne 'OK' -or
            [string]::IsNullOrWhiteSpace([string]$branding.data.systemName) -or
            [string]::IsNullOrWhiteSpace([string]$branding.data.shortName)) {
        throw 'Public branding probe did not return a valid service contract'
    }
    Assert-HttpStatus -ExpectedStatus 404 -Operation {
        Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$baseUrl/auth/captcha" -TimeoutSec 15
    }

    $login = Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
        username = 'admin'; password = $initialPassword
    }
    $refreshToken0 = $login.data.tokens.refreshToken
    $rotated = Invoke-Json -Method Post -Uri "$baseUrl/auth/refresh" -Body @{
        refreshToken = $refreshToken0
    }
    $rotatedAccessToken = $rotated.data.accessToken
    Assert-ApiError -ExpectedCodes @('REFRESH_TOKEN_REUSED') -Operation {
        Invoke-Json -Method Post -Uri "$baseUrl/auth/refresh" -Body @{
            refreshToken = $refreshToken0
        }
    }
    Assert-ApiError -ExpectedCodes @('TOKEN_REVOKED') -Operation {
        Invoke-Json -Method Get -Uri "$baseUrl/auth/me" `
            -Headers @{ Authorization = "Bearer $rotatedAccessToken" }
    }

    $login = Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
        username = 'admin'; password = $initialPassword
    }
    $accessToken = $login.data.tokens.accessToken
    $changed = Invoke-Json -Method Put -Uri "$baseUrl/auth/password" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{ currentPassword = $initialPassword; newPassword = $changedPassword }
    $accessToken = $changed.data.accessToken
    $authorized = @{ Authorization = "Bearer $accessToken" }

    $parameters = Invoke-Json -Method Get `
        -Uri "$baseUrl/system/parameters?keyword=system.short_name" -Headers $authorized
    $parameter = @($parameters.data) |
        Where-Object { $_.parameterKey -ceq 'system.short_name' } | Select-Object -First 1
    if ($null -eq $parameter) { throw 'Idempotency fixture parameter was not found' }
    $idempotencyKey = [guid]::NewGuid().ToString()
    $updateBody = @{
        parameterKey = $parameter.parameterKey
        parameterName = $parameter.parameterName
        parameterValue = $parameter.parameterValue
        valueType = $parameter.valueType
        groupCode = $parameter.groupCode
        description = $parameter.description
        enabled = $true
        version = $parameter.version
    }
    $idempotentHeaders = @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = $idempotencyKey
    }
    $first = Invoke-Json -Method Put -Uri "$baseUrl/system/parameters/$($parameter.id)" `
        -Headers $idempotentHeaders -Body $updateBody
    $second = Invoke-Json -Method Put -Uri "$baseUrl/system/parameters/$($parameter.id)" `
        -Headers $idempotentHeaders -Body $updateBody
    if (($first | ConvertTo-Json -Depth 8 -Compress) -cne
            ($second | ConvertTo-Json -Depth 8 -Compress)) {
        throw 'Repeated idempotent request did not replay the original response'
    }
    Stop-Backend
    Start-Backend -JarPath $jar
    $third = Invoke-Json -Method Put -Uri "$baseUrl/system/parameters/$($parameter.id)" `
        -Headers $idempotentHeaders -Body $updateBody
    if (($first | ConvertTo-Json -Depth 8 -Compress) -cne
            ($third | ConvertTo-Json -Depth 8 -Compress)) {
        throw 'Idempotent response was not replayed after backend restart'
    }

    Invoke-Json -Method Post -Uri "$baseUrl/auth/logout" -Headers $authorized | Out-Null
    Assert-ApiError -ExpectedCodes @('TOKEN_REVOKED') -Operation {
        Invoke-Json -Method Get -Uri "$baseUrl/auth/me" -Headers $authorized
    }
    Stop-Backend
    Start-Backend -JarPath $jar
    Assert-ApiError -ExpectedCodes @('TOKEN_REVOKED') -Operation {
        Invoke-Json -Method Get -Uri "$baseUrl/auth/me" -Headers $authorized
    }

    $relogin = Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
        username = 'admin'; password = $changedPassword
    }
    $accessToken = $relogin.data.tokens.accessToken
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        Assert-ApiError -ExpectedCodes @('LOGIN_FAILED') -Operation {
            Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
                username = 'admin'; password = 'definitely-wrong'
            }
        }
    }
    $lockedStateCount = (& mysql.exe @mysqlArguments -N `
        -e "SELECT COUNT(*) FROM ${database}.auth_login_security_state WHERE principal_key LIKE 'U:%' AND locked_until > CURRENT_TIMESTAMP(3);").Trim()
    if ($lockedStateCount -ne '1') {
        throw 'Persistent account lock state was not recorded after the configured threshold'
    }
    Stop-Backend
    Start-Backend -JarPath $jar
    Assert-ApiError -ExpectedCodes @('LOGIN_FAILED') -Operation {
        Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
            username = 'admin'; password = $changedPassword
        }
    }

    $openApi = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/v3/api-docs" -TimeoutSec 15
    if ($null -eq $openApi.paths.'/api/v1/auth/login'.post -or
            $null -eq $openApi.paths.'/api/v1/public/branding'.get -or
            $null -ne $openApi.paths.'/api/v1/auth/me'.get.security -and
            $openApi.paths.'/api/v1/auth/me'.get.security.Count -eq 0 -or
            $null -eq $openApi.components.securitySchemes.bearerAuth) {
        throw 'OpenAPI authentication contract is incomplete'
    }

    Write-Output 'MYSQL_TARGET_UUID=PASS'
    Write-Output ("MYSQL_FLYWAY_V1_TO_V{0}=PASS" -f [int]$version.databaseSchemaVersion)
    Write-Output 'PUBLIC_BRANDING_PROBE=PASS'
    Write-Output 'CAPTCHA_ENDPOINT_REMOVED_404=PASS'
    Write-Output 'REFRESH_ROTATION_REUSE_REVOKES_SESSION=PASS'
    Write-Output 'LOGIN_PASSWORD_CHANGE_LOGOUT_RELOGIN=PASS'
    Write-Output 'LOGOUT_REVOCATION_SURVIVES_RESTART=PASS'
    Write-Output 'LOGIN_LOCK_SURVIVES_RESTART=PASS'
    Write-Output 'DATABASE_IDEMPOTENCY_REPLAY_SURVIVES_RESTART=PASS'
    Write-Output 'OPENAPI_AUTH_CONTRACT=PASS'
}
catch {
    Write-Output ('E2E_ERROR=' + $_.Exception.Message)
    foreach ($logName in @('backend.out.log', 'backend.err.log')) {
        $logPath = Join-Path $runtimeRoot $logName
        if (Test-Path -LiteralPath $logPath) {
            Write-Output ("E2E_LOG_BEGIN=$logName")
            Get-Content -LiteralPath $logPath -Tail 120
            Write-Output ("E2E_LOG_END=$logName")
        }
    }
    throw
}
finally {
    Stop-Backend
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    if ($databaseCreated -and $database -match '^leantpm_auth_verify_\d{14}_\d+$') {
        & mysql.exe @mysqlArguments -e "DROP DATABASE IF EXISTS $database;" 2>$null
    }
    $ErrorActionPreference = $savedPreference
    @(
        'LEANTPM_DB_URL', 'LEANTPM_DB_USERNAME', 'LEANTPM_DB_PASSWORD',
        'LEANTPM_SERVER_PORT', 'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD',
        'LEANTPM_JWT_SECRET', 'LEANTPM_UPLOAD_DIR', 'LEANTPM_RELEASE_VERSION',
        'LEANTPM_DATABASE_SCHEMA_VERSION', 'LEANTPM_OPENAPI_ENABLED'
    ) | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
    if ($null -eq $previousMySqlPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    else { $env:MYSQL_PWD = $previousMySqlPassword }
    if ($null -eq $previousMavenOpts) { Remove-Item Env:MAVEN_OPTS -ErrorAction SilentlyContinue }
    else { $env:MAVEN_OPTS = $previousMavenOpts }
    if (Test-Path -LiteralPath $runtimeRoot) {
        $resolvedRuntime = (Resolve-Path -LiteralPath $runtimeRoot).Path
        $resolvedTemp = (Resolve-Path -LiteralPath $env:TEMP).Path
        if ($resolvedRuntime.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedRuntime -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
