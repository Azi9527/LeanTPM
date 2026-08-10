[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$DeploymentLockToken,
    [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$BackendPort,
    [switch]$AllowNonProductionRoot,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
function Get-TextSha256 {
    param([Parameter(Mandatory)][string]$Text)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString(
            $hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
}
$install = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoot) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
    -ContainmentOnly:(-not $AllowNonProductionRoot) `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionRoot) {
    throw 'AllowNonProductionRoot cannot be used with production roots'
}
$externalProxy = $isProductionRootPair -and
    [string]$rootPolicy.proxy.mode -ceq 'EXTERNAL_EXISTING'

$lockPath = Join-Path $data 'locks\deployment.lock'
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf) -or
        [string](Get-Content -LiteralPath $lockPath -Encoding ascii -Raw) -cne
            $DeploymentLockToken) {
    throw 'Fail-closed compensation does not own the global deployment lock'
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator privileges are required for fail-closed service compensation'
}

$backendServiceId = 'LeanTPM.Backend'
$proxyServiceId = if ($isProductionRootPair) {
    [string]$rootPolicy.proxy.serviceId
}
else { 'LeanTPM.Proxy' }
$expectedBackendImage = Join-Path $install 'service\LeanTPM.Backend.exe'
$backend = $null
$backendQueryFailure = $null
try {
    $backend = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
}
catch { $backendQueryFailure = $_ }

$forcedProcessIds = [System.Collections.Generic.List[uint32]]::new()
$terminationFailures = [System.Collections.Generic.List[string]]::new()
if ($null -ne $backend -and [uint32]$backend.ProcessId -ne 0 -and
        ([string]$backend.PathName).Trim().Trim('"').Equals(
            $expectedBackendImage, [StringComparison]::OrdinalIgnoreCase
        )) {
    try {
        $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
        $depthByPid = @{}
        $depthByPid[[uint32]$backend.ProcessId] = 0
        $pending = [System.Collections.Generic.Queue[uint32]]::new()
        $pending.Enqueue([uint32]$backend.ProcessId)
        while ($pending.Count -gt 0) {
            $parent = $pending.Dequeue()
            foreach ($child in @($processes | Where-Object {
                        [uint32]$_.ParentProcessId -eq $parent -and
                        -not $depthByPid.ContainsKey([uint32]$_.ProcessId)
                    })) {
                $childPid = [uint32]$child.ProcessId
                $depthByPid[$childPid] = [int]$depthByPid[$parent] + 1
                $pending.Enqueue($childPid)
            }
        }
        foreach ($pidValue in @($depthByPid.Keys | Sort-Object {
                    -1 * [int]$depthByPid[$_]
                })) {
            $forcedProcessIds.Add([uint32]$pidValue)
            try { Stop-Process -Id ([int]$pidValue) -Force -ErrorAction Stop }
            catch {
                # A PID may disappear naturally; never skip the authoritative state/port check.
                $terminationFailures.Add("BACKEND_PID_$pidValue`: $($_.Exception.Message)")
            }
        }
    }
    catch { $terminationFailures.Add("BACKEND_PROCESS_ENUM: $($_.Exception.Message)") }
}

for ($attempt = 1; $attempt -le 10; $attempt++) {
    try {
        $backend = Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
        $backendListeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { [int]$_.LocalPort -eq $BackendPort })
        if ($null -ne $backend -and [string]$backend.State -ceq 'Stopped' -and
                [uint32]$backend.ProcessId -eq 0 -and $backendListeners.Count -eq 0) {
            $report = [pscustomobject]@{
                status = 'STOPPED'
                severity = 'RECOVERED'
                serviceId = $backendServiceId
                backendPort = $BackendPort
                forcedProcessIds = @($forcedProcessIds)
                terminationFailures = @($terminationFailures)
            }
            if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
            else { $report | Format-List }
            return
        }
    }
    catch { $backendQueryFailure = $_ }
    Start-Sleep -Seconds 1
}

