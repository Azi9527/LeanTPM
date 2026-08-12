[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$sourceRoot = $PSScriptRoot
$releaseWork = Join-Path $sourceRoot 'release-work-v1'
$releaseZip = Join-Path $sourceRoot 'deliverables\LeanTPM-1.0.4-20260812.1-backend-web-v52.v1.zip'
$releaseManifest = Join-Path $releaseWork 'package\direct-release-manifest.json'
$templatePath = Join-Path $sourceRoot 'Invoke-LeanTpmDirectApplicationDeployment-1.0.4.ps1'
$readmePath = Join-Path $sourceRoot 'README.txt'
$outputPath = Join-Path $sourceRoot 'deliverables\production-1.0.4-20260812.1-direct-application-v1.zip'
$partialPath = $outputPath + '.partial'
$generatedRoot = Join-Path $sourceRoot 'operator-work-v1'
$generatedScript = Join-Path $generatedRoot 'Invoke-LeanTpmDirectApplicationDeployment-1.0.4.ps1'
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Get-Sha256([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
foreach ($path in @($releaseZip, $releaseManifest, $templatePath, $readmePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required operator input is missing: $path" }
}
foreach ($path in @($outputPath, $partialPath, $generatedRoot)) {
    if (Test-Path -LiteralPath $path) { throw "Operator output already exists: $path" }
}

$manifest = Get-Content -LiteralPath $releaseManifest -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceCommit = [string]$manifest.source.commit
$releaseItem = Get-Item $releaseZip
$manifestItem = Get-Item $releaseManifest
$text = [IO.File]::ReadAllText($templatePath)
$replacements = [ordered]@{
    '$releaseZipBytes = 0' = '$releaseZipBytes = ' + [string][long]$releaseItem.Length
    "`$releaseZipSha256 = '__RELEASE_ZIP_SHA256__'" = "`$releaseZipSha256 = '$(Get-Sha256 $releaseZip)'"
    '$releaseManifestBytes = 0' = '$releaseManifestBytes = ' + [string][long]$manifestItem.Length
    "`$releaseManifestSha256 = '__RELEASE_MANIFEST_SHA256__'" = "`$releaseManifestSha256 = '$(Get-Sha256 $releaseManifest)'"
    "`$sourceCommit = '__SOURCE_COMMIT__'" = "`$sourceCommit = '$sourceCommit'"
}
foreach ($entry in $replacements.GetEnumerator()) {
    if ([regex]::Matches($text, [regex]::Escape([string]$entry.Key)).Count -ne 1) {
        throw "Executor template placeholder was not unique: $($entry.Key)"
    }
    $text = $text.Replace([string]$entry.Key, [string]$entry.Value)
}
if ($text.Contains('__RELEASE_') -or $text.Contains('__SOURCE_COMMIT__')) { throw 'Executor template still contains placeholders' }
[void][scriptblock]::Create($text)
[IO.Directory]::CreateDirectory($generatedRoot) | Out-Null
[IO.File]::WriteAllText($generatedScript, $text, $utf8NoBom)

$files = @(
    [ordered]@{ path = 'Invoke-LeanTpmDirectApplicationDeployment-1.0.4.ps1'; source = $generatedScript },
    [ordered]@{ path = 'README.txt'; source = $readmePath }
)
$artifacts = @($files | ForEach-Object {
    $item = Get-Item $_.source
    [ordered]@{ path = $_.path; bytes = [long]$item.Length; sha256 = Get-Sha256 $_.source }
})
$operatorManifest = [ordered]@{
    schemaVersion = 1; operatorVersion = 1; packageType = 'DIRECT_APPLICATION_ONLY_BACKEND_WEB_V52'
    releaseId = '1.0.4-20260812.1'; sourceCommit = $sourceCommit
    releasePackage = [ordered]@{ bytes = [long]$releaseItem.Length; sha256 = Get-Sha256 $releaseZip }
    scope = [ordered]@{ backend = $true; web = $true; databaseMigrations = $false; app = $false }
    production = [ordered]@{ serverUuid = '007df095-92ef-11f1-8f53-00163e059faa'; schemaFrom = 52; schemaTo = 52 }
    rollback = 'APPLICATION_ROLLBACK_TO_1.0.3_V52'
    operatorFlow = @('PLAN_ONLY','CONFIRM_EXACT_PLAN_SHA256','BACKUP','SWITCH_BACKEND_WEB','VERIFY','APPLICATION_ROLLBACK_ON_FAILURE')
    artifacts = $artifacts
}
$operatorJson = $operatorManifest | ConvertTo-Json -Depth 8

try {
    $stream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($file in $files) {
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.source, $file.path, [IO.Compression.CompressionLevel]::Optimal)
            }
            $entry = $zip.CreateEntry('operator-manifest.json', [IO.Compression.CompressionLevel]::Optimal)
            $writer = New-Object IO.StreamWriter($entry.Open(), $utf8NoBom)
            try { $writer.Write($operatorJson) } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    $verify = [IO.Compression.ZipFile]::OpenRead($partialPath)
    try {
        $actual = @($verify.Entries | ForEach-Object { $_.FullName })
        $expected = @($artifacts.path) + 'operator-manifest.json'
        if ($actual.Count -ne 3 -or @($actual | Where-Object { $expected -notcontains $_ }).Count -ne 0) { throw 'Operator entry allowlist failed' }
        foreach ($name in $actual) {
            if ($name.Contains('..') -or $name.Contains('\') -or $name.StartsWith('/')) { throw "Unsafe operator entry: $name" }
        }
        foreach ($artifact in $artifacts) {
            $entry = $verify.GetEntry([string]$artifact.path)
            if ($null -eq $entry -or $entry.Length -ne [long]$artifact.bytes) { throw "Operator bytes changed: $($artifact.path)" }
        }
    } finally { $verify.Dispose() }
    Move-Item -LiteralPath $partialPath -Destination $outputPath
} catch {
    if (Test-Path -LiteralPath $partialPath) { [IO.File]::Delete($partialPath) }
    throw
}

[ordered]@{
    status = 'PASS'; path = $outputPath; bytes = (Get-Item $outputPath).Length; sha256 = Get-Sha256 $outputPath
    releaseZipBytes = [long]$releaseItem.Length; releaseZipSha256 = Get-Sha256 $releaseZip
    generatedExecutorBytes = (Get-Item $generatedScript).Length; generatedExecutorSha256 = Get-Sha256 $generatedScript
    entries = 3; appIncluded = $false; databaseMigrationsIncluded = $false
} | ConvertTo-Json -Depth 5
