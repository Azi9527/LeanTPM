[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$releaseId = '1.0.4-20260812.2'
$runtimeRoot = Join-Path $repositoryRoot 'runtime\production-1.0.4-20260812.2-backend-v53-operator-v1'
$workRoot = Join-Path $runtimeRoot 'release-work-v1'
$packageRoot = Join-Path $workRoot 'package'
$verifyRoot = Join-Path $workRoot 'verify'
$outputRoot = Join-Path $runtimeRoot 'deliverables'
$zipPath = Join-Path $outputRoot 'LeanTPM-1.0.4-20260812.2-backend-v53.v1.zip'
$partialPath = $zipPath + '.partial'
$manifestPath = Join-Path $packageRoot 'direct-release-manifest.json'
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

foreach ($path in @($workRoot, $zipPath, $partialPath)) {
    if (Test-Path -LiteralPath $path) { throw "Release output already exists: $path" }
}

$compatibilityPath = Join-Path $repositoryRoot 'release\compatibility-matrix.json'
$compatibility = Get-Content -LiteralPath $compatibilityPath -Raw -Encoding UTF8 | ConvertFrom-Json
$matchingCombinations = @($compatibility.combinations | Where-Object {
        [string]$_.backend -ceq '1.0.4' -and
        [string]$_.web -ceq '1.0.4' -and
        [int]$_.appVersionCodeRange.minimum -eq 101 -and
        [int]$_.appVersionCodeRange.maximum -eq 103 -and
        [int]$_.databaseSchema -eq 53
    })
if ([int]$compatibility.schemaVersion -ne 1 -or
        [string]$compatibility.productVersion -cne '1.0.4' -or
        [int]$compatibility.databaseSchemaVersion -ne 53 -or
        $matchingCombinations.Count -ne 1 -or
        [string]$matchingCombinations[0].status -cne 'SUPPORTED') {
    throw 'Backend 1.0.4/Web 1.0.4/APP 101-103/DB 53 compatibility must have exactly one SUPPORTED entry before release packaging'
}

$sourceCommit = ([string](& git -C $repositoryRoot rev-parse HEAD 2>&1)).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve source commit'
}
$sourceStatus = @(& git -C $repositoryRoot status --porcelain=v1 --untracked-files=all 2>&1)
if ($LASTEXITCODE -ne 0 -or $sourceStatus.Count -ne 0) {
    throw ('Release source must be completely clean: ' + ($sourceStatus -join '; '))
}

