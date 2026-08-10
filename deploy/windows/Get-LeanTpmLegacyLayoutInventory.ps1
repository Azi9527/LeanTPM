[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [switch]$AllowMissingCanonicalRoots,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$maximumInspectedItems = 10000

function Resolve-InventoryRoot {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedLeaf,
        [switch]$AllowMissing
    )

    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$ExpectedLeaf root must be absolute" }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ([IO.Path]::GetFileName($resolved) -ine $ExpectedLeaf) {
        throw "Legacy inventory requires sibling $ExpectedLeaf roots"
    }
    if (Test-Path -LiteralPath $resolved) {
        $item = Get-Item -LiteralPath $resolved -Force
        if (-not $item.PSIsContainer -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$ExpectedLeaf must be a non-reparse directory"
        }
        return [pscustomobject]@{ Path = $item.FullName.TrimEnd('\', '/'); Exists = $true }
    }
    if (-not $AllowMissing) { throw "$ExpectedLeaf must be an existing non-reparse directory" }

    $parentPath = [IO.Directory]::GetParent($resolved).FullName.TrimEnd('\', '/')
    $parent = Get-Item -LiteralPath $parentPath -Force
    if (-not $parent.PSIsContainer -or
            ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$ExpectedLeaf parent must be an existing non-reparse directory"
    }
    return [pscustomobject]@{ Path = $resolved; Exists = $false }
}

function Get-PathInventory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$RelativePath,
        [switch]$PreserveExternal
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject][ordered]@{
            relativePath = $RelativePath
            path = $Path
            classification = 'ABSENT'
            exists = $false
            nonEmpty = $false
            itemCount = 0
            inspectionComplete = $true
            reparseDetected = $false
        }
    }

    $rootItem = Get-Item -LiteralPath $Path -Force
    $rootReparse = (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
    if (-not $rootItem.PSIsContainer) {
        return [pscustomobject][ordered]@{
            relativePath = $RelativePath
            path = $rootItem.FullName
            classification = if ($rootReparse) { 'UNSAFE_REPARSE' } else { 'IMPORT_REQUIRED' }
            exists = $true
            nonEmpty = $true
            itemCount = 1
            inspectionComplete = $true
            reparseDetected = $rootReparse
        }
    }
    if ($rootReparse) {
        return [pscustomobject][ordered]@{
            relativePath = $RelativePath
            path = $rootItem.FullName
            classification = 'UNSAFE_REPARSE'
            exists = $true
            nonEmpty = $true
            itemCount = 1
            inspectionComplete = $false
            reparseDetected = $true
        }
    }

    if ($PreserveExternal) {
        $directChildren = @(Get-ChildItem -LiteralPath $rootItem.FullName -Force -ErrorAction Stop)
        return [pscustomobject][ordered]@{
            relativePath = $RelativePath
            path = $rootItem.FullName
            classification = 'PRESERVE_EXTERNAL'
            exists = $true
            nonEmpty = ($directChildren.Count -gt 0)
            itemCount = $directChildren.Count
            inspectionComplete = ($directChildren.Count -eq 0)
            reparseDetected = $false
        }
    }

    $queue = [Collections.Generic.Queue[string]]::new()
    $queue.Enqueue($rootItem.FullName)
    $count = 0
    $reparseDetected = $false
    $inspectionComplete = $true
    while ($queue.Count -gt 0) {
        $directory = $queue.Dequeue()
        foreach ($child in @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop)) {
            $count++
            if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $reparseDetected = $true
                continue
            }
            if ($child.PSIsContainer) { $queue.Enqueue($child.FullName) }
            if ($count -ge $maximumInspectedItems) {
                $inspectionComplete = $false
                $queue.Clear()
                break
            }
        }
    }
    $classification = if ($reparseDetected) { 'UNSAFE_REPARSE' }
        elseif ($count -gt 0) { 'IMPORT_REQUIRED' }
        else { 'EMPTY' }
    return [pscustomobject][ordered]@{
        relativePath = $RelativePath
        path = $rootItem.FullName
        classification = $classification
        exists = $true
        nonEmpty = ($count -gt 0)
        itemCount = $count
        inspectionComplete = $inspectionComplete
        reparseDetected = $reparseDetected
    }
}

