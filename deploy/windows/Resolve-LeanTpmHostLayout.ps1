[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LayoutPath,
    [Parameter(Mandatory)][string]$ExpectedLayoutSha256,
    [Parameter(Mandatory)][string]$ExpectedEnvironmentId,
    [Parameter(Mandatory)][string]$ExpectedHostId,
    [Parameter(Mandatory)][string]$ExpectedInstallRoot,
    [Parameter(Mandatory)][string]$ExpectedDataRoot,
    [Parameter(Mandatory)][string]$ExpectedVolumeIdentity,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Assert-ExactProperties {
    param($Object, [string[]]$Expected, [string]$Context)

    if ($null -eq $Object) { throw "$Context must be an object" }
    $actual = @($Object.PSObject.Properties.Name)
    if (@($actual | Where-Object { $Expected -cnotcontains $_ }).Count -gt 0 -or
            @($Expected | Where-Object { $actual -cnotcontains $_ }).Count -gt 0) {
        throw "$Context has unknown or missing fields"
    }
}

function Read-LayoutSnapshot {
    param([string]$Path)

    $stream = [IO.File]::Open(
        $Path,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    try {
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        }
        finally { $sha.Dispose() }
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        $json = $utf8.GetString($bytes)
        return [pscustomobject]@{
            sha256 = $digest
            json = $json
        }
    }
    finally { $stream.Dispose() }
}

function Resolve-LocalRoot {
    param([string]$Value, [string]$Name)

    if ([string]::IsNullOrWhiteSpace($Value) -or
            $Value -match '^(?:\\\\|\\\\\?\\|\\\\\.\\)' -or
            $Value -notmatch '^[A-Za-z]:\\' -or
            $Value -match '(^|[\\/])\.\.($|[\\/])') {
        throw "$Name must be an absolute local drive path without traversal or device syntax"
    }
    $normalizedInput = $Value.Replace('/', '\')
    $components = @($normalizedInput.Substring(3).Split('\'))
    foreach ($component in $components) {
        if ([string]::IsNullOrWhiteSpace($component) -or
                $component.EndsWith(' ') -or $component.EndsWith('.') -or
                $component -match '[\x00-\x1F<>:"|?*]' -or
                $component -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$') {
            throw "$Name contains a Windows path alias, reserved name or unsafe character"
        }
    }
    try { $resolved = [IO.Path]::GetFullPath($Value).TrimEnd('\', '/') }
    catch { throw "$Name is not a valid absolute local path" }
    if ($resolved -notmatch '^[A-Za-z]:\\' -or $resolved.Length -le 3) {
        throw "$Name cannot be a drive root"
    }
    return $resolved
}

function Test-AncestorPath {
    param([string]$Ancestor, [string]$Candidate)

    return $Candidate.StartsWith(
        ($Ancestor.TrimEnd('\') + '\'),
        [StringComparison]::OrdinalIgnoreCase
    )
}

if ($ExpectedLayoutSha256 -notmatch '^[0-9a-f]{64}$' -or
        $ExpectedEnvironmentId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $ExpectedHostId -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{2,255}$' -or
        $ExpectedVolumeIdentity -notmatch '^sha256:[0-9a-f]{64}$') {
    throw 'Expected host layout identity is invalid'
}

$resolvedLayoutPath = (Resolve-Path -LiteralPath $LayoutPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedLayoutPath -PathType Leaf) -or
        ((Get-Item -LiteralPath $resolvedLayoutPath).Attributes -band
            [IO.FileAttributes]::ReparsePoint)) {
    throw 'Host layout must be a regular non-reparse file'
}
$snapshot = Read-LayoutSnapshot $resolvedLayoutPath
$layoutSha256 = [string]$snapshot.sha256
if ($layoutSha256 -cne $ExpectedLayoutSha256) {
    throw 'Host layout bytes do not match the expected SHA-256'
}

$layoutJson = [string]$snapshot.json
$propertyTokens = [regex]::Matches(
    $layoutJson,
    '"(?<name>(?:\\.|[^"\\])*)"\s*:'
)
if ($propertyTokens.Count -notin @(11, 13) -or @($propertyTokens | Where-Object {
            $_.Groups['name'].Value.Contains('\')
        }).Count -gt 0) {
    throw 'Host layout property names must use the fixed unescaped ASCII contract'
}
foreach ($propertyName in @(
        'schemaVersion', 'readiness', 'environmentKind', 'environmentId', 'hostId',
        'installRoot', 'dataRoot', 'volumeIdentity', 'proxy', 'mode', 'serviceId'
    )) {
    $propertyPattern = '"{0}"\s*:' -f [regex]::Escape($propertyName)
    if ([regex]::Matches($layoutJson, $propertyPattern).Count -ne 1) {
        throw "Host layout property must occur exactly once: $propertyName"
    }
}
if ($propertyTokens.Count -eq 13) {
    foreach ($propertyName in @('bindingPolicyPath', 'bindingPolicySha256')) {
        $propertyPattern = '"{0}"\s*:' -f [regex]::Escape($propertyName)
        if ([regex]::Matches($layoutJson, $propertyPattern).Count -ne 1) {
            throw "External proxy property must occur exactly once: $propertyName"
        }
    }
}
$layout = $layoutJson | ConvertFrom-Json
Assert-ExactProperties $layout @(
    'schemaVersion', 'readiness', 'environmentKind', 'environmentId', 'hostId',
    'installRoot', 'dataRoot', 'volumeIdentity', 'proxy'
) 'host layout'
$proxyMode = [string]$layout.proxy.mode
$proxyServiceId = [string]$layout.proxy.serviceId
if ($proxyMode -ceq 'EXTERNAL_EXISTING') {
    Assert-ExactProperties $layout.proxy @(
        'mode', 'serviceId', 'bindingPolicyPath', 'bindingPolicySha256'
    ) 'host layout proxy'
}
else {
    Assert-ExactProperties $layout.proxy @('mode', 'serviceId') 'host layout proxy'
}

if (($layout.schemaVersion -isnot [int] -and $layout.schemaVersion -isnot [long]) -or
        [int64]$layout.schemaVersion -ne 1 -or
        $layout.readiness -isnot [string] -or [string]$layout.readiness -cne 'READY' -or
        $layout.environmentKind -isnot [string] -or
        [string]$layout.environmentKind -cne 'PRODUCTION' -or
        $layout.environmentId -isnot [string] -or
        [string]$layout.environmentId -cne $ExpectedEnvironmentId -or
        $layout.hostId -isnot [string] -or
        [string]$layout.hostId -cne $ExpectedHostId -or
        $layout.installRoot -isnot [string] -or $layout.dataRoot -isnot [string] -or
        $layout.volumeIdentity -isnot [string] -or
        [string]$layout.volumeIdentity -cne $ExpectedVolumeIdentity -or
        [string]$layout.volumeIdentity -notmatch '^sha256:[0-9a-f]{64}$') {
    throw 'Host layout identity or production classification is invalid'
}

$installRoot = Resolve-LocalRoot ([string]$layout.installRoot) 'installRoot'
$dataRoot = Resolve-LocalRoot ([string]$layout.dataRoot) 'dataRoot'
$reservedMySqlRoot = [IO.Path]::GetFullPath('D:\LeanTPM\data').TrimEnd('\')
if ($dataRoot.Equals($reservedMySqlRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'dataRoot must not reuse the existing D:\LeanTPM\data MySQL storage tree'
}
$expectedInstall = Resolve-LocalRoot $ExpectedInstallRoot 'ExpectedInstallRoot'
$expectedData = Resolve-LocalRoot $ExpectedDataRoot 'ExpectedDataRoot'
if (-not $installRoot.Equals($expectedInstall, [StringComparison]::OrdinalIgnoreCase) -or
        -not $dataRoot.Equals($expectedData, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Host layout roots do not match the caller-bound production roots'
}
if ($installRoot.Equals($dataRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Test-AncestorPath $installRoot $dataRoot) -or
        (Test-AncestorPath $dataRoot $installRoot)) {
    throw 'InstallRoot and DataRoot must be distinct non-nested trust boundaries'
}

if ($layout.proxy.mode -isnot [string] -or $layout.proxy.serviceId -isnot [string] -or
        (($proxyMode -cne 'EXTERNAL_EXISTING' -or $proxyServiceId -cne 'caddy') -and
        ($proxyMode -cne 'MANAGED_LEANTPM_PROXY' -or
            $proxyServiceId -cne 'LeanTPM.Proxy'))) {
    throw 'Proxy mode and fixed service identifier are inconsistent'
}
$proxyBindingPolicyPath = $null
$proxyBindingPolicySha256 = $null
if ($proxyMode -ceq 'EXTERNAL_EXISTING') {
    if ($layout.proxy.bindingPolicyPath -isnot [string] -or
            $layout.proxy.bindingPolicySha256 -isnot [string] -or
            [string]$layout.proxy.bindingPolicySha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'External proxy binding policy path or digest is invalid'
    }
    $proxyBindingPolicyPath = Resolve-LocalRoot `
        ([string]$layout.proxy.bindingPolicyPath) 'proxy.bindingPolicyPath'
    $expectedProxyConfigRoot = Join-Path $dataRoot 'config'
    if (-not $proxyBindingPolicyPath.StartsWith(
            $expectedProxyConfigRoot + '\', [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'External proxy binding policy must remain under the approved DataRoot config'
    }
    $proxyBindingPolicySha256 = [string]$layout.proxy.bindingPolicySha256
}

if (-not $PlanOnly) {
    throw 'Host filesystem and bootstrap ACL verification is required before this layout can be executed'
}

$paths = [ordered]@{
    installRoot = $installRoot
    dataRoot = $dataRoot
    releases = Join-Path $installRoot 'releases'
    current = Join-Path $installRoot 'current'
    service = Join-Path $installRoot 'service'
    proxyService = Join-Path $installRoot 'proxy'
    staging = Join-Path $dataRoot 'staging'
    config = Join-Path $dataRoot 'config'
    uploads = Join-Path $dataRoot 'data\uploads'
    pointers = Join-Path $dataRoot 'pointers'
    backups = Join-Path $dataRoot 'backups'
    logs = Join-Path $dataRoot 'logs'
    audit = Join-Path $dataRoot 'audit'
    locks = Join-Path $dataRoot 'locks'
    state = Join-Path $dataRoot 'state'
    secrets = Join-Path $dataRoot 'secrets'
}
$report = [pscustomobject]@{
    status = 'PLAN_ONLY'
    executable = $false
    trustSource = 'CALLER_BOUND_PLAN_ONLY'
    schemaVersion = 1
    readiness = 'READY'
    layoutSha256 = $layoutSha256
    environmentKind = 'PRODUCTION'
    environmentId = [string]$layout.environmentId
    hostId = [string]$layout.hostId
    volumeIdentity = [string]$layout.volumeIdentity
    hostFilesystemVerified = $false
    paths = [pscustomobject]$paths
    proxy = [pscustomobject]@{
        mode = $proxyMode
        serviceId = $proxyServiceId
        bindingPolicyPath = $proxyBindingPolicyPath
        bindingPolicySha256 = $proxyBindingPolicySha256
    }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 6 -Compress }
else { $report | Format-List }
