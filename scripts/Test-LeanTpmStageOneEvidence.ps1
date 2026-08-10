[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EvidencePath,
    [string]$SignaturePath = '',
    [string]$TrustedCertificateThumbprint = '',
    [Parameter(Mandatory)][string]$ExpectedBaselineCommit,
    [Parameter(Mandatory)][string]$ExpectedReleaseId,
    [Parameter(Mandatory)][string]$ExpectedProductVersion,
    [Parameter(Mandatory)][string]$ExpectedPackageSha256,
    [Parameter(Mandatory)][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][string]$ExpectedToolchainLockSha256,
    [switch]$AllowUnsignedTestEvidence,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Assert-OnlyProperties {
    param($Object, [string[]]$Allowed, [string]$Context)

    if ($null -eq $Object) { throw "$Context must be an object" }
    $actual = @($Object.PSObject.Properties.Name)
    $unexpected = @($actual | Where-Object { $Allowed -notcontains $_ })
    $missing = @($Allowed | Where-Object { $actual -notcontains $_ })
    if ($unexpected.Count -gt 0 -or $missing.Count -gt 0) {
        throw "$Context contains unknown fields or omits required fields"
    }
}

function ConvertTo-StrictUtcTimestamp {
    param([string]$Value, [string]$Context)

    $parsed = [DateTimeOffset]::MinValue
    if ([string]::IsNullOrWhiteSpace($Value) -or -not $Value.EndsWith('Z') -or
            -not [DateTimeOffset]::TryParse($Value, [ref]$parsed)) {
        throw "$Context must be an explicit UTC timestamp"
    }
    return $parsed.ToUniversalTime()
}

function Test-DetachedCmsSignature {
    param(
        [byte[]]$ContentBytes,
        [string]$DetachedSignaturePath,
        [string]$ExpectedThumbprint
    )

    if ([string]::IsNullOrWhiteSpace($ExpectedThumbprint) -or
            $ExpectedThumbprint.Replace(' ', '') -notmatch '^[0-9A-Fa-f]{40,128}$') {
        throw 'A pinned stage-one evidence signer thumbprint is required'
    }
    $resolvedSignature = (Resolve-Path -LiteralPath $DetachedSignaturePath).Path
    $signatureBytes = [IO.File]::ReadAllBytes($resolvedSignature)
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $cms = New-Object Security.Cryptography.Pkcs.SignedCms(
        (New-Object Security.Cryptography.Pkcs.ContentInfo(, $ContentBytes)),
        $true
    )
    $cms.Decode($signatureBytes)
    $cms.CheckSignature($true)
    if ($cms.SignerInfos.Count -ne 1 -or $null -eq $cms.SignerInfos[0].Certificate) {
        throw 'Stage-one evidence must have exactly one embedded signer certificate'
    }
    $certificate = $cms.SignerInfos[0].Certificate
    if (-not $certificate.Thumbprint.Replace(' ', '').Equals(
            $ExpectedThumbprint.Replace(' ', ''),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Stage-one evidence signer does not match the pinned trust anchor'
    }
    $now = [DateTime]::UtcNow
    if ($now -lt $certificate.NotBefore.ToUniversalTime() -or
            $now -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'Stage-one evidence signer is outside its validity period'
    }
    $hasCodeSigningEku = @($certificate.Extensions | Where-Object {
            $_ -is [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]
        } | ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object { $_ } | Where-Object {
            $_.Value -eq '1.3.6.1.5.5.7.3.3'
        }).Count -gt 0
    if (-not $hasCodeSigningEku) {
        throw 'Stage-one evidence signer lacks the code-signing EKU'
    }
    $chain = New-Object Security.Cryptography.X509Certificates.X509Chain
    $chain.ChainPolicy.RevocationMode =
        [Security.Cryptography.X509Certificates.X509RevocationMode]::Online
    $chain.ChainPolicy.RevocationFlag =
        [Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
    if (-not $chain.Build($certificate)) {
        throw 'Stage-one evidence signer chain or revocation check failed'
    }
}

$resolvedEvidence = (Resolve-Path -LiteralPath $EvidencePath).Path
$evidenceItem = Get-Item -LiteralPath $resolvedEvidence -Force
if (($evidenceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $evidenceItem.Length -lt 2 -or $evidenceItem.Length -gt 1048576) {
    throw 'Stage-one evidence must be a regular JSON file no larger than 1 MiB'
}
$evidenceBytes = [IO.File]::ReadAllBytes($resolvedEvidence)
try {
    if (-not $AllowUnsignedTestEvidence) {
        if ([string]::IsNullOrWhiteSpace($SignaturePath)) {
            throw 'Executable stage-one evidence must have a detached CMS signature'
        }
        Test-DetachedCmsSignature `
            -ContentBytes $evidenceBytes `
            -DetachedSignaturePath $SignaturePath `
            -ExpectedThumbprint $TrustedCertificateThumbprint
    }
    elseif (-not [string]::IsNullOrWhiteSpace($SignaturePath)) {
        throw 'Unsigned test evidence mode cannot accept an ambiguous signature path'
    }

    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    $evidence = $strictUtf8.GetString($evidenceBytes) | ConvertFrom-Json
    $topLevel = @(
        'schemaVersion', 'evidenceId', 'environmentId', 'hostId', 'environmentKind',
        'baselineCommit', 'workingTreeClean', 'releaseId', 'productVersion',
        'packageSha256', 'manifestSha256', 'toolchainLockSha256', 'startedAtUtc',
        'completedAtUtc', 'scenarios', 'residualRisks'
    )
    Assert-OnlyProperties $evidence $topLevel 'stage-one evidence'
    if ([int]$evidence.schemaVersion -ne 1 -or
            [string]$evidence.evidenceId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$evidence.environmentId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$evidence.hostId -notmatch '^[A-Za-z0-9._-]{3,128}$' -or
            [string]$evidence.environmentKind -cne 'ISOLATED_WINDOWS_SERVER' -or
            -not [bool]$evidence.workingTreeClean -or
            [string]$evidence.releaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$') {
        throw 'Stage-one evidence identity or isolation contract is invalid'
    }
    foreach ($digestField in @(
            'packageSha256', 'manifestSha256', 'toolchainLockSha256'
        )) {
        if ([string]$evidence.$digestField -notmatch '^[0-9a-f]{64}$') {
            throw "Stage-one evidence $digestField is invalid"
        }
    }
    if ([string]$evidence.baselineCommit -cne $ExpectedBaselineCommit -or
            [string]$evidence.releaseId -cne $ExpectedReleaseId -or
            [string]$evidence.productVersion -cne $ExpectedProductVersion -or
            [string]$evidence.packageSha256 -cne $ExpectedPackageSha256 -or
            [string]$evidence.manifestSha256 -cne $ExpectedManifestSha256 -or
            [string]$evidence.toolchainLockSha256 -cne $ExpectedToolchainLockSha256) {
        throw 'Stage-one evidence is not bound to this exact source, package, manifest, and toolchain'
    }
    $started = ConvertTo-StrictUtcTimestamp ([string]$evidence.startedAtUtc) 'startedAtUtc'
    $completed = ConvertTo-StrictUtcTimestamp ([string]$evidence.completedAtUtc) 'completedAtUtc'
    if ($completed -lt $started -or $completed -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
            $completed -lt [DateTimeOffset]::UtcNow.AddDays(-30)) {
        throw 'Stage-one evidence time window is invalid, future-dated, or older than 30 days'
    }

    $requiredScenarios = @(
        'WINDOWS_SERVICE_LIFECYCLE', 'HTTPS_SECRET_HEALTH', 'BACKUP_OFFHOST_RECEIPT',
        'DATABASE_FRESH_UPGRADE', 'DATABASE_OLD_SCHEMA_MATRIX', 'DATABASE_REPEAT_MIGRATE',
        'DEPLOYMENT_E2E', 'ROLLBACK_E2E', 'RESTORE_E2E', 'POWER_LOSS_RECOVERY',
        'FAULT_INJECTION'
    )
    $scenarios = @($evidence.scenarios)
    if ($scenarios.Count -ne $requiredScenarios.Count) {
        throw 'Stage-one evidence must contain every required scenario exactly once'
    }
    $seenScenarios = New-Object 'Collections.Generic.HashSet[string]' `
        ([StringComparer]::Ordinal)
    foreach ($scenario in $scenarios) {
        Assert-OnlyProperties $scenario @(
            'name', 'result', 'startedAtUtc', 'completedAtUtc', 'evidenceSha256',
            'evidenceUri'
        ) 'stage-one scenario'
        $scenarioName = [string]$scenario.name
        $scenarioStart = ConvertTo-StrictUtcTimestamp `
            ([string]$scenario.startedAtUtc) "$scenarioName.startedAtUtc"
        $scenarioCompleted = ConvertTo-StrictUtcTimestamp `
            ([string]$scenario.completedAtUtc) "$scenarioName.completedAtUtc"
        if ($scenarioName -notin $requiredScenarios -or
                -not $seenScenarios.Add($scenarioName) -or
                [string]$scenario.result -cne 'PASS' -or
                [string]$scenario.evidenceSha256 -notmatch '^[0-9a-f]{64}$' -or
                [string]$scenario.evidenceUri -notmatch
                    '^(?:evidence|worm)://[A-Za-z0-9._/@:-]{3,500}$' -or
                $scenarioStart -lt $started -or $scenarioCompleted -gt $completed -or
                $scenarioCompleted -lt $scenarioStart) {
            throw "Stage-one scenario $scenarioName is missing, duplicated, failed, or invalid"
        }
    }
    if (@($requiredScenarios | Where-Object { -not $seenScenarios.Contains($_) }).Count -gt 0) {
        throw 'Stage-one evidence omits a required scenario'
    }
    foreach ($risk in @($evidence.residualRisks)) {
        if ([string]::IsNullOrWhiteSpace([string]$risk) -or ([string]$risk).Length -gt 500) {
            throw 'Stage-one residual risk entries must be short non-empty statements'
        }
    }

    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $evidenceSha256 = [BitConverter]::ToString($hasher.ComputeHash($evidenceBytes)).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
    $report = [pscustomobject]@{
        status = 'PASS'
        evidenceId = [string]$evidence.evidenceId
        environmentId = [string]$evidence.environmentId
        hostId = [string]$evidence.hostId
        releaseId = [string]$evidence.releaseId
        evidenceSha256 = $evidenceSha256
        scenarioCount = $scenarios.Count
        signed = -not [bool]$AllowUnsignedTestEvidence
        completedAtUtc = [string]$evidence.completedAtUtc
    }
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
}
finally {
    [Array]::Clear($evidenceBytes, 0, $evidenceBytes.Length)
}
