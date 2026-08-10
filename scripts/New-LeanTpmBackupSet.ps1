[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$ConfirmDatabase,
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'leantpm_backup',
    [string]$MySqlPassword = $env:LEANTPM_BACKUP_DB_PASSWORD,
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [Parameter(Mandatory)][string]$AttachmentRoot,
    [Parameter(Mandatory)][string]$ConfigPath,
    [Parameter(Mandatory)][string]$RuntimeEnvironmentPath,
    [Parameter(Mandatory)][string]$SecretReferencePath,
    [Parameter(Mandatory)][string]$PointerRoot,
    [Parameter(Mandatory)][string]$ProtectionProfilePath,
    [Parameter(Mandatory)][string]$ReleaseManifestPath,
    [Parameter(Mandatory)][string]$BackupRoot,
    [Parameter(Mandatory)][string]$EnvironmentName,
    [ValidateSet('NON_PRODUCTION', 'PRODUCTION')]
    [string]$EnvironmentKind,
    [string]$ExpectedServerUuid = '',
    [switch]$ConfirmBackupTarget,
    [switch]$ConfirmApplicationWritesQuiesced,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($Database -cne $ConfirmDatabase -or $Database -notmatch '^[A-Za-z0-9_]+$') {
    throw 'ConfirmDatabase must exactly match a safe Database name'
}
if ($MySqlHost -notmatch '^[A-Za-z0-9.-]+$' -or
        $MySqlPort -lt 1 -or $MySqlPort -gt 65535) {
    throw 'MySQL backup host or port is invalid'
}
if ($EnvironmentName -notmatch '^[A-Za-z0-9._-]{2,64}$') {
    throw 'EnvironmentName contains unsupported characters'
}
$resolvedAttachments = (Resolve-Path -LiteralPath $AttachmentRoot).Path
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path
$resolvedRuntimeEnvironment = (Resolve-Path -LiteralPath $RuntimeEnvironmentPath).Path
$resolvedSecretReferences = (Resolve-Path -LiteralPath $SecretReferencePath).Path
$resolvedPointers = (Resolve-Path -LiteralPath $PointerRoot).Path.TrimEnd('\', '/')
$resolvedProtectionProfile = (Resolve-Path -LiteralPath $ProtectionProfilePath).Path
$resolvedReleaseManifest = (Resolve-Path -LiteralPath $ReleaseManifestPath).Path
$resolvedBackupRoot = (Resolve-Path -LiteralPath $BackupRoot).Path.TrimEnd('\', '/')

$secretReferenceText = Get-Content -LiteralPath $resolvedSecretReferences -Encoding utf8 -Raw
$secretReferenceObject = $secretReferenceText | ConvertFrom-Json
$allowedSecretReferenceNames = @(
    'LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET',
    'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD'
)
$requiredSecretReferenceNames = @(
    'LEANTPM_DB_PASSWORD', 'LEANTPM_JWT_SECRET'
)
$actualSecretReferenceNames = @($secretReferenceObject.PSObject.Properties.Name)
if (@($actualSecretReferenceNames | Where-Object {
            $allowedSecretReferenceNames -notcontains $_
        }).Count -gt 0 -or @($requiredSecretReferenceNames | Where-Object {
            $actualSecretReferenceNames -notcontains $_
        }).Count -gt 0) {
    throw 'Secret reference file contains unknown keys or omits a required production secret'
}
$referenceValues = @($secretReferenceObject.PSObject.Properties | ForEach-Object { [string]$_.Value })
if ($referenceValues.Count -eq 0 -or @($referenceValues | Where-Object {
            $_ -notmatch '^(vault|dpapi|wincred|azurekeyvault)://[A-Za-z0-9._/@:-]+$'
        }).Count -gt 0) {
    throw 'Secret reference file must contain only approved provider URIs, never secret values'
}
$configObject = Get-Content -LiteralPath $resolvedConfig -Encoding utf8 -Raw | ConvertFrom-Json
function Assert-OnlyProperties {
    param($Object, [string[]]$Allowed, [string]$Context)

    $unexpected = @($Object.PSObject.Properties.Name | Where-Object { $Allowed -notcontains $_ })
    $missing = @($Allowed | Where-Object { $null -eq $Object.PSObject.Properties[$_] })
    if ($unexpected.Count -gt 0 -or $missing.Count -gt 0) {
        throw "$Context has unknown or missing fields"
    }
}
Assert-OnlyProperties $configObject @(
    'schemaVersion', 'serverAddress', 'serverPort', 'database', 'uploadDir',
    'corsAllowedOrigins', 'releaseVersion', 'databaseSchemaVersion'
) 'effective config'
Assert-OnlyProperties $configObject.database @('url', 'username') 'effective config database'
if ([int]$configObject.schemaVersion -ne 1 -or
        [string]$configObject.serverAddress -cne '127.0.0.1' -or
        [int]$configObject.serverPort -lt 1 -or [int]$configObject.serverPort -gt 65535 -or
        [string]$configObject.database.url -notmatch '^jdbc:mysql://' -or
        [string]$configObject.database.url -notmatch
            '(?i)(?:\?|&)sslMode=VERIFY_IDENTITY(?:&|$)' -or
        [string]$configObject.database.url -match
            '(?i)(?:\?|&)useSSL=false(?:&|$)' -or
        [string]$configObject.database.url -match '(?i)(password|passwd|pwd|user)=' -or
        [string]::IsNullOrWhiteSpace([string]$configObject.database.username) -or
        -not [IO.Path]::IsPathRooted([string]$configObject.uploadDir) -or
        [string]$configObject.releaseVersion -notmatch '^\d+\.\d+\.\d+' -or
        [int]$configObject.databaseSchemaVersion -lt 1) {
    throw 'Effective config contains an invalid or unsafe production value'
}
$databaseUrlMatch = [regex]::Match(
    [string]$configObject.database.url,
    '^jdbc:mysql://(?<host>[A-Za-z0-9.-]+):(?<port>[0-9]{1,5})/(?<database>[A-Za-z0-9_]+)(?:\?|$)'
)
if (-not $databaseUrlMatch.Success -or
        -not $databaseUrlMatch.Groups['host'].Value.Equals(
            $MySqlHost,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        [int]$databaseUrlMatch.Groups['port'].Value -ne $MySqlPort -or
        $databaseUrlMatch.Groups['database'].Value -cne $Database) {
    throw 'Backup target must exactly match the host-owned effective JDBC target'
}
$runtimeEnvironment = @{}
foreach ($line in Get-Content -LiteralPath $resolvedRuntimeEnvironment -Encoding utf8) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -notmatch '^(LEANTPM_[A-Z0-9_]+)=(.*)$' -or
            $runtimeEnvironment.ContainsKey($Matches[1])) {
        throw 'Runtime environment contains an invalid or duplicate entry'
    }
    $runtimeEnvironment[$Matches[1]] = $Matches[2]
}
$expectedRuntimeEnvironment = [ordered]@{
    LEANTPM_SERVER_ADDRESS = [string]$configObject.serverAddress
    LEANTPM_SERVER_PORT = [string]$configObject.serverPort
    LEANTPM_DB_URL = [string]$configObject.database.url
    LEANTPM_DB_USERNAME = [string]$configObject.database.username
    LEANTPM_UPLOAD_DIR = [string]$configObject.uploadDir
    LEANTPM_CORS_ALLOWED_ORIGINS = (@($configObject.corsAllowedOrigins) -join ',')
    LEANTPM_RELEASE_VERSION = [string]$configObject.releaseVersion
    LEANTPM_DATABASE_SCHEMA_VERSION = [string]$configObject.databaseSchemaVersion
    LEANTPM_FLYWAY_ENABLED = 'false'
    LEANTPM_FLYWAY_BASELINE_ON_MIGRATE = 'false'
}
foreach ($entry in $expectedRuntimeEnvironment.GetEnumerator()) {
    if (-not $runtimeEnvironment.ContainsKey($entry.Key) -or
            [string]$runtimeEnvironment[$entry.Key] -cne [string]$entry.Value) {
        throw "Runtime environment differs from effective config at $($entry.Key)"
    }
}
$corsOrigins = @($configObject.corsAllowedOrigins)
if ($corsOrigins.Count -eq 0 -or @($corsOrigins | Where-Object {
            [string]$_ -notmatch '^https://[A-Za-z0-9.-]+(?::\d+)?$'
        }).Count -gt 0) {
    throw 'Effective config CORS origins must be explicit HTTPS origins'
}
function Assert-NoInlineSecret {
    param($Value, [string]$Path = 'config')

    if ($null -eq $Value) { return }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string] -and
            $Value -isnot [pscustomobject]) {
        $index = 0
        foreach ($item in $Value) {
            Assert-NoInlineSecret $item "$Path[$index]"
            $index++
        }
        return
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            $propertyPath = "$Path.$($property.Name)"
            if ($property.Name -match '(?i)(password|secret|token|credential|private.?key)' -and
                    [string]$property.Value -notmatch
                        '^(vault|dpapi|wincred|azurekeyvault)://[A-Za-z0-9._/@:-]+$') {
                throw "Effective config contains an inline sensitive value at $propertyPath"
            }
            Assert-NoInlineSecret $property.Value $propertyPath
        }
    }
}
Assert-NoInlineSecret $configObject
$protection = Get-Content -LiteralPath $resolvedProtectionProfile -Encoding utf8 -Raw |
    ConvertFrom-Json
