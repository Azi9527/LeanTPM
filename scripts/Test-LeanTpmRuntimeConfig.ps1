[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RuntimeConfigRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$ExpectedReleaseId,
    [Parameter(Mandatory)][string]$ExpectedConfigId,
    [Parameter(Mandatory)][string]$ExpectedProductVersion,
    [Parameter(Mandatory)][int]$ExpectedDatabaseSchemaVersion,
    [Parameter(Mandatory)][string]$ExpectedDatabaseHost,
    [Parameter(Mandatory)][int]$ExpectedDatabasePort,
    [Parameter(Mandatory)][string]$ExpectedDatabaseName,
    [Parameter(Mandatory)][string]$ExpectedDirectorySha256,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Assert-OnlyProperties {
    param($Object, [string[]]$Allowed, [string]$Context)

    if ($null -eq $Object) { throw "$Context must be an object" }
    $actual = @($Object.PSObject.Properties.Name)
    if (@($actual | Where-Object { $Allowed -notcontains $_ }).Count -gt 0 -or
            @($Allowed | Where-Object { $actual -notcontains $_ }).Count -gt 0) {
        throw "$Context has unknown or missing fields"
    }
}

if ($ExpectedReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $ExpectedConfigId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $ExpectedDirectorySha256 -notmatch '^[0-9a-f]{64}$' -or
        $ExpectedDatabaseSchemaVersion -lt 1 -or
        $ExpectedDatabasePort -lt 1 -or $ExpectedDatabasePort -gt 65535 -or
        $ExpectedDatabaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Runtime configuration expectations are invalid'
}
$resolvedData = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$expectedRoot = [IO.Path]::GetFullPath(
    (Join-Path $resolvedData ("config\versions\{0}" -f $ExpectedConfigId))
).TrimEnd('\')
$resolvedConfigRoot = (Resolve-Path -LiteralPath $RuntimeConfigRoot).Path.TrimEnd('\', '/')
if (-not $resolvedConfigRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        ((Get-Item -LiteralPath $resolvedConfigRoot).Attributes -band
            [IO.FileAttributes]::ReparsePoint)) {
    throw 'Runtime configuration must use the exact host-owned immutable release directory'
}
$expectedFiles = @('effective-config.json', 'leantpm.env', 'secret-references.json')
$actualFiles = @(Get-ChildItem -LiteralPath $resolvedConfigRoot -Recurse -File -Force)
$actualDirectories = @(Get-ChildItem -LiteralPath $resolvedConfigRoot -Recurse -Directory -Force)
if ($actualDirectories.Count -gt 0 -or $actualFiles.Count -ne $expectedFiles.Count -or
        @($actualFiles | Where-Object {
                $_.DirectoryName -cne $resolvedConfigRoot -or
                $_.Name -notin $expectedFiles -or
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
            }).Count -gt 0) {
    throw 'Runtime configuration directory contains unexpected paths or reparse points'
}
$digestReport = & (Join-Path $PSScriptRoot 'Get-LeanTpmDirectoryDigest.ps1') `
    -DirectoryPath $resolvedConfigRoot -OutputFormat Json | ConvertFrom-Json
if ([string]$digestReport.digest -cne $ExpectedDirectorySha256) {
    throw 'Runtime configuration directory digest does not match the approved plan'
}

$effectivePath = Join-Path $resolvedConfigRoot 'effective-config.json'
$environmentPath = Join-Path $resolvedConfigRoot 'leantpm.env'
$secretReferencePath = Join-Path $resolvedConfigRoot 'secret-references.json'
$config = Get-Content -LiteralPath $effectivePath -Encoding utf8 -Raw | ConvertFrom-Json
Assert-OnlyProperties $config @(
    'schemaVersion', 'serverAddress', 'serverPort', 'database', 'uploadDir',
    'corsAllowedOrigins', 'releaseVersion', 'databaseSchemaVersion'
) 'effective config'
Assert-OnlyProperties $config.database @('url', 'username') 'effective config database'
$databaseUrl = [string]$config.database.url
$databaseMatch = [regex]::Match(
    $databaseUrl,
    '^jdbc:mysql://(?<host>[A-Za-z0-9.-]+):(?<port>[0-9]{1,5})/(?<database>[A-Za-z0-9_]+)(?:\?|$)'
)
if ([int]$config.schemaVersion -ne 1 -or
        [string]$config.serverAddress -cne '127.0.0.1' -or
        [int]$config.serverPort -lt 1 -or [int]$config.serverPort -gt 65535 -or
        -not $databaseMatch.Success -or
        -not $databaseMatch.Groups['host'].Value.Equals(
            $ExpectedDatabaseHost,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        [int]$databaseMatch.Groups['port'].Value -ne $ExpectedDatabasePort -or
        $databaseMatch.Groups['database'].Value -cne $ExpectedDatabaseName -or
        $databaseUrl -notmatch '(?i)(?:\?|&)sslMode=VERIFY_IDENTITY(?:&|$)' -or
        $databaseUrl -match '(?i)(?:\?|&)useSSL=false(?:&|$)' -or
        $databaseUrl -match '(?i)(?:password|passwd|pwd|user)=' -or
        [string]::IsNullOrWhiteSpace([string]$config.database.username) -or
        [string]$config.releaseVersion -cne $ExpectedProductVersion -or
        [int]$config.databaseSchemaVersion -ne $ExpectedDatabaseSchemaVersion -or
        -not [IO.Path]::GetFullPath([string]$config.uploadDir).Equals(
            [IO.Path]::GetFullPath((Join-Path $resolvedData 'data\uploads')),
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Effective runtime configuration violates the approved release or production boundary'
}
$corsOrigins = @($config.corsAllowedOrigins)
if ($corsOrigins.Count -eq 0 -or @($corsOrigins | Where-Object {
            [string]$_ -notmatch '^https://[^,\s]+$' -or [string]$_ -match '\*'
        }).Count -gt 0) {
    throw 'Production CORS origins must be explicit HTTPS origins'
}

$runtimeEnvironment = @{}
foreach ($line in Get-Content -LiteralPath $environmentPath -Encoding utf8) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -notmatch '^(LEANTPM_[A-Z0-9_]+)=(.*)$' -or
            $runtimeEnvironment.ContainsKey($Matches[1])) {
        throw 'Runtime environment contains an invalid or duplicate entry'
    }
    $runtimeEnvironment[$Matches[1]] = $Matches[2]
}
$expectedEnvironment = [ordered]@{
    LEANTPM_SERVER_ADDRESS = [string]$config.serverAddress
    LEANTPM_SERVER_PORT = [string]$config.serverPort
    LEANTPM_DB_URL = $databaseUrl
    LEANTPM_DB_USERNAME = [string]$config.database.username
    LEANTPM_UPLOAD_DIR = [string]$config.uploadDir
    LEANTPM_CORS_ALLOWED_ORIGINS = ($corsOrigins -join ',')
    LEANTPM_OPENAPI_ENABLED = 'false'
    LEANTPM_RELEASE_VERSION = $ExpectedProductVersion
    LEANTPM_DATABASE_SCHEMA_VERSION = [string]$ExpectedDatabaseSchemaVersion
    LEANTPM_FLYWAY_ENABLED = 'false'
    LEANTPM_FLYWAY_BASELINE_ON_MIGRATE = 'false'
}
$secretNames = @(
    'LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET',
    'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD'
)
if (@($runtimeEnvironment.Keys | Where-Object {
            $expectedEnvironment.Keys -notcontains $_ -or $secretNames -contains $_
        }).Count -gt 0 -or
        @($expectedEnvironment.Keys | Where-Object {
            -not $runtimeEnvironment.ContainsKey($_) -or
            [string]$runtimeEnvironment[$_] -cne [string]$expectedEnvironment[$_]
        }).Count -gt 0) {
    throw 'Runtime environment differs from the approved effective configuration'
}

$secretReferences = Get-Content -LiteralPath $secretReferencePath -Encoding utf8 -Raw |
    ConvertFrom-Json
$referenceNames = @($secretReferences.PSObject.Properties.Name)
if (@($referenceNames | Where-Object { $secretNames -notcontains $_ }).Count -gt 0 -or
        @(@('LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET') |
            Where-Object { $referenceNames -notcontains $_ }).Count -gt 0 -or
        @($secretReferences.PSObject.Properties.Value | Where-Object {
                [string]$_ -notmatch '^(?:vault|dpapi|wincred|azurekeyvault)://[A-Za-z0-9._/@:-]+$'
            }).Count -gt 0) {
    throw 'Runtime secret references contain unknown keys, missing keys, or inline values'
}

$report = [pscustomobject]@{
    status = 'PASS'
    releaseId = $ExpectedReleaseId
    configId = $ExpectedConfigId
    productVersion = $ExpectedProductVersion
    databaseSchemaVersion = $ExpectedDatabaseSchemaVersion
    directorySha256 = [string]$digestReport.digest
    fileCount = [int]$digestReport.fileCount
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
