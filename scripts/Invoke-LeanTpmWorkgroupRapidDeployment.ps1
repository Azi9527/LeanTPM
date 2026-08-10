[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [switch]$PlanOnly,
    [switch]$AllowUnsignedTestManifest,
    [switch]$ConfirmDeployment,
    [switch]$AllowNonProductionCustomRoots,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixedInstallRoot = 'D:\LeanTPM\App'
$fixedDataRoot = 'D:\LeanTPM\Runtime'
$fixedHostLayoutPath = 'C:\ProgramData\LeanTPM-bootstrap\host-layout.json'
$fixedCaddyPolicyPath = Join-Path $fixedDataRoot `
    'config\external-caddy-binding.json'
$fixedTrustPath = Join-Path $fixedDataRoot 'config\release-trust.json'
$fixedPointerPath = Join-Path $fixedDataRoot 'pointers\current-release.json'
$fixedDbSecretPath = Join-Path $fixedDataRoot 'secrets\db-password.bin'
$fixedBackendStarterPath = Join-Path $fixedInstallRoot `
    'service\Start-LeanTpmBackend-Rapid.ps1'
$fixedCaddyConfigPath = 'D:\LeanTPM\shared\config\Caddyfile'
$fixedCaddyPath = 'D:\LeanTPM\tools\caddy\caddy.exe'
$fixedMysqlPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$fixedMysqldumpPath = `
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$fixedUploadRoot = Join-Path $fixedDataRoot 'ops-control-plane\uploads'
$fixedApprovalRoot = Join-Path $fixedDataRoot 'ops-control-plane\approvals'
$strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
$steps = @(
    'LOCK',
    'VERIFY_PACKAGE',
    'VERIFY_V50',
    'BACKUP',
    'STAGE',
    'ACTIVATE',
    'VERIFY',
    'ROLLBACK_ON_FAILURE',
    'AUDIT'
)

if ($AllowUnsignedTestManifest -or $AllowNonProductionCustomRoots) {
    throw 'WORKGROUP_RAPID never permits unsigned or custom-root deployment'
}

function Get-BytesSha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.ToLowerInvariant()
}

function Read-StrictJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [int64]$MaximumBytes = 8MB
    )

    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
            $item.Length -lt 2 -or $item.Length -gt $MaximumBytes) {
        throw "$Label must be a bounded regular non-reparse file"
    }
    $bytes = [IO.File]::ReadAllBytes($item.FullName)
    try {
        return [pscustomobject]@{
            path = $item.FullName
            bytes = $bytes
            sha256 = Get-BytesSha256 $bytes
            value = $strictUtf8.GetString($bytes) |
                ConvertFrom-Json -ErrorAction Stop
        }
    }
    catch { throw "$Label must be strict UTF-8 JSON" }
}

function Get-ContainedFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $pathFull = [IO.Path]::GetFullPath($Path)
    if (-not $pathFull.StartsWith(
            $rootFull + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Label is outside its fixed control root"
    }
    $item = Get-Item -LiteralPath $pathFull -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse file"
    }
    return $item.FullName
}

function Read-MachineSecret {
    param([Parameter(Mandatory)][string]$Path)

    Add-Type -AssemblyName System.Security
    $encrypted = [IO.File]::ReadAllBytes($Path)
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $encrypted,
            $null,
            [Security.Cryptography.DataProtectionScope]::LocalMachine
        )
        try { return [Text.Encoding]::UTF8.GetString($plain) }
        finally { [Array]::Clear($plain, 0, $plain.Length) }
    }
    finally { [Array]::Clear($encrypted, 0, $encrypted.Length) }
}

function Invoke-MySqlQuery {
    param(
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Query
    )

    $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $Password, 'Process')
        $output = @(& $fixedMysqlPath `
                '--protocol=TCP' '--host=127.0.0.1' '--port=3306' `
                '--user=leantpm_app' '--batch' '--skip-column-names' `
                '--database=leantpm' "--execute=$Query")
        if ($LASTEXITCODE -ne 0) { throw 'Fixed MySQL read-only query failed' }
        return @($output | ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $_.Length -gt 0 })
    }
    finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process')
    }
}

