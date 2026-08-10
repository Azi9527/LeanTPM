[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)]
    [ValidateSet('NON_PRODUCTION', 'PRODUCTION')][string]$EnvironmentKind,
    [Parameter(Mandatory)][string]$RecoveryMarkerPath,
    [Parameter(Mandatory)][string]$ExpectedRecoveryStateSha256,
    [Parameter(Mandatory)][string]$ExpectedProxyBindingSha256,
    [string]$DeploymentLockToken = '',
    [switch]$PlanOnly,
    [switch]$AllowNonProductionRoot,
    [switch]$ConfirmIngressRecovery,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

function Read-JsonSnapshot {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
            ((Get-Item -LiteralPath $resolved -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Recovery state must be a regular non-reparse file: $Path"
    }
    $stream = [IO.File]::Open(
        $resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read
    )
    try {
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    }
    finally { $stream.Dispose() }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $sha256 = [BitConverter]::ToString($hasher.ComputeHash($bytes)).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    return [pscustomobject]@{
        path = $resolved
        sha256 = $sha256
        value = $utf8.GetString($bytes) | ConvertFrom-Json
    }
}

$install = (Resolve-Path -LiteralPath $InstallRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$expectedMarker = [IO.Path]::GetFullPath(
    (Join-Path $data 'state\recovery-inhibit.json')
)
$resolvedMarker = [IO.Path]::GetFullPath($RecoveryMarkerPath)
if (-not $resolvedMarker.Equals($expectedMarker, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'RecoveryMarkerPath must be the host-owned DataRoot recovery marker'
}
if ($ExpectedRecoveryStateSha256 -notmatch '^[a-f0-9]{64}$' -or
        $ExpectedProxyBindingSha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'Recovery state and proxy binding digests must be lowercase SHA-256 values'
}

$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $EnvironmentKind `
    -PlanOnly:$PlanOnly -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
    -ContainmentOnly -OutputFormat Json | ConvertFrom-Json
if (-not [bool]$rootPolicy.isProductionRootPair -or
        [string]$rootPolicy.proxy.mode -cne 'EXTERNAL_EXISTING' -or
        [string]$rootPolicy.proxyBindingSha256 -cne $ExpectedProxyBindingSha256) {
    throw 'Ingress recovery requires the exact host-owned EXTERNAL_EXISTING proxy contract'
}

$markerSnapshot = Read-JsonSnapshot $resolvedMarker
if ([string]$markerSnapshot.sha256 -cne $ExpectedRecoveryStateSha256) {
    throw 'Recovery state bytes changed before ingress recovery'
}
$marker = $markerSnapshot.value
$isolationMethod = [string]$marker.isolationMethod
if ([int]$marker.schemaVersion -ne 1 -or
        [string]$marker.status -notin @('ACTIVATION_AUTHORIZED', 'RECOVERY_REQUIRED') -or
        $isolationMethod -notin @('SERVICE_STOP', 'HOST_FIREWALL') -or
        [string]$marker.proxyBindingSha256 -cne $ExpectedProxyBindingSha256 -or
        [string]$marker.firewallPolicySha256 -cne
            [string]$rootPolicy.proxyBinding.firewallPolicySha256 -or
        [string]$marker.isolatedServiceId -cne [string]$rootPolicy.proxy.serviceId) {
    throw 'Recovery marker does not authorize the exact isolated external ingress'
}

$serviceId = [string]$rootPolicy.proxy.serviceId
$firewallRuleGroup = [string]$marker.firewallRuleGroup
if ([string]::IsNullOrWhiteSpace($firewallRuleGroup) -or
        [string]$firewallRuleGroup -cne
            [string]$rootPolicy.proxyBinding.firewallRuleGroup) {
    throw 'Recovery marker is not bound to the approved firewall guard'
}

$steps = [System.Collections.Generic.List[string]]::new()
$steps.Add('ENABLE_AND_VERIFY_EXACT_FIREWALL_GUARD')
$steps.Add('STOP_AND_PROVE_EXTERNAL_CADDY_QUIESCENT')
$steps.Add('VERIFY_MUTABLE_RUNTIME_TREE_WHILE_QUIESCENT')
$steps.Add('START_EXACT_EXTERNAL_CADDY')
$steps.Add('VERIFY_EXTERNAL_CADDY_BEHIND_GUARD')
$steps.Add('DISABLE_EXACT_FIREWALL_GUARD')
$steps.Add('VERIFY_EXTERNAL_CADDY_BINDING')
$steps.Add('VERIFY_PUBLIC_HTTPS')
$report = [ordered]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    isolationMethod = $isolationMethod
    serviceId = $serviceId
    isolatedServiceId = $serviceId
    firewallRuleGroup = $firewallRuleGroup
    firewallPolicySha256 = [string]$rootPolicy.proxyBinding.firewallPolicySha256
    proxyBindingSha256 = $ExpectedProxyBindingSha256
    recoveryStateSha256 = $ExpectedRecoveryStateSha256
    steps = @($steps)
    runtimeTreeQuiescenceVerified = $false
    publicHttpsVerified = $false
}
if ($PlanOnly) {
    $output = [pscustomobject]$report
    if ($OutputFormat -eq 'Json') { $output | ConvertTo-Json -Depth 5 -Compress }
    else { $output | Format-List }
    return
}
if (-not $ConfirmIngressRecovery) {
    throw 'ConfirmIngressRecovery is required for executable ingress recovery'
}
if ($DeploymentLockToken -notmatch '^[a-f0-9]{64}$') {
    throw 'Executable ingress recovery requires the exact deployment lock token'
}
$lockPath = Join-Path $data 'locks\deployment.lock'
$lockTokenOnDisk = [IO.File]::ReadAllText($lockPath, [Text.Encoding]::ASCII).Trim()
if ($lockTokenOnDisk -cne $DeploymentLockToken) {
    throw 'Ingress recovery does not own the global deployment lock'
}
if (-not $PSCmdlet.ShouldProcess($serviceId, 'Restore exact external ingress')) { return }

function Assert-FirewallState {
    param([Parameter(Mandatory)][ValidateSet('STANDBY_DISABLED', 'ACTIVE')][string]$State)
    $verified = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalFirewallIsolation.ps1') `
        -PolicyPath ([string]$rootPolicy.proxy.bindingPolicyPath) `
        -ExpectedPolicySha256 ([string]$rootPolicy.proxy.bindingPolicySha256) `
        -ExpectedInstallRoot $install -ExpectedDataRoot $data `
        -ExpectedState $State -OutputFormat Json | ConvertFrom-Json
    if ([string]$verified.status -cne 'PASS' -or
            [string]$verified.firewallState -cne $State -or
            [string]$verified.firewallPolicySha256 -cne
                [string]$rootPolicy.proxyBinding.firewallPolicySha256) {
        throw "External ingress firewall did not reach the exact $State state"
    }
}

function Test-ProxyServiceAndListenersStopped {
    $serviceBinding = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='$serviceId'" -ErrorAction Stop
    $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop |
        Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_.ExecutablePath) -and
            ([string]$_.ExecutablePath).Equals(
                [string]$rootPolicy.proxyBinding.serviceImagePath,
                [StringComparison]::OrdinalIgnoreCase
            )
        })
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { [int]$_.LocalPort -in @(80, 443) })
    return $null -ne $serviceBinding -and
        [string]$serviceBinding.State -ceq 'Stopped' -and
        [uint32]$serviceBinding.ProcessId -eq 0 -and
        $processes.Count -eq 0 -and $listeners.Count -eq 0
}

$mutationStarted = $false
try {
    $mutationStarted = $true
    Enable-NetFirewallRule -DisplayGroup $firewallRuleGroup -ErrorAction Stop
    Assert-FirewallState 'ACTIVE'

    $service = Get-Service -Name $serviceId -ErrorAction Stop
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $serviceId -Force -ErrorAction Stop
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    if (-not (Test-ProxyServiceAndListenersStopped)) {
        throw 'External Caddy did not reach the exact quiescent SCM/PID/listener state'
    }
    $quiescedBinding = & (Join-Path $PSScriptRoot `
            'Test-LeanTpmExternalCaddyBinding.ps1') `
        -InstallRoot $install -DataRoot $data `
        -PolicyPath ([string]$rootPolicy.proxy.bindingPolicyPath) `
        -ExpectedPolicySha256 ([string]$rootPolicy.proxy.bindingPolicySha256) `
        -ExpectedHostLayoutSha256 ([string]$rootPolicy.hostLayoutSha256) `
        -ExpectedFirewallState ACTIVE -RuntimeTreeMode QUIESCED_TREE `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$quiescedBinding.status -cne 'PASS' -or
            [string]$quiescedBinding.firewallState -cne 'ACTIVE' -or
            -not [bool]$quiescedBinding.runtimeTreeQuiescenceVerified -or
            -not [bool]$quiescedBinding.failClosedCapable -or
            [string]$quiescedBinding.firewallPolicySha256 -cne
                [string]$rootPolicy.proxyBinding.firewallPolicySha256 -or
            [string]$quiescedBinding.proxyBindingSha256 -cne
                $ExpectedProxyBindingSha256) {
        throw 'External Caddy mutable runtime tree did not pass quiescent validation'
    }
    $report.runtimeTreeQuiescenceVerified = $true
    $report.runtimeTreeScanCompletedAtUtc =
        ([DateTime]$quiescedBinding.runtimeTreeScanCompletedAtUtc).ToUniversalTime().ToString('o')

    Start-Service -Name $serviceId -ErrorAction Stop
    (Get-Service -Name $serviceId -ErrorAction Stop).
        WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
    $guardedBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyBinding.ps1') `
        -InstallRoot $install -DataRoot $data `
        -PolicyPath ([string]$rootPolicy.proxy.bindingPolicyPath) `
        -ExpectedPolicySha256 ([string]$rootPolicy.proxy.bindingPolicySha256) `
        -ExpectedHostLayoutSha256 ([string]$rootPolicy.hostLayoutSha256) `
        -ExpectedFirewallState ACTIVE -RuntimeTreeMode ROOTS_ONLY `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$guardedBinding.status -cne 'PASS' -or
            [string]$guardedBinding.firewallState -cne 'ACTIVE' -or
            [string]$guardedBinding.proxyBindingSha256 -cne $ExpectedProxyBindingSha256) {
        throw 'External Caddy did not pass the exact binding while public ingress was guarded'
    }
    if (([DateTime]$guardedBinding.processStartedAtUtc).ToUniversalTime() -le
            ([DateTime]$quiescedBinding.runtimeTreeScanCompletedAtUtc).ToUniversalTime()) {
        throw 'External Caddy guarded PID did not start after quiescent runtime-tree validation'
    }

    Disable-NetFirewallRule -DisplayGroup $firewallRuleGroup -ErrorAction Stop
    $binding = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyBinding.ps1') `
        -InstallRoot $install -DataRoot $data `
        -PolicyPath ([string]$rootPolicy.proxy.bindingPolicyPath) `
        -ExpectedPolicySha256 ([string]$rootPolicy.proxy.bindingPolicySha256) `
        -ExpectedHostLayoutSha256 ([string]$rootPolicy.hostLayoutSha256) `
        -ExpectedFirewallState STANDBY_DISABLED -RuntimeTreeMode ROOTS_ONLY `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$binding.status -cne 'PASS' -or
            [string]$binding.firewallState -cne 'STANDBY_DISABLED' -or
            -not [bool]$binding.failClosedCapable -or
            [string]$binding.proxyBindingSha256 -cne $ExpectedProxyBindingSha256) {
        throw 'External Caddy did not return to the exact approved runtime binding'
    }

    $publicUri = [Uri]("https://{0}/" -f [string]$binding.publicHost)
    $response = Invoke-WebRequest -Uri $publicUri -UseBasicParsing -TimeoutSec 15
    if ([int]$response.StatusCode -lt 200 -or [int]$response.StatusCode -ge 400) {
        throw 'External Caddy public HTTPS readiness failed'
    }
    $report.status = 'INGRESS_RESTORED'
    $report.isolationMethod = $null
    $report.publicHttpsVerified = $true
    $report.publicUri = $publicUri.AbsoluteUri
}
catch {
    $restoreFailure = $_
    if ($mutationStarted) {
        try {
            Enable-NetFirewallRule -DisplayGroup $firewallRuleGroup -ErrorAction Stop
            Assert-FirewallState 'ACTIVE'
            $report.status = 'INGRESS_RESTORE_FAILED'
            $report.isolationMethod = 'HOST_FIREWALL'
            $report.publicHttpsVerified = $false
            $report.failure = $restoreFailure.Exception.Message
            $output = [pscustomobject]$report
            if ($OutputFormat -eq 'Json') { $output | ConvertTo-Json -Depth 5 -Compress }
            else { $output | Format-List }
            return
        }
        catch {
            $firewallFailure = $_
            try {
                Stop-Service -Name $serviceId -Force -ErrorAction Stop
                (Get-Service -Name $serviceId -ErrorAction Stop).
                    WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
                if (-not (Test-ProxyServiceAndListenersStopped)) {
                    throw 'SCM/PID/listener verification did not prove proxy isolation'
                }
                $report.status = 'INGRESS_RESTORE_FAILED'
                $report.isolationMethod = 'SERVICE_STOP'
                $report.publicHttpsVerified = $false
                $report.failure = $restoreFailure.Exception.Message
                $output = [pscustomobject]$report
                if ($OutputFormat -eq 'Json') { $output | ConvertTo-Json -Depth 5 -Compress }
                else { $output | Format-List }
                return
            }
            catch {
                throw ("CRITICAL_INGRESS_ISOLATION_UNPROVEN after '{0}'; firewall: {1}; " +
                    "service isolation: {2}" -f $restoreFailure.Exception.Message,
                    $firewallFailure.Exception.Message, $_.Exception.Message)
            }
        }
    }
    throw
}
$output = [pscustomobject]$report
if ($OutputFormat -eq 'Json') { $output | ConvertTo-Json -Depth 5 -Compress }
else { $output | Format-List }
