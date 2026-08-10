[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WrapperPath,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$JavaExecutable,
    [Parameter(Mandatory)][string]$OpsControlPlaneJarPath,
    [Parameter(Mandatory)][string]$OpsControlPlaneConfigPath,
    [Parameter(Mandatory)][string]$SignedOpsStarterPath,
    [Parameter(Mandatory)][string]$SignedReleaseAgentStarterPath,
    [Parameter(Mandatory)][string]$DeploymentToolkitRoot,
    [Parameter(Mandatory)][string]$DeploymentToolkitLockPath,
    [Parameter(Mandatory)][string]$OpsServiceAccount,
    [Parameter(Mandatory)][string]$ReleaseAgentServiceAccount,
    [Parameter(Mandatory)][string]$BackendServiceAccount,
    [Parameter(Mandatory)][string]$ProxyServiceAccount,
    [ValidateSet('GMSA', 'WORKGROUP_VIRTUAL')]
    [string]$ServiceAccountMode = 'GMSA',
    [Parameter(Mandatory)][string]$AgentId,
    [Parameter(Mandatory)][string]$AgentVersion,
    [switch]$AllowNonProductionRoots,
    [switch]$AllowUnverifiedTestHostState,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$blockers = New-Object 'System.Collections.Generic.List[object]'
$gmsaPattern = '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$'
$ServiceAccountMode = $ServiceAccountMode.ToUpperInvariant()
$requiredToolkitPaths = @(
    'deploy/windows/Invoke-LeanTpmReleaseAgent.ps1',
    'release/deployment-bundle.schema.json',
    'release/toolchain-lock.json',
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Test-LeanTpmReleaseApproval.ps1',
    'scripts/Test-ReleasePackage.ps1'
)

function Add-ReadinessBlocker {
    param(
        [Parameter(Mandatory)][string]$Code,
        [Parameter(Mandatory)][string]$Message
    )

    if (@($script:blockers | Where-Object { $_.code -ceq $Code }).Count -eq 0) {
        $script:blockers.Add([pscustomobject]@{
                code = $Code
                message = $Message
            })
    }
}

function Resolve-ReadinessFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$BlockerCode
    )

    try {
        if (-not [IO.Path]::IsPathRooted($Path)) {
            throw 'path is not absolute'
        }
        $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) `
            -Force -ErrorAction Stop
        if ($item.PSIsContainer -or
                (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            throw 'path is not a regular non-reparse file'
        }
        return $item.FullName
    }
    catch {
        Add-ReadinessBlocker -Code $BlockerCode `
            -Message "$Label is not a fixed readable file"
        return $null
    }
}

function Resolve-ReadinessDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$BlockerCode
    )

    try {
        if (-not [IO.Path]::IsPathRooted($Path)) {
            throw 'path is not absolute'
        }
        $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) `
            -Force -ErrorAction Stop
        if (-not $item.PSIsContainer -or
                (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            throw 'path is not a regular non-reparse directory'
        }
        return $item.FullName.TrimEnd('\', '/')
    }
    catch {
        Add-ReadinessBlocker -Code $BlockerCode `
            -Message "$Label is not a fixed readable directory"
        return $null
    }
}

function Get-ReadinessSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.ToLowerInvariant()
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) {
        throw "$Label property count is invalid"
    }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Test-ToolkitLock {
    param(
        [Parameter(Mandatory)][string]$ToolkitRoot,
        [Parameter(Mandatory)][string]$LockPath
    )

    try {
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $bytes = [IO.File]::ReadAllBytes($LockPath)
        $json = $strictUtf8.GetString($bytes)
        $lock = $json | ConvertFrom-Json
        Assert-ExactProperties -Value $lock -Expected @(
            'executorRelativePath', 'files', 'schemaVersion', 'toolkitId'
        ) -Label 'toolkit lock'
        if ([int]$lock.schemaVersion -ne 1 -or
                [string]$lock.toolkitId -cne 'leantpm-release-agent-toolkit' -or
                [string]$lock.executorRelativePath -cne
                    'scripts/Invoke-LeanTpmDeployment.ps1' -or
                @($lock.files).Count -lt 1 -or @($lock.files).Count -gt 256) {
            throw 'toolkit lock identity is invalid'
        }

        $propertyTokens = @([regex]::Matches(
                $json,
                '"(?<name>[A-Za-z][A-Za-z0-9]*)"\s*:'
            ))
        $allowedPropertyNames = @(
            'executorRelativePath', 'files', 'schemaVersion', 'toolkitId',
            'path', 'sha256'
        )
        foreach ($token in $propertyTokens) {
            if ($token.Groups['name'].Value -cnotin $allowedPropertyNames) {
                throw 'toolkit lock contains an unknown property'
            }
        }
        foreach ($singleProperty in @(
                'executorRelativePath', 'files', 'schemaVersion', 'toolkitId'
            )) {
            if (@($propertyTokens | Where-Object {
                        $_.Groups['name'].Value -ceq $singleProperty
                    }).Count -ne 1) {
                throw "toolkit lock property $singleProperty is duplicated"
            }
        }
        if (@($propertyTokens | Where-Object {
                    $_.Groups['name'].Value -ceq 'path'
                }).Count -ne @($lock.files).Count -or
                @($propertyTokens | Where-Object {
                    $_.Groups['name'].Value -ceq 'sha256'
                }).Count -ne @($lock.files).Count) {
            throw 'toolkit lock file properties are duplicated or incomplete'
        }

        $toolkitPrefix = $ToolkitRoot + [IO.Path]::DirectorySeparatorChar
        $seen = New-Object 'System.Collections.Generic.HashSet[string]' `
            ([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in @($lock.files)) {
            Assert-ExactProperties -Value $entry -Expected @('path', 'sha256') `
                -Label 'toolkit file entry'
            $relative = [string]$entry.path
            if ($relative -notmatch '^(?:(?:scripts|deploy/windows)/[A-Za-z0-9._/-]+\.ps1|release/(?:deployment-bundle\.schema|toolchain-lock)\.json)$' -or
                    $relative.Contains('..') -or -not $seen.Add($relative) -or
                    [string]$entry.sha256 -notmatch '^[a-f0-9]{64}$') {
                throw 'toolkit lock contains an unsafe or duplicate file entry'
            }
            $candidate = [IO.Path]::GetFullPath((
                    Join-Path $ToolkitRoot ($relative.Replace('/', '\'))
                ))
            if (-not $candidate.StartsWith(
                    $toolkitPrefix,
                    [StringComparison]::OrdinalIgnoreCase
                )) {
                throw 'toolkit file escaped the toolkit root'
            }
            $fixed = Resolve-ReadinessFile -Path $candidate `
                -Label "toolkit file $relative" `
                -BlockerCode 'TOOLKIT_FILE_INVALID'
            if ($null -eq $fixed) { continue }
            if (-not (Get-ReadinessSha256 $fixed).Equals(
                    [string]$entry.sha256,
                    [StringComparison]::OrdinalIgnoreCase
                )) {
                Add-ReadinessBlocker -Code 'TOOLKIT_FILE_HASH_MISMATCH' `
                    -Message "Toolkit file differs from its lock: $relative"
            }
        }
        foreach ($required in $script:requiredToolkitPaths) {
            if (-not $seen.Contains($required)) {
                Add-ReadinessBlocker -Code 'TOOLKIT_REQUIRED_FILE_MISSING' `
                    -Message "Toolkit lock does not bind required file $required"
            }
        }
    }
    catch {
        Add-ReadinessBlocker -Code 'TOOLKIT_LOCK_INVALID' `
            -Message 'Deployment toolkit lock is not canonical and complete'
    }
}

if ($AllowUnverifiedTestHostState -and -not $AllowNonProductionRoots) {
    Add-ReadinessBlocker -Code 'TEST_HOST_STATE_BYPASS_FORBIDDEN' `
        -Message 'Unverified host-state bypass is restricted to non-production roots'
}

$accountValues = @(
    $OpsServiceAccount,
    $ReleaseAgentServiceAccount,
    $BackendServiceAccount,
    $ProxyServiceAccount
)
if ($ServiceAccountMode -ceq 'GMSA') {
    if (@($accountValues | Where-Object { $_ -notmatch $gmsaPattern }).Count -gt 0) {
        Add-ReadinessBlocker -Code 'SERVICE_ACCOUNT_SHAPE_INVALID' `
            -Message 'GMSA mode requires four domain gMSA-shaped accounts'
    }
}
else {
    $workgroupExpectedAccounts = @(
        'NT SERVICE\LeanTPM.OpsControl',
        'NT SERVICE\LeanTPM.ReleaseAgent',
        'NT AUTHORITY\NetworkService',
        'LocalSystem'
    )
    for ($accountIndex = 0; $accountIndex -lt $accountValues.Count; $accountIndex++) {
        if ([string]$accountValues[$accountIndex] -cne
                [string]$workgroupExpectedAccounts[$accountIndex]) {
            Add-ReadinessBlocker `
                -Code 'WORKGROUP_SERVICE_ACCOUNT_CONTRACT_INVALID' `
                -Message 'WORKGROUP_VIRTUAL requires the four fixed service identities'
            break
        }
    }
}
$normalizedAccounts = @($accountValues | ForEach-Object { $_.ToUpperInvariant() } |
    Select-Object -Unique)
if ($normalizedAccounts.Count -ne 4) {
    Add-ReadinessBlocker -Code 'SERVICE_ACCOUNTS_NOT_DISTINCT' `
        -Message 'Ops, ReleaseAgent, Backend and Proxy accounts must be distinct'
}
if ($AgentId -notmatch '^[a-z0-9][a-z0-9._-]{2,63}$') {
    Add-ReadinessBlocker -Code 'AGENT_ID_INVALID' `
        -Message 'AgentId is not canonical'
}
if ($AgentVersion -notmatch
        '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
    Add-ReadinessBlocker -Code 'AGENT_VERSION_INVALID' `
        -Message 'AgentVersion is not canonical'
}

$resolvedInstall = Resolve-ReadinessDirectory -Path $InstallRoot `
    -Label 'InstallRoot' -BlockerCode 'INSTALL_ROOT_INVALID'
$resolvedData = Resolve-ReadinessDirectory -Path $DataRoot `
    -Label 'DataRoot' -BlockerCode 'DATA_ROOT_INVALID'
$resolvedWrapper = Resolve-ReadinessFile -Path $WrapperPath `
    -Label 'WinSW wrapper' -BlockerCode 'WINSW_INVALID'
$resolvedJava = Resolve-ReadinessFile -Path $JavaExecutable `
    -Label 'Java executable' -BlockerCode 'JAVA_INVALID'
$resolvedJar = Resolve-ReadinessFile -Path $OpsControlPlaneJarPath `
    -Label 'OpsControl JAR' -BlockerCode 'OPS_JAR_INVALID'
$resolvedConfig = Resolve-ReadinessFile -Path $OpsControlPlaneConfigPath `
    -Label 'OpsControl config' -BlockerCode 'OPS_CONFIG_INVALID'
$resolvedOpsStarter = Resolve-ReadinessFile -Path $SignedOpsStarterPath `
    -Label 'OpsControl starter' -BlockerCode 'OPS_STARTER_INVALID'
$resolvedAgentStarter = Resolve-ReadinessFile -Path $SignedReleaseAgentStarterPath `
    -Label 'ReleaseAgent starter' -BlockerCode 'AGENT_STARTER_INVALID'
$resolvedToolkit = Resolve-ReadinessDirectory -Path $DeploymentToolkitRoot `
    -Label 'Deployment toolkit root' -BlockerCode 'TOOLKIT_ROOT_INVALID'
$resolvedLock = Resolve-ReadinessFile -Path $DeploymentToolkitLockPath `
    -Label 'Deployment toolkit lock' -BlockerCode 'TOOLKIT_LOCK_INVALID'

$pins = [ordered]@{
    winSWSha256 = if ($null -ne $resolvedWrapper) {
        Get-ReadinessSha256 $resolvedWrapper
    }
    else { $null }
    javaSha256 = if ($null -ne $resolvedJava) {
        Get-ReadinessSha256 $resolvedJava
    }
    else { $null }
    opsJarSha256 = if ($null -ne $resolvedJar) {
        Get-ReadinessSha256 $resolvedJar
    }
    else { $null }
    opsConfigSha256 = if ($null -ne $resolvedConfig) {
        Get-ReadinessSha256 $resolvedConfig
    }
    else { $null }
    opsStarterSha256 = if ($null -ne $resolvedOpsStarter) {
        Get-ReadinessSha256 $resolvedOpsStarter
    }
    else { $null }
    releaseAgentStarterSha256 = if ($null -ne $resolvedAgentStarter) {
        Get-ReadinessSha256 $resolvedAgentStarter
    }
    else { $null }
    toolkitLockSha256 = if ($null -ne $resolvedLock) {
        Get-ReadinessSha256 $resolvedLock
    }
    else { $null }
}

if ($null -ne $resolvedToolkit -and $null -ne $resolvedLock) {
    $toolkitPrefix = $resolvedToolkit + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedLock.StartsWith(
            $toolkitPrefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        Add-ReadinessBlocker -Code 'TOOLKIT_LOCK_OUTSIDE_ROOT' `
            -Message 'Toolkit lock must be contained by the toolkit root'
    }
    else {
        Test-ToolkitLock -ToolkitRoot $resolvedToolkit -LockPath $resolvedLock
    }
}

if (-not $AllowNonProductionRoots -and
        $null -ne $resolvedOpsStarter -and $null -ne $resolvedAgentStarter -and
        $null -ne $resolvedData) {
    try {
        $trustPath = Resolve-ReadinessFile `
            -Path (Join-Path $resolvedData 'config\release-trust.json') `
            -Label 'release trust' -BlockerCode 'RELEASE_TRUST_INVALID'
        if ($null -ne $trustPath) {
            $trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw |
                ConvertFrom-Json
            $expectedSigner = [string]$trust.scriptSignerThumbprint
            if ($expectedSigner -notmatch '^[a-fA-F0-9]{40,128}$') {
                throw 'release trust signer is invalid'
            }
            foreach ($starter in @($resolvedOpsStarter, $resolvedAgentStarter)) {
                $signature = Get-AuthenticodeSignature -LiteralPath $starter
                if ($signature.Status -ne 'Valid' -or
                        $null -eq $signature.SignerCertificate -or
                        -not $signature.SignerCertificate.Thumbprint.Equals(
                            $expectedSigner,
                            [StringComparison]::OrdinalIgnoreCase
                        )) {
                    throw 'starter signer differs from release trust'
                }
            }
        }
    }
    catch {
        Add-ReadinessBlocker -Code 'STARTER_SIGNATURE_NOT_VERIFIED' `
            -Message 'Both starters must have the host-pinned Authenticode signer'
    }

    if ($ServiceAccountMode -ceq 'GMSA') {
        $testAdServiceAccount = Get-Command Test-ADServiceAccount `
            -ErrorAction SilentlyContinue
        if ($null -eq $testAdServiceAccount) {
            Add-ReadinessBlocker -Code 'GMSA_VALIDATION_UNAVAILABLE' `
                -Message 'Test-ADServiceAccount is unavailable on this host'
        }
        elseif (@($accountValues | Where-Object { $_ -notmatch $gmsaPattern }).Count -eq 0) {
            foreach ($account in @($OpsServiceAccount, $ReleaseAgentServiceAccount)) {
                try {
                    $sid = (New-Object Security.Principal.NTAccount $account).
                        Translate([Security.Principal.SecurityIdentifier]).Value
                    if ($sid -notmatch '^S-1-5-21-') {
                        throw 'gMSA SID is not domain-scoped'
                    }
                    $samAccountName = ($account -split '\\', 2)[1].TrimEnd('$')
                    if (-not (Test-ADServiceAccount -Identity $samAccountName)) {
                        throw 'gMSA is not installed for the local machine'
                    }
                }
                catch {
                    Add-ReadinessBlocker -Code 'GMSA_NOT_READY' `
                        -Message 'OpsControl and ReleaseAgent gMSAs must resolve and pass Test-ADServiceAccount'
                }
            }
        }
    }
    else {
        try {
            $computerSystem = Get-CimInstance -ClassName Win32_ComputerSystem `
                -ErrorAction Stop
            if ([bool]$computerSystem.PartOfDomain) {
                throw 'host is domain joined'
            }
            foreach ($serviceId in @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')) {
                $sidText = (& sc.exe showsid $serviceId 2>&1 | Out-String)
                if ($LASTEXITCODE -ne 0 -or
                        $sidText -notmatch 'S-1-5-80-(?:[0-9]+-){4}[0-9]+') {
                    throw "unable to derive virtual service SID for $serviceId"
                }
            }
        }
        catch {
            Add-ReadinessBlocker -Code 'WORKGROUP_HOST_IDENTITY_NOT_READY' `
                -Message 'WORKGROUP_VIRTUAL requires a non-domain host and resolvable fixed service SIDs'
        }
    }
}

if (-not $AllowUnverifiedTestHostState) {
    try {
        foreach ($serviceId in @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')) {
            $existing = @(Get-CimInstance -ClassName Win32_Service `
                    -Filter "Name='$serviceId'" -ErrorAction Stop)
            if ($existing.Count -gt 0) {
                Add-ReadinessBlocker -Code 'OPS_SERVICE_ALREADY_REGISTERED' `
                    -Message 'A fixed Ops service is already registered; use the binding/status path'
            }
        }
    }
    catch {
        Add-ReadinessBlocker -Code 'SCM_QUERY_FAILED' `
            -Message 'Unable to prove the fixed Ops service IDs are absent'
    }
}

$plan = $null
$installerParameters = $null
if ($blockers.Count -eq 0) {
    $installerParameters = [ordered]@{
        WrapperPath = $resolvedWrapper
        ExpectedWrapperSha256 = $pins.winSWSha256
        InstallRoot = $resolvedInstall
        DataRoot = $resolvedData
        JavaExecutable = $resolvedJava
        ExpectedJavaSha256 = $pins.javaSha256
        OpsControlPlaneJarPath = $resolvedJar
        ExpectedOpsControlPlaneJarSha256 = $pins.opsJarSha256
        OpsControlPlaneConfigPath = $resolvedConfig
        ExpectedOpsControlPlaneConfigSha256 = $pins.opsConfigSha256
        SignedOpsStarterPath = $resolvedOpsStarter
        SignedReleaseAgentStarterPath = $resolvedAgentStarter
        DeploymentToolkitRoot = $resolvedToolkit
        DeploymentToolkitLockPath = $resolvedLock
        ExpectedDeploymentToolkitLockSha256 = $pins.toolkitLockSha256
        OpsServiceAccount = $OpsServiceAccount
        ReleaseAgentServiceAccount = $ReleaseAgentServiceAccount
        BackendServiceAccount = $BackendServiceAccount
        ProxyServiceAccount = $ProxyServiceAccount
        ServiceAccountMode = $ServiceAccountMode
        AgentId = $AgentId
        AgentVersion = $AgentVersion
        PlanOnly = $true
        OutputFormat = 'Json'
    }
    if ($AllowNonProductionRoots) {
        $installerParameters.AllowNonProductionRoots = $true
        $installerParameters.AllowUnpinnedTestInputs = $true
    }
    try {
        $plan = & (Join-Path $PSScriptRoot 'Install-LeanTpmOpsServices.ps1') `
            @installerParameters | ConvertFrom-Json
        if ([string]$plan.status -cne 'PLAN' -or [bool]$plan.executable) {
            throw 'installer did not return a non-executable plan'
        }
    }
    catch {
        Add-ReadinessBlocker -Code 'INSTALLER_PLAN_REJECTED' `
            -Message $_.Exception.Message
        $plan = $null
    }
}

$report = [ordered]@{
    schemaVersion = 1
    status = if ($blockers.Count -eq 0 -and $null -ne $plan) {
        'PLAN_READY'
    }
    else { 'INPUT_REQUIRED' }
    readOnly = $true
    executable = $false
    environmentKind = if ($AllowNonProductionRoots) {
        'NON_PRODUCTION'
    }
    else { 'PRODUCTION' }
    serviceAccountMode = $ServiceAccountMode
    installRoot = $resolvedInstall
    dataRoot = $resolvedData
    serviceIds = @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')
    accounts = [ordered]@{
        opsControl = $OpsServiceAccount
        releaseAgent = $ReleaseAgentServiceAccount
        backend = $BackendServiceAccount
        proxy = $ProxyServiceAccount
    }
    pins = $pins
    blockers = @($blockers | ForEach-Object { $_ })
    installerParameters = $installerParameters
    plan = $plan
}

if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 9 -Compress
}
else {
    [pscustomobject]$report | Format-List
}
