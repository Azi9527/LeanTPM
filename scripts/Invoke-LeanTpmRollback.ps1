[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [switch]$PlanOnly,
    [switch]$ConfirmRollback,
    [switch]$AllowNonProductionCustomRoots,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedPlan = (Resolve-Path -LiteralPath $PlanPath).Path
$loadedPlanBytes = [System.IO.File]::ReadAllBytes($resolvedPlan)
$strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$plan = $strictUtf8.GetString($loadedPlanBytes) | ConvertFrom-Json
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $loadedPlanSha256 = [BitConverter]::ToString($sha256.ComputeHash($loadedPlanBytes)).Replace('-', '').
        ToLowerInvariant()
}
finally {
    $sha256.Dispose()
}
foreach ($name in @(
        'schemaVersion', 'environmentName', 'environmentKind', 'rollbackId', 'approvalId',
        'failedReleaseId', 'targetReleaseId', 'installRoot', 'dataRoot', 'serviceId',
        'rollbackClass', 'healthUri', 'targetRuntimeConfigId',
        'targetRuntimeConfigSha256'
    )) {
    if ($null -eq $plan.PSObject.Properties[$name]) { throw "Rollback plan is missing '$name'" }
}
if ([int]$plan.schemaVersion -ne 1 -or
        [string]$plan.environmentKind -notin @('NON_PRODUCTION', 'PRODUCTION')) {
    throw 'Unsupported rollback plan schemaVersion or environmentKind'
}
if ([string]$plan.serviceId -cne 'LeanTPM.Backend') {
    throw 'Rollback may control only the fixed LeanTPM.Backend service'
}
if ([string]$plan.rollbackClass -notin @('APPLICATION_ONLY', 'FORWARD_COMPATIBLE_SCHEMA')) {
    throw 'RECOVERY_REQUIRED: this rollback class cannot use automatic application rollback'
}
if ([string]$plan.targetRuntimeConfigId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.targetRuntimeConfigSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Rollback plan must bind an immutable target runtime configuration'
}
foreach ($idName in @('rollbackId', 'approvalId', 'failedReleaseId', 'targetReleaseId')) {
    if ([string]$plan.$idName -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.$idName -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
            [string]$plan.$idName -match '\.$') {
        throw "$idName contains unsupported Windows characters or a reserved name"
    }
}
$healthUri = [Uri]([string]$plan.healthUri)
if ($healthUri.Scheme -ne 'http' -or $healthUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or
        -not $healthUri.AbsolutePath.EndsWith('/actuator/health/readiness')) {
    throw 'healthUri must be the loopback readiness endpoint'
}
$installRoot = (Resolve-Path -LiteralPath ([string]$plan.installRoot)).Path.TrimEnd('\', '/')
$dataRoot = (Resolve-Path -LiteralPath ([string]$plan.dataRoot)).Path.TrimEnd('\', '/')
$rootPolicy = & (Join-Path $PSScriptRoot '..\deploy\windows\Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $installRoot -DataRoot $dataRoot `
    -EnvironmentKind ([string]$plan.environmentKind) -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionCustomRoots `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
$customRoots = -not $isProductionRootPair
if ($isProductionRootPair -and [string]$plan.environmentKind -ne 'PRODUCTION') {
    throw 'The production root pair requires environmentKind=PRODUCTION'
}
if ($isProductionRootPair -and $AllowNonProductionCustomRoots) {
    throw 'AllowNonProductionCustomRoots cannot be used with the production root pair'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    foreach ($layoutField in @(
            'hostLayoutSha256', 'environmentId', 'hostId', 'volumeIdentity',
            'proxyBindingSha256'
        )) {
        if ($null -eq $plan.PSObject.Properties[$layoutField] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$layoutField)) {
            throw "PRODUCTION rollback plan must bind $layoutField"
        }
    }
    $layoutSha256 = [string]$rootPolicy.hostLayoutSha256
    $proxyBindingSha256 = [string]$rootPolicy.proxyBindingSha256
    if ([string]$plan.hostLayoutSha256 -notmatch '^[a-f0-9]{64}$' -or
            [string]$plan.hostLayoutSha256 -cne $layoutSha256 -or
            [string]$plan.environmentId -cne [string]$rootPolicy.environmentId -or
            [string]$plan.hostId -cne [string]$rootPolicy.hostId -or
            [string]$plan.volumeIdentity -cne [string]$rootPolicy.volumeIdentity -or
            [string]$plan.proxyBindingSha256 -notmatch '^[a-f0-9]{64}$' -or
            [string]$plan.proxyBindingSha256 -cne $proxyBindingSha256) {
        throw 'PRODUCTION rollback plan does not match the verified production root pair layout'
    }
}

function Assert-HostLayoutPolicyUnchanged {
    $lockedPolicy = & (Join-Path $PSScriptRoot `
        '..\deploy\windows\Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -EnvironmentKind ([string]$plan.environmentKind) `
        -AllowNonProductionCustomRoots:$AllowNonProductionCustomRoots `
        -OutputFormat Json | ConvertFrom-Json
    if ([bool]$lockedPolicy.isProductionRootPair -ne $isProductionRootPair -or
            [bool]$lockedPolicy.customRoots -ne $customRoots -or
            ($isProductionRootPair -and (
                [string]$lockedPolicy.hostLayoutSha256 -cne $layoutSha256 -or
                [string]$lockedPolicy.environmentId -cne [string]$plan.environmentId -or
                [string]$lockedPolicy.hostId -cne [string]$plan.hostId -or
                [string]$lockedPolicy.volumeIdentity -cne [string]$plan.volumeIdentity -or
                [string]$lockedPolicy.proxyBindingSha256 -cne $proxyBindingSha256
            ))) {
        throw 'Host layout policy changed after plan validation and before rollback side effects'
    }
}

function Invoke-BackendService {
    param([Parameter(Mandatory)][ValidateSet('Start', 'Stop')][string]$Action)

    & (Join-Path $PSScriptRoot '..\deploy\windows\Invoke-LeanTpmWindowsService.ps1') `
        -Action $Action `
        -InstallRoot $installRoot `
        -DataRoot $dataRoot `
        -DeploymentLockToken $deploymentLockToken `
        -AllowNonProductionRoot:($customRoots -and
            [string]$plan.environmentKind -eq 'NON_PRODUCTION') `
        -ConfirmServiceAction `
        -Confirm:$false `
        -OutputFormat Json | ConvertFrom-Json
}

function Invoke-FailClosedBackendStop {
    & (Join-Path $PSScriptRoot '..\deploy\windows\Stop-LeanTpmBackendFailClosed.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -DeploymentLockToken $deploymentLockToken -BackendPort $healthUri.Port `
        -AllowNonProductionRoot:($customRoots -and
            [string]$plan.environmentKind -eq 'NON_PRODUCTION') `
        -OutputFormat Json | ConvertFrom-Json
}

function Wait-ReleaseReadiness {
    param(
        [Parameter(Mandatory)][string]$ExpectedVersion,
        [Parameter(Mandatory)][int]$ExpectedSchema
    )

    for ($attempt = 1; $attempt -le 12; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 5
            $infoUri = [Uri]("{0}://{1}:{2}/actuator/info" -f
                $healthUri.Scheme, $healthUri.Host, $healthUri.Port)
            $info = Invoke-RestMethod -Uri $infoUri -TimeoutSec 5
            if ([string]$health.status -eq 'UP' -and
                    [string]$info.app.version -ceq $ExpectedVersion -and
                    [int]$info.app.'database-schema-version' -eq $ExpectedSchema) {
                return
            }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    throw "Readiness identity did not reach version $ExpectedVersion / schema $ExpectedSchema"
}

function Invoke-CompensationStep {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Action,
        [Parameter(Mandatory)]
        [System.Collections.Generic.List[string]]$Errors
    )

    try {
        & $Action | Out-Null
        return $true
    }
    catch {
        $Errors.Add(("{0}: {1}" -f $Name, $_.Exception.Message))
        return $false
    }
}
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    rollbackId = [string]$plan.rollbackId
    approvalId = [string]$plan.approvalId
    failedReleaseId = [string]$plan.failedReleaseId
    targetReleaseId = [string]$plan.targetReleaseId
    rollbackClass = [string]$plan.rollbackClass
    hostLayoutSha256 = if ($isProductionRootPair) { $layoutSha256 } else { $null }
    proxyBindingSha256 = if ($isProductionRootPair) { $proxyBindingSha256 } else { $null }
    steps = @('LOCK', 'PREFLIGHT', 'STOP_SERVICE', 'SWITCH_POINTER', 'START_SERVICE',
        'VERIFY_READINESS', 'AUDIT')
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmRollback) { throw 'ConfirmRollback is required before stopping the service' }

$trustConfigPath = Join-Path $dataRoot 'config\release-trust.json'
$manifestThumbprint = ''
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    foreach ($field in @(
            'requestedBy', 'approvedBy', 'requesterSignaturePath', 'approverSignaturePath'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION rollback requires signed approval field $field"
        }
    }
    $trust = Get-Content -LiteralPath (Resolve-Path -LiteralPath $trustConfigPath).Path `
        -Encoding utf8 -Raw | ConvertFrom-Json
    $manifestThumbprint = [string]$trust.manifestCertificateThumbprint
        foreach ($field in @(
                'nonce', 'expiresAtUtc', 'expectedCurrentPackageSha256',
                'expectedTargetPackageSha256', 'targetManifestSha256',
                'expectedCurrentRuntimeConfigSha256',
                'expectedTargetRuntimeConfigSha256',
                'environmentId', 'hostId', 'hostLayoutSha256', 'volumeIdentity',
                'proxyBindingSha256'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION rollback plan must bind $field"
        }
    }
    if ([string]$trust.environmentId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$trust.hostId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.environmentId -cne [string]$trust.environmentId -or
            [string]$plan.hostId -cne [string]$trust.hostId) {
        throw 'PRODUCTION rollback approval is not bound to this environment and host'
    }
    $expiresAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$plan.expiresAtUtc, [ref]$expiresAt) -or
            -not ([string]$plan.expiresAtUtc).EndsWith('Z') -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -gt [DateTimeOffset]::UtcNow.AddHours(24) -or
            [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$') {
        throw 'PRODUCTION rollback nonce or expiry is invalid'
    }
    $approvalReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmReleaseApproval.ps1') `
        -PlanPath $resolvedPlan `
        -RequesterSignaturePath ([string]$plan.requesterSignaturePath) `
        -ApproverSignaturePath ([string]$plan.approverSignaturePath) `
        -TrustConfigPath $trustConfigPath `
        -OutputFormat Json | ConvertFrom-Json
    if (-not ([string]$approvalReport.planSha256).Equals(
            $loadedPlanSha256,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Approved rollback plan bytes differ from the plan loaded for execution'
    }
}
$pointersRoot = Join-Path $dataRoot 'pointers'
$currentPointer = Join-Path $pointersRoot 'current-release.json'
$previousPointer = Join-Path $pointersRoot 'previous-release.json'
$currentConfigPointer = Join-Path $pointersRoot 'current-config.json'
$previousConfigPointer = Join-Path $pointersRoot 'previous-config.json'
$currentPointerContent = Get-Content -LiteralPath (Resolve-Path -LiteralPath $currentPointer).Path `
    -Encoding utf8 -Raw
$currentPointerObject = $currentPointerContent | ConvertFrom-Json
$targetPointerContent = Get-Content -LiteralPath (Resolve-Path -LiteralPath $previousPointer).Path `
    -Encoding utf8 -Raw
$targetPointerObject = $targetPointerContent | ConvertFrom-Json
$currentConfigPointerContent = Get-Content -LiteralPath (
    Resolve-Path -LiteralPath $currentConfigPointer
).Path -Encoding utf8 -Raw
$currentConfigPointerObject = $currentConfigPointerContent | ConvertFrom-Json
if ([string]$currentPointerObject.releaseId -cne [string]$plan.failedReleaseId -or
        [string]$targetPointerObject.releaseId -cne [string]$plan.targetReleaseId -or
        [string]$currentConfigPointerObject.releaseId -cne [string]$plan.failedReleaseId -or
        [string]$currentConfigPointerObject.configId -notmatch
            '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$currentConfigPointerObject.directorySha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.targetRuntimeConfigSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Rollback plan must match the current and previous release pointers'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and
        (-not ([string]$currentPointerObject.packageSha256).Equals(
                [string]$plan.expectedCurrentPackageSha256,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or
            -not ([string]$targetPointerObject.packageSha256).Equals(
                [string]$plan.expectedTargetPackageSha256,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or
            [string]$currentConfigPointerObject.directorySha256 -cne
                [string]$plan.expectedCurrentRuntimeConfigSha256 -or
            [string]$plan.targetRuntimeConfigSha256 -cne
                [string]$plan.expectedTargetRuntimeConfigSha256)) {
    throw 'PRODUCTION rollback expected package digests do not match the host pointers'
}
$repositorySchema = Join-Path $PSScriptRoot '..\release\release-manifest.schema.json'

function Get-VerifiedReleaseManifest {
    param([string]$ReleaseId, [string]$Role)

    $root = Join-Path $installRoot ("releases\{0}" -f $ReleaseId)
    $manifestPath = Join-Path $root 'release-manifest.json'
    $schemaPath = Join-Path $root 'release-manifest.schema.json'
    $payloadRoot = Join-Path $root 'payload'
    foreach ($path in @($manifestPath, $schemaPath, $payloadRoot)) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "$Role verified release component is missing: $path"
        }
    }
    if (-not (Get-FileHash -Algorithm SHA256 -LiteralPath $schemaPath).Hash.Equals(
            (Get-FileHash -Algorithm SHA256 -LiteralPath $repositorySchema).Hash,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Role release schema differs from the repository trust anchor"
    }
    $verifyArguments = @{
        ManifestPath = $manifestPath
        PackageRoot = $payloadRoot
        OutputFormat = 'Json'
        AllowUnsignedTestManifest = [string]$plan.environmentKind -eq 'NON_PRODUCTION'
    }
    if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
        $verifyArguments.TrustedCertificateThumbprint = $manifestThumbprint
    }
    $null = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') @verifyArguments |
        ConvertFrom-Json
    return [pscustomobject]@{
        root = $root
        payloadRoot = $payloadRoot
        manifestPath = $manifestPath
        manifest = Get-Content -LiteralPath $manifestPath -Encoding utf8 -Raw |
            ConvertFrom-Json
    }
}

$failedRelease = Get-VerifiedReleaseManifest ([string]$plan.failedReleaseId) 'Current failed'
$targetRelease = Get-VerifiedReleaseManifest ([string]$plan.targetReleaseId) 'Rollback target'
$failedManifest = $failedRelease.manifest
$targetManifest = $targetRelease.manifest
$targetPayloadRoot = $targetRelease.payloadRoot
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and (
        [string]$failedManifest.releaseTier -cne 'PRODUCTION' -or
        [string]$targetManifest.releaseTier -cne 'PRODUCTION' -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $targetRelease.manifestPath).
            Hash.Equals(
                [string]$plan.targetManifestSha256,
                [System.StringComparison]::OrdinalIgnoreCase
            )
    )) {
    throw 'PRODUCTION rollback target tier or approved manifest digest does not match'
}
if ([string]$failedManifest.rollback.class -cne [string]$plan.rollbackClass) {
    throw 'Rollback plan class must match the verified current failed release manifest'
}
if ([string]$failedManifest.rollback.class -eq 'RECOVERY_REQUIRED') {
    throw 'RECOVERY_REQUIRED: current failed release forbids automatic application rollback'
}
$currentSchema = [int]$currentPointerObject.databaseSchemaVersion
if ([int]$failedManifest.components.database.schemaTo -ne $currentSchema) {
    throw 'Current pointer schema does not match the verified current failed release'
}
if ([string]$plan.rollbackClass -eq 'APPLICATION_ONLY' -and
        [int]$targetManifest.components.database.schemaTo -ne $currentSchema) {
    throw 'APPLICATION_ONLY rollback requires the identical current database schema'
}
if ([string]$plan.rollbackClass -eq 'FORWARD_COMPATIBLE_SCHEMA') {
    $matrix = Get-Content -LiteralPath (Join-Path $targetPayloadRoot `
            ([string]$targetManifest.compatibility.matrixPath).Replace('/', '\')) `
        -Encoding utf8 -Raw | ConvertFrom-Json
    $supported = @($matrix.combinations | Where-Object {
            [string]$_.backend -ceq [string]$targetManifest.components.backend.version -and
            [string]$_.web -ceq [string]$targetManifest.components.web.version -and
            [int]$_.appVersionCodeRange.minimum -le
                [int]$targetManifest.components.app.versionCode -and
            [int]$_.appVersionCodeRange.maximum -ge
                [int]$targetManifest.components.app.versionCode -and
            [int]$_.databaseSchema -eq $currentSchema -and
            [string]$_.status -ceq 'SUPPORTED'
        })
    if ($supported.Count -ne 1) {
        throw 'Target release is not explicitly SUPPORTED on the current forward-compatible schema'
    }
}
$configVersionsRoot = [IO.Path]::GetFullPath((Join-Path $dataRoot 'config\versions')).TrimEnd('\')
$targetRuntimeConfigRoot = [IO.Path]::GetFullPath(
    (Join-Path $configVersionsRoot ([string]$plan.targetRuntimeConfigId))
)
if (-not $targetRuntimeConfigRoot.StartsWith(
        $configVersionsRoot + '\',
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Rollback runtime configuration escapes the host-owned versions root'
}

function Assert-TargetRuntimeConfig {
    $effectivePath = Join-Path $targetRuntimeConfigRoot 'effective-config.json'
    $effective = Get-Content -LiteralPath (Resolve-Path -LiteralPath $effectivePath).Path `
        -Encoding utf8 -Raw | ConvertFrom-Json
    $databaseMatch = [regex]::Match(
        [string]$effective.database.url,
        '^jdbc:mysql://(?<host>[A-Za-z0-9.-]+):(?<port>[0-9]{1,5})/(?<database>[A-Za-z0-9_]+)(?:\?|$)'
    )
    if (-not $databaseMatch.Success) {
        throw 'Rollback target runtime configuration has no valid JDBC identity'
    }
    $configReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmRuntimeConfig.ps1') `
        -RuntimeConfigRoot $targetRuntimeConfigRoot `
        -DataRoot $dataRoot `
        -ExpectedReleaseId ([string]$plan.targetReleaseId) `
        -ExpectedConfigId ([string]$plan.targetRuntimeConfigId) `
        -ExpectedProductVersion ([string]$targetManifest.productVersion) `
        -ExpectedDatabaseSchemaVersion $currentSchema `
        -ExpectedDatabaseHost ([string]$databaseMatch.Groups['host'].Value) `
        -ExpectedDatabasePort ([int]$databaseMatch.Groups['port'].Value) `
        -ExpectedDatabaseName ([string]$databaseMatch.Groups['database'].Value) `
        -ExpectedDirectorySha256 ([string]$plan.targetRuntimeConfigSha256) `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$configReport.status -cne 'PASS') {
        throw 'Rollback target runtime configuration validation failed'
    }
}

Assert-TargetRuntimeConfig
$serviceWrapper = Join-Path $installRoot 'service\LeanTPM.Backend.exe'
$toolchain = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$toolchain.winSW.sha256 -notmatch '^[0-9a-f]{64}$' -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $serviceWrapper).Hash.Equals(
            [string]$toolchain.winSW.sha256,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Installed LeanTPM.Backend wrapper does not match the repository pin'
}
$hostStateBeforeLock = @(
    [pscustomobject]@{
        path = $currentPointer
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $currentPointer).Hash
    },
    [pscustomobject]@{
        path = $previousPointer
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $previousPointer).Hash
    },
    [pscustomobject]@{
        path = $currentConfigPointer
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $currentConfigPointer).Hash
    },
    [pscustomobject]@{
        path = $failedRelease.manifestPath
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $failedRelease.manifestPath).Hash
    },
    [pscustomobject]@{
        path = $targetRelease.manifestPath
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetRelease.manifestPath).Hash
    }
)

function Assert-LockedHostState {
    foreach ($item in $hostStateBeforeLock) {
        if (-not (Test-Path -LiteralPath $item.path -PathType Leaf) -or
                -not (Get-FileHash -Algorithm SHA256 -LiteralPath $item.path).Hash.Equals(
                    [string]$item.sha256,
                    [System.StringComparison]::OrdinalIgnoreCase
                )) {
            throw 'Release host state changed before the global deployment lock was acquired'
        }
    }
}

function Write-RollbackAudit {
    param([string]$Status, [string]$Message)

    $auditDirectory = Join-Path $dataRoot 'audit'
    $null = New-Item -ItemType Directory -Path $auditDirectory -Force
    $auditPath = Join-Path $auditDirectory 'deployments.jsonl'
    $previousHash = ('0' * 64)
    if (Test-Path -LiteralPath $auditPath -PathType Leaf) {
        $auditReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $previousHash = [string]$auditReport.finalHash
    }
    $event = [ordered]@{
        schemaVersion = 1
        timestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        rollbackId = [string]$plan.rollbackId
        approvalId = [string]$plan.approvalId
        nonce = if ($plan.PSObject.Properties['nonce']) { [string]$plan.nonce } else { $null }
        failedReleaseId = [string]$plan.failedReleaseId
        targetReleaseId = [string]$plan.targetReleaseId
        status = $Status
        actor = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        message = $Message
        previousHash = $previousHash
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($event | ConvertTo-Json -Compress))
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $event.hash = ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose() }
    Add-Content -LiteralPath $auditPath -Value ($event | ConvertTo-Json -Compress) -Encoding utf8
}