$installState = Resolve-InventoryRoot -Path $InstallRoot -ExpectedLeaf 'App' `
    -AllowMissing:$AllowMissingCanonicalRoots
$dataState = Resolve-InventoryRoot -Path $DataRoot -ExpectedLeaf 'Runtime' `
    -AllowMissing:$AllowMissingCanonicalRoots
$resolvedInstall = $installState.Path
$resolvedData = $dataState.Path
if ($installState.Exists -ne $dataState.Exists) {
    throw 'App and Runtime must either both exist or both be absent'
}
if ($AllowMissingCanonicalRoots -and -not $PlanOnly -and
        ($resolvedInstall -ine 'D:\LeanTPM\App' -or $resolvedData -ine 'D:\LeanTPM\Runtime')) {
    throw 'Executable missing-root inventory is restricted to D:\LeanTPM\App and D:\LeanTPM\Runtime'
}
$legacyRoot = [IO.Directory]::GetParent($resolvedInstall).FullName.TrimEnd('\', '/')
$dataParent = [IO.Directory]::GetParent($resolvedData).FullName.TrimEnd('\', '/')
if ($legacyRoot -ine $dataParent) {
    throw 'InstallRoot and DataRoot must be direct siblings under one legacy umbrella root'
}
if ([string]::IsNullOrWhiteSpace($legacyRoot) -or
        $legacyRoot -ieq [IO.Path]::GetPathRoot($legacyRoot).TrimEnd('\')) {
    throw 'The drive root cannot be used as the legacy umbrella root'
}

$entries = [Collections.Generic.List[object]]::new()
foreach ($relativePath in @(
        'packages',
        'releases',
        'current\backend',
        'current\frontend',
        'shared\config',
        'shared\uploads',
        'backups',
        'logs',
        'tools',
        'temp'
    )) {
    $entries.Add((Get-PathInventory `
        -Path (Join-Path $legacyRoot $relativePath) -RelativePath $relativePath))
}
$entries.Add((Get-PathInventory -Path (Join-Path $legacyRoot 'data') `
    -RelativePath 'data' -PreserveExternal))

$knownTopLevel = @('App', 'Runtime', 'packages', 'releases', 'current', 'shared', 'data',
    'backups', 'logs', 'tools', 'temp')
foreach ($unknown in @(Get-ChildItem -LiteralPath $legacyRoot -Force -ErrorAction Stop | Where-Object {
            $_.Name -notin $knownTopLevel
        } | Sort-Object Name)) {
    $entries.Add((Get-PathInventory -Path $unknown.FullName -RelativePath $unknown.Name))
}
foreach ($containerName in @('current', 'shared')) {
    $containerPath = Join-Path $legacyRoot $containerName
    if (-not (Test-Path -LiteralPath $containerPath -PathType Container)) { continue }
    $allowedChildren = if ($containerName -eq 'current') { @('backend', 'frontend') }
        else { @('config', 'uploads') }
    foreach ($unknown in @(Get-ChildItem -LiteralPath $containerPath -Force -ErrorAction Stop |
            Where-Object { $_.Name -notin $allowedChildren } | Sort-Object Name)) {
        $relativePath = "$containerName\$($unknown.Name)"
        $entries.Add((Get-PathInventory -Path $unknown.FullName -RelativePath $relativePath))
    }
}

$blocking = @($entries | Where-Object {
        $_.classification -in @('IMPORT_REQUIRED', 'UNSAFE_REPARSE')
    })
$report = [pscustomobject][ordered]@{
    schemaVersion = 1
    status = if ($blocking.Count -eq 0) { 'PASS' } else { 'IMPORT_REQUIRED' }
    readOnly = $true
    legacyRoot = $legacyRoot
    installRoot = $resolvedInstall
    dataRoot = $resolvedData
    canonicalRoots = [pscustomobject][ordered]@{
        installRootExists = [bool]$installState.Exists
        dataRootExists = [bool]$dataState.Exists
        bothAbsent = (-not $installState.Exists -and -not $dataState.Exists)
        missingAllowed = [bool]$AllowMissingCanonicalRoots
    }
    blockingCount = $blocking.Count
    entries = @($entries | Sort-Object relativePath)
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 6 -Compress }
else { $report | Format-List }
