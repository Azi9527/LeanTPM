[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$CaddyPath,
    [Parameter(Mandatory)][string]$ExpectedCaddySha256,
    [Parameter(Mandatory)][string]$WrapperPath,
    [Parameter(Mandatory)][string]$ExpectedWrapperSha256,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$SiteHost,
    [Parameter(Mandatory)][string]$ProxyServiceAccount,
    [Parameter(Mandatory)][string]$BackendServiceAccount,
    [switch]$AllowUnpinnedTestBinaries,
    [switch]$AllowNonProductionRoots,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Proxy'
$resolvedCaddy = (Resolve-Path -LiteralPath $CaddyPath).Path
$resolvedWrapper = (Resolve-Path -LiteralPath $WrapperPath).Path
$install = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoots) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
    -PlanOnly:$PlanOnly -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionRoots) {
    throw 'AllowNonProductionRoots cannot be used with the production root pair'
}
if ($isProductionRootPair -and [string]$rootPolicy.proxy.mode -cne 'MANAGED_LEANTPM_PROXY') {
    throw 'Managed LeanTPM.Proxy installation is forbidden by the host-owned proxy mode'
}
if ($AllowUnpinnedTestBinaries -and -not $PlanOnly) {
    throw 'AllowUnpinnedTestBinaries is restricted to side-effect-free PlanOnly validation'
}
if ($SiteHost -cnotmatch '^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$') {
    throw 'SiteHost must be a lower-case DNS hostname'
}
foreach ($account in @($ProxyServiceAccount, $BackendServiceAccount)) {
    if ($account -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$') {
        throw 'ProxyServiceAccount and BackendServiceAccount must be approved gMSA identities'
    }
}
if ($ProxyServiceAccount.Equals($BackendServiceAccount, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ProxyServiceAccount and BackendServiceAccount must be different identities'
}

$toolchain = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$caddyPin = [string]$toolchain.caddy.sha256
$wrapperPin = [string]$toolchain.winSW.sha256
if ($PlanOnly -and $AllowUnpinnedTestBinaries) {
    $caddyPin = $ExpectedCaddySha256
    $wrapperPin = $ExpectedWrapperSha256
}
if ($caddyPin -notmatch '^[A-Fa-f0-9]{64}$' -or $wrapperPin -notmatch '^[A-Fa-f0-9]{64}$') {
    throw 'Caddy and WinSW must be pinned in release/toolchain-lock.json before installation'
}
if ($ExpectedCaddySha256 -notmatch '^[A-Fa-f0-9]{64}$' -or
        $ExpectedWrapperSha256 -notmatch '^[A-Fa-f0-9]{64}$' -or
        -not $ExpectedCaddySha256.Equals($caddyPin, [StringComparison]::OrdinalIgnoreCase) -or
        -not $ExpectedWrapperSha256.Equals($wrapperPin, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Expected Caddy or WinSW digest does not match the approved toolchain lock'
}
$actualCaddy = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedCaddy).Hash
$actualWrapper = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedWrapper).Hash
if (-not $actualCaddy.Equals($caddyPin, [StringComparison]::OrdinalIgnoreCase) -or
        -not $actualWrapper.Equals($wrapperPin, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Caddy or WinSW source bytes do not match the approved digest'
}

$proxyServiceRoot = Join-Path $install 'proxy'
$proxyRoot = Join-Path $data 'proxy'
$proxyConfigRoot = Join-Path $proxyRoot 'config'
$proxyDataRoot = Join-Path $proxyRoot 'data'
$proxyLogRoot = Join-Path $proxyRoot 'logs'
$targetWrapper = Join-Path $proxyServiceRoot "$serviceId.exe"
$targetCaddy = Join-Path $proxyServiceRoot 'caddy.exe'
$targetXml = Join-Path $proxyServiceRoot "$serviceId.xml"
$targetCaddyfile = Join-Path $proxyConfigRoot 'Caddyfile'
$currentReleasePath = Join-Path $install 'current'
$releasesDirectory = Join-Path $install 'releases'

$caddyTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Caddyfile.template') `
    -Encoding utf8 -Raw
$renderedCaddyfile = $caddyTemplate.Replace('@SITE_HOST@', $SiteHost).
    Replace('@INSTALL_ROOT@', $install)
$xmlTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'LeanTPM.Proxy.xml.template') `
    -Encoding utf8 -Raw
$renderedXml = $xmlTemplate.Replace('@CADDY_EXECUTABLE@', $targetCaddy).
    Replace('@CADDYFILE@', $targetCaddyfile).
    Replace('@PROXY_DATA_ROOT@', $proxyDataRoot).
    Replace('@PROXY_CONFIG_ROOT@', $proxyConfigRoot).
    Replace('@PROXY_LOG_ROOT@', $proxyLogRoot).
    Replace('@PROXY_SERVICE_ACCOUNT@', $ProxyServiceAccount)

$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    serviceId = $serviceId
    siteHost = $SiteHost
    proxyServiceAccount = $ProxyServiceAccount
    backendServiceAccount = $BackendServiceAccount
    caddySha256 = $actualCaddy.ToLowerInvariant()
    wrapperSha256 = $actualWrapper.ToLowerInvariant()
    hostLayoutSha256 = if ($isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
    steps = @(
        'VERIFY_TOOLCHAIN', 'VERIFY_IDENTITY_ISOLATION', 'CREATE_HARDENED_DIRECTORIES',
        'GRANT_CURRENT_WEB_RX', 'RENDER_CADDY_CONFIG', 'INSTALL_DISABLED_SERVICE',
        'VERIFY_DISABLED_BINDING', 'ENABLE_DELAYED_AUTOMATIC', 'VERIFY_BINDING'
    )
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}

$lockPath = Join-Path $data 'locks\deployment.lock'
if (-not (Test-Path -LiteralPath (Split-Path -Parent $lockPath) -PathType Container)) {
    throw 'Host mutation lock directory must be initialized before proxy installation'
}
$ownedLock = New-Object IO.FileStream(
    $lockPath,
    [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite,
    [IO.FileShare]::Read
)
$lockTokenBytes = New-Object byte[] 32
$lockTokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $lockTokenGenerator.GetBytes($lockTokenBytes) }
finally { $lockTokenGenerator.Dispose() }
$lockToken = [Text.Encoding]::ASCII.GetBytes(
    [BitConverter]::ToString($lockTokenBytes).Replace('-', '').ToLowerInvariant()
)
$ownedLock.SetLength(0)
$ownedLock.Write($lockToken, 0, $lockToken.Length)
$ownedLock.Flush($true)
try {
    $lockedPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
        -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
        -OutputFormat Json | ConvertFrom-Json
    if ([bool]$lockedPolicy.isProductionRootPair -ne $isProductionRootPair -or
            [string]$lockedPolicy.installRoot -cne [string]$rootPolicy.installRoot -or
            [string]$lockedPolicy.dataRoot -cne [string]$rootPolicy.dataRoot -or
            [string]$lockedPolicy.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256 -or
            [string]$lockedPolicy.proxy.mode -cne [string]$rootPolicy.proxy.mode) {
        throw 'Host layout or proxy mode changed after acquiring the global mutation lock'
    }
    if ($isProductionRootPair -and
            [string]$lockedPolicy.proxy.mode -cne 'MANAGED_LEANTPM_PROXY') {
        throw 'Managed LeanTPM.Proxy installation is forbidden by the locked host policy'
    }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator privileges are required to install the HTTPS proxy service'
}
$trustPath = Join-Path $data 'config\release-trust.json'
if (-not (Test-Path -LiteralPath $trustPath -PathType Leaf)) {
    throw 'Host-owned release-trust.json is required before proxy installation'
}
$trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$trust.proxyServiceAccount -cne $ProxyServiceAccount -or
        [string]$trust.backendServiceAccount -cne $BackendServiceAccount -or
        [string]$trust.publicHost -cne $SiteHost) {
    throw 'Proxy/Backend identities and SiteHost must match host trust.publicHost'
}
if (-not ([string]$trust.caddySha256).Equals(
        $caddyPin, [StringComparison]::OrdinalIgnoreCase
    ) -or -not ([string]$trust.winSWSha256).Equals(
        $wrapperPin, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host-owned Caddy/WinSW digests differ from release/toolchain-lock.json'
}
$backendBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmWindowsServiceBinding.ps1') `
    -InstallRoot $install -DataRoot $data -OutputFormat Json | ConvertFrom-Json
