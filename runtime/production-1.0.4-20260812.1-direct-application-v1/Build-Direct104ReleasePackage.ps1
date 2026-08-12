[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$releaseId = '1.0.4-20260812.1'
$workRoot = Join-Path $PSScriptRoot 'release-work-v1'
$packageRoot = Join-Path $workRoot 'package'
$verifyRoot = Join-Path $workRoot 'verify'
$outputRoot = Join-Path $PSScriptRoot 'deliverables'
$zipPath = Join-Path $outputRoot 'LeanTPM-1.0.4-20260812.1-backend-web-v52.v1.zip'
$partialPath = $zipPath + '.partial'
$manifestPath = Join-Path $packageRoot 'direct-release-manifest.json'
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

foreach ($path in @($workRoot, $zipPath, $partialPath)) {
    if (Test-Path -LiteralPath $path) { throw "Release output already exists: $path" }
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$sourceCommit = ([string](& git -C $repositoryRoot rev-parse HEAD 2>&1)).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'Unable to resolve source commit' }
$releaseStatus = @(& git -C $repositoryRoot status --porcelain -- backend frontend LeanTPM-APP release VERSION.json runtime/production-1.0.4-20260812.1-direct-application-v1 2>&1)
if ($LASTEXITCODE -ne 0 -or $releaseStatus.Count -ne 0) {
    throw ('Release input paths must match the committed source: ' + ($releaseStatus -join '; '))
}

$buildStartedAtUtc = [DateTime]::UtcNow
$backendPom = Join-Path $repositoryRoot 'backend\pom.xml'
& mvn.cmd '-Dleantpm.build.directory=target-codex-104-release' -f $backendPom clean package
if ($LASTEXITCODE -ne 0) { throw 'Clean Backend 1.0.4 build failed' }
$frontendRoot = Join-Path $repositoryRoot 'frontend'
& npm.cmd --prefix $frontendRoot run build
if ($LASTEXITCODE -ne 0) { throw 'Clean Web 1.0.4 build failed' }

$backendSource = Join-Path $repositoryRoot 'backend\target-codex-104-release\leantpm-backend-1.0.4.jar'
$webSource = Join-Path $repositoryRoot 'frontend\dist'
if (-not (Test-Path -LiteralPath $backendSource -PathType Leaf)) { throw 'Backend 1.0.4 build is missing' }
if (-not (Test-Path -LiteralPath $webSource -PathType Container)) { throw 'Web build is missing' }
if ((Get-Item -LiteralPath $backendSource).LastWriteTimeUtc -lt $buildStartedAtUtc.AddSeconds(-2) -or
        (Get-Item -LiteralPath (Join-Path $webSource 'index.html')).LastWriteTimeUtc -lt $buildStartedAtUtc.AddSeconds(-2)) {
    throw 'A packaged build artifact predates the controlled clean build'
}
$postBuildStatus = @(& git -C $repositoryRoot status --porcelain -- backend frontend LeanTPM-APP release VERSION.json runtime/production-1.0.4-20260812.1-direct-application-v1 2>&1)
if ($LASTEXITCODE -ne 0 -or $postBuildStatus.Count -ne 0) {
    throw ('Controlled build changed committed release inputs: ' + ($postBuildStatus -join '; '))
}

[IO.Directory]::CreateDirectory((Join-Path $packageRoot 'payload\backend')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $packageRoot 'payload\web')) | Out-Null
Copy-Item -LiteralPath $backendSource -Destination (Join-Path $packageRoot 'payload\backend\leantpm-backend.jar')
Copy-Item -Path (Join-Path $webSource '*') -Destination (Join-Path $packageRoot 'payload\web') -Recurse

$payloadFiles = @(Get-ChildItem -LiteralPath (Join-Path $packageRoot 'payload') -File -Recurse | Sort-Object FullName)
$artifacts = @()
foreach ($file in $payloadFiles) {
    $relative = $file.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
    $component = if ($relative.StartsWith('payload/backend/')) { 'backend' }
        elseif ($relative.StartsWith('payload/web/')) { 'web' }
        else { throw "Unexpected payload path: $relative" }
    $artifacts += [ordered]@{
        component = $component
        path = $relative
        bytes = [long]$file.Length
        sha256 = Get-Sha256 $file.FullName
    }
}
if (@($artifacts | Where-Object { $_.component -ceq 'backend' }).Count -ne 1 -or
        @($artifacts | Where-Object { $_.component -ceq 'web' }).Count -lt 2) {
    throw 'Backend/Web payload shape is invalid'
}

