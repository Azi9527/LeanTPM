[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DiscoveryPath,
    [Parameter(Mandatory)][string]$ArtifactManifestPath,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]{3,63}$')]
    [string]$ExpectedComputerName,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $PlanOnly) {
    throw 'WORKGROUP rapid bootstrap planning is non-executable and requires PlanOnly'
}

$fixedMissingTargets = @(
    'C:\ProgramData\LeanTPM-bootstrap',
    'C:\ProgramData\LeanTPM-bootstrap\host-layout.json',
    'D:\LeanTPM\Runtime\config\release-trust.json',
    'D:\LeanTPM\Runtime\config\external-caddy-binding.json',
    'D:\LeanTPM\App\ops-services',
    'D:\LeanTPM\Runtime\ops-control-plane',
    'D:\LeanTPM\Runtime\release-agent'
)
$fixedServiceIds = @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')
$fixedActions = @(
    'CREATE_FIXED_HOST_POLICY',
    'CREATE_FIXED_RELEASE_TRUST',
    'STAGE_OPS_ARTIFACTS',
    'INSTALL_DISABLED_OPS_SERVICES',
    'VERIFY_LOOPBACK_CONTROL_PLANE'
)

function Get-Sha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Read-StrictJsonFile {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label path must be absolute"
    }
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse file"
    }
    $stream = [IO.File]::Open(
        $resolved,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    try {
        if ($stream.Length -gt 4MB) { throw "$Label exceeds the 4 MiB limit" }
        $memory = New-Object IO.MemoryStream
        try {
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
        }
        finally { $memory.Dispose() }
    }
    finally { $stream.Dispose() }
    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    try {
        $json = $strictUtf8.GetString($bytes)
        $value = $json | ConvertFrom-Json -ErrorAction Stop
    }
    catch { throw "$Label must be strict UTF-8 JSON" }
    return [pscustomobject]@{
        path = $resolved
        sha256 = Get-Sha256 $bytes
        value = $value
    }
}

function Assert-ExactProperties {
    param($Value, [string[]]$Expected, [string]$Label)
    if ($null -eq $Value) { throw "$Label is missing" }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) {
        throw "$Label property count is invalid"
    }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Assert-Sha256 {
    param([string]$Value, [string]$Label)
    if ($Value -cnotmatch '^[a-f0-9]{64}\z') {
        throw "$Label must be a lowercase SHA-256"
    }
}

$discoverySnapshot = Read-StrictJsonFile $DiscoveryPath 'Discovery'
$artifactSnapshot = Read-StrictJsonFile $ArtifactManifestPath 'Artifact manifest'
$discovery = $discoverySnapshot.value
$artifacts = $artifactSnapshot.value

Assert-ExactProperties $discovery @(
    'Status', 'ComputerName', 'PartOfDomain', 'Domain', 'Paths', 'Services',
    'HostLayout', 'ReleaseTrust'
) 'Discovery'
if ([string]$discovery.Status -cne 'OPS_BOOTSTRAP_READ_ONLY_DISCOVERY') {
    throw 'Discovery status is not the read-only Ops bootstrap contract'
}
if ([string]$discovery.ComputerName -cne $ExpectedComputerName) {
    throw 'Discovery computer name differs from the expected host'
}
if ($discovery.PartOfDomain -isnot [bool] -or [bool]$discovery.PartOfDomain -or
        [string]$discovery.Domain -cne 'WORKGROUP') {
    throw 'WORKGROUP rapid bootstrap is forbidden on a domain-joined host'
}
if ($null -ne $discovery.HostLayout -or $null -ne $discovery.ReleaseTrust) {
    throw 'Rapid bootstrap refuses a pre-existing HostLayout or release trust'
}

$paths = @($discovery.Paths)
if ($paths.Count -ne $fixedMissingTargets.Count) {
    throw 'Discovery path inventory is incomplete or contains an extra target'
}
foreach ($target in $fixedMissingTargets) {
    $matching = @($paths | Where-Object { [string]$_.Path -ceq $target })
    if ($matching.Count -ne 1) {
        throw "Discovery does not contain the exact bootstrap target $target"
    }
    $entry = $matching[0]
    Assert-ExactProperties $entry @(
        'Path', 'Exists', 'Type', 'IsReparse', 'SHA256'
    ) "Discovery path $target"
    if ($entry.Exists -isnot [bool] -or [bool]$entry.Exists -or
            [string]$entry.Type -cne 'MISSING' -or
            $entry.IsReparse -isnot [bool] -or [bool]$entry.IsReparse -or
            $null -ne $entry.SHA256) {
        throw "Rapid bootstrap target already exists or is not proven missing: $target"
    }
}

