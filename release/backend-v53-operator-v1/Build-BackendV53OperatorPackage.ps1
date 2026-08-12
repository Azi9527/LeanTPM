[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$releaseId = '1.0.4-20260812.2'
$runtimeRoot = Join-Path $repositoryRoot 'runtime\production-1.0.4-20260812.2-backend-v53-operator-v1'
$releaseZip = Join-Path $runtimeRoot 'deliverables\LeanTPM-1.0.4-20260812.2-backend-v53.v1.zip'
$releaseManifest = Join-Path $runtimeRoot 'release-work-v1\package\direct-release-manifest.json'
$executorTemplate = Join-Path $PSScriptRoot 'Invoke-LeanTpmBackendV53Deployment.ps1'
$testSource = Join-Path $PSScriptRoot 'Test-BackendV53ReleaseOperator.ps1'
$readmeSource = Join-Path $PSScriptRoot 'README.txt'
$releaseBuilderSource = Join-Path $PSScriptRoot 'Build-BackendV53ReleasePackage.ps1'
$operatorBuilderSource = Join-Path $PSScriptRoot 'Build-BackendV53OperatorPackage.ps1'
$workRoot = Join-Path $runtimeRoot 'operator-work-v1'
$packageRoot = Join-Path $workRoot 'package'
$verifyRoot = Join-Path $workRoot 'verify'
$outputRoot = Join-Path $runtimeRoot 'deliverables'
$operatorZip = Join-Path $outputRoot 'LeanTPM-1.0.4-20260812.2-backend-v53-operator.v1.zip'
$partialPath = $operatorZip + '.partial'
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

foreach ($path in @($workRoot, $operatorZip, $partialPath)) {
    if (Test-Path -LiteralPath $path) { throw "Operator output already exists: $path" }
}
foreach ($path in @(
        $releaseZip, $releaseManifest, $executorTemplate, $testSource, $readmeSource,
        $releaseBuilderSource, $operatorBuilderSource
    )) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required operator input is missing: $path" }
}

$sourceCommit = ([string](& git -C $repositoryRoot rev-parse HEAD 2>&1)).Trim()
$sourceStatus = @(& git -C $repositoryRoot status --porcelain=v1 --untracked-files=all 2>&1)
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$' -or $sourceStatus.Count -ne 0) {
    throw 'Operator source must be a completely clean committed tree'
}
$manifest = Get-Content -LiteralPath $releaseManifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]$manifest.releaseId -cne $releaseId -or [string]$manifest.source.commit -cne $sourceCommit -or
        [string]$manifest.mode -cne 'BACKEND_ONLY_DATABASE_MIGRATION' -or
        [bool]$manifest.scope.webIncluded -or [bool]$manifest.scope.appIncluded -or
        -not [bool]$manifest.scope.databaseMigrationsIncluded -or
        [int]$manifest.database.schemaFrom -ne 52 -or [int]$manifest.database.schemaTo -ne 53) {
    throw 'Release manifest is not the clean committed Backend/V53 candidate'
}

