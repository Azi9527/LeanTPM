[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleasePackagePath,
    [Parameter(Mandatory)][string]$DeploymentPlanPath,
    [Parameter(Mandatory)][string]$RequesterSignaturePath,
    [Parameter(Mandatory)][string]$ApproverSignaturePath,
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedHostSnapshotSha256,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false, $true)
$sha256Pattern = '^[a-f0-9]{64}$'
$createdOutput = $false
$archive = $null
$outputStream = $null
$outputFull = $null
$locked = New-Object 'Collections.Generic.List[IO.FileStream]'

function Get-FixedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) `
        -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a fixed regular file"
    }
    $current = $item.Directory
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label contains a reparse ancestor"
        }
        $current = $current.Parent
    }
    return $item.FullName
}

function Open-LockedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][int64]$MaximumBytes
    )

    $fixed = Get-FixedFile -Path $Path -Label $Label
    $stream = New-Object IO.FileStream(
        $fixed,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    if ($stream.Length -lt 1 -or $stream.Length -gt $MaximumBytes) {
        $stream.Dispose()
        throw "$Label size is invalid"
    }
    $locked.Add($stream)
    return [pscustomobject]@{
        Path = $fixed
        Stream = $stream
        Length = [int64]$stream.Length
    }
}

function Get-StreamSha256 {
    param([Parameter(Mandatory)][IO.Stream]$Stream)

    $Stream.Position = 0
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $algorithm.ComputeHash($Stream)
        )).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
        $Stream.Position = 0
    }
}

function Read-StreamBytes {
    param(
        [Parameter(Mandatory)][IO.Stream]$Stream,
        [Parameter(Mandatory)][string]$Label
    )

    $Stream.Position = 0
    $bytes = New-Object byte[] ([int]$Stream.Length)
    $offset = 0
    while ($offset -lt $bytes.Length) {
        $count = $Stream.Read($bytes, $offset, $bytes.Length - $offset)
        if ($count -le 0) { throw "$Label was truncated during locked read" }
        $offset += $count
    }
    $Stream.Position = 0
    return $bytes
}

function Write-ZipEntryFromStream {
    param(
        [Parameter(Mandatory)][IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][IO.Stream]$Source,
        [Parameter(Mandatory)][DateTimeOffset]$Timestamp
    )

    $entry = $Archive.CreateEntry(
        $Name,
        [IO.Compression.CompressionLevel]::Optimal
    )
    $entry.LastWriteTime = $Timestamp
    $destination = $null
    try {
        $Source.Position = 0
        $destination = $entry.Open()
        $Source.CopyTo($destination)
    }
    finally {
        if ($null -ne $destination) { $destination.Dispose() }
        $Source.Position = 0
    }
}

function Write-ZipEntryBytes {
    param(
        [Parameter(Mandatory)][IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][byte[]]$Bytes,
        [Parameter(Mandatory)][DateTimeOffset]$Timestamp
    )

    $memory = New-Object IO.MemoryStream(,$Bytes)
    try {
        Write-ZipEntryFromStream -Archive $Archive -Name $Name `
            -Source $memory -Timestamp $Timestamp
    }
    finally { $memory.Dispose() }
}

function Assert-RequiredProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Label
    )

    foreach ($name in $Names) {
        if ($null -eq $Value.PSObject.Properties[$name]) {
            throw "$Label is missing '$name'"
        }
    }
}