if ([int]$protection.schemaVersion -ne 1 -or
        [string]$protection.encryptionAtRest -cne 'BITLOCKER_OR_ENTERPRISE_STORAGE' -or
        -not [bool]$protection.storageIsolation -or
        -not [bool]$protection.offHostCopyRequired -or
        [int]$protection.retentionDays -lt 1) {
    throw 'Backup protection profile does not prove encryption, isolation, retention and off-host copy'
}
$releaseManifest = Get-Content -LiteralPath $resolvedReleaseManifest -Encoding utf8 -Raw |
    ConvertFrom-Json
$backupId = 'backup-{0}-{1}' -f (
    (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
), [Guid]::NewGuid().ToString('N').Substring(0, 12)
$components = @(
    'database', 'attachments', 'config', 'secret-references', 'release', 'pointers', 'protection'
)
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    backupId = $backupId
    environmentName = $EnvironmentName
    environmentKind = $EnvironmentKind
    database = $Database
    releaseId = [string]$releaseManifest.releaseId
    components = $components
    destinationRoot = $resolvedBackupRoot
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if (-not $ConfirmBackupTarget) {
    throw 'ConfirmBackupTarget is required before reading the database and creating a backup set'
}
if (-not $ConfirmApplicationWritesQuiesced) {
    throw 'ConfirmApplicationWritesQuiesced is required for a database/attachment consistency window'
}
if ([string]::IsNullOrWhiteSpace($ExpectedServerUuid)) {
    throw 'ExpectedServerUuid is required for every MySQL backup source, including loopback'
}
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf)) {
    throw 'MySqlSslCaPath must identify the host-owned MySQL CA certificate'
}
$backupSignerThumbprint = [string]$protection.manifestSignerCertificateThumbprint
if ($EnvironmentKind -eq 'PRODUCTION' -and
        $backupSignerThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
    throw 'PRODUCTION backup protection profile must pin a backup manifest signer certificate'
}
$shouldSignBackup = $backupSignerThumbprint -match '^[0-9A-Fa-f]{40,128}$'
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    $actualServerUuid = (& mysql.exe "--host=$MySqlHost" "--port=$MySqlPort" `
            "--user=$MySqlUser" '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$resolvedSslCa" `
            --batch --skip-column-names -e 'SELECT @@server_uuid;').Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($actualServerUuid)) {
        throw 'Failed to read the MySQL backup source server UUID'
    }
}
finally { $env:MYSQL_PWD = $previousPassword }
if (-not [string]::IsNullOrWhiteSpace($ExpectedServerUuid) -and
        -not $actualServerUuid.Equals(
            $ExpectedServerUuid,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'MySQL backup source server UUID does not match ExpectedServerUuid'
}

function Copy-TreeWithoutLinks {
    param([string]$SourceRoot, [string]$DestinationRoot)

    $source = (Resolve-Path -LiteralPath $SourceRoot).Path.TrimEnd('\', '/')
    $null = New-Item -ItemType Directory -Path $DestinationRoot -Force
    foreach ($item in Get-ChildItem -LiteralPath $source -Recurse -Force) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Backup source cannot contain a reparse point: $($item.FullName)"
        }
        $relative = $item.FullName.Substring($source.Length + 1)
        $destination = Join-Path $DestinationRoot $relative
        if ($item.PSIsContainer) {
            $null = New-Item -ItemType Directory -Path $destination -Force
        }
        else {
            $parent = Split-Path -Parent $destination
            if (-not (Test-Path -LiteralPath $parent)) {
                $null = New-Item -ItemType Directory -Path $parent -Force
            }
            [System.IO.File]::Copy($item.FullName, $destination, $false)
        }
    }
}

