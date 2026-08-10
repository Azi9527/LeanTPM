[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$JavaExecutable
)

$ErrorActionPreference = 'Stop'
$pointerPath = Join-Path $DataRoot 'pointers\current-release.json'
$configPointerPath = Join-Path $DataRoot 'pointers\current-config.json'
$mysqlTrustStorePath = Join-Path $DataRoot 'config\mysql-truststore.jks'
if (-not (Test-Path -LiteralPath $pointerPath -PathType Leaf)) {
    throw "Release pointer is missing: $pointerPath"
}
if (-not (Test-Path -LiteralPath $configPointerPath -PathType Leaf)) {
    throw "Runtime configuration pointer is missing: $configPointerPath"
}
if (-not (Test-Path -LiteralPath $mysqlTrustStorePath -PathType Leaf) -or
        ((Get-Item -LiteralPath $mysqlTrustStorePath -Force).Attributes -band
            [IO.FileAttributes]::ReparsePoint)) {
    throw "Host-owned MySQL trust store is missing or unsafe: $mysqlTrustStorePath"
}
if (-not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) {
    throw "Java executable is missing: $JavaExecutable"
}
$pointer = Get-Content -LiteralPath $pointerPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$pointer.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$pointer.packageSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Release pointer contains an invalid releaseId or package digest'
}
$configPointer = Get-Content -LiteralPath $configPointerPath -Encoding utf8 -Raw |
    ConvertFrom-Json
if ([int]$configPointer.schemaVersion -ne 1 -or
        [string]$configPointer.releaseId -cne [string]$pointer.releaseId -or
        [string]$configPointer.configId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        [string]$configPointer.directorySha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Runtime configuration pointer does not match the exact current release'
}
$configVersionsRoot = [IO.Path]::GetFullPath(
    (Join-Path $DataRoot 'config\versions')
).TrimEnd('\')
$runtimeConfigRoot = [IO.Path]::GetFullPath(
    (Join-Path $configVersionsRoot ([string]$configPointer.configId))
)
if (-not $runtimeConfigRoot.StartsWith(
        $configVersionsRoot + '\',
        [StringComparison]::OrdinalIgnoreCase
    ) -or -not (Test-Path -LiteralPath $runtimeConfigRoot -PathType Container) -or
        ((Get-Item -LiteralPath $runtimeConfigRoot).Attributes -band
            [IO.FileAttributes]::ReparsePoint)) {
    throw 'Runtime configuration pointer escapes the host-owned config\versions root'
}

function Get-RuntimeConfigDirectoryDigest {
    param([Parameter(Mandatory)][string]$Root)

    $files = [Collections.Generic.List[string]]::new()
    foreach ($item in Get-ChildItem -LiteralPath $Root -Recurse -Force) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Runtime configuration digest refuses reparse points'
        }
        if (-not $item.PSIsContainer) { $files.Add($item.FullName) }
    }
    $fileArray = $files.ToArray()
    [Array]::Sort($fileArray, [StringComparer]::Ordinal)
    $hash = [Security.Cryptography.SHA256]::Create()
    $buffer = New-Object byte[] 1048576
    $separator = [byte[]]@(0)
    $encoding = New-Object Text.UTF8Encoding($false)
    try {
        foreach ($filePath in $fileArray) {
            $relative = $filePath.Substring($Root.TrimEnd('\').Length + 1).Replace('\', '/')
            $pathBytes = $encoding.GetBytes($relative)
            $null = $hash.TransformBlock($pathBytes, 0, $pathBytes.Length, $pathBytes, 0)
            $null = $hash.TransformBlock($separator, 0, 1, $separator, 0)
            $lengthBytes = $encoding.GetBytes(([IO.FileInfo]$filePath).Length.ToString(
                    [Globalization.CultureInfo]::InvariantCulture
                ))
            $null = $hash.TransformBlock($lengthBytes, 0, $lengthBytes.Length, $lengthBytes, 0)
            $null = $hash.TransformBlock($separator, 0, 1, $separator, 0)
            $stream = [IO.File]::Open(
                $filePath,
                [IO.FileMode]::Open,
                [IO.FileAccess]::Read,
                [IO.FileShare]::Read
            )
            try {
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $null = $hash.TransformBlock($buffer, 0, $read, $buffer, 0)
                }
            }
            finally { $stream.Dispose() }
            $null = $hash.TransformBlock($separator, 0, 1, $separator, 0)
        }
        $null = $hash.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        return [BitConverter]::ToString($hash.Hash).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $hash.Dispose()
        [Array]::Clear($buffer, 0, $buffer.Length)
    }
}