$treeLines = @($artifacts | ForEach-Object { '{0}`t{1}`t{2}' -f $_.path, $_.bytes, $_.sha256 })
$treeText = ($treeLines -join "`n") + "`n"
$treeBytes = [Text.Encoding]::UTF8.GetBytes($treeText)
$sha = [Security.Cryptography.SHA256]::Create()
try { $treeSha256 = [BitConverter]::ToString($sha.ComputeHash($treeBytes)).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }

$manifest = [ordered]@{
    schemaVersion = 1
    mode = 'DIRECT_APPLICATION_ONLY_V52'
    releaseId = $releaseId
    productVersion = '1.0.4'
    source = [ordered]@{ commit = $sourceCommit; releaseInputsMatchCommit = $true; protectedNonReleaseChangesExcluded = $true }
    scope = [ordered]@{ backendIncluded = $true; webIncluded = $true; databaseMigrationsIncluded = $false; appIncluded = $false }
    database = [ordered]@{
        engine = 'mysql'; schemaFrom = 52; schemaTo = 52
        expectedServerUuid = '007df095-92ef-11f1-8f53-00163e059faa'
        backwardCompatible = $true; rollbackClass = 'APPLICATION_ONLY'; databaseRestoreRequired = $false
    }
    runtime = [ordered]@{ flywayEnabled = $false; expectedInfoVersion = '1.0.4'; expectedInfoSchema = 52 }
    currentProduction = [ordered]@{ releaseId = '1.0.3-20260811.1'; databaseSchemaVersion = 52 }
    payloadFileCount = $artifacts.Count
    payloadTreeSha256 = $treeSha256
    artifacts = $artifacts
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), $utf8NoBom)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    $stream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($file in @(Get-ChildItem -LiteralPath $packageRoot -File -Recurse | Sort-Object FullName)) {
                $entryName = $file.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal)
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }

    [IO.Directory]::CreateDirectory($verifyRoot) | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($partialPath, $verifyRoot)
    $verifiedManifestPath = Join-Path $verifyRoot 'direct-release-manifest.json'
    if ((Get-Sha256 $verifiedManifestPath) -cne (Get-Sha256 $manifestPath)) { throw 'Extracted manifest changed' }
    $verified = Get-Content -LiteralPath $verifiedManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$verified.releaseId -cne $releaseId -or [bool]$verified.scope.appIncluded -or
            [bool]$verified.scope.databaseMigrationsIncluded -or [int]$verified.database.schemaFrom -ne 52 -or
            [int]$verified.database.schemaTo -ne 52) { throw 'Extracted release scope changed' }
    foreach ($artifact in @($verified.artifacts)) {
        $path = Join-Path $verifyRoot ([string]$artifact.path).Replace('/', '\')
        if ((Get-Item $path).Length -ne [long]$artifact.bytes -or (Get-Sha256 $path) -cne [string]$artifact.sha256) {
            throw "Extracted artifact changed: $($artifact.path)"
        }
    }
    Move-Item -LiteralPath $partialPath -Destination $zipPath
} catch {
    if (Test-Path -LiteralPath $partialPath) { [IO.File]::Delete($partialPath) }
    throw
}

[ordered]@{
    status = 'PASS'; releaseId = $releaseId; sourceCommit = $sourceCommit
    zipPath = $zipPath; zipBytes = (Get-Item $zipPath).Length; zipSha256 = Get-Sha256 $zipPath
    manifestBytes = (Get-Item $manifestPath).Length; manifestSha256 = Get-Sha256 $manifestPath
    payloadFileCount = $artifacts.Count; payloadTreeSha256 = $treeSha256
    backendSha256 = [string](@($artifacts | Where-Object { $_.path -ceq 'payload/backend/leantpm-backend.jar' })[0].sha256)
    webIndexSha256 = [string](@($artifacts | Where-Object { $_.path -ceq 'payload/web/index.html' })[0].sha256)
    appIncluded = $false; databaseMigrationsIncluded = $false; verifiedFromExtractedZip = $true
} | ConvertTo-Json -Depth 5