function Test-DatabaseV50 {
    param(
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$ExpectedServerUuid
    )

    $query = @'
SELECT @@server_uuid;
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='leantpm' AND TABLE_NAME='flyway_schema_history';
SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM leantpm.flyway_schema_history WHERE success=1;
SELECT COUNT(*) FROM leantpm.flyway_schema_history WHERE success=0;
'@
    $values = @(Invoke-MySqlQuery -Password $Password -Query $query)
    if ($values.Count -ne 4 -or
            -not $values[0].Equals(
                $ExpectedServerUuid,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            $values[1] -ne '1' -or $values[2] -ne '50' -or
            $values[3] -ne '0') {
        throw 'Production database is not the approved MySQL server at clean V50'
    }
    return [pscustomobject]@{
        serverUuid = $values[0]
        schemaVersion = 50
        failedMigrationCount = 0
    }
}

function Wait-BackendReadiness {
    param(
        [Parameter(Mandatory)][string]$ExpectedVersion,
        [Parameter(Mandatory)][int]$ExpectedSchema
    )

    $deadline = (Get-Date).AddSeconds(120)
    do {
        try {
            $health = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/actuator/health/readiness' `
                -TimeoutSec 5
            $info = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/actuator/info' -TimeoutSec 5
            if ([string]$health.status -ceq 'UP' -and
                    [string]$info.app.version -ceq $ExpectedVersion -and
                    [int]$info.app.'database-schema-version' -eq $ExpectedSchema) {
                return
            }
        }
        catch { }
        Start-Sleep -Seconds 2
    }
    while ((Get-Date) -lt $deadline)
    throw 'Activated Backend did not reach the signed release identity'
}

function Test-FixedRuntimeState {
    $backend = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
    $caddy = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='caddy'" -ErrorAction Stop
    if ([string]$backend.State -cne 'Running' -or
            [string]$backend.StartName -cne 'NT AUTHORITY\NetworkService' -or
            [string]$caddy.State -cne 'Running' -or
            [string]$caddy.StartName -notin @(
                'LocalSystem', 'NT AUTHORITY\SYSTEM'
            ) -or [int]$caddy.ProcessId -le 0) {
        throw 'Backend and Caddy must be running under the fixed production accounts'
    }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { [int]$_.LocalPort -in @(80, 443, 18080, 3306) })
    foreach ($port in @(80, 443)) {
        $public = @($listeners | Where-Object { [int]$_.LocalPort -eq $port })
        if ($public.Count -lt 1 -or
                @($public | Where-Object {
                        [int]$_.OwningProcess -ne [int]$caddy.ProcessId
                    }).Count -ne 0) {
            throw "Port $port is not exclusively owned by the fixed Caddy process"
        }
    }
    $backendListeners = @($listeners | Where-Object {
            [int]$_.LocalPort -eq 18080
        })
    if ($backendListeners.Count -ne 1 -or
            [string]$backendListeners[0].LocalAddress -ne '127.0.0.1') {
        throw 'Backend port 18080 is not one fixed loopback listener'
    }
    if (@($listeners | Where-Object {
                [int]$_.LocalPort -eq 3306 -and
                [string]$_.LocalAddress -notin @('127.0.0.1', '::1')
            }).Count -ne 0) {
        throw 'MySQL port 3306 has a non-loopback listener'
    }
    return [pscustomobject]@{
        backendState = [string]$backend.State
        caddyState = [string]$caddy.State
        caddyPid = [int]$caddy.ProcessId
    }
}

