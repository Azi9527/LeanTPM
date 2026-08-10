[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$PlanOnly,
    [switch]$ConfirmInitialization,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$actions = @(
    'CREATE_REQUESTER_CODE_SIGNING_IDENTITY',
    'CREATE_APPROVER_CODE_SIGNING_IDENTITY',
    'TRUST_PUBLIC_CERTIFICATES_FOR_LOCAL_BUILD',
    'WRITE_NON_SECRET_IDENTITY_RECEIPT'
)
$requesterSubject = 'CN=LeanTPM Workgroup Release Requester'
$approverSubject = 'CN=LeanTPM Workgroup Release Approver'

function Get-TextSha256 {
    param([Parameter(Mandatory)][string]$Text)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
                $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
            )).Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.ToLowerInvariant()
}

function Write-Result {
    param([Parameter(Mandatory)]$Value)

    if ($OutputFormat -eq 'Json') {
        $Value | ConvertTo-Json -Depth 6 -Compress
    }
    else { $Value | Format-List }
}

if (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    throw 'OutputDirectory must be an absolute local path'
}
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\', '/')
if ($output.StartsWith('\\') -or $output.StartsWith('\\?\')) {
    throw 'OutputDirectory must not be a network or device path'
}

$planCore = [ordered]@{
    schemaVersion = 1
    identityMode = 'WORKGROUP_LOCAL_AUTOMATED'
    requesterSubject = $requesterSubject
    approverSubject = $approverSubject
    certificateStore = 'Cert:\CurrentUser\My'
    trustStores = @('Cert:\CurrentUser\Root', 'Cert:\CurrentUser\TrustedPublisher')
    outputDirectory = $output
    actions = $actions
}
$planSha256 = Get-TextSha256 ($planCore | ConvertTo-Json -Depth 5 -Compress)

if ($PlanOnly) {
    Write-Result ([pscustomobject][ordered]@{
            status = 'PLAN'
            executable = $false
            identityMode = 'WORKGROUP_LOCAL_AUTOMATED'
            identityCount = 2
            operatorCertificateSteps = 0
            outputDirectory = $output
            planSha256 = $planSha256
            actions = $actions
        })
    return
}
if (-not $ConfirmInitialization) {
    throw 'Run with PlanOnly first, then provide ConfirmInitialization'
}
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'WORKGROUP signing identity initialization requires Windows'
}
if (-not $PSCmdlet.ShouldProcess(
        $output,
        'Create two local LeanTPM signing identities and public trust records'
    )) {
    return
}

$receiptPath = Join-Path $output 'workgroup-signing-identities.json'
$requesterCertificatePath = Join-Path $output 'requester-public.cer'
$approverCertificatePath = Join-Path $output 'approver-public.cer'
if (Test-Path -LiteralPath $receiptPath) {
    throw 'A WORKGROUP signing identity receipt already exists; reuse it'
}
if (Test-Path -LiteralPath $output) {
    $outputItem = Get-Item -LiteralPath $output -Force
    if (-not $outputItem.PSIsContainer -or
            (($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
            @(Get-ChildItem -LiteralPath $output -Force).Count -ne 0) {
        throw 'OutputDirectory must be missing or an empty non-reparse directory'
    }
}
else {
    $null = New-Item -ItemType Directory -Path $output
}

$createdThumbprints = New-Object 'System.Collections.Generic.List[string]'
$trustedLocations = New-Object 'System.Collections.Generic.List[string]'
try {
    $notAfter = (Get-Date).ToUniversalTime().AddYears(5)
    $requester = New-SelfSignedCertificate -Type CodeSigningCert `
        -Subject $requesterSubject -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 `
        -KeyExportPolicy NonExportable -NotAfter $notAfter
    $createdThumbprints.Add([string]$requester.Thumbprint)
    $approver = New-SelfSignedCertificate -Type CodeSigningCert `
        -Subject $approverSubject -CertStoreLocation 'Cert:\CurrentUser\My' `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 `
        -KeyExportPolicy NonExportable -NotAfter $notAfter
    $createdThumbprints.Add([string]$approver.Thumbprint)
    if ($requester.Thumbprint -eq $approver.Thumbprint -or
            $requester.HasPrivateKey -ne $true -or $approver.HasPrivateKey -ne $true) {
        throw 'Generated signing identities are not distinct usable private identities'
    }

    $null = Export-Certificate -Cert $requester -FilePath $requesterCertificatePath
    $null = Export-Certificate -Cert $approver -FilePath $approverCertificatePath
    foreach ($certificatePath in @(
            $requesterCertificatePath,
            $approverCertificatePath
        )) {
        foreach ($store in @(
                'Cert:\CurrentUser\Root',
                'Cert:\CurrentUser\TrustedPublisher'
            )) {
            $imported = Import-Certificate -FilePath $certificatePath `
                -CertStoreLocation $store
            $trustedLocations.Add((Join-Path $store $imported.Thumbprint))
        }
    }

    $receipt = [ordered]@{
        schemaVersion = 1
        identityMode = 'WORKGROUP_LOCAL_AUTOMATED'
        createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        requester = [ordered]@{
            identity = 'workgroup-release-requester'
            subject = $requester.Subject
            thumbprint = $requester.Thumbprint.ToUpperInvariant()
            publicCertificatePath = $requesterCertificatePath
            publicCertificateSha256 = Get-FileSha256 $requesterCertificatePath
        }
        approver = [ordered]@{
            identity = 'workgroup-release-approver'
            subject = $approver.Subject
            thumbprint = $approver.Thumbprint.ToUpperInvariant()
            publicCertificatePath = $approverCertificatePath
            publicCertificateSha256 = Get-FileSha256 $approverCertificatePath
        }
        planSha256 = $planSha256
        privateKeyExported = $false
        operatorCertificateSteps = 0
    }
    [IO.File]::WriteAllText(
        $receiptPath,
        ($receipt | ConvertTo-Json -Depth 6),
        (New-Object Text.UTF8Encoding($false))
    )
    Write-Result ([pscustomobject][ordered]@{
            status = 'INITIALIZED'
            executable = $true
            identityMode = 'WORKGROUP_LOCAL_AUTOMATED'
            identityCount = 2
            operatorCertificateSteps = 0
            requesterThumbprint = $receipt.requester.thumbprint
            approverThumbprint = $receipt.approver.thumbprint
            receiptPath = $receiptPath
            receiptSha256 = Get-FileSha256 $receiptPath
            planSha256 = $planSha256
        })
}
catch {
    foreach ($location in @($trustedLocations)) {
        Remove-Item -LiteralPath $location -Force -ErrorAction SilentlyContinue
    }
    foreach ($thumbprint in @($createdThumbprints)) {
        Remove-Item -LiteralPath (Join-Path 'Cert:\CurrentUser\My' $thumbprint) `
            -Force -ErrorAction SilentlyContinue
    }
    foreach ($path in @(
            $receiptPath,
            $requesterCertificatePath,
            $approverCertificatePath
        )) {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
    throw
}