if ((Get-RuntimeConfigDirectoryDigest $runtimeConfigRoot) -cne
        [string]$configPointer.directorySha256) {
    throw 'Runtime configuration no longer matches its approved directory digest'
}
$environmentPath = Join-Path $runtimeConfigRoot 'leantpm.env'
$secretReferencePath = Join-Path $runtimeConfigRoot 'secret-references.json'
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $secretReferencePath -PathType Leaf)) {
    throw 'Approved runtime configuration omits the environment or secret references'
}

$stateDirectory = Join-Path $DataRoot 'state'
$recoveryMarker = Join-Path $stateDirectory 'recovery-inhibit.json'
try {
    if (-not [IO.Directory]::Exists($stateDirectory)) {
        throw 'Recovery state directory is missing'
    }
    $recoveryMarkers = @([IO.Directory]::EnumerateFiles(
            $stateDirectory,
            'recovery-inhibit.json',
            [IO.SearchOption]::TopDirectoryOnly
        ))
}
catch {
    throw 'Recovery state directory cannot be read; startup is inhibited'
}
if ($recoveryMarkers.Count -gt 1) {
    throw 'Recovery state is ambiguous; startup is inhibited'
}
if ($recoveryMarkers.Count -eq 1) {
    try {
        $markerItem = Get-Item -LiteralPath $recoveryMarker -Force -ErrorAction Stop
        if (($markerItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Recovery state marker cannot be a reparse point'
        }
        $recoveryState = Get-Content -LiteralPath $recoveryMarker -Encoding utf8 -Raw `
            -ErrorAction Stop | ConvertFrom-Json
    }
    catch {
        throw 'Recovery state marker cannot be read; startup is inhibited'
    }
    if ([int]$recoveryState.schemaVersion -ne 1 -or
            [string]$recoveryState.status -notin @(
                'ACTIVATION_AUTHORIZED', 'ROLLBACK_AUTHORIZED'
            ) -or
            [string]$recoveryState.authorizedReleaseId -cne [string]$pointer.releaseId -or
            [string]$recoveryState.authorizedPackageSha256 -cne
                [string]$pointer.packageSha256 -or
            [string]$recoveryState.planSha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'Recovery state does not authorize this exact release; startup is inhibited'
    }
}
$releasesRoot = [System.IO.Path]::GetFullPath((Join-Path $InstallRoot 'releases')).TrimEnd('\')
$releaseRoot = [System.IO.Path]::GetFullPath((Join-Path $releasesRoot ([string]$pointer.releaseId)))
if (-not $releaseRoot.StartsWith(
        $releasesRoot + '\',
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Release pointer escapes the releases root'
}
$jarPath = Join-Path $releaseRoot 'payload\backend\leantpm-backend.jar'
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Backend JAR is missing from current release: $jarPath"
}
$manifestPath = Join-Path $releaseRoot 'release-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Release manifest is missing from current release: $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Encoding utf8 -Raw | ConvertFrom-Json
$jarArtifacts = @($manifest.artifacts | Where-Object {
        [string]$_.component -ceq 'backend' -and
        [string]$_.path -ceq 'backend/leantpm-backend.jar'
    })
if ($jarArtifacts.Count -ne 1) { throw 'Release manifest must identify exactly one backend JAR' }
$jar = Get-Item -LiteralPath $jarPath
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
if ([int64]$jar.Length -ne [int64]$jarArtifacts[0].size -or
        $jarHash -cne [string]$jarArtifacts[0].sha256) {
    throw 'Backend JAR no longer matches the verified release manifest'
}

$secretNames = @(
    'LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET',
    'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD'
)
$allowedEnvironmentNames = New-Object 'Collections.Generic.HashSet[string]' `
    ([StringComparer]::Ordinal)
foreach ($name in @(
        'LEANTPM_SERVER_ADDRESS', 'LEANTPM_SERVER_PORT', 'LEANTPM_DB_URL',
        'LEANTPM_DB_USERNAME',
        'LEANTPM_CORS_ALLOWED_ORIGINS', 'LEANTPM_UPLOAD_DIR', 'LEANTPM_OPENAPI_ENABLED',
        'LEANTPM_FLYWAY_ENABLED', 'LEANTPM_FLYWAY_BASELINE_ON_MIGRATE',
        'LEANTPM_RELEASE_VERSION', 'LEANTPM_DATABASE_SCHEMA_VERSION',
        'LEANTPM_ACCESS_TOKEN_MINUTES', 'LEANTPM_REFRESH_TOKEN_DAYS',
        'LEANTPM_MAX_LOGIN_FAILURES', 'LEANTPM_FAILURE_WINDOW_MINUTES',
        'LEANTPM_IDEMPOTENCY_PROCESSING_SECONDS', 'LEANTPM_IDEMPOTENCY_COMPLETED_HOURS'
    )) { $null = $allowedEnvironmentNames.Add($name) }
$seenEnvironmentNames = New-Object 'Collections.Generic.HashSet[string]' `
    ([StringComparer]::Ordinal)
foreach ($line in Get-Content -LiteralPath $environmentPath -Encoding utf8) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
        continue
    }
    if ($trimmed -notmatch '^(LEANTPM_[A-Z0-9_]+)=(.*)$') {
        throw 'Environment file contains an invalid line; values were not logged'
    }
    $name = $Matches[1]
    $value = $Matches[2]
    if ($secretNames -contains $name -or -not $allowedEnvironmentNames.Contains($name) -or
            -not $seenEnvironmentNames.Add($name)) {
        throw 'Environment file contains a secret, unsupported, or duplicate variable name'
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$databaseUrl = [Environment]::GetEnvironmentVariable('LEANTPM_DB_URL', 'Process')
if ([Environment]::GetEnvironmentVariable('LEANTPM_SERVER_ADDRESS', 'Process') -cne
        '127.0.0.1' -or
        $databaseUrl -notmatch '^jdbc:mysql://' -or
        $databaseUrl -notmatch '(?i)(?:\?|&)sslMode=VERIFY_IDENTITY(?:&|$)' -or
        $databaseUrl -match '(?i)(?:\?|&)useSSL=false(?:&|$)' -or
        [Environment]::GetEnvironmentVariable('LEANTPM_FLYWAY_ENABLED', 'Process') -cne
        'false' -or
        [Environment]::GetEnvironmentVariable(
            'LEANTPM_FLYWAY_BASELINE_ON_MIGRATE',
            'Process'
        ) -cne 'false') {
    throw 'Host-owned environment would weaken the production network or migration boundary'
}

$secretReferences = Get-Content -LiteralPath $secretReferencePath -Encoding utf8 -Raw |
    ConvertFrom-Json
$referenceNames = @($secretReferences.PSObject.Properties.Name)
foreach ($requiredSecret in @(
        'LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET'
    )) {
    if ($referenceNames -notcontains $requiredSecret) {
        throw "Secret reference file is missing $requiredSecret"
    }
}
if (@($referenceNames | Where-Object { $secretNames -notcontains $_ }).Count -gt 0) {
    throw 'Secret reference file contains an unsupported environment variable name'
}
$secretsRoot = [IO.Path]::GetFullPath((Join-Path $DataRoot 'secrets')).TrimEnd('\')
if (-not (Test-Path -LiteralPath $secretsRoot -PathType Container) -or
        ((Get-Item -LiteralPath $secretsRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Host-owned secrets directory is missing or is a reparse point'
}
Add-Type -AssemblyName System.Security -ErrorAction Stop
foreach ($property in $secretReferences.PSObject.Properties) {
    $reference = [string]$property.Value
    if ($reference -notmatch '^dpapi://([A-Za-z0-9._-]+\.bin)$' -or
            $Matches[1] -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)') {
        throw 'Only safe dpapi://filename.bin secret references are supported on Windows'
    }
    $secretPath = [IO.Path]::GetFullPath((Join-Path $secretsRoot $Matches[1]))
    if (-not $secretPath.StartsWith($secretsRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $secretPath -PathType Leaf) -or
            ((Get-Item -LiteralPath $secretPath).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'DPAPI secret reference escapes the protected secrets directory or is unavailable'
    }
    $encryptedBytes = [IO.File]::ReadAllBytes($secretPath)
    $clearBytes = $null
    try {
        $clearBytes = [Security.Cryptography.ProtectedData]::Unprotect(
            $encryptedBytes,
            $null,
            [Security.Cryptography.DataProtectionScope]::LocalMachine
        )
        if ($clearBytes.Length -eq 0) { throw 'DPAPI secret resolved to an empty value' }
        $clearValue = [Text.Encoding]::UTF8.GetString($clearBytes)
        [Environment]::SetEnvironmentVariable($property.Name, $clearValue, 'Process')
        $clearValue = $null
    }
    finally {
        if ($null -ne $clearBytes) { [Array]::Clear($clearBytes, 0, $clearBytes.Length) }
        [Array]::Clear($encryptedBytes, 0, $encryptedBytes.Length)
    }
}
[Environment]::SetEnvironmentVariable('LEANTPM_FLYWAY_ENABLED', 'false', 'Process')

& $JavaExecutable '-XX:MaxRAMPercentage=75' `
    "-Djavax.net.ssl.trustStore=$mysqlTrustStorePath" `
    '-jar' $jarPath '--spring.profiles.active=prod'
exit $LASTEXITCODE