$stateDirectory = Join-Path $dataRoot 'state'
$recoveryMarker = Join-Path $stateDirectory 'recovery-inhibit.json'
$script:recoveryStateWritten = $false
$script:lastIngressIsolation = $null

function Write-RecoveryState {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('ROLLBACK_AUTHORIZED', 'RECOVERY_REQUIRED')]
        [string]$Status,
        [string]$AuthorizedReleaseId = '',
        [string]$AuthorizedPackageSha256 = ''
    )

    if (-not (Test-Path -LiteralPath $stateDirectory -PathType Container) -or
            ((Get-Item -LiteralPath $stateDirectory).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw 'Host-owned recovery state directory is missing or is a reparse point'
    }
    if ($null -eq ('LeanTpm.Release.NativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace LeanTpm.Release {
    public static class NativeMethods {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool MoveFileEx(string existingName, string newName, int flags);
    }
}
'@
    }
    $isAuthorized = $Status -eq 'ROLLBACK_AUTHORIZED'
    if ($isAuthorized -and (
            $AuthorizedReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
            $AuthorizedPackageSha256 -notmatch '^[a-f0-9]{64}$'
        )) {
        throw 'Rollback authorization requires an exact release and package digest'
    }
    $state = [ordered]@{
        schemaVersion = 1
        status = $Status
        releaseId = [string]$plan.failedReleaseId
        previousReleaseId = [string]$plan.targetReleaseId
        targetSchema = $currentSchema
        targetPackageSha256 = if ($isAuthorized) {
            $AuthorizedPackageSha256
        }
        else { [string]$failedPointerObject.packageSha256 }
        backupId = $null
        planSha256 = $loadedPlanSha256
        authorizedReleaseId = if ($isAuthorized) { $AuthorizedReleaseId } else { $null }
        authorizedPackageSha256 = if ($isAuthorized) {
            $AuthorizedPackageSha256
        }
        else { $null }
        isolationMethod = if ($null -ne $script:lastIngressIsolation) {
            [string]$script:lastIngressIsolation.isolationMethod
        }
        else { $null }
        isolatedServiceId = if ($null -ne $script:lastIngressIsolation) {
            [string]$script:lastIngressIsolation.isolatedServiceId
        }
        else { $null }
        firewallRuleGroup = if ($null -ne $script:lastIngressIsolation) {
            [string]$script:lastIngressIsolation.firewallRuleGroup
        }
        else { $null }
        proxyBindingSha256 = if ($null -ne $script:lastIngressIsolation) {
            [string]$script:lastIngressIsolation.proxyBindingSha256
        }
        else { $null }
        firewallPolicySha256 = if ($null -ne $script:lastIngressIsolation) {
            [string]$script:lastIngressIsolation.firewallPolicySha256
        }
        else { $null }
        createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($state | ConvertTo-Json -Depth 5))
    $temporaryMarker = Join-Path $stateDirectory (
        'recovery-inhibit.{0}.new' -f [Guid]::NewGuid().ToString('N')
    )
    $stream = $null
    try {
        $stream = New-Object IO.FileStream(
            $temporaryMarker,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None,
            4096,
            [IO.FileOptions]::WriteThrough
        )
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
        $stream.Dispose()
        $stream = $null
        if (-not [LeanTpm.Release.NativeMethods]::MoveFileEx(
                $temporaryMarker,
                $recoveryMarker,
                0x1 -bor 0x8
            )) {
            throw (New-Object ComponentModel.Win32Exception(
                    [Runtime.InteropServices.Marshal]::GetLastWin32Error(),
                    'Failed to durably publish rollback recovery state'
                ))
        }
        $script:recoveryStateWritten = $true
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
        if (Test-Path -LiteralPath $temporaryMarker -PathType Leaf) {
            [IO.File]::Delete($temporaryMarker)
        }
    }
}

