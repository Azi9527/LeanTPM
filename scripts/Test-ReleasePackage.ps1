[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PackagePath,
    [switch]$AllowUnsignedTestManifest,
    [string]$TrustedCertificateThumbprint = '',
    [string]$ExtractTo = '',
    [ValidateRange(1048576, 5368709120)][int64]$MaxExpandedBytes = 5368709120,
    [ValidateRange(1048576, 2147483648)][int64]$MaxEntryBytes = 2147483648,
    [ValidateRange(2, 1000)][int]$MaxCompressionRatio = 200,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedPackage = (Resolve-Path -LiteralPath $PackagePath).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trustedSchemaPath = Join-Path $repositoryRoot 'release\release-manifest.schema.json'
Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop

$systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$temporaryRoot = Join-Path $systemTemp ("leantpm-package-verify-{0}" -f [Guid]::NewGuid().ToString('N'))
$cleanupTemporaryRoot = $true
$null = New-Item -ItemType Directory -Path $temporaryRoot

function Assert-ArchivePath {
    param([string]$Name)

    if ([string]::IsNullOrWhiteSpace($Name) -or $Name.Contains('\') -or
            [System.IO.Path]::IsPathRooted($Name) -or $Name.Contains(':') -or
            $Name.Length -gt 260) {
        throw "Archive entry must be a short POSIX-style relative path: $Name"
    }
    $segments = $Name.Split('/')
    if (@($segments | Where-Object {
                $_ -eq '..' -or $_ -eq '.' -or [string]::IsNullOrWhiteSpace($_)
            }).Count -gt 0) {
        throw "Archive entry contains path traversal or empty segments: $Name"
    }
}

try {
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' `
        ([System.StringComparer]::OrdinalIgnoreCase)
    [int64]$totalBytes = 0
    $fileStream = $null
    $archive = $null
    try {
        $fileStream = [System.IO.File]::Open(
            $resolvedPackage,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $packageHasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            $packageSha256 = ([BitConverter]::ToString(
                    $packageHasher.ComputeHash($fileStream)
                )).Replace('-', '').ToLowerInvariant()
        }
        finally { $packageHasher.Dispose() }
        $fileStream.Position = 0
        $archive = New-Object System.IO.Compression.ZipArchive(
            $fileStream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        if ($archive.Entries.Count -lt 3 -or $archive.Entries.Count -gt 5000) {
            throw 'Release package entry count is outside the 3..5000 safety range'
        }
        foreach ($entry in $archive.Entries) {
            Assert-ArchivePath $entry.FullName
            if ([string]::IsNullOrWhiteSpace($entry.Name)) {
                throw "Explicit directory archive entries are forbidden: $($entry.FullName)"
            }
            if (-not $seen.Add($entry.FullName)) {
                throw "Duplicate archive entry (case-insensitive): $($entry.FullName)"
            }
            if ([int64]$entry.Length -gt $MaxEntryBytes) {
                throw "Archive entry exceeds the per-file safety limit: $($entry.FullName)"
            }
            if ([int64]$entry.Length -gt 0 -and (
                    [int64]$entry.CompressedLength -eq 0 -or
                    ([double]$entry.Length / [double]$entry.CompressedLength) -gt $MaxCompressionRatio
                )) {
                throw "Archive entry exceeds the compression-ratio safety limit: $($entry.FullName)"
            }
            $totalBytes += [int64]$entry.Length
            if ($totalBytes -gt $MaxExpandedBytes) {
                throw 'Release package exceeds the expanded-size safety limit'
            }
        }
        $driveRoot = [System.IO.Path]::GetPathRoot($systemTemp)
        $available = (New-Object System.IO.DriveInfo($driveRoot)).AvailableFreeSpace
        if ($available -lt ($totalBytes + 536870912)) {
            throw 'Temporary volume lacks the required expanded bytes plus 512 MiB reserve'
        }
        foreach ($entry in $archive.Entries) {
            $destination = Join-Path $temporaryRoot $entry.FullName.Replace('/', '\')
            $destinationFull = [System.IO.Path]::GetFullPath($destination)
            $prefix = [System.IO.Path]::GetFullPath($temporaryRoot).TrimEnd('\') + '\'
            if (-not $destinationFull.StartsWith(
                    $prefix,
                    [System.StringComparison]::OrdinalIgnoreCase
                )) {
                throw "Archive entry escapes extraction root: $($entry.FullName)"
            }
            $parent = Split-Path -Parent $destinationFull
            if (-not (Test-Path -LiteralPath $parent)) {
                $null = New-Item -ItemType Directory -Path $parent -Force
            }
            $inputStream = $null
            $outputStream = $null
            try {
                $inputStream = $entry.Open()
                $outputStream = New-Object System.IO.FileStream(
                    $destinationFull,
                    [System.IO.FileMode]::CreateNew,
                    [System.IO.FileAccess]::Write,
                    [System.IO.FileShare]::None
                )
                $inputStream.CopyTo($outputStream)
            }
            finally {
                if ($null -ne $outputStream) { $outputStream.Dispose() }
                if ($null -ne $inputStream) { $inputStream.Dispose() }
            }
        }
    }
    finally {
        if ($null -ne $archive) { $archive.Dispose() }
        if ($null -ne $fileStream) { $fileStream.Dispose() }
    }

    foreach ($required in @('release-manifest.json', 'release-manifest.schema.json')) {
        if (-not $seen.Contains($required)) {
            throw "Release package is missing required entry: $required"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $temporaryRoot 'payload') -PathType Container)) {
        throw 'Release package is missing the payload directory'
    }
    $packagedSchema = Join-Path $temporaryRoot 'release-manifest.schema.json'
    $packagedSchemaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $packagedSchema).Hash
    $trustedSchemaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $trustedSchemaPath).Hash
    if (-not $packagedSchemaHash.Equals(
            $trustedSchemaHash,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Packaged release-manifest schema differs from the repository trust anchor'
    }
    $manifestPath = Join-Path $temporaryRoot 'release-manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Encoding utf8 -Raw | ConvertFrom-Json
    $signaturePath = [string]$manifest.signing.signaturePath
    foreach ($entryName in $seen) {
        $allowed = $entryName -ceq 'release-manifest.json' -or
            $entryName -ceq 'release-manifest.schema.json' -or
            $entryName.StartsWith('payload/', [System.StringComparison]::Ordinal) -or
            (-not [string]::IsNullOrWhiteSpace($signaturePath) -and
                $entryName -ceq $signaturePath)
        if (-not $allowed) { throw "Unexpected unlisted ZIP root entry: $entryName" }
    }

    $validationArguments = @{
        ManifestPath = $manifestPath
        PackageRoot = (Join-Path $temporaryRoot 'payload')
        OutputFormat = 'Json'
        AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
    }
    if (-not [string]::IsNullOrWhiteSpace($TrustedCertificateThumbprint)) {
        $validationArguments.TrustedCertificateThumbprint = $TrustedCertificateThumbprint
    }
    $validationJson = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') @validationArguments
    $validation = $validationJson | ConvertFrom-Json
    $packageFile = Get-Item -LiteralPath $resolvedPackage
    $report = [pscustomobject]@{
        status = 'PASS'
        releaseId = [string]$validation.releaseId
        releaseTier = [string]$validation.releaseTier
        productVersion = [string]$validation.productVersion
        databaseSchemaFrom = [int]$validation.databaseSchemaFrom
        databaseSchemaVersion = [int]$validation.databaseSchemaVersion
        artifactCount = [int]$validation.artifactCount
        package = $packageFile.FullName
        bytes = $packageFile.Length
        expandedBytes = $totalBytes
        sha256 = $packageSha256
        manifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).
            Hash.ToLowerInvariant()
        schemaSha256 = $trustedSchemaHash.ToLowerInvariant()
    }
    if (-not [string]::IsNullOrWhiteSpace($ExtractTo)) {
        $destinationFull = [System.IO.Path]::GetFullPath($ExtractTo).TrimEnd('\', '/')
        $destinationParent = Split-Path -Parent $destinationFull
        if (-not (Test-Path -LiteralPath $destinationParent -PathType Container)) {
            throw "Verified extraction parent does not exist: $destinationParent"
        }
        if (Test-Path -LiteralPath $destinationFull) {
            throw "Verified extraction target already exists: $destinationFull"
        }
        Move-Item -LiteralPath $temporaryRoot -Destination $destinationFull
        $cleanupTemporaryRoot = $false
        $report | Add-Member -NotePropertyName extractedTo -NotePropertyValue $destinationFull
    }
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
}
finally {
    $temporaryFull = [System.IO.Path]::GetFullPath($temporaryRoot).TrimEnd('\', '/')
    $expectedPrefix = $systemTemp + [System.IO.Path]::DirectorySeparatorChar +
        'leantpm-package-verify-'
    if (-not $temporaryFull.StartsWith(
            $expectedPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Refusing to clean an unexpected temporary path: $temporaryFull"
    }
    if ($cleanupTemporaryRoot -and (Test-Path -LiteralPath $temporaryFull)) {
        Remove-Item -LiteralPath $temporaryFull -Recurse -Force
    }
}
