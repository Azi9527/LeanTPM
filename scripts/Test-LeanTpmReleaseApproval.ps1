[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [Parameter(Mandatory)][string]$RequesterSignaturePath,
    [Parameter(Mandatory)][string]$ApproverSignaturePath,
    [Parameter(Mandatory)][string]$TrustConfigPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$planFile = (Resolve-Path -LiteralPath $PlanPath).Path
$requestSignature = (Resolve-Path -LiteralPath $RequesterSignaturePath).Path
$approvalSignature = (Resolve-Path -LiteralPath $ApproverSignaturePath).Path
$trustFile = (Resolve-Path -LiteralPath $TrustConfigPath).Path
$planBytes = [System.IO.File]::ReadAllBytes($planFile)
$strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$plan = $strictUtf8.GetString($planBytes) | ConvertFrom-Json
$trust = Get-Content -LiteralPath $trustFile -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$trust.schemaVersion -ne 1 -or
        [string]::IsNullOrWhiteSpace([string]$plan.requestedBy) -or
        [string]::IsNullOrWhiteSpace([string]$plan.approvedBy) -or
        [string]$plan.requestedBy -ceq [string]$plan.approvedBy) {
    throw 'Production approval identities or trust configuration are invalid'
}

function Test-DetachedSigner {
    param([string]$SignaturePath, $AllowedSigners, [string]$ExpectedIdentity)

    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $cms = New-Object System.Security.Cryptography.Pkcs.SignedCms(
        (New-Object System.Security.Cryptography.Pkcs.ContentInfo(
                (, $planBytes)
            )),
        $true
    )
    $cms.Decode([System.IO.File]::ReadAllBytes($SignaturePath))
    $cms.CheckSignature($true)
    if ($cms.SignerInfos.Count -ne 1 -or $null -eq $cms.SignerInfos[0].Certificate) {
        throw 'Approval must contain exactly one signer certificate'
    }
    $certificate = $cms.SignerInfos[0].Certificate
    $now = [DateTime]::UtcNow
    if ($now -lt $certificate.NotBefore.ToUniversalTime() -or
            $now -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'Approval signer certificate is outside its validity period'
    }
    $hasCodeSigningEku = @($certificate.Extensions | Where-Object {
            $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]
        } | ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object { $_ } | Where-Object {
            $_.Value -eq '1.3.6.1.5.5.7.3.3'
        }).Count -gt 0
    if (-not $hasCodeSigningEku) { throw 'Approval signer certificate lacks the code-signing EKU' }
    $thumbprint = $certificate.Thumbprint.Replace(' ', '').ToUpperInvariant()
    $matches = @($AllowedSigners | Where-Object {
            [string]$_.identity -ceq $ExpectedIdentity -and
            ([string]$_.thumbprint).Replace(' ', '').ToUpperInvariant() -ceq $thumbprint
        })
    if ($matches.Count -ne 1) {
        throw 'Approval signer is not pinned to the signed plan identity'
    }
    $chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode =
        [System.Security.Cryptography.X509Certificates.X509RevocationMode]::Online
    $chain.ChainPolicy.RevocationFlag =
        [System.Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
    if (-not $chain.Build($certificate)) { throw 'Approval certificate chain/revocation check failed' }
    return $thumbprint
}

$requesterThumbprint = Test-DetachedSigner $requestSignature $trust.requesterSigners `
    ([string]$plan.requestedBy)
$approverThumbprint = Test-DetachedSigner $approvalSignature $trust.approverSigners `
    ([string]$plan.approvedBy)
if ($requesterThumbprint -ceq $approverThumbprint) {
    throw 'Requester and approver must use different pinned certificates'
}
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $planSha256 = [BitConverter]::ToString($sha256.ComputeHash($planBytes)).Replace('-', '').
        ToLowerInvariant()
}
finally {
    $sha256.Dispose()
}
$report = [pscustomobject]@{
    status = 'PASS'
    requestedBy = [string]$plan.requestedBy
    approvedBy = [string]$plan.approvedBy
    requesterCertificateThumbprint = $requesterThumbprint
    approverCertificateThumbprint = $approverThumbprint
    planSha256 = $planSha256
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
