[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [switch]$AllowDisabledForInstallation,
    [switch]$AllowNonProductionRoots,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Proxy'
$install = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoots) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionRoots) {
    throw 'AllowNonProductionRoots cannot be used with the production root pair'
}
if ($isProductionRootPair -and [string]$rootPolicy.proxy.mode -cne 'MANAGED_LEANTPM_PROXY') {
    throw 'LeanTPM.Proxy binding verification is forbidden by the host-owned proxy mode'
}
$serviceRoot = Join-Path $install 'proxy'
$proxyRoot = Join-Path $data 'proxy'
$proxyConfigRoot = Join-Path $proxyRoot 'config'
$proxyDataRoot = Join-Path $proxyRoot 'data'
$proxyLogRoot = Join-Path $proxyRoot 'logs'
$releasesRoot = Join-Path $install 'releases'
$wrapperPath = Join-Path $serviceRoot "$serviceId.exe"
$caddyPath = Join-Path $serviceRoot 'caddy.exe'
$xmlPath = Join-Path $serviceRoot "$serviceId.xml"
$caddyfilePath = Join-Path $proxyConfigRoot 'Caddyfile'
$trustPath = Join-Path $data 'config\release-trust.json'
$toolchainPath = Join-Path $PSScriptRoot '..\..\release\toolchain-lock.json'

foreach ($path in @(
        $wrapperPath, $caddyPath, $xmlPath, $caddyfilePath, $trustPath, $toolchainPath
    )) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            ((Get-Item -LiteralPath $path -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw "Proxy supply-chain file is missing or unsafe: $path"
    }
}
$toolchain = Get-Content -LiteralPath $toolchainPath -Encoding utf8 -Raw | ConvertFrom-Json
$trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
$wrapperPin = [string]$toolchain.winSW.sha256
$caddyPin = [string]$toolchain.caddy.sha256
$proxyAccount = [string]$trust.proxyServiceAccount
$backendAccount = [string]$trust.backendServiceAccount
$publicHost = [string]$trust.publicHost
if ($wrapperPin -notmatch '^[a-f0-9]{64}$' -or $caddyPin -notmatch '^[a-f0-9]{64}$' -or
        $proxyAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -or
        $backendAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -or
        $proxyAccount.Equals($backendAccount, [StringComparison]::OrdinalIgnoreCase) -or
        $publicHost -cnotmatch '^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$') {
    throw 'Proxy toolchain, identities or publicHost are not pinned by host trust'
}
if (-not ([string]$trust.winSWSha256).Equals(
        $wrapperPin, [StringComparison]::OrdinalIgnoreCase
    ) -or -not ([string]$trust.caddySha256).Equals(
        $caddyPin, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host trust Caddy/WinSW digests differ from release/toolchain-lock.json'
}
if (-not (Get-FileHash -Algorithm SHA256 -LiteralPath $wrapperPath).Hash.Equals(
        $wrapperPin, [StringComparison]::OrdinalIgnoreCase
    ) -or -not (Get-FileHash -Algorithm SHA256 -LiteralPath $caddyPath).Hash.Equals(
        $caddyPin, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Installed Caddy or WinSW bytes differ from the pinned toolchain'
}

$caddyTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Caddyfile.template') `
    -Encoding utf8 -Raw
$expectedCaddyfile = $caddyTemplate.Replace('@SITE_HOST@', $publicHost).
    Replace('@INSTALL_ROOT@', $install)
$xmlTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'LeanTPM.Proxy.xml.template') `
    -Encoding utf8 -Raw
$expectedXml = $xmlTemplate.Replace('@CADDY_EXECUTABLE@', $caddyPath).
    Replace('@CADDYFILE@', $caddyfilePath).
    Replace('@PROXY_DATA_ROOT@', $proxyDataRoot).
    Replace('@PROXY_CONFIG_ROOT@', $proxyConfigRoot).
    Replace('@PROXY_LOG_ROOT@', $proxyLogRoot).
    Replace('@PROXY_SERVICE_ACCOUNT@', $proxyAccount)
if ((Get-Content -LiteralPath $caddyfilePath -Encoding utf8 -Raw) -cne $expectedCaddyfile -or
        (Get-Content -LiteralPath $xmlPath -Encoding utf8 -Raw) -cne $expectedXml) {
    throw 'Installed Caddyfile or WinSW XML differs from the host-rendered fixed template'
}

$adoptedCaddy = @(Get-CimInstance -ClassName Win32_Service -Filter "Name='caddy'" `
    -ErrorAction Stop)
if ($adoptedCaddy.Count -ne 0) {
    throw 'LeanTPM.Proxy binding rejects coexistence with the existing caddy service'
}
$service = Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceId'" `
    -ErrorAction Stop
if ($null -eq $service) { throw 'LeanTPM.Proxy is not registered in SCM' }
$imagePath = ([string]$service.PathName).Trim().Trim('"')
$approvedStartModes = if ($AllowDisabledForInstallation) {
    @('Disabled')
}
else { @('Auto', 'Automatic') }
if (-not $imagePath.Equals($wrapperPath, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$service.StartName -cne $proxyAccount -or
        [string]$service.StartMode -notin $approvedStartModes) {
    throw 'LeanTPM.Proxy SCM image, StartName or StartMode drifted'
}
$approvedProxyPids = [System.Collections.Generic.HashSet[int]]::new()
if ([int]$service.ProcessId -gt 0) {
    $allProcesses = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    $null = $approvedProxyPids.Add([int]$service.ProcessId)
    do {
        $added = $false
        foreach ($process in $allProcesses) {
            if ([int]$process.ParentProcessId -in $approvedProxyPids -and
                    $approvedProxyPids.Add([int]$process.ProcessId)) { $added = $true }
        }
    } while ($added)
}
$publicListeners = @(Get-NetTCPConnection -State Listen -LocalPort 80, 443 `
    -ErrorAction Stop)
if (@($publicListeners | Where-Object {
            [int]$_.OwningProcess -notin $approvedProxyPids
        }).Count -ne 0) {
    throw 'Public ingress ports 80 and 443 are owned outside the managed proxy process tree'
}

function Assert-ExactAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$ExactReaders,
        [Parameter(Mandatory)][string[]]$AllowedWriters,
        [string[]]$RequiredReadAndExecuteSids = @(),
        [string]$RequiredModifySid = '',
        [switch]$RequireProtected
    )
    if (-not (Test-Path -LiteralPath $Path) -or
            ((Get-Item -LiteralPath $Path -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw "Proxy ACL target is missing or is a reparse point: $Path"
    }
    $acl = Get-Acl -LiteralPath $Path
    if ($RequireProtected -and -not $acl.AreAccessRulesProtected) {
        throw "Proxy ACL inheritance is enabled on protected target: $Path"
    }
    $ownerSid = try {
        (New-Object Security.Principal.NTAccount($acl.Owner)).Translate(
            [Security.Principal.SecurityIdentifier]
        ).Value
    }
    catch { '' }
    if ($ownerSid -notin $ExactReaders) {
        throw "Proxy ACL target has an unapproved owner: $Path"
    }
    $writeMask = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $rules = @($acl.GetAccessRules(
            $true, $true, [Security.Principal.SecurityIdentifier]
    ))
    $rightsBySid = @{}
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Value
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Deny) {
            if ($sid -in $RequiredReadAndExecuteSids -or $sid -eq $RequiredModifySid) {
                throw "Required proxy reader has an explicit deny ACE: $Path"
            }
            continue
        }
        if ($sid -notin $ExactReaders) {
            throw "Unexpected reader $sid on isolated proxy target: $Path"
        }
        if (-not $rightsBySid.ContainsKey($sid)) { $rightsBySid[$sid] = [int64]0 }
        $rightsBySid[$sid] = [int64]$rightsBySid[$sid] -bor [int64]$rule.FileSystemRights
        $hasWrite = ([int64]$rule.FileSystemRights -band [int64]$writeMask) -ne 0
        if ($hasWrite -and $sid -notin $AllowedWriters) {
            throw "Unexpected writer $sid on isolated proxy target: $Path"
        }
    }
    $requiredModifyFound = [string]::IsNullOrWhiteSpace($RequiredModifySid) -or
        ($rightsBySid.ContainsKey($RequiredModifySid) -and
            (([int64]$rightsBySid[$RequiredModifySid] -band
                    [int64][Security.AccessControl.FileSystemRights]::Modify) -eq
                [int64][Security.AccessControl.FileSystemRights]::Modify))
    $requiredReadAndExecuteFound = $true
    foreach ($requiredSid in $RequiredReadAndExecuteSids) {
        if (-not $rightsBySid.ContainsKey($requiredSid) -or
                ([int64]$rightsBySid[$requiredSid] -band
                    [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) -ne
                    [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) {
            $requiredReadAndExecuteFound = $false
        }
    }
    if (-not $requiredReadAndExecuteFound) {
        throw "Required proxy reader lacks ReadAndExecute: $Path"
    }
    if (-not $requiredModifyFound) {
        throw "Proxy identity lacks required Modify access: $Path"
    }
}

$adminSid = 'S-1-5-32-544'
$systemSid = 'S-1-5-18'
$proxySid = (New-Object Security.Principal.NTAccount($proxyAccount)).Translate(
    [Security.Principal.SecurityIdentifier]
).Value
$backendSid = (New-Object Security.Principal.NTAccount($backendAccount)).Translate(
    [Security.Principal.SecurityIdentifier]
).Value
$administrativeWriters = @($adminSid, $systemSid)
$proxyOnlyReaders = @($adminSid, $systemSid, $proxySid)
foreach ($readOnlyPath in @($serviceRoot, $proxyRoot, $proxyConfigRoot)) {
    Assert-ExactAcl -Path $readOnlyPath -ExactReaders $proxyOnlyReaders `
        -AllowedWriters $administrativeWriters `
        -RequiredReadAndExecuteSids @($proxySid) -RequireProtected
}
foreach ($readOnlyFile in @($wrapperPath, $caddyPath, $xmlPath, $caddyfilePath)) {
    Assert-ExactAcl -Path $readOnlyFile -ExactReaders $proxyOnlyReaders `
        -AllowedWriters $administrativeWriters `
        -RequiredReadAndExecuteSids @($proxySid) -RequireProtected
}
Assert-ExactAcl -Path $releasesRoot `
    -ExactReaders @($adminSid, $systemSid, $backendSid, $proxySid) `
    -AllowedWriters $administrativeWriters `
    -RequiredReadAndExecuteSids @($backendSid, $proxySid) -RequireProtected
Assert-ExactAcl -Path $proxyDataRoot -ExactReaders $proxyOnlyReaders `
    -AllowedWriters @($adminSid, $systemSid, $proxySid) `
    -RequiredModifySid $proxySid -RequireProtected
Assert-ExactAcl -Path $proxyLogRoot -ExactReaders $proxyOnlyReaders `
    -AllowedWriters @($adminSid, $systemSid, $proxySid) `
    -RequiredModifySid $proxySid -RequireProtected
foreach ($tlsStateItem in @(Get-ChildItem -LiteralPath $proxyDataRoot -Force -Recurse)) {
    Assert-ExactAcl -Path $tlsStateItem.FullName -ExactReaders $proxyOnlyReaders `
        -AllowedWriters @($adminSid, $systemSid, $proxySid) -RequiredModifySid $proxySid
}
foreach ($releaseDirectory in @(Get-ChildItem -LiteralPath $releasesRoot -Directory -Force)) {
    $releaseAcl = & (Join-Path $PSScriptRoot 'Protect-LeanTpmReleaseDirectory.ps1') `
        -InstallRoot $install -DataRoot $data -ReleaseId $releaseDirectory.Name `
        -VerifyOnly -OutputFormat Json | ConvertFrom-Json
    if ([string]$releaseAcl.status -cne 'PASS') {
        throw "Immutable release ACL verification failed: $($releaseDirectory.Name)"
    }
}

$expectedServiceSddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
$sddlOutput = @(& sc.exe sdshow $serviceId 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'sc.exe sdshow failed for LeanTPM.Proxy' }
$actualSddl = [string]($sddlOutput | Where-Object {
        ([string]$_).Trim() -match '^[OGDS]:'
    } | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($actualSddl) -or
        $actualSddl.Trim() -cne $expectedServiceSddl) {
    throw 'LeanTPM.Proxy SCM DACL differs from the fixed least-privilege SDDL'
}

$report = [pscustomobject]@{
    status = 'PASS'
    serviceId = $serviceId
    state = [string]$service.State
    publicHost = $publicHost
    proxyServiceAccount = $proxyAccount
    backendServiceAccount = $backendAccount
    caddySha256 = $caddyPin
    wrapperSha256 = $wrapperPin
    serviceSddl = $expectedServiceSddl
    hostLayoutSha256 = if ($isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
