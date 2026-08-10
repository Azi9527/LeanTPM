[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$TargetReleaseId,
    [string]$DeploymentLockToken = '',
    [string]$ExpectedHostLayoutSha256 = '',
    [string]$ExpectedManifestSha256 = '',
    [switch]$AllowNonProductionRoot,
    [switch]$AllowUncoordinatedNonProductionSwitch,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($TargetReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $TargetReleaseId.EndsWith('.') -or
        $TargetReleaseId -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)') {
    throw 'TargetReleaseId is not a safe Windows release identifier'
}
$root = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoot) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $root -DataRoot $data -EnvironmentKind $environmentKind `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRoot = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRoot -and $AllowNonProductionRoot) {
    throw 'AllowNonProductionRoot cannot be used with the production InstallRoot'
}
if (-not $isProductionRoot -and -not $AllowNonProductionRoot) {
    throw 'InstallRoot must be the host-owned production root'
}
if ($AllowUncoordinatedNonProductionSwitch -and -not $AllowNonProductionRoot) {
    throw 'Uncoordinated current switching is limited to isolated NON_PRODUCTION roots'
}
if ($AllowUncoordinatedNonProductionSwitch -and
        -not [string]::IsNullOrWhiteSpace($DeploymentLockToken)) {
    throw 'Uncoordinated NON_PRODUCTION switching cannot claim a deployment lock token'
}
if (-not $AllowUncoordinatedNonProductionSwitch) {
    $lockPath = Join-Path $data 'locks\deployment.lock'
    if ($DeploymentLockToken -notmatch '^[a-f0-9]{64}$' -or
            -not (Test-Path -LiteralPath $lockPath -PathType Leaf) -or
            (Get-Content -LiteralPath $lockPath -Encoding ascii -Raw).Trim() -cne
                $DeploymentLockToken) {
        throw 'Caller deployment lock token is invalid for current switching'
    }
}
if ($isProductionRoot) {
    if ($ExpectedHostLayoutSha256 -notmatch '^[a-f0-9]{64}$' -or
            [string]$rootPolicy.hostLayoutSha256 -cne $ExpectedHostLayoutSha256) {
        throw 'Current switch host layout digest differs from the verified production layout'
    }
}
$releasesRoot = [System.IO.Path]::GetFullPath((Join-Path $root 'releases')).TrimEnd('\')
$target = [System.IO.Path]::GetFullPath((Join-Path $releasesRoot $TargetReleaseId)).TrimEnd('\')
if (-not $target.StartsWith(
        $releasesRoot + '\',
        [System.StringComparison]::OrdinalIgnoreCase
    ) -or -not (Test-Path -LiteralPath (Join-Path $target 'payload\web') -PathType Container)) {
    throw 'Target release is outside releases or has no verified Web payload'
}
$manifestStream = $null
if (-not $AllowUncoordinatedNonProductionSwitch) {
    if ($ExpectedManifestSha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'ExpectedManifestSha256 is required for coordinated current switching'
    }
    $manifestPath = Join-Path $target 'release-manifest.json'
    $manifestStream = [IO.File]::Open(
        $manifestPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    $manifestHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $actualManifestSha256 = [BitConverter]::ToString(
            $manifestHasher.ComputeHash($manifestStream)
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $manifestHasher.Dispose() }
    if ($actualManifestSha256 -cne $ExpectedManifestSha256) {
        $manifestStream.Dispose()
        $manifestStream = $null
        throw 'Target release manifest differs from the authorized digest'
    }
}
$current = Join-Path $root 'current'
$next = Join-Path $root ("current.new.{0}" -f [Guid]::NewGuid().ToString('N'))
$previous = Join-Path $root ("current.previous.{0}" -f [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Junction -Path $next -Target $target
try {
    if (Test-Path -LiteralPath $current) {
        $currentItem = Get-Item -LiteralPath $current -Force
        if (($currentItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
            throw 'Existing current path is not a reparse point; refusing replacement'
        }
        [System.IO.Directory]::Move($current, $previous)
        try { [System.IO.Directory]::Move($next, $current) }
        catch {
            [System.IO.Directory]::Move($previous, $current)
            throw
        }
        [System.IO.Directory]::Delete($previous)
    }
    else {
        [System.IO.Directory]::Move($next, $current)
    }
    $actualTarget = [System.IO.Path]::GetFullPath([string](Get-Item -LiteralPath $current -Force).Target).
        TrimEnd('\')
    if (-not $actualTarget.Equals($target, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Current junction target verification failed after replacement'
    }
}
finally {
    if (Test-Path -LiteralPath $next) {
        $nextItem = Get-Item -LiteralPath $next -Force
        if (($nextItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
            throw 'Refusing to clean a non-reparse temporary current path'
        }
        [System.IO.Directory]::Delete($next)
    }
    if ($null -ne $manifestStream) { $manifestStream.Dispose() }
}
$report = [pscustomobject]@{
    status = 'SWITCHED'
    releaseId = $TargetReleaseId
    currentPath = $current
    targetPath = $target
    hostLayoutSha256 = if ($isProductionRoot) { [string]$rootPolicy.hostLayoutSha256 } else { $null }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
