[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PayloadRoot,
    [Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][string]$ReleaseId,
    [Parameter(Mandatory)][ValidateSet('TEST', 'STAGING', 'PRODUCTION')]
    [string]$ReleaseTier,
    [Parameter(Mandatory)][string]$SourceCommit,
    [Parameter(Mandatory)][string]$BaselinePath,
    [Parameter(Mandatory)][string]$CreatedAtUtc,
    [Parameter(Mandatory)][int]$SchemaFrom,
    [Parameter(Mandatory)][ValidateSet('NONE', 'EXPAND', 'MIGRATE', 'CONTRACT')]
    [string]$DatabasePhase,
    [switch]$RequiresDowntime,
    [switch]$AllowUnsignedTestManifest,
    [switch]$AllowSyntheticTestBaseline,
    [switch]$EmitUnsignedSigningCandidate,
    [string]$SigningCertificateThumbprint = '',
    [string]$SignaturePath = 'release-manifest.p7s',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($ReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $ReleaseId -match '^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        $ReleaseId -match '\.$') {
    throw 'ReleaseId is not a safe canonical identifier'
}
if ($SourceCommit -cnotmatch '^[0-9a-f]{40}$') {
    throw 'SourceCommit must be a lowercase 40-character Git commit'
}
$baseline = Get-Content -LiteralPath (Resolve-Path -LiteralPath $BaselinePath).Path `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$baseline.schemaVersion -ne 1 -or [string]$baseline.status -cne 'PASS' -or
        [bool]$baseline.dirty -or [string]$baseline.commit -cne $SourceCommit -or
        [string]$baseline.fileTreeSha256 -cnotmatch '^[0-9a-f]{64}$') {
    throw 'Baseline record is not a clean PASS for SourceCommit'
}
if (-not $AllowSyntheticTestBaseline) {
    $repositoryRootForBaseline = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $actualCommit = (& git -C $repositoryRootForBaseline rev-parse HEAD).Trim()
    $dirty = @(& git -C $repositoryRootForBaseline status --porcelain=v1 --untracked-files=all)
    $tree = @(& git -C $repositoryRootForBaseline ls-tree -r --full-tree HEAD)
    $treeBytes = [Text.Encoding]::UTF8.GetBytes(($tree -join "`n") + "`n")
    $treeHasher = [Security.Cryptography.SHA256]::Create()
    try { $actualTreeHash = ([BitConverter]::ToString($treeHasher.ComputeHash($treeBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $treeHasher.Dispose() }
    if ($LASTEXITCODE -ne 0 -or $actualCommit -cne $SourceCommit -or $dirty.Count -ne 0 -or
            $actualTreeHash -cne [string]$baseline.fileTreeSha256) {
        throw 'Current source does not match the clean baseline record'
    }
}
elseif ($ReleaseTier -ne 'TEST') {
    throw 'Synthetic baseline records are allowed only for TEST manifests'
}
$created = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParse($CreatedAtUtc, [ref]$created) -or
        -not $CreatedAtUtc.EndsWith('Z')) {
    throw 'CreatedAtUtc must be an ISO-8601 UTC timestamp ending in Z'
}
if ($ReleaseTier -eq 'TEST') {
    if (-not $AllowUnsignedTestManifest -or $EmitUnsignedSigningCandidate) {
        throw 'TEST manifests require the explicit unsigned-test workflow'
    }
}
else {
    if (-not $EmitUnsignedSigningCandidate -or $AllowUnsignedTestManifest -or
            $SigningCertificateThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$' -or
            $SignaturePath -notmatch '^[A-Za-z0-9._/-]+$' -or
            $SignaturePath.Split('/') -contains '..' -or $SignaturePath.Contains('\')) {
        throw 'STAGING/PRODUCTION requires a safe unsigned signing candidate with a pinned signer'
    }
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$version = Get-Content -LiteralPath (Join-Path $repositoryRoot 'VERSION.json') -Encoding utf8 -Raw |
    ConvertFrom-Json
$payload = (Resolve-Path -LiteralPath $PayloadRoot).Path.TrimEnd('\', '/')
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
$outputParent = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "Output directory does not exist: $outputParent"
}
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Output manifest already exists: $outputFullPath"
}
if ($SchemaFrom -lt 0 -or $SchemaFrom -gt [int]$version.databaseSchemaVersion) {
    throw 'SchemaFrom must be between 0 and the canonical database schema version'
}

function Get-ComponentName {
    param([string]$RelativePath)

    $top = $RelativePath.Split('/')[0]
    switch ($top) {
        'backend' { 'backend' }
        'web' { 'web' }
        'app' { 'app' }
        'database' { 'database' }
        'operations' { 'operations' }
        'config' { 'config' }
        default { throw "Unsupported top-level package directory: $top" }
    }
}

function Get-MediaType {
    param([string]$RelativePath)

    switch ([System.IO.Path]::GetExtension($RelativePath).ToLowerInvariant()) {
        '.jar' { 'application/java-archive' }
        '.apk' { 'application/vnd.android.package-archive' }
        '.json' { 'application/json' }
        '.html' { 'text/html' }
        '.js' { 'text/javascript' }
        '.css' { 'text/css' }
        default { 'application/octet-stream' }
    }
}

$artifacts = @(
    Get-ChildItem -LiteralPath $payload -Recurse -File -Force |
        Sort-Object FullName |
        ForEach-Object {
            if (($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Package payload cannot contain a reparse point: $($_.FullName)"
            }
            $relative = $_.FullName.Substring($payload.Length + 1).Replace('\', '/')
            if ($relative -notmatch '^[A-Za-z0-9._/-]+$' -or $relative.Split('/') -contains '..') {
                throw "Package payload contains an unsafe path: $relative"
            }
            [ordered]@{
                component = Get-ComponentName $relative
                path = $relative
                size = [int64]$_.Length
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).
                    Hash.ToLowerInvariant()
                mediaType = Get-MediaType $relative
            }
        }
)
if ($artifacts.Count -eq 0) { throw 'Package payload is empty' }
$migrationCatalogPath = Join-Path $payload 'database\migrations.json'
$migrationCatalog = Get-Content -LiteralPath $migrationCatalogPath -Encoding utf8 -Raw |
    ConvertFrom-Json
if ([int]$migrationCatalog.schemaFrom -ne $SchemaFrom -or
        [int]$migrationCatalog.schemaTo -ne [int]$version.databaseSchemaVersion -or
        [string]$migrationCatalog.phase -cne $DatabasePhase -or
        [bool]$migrationCatalog.requiresDowntime -ne [bool]$RequiresDowntime) {
    throw 'Requested database range/phase contradicts the reviewed migration catalog'
}
$matrixPath = 'operations/compatibility-matrix.json'
if (@($artifacts | Where-Object { $_.path -ceq $matrixPath }).Count -ne 1) {
    throw "Payload must contain exactly one $matrixPath"
}
$matrix = Get-Content -LiteralPath (Join-Path $payload $matrixPath.Replace('/', '\')) `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ($ReleaseTier -ne 'TEST') {
    $supported = @($matrix.combinations | Where-Object {
            [string]$_.backend -ceq [string]$version.productVersion -and
            [string]$_.web -ceq [string]$version.productVersion -and
            [int]$_.appVersionCodeRange.minimum -le [int]$version.appVersionCode -and
            [int]$_.appVersionCodeRange.maximum -ge [int]$version.appVersionCode -and
            [int]$_.databaseSchema -eq [int]$version.databaseSchemaVersion -and
            [string]$_.status -ceq 'SUPPORTED'
        })
    if ($supported.Count -ne 1) {
        throw 'STAGING/PRODUCTION manifest requires exactly one SUPPORTED current combination'
    }
}

$rollbackClass = switch ($DatabasePhase) {
    'NONE' { 'APPLICATION_ONLY' }
    'CONTRACT' { 'RECOVERY_REQUIRED' }
    default {
        if ([bool]$migrationCatalog.backwardCompatible) {
            'FORWARD_COMPATIBLE_SCHEMA'
        }
        else { 'RECOVERY_REQUIRED' }
    }
}
$manifest = [ordered]@{
    schemaVersion = 1
    releaseId = $ReleaseId
    releaseTier = $ReleaseTier
    productVersion = [string]$version.productVersion
    createdAtUtc = $created.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    source = [ordered]@{
        commit = $SourceCommit
        dirty = $false
        treeSha256 = [string]$baseline.fileTreeSha256
        sourceDateEpoch = [int64]$baseline.sourceDateEpoch
    }
    components = [ordered]@{
        backend = [ordered]@{ version = [string]$version.productVersion }
        web = [ordered]@{ version = [string]$version.productVersion }
        app = [ordered]@{
            version = [string]$version.productVersion
            versionCode = [int]$version.appVersionCode
            minimumSupportedVersionCode = [int]$version.minimumSupportedAppVersionCode
        }
        database = [ordered]@{
            engine = 'mysql'
            migrationTool = 'flyway'
            schemaFrom = $SchemaFrom
            schemaTo = [int]$version.databaseSchemaVersion
            phase = $DatabasePhase
            backwardCompatible = [bool]$migrationCatalog.backwardCompatible
            requiresDowntime = [bool]$migrationCatalog.requiresDowntime
        }
    }
    compatibility = [ordered]@{
        matrixPath = $matrixPath
        unknownCombinationPolicy = 'BLOCKED'
    }
    artifacts = $artifacts
    signing = [ordered]@{
        algorithm = 'CMS-SHA256'
        required = $ReleaseTier -ne 'TEST'
        signaturePath = if ($ReleaseTier -eq 'TEST') { $null } else { $SignaturePath }
        certificateThumbprint = if ($ReleaseTier -eq 'TEST') {
            $null
        }
        else { $SigningCertificateThumbprint.ToLowerInvariant() }
    }
    rollback = [ordered]@{
        class = $rollbackClass
        previousReleaseRequired = $SchemaFrom -gt 0
        databaseRestoreRequired = $rollbackClass -eq 'RECOVERY_REQUIRED'
        instructions = 'Use only the typed rollback or isolated restore workflow described by this release.'
    }
}
[System.IO.File]::WriteAllText(
    $outputFullPath,
    ($manifest | ConvertTo-Json -Depth 10),
    (New-Object System.Text.UTF8Encoding($false))
)
if ($ReleaseTier -eq 'TEST') {
    try {
        $validationJson = & (Join-Path $PSScriptRoot 'Test-ReleaseManifest.ps1') `
            -ManifestPath $outputFullPath `
            -PackageRoot $payload `
            -AllowUnsignedTestManifest `
            -OutputFormat Json
        $validation = $validationJson | ConvertFrom-Json
    }
    catch {
        Remove-Item -LiteralPath $outputFullPath -Force -ErrorAction SilentlyContinue
        throw
    }
}
$report = [pscustomobject]@{
    status = if ($ReleaseTier -eq 'TEST') { 'PASS' } else { 'AWAITING_SIGNATURE' }
    releaseId = $ReleaseId
    artifactCount = $artifacts.Count
    manifestPath = $outputFullPath
    validationStatus = if ($ReleaseTier -eq 'TEST') {
        [string]$validation.status
    }
    else { 'SIGNATURE_PENDING' }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
