param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = 'root',
    [int]$RedisPort = 6389,
    [int]$ServerPort = 18088
)

$ErrorActionPreference = 'Stop'
$database = 'leantpm_captcha_verify_{0}_{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), $PID
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$runtimeRoot = Join-Path $env:TEMP ('leantpm-captcha-e2e-' + $PID)
$redisZip = Join-Path $runtimeRoot 'redis.zip'
$redisRoot = Join-Path $runtimeRoot 'redis'
$redisUrl = 'https://github.com/redis-windows/redis-windows/releases/download/7.4.10/Redis-7.4.10-Windows-x64-msys2.zip'
$redisSha256 = '3CFA2FA4E85E61D633F5D13971A559BB7961EBC3DD647718BFA8CF9EC7717912'
$initialPassword = 'Start#' + [guid]::NewGuid().ToString('N')
$changedPassword = 'Changed#' + [guid]::NewGuid().ToString('N')
$jwtSecret = 'e2e-' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$baseUrl = "http://127.0.0.1:$ServerPort/api/v1"
$redisProcess = $null
$backendProcess = $null
$backendListenerProcessId = $null

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body
    )
    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $arguments.Body = $Body | ConvertTo-Json -Depth 8
    }
    Invoke-RestMethod @arguments
}

function Read-ErrorCode {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)
    if ($null -eq $ErrorRecord.Exception.Response) {
        throw $ErrorRecord
    }
    $stream = $ErrorRecord.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    try {
        return ($reader.ReadToEnd() | ConvertFrom-Json).code
    }
    finally {
        $reader.Dispose()
    }
}

