[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][string]$CertificateThumbprint,
    [ValidateSet('CurrentUser', 'LocalMachine')][string]$StoreLocation = 'CurrentUser',
    [switch]$ConfirmSigning,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$outputParent = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputParent -PathType Container) -or
        (Test-Path -LiteralPath $outputFullPath)) {
    throw 'Signature output parent must exist and the output must not already exist'
}
if ($CertificateThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
    throw 'CertificateThumbprint must be a pinned certificate identifier'
}
if (-not $ConfirmSigning) { throw 'ConfirmSigning is required before using a private key' }
if (-not $PSCmdlet.ShouldProcess($resolvedInput, 'Create detached CMS SHA-256 signature')) { return }

Add-Type -AssemblyName System.Security -ErrorAction Stop
$store = New-Object Security.Cryptography.X509Certificates.X509Store(
    [Security.Cryptography.X509Certificates.StoreName]::My,
    [Security.Cryptography.X509Certificates.StoreLocation]::$StoreLocation
)
try {
    $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
    $certificate = @($store.Certificates | Where-Object {
            $_.Thumbprint.Replace(' ', '').Equals(
                $CertificateThumbprint,
                [StringComparison]::OrdinalIgnoreCase
            )
        })
    if ($certificate.Count -ne 1 -or -not $certificate[0].HasPrivateKey) {
        throw 'Pinned signing certificate/private key is unavailable'
    }
    $certificate = $certificate[0]
    $now = [DateTime]::UtcNow
    if ($now -lt $certificate.NotBefore.ToUniversalTime() -or
            $now -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'Signing certificate is outside its validity period'
    }
    $hasCodeSigningEku = @($certificate.Extensions | Where-Object {
            $_ -is [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]
        } | ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object { $_ } |
        Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' }).Count -gt 0
    if (-not $hasCodeSigningEku) { throw 'Signing certificate lacks the code-signing EKU' }
    $chain = New-Object Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode =
        [Security.Cryptography.X509Certificates.X509RevocationMode]::Online
    $chain.ChainPolicy.RevocationFlag =
        [Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
    if (-not $chain.Build($certificate)) {
        throw 'Signing certificate chain/revocation verification failed'
    }
    $content = New-Object Security.Cryptography.Pkcs.ContentInfo(
        (, [IO.File]::ReadAllBytes($resolvedInput))
    )
    $cms = New-Object Security.Cryptography.Pkcs.SignedCms($content, $true)
    $signer = New-Object Security.Cryptography.Pkcs.CmsSigner($certificate)
    $signer.DigestAlgorithm = New-Object Security.Cryptography.Oid(
        '2.16.840.1.101.3.4.2.1'
    )
    $signer.IncludeOption =
        [Security.Cryptography.X509Certificates.X509IncludeOption]::EndCertOnly
    $cms.ComputeSignature($signer)
    $signatureBytes = $cms.Encode()
    $stream = New-Object IO.FileStream(
        $outputFullPath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try { $stream.Write($signatureBytes, 0, $signatureBytes.Length) }
    finally { $stream.Dispose() }

    $verified = New-Object Security.Cryptography.Pkcs.SignedCms($content, $true)
    $verified.Decode([IO.File]::ReadAllBytes($outputFullPath))
    $verified.CheckSignature($true)
}
catch {
    if (Test-Path -LiteralPath $outputFullPath -PathType Leaf) {
        [IO.File]::Delete($outputFullPath)
    }
    throw
}
finally { $store.Close() }

$report = [pscustomobject]@{
    status = 'PASS'
    inputSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedInput).Hash.ToLowerInvariant()
    signatureSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputFullPath).Hash.ToLowerInvariant()
    certificateThumbprint = $certificate.Thumbprint.Replace(' ', '').ToLowerInvariant()
    outputPath = $outputFullPath
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }
