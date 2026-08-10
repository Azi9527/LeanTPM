[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Status', 'Start', 'Stop', 'Restart', 'Uninstall')]
    [string]$Action,
    [ValidateSet('All', 'OpsControl', 'ReleaseAgent')][string]$Target = 'All',
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [string]$DeploymentLockToken = '',
    [switch]$AllowNonProductionRoot,
    [switch]$PlanOnly,
    [switch]$ConfirmServiceAction,
    [string]$ConfirmUninstallServiceIds = '',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$install = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoot) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
    -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
    -OutputFormat Json | ConvertFrom-Json
$allServiceIds = @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')
$serviceIds = switch ($Target) {
    'OpsControl' { @('LeanTPM.OpsControl') }
    'ReleaseAgent' { @('LeanTPM.ReleaseAgent') }
    default { $allServiceIds }
}
$steps = switch ($Action) {
    'Status' { @('VERIFY_FIXED_BINDING', 'QUERY_STATUS') }
    'Start' { @('VERIFY_FIXED_BINDING', 'ACQUIRE_GLOBAL_LOCK', 'START', 'QUERY_STATUS') }
    'Stop' { @('VERIFY_FIXED_BINDING', 'ACQUIRE_GLOBAL_LOCK', 'STOP', 'QUERY_STATUS') }
    'Restart' {
        @('VERIFY_FIXED_BINDING', 'ACQUIRE_GLOBAL_LOCK', 'STOP', 'START', 'QUERY_STATUS')
    }
    'Uninstall' {
        @('VERIFY_FIXED_BINDING', 'VERIFY_DUAL_APPROVAL', 'ACQUIRE_GLOBAL_LOCK',
            'STOP', 'UNINSTALL_REGISTRATION_ONLY', 'QUERY_STATUS')
    }
}
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    executable = -not $PlanOnly
    action = $Action.ToUpperInvariant()
    target = $Target.ToUpperInvariant()
    serviceIds = $serviceIds
    steps = $steps
    dataPreserved = $true
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}

$binding = & (Join-Path $PSScriptRoot 'Test-LeanTpmOpsServicesBinding.ps1') `
    -InstallRoot $install -DataRoot $data -OutputFormat Json | ConvertFrom-Json
if ([string]$binding.status -cne 'PASS') {
    throw 'Ops services fixed binding verification failed'
}
if ($Action -ne 'Status' -and -not $ConfirmServiceAction) {
    throw 'ConfirmServiceAction is required for a mutating Ops service action'
}
if ($Action -eq 'Uninstall') {
    if ($Target -cne 'All' -or
            $ConfirmUninstallServiceIds -cne
                'LeanTPM.OpsControl,LeanTPM.ReleaseAgent') {
        throw 'ConfirmUninstallServiceIds must exactly confirm both fixed Ops service IDs'
    }
    if ([bool]$rootPolicy.isProductionRootPair) {
        throw 'Production Ops service uninstall is disabled until its dedicated signed nonce/replay ceremony is implemented'
    }
}
if ($Action -ne 'Status' -and
        -not $PSCmdlet.ShouldProcess(($serviceIds -join ', '), $Action)) {
    return
}

$ownedLock = $null
try {
    if ($Action -ne 'Status') {
        $lockPath = Join-Path $data 'locks\deployment.lock'
        if ([string]::IsNullOrWhiteSpace($DeploymentLockToken)) {
            $ownedLock = New-Object IO.FileStream(
                $lockPath,
                [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::Read
            )
        }
        elseif ($DeploymentLockToken -notmatch '^[a-f0-9]{64}$' -or
                -not (Test-Path -LiteralPath $lockPath -PathType Leaf) -or
                (Get-Content -LiteralPath $lockPath -Encoding ascii -Raw).Trim() -cne
                    $DeploymentLockToken) {
            throw 'Caller deployment lock token is invalid'
        }
        $lockedPolicy = & (Join-Path $PSScriptRoot `
            'Test-LeanTpmProductionRootPolicy.ps1') `
            -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
            -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$lockedPolicy.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256 -or
                [string]$lockedPolicy.installRoot -cne [string]$rootPolicy.installRoot -or
                [string]$lockedPolicy.dataRoot -cne [string]$rootPolicy.dataRoot) {
            throw 'Host layout changed after acquiring the global deployment lock'
        }
        $lockedBinding = & (Join-Path $PSScriptRoot `
            'Test-LeanTpmOpsServicesBinding.ps1') `
            -InstallRoot $install -DataRoot $data -OutputFormat Json |
            ConvertFrom-Json
        if ([string]$lockedBinding.bindingSha256 -cne
                [string]$binding.bindingSha256) {
            throw 'Ops service binding changed after acquiring the global deployment lock'
        }
    }

    if ($Action -in @('Stop', 'Restart', 'Uninstall')) {
        foreach ($serviceId in $serviceIds) {
            Stop-Service -Name $serviceId -Force -ErrorAction Stop
            (Get-Service -Name $serviceId -ErrorAction Stop).WaitForStatus(
                'Stopped',
                [TimeSpan]::FromSeconds(60)
            )
        }
    }
    if ($Action -eq 'Uninstall') {
        $uninstallServiceIds = @($serviceIds)
        [array]::Reverse($uninstallServiceIds)
        foreach ($serviceId in $uninstallServiceIds) {
            $wrapper = Join-Path $install "ops-services\$serviceId.exe"
            & $wrapper uninstall
            if ($LASTEXITCODE -ne 0) { throw "Failed to uninstall $serviceId" }
        }
    }
    if ($Action -in @('Start', 'Restart')) {
        foreach ($serviceId in @(
                $serviceIds | Sort-Object {
                    if ($_ -ceq 'LeanTPM.ReleaseAgent') { 0 } else { 1 }
                }
            )) {
            Start-Service -Name $serviceId -ErrorAction Stop
            (Get-Service -Name $serviceId -ErrorAction Stop).WaitForStatus(
                'Running',
                [TimeSpan]::FromSeconds(60)
            )
        }
    }

    $states = New-Object Collections.Generic.List[object]
    foreach ($serviceId in $serviceIds) {
        $service = Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='$serviceId'" -ErrorAction SilentlyContinue
        $states.Add([pscustomobject]@{
            serviceId = $serviceId
            state = if ($null -eq $service) { 'NOT_INSTALLED' } else {
                [string]$service.State
            }
            processId = if ($null -eq $service) { 0 } else { [int]$service.ProcessId }
        })
    }
    $report.status = 'COMPLETED'
    $report | Add-Member -NotePropertyName states -NotePropertyValue @($states)
}
finally {
    if ($null -ne $ownedLock) { $ownedLock.Dispose() }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