$services = @($discovery.Services)
foreach ($serviceId in @('LeanTPM.Backend', 'caddy') + $fixedServiceIds) {
    if (@($services | Where-Object { [string]$_.Name -ceq $serviceId }).Count -ne 1) {
        throw "Discovery service inventory is missing exact service $serviceId"
    }
}
$backend = @($services | Where-Object { [string]$_.Name -ceq 'LeanTPM.Backend' })[0]
$caddy = @($services | Where-Object { [string]$_.Name -ceq 'caddy' })[0]
if ([string]$backend.State -cne 'Running' -or
        [string]$backend.StartMode -cne 'Auto' -or
        [string]$backend.StartName -cne 'NT AUTHORITY\NetworkService' -or
        [int64]$backend.ProcessId -le 0 -or
        -not ([string]$backend.PathName).Trim().Trim('"').Equals(
            'D:\LeanTPM\App\service\LeanTPM.Backend.exe',
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Backend service does not match the fixed existing production binding'
}
if ([string]$caddy.State -cne 'Running' -or
        [string]$caddy.StartMode -cne 'Auto' -or
        [string]$caddy.StartName -cnotin @('LocalSystem', 'NT AUTHORITY\SYSTEM') -or
        [int64]$caddy.ProcessId -le 0 -or
        [string]$caddy.PathName -cnotmatch
            '^D:\\LeanTPM\\tools\\caddy\\caddy\.exe run --environ --config D:\\LeanTPM\\shared\\config\\Caddyfile --adapter caddyfile\z') {
    throw 'Caddy service does not match the fixed existing production binding'
}
foreach ($serviceId in $fixedServiceIds) {
    $service = @($services | Where-Object { [string]$_.Name -ceq $serviceId })[0]
    if ([string]$service.State -cne 'NOT_INSTALLED' -or
            [int64]$service.ProcessId -ne 0) {
        throw "Rapid bootstrap target service is already installed: $serviceId"
    }
}

Assert-ExactProperties $artifacts @(
    'schemaVersion', 'productVersion', 'mainCommit', 'opsControlJarSha256',
    'winSWSha256', 'javaSha256', 'deploymentToolkitLockSha256', 'caddySha256'
) 'Artifact manifest'
if (($artifacts.schemaVersion -isnot [int] -and
        $artifacts.schemaVersion -isnot [long]) -or
        [int64]$artifacts.schemaVersion -ne 1 -or
        [string]$artifacts.productVersion -cnotmatch
            '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?\z' -or
        [string]$artifacts.mainCommit -cnotmatch '^[a-f0-9]{40}\z') {
    throw 'Artifact manifest version or main commit is invalid'
}
foreach ($name in @(
        'opsControlJarSha256', 'winSWSha256', 'javaSha256',
        'deploymentToolkitLockSha256', 'caddySha256'
    )) {
    Assert-Sha256 ([string]$artifacts.$name) "Artifact manifest $name"
}

$planCore = [ordered]@{
    schemaVersion = 1
    bootstrapMode = 'WORKGROUP_RAPID'
    computerName = [string]$discovery.ComputerName
    discoverySha256 = [string]$discoverySnapshot.sha256
    artifactManifestSha256 = [string]$artifactSnapshot.sha256
    productVersion = [string]$artifacts.productVersion
    mainCommit = [string]$artifacts.mainCommit
    serviceIds = $fixedServiceIds
    actions = $fixedActions
    webConfirmationCount = 1
}
$planCoreJson = $planCore | ConvertTo-Json -Depth 5 -Compress
$report = [pscustomobject][ordered]@{
    status = 'PLAN'
    executable = $false
    bootstrapMode = 'WORKGROUP_RAPID'
    computerName = [string]$discovery.ComputerName
    productVersion = [string]$artifacts.productVersion
    mainCommit = [string]$artifacts.mainCommit
    discoverySha256 = [string]$discoverySnapshot.sha256
    artifactManifestSha256 = [string]$artifactSnapshot.sha256
    planSha256 = Get-Sha256 ([Text.Encoding]::UTF8.GetBytes($planCoreJson))
    serviceIds = $fixedServiceIds
    actions = $fixedActions
    webConfirmationCount = 1
    productionMutationAuthorized = $false
}

if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 5 -Compress
}
else { $report | Format-List }
