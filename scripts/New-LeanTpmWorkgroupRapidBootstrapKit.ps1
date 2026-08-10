[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory)][string]$SigningReceiptPath,
    [Parameter(Mandatory)][string]$OpsControlPlaneJarPath,
    [Parameter(Mandatory)][string]$WinSWPath,
    [Parameter(Mandatory)][string]$JavaExecutablePath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedJavaSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedCaddySha256,
    [Parameter(Mandatory)][string]$DeploymentToolkitRoot,
    [Parameter(Mandatory)][string]$DeploymentToolkitLockPath,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]{3,63}$')]
    [string]$ExpectedComputerName,
    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$ProductVersion,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')]
    [string]$MainCommit,
    [Parameter(Mandatory)][string]$OutputPath,
    [switch]$PlanOnly,
    [switch]$ConfirmPackageBuild,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$actions = @(
    'GENERATE_SINGLE_OPERATOR_TOKEN',
    'COPY_PINNED_OPS_ARTIFACTS',
    'SIGN_TWO_FIXED_SERVICE_STARTERS',
    'COPY_LOCKED_DEPLOYMENT_TOOLKIT',
    'RENDER_FIXED_BOOTSTRAP_TEMPLATES',
    'CREATE_SINGLE_BOOTSTRAP_ZIP'
)

function Get-FixedFile {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$Label path must be absolute" }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse file"
    }
    return $item.FullName
}

function Get-FixedDirectory {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$Label path must be absolute" }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if (-not $item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse directory"
    }
    return $item.FullName.TrimEnd('\', '/')
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.ToLowerInvariant()
}

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

function Assert-ExactProperties {
    param($Value, [string[]]$Expected, [string]$Label)

    if ($null -eq $Value) { throw "$Label is missing" }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) { throw "$Label property count is invalid" }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Read-StrictJson {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    $fixed = Get-FixedFile $Path $Label
    $bytes = [IO.File]::ReadAllBytes($fixed)
    if ($bytes.Length -gt 4MB) { throw "$Label exceeds 4 MiB" }
    $strict = New-Object Text.UTF8Encoding($false, $true)
    try { return $strict.GetString($bytes) | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "$Label must be strict UTF-8 JSON" }
}

function Write-Result {
    param([Parameter(Mandatory)]$Value)
    if ($OutputFormat -eq 'Json') { $Value | ConvertTo-Json -Depth 8 -Compress }
    else { $Value | Format-List }
}

$receiptPath = Get-FixedFile $SigningReceiptPath 'Signing receipt'
$jarPath = Get-FixedFile $OpsControlPlaneJarPath 'OpsControl JAR'
$wrapperPath = Get-FixedFile $WinSWPath 'WinSW wrapper'
$toolkitRoot = Get-FixedDirectory $DeploymentToolkitRoot 'Deployment toolkit'
$lockPath = Get-FixedFile $DeploymentToolkitLockPath 'Deployment toolkit lock'
$toolkitPrefix = $toolkitRoot + [IO.Path]::DirectorySeparatorChar
if (-not $lockPath.StartsWith($toolkitPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Deployment toolkit lock must be inside DeploymentToolkitRoot'
}
if (-not [IO.Path]::IsPathRooted($JavaExecutablePath) -or
        -not [IO.Path]::IsPathRooted($OutputPath)) {
    throw 'JavaExecutablePath and OutputPath must be absolute'
}
$output = [IO.Path]::GetFullPath($OutputPath)
if ([IO.Path]::GetExtension($output) -cne '.zip' -or
        $output.StartsWith('\\') -or $output.StartsWith('\\?\')) {
    throw 'OutputPath must be a local .zip file'
}

$receipt = Read-StrictJson $receiptPath 'Signing receipt'
Assert-ExactProperties $receipt @(
    'schemaVersion', 'identityMode', 'createdAtUtc', 'requester', 'approver',
    'planSha256', 'privateKeyExported', 'operatorCertificateSteps'
) 'Signing receipt'
foreach ($role in @('requester', 'approver')) {
    Assert-ExactProperties $receipt.$role @(
        'identity', 'subject', 'thumbprint', 'publicCertificatePath',
        'publicCertificateSha256'
    ) "Signing receipt $role"
    if ([string]$receipt.$role.thumbprint -cnotmatch '^[A-F0-9]{40}$' -or
            [string]$receipt.$role.publicCertificateSha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw "Signing receipt $role digest or thumbprint is invalid"
    }
    $publicCertificatePath = Get-FixedFile `
        ([string]$receipt.$role.publicCertificatePath) "$role public certificate"
    if ((Get-FileSha256 $publicCertificatePath) -cne
            [string]$receipt.$role.publicCertificateSha256) {
        throw "$role public certificate differs from the signing receipt"
    }
}
if ([int]$receipt.schemaVersion -ne 1 -or
        [string]$receipt.identityMode -cne 'WORKGROUP_LOCAL_AUTOMATED' -or
        [bool]$receipt.privateKeyExported -or
        [int]$receipt.operatorCertificateSteps -ne 0 -or
        [string]$receipt.requester.thumbprint -ceq [string]$receipt.approver.thumbprint) {
    throw 'Signing receipt does not represent two automated non-exported identities'
}

$lock = Read-StrictJson $lockPath 'Deployment toolkit lock'
Assert-ExactProperties $lock @(
    'executorRelativePath', 'files', 'schemaVersion', 'toolkitId'
) 'Deployment toolkit lock'
if ([int]$lock.schemaVersion -ne 1 -or
        [string]$lock.toolkitId -cne 'leantpm-release-agent-toolkit' -or
        [string]$lock.executorRelativePath -cne 'scripts/Invoke-LeanTpmDeployment.ps1') {
    throw 'Deployment toolkit lock identity is invalid'
}
$seen = New-Object 'System.Collections.Generic.HashSet[string]' `
    ([StringComparer]::Ordinal)
foreach ($entry in @($lock.files)) {
    Assert-ExactProperties $entry @('path', 'sha256') 'Deployment toolkit entry'
    $relative = [string]$entry.path
        if ($relative -notmatch
            '^(?:(?:scripts|deploy/windows)/[A-Za-z0-9._/-]+\.ps1|release/(?:deployment-bundle\.schema|toolchain-lock)\.json)$' -or
            $relative.Contains('..') -or -not $seen.Add($relative) -or
            [string]$entry.sha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Deployment toolkit lock contains an unsafe entry'
    }
    $source = Get-FixedFile `
        (Join-Path $toolkitRoot $relative.Replace('/', '\')) "Toolkit $relative"
    if ((Get-FileSha256 $source) -cne [string]$entry.sha256) {
        throw "Toolkit file differs from lock: $relative"
    }
}
foreach ($required in @(
        'deploy/windows/Invoke-LeanTpmReleaseAgent.ps1',
        'release/deployment-bundle.schema.json',
        'release/toolchain-lock.json',
        'scripts/Invoke-LeanTpmDeployment.ps1',
        'scripts/Invoke-LeanTpmWorkgroupRapidDeployment.ps1',
        'scripts/Test-LeanTpmReleaseApproval.ps1',
        'scripts/Test-ReleasePackage.ps1',
        'scripts/Test-LeanTpmDeploymentBundle.ps1'
    )) {
    if (-not $seen.Contains($required)) { throw "Toolkit is missing $required" }
}

$planCore = [ordered]@{
    schemaVersion = 1
    bootstrapMode = 'WORKGROUP_RAPID'
    expectedComputerName = $ExpectedComputerName
    productVersion = $ProductVersion
    mainCommit = $MainCommit
    opsControlJarSha256 = Get-FileSha256 $jarPath
    winSWSha256 = Get-FileSha256 $wrapperPath
    javaExecutablePath = [IO.Path]::GetFullPath($JavaExecutablePath)
    javaSha256 = $ExpectedJavaSha256
    caddySha256 = $ExpectedCaddySha256
    deploymentToolkitLockSha256 = Get-FileSha256 $lockPath
    requesterThumbprint = [string]$receipt.requester.thumbprint
    approverThumbprint = [string]$receipt.approver.thumbprint
    outputPath = $output
    actions = $actions
}
$planSha256 = Get-TextSha256 ($planCore | ConvertTo-Json -Depth 6 -Compress)
if ($PlanOnly) {
    Write-Result ([pscustomobject][ordered]@{
            status = 'PLAN'
            executable = $false
            bootstrapMode = 'WORKGROUP_RAPID'
            productVersion = $ProductVersion
            mainCommit = $MainCommit
            operatorTokenCount = 1
            webConfirmationCount = 1
            operatorCertificateSteps = 0
            outputPath = $output
            planSha256 = $planSha256
            actions = $actions
        })
    return
}
if (-not $ConfirmPackageBuild) {
    throw 'Run with PlanOnly first, then provide ConfirmPackageBuild'
}
if (Test-Path -LiteralPath $output) {
    throw 'OutputPath already exists; refusing to overwrite a bootstrap kit'
}
$operatorTokenPath = $output + '.operator-token.txt'
if (Test-Path -LiteralPath $operatorTokenPath) {
    throw 'Operator token output already exists; refusing to overwrite it'
}
if (-not $PSCmdlet.ShouldProcess($output, 'Build one fixed WORKGROUP bootstrap ZIP')) {
    return
}

$requesterThumbprint = [string]$receipt.requester.thumbprint
$requesterCertificate = Get-Item `
    -LiteralPath (Join-Path 'Cert:\CurrentUser\My' $requesterThumbprint) -ErrorAction Stop
$approverCertificate = Get-Item `
    -LiteralPath (Join-Path 'Cert:\CurrentUser\My' ([string]$receipt.approver.thumbprint)) `
    -ErrorAction Stop
if (-not $requesterCertificate.HasPrivateKey -or -not $approverCertificate.HasPrivateKey) {
    throw 'Both automated signing identities must retain their private keys locally'
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$opsStarterSource = Get-FixedFile `
    (Join-Path $repositoryRoot 'deploy\windows\Start-LeanTpmOpsControl.ps1') `
    'OpsControl starter source'
$agentStarterSource = Get-FixedFile `
    (Join-Path $repositoryRoot 'deploy\windows\Start-LeanTpmReleaseAgentService.ps1') `
    'ReleaseAgent starter source'
$serverBootstrapSource = Get-FixedFile `
    (Join-Path $repositoryRoot 'deploy\windows\Invoke-LeanTpmWorkgroupRapidBootstrap.ps1') `
    'Server rapid bootstrap entry point'
$parent = Split-Path -Parent $output
if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
    throw 'OutputPath parent directory must already exist'
}
$staging = Join-Path $parent ('.leantpm-bootstrap-' + [Guid]::NewGuid().ToString('N'))
$randomBytes = New-Object byte[] 32
$randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $randomGenerator.GetBytes($randomBytes) }
finally { $randomGenerator.Dispose() }
$operatorToken = [Convert]::ToBase64String($randomBytes).TrimEnd('=').
    Replace('+', '-').Replace('/', '_')
[Array]::Clear($randomBytes, 0, $randomBytes.Length)
$operatorTokenSha256 = Get-TextSha256 $operatorToken
try {
    $null = New-Item -ItemType Directory -Path $staging
    $inputsRoot = Join-Path $staging 'inputs'
    $kitToolkitRoot = Join-Path $staging 'toolkit'
    $certificatesRoot = Join-Path $staging 'certificates'
    foreach ($directory in @($inputsRoot, $kitToolkitRoot, $certificatesRoot)) {
        $null = New-Item -ItemType Directory -Path $directory
    }
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputsRoot 'ops-control-plane.jar')
    Copy-Item -LiteralPath $wrapperPath -Destination (Join-Path $inputsRoot 'WinSW.exe')
    Copy-Item -LiteralPath $opsStarterSource `
        -Destination (Join-Path $inputsRoot 'Start-LeanTpmOpsControl.ps1')
    Copy-Item -LiteralPath $agentStarterSource `
        -Destination (Join-Path $inputsRoot 'Start-LeanTpmReleaseAgentService.ps1')
    Copy-Item -LiteralPath $serverBootstrapSource `
        -Destination (Join-Path $staging 'Invoke-LeanTpmWorkgroupRapidBootstrap.ps1')
    Copy-Item -LiteralPath ([string]$receipt.requester.publicCertificatePath) `
        -Destination (Join-Path $certificatesRoot 'requester-public.cer')
    Copy-Item -LiteralPath ([string]$receipt.approver.publicCertificatePath) `
        -Destination (Join-Path $certificatesRoot 'approver-public.cer')
    foreach ($entry in @($lock.files)) {
        $relative = [string]$entry.path
        $source = Join-Path $toolkitRoot $relative.Replace('/', '\')
        $destination = Join-Path $kitToolkitRoot $relative.Replace('/', '\')
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force
        Copy-Item -LiteralPath $source -Destination $destination
    }
    $kitLockPath = Join-Path $kitToolkitRoot 'release\release-agent-toolkit-lock.json'
    Copy-Item -LiteralPath $lockPath -Destination $kitLockPath

    foreach ($starter in @(
            (Join-Path $inputsRoot 'Start-LeanTpmOpsControl.ps1'),
            (Join-Path $inputsRoot 'Start-LeanTpmReleaseAgentService.ps1')
        )) {
        $signature = Set-AuthenticodeSignature -FilePath $starter `
            -Certificate $requesterCertificate -HashAlgorithm SHA256 `
            -IncludeChain All
        if ($signature.Status -ne 'Valid' -or
                $signature.SignerCertificate.Thumbprint -cne $requesterThumbprint) {
            throw 'A fixed service starter could not be signed by the automated identity'
        }
    }

    $bundleSchemaPath = Join-Path $kitToolkitRoot 'release\deployment-bundle.schema.json'
    $packageVerifierPath = Join-Path $kitToolkitRoot 'scripts\Test-ReleasePackage.ps1'
    $approvalVerifierPath = Join-Path $kitToolkitRoot 'scripts\Test-LeanTpmReleaseApproval.ps1'
    $bundleVerifierPath = Join-Path $kitToolkitRoot 'scripts\Test-LeanTpmDeploymentBundle.ps1'
    $configTemplate = @"
server:
  address: 127.0.0.1
  port: 18090
leantpm:
  ops:
    data-root: 'D:\LeanTPM\Runtime\ops-control-plane'
    maximum-upload-bytes: 536870912
    required-approvals: 1
    powershell-executable: 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
    verifier-script: 'D:\LeanTPM\App\ops-services\release-agent-toolkit\scripts\Test-ReleasePackage.ps1'
    verifier-script-sha256: '$(Get-FileSha256 $packageVerifierPath)'
    bundle-verifier-script: 'D:\LeanTPM\App\ops-services\release-agent-toolkit\scripts\Test-LeanTpmDeploymentBundle.ps1'
    bundle-verifier-script-sha256: '$(Get-FileSha256 $bundleVerifierPath)'
    deployment-bundle-schema-sha256: '$(Get-FileSha256 $bundleSchemaPath)'
    approval-verifier-script-sha256: '$(Get-FileSha256 $approvalVerifierPath)'
    release-trust-config-path: 'D:\LeanTPM\Runtime\config\release-trust.json'
    trusted-certificate-thumbprint: '$requesterThumbprint'
    verifier-timeout: 2m
    verifier-maximum-output-bytes: 262144
    agent-heartbeat-maximum-age: 30s
    host-layout-path: 'C:\ProgramData\LeanTPM-bootstrap\host-layout.json'
    host-layout-sha256: '@HOST_LAYOUT_SHA256@'
    current-release-pointer: 'D:\LeanTPM\Runtime\pointers\current-release.json'
    operator-token-sha256:
      release_operator: '$operatorTokenSha256'
    monitoring:
      host-resources-enabled: true
      enabled: false
      interval: 30s
      initial-delay: 10s
      disk-path: 'D:\LeanTPM\Runtime'
      disk-degraded-percent: 85
      disk-down-percent: 95
      sc-executable: 'C:\Windows\System32\sc.exe'
      service-timeout: 5s
      backend-readiness-uri: 'http://127.0.0.1:18080/actuator/health/readiness'
      backend-readiness-timeout: 5s
      database-url: 'jdbc:mysql://127.0.0.1:3306/leantpm?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000'
      database-username: ''
      database-password: ''
      database-timeout-seconds: 5
      log-root: 'D:\LeanTPM\Runtime\logs'
      log-files:
        - 'LeanTPM.Backend.wrapper.log'
        - 'LeanTPM.Backend.err.log'
      maximum-log-tail-bytes: 262144
    remediation:
      enabled: false
      failure-threshold: 3
      cooldown: 10m
      maximum-attempts-per-hour: 2
    notifications:
      pushplus:
        enabled: false
        allow-paid-channels: false
        timeout: 8s
        recipients: []
"@
    [IO.File]::WriteAllText(
        (Join-Path $inputsRoot 'application-production.yml.template'),
        $configTemplate,
        (New-Object Text.UTF8Encoding($false))
    )
    $trustTemplate = [ordered]@{
        schemaVersion = 1
        environmentId = 'leantpm-production-cn'
        hostId = '@HOST_ID@'
        publicHost = '8.163.66.164'
        serviceAccountMode = 'WORKGROUP_VIRTUAL'
        backendServiceAccount = 'NT AUTHORITY\NetworkService'
        proxyServiceAccount = 'LocalSystem'
        opsControlServiceAccount = 'NT SERVICE\LeanTPM.OpsControl'
        releaseAgentServiceAccount = 'NT SERVICE\LeanTPM.ReleaseAgent'
        javaSha256 = $ExpectedJavaSha256
        winSWSha256 = Get-FileSha256 $wrapperPath
        caddySha256 = $ExpectedCaddySha256
        manifestCertificateThumbprint = $requesterThumbprint
        backupManifestCertificateThumbprint = [string]$receipt.approver.thumbprint
        scriptSignerThumbprint = $requesterThumbprint
        requesterSigners = @([ordered]@{
                identity = 'workgroup-release-requester'
                thumbprint = $requesterThumbprint
            })
        approverSigners = @([ordered]@{
                identity = 'workgroup-release-approver'
                thumbprint = [string]$receipt.approver.thumbprint
            })
    }
    [IO.File]::WriteAllText(
        (Join-Path $inputsRoot 'release-trust.json.template'),
        ($trustTemplate | ConvertTo-Json -Depth 6),
        (New-Object Text.UTF8Encoding($false))
    )

    $entries = @(
        Get-ChildItem -LiteralPath $staging -File -Recurse -Force |
            ForEach-Object {
                [ordered]@{
                    path = $_.FullName.Substring($staging.Length + 1).Replace('\', '/')
                    bytes = [int64]$_.Length
                    sha256 = Get-FileSha256 $_.FullName
                }
            } | Sort-Object { $_.path }
    )
    $manifest = [ordered]@{
        schemaVersion = 1
        bootstrapMode = 'WORKGROUP_RAPID'
        expectedComputerName = $ExpectedComputerName
        productVersion = $ProductVersion
        mainCommit = $MainCommit
        javaExecutablePath = [IO.Path]::GetFullPath($JavaExecutablePath)
        javaSha256 = $ExpectedJavaSha256
        caddySha256 = $ExpectedCaddySha256
        requesterThumbprint = $requesterThumbprint
        approverThumbprint = [string]$receipt.approver.thumbprint
        operatorTokenSha256 = $operatorTokenSha256
        webConfirmationCount = 1
        entries = $entries
    }
    $manifestPath = Join-Path $staging 'workgroup-rapid-bootstrap.json'
    [IO.File]::WriteAllText(
        $manifestPath,
        ($manifest | ConvertTo-Json -Depth 8),
        (New-Object Text.UTF8Encoding($false))
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory(
        $staging,
        $output,
        [IO.Compression.CompressionLevel]::Optimal,
        $false
    )
    [IO.File]::WriteAllText(
        $operatorTokenPath,
        $operatorToken + [Environment]::NewLine,
        (New-Object Text.UTF8Encoding($false))
    )
    Write-Result ([pscustomobject][ordered]@{
            status = 'CREATED'
            bootstrapMode = 'WORKGROUP_RAPID'
            outputPath = $output
            bytes = (Get-Item -LiteralPath $output).Length
            sha256 = Get-FileSha256 $output
            operatorTokenPath = $operatorTokenPath
            operatorTokenSha256 = $operatorTokenSha256
            operatorTokenCount = 1
            webConfirmationCount = 1
            operatorCertificateSteps = 0
            planSha256 = $planSha256
        })
}
catch {
    Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $operatorTokenPath -Force -ErrorAction SilentlyContinue
    throw
}
finally {
    $operatorToken = $null
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    }
}
