[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReleaseRoot,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ReleaseRoot).Path.TrimEnd('\', '/')
$manifestPath = Join-Path $root 'release-manifest.json'
$catalogPath = Join-Path $root 'payload\database\migrations.json'
$jarPath = Join-Path $root 'payload\backend\leantpm-backend.jar'
foreach ($path in @($manifestPath, $catalogPath, $jarPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Migrator release asset is missing: $path"
    }
}
$manifest = Get-Content -LiteralPath $manifestPath -Encoding utf8 -Raw | ConvertFrom-Json
$catalog = Get-Content -LiteralPath $catalogPath -Encoding utf8 -Raw | ConvertFrom-Json
$migrations = @($catalog.migrations)
if ([int]$catalog.schemaFrom -ne [int]$manifest.components.database.schemaFrom -or
        [int]$catalog.schemaTo -ne [int]$manifest.components.database.schemaTo -or
        $migrations.Count -ne ([int]$catalog.schemaTo - [int]$catalog.schemaFrom)) {
    throw 'Migration catalog range contradicts the verified release manifest'
}

if ($migrations.Count -gt 0) {
    Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $stream = [System.IO.File]::Open(
        $jarPath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    $archive = $null
    try {
        $archive = New-Object System.IO.Compression.ZipArchive(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        foreach ($migration in $migrations) {
            $script = [string]$migration.script
            if ($script -notmatch '^V[1-9][0-9]*__[A-Za-z0-9_]+\.sql$' -or
                    [string]$migration.sha256 -notmatch '^[0-9a-f]{64}$') {
                throw 'Migration catalog contains an unsafe script or checksum'
            }
            $entryName = "BOOT-INF/classes/db/migration/$script"
            $entries = @($archive.Entries | Where-Object { $_.FullName -ceq $entryName })
            if ($entries.Count -ne 1) {
                throw "Backend JAR must contain exactly one reviewed migration entry: $entryName"
            }
            $entryStream = $entries[0].Open()
            $hasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $actual = [BitConverter]::ToString($hasher.ComputeHash($entryStream)).
                    Replace('-', '').ToLowerInvariant()
            }
            finally {
                $hasher.Dispose()
                $entryStream.Dispose()
            }
            if ($actual -cne [string]$migration.sha256) {
                throw "Backend JAR migration bytes differ from the reviewed catalog: $script"
            }
        }
    }
    finally {
        if ($null -ne $archive) { $archive.Dispose() }
        $stream.Dispose()
    }
}

$report = [pscustomobject]@{
    status = 'PASS'
    schemaFrom = [int]$catalog.schemaFrom
    schemaTo = [int]$catalog.schemaTo
    migrationCount = $migrations.Count
    jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