function Write-AtomicJson {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)

    $temporary = $Path + '.rapid-new'
    $bytes = $strictUtf8.GetBytes(($Value | ConvertTo-Json -Depth 8 -Compress))
    try {
        [IO.File]::WriteAllBytes($temporary, $bytes)
        [IO.File]::Replace($temporary, $Path, $null, $true)
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Write-Result {
    param([Parameter(Mandatory)]$Value)

    if ($OutputFormat -eq 'Json') {
        $Value | ConvertTo-Json -Depth 8 -Compress
    }
    else { $Value | Format-List }
}

$planSnapshot = Read-StrictJson $PlanPath 'signed deployment plan' 1MB
$plan = $planSnapshot.value
foreach ($field in @(
        'schemaVersion', 'deploymentMode', 'environmentName', 'environmentKind',
        'environmentId', 'hostId', 'releaseId', 'approvalId', 'packagePath',
        'packageSha256', 'manifestSha256', 'databaseSchemaVersion',
        'opsHostSnapshotSha256', 'requestedBy', 'approvedBy',
        'nonce', 'expiresAtUtc',
        'requesterSignaturePath', 'approverSignaturePath', 'hostLayoutSha256',
        'proxyBindingSha256', 'expectedCurrentReleaseId',
        'expectedCurrentPackageSha256', 'backup'
    )) {
    if ($null -eq $plan.PSObject.Properties[$field]) {
        throw "WORKGROUP_RAPID plan is missing $field"
    }
}
if ([int]$plan.schemaVersion -ne 1 -or
        [string]$plan.deploymentMode -cne 'WORKGROUP_RAPID' -or
        [string]$plan.environmentKind -cne 'PRODUCTION' -or
        [string]$plan.environmentId -cne 'leantpm-production-cn' -or
        [int]$plan.databaseSchemaVersion -ne 50 -or
        [string]$plan.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
        [string]$plan.packageSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.manifestSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.hostLayoutSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.proxyBindingSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.expectedCurrentReleaseId -notmatch
            '^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$' -or
        [string]$plan.expectedCurrentPackageSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$') {
    throw 'WORKGROUP_RAPID plan identity is invalid'
}
$expiresAt = [DateTimeOffset]::MinValue
$expiresText = [string]$plan.expiresAtUtc
$parsedExpiry = $expiresText.EndsWith('Z', [StringComparison]::Ordinal) -and
    [DateTimeOffset]::TryParse(
        $expiresText,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal,
        [ref]$expiresAt
    )
$now = [DateTimeOffset]::UtcNow
if (-not $parsedExpiry -or $expiresAt -le $now -or
        $expiresAt -gt $now.AddHours(24)) {
    throw 'WORKGROUP_RAPID plan expiry must be UTC and within 24 hours'
}
foreach ($backupField in @(
        'database', 'mySqlHost', 'mySqlPort', 'mySqlUser', 'expectedServerUuid'
    )) {
    if ($null -eq $plan.backup.PSObject.Properties[$backupField]) {
        throw "WORKGROUP_RAPID backup contract is missing $backupField"
    }
}
if ([string]$plan.backup.database -cne 'leantpm' -or
        [string]$plan.backup.mySqlHost -cne '127.0.0.1' -or
        [int]$plan.backup.mySqlPort -ne 3306 -or
        [string]$plan.backup.mySqlUser -cne 'leantpm_app' -or
        [string]$plan.backup.expectedServerUuid -notmatch
            '^[A-Fa-f0-9-]{16,64}$') {
    throw 'WORKGROUP_RAPID backup target is not the fixed production database'
}

$layoutSnapshot = Read-StrictJson $fixedHostLayoutPath 'fixed host layout'
$layout = $layoutSnapshot.value
$policySnapshot = Read-StrictJson $fixedCaddyPolicyPath `
    'fixed WORKGROUP Caddy policy'
$policy = $policySnapshot.value
$trustSnapshot = Read-StrictJson $fixedTrustPath 'fixed release trust'
$trust = $trustSnapshot.value
if ($layoutSnapshot.sha256 -cne [string]$plan.hostLayoutSha256 -or
        [string]$layout.environmentKind -cne 'PRODUCTION' -or
        [string]$layout.environmentId -cne [string]$plan.environmentId -or
        [string]$layout.hostId -cne [string]$plan.hostId -or
        [string]$layout.installRoot -cne $fixedInstallRoot -or
        [string]$layout.dataRoot -cne $fixedDataRoot -or
        [string]$layout.proxy.bindingPolicyPath -cne $fixedCaddyPolicyPath -or
        [string]$layout.proxy.bindingPolicySha256 -cne
            [string]$plan.proxyBindingSha256 -or
        $policySnapshot.sha256 -cne [string]$plan.proxyBindingSha256 -or
        [string]$policy.bootstrapMode -cne 'WORKGROUP_RAPID' -or
        [string]$policy.readiness -cne 'READY' -or
        [string]$policy.serviceId -cne 'caddy' -or
        [string]$policy.configPath -cne $fixedCaddyConfigPath -or
        [string]$policy.backendUpstream -cne 'http://127.0.0.1:18080') {
    throw 'Signed plan does not match the fixed WORKGROUP_RAPID host binding'
}
if ([string]$trust.environmentId -cne [string]$plan.environmentId -or
        [string]$trust.hostId -cne [string]$plan.hostId -or
        [string]$trust.manifestCertificateThumbprint -notmatch
            '^[A-Fa-f0-9]{40}$') {
    throw 'Release trust does not match the fixed WORKGROUP host'
}

$packagePath = Get-ContainedFile -Root $fixedUploadRoot `
    -Path ([string]$plan.packagePath) -Label 'release package'
$requesterSignaturePath = Get-ContainedFile -Root $fixedApprovalRoot `
    -Path ([string]$plan.requesterSignaturePath) -Label 'requester signature'
$approverSignaturePath = Get-ContainedFile -Root $fixedApprovalRoot `
    -Path ([string]$plan.approverSignaturePath) -Label 'approver signature'
if ((Get-FileSha256 $packagePath) -cne [string]$plan.packageSha256) {
    throw 'Release package bytes differ from the signed plan'
}
$packageReport = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') `
    -PackagePath $packagePath `
    -TrustedCertificateThumbprint `
        ([string]$trust.manifestCertificateThumbprint) `
    -OutputFormat Json | ConvertFrom-Json
if ([string]$packageReport.status -cne 'PASS' -or
        [string]$packageReport.releaseTier -cne 'PRODUCTION' -or
        [string]$packageReport.releaseId -cne [string]$plan.releaseId -or
        [string]$packageReport.sha256 -cne [string]$plan.packageSha256 -or
        [string]$packageReport.manifestSha256 -cne
            [string]$plan.manifestSha256 -or
        [int]$packageReport.databaseSchemaFrom -ne 50 -or
        [int]$packageReport.databaseSchemaVersion -ne 50) {
    throw 'Verified package is not the signed no-migration V50 release'
}
$approvalReport = & (Join-Path $PSScriptRoot `
        'Test-LeanTpmReleaseApproval.ps1') `
    -PlanPath $planSnapshot.path `
    -RequesterSignaturePath $requesterSignaturePath `
    -ApproverSignaturePath $approverSignaturePath `
    -TrustConfigPath $fixedTrustPath -OutputFormat Json | ConvertFrom-Json
