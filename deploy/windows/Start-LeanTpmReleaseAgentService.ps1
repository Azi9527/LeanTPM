[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedToolkitLockSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9._-]{2,63}$')]
    [string]$AgentId,
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AgentVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-FixedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a fixed regular file"
    }
    return $item.FullName
}
function Get-LockEntrySha256 {
    param(
        [Parameter(Mandatory)]$Lock,
        [Parameter(Mandatory)][string]$RelativePath
    )

    $matches = @($Lock.files | Where-Object {
        [string]$_.path -ceq $RelativePath
    })
    if ($matches.Count -ne 1 -or
            [string]$matches[0].sha256 -notmatch '^[a-f0-9]{64}$') {
        throw "Toolkit lock does not contain exactly one $RelativePath entry"
    }
    return [string]$matches[0].sha256
}

$install = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\', '/')
$data = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\', '/')
$toolkitRoot = Join-Path $install 'ops-services\release-agent-toolkit'
$lockPath = Get-FixedFile `
    -Path (Join-Path $toolkitRoot 'release\release-agent-toolkit-lock.json') `
    -Label 'ReleaseAgent toolkit lock'
$lockHash = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash
if (-not $lockHash.Equals(
        $ExpectedToolkitLockSha256,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'ReleaseAgent toolkit lock SHA-256 differs from the service binding'
}
$lock = Get-Content -LiteralPath $lockPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([int]$lock.schemaVersion -ne 1 -or
        [string]$lock.toolkitId -cne 'leantpm-release-agent-toolkit' -or
        [string]$lock.executorRelativePath -cne
            'scripts/Invoke-LeanTpmDeployment.ps1') {
    throw 'ReleaseAgent toolkit lock identity is invalid'
}

$agentRelative = 'deploy/windows/Invoke-LeanTpmReleaseAgent.ps1'
$packageVerifierRelative = 'scripts/Test-ReleasePackage.ps1'
$approvalVerifierRelative = 'scripts/Test-LeanTpmReleaseApproval.ps1'
$agentPath = Get-FixedFile -Path (
    Join-Path $toolkitRoot ($agentRelative.Replace('/', '\'))
) -Label 'ReleaseAgent entry point'
$packageVerifierPath = Get-FixedFile -Path (
    Join-Path $toolkitRoot ($packageVerifierRelative.Replace('/', '\'))
) -Label 'release package verifier'
$approvalVerifierPath = Get-FixedFile -Path (
    Join-Path $toolkitRoot ($approvalVerifierRelative.Replace('/', '\'))
) -Label 'release approval verifier'

foreach ($binding in @(
        @($agentPath, (Get-LockEntrySha256 $lock $agentRelative), 'ReleaseAgent entry point'),
        @($packageVerifierPath, (Get-LockEntrySha256 $lock $packageVerifierRelative),
            'release package verifier'),
        @($approvalVerifierPath, (Get-LockEntrySha256 $lock $approvalVerifierRelative),
            'release approval verifier')
    )) {
    $actual = (Get-FileHash -LiteralPath $binding[0] -Algorithm SHA256).Hash
    if (-not $actual.Equals($binding[1], [StringComparison]::OrdinalIgnoreCase)) {
        throw "$($binding[2]) differs from the toolkit lock"
    }
}

$releaseTrustPath = Get-FixedFile `
    -Path (Join-Path $data 'config\release-trust.json') `
    -Label 'release trust configuration'
$releaseTrust = Get-Content -LiteralPath $releaseTrustPath -Encoding utf8 -Raw |
    ConvertFrom-Json
$certificateThumbprint = [string]$releaseTrust.manifestCertificateThumbprint
if ($certificateThumbprint -notmatch '^[a-fA-F0-9]{40}$') {
    throw 'Release trust does not pin the production manifest certificate'
}

$opsDataRoot = Join-Path $data 'ops-control-plane'
$queueRoot = Join-Path $opsDataRoot 'queue'
$uploadRoot = Join-Path $opsDataRoot 'uploads'
$approvalRoot = Join-Path $opsDataRoot 'approvals'

while ($true) {
    & $agentPath `
        -Mode ExecuteSignedDeployment `
        -QueueRoot $queueRoot `
        -UploadRoot $uploadRoot `
        -ApprovalRoot $approvalRoot `
        -PackageVerifierPath $packageVerifierPath `
        -PackageVerifierSha256 (Get-LockEntrySha256 $lock $packageVerifierRelative) `
        -ApprovalVerifierPath $approvalVerifierPath `
        -ApprovalVerifierSha256 (Get-LockEntrySha256 $lock $approvalVerifierRelative) `
        -ReleaseTrustConfigPath $releaseTrustPath `
        -DeploymentToolkitRoot $toolkitRoot `
        -DeploymentToolkitLockPath $lockPath `
        -DeploymentToolkitLockSha256 $ExpectedToolkitLockSha256 `
        -TrustedCertificateThumbprint $certificateThumbprint `
        -AgentId $AgentId `
        -AgentVersion $AgentVersion `
        -RunOnce `
        -OutputFormat Json
    if (-not $?) {
        throw 'ReleaseAgent single-cycle execution failed'
    }
    Start-Sleep -Seconds 2
}
