[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ToolkitRoot,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidateSet('Json')][string]$OutputFormat = 'Json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$strictUtf8 = New-Object Text.UTF8Encoding($false, $true)

function Get-FixedDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be absolute"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if (-not $item.PSIsContainer -or
        (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse directory"
    }
    return $item.FullName.TrimEnd('\', '/')
}

function Assert-ContainedRegularFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
        (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse file"
    }
    $prefix = $Root + [IO.Path]::DirectorySeparatorChar
    if (-not $item.FullName.StartsWith(
            $prefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Label escaped the toolkit root"
    }
    $current = $item.Directory
    $reachedRoot = $false
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label contains a reparse ancestor"
        }
        if ($current.FullName.TrimEnd('\', '/').Equals(
                $Root,
                [StringComparison]::OrdinalIgnoreCase
            )) {
            $reachedRoot = $true
            break
        }
        $current = $current.Parent
    }
    if (-not $reachedRoot) {
        throw "$Label did not resolve beneath the toolkit root"
    }
    return $item.FullName
}

$root = Get-FixedDirectory -Path $ToolkitRoot -Label 'toolkit root'
$scriptsRoot = Get-FixedDirectory -Path (Join-Path $root 'scripts') `
    -Label 'toolkit scripts root'
$windowsRoot = Get-FixedDirectory -Path (Join-Path $root 'deploy\windows') `
    -Label 'toolkit Windows root'
$releaseRoot = Get-FixedDirectory -Path (Join-Path $root 'release') `
    -Label 'toolkit release root'
$expectedOutputPath = Join-Path $releaseRoot 'release-agent-toolkit-lock.json'
$outputFull = [IO.Path]::GetFullPath($OutputPath)
if (-not $outputFull.Equals(
        $expectedOutputPath,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Toolkit lock output path must use the fixed release location'
}
if (Test-Path -LiteralPath $outputFull) {
    throw 'Toolkit lock output already exists; refusing to overwrite it'
}

$relativePaths = New-Object 'System.Collections.Generic.List[string]'
$sourceByRelativePath = New-Object `
    'System.Collections.Generic.Dictionary[string,string]' `
    ([StringComparer]::Ordinal)
foreach ($scanRoot in @($scriptsRoot, $windowsRoot)) {
    foreach ($file in @(Get-ChildItem -LiteralPath $scanRoot -Recurse -File `
            -Filter '*.ps1' -Force)) {
        $fixed = Assert-ContainedRegularFile -Root $root -Path $file.FullName `
            -Label 'toolkit script'
        $relative = $fixed.Substring($root.Length + 1).Replace('\', '/')
        if ($sourceByRelativePath.ContainsKey($relative)) {
            throw 'Toolkit contains a duplicate script path'
        }
        $relativePaths.Add($relative)
        $sourceByRelativePath.Add($relative, $fixed)
    }
}
$relativePaths.Sort([StringComparer]::Ordinal)
if ($relativePaths.Count -lt 1 -or $relativePaths.Count -gt 256 -or
    -not $sourceByRelativePath.ContainsKey(
        'scripts/Invoke-LeanTpmDeployment.ps1'
    )) {
    throw 'Toolkit script set is incomplete or too large'
}

$streams = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
try {
    $files = @()
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($relative in $relativePaths) {
            $stream = New-Object IO.FileStream(
                $sourceByRelativePath[$relative],
                [IO.FileMode]::Open,
                [IO.FileAccess]::Read,
                [IO.FileShare]::Read
            )
            $streams.Add($stream)
            $sha256 = ([BitConverter]::ToString(
                $algorithm.ComputeHash($stream)
            )).Replace('-', '').ToLowerInvariant()
            $algorithm.Initialize()
            $files += [ordered]@{
                path = $relative
                sha256 = $sha256
            }
        }
    }
    finally {
        $algorithm.Dispose()
    }

    $lock = [ordered]@{
        executorRelativePath = 'scripts/Invoke-LeanTpmDeployment.ps1'
        files = $files
        schemaVersion = 1
        toolkitId = 'leantpm-release-agent-toolkit'
    }
    $json = $lock | ConvertTo-Json -Depth 6 -Compress
    $bytes = $strictUtf8.GetBytes($json)
    $outputStream = New-Object IO.FileStream(
        $outputFull,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $outputStream.Write($bytes, 0, $bytes.Length)
        $outputStream.Flush($true)
    }
    finally {
        $outputStream.Dispose()
    }

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $lockSha256 = ([BitConverter]::ToString(
            $sha.ComputeHash($bytes)
        )).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
    [ordered]@{
        status = 'CREATED'
        path = $outputFull
        bytes = $bytes.Length
        fileCount = $files.Count
        lockSha256 = $lockSha256
    } | ConvertTo-Json -Depth 4 -Compress
}
catch {
    if (Test-Path -LiteralPath $outputFull) {
        $created = Get-Item -LiteralPath $outputFull -Force
        if ($created.Length -eq 0) {
            Remove-Item -LiteralPath $outputFull -Force
        }
    }
    throw
}
finally {
    foreach ($stream in $streams) {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