if ([string]$approvalReport.status -cne 'PASS' -or
        [string]$approvalReport.planSha256 -cne $planSnapshot.sha256 -or
        [string]$approvalReport.requestedBy -cne [string]$plan.requestedBy -or
        [string]$approvalReport.approvedBy -cne [string]$plan.approvedBy) {
    throw 'Automated dual-CMS approval does not bind the loaded plan bytes'
}

$currentSnapshot = Read-StrictJson $fixedPointerPath 'current release pointer'
$current = $currentSnapshot.value
if ([string]$current.releaseId -cne [string]$plan.expectedCurrentReleaseId -or
        [string]$current.packageSha256 -cne
            [string]$plan.expectedCurrentPackageSha256) {
    throw 'Current release changed after the signed plan was created'
}
$dbPassword = Read-MachineSecret $fixedDbSecretPath
try {
    $runtime = Test-FixedRuntimeState
    $database = Test-DatabaseV50 -Password $dbPassword `
        -ExpectedServerUuid ([string]$plan.backup.expectedServerUuid)

    $report = [pscustomobject][ordered]@{
        status = 'PLAN'
        releaseId = [string]$plan.releaseId
        approvalId = [string]$plan.approvalId
        environmentName = [string]$plan.environmentName
        environmentKind = 'PRODUCTION'
        packageSha256 = [string]$plan.packageSha256
        hostLayoutSha256 = [string]$plan.hostLayoutSha256
        proxyBindingSha256 = [string]$plan.proxyBindingSha256
        databaseSchemaVersion = 50
        serverUuid = [string]$database.serverUuid
        backendState = [string]$runtime.backendState
        caddyState = [string]$runtime.caddyState
        steps = $steps
    }
    if ($PlanOnly) {
        Write-Result $report
        return
    }
    if (-not $ConfirmDeployment) {
        throw 'ConfirmDeployment is required after a fresh WORKGROUP_RAPID PlanOnly'
    }
    if (-not $PSCmdlet.ShouldProcess(
            [string]$plan.environmentName,
            "Deploy $($plan.releaseId) with fixed backup and rollback"
        )) {
        return
    }

    $locksRoot = Join-Path $fixedDataRoot 'locks'
    if (-not (Test-Path -LiteralPath $locksRoot -PathType Container)) {
        throw 'Fixed deployment lock directory is missing'
    }
    $lockPath = Join-Path $locksRoot 'workgroup-rapid-deployment.lock'
    $lock = $null
    $lock = New-Object IO.FileStream(
        $lockPath,
        [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
    $backupRoot = Join-Path $fixedDataRoot (
        'backups\rapid-' + [string]$plan.approvalId
    )
    $stageRoot = Join-Path $fixedDataRoot (
        'staging\' + [string]$plan.releaseId
    )
    $releaseRoot = Join-Path $fixedInstallRoot (
        'releases\' + [string]$plan.releaseId
    )
    $backendStopped = $false
    $configurationChanged = $false
    try {
        $null = Test-DatabaseV50 -Password $dbPassword `
            -ExpectedServerUuid ([string]$plan.backup.expectedServerUuid)
        if ((Get-FileSha256 $packagePath) -cne [string]$plan.packageSha256 -or
                (Get-FileSha256 $fixedHostLayoutPath) -cne
                    [string]$plan.hostLayoutSha256 -or
                (Get-FileSha256 $fixedCaddyPolicyPath) -cne
                    [string]$plan.proxyBindingSha256) {
            throw 'Package or fixed host binding changed after PlanOnly'
        }
        if ((Test-Path -LiteralPath $backupRoot) -or
                (Test-Path -LiteralPath $stageRoot) -or
                (Test-Path -LiteralPath $releaseRoot)) {
            throw 'Backup, staging or immutable release target already exists'
        }

        $null = New-Item -ItemType Directory -Path $backupRoot
        $databaseDumpPath = Join-Path $backupRoot 'leantpm.sql'
        $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
        try {
            [Environment]::SetEnvironmentVariable('MYSQL_PWD', $dbPassword, 'Process')
            & $fixedMysqldumpPath '--protocol=TCP' '--host=127.0.0.1' `
                '--port=3306' '--user=leantpm_app' '--single-transaction' `
                '--routines' '--events' '--triggers' '--hex-blob' `
                '--set-gtid-purged=OFF' "--result-file=$databaseDumpPath" `
                'leantpm'
            if ($LASTEXITCODE -ne 0) { throw 'mysqldump.exe failed' }
        }
        finally {
            [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process')
        }
        $dumpItem = Get-Item -LiteralPath $databaseDumpPath -Force
        if ($dumpItem.Length -lt 128) { throw 'Database backup is unexpectedly empty' }
        Copy-Item -LiteralPath $fixedBackendStarterPath `
            -Destination (Join-Path $backupRoot 'backend-starter.ps1')
        Copy-Item -LiteralPath $fixedCaddyConfigPath `
            -Destination (Join-Path $backupRoot 'Caddyfile')
        Copy-Item -LiteralPath $fixedPointerPath `
            -Destination (Join-Path $backupRoot 'current-release.json')
        $uploads = Join-Path $fixedDataRoot 'data\uploads'
        if (Test-Path -LiteralPath $uploads -PathType Container) {
            Compress-Archive -LiteralPath $uploads `
                -DestinationPath (Join-Path $backupRoot 'uploads.zip') `
                -CompressionLevel Optimal
        }
        $backupReceipt = [ordered]@{
            schemaVersion = 1
            backupId = 'rapid-' + [string]$plan.approvalId
            createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            serverUuid = [string]$database.serverUuid
            databaseSchemaVersion = 50
            databaseDump = 'leantpm.sql'
            databaseDumpBytes = [int64]$dumpItem.Length
            databaseDumpSha256 = Get-FileSha256 $databaseDumpPath
            previousReleaseId = [string]$current.releaseId
            previousPackageSha256 = [string]$current.packageSha256
        }
        [IO.File]::WriteAllText(
            (Join-Path $backupRoot 'backup-receipt.json'),
            ($backupReceipt | ConvertTo-Json -Compress),
            $strictUtf8
        )

        $staged = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') `
            -PackagePath $packagePath -ExtractTo $stageRoot `
            -TrustedCertificateThumbprint `
                ([string]$trust.manifestCertificateThumbprint) `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$staged.status -cne 'PASS' -or
                [string]$staged.sha256 -cne [string]$plan.packageSha256) {
            throw 'Staged release did not match the verified package'
        }
        Move-Item -LiteralPath $stageRoot -Destination $releaseRoot

        $oldReleaseId = [string]$plan.expectedCurrentReleaseId
        $oldBackendJar = Join-Path $fixedInstallRoot (
            "releases\$oldReleaseId\payload\backend\leantpm-backend.jar"
        )
        $newBackendJar = Join-Path $releaseRoot `
            'payload\backend\leantpm-backend.jar'
        $oldWebRoot = "D:/LeanTPM/App/releases/$oldReleaseId/payload/web"
        $newWebRoot = (
            "D:/LeanTPM/App/releases/$($plan.releaseId)/payload/web"
        )
        if (-not (Test-Path -LiteralPath $newBackendJar -PathType Leaf) -or
                -not (Test-Path -LiteralPath (Join-Path $releaseRoot `
                        'payload\web\index.html') -PathType Leaf)) {
            throw 'Staged release is missing Backend or Web payload'
        }
        $starterText = [IO.File]::ReadAllText($fixedBackendStarterPath, $strictUtf8)
        $caddyText = [IO.File]::ReadAllText($fixedCaddyConfigPath, $strictUtf8)
        if (-not $starterText.Contains($oldBackendJar) -or
                -not $caddyText.Contains($oldWebRoot)) {
            throw 'Existing Backend starter or Caddyfile does not bind the signed current release'
        }
        $newStarterText = $starterText.Replace($oldBackendJar, $newBackendJar)
        $newCaddyText = $caddyText.Replace($oldWebRoot, $newWebRoot)
        $candidateCaddy = Join-Path $backupRoot 'Caddyfile.candidate'
        [IO.File]::WriteAllText($candidateCaddy, $newCaddyText, $strictUtf8)
        & $fixedCaddyPath validate --config $candidateCaddy --adapter caddyfile
        if ($LASTEXITCODE -ne 0) { throw 'Candidate Caddyfile is invalid' }

        Stop-Service -Name 'LeanTPM.Backend' -Force
        (Get-Service -Name 'LeanTPM.Backend').WaitForStatus(
            'Stopped', [TimeSpan]::FromSeconds(45)
        )
        $backendStopped = $true
        [IO.File]::WriteAllText($fixedBackendStarterPath, $newStarterText, $strictUtf8)
        [IO.File]::WriteAllText($fixedCaddyConfigPath, $newCaddyText, $strictUtf8)
        $configurationChanged = $true
        Restart-Service -Name 'caddy' -Force
        (Get-Service -Name 'caddy').WaitForStatus(
            'Running', [TimeSpan]::FromSeconds(45)
        )
        Start-Service -Name 'LeanTPM.Backend'
        $backendStopped = $false
        (Get-Service -Name 'LeanTPM.Backend').WaitForStatus(
            'Running', [TimeSpan]::FromSeconds(45)
        )
        Wait-BackendReadiness -ExpectedVersion ([string]$packageReport.productVersion) `
            -ExpectedSchema 50
        $branding = Invoke-RestMethod `
            -Uri 'http://127.0.0.1/api/v1/public/branding' -TimeoutSec 10
        if ([string]$branding.code -cne 'OK') {
            throw 'Public branding verification failed after activation'
        }
        Write-AtomicJson -Path $fixedPointerPath -Value ([ordered]@{
                schemaVersion = 1
                releaseId = [string]$plan.releaseId
                productVersion = [string]$packageReport.productVersion
                databaseSchemaVersion = 50
                packageSha256 = [string]$plan.packageSha256
                activatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                approvalId = [string]$plan.approvalId
            })
        & icacls.exe $releaseRoot '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
            'NT AUTHORITY\NetworkService:(OI)(CI)RX' `
            'NT SERVICE\LeanTPM.ReleaseAgent:(OI)(CI)M' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Activated release ACL normalization failed'
        }
        $report.status = 'SUCCEEDED'
        $report | Add-Member -NotePropertyName backupId `
            -NotePropertyValue ([string]$backupReceipt.backupId)
        $report | Add-Member -NotePropertyName completedAtUtc `
            -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o'))
        Write-Result $report
    }
    catch {
        $failure = $_
        try {
            if ($configurationChanged -or $backendStopped) {
                Stop-Service -Name 'LeanTPM.Backend' -Force `
                    -ErrorAction SilentlyContinue
                if (Test-Path -LiteralPath (
                        Join-Path $backupRoot 'backend-starter.ps1'
                    )) {
                    Copy-Item -LiteralPath (
                        Join-Path $backupRoot 'backend-starter.ps1'
                    ) -Destination $fixedBackendStarterPath -Force
                }
                if (Test-Path -LiteralPath (Join-Path $backupRoot 'Caddyfile')) {
                    Copy-Item -LiteralPath (Join-Path $backupRoot 'Caddyfile') `
                        -Destination $fixedCaddyConfigPath -Force
                    Restart-Service -Name 'caddy' -Force
                    (Get-Service -Name 'caddy').WaitForStatus(
                        'Running', [TimeSpan]::FromSeconds(45)
                    )
                }
                if (Test-Path -LiteralPath (
                        Join-Path $backupRoot 'current-release.json'
                    )) {
                    Copy-Item -LiteralPath (
                        Join-Path $backupRoot 'current-release.json'
                    ) -Destination $fixedPointerPath -Force
                }
                Start-Service -Name 'LeanTPM.Backend'
                (Get-Service -Name 'LeanTPM.Backend').WaitForStatus(
                    'Running', [TimeSpan]::FromSeconds(45)
                )
            }
            if (Test-Path -LiteralPath $stageRoot) {
                Remove-Item -LiteralPath $stageRoot -Recurse -Force
            }
            if (Test-Path -LiteralPath $releaseRoot) {
                Remove-Item -LiteralPath $releaseRoot -Recurse -Force
            }
            if (Test-Path -LiteralPath $backupRoot -PathType Container) {
                [IO.File]::WriteAllText(
                    (Join-Path $backupRoot 'ROLLBACK.json'),
                    ([ordered]@{
                            status = 'ROLLED_BACK'
                            failedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                            releaseId = [string]$plan.releaseId
                            approvalId = [string]$plan.approvalId
                            error = [string]$failure.Exception.Message
                        } | ConvertTo-Json -Compress),
                    $strictUtf8
                )
            }
        }
        catch {
            throw "WORKGROUP_RAPID deployment failed and ROLLBACK failed: $($failure.Exception.Message); $($_.Exception.Message)"
        }
        throw $failure
    }
    finally {
        if ($null -ne $lock) { $lock.Dispose() }
    }
}
finally {
    $dbPassword = $null
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
