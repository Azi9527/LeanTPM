[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CatalogPath,
    [Parameter(Mandatory)][string]$MigrationRoot,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$canonical = Get-Content -LiteralPath (Join-Path $repositoryRoot 'VERSION.json') -Encoding utf8 -Raw |
    ConvertFrom-Json
$catalogFile = (Resolve-Path -LiteralPath $CatalogPath).Path
$root = (Resolve-Path -LiteralPath $MigrationRoot).Path.TrimEnd('\', '/')
$catalog = Get-Content -LiteralPath $catalogFile -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$catalog.schemaVersion -ne 1 -or [string]$catalog.engine -cne 'mysql' -or
        [string]$catalog.migrationTool -cne 'flyway') {
    throw 'Unsupported migration catalog contract'
}
if ([int]$catalog.schemaTo -ne [int]$canonical.databaseSchemaVersion -or
        [int]$catalog.schemaFrom -lt 0 -or [int]$catalog.schemaFrom -gt [int]$catalog.schemaTo) {
    throw 'Migration catalog range does not match VERSION.json'
}
$expectedCount = [int]$catalog.schemaTo - [int]$catalog.schemaFrom
$migrations = @($catalog.migrations)
if ($migrations.Count -ne $expectedCount) {
    throw 'Migration catalog count does not match schemaFrom/schemaTo'
}
if ($expectedCount -eq 0 -and [string]$catalog.phase -cne 'NONE') {
    throw 'An empty migration catalog must use phase NONE'
}
if ($expectedCount -gt 0 -and (
        [string]$catalog.phase -eq 'NONE' -or -not [bool]$catalog.classificationConfirmed
    )) {
    throw 'A non-empty migration catalog needs a confirmed non-NONE phase'
}
$phaseRank = @{ EXPAND = 1; MIGRATE = 2; CONTRACT = 3 }
$strictestPhase = 'NONE'
$allBackwardCompatible = $true
$anyDowntime = $false
$approvedScripts = New-Object 'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
for ($index = 0; $index -lt $migrations.Count; $index++) {
    $migration = $migrations[$index]
    $expectedVersion = [int]$catalog.schemaFrom + $index + 1
    if ([int]$migration.version -ne $expectedVersion) {
        throw "Migration catalog is not contiguous at V$expectedVersion"
    }
    if ([string]$migration.phase -notin @('EXPAND', 'MIGRATE', 'CONTRACT') -or
            [string]$migration.execution -cne 'FLYWAY_CHECKSUM_GUARDED_ONCE' -or
            [string]$migration.review.status -cne 'APPROVED' -or
            [string]::IsNullOrWhiteSpace([string]$migration.review.evidence)) {
        throw "Migration V$expectedVersion has an unsupported execution contract"
    }
    if ($strictestPhase -eq 'NONE' -or
            $phaseRank[[string]$migration.phase] -gt $phaseRank[$strictestPhase]) {
        $strictestPhase = [string]$migration.phase
    }
    if (-not [bool]$migration.backwardCompatible) { $allBackwardCompatible = $false }
    if ([bool]$migration.requiresDowntime) { $anyDowntime = $true }
    $script = [string]$migration.script
    if ($script -notmatch '^V[1-9][0-9]*__[A-Za-z0-9_]+\.sql$' -or
            -not $approvedScripts.Add($script)) {
        throw "Migration V$expectedVersion has an unsafe or duplicate script"
    }
    $scriptPath = Join-Path $root $script
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw "Migration script is missing: $script"
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $scriptPath).Hash.ToLowerInvariant()
    if ($actualHash -cne [string]$migration.sha256) {
        throw "Migration checksum mismatch: $script"
    }
}
if ($expectedCount -gt 0 -and (
        $strictestPhase -cne [string]$catalog.phase -or
        $allBackwardCompatible -ne [bool]$catalog.backwardCompatible -or
        $anyDowntime -ne [bool]$catalog.requiresDowntime
    )) {
    throw 'Migration catalog overall policy does not equal the strictest reviewed entry'
}
$report = [pscustomobject]@{
    status = 'PASS'
    schemaFrom = [int]$catalog.schemaFrom
    schemaTo = [int]$catalog.schemaTo
    migrationCount = $migrations.Count
    phase = [string]$catalog.phase
    path = $catalogFile
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