if ($PSCmdlet.ShouldProcess("$EnvironmentName/$Database", 'Create atomic LeanTPM backup set')) {
    $partialPath = Join-Path $resolvedBackupRoot "$backupId.partial"
    $finalPath = Join-Path $resolvedBackupRoot $backupId
    if ((Test-Path -LiteralPath $partialPath) -or (Test-Path -LiteralPath $finalPath)) {
        throw "Backup ID already exists: $backupId"
    }
    $null = New-Item -ItemType Directory -Path $partialPath
    try {
        $consistencyWindowStartedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        $databaseDirectory = Join-Path $partialPath 'database'
        $null = New-Item -ItemType Directory -Path $databaseDirectory
        $databaseBackup = Join-Path $databaseDirectory 'database.sql'
        & (Join-Path $PSScriptRoot 'backup-mysql.ps1') `
            -MySqlHost $MySqlHost `
            -MySqlPort $MySqlPort `
            -MySqlUser $MySqlUser `
            -MySqlPassword $MySqlPassword `
            -MySqlSslCaPath $resolvedSslCa `
            -Database $Database `
            -OutputFile $databaseBackup

        Copy-TreeWithoutLinks $resolvedAttachments (Join-Path $partialPath 'attachments')
        $configDirectory = Join-Path $partialPath 'config'
        $null = New-Item -ItemType Directory -Path $configDirectory
        [System.IO.File]::Copy($resolvedConfig, (Join-Path $configDirectory 'effective-config.json'), $false)
        [System.IO.File]::Copy(
            $resolvedRuntimeEnvironment,
            (Join-Path $configDirectory 'leantpm.env'),
            $false
        )
        [System.IO.File]::Copy(
            $resolvedSecretReferences,
            (Join-Path $configDirectory 'secret-references.json'),
            $false
        )
        $releaseDirectory = Join-Path $partialPath 'release'
        $null = New-Item -ItemType Directory -Path $releaseDirectory
        [System.IO.File]::Copy(
            $resolvedReleaseManifest,
            (Join-Path $releaseDirectory 'release-manifest.json'),
            $false
        )
        $pointerDirectory = Join-Path $partialPath 'pointers'
        $null = New-Item -ItemType Directory -Path $pointerDirectory
        $currentPointer = Join-Path $resolvedPointers 'current-release.json'
        if (-not (Test-Path -LiteralPath $currentPointer -PathType Leaf)) {
            throw 'Current release pointer is required for a restorable backup set'
        }
        [System.IO.File]::Copy(
            $currentPointer,
            (Join-Path $pointerDirectory 'current-release.json'),
            $false
        )
        $previousPointer = Join-Path $resolvedPointers 'previous-release.json'
        if (Test-Path -LiteralPath $previousPointer -PathType Leaf) {
            [System.IO.File]::Copy(
                $previousPointer,
                (Join-Path $pointerDirectory 'previous-release.json'),
                $false
            )
        }
        $protectionDirectory = Join-Path $partialPath 'protection'
        $null = New-Item -ItemType Directory -Path $protectionDirectory
        [System.IO.File]::Copy(
            $resolvedProtectionProfile,
            (Join-Path $protectionDirectory 'profile.json'),
            $false
        )

        $files = Get-ChildItem -LiteralPath $partialPath -Recurse -File | Sort-Object FullName |
            ForEach-Object {
                [pscustomobject]@{
                    path = $_.FullName.Substring($partialPath.Length + 1).Replace('\', '/')
                    size = $_.Length
                    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).
                        Hash.ToLowerInvariant()
                }
            }
        $backupManifest = [ordered]@{
            schemaVersion = 1
            backupId = $backupId
            status = 'VALID'
            createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            environmentName = $EnvironmentName
            environmentKind = $EnvironmentKind
            database = [ordered]@{
                host = $MySqlHost
                port = $MySqlPort
                name = $Database
                serverUuid = $actualServerUuid
            }
            releaseId = [string]$releaseManifest.releaseId
            productVersion = [string]$releaseManifest.productVersion
            databaseSchemaVersion = [int]$releaseManifest.components.database.schemaTo
            consistencyWindow = [ordered]@{
                writesQuiesced = $true
                startedAtUtc = $consistencyWindowStartedAtUtc
                completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            }
            components = $components
            protection = [ordered]@{
                encryptionAtRest = [string]$protection.encryptionAtRest
                storageIsolation = [bool]$protection.storageIsolation
                offHostCopyRequired = [bool]$protection.offHostCopyRequired
                retentionDays = [int]$protection.retentionDays
            }
            files = @($files)
        }
        $manifestJson = $backupManifest | ConvertTo-Json -Depth 8
        [System.IO.File]::WriteAllText(
            (Join-Path $partialPath 'backup-manifest.json'),
            $manifestJson,
            (New-Object System.Text.UTF8Encoding($false))
        )
        if ($shouldSignBackup) {
            Add-Type -AssemblyName System.Security -ErrorAction Stop
            $store = New-Object Security.Cryptography.X509Certificates.X509Store(
                [Security.Cryptography.X509Certificates.StoreName]::My,
                [Security.Cryptography.X509Certificates.StoreLocation]::LocalMachine
            )
            try {
                $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
                $certificate = @($store.Certificates | Where-Object {
                        $_.Thumbprint.Replace(' ', '').Equals(
                            $backupSignerThumbprint,
                            [StringComparison]::OrdinalIgnoreCase
                        )
                    })
                if ($certificate.Count -ne 1 -or -not $certificate[0].HasPrivateKey) {
                    throw 'Pinned backup signer certificate/private key is unavailable'
                }
                $manifestPath = Join-Path $partialPath 'backup-manifest.json'
                $contentInfo = New-Object Security.Cryptography.Pkcs.ContentInfo(
                    (, [IO.File]::ReadAllBytes($manifestPath))
                )
                $signedCms = New-Object Security.Cryptography.Pkcs.SignedCms($contentInfo, $true)
                $cmsSigner = New-Object Security.Cryptography.Pkcs.CmsSigner($certificate[0])
                $cmsSigner.DigestAlgorithm = New-Object Security.Cryptography.Oid(
                    '2.16.840.1.101.3.4.2.1'
                )
                $cmsSigner.IncludeOption =
                    [Security.Cryptography.X509Certificates.X509IncludeOption]::EndCertOnly
                $signedCms.ComputeSignature($cmsSigner)
                [IO.File]::WriteAllBytes(
                    (Join-Path $partialPath 'backup-manifest.p7s'),
                    $signedCms.Encode()
                )
            }
            finally { $store.Close() }
        }
        $backupVerificationArguments = @{
            BackupSetPath = $partialPath
            OutputFormat = 'Json'
        }
        if ($shouldSignBackup) {
            $backupVerificationArguments.TrustedSignerThumbprint = $backupSignerThumbprint
        }
        $backupVerification = & (Join-Path $PSScriptRoot 'Test-LeanTpmBackupSet.ps1') `
            @backupVerificationArguments | ConvertFrom-Json
        Move-Item -LiteralPath $partialPath -Destination $finalPath
        $report.status = 'VALID'
        $report | Add-Member -NotePropertyName path -NotePropertyValue $finalPath
        $report | Add-Member -NotePropertyName fileCount -NotePropertyValue $files.Count
        $report | Add-Member -NotePropertyName manifestSha256 -NotePropertyValue (
            (Get-FileHash -Algorithm SHA256 -LiteralPath (
                    Join-Path $finalPath 'backup-manifest.json'
                )).Hash.ToLowerInvariant()
        )
        $report | Add-Member -NotePropertyName manifestSigned -NotePropertyValue (
            $shouldSignBackup
        )
    }
    catch {
        if (Test-Path -LiteralPath $partialPath) {
            $invalidMarker = Join-Path $partialPath 'BACKUP_INVALID.txt'
            [System.IO.File]::WriteAllText($invalidMarker, 'Backup creation failed; do not restore this set.')
        }
        throw
    }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
