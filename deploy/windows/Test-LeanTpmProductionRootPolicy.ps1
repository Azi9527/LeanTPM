[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)]
    [ValidateSet('NON_PRODUCTION', 'PRODUCTION')][string]$EnvironmentKind,
    [switch]$PlanOnly,
    [switch]$AllowNonProductionCustomRoots,
    [switch]$ContainmentOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$install = (Resolve-Path -LiteralPath $InstallRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$bootstrap = & (Join-Path $PSScriptRoot 'Test-LeanTpmHostBootstrap.ps1') `
    -AllowMissing -OutputFormat Json | ConvertFrom-Json

$isProductionRootPair = $false
if ([string]$bootstrap.status -ceq 'PASS') {
    $isProductionRootPair = $install.Equals(
            [string]$bootstrap.layout.paths.installRoot,
            [StringComparison]::OrdinalIgnoreCase
        ) -and $data.Equals(
            [string]$bootstrap.layout.paths.dataRoot,
            [StringComparison]::OrdinalIgnoreCase
        )
}
elseif ([string]$bootstrap.status -cne 'MISSING') {
    throw 'Host bootstrap returned an unsupported production policy state'
}

if ($EnvironmentKind -eq 'PRODUCTION' -and
        [string]$bootstrap.status -ne 'PASS') {
    throw 'PRODUCTION requires a verified host-owned production root pair'
}
if ($EnvironmentKind -eq 'PRODUCTION' -and -not $isProductionRootPair) {
    throw 'Plan roots differ from the verified host-owned production root pair'
}
if ($isProductionRootPair -and $EnvironmentKind -ne 'PRODUCTION') {
    throw 'The production root pair requires environmentKind=PRODUCTION'
}
if ($isProductionRootPair -and $AllowNonProductionCustomRoots) {
    throw 'AllowNonProductionCustomRoots cannot be used with the production root pair'
}
$proxyBinding = $null
if ($isProductionRootPair -and
        [string]$bootstrap.layout.proxy.mode -ceq 'EXTERNAL_EXISTING') {
    if ($ContainmentOnly) {
        $proxyBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyContract.ps1') `
            -PolicyPath ([string]$bootstrap.layout.proxy.bindingPolicyPath) `
            -ExpectedPolicySha256 ([string]$bootstrap.layout.proxy.bindingPolicySha256) `
            -ExpectedInstallRoot $install -ExpectedDataRoot $data `
            -PolicyOnly -OutputFormat Json | ConvertFrom-Json
        if ([string]$proxyBinding.status -cne 'PASS' -or
                -not [bool]$proxyBinding.policyOnly -or
                [string]$proxyBinding.proxyBindingSha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'EXTERNAL_EXISTING containment policy is not host-bound and valid'
        }
    }
    else {
        $proxyBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyBinding.ps1') `
            -InstallRoot $install -DataRoot $data `
            -PolicyPath ([string]$bootstrap.layout.proxy.bindingPolicyPath) `
            -ExpectedPolicySha256 ([string]$bootstrap.layout.proxy.bindingPolicySha256) `
            -ExpectedHostLayoutSha256 ([string]$bootstrap.layoutSha256) `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$proxyBinding.status -cne 'PASS' -or
                -not [bool]$proxyBinding.failClosedCapable -or
                [string]$proxyBinding.proxyBindingSha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'EXTERNAL_EXISTING Caddy did not pass the exact fail-closed binding contract'
        }
    }
}

$customRoots = -not $isProductionRootPair
if ($customRoots -and $EnvironmentKind -ne 'NON_PRODUCTION') {
    throw 'Custom roots require environmentKind=NON_PRODUCTION'
}
if (-not $PlanOnly -and $customRoots -and -not $AllowNonProductionCustomRoots) {
    throw 'Executable plans must use host-owned roots; custom roots are isolated NON_PRODUCTION only'
}

$report = [pscustomobject]@{
    status = 'PASS'
    environmentKind = $EnvironmentKind
    installRoot = $install
    dataRoot = $data
    isProductionRootPair = $isProductionRootPair
    customRoots = $customRoots
    bootstrapStatus = [string]$bootstrap.status
    hostLayoutSha256 = if ($isProductionRootPair) { [string]$bootstrap.layoutSha256 } else { $null }
    environmentId = if ($isProductionRootPair) {
        [string]$bootstrap.layout.environmentId
    }
    else { $null }
    hostId = if ($isProductionRootPair) { [string]$bootstrap.layout.hostId } else { $null }
    volumeIdentity = if ($isProductionRootPair) {
        [string]$bootstrap.layout.volumeIdentity
    }
    else { $null }
    proxy = if ($isProductionRootPair) { $bootstrap.layout.proxy } else { $null }
    proxyBindingSha256 = if ($null -ne $proxyBinding) {
        [string]$proxyBinding.proxyBindingSha256
    }
    else { $null }
    proxyBinding = $proxyBinding
    serviceAccountMode = if ($isProductionRootPair) {
        [string]$bootstrap.serviceAccountMode
    }
    else { $null }
    serviceIdentities = if ($isProductionRootPair) {
        $bootstrap.serviceIdentities
    }
    else { $null }
    containmentOnly = [bool]$ContainmentOnly
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 6 -Compress }
else { $report | Format-List }
