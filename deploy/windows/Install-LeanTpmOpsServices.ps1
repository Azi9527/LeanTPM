[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$WrapperPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedWrapperSha256,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$JavaExecutable,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedJavaSha256,
    [Parameter(Mandatory)][string]$OpsControlPlaneJarPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedOpsControlPlaneJarSha256,
    [Parameter(Mandatory)][string]$OpsControlPlaneConfigPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedOpsControlPlaneConfigSha256,
    [Parameter(Mandatory)][string]$SignedOpsStarterPath,
    [Parameter(Mandatory)][string]$SignedReleaseAgentStarterPath,
    [Parameter(Mandatory)][string]$DeploymentToolkitRoot,
    [Parameter(Mandatory)][string]$DeploymentToolkitLockPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedDeploymentToolkitLockSha256,
    [Parameter(Mandatory)][string]$OpsServiceAccount,
    [Parameter(Mandatory)][string]$ReleaseAgentServiceAccount,
    [Parameter(Mandatory)][string]$BackendServiceAccount,
    [Parameter(Mandatory)][string]$ProxyServiceAccount,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9._-]{2,63}$')]
    [string]$AgentId,
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AgentVersion,
    [switch]$AllowNonProductionRoots,
    [switch]$AllowUnpinnedTestInputs,
    [switch]$PlanOnly,
    [switch]$ConfirmInstallation,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$opsServiceId = 'LeanTPM.OpsControl'
$agentServiceId = 'LeanTPM.ReleaseAgent'
$serviceIds = @($opsServiceId, $agentServiceId)
$gmsaPattern = '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$'

function Get-FixedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a fixed regular file"
    }
    return $item.FullName
}

function Get-FixedDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if (-not $item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a fixed directory"
    }
    return $item.FullName.TrimEnd('\', '/')
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ExpectedSha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    $actual = Get-Sha256 $Path
    if (-not $actual.Equals($Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label SHA-256 does not match the approved value"
    }
    return $actual
}

function Assert-DistinctGmsaAccounts {
    $accounts = @(
        $OpsServiceAccount,
        $ReleaseAgentServiceAccount,
        $BackendServiceAccount,
        $ProxyServiceAccount
    )
    foreach ($account in $accounts) {
        if ($account -notmatch $gmsaPattern) {
            throw 'All Ops, ReleaseAgent, Backend and Proxy accounts must be gMSA-shaped accounts'
        }
    }
    $normalized = @($accounts | ForEach-Object { $_.ToUpperInvariant() } |
        Select-Object -Unique)
    if ($normalized.Count -ne $accounts.Count) {
        throw 'Ops, ReleaseAgent, Backend and Proxy service accounts must remain distinct'
    }
}

function Get-RenderedTemplates {
    param(
        [Parameter(Mandatory)][string]$ServiceRoot,
        [Parameter(Mandatory)][string]$ResolvedInstall,
        [Parameter(Mandatory)][string]$ResolvedData,
        [Parameter(Mandatory)][string]$ResolvedJava,
        [Parameter(Mandatory)][string]$JavaSha256,
        [Parameter(Mandatory)][string]$JarSha256,
        [Parameter(Mandatory)][string]$ConfigSha256,
        [Parameter(Mandatory)][string]$ToolkitLockSha256
    )

    $opsTemplate = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot 'LeanTPM.OpsControl.xml.template'
    ) -Encoding utf8 -Raw
    $agentTemplate = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot 'LeanTPM.ReleaseAgent.xml.template'
    ) -Encoding utf8 -Raw
    return [pscustomobject]@{
        ops = $opsTemplate.Replace('@SERVICE_ROOT@', $ServiceRoot).
            Replace('@INSTALL_ROOT@', $ResolvedInstall).
            Replace('@DATA_ROOT@', $ResolvedData).
            Replace('@JAVA_EXECUTABLE@', $ResolvedJava).
            Replace('@JAVA_SHA256@', $JavaSha256).
            Replace('@OPS_JAR_SHA256@', $JarSha256).
            Replace('@OPS_CONFIG_SHA256@', $ConfigSha256).
            Replace('@OPS_SERVICE_ACCOUNT@', $OpsServiceAccount)
        agent = $agentTemplate.Replace('@SERVICE_ROOT@', $ServiceRoot).
            Replace('@INSTALL_ROOT@', $ResolvedInstall).
            Replace('@DATA_ROOT@', $ResolvedData).
            Replace('@TOOLKIT_LOCK_SHA256@', $ToolkitLockSha256).
            Replace('@AGENT_ID@', $AgentId).
            Replace('@AGENT_VERSION@', $AgentVersion).
            Replace('@RELEASE_AGENT_SERVICE_ACCOUNT@', $ReleaseAgentServiceAccount)
    }
}

