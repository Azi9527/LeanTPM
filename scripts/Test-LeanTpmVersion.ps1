[CmdletBinding()]
param(
    [string]$RepositoryRoot = '',
    [ValidateSet('Text', 'Json')]
    [string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
}
else {
    $RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required JSON file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Encoding utf8 -Raw | ConvertFrom-Json
}

function Read-JsonWithComments {
    param([Parameter(Mandatory)][string]$Path)

    $source = Get-Content -LiteralPath $Path -Encoding utf8 -Raw
    $withoutBlockComments = [regex]::Replace($source, '/\*[\s\S]*?\*/', '')
    return $withoutBlockComments | ConvertFrom-Json
}

function Add-Mismatch {
    param(
        [System.Collections.Generic.List[string]]$Errors,
        [string]$Name,
        $Actual,
        $Expected
    )

    if ([string]$Actual -cne [string]$Expected) {
        $Errors.Add("$Name is '$Actual'; expected '$Expected'")
    }
}

$versionPath = Join-Path $RepositoryRoot 'VERSION.json'
$version = Read-JsonFile $versionPath
$errors = [System.Collections.Generic.List[string]]::new()
$semVerPattern = '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$'

if ([int]$version.schemaVersion -ne 1) {
    $errors.Add('VERSION.json schemaVersion must be 1')
}
if ([string]$version.productVersion -notmatch $semVerPattern) {
    $errors.Add("Invalid SemVer productVersion: $($version.productVersion)")
}
if ([int64]$version.appVersionCode -lt 1 -or [int64]$version.appVersionCode -gt [int]::MaxValue) {
    $errors.Add('appVersionCode must be a positive 32-bit integer')
}
if ([string]$version.appPackageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$') {
    $errors.Add('appPackageName must be a canonical reverse-DNS Android package name')
}
if ([int64]$version.minimumSupportedAppVersionCode -lt 1 -or
        [int64]$version.minimumSupportedAppVersionCode -gt [int64]$version.appVersionCode) {
    $errors.Add('minimumSupportedAppVersionCode must be positive and no greater than appVersionCode')
}

[xml]$pom = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'backend\pom.xml') -Encoding utf8 -Raw
$frontend = Read-JsonFile (Join-Path $RepositoryRoot 'frontend\package.json')
$frontendLockSource = Get-Content -LiteralPath (
    Join-Path $RepositoryRoot 'frontend\package-lock.json'
) -Encoding utf8 -Raw
$frontendLockVersions = [regex]::Matches(
    $frontendLockSource,
    '(?m)^\s*"version"\s*:\s*"([^"]+)"\s*,?\s*$'
)
if ($frontendLockVersions.Count -lt 2) {
    throw 'Unable to read top-level and root versions from frontend/package-lock.json'
}
$appPackage = Read-JsonFile (Join-Path $RepositoryRoot 'LeanTPM-APP\package.json')
$appManifest = Read-JsonWithComments (Join-Path $RepositoryRoot 'LeanTPM-APP\manifest.json')
$compatibility = Read-JsonFile (Join-Path $RepositoryRoot 'release\compatibility-matrix.json')
$exampleManifest = Read-JsonFile (Join-Path $RepositoryRoot 'release\release-manifest.example.json')

Add-Mismatch $errors 'backend/pom.xml version' $pom.project.version $version.productVersion
Add-Mismatch $errors 'frontend/package.json version' $frontend.version $version.productVersion
Add-Mismatch $errors 'frontend/package-lock.json version' `
    $frontendLockVersions[0].Groups[1].Value $version.productVersion
Add-Mismatch $errors 'frontend/package-lock.json root version' `
    $frontendLockVersions[1].Groups[1].Value $version.productVersion
Add-Mismatch $errors 'LeanTPM-APP/package.json version' $appPackage.version $version.productVersion
Add-Mismatch $errors 'LeanTPM-APP/manifest.json versionName' $appManifest.versionName $version.productVersion
Add-Mismatch $errors 'LeanTPM-APP/manifest.json versionCode' $appManifest.versionCode $version.appVersionCode
Add-Mismatch $errors 'LeanTPM-APP Android package name' `
    $appManifest.'app-plus'.distribute.android.packagename $version.appPackageName
Add-Mismatch $errors 'compatibility matrix productVersion' $compatibility.productVersion $version.productVersion
Add-Mismatch $errors 'compatibility matrix databaseSchemaVersion' $compatibility.databaseSchemaVersion $version.databaseSchemaVersion
Add-Mismatch $errors 'example manifest productVersion' $exampleManifest.productVersion $version.productVersion
Add-Mismatch $errors 'example manifest backend version' $exampleManifest.components.backend.version $version.productVersion
Add-Mismatch $errors 'example manifest web version' $exampleManifest.components.web.version $version.productVersion
Add-Mismatch $errors 'example manifest app version' $exampleManifest.components.app.version $version.productVersion
Add-Mismatch $errors 'example manifest app versionCode' $exampleManifest.components.app.versionCode $version.appVersionCode
Add-Mismatch $errors 'example manifest minimum app versionCode' `
    $exampleManifest.components.app.minimumSupportedVersionCode $version.minimumSupportedAppVersionCode
Add-Mismatch $errors 'example manifest database schemaTo' `
    $exampleManifest.components.database.schemaTo $version.databaseSchemaVersion
Add-Mismatch $errors 'example manifest schemaVersion' `
    $exampleManifest.schemaVersion $version.releaseManifestSchemaVersion

$gradleSource = Get-Content -LiteralPath (
    Join-Path $RepositoryRoot 'frontend\android\app\build.gradle'
) -Encoding utf8 -Raw
$gradleVersionName = [regex]::Match($gradleSource, '(?m)^\s*versionName\s+"([^"]+)"\s*$')
$gradleVersionCode = [regex]::Match($gradleSource, '(?m)^\s*versionCode\s+(\d+)\s*$')
if (-not $gradleVersionName.Success -or -not $gradleVersionCode.Success) {
    $errors.Add('Unable to read Android versionName/versionCode from frontend/android/app/build.gradle')
}
else {
    Add-Mismatch $errors 'Capacitor Android versionName' $gradleVersionName.Groups[1].Value $version.productVersion
    Add-Mismatch $errors 'Capacitor Android versionCode' $gradleVersionCode.Groups[1].Value $version.appVersionCode
}

$appVersionSource = Get-Content -LiteralPath (
    Join-Path $RepositoryRoot 'LeanTPM-APP\utils\version.js'
) -Encoding utf8 -Raw
$fallbackVersion = [regex]::Match($appVersionSource, "let version = '([^']+)'")
$fallbackCode = [regex]::Match($appVersionSource, 'let versionCode = (\d+)')
if (-not $fallbackVersion.Success -or -not $fallbackCode.Success) {
    $errors.Add('Unable to read uni-app runtime fallback version')
}
else {
    Add-Mismatch $errors 'uni-app runtime fallback version' $fallbackVersion.Groups[1].Value $version.productVersion
    Add-Mismatch $errors 'uni-app runtime fallback versionCode' $fallbackCode.Groups[1].Value $version.appVersionCode
}

$migrations = Get-ChildItem -LiteralPath (
    Join-Path $RepositoryRoot 'backend\src\main\resources\db\migration'
) -File -Filter 'V*.sql' | ForEach-Object {
    if ($_.Name -match '^V(\d+)__') {
        [pscustomobject]@{ Version = [int]$Matches[1]; Name = $_.Name }
    }
}
$orderedVersions = @($migrations | Sort-Object Version | Select-Object -ExpandProperty Version)
if ($orderedVersions.Count -eq 0) {
    $errors.Add('No Flyway migrations were found')
}
else {
    $duplicates = @($orderedVersions | Group-Object | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) {
        $errors.Add("Duplicate Flyway versions: $($duplicates.Name -join ', ')")
    }
    $expectedVersions = @(1..[int]$version.databaseSchemaVersion)
    if (($orderedVersions -join ',') -cne ($expectedVersions -join ',')) {
        $errors.Add(
            "Flyway versions must be continuous V1-V$($version.databaseSchemaVersion); found: " +
            ($orderedVersions -join ',')
        )
    }
}

$report = [pscustomobject]@{
    status = if ($errors.Count -eq 0) { 'PASS' } else { 'FAIL' }
    productVersion = [string]$version.productVersion
    appVersionCode = [int]$version.appVersionCode
    appPackageName = [string]$version.appPackageName
    minimumSupportedAppVersionCode = [int]$version.minimumSupportedAppVersionCode
    databaseSchemaVersion = [int]$version.databaseSchemaVersion
    releaseManifestSchemaVersion = [int]$version.releaseManifestSchemaVersion
    migrationCount = $orderedVersions.Count
    errors = @($errors)
}

if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 6 -Compress
}
else {
    $report | Format-List
}

if ($errors.Count -gt 0) {
    exit 1
}