[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$buildStartedAtUtc = [DateTime]::UtcNow
$backendPom = Join-Path $repositoryRoot 'backend\pom.xml'
& mvn.cmd '-Dleantpm.build.directory=target-codex-backend-v53' -f $backendPom clean package
if ($LASTEXITCODE -ne 0) { throw 'Clean Backend 1.0.4/V53 build failed' }

$backendSource = Join-Path $repositoryRoot 'backend\target-codex-backend-v53\leantpm-backend-1.0.4.jar'
$migrationSource = Join-Path $repositoryRoot 'backend\src\main\resources\db\migration\V53__inspection_abnormal_measures.sql'
foreach ($path in @($backendSource, $migrationSource)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required release input is missing: $path" }
}
if ((Get-Item -LiteralPath $backendSource).LastWriteTimeUtc -lt $buildStartedAtUtc.AddSeconds(-2)) {
    throw 'Packaged Backend predates the controlled clean build'
}
$postBuildStatus = @(& git -C $repositoryRoot status --porcelain=v1 --untracked-files=all 2>&1)
if ($LASTEXITCODE -ne 0 -or $postBuildStatus.Count -ne 0) {
    throw ('Controlled build changed committed release inputs: ' + ($postBuildStatus -join '; '))
}

$backendDirectory = Join-Path $packageRoot 'payload\backend'
$migrationDirectory = Join-Path $packageRoot 'payload\database\migrations'
[IO.Directory]::CreateDirectory($backendDirectory) | Out-Null
[IO.Directory]::CreateDirectory($migrationDirectory) | Out-Null
Copy-Item -LiteralPath $backendSource -Destination (Join-Path $backendDirectory 'leantpm-backend.jar')
Copy-Item -LiteralPath $migrationSource -Destination (Join-Path $migrationDirectory 'V53__inspection_abnormal_measures.sql')

$packagedMigration = Join-Path $migrationDirectory 'V53__inspection_abnormal_measures.sql'
$catalogPath = Join-Path $packageRoot 'payload\database\migrations.json'
$catalog = [ordered]@{
    schemaVersion = 1
    engine = 'mysql'
    migrationTool = 'flyway'
    schemaFrom = 52
    schemaTo = 53
    phase = 'EXPAND'
    backwardCompatible = $true
    requiresDowntime = $false
    classificationConfirmed = $true
    migrations = @(
        [ordered]@{
            version = 53
            description = 'inspection abnormal measures'
            script = 'V53__inspection_abnormal_measures.sql'
            bytes = [long](Get-Item -LiteralPath $packagedMigration).Length
            sha256 = Get-Sha256 $packagedMigration
            phase = 'EXPAND'
            backwardCompatible = $true
            requiresDowntime = $false
            execution = 'FLYWAY_CHECKSUM_GUARDED_ONCE'
            review = [ordered]@{
                status = 'APPROVED'
                evidence = 'reports/ai/2026-08-12-backend-v53-release-preparation.md'
            }
        }
    )
}
[IO.File]::WriteAllText($catalogPath, ($catalog | ConvertTo-Json -Depth 8), $utf8NoBom)

$payloadFiles = @(Get-ChildItem -LiteralPath (Join-Path $packageRoot 'payload') -File -Recurse | Sort-Object FullName)
$artifacts = @()
foreach ($file in $payloadFiles) {
    $relative = $file.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
    $component = if ($relative.StartsWith('payload/backend/')) { 'backend' }
        elseif ($relative.StartsWith('payload/database/')) { 'database' }
        else { throw "Unexpected payload path: $relative" }
    $artifacts += [ordered]@{
        component = $component
        path = $relative
        bytes = [long]$file.Length
        sha256 = Get-Sha256 $file.FullName
    }
}
if (@($artifacts | Where-Object { $_.component -ceq 'backend' }).Count -ne 1 -or
        @($artifacts | Where-Object { $_.component -ceq 'database' }).Count -ne 2) {
    throw 'Backend/V53 payload shape is invalid'
}

$treeLines = @($artifacts | ForEach-Object { '{0}`t{1}`t{2}' -f $_.path, $_.bytes, $_.sha256 })
$treeBytes = [Text.Encoding]::UTF8.GetBytes(($treeLines -join "`n") + "`n")
$sha = [Security.Cryptography.SHA256]::Create()
try { $treeSha256 = [BitConverter]::ToString($sha.ComputeHash($treeBytes)).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }

$manifest = [ordered]@{
    schemaVersion = 1
    mode = 'BACKEND_ONLY_DATABASE_MIGRATION'
    releaseId = $releaseId
    productVersion = '1.0.4'
    source = [ordered]@{
        commit = $sourceCommit
        releaseInputsMatchCommit = $true
        protectedNonReleaseChangesExcluded = $true
    }
    scope = [ordered]@{
        backendIncluded = $true
        webIncluded = $false
        databaseMigrationsIncluded = $true
        appIncluded = $false
    }
    database = [ordered]@{
        engine = 'mysql'
        schemaFrom = 52
        schemaTo = 53
        phase = 'EXPAND'
        expectedServerUuid = '007df095-92ef-11f1-8f53-00163e059faa'
        backwardCompatible = $true
        rollbackClass = 'RECOVERY_REQUIRED'
        databaseRestoreRequired = $true
    }
    runtime = [ordered]@{
        flywayEnabled = $false
        expectedInfoVersion = '1.0.4'
        expectedInfoSchema = 53
    }
    currentProduction = [ordered]@{
        releaseId = '1.0.4-20260812.1'
        databaseSchemaVersion = 52
    }
    payloadFileCount = $artifacts.Count
    payloadTreeSha256 = $treeSha256
    artifacts = $artifacts
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), $utf8NoBom)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixedTimestamp = [DateTimeOffset]::Parse('2026-08-12T00:00:00Z')
try {
    $stream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($file in @(Get-ChildItem -LiteralPath $packageRoot -File -Recurse | Sort-Object FullName)) {
                $entryName = $file.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
                $entry = $zip.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $fixedTimestamp
                $input = [IO.File]::OpenRead($file.FullName)
                $output = $entry.Open()
                try { $input.CopyTo($output) }
                finally { $output.Dispose(); $input.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }

    [IO.Directory]::CreateDirectory($verifyRoot) | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($partialPath, $verifyRoot)
    $verifiedManifestPath = Join-Path $verifyRoot 'direct-release-manifest.json'
    if ((Get-Sha256 $verifiedManifestPath) -cne (Get-Sha256 $manifestPath)) {
        throw 'Extracted manifest changed'
    }
    $verified = Get-Content -LiteralPath $verifiedManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$verified.mode -cne 'BACKEND_ONLY_DATABASE_MIGRATION' -or
            [string]$verified.releaseId -cne $releaseId -or
            [bool]$verified.scope.webIncluded -or [bool]$verified.scope.appIncluded -or
            -not [bool]$verified.scope.databaseMigrationsIncluded -or
            [int]$verified.database.schemaFrom -ne 52 -or [int]$verified.database.schemaTo -ne 53) {
        throw 'Extracted release scope changed'
    }
    foreach ($artifact in @($verified.artifacts)) {
        $path = Join-Path $verifyRoot ([string]$artifact.path).Replace('/', '\')
        if ((Get-Item -LiteralPath $path).Length -ne [long]$artifact.bytes -or
                (Get-Sha256 $path) -cne [string]$artifact.sha256) {
            throw "Extracted artifact changed: $($artifact.path)"
        }
    }
    Move-Item -LiteralPath $partialPath -Destination $zipPath
} catch {
    if (Test-Path -LiteralPath $partialPath) { [IO.File]::Delete($partialPath) }
    throw
}

[ordered]@{
    status = 'PASS'
    releaseId = $releaseId
    sourceCommit = $sourceCommit
    zipPath = $zipPath
    zipBytes = (Get-Item -LiteralPath $zipPath).Length
    zipSha256 = Get-Sha256 $zipPath
    manifestBytes = (Get-Item -LiteralPath $manifestPath).Length
    manifestSha256 = Get-Sha256 $manifestPath
    payloadFileCount = $artifacts.Count
    payloadTreeSha256 = $treeSha256
    backendSha256 = [string](@($artifacts | Where-Object { $_.path -ceq 'payload/backend/leantpm-backend.jar' })[0].sha256)
    migrationSha256 = [string](@($artifacts | Where-Object { $_.path -ceq 'payload/database/migrations/V53__inspection_abnormal_measures.sql' })[0].sha256)
    webIncluded = $false
    appIncluded = $false
    databaseMigrationsIncluded = $true
    verifiedFromExtractedZip = $true
} | ConvertTo-Json -Depth 5