try {
    Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop

    $repositoryRoot = (Resolve-Path -LiteralPath (
        Join-Path $PSScriptRoot '..'
    )).Path
    $schema = Open-LockedFile `
        -Path (Join-Path $repositoryRoot 'release\deployment-bundle.schema.json') `
        -Label 'deployment bundle schema' -MaximumBytes 1MB
    $package = Open-LockedFile -Path $ReleasePackagePath `
        -Label 'release package' -MaximumBytes 5GB
    $planFile = Open-LockedFile -Path $DeploymentPlanPath `
        -Label 'deployment plan' -MaximumBytes 1MB
    $requester = Open-LockedFile -Path $RequesterSignaturePath `
        -Label 'requester signature' -MaximumBytes 16MB
    $approver = Open-LockedFile -Path $ApproverSignaturePath `
        -Label 'approver signature' -MaximumBytes 16MB

    $outputFull = [IO.Path]::GetFullPath($OutputPath)
    if ([IO.Path]::GetExtension($outputFull) -cne '.zip') {
        throw 'Deployment bundle output must use the .zip extension'
    }
    $outputParent = Split-Path -Parent $outputFull
    if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
        throw 'Deployment bundle output parent must already exist'
    }
    $outputParentItem = Get-Item -LiteralPath $outputParent -Force
    $outputAncestor = $outputParentItem
    while ($null -ne $outputAncestor) {
        if (($outputAncestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Deployment bundle output parent chain cannot contain a reparse point'
        }
        $outputAncestor = $outputAncestor.Parent
    }
    if (Test-Path -LiteralPath $outputFull) {
        throw 'Deployment bundle output already exists and will not be overwritten'
    }
    foreach ($source in @($schema, $package, $planFile, $requester, $approver)) {
        if ($outputFull.Equals(
                [string]$source.Path,
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw 'Deployment bundle output cannot replace source material'
        }
    }

    $planBytes = Read-StreamBytes -Stream $planFile.Stream `
        -Label 'deployment plan'
    $planText = $utf8.GetString($planBytes)
    $plan = $planText | ConvertFrom-Json -ErrorAction Stop
    Assert-RequiredProperties -Value $plan -Label 'deployment plan' -Names @(
        'schemaVersion', 'environmentKind', 'environmentId', 'hostId',
        'releaseId', 'approvalId', 'packagePath', 'packageSha256',
        'manifestSha256', 'opsHostSnapshotSha256', 'nonce', 'requestedBy',
        'approvedBy', 'requesterSignaturePath', 'approverSignaturePath',
        'expiresAtUtc'
    )
    if ([int]$plan.schemaVersion -ne 1 -or
            [string]$plan.environmentKind -cne 'PRODUCTION' -or
            [string]$plan.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
            [string]$plan.environmentId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.hostId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]::IsNullOrWhiteSpace([string]$plan.requestedBy) -or
            [string]::IsNullOrWhiteSpace([string]$plan.approvedBy) -or
            [string]$plan.requestedBy -ceq [string]$plan.approvedBy) {
        throw 'Deployment plan identity or approval contract is invalid'
    }
    $expectedSnapshot = $ExpectedHostSnapshotSha256.ToLowerInvariant()
    if ([string]$plan.opsHostSnapshotSha256 -cne $expectedSnapshot -or
            [string]$plan.packageSha256 -notmatch $sha256Pattern -or
            [string]$plan.manifestSha256 -notmatch $sha256Pattern) {
        throw 'Deployment plan digest bindings are invalid'
    }
    if ((Split-Path -Leaf ([string]$plan.packagePath)) -cne
            'release-package.zip' -or
            (Split-Path -Leaf ([string]$plan.requesterSignaturePath)) -cne
            'deployment-plan.requester.p7s' -or
            (Split-Path -Leaf ([string]$plan.approverSignaturePath)) -cne
            'deployment-plan.approver.p7s') {
        throw 'Deployment plan fixed target paths are invalid'
    }

    $expiresAt = [DateTimeOffset]::MinValue
    $createdAt = [DateTimeOffset]::UtcNow
    if (-not ([string]$plan.expiresAtUtc).EndsWith('Z') -or
            -not [DateTimeOffset]::TryParse(
                [string]$plan.expiresAtUtc,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AssumeUniversal,
                [ref]$expiresAt
            ) -or
            $expiresAt -le $createdAt -or
            $expiresAt -gt $createdAt.AddHours(24)) {
        throw 'Deployment plan expiry is invalid for a deployment bundle'
    }

    $packageSha256 = Get-StreamSha256 -Stream $package.Stream
    $planSha256 = Get-StreamSha256 -Stream $planFile.Stream
    $requesterSha256 = Get-StreamSha256 -Stream $requester.Stream
    $approverSha256 = Get-StreamSha256 -Stream $approver.Stream
    if ($packageSha256 -cne [string]$plan.packageSha256) {
        throw 'Release package digest does not match the deployment plan'
    }

    $createdAtUtc = $createdAt.ToString(
        'yyyy-MM-ddTHH:mm:ss.fffZ',
        [Globalization.CultureInfo]::InvariantCulture
    )
    $metadata = [ordered]@{
        schemaVersion = 1
        action = 'DEPLOY_RELEASE'
        releaseId = [string]$plan.releaseId
        environmentId = [string]$plan.environmentId
        hostId = [string]$plan.hostId
        hostSnapshotSha256 = $expectedSnapshot
        createdAtUtc = $createdAtUtc
        expiresAtUtc = [string]$plan.expiresAtUtc
        releasePackage = [ordered]@{
            path = 'release-package.zip'
            bytes = [int64]$package.Length
            sha256 = $packageSha256
            manifestSha256 = [string]$plan.manifestSha256
        }
        deploymentPlan = [ordered]@{
            path = 'deployment-plan.json'
            bytes = [int64]$planFile.Length
            sha256 = $planSha256
            requesterSignaturePath = 'deployment-plan.requester.p7s'
            requesterSignatureBytes = [int64]$requester.Length
            requesterSignatureSha256 = $requesterSha256
            approverSignaturePath = 'deployment-plan.approver.p7s'
            approverSignatureBytes = [int64]$approver.Length
            approverSignatureSha256 = $approverSha256
        }
    }
    $metadataBytes = $utf8.GetBytes(
        ($metadata | ConvertTo-Json -Depth 6 -Compress)
    )
    $schemaBytes = Read-StreamBytes -Stream $schema.Stream `
        -Label 'deployment bundle schema'
    $zipTimestamp = $createdAt
    $zipMinimum = [DateTimeOffset]::new(
        1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero
    )
    if ($zipTimestamp -lt $zipMinimum) { $zipTimestamp = $zipMinimum }

    $outputStream = New-Object IO.FileStream(
        $outputFull,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
    $createdOutput = $true
    $archive = New-Object IO.Compression.ZipArchive(
        $outputStream,
        [IO.Compression.ZipArchiveMode]::Create,
        $true
    )
    Write-ZipEntryBytes -Archive $archive -Name 'deployment-bundle.json' `
        -Bytes $metadataBytes -Timestamp $zipTimestamp
    Write-ZipEntryBytes -Archive $archive -Name 'deployment-bundle.schema.json' `
        -Bytes $schemaBytes -Timestamp $zipTimestamp
    Write-ZipEntryFromStream -Archive $archive `
        -Name 'deployment-plan.approver.p7s' -Source $approver.Stream `
        -Timestamp $zipTimestamp
    Write-ZipEntryFromStream -Archive $archive -Name 'deployment-plan.json' `
        -Source $planFile.Stream -Timestamp $zipTimestamp
    Write-ZipEntryFromStream -Archive $archive `
        -Name 'deployment-plan.requester.p7s' -Source $requester.Stream `
        -Timestamp $zipTimestamp
    Write-ZipEntryFromStream -Archive $archive -Name 'release-package.zip' `
        -Source $package.Stream -Timestamp $zipTimestamp
    $archive.Dispose()
    $archive = $null
    $outputStream.Flush($true)
    $outputStream.Dispose()
    $outputStream = $null

    $bundleItem = Get-Item -LiteralPath $outputFull -Force
    if ($bundleItem.Length -le 0) {
        throw 'Deployment bundle output is empty'
    }
    $bundleSha256 = (
        Get-FileHash -LiteralPath $outputFull -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $createdOutput = $false
    $report = [pscustomobject]@{
        status = 'CREATED'
        releaseId = [string]$plan.releaseId
        approvalId = [string]$plan.approvalId
        hostSnapshotSha256 = $expectedSnapshot
        packageSha256 = $packageSha256
        deploymentPlanSha256 = $planSha256
        bundlePath = $bundleItem.FullName
        bundleBytes = [int64]$bundleItem.Length
        bundleSha256 = $bundleSha256
        entryCount = 6
        createdAtUtc = $createdAtUtc
        expiresAtUtc = [string]$plan.expiresAtUtc
    }
    if ($OutputFormat -eq 'Json') {
        $report | ConvertTo-Json -Depth 4 -Compress
    }
    else { $report | Format-List }
}
catch {
    if ($null -ne $archive) {
        $archive.Dispose()
        $archive = $null
    }
    if ($null -ne $outputStream) {
        $outputStream.Dispose()
        $outputStream = $null
    }
    if ($createdOutput -and
            -not [string]::IsNullOrWhiteSpace($outputFull) -and
            (Test-Path -LiteralPath $outputFull -PathType Leaf)) {
        [IO.File]::Delete($outputFull)
    }
    throw
}
finally {
    if ($null -ne $archive) { $archive.Dispose() }
    if ($null -ne $outputStream) { $outputStream.Dispose() }
    foreach ($stream in $locked) {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