$executor = [IO.File]::ReadAllText($executorTemplate)
$contracts = [ordered]@{
    '__RELEASE_ZIP_SHA256__' = Get-Sha256 $releaseZip
    '__RELEASE_MANIFEST_SHA256__' = Get-Sha256 $releaseManifest
    '__SOURCE_COMMIT__' = $sourceCommit
}
foreach ($entry in $contracts.GetEnumerator()) {
    if ([regex]::Matches($executor, [regex]::Escape([string]$entry.Key)).Count -ne 1) {
        throw "Executor template placeholder count changed: $($entry.Key)"
    }
    $executor = $executor.Replace([string]$entry.Key, [string]$entry.Value)
}
$executor = $executor.Replace('$releaseZipBytes = 0', '$releaseZipBytes = ' + (Get-Item -LiteralPath $releaseZip).Length)
$executor = $executor.Replace('$releaseManifestBytes = 0', '$releaseManifestBytes = ' + (Get-Item -LiteralPath $releaseManifest).Length)
if ([regex]::IsMatch($executor, '__[A-Z][A-Z0-9_]+__') -or [regex]::IsMatch($executor, '[^\x00-\x7F]')) {
    throw 'Substituted executor contains a placeholder or non-ASCII byte'
}
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseInput($executor, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Substituted executor does not parse under Windows PowerShell syntax' }

[IO.Directory]::CreateDirectory($packageRoot) | Out-Null
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$executorPath = Join-Path $packageRoot 'Invoke-LeanTpmBackendV53Deployment.ps1'
[IO.File]::WriteAllText($executorPath, $executor, $utf8NoBom)
Copy-Item -LiteralPath $testSource -Destination (Join-Path $packageRoot 'Test-BackendV53ReleaseOperator.ps1')
Copy-Item -LiteralPath $readmeSource -Destination (Join-Path $packageRoot 'README.txt')
Copy-Item -LiteralPath $releaseBuilderSource -Destination (Join-Path $packageRoot 'Build-BackendV53ReleasePackage.ps1')
Copy-Item -LiteralPath $operatorBuilderSource -Destination (Join-Path $packageRoot 'Build-BackendV53OperatorPackage.ps1')

$files = @(Get-ChildItem -LiteralPath $packageRoot -File | Sort-Object Name)
$operatorManifest = [ordered]@{
    schemaVersion = 1
    mode = 'BACKEND_ONLY_DATABASE_MIGRATION'
    releaseId = $releaseId
    sourceCommit = $sourceCommit
    releaseZip = [ordered]@{
        path = 'D:\LeanTPM\temp\LeanTPM-1.0.4-20260812.2-backend-v53.v1.zip'
        bytes = [long](Get-Item -LiteralPath $releaseZip).Length
        sha256 = Get-Sha256 $releaseZip
    }
    releaseManifest = [ordered]@{
        bytes = [long](Get-Item -LiteralPath $releaseManifest).Length
        sha256 = Get-Sha256 $releaseManifest
    }
    files = @($files | ForEach-Object {
        [ordered]@{ path = $_.Name; bytes = [long]$_.Length; sha256 = Get-Sha256 $_.FullName }
    })
}
$operatorManifestPath = Join-Path $packageRoot 'operator-manifest.json'
[IO.File]::WriteAllText($operatorManifestPath, ($operatorManifest | ConvertTo-Json -Depth 7), $utf8NoBom)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixedTimestamp = [DateTimeOffset]::Parse('2026-08-12T00:00:00Z')
try {
    $stream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($file in @(Get-ChildItem -LiteralPath $packageRoot -File | Sort-Object Name)) {
                $entry = $zip.CreateEntry($file.Name, [IO.Compression.CompressionLevel]::Optimal)
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
    $verified = Get-Content -LiteralPath (Join-Path $verifyRoot 'operator-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$verified.releaseId -cne $releaseId -or [string]$verified.sourceCommit -cne $sourceCommit -or
            [string]$verified.releaseZip.sha256 -cne (Get-Sha256 $releaseZip)) {
        throw 'Extracted operator manifest changed'
    }
    foreach ($file in @($verified.files)) {
        $path = Join-Path $verifyRoot ([string]$file.path)
        if ((Get-Item -LiteralPath $path).Length -ne [long]$file.bytes -or
                (Get-Sha256 $path) -cne [string]$file.sha256) {
            throw "Extracted operator file changed: $($file.path)"
        }
    }
    Move-Item -LiteralPath $partialPath -Destination $operatorZip
} catch {
    if (Test-Path -LiteralPath $partialPath) { [IO.File]::Delete($partialPath) }
    throw
}

[ordered]@{
    status = 'PASS'
    releaseId = $releaseId
    sourceCommit = $sourceCommit
    operatorZip = $operatorZip
    operatorZipBytes = (Get-Item -LiteralPath $operatorZip).Length
    operatorZipSha256 = Get-Sha256 $operatorZip
    executorBytes = (Get-Item -LiteralPath $executorPath).Length
    executorSha256 = Get-Sha256 $executorPath
    releaseZipBytes = (Get-Item -LiteralPath $releaseZip).Length
    releaseZipSha256 = Get-Sha256 $releaseZip
    verifiedFromExtractedZip = $true
} | ConvertTo-Json -Depth 5
