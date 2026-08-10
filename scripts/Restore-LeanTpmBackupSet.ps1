[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$BackupSetPath,
    [Parameter(Mandatory)][string]$ExpectedSourceDatabase,
    [Parameter(Mandatory)][string]$TargetDatabase,
    [Parameter(Mandatory)][string]$ConfirmTargetDatabase,
    [Parameter(Mandatory)][string]$RestoreRoot,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][ValidateSet('NON_PRODUCTION')][string]$EnvironmentKind,
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'leantpm_restore',
    [string]$MySqlPassword = $env:LEANTPM_RESTORE_DB_PASSWORD,
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$ExpectedServerUuid = '',
    [string]$BackupTrustConfigPath = '',
    [string]$ApprovalPlanPath = '',
    [string]$RequesterSignaturePath = '',
    [string]$ApproverSignaturePath = '',
    [switch]$AllowNonProductionHostRoots,
    [switch]$ConfirmRestoreTarget,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
foreach ($databaseName in @($ExpectedSourceDatabase, $TargetDatabase, $ConfirmTargetDatabase)) {
    if ($databaseName -notmatch '^[A-Za-z0-9_]+$') {
        throw 'Database names must contain only letters, numbers and underscore'
    }
}
if ($TargetDatabase -cne $ConfirmTargetDatabase) {
    throw 'ConfirmTargetDatabase must exactly match TargetDatabase'
}
if ($TargetDatabase -ceq $ExpectedSourceDatabase) {
    throw 'Restore must use a different, new target database'
}
$backupRoot = (Resolve-Path -LiteralPath $BackupSetPath).Path.TrimEnd('\', '/')
$restoreTarget = (Resolve-Path -LiteralPath $RestoreRoot).Path.TrimEnd('\', '/')
$resolvedInstall = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$resolvedData = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$hostEnvironmentKind = if ($AllowNonProductionHostRoots) { 'NON_PRODUCTION' } else {
    'PRODUCTION'
}
$rootPolicy = & (Join-Path $PSScriptRoot '..\deploy\windows\Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $resolvedInstall -DataRoot $resolvedData `
    -EnvironmentKind $hostEnvironmentKind -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionHostRoots `
    -ContainmentOnly:(-not $AllowNonProductionHostRoots) `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionHostRoots) {
    throw 'AllowNonProductionHostRoots cannot be used with the production root pair'
}
$verifiedSnapshotRoot = ''
$hostTrustConfigPath = Join-Path $resolvedData 'config\release-trust.json'
if (@(Get-ChildItem -LiteralPath $restoreTarget -Force).Count -ne 0) {
    throw 'RestoreRoot must be an existing empty isolated directory'
}

$candidateManifest = Get-Content -LiteralPath (Join-Path $backupRoot 'backup-manifest.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$candidateManifest.environmentKind -eq 'PRODUCTION' -and
        -not $isProductionRootPair) {
    throw 'PRODUCTION backup restore requires the verified host-owned production root pair'
}
$backupSignerThumbprint = ''
if (-not [string]::IsNullOrWhiteSpace($BackupTrustConfigPath)) {
    $backupTrust = Get-Content -LiteralPath (
        (Resolve-Path -LiteralPath $BackupTrustConfigPath).Path
    ) -Encoding utf8 -Raw | ConvertFrom-Json
    $backupSignerThumbprint = [string]$backupTrust.backupManifestCertificateThumbprint
}
if ([string]$candidateManifest.environmentKind -eq 'PRODUCTION' -and
        $backupSignerThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
    throw 'PRODUCTION restore requires a host-owned pinned backup trust configuration'
}
$verificationArguments = @{
    BackupSetPath = $backupRoot
    OutputFormat = 'Json'
}
if (-not [string]::IsNullOrWhiteSpace($backupSignerThumbprint)) {
    $verificationArguments.TrustedSignerThumbprint = $backupSignerThumbprint
}
$verificationJson = & (Join-Path $PSScriptRoot 'Test-LeanTpmBackupSet.ps1') `
    @verificationArguments
$verification = $verificationJson | ConvertFrom-Json
$manifest = $candidateManifest
if ([string]$manifest.database.name -cne $ExpectedSourceDatabase) {
    throw 'Backup source database does not match ExpectedSourceDatabase'
}
$databaseBackup = Join-Path $backupRoot 'database\database.sql'
if (-not (Test-Path -LiteralPath $databaseBackup -PathType Leaf)) {
    throw 'Backup set has no database/database.sql file'
}
$steps = @(
    'VERIFY_BACKUP', 'VERIFY_EMPTY_TARGET', 'RESTORE_DATABASE', 'RESTORE_ATTACHMENTS',
    'RESTORE_CONFIG_REFERENCES', 'VERIFY_FLYWAY', 'VERIFY_APPLICATION', 'AUDIT'
)
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    backupId = [string]$manifest.backupId
    backupVerification = [string]$verification.status
    sourceDatabase = $ExpectedSourceDatabase
    targetDatabase = $TargetDatabase
    expectedSchemaVersion = [int]$manifest.databaseSchemaVersion
    restoreRoot = $restoreTarget
    environmentKind = $EnvironmentKind
    hostLayoutSha256 = if ($isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
    steps = $steps
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmRestoreTarget) {
    throw 'ConfirmRestoreTarget is required before restoring any data'
}
if (-not (Test-Path -LiteralPath (Join-Path $backupRoot 'backup-manifest.p7s') -PathType Leaf)) {
    throw 'Executable restore requires a signed backup'
}
if ([string]::IsNullOrWhiteSpace($ExpectedServerUuid)) {
    throw 'ExpectedServerUuid is required for every MySQL restore target, including loopback'
}
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf)) {
    throw 'MySqlSslCaPath must identify the host-owned MySQL CA certificate'
}
$approvedBackupManifestSha256 = ''
$restoreApprovalNonce = ''
if ([string]$candidateManifest.environmentKind -eq 'PRODUCTION') {
    foreach ($approvalPath in @(
            $ApprovalPlanPath, $RequesterSignaturePath, $ApproverSignaturePath
        )) {
        if ([string]::IsNullOrWhiteSpace($approvalPath)) {
            throw 'PRODUCTION restore requires a signed two-person approval plan'
        }
    }
    $requestedTrustPath = if ([string]::IsNullOrWhiteSpace($BackupTrustConfigPath)) {
        $hostTrustConfigPath
    }
    else { $BackupTrustConfigPath }
    if (-not [IO.Path]::GetFullPath($requestedTrustPath).Equals(
            [IO.Path]::GetFullPath($hostTrustConfigPath),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'PRODUCTION restore approval must use the fixed host trust configuration'
    }
    $hostMySqlCaPath = Join-Path $resolvedData 'config\mysql-ca.pem'
    if (-not [IO.Path]::GetFullPath($resolvedSslCa).Equals(
            [IO.Path]::GetFullPath($hostMySqlCaPath),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'PRODUCTION restore must use the fixed host-owned MySQL CA path'
    }
    $hostTrust = Get-Content -LiteralPath (Resolve-Path -LiteralPath $hostTrustConfigPath).Path `
        -Encoding utf8 -Raw | ConvertFrom-Json
    $approvalPlanFile = (Resolve-Path -LiteralPath $ApprovalPlanPath).Path
    $approvalPlanBytes = [IO.File]::ReadAllBytes($approvalPlanFile)
    $approvalPlan = (New-Object Text.UTF8Encoding($false, $true)).
        GetString($approvalPlanBytes) | ConvertFrom-Json
    $expiresAt = [DateTimeOffset]::MinValue
    $candidateManifestPath = Join-Path $backupRoot 'backup-manifest.json'
    $approvedBackupManifestSha256 = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath $candidateManifestPath).Hash.ToLowerInvariant()
    if ([string]$approvalPlan.action -cne 'RESTORE_TO_ISOLATED_TARGET' -or
            [string]$approvalPlan.backupId -cne [string]$candidateManifest.backupId -or
            -not ([string]$approvalPlan.backupManifestSha256).Equals(
                $approvedBackupManifestSha256,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            [string]$approvalPlan.targetDatabase -cne $TargetDatabase -or
            [string]$approvalPlan.expectedServerUuid -cne $ExpectedServerUuid -or
            [string]$approvalPlan.mySqlHost -cne $MySqlHost -or
            [int]$approvalPlan.mySqlPort -ne $MySqlPort -or
            -not [IO.Path]::GetFullPath([string]$approvalPlan.restoreRoot).Equals(
                [IO.Path]::GetFullPath($restoreTarget),
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            [string]$approvalPlan.environmentId -cne [string]$hostTrust.environmentId -or
            [string]$approvalPlan.hostId -cne [string]$hostTrust.hostId -or
            [string]$approvalPlan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
            -not [DateTimeOffset]::TryParse([string]$approvalPlan.expiresAtUtc, [ref]$expiresAt) -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -gt [DateTimeOffset]::UtcNow.AddHours(24)) {
        throw 'PRODUCTION restore approval is expired or not bound to the exact backup/target/host'
    }
    $approvalReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmReleaseApproval.ps1') `
        -PlanPath $approvalPlanFile `
        -RequesterSignaturePath $RequesterSignaturePath `
        -ApproverSignaturePath $ApproverSignaturePath `
        -TrustConfigPath $hostTrustConfigPath `
        -OutputFormat Json | ConvertFrom-Json
    $approvalHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $loadedApprovalSha256 = [BitConverter]::ToString(
            $approvalHasher.ComputeHash($approvalPlanBytes)
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $approvalHasher.Dispose() }
    if (-not ([string]$approvalReport.planSha256).Equals(
            $loadedApprovalSha256,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'PRODUCTION restore approval differs from the loaded plan bytes'
    }
    $restoreApprovalNonce = [string]$approvalPlan.nonce
}
if (-not $PSCmdlet.ShouldProcess(
        "$MySqlHost`:$MySqlPort/$TargetDatabase and $restoreTarget",
        'Restore verified LeanTPM backup into an isolated new target'
    )) { return }
if ([string]$candidateManifest.environmentKind -eq 'PRODUCTION') {
    $restoreAuditDirectory = Join-Path $resolvedData 'audit'
    $null = New-Item -ItemType Directory -Path $restoreAuditDirectory -Force
    $restoreNoncePath = Join-Path $restoreAuditDirectory 'restore-nonces.jsonl'
    $previousHash = ('0' * 64)
    if (Test-Path -LiteralPath $restoreNoncePath -PathType Leaf) {
        $nonceAudit = & (Join-Path $PSScriptRoot 'Test-LeanTpmAuditLog.ps1') `
            -AuditPath $restoreNoncePath -OutputFormat Json | ConvertFrom-Json
        $previousHash = [string]$nonceAudit.finalHash
        $replayed = @(Get-Content -LiteralPath $restoreNoncePath -Encoding utf8 |
            ForEach-Object { $_ | ConvertFrom-Json } |
            Where-Object { [string]$_.nonce -ceq $restoreApprovalNonce })
        if ($replayed.Count -gt 0) {
            throw 'PRODUCTION restore approval nonce was already consumed'
        }
    }
    $nonceEvent = [ordered]@{
        schemaVersion = 1
        timestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        status = 'RESTORE_APPROVAL_CONSUMED'
        actor = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        message = 'Signed restore approval reserved before data mutation.'
        nonce = $restoreApprovalNonce
        backupId = [string]$candidateManifest.backupId
        targetDatabase = $TargetDatabase
        previousHash = $previousHash
    }
    $nonceBytes = [Text.Encoding]::UTF8.GetBytes(($nonceEvent | ConvertTo-Json -Compress))
    $nonceHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $nonceEvent.hash = [BitConverter]::ToString(
            $nonceHasher.ComputeHash($nonceBytes)
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $nonceHasher.Dispose() }
    Add-Content -LiteralPath $restoreNoncePath `
        -Value ($nonceEvent | ConvertTo-Json -Compress) -Encoding utf8
}

function Copy-VerifiedTree {
    param([string]$Source, [string]$Destination)

    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $Destination
        return
    }
    $null = New-Item -ItemType Directory -Path $Destination
    $sourceRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\', '/')
    foreach ($item in Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Restore source cannot contain a reparse point: $($item.FullName)"
        }
        $relative = $item.FullName.Substring($sourceRoot.Length + 1)
        $target = Join-Path $Destination $relative
        if ($item.PSIsContainer) { $null = New-Item -ItemType Directory -Path $target }
        else {
            $parent = Split-Path -Parent $target
            if (-not (Test-Path -LiteralPath $parent)) {
                $null = New-Item -ItemType Directory -Path $parent -Force
            }
            [System.IO.File]::Copy($item.FullName, $target, $false)
        }
    }
}

function Copy-ManifestComponent {
    param(
        [string]$SnapshotRoot,
        [string]$Destination,
        $VerifiedManifest,
        [string]$Component
    )

    $null = New-Item -ItemType Directory -Path $Destination
    $prefix = "$Component/"
    foreach ($file in @($VerifiedManifest.files | Where-Object {
                ([string]$_.path).StartsWith(
                    $prefix,
                    [StringComparison]::OrdinalIgnoreCase
                )
            })) {
        $relative = ([string]$file.path).Substring($prefix.Length)
        $source = Join-Path $SnapshotRoot ([string]$file.path).Replace('/', '\')
        $target = Join-Path $Destination $relative.Replace('/', '\')
        $parent = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
            $null = New-Item -ItemType Directory -Path $parent -Force
        }
        [IO.File]::Copy($source, $target, $false)
    }
}

$restoreStartedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
$snapshotLocks = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
try {
    $verifiedSnapshotRoot = Join-Path $restoreTarget (
        '.verified-backup-' + [Guid]::NewGuid().ToString('N')
    )
    Copy-VerifiedTree $backupRoot $verifiedSnapshotRoot
    foreach ($snapshotFile in Get-ChildItem -LiteralPath $verifiedSnapshotRoot -Recurse -File) {
        $snapshotLocks.Add((New-Object IO.FileStream(
                    $snapshotFile.FullName,
                    [IO.FileMode]::Open,
                    [IO.FileAccess]::Read,
                    [IO.FileShare]::Read
                )))
    }
    $snapshotManifestPath = Join-Path $verifiedSnapshotRoot 'backup-manifest.json'
    $manifest = Get-Content -LiteralPath $snapshotManifestPath -Encoding utf8 -Raw |
        ConvertFrom-Json
    $snapshotSignaturePath = Join-Path $verifiedSnapshotRoot 'backup-manifest.p7s'
    $snapshotVerificationArguments = @{
        BackupSetPath = $verifiedSnapshotRoot
        OutputFormat = 'Json'
    }
    if ([string]$manifest.environmentKind -eq 'PRODUCTION' -or
            (Test-Path -LiteralPath $snapshotSignaturePath -PathType Leaf)) {
        $requestedTrustConfigPath = if ([string]::IsNullOrWhiteSpace(
                $BackupTrustConfigPath
            )) {
            $hostTrustConfigPath
        }
        else { $BackupTrustConfigPath }
        if (-not [IO.Path]::GetFullPath($hostTrustConfigPath).Equals(
                [IO.Path]::GetFullPath($requestedTrustConfigPath),
                [StringComparison]::OrdinalIgnoreCase
            ) -or -not (Test-Path -LiteralPath $hostTrustConfigPath -PathType Leaf)) {
            throw 'Signed restore requires the fixed host-owned backup trust configuration'
        }
        $hostTrust = Get-Content -LiteralPath $hostTrustConfigPath -Encoding utf8 -Raw |
            ConvertFrom-Json
        $snapshotVerificationArguments.TrustedSignerThumbprint =
            [string]$hostTrust.backupManifestCertificateThumbprint
    }
    $verification = & (Join-Path $PSScriptRoot 'Test-LeanTpmBackupSet.ps1') `
        @snapshotVerificationArguments | ConvertFrom-Json
    if ([string]$verification.status -cne 'PASS') {
        throw 'Verified backup snapshot failed its independent integrity check'
    }
    if ([string]$manifest.environmentKind -eq 'PRODUCTION' -and
            -not (Get-FileHash -Algorithm SHA256 -LiteralPath $snapshotManifestPath).
                Hash.Equals(
                    $approvedBackupManifestSha256,
                    [StringComparison]::OrdinalIgnoreCase
                )) {
        throw 'Verified backup snapshot differs from the exact approved production manifest'
    }
    if ([string]$manifest.database.name -cne $ExpectedSourceDatabase) {
        throw 'Verified snapshot source database does not match ExpectedSourceDatabase'
    }
    $databaseBackup = Join-Path $verifiedSnapshotRoot 'database\database.sql'
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $MySqlPassword
        $actualUuid = & mysql.exe "--host=$MySqlHost" "--port=$MySqlPort" `
            "--user=$MySqlUser" '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$resolvedSslCa" `
            --batch --skip-column-names `
            -e 'SELECT @@server_uuid;'
        if ($LASTEXITCODE -ne 0 -or
                -not ([string]$actualUuid).Trim().Equals(
                    $ExpectedServerUuid,
                    [System.StringComparison]::OrdinalIgnoreCase
                )) {
            throw 'MySQL restore target server UUID does not match ExpectedServerUuid'
        }
        & (Join-Path $PSScriptRoot 'restore-mysql.ps1') `
            -BackupFile $databaseBackup `
            -Database $TargetDatabase `
            -ConfirmDatabase $ConfirmTargetDatabase `
            -MySqlHost $MySqlHost `
            -MySqlPort $MySqlPort `
            -MySqlUser $MySqlUser `
            -MySqlPassword $MySqlPassword `
            -MySqlSslCaPath $resolvedSslCa

        foreach ($component in @('attachments', 'config', 'release', 'pointers', 'protection')) {
            Copy-ManifestComponent `
                -SnapshotRoot $verifiedSnapshotRoot `
                -Destination (Join-Path $restoreTarget $component) `
                -VerifiedManifest $manifest `
                -Component $component
        }
        $schemaVersion = & mysql.exe "--host=$MySqlHost" "--port=$MySqlPort" `
            "--user=$MySqlUser" '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$resolvedSslCa" `
            --batch --skip-column-names `
            "--database=$TargetDatabase" `
            -e 'SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) FROM flyway_schema_history WHERE success = 1;'
        if ($LASTEXITCODE -ne 0 -or [int]$schemaVersion -ne [int]$manifest.databaseSchemaVersion) {
            throw 'Restored Flyway schema version does not match the backup manifest'
        }
        & (Join-Path $PSScriptRoot 'Test-LeanTpmRestoredApplication.ps1') `
            -TargetDatabase $TargetDatabase `
            -RestoreRoot $restoreTarget `
            -ExpectedSchemaVersion ([int]$manifest.databaseSchemaVersion) `
            -MySqlHost $MySqlHost `
            -MySqlPort $MySqlPort `
            -MySqlUser $MySqlUser `
            -MySqlPassword $MySqlPassword `
            -MySqlSslCaPath $resolvedSslCa
        if ($LASTEXITCODE -ne 0) { throw 'Application restore verification failed' }
    }
    finally {
        $env:MYSQL_PWD = $previousPassword
    }
    $auditPath = Join-Path $restoreTarget 'restore-audit.json'
    $audit = [ordered]@{
        schemaVersion = 1
        status = 'DATA_RESTORED_PENDING_APPLICATION_E2E'
        backupId = [string]$manifest.backupId
        targetDatabase = $TargetDatabase
        startedAtUtc = $restoreStartedAtUtc
        completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    }
    [System.IO.File]::WriteAllText(
        $auditPath,
        ($audit | ConvertTo-Json -Depth 5),
        (New-Object System.Text.UTF8Encoding($false))
    )
    $report.status = 'DATA_RESTORED_PENDING_APPLICATION_E2E'
    $report | Add-Member -NotePropertyName auditPath -NotePropertyValue $auditPath
}
catch {
    $failure = $_
    $invalidPath = Join-Path $restoreTarget 'RESTORE_INVALID.json'
    $invalid = [ordered]@{
        schemaVersion = 1
        status = 'RESTORE_FAILED'
        backupId = [string]$manifest.backupId
        targetDatabase = $TargetDatabase
        startedAtUtc = $restoreStartedAtUtc
        failedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        databaseMayContainPartialData = $true
        action = 'Quarantine this restore root and target database; investigate before exact cleanup.'
    }
    [System.IO.File]::WriteAllText(
        $invalidPath,
        ($invalid | ConvertTo-Json -Depth 5),
        (New-Object System.Text.UTF8Encoding($false))
    )
    throw $failure
}
finally {
    foreach ($snapshotLock in $snapshotLocks) { $snapshotLock.Dispose() }
    if (-not [string]::IsNullOrWhiteSpace($verifiedSnapshotRoot) -and
            (Test-Path -LiteralPath $verifiedSnapshotRoot -PathType Container)) {
        [System.IO.Directory]::Delete($verifiedSnapshotRoot, $true)
    }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