function Remove-RecoveryState {
    if ($script:recoveryStateWritten -and
            (Test-Path -LiteralPath $recoveryMarker -PathType Leaf)) {
        [IO.File]::Delete($recoveryMarker)
    }
    $script:recoveryStateWritten = $false
}

$lockPath = Join-Path $dataRoot 'locks\deployment.lock'
$lockStream = $null
$lockAcquired = $false
$deploymentLockToken = ''
$pointerSwitched = $false
$configPointerSwitched = $false
$junctionSwitched = $false
$serviceStopped = $false
$newServiceStarted = $false
$startAttempted = $false
$mutationStarted = $false
try {
    $lockStream = New-Object System.IO.FileStream(
        $lockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::Read
    )
    $lockAcquired = $true
    $tokenBytes = New-Object byte[] 32
    $tokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $tokenGenerator.GetBytes($tokenBytes) }
    finally { $tokenGenerator.Dispose() }
    $deploymentLockToken = [BitConverter]::ToString($tokenBytes).
        Replace('-', '').ToLowerInvariant()
    $lockBytes = [Text.Encoding]::ASCII.GetBytes($deploymentLockToken)
    $lockStream.SetLength(0)
    $lockStream.Position = 0
    $lockStream.Write($lockBytes, 0, $lockBytes.Length)
    $lockStream.Flush($true)
    Assert-HostLayoutPolicyUnchanged
    Assert-LockedHostState
    Assert-TargetRuntimeConfig
    if (Test-Path -LiteralPath $recoveryMarker -PathType Leaf) {
        try {
            $markerItem = Get-Item -LiteralPath $recoveryMarker -Force -ErrorAction Stop
            if (($markerItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Recovery marker cannot be a reparse point'
            }
            $existingRecoveryState = Get-Content -LiteralPath $recoveryMarker `
                -Encoding utf8 -Raw -ErrorAction Stop | ConvertFrom-Json
        }
        catch {
            throw 'Existing recovery state is unreadable; rollback is inhibited'
        }
        if ([int]$existingRecoveryState.schemaVersion -ne 1 -or
                [string]$existingRecoveryState.releaseId -cne [string]$plan.failedReleaseId -or
                [string]$existingRecoveryState.planSha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'Existing recovery state does not match the approved failed release'
        }
        if ([string]$existingRecoveryState.status -in @(
                'MIGRATION_IN_PROGRESS', 'RECOVERY_REQUIRED'
            )) {
            throw 'Interrupted or non-compatible migration requires approved recovery reconciliation'
        }
        if ([string]$existingRecoveryState.status -notin @(
                'ACTIVATION_AUTHORIZED', 'ROLLBACK_AUTHORIZED'
            ) -or
                [string]$existingRecoveryState.authorizedReleaseId -cne
                    [string]$currentPointerObject.releaseId -or
                [string]$existingRecoveryState.authorizedPackageSha256 -cne
                    [string]$currentPointerObject.packageSha256) {
            throw 'Existing recovery state does not authorize the current exact release'
        }
    }
    $auditPath = Join-Path $dataRoot 'audit\deployments.jsonl'
    if ([string]$plan.environmentKind -eq 'PRODUCTION' -and
            (Test-Path -LiteralPath $auditPath -PathType Leaf)) {
        $null = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $replayed = @(Get-Content -LiteralPath $auditPath -Encoding utf8 | ForEach-Object {
                $_ | ConvertFrom-Json
            } | Where-Object {
                [string]$_.nonce -ceq [string]$plan.nonce -or
                [string]$_.approvalId -ceq [string]$plan.approvalId
            })
        if ($replayed.Count -gt 0) { throw 'PRODUCTION rollback approval nonce was already consumed' }
    }
    if ($PSCmdlet.ShouldProcess([string]$plan.environmentName, "Rollback to $($plan.targetReleaseId)")) {
        Write-RollbackAudit 'ROLLBACK_PREFLIGHTED' 'Target release, compatibility and approval validated.'
        $mutationStarted = $true
        $null = Invoke-BackendService Stop
        $serviceStopped = $true
        Assert-TargetRuntimeConfig
        Write-RecoveryState 'ROLLBACK_AUTHORIZED' `
            -AuthorizedReleaseId ([string]$plan.targetReleaseId) `
            -AuthorizedPackageSha256 ([string]$targetPointerObject.packageSha256)
        & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
            -InstallRoot $installRoot -DataRoot $dataRoot `
            -TargetReleaseId ([string]$plan.targetReleaseId) `
            -DeploymentLockToken $deploymentLockToken `
            -ExpectedHostLayoutSha256 $layoutSha256 `
            -ExpectedManifestSha256 ([string]$plan.targetManifestSha256) `
            -AllowNonProductionRoot:($customRoots -and
                [string]$plan.environmentKind -eq 'NON_PRODUCTION') | Out-Null
        $junctionSwitched = $true
        $targetConfigPointerContent = [ordered]@{
            schemaVersion = 1
            releaseId = [string]$plan.targetReleaseId
            configId = [string]$plan.targetRuntimeConfigId
            directorySha256 = [string]$plan.targetRuntimeConfigSha256
            activatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            approvalId = [string]$plan.approvalId
        } | ConvertTo-Json
        $configPointerTemp = Join-Path $pointersRoot 'current-config.rollback'
        [IO.File]::WriteAllText($configPointerTemp, $targetConfigPointerContent)
        [IO.File]::Replace(
            $configPointerTemp,
            $currentConfigPointer,
            $previousConfigPointer,
            $true
        )
        $configPointerSwitched = $true
        $pointerTemp = Join-Path $pointersRoot 'current-release.rollback'
        [System.IO.File]::WriteAllText($pointerTemp, $targetPointerContent)
        [System.IO.File]::Replace($pointerTemp, $currentPointer, $previousPointer, $true)
        $pointerSwitched = $true
        $startAttempted = $true
        $null = Invoke-BackendService Start
        $newServiceStarted = $true
        Wait-ReleaseReadiness `
            -ExpectedVersion ([string]$targetManifest.productVersion) `
            -ExpectedSchema $currentSchema
        Write-RollbackAudit 'ROLLED_BACK' 'Target release identity and readiness verified.'
        Remove-RecoveryState
        $report.status = 'ROLLED_BACK'
    }
}
catch {
    $failure = $_
    $compensationErrors = [System.Collections.Generic.List[string]]::new()
    if ($mutationStarted) {
        $targetStopped = $true
        if ($startAttempted) {
            $targetStopped = Invoke-CompensationStep 'STOP_ROLLBACK_TARGET' {
                Invoke-BackendService Stop
            } $compensationErrors
            try {
                $failClosedStop = Invoke-FailClosedBackendStop
                if ([string]$failClosedStop.status -ceq 'STOPPED') {
                    $targetStopped = $true
                }
                elseif ([string]$failClosedStop.status -ceq 'PROXY_ISOLATED') {
                    $targetStopped = $false
                    $script:lastIngressIsolation = $failClosedStop
                    Write-RollbackAudit 'COMPENSATION_PROXY_ISOLATED_CRITICAL' `
                        'Rollback target could not be stopped; fixed public ingress was isolated.'
                }
                else {
                    throw "Unexpected fail-closed result: $($failClosedStop.status)"
                }
            }
            catch {
                $targetStopped = $false
                $compensationErrors.Add(
                    "FAIL_CLOSED_STOP_ROLLBACK_TARGET: $($_.Exception.Message)"
                )
            }
        }
        if ($targetStopped -and $pointerSwitched) {
            $null = Invoke-CompensationStep 'RESTORE_FAILED_POINTER' {
                $restoreTemp = Join-Path $pointersRoot 'current-release.restore-failed-rollback'
                [System.IO.File]::WriteAllText($restoreTemp, $currentPointerContent)
                [System.IO.File]::Replace($restoreTemp, $currentPointer, $previousPointer, $true)
            } $compensationErrors
        }
        if ($targetStopped -and $configPointerSwitched) {
            $null = Invoke-CompensationStep 'RESTORE_FAILED_CONFIG_POINTER' {
                $configRestoreTemp = Join-Path $pointersRoot `
                    'current-config.restore-failed-rollback'
                [IO.File]::WriteAllText(
                    $configRestoreTemp,
                    $currentConfigPointerContent
                )
                [IO.File]::Replace(
                    $configRestoreTemp,
                    $currentConfigPointer,
                    $null,
                    $true
                )
            } $compensationErrors
        }
        if ($targetStopped -and $junctionSwitched) {
            $null = Invoke-CompensationStep 'RESTORE_FAILED_WEB_JUNCTION' {
                & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
                    -InstallRoot $installRoot -DataRoot $dataRoot `
                    -TargetReleaseId ([string]$plan.failedReleaseId) `
                    -DeploymentLockToken $deploymentLockToken `
                    -ExpectedHostLayoutSha256 $layoutSha256 `
                    -ExpectedManifestSha256 ((Get-FileHash -Algorithm SHA256 `
                            -LiteralPath ([string]$failedRelease.manifestPath)).Hash.ToLowerInvariant()) `
                    -AllowNonProductionRoot:($customRoots -and
                        [string]$plan.environmentKind -eq 'NON_PRODUCTION')
            } $compensationErrors
        }
        $null = Invoke-CompensationStep 'PERSIST_RECOVERY_REQUIRED' {
            Write-RecoveryState 'RECOVERY_REQUIRED'
        } $compensationErrors
        $null = Invoke-CompensationStep 'AUDIT_ROLLBACK_FAILED' {
            Write-RollbackAudit 'ROLLBACK_FAILED' (
                $failure.Exception.Message + '; recovery required; ' +
                ($compensationErrors -join '; ')
            )
        } $compensationErrors
    }
    if ($compensationErrors.Count -gt 0) {
        throw ("{0}; compensation: {1}" -f
            $failure.Exception.Message, ($compensationErrors -join '; '))
    }
    throw $failure
}
finally {
    if ($null -ne $lockStream) { $lockStream.Dispose() }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