# A running process cannot observe a new recovery marker. Remove and prove the public ingress.
$proxyPublicPorts = @(80, 443)
$expectedCaddyPath = if ($externalProxy) {
    [string]$rootPolicy.proxyBinding.serviceImagePath
}
else { Join-Path $install 'proxy\caddy.exe' }
$proxyOperationFailures = [System.Collections.Generic.List[string]]::new()
$proxy = $null
try {
    $proxy = Get-CimInstance -ClassName Win32_Service -Filter ("Name='{0}'" -f $proxyServiceId) `
        -ErrorAction Stop
}
catch { $proxyOperationFailures.Add("PROXY_QUERY: $($_.Exception.Message)") }
if ($null -ne $proxy) {
    try { Stop-Service -Name $proxyServiceId -Force -ErrorAction Stop }
    catch { $proxyOperationFailures.Add("PROXY_STOP: $($_.Exception.Message)") }
}
for ($attempt = 1; $attempt -le 10; $attempt++) {
    try {
        $proxy = Get-CimInstance -ClassName Win32_Service -Filter ("Name='{0}'" -f $proxyServiceId) `
            -ErrorAction Stop
        $runningProcesses = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
        $caddyProcesses = @($runningProcesses | Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_.ExecutablePath) -and
                ([string]$_.ExecutablePath).Equals(
                    $expectedCaddyPath, [StringComparison]::OrdinalIgnoreCase
                )
            })
        $publicListeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { [int]$_.LocalPort -in $proxyPublicPorts })
        $proxyScmStopped = $null -eq $proxy -or (
            [string]$proxy.State -ceq 'Stopped' -and [uint32]$proxy.ProcessId -eq 0
        )
        if ($proxyScmStopped -and $caddyProcesses.Count -eq 0 -and
                $publicListeners.Count -eq 0) {
            $report = [pscustomobject]@{
                status = 'PROXY_ISOLATED'
                severity = 'CRITICAL'
                isolationMethod = 'SERVICE_STOP'
                serviceId = $backendServiceId
                isolatedServiceId = $proxyServiceId
                backendPort = $BackendPort
                publicPorts = $proxyPublicPorts
                firewallRuleGroup = if ($externalProxy) {
                    [string]$rootPolicy.proxyBinding.firewallRuleGroup
                }
                else { $null }
                proxyBindingSha256 = [string]$rootPolicy.proxyBindingSha256
                firewallPolicySha256 = if ($externalProxy) {
                    [string]$rootPolicy.proxyBinding.firewallPolicySha256
                }
                else { $null }
                forcedProcessIds = @($forcedProcessIds)
                terminationFailures = @($terminationFailures)
                proxyOperationFailures = @($proxyOperationFailures)
            }
            if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
            else { $report | Format-List }
            return
        }
        $proxyProcessIds = [System.Collections.Generic.HashSet[uint32]]::new()
        if ($null -ne $proxy -and [uint32]$proxy.ProcessId -ne 0) {
            $null = $proxyProcessIds.Add([uint32]$proxy.ProcessId)
        }
        foreach ($caddyProcess in $caddyProcesses) {
            $null = $proxyProcessIds.Add([uint32]$caddyProcess.ProcessId)
        }
        foreach ($proxyPid in $proxyProcessIds) {
            try { Stop-Process -Id ([int]$proxyPid) -Force -ErrorAction Stop }
            catch {
                $proxyOperationFailures.Add("PROXY_PID_$proxyPid`: $($_.Exception.Message)")
            }
        }
    }
    catch { $proxyOperationFailures.Add("PROXY_VERIFY: $($_.Exception.Message)") }
    Start-Sleep -Seconds 1
}
$firewallIsolationFailure = $null
if ($externalProxy) {
    try {
        $firewallGroup = [string]$rootPolicy.proxyBinding.firewallRuleGroup
        if ([string]::IsNullOrWhiteSpace($firewallGroup)) {
            throw 'Host proxy binding has no pre-approved firewall isolation group'
        }
        Enable-NetFirewallRule -DisplayGroup $firewallGroup -ErrorAction Stop
        $firewallRules = @(Get-NetFirewallRule -DisplayGroup $firewallGroup -ErrorAction Stop)
        if ($firewallRules.Count -ne 2) {
            throw 'Exactly two public isolation firewall rules must exist after activation'
        }
        $firewallEvidence = @()
        foreach ($rule in $firewallRules) {
            $portFilter = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule `
                -ErrorAction Stop
            $addressFilter = Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $rule `
                -ErrorAction Stop
            $applicationFilter = Get-NetFirewallApplicationFilter `
                -AssociatedNetFirewallRule $rule -ErrorAction Stop
            $serviceFilter = Get-NetFirewallServiceFilter -AssociatedNetFirewallRule $rule `
                -ErrorAction Stop
            $interfaceFilter = Get-NetFirewallInterfaceFilter `
                -AssociatedNetFirewallRule $rule -ErrorAction Stop
            $securityFilter = Get-NetFirewallSecurityFilter `
                -AssociatedNetFirewallRule $rule -ErrorAction Stop
            $firewallEvidence += [ordered]@{
                name = [string]$rule.Name; enabled = [string]$rule.Enabled
                direction = [string]$rule.Direction; action = [string]$rule.Action
                profile = [string]$rule.Profile; interfaceType = [string]$rule.InterfaceType
                edgeTraversalPolicy = [string]$rule.EdgeTraversalPolicy
                platform = [string]$rule.Platform
                localOnlyMapping = [string]$rule.LocalOnlyMapping
                looseSourceMapping = [string]$rule.LooseSourceMapping
                dynamicTarget = [string]$rule.DynamicTarget
                remoteDynamicKeywordAddresses = [string]$rule.RemoteDynamicKeywordAddresses
                policyAppId = [string]$rule.PolicyAppId
                protocol = [string]$portFilter.Protocol
                localPort = [string]$portFilter.LocalPort
                remotePort = [string]$portFilter.RemotePort
                localAddress = [string]$addressFilter.LocalAddress
                remoteAddress = [string]$addressFilter.RemoteAddress
                program = [string]$applicationFilter.Program
                package = [string]$applicationFilter.Package
                service = [string]$serviceFilter.Service
                interfaceAlias = [string]$interfaceFilter.InterfaceAlias
                authentication = [string]$securityFilter.Authentication
                encryption = [string]$securityFilter.Encryption
                overrideBlockRules = [string]$securityFilter.OverrideBlockRules
                localUser = [string]$securityFilter.LocalUser
                remoteUser = [string]$securityFilter.RemoteUser
                remoteMachine = [string]$securityFilter.RemoteMachine
            }
        }
        $unsafeRules = @($firewallEvidence | Where-Object {
                $_.enabled -notin @('True', '1') -or $_.direction -ne 'Inbound' -or
                $_.action -ne 'Block' -or $_.profile -ne 'Any' -or
                $_.interfaceType -ne 'Any' -or $_.edgeTraversalPolicy -ne 'Block' -or
                $_.platform -notin @('', 'Any') -or
                $_.localOnlyMapping -notin @('', 'False', '0') -or
                $_.looseSourceMapping -notin @('', 'False', '0') -or
                $_.dynamicTarget -notin @('', 'Any') -or
                $_.remoteDynamicKeywordAddresses -notin @('', 'Any') -or
                $_.policyAppId -notin @('', 'Any') -or
                $_.protocol -notin @('TCP', '6') -or $_.remotePort -ne 'Any' -or
                $_.localAddress -ne 'Any' -or $_.remoteAddress -ne 'Any' -or
                $_.program -ne 'Any' -or $_.package -ne 'Any' -or
                $_.service -ne 'Any' -or $_.interfaceAlias -ne 'Any'
                -or $_.authentication -notin @('', 'None', 'NotRequired')
                -or $_.encryption -notin @('', 'None', 'NotRequired')
                -or $_.overrideBlockRules -notin @('', 'False', '0')
                -or $_.localUser -notin @('', 'Any') -or $_.remoteUser -notin @('', 'Any')
                -or $_.remoteMachine -notin @('', 'Any')
            })
        $staticEvidence = @($firewallEvidence | Sort-Object name | ForEach-Object {
                [ordered]@{
                    name = $_.name; direction = $_.direction; action = $_.action
                    profile = $_.profile; interfaceType = $_.interfaceType
                    edgeTraversalPolicy = $_.edgeTraversalPolicy; protocol = $_.protocol
                    platform = $_.platform; localOnlyMapping = $_.localOnlyMapping
                    looseSourceMapping = $_.looseSourceMapping; dynamicTarget = $_.dynamicTarget
                    remoteDynamicKeywordAddresses = $_.remoteDynamicKeywordAddresses
                    policyAppId = $_.policyAppId
                    localPort = $_.localPort; remotePort = $_.remotePort
                    localAddress = $_.localAddress; remoteAddress = $_.remoteAddress
                    program = $_.program; package = $_.package; service = $_.service
                    interfaceAlias = $_.interfaceAlias
                    authentication = $_.authentication; encryption = $_.encryption
                    overrideBlockRules = $_.overrideBlockRules; localUser = $_.localUser
                    remoteUser = $_.remoteUser; remoteMachine = $_.remoteMachine
                }
            })
        $firewallPolicySha256 = Get-TextSha256 (
            $staticEvidence | ConvertTo-Json -Compress
        )
        $blockedPorts = @($firewallEvidence | ForEach-Object { [string]$_.localPort })
        if ($unsafeRules.Count -ne 0 -or
                $firewallPolicySha256 -cne
                    [string]$rootPolicy.proxyBinding.firewallPolicySha256 -or
                @($blockedPorts | Where-Object { $_ -eq '80' }).Count -ne 1 -or
                @($blockedPorts | Where-Object { $_ -eq '443' }).Count -ne 1) {
            throw 'Public isolation firewall policy is scoped, drifted or incomplete'
        }
        $report = [pscustomobject]@{
            status = 'PROXY_ISOLATED'
            severity = 'CRITICAL'
            isolationMethod = 'HOST_FIREWALL'
            serviceId = $backendServiceId
            isolatedServiceId = $proxyServiceId
            backendPort = $BackendPort
            publicPorts = $proxyPublicPorts
            firewallRuleGroup = $firewallGroup
            proxyBindingSha256 = [string]$rootPolicy.proxyBindingSha256
            firewallPolicySha256 = $firewallPolicySha256
            forcedProcessIds = @($forcedProcessIds)
            terminationFailures = @($terminationFailures)
            proxyOperationFailures = @($proxyOperationFailures)
        }
        if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
        else { $report | Format-List }
        return
    }
    catch { $firewallIsolationFailure = $_.Exception.Message }
}
$failureDetails = @($terminationFailures) + @($proxyOperationFailures)
if (-not [string]::IsNullOrWhiteSpace($firewallIsolationFailure)) {
    $failureDetails += "FIREWALL_ISOLATION: $firewallIsolationFailure"
}
throw ("CRITICAL_FAIL_CLOSED_UNPROVEN: proxy SCM, caddy.exe and public ports " +
    "could not be independently confirmed isolated: $($failureDetails -join '; ')")
