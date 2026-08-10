[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedPlanSha256,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    $actual = @($Value.PSObject.Properties.Name)
    if ($actual.Count -ne $Expected.Count) { throw "$Label properties are not exact" }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing or duplicates property $name"
        }
    }
    foreach ($name in $actual) {
        if ($name -cnotin $Expected) { throw "$Label contains unknown property $name" }
    }
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

$resolvedPlan = (Resolve-Path -LiteralPath $PlanPath).Path
$planItem = Get-Item -LiteralPath $resolvedPlan -Force
if ($planItem.PSIsContainer -or
        ($planItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Legacy import plan must be a regular non-reparse file'
}
$stream = [IO.File]::Open($resolvedPlan, [IO.FileMode]::Open, [IO.FileAccess]::Read,
    [IO.FileShare]::Read)
try {
    if ($stream.Length -gt 8MB) { throw 'Legacy import plan exceeds the 8 MiB limit' }
    $bytes = New-Object byte[] ([int]$stream.Length)
    $offset = 0
    while ($offset -lt $bytes.Length) {
        $read = $stream.Read($bytes, $offset, $bytes.Length - $offset)
        if ($read -le 0) { throw 'Legacy import plan could not be read completely' }
        $offset += $read
    }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try { $text = $utf8.GetString($bytes) }
    catch { throw 'Legacy import plan must be strict UTF-8 JSON' }
    try { $plan = $text | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Legacy import plan is not valid JSON' }
} finally { $stream.Dispose() }

$topLevelProperties = @(
    'schemaVersion', 'status', 'planOnly', 'executable', 'trustSource',
    'approvalReadiness', 'hostFilesystemVerified', 'quarantineFilesystemVerified',
    'confirmationComplete', 'planId', 'legacyRoot', 'installRoot', 'dataRoot',
    'quarantineRoot', 'sourceInventory', 'backupReceipt', 'preservedExternalPaths',
    'entries', 'constraints', 'planSha256'
)
Assert-ExactProperties -Value $plan -Expected $topLevelProperties -Label 'Plan'
if ([int]$plan.schemaVersion -ne 1 -or [string]$plan.status -cne 'INPUT_REQUIRED' -or
        [bool]$plan.planOnly -ne $true -or [bool]$plan.executable -ne $false -or
        [string]$plan.trustSource -cne 'CALLER_BOUND_PLAN_ONLY' -or
        [string]$plan.approvalReadiness -cne
            'TRUSTED_BACKUP_HOST_BINDING_AND_EXECUTOR_REQUIRED' -or
        [bool]$plan.hostFilesystemVerified -ne $false -or
        [bool]$plan.quarantineFilesystemVerified -ne $false) {
    throw 'Plan status or non-executable trust boundary is invalid'
}
if ([string]$plan.planId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$plan.planId -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        [string]$plan.planId -match '\.$') {
    throw 'Plan ID is invalid for a Windows quarantine directory'
}

$core = [ordered]@{}
foreach ($property in $plan.PSObject.Properties) {
    if ($property.Name -cne 'planSha256') { $core[$property.Name] = $property.Value }
}
$coreJson = $core | ConvertTo-Json -Depth 10 -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $actualPlanSha256 = ([BitConverter]::ToString($sha.ComputeHash(
                [Text.Encoding]::UTF8.GetBytes($coreJson)))).Replace('-', '').ToLowerInvariant()
} finally { $sha.Dispose() }
if ([string]$plan.planSha256 -cne $ExpectedPlanSha256 -or
        $actualPlanSha256 -cne $ExpectedPlanSha256) {
    throw 'Plan SHA-256 does not match its exact canonical fields'
}

$legacyRoot = [IO.Path]::GetFullPath([string]$plan.legacyRoot).TrimEnd('\', '/')
$installRoot = [IO.Path]::GetFullPath([string]$plan.installRoot).TrimEnd('\', '/')
$dataRoot = [IO.Path]::GetFullPath([string]$plan.dataRoot).TrimEnd('\', '/')
if ([IO.Path]::GetFileName($installRoot) -cne 'App' -or
        [IO.Path]::GetFileName($dataRoot) -cne 'Runtime' -or
        [IO.Directory]::GetParent($installRoot).FullName.TrimEnd('\', '/') -ine $legacyRoot -or
        [IO.Directory]::GetParent($dataRoot).FullName.TrimEnd('\', '/') -ine $legacyRoot) {
    throw 'Plan roots do not describe the approved App and Runtime sibling layout'
}
$expectedQuarantineRoot = Resolve-ExactChildPath -Root $dataRoot `
    -RelativePath "staging\legacy-import\$($plan.planId)" -Label 'Quarantine root'
if ([IO.Path]::GetFullPath([string]$plan.quarantineRoot).TrimEnd('\', '/') -ine
        $expectedQuarantineRoot) {
    throw 'Plan quarantine root does not match its Runtime mapping'
}

Assert-ExactProperties -Value $plan.sourceInventory `
    -Expected @('path', 'sha256', 'status', 'blockingCount') -Label 'Source inventory'
if ([string]$plan.sourceInventory.sha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$plan.sourceInventory.status -cne 'IMPORT_REQUIRED' -or
        [int]$plan.sourceInventory.blockingCount -lt 1) {
    throw 'Source inventory binding is invalid'
}
if ($null -ne $plan.backupReceipt) {
    Assert-ExactProperties -Value $plan.backupReceipt -Expected @(
        'path', 'sha256', 'backupId', 'status', 'receiptBound',
        'cryptographicallyVerified', 'recoverabilityVerified'
    ) -Label 'Backup receipt'
    if ([string]$plan.backupReceipt.sha256 -notmatch '^[a-f0-9]{64}$' -or
            [string]$plan.backupReceipt.status -cne 'VALID' -or
            [bool]$plan.backupReceipt.receiptBound -ne $true -or
            [bool]$plan.backupReceipt.cryptographicallyVerified -ne $false -or
            [bool]$plan.backupReceipt.recoverabilityVerified -ne $false) {
        throw 'Backup receipt is only allowed as an untrusted caller-bound input'
    }
}

$preserved = @($plan.preservedExternalPaths)
if ($preserved.Count -ne 1) { throw 'Plan must preserve exactly one external data path' }
Assert-ExactProperties -Value $preserved[0] -Expected @('relativePath', 'path', 'action') `
    -Label 'Preserved data path'
$expectedDataPath = Resolve-ExactChildPath -Root $legacyRoot -RelativePath 'data' `
    -Label 'Preserved data path'
if ([string]$preserved[0].relativePath -cne 'data' -or
        [string]$preserved[0].action -cne 'PRESERVE_EXTERNAL' -or
        [IO.Path]::GetFullPath([string]$preserved[0].path).TrimEnd('\', '/') -ine
            $expectedDataPath) {
    throw 'Plan must preserve legacyRoot\data exactly'
}

$allowedImportPaths = @(
    'packages', 'releases', 'current\backend', 'current\frontend',
    'shared\config', 'shared\uploads', 'backups', 'logs', 'tools', 'temp'
)
$entries = @($plan.entries)
if ($entries.Count -lt 1 -or $entries.Count -ne [int]$plan.sourceInventory.blockingCount) {
    throw 'Plan entries must match the blocking inventory count'
}
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$allConfirmed = $true
foreach ($entry in $entries) {
    Assert-ExactProperties -Value $entry -Expected @(
        'relativePath', 'sourcePath', 'targetPath', 'action', 'confirmed',
        'targetMustBeAbsentOrEmpty', 'sourceDigest'
    ) -Label 'Import entry'
    $relativePath = [string]$entry.relativePath
    if ($relativePath -notin $allowedImportPaths -or -not $seen.Add($relativePath)) {
        throw "Import entry relative path is unknown or duplicated: $relativePath"
    }
    $expectedSource = Resolve-ExactChildPath -Root $legacyRoot -RelativePath $relativePath `
        -Label 'Import source'
    $expectedTarget = Resolve-ExactChildPath -Root $expectedQuarantineRoot `
        -RelativePath $relativePath -Label 'Import target'
    if ([IO.Path]::GetFullPath([string]$entry.sourcePath).TrimEnd('\', '/') -ine $expectedSource -or
            [IO.Path]::GetFullPath([string]$entry.targetPath).TrimEnd('\', '/') -ine $expectedTarget -or
            [string]$entry.action -cne 'COPY_TO_QUARANTINE' -or
            [bool]$entry.targetMustBeAbsentOrEmpty -ne $true) {
        throw "Import source or target mapping is invalid: $relativePath"
    }
    if (-not [bool]$entry.confirmed) { $allConfirmed = $false }
    Assert-ExactProperties -Value $entry.sourceDigest `
        -Expected @('algorithm', 'sha256', 'fileCount', 'totalBytes') -Label 'Source digest'
    if ([string]$entry.sourceDigest.algorithm -cne 'LEANTPM-DIRECTORY-SHA256-V1' -or
            [string]$entry.sourceDigest.sha256 -notmatch '^[a-f0-9]{64}$' -or
            [int]$entry.sourceDigest.fileCount -lt 0 -or
            [long]$entry.sourceDigest.totalBytes -lt 0) {
        throw "Import source digest is invalid: $relativePath"
    }
}
if ([bool]$plan.confirmationComplete -ne $allConfirmed) {
    throw 'Plan confirmationComplete does not match its entries'
}

Assert-ExactProperties -Value $plan.constraints -Expected @(
    'copyOnly', 'deleteSource', 'overwriteTarget', 'followReparsePoints',
    'preserveMySqlData', 'executionRequiresHostBootstrap',
    'executionRequiresSignedApproval', 'executionRequiresSourceRevalidation'
) -Label 'Constraints'
if ([bool]$plan.constraints.copyOnly -ne $true -or
        [bool]$plan.constraints.deleteSource -ne $false -or
        [bool]$plan.constraints.overwriteTarget -ne $false -or
        [bool]$plan.constraints.followReparsePoints -ne $false -or
        [bool]$plan.constraints.preserveMySqlData -ne $true -or
        [bool]$plan.constraints.executionRequiresHostBootstrap -ne $true -or
        [bool]$plan.constraints.executionRequiresSignedApproval -ne $true -or
        [bool]$plan.constraints.executionRequiresSourceRevalidation -ne $true) {
    throw 'Plan constraints do not remain fail closed'
}

$report = [pscustomobject][ordered]@{
    status = 'PASS'
    planSha256 = $actualPlanSha256
    executable = $false
    inputRequired = $true
    entryCount = $entries.Count
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }
