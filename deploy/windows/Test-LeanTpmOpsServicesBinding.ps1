[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [ValidateSet('Manual', 'Automatic')][string]$ExpectedStartPolicy = 'Automatic',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$opsServiceId = 'LeanTPM.OpsControl'
$agentServiceId = 'LeanTPM.ReleaseAgent'
$serviceIds = @($opsServiceId, $agentServiceId)
$install = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\', '/')
$data = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\', '/')
$serviceRoot = Join-Path $install 'ops-services'
$policyPath = Join-Path $serviceRoot 'ops-services-binding.json'
$policyItem = Get-Item -LiteralPath $policyPath -Force
if ($policyItem.PSIsContainer -or
        (($policyItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
    throw 'Ops service binding policy is missing or unsafe'
}
$policy = Get-Content -LiteralPath $policyItem.FullName -Encoding utf8 -Raw |
    ConvertFrom-Json

$requiredProperties = @(
    'schemaVersion', 'installRoot', 'dataRoot', 'opsServiceId',
    'releaseAgentServiceId', 'opsServiceAccount', 'releaseAgentServiceAccount',
    'backendServiceAccount', 'proxyServiceAccount', 'wrapperSha256', 'javaPath',
    'javaSha256', 'opsJarSha256', 'opsConfigSha256', 'opsStarterSha256',
    'releaseAgentStarterSha256', 'opsXmlSha256', 'releaseAgentXmlSha256',
    'toolkitLockSha256', 'agentId', 'agentVersion',
    'opsListenAddress', 'opsListenPort', 'releaseAgentMode', 'bindingSha256'
)
$actualProperties = @($policy.PSObject.Properties.Name | Sort-Object)
$wantedProperties = @($requiredProperties | Sort-Object)
if ($actualProperties.Count -ne $wantedProperties.Count) {
    throw 'Ops service binding policy properties do not match the fixed schema'
}
for ($index = 0; $index -lt $wantedProperties.Count; $index++) {
    if ($actualProperties[$index] -cne $wantedProperties[$index]) {
        throw 'Ops service binding policy properties do not match the fixed schema'
    }
}
if ([int]$policy.schemaVersion -ne 1 -or
        [string]$policy.installRoot -cne $install -or
        [string]$policy.dataRoot -cne $data -or
        [string]$policy.opsServiceId -cne $opsServiceId -or
        [string]$policy.releaseAgentServiceId -cne $agentServiceId -or
        [string]$policy.opsListenAddress -cne '127.0.0.1' -or
        [int]$policy.opsListenPort -ne 18090 -or
        [string]$policy.releaseAgentMode -cne 'ExecuteSignedDeployment') {
    throw 'Ops service binding policy identity or fixed runtime contract drifted'
}
foreach ($account in @(
        [string]$policy.opsServiceAccount,
        [string]$policy.releaseAgentServiceAccount,
        [string]$policy.backendServiceAccount,
        [string]$policy.proxyServiceAccount
    )) {
    if ($account -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$') {
        throw 'Ops service binding policy contains a non-gMSA-shaped account'
    }
}
$distinctAccounts = @(
    @(
        [string]$policy.opsServiceAccount,
        [string]$policy.releaseAgentServiceAccount,
        [string]$policy.backendServiceAccount,
        [string]$policy.proxyServiceAccount
    ) | ForEach-Object { $_.ToUpperInvariant() } | Select-Object -Unique
)
if ($distinctAccounts.Count -ne 4) {
    throw 'Ops, ReleaseAgent, Backend and Proxy accounts are not distinct'
}

$policyCore = [ordered]@{
    schemaVersion = [int]$policy.schemaVersion
    installRoot = [string]$policy.installRoot
    dataRoot = [string]$policy.dataRoot
    opsServiceId = [string]$policy.opsServiceId
    releaseAgentServiceId = [string]$policy.releaseAgentServiceId
    opsServiceAccount = [string]$policy.opsServiceAccount
    releaseAgentServiceAccount = [string]$policy.releaseAgentServiceAccount
    backendServiceAccount = [string]$policy.backendServiceAccount
    proxyServiceAccount = [string]$policy.proxyServiceAccount
    wrapperSha256 = [string]$policy.wrapperSha256
    javaPath = [string]$policy.javaPath
    javaSha256 = [string]$policy.javaSha256
    opsJarSha256 = [string]$policy.opsJarSha256
    opsConfigSha256 = [string]$policy.opsConfigSha256
    opsStarterSha256 = [string]$policy.opsStarterSha256
    releaseAgentStarterSha256 = [string]$policy.releaseAgentStarterSha256
    opsXmlSha256 = [string]$policy.opsXmlSha256
    releaseAgentXmlSha256 = [string]$policy.releaseAgentXmlSha256
    toolkitLockSha256 = [string]$policy.toolkitLockSha256
    agentId = [string]$policy.agentId
    agentVersion = [string]$policy.agentVersion
    opsListenAddress = [string]$policy.opsListenAddress
    opsListenPort = [int]$policy.opsListenPort
    releaseAgentMode = [string]$policy.releaseAgentMode
}
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $actualPolicySha256 = [BitConverter]::ToString(
        $hasher.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes(
                ($policyCore | ConvertTo-Json -Compress)
            )
        )
    ).Replace('-', '').ToLowerInvariant()
}
finally { $hasher.Dispose() }
if ([string]$policy.bindingSha256 -cne $actualPolicySha256) {
    throw 'Ops service binding policy hash is invalid'
}

$files = [ordered]@{
    opsWrapper = Join-Path $serviceRoot "$opsServiceId.exe"
    agentWrapper = Join-Path $serviceRoot "$agentServiceId.exe"
    opsXml = Join-Path $serviceRoot "$opsServiceId.xml"
    agentXml = Join-Path $serviceRoot "$agentServiceId.xml"
    opsStarter = Join-Path $serviceRoot 'Start-LeanTpmOpsControl.ps1'
    agentStarter = Join-Path $serviceRoot 'Start-LeanTpmReleaseAgentService.ps1'
    jar = Join-Path $serviceRoot 'ops-control-plane.jar'
    config = Join-Path $serviceRoot 'application-production.yml'
    toolkitLock = Join-Path $serviceRoot `
        'release-agent-toolkit\release\release-agent-toolkit-lock.json'
    java = [string]$policy.javaPath
}
foreach ($entry in $files.GetEnumerator()) {
    $item = Get-Item -LiteralPath $entry.Value -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "Ops service supply-chain file is missing or unsafe: $($entry.Value)"
    }
}
foreach ($binding in @(
        @($files.opsWrapper, [string]$policy.wrapperSha256, 'OpsControl wrapper'),
        @($files.agentWrapper, [string]$policy.wrapperSha256, 'ReleaseAgent wrapper'),
        @($files.opsXml, [string]$policy.opsXmlSha256, 'OpsControl WinSW XML'),
        @($files.agentXml, [string]$policy.releaseAgentXmlSha256,
            'ReleaseAgent WinSW XML'),
        @($files.java, [string]$policy.javaSha256, 'Java executable'),
        @($files.jar, [string]$policy.opsJarSha256, 'OpsControl JAR'),
        @($files.config, [string]$policy.opsConfigSha256, 'OpsControl config'),
        @($files.opsStarter, [string]$policy.opsStarterSha256, 'OpsControl starter'),
        @($files.agentStarter, [string]$policy.releaseAgentStarterSha256,
            'ReleaseAgent starter'),
        @($files.toolkitLock, [string]$policy.toolkitLockSha256, 'Agent toolkit lock')
    )) {
    if ([string]$binding[1] -notmatch '^[a-f0-9]{64}$' -or
            -not (Get-FileHash -LiteralPath $binding[0] -Algorithm SHA256).Hash.Equals(
                [string]$binding[1],
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw "$($binding[2]) differs from the fixed binding"
    }
}

$trustPath = Join-Path $data 'config\release-trust.json'
$trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$trust.backendServiceAccount -cne
        [string]$policy.backendServiceAccount -or
        [string]$trust.proxyServiceAccount -cne
            [string]$policy.proxyServiceAccount -or
        [string]$trust.opsControlServiceAccount -cne
            [string]$policy.opsServiceAccount -or
        [string]$trust.releaseAgentServiceAccount -cne
            [string]$policy.releaseAgentServiceAccount -or
        -not ([string]$trust.winSWSha256).Equals(
            [string]$policy.wrapperSha256,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        -not ([string]$trust.javaSha256).Equals(
            [string]$policy.javaSha256,
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Host-owned release trust differs from the Ops service binding'
}
$signerThumbprint = [string]$trust.scriptSignerThumbprint
foreach ($starter in @($files.opsStarter, $files.agentStarter)) {
    $signature = Get-AuthenticodeSignature -LiteralPath $starter
    if ($signature.Status -ne 'Valid' -or
            $null -eq $signature.SignerCertificate -or
            -not $signature.SignerCertificate.Thumbprint.Equals(
                $signerThumbprint,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Installed Ops service starter signature is not trusted'
    }
}

$expectedImagePaths = @{
    $opsServiceId = $files.opsWrapper
    $agentServiceId = $files.agentWrapper
}
$expectedAccounts = @{
    $opsServiceId = [string]$policy.opsServiceAccount
    $agentServiceId = [string]$policy.releaseAgentServiceAccount
}
$expectedMode = if ($ExpectedStartPolicy -eq 'Manual') { 'Manual' } else { 'Auto' }
$serviceReports = New-Object Collections.Generic.List[object]
$expectedSddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
foreach ($serviceId in $serviceIds) {
    $service = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='$serviceId'" -ErrorAction Stop
    if ($null -eq $service) { throw "$serviceId is not registered in SCM" }
    $actualImage = ([string]$service.PathName).Trim().Trim('"')
    if (-not $actualImage.Equals(
            $expectedImagePaths[$serviceId],
            [StringComparison]::OrdinalIgnoreCase
        ) -or [string]$service.StartName -cne $expectedAccounts[$serviceId] -or
        [string]$service.StartMode -cne $expectedMode) {
        throw "$serviceId SCM image, account or start mode drifted"
    }
    $sddlText = (& sc.exe sdshow $serviceId 2>&1 | Out-String)
    if (-not $sddlText.Contains($expectedSddl)) {
        throw "$serviceId SCM DACL drifted"
    }
    $serviceReports.Add([pscustomobject]@{
        serviceId = $serviceId
        state = [string]$service.State
        startMode = [string]$service.StartMode
        processId = [int]$service.ProcessId
    })
}

$report = [pscustomobject]@{
    status = 'PASS'
    bindingSha256 = $actualPolicySha256
    startPolicy = $ExpectedStartPolicy.ToUpperInvariant()
    services = @($serviceReports)
    opsListenAddress = '127.0.0.1'
    opsListenPort = 18090
    releaseAgentMode = 'ExecuteSignedDeployment'
}
if ($OutputFormat -eq 'Json') {
    $report | ConvertTo-Json -Depth 5 -Compress
}
else { $report | Format-List }
