[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InventoryPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedInventorySha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9._-]{2,127}$')][string]$PlanId,
    [string]$BackupReceiptPath = '',
    [ValidatePattern('^$|^[a-f0-9]{64}$')][string]$ExpectedBackupReceiptSha256 = '',
    [string]$ConfirmedRelativePath = '',
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

if (-not $PlanOnly) {
    throw 'Legacy import planning is PlanOnly; executable import requires a separate signed ceremony'
}
if ($PlanId.EndsWith('.', [StringComparison]::Ordinal) -or
        $PlanId -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)') {
    throw 'Plan ID cannot use a Windows device name or trailing dot'
}

function Read-LockedUtf8JsonSnapshot {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $item = Get-Item -LiteralPath $resolved -Force
    if ($item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label must be a regular non-reparse file"
    }
    $stream = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        [IO.FileShare]::Read)
    try {
        if ($stream.Length -gt 8MB) { throw "$Label exceeds the 8 MiB planning limit" }
        $bytes = New-Object byte[] ([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $read = $stream.Read($bytes, $offset, $bytes.Length - $offset)
            if ($read -le 0) { throw "$Label could not be read completely" }
            $offset += $read
        }
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $digest = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        } finally { $sha.Dispose() }
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        try { $text = $utf8.GetString($bytes) }
        catch { throw "$Label must be strict UTF-8 JSON" }
        try { $value = $text | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "$Label is not valid JSON" }
        return [pscustomobject]@{ Path = $resolved; Sha256 = $digest; Value = $value }
    } finally { $stream.Dispose() }
}

function Resolve-ExactChildPath {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
            [IO.Path]::IsPathRooted($RelativePath) -or
            $RelativePath -match '(^|[\\/])\.\.([\\/]|$)' -or
            $RelativePath.IndexOf(':') -ge 0) {
        throw "$Label contains an unsafe relative path"
    }
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $childFull = [IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath)).TrimEnd('\', '/')
    if (-not $childFull.StartsWith($rootFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes its approved root"
    }
    return $childFull
}

function Get-DirectoryDigest {
    param([Parameter(Mandatory)][string]$Path)

    $tool = Join-Path ([IO.Directory]::GetParent($PSScriptRoot).Parent.FullName) `
        'scripts\Get-LeanTpmDirectoryDigest.ps1'
    $json = & $tool -DirectoryPath $Path -OutputFormat Json
    $digest = $json | ConvertFrom-Json -ErrorAction Stop
    if ([string]$digest.status -cne 'PASS' -or
            [string]$digest.algorithm -cne 'LEANTPM-DIRECTORY-SHA256-V1' -or
            [string]$digest.digest -notmatch '^[a-f0-9]{64}$') {
        throw "Directory digest is invalid for $Path"
    }
    return $digest
}

function Convert-InventoryToCanonicalJson {
    param([Parameter(Mandatory)]$Inventory)

    $entries = @($Inventory.entries | Sort-Object relativePath | ForEach-Object {
            [pscustomobject][ordered]@{
                relativePath = [string]$_.relativePath
                path = [string]$_.path
                classification = [string]$_.classification
                exists = [bool]$_.exists
                nonEmpty = [bool]$_.nonEmpty
                itemCount = [int]$_.itemCount
                inspectionComplete = [bool]$_.inspectionComplete
                reparseDetected = [bool]$_.reparseDetected
            }
        })
    return ([pscustomobject][ordered]@{
            schemaVersion = [int]$Inventory.schemaVersion
            status = [string]$Inventory.status
            readOnly = [bool]$Inventory.readOnly
            legacyRoot = [string]$Inventory.legacyRoot
            installRoot = [string]$Inventory.installRoot
            dataRoot = [string]$Inventory.dataRoot
            canonicalRoots = [pscustomobject][ordered]@{
                installRootExists = [bool]$Inventory.canonicalRoots.installRootExists
                dataRootExists = [bool]$Inventory.canonicalRoots.dataRootExists
                bothAbsent = [bool]$Inventory.canonicalRoots.bothAbsent
                missingAllowed = [bool]$Inventory.canonicalRoots.missingAllowed
            }
            blockingCount = [int]$Inventory.blockingCount
            entries = $entries
        } | ConvertTo-Json -Depth 8 -Compress)
}

$inventorySnapshot = Read-LockedUtf8JsonSnapshot -Path $InventoryPath -Label 'Inventory'
if ($inventorySnapshot.Sha256 -cne $ExpectedInventorySha256) {
    throw 'Inventory SHA-256 does not match the approved digest'
}
$inventory = $inventorySnapshot.Value
if ([int]$inventory.schemaVersion -ne 1 -or
        [string]$inventory.status -cne 'IMPORT_REQUIRED' -or
        [bool]$inventory.readOnly -ne $true -or
        [int]$inventory.blockingCount -lt 1) {
    throw 'Inventory must be a read-only IMPORT_REQUIRED report'
}

$legacyRoot = [IO.Path]::GetFullPath([string]$inventory.legacyRoot).TrimEnd('\', '/')
$installRoot = [IO.Path]::GetFullPath([string]$inventory.installRoot).TrimEnd('\', '/')
$dataRoot = [IO.Path]::GetFullPath([string]$inventory.dataRoot).TrimEnd('\', '/')
if ([IO.Path]::GetFileName($installRoot) -cne 'App' -or
        [IO.Path]::GetFileName($dataRoot) -cne 'Runtime' -or
        [IO.Directory]::GetParent($installRoot).FullName.TrimEnd('\', '/') -ine $legacyRoot -or
        [IO.Directory]::GetParent($dataRoot).FullName.TrimEnd('\', '/') -ine $legacyRoot) {
    throw 'Inventory roots do not describe the approved App and Runtime sibling layout'
}
$seenInventoryPaths = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)
foreach ($inventoryEntry in @($inventory.entries)) {
    if (-not $seenInventoryPaths.Add([string]$inventoryEntry.relativePath)) {
        throw "Duplicate legacy inventory path: $($inventoryEntry.relativePath)"
    }
}
$inventoryTool = Join-Path $PSScriptRoot 'Get-LeanTpmLegacyLayoutInventory.ps1'
$allowMissingCanonicalRoots = [bool]$inventory.canonicalRoots.missingAllowed
$liveInventoryJson = & $inventoryTool -InstallRoot $installRoot -DataRoot $dataRoot `
    -AllowMissingCanonicalRoots:$allowMissingCanonicalRoots -PlanOnly -OutputFormat Json
$liveInventory = $liveInventoryJson | ConvertFrom-Json -ErrorAction Stop
if ((Convert-InventoryToCanonicalJson -Inventory $inventory) -cne
        (Convert-InventoryToCanonicalJson -Inventory $liveInventory)) {
    throw 'Inventory is no longer current; rerun the read-only legacy inventory'
}

$allowedImportPaths = @(
    'packages', 'releases', 'current\backend', 'current\frontend',
    'shared\config', 'shared\uploads', 'backups', 'logs', 'tools', 'temp'
)
$confirmed = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$confirmationValues = if ([string]::IsNullOrWhiteSpace($ConfirmedRelativePath)) { @() }
    else { @($ConfirmedRelativePath.Split(',') | ForEach-Object { $_.Trim() }) }
foreach ($relativePath in $confirmationValues) {
    if ($relativePath -notin $allowedImportPaths) {
        throw "Confirmation is not an approved legacy path: $relativePath"
    }
    if (-not $confirmed.Add($relativePath)) {
        throw "Duplicate legacy path confirmation: $relativePath"
    }
}

$preservedData = @($inventory.entries | Where-Object { [string]$_.relativePath -ceq 'data' })
if ($preservedData.Count -ne 1 -or
        [string]$preservedData[0].classification -cne 'PRESERVE_EXTERNAL') {
    throw 'Inventory must preserve the legacy data path as external MySQL state'
}
$expectedPreservedDataPath = Resolve-ExactChildPath -Root $legacyRoot -RelativePath 'data' `
    -Label 'Preserved MySQL data path'
if ([IO.Path]::GetFullPath([string]$preservedData[0].path).TrimEnd('\', '/') -ine
        $expectedPreservedDataPath) {
    throw 'Inventory data path must resolve exactly to legacyRoot\data'
}
$preservedDataItem = Get-Item -LiteralPath $expectedPreservedDataPath -Force
if (-not $preservedDataItem.PSIsContainer -or
        ($preservedDataItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Preserved MySQL data path must be a non-reparse directory'
}

$blockingEntries = @($inventory.entries | Where-Object {
        [string]$_.classification -in @('IMPORT_REQUIRED', 'UNSAFE_REPARSE')
    })
if ($blockingEntries.Count -ne [int]$inventory.blockingCount) {
    throw 'Inventory blockingCount does not match its entries'
}
$quarantineRoot = Join-Path $dataRoot "staging\legacy-import\$PlanId"
if (Test-Path -LiteralPath $quarantineRoot) {
    throw "Legacy import quarantine root must be absent; overwrite is forbidden: $quarantineRoot"
}
$plannedEntries = [Collections.Generic.List[object]]::new()
$seenBlockingPaths = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)
foreach ($entry in @($blockingEntries | Sort-Object relativePath)) {
    $relativePath = [string]$entry.relativePath
    if (-not $seenBlockingPaths.Add($relativePath)) {
        throw "Duplicate legacy inventory path: $relativePath"
    }
    if ($relativePath -notin $allowedImportPaths) {
        throw "Legacy path has no approved import mapping: $relativePath"
    }
    if ([string]$entry.classification -cne 'IMPORT_REQUIRED' -or
            -not [bool]$entry.exists -or -not [bool]$entry.nonEmpty -or
            -not [bool]$entry.inspectionComplete -or [bool]$entry.reparseDetected) {
        throw "Legacy path is unsafe or incompletely inspected: $relativePath"
    }
    $sourcePath = Resolve-ExactChildPath -Root $legacyRoot -RelativePath $relativePath `
        -Label 'Legacy source path'
    if ([IO.Path]::GetFullPath([string]$entry.path).TrimEnd('\', '/') -ine $sourcePath) {
        throw "Inventory source path does not match its relative path: $relativePath"
    }
    $sourceItem = Get-Item -LiteralPath $sourcePath -Force
    if (-not $sourceItem.PSIsContainer -or
            ($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Legacy source must be a non-reparse directory: $relativePath"
    }
    $targetPath = Resolve-ExactChildPath -Root $quarantineRoot -RelativePath $relativePath `
        -Label 'Legacy quarantine target'
    if (Test-Path -LiteralPath $targetPath) {
        $targetItem = Get-Item -LiteralPath $targetPath -Force
        if (-not $targetItem.PSIsContainer -or
                ($targetItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                @(Get-ChildItem -LiteralPath $targetPath -Force).Count -gt 0) {
            throw "Legacy import target must be absent or empty; overwrite is forbidden: $targetPath"
        }
    }
    $sourceDigest = Get-DirectoryDigest -Path $sourcePath
    $plannedEntries.Add([pscustomobject][ordered]@{
            relativePath = $relativePath
            sourcePath = $sourcePath
            targetPath = $targetPath
            action = 'COPY_TO_QUARANTINE'
            confirmed = $confirmed.Contains($relativePath)
            targetMustBeAbsentOrEmpty = $true
            sourceDigest = [pscustomobject][ordered]@{
                algorithm = [string]$sourceDigest.algorithm
                sha256 = [string]$sourceDigest.digest
                fileCount = [int]$sourceDigest.fileCount
                totalBytes = [long]$sourceDigest.totalBytes
            }
        })
}
foreach ($relativePath in $confirmed) {
    if (-not $seenBlockingPaths.Contains($relativePath)) {
        throw "Confirmed path is not present as a blocking inventory entry: $relativePath"
    }
}

$backupReceipt = $null
if (-not [string]::IsNullOrWhiteSpace($BackupReceiptPath) -or
        -not [string]::IsNullOrWhiteSpace($ExpectedBackupReceiptSha256)) {
    if ([string]::IsNullOrWhiteSpace($BackupReceiptPath) -or
            [string]::IsNullOrWhiteSpace($ExpectedBackupReceiptSha256)) {
        throw 'Backup receipt path and expected digest must be supplied together'
    }
    $backupSnapshot = Read-LockedUtf8JsonSnapshot -Path $BackupReceiptPath -Label 'Backup receipt'
    if ($backupSnapshot.Sha256 -cne $ExpectedBackupReceiptSha256) {
        throw 'Backup receipt SHA-256 does not match the approved digest'
    }
    if ([int]$backupSnapshot.Value.schemaVersion -ne 1 -or
            [string]$backupSnapshot.Value.status -cne 'VALID' -or
            [string]$backupSnapshot.Value.backupId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$') {
        throw 'Backup receipt must bind a VALID backup manifest'
    }
    $backupReceipt = [pscustomobject][ordered]@{
        path = $backupSnapshot.Path
        sha256 = $backupSnapshot.Sha256
        backupId = [string]$backupSnapshot.Value.backupId
        status = 'VALID'
        receiptBound = $true
        cryptographicallyVerified = $false
        recoverabilityVerified = $false
    }
}

$allConfirmed = @($plannedEntries | Where-Object { -not $_.confirmed }).Count -eq 0
$core = [pscustomobject][ordered]@{
    schemaVersion = 1
    status = 'INPUT_REQUIRED'
    planOnly = $true
    executable = $false
    trustSource = 'CALLER_BOUND_PLAN_ONLY'
    approvalReadiness = 'TRUSTED_BACKUP_HOST_BINDING_AND_EXECUTOR_REQUIRED'
    hostFilesystemVerified = $false
    quarantineFilesystemVerified = $false
    confirmationComplete = $allConfirmed
    planId = $PlanId
    legacyRoot = $legacyRoot
    installRoot = $installRoot
    dataRoot = $dataRoot
    quarantineRoot = $quarantineRoot
    sourceInventory = [pscustomobject][ordered]@{
        path = $inventorySnapshot.Path
        sha256 = $inventorySnapshot.Sha256
        status = 'IMPORT_REQUIRED'
        blockingCount = [int]$inventory.blockingCount
    }
    backupReceipt = $backupReceipt
    preservedExternalPaths = @([pscustomobject][ordered]@{
            relativePath = 'data'
            path = $expectedPreservedDataPath
            action = 'PRESERVE_EXTERNAL'
        })
    entries = @($plannedEntries)
    constraints = [pscustomobject][ordered]@{
        copyOnly = $true
        deleteSource = $false
        overwriteTarget = $false
        followReparsePoints = $false
        preserveMySqlData = $true
        executionRequiresHostBootstrap = $true
        executionRequiresSignedApproval = $true
        executionRequiresSourceRevalidation = $true
    }
}
$coreJson = $core | ConvertTo-Json -Depth 10 -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $planDigest = ([BitConverter]::ToString($sha.ComputeHash(
                [Text.Encoding]::UTF8.GetBytes($coreJson)))).Replace('-', '').ToLowerInvariant()
} finally { $sha.Dispose() }
$report = [pscustomobject][ordered]@{}
foreach ($property in $core.PSObject.Properties) {
    $report | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
}
$report | Add-Member -NotePropertyName planSha256 -NotePropertyValue $planDigest

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 10 -Compress }
else { $report | Format-List }