if ($AllowUnpinnedTestInputs -and -not $PlanOnly) {
    throw 'AllowUnpinnedTestInputs is restricted to side-effect-free PlanOnly validation'
}
Assert-DistinctGmsaAccounts

$resolvedInstall = Get-FixedDirectory -Path $InstallRoot -Label 'InstallRoot'
$resolvedData = Get-FixedDirectory -Path $DataRoot -Label 'DataRoot'
if ($resolvedInstall.Equals($resolvedData, [StringComparison]::OrdinalIgnoreCase) -or
        $resolvedInstall.StartsWith(
            $resolvedData + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        $resolvedData.StartsWith(
            $resolvedInstall + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'InstallRoot and DataRoot must be distinct non-nested roots'
}

$environmentKind = if ($AllowNonProductionRoots) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $resolvedInstall `
    -DataRoot $resolvedData `
    -EnvironmentKind $environmentKind `
    -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
    -OutputFormat Json | ConvertFrom-Json
if ([bool]$rootPolicy.isProductionRootPair -and $AllowNonProductionRoots) {
    throw 'AllowNonProductionRoots cannot be used with the production root pair'
}
if ([bool]$rootPolicy.isProductionRootPair -and (
        [string]$rootPolicy.serviceIdentities.opsControl.account -cne
            $OpsServiceAccount -or
        [string]$rootPolicy.serviceIdentities.releaseAgent.account -cne
            $ReleaseAgentServiceAccount -or
        [string]$rootPolicy.serviceIdentities.backend.account -cne
            $BackendServiceAccount -or
        [string]$rootPolicy.serviceIdentities.proxy.account -cne
            $ProxyServiceAccount
    )) {
    throw 'Service accounts differ from the verified HostBootstrap identities'
}

$resolvedWrapper = Get-FixedFile -Path $WrapperPath -Label 'WinSW wrapper'
$resolvedJava = Get-FixedFile -Path $JavaExecutable -Label 'Java executable'
$resolvedJar = Get-FixedFile -Path $OpsControlPlaneJarPath -Label 'OpsControlPlaneJar'
$resolvedConfig = Get-FixedFile -Path $OpsControlPlaneConfigPath `
    -Label 'OpsControlPlaneConfig'
$resolvedOpsStarter = Get-FixedFile -Path $SignedOpsStarterPath `
    -Label 'signed OpsControl starter'
$resolvedAgentStarter = Get-FixedFile -Path $SignedReleaseAgentStarterPath `
    -Label 'signed ReleaseAgent starter'
$resolvedToolkit = Get-FixedDirectory -Path $DeploymentToolkitRoot `
    -Label 'DeploymentToolkitRoot'
$resolvedToolkitLock = Get-FixedFile -Path $DeploymentToolkitLockPath `
    -Label 'DeploymentToolkitLock'
$toolkitPrefix = $resolvedToolkit + [IO.Path]::DirectorySeparatorChar
if (-not $resolvedToolkitLock.StartsWith(
        $toolkitPrefix,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'DeploymentToolkitLock must be contained by DeploymentToolkitRoot'
}

$wrapperSha256 = Assert-ExpectedSha256 $resolvedWrapper `
    $ExpectedWrapperSha256 'WinSW wrapper'
$javaSha256 = Assert-ExpectedSha256 $resolvedJava $ExpectedJavaSha256 'Java executable'
$jarSha256 = Assert-ExpectedSha256 $resolvedJar `
    $ExpectedOpsControlPlaneJarSha256 'OpsControlPlaneJar'
$configSha256 = Assert-ExpectedSha256 $resolvedConfig `
    $ExpectedOpsControlPlaneConfigSha256 'OpsControlPlaneConfig'
$opsStarterSha256 = Get-Sha256 $resolvedOpsStarter
$agentStarterSha256 = Get-Sha256 $resolvedAgentStarter
$toolkitLockSha256 = Assert-ExpectedSha256 $resolvedToolkitLock `
    $ExpectedDeploymentToolkitLockSha256 'DeploymentToolkitLock'

$toolchainPath = Join-Path $PSScriptRoot '..\..\release\toolchain-lock.json'
$toolchain = Get-Content -LiteralPath $toolchainPath -Encoding utf8 -Raw |
    ConvertFrom-Json
if (-not $AllowUnpinnedTestInputs) {
    if ([string]$toolchain.winSW.status -cne 'PINNED' -or
            [string]$toolchain.java.status -cne 'PINNED' -or
            -not $wrapperSha256.Equals(
                [string]$toolchain.winSW.sha256,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            -not $javaSha256.Equals(
                [string]$toolchain.java.sha256,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Java and WinSW inputs must match release/toolchain-lock.json'
    }
}

$serviceRoot = Join-Path $resolvedInstall 'ops-services'
$opsDataRoot = Join-Path $resolvedData 'ops-control-plane'
$agentDataRoot = Join-Path $resolvedData 'release-agent'
$installedToolkitRoot = Join-Path $serviceRoot 'release-agent-toolkit'
$templates = Get-RenderedTemplates -ServiceRoot $serviceRoot `
    -ResolvedInstall $resolvedInstall -ResolvedData $resolvedData `
    -ResolvedJava $resolvedJava -JavaSha256 $javaSha256 `
    -JarSha256 $jarSha256 -ConfigSha256 $configSha256 `
    -ToolkitLockSha256 $toolkitLockSha256
$templateHasher = [Security.Cryptography.SHA256]::Create()
try {
    $opsXmlSha256 = [BitConverter]::ToString(
        $templateHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($templates.ops))
    ).Replace('-', '').ToLowerInvariant()
    $templateHasher.Initialize()
    $agentXmlSha256 = [BitConverter]::ToString(
        $templateHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($templates.agent))
    ).Replace('-', '').ToLowerInvariant()
}
finally { $templateHasher.Dispose() }
$actions = @(
    'VERIFY_HOST_POLICY',
    'VERIFY_PINNED_INPUTS',
    'VERIFY_DISTINCT_GMSA_IDENTITIES',
    'ACQUIRE_GLOBAL_DEPLOYMENT_LOCK',
    'COPY_IMMUTABLE_SERVICE_ASSETS',
    'SET_SEPARATE_ACLS',
    'INSTALL_DISABLED_SERVICES',
    'VERIFY_FIXED_BINDING',
    'SET_DELAYED_AUTOMATIC_START',
    'VERIFY_FIXED_BINDING'
)
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    executable = -not $PlanOnly
    installRoot = $resolvedInstall
    dataRoot = $resolvedData
    hostLayoutSha256 = if ([bool]$rootPolicy.isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
    opsControl = [pscustomobject]@{
        serviceId = $opsServiceId
        account = $OpsServiceAccount
        listenAddress = '127.0.0.1'
        listenPort = 18090
        dataRoot = $opsDataRoot
        jarSha256 = $jarSha256
        configSha256 = $configSha256
    }
    releaseAgent = [pscustomobject]@{
        serviceId = $agentServiceId
        account = $ReleaseAgentServiceAccount
        mode = 'ExecuteSignedDeployment'
        dataRoot = $agentDataRoot
        toolkitRoot = $installedToolkitRoot
        toolkitLockSha256 = $toolkitLockSha256
        agentId = $AgentId
        agentVersion = $AgentVersion
    }
    winSWSha256 = $wrapperSha256
    javaSha256 = $javaSha256
    actions = $actions
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') {
        $report | ConvertTo-Json -Depth 7 -Compress
    }
    else { $report | Format-List }
    return
}

if (-not $ConfirmInstallation) {
    throw 'ConfirmInstallation is required before installing the fixed Ops services'
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator privileges are required to install the Ops services'
}

$trustPath = Join-Path $resolvedData 'config\release-trust.json'
$trust = Get-Content -LiteralPath (Get-FixedFile $trustPath 'release trust') `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$trust.backendServiceAccount -cne $BackendServiceAccount -or
        [string]$trust.proxyServiceAccount -cne $ProxyServiceAccount -or
        [string]$trust.opsControlServiceAccount -cne $OpsServiceAccount -or
        [string]$trust.releaseAgentServiceAccount -cne
            $ReleaseAgentServiceAccount) {
    throw 'A service account differs from the host-owned release trust'
}
if (-not ([string]$trust.winSWSha256).Equals(
        $wrapperSha256,
        [StringComparison]::OrdinalIgnoreCase
    ) -or -not ([string]$trust.javaSha256).Equals(
        $javaSha256,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host-owned Java or WinSW digest differs from the approved inputs'
}
$scriptSignerThumbprint = [string]$trust.scriptSignerThumbprint
if ($scriptSignerThumbprint -notmatch '^[a-fA-F0-9]{40,128}$') {
    throw 'Host trust does not pin the starter Authenticode signer'
}
foreach ($starter in @($resolvedOpsStarter, $resolvedAgentStarter)) {
    $signature = Get-AuthenticodeSignature -LiteralPath $starter
    if ($signature.Status -ne 'Valid' -or
            $null -eq $signature.SignerCertificate -or
            -not $signature.SignerCertificate.Thumbprint.Equals(
                $scriptSignerThumbprint,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'An Ops service starter is not signed by the host-owned script signer'
    }
}

$toolkitLock = Get-Content -LiteralPath $resolvedToolkitLock -Encoding utf8 -Raw |
    ConvertFrom-Json
if ([int]$toolkitLock.schemaVersion -ne 1 -or
        [string]$toolkitLock.toolkitId -cne 'leantpm-release-agent-toolkit' -or
        [string]$toolkitLock.executorRelativePath -cne
            'scripts/Invoke-LeanTpmDeployment.ps1' -or
        @($toolkitLock.files).Count -lt 1) {
    throw 'Deployment toolkit lock identity is invalid'
}
$normalizedToolkitPaths = New-Object Collections.Generic.HashSet[string] `
    ([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in @($toolkitLock.files)) {
    $relative = [string]$entry.path
    if ($relative -notmatch '^(?:scripts|deploy/windows)/[A-Za-z0-9._/-]+$' -or
            $relative.Contains('..') -or
            -not $normalizedToolkitPaths.Add($relative) -or
            [string]$entry.sha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'Deployment toolkit lock contains an unsafe or duplicate entry'
    }
    $source = Get-FixedFile -Path (
        Join-Path $resolvedToolkit ($relative.Replace('/', '\'))
    ) -Label "toolkit file $relative"
    if (-not (Get-Sha256 $source).Equals(
            [string]$entry.sha256,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Deployment toolkit file differs from its lock: $relative"
    }
}
foreach ($required in @(
        'deploy/windows/Invoke-LeanTpmReleaseAgent.ps1',
        'scripts/Invoke-LeanTpmDeployment.ps1',
        'scripts/Test-ReleasePackage.ps1',
        'scripts/Test-LeanTpmReleaseApproval.ps1'
    )) {
    if (-not $normalizedToolkitPaths.Contains($required)) {
        throw "Deployment toolkit lock is missing $required"
    }
}

$lockPath = Join-Path $resolvedData 'locks\deployment.lock'
if (-not (Test-Path -LiteralPath (Split-Path -Parent $lockPath) -PathType Container)) {
    throw 'Host mutation lock directory must exist before Ops service installation'
}
$ownedLock = New-Object IO.FileStream(
    $lockPath,
    [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite,
    [IO.FileShare]::Read
)
$createdServiceIds = New-Object Collections.Generic.List[string]
try {
    $lockedPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $resolvedInstall `
        -DataRoot $resolvedData `
        -EnvironmentKind $environmentKind `
        -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
        -OutputFormat Json | ConvertFrom-Json
    $lockedIdentityChanged = [bool]$rootPolicy.isProductionRootPair -and (
        [string]$lockedPolicy.serviceIdentities.opsControl.sid -cne
            [string]$rootPolicy.serviceIdentities.opsControl.sid -or
        [string]$lockedPolicy.serviceIdentities.releaseAgent.sid -cne
            [string]$rootPolicy.serviceIdentities.releaseAgent.sid
    )
    if ([bool]$lockedPolicy.isProductionRootPair -ne
            [bool]$rootPolicy.isProductionRootPair -or
            [string]$lockedPolicy.installRoot -cne [string]$rootPolicy.installRoot -or
            [string]$lockedPolicy.dataRoot -cne [string]$rootPolicy.dataRoot -or
            [string]$lockedPolicy.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256 -or
            $lockedIdentityChanged) {
        throw 'Host layout changed after acquiring the global deployment lock'
    }

    $existing = @($serviceIds | ForEach-Object {
        Get-CimInstance -ClassName Win32_Service -Filter "Name='$_'" `
            -ErrorAction SilentlyContinue
    } | Where-Object { $null -ne $_ })
    if ($existing.Count -gt 0) {
        if ($existing.Count -ne 2) {
            throw 'Only one Ops service exists; refuse to repair a partial registration'
        }
        $binding = & (Join-Path $PSScriptRoot 'Test-LeanTpmOpsServicesBinding.ps1') `
            -InstallRoot $resolvedInstall -DataRoot $resolvedData -OutputFormat Json |
            ConvertFrom-Json
        if ([string]$binding.status -cne 'PASS') {
            throw 'Existing Ops services do not match the fixed binding'
        }
        $report.status = 'ALREADY_INSTALLED'
        if ($OutputFormat -eq 'Json') {
            $report | ConvertTo-Json -Depth 7 -Compress
        }
        else { $report | Format-List }
        return
    }

    if (-not $PSCmdlet.ShouldProcess(
            ($serviceIds -join ', '),
            'Install fixed isolated OpsControl and ReleaseAgent services'
        )) {
        return
    }

    $null = New-Item -ItemType Directory -Path $serviceRoot -Force
    & icacls.exe $serviceRoot '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        "$OpsServiceAccount`:RX" "$ReleaseAgentServiceAccount`:RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the Ops service root ACL' }
    foreach ($directory in @(
            $opsDataRoot,
            (Join-Path $opsDataRoot 'uploads'),
            (Join-Path $opsDataRoot 'approvals'),
            (Join-Path $opsDataRoot 'queue'),
            (Join-Path $opsDataRoot 'state'),
            (Join-Path $opsDataRoot 'audit'),
            (Join-Path $opsDataRoot 'logs'),
            $agentDataRoot,
            (Join-Path $agentDataRoot 'logs'),
            $installedToolkitRoot
        )) {
        $null = New-Item -ItemType Directory -Path $directory -Force
    }

    $targetOpsWrapper = Join-Path $serviceRoot "$opsServiceId.exe"
    $targetAgentWrapper = Join-Path $serviceRoot "$agentServiceId.exe"
    $targetOpsXml = Join-Path $serviceRoot "$opsServiceId.xml"
    $targetAgentXml = Join-Path $serviceRoot "$agentServiceId.xml"
    $targetOpsStarter = Join-Path $serviceRoot 'Start-LeanTpmOpsControl.ps1'
    $targetAgentStarter = Join-Path $serviceRoot 'Start-LeanTpmReleaseAgentService.ps1'
    $targetJar = Join-Path $serviceRoot 'ops-control-plane.jar'
    $targetConfig = Join-Path $serviceRoot 'application-production.yml'
    $policyPath = Join-Path $serviceRoot 'ops-services-binding.json'

    Copy-Item -LiteralPath $resolvedWrapper -Destination $targetOpsWrapper
    Copy-Item -LiteralPath $resolvedWrapper -Destination $targetAgentWrapper
    Copy-Item -LiteralPath $resolvedOpsStarter -Destination $targetOpsStarter
    Copy-Item -LiteralPath $resolvedAgentStarter -Destination $targetAgentStarter
    Copy-Item -LiteralPath $resolvedJar -Destination $targetJar
    Copy-Item -LiteralPath $resolvedConfig -Destination $targetConfig
    foreach ($entry in @($toolkitLock.files)) {
        $relative = [string]$entry.path
        $source = Join-Path $resolvedToolkit ($relative.Replace('/', '\'))
        $destination = Join-Path $installedToolkitRoot ($relative.Replace('/', '\'))
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force
        Copy-Item -LiteralPath $source -Destination $destination
    }
    $installedLock = Join-Path $installedToolkitRoot `
        'release\release-agent-toolkit-lock.json'
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $installedLock) -Force
    Copy-Item -LiteralPath $resolvedToolkitLock -Destination $installedLock
    [IO.File]::WriteAllText(
        $targetOpsXml,
        $templates.ops,
        (New-Object Text.UTF8Encoding($false))
    )
    [IO.File]::WriteAllText(
        $targetAgentXml,
        $templates.agent,
        (New-Object Text.UTF8Encoding($false))
    )

    $policyCore = [ordered]@{
        schemaVersion = 1
        installRoot = $resolvedInstall
        dataRoot = $resolvedData
        opsServiceId = $opsServiceId
        releaseAgentServiceId = $agentServiceId
        opsServiceAccount = $OpsServiceAccount
        releaseAgentServiceAccount = $ReleaseAgentServiceAccount
        backendServiceAccount = $BackendServiceAccount
        proxyServiceAccount = $ProxyServiceAccount
        wrapperSha256 = $wrapperSha256
        javaPath = $resolvedJava
        javaSha256 = $javaSha256
        opsJarSha256 = $jarSha256
        opsConfigSha256 = $configSha256
        opsStarterSha256 = $opsStarterSha256
        releaseAgentStarterSha256 = $agentStarterSha256
        opsXmlSha256 = $opsXmlSha256
        releaseAgentXmlSha256 = $agentXmlSha256
        toolkitLockSha256 = $toolkitLockSha256
        agentId = $AgentId
        agentVersion = $AgentVersion
        opsListenAddress = '127.0.0.1'
        opsListenPort = 18090
        releaseAgentMode = 'ExecuteSignedDeployment'
    }
    $policyCoreJson = $policyCore | ConvertTo-Json -Compress
    $policyHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $policySha256 = [BitConverter]::ToString(
            $policyHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($policyCoreJson))
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $policyHasher.Dispose() }
    $policy = [ordered]@{}
    foreach ($key in $policyCore.Keys) { $policy[$key] = $policyCore[$key] }
    $policy.bindingSha256 = $policySha256
    [IO.File]::WriteAllText(
        $policyPath,
        ($policy | ConvertTo-Json -Compress),
        (New-Object Text.UTF8Encoding($false))
    )

    foreach ($opsFile in @(
            $targetOpsWrapper,
            $targetOpsXml,
            $targetOpsStarter,
            $targetJar,
            $targetConfig
        )) {
        & icacls.exe $opsFile '/inheritance:r' '/grant:r' `
            'Administrators:F' 'SYSTEM:F' "$OpsServiceAccount`:RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to protect Ops asset: $opsFile" }
    }
    foreach ($agentFile in @(
            $targetAgentWrapper,
            $targetAgentXml,
            $targetAgentStarter
        )) {
        & icacls.exe $agentFile '/inheritance:r' '/grant:r' `
            'Administrators:F' 'SYSTEM:F' "$ReleaseAgentServiceAccount`:RX" |
            Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to protect Agent asset: $agentFile" }
    }
    & icacls.exe $policyPath '/inheritance:r' '/grant:r' `
        'Administrators:F' 'SYSTEM:F' "$OpsServiceAccount`:RX" `
        "$ReleaseAgentServiceAccount`:RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the Ops binding policy' }
    & icacls.exe $installedToolkitRoot '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        "$ReleaseAgentServiceAccount`:(OI)(CI)RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the Agent toolkit ACL' }
    & icacls.exe $opsDataRoot '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        "$OpsServiceAccount`:(OI)(CI)M" `
        "$ReleaseAgentServiceAccount`:(OI)(CI)RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the Ops data root ACL' }
    & icacls.exe (Join-Path $opsDataRoot 'queue') '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        "$OpsServiceAccount`:(OI)(CI)M" `
        "$ReleaseAgentServiceAccount`:(OI)(CI)M" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the Agent queue ACL' }
    foreach ($readRoot in @(
            (Join-Path $opsDataRoot 'uploads'),
            (Join-Path $opsDataRoot 'approvals')
        )) {
        & icacls.exe $readRoot '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
            "$OpsServiceAccount`:(OI)(CI)M" `
            "$ReleaseAgentServiceAccount`:(OI)(CI)RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to protect Agent input ACL: $readRoot" }
    }
    & icacls.exe $agentDataRoot '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        "$ReleaseAgentServiceAccount`:(OI)(CI)M" |
        Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the ReleaseAgent data ACL' }
    & icacls.exe $resolvedData '/grant:r' "$OpsServiceAccount`:RX" `
        "$ReleaseAgentServiceAccount`:RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to grant fixed Runtime traversal access' }
    & icacls.exe $trustPath '/grant:r' "$OpsServiceAccount`:RX" `
        "$ReleaseAgentServiceAccount`:RX" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to grant fixed release-trust read access' }
    foreach ($protectedRoot in @(
            $serviceRoot,
            $installedToolkitRoot,
            $opsDataRoot,
            $agentDataRoot
        )) {
        & icacls.exe $protectedRoot '/setowner' 'Administrators' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to set owner: $protectedRoot" }
    }

    foreach ($binding in @(
            @($targetOpsWrapper, $wrapperSha256, 'installed OpsControl wrapper'),
            @($targetAgentWrapper, $wrapperSha256, 'installed ReleaseAgent wrapper'),
            @($targetOpsXml, $opsXmlSha256, 'installed OpsControl WinSW XML'),
            @($targetAgentXml, $agentXmlSha256, 'installed ReleaseAgent WinSW XML'),
            @($targetOpsStarter, $opsStarterSha256, 'installed OpsControl starter'),
            @($targetAgentStarter, $agentStarterSha256,
                'installed ReleaseAgent starter'),
            @($targetJar, $jarSha256, 'installed OpsControl JAR'),
            @($targetConfig, $configSha256, 'installed OpsControl config'),
            @($installedLock, $toolkitLockSha256, 'installed toolkit lock')
        )) {
        if (-not (Get-Sha256 $binding[0]).Equals(
                [string]$binding[1],
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw "$($binding[2]) changed during protected copy"
        }
    }
    foreach ($entry in @($toolkitLock.files)) {
        $relative = [string]$entry.path
        $installedToolkitFile = Join-Path $installedToolkitRoot `
            ($relative.Replace('/', '\'))
        if (-not (Get-Sha256 $installedToolkitFile).Equals(
                [string]$entry.sha256,
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw "Installed toolkit file differs from its lock: $relative"
        }
    }
    foreach ($starter in @($targetOpsStarter, $targetAgentStarter)) {
        $installedSignature = Get-AuthenticodeSignature -LiteralPath $starter
        if ($installedSignature.Status -ne 'Valid' -or
                $null -eq $installedSignature.SignerCertificate -or
                -not $installedSignature.SignerCertificate.Thumbprint.Equals(
                    $scriptSignerThumbprint,
                    [StringComparison]::OrdinalIgnoreCase
                )) {
            throw 'Installed Ops service starter signature changed during copy'
        }
    }

    & $targetOpsWrapper install
    if ($LASTEXITCODE -ne 0) { throw 'LeanTPM.OpsControl WinSW installation failed' }
    $createdServiceIds.Add($opsServiceId)
    & $targetAgentWrapper install
    if ($LASTEXITCODE -ne 0) { throw 'LeanTPM.ReleaseAgent WinSW installation failed' }
    $createdServiceIds.Add($agentServiceId)
    $serviceSddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
    foreach ($serviceId in $serviceIds) {
        & sc.exe sdset $serviceId $serviceSddl | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to protect SCM DACL: $serviceId" }
    }

    $manualBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmOpsServicesBinding.ps1') `
        -InstallRoot $resolvedInstall -DataRoot $resolvedData `
        -ExpectedStartPolicy Manual -OutputFormat Json | ConvertFrom-Json
    if ([string]$manualBinding.status -cne 'PASS') {
        throw 'New Ops services failed the disabled/manual binding verification'
    }
    foreach ($serviceId in $serviceIds) {
        & sc.exe config $serviceId 'start=' 'delayed-auto' | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to set delayed automatic start: $serviceId"
        }
    }
    $finalBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmOpsServicesBinding.ps1') `
        -InstallRoot $resolvedInstall -DataRoot $resolvedData `
        -ExpectedStartPolicy Automatic -OutputFormat Json | ConvertFrom-Json
    if ([string]$finalBinding.status -cne 'PASS') {
        throw 'Ops services failed the final fixed binding verification'
    }
    $report.status = 'INSTALLED'
    $report | Add-Member -NotePropertyName bindingSha256 `
        -NotePropertyValue ([string]$finalBinding.bindingSha256)
}
catch {
    $rollbackServiceIds = @($createdServiceIds)
    [array]::Reverse($rollbackServiceIds)
    foreach ($serviceId in $rollbackServiceIds) {
        $wrapper = Join-Path $serviceRoot "$serviceId.exe"
        if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
            & $wrapper uninstall | Out-Null
        }
    }
    throw
}
finally {
    $ownedLock.Dispose()
}

if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 7 -Compress
}
else { $report | Format-List }
