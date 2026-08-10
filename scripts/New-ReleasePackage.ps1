[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ManifestPath,
    [Parameter(Mandatory)]
    [string]$PayloadRoot,
    [Parameter(Mandatory)]
    [string]$OutputPath,
    [switch]$AllowUnsignedTestManifest,
    [string]$TrustedCertificateThumbprint = '',
    [ValidateSet('Text', 'Json')]
    [string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedManifest = (Resolve-Path -LiteralPath $ManifestPath).Path
$resolvedPayload = (Resolve-Path -LiteralPath $PayloadRoot).Path
$manifest = Get-Content -LiteralPath $resolvedManifest -Encoding utf8 -Raw | ConvertFrom-Json
$schemaPath = Join-Path $repositoryRoot 'release\release-manifest.schema.json'

$validationArguments = @{
    ManifestPath = $resolvedManifest
    PackageRoot = $resolvedPayload
    OutputFormat = 'Json'
    AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
}
if (-not [string]::IsNullOrWhiteSpace($TrustedCertificateThumbprint)) {
    $validationArguments.TrustedCertificateThumbprint = $TrustedCertificateThumbprint
}
$validationOutput = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') @validationArguments
$null = $validationOutput | ConvertFrom-Json

$outputParent = Split-Path -Parent $OutputPath
if ([string]::IsNullOrWhiteSpace($outputParent)) {
    $outputParent = (Get-Location).Path
}
$resolvedParent = (Resolve-Path -LiteralPath (
    New-Item -ItemType Directory -Path $outputParent -Force
)).Path
$resolvedOutput = Join-Path $resolvedParent (Split-Path -Leaf $OutputPath)
if (Test-Path -LiteralPath $resolvedOutput) {
    throw "Release package already exists and will not be overwritten: $resolvedOutput"
}
if ([System.IO.Path]::GetExtension($resolvedOutput) -cne '.zip') {
    throw 'Release package output must use the .zip extension'
}

Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop

$timestamp = [DateTimeOffset]::FromUnixTimeSeconds([int64]$manifest.source.sourceDateEpoch)
$zipMinimum = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
if ($timestamp -lt $zipMinimum) {
    $timestamp = $zipMinimum
}

$entries = [System.Collections.Generic.List[object]]::new()
$entries.Add([pscustomobject]@{ Name = 'release-manifest.json'; Source = $resolvedManifest })
$entries.Add([pscustomobject]@{ Name = 'release-manifest.schema.json'; Source = $schemaPath })
if (-not [string]::IsNullOrWhiteSpace([string]$manifest.signing.signaturePath)) {
    $signatureSource = Join-Path (Split-Path -Parent $resolvedManifest) `
        ([string]$manifest.signing.signaturePath).Replace('/', '\')
    $entries.Add([pscustomobject]@{
            Name = ([string]$manifest.signing.signaturePath).Replace('\', '/')
            Source = (Resolve-Path -LiteralPath $signatureSource).Path
        })
}
Get-ChildItem -LiteralPath $resolvedPayload -Recurse -File -Force | ForEach-Object {
    if (($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Release payload cannot contain a reparse point: $($_.FullName)"
    }
    $relative = $_.FullName.Substring($resolvedPayload.TrimEnd('\', '/').Length + 1).Replace('\', '/')
    $entries.Add([pscustomobject]@{ Name = "payload/$relative"; Source = $_.FullName })
}

$stream = $null
$archive = $null
try {
    $stream = New-Object System.IO.FileStream(
        $resolvedOutput,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    $archive = New-Object System.IO.Compression.ZipArchive(
        $stream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    foreach ($item in @($entries | Sort-Object Name)) {
        $entry = $archive.CreateEntry(
            [string]$item.Name,
            [System.IO.Compression.CompressionLevel]::Optimal
        )
        $entry.LastWriteTime = $timestamp
        $inputStream = $null
        $outputStream = $null
        try {
            $inputStream = [System.IO.File]::OpenRead([string]$item.Source)
            $outputStream = $entry.Open()
            $inputStream.CopyTo($outputStream)
        }
        finally {
            if ($null -ne $outputStream) { $outputStream.Dispose() }
            if ($null -ne $inputStream) { $inputStream.Dispose() }
        }
    }
}
catch {
    if (Test-Path -LiteralPath $resolvedOutput) {
        [System.IO.File]::Delete($resolvedOutput)
    }
    throw
}
finally {
    if ($null -ne $archive) { $archive.Dispose() }
    if ($null -ne $stream) { $stream.Dispose() }
}

$finalValidationArguments = @{
    PackagePath = $resolvedOutput
    AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
    OutputFormat = 'Json'
}
if (-not [string]::IsNullOrWhiteSpace($TrustedCertificateThumbprint)) {
    $finalValidationArguments.TrustedCertificateThumbprint = $TrustedCertificateThumbprint
}
try {
    $finalValidation = & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') `
        @finalValidationArguments | ConvertFrom-Json
}
catch {
    if (Test-Path -LiteralPath $resolvedOutput -PathType Leaf) {
        [System.IO.File]::Delete($resolvedOutput)
    }
    throw
}
if ([string]$finalValidation.releaseId -cne [string]$manifest.releaseId) {
    [System.IO.File]::Delete($resolvedOutput)
    throw 'Final release package validation returned a different releaseId'
}
$file = Get-Item -LiteralPath $resolvedOutput
$report = [pscustomobject]@{
    status = 'PASS'
    releaseId = [string]$manifest.releaseId
    package = $file.FullName
    bytes = $file.Length
    sha256 = [string]$finalValidation.sha256
    entryCount = $entries.Count
    sourceDateEpoch = [int64]$manifest.source.sourceDateEpoch
}
if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 4 -Compress
}
else {
    $report | Format-List
}