if ([string]$backendBinding.status -cne 'PASS') {
    throw 'LeanTPM.Backend binding must be healthy before proxy installation'
}

$adoptedCaddy = @(Get-CimInstance -ClassName Win32_Service -Filter "Name='caddy'" `
    -ErrorAction Stop)
if ($adoptedCaddy.Count -ne 0) {
    throw 'Managed LeanTPM.Proxy cannot coexist with the existing caddy service'
}
$existing = Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceId'" `
    -ErrorAction Stop
if ($null -ne $existing) {
    $proxyBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmCaddyServiceBinding.ps1') `
        -InstallRoot $install -DataRoot $data `
        -AllowNonProductionRoots:$AllowNonProductionRoots `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$proxyBinding.status -cne 'PASS') {
        throw 'LeanTPM.Proxy installation drift detected; refusing an in-place overwrite'
    }
    $report.status = 'ALREADY_INSTALLED'
    $report | Add-Member -NotePropertyName serviceStatus -NotePropertyValue ([string]$existing.State)
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}
$occupiedPublicPorts = @(Get-NetTCPConnection -State Listen -LocalPort 80, 443 `
    -ErrorAction Stop)
if ($occupiedPublicPorts.Count -ne 0) {
    throw 'Managed proxy installation requires unoccupied public ingress ports 80 and 443'
}

$ownsNewService = $false
if ($PSCmdlet.ShouldProcess($serviceId, 'Install pinned isolated HTTPS proxy service')) {
    try {
        foreach ($directory in @(
            $proxyServiceRoot, $proxyRoot, $proxyConfigRoot, $proxyDataRoot,
            $proxyLogRoot, $releasesDirectory
        )) {
            $null = New-Item -ItemType Directory -Path $directory -Force
        }
        & icacls.exe $proxyServiceRoot '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' "$ProxyServiceAccount`:(OI)(CI)RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the proxy service directory' }
        & icacls.exe $proxyRoot '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' "$ProxyServiceAccount`:(OI)(CI)RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the proxy data root' }
        & icacls.exe $proxyConfigRoot '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' "$ProxyServiceAccount`:(OI)(CI)RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the proxy configuration directory' }
        foreach ($runtimePath in @($proxyDataRoot, $proxyLogRoot)) {
            & icacls.exe $runtimePath '/inheritance:r' '/grant:r' `
                'Administrators:(OI)(CI)F' "$ProxyServiceAccount`:(OI)(CI)M" | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to harden proxy runtime directory: $runtimePath"
            }
        }
        # The proxy receives RX on versioned Web files/current targets, never on Backend config or secrets.
        & icacls.exe $releasesDirectory '/inheritance:r' '/grant:r' `
            'Administrators:(OI)(CI)F' "$BackendServiceAccount`:(OI)(CI)RX" `
            "$ProxyServiceAccount`:(OI)(CI)RX" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to grant current Web release RX: $currentReleasePath"
        }
        foreach ($ownedDirectory in @(
            $proxyServiceRoot, $proxyRoot, $proxyConfigRoot, $proxyDataRoot,
            $proxyLogRoot, $releasesDirectory
        )) {
            & icacls.exe $ownedDirectory '/setowner' 'Administrators' | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to set proxy directory owner: $ownedDirectory"
            }
        }

        Copy-Item -LiteralPath $resolvedWrapper -Destination $targetWrapper
        Copy-Item -LiteralPath $resolvedCaddy -Destination $targetCaddy
        [IO.File]::WriteAllText(
            $targetXml, $renderedXml, (New-Object Text.UTF8Encoding($false))
        )
        [IO.File]::WriteAllText(
            $targetCaddyfile, $renderedCaddyfile, (New-Object Text.UTF8Encoding($false))
        )
        foreach ($protectedFile in @($targetWrapper, $targetCaddy, $targetXml, $targetCaddyfile)) {
            & icacls.exe $protectedFile '/inheritance:r' '/grant:r' `
                'Administrators:F' "$ProxyServiceAccount`:RX" | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to harden proxy file ACL: $protectedFile"
            }
            & icacls.exe $protectedFile '/setowner' 'Administrators' | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to set proxy file owner: $protectedFile"
            }
        }
        if (-not (Get-FileHash -Algorithm SHA256 -LiteralPath $targetWrapper).Hash.Equals(
                $wrapperPin, [StringComparison]::OrdinalIgnoreCase
            ) -or -not (Get-FileHash -Algorithm SHA256 -LiteralPath $targetCaddy).Hash.Equals(
                $caddyPin, [StringComparison]::OrdinalIgnoreCase
            )) {
            throw 'Installed proxy binaries changed after copying into the hardened directory'
        }
        & $targetWrapper install
        $ownsNewService = $true
        if ($LASTEXITCODE -ne 0) { throw 'WinSW proxy service installation failed' }
        $proxySddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
        & sc.exe sdset $serviceId $proxySddl | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the fixed LeanTPM.Proxy SCM DACL' }
        $disabledBinding = & (Join-Path $PSScriptRoot `
                'Test-LeanTpmCaddyServiceBinding.ps1') `
            -InstallRoot $install -DataRoot $data -AllowDisabledForInstallation `
            -AllowNonProductionRoots:$AllowNonProductionRoots `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$disabledBinding.status -cne 'PASS') {
            throw 'LeanTPM.Proxy failed its disabled pre-activation binding verification'
        }
        & sc.exe config $serviceId start= delayed-auto | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to enable LeanTPM.Proxy delayed automatic start after verification'
        }
        $proxyBinding = & (Join-Path $PSScriptRoot 'Test-LeanTpmCaddyServiceBinding.ps1') `
            -InstallRoot $install -DataRoot $data `
            -AllowNonProductionRoots:$AllowNonProductionRoots `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$proxyBinding.status -cne 'PASS') {
            throw 'LeanTPM.Proxy failed its final supply-chain, ACL or SCM verification'
        }
        $report.status = 'INSTALLED'
        $report | Add-Member -NotePropertyName serviceStatus `
            -NotePropertyValue ([string]$proxyBinding.state)
    }
    catch {
        $failure = $_
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        if ($ownsNewService) {
            try {
                $ownedRegistration = Get-CimInstance -ClassName Win32_Service `
                    -Filter "Name='LeanTPM.Proxy'" -ErrorAction Stop
                if ($null -ne $ownedRegistration) {
                    & sc.exe config $serviceId start= disabled | Out-Null
                    if ($LASTEXITCODE -ne 0) {
                        throw 'Failed to disable the owned proxy registration before cleanup'
                    }
                    try { Stop-Service -Name $serviceId -Force -ErrorAction Stop }
                    catch {
                        if ([string]$ownedRegistration.State -cne 'Stopped') { throw }
                    }
                    & $targetWrapper uninstall | Out-Null
                }
            }
            catch { $cleanupErrors.Add("DISABLE_OR_UNINSTALL: $($_.Exception.Message)") }
            try {
                $remainingProxy = Get-CimInstance -ClassName Win32_Service `
                    -Filter "Name='LeanTPM.Proxy'" -ErrorAction Stop
                if ($null -ne $remainingProxy) {
                    if ([string]$remainingProxy.StartMode -cne 'Disabled' -or
                            [string]$remainingProxy.State -cne 'Stopped' -or
                            [uint32]$remainingProxy.ProcessId -ne 0) {
                        throw 'LeanTPM.Proxy cleanup failed without a Disabled/Stopped safety state'
                    }
                    throw 'LeanTPM.Proxy must not remain registered after failed installation'
                }
            }
            catch { $cleanupErrors.Add("VERIFY_REMOVAL: $($_.Exception.Message)") }
        }
        if ($cleanupErrors.Count -gt 0) {
            throw "PROXY_INSTALL_COMPENSATION_FAILED after '$($failure.Exception.Message)': $($cleanupErrors -join '; ')"
        }
        throw $failure
    }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
}
finally { $ownedLock.Dispose() }
