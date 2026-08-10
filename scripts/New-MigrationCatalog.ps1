[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MigrationRoot,
    [Parameter(Mandatory)][ValidateRange(0, 2147483647)][int]$SchemaFrom,
    [Parameter(Mandatory)][string]$OutputPath,
    [string]$ClassificationPath = '',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$version = Get-Content -LiteralPath (Join-Path $repositoryRoot 'VERSION.json') -Encoding utf8 -Raw |
    ConvertFrom-Json
$schemaTo = [int]$version.databaseSchemaVersion
if ($SchemaFrom -gt $schemaTo) { throw 'SchemaFrom cannot exceed the canonical schema version' }
$root = (Resolve-Path -LiteralPath $MigrationRoot).Path.TrimEnd('\', '/')
$parsed = @(
    Get-ChildItem -LiteralPath $root -File -Filter 'V*__*.sql' | ForEach-Object {
        if ($_.Name -notmatch '^V([1-9][0-9]*)__([A-Za-z0-9_]+)\.sql$') {
            throw "Migration file name is not canonical: $($_.Name)"
        }
        [pscustomobject]@{ version = [int]$Matches[1]; description = $Matches[2]; file = $_ }
    } | Sort-Object version
)
if ($parsed.Count -ne $schemaTo) {
    throw "Migration count $($parsed.Count) does not equal canonical schema version $schemaTo"
}
for ($expected = 1; $expected -le $schemaTo; $expected++) {
    if (@($parsed | Where-Object { $_.version -eq $expected }).Count -ne 1) {
        throw "Migration versions must be contiguous and unique at V$expected"
    }
}
$selected = @($parsed | Where-Object { $_.version -gt $SchemaFrom })
$classificationEntries = @()
$classificationHash = $null
if ($selected.Count -gt 0) {
    if ([string]::IsNullOrWhiteSpace($ClassificationPath)) {
        throw 'A non-empty range requires reviewed per-migration classification evidence'
    }
    $classificationFile = (Resolve-Path -LiteralPath $ClassificationPath).Path
    $classification = Get-Content -LiteralPath $classificationFile -Encoding utf8 -Raw |
        ConvertFrom-Json
    if ([int]$classification.schemaVersion -ne 1) {
        throw 'Unsupported migration classification evidence schemaVersion'
    }
    $classificationEntries = @($classification.entries)
    $classificationHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $classificationFile).
        Hash.ToLowerInvariant()
}

$phaseRank = @{ EXPAND = 1; MIGRATE = 2; CONTRACT = 3 }
$reviewed = @{}
foreach ($migration in $selected) {
    $matches = @($classificationEntries | Where-Object { [int]$_.version -eq $migration.version })
    if ($matches.Count -ne 1) { throw "V$($migration.version) needs exactly one classification" }
    $entry = $matches[0]
    if ([string]$entry.phase -notin @('EXPAND', 'MIGRATE', 'CONTRACT') -or
            [string]$entry.reviewStatus -cne 'APPROVED' -or
            [string]$entry.reviewedBy -notmatch '^[A-Za-z0-9@._-]{3,128}$' -or
            [string]::IsNullOrWhiteSpace([string]$entry.evidence)) {
        throw "V$($migration.version) classification is not approved and evidenced"
    }
    $approvedAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$entry.approvedAtUtc, [ref]$approvedAt) -or
            -not ([string]$entry.approvedAtUtc).EndsWith('Z')) {
        throw "V$($migration.version) classification approval time is invalid"
    }
    if ([string]$entry.phase -eq 'CONTRACT' -and [bool]$entry.backwardCompatible) {
        throw "V$($migration.version) CONTRACT cannot claim backward compatibility"
    }
    $reviewed[$migration.version] = $entry
}
$overallPhase = 'NONE'
$backwardCompatible = $true
$requiresDowntime = $false
if ($selected.Count -gt 0) {
    $strictest = $selected | ForEach-Object { $reviewed[$_.version] } |
        Sort-Object { $phaseRank[[string]$_.phase] } -Descending | Select-Object -First 1
    $overallPhase = [string]$strictest.phase
    $backwardCompatible = @($selected | Where-Object {
            -not [bool]$reviewed[$_.version].backwardCompatible
        }).Count -eq 0
    $requiresDowntime = @($selected | Where-Object {
            [bool]$reviewed[$_.version].requiresDowntime
        }).Count -gt 0
}
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
$outputParent = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "Output directory does not exist: $outputParent"
}
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Migration catalog already exists: $outputFullPath"
}
$catalog = [ordered]@{
    schemaVersion = 1
    engine = 'mysql'
    migrationTool = 'flyway'
    schemaFrom = $SchemaFrom
    schemaTo = $schemaTo
    phase = $overallPhase
    backwardCompatible = $backwardCompatible
    requiresDowntime = $requiresDowntime
    classificationConfirmed = $selected.Count -eq 0 -or $true
    classificationSha256 = $classificationHash
    migrations = @($selected | ForEach-Object {
        $review = $reviewed[$_.version]
        [ordered]@{
            version = $_.version
            description = $_.description
            script = $_.file.Name
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.file.FullName).
                Hash.ToLowerInvariant()
            phase = [string]$review.phase
            backwardCompatible = [bool]$review.backwardCompatible
            requiresDowntime = [bool]$review.requiresDowntime
            review = [ordered]@{
                status = [string]$review.reviewStatus
                reviewedBy = [string]$review.reviewedBy
                approvedAtUtc = [string]$review.approvedAtUtc
                evidence = [string]$review.evidence
            }
            execution = 'FLYWAY_CHECKSUM_GUARDED_ONCE'
        }
    })
}
[System.IO.File]::WriteAllText(
    $outputFullPath,
    ($catalog | ConvertTo-Json -Depth 10),
    (New-Object System.Text.UTF8Encoding($false))
)
$report = [pscustomobject]@{
    status = 'PASS'
    schemaFrom = $SchemaFrom
    schemaTo = $schemaTo
    migrationCount = $selected.Count
    phase = $overallPhase
    backwardCompatible = $backwardCompatible
    path = $outputFullPath
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
