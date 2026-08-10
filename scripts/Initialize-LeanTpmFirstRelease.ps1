[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [switch]$PlanOnly,
    [switch]$AllowUnsignedTestManifest,
    [switch]$ConfirmFirstInstall,
    [switch]$AllowNonProductionCustomRoots,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedPlan = (Resolve-Path -LiteralPath $PlanPath).Path
$loadedPlanBytes = [IO.File]::ReadAllBytes($resolvedPlan)
$plan = (New-Object Text.UTF8Encoding($false, $true)).GetString($loadedPlanBytes) |
    ConvertFrom-Json
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $loadedPlanSha256 = [BitConverter]::ToString(
        $hasher.ComputeHash($loadedPlanBytes)
    ).Replace('-', '').ToLowerInvariant()
}
finally { $hasher.Dispose() }

foreach ($field in @(
        'schemaVersion', 'operation', 'expectedCurrentState', 'environmentName',
        'environmentKind', 'releaseId', 'approvalId', 'packagePath', 'installRoot',
        'dataRoot', 'backupRoot', 'serviceId', 'healthUri', 'runtimeConfigId',
        'runtimeConfigSha256'
    )) {
    if ($null -eq $plan.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
        throw "First-install plan is missing $field"
    }
}
if ([int]$plan.schemaVersion -ne 1 -or [string]$plan.operation -cne 'FIRST_INSTALL' -or
        [string]$plan.expectedCurrentState -cne 'UNINITIALIZED' -or
        [string]$plan.environmentKind -notin @('NON_PRODUCTION', 'PRODUCTION')) {
    throw 'First-install plan must explicitly bind FIRST_INSTALL / UNINITIALIZED'
}
if ([string]$plan.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.releaseId -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        [string]$plan.releaseId -match '\.$' -or
        [string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
        [string]$plan.runtimeConfigId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.runtimeConfigSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'First-install release, approval or runtime configuration identity is invalid'
}
if ([string]$plan.serviceId -cne 'LeanTPM.Backend') {
    throw 'First install may control only the fixed LeanTPM.Backend service'
}
$healthUri = [Uri]([string]$plan.healthUri)
if ($healthUri.Scheme -ne 'http' -or $healthUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or
        -not $healthUri.AbsolutePath.EndsWith('/actuator/health/readiness')) {
    throw 'healthUri must be the loopback readiness endpoint'
}

$installRoot = (Resolve-Path -LiteralPath ([string]$plan.installRoot)).Path.TrimEnd('\', '/')
$dataRoot = (Resolve-Path -LiteralPath ([string]$plan.dataRoot)).Path.TrimEnd('\', '/')
$backupRoot = (Resolve-Path -LiteralPath ([string]$plan.backupRoot)).Path.TrimEnd('\', '/')
$packagePath = (Resolve-Path -LiteralPath ([string]$plan.packagePath)).Path
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
            throw "PRODUCTION first-install plan must bind $layoutField"
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
        throw 'PRODUCTION first-install plan does not match the verified production root pair layout'
    }
}

function Assert-LegacyLayoutReady {
    if (-not $isProductionRootPair) { return }
    $legacyInventory = & (Join-Path $PSScriptRoot `
        '..\deploy\windows\Get-LeanTpmLegacyLayoutInventory.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot `
        -PlanOnly:$PlanOnly -OutputFormat Json | ConvertFrom-Json
    if ([string]$legacyInventory.status -ceq 'IMPORT_REQUIRED') {
        $blockedPaths = @($legacyInventory.entries | Where-Object {
                [string]$_.classification -in @('IMPORT_REQUIRED', 'UNSAFE_REPARSE')
            } | ForEach-Object { [string]$_.relativePath })
        throw ('Legacy host content requires an approved import before first install: ' +
            ($blockedPaths -join ', '))
    }
    if ([string]$legacyInventory.status -cne 'PASS') {
        throw 'Legacy host inventory returned an unsupported status'
    }
}

Assert-LegacyLayoutReady

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
        throw 'Host layout policy changed after plan validation and before first-install side effects'
    }
}
$expectedBackupRoot = [IO.Path]::GetFullPath((Join-Path $dataRoot 'backups')).TrimEnd('\')
if (-not $backupRoot.Equals($expectedBackupRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'backupRoot must be the host-owned dataRoot/backups directory'
}

$trustConfigPath = Join-Path $dataRoot 'config\release-trust.json'
$manifestThumbprint = ''
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    if (-not (Test-Path -LiteralPath $trustConfigPath -PathType Leaf)) {
        throw 'PRODUCTION first install requires host-owned release trust configuration'
    }
    $trust = Get-Content -LiteralPath $trustConfigPath -Encoding utf8 -Raw | ConvertFrom-Json
    $manifestThumbprint = [string]$trust.manifestCertificateThumbprint
    foreach ($field in @(
            'packageSha256', 'manifestSha256', 'nonce', 'expiresAtUtc',
            'environmentId', 'hostId', 'hostLayoutSha256', 'volumeIdentity',
            'proxyBindingSha256',
            'requestedBy', 'approvedBy',
            'requesterSignaturePath', 'approverSignaturePath'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION first-install plan must bind $field"
        }
    }
    $expiresAt = [DateTimeOffset]::MinValue
    if ($manifestThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$' -or
            [string]$plan.environmentId -cne [string]$trust.environmentId -or
            [string]$plan.hostId -cne [string]$trust.hostId -or
            [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
            -not [DateTimeOffset]::TryParse([string]$plan.expiresAtUtc, [ref]$expiresAt) -or
            -not ([string]$plan.expiresAtUtc).EndsWith('Z') -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -gt [DateTimeOffset]::UtcNow.AddHours(24)) {
        throw 'PRODUCTION first-install trust, host, nonce or expiry binding is invalid'
    }
}
$verifyArguments = @{
    PackagePath = $packagePath
    OutputFormat = 'Json'
    AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
}
if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
    $verifyArguments.TrustedCertificateThumbprint = $manifestThumbprint
}
$packageReport = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') @verifyArguments |
    ConvertFrom-Json
if ([string]$packageReport.releaseId -cne [string]$plan.releaseId) {
    throw 'First-install releaseId differs from the verified package'
}
if ([int]$packageReport.databaseSchemaFrom -ne 0) {
    throw 'FIRST_INSTALL requires a complete migration catalog with schemaFrom equal to 0'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and (
        [string]$packageReport.releaseTier -cne 'PRODUCTION' -or
        [string]$packageReport.sha256 -cne [string]$plan.packageSha256 -or
        [string]$packageReport.manifestSha256 -cne [string]$plan.manifestSha256 -or
        $AllowUnsignedTestManifest
    )) {
    throw 'PRODUCTION first-install approval must bind the exact signed PRODUCTION package'
}

$steps = @(
    'LOCK', 'PROVE_UNINITIALIZED', 'PREFLIGHT', 'VERIFY_PACKAGE', 'STAGE',
    'INHIBIT_START', 'MIGRATE_EMPTY_SCHEMA', 'SWITCH_INITIAL_POINTERS',
    'START_SERVICE', 'VERIFY_READINESS', 'AUDIT'
)
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    operation = 'FIRST_INSTALL'
    expectedCurrentState = 'UNINITIALIZED'
    releaseId = [string]$plan.releaseId
    packageSha256 = [string]$packageReport.sha256
    hostLayoutSha256 = if ($isProductionRootPair) { $layoutSha256 } else { $null }
    proxyBindingSha256 = if ($isProductionRootPair) { $proxyBindingSha256 } else { $null }
    planSha256 = $loadedPlanSha256
    steps = $steps
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmFirstInstall) {
    throw 'ConfirmFirstInstall is required before the initial database migration and activation'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    $approval = & (Join-Path $PSScriptRoot 'Test-LeanTpmReleaseApproval.ps1') `
        -PlanPath $resolvedPlan `
        -RequesterSignaturePath ([string]$plan.requesterSignaturePath) `
        -ApproverSignaturePath ([string]$plan.approverSignaturePath) `
        -TrustConfigPath $trustConfigPath `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$approval.planSha256 -cne $loadedPlanSha256) {
        throw 'Approved first-install bytes differ from the loaded plan bytes'
    }
}
if ($null -eq $plan.PSObject.Properties['migration'] -or
        $null -eq $plan.PSObject.Properties['capacity']) {
    throw 'Executable first install requires typed migration and capacity contracts'
}
foreach ($field in @(
        'database', 'mySqlHost', 'mySqlPort', 'mySqlUser', 'expectedServerUuid',
        'mySqlSslTrustStorePath'
    )) {
    if ($null -eq $plan.migration.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.migration.$field)) {
        throw "First-install migration contract is missing $field"
    }
}
foreach ($field in @('expectedDatabaseBytes', 'expectedAttachmentBytes', 'minimumFreeBytes')) {
    if ($null -eq $plan.capacity.PSObject.Properties[$field]) {
        throw "First-install capacity contract is missing $field"
    }
}
if ([string]$plan.migration.expectedServerUuid -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
        [int64]$plan.capacity.expectedDatabaseBytes -lt 0 -or
        [int64]$plan.capacity.expectedAttachmentBytes -lt 0 -or
        [int64]$plan.capacity.minimumFreeBytes -lt 1073741824) {
    throw 'First-install migration target or capacity values are invalid'
}
$expectedTrustStore = [IO.Path]::GetFullPath(
    (Join-Path $dataRoot 'config\mysql-truststore.jks')
)
if (-not [IO.Path]::GetFullPath([string]$plan.migration.mySqlSslTrustStorePath).Equals(
        $expectedTrustStore, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'First-install MySQL trust store must be the host-owned fixed path'
}

$toolchain = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$serviceWrapper = Join-Path $installRoot 'service\LeanTPM.Backend.exe'
if ([string]$toolchain.winSW.sha256 -notmatch '^[a-f0-9]{64}$' -or
        -not (Test-Path -LiteralPath $serviceWrapper -PathType Leaf) -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $serviceWrapper).Hash.Equals(
            [string]$toolchain.winSW.sha256, [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Installed LeanTPM.Backend wrapper does not match the repository pin'
}
$serviceBinding = Get-CimInstance -ClassName Win32_Service `
    -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
if ($null -eq $serviceBinding -or
        -not ([string]$serviceBinding.PathName).Trim().Trim('"').Equals(
            $serviceWrapper, [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'LeanTPM.Backend is not bound to the fixed host-owned wrapper'
}
$service = Get-Service -Name 'LeanTPM.Backend' -ErrorAction Stop
if ($service.Status -ne 'Stopped') {
    throw 'First-install service must already be Stopped before initialization'
}

$pointersRoot = Join-Path $dataRoot 'pointers'
$currentReleasePointer = Join-Path $pointersRoot 'current-release.json'
$currentConfigPointer = Join-Path $pointersRoot 'current-config.json'
$stateDirectory = Join-Path $dataRoot 'state'
$recoveryMarker = Join-Path $stateDirectory 'recovery-inhibit.json'
$script:lastIngressIsolation = $null
$configRoot = [IO.Path]::GetFullPath(
    (Join-Path $dataRoot ("config\versions\{0}" -f [string]$plan.runtimeConfigId))
)
$stageRoot = Join-Path $dataRoot ("staging\{0}" -f [string]$plan.releaseId)
$releaseRoot = Join-Path $installRoot ("releases\{0}" -f [string]$plan.releaseId)
$currentWeb = Join-Path $installRoot 'current'
$lockPath = Join-Path $dataRoot 'locks\deployment.lock'
$auditPath = Join-Path $dataRoot 'audit\deployments.jsonl'

function Get-Hash {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').
            ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Write-AuditEvent {
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
        releaseId = [string]$plan.releaseId
        packageSha256 = [string]$packageReport.sha256
        runtimeConfigSha256 = [string]$plan.runtimeConfigSha256
        planSha256 = $loadedPlanSha256
        backupId = $null
        status = $Status
        actor = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        message = $Message
        previousHash = $previousHash
    }
    $event.hash = Get-Hash -Bytes ([Text.Encoding]::UTF8.GetBytes(
            ($event | ConvertTo-Json -Compress)
        ))
    Add-Content -LiteralPath $auditPath -Value ($event | ConvertTo-Json -Compress) -Encoding utf8
}

function Write-RecoveryState {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('MIGRATION_IN_PROGRESS', 'ACTIVATION_AUTHORIZED', 'RECOVERY_REQUIRED')]
        [string]$Status
    )
    if (-not (Test-Path -LiteralPath $stateDirectory -PathType Container) -or
            ((Get-Item -LiteralPath $stateDirectory).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw 'Host-owned recovery state directory is missing or unsafe'
    }
    if ($null -eq ('LeanTpm.FirstInstall.NativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace LeanTpm.FirstInstall {
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
        releaseId = [string]$plan.releaseId
        previousReleaseId = $null
        targetSchema = [int]$packageReport.databaseSchemaVersion
        targetPackageSha256 = [string]$packageReport.sha256
        database = [string]$plan.migration.database
        mySqlHost = ([string]$plan.migration.mySqlHost).Trim().ToLowerInvariant()
        mySqlPort = [int]$plan.migration.mySqlPort
        expectedServerUuid = ([string]$plan.migration.expectedServerUuid).Trim().ToLowerInvariant()
        runtimeConfigSha256 = [string]$plan.runtimeConfigSha256
        backupId = $null
        planSha256 = $loadedPlanSha256
        authorizedReleaseId = if ($authorized) { [string]$plan.releaseId } else { $null }
        authorizedPackageSha256 = if ($authorized) { [string]$packageReport.sha256 } else { $null }
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
    $temporary = Join-Path $stateDirectory (
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
        if (-not [LeanTpm.FirstInstall.NativeMethods]::MoveFileEx(
                $temporary, $recoveryMarker, 0x1 -bor 0x8
            )) {
            throw (New-Object ComponentModel.Win32Exception(
                    [Runtime.InteropServices.Marshal]::GetLastWin32Error(),
                    'Failed to durably publish first-install recovery state'
                ))
        }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { [IO.File]::Delete($temporary) }
    }
}

function Write-NewPointer {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 5))
    $stream = $null
    try {
        $stream = New-Object IO.FileStream(
            $Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
            [IO.FileShare]::Read, 4096, [IO.FileOptions]::WriteThrough
        )
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Invoke-BackendService {
    param([Parameter(Mandatory)][ValidateSet('Start', 'Stop')][string]$Action)
    & (Join-Path $PSScriptRoot '..\deploy\windows\Invoke-LeanTpmWindowsService.ps1') `
        -Action $Action -InstallRoot $installRoot -DataRoot $dataRoot `
        -DeploymentLockToken $deploymentLockToken `
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
            Write-AuditEvent 'COMPENSATION_STOP_RECONCILED' `
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
        Write-AuditEvent 'COMPENSATION_PROXY_ISOLATED_CRITICAL' `
            'Backend stop could not be proved; the fixed public proxy was stopped and verified.'
        return
    }
    if ([string]$failClosed.status -cne 'STOPPED') {
        $detail = if ($null -ne $queryFailure) {
            $queryFailure.Exception.Message
        }
        elseif ($null -ne $stopFailure) { $stopFailure.Exception.Message }
        else { 'SCM is not stopped' }
        throw "COMPENSATION_FAILED: fail-closed stop result is ambiguous: $detail"
    }
}

function Wait-FirstReleaseReadiness {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 5
            $infoUri = [Uri]("{0}://{1}:{2}/actuator/info" -f
                $healthUri.Scheme, $healthUri.Host, $healthUri.Port)
            $info = Invoke-RestMethod -Uri $infoUri -TimeoutSec 5
            if ([string]$health.status -eq 'UP' -and
                    [string]$info.app.version -ceq [string]$packageReport.productVersion -and
                    [int]$info.app.'database-schema-version' -eq
                        [int]$packageReport.databaseSchemaVersion) {
                return
            }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    throw 'First release did not reach its exact readiness/version/schema identity'
}

$lockStream = $null
$deploymentLockToken = ''
$migrationStarted = $false
$startAttempted = $false
try {
    $lockStream = New-Object IO.FileStream(
        $lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::Read
    )
    $token = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($token) } finally { $rng.Dispose() }
    $deploymentLockToken = [BitConverter]::ToString($token).Replace('-', '').ToLowerInvariant()
    $lockBytes = [Text.Encoding]::ASCII.GetBytes($deploymentLockToken)
    $lockStream.SetLength(0)
    $lockStream.Write($lockBytes, 0, $lockBytes.Length)
    $lockStream.Flush($true)
    Assert-HostLayoutPolicyUnchanged
    Assert-LegacyLayoutReady

    foreach ($path in @(
            $currentReleasePointer, $currentConfigPointer,
            (Join-Path $pointersRoot 'previous-release.json'),
            (Join-Path $pointersRoot 'previous-config.json'),
            $currentWeb, $stageRoot, $releaseRoot, $recoveryMarker
        )) {
        if (Test-Path -LiteralPath $path) {
            throw "UNINITIALIZED host proof failed because state already exists: $path"
        }
    }
    if (@(Get-ChildItem -LiteralPath (Join-Path $dataRoot 'data\uploads') -Force `
                -ErrorAction Stop).Count -ne 0) {
        throw 'UNINITIALIZED host proof requires an empty attachment root'
    }
    if ((Get-Service -Name 'LeanTPM.Backend' -ErrorAction Stop).Status -ne 'Stopped') {
        throw 'First-install service changed state before the global lock was acquired'
    }
    if (Test-Path -LiteralPath $auditPath -PathType Leaf) {
        $null = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $auditEntries = @(Get-Content -LiteralPath $auditPath -Encoding utf8 |
            ForEach-Object { $_ | ConvertFrom-Json })
        if (@($auditEntries | Where-Object {
                    [string]$_.nonce -ceq [string]$plan.nonce -or
                    [string]$_.correlationId -ceq [string]$plan.approvalId
                }).Count -gt 0) {
            throw 'First-install approval nonce or approvalId was already consumed'
        }
    }
    $runtimeConfigReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmRuntimeConfig.ps1') `
        -RuntimeConfigRoot $configRoot `
        -DataRoot $dataRoot `
        -ExpectedReleaseId ([string]$plan.releaseId) `
        -ExpectedConfigId ([string]$plan.runtimeConfigId) `
        -ExpectedProductVersion ([string]$packageReport.productVersion) `
        -ExpectedDatabaseSchemaVersion ([int]$packageReport.databaseSchemaVersion) `
        -ExpectedDatabaseHost ([string]$plan.migration.mySqlHost) `
        -ExpectedDatabasePort ([int]$plan.migration.mySqlPort) `
        -ExpectedDatabaseName ([string]$plan.migration.database) `
        -ExpectedDirectorySha256 ([string]$plan.runtimeConfigSha256) `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$runtimeConfigReport.status -cne 'PASS') {
        throw 'Initial immutable runtime configuration did not validate'
    }
    $preflight = & (Join-Path $PSScriptRoot 'Test-LeanTpmDeploymentPreflight.ps1') `
        -InstallRoot $installRoot -DataRoot $dataRoot -BackupRoot $backupRoot `
        -PackagePath $packagePath -HealthUri $healthUri `
        -ExpectedDatabaseBytes ([int64]$plan.capacity.expectedDatabaseBytes) `
        -ExpectedAttachmentBytes ([int64]$plan.capacity.expectedAttachmentBytes) `
        -MinimumFreeBytes ([int64]$plan.capacity.minimumFreeBytes) `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$preflight.status -cne 'PASS') { throw 'First-install resource preflight failed' }

    if ($PSCmdlet.ShouldProcess([string]$plan.environmentName, "FIRST_INSTALL $($plan.releaseId)")) {
        Write-AuditEvent 'FIRST_INSTALL_PREFLIGHTED' 'Host, service, package and empty-state proof passed.'
        $stagedArguments = @{
            PackagePath = $packagePath
            ExtractTo = $stageRoot
            OutputFormat = 'Json'
            AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
        }
        if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
            $stagedArguments.TrustedCertificateThumbprint = $manifestThumbprint
        }
        $staged = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') @stagedArguments |
            ConvertFrom-Json
        if ([string]$staged.sha256 -cne [string]$packageReport.sha256) {
            throw 'First-install package changed after preflight verification'
        }
        Move-Item -LiteralPath $stageRoot -Destination $releaseRoot
        $releaseAcl = & (Join-Path $PSScriptRoot `
                '..\deploy\windows\Protect-LeanTpmReleaseDirectory.ps1') `
            -InstallRoot $installRoot -DataRoot $dataRoot `
            -ReleaseId ([string]$plan.releaseId) -Confirm:$false `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$releaseAcl.status -cne 'PASS') {
            throw 'First release did not pass immutable ACL normalization'
        }
        $installedManifestArguments = @{
            ManifestPath = (Join-Path $releaseRoot 'release-manifest.json')
            PackageRoot = (Join-Path $releaseRoot 'payload')
            OutputFormat = 'Json'
            AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
        }
        if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
            $installedManifestArguments.TrustedCertificateThumbprint = $manifestThumbprint
        }
        $installedManifest = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') `
            @installedManifestArguments | ConvertFrom-Json
        if ([string]$installedManifest.status -cne 'PASS' -or
                [string]$installedManifest.releaseId -cne [string]$plan.releaseId -or
                [string]$installedManifest.productVersion -cne
                    [string]$packageReport.productVersion -or
                [int]$installedManifest.databaseSchemaVersion -ne
                    [int]$packageReport.databaseSchemaVersion -or
                -not (Get-FileHash -Algorithm SHA256 `
                    -LiteralPath (Join-Path $releaseRoot 'release-manifest.json')).Hash.Equals(
                        [string]$packageReport.manifestSha256,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
            throw 'First release bytes changed after ACL normalization'
        }
        Write-AuditEvent 'STAGED' `
            'Verified first release entered its byte-revalidated immutable release directory.'

        Write-RecoveryState 'MIGRATION_IN_PROGRESS'
        $migrationStarted = $true
        $migration = & (Join-Path $PSScriptRoot 'Invoke-LeanTpmMigrator.ps1') `
            -ReleaseRoot $releaseRoot `
            -MySqlHost ([string]$plan.migration.mySqlHost) `
            -MySqlPort ([int]$plan.migration.mySqlPort) `
            -Database ([string]$plan.migration.database) `
            -MySqlUser ([string]$plan.migration.mySqlUser) `
            -ExpectedServerUuid ([string]$plan.migration.expectedServerUuid) `
            -MySqlSslTrustStorePath ([string]$plan.migration.mySqlSslTrustStorePath) `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$migration.status -cne 'PASS' -or [int]$migration.schemaFrom -ne 0) {
            throw 'First-install migrator did not prove an empty schema and reach schemaTo'
        }
        Write-AuditEvent 'MIGRATED' 'Empty schema migrated through the complete reviewed catalog.'
        Write-RecoveryState 'ACTIVATION_AUTHORIZED'

        & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
            -InstallRoot $installRoot -DataRoot $dataRoot `
            -TargetReleaseId ([string]$plan.releaseId) `
            -DeploymentLockToken $deploymentLockToken `
            -ExpectedHostLayoutSha256 $layoutSha256 `
            -ExpectedManifestSha256 ([string]$plan.manifestSha256) `
            -AllowNonProductionRoot:($customRoots -and
                [string]$plan.environmentKind -eq 'NON_PRODUCTION') | Out-Null
        Write-NewPointer -Path $currentConfigPointer -Value ([ordered]@{
                schemaVersion = 1
                releaseId = [string]$plan.releaseId
                configId = [string]$plan.runtimeConfigId
                directorySha256 = [string]$plan.runtimeConfigSha256
                activatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                approvalId = [string]$plan.approvalId
            })
        Write-NewPointer -Path $currentReleasePointer -Value ([ordered]@{
                schemaVersion = 1
                releaseId = [string]$plan.releaseId
                productVersion = [string]$packageReport.productVersion
                databaseSchemaVersion = [int]$packageReport.databaseSchemaVersion
                packageSha256 = [string]$packageReport.sha256
                activatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                approvalId = [string]$plan.approvalId
            })
        Write-AuditEvent 'SWITCHED' 'Initial Web, runtime configuration and backend pointers activated.'
        $startAttempted = $true
        $null = Invoke-BackendService Start
        Wait-FirstReleaseReadiness
        Write-AuditEvent 'SUCCEEDED' 'FIRST_INSTALL completed with exact readiness identity.'
        [IO.File]::Delete($recoveryMarker)
        $report.status = 'SUCCEEDED'
    }
}
catch {
    $failure = $_
    $compensationErrors = [System.Collections.Generic.List[string]]::new()
    if ($startAttempted) {
        try { Assert-ServiceStoppedAfterCompensation }
        catch { $compensationErrors.Add($_.Exception.Message) }
    }
    if ($migrationStarted) {
        try { Write-RecoveryState 'RECOVERY_REQUIRED' }
        catch { $compensationErrors.Add("RECOVERY_STATE: $($_.Exception.Message)") }
        $failureStatus = if ($compensationErrors.Count -gt 0) {
            'COMPENSATION_FAILED'
        }
        else { 'RECOVERY_REQUIRED' }
        try { Write-AuditEvent $failureStatus $failure.Exception.Message }
        catch { $compensationErrors.Add("AUDIT: $($_.Exception.Message)") }
    }
    else {
        foreach ($ownedPath in @($stageRoot, $releaseRoot)) {
            if (Test-Path -LiteralPath $ownedPath) {
                Remove-Item -LiteralPath $ownedPath -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        try { Write-AuditEvent 'FAILED' $failure.Exception.Message }
        catch { $compensationErrors.Add("AUDIT: $($_.Exception.Message)") }
    }
    if ($compensationErrors.Count -gt 0) {
        throw "COMPENSATION_FAILED after first install error '$($failure.Exception.Message)': $($compensationErrors -join '; ')"
    }
    throw $failure
}
finally {
    if ($null -ne $lockStream) { $lockStream.Dispose() }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
