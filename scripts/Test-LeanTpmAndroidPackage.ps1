[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$ExpectedPackageName,
    [Parameter(Mandatory)][string]$ExpectedVersionName,
    [Parameter(Mandatory)][int]$ExpectedVersionCode,
    [Parameter(Mandatory)][string]$TrustedSignerSha256,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if ($TrustedSignerSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
    throw 'TrustedSignerSha256 must be a host-approved SHA-256 certificate digest'
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$buildToolsRoot = Join-Path $AndroidSdk 'build-tools'
$toolDirectory = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction Stop |
    Sort-Object { [version]$_.Name } -Descending | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe'))
    } | Select-Object -First 1
if ($null -eq $toolDirectory) {
    throw 'Android build tools with apksigner and aapt were not found'
}
$apkSigner = Join-Path $toolDirectory.FullName 'apksigner.bat'
$aapt = Join-Path $toolDirectory.FullName 'aapt.exe'

$signatureOutput = @(& $apkSigner verify --verbose --print-certs $resolvedApk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$signerDigests = @($signatureOutput | ForEach-Object {
        if ([string]$_ -match 'Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})') {
            $Matches[1].ToLowerInvariant()
        }
    } | Select-Object -Unique)
if ($signerDigests.Count -ne 1 -or
        $signerDigests[0] -cne $TrustedSignerSha256.ToLowerInvariant()) {
    throw 'APK signer certificate does not match the host-approved SHA-256 digest'
}

$badging = (@(& $aapt dump badging $resolvedApk 2>&1) -join "`n")
if ($LASTEXITCODE -ne 0) { throw 'Unable to read APK package metadata' }
$packageMatch = [regex]::Match($badging, "package:\s+name='([^']+)'")
$versionCodeMatch = [regex]::Match($badging, "versionCode='(\d+)'")
$versionNameMatch = [regex]::Match($badging, "versionName='([^']+)'")
if (-not $packageMatch.Success -or -not $versionCodeMatch.Success -or
        -not $versionNameMatch.Success) {
    throw 'APK package metadata is incomplete'
}
if ($packageMatch.Groups[1].Value -cne $ExpectedPackageName -or
        $versionNameMatch.Groups[1].Value -cne $ExpectedVersionName -or
        [int]$versionCodeMatch.Groups[1].Value -ne $ExpectedVersionCode) {
    throw 'APK package, versionName, or versionCode differs from the unified version contract'
}

$file = Get-Item -LiteralPath $resolvedApk
$report = [pscustomobject]@{
    status = 'PASS'
    packageName = $packageMatch.Groups[1].Value
    versionName = $versionNameMatch.Groups[1].Value
    versionCode = [int]$versionCodeMatch.Groups[1].Value
    signerSha256 = $signerDigests[0]
    bytes = [int64]$file.Length
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApk).Hash.ToLowerInvariant()
    buildToolsVersion = $toolDirectory.Name
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }
