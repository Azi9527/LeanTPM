[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BundlePath,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ExpectedHostSnapshotSha256,
    [Parameter(Mandatory)][string]$ApprovalRoot,
    [Parameter(Mandatory)][string]$UploadRoot,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Fa-f0-9]{40}$')]
    [string]$TrustedManifestCertificateThumbprint,
    [Parameter(Mandatory)][string]$ReleaseTrustConfigPath,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$TrustedSchemaSha256,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$PackageVerifierSha256,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovalVerifierSha256,
    [ValidateRange(1048576, 6442450944)][int64]$MaxExpandedBytes = 6442450944,
    [ValidateRange(2, 1000)][int]$MaxCompressionRatio = 200,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trustedSchemaPath = Join-Path $repositoryRoot 'release\deployment-bundle.schema.json'
$packageVerifierPath = Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1'
$approvalVerifierPath = Join-Path $PSScriptRoot 'Test-LeanTpmReleaseApproval.ps1'
$strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$temporaryRoot = Join-Path $systemTemp (
    'leantpm-deployment-bundle-verify-{0}' -f [Guid]::NewGuid().ToString('N')
)
$lockedContentStreams = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).
        Hash.ToLowerInvariant()
}

function Assert-RegularPath {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [switch]$Container
    )

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ([bool]$item.PSIsContainer -ne [bool]$Container -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        $kind = if ($Container) { 'directory' } else { 'file' }
        throw "$Label must be a regular non-reparse $kind"
    }
    return $item
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    if ($null -eq $Value) { throw "$Label is missing" }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) {
        throw "$Label property count is invalid"
    }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Assert-RequiredProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Required,
        [Parameter(Mandatory)][string]$Label
    )

    if ($null -eq $Value) { throw "$Label is missing" }
    foreach ($name in $Required) {
        if ($null -eq $Value.PSObject.Properties[$name]) {
            throw "$Label is missing property $name"
        }
    }
}

function Assert-NoDuplicateJsonProperties {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Json)

    $stack = [Collections.Generic.List[object]]::new()
    $index = 0
    while ($index -lt $Json.Length) {
        $character = $Json[$index]
        if ([char]::IsWhiteSpace($character)) { $index++; continue }
        if ($character -eq '{') {
            $stack.Add([pscustomobject]@{
                    kind = 'object'
                    expectProperty = $true
                    names = [Collections.Generic.HashSet[string]]::new(
                        [StringComparer]::Ordinal
                    )
                })
            $index++
            continue
        }
        if ($character -eq '[') {
            $stack.Add([pscustomobject]@{
                    kind = 'array'
                    expectProperty = $false
                    names = $null
                })
            $index++
            continue
        }
        if ($character -eq '}' -or $character -eq ']') {
            if ($stack.Count -gt 0) { $stack.RemoveAt($stack.Count - 1) }
            $index++
            continue
        }
        if ($character -eq ',') {
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object') { $context.expectProperty = $true }
            }
            $index++
            continue
        }
        if ($character -eq '"') {
            $start = $index + 1
            $cursor = $start
            $containsEscape = $false
            while ($cursor -lt $Json.Length) {
                if ($Json[$cursor] -eq '\') {
                    $containsEscape = $true
                    $cursor += 2
                    continue
                }
                if ($Json[$cursor] -eq '"') { break }
                $cursor++
            }
            if ($cursor -ge $Json.Length) { throw 'JSON string is unterminated' }
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object' -and $context.expectProperty) {
                    if ($containsEscape) {
                        throw 'JSON property names must be unescaped ASCII literals'
                    }
                    $propertyName = $Json.Substring($start, $cursor - $start)
                    if (-not $context.names.Add($propertyName)) {
                        throw "JSON contains duplicate property $propertyName"
                    }
                    $context.expectProperty = $false
                    $next = $cursor + 1
                    while ($next -lt $Json.Length -and
                            [char]::IsWhiteSpace($Json[$next])) {
                        $next++
                    }
                    if ($next -ge $Json.Length -or $Json[$next] -ne ':') {
                        throw 'JSON property is missing a colon'
                    }
                }
            }
            $index = $cursor + 1
            continue
        }
        $index++
    }
}

function Read-StrictJsonFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][int64]$MaxBytes,
        [Parameter(Mandatory)][string]$Label
    )

    $item = Assert-RegularPath -Path $Path -Label $Label
    if ($item.Length -lt 2 -or $item.Length -gt $MaxBytes) {
        throw "$Label byte length is outside the allowed range"
    }
    $bytes = [IO.File]::ReadAllBytes($item.FullName)
    try {
        $json = $strictUtf8.GetString($bytes)
        Assert-NoDuplicateJsonProperties $json
        try { return $json | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "$Label is not valid strict JSON: $($_.Exception.Message)" }
    }
    finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Assert-ExactAbsolutePath {
    param(
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Actual)) {
        throw "$Label must be an absolute path"
    }
    $actualFull = [IO.Path]::GetFullPath($Actual).TrimEnd('\', '/')
    $expectedFull = [IO.Path]::GetFullPath($Expected).TrimEnd('\', '/')
    if (-not $actualFull.Equals(
            $expectedFull,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Label is not the fixed host target"
    }
}

function Assert-ContentBinding {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][int64]$ExpectedBytes,
        [Parameter(Mandatory)][string]$ExpectedSha256,
        [Parameter(Mandatory)][string]$Label
    )

    $item = Assert-RegularPath -Path $Path -Label $Label
    if ($item.Length -ne $ExpectedBytes) {
        throw "$Label byte length does not match deployment-bundle.json"
    }
    $actualSha256 = Get-FileSha256 $item.FullName
    if ($ExpectedSha256 -notmatch '^[a-f0-9]{64}$' -or
            $actualSha256 -cne $ExpectedSha256) {
        throw "$Label SHA-256 digest does not match deployment-bundle.json"
    }
    return $actualSha256
}

function Test-PathWithin {
    param(
        [Parameter(Mandatory)][string]$Candidate,
        [Parameter(Mandatory)][string]$Root
    )
    $candidateFull = [IO.Path]::GetFullPath($Candidate).TrimEnd('\', '/')
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    return $candidateFull.Equals($rootFull, [StringComparison]::OrdinalIgnoreCase) -or
        $candidateFull.StartsWith(
            $rootFull + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )
}

$bundleItem = Assert-RegularPath -Path $BundlePath -Label 'BundlePath'
if ($bundleItem.Length -lt 1 -or $bundleItem.Length -gt 5368709120) {
    throw 'Deployment bundle size is outside the 1 byte..5 GiB safety range'
}
$approvalRootItem = Assert-RegularPath `
    -Path $ApprovalRoot -Label 'ApprovalRoot' -Container
$uploadRootItem = Assert-RegularPath `
    -Path $UploadRoot -Label 'UploadRoot' -Container
$trustItem = Assert-RegularPath `
    -Path $ReleaseTrustConfigPath -Label 'ReleaseTrustConfigPath'
$schemaItem = Assert-RegularPath `
    -Path $trustedSchemaPath -Label 'Repository deployment-bundle schema'
$null = Assert-RegularPath `
    -Path $packageVerifierPath -Label 'Fixed release package verifier'
$null = Assert-RegularPath `
    -Path $approvalVerifierPath -Label 'Fixed release approval verifier'
if ((Get-FileSha256 $schemaItem.FullName) -cne
        $TrustedSchemaSha256.ToLowerInvariant()) {
    throw 'Repository deployment-bundle schema SHA-256 digest does not match'
}
if ((Get-FileSha256 $packageVerifierPath) -cne
        $PackageVerifierSha256.ToLowerInvariant()) {
    throw 'Release package verifier SHA-256 digest does not match'
}
if ((Get-FileSha256 $approvalVerifierPath) -cne
        $ApprovalVerifierSha256.ToLowerInvariant()) {
    throw 'Release approval verifier SHA-256 digest does not match'
}

$approvalRootFull = $approvalRootItem.FullName.TrimEnd('\', '/')
$uploadRootFull = $uploadRootItem.FullName.TrimEnd('\', '/')
if ((Test-PathWithin $approvalRootFull $uploadRootFull) -or
        (Test-PathWithin $uploadRootFull $approvalRootFull)) {
    throw 'ApprovalRoot and UploadRoot must be separate, non-nested directories'
}
if ((Test-PathWithin $trustItem.FullName $approvalRootFull) -or
        (Test-PathWithin $trustItem.FullName $uploadRootFull)) {
    throw 'ReleaseTrustConfigPath cannot be inside approval or upload storage'
}

$expectedEntries = @(
    'deployment-bundle.json',
    'deployment-bundle.schema.json',
    'release-package.zip',
    'deployment-plan.json',
    'deployment-plan.requester.p7s',
    'deployment-plan.approver.p7s'
)
$entryLimits = @{
    'deployment-bundle.json' = 1MB
    'deployment-bundle.schema.json' = 1MB
    'release-package.zip' = 5GB
    'deployment-plan.json' = 1MB
    'deployment-plan.requester.p7s' = 16MB
    'deployment-plan.approver.p7s' = 16MB
}

$bundleStream = $null
$archive = $null
try {
    $null = New-Item -ItemType Directory -Path $temporaryRoot -ErrorAction Stop
    $bundleStream = [IO.File]::Open(
        $bundleItem.FullName,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    $bundleHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $bundleSha256 = ([BitConverter]::ToString(
                $bundleHasher.ComputeHash($bundleStream)
            )).Replace('-', '').ToLowerInvariant()
    }
    finally { $bundleHasher.Dispose() }
    $bundleStream.Position = 0
    $archive = New-Object IO.Compression.ZipArchive(
        $bundleStream,
        [IO.Compression.ZipArchiveMode]::Read,
        $false
    )
    if ($archive.Entries.Count -ne $expectedEntries.Count) {
        throw 'Deployment bundle ZIP must contain the exact six-file layout'
    }
    $seen = New-Object 'Collections.Generic.HashSet[string]' `
        ([StringComparer]::Ordinal)
    [int64]$totalExpandedBytes = 0
    foreach ($entry in $archive.Entries) {
        if ([string]::IsNullOrWhiteSpace($entry.Name) -or
                $entry.FullName.Contains('/') -or
                $entry.FullName.Contains('\') -or
                $entry.FullName.Contains(':') -or
                [IO.Path]::IsPathRooted($entry.FullName) -or
                $entry.FullName -in @('.', '..') -or
                -not $seen.Add($entry.FullName)) {
            throw "Deployment bundle contains an unsafe or duplicate entry: $($entry.FullName)"
        }
        if ($entry.FullName -cnotin $expectedEntries) {
            throw "Deployment bundle contains an unexpected entry: $($entry.FullName)"
        }
        [uint32]$externalAttributes = [uint32](
            [int64]$entry.ExternalAttributes -band 0xFFFFFFFFL
        )
        $unixKind = ($externalAttributes -shr 16) -band 0xF000
        if (($externalAttributes -band [uint32][IO.FileAttributes]::ReparsePoint) -ne 0 -or
                $unixKind -eq 0xA000) {
            throw "Deployment bundle cannot contain a reparse or symbolic-link entry: $($entry.FullName)"
        }
        if ([int64]$entry.Length -lt 1 -or
                [int64]$entry.Length -gt [int64]$entryLimits[$entry.FullName]) {
            throw "Deployment bundle entry size is invalid: $($entry.FullName)"
        }
        if ([int64]$entry.CompressedLength -eq 0 -or
                ([double]$entry.Length / [double]$entry.CompressedLength) -gt
                $MaxCompressionRatio) {
            throw "Deployment bundle entry exceeds the compression-ratio limit: $($entry.FullName)"
        }
        $totalExpandedBytes += [int64]$entry.Length
        if ($totalExpandedBytes -gt $MaxExpandedBytes) {
            throw 'Deployment bundle exceeds the expanded-size safety limit'
        }
    }
    foreach ($expectedEntry in $expectedEntries) {
        if (-not $seen.Contains($expectedEntry)) {
            throw "Deployment bundle is missing exact entry $expectedEntry"
        }
    }

    foreach ($entry in $archive.Entries) {
        $destination = Join-Path $temporaryRoot $entry.FullName
        $input = $null
        $output = $null
        try {
            $input = $entry.Open()
            $output = New-Object IO.FileStream(
                $destination,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            $input.CopyTo($output)
            $output.Flush($true)
        }
        finally {
            if ($null -ne $output) { $output.Dispose() }
            if ($null -ne $input) { $input.Dispose() }
        }
    }
    $archive.Dispose()
    $archive = $null
    $bundleStream.Dispose()
    $bundleStream = $null

    $packagedSchemaPath = Join-Path $temporaryRoot 'deployment-bundle.schema.json'
    if ((Get-FileSha256 $packagedSchemaPath) -cne
            (Get-FileSha256 $schemaItem.FullName)) {
        throw 'Packaged deployment-bundle schema differs from the repository trust anchor'
    }

    $metadataPath = Join-Path $temporaryRoot 'deployment-bundle.json'
    $releasePackagePath = Join-Path $temporaryRoot 'release-package.zip'
    $deploymentPlanPath = Join-Path $temporaryRoot 'deployment-plan.json'
    $requesterSignaturePath = Join-Path `
        $temporaryRoot 'deployment-plan.requester.p7s'
    $approverSignaturePath = Join-Path `
        $temporaryRoot 'deployment-plan.approver.p7s'

    foreach ($lockedPath in @(
            $releasePackagePath,
            $deploymentPlanPath,
            $requesterSignaturePath,
            $approverSignaturePath
        )) {
        $lockedContentStreams.Add([IO.File]::Open(
                $lockedPath,
                [IO.FileMode]::Open,
                [IO.FileAccess]::Read,
                [IO.FileShare]::Read
            ))
    }

    $metadata = Read-StrictJsonFile `
        -Path $metadataPath -MaxBytes 1MB -Label 'deployment-bundle.json'
    Assert-ExactProperties $metadata @(
        'schemaVersion', 'action', 'releaseId', 'environmentId', 'hostId',
        'hostSnapshotSha256', 'createdAtUtc', 'expiresAtUtc', 'releasePackage',
        'deploymentPlan'
    ) 'deployment-bundle.json'
    Assert-ExactProperties $metadata.releasePackage @(
        'path', 'bytes', 'sha256', 'manifestSha256'
    ) 'deployment-bundle.json releasePackage'
    Assert-ExactProperties $metadata.deploymentPlan @(
        'path', 'bytes', 'sha256', 'requesterSignaturePath',
        'requesterSignatureBytes', 'requesterSignatureSha256',
        'approverSignaturePath', 'approverSignatureBytes',
        'approverSignatureSha256'
    ) 'deployment-bundle.json deploymentPlan'

    if ([int]$metadata.schemaVersion -ne 1 -or
            [string]$metadata.action -cne 'DEPLOY_RELEASE' -or
            [string]$metadata.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
            [string]$metadata.releaseId -match
            '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
            [string]$metadata.releaseId -match '\.$' -or
            [string]$metadata.environmentId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$metadata.hostId -notmatch '^[A-Za-z0-9._-]{3,128}$') {
        throw 'Deployment bundle identity fields are invalid'
    }
    $expectedSnapshot = $ExpectedHostSnapshotSha256.ToLowerInvariant()
    if ([string]$metadata.hostSnapshotSha256 -notmatch '^[a-f0-9]{64}$' -or
            [string]$metadata.hostSnapshotSha256 -cne $expectedSnapshot) {
        throw 'Deployment bundle host snapshot binding does not match this server'
    }

    $createdAt = [DateTimeOffset]::MinValue
    $expiresAt = [DateTimeOffset]::MinValue
    if (-not ([string]$metadata.createdAtUtc).EndsWith('Z') -or
            -not ([string]$metadata.expiresAtUtc).EndsWith('Z') -or
            -not [DateTimeOffset]::TryParse(
                [string]$metadata.createdAtUtc,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AssumeUniversal,
                [ref]$createdAt
            ) -or
            -not [DateTimeOffset]::TryParse(
                [string]$metadata.expiresAtUtc,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AssumeUniversal,
                [ref]$expiresAt
            ) -or
            $createdAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -le $createdAt -or
            $expiresAt -gt $createdAt.AddHours(24)) {
        throw 'Deployment bundle issue/expiry window is invalid'
    }

    if ([string]$metadata.releasePackage.path -cne 'release-package.zip' -or
            [string]$metadata.deploymentPlan.path -cne 'deployment-plan.json' -or
            [string]$metadata.deploymentPlan.requesterSignaturePath -cne
            'deployment-plan.requester.p7s' -or
            [string]$metadata.deploymentPlan.approverSignaturePath -cne
            'deployment-plan.approver.p7s') {
        throw 'Deployment bundle metadata paths do not match the exact archive layout'
    }

    $releasePackageSha256 = Assert-ContentBinding `
        -Path $releasePackagePath `
        -ExpectedBytes ([int64]$metadata.releasePackage.bytes) `
        -ExpectedSha256 ([string]$metadata.releasePackage.sha256) `
        -Label 'release package'
    $deploymentPlanSha256 = Assert-ContentBinding `
        -Path $deploymentPlanPath `
        -ExpectedBytes ([int64]$metadata.deploymentPlan.bytes) `
        -ExpectedSha256 ([string]$metadata.deploymentPlan.sha256) `
        -Label 'deployment plan'
    $requesterSignatureSha256 = Assert-ContentBinding `
        -Path $requesterSignaturePath `
        -ExpectedBytes ([int64]$metadata.deploymentPlan.requesterSignatureBytes) `
        -ExpectedSha256 ([string]$metadata.deploymentPlan.requesterSignatureSha256) `
        -Label 'requester signature'
    $approverSignatureSha256 = Assert-ContentBinding `
        -Path $approverSignaturePath `
        -ExpectedBytes ([int64]$metadata.deploymentPlan.approverSignatureBytes) `
        -ExpectedSha256 ([string]$metadata.deploymentPlan.approverSignatureSha256) `
        -Label 'approver signature'

    if ([string]$metadata.releasePackage.manifestSha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'Deployment bundle manifest SHA-256 is invalid'
    }

    $plan = Read-StrictJsonFile `
        -Path $deploymentPlanPath -MaxBytes 1MB -Label 'deployment plan'
    Assert-RequiredProperties $plan @(
        'schemaVersion', 'environmentKind', 'environmentId', 'hostId', 'releaseId',
        'approvalId', 'packagePath', 'packageSha256', 'manifestSha256',
        'opsHostSnapshotSha256', 'nonce', 'requestedBy', 'approvedBy',
        'requesterSignaturePath', 'approverSignaturePath', 'expiresAtUtc'
    ) 'deployment plan'
    if ([int]$plan.schemaVersion -ne 1 -or
            [string]$plan.environmentKind -cne 'PRODUCTION' -or
            [string]$plan.releaseId -cne [string]$metadata.releaseId -or
            [string]$plan.environmentId -cne [string]$metadata.environmentId -or
            [string]$plan.hostId -cne [string]$metadata.hostId -or
            [string]$plan.packageSha256 -cne $releasePackageSha256 -or
            [string]$plan.manifestSha256 -cne
            [string]$metadata.releasePackage.manifestSha256 -or
            [string]$plan.opsHostSnapshotSha256 -cne $expectedSnapshot -or
            [string]$plan.expiresAtUtc -cne [string]$metadata.expiresAtUtc) {
        throw 'Deployment plan does not match the host-bound deployment bundle'
    }
    if ([string]$plan.approvalId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$plan.approvalId -match
            '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
            [string]$plan.approvalId -match '\.$' -or
            [string]$plan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
            [string]::IsNullOrWhiteSpace([string]$plan.requestedBy) -or
            [string]::IsNullOrWhiteSpace([string]$plan.approvedBy) -or
            [string]$plan.requestedBy -ceq [string]$plan.approvedBy) {
        throw 'Deployment approval identities or approvalId are invalid'
    }

    $expectedPackageTarget = Join-Path $uploadRootFull (
        'releases\{0}\release-package.zip' -f $releasePackageSha256
    )
    $expectedApprovalDirectory = Join-Path `
        $approvalRootFull ([string]$plan.approvalId)
    Assert-ExactAbsolutePath `
        -Actual ([string]$plan.packagePath) `
        -Expected $expectedPackageTarget `
        -Label 'Deployment plan packagePath'
    Assert-ExactAbsolutePath `
        -Actual ([string]$plan.requesterSignaturePath) `
        -Expected (Join-Path $expectedApprovalDirectory `
            'deployment-plan.requester.p7s') `
        -Label 'Deployment plan requesterSignaturePath'
    Assert-ExactAbsolutePath `
        -Actual ([string]$plan.approverSignaturePath) `
        -Expected (Join-Path $expectedApprovalDirectory `
            'deployment-plan.approver.p7s') `
        -Label 'Deployment plan approverSignaturePath'

    $packageJson = & $packageVerifierPath `
        -PackagePath $releasePackagePath `
        -TrustedCertificateThumbprint $TrustedManifestCertificateThumbprint `
        -OutputFormat Json
    $packageReport = $packageJson | ConvertFrom-Json -ErrorAction Stop
    if ([string]$packageReport.status -cne 'PASS' -or
            [string]$packageReport.releaseTier -cne 'PRODUCTION' -or
            [string]$packageReport.releaseId -cne [string]$metadata.releaseId -or
            [string]$packageReport.sha256 -cne $releasePackageSha256 -or
            [string]$packageReport.manifestSha256 -cne
            [string]$metadata.releasePackage.manifestSha256) {
        throw 'Verified release package does not match the production deployment bundle'
    }
    if ([int]$packageReport.databaseSchemaVersion -lt 1 -or
            [string]$packageReport.productVersion -notmatch
            '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') {
        throw 'Verified release package version contract is invalid'
    }

    $approvalJson = & $approvalVerifierPath `
        -PlanPath $deploymentPlanPath `
        -RequesterSignaturePath $requesterSignaturePath `
        -ApproverSignaturePath $approverSignaturePath `
        -TrustConfigPath $trustItem.FullName `
        -OutputFormat Json
    $approvalReport = $approvalJson | ConvertFrom-Json -ErrorAction Stop
    if ([string]$approvalReport.status -cne 'PASS' -or
            [string]$approvalReport.requestedBy -cne [string]$plan.requestedBy -or
            [string]$approvalReport.approvedBy -cne [string]$plan.approvedBy -or
            [string]$approvalReport.planSha256 -cne $deploymentPlanSha256 -or
            [string]$approvalReport.requesterCertificateThumbprint -ceq
            [string]$approvalReport.approverCertificateThumbprint) {
        throw 'Deployment plan dual-CMS approval verification is invalid'
    }

    if ((Get-FileSha256 $releasePackagePath) -cne $releasePackageSha256 -or
            (Get-FileSha256 $deploymentPlanPath) -cne $deploymentPlanSha256 -or
            (Get-FileSha256 $requesterSignaturePath) -cne
            $requesterSignatureSha256 -or
            (Get-FileSha256 $approverSignaturePath) -cne
            $approverSignatureSha256) {
        throw 'Deployment bundle content changed during verification'
    }

    $report = [pscustomobject]@{
        status = 'PASS'
        readOnly = $true
        action = 'DEPLOY_RELEASE'
        bundlePath = $bundleItem.FullName
        releaseId = [string]$metadata.releaseId
        productVersion = [string]$packageReport.productVersion
        databaseSchemaVersion = [int]$packageReport.databaseSchemaVersion
        environmentId = [string]$metadata.environmentId
        hostId = [string]$metadata.hostId
        hostSnapshotSha256 = $expectedSnapshot
        bundleSha256 = $bundleSha256
        releasePackageBytes = [int64]$metadata.releasePackage.bytes
        releasePackageSha256 = $releasePackageSha256
        manifestSha256 = [string]$metadata.releasePackage.manifestSha256
        deploymentPlanSha256 = $deploymentPlanSha256
        requesterSignatureSha256 = $requesterSignatureSha256
        approverSignatureSha256 = $approverSignatureSha256
        approvalId = [string]$plan.approvalId
        nonce = [string]$plan.nonce
        requestedBy = [string]$approvalReport.requestedBy
        approvedBy = [string]$approvalReport.approvedBy
        issuedAtUtc = [string]$metadata.createdAtUtc
        expiresAtUtc = [string]$metadata.expiresAtUtc
    }
    if ($OutputFormat -eq 'Json') {
        $report | ConvertTo-Json -Depth 4 -Compress
    }
    else { $report | Format-List }
}
finally {
    foreach ($stream in $lockedContentStreams) {
        if ($null -ne $stream) { $stream.Dispose() }
    }
    if ($null -ne $archive) { $archive.Dispose() }
    if ($null -ne $bundleStream) { $bundleStream.Dispose() }
    $temporaryFull = [IO.Path]::GetFullPath($temporaryRoot).TrimEnd('\', '/')
    $expectedPrefix = $systemTemp + [IO.Path]::DirectorySeparatorChar +
        'leantpm-deployment-bundle-verify-'
    if (-not $temporaryFull.StartsWith(
            $expectedPrefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Refusing to clean an unexpected temporary path: $temporaryFull"
    }
    if (Test-Path -LiteralPath $temporaryFull) {
        Remove-Item -LiteralPath $temporaryFull -Recurse -Force
    }
}