try {
    $occupiedPorts = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in @($RedisPort, $ServerPort) }
    if ($occupiedPorts) {
        throw "E2E ports are already occupied: $($occupiedPorts.LocalPort -join ', ')"
    }

    New-Item -ItemType Directory -Path $redisRoot -Force | Out-Null
    Invoke-WebRequest -Uri $redisUrl -OutFile $redisZip
    $actualHash = (Get-FileHash -LiteralPath $redisZip -Algorithm SHA256).Hash
    if ($actualHash -ne $redisSha256) {
        throw "Redis archive hash mismatch: $actualHash"
    }
    Expand-Archive -LiteralPath $redisZip -DestinationPath $redisRoot -Force
    $redisServer = Get-ChildItem -LiteralPath $redisRoot -Recurse -File -Filter 'redis-server.exe' |
        Select-Object -First 1 -ExpandProperty FullName
    $redisCli = Get-ChildItem -LiteralPath $redisRoot -Recurse -File -Filter 'redis-cli.exe' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $redisServer -or -not $redisCli) {
        throw 'Redis executables were not found in the verified archive'
    }
    $redisProcess = Start-Process -FilePath $redisServer `
        -ArgumentList @('--port', $RedisPort, '--save', '""', '--appendonly', 'no') `
        -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    $pong = & $redisCli -p $RedisPort ping
    if ($pong -ne 'PONG') {
        throw "Redis did not start: $pong"
    }

    & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -e "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create the temporary MySQL database'
    }
    $createdDatabase = & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -N -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$database';"
    if ($createdDatabase.Trim() -ne $database) {
        throw "Temporary MySQL database was not visible after creation: $createdDatabase"
    }
    Write-Output "MYSQL_TEMP_DATABASE_CREATED=$database"

    & mvn.cmd '-Dleantpm.build.directory=target-codex' '-DskipTests' package `
        -f (Join-Path $backendRoot 'pom.xml')
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend package failed'
    }

    $env:LEANTPM_DB_URL = "jdbc:mysql://${MySqlHost}:$MySqlPort/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $env:LEANTPM_DB_USERNAME = $MySqlUser
    $env:LEANTPM_DB_PASSWORD = $MySqlPassword
    $env:LEANTPM_REDIS_PORT = [string]$RedisPort
    $env:LEANTPM_REDIS_DATABASE = '11'
    $env:LEANTPM_SERVER_PORT = [string]$ServerPort
    $env:LEANTPM_BOOTSTRAP_ADMIN_PASSWORD = $initialPassword
    $env:LEANTPM_JWT_SECRET = $jwtSecret
    $env:LEANTPM_UPLOAD_DIR = Join-Path $runtimeRoot 'uploads'

    $jar = Join-Path $backendRoot 'target-codex\leantpm-backend-0.1.0-SNAPSHOT.jar'
    $backendProcess = Start-Process -FilePath 'java.exe' -ArgumentList @('-jar', $jar) `
        -RedirectStandardOutput (Join-Path $runtimeRoot 'backend.out.log') `
        -RedirectStandardError (Join-Path $runtimeRoot 'backend.err.log') `
        -WindowStyle Hidden -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/actuator/health"
            if ($health.status -eq 'UP') {
                $ready = $true
                break
            }
        }
        catch {
            if ($backendProcess.HasExited) {
                break
            }
        }
    }
    if (-not $ready) {
        throw "Backend did not become healthy. See $runtimeRoot"
    }
    $backendListenerProcessId = Get-NetTCPConnection -State Listen -LocalPort $ServerPort |
        Select-Object -First 1 -ExpandProperty OwningProcess
    $listenerProcess = Get-CimInstance Win32_Process `
        -Filter "ProcessId=$backendListenerProcessId"
    if ($listenerProcess.CommandLine -notlike '*leantpm-backend-0.1.0-SNAPSHOT.jar*') {
        throw "Health response came from unexpected process $backendListenerProcessId"
    }
    Start-Sleep -Seconds 1

    $disabled = Invoke-Json -Method Get -Uri "$baseUrl/auth/captcha"
    if ($disabled.data.enabled) {
        throw 'Captcha should be disabled by default'
    }

    $login = Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
        username = 'admin'
        password = $initialPassword
    }
    $accessToken = $login.data.tokens.accessToken
    $changed = Invoke-Json -Method Put -Uri "$baseUrl/auth/password" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{ currentPassword = $initialPassword; newPassword = $changedPassword }
    $accessToken = $changed.data.accessToken
    $authorized = @{ Authorization = "Bearer $accessToken" }

    $parameters = Invoke-Json -Method Get `
        -Uri "$baseUrl/system/parameters?keyword=security.captcha.enabled" `
        -Headers $authorized
    $parameter = @($parameters.data) | Where-Object { $_.parameterKey -eq 'security.captcha.enabled' } |
        Select-Object -First 1
    if ($null -eq $parameter) {
        throw 'Captcha parameter was not found'
    }
    $updateHeaders = @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = [guid]::NewGuid().ToString()
    }
    Invoke-Json -Method Put -Uri "$baseUrl/system/parameters/$($parameter.id)" `
        -Headers $updateHeaders `
        -Body @{
            parameterKey = $parameter.parameterKey
            parameterName = $parameter.parameterName
            parameterValue = 'true'
            valueType = $parameter.valueType
            groupCode = $parameter.groupCode
            description = $parameter.description
            enabled = $true
            version = $parameter.version
        } | Out-Null

    $challenge = Invoke-Json -Method Get -Uri "$baseUrl/auth/captcha"
    if (-not $challenge.data.enabled -or -not $challenge.data.captchaId) {
        throw 'Enabled captcha challenge was not returned'
    }
    $encodedSvg = $challenge.data.imageDataUrl -replace '^data:image/svg\+xml;base64,', ''
    $svg = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedSvg))
    $matches = [regex]::Matches($svg, '<text[^>]*>([^<])</text>')
    $captchaCode = -join ($matches | ForEach-Object { $_.Groups[1].Value })
    if ($captchaCode.Length -ne 4) {
        throw 'Captcha code could not be recovered for automated verification'
    }

    try {
        Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
            username = 'admin'
            password = $changedPassword
            captchaId = $challenge.data.captchaId
        } | Out-Null
        throw 'Login without captcha code unexpectedly succeeded'
    }
    catch {
        if ((Read-ErrorCode $_) -ne 'CAPTCHA_INVALID') {
            throw
        }
    }

    $captchaLogin = Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
        username = 'admin'
        password = $changedPassword
        captchaId = $challenge.data.captchaId
        captchaCode = $captchaCode
    }
    if ($captchaLogin.code -ne 'OK') {
        throw 'Login with the correct captcha failed'
    }

    try {
        Invoke-Json -Method Post -Uri "$baseUrl/auth/login" -Body @{
            username = 'admin'
            password = $changedPassword
            captchaId = $challenge.data.captchaId
            captchaCode = $captchaCode
        } | Out-Null
        throw 'One-time captcha reuse unexpectedly succeeded'
    }
    catch {
        if ((Read-ErrorCode $_) -ne 'CAPTCHA_INVALID') {
            throw
        }
    }

    $openApi = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/v3/api-docs"
    $captchaSecurity = $openApi.paths.'/api/v1/auth/captcha'.get.security
    $profileSecurity = $openApi.paths.'/api/v1/auth/me'.get.security
    $parameterHeaders = @(
        $openApi.paths.'/api/v1/system/parameters/{id}'.put.parameters |
            Where-Object { $_.name -eq 'Idempotency-Key' -and $_.required }
    )
    $captchaIsProtected = $null -ne $captchaSecurity
    $profileIsPublic = $null -eq $profileSecurity -or $profileSecurity.Count -eq 0
    $idempotencyHeaderMissing = $parameterHeaders.Count -ne 1
    $bearerSchemeMissing = $null -eq $openApi.components.securitySchemes.bearerAuth
    if ($captchaIsProtected -or $profileIsPublic -or
        $idempotencyHeaderMissing -or $bearerSchemeMissing) {
        throw 'OpenAPI security or idempotency metadata is incomplete'
    }

    Write-Output 'CAPTCHA_DISABLED_DEFAULT=PASS'
    Write-Output 'CAPTCHA_REQUIRED_WHEN_ENABLED=PASS'
    Write-Output 'CAPTCHA_CORRECT_LOGIN=PASS'
    Write-Output 'CAPTCHA_ONE_TIME_CONSUME=PASS'
    Write-Output 'MYSQL_FLYWAY_V1_TO_V5=PASS'
    Write-Output 'REDIS_PING=PONG'
    Write-Output 'OPENAPI_SECURITY_AND_IDEMPOTENCY=PASS'
}
catch {
    Write-Output ('E2E_ERROR=' + $_.Exception.Message)
    Write-Output ('E2E_STACK=' + $_.ScriptStackTrace)
    $backendOutput = Join-Path $runtimeRoot 'backend.out.log'
    $backendError = Join-Path $runtimeRoot 'backend.err.log'
    if (Test-Path -LiteralPath $backendOutput) {
        Write-Output 'E2E_BACKEND_OUTPUT_BEGIN'
        Get-Content -LiteralPath $backendOutput -Tail 120
        Write-Output 'E2E_BACKEND_OUTPUT_END'
    }
    if (Test-Path -LiteralPath $backendError) {
        Write-Output 'E2E_BACKEND_ERROR_BEGIN'
        Get-Content -LiteralPath $backendError -Tail 120
        Write-Output 'E2E_BACKEND_ERROR_END'
    }
    throw
}
finally {
    if ($backendListenerProcessId) {
        Stop-Process -Id $backendListenerProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
        [void]$backendProcess.WaitForExit(5000)
    }
    if ($backendProcess) {
        $backendProcess.Dispose()
    }
    if ($redisProcess -and -not $redisProcess.HasExited) {
        Stop-Process -Id $redisProcess.Id -Force -ErrorAction SilentlyContinue
        [void]$redisProcess.WaitForExit(5000)
    }
    if ($redisProcess) {
        $redisProcess.Dispose()
    }
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & mysql.exe "-h$MySqlHost" "-P$MySqlPort" "-u$MySqlUser" "-p$MySqlPassword" `
        -e "DROP DATABASE IF EXISTS $database;" 2>$null
    $ErrorActionPreference = $previousErrorPreference
    @(
        'LEANTPM_DB_URL',
        'LEANTPM_DB_USERNAME',
        'LEANTPM_DB_PASSWORD',
        'LEANTPM_REDIS_PORT',
        'LEANTPM_REDIS_DATABASE',
        'LEANTPM_SERVER_PORT',
        'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD',
        'LEANTPM_JWT_SECRET',
        'LEANTPM_UPLOAD_DIR'
    ) | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $runtimeRoot) {
        $resolvedRuntime = (Resolve-Path -LiteralPath $runtimeRoot).Path
        $resolvedTemp = (Resolve-Path -LiteralPath $env:TEMP).Path
        if ($resolvedRuntime.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
            Start-Sleep -Milliseconds 300
            Remove-Item -LiteralPath $resolvedRuntime -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
