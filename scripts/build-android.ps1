param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$OutputDirectory = '',
    [string]$GradleExecutable = $env:LEANTPM_GRADLE_EXECUTABLE
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$androidRoot = Join-Path $frontendRoot 'android'

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar'))) {
    throw "Android SDK Platform 36 was not found under $AndroidSdk"
}
if (-not (Get-Command node.exe -ErrorAction SilentlyContinue)) {
    throw 'Node.js is required'
}
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    throw 'Java 21 is required'
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules'))) {
    Push-Location $frontendRoot
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    }
    finally {
        Pop-Location
    }
}

if ($Configuration -eq 'Release') {
    $requiredSigningVariables = @(
        'LEANTPM_ANDROID_KEYSTORE',
        'LEANTPM_ANDROID_STORE_PASSWORD',
        'LEANTPM_ANDROID_KEY_ALIAS',
        'LEANTPM_ANDROID_KEY_PASSWORD'
    )
    foreach ($name in $requiredSigningVariables) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            throw "Release signing variable $name is required"
        }
    }
}

Push-Location $frontendRoot
try {
    & npm.cmd run mobile:sync
    if ($LASTEXITCODE -ne 0) { throw 'Capacitor sync failed' }
}
finally {
    Pop-Location
}

$task = "assemble$Configuration"
if ([string]::IsNullOrWhiteSpace($GradleExecutable)) {
    $GradleExecutable = Join-Path $androidRoot 'gradlew.bat'
}
if (-not (Test-Path -LiteralPath $GradleExecutable)) {
    throw "Gradle executable was not found at $GradleExecutable"
}
Push-Location $androidRoot
try {
    & $GradleExecutable --no-daemon --console=plain $task
    if ($LASTEXITCODE -ne 0) { throw "Gradle task $task failed" }
}
finally {
    Pop-Location
}

$variant = $Configuration.ToLowerInvariant()
$sourceApk = Join-Path $androidRoot "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path -LiteralPath $sourceApk)) {
    throw "APK was not created at $sourceApk"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'runtime\deliverables'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destinationApk = Join-Path $OutputDirectory "LeanTPM-M6-$variant.apk"
Copy-Item -LiteralPath $sourceApk -Destination $destinationApk -Force

$apkSigner = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') `
    -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if ($apkSigner) {
    & $apkSigner verify --verbose $destinationApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
}

$file = Get-Item -LiteralPath $destinationApk
$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $destinationApk
[pscustomobject]@{
    Apk = $file.FullName
    Bytes = $file.Length
    Sha256 = $hash.Hash
}
