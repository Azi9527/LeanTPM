[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [switch]$PlanOnly,
    [switch]$AllowUnsignedTestManifest,
    [switch]$ConfirmDeployment,
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
$required = @(
    'schemaVersion', 'environmentName', 'environmentKind', 'releaseId', 'approvalId',
    'packagePath', 'installRoot', 'dataRoot', 'backupRoot', 'serviceId', 'healthUri',
    'runtimeConfigId', 'runtimeConfigSha256'
)
foreach ($name in $required) {
    if ($null -eq $plan.PSObject.Properties[$name]) { throw "Deployment plan is missing '$name'" }
}
if ([int]$plan.schemaVersion -ne 1 -or
        [string]$plan.environmentKind -notin @('NON_PRODUCTION', 'PRODUCTION')) {
    throw 'Unsupported deployment plan schemaVersion or environmentKind'
}
if ([string]$plan.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.releaseId -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        [string]$plan.releaseId -match '\.$' -or
        [string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$') {
    throw 'releaseId or approvalId is invalid for Windows'
}
if ([string]$plan.runtimeConfigId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.runtimeConfigSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Deployment plan must bind the immutable runtime configuration digest'
}
if ([string]$plan.serviceId -cne 'LeanTPM.Backend') {
    throw 'Deployment may control only the fixed LeanTPM.Backend service'
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
            throw "PRODUCTION deployment plan must bind $layoutField"
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
        throw 'PRODUCTION deployment plan does not match the verified production root pair layout'
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
        throw 'Host layout policy changed after plan validation and before deployment side effects'
    }
}
$expectedBackupRoot = [System.IO.Path]::GetFullPath((Join-Path $dataRoot 'backups')).TrimEnd('\')
if (-not $backupRoot.Equals($expectedBackupRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'backupRoot must be the host-owned dataRoot/backups directory'
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

$trustConfigPath = Join-Path $dataRoot 'config\release-trust.json'
$manifestThumbprint = ''
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    if (-not (Test-Path -LiteralPath $trustConfigPath -PathType Leaf)) {
        throw 'PRODUCTION requires the host-owned release trust configuration'
    }
    $trust = Get-Content -LiteralPath $trustConfigPath -Encoding utf8 -Raw | ConvertFrom-Json
    $manifestThumbprint = [string]$trust.manifestCertificateThumbprint
    if ($manifestThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
        throw 'Host release trust configuration has no pinned manifest signer'
    }
        foreach ($field in @(
                'packageSha256', 'manifestSha256', 'nonce', 'expiresAtUtc',
                'expectedCurrentReleaseId', 'expectedCurrentPackageSha256',
                'expectedCurrentRuntimeConfigSha256',
                'environmentId', 'hostId', 'hostLayoutSha256', 'volumeIdentity',
                'proxyBindingSha256'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION deployment plan must bind $field"
        }
    }
    if ([string]$trust.environmentId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$trust.hostId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.environmentId -cne [string]$trust.environmentId -or
            [string]$plan.hostId -cne [string]$trust.hostId) {
        throw 'PRODUCTION deployment approval is not bound to this environment and host'
    }
    $expiresAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$plan.expiresAtUtc, [ref]$expiresAt) -or
            -not ([string]$plan.expiresAtUtc).EndsWith('Z') -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -gt [DateTimeOffset]::UtcNow.AddHours(24) -or
            [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$') {
        throw 'PRODUCTION deployment nonce or expiry is invalid'
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
$packageReport = (& (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') @verifyArguments) |
    ConvertFrom-Json
if ([string]$packageReport.releaseId -cne [string]$plan.releaseId) {
    throw 'Deployment plan releaseId does not match the verified package'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and (
        [string]$packageReport.releaseTier -cne 'PRODUCTION' -or
        -not ([string]$packageReport.sha256).Equals(
            [string]$plan.packageSha256,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -or
        -not ([string]$packageReport.manifestSha256).Equals(
            [string]$plan.manifestSha256,
            [System.StringComparison]::OrdinalIgnoreCase
        )
    )) {
    throw 'PRODUCTION approval must bind the exact PRODUCTION package and manifest digests'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and $AllowUnsignedTestManifest) {
    throw 'Unsigned test manifests are forbidden for PRODUCTION'
}

$steps = @(
    'LOCK', 'PREFLIGHT', 'VERIFY_PACKAGE', 'STOP_SERVICE', 'BACKUP', 'STAGE', 'MIGRATE',
    'SWITCH_POINTER', 'START_SERVICE', 'VERIFY_READINESS', 'AUDIT'
)
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    releaseId = [string]$plan.releaseId
    approvalId = [string]$plan.approvalId
    environmentName = [string]$plan.environmentName
    environmentKind = [string]$plan.environmentKind
    packageSha256 = [string]$packageReport.sha256
    hostLayoutSha256 = if ($isProductionRootPair) { $layoutSha256 } else { $null }
    proxyBindingSha256 = if ($isProductionRootPair) { $proxyBindingSha256 } else { $null }
    steps = $steps
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmDeployment) {
    throw 'ConfirmDeployment is required before service stop, backup, migration, or activation'
}
if ([string]$plan.environmentKind -eq 'PRODUCTION') {
    foreach ($field in @(
            'requestedBy', 'approvedBy', 'requesterSignaturePath', 'approverSignaturePath'
        )) {
        if ($null -eq $plan.PSObject.Properties[$field] -or
                [string]::IsNullOrWhiteSpace([string]$plan.$field)) {
            throw "PRODUCTION deployment requires signed approval field $field"
        }
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
        throw 'Approved deployment plan bytes differ from the plan loaded for execution'
    }
}
if ($null -eq $plan.PSObject.Properties['backup']) {
    throw 'Executable deployment plan must include a typed backup object'
}
foreach ($field in @(
        'database', 'mySqlHost', 'mySqlPort', 'mySqlUser', 'expectedServerUuid',
        'mySqlSslCaPath'
    )) {
    if ($null -eq $plan.backup.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.backup.$field)) {
        throw "Deployment backup contract is missing $field"
    }
}
if ([string]$plan.backup.expectedServerUuid -notmatch '^[A-Fa-f0-9-]{16,64}$') {
    throw 'Deployment backup requires a pinned MySQL server UUID'
}
if ($null -eq $plan.PSObject.Properties['migration']) {
    throw 'Executable deployment plan must include a typed migration target for schema validation'
}
foreach ($field in @(
        'database', 'mySqlHost', 'mySqlPort', 'mySqlUser', 'expectedServerUuid',
        'mySqlSslTrustStorePath'
    )) {
    if ($null -eq $plan.migration.PSObject.Properties[$field] -or
            [string]::IsNullOrWhiteSpace([string]$plan.migration.$field)) {
        throw "Deployment migration contract is missing $field"
    }
}
if ([string]$plan.migration.database -cne [string]$plan.backup.database -or
        -not ([string]$plan.migration.mySqlHost).Equals(
            [string]$plan.backup.mySqlHost,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -or
        [int]$plan.migration.mySqlPort -ne [int]$plan.backup.mySqlPort -or
        -not ([string]$plan.migration.expectedServerUuid).Equals(
            [string]$plan.backup.expectedServerUuid,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Migration target must match the independently backed-up database target'
}
if ($null -eq $plan.PSObject.Properties['capacity']) {
    throw 'Executable deployment plan must include signed capacity estimates'
}
foreach ($field in @('expectedDatabaseBytes', 'expectedAttachmentBytes', 'minimumFreeBytes')) {
    if ($null -eq $plan.capacity.PSObject.Properties[$field]) {
        throw "Deployment capacity contract is missing $field"
    }
}
if ([int64]$plan.capacity.expectedDatabaseBytes -lt 0 -or
        [int64]$plan.capacity.expectedAttachmentBytes -lt 0 -or
        [int64]$plan.capacity.minimumFreeBytes -lt 1073741824) {
    throw 'Deployment capacity estimates or safety reserve are invalid'
}
$expectedMySqlCa = [IO.Path]::GetFullPath((Join-Path $dataRoot 'config\mysql-ca.pem'))
$expectedMySqlTrustStore = [IO.Path]::GetFullPath(
    (Join-Path $dataRoot 'config\mysql-truststore.jks')
)
if (-not [IO.Path]::GetFullPath([string]$plan.backup.mySqlSslCaPath).Equals(
        $expectedMySqlCa,
        [StringComparison]::OrdinalIgnoreCase
    ) -or
        -not [IO.Path]::GetFullPath([string]$plan.migration.mySqlSslTrustStorePath).Equals(
            $expectedMySqlTrustStore,
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Deployment MySQL trust anchors must use the host-owned fixed config paths'
}
$serviceWrapper = Join-Path $installRoot 'service\LeanTPM.Backend.exe'
$toolchain = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$pinnedWrapperHash = [string]$toolchain.winSW.sha256
if ($pinnedWrapperHash -notmatch '^[0-9a-f]{64}$' -or
        -not (Test-Path -LiteralPath $serviceWrapper -PathType Leaf) -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $serviceWrapper).Hash.Equals(
            $pinnedWrapperHash,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Installed LeanTPM.Backend wrapper does not match the repository pin'
}
$pointersRoot = Join-Path $dataRoot 'pointers'
$currentPointer = Join-Path $pointersRoot 'current-release.json'
$currentConfigPointer = Join-Path $pointersRoot 'current-config.json'
if (-not (Test-Path -LiteralPath $currentPointer -PathType Leaf)) {
    throw 'Upgrade deployment requires an existing current release; use the first-install ceremony'
}
if (-not (Test-Path -LiteralPath $currentConfigPointer -PathType Leaf)) {
    throw 'Upgrade deployment requires an existing runtime configuration pointer'
}
$previousPointerContent = Get-Content -LiteralPath $currentPointer -Encoding utf8 -Raw
$previousPointerObject = $previousPointerContent | ConvertFrom-Json
$previousConfigPointerContent = Get-Content -LiteralPath $currentConfigPointer -Encoding utf8 -Raw
$previousConfigPointerObject = $previousConfigPointerContent | ConvertFrom-Json
if ([string]$previousPointerObject.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$') {
    throw 'Current release pointer is invalid'
}
if ([int]$previousConfigPointerObject.schemaVersion -ne 1 -or
        [string]$previousConfigPointerObject.releaseId -cne
            [string]$previousPointerObject.releaseId -or
        [string]$previousConfigPointerObject.configId -notmatch
            '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$previousConfigPointerObject.directorySha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Current runtime configuration pointer does not match the current release'
}
$exactRetryCandidate = [string]$previousPointerObject.releaseId -ceq
        [string]$plan.releaseId -and
    [string]$previousPointerObject.packageSha256 -ceq [string]$packageReport.sha256 -and
    [string]$previousPointerObject.productVersion -ceq [string]$packageReport.productVersion -and
    [int]$previousPointerObject.databaseSchemaVersion -eq
        [int]$packageReport.databaseSchemaVersion -and
    [string]$previousConfigPointerObject.releaseId -ceq [string]$plan.releaseId -and
    [string]$previousConfigPointerObject.configId -ceq [string]$plan.runtimeConfigId -and
    [string]$previousConfigPointerObject.directorySha256 -ceq
        [string]$plan.runtimeConfigSha256
if ([string]$plan.environmentKind -eq 'PRODUCTION' -and -not $exactRetryCandidate -and (
        [string]$previousPointerObject.releaseId -cne [string]$plan.expectedCurrentReleaseId -or
        -not ([string]$previousPointerObject.packageSha256).Equals(
                [string]$plan.expectedCurrentPackageSha256,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or
        [string]$previousConfigPointerObject.directorySha256 -cne
            [string]$plan.expectedCurrentRuntimeConfigSha256
    )) {
    throw 'PRODUCTION deployment expected current release/config does not match host pointers'
}
$configVersionsRoot = [IO.Path]::GetFullPath((Join-Path $dataRoot 'config\versions')).TrimEnd('\')
$previousRuntimeConfigRoot = [IO.Path]::GetFullPath(
    (Join-Path $configVersionsRoot ([string]$previousConfigPointerObject.configId))
)
$runtimeConfigRoot = [IO.Path]::GetFullPath(
    (Join-Path $configVersionsRoot ([string]$plan.runtimeConfigId))
)
foreach ($configRoot in @($previousRuntimeConfigRoot, $runtimeConfigRoot)) {
    if (-not $configRoot.StartsWith(
            $configVersionsRoot + '\',
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Runtime configuration path escapes the host-owned versions root'
    }
}
$previousReleaseRoot = Join-Path $installRoot ("releases\{0}" -f [string]$previousPointerObject.releaseId)
$previousManifestPath = Join-Path $previousReleaseRoot 'release-manifest.json'
if (-not (Test-Path -LiteralPath $previousManifestPath -PathType Leaf)) {
    throw 'Current release manifest is missing; safe backup and rollback are impossible'
}
$previousManifest = Get-Content -LiteralPath $previousManifestPath -Encoding utf8 -Raw |
    ConvertFrom-Json

function Assert-RuntimeConfig {
    param(
        [string]$Root,
        [string]$ReleaseId,
        [string]$ConfigId,
        [string]$ProductVersion,
        [int]$DatabaseSchemaVersion,
        [string]$DirectorySha256
    )

    $result = & (Join-Path $PSScriptRoot 'Test-LeanTpmRuntimeConfig.ps1') `
        -RuntimeConfigRoot $Root `
        -DataRoot $dataRoot `
        -ExpectedReleaseId $ReleaseId `
        -ExpectedConfigId $ConfigId `
        -ExpectedProductVersion $ProductVersion `
        -ExpectedDatabaseSchemaVersion $DatabaseSchemaVersion `
        -ExpectedDatabaseHost ([string]$plan.migration.mySqlHost) `
        -ExpectedDatabasePort ([int]$plan.migration.mySqlPort) `
        -ExpectedDatabaseName ([string]$plan.migration.database) `
        -ExpectedDirectorySha256 $DirectorySha256 `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$result.status -cne 'PASS') {
        throw "Runtime configuration validation failed for $ReleaseId"
    }
}
$hostStateBeforeLock = @(
    [pscustomobject]@{
        path = $currentPointer
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $currentPointer).Hash
    },
    [pscustomobject]@{
        path = $currentConfigPointer
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $currentConfigPointer).Hash
    },
    [pscustomobject]@{
        path = $previousManifestPath
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $previousManifestPath).Hash
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

function Write-AuditEvent {
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
        correlationId = [string]$plan.approvalId
        nonce = if ($plan.PSObject.Properties['nonce']) { [string]$plan.nonce } else { $null }
        environmentName = [string]$plan.environmentName
        releaseId = [string]$plan.releaseId
        packageSha256 = [string]$packageReport.sha256
        runtimeConfigSha256 = [string]$plan.runtimeConfigSha256
        planSha256 = $loadedPlanSha256
        backupId = if ($null -ne $backupReport) { [string]$backupReport.backupId } else { $null }
        status = $Status
        actor = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        message = $Message
        previousHash = $previousHash
    }
    $eventBytes = [Text.Encoding]::UTF8.GetBytes(($event | ConvertTo-Json -Compress))
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $event.hash = ([BitConverter]::ToString($hasher.ComputeHash($eventBytes))).Replace('-', '').ToLowerInvariant() }
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
        [ValidateSet(
            'MIGRATION_IN_PROGRESS', 'ACTIVATION_AUTHORIZED',
            'ROLLBACK_AUTHORIZED', 'RECOVERY_REQUIRED'
        )]
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
    $isStartAuthorized = $Status -in @('ACTIVATION_AUTHORIZED', 'ROLLBACK_AUTHORIZED')
    if ($isStartAuthorized -and (
            $AuthorizedReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
            $AuthorizedPackageSha256 -notmatch '^[a-f0-9]{64}$'
        )) {
        throw 'Recovery start authorization requires an exact release and package digest'
    }
    $state = [ordered]@{
        schemaVersion = 1
        status = $Status
        releaseId = [string]$plan.releaseId
        previousReleaseId = [string]$previousPointerObject.releaseId
        targetSchema = if ($null -ne $manifest) {
            [int]$manifest.components.database.schemaTo
        }
        else { [int]$previousPointerObject.databaseSchemaVersion }
        targetPackageSha256 = [string]$packageReport.sha256
        database = [string]$plan.migration.database
        mySqlHost = ([string]$plan.migration.mySqlHost).Trim().ToLowerInvariant()
        mySqlPort = [int]$plan.migration.mySqlPort
        expectedServerUuid = ([string]$plan.migration.expectedServerUuid).Trim().ToLowerInvariant()
        runtimeConfigSha256 = [string]$plan.runtimeConfigSha256
        backupId = if ($null -ne $backupReport) { [string]$backupReport.backupId } else { $null }
        planSha256 = $loadedPlanSha256
        authorizedReleaseId = if ($isStartAuthorized) { $AuthorizedReleaseId } else { $null }
        authorizedPackageSha256 = if ($isStartAuthorized) {
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
                    'Failed to durably publish the recovery inhibition marker'
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

$lockDirectory = Join-Path $dataRoot 'locks'
$null = New-Item -ItemType Directory -Path $lockDirectory -Force
$lockPath = Join-Path $lockDirectory 'deployment.lock'
$lockStream = $null
$lockAcquired = $false
$deploymentLockToken = ''
$stageRoot = Join-Path $dataRoot ("staging\{0}" -f [string]$plan.releaseId)
$releaseRoot = Join-Path $installRoot ("releases\{0}" -f [string]$plan.releaseId)
$pointerSwitched = $false
$configPointerSwitched = $false
$junctionSwitched = $false
$serviceStopped = $false
$newServiceStarted = $false
$startAttempted = $false
$migrationStarted = $false
$automaticRollbackAllowed = $true
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
    if (Test-Path -LiteralPath $recoveryMarker -PathType Leaf) {
        throw 'An unfinished recovery transaction exists; use approved rollback or recovery reconciliation'
    }
    $auditPath = Join-Path $dataRoot 'audit\deployments.jsonl'
    $auditEntries = @()
    if (Test-Path -LiteralPath $auditPath -PathType Leaf) {
        $null = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $auditPath -OutputFormat Json | ConvertFrom-Json
        $auditEntries = @(Get-Content -LiteralPath $auditPath -Encoding utf8 |
            ForEach-Object { $_ | ConvertFrom-Json })
    }
    $alreadySucceeded = $false
    if ($exactRetryCandidate) {
        Assert-RuntimeConfig `
            -Root $runtimeConfigRoot `
            -ReleaseId ([string]$plan.releaseId) `
            -ConfigId ([string]$plan.runtimeConfigId) `
            -ProductVersion ([string]$packageReport.productVersion) `
            -DatabaseSchemaVersion ([int]$packageReport.databaseSchemaVersion) `
            -DirectorySha256 ([string]$plan.runtimeConfigSha256)
        $installedManifestPath = Join-Path $releaseRoot 'release-manifest.json'
        $existingSuccess = @($auditEntries | Where-Object {
                [string]$_.status -ceq 'SUCCEEDED' -and
                [string]$_.correlationId -ceq [string]$plan.approvalId -and
                [string]$_.releaseId -ceq [string]$plan.releaseId -and
                [string]$_.packageSha256 -ceq [string]$packageReport.sha256 -and
                [string]$_.runtimeConfigSha256 -ceq [string]$plan.runtimeConfigSha256 -and
                [string]$_.planSha256 -ceq $loadedPlanSha256
            })
        if ($existingSuccess.Count -ne 1 -or
                -not (Test-Path -LiteralPath $installedManifestPath -PathType Leaf) -or
                -not (Get-FileHash -Algorithm SHA256 -LiteralPath $installedManifestPath).
                    Hash.Equals(
                        [string]$packageReport.manifestSha256,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
            throw 'Current release matches the request but lacks one exact audited success result'
        }
        $alreadySucceeded = $true
        $report.status = 'ALREADY_SUCCEEDED'
        $report | Add-Member -NotePropertyName completedAtUtc `
            -NotePropertyValue ([string]$existingSuccess[0].timestampUtc)
        if ($null -ne $existingSuccess[0].backupId) {
            $report | Add-Member -NotePropertyName backupId `
                -NotePropertyValue ([string]$existingSuccess[0].backupId)
        }
    }
    if (-not $alreadySucceeded) {
        Assert-RuntimeConfig `
            -Root $previousRuntimeConfigRoot `
            -ReleaseId ([string]$previousPointerObject.releaseId) `
            -ConfigId ([string]$previousConfigPointerObject.configId) `
            -ProductVersion ([string]$previousManifest.productVersion) `
            -DatabaseSchemaVersion ([int]$previousPointerObject.databaseSchemaVersion) `
            -DirectorySha256 ([string]$previousConfigPointerObject.directorySha256)
        Assert-RuntimeConfig `
            -Root $runtimeConfigRoot `
            -ReleaseId ([string]$plan.releaseId) `
            -ConfigId ([string]$plan.runtimeConfigId) `
            -ProductVersion ([string]$packageReport.productVersion) `
            -DatabaseSchemaVersion ([int]$packageReport.databaseSchemaVersion) `
            -DirectorySha256 ([string]$plan.runtimeConfigSha256)
        $preflightReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmDeploymentPreflight.ps1') `
            -InstallRoot $installRoot `
            -DataRoot $dataRoot `
            -BackupRoot $backupRoot `
            -PackagePath $packagePath `
            -HealthUri $healthUri `
            -ExpectedDatabaseBytes ([int64]$plan.capacity.expectedDatabaseBytes) `
            -ExpectedAttachmentBytes ([int64]$plan.capacity.expectedAttachmentBytes) `
            -MinimumFreeBytes ([int64]$plan.capacity.minimumFreeBytes) `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$preflightReport.status -cne 'PASS') {
            throw 'Deployment target resource preflight did not pass'
        }
        if ([string]$plan.environmentKind -eq 'PRODUCTION') {
            $replayed = @($auditEntries | Where-Object {
                    [string]$_.nonce -ceq [string]$plan.nonce -or
                    [string]$_.correlationId -ceq [string]$plan.approvalId
                })
            if ($replayed.Count -gt 0) {
                throw 'PRODUCTION deployment approval nonce was already consumed'
            }
        }
        if ((Test-Path -LiteralPath $stageRoot) -or (Test-Path -LiteralPath $releaseRoot)) {
            throw 'Staging or immutable release directory already exists'
        }
    }
    if (-not $alreadySucceeded -and
            $PSCmdlet.ShouldProcess([string]$plan.environmentName, "Deploy $($plan.releaseId)")) {
        Write-AuditEvent 'PREFLIGHTED' 'Package, host roots, wrapper and approval validated.'
        $null = Invoke-BackendService Stop
        $serviceStopped = $true

        $backupReport = & (Join-Path $PSScriptRoot 'New-LeanTpmBackupSet.ps1') `
            -Database ([string]$plan.backup.database) `
            -ConfirmDatabase ([string]$plan.backup.database) `
            -MySqlHost ([string]$plan.backup.mySqlHost) `
            -MySqlPort ([int]$plan.backup.mySqlPort) `
            -MySqlUser ([string]$plan.backup.mySqlUser) `
            -MySqlSslCaPath ([string]$plan.backup.mySqlSslCaPath) `
            -AttachmentRoot (Join-Path $dataRoot 'data\uploads') `
            -ConfigPath (Join-Path $previousRuntimeConfigRoot 'effective-config.json') `
            -RuntimeEnvironmentPath (Join-Path $previousRuntimeConfigRoot 'leantpm.env') `
            -SecretReferencePath (Join-Path $previousRuntimeConfigRoot 'secret-references.json') `
            -PointerRoot $pointersRoot `
            -ProtectionProfilePath (Join-Path $dataRoot 'config\backup-protection.json') `
            -ReleaseManifestPath $previousManifestPath `
            -BackupRoot $backupRoot `
            -EnvironmentName ([string]$plan.environmentName) `
            -EnvironmentKind ([string]$plan.environmentKind) `
            -ExpectedServerUuid ([string]$plan.backup.expectedServerUuid) `
            -ConfirmBackupTarget `
            -ConfirmApplicationWritesQuiesced `
            -Confirm:$false `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$backupReport.status -cne 'VALID') { throw 'Backup set was not independently validated' }
        Write-AuditEvent 'BACKUP_VERIFIED' 'Quiesced database, attachment, config and pointer backup validated.'

        $stagedArguments = @{
            PackagePath = $packagePath
            ExtractTo = $stageRoot
            OutputFormat = 'Json'
            AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
        }
        if (-not [string]::IsNullOrWhiteSpace($manifestThumbprint)) {
            $stagedArguments.TrustedCertificateThumbprint = $manifestThumbprint
        }
        $stagedReport = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') @stagedArguments |
            ConvertFrom-Json
        if ([string]$stagedReport.sha256 -cne [string]$packageReport.sha256) {
            throw 'Release package changed after preflight verification'
        }
        Move-Item -LiteralPath $stageRoot -Destination $releaseRoot
        $releaseAcl = & (Join-Path $PSScriptRoot `
                '..\deploy\windows\Protect-LeanTpmReleaseDirectory.ps1') `
            -InstallRoot $installRoot -DataRoot $dataRoot `
            -ReleaseId ([string]$plan.releaseId) -Confirm:$false `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$releaseAcl.status -cne 'PASS') {
            throw 'Promoted release did not pass immutable ACL normalization'
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
            throw 'Promoted release bytes changed after ACL normalization'
        }
        Write-AuditEvent 'STAGED' `
            'Complete verified package moved into a byte-revalidated immutable release root.'

        $manifest = Get-Content -LiteralPath (Join-Path $releaseRoot 'release-manifest.json') `
            -Encoding utf8 -Raw | ConvertFrom-Json
        $automaticRollbackAllowed = [string]$manifest.rollback.class -in @(
            'APPLICATION_ONLY', 'FORWARD_COMPATIBLE_SCHEMA'
        )
        Write-RecoveryState 'MIGRATION_IN_PROGRESS'
        $migrationStarted = $true
        $migrationReport = & (Join-Path $PSScriptRoot 'Invoke-LeanTpmMigrator.ps1') `
            -ReleaseRoot $releaseRoot `
            -MySqlHost ([string]$plan.migration.mySqlHost) `
            -MySqlPort ([int]$plan.migration.mySqlPort) `
            -Database ([string]$plan.migration.database) `
            -MySqlUser ([string]$plan.migration.mySqlUser) `
            -ExpectedServerUuid ([string]$plan.migration.expectedServerUuid) `
            -MySqlSslTrustStorePath ([string]$plan.migration.mySqlSslTrustStorePath) `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$migrationReport.status -cne 'PASS') {
            throw 'Migrator did not validate the actual Flyway schema to the approved target'
        }
        Write-AuditEvent 'MIGRATED' 'Flyway validate/migrate reached the manifest schemaTo.'
        Assert-RuntimeConfig `
            -Root $runtimeConfigRoot `
            -ReleaseId ([string]$plan.releaseId) `
            -ConfigId ([string]$plan.runtimeConfigId) `
            -ProductVersion ([string]$manifest.productVersion) `
            -DatabaseSchemaVersion ([int]$manifest.components.database.schemaTo) `
            -DirectorySha256 ([string]$plan.runtimeConfigSha256)
        Write-RecoveryState 'ACTIVATION_AUTHORIZED' `
            -AuthorizedReleaseId ([string]$plan.releaseId) `
            -AuthorizedPackageSha256 ([string]$packageReport.sha256)

        & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
            -InstallRoot $installRoot -DataRoot $dataRoot `
            -TargetReleaseId ([string]$plan.releaseId) `
            -DeploymentLockToken $deploymentLockToken `
            -ExpectedHostLayoutSha256 $layoutSha256 `
            -ExpectedManifestSha256 ([string]$plan.manifestSha256) `
            -AllowNonProductionRoot:($customRoots -and
                [string]$plan.environmentKind -eq 'NON_PRODUCTION') | Out-Null
        $junctionSwitched = $true
        $newConfigPointer = [ordered]@{
            schemaVersion = 1
            releaseId = [string]$plan.releaseId
            configId = [string]$plan.runtimeConfigId
            directorySha256 = [string]$plan.runtimeConfigSha256
            activatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            approvalId = [string]$plan.approvalId
        } | ConvertTo-Json
        $configPointerTemp = Join-Path $pointersRoot 'current-config.json.new'
        [IO.File]::WriteAllText($configPointerTemp, $newConfigPointer)
        [IO.File]::Replace(
            $configPointerTemp,
            $currentConfigPointer,
            (Join-Path $pointersRoot 'previous-config.json'),
            $true
        )
        $configPointerSwitched = $true
        $newPointer = [ordered]@{
            schemaVersion = 1
            releaseId = [string]$plan.releaseId
            productVersion = [string]$manifest.productVersion
            databaseSchemaVersion = [int]$manifest.components.database.schemaTo
            packageSha256 = [string]$packageReport.sha256
            activatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            approvalId = [string]$plan.approvalId
        } | ConvertTo-Json
        $pointerTemp = Join-Path $pointersRoot 'current-release.json.new'
        [System.IO.File]::WriteAllText($pointerTemp, $newPointer)
        [System.IO.File]::Replace(
            $pointerTemp,
            $currentPointer,
            (Join-Path $pointersRoot 'previous-release.json'),
            $true
        )
        $pointerSwitched = $true
        Write-AuditEvent 'SWITCHED' 'Backend pointer and Web current junction switched.'

        $startAttempted = $true
        $null = Invoke-BackendService Start
        $newServiceStarted = $true
        Wait-ReleaseReadiness `
            -ExpectedVersion ([string]$manifest.productVersion) `
            -ExpectedSchema ([int]$manifest.components.database.schemaTo)
        Write-AuditEvent 'SUCCEEDED' 'Deployment, release identity and readiness verification completed.'
        Remove-RecoveryState
        $report.status = 'SUCCEEDED'
        $report | Add-Member -NotePropertyName backupId -NotePropertyValue $backupReport.backupId
    }
}
catch {
    $failure = $_
    $compensationErrors = [System.Collections.Generic.List[string]]::new()
    $newServiceStopped = $true
    if ($startAttempted) {
        $newServiceStopped = Invoke-CompensationStep 'STOP_NEW_SERVICE' {
            Invoke-BackendService Stop
        } $compensationErrors
        try {
            $failClosedStop = Invoke-FailClosedBackendStop
            if ([string]$failClosedStop.status -ceq 'STOPPED') {
                $newServiceStopped = $true
            }
            elseif ([string]$failClosedStop.status -ceq 'PROXY_ISOLATED') {
                $newServiceStopped = $false
                $script:lastIngressIsolation = $failClosedStop
                Write-AuditEvent 'COMPENSATION_PROXY_ISOLATED_CRITICAL' `
                    'Deployment target could not be stopped; fixed public ingress was isolated.'
            }
            else {
                throw "Unexpected fail-closed result: $($failClosedStop.status)"
            }
        }
        catch {
            $newServiceStopped = $false
            $compensationErrors.Add(
                "FAIL_CLOSED_STOP_NEW_SERVICE: $($_.Exception.Message)"
            )
        }
    }
    $recoveryRequired = $migrationStarted -and -not $automaticRollbackAllowed
    if (-not $newServiceStopped) { $recoveryRequired = $true }
    if (-not $recoveryRequired) {
        $compensated = $true
        if ($migrationStarted) {
            $compensated = (Invoke-CompensationStep 'AUTHORIZE_PREVIOUS_RELEASE' {
                    Write-RecoveryState 'ROLLBACK_AUTHORIZED' `
                        -AuthorizedReleaseId ([string]$previousPointerObject.releaseId) `
                        -AuthorizedPackageSha256 ([string]$previousPointerObject.packageSha256)
                } $compensationErrors) -and $compensated
        }
        if ($pointerSwitched) {
            $compensated = (Invoke-CompensationStep 'RESTORE_BACKEND_POINTER' {
                    $rollbackTemp = Join-Path $pointersRoot 'current-release.rollback'
                    [System.IO.File]::WriteAllText($rollbackTemp, $previousPointerContent)
                    [System.IO.File]::Replace($rollbackTemp, $currentPointer, $null, $true)
                } $compensationErrors) -and $compensated
        }
        if ($configPointerSwitched) {
            $compensated = (Invoke-CompensationStep 'RESTORE_CONFIG_POINTER' {
                    $configRollbackTemp = Join-Path $pointersRoot 'current-config.rollback'
                    [IO.File]::WriteAllText(
                        $configRollbackTemp,
                        $previousConfigPointerContent
                    )
                    [IO.File]::Replace(
                        $configRollbackTemp,
                        $currentConfigPointer,
                        $null,
                        $true
                    )
                } $compensationErrors) -and $compensated
        }
        if ($junctionSwitched) {
            $compensated = (Invoke-CompensationStep 'RESTORE_WEB_JUNCTION' {
                    & (Join-Path $PSScriptRoot '..\deploy\windows\Set-LeanTpmCurrentJunction.ps1') `
                        -InstallRoot $installRoot -DataRoot $dataRoot `
                        -TargetReleaseId ([string]$previousPointerObject.releaseId) `
                        -DeploymentLockToken $deploymentLockToken `
                        -ExpectedHostLayoutSha256 $layoutSha256 `
                        -ExpectedManifestSha256 ((Get-FileHash -Algorithm SHA256 `
                                -LiteralPath $previousManifestPath).Hash.ToLowerInvariant()) `
                        -AllowNonProductionRoot:($customRoots -and
                            [string]$plan.environmentKind -eq 'NON_PRODUCTION')
                } $compensationErrors) -and $compensated
        }
        if ($serviceStopped -and $compensated) {
            $compensated = (Invoke-CompensationStep 'START_PREVIOUS_SERVICE' {
                    Invoke-BackendService Start
                } $compensationErrors) -and $compensated
        }
        if ($serviceStopped -and $compensated) {
            $rollbackSchema = if ($migrationStarted) {
                [int]$manifest.components.database.schemaTo
            }
            else { [int]$previousPointerObject.databaseSchemaVersion }
            $compensated = (Invoke-CompensationStep 'VERIFY_PREVIOUS_READINESS' {
                    Wait-ReleaseReadiness `
                        -ExpectedVersion ([string]$previousManifest.productVersion) `
                        -ExpectedSchema $rollbackSchema
                } $compensationErrors) -and $compensated
        }
        if ($compensated) {
            $compensated = (Invoke-CompensationStep 'AUDIT_FAILED_DEPLOYMENT' {
                    Write-AuditEvent 'FAILED' $failure.Exception.Message
                } $compensationErrors) -and $compensated
        }
        if ($compensated) {
            Remove-RecoveryState
        }
        else { $recoveryRequired = $true }
    }
    if ($recoveryRequired) {
        $null = Invoke-CompensationStep 'PERSIST_RECOVERY_REQUIRED' {
            Write-RecoveryState 'RECOVERY_REQUIRED'
        } $compensationErrors
        $null = Invoke-CompensationStep 'AUDIT_COMPENSATION_FAILED' {
            Write-AuditEvent 'COMPENSATION_FAILED' (
                'Deployment requires recovery: ' + ($compensationErrors -join '; ')
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
