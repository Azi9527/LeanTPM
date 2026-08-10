[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupSetPath,
    [string]$TrustedSignerThumbprint = '',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $BackupSetPath).Path.TrimEnd('\', '/')
$manifestPath = Join-Path $root 'backup-manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$manifest.schemaVersion -ne 1 -or [string]$manifest.status -cne 'VALID') {
    throw 'Backup manifest is unsupported or not VALID'
}
$signaturePath = Join-Path $root 'backup-manifest.p7s'
$signerThumbprint = $null
if ([string]$manifest.environmentKind -eq 'PRODUCTION') {
    if ($TrustedSignerThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$' -or
            -not (Test-Path -LiteralPath $signaturePath -PathType Leaf)) {
        throw 'PRODUCTION backup requires a detached signature and a pinned trusted signer'
    }
}
if (Test-Path -LiteralPath $signaturePath -PathType Leaf) {
    if ($TrustedSignerThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
        throw 'A signed backup must be verified against a pinned trusted signer'
    }
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $contentInfo = New-Object Security.Cryptography.Pkcs.ContentInfo(
        (, [IO.File]::ReadAllBytes($manifestPath))
    )
    $signedCms = New-Object Security.Cryptography.Pkcs.SignedCms($contentInfo, $true)
    $signedCms.Decode([IO.File]::ReadAllBytes($signaturePath))
    $signedCms.CheckSignature($true)
    if ($signedCms.SignerInfos.Count -ne 1 -or
            $null -eq $signedCms.SignerInfos[0].Certificate) {
        throw 'Backup manifest signature must contain exactly one signer'
    }
    $certificate = $signedCms.SignerInfos[0].Certificate
    $signerThumbprint = $certificate.Thumbprint.Replace(' ', '').ToLowerInvariant()
    if (-not $signerThumbprint.Equals(
            $TrustedSignerThumbprint.Replace(' ', ''),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Backup manifest signer does not match the pinned trust anchor'
    }
    $now = [DateTime]::UtcNow
    if ($now -lt $certificate.NotBefore.ToUniversalTime() -or
            $now -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'Backup manifest signer certificate is outside its validity period'
    }
    $chain = New-Object Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode =
        [Security.Cryptography.X509Certificates.X509RevocationMode]::Online
    $chain.ChainPolicy.RevocationFlag =
        [Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
    if (-not $chain.Build($certificate)) {
        throw 'Backup signer certificate chain/revocation verification failed'
    }
}
$approved = New-Object 'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($file in @($manifest.files)) {
    $path = [string]$file.path
    if ([System.IO.Path]::IsPathRooted($path) -or $path.Contains('\') -or
            $path.Split('/') -contains '..') {
        throw "Backup manifest contains an unsafe path: $path"
    }
    if (-not $approved.Add($path)) { throw "Duplicate backup path: $path" }
    $target = Join-Path $root $path.Replace('/', '\')
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Backup file is missing: $path" }
    $item = Get-Item -LiteralPath $target
    if ([int64]$item.Length -ne [int64]$file.size) { throw "Backup size mismatch: $path" }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
    if ($hash -cne [string]$file.sha256) { throw "Backup hash mismatch: $path" }
}
$unexpected = Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
    $_.FullName.Substring($root.Length + 1).Replace('\', '/')
} | Where-Object {
    $_ -notin @('backup-manifest.json', 'backup-manifest.p7s') -and
    -not $approved.Contains($_)
}
if (@($unexpected).Count -gt 0) { throw "Unexpected backup files: $($unexpected -join ', ')" }

$report = [pscustomobject]@{
    status = 'PASS'
    backupId = [string]$manifest.backupId
    releaseId = [string]$manifest.releaseId
    fileCount = @($manifest.files).Count
    signerThumbprint = $signerThumbprint
    path = $root
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
