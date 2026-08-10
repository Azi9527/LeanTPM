[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ManifestPath,
    [Parameter(Mandatory)]
    [string]$PackageRoot,
    [switch]$AllowUnsignedTestManifest,
    [string]$TrustedCertificateThumbprint = '',
    [ValidateSet('Text', 'Json')]
    [string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Require-Property {
    param($Object, [string]$Name, [string]$Context)

    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Context is missing required property '$Name'"
    }
}

function Assert-OnlyProperties {
    param($Object, [string[]]$Allowed, [string]$Context)

    if ($null -eq $Object) { throw "$Context must be an object" }
    foreach ($property in @($Object.PSObject.Properties)) {
        if ([string]$property.Name -notin $Allowed) {
            throw "$Context contains unexpected property '$($property.Name)'"
        }
    }
}

function Assert-PackageRelativePath {
    param([string]$Value, [string]$Context)

    if ([string]::IsNullOrWhiteSpace($Value) -or
            [System.IO.Path]::IsPathRooted($Value) -or
            $Value.Contains('\') -or
            $Value.Contains(':') -or
            $Value.Length -gt 240) {
        throw "$Context path must be a short POSIX-style relative path: $Value"
    }
    $segments = $Value.Split('/')
    if ($segments.Count -eq 0 -or @($segments | Where-Object {
                [string]::IsNullOrWhiteSpace($_) -or $_ -eq '.' -or $_ -eq '..'
            }).Count -gt 0) {
        throw "$Context path contains traversal or empty segments: $Value"
    }
    if ($Value -notmatch '^[A-Za-z0-9._/-]+$') {
        throw "$Context path contains unsupported characters: $Value"
    }
}

function Resolve-ContainedFile {
    param([string]$Root, [string]$RelativePath, [string]$Context)

    Assert-PackageRelativePath $RelativePath $Context
    $rootPath = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\', '/')
    $candidate = Join-Path $rootPath ($RelativePath.Replace('/', '\'))
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Context file is missing: $RelativePath"
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    $prefix = $rootPath + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Context path escapes package root: $RelativePath"
    }
    $item = Get-Item -LiteralPath $resolved -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Context cannot be a reparse point: $RelativePath"
    }
    return $item
}

function Test-DetachedCmsSignature {
    param(
        [string]$ContentPath,
        [string]$SignaturePath,
        [string]$ExpectedThumbprint
    )

    if ([string]::IsNullOrWhiteSpace($ExpectedThumbprint)) {
        throw 'A trusted certificate thumbprint is required for signed manifests'
    }
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $content = [System.IO.File]::ReadAllBytes($ContentPath)
    $signature = [System.IO.File]::ReadAllBytes($SignaturePath)
    $cms = New-Object System.Security.Cryptography.Pkcs.SignedCms(
        (New-Object System.Security.Cryptography.Pkcs.ContentInfo(, $content)),
        $true
    )
    $cms.Decode($signature)
    $cms.CheckSignature($true)
    if ($cms.SignerInfos.Count -ne 1 -or $null -eq $cms.SignerInfos[0].Certificate) {
        throw 'Manifest signature must contain exactly one signing certificate'
    }
    $actual = $cms.SignerInfos[0].Certificate.Thumbprint.Replace(' ', '')
    $expected = $ExpectedThumbprint.Replace(' ', '')
    if (-not $actual.Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest signing certificate does not match trusted thumbprint: $actual"
    }
    $certificate = $cms.SignerInfos[0].Certificate
    $now = [DateTime]::UtcNow
    if ($now -lt $certificate.NotBefore.ToUniversalTime() -or
            $now -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'Manifest signing certificate is outside its validity period'
    }
    $hasCodeSigningEku = @($certificate.Extensions | Where-Object {
            $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]
        } | ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object { $_ } | Where-Object {
            $_.Value -eq '1.3.6.1.5.5.7.3.3'
        }).Count -gt 0
    if (-not $hasCodeSigningEku) { throw 'Manifest signer lacks the code-signing EKU' }
    $chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode =
        [System.Security.Cryptography.X509Certificates.X509RevocationMode]::Online
    $chain.ChainPolicy.RevocationFlag =
        [System.Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
    if (-not $chain.Build($certificate)) {
        throw 'Manifest signer chain or revocation check failed'
    }
}

$resolvedManifest = (Resolve-Path -LiteralPath $ManifestPath).Path
$resolvedPackageRoot = (Resolve-Path -LiteralPath $PackageRoot).Path
$schemaPath = Join-Path (Split-Path -Parent $resolvedManifest) 'release-manifest.schema.json'
if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
    $repositorySchema = Join-Path (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path `
        'release\release-manifest.schema.json'
    $schemaPath = $repositorySchema
}
$schema = Get-Content -LiteralPath $schemaPath -Encoding utf8 -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath $resolvedManifest -Encoding utf8 -Raw | ConvertFrom-Json

Assert-OnlyProperties $manifest @(
    'schemaVersion', 'releaseId', 'releaseTier', 'productVersion', 'createdAtUtc',
    'source', 'components', 'compatibility', 'artifacts', 'signing', 'rollback'
) 'release manifest'

foreach ($name in @(
        'schemaVersion', 'releaseId', 'releaseTier', 'productVersion', 'createdAtUtc',
        'source', 'components', 'compatibility', 'artifacts', 'signing', 'rollback'
    )) {
    Require-Property $manifest $name 'release manifest'
}
if ([int]$schema.properties.schemaVersion.const -ne 1 -or [int]$manifest.schemaVersion -ne 1) {
    throw 'Unsupported release-manifest schemaVersion; only version 1 is accepted'
}
if ([string]$manifest.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$manifest.releaseId -match '^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        [string]$manifest.releaseId -match '\.$') {
    throw "Invalid releaseId: $($manifest.releaseId)"
}
if ([string]$manifest.releaseTier -notin @('TEST', 'STAGING', 'PRODUCTION')) {
    throw "Invalid releaseTier: $($manifest.releaseTier)"
}
if ([string]$manifest.productVersion -notmatch `
        '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$') {
    throw "Invalid productVersion: $($manifest.productVersion)"
}
$created = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParse([string]$manifest.createdAtUtc, [ref]$created) -or
        -not ([string]$manifest.createdAtUtc).EndsWith('Z')) {
    throw 'createdAtUtc must be an ISO-8601 UTC timestamp ending in Z'
}

Require-Property $manifest.source 'commit' 'source'
Require-Property $manifest.source 'dirty' 'source'
Require-Property $manifest.source 'treeSha256' 'source'
Assert-OnlyProperties $manifest.source @('commit', 'dirty', 'treeSha256', 'sourceDateEpoch') 'source'
if ([string]$manifest.source.commit -notmatch '^[0-9a-f]{40}$') {
    throw 'source.commit must be a lowercase 40-character Git commit'
}
if ([bool]$manifest.source.dirty) {
    throw 'Dirty source trees are not releaseable'
}
if ([string]$manifest.source.treeSha256 -cnotmatch '^[0-9a-f]{64}$') {
    throw 'source.treeSha256 must be a lowercase SHA-256 digest'
}

foreach ($componentName in @('backend', 'web', 'app', 'database')) {
    Require-Property $manifest.components $componentName 'components'
}
Assert-OnlyProperties $manifest.components @('backend', 'web', 'app', 'database') 'components'
foreach ($componentName in @('backend', 'web')) {
    $component = $manifest.components.PSObject.Properties[$componentName].Value
    Require-Property $component 'version' "components.$componentName"
    if ([string]$component.version -cne [string]$manifest.productVersion) {
        throw "components.$componentName.version must equal productVersion"
    }
}
Assert-OnlyProperties $manifest.components.backend @('version') 'components.backend'
Assert-OnlyProperties $manifest.components.web @('version') 'components.web'
Assert-OnlyProperties $manifest.components.app @(
    'version', 'versionCode', 'minimumSupportedVersionCode', 'includedInRelease'
) 'components.app'
Require-Property $manifest.components.app 'version' 'components.app'
Require-Property $manifest.components.app 'versionCode' 'components.app'
Require-Property $manifest.components.app 'minimumSupportedVersionCode' 'components.app'
$appSemVerPattern = '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$'
if ([string]$manifest.components.app.version -notmatch $appSemVerPattern -or
        [int64]$manifest.components.app.versionCode -lt 1 -or
        [int64]$manifest.components.app.minimumSupportedVersionCode -lt 1 -or
        [int64]$manifest.components.app.minimumSupportedVersionCode -gt
        [int64]$manifest.components.app.versionCode) {
    throw 'APP versionCode values are invalid or minimum exceeds current'
}
$appIncludedProperty = $manifest.components.app.PSObject.Properties['includedInRelease']
if ($null -ne $appIncludedProperty -and $appIncludedProperty.Value -isnot [bool]) {
    throw 'components.app.includedInRelease must be a JSON boolean'
}
$appArtifactIncluded = if ($null -eq $appIncludedProperty) {
    $true
}
else {
    [bool]$appIncludedProperty.Value
}
Require-Property $manifest.components.database 'schemaTo' 'components.database'
Require-Property $manifest.components.database 'phase' 'components.database'
foreach ($name in @(
        'engine', 'migrationTool', 'schemaFrom', 'schemaTo', 'phase',
        'backwardCompatible', 'requiresDowntime'
    )) {
    Require-Property $manifest.components.database $name 'components.database'
}
Assert-OnlyProperties $manifest.components.database @(
    'engine', 'migrationTool', 'schemaFrom', 'schemaTo', 'phase',
    'backwardCompatible', 'requiresDowntime'
) 'components.database'
if ($null -eq $manifest.components.database.schemaFrom -or
        [string]$manifest.components.database.engine -cne 'mysql' -or
        [string]$manifest.components.database.migrationTool -cne 'flyway' -or
        [int]$manifest.components.database.schemaFrom -lt 0 -or
        [int]$manifest.components.database.schemaTo -lt
            [int]$manifest.components.database.schemaFrom) {
    throw 'components.database engine, migrationTool or schema range is invalid'
}
if ([string]$manifest.components.database.phase -notin @('NONE', 'EXPAND', 'MIGRATE', 'CONTRACT')) {
    throw 'Database phase must be NONE, EXPAND, MIGRATE or CONTRACT'
}

Require-Property $manifest.compatibility 'matrixPath' 'compatibility'
Require-Property $manifest.compatibility 'unknownCombinationPolicy' 'compatibility'
Assert-OnlyProperties $manifest.compatibility @(
    'matrixPath', 'unknownCombinationPolicy'
) 'compatibility'
if ([string]$manifest.compatibility.unknownCombinationPolicy -cne 'BLOCKED') {
    throw 'Unknown compatibility combinations must be BLOCKED'
}

$artifacts = @($manifest.artifacts)
if ($artifacts.Count -lt 1) {
    throw 'release manifest must contain at least one artifact'
}
$approvedPaths = New-Object 'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($artifact in $artifacts) {
    Assert-OnlyProperties $artifact @(
        'component', 'path', 'size', 'sha256', 'mediaType'
    ) 'artifact'
    foreach ($name in @('component', 'path', 'size', 'sha256')) {
        Require-Property $artifact $name 'artifact'
    }
    $relativePath = [string]$artifact.path
    if ([string]$artifact.component -notin @(
            'backend', 'web', 'app', 'database', 'config', 'operations'
        )) {
        throw "artifact component is invalid: $($artifact.component)"
    }
    if ([int64]$artifact.size -lt 0) { throw "artifact size is invalid: $relativePath" }
    if (-not $approvedPaths.Add($relativePath)) {
        throw "Duplicate artifact path (case-insensitive): $relativePath"
    }
    $file = Resolve-ContainedFile $resolvedPackageRoot $relativePath 'artifact'
    if ([int64]$artifact.size -ne [int64]$file.Length) {
        throw "Artifact hash/size integrity mismatch: $relativePath"
    }
    if ([string]$artifact.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Artifact sha256 must be lowercase hexadecimal: $relativePath"
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    if ($actualHash -cne [string]$artifact.sha256) {
        throw "Artifact SHA-256 hash mismatch: $relativePath"
    }
}

$migrationCatalogArtifacts = @($artifacts | Where-Object {
        [string]$_.component -ceq 'database' -and
        [string]$_.path -ceq 'database/migrations.json'
    })
if ($migrationCatalogArtifacts.Count -ne 1) {
    throw 'release manifest must contain exactly one database/migrations.json catalog artifact'
}
$migrationCatalogPath = Join-Path $resolvedPackageRoot 'database\migrations.json'
$migrationCatalog = Get-Content -LiteralPath $migrationCatalogPath -Encoding utf8 -Raw |
    ConvertFrom-Json
if ([int]$migrationCatalog.schemaVersion -ne 1 -or
        [string]$migrationCatalog.engine -cne 'mysql' -or
        [string]$migrationCatalog.migrationTool -cne 'flyway') {
    throw 'Unsupported database migration catalog contract'
}
if ([int]$migrationCatalog.schemaFrom -ne [int]$manifest.components.database.schemaFrom -or
        [int]$migrationCatalog.schemaTo -ne [int]$manifest.components.database.schemaTo -or
        [string]$migrationCatalog.phase -cne [string]$manifest.components.database.phase -or
        [bool]$migrationCatalog.requiresDowntime -ne
            [bool]$manifest.components.database.requiresDowntime) {
    throw 'Migration catalog range/phase contradicts the release manifest database contract'
}
$catalogMigrations = @($migrationCatalog.migrations)
$expectedMigrationCount = [int]$migrationCatalog.schemaTo - [int]$migrationCatalog.schemaFrom
if ($expectedMigrationCount -lt 0 -or $catalogMigrations.Count -ne $expectedMigrationCount) {
    throw 'Migration catalog count does not match schemaFrom/schemaTo'
}
if ($expectedMigrationCount -eq 0 -and [string]$migrationCatalog.phase -cne 'NONE') {
    throw 'An empty migration catalog must use phase NONE'
}
if ($expectedMigrationCount -gt 0 -and (
        [string]$migrationCatalog.phase -eq 'NONE' -or
        -not [bool]$migrationCatalog.classificationConfirmed
    )) {
    throw 'A non-empty migration catalog needs a confirmed non-NONE phase'
}
for ($index = 0; $index -lt $catalogMigrations.Count; $index++) {
    $migration = $catalogMigrations[$index]
    $expectedVersion = [int]$migrationCatalog.schemaFrom + $index + 1
    if ([int]$migration.version -ne $expectedVersion -or
            [string]$migration.phase -notin @('EXPAND', 'MIGRATE', 'CONTRACT') -or
            [string]$migration.execution -cne 'FLYWAY_CHECKSUM_GUARDED_ONCE') {
        throw "Migration catalog entry V$expectedVersion has an invalid order or execution contract"
    }
    $script = [string]$migration.script
    if ($script -notmatch '^V[1-9][0-9]*__[A-Za-z0-9_]+\.sql$') {
        throw "Migration catalog contains an unsafe script name: $script"
    }
    $artifactPath = "database/migrations/$script"
    $scriptArtifacts = @($artifacts | Where-Object {
            [string]$_.component -ceq 'database' -and [string]$_.path -ceq $artifactPath
        })
    if ($scriptArtifacts.Count -ne 1 -or
            [string]$scriptArtifacts[0].sha256 -cne [string]$migration.sha256) {
        throw "Migration catalog script is absent or has a contradictory hash: $script"
    }
}
$phaseRank = @{ EXPAND = 1; MIGRATE = 2; CONTRACT = 3 }
if ($catalogMigrations.Count -gt 0) {
    $strictestCatalogPhase = $catalogMigrations | Sort-Object {
        $phaseRank[[string]$_.phase]
    } -Descending | Select-Object -First 1
    if ([string]$strictestCatalogPhase.phase -cne [string]$migrationCatalog.phase -or
            [bool]$migrationCatalog.backwardCompatible -ne
                [bool]$manifest.components.database.backwardCompatible) {
        throw 'Migration catalog overall phase/compatibility does not match its strictest entries'
    }
}

Assert-PackageRelativePath ([string]$manifest.compatibility.matrixPath) 'compatibility matrix'
if (-not $approvedPaths.Contains([string]$manifest.compatibility.matrixPath)) {
    throw 'compatibility.matrixPath must identify an approved artifact'
}
$requiredCoreArtifacts = [ordered]@{
    'backend/leantpm-backend.jar' = 'backend'
    'web/index.html' = 'web'
    'database/migrations.json' = 'database'
    'operations/compatibility-matrix.json' = 'operations'
}
if ($appArtifactIncluded) {
    $requiredCoreArtifacts['app/LeanTPM.apk'] = 'app'
}
elseif (@($artifacts | Where-Object { [string]$_.component -ceq 'app' }).Count -ne 0) {
    throw 'APP-excluded release manifest must not list APP artifacts'
}
foreach ($requiredPath in $requiredCoreArtifacts.Keys) {
    $coreMatches = @($artifacts | Where-Object {
            [string]$_.path -ceq $requiredPath -and
            [string]$_.component -ceq $requiredCoreArtifacts[$requiredPath]
        })
    if ($coreMatches.Count -ne 1) {
        throw "release manifest must contain exactly one core artifact: $requiredPath"
    }
}
$compatibilityPath = Join-Path $resolvedPackageRoot `
    ([string]$manifest.compatibility.matrixPath).Replace('/', '\')
$matrix = Get-Content -LiteralPath $compatibilityPath -Encoding utf8 -Raw | ConvertFrom-Json
Assert-OnlyProperties $matrix @(
    'schemaVersion', 'productVersion', 'databaseSchemaVersion', 'combinations',
    'unknownCombinationPolicy'
) 'compatibility matrix'
foreach ($name in @(
        'schemaVersion', 'productVersion', 'databaseSchemaVersion', 'combinations',
        'unknownCombinationPolicy'
    )) {
    Require-Property $matrix $name 'compatibility matrix'
}
if ([int]$matrix.schemaVersion -ne 1 -or
        [string]$matrix.productVersion -cne [string]$manifest.productVersion -or
        [int]$matrix.databaseSchemaVersion -ne
            [int]$manifest.components.database.schemaTo -or
        [string]$matrix.unknownCombinationPolicy -cne 'BLOCKED') {
    throw 'Compatibility matrix metadata contradicts the release manifest'
}
$matchingCombinations = @(@($matrix.combinations) | Where-Object {
        [string]$_.backend -ceq [string]$manifest.components.backend.version -and
        [string]$_.web -ceq [string]$manifest.components.web.version -and
        [int]$_.databaseSchema -eq [int]$manifest.components.database.schemaTo -and
        [int]$_.appVersionCodeRange.minimum -le [int]$manifest.components.app.versionCode -and
        [int]$_.appVersionCodeRange.maximum -ge [int]$manifest.components.app.versionCode
    })
if ($matchingCombinations.Count -ne 1) {
    throw 'Compatibility matrix must contain exactly one current component combination'
}
$combination = $matchingCombinations[0]
Assert-OnlyProperties $combination @(
    'backend', 'web', 'appVersionCodeRange', 'databaseSchema', 'status', 'notes'
) 'compatibility combination'
Assert-OnlyProperties $combination.appVersionCodeRange @('minimum', 'maximum') `
    'compatibility appVersionCodeRange'
if ([string]$combination.status -notin @(
        'SUPPORTED', 'TRANSITIONAL', 'FORCE_UPGRADE', 'CANDIDATE_UNVERIFIED',
        'BLOCKED', 'UNKNOWN'
    ) -or
        [string]::IsNullOrWhiteSpace([string]$combination.notes)) {
    throw 'Compatibility combination status or notes are invalid'
}
if ([string]$manifest.releaseTier -ne 'TEST' -and
        [string]$combination.status -cne 'SUPPORTED') {
    throw 'STAGING/PRODUCTION releases require a SUPPORTED compatibility combination'
}

$actualPaths = Get-ChildItem -LiteralPath $resolvedPackageRoot -Recurse -File -Force | ForEach-Object {
    $relative = $_.FullName.Substring($resolvedPackageRoot.TrimEnd('\', '/').Length + 1)
    $relative.Replace('\', '/')
}
$unexpected = @($actualPaths | Where-Object { -not $approvedPaths.Contains($_) })
if ($unexpected.Count -gt 0) {
    throw "Unexpected extra package files: $($unexpected -join ', ')"
}

foreach ($name in @('algorithm', 'required', 'signaturePath', 'certificateThumbprint')) {
    Require-Property $manifest.signing $name 'signing'
}
Assert-OnlyProperties $manifest.signing @(
    'algorithm', 'required', 'signaturePath', 'certificateThumbprint'
) 'signing'
if ([string]$manifest.signing.algorithm -cne 'CMS-SHA256') {
    throw 'Only CMS-SHA256 detached manifest signatures are accepted'
}
$signatureRequired = [bool]$manifest.signing.required -or
    [string]$manifest.releaseTier -eq 'PRODUCTION'
if (-not $signatureRequired) {
    if (-not $AllowUnsignedTestManifest) {
        throw 'Unsigned test manifest rejected; use AllowUnsignedTestManifest only in isolated tests'
    }
}
else {
    if ([string]::IsNullOrWhiteSpace([string]$manifest.signing.signaturePath)) {
        throw 'A detached signature is required for this manifest'
    }
    Assert-PackageRelativePath ([string]$manifest.signing.signaturePath) 'signature'
    $signatureFile = Join-Path (Split-Path -Parent $resolvedManifest) `
        ([string]$manifest.signing.signaturePath).Replace('/', '\')
    if (-not (Test-Path -LiteralPath $signatureFile -PathType Leaf)) {
        throw "Detached signature file is missing: $signatureFile"
    }
    if ([string]::IsNullOrWhiteSpace($TrustedCertificateThumbprint)) {
        throw 'A trusted certificate thumbprint is required to verify signed manifests'
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$manifest.signing.certificateThumbprint) -and
            -not ([string]$manifest.signing.certificateThumbprint).Equals(
                $TrustedCertificateThumbprint,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Manifest certificate thumbprint does not match the trusted thumbprint'
    }
    Test-DetachedCmsSignature $resolvedManifest $signatureFile $TrustedCertificateThumbprint
}

foreach ($name in @('class', 'previousReleaseRequired', 'databaseRestoreRequired')) {
    Require-Property $manifest.rollback $name 'rollback'
}
Assert-OnlyProperties $manifest.rollback @(
    'class', 'previousReleaseRequired', 'databaseRestoreRequired', 'instructions'
) 'rollback'
if ([string]$manifest.rollback.class -notin @(
        'APPLICATION_ONLY', 'FORWARD_COMPATIBLE_SCHEMA', 'RECOVERY_REQUIRED'
    )) {
    throw 'rollback.class is invalid'
}
if ([bool]$manifest.rollback.previousReleaseRequired -ne
        ([int]$manifest.components.database.schemaFrom -gt 0)) {
    throw 'rollback.previousReleaseRequired must match whether a previous schema/release exists'
}
$databasePhase = [string]$manifest.components.database.phase
$rollbackClass = [string]$manifest.rollback.class
$rollbackRank = @{
    APPLICATION_ONLY = 0
    FORWARD_COMPATIBLE_SCHEMA = 1
    RECOVERY_REQUIRED = 2
}
$minimumRollbackClass = if ($databasePhase -eq 'CONTRACT' -or
        -not [bool]$manifest.components.database.backwardCompatible) {
    'RECOVERY_REQUIRED'
}
elseif ($databasePhase -in @('EXPAND', 'MIGRATE')) {
    'FORWARD_COMPATIBLE_SCHEMA'
}
else {
    'APPLICATION_ONLY'
}
if (($rollbackRank[$rollbackClass] -lt $rollbackRank[$minimumRollbackClass]) -or
        ([bool]$manifest.rollback.databaseRestoreRequired -ne
            ($rollbackClass -eq 'RECOVERY_REQUIRED'))) {
    throw 'rollback contract contradicts the database migration phase'
}

$report = [pscustomobject]@{
    status = 'PASS'
    releaseId = [string]$manifest.releaseId
    releaseTier = [string]$manifest.releaseTier
    productVersion = [string]$manifest.productVersion
    databaseSchemaFrom = [int]$manifest.components.database.schemaFrom
    databaseSchemaVersion = [int]$manifest.components.database.schemaTo
    appArtifactIncluded = $appArtifactIncluded
    artifactCount = $artifacts.Count
    packageRoot = $resolvedPackageRoot
    signatureVerified = $signatureRequired
}

if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 4 -Compress
}
else {
    $report | Format-List
}
