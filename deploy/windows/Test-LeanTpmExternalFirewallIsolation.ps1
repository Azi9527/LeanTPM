[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PolicyPath,
    [Parameter(Mandatory)][string]$ExpectedPolicySha256,
    [Parameter(Mandatory)][string]$ExpectedInstallRoot,
    [Parameter(Mandatory)][string]$ExpectedDataRoot,
    [Parameter(Mandatory)]
    [ValidateSet('STANDBY_DISABLED', 'ACTIVE')][string]$ExpectedState,
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

$contract = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyContract.ps1') `
    -PolicyPath $PolicyPath -ExpectedPolicySha256 $ExpectedPolicySha256 `
    -ExpectedInstallRoot $ExpectedInstallRoot -ExpectedDataRoot $ExpectedDataRoot `
    -PolicyOnly -OutputFormat Json | ConvertFrom-Json
if ([string]$contract.status -cne 'PASS' -or -not [bool]$contract.policyOnly) {
    throw 'External firewall isolation policy is not host-bound and valid'
}

$rules = @(Get-NetFirewallRule -DisplayGroup ([string]$contract.firewallRuleGroup) `
        -ErrorAction Stop)
if ($rules.Count -ne 2) {
    throw 'Exactly two external ingress firewall rules are required'
}
$evidence = @()
foreach ($rule in $rules) {
    $port = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop
    $address = Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop
    $application = Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $rule `
        -ErrorAction Stop
    $service = Get-NetFirewallServiceFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop
    $interface = Get-NetFirewallInterfaceFilter -AssociatedNetFirewallRule $rule `
        -ErrorAction Stop
    $security = Get-NetFirewallSecurityFilter -AssociatedNetFirewallRule $rule `
        -ErrorAction Stop
    $evidence += [ordered]@{
        name = [string]$rule.Name; enabled = [string]$rule.Enabled
        direction = [string]$rule.Direction; action = [string]$rule.Action
        profile = [string]$rule.Profile; interfaceType = [string]$rule.InterfaceType
        edgeTraversalPolicy = [string]$rule.EdgeTraversalPolicy
        platform = [string]$rule.Platform; localOnlyMapping = [string]$rule.LocalOnlyMapping
        looseSourceMapping = [string]$rule.LooseSourceMapping
        dynamicTarget = [string]$rule.DynamicTarget
        remoteDynamicKeywordAddresses = [string]$rule.RemoteDynamicKeywordAddresses
        policyAppId = [string]$rule.PolicyAppId
        protocol = [string]$port.Protocol; localPort = [string]$port.LocalPort
        remotePort = [string]$port.RemotePort
        localAddress = [string]$address.LocalAddress
        remoteAddress = [string]$address.RemoteAddress
        program = [string]$application.Program; package = [string]$application.Package
        service = [string]$service.Service; interfaceAlias = [string]$interface.InterfaceAlias
        authentication = [string]$security.Authentication
        encryption = [string]$security.Encryption
        overrideBlockRules = [string]$security.OverrideBlockRules
        localUser = [string]$security.LocalUser; remoteUser = [string]$security.RemoteUser
        remoteMachine = [string]$security.RemoteMachine
    }
}
$expectedEnabled = if ($ExpectedState -eq 'ACTIVE') { @('True', '1') } else { @('False', '0') }
$unsafe = @($evidence | Where-Object {
        $_.enabled -notin $expectedEnabled -or $_.direction -ne 'Inbound' -or
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
        $_.program -ne 'Any' -or $_.package -ne 'Any' -or $_.service -ne 'Any' -or
        $_.interfaceAlias -ne 'Any' -or
        $_.authentication -notin @('', 'None', 'NotRequired') -or
        $_.encryption -notin @('', 'None', 'NotRequired') -or
        $_.overrideBlockRules -notin @('', 'False', '0') -or
        $_.localUser -notin @('', 'Any') -or $_.remoteUser -notin @('', 'Any') -or
        $_.remoteMachine -notin @('', 'Any')
    })
$staticEvidence = @($evidence | Sort-Object name | ForEach-Object {
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
$firewallPolicySha256 = Get-TextSha256 ($staticEvidence | ConvertTo-Json -Compress)
$ports = @($evidence | ForEach-Object { [string]$_.localPort })
if ($unsafe.Count -ne 0 -or
        $firewallPolicySha256 -cne [string]$contract.firewallPolicySha256 -or
        @($ports | Where-Object { $_ -eq '80' }).Count -ne 1 -or
        @($ports | Where-Object { $_ -eq '443' }).Count -ne 1) {
    throw 'External ingress firewall rules are scoped, drifted or in the wrong state'
}

$report = [pscustomobject]@{
    status = 'PASS'
    firewallState = $ExpectedState
    firewallRuleGroup = [string]$contract.firewallRuleGroup
    firewallPolicySha256 = $firewallPolicySha256
    ports = @(80, 443)
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
