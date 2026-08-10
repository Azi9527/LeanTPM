[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [switch]$PlanOnly,
    [switch]$AllowUnsignedTestManifest,
    [switch]$ConfirmRecovery,
    [switch]$AllowNonProductionCustomRoots,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedPlan = (Resolve-Path -LiteralPath $PlanPath).Path
$planBytes = [IO.File]::ReadAllBytes($resolvedPlan)
$plan = (New-Object Text.UTF8Encoding($false, $true)).GetString($planBytes) | ConvertFrom-Json
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $loadedPlanSha256 = [BitConverter]::ToString($sha.ComputeHash($planBytes)).Replace('-', '').
        ToLowerInvariant()
}
finally { $sha.Dispose() }
foreach ($field in @(
        'schemaVersion', 'action', 'environmentName', 'environmentKind', 'approvalId',
        'targetReleaseId', 'targetPackageSha256', 'targetManifestSha256',
        'expectedRecoveryStateSha256', 'installRoot', 'dataRoot', 'serviceId',
        'healthUri', 'runtimeConfigId', 'runtimeConfigSha256'
    )) {
    if ($null -eq $plan.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
        throw "Recovery plan is missing $field"
    }
}
if ([int]$plan.schemaVersion -ne 1 -or [string]$plan.action -cne 'COMPLETE_FORWARD' -or
        [string]$plan.environmentKind -notin @('NON_PRODUCTION', 'PRODUCTION')) {
    throw 'Only the COMPLETE_FORWARD recovery action is supported'
}
if ([string]$plan.targetReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.targetPackageSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.targetManifestSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.expectedRecoveryStateSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.runtimeConfigId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.runtimeConfigSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
        [string]$plan.serviceId -cne 'LeanTPM.Backend') {
    throw 'Recovery plan identities or digests are invalid'
}
$healthUri = [Uri]([string]$plan.healthUri)
if ($healthUri.Scheme -ne 'http' -or $healthUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or
        -not $healthUri.AbsolutePath.EndsWith('/actuator/health/readiness')) {
    throw 'Recovery healthUri must be the loopback readiness endpoint'
}
$installRoot = (Resolve-Path -LiteralPath ([string]$plan.installRoot)).Path.TrimEnd('\', '/')
$dataRoot = (Resolve-Path -LiteralPath ([string]$plan.dataRoot)).Path.TrimEnd('\', '/')
$pointersRoot = Join-Path $dataRoot 'pointers'
$currentReleasePointer = Join-Path $pointersRoot 'current-release.json'
$currentConfigPointer = Join-Path $pointersRoot 'current-config.json'
$stateRoot = Join-Path $dataRoot 'state'
$recoveryMarker = Join-Path $stateRoot 'recovery-inhibit.json'
$auditPath = Join-Path $dataRoot 'audit\deployments.jsonl'
$lockPath = Join-Path $dataRoot 'locks\deployment.lock'
$containmentRecovery = $false
$entryMarker = $null
if (Test-Path -LiteralPath $recoveryMarker -PathType Leaf) {
    if (((Get-Item -LiteralPath $recoveryMarker -Force).Attributes -band
            [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Recovery marker must not be a reparse point'
    }
    $entryMarkerBytes = [IO.File]::ReadAllBytes($recoveryMarker)
    $entryHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $entryMarkerSha256 = [BitConverter]::ToString(
            $entryHasher.ComputeHash($entryMarkerBytes)
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $entryHasher.Dispose() }
    if ($entryMarkerSha256 -cne [string]$plan.expectedRecoveryStateSha256) {
        throw 'Recovery marker bytes differ from the approved entry digest'
    }
    $entryMarker = (New-Object Text.UTF8Encoding($false, $true)).
        GetString($entryMarkerBytes) | ConvertFrom-Json
    $containmentRecovery = [string]$entryMarker.isolationMethod -in @(
        'SERVICE_STOP', 'HOST_FIREWALL'
    )
}
$rootPolicy = & (Join-Path $PSScriptRoot '..\deploy\windows\Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $installRoot -DataRoot $dataRoot `
    -EnvironmentKind ([string]$plan.environmentKind) -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionCustomRoots `
    -ContainmentOnly:$containmentRecovery `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
$customRoots = -not $isProductionRootPair
if ($isProductionRootPair -and [string]$plan.environmentKind -ne 'PRODUCTION') {
    throw 'The production root pair requires environmentKind=PRODUCTION'
}
if ($isProductionRootPair -and $AllowNonProductionCustomRoots) {
    throw 'AllowNonProductionCustomRoots cannot be used with the production root pair'
}
function Assert-HostLayoutPolicyUnchanged {
    $lockedPolicy = & (Join-Path $PSScriptRoot `
        '..\deploy\windows\Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -EnvironmentKind ([string]$plan.environmentKind) `
        -AllowNonProductionCustomRoots:$AllowNonProductionCustomRoots `
        -ContainmentOnly:$containmentRecovery `
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
        throw 'Host layout policy changed after plan validation and before recovery side effects'
    }
}

$trustPath = Join-Path $dataRoot 'config\release-trust.json'
$manifestThumbprint = ''
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    foreach ($layoutField in @(
            'hostLayoutSha256', 'environmentId', 'hostId', 'volumeIdentity',
            'proxyBindingSha256'
        )) {
        if ($null -eq $plan.PSObject.Properties[$layoutField] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$layoutField)) {
            throw "PRODUCTION recovery plan must bind $layoutField"
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
        throw 'PRODUCTION recovery plan does not match the verified production root pair layout'
    }
}

if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    if (-not (Test-Path -LiteralPath $trustPath -PathType Leaf)) {
        throw 'PRODUCTION recovery requires host-owned release trust configuration'
    }
    $trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
    $manifestThumbprint = [string]$trust.manifestCertificateThumbprint
    foreach ($field in @(
            'nonce', 'expiresAtUtc', 'environmentId', 'hostId',
            'hostLayoutSha256', 'volumeIdentity', 'proxyBindingSha256',
            'requestedBy', 'approvedBy',
            'requesterSignaturePath', 'approverSignaturePath'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION recovery plan must bind $field"
        }
    }
    $expiry = [DateTimeOffset]::MinValue
    if ($manifestThumbprint -notmatch '^[A-Fa-f0-9]{40,128}$' -or
            [string]$plan.environmentId -cne [string]$trust.environmentId -or
            [string]$plan.hostId -cne [string]$trust.hostId -or
            [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
            -not [DateTimeOffset]::TryParse([string]$plan.expiresAtUtc, [ref]$expiry) -or
            -not ([string]$plan.expiresAtUtc).EndsWith('Z') -or
            $expiry -le [DateTimeOffset]::UtcNow -or
            $expiry -gt [DateTimeOffset]::UtcNow.AddHours(24)) {
        throw 'PRODUCTION recovery host, nonce or expiry binding is invalid'
    }
}

$releaseRoot = [IO.Path]::GetFullPath(
    (Join-Path $installRoot ("releases\{0}" -f [string]$plan.targetReleaseId))
)
$manifestPath = Join-Path $releaseRoot 'release-manifest.json'
$payloadRoot = Join-Path $releaseRoot 'payload'
$configRoot = [IO.Path]::GetFullPath(
    (Join-Path $dataRoot ("config\versions\{0}" -f [string]$plan.runtimeConfigId))
)
$manifestArguments = @{
    ManifestPath = $manifestPath
    PackageRoot = $payloadRoot
    OutputFormat = 'Json'
    AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
}
if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
    $manifestArguments.TrustedCertificateThumbprint = $manifestThumbprint
}
$manifestReport = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') @manifestArguments |
    ConvertFrom-Json
if ([string]$manifestReport.releaseId -cne [string]$plan.targetReleaseId -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash.Equals(
            [string]$plan.targetManifestSha256, [StringComparison]::OrdinalIgnoreCase
        ) -or
        ([string]$plan.environmentKind -eq 'PRODUCTION' -and (
            [string]$manifestReport.releaseTier -cne 'PRODUCTION' -or
            $AllowUnsignedTestManifest
        ))) {
    throw 'Recovery target release manifest does not match the approved immutable target'
}

$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    action = 'COMPLETE_FORWARD'
    targetReleaseId = [string]$plan.targetReleaseId
    targetPackageSha256 = [string]$plan.targetPackageSha256
    expectedRecoveryStateSha256 = [string]$plan.expectedRecoveryStateSha256
    planSha256 = $loadedPlanSha256
    hostLayoutSha256 = if ($isProductionRootPair) { $layoutSha256 } else { $null }
    proxyBindingSha256 = if ($isProductionRootPair) { $proxyBindingSha256 } else { $null }
    steps = @(
        'LOCK', 'VERIFY_RECOVERY_STATE', 'VERIFY_RELEASE_CONFIG_SCHEMA',
        'AUTHORIZE_ACTIVATION', 'SWITCH_POINTERS', 'START', 'READINESS',
        'AUDIT_RECOVERY_COMPLETED', 'CLEAR_INHIBIT'
    )
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmRecovery) {
    throw 'ConfirmRecovery is required for an executable recovery reconciliation'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    $approval = & (Join-Path $PSScriptRoot 'Test-LeanTpmReleaseApproval.ps1') `
        -PlanPath $resolvedPlan `
        -RequesterSignaturePath ([string]$plan.requesterSignaturePath) `
        -ApproverSignaturePath ([string]$plan.approverSignaturePath) `
        -TrustConfigPath $trustPath `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$approval.planSha256 -cne $loadedPlanSha256) {
        throw 'Approved recovery bytes differ from the loaded plan bytes'
    }
}
if ($null -eq $plan.PSObject.Properties['migration']) {
    throw 'Executable recovery requires a typed migration target'
}
foreach ($field in @(
        'database', 'mySqlHost', 'mySqlPort', 'mySqlUser', 'expectedServerUuid',
        'mySqlSslTrustStorePath'
    )) {
    if ($null -eq $plan.migration.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.migration.$field)) {
        throw "Recovery migration contract is missing $field"
    }
}
if ([string]$plan.migration.expectedServerUuid -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
        -not [IO.Path]::GetFullPath([string]$plan.migration.mySqlSslTrustStorePath).Equals(
            [IO.Path]::GetFullPath((Join-Path $dataRoot 'config\mysql-truststore.jks')),
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Recovery requires the pinned MySQL UUID and host-owned TLS trust store'
}

function Get-BytesHash {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').
            ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Write-RecoveryAudit {
    param([Parameter(Mandatory)][string]$Status, [Parameter(Mandatory)][string]$Message)
    $previousHash = ('0' * 64)
    if (Test-Path -LiteralPath $auditPath -PathType Leaf) {
        $auditReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $previousHash = [string]$auditReport.finalHash
    }
    $event = [ordered]@{
        schemaVersion = 1
        timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
        correlationId = [string]$plan.approvalId
        nonce = if ($plan.PSObject.Properties['nonce']) { [string]$plan.nonce } else { $null }
        environmentName = [string]$plan.environmentName
        releaseId = [string]$plan.targetReleaseId
        packageSha256 = [string]$plan.targetPackageSha256
        runtimeConfigSha256 = [string]$plan.runtimeConfigSha256
        planSha256 = $loadedPlanSha256
        backupId = if ($recoveryState.PSObject.Properties['backupId']) {
            [string]$recoveryState.backupId
        }
        else { $null }
        status = $Status
        actor = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        message = $Message
        previousHash = $previousHash
    }
    $event.hash = Get-BytesHash -Bytes ([Text.Encoding]::UTF8.GetBytes(
            ($event | ConvertTo-Json -Compress)
        ))
    Add-Content -LiteralPath $auditPath -Value ($event | ConvertTo-Json -Compress) -Encoding utf8
}

function Write-RecoveryState {
    param([Parameter(Mandatory)][ValidateSet('ACTIVATION_AUTHORIZED', 'RECOVERY_REQUIRED')]
        [string]$Status)
    if ($null -eq ('LeanTpm.Recovery.NativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace LeanTpm.Recovery {
    public static class NativeMethods {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool MoveFileEx(string existingName, string newName, int flags);
    }
}
'@
    }
    $authorized = $Status -eq 'ACTIVATION_AUTHORIZED'
    $state = [ordered]@{
        schemaVersion = 1
        status = $Status
        releaseId = [string]$plan.targetReleaseId
        previousReleaseId = if ($null -ne $currentReleaseBefore) {
            [string]$currentReleaseBefore.releaseId
        }
        else { $null }
        targetSchema = [int]$manifestReport.databaseSchemaVersion
        targetPackageSha256 = [string]$plan.targetPackageSha256
        database = [string]$recoveryState.database
        mySqlHost = [string]$recoveryState.mySqlHost
        mySqlPort = [int]$recoveryState.mySqlPort
        expectedServerUuid = [string]$recoveryState.expectedServerUuid
        runtimeConfigSha256 = [string]$recoveryState.runtimeConfigSha256
        backupId = if ($recoveryState.PSObject.Properties['backupId']) {
            [string]$recoveryState.backupId
        }
        else { $null }
        planSha256 = [string]$recoveryState.planSha256
        recoveryPlanSha256 = $loadedPlanSha256
        authorizedReleaseId = if ($authorized) { [string]$plan.targetReleaseId } else { $null }
        authorizedPackageSha256 = if ($authorized) {
            [string]$plan.targetPackageSha256
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
        createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    }
    $temporary = Join-Path $stateRoot (
        'recovery-inhibit.{0}.new' -f [Guid]::NewGuid().ToString('N')
    )
    $bytes = [Text.Encoding]::UTF8.GetBytes(($state | ConvertTo-Json -Depth 5))
    $stream = $null
    try {
        $stream = New-Object IO.FileStream(
            $temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
            [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough
        )
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
        $stream.Dispose()
        $stream = $null
        if (-not [LeanTpm.Recovery.NativeMethods]::MoveFileEx(
                $temporary, $recoveryMarker, 0x1 -bor 0x8
            )) {
            throw 'Failed to durably publish recovery reconciliation state'
        }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { [IO.File]::Delete($temporary) }
    }
}

function Set-Pointer {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 5))
    $temporary = "$Path.recovery-new"
    $stream = $null
    try {
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            $stream = New-Object IO.FileStream(
                $temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
                [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough
            )
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
            $stream.Dispose()
            $stream = $null
            [IO.File]::Replace($temporary, $Path, $null, $true)
        }
        else {
            $stream = New-Object IO.FileStream(
                $Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
                [IO.FileShare]::Read, 4096, [IO.FileOptions]::WriteThrough
            )
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { [IO.File]::Delete($temporary) }
    }
}

function Invoke-BackendService {
    param([Parameter(Mandatory)][ValidateSet('Start', 'Stop')][string]$Action)
    & (Join-Path $PSScriptRoot '..\deploy\windows\Invoke-LeanTpmWindowsService.ps1') `
        -Action $Action -InstallRoot $installRoot -DataRoot $dataRoot `
        -DeploymentLockToken $deploymentLockToken `
        -RecoveryContainmentOnly:$containmentRecovery `
        -AllowNonProductionRoot:($customRoots -and
            [string]$plan.environmentKind -eq 'NON_PRODUCTION') `
        -ConfirmServiceAction -Confirm:$false -OutputFormat Json | ConvertFrom-Json
}

function Assert-ServiceStoppedAfterCompensation {
    $stopFailure = $null
    try { $null = Invoke-BackendService Stop }
    catch { $stopFailure = $_ }
    $service = $null
    $serviceBinding = $null
    $queryFailure = $null
    try {
        $service = Get-Service -Name 'LeanTPM.Backend' -ErrorAction Stop
        $serviceBinding = Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
    }
    catch { $queryFailure = $_ }
    if ($null -ne $serviceBinding -and $service.Status -eq 'Stopped' -and
            [string]$serviceBinding.State -ceq 'Stopped' -and
            [uint32]$serviceBinding.ProcessId -eq 0) {
        if ($null -ne $stopFailure) {
            Write-RecoveryAudit 'COMPENSATION_STOP_RECONCILED' `
                "The stop command failed but SCM independently reports Stopped: $($stopFailure.Exception.Message)"
        }
    }
    $failClosed = & (Join-Path $PSScriptRoot `
            '..\deploy\windows\Stop-LeanTpmBackendFailClosed.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -DeploymentLockToken $deploymentLockToken `
        -BackendPort $healthUri.Port `
        -AllowNonProductionRoot:($customRoots -and
            [string]$plan.environmentKind -eq 'NON_PRODUCTION') `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$failClosed.status -ceq 'PROXY_ISOLATED') {
        $script:lastIngressIsolation = $failClosed
        Write-RecoveryAudit 'COMPENSATION_PROXY_ISOLATED_CRITICAL' `
            'Backend stop could not be proved; the fixed public proxy was stopped and verified.'
        return
    }
    if ([string]$failClosed.status -cne 'STOPPED') {
        $detail = if ($null -ne $queryFailure) {
            $queryFailure.Exception.Message
        }
        elseif ($null -ne $stopFailure) { $stopFailure.Exception.Message }
        else { 'SCM is not stopped' }
        throw "RECOVERY_COMPENSATION_FAILED: fail-closed stop result is ambiguous: $detail"
    }
}

function Wait-ReconciledReadiness {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 5
            $infoUri = [Uri]("{0}://{1}:{2}/actuator/info" -f
                $healthUri.Scheme, $healthUri.Host, $healthUri.Port)
            $info = Invoke-RestMethod -Uri $infoUri -TimeoutSec 5
            if ([string]$health.status -eq 'UP' -and
                    [string]$info.app.version -ceq [string]$manifestReport.productVersion -and
                    [int]$info.app.'database-schema-version' -eq
                        [int]$manifestReport.databaseSchemaVersion) { return }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    throw 'Reconciled release failed exact readiness/version/schema verification'
}

function Restore-ExternalIngressIfRequired {
    if ($null -eq $script:lastIngressIsolation) { return }
    if (-not $isProductionRootPair -or
            [string]$rootPolicy.proxy.mode -cne 'EXTERNAL_EXISTING') {
        throw 'Persisted ingress isolation requires the production EXTERNAL_EXISTING proxy mode'
    }
    $currentRecoveryStateSha256 = (Get-FileHash -Algorithm SHA256 `
        -LiteralPath $recoveryMarker).Hash.ToLowerInvariant()
    $ingress = & (Join-Path $PSScriptRoot `
            '..\deploy\windows\Restore-LeanTpmExternalIngress.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -EnvironmentKind ([string]$plan.environmentKind) `
        -RecoveryMarkerPath $recoveryMarker `
        -ExpectedRecoveryStateSha256 $currentRecoveryStateSha256 `
        -ExpectedProxyBindingSha256 $proxyBindingSha256 `
        -DeploymentLockToken $deploymentLockToken `
        -ConfirmIngressRecovery -Confirm:$false -OutputFormat Json | ConvertFrom-Json
    if ([string]$ingress.status -cne 'INGRESS_RESTORED' -or
            -not [bool]$ingress.publicHttpsVerified) {
        if ([string]$ingress.status -ceq 'INGRESS_RESTORE_FAILED' -and
                [string]$ingress.isolationMethod -in @('SERVICE_STOP', 'HOST_FIREWALL')) {
            $script:lastIngressIsolation = $ingress
        }
        throw 'External ingress was not proven restored after backend readiness'
    }
    $script:lastIngressIsolation = $null
    $script:containmentRecovery = $false
}

$lock = $null
$deploymentLockToken = ''
$startAttempted = $false
$mutationStarted = $false
$script:lastIngressIsolation = if ($containmentRecovery) { $entryMarker } else { $null }
try {
    $lock = New-Object IO.FileStream(
        $lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::Read
    )
    $token = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($token) } finally { $rng.Dispose() }
    $deploymentLockToken = [BitConverter]::ToString($token).Replace('-', '').ToLowerInvariant()
    $lockBytes = [Text.Encoding]::ASCII.GetBytes($deploymentLockToken)
    $lock.SetLength(0)
    $lock.Write($lockBytes, 0, $lockBytes.Length)
    $lock.Flush($true)
    Assert-HostLayoutPolicyUnchanged

    if (-not (Test-Path -LiteralPath $recoveryMarker -PathType Leaf)) {
        throw 'Mutable recovery state does not match the exact approved state digest'
    }
    $lockedRecoveryBytes = [IO.File]::ReadAllBytes($recoveryMarker)
    if ((Get-BytesHash -Bytes $lockedRecoveryBytes) -cne
            [string]$plan.expectedRecoveryStateSha256) {
        throw 'Mutable recovery state does not match the exact approved state digest'
    }
    $recoveryState = (New-Object Text.UTF8Encoding($false, $true)).
        GetString($lockedRecoveryBytes) | ConvertFrom-Json
        if ([int]$recoveryState.schemaVersion -ne 1 -or
            [string]$recoveryState.status -notin @(
                'MIGRATION_IN_PROGRESS', 'ACTIVATION_AUTHORIZED', 'RECOVERY_REQUIRED'
            ) -or
            [string]$recoveryState.releaseId -cne [string]$plan.targetReleaseId -or
            [string]$recoveryState.targetPackageSha256 -cne
                [string]$plan.targetPackageSha256 -or
                [int]$recoveryState.targetSchema -ne [int]$manifestReport.databaseSchemaVersion -or
                [string]$recoveryState.database -cne [string]$plan.migration.database -or
                -not ([string]$recoveryState.mySqlHost).Equals(
                    ([string]$plan.migration.mySqlHost).Trim(),
                    [StringComparison]::OrdinalIgnoreCase
                ) -or
                [int]$recoveryState.mySqlPort -ne [int]$plan.migration.mySqlPort -or
                -not ([string]$recoveryState.expectedServerUuid).Equals(
                    ([string]$plan.migration.expectedServerUuid).Trim(),
                    [StringComparison]::OrdinalIgnoreCase
                ) -or
                [string]$recoveryState.runtimeConfigSha256 -cne
                    [string]$plan.runtimeConfigSha256 -or
                ($containmentRecovery -and (
                    [string]$recoveryState.isolationMethod -notin @(
                        'SERVICE_STOP', 'HOST_FIREWALL'
                    ) -or
                    [string]$recoveryState.isolatedServiceId -cne 'caddy' -or
                    [string]$recoveryState.proxyBindingSha256 -cne $proxyBindingSha256 -or
                    [string]$recoveryState.firewallPolicySha256 -cne
                        [string]$rootPolicy.proxyBinding.firewallPolicySha256
                ))) {
            throw 'Recovery marker is not bound to the approved forward target'
    }
    $currentReleaseBefore = if (Test-Path -LiteralPath $currentReleasePointer -PathType Leaf) {
        Get-Content -LiteralPath $currentReleasePointer -Encoding utf8 -Raw | ConvertFrom-Json
    }
    else { $null }
    if (Test-Path -LiteralPath $auditPath -PathType Leaf) {
        $null = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $events = @(Get-Content -LiteralPath $auditPath -Encoding utf8 |
            ForEach-Object { $_ | ConvertFrom-Json })
        if (@($events | Where-Object {
                    [string]$_.correlationId -ceq [string]$plan.approvalId -or
                    ($null -ne $plan.PSObject.Properties['nonce'] -and
                        [string]$_.nonce -ceq [string]$plan.nonce)
                }).Count -gt 0) {
            throw 'Recovery approval nonce or approvalId was already consumed'
        }
    }
    $lockedManifestReport = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') `
        @manifestArguments | ConvertFrom-Json
    if ([string]$lockedManifestReport.releaseId -cne [string]$manifestReport.releaseId -or
            -not (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash.Equals(
                [string]$plan.targetManifestSha256,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Recovery release bytes changed before the global lock was acquired'
    }
    $runtimeConfig = & (Join-Path $PSScriptRoot 'Test-LeanTpmRuntimeConfig.ps1') `
        -RuntimeConfigRoot $configRoot -DataRoot $dataRoot `
        -ExpectedReleaseId ([string]$plan.targetReleaseId) `
        -ExpectedConfigId ([string]$plan.runtimeConfigId) `
        -ExpectedProductVersion ([string]$manifestReport.productVersion) `
        -ExpectedDatabaseSchemaVersion ([int]$manifestReport.databaseSchemaVersion) `
        -ExpectedDatabaseHost ([string]$plan.migration.mySqlHost) `
        -ExpectedDatabasePort ([int]$plan.migration.mySqlPort) `
        -ExpectedDatabaseName ([string]$plan.migration.database) `
        -ExpectedDirectorySha256 ([string]$plan.runtimeConfigSha256) `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$runtimeConfig.status -cne 'PASS') {
        throw 'Recovery runtime configuration does not match the approved target'
    }
        if (-not $PSCmdlet.ShouldProcess(
            [string]$plan.environmentName,
            'Complete forward recovery'
        )) {
            return
        }
        $mutationStarted = $true
        $null = Invoke-BackendService Stop
    $migration = & (Join-Path $PSScriptRoot 'Invoke-LeanTpmMigrator.ps1') `
        -ReleaseRoot $releaseRoot `
        -MySqlHost ([string]$plan.migration.mySqlHost) `
        -MySqlPort ([int]$plan.migration.mySqlPort) `
        -Database ([string]$plan.migration.database) `
        -MySqlUser ([string]$plan.migration.mySqlUser) `
        -ExpectedServerUuid ([string]$plan.migration.expectedServerUuid) `
        -MySqlSslTrustStorePath ([string]$plan.migration.mySqlSslTrustStorePath) `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$migration.status -cne 'PASS' -or
            [int]$migration.schemaTo -ne [int]$manifestReport.databaseSchemaVersion) {
        throw 'Recovery migrator did not prove the exact approved schemaTo'
    }
    Write-RecoveryState 'ACTIVATION_AUTHORIZED'
    & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -TargetReleaseId ([string]$plan.targetReleaseId) `
        -DeploymentLockToken $deploymentLockToken `
        -ExpectedHostLayoutSha256 $layoutSha256 `
        -ExpectedManifestSha256 ([string]$plan.targetManifestSha256) `
        -AllowNonProductionRoot:($customRoots -and
            [string]$plan.environmentKind -eq 'NON_PRODUCTION') | Out-Null
    Set-Pointer -Path $currentConfigPointer -Value ([ordered]@{
            schemaVersion = 1
            releaseId = [string]$plan.targetReleaseId
            configId = [string]$plan.runtimeConfigId
            directorySha256 = [string]$plan.runtimeConfigSha256
            activatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            approvalId = [string]$plan.approvalId
        })
    Set-Pointer -Path $currentReleasePointer -Value ([ordered]@{
            schemaVersion = 1
            releaseId = [string]$plan.targetReleaseId
            productVersion = [string]$manifestReport.productVersion
            databaseSchemaVersion = [int]$manifestReport.databaseSchemaVersion
            packageSha256 = [string]$plan.targetPackageSha256
            activatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            approvalId = [string]$plan.approvalId
        })
    $startAttempted = $true
    $null = Invoke-BackendService Start
    Wait-ReconciledReadiness
    Restore-ExternalIngressIfRequired
    Write-RecoveryAudit 'RECOVERY_COMPLETED' 'Forward recovery reached exact readiness identity.'
    [IO.File]::Delete($recoveryMarker)
    $report.status = 'RECOVERY_COMPLETED'
}
catch {
    $failure = $_
    $compensationErrors = [System.Collections.Generic.List[string]]::new()
    if ($mutationStarted) {
        if ($startAttempted) {
            try { Assert-ServiceStoppedAfterCompensation }
            catch { $compensationErrors.Add($_.Exception.Message) }
        }
        try { Write-RecoveryState 'RECOVERY_REQUIRED' }
        catch { $compensationErrors.Add("RECOVERY_STATE: $($_.Exception.Message)") }
        $failureStatus = if ($compensationErrors.Count -gt 0) {
            'RECOVERY_COMPENSATION_FAILED'
        }
        else { 'RECOVERY_RECONCILIATION_FAILED' }
        try { Write-RecoveryAudit $failureStatus $failure.Exception.Message }
        catch { $compensationErrors.Add("AUDIT: $($_.Exception.Message)") }
    }
    if ($compensationErrors.Count -gt 0) {
        throw "RECOVERY_COMPENSATION_FAILED after '$($failure.Exception.Message)': $($compensationErrors -join '; ')"
    }
    throw $failure
}
finally {
    if ($null -ne $lock) { $lock.Dispose() }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
