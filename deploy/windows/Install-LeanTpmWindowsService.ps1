[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$WrapperPath,
    [Parameter(Mandatory)][string]$ExpectedWrapperSha256,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [string]$JavaExecutable = 'C:\Program Files\Java\jdk-21\bin\java.exe',
    [string]$ServiceAccount = 'NT AUTHORITY\LocalService',
    [string]$SignedStarterPath = '',
    [switch]$AllowUnpinnedTestWrapper,
    [switch]$AllowNonProductionRoots,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Backend'
$resolvedWrapper = (Resolve-Path -LiteralPath $WrapperPath).Path
$resolvedInstall = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$resolvedData = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoots) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $resolvedInstall -DataRoot $resolvedData `
    -EnvironmentKind $environmentKind -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionRoots) {
    throw 'AllowNonProductionRoots cannot be used with the production root pair'
}
$toolchainLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$pinnedWrapperSha256 = [string]$toolchainLock.winSW.sha256
if ($PlanOnly -and $AllowUnpinnedTestWrapper) {
    $pinnedWrapperSha256 = $ExpectedWrapperSha256
}
if ($pinnedWrapperSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
    throw 'WinSW is not pinned in release/toolchain-lock.json'
}
if ($ExpectedWrapperSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
        -not $ExpectedWrapperSha256.Equals(
            $pinnedWrapperSha256,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'ExpectedWrapperSha256 must be a 64-character SHA-256 value'
}
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedWrapper).Hash
if (-not $actualHash.Equals($ExpectedWrapperSha256, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'WinSW wrapper SHA-256 does not match the pinned value'
}
if (-not $PlanOnly -and $ServiceAccount -eq 'NT AUTHORITY\LocalService') {
    throw 'LocalService is not permitted outside PlanOnly; use an approved isolated gMSA account'
}
if ($ServiceAccount -ne 'NT AUTHORITY\LocalService' -and
        $ServiceAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$') {
    throw 'ServiceAccount must be an approved gMSA account'
}

$serviceRoot = Join-Path $resolvedInstall 'service'
$targetWrapper = Join-Path $serviceRoot "$serviceId.exe"
$targetConfig = Join-Path $serviceRoot "$serviceId.xml"
$starterSource = Join-Path $PSScriptRoot 'Start-LeanTpmBackend.ps1'
$template = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'LeanTPM.Backend.xml.template') `
    -Encoding utf8 -Raw
$renderedConfig = $template.Replace('@SERVICE_ROOT@', $serviceRoot).
    Replace('@INSTALL_ROOT@', $resolvedInstall).
    Replace('@DATA_ROOT@', $resolvedData).
    Replace('@JAVA_EXECUTABLE@', $JavaExecutable).
    Replace('@SERVICE_ACCOUNT@', $ServiceAccount)

$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    serviceId = $serviceId
    account = $ServiceAccount
    wrapperSha256 = $actualHash.ToLowerInvariant()
    serviceRoot = $serviceRoot
    dataRoot = $resolvedData
    hostLayoutSha256 = if ($isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
    actions = @('VERIFY_WRAPPER', 'CREATE_SERVICE_DIRECTORY', 'COPY_WRAPPER', 'RENDER_CONFIG',
        'SET_ACL', 'INSTALL_SERVICE', 'QUERY_SERVICE')
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}

$lockPath = Join-Path $resolvedData 'locks\deployment.lock'
if (-not (Test-Path -LiteralPath (Split-Path -Parent $lockPath) -PathType Container)) {
    throw 'Host mutation lock directory must be initialized before Windows Service installation'
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
        -InstallRoot $resolvedInstall -DataRoot $resolvedData `
        -EnvironmentKind $environmentKind `
        -AllowNonProductionCustomRoots:$AllowNonProductionRoots `
        -OutputFormat Json | ConvertFrom-Json
    if ([bool]$lockedPolicy.isProductionRootPair -ne $isProductionRootPair -or
            [string]$lockedPolicy.installRoot -cne [string]$rootPolicy.installRoot -or
            [string]$lockedPolicy.dataRoot -cne [string]$rootPolicy.dataRoot -or
            [string]$lockedPolicy.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256) {
        throw 'Host layout changed after acquiring the global mutation lock'
    }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator privileges are required to install the Windows Service'
}
$trustConfigPath = Join-Path $resolvedData 'config\release-trust.json'
if (-not (Test-Path -LiteralPath $trustConfigPath -PathType Leaf)) {
    throw 'Host-owned release-trust.json is required before Windows Service installation'
}
$trust = Get-Content -LiteralPath $trustConfigPath -Encoding utf8 -Raw | ConvertFrom-Json
$TrustedScriptSignerThumbprint = [string]$trust.scriptSignerThumbprint
if ([string]$trust.backendServiceAccount -cne $ServiceAccount) {
    throw 'ServiceAccount must match the host-owned Backend service identity'
}
if (-not ([string]$trust.winSWSha256).Equals(
        $pinnedWrapperSha256, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host-owned WinSW digest differs from release/toolchain-lock.json'
}
if ($TrustedScriptSignerThumbprint -notmatch '^[0-9A-Fa-f]{40,128}$') {
    throw 'Host-owned trust configuration must pin the AllSigned starter signer'
}
if ([string]::IsNullOrWhiteSpace($SignedStarterPath)) {
    throw 'SignedStarterPath is required for the AllSigned Windows Service entry point'
}
$resolvedSignedStarter = (Resolve-Path -LiteralPath $SignedStarterPath).Path
$starterSignature = Get-AuthenticodeSignature -LiteralPath $resolvedSignedStarter
if ($starterSignature.Status -ne 'Valid' -or $null -eq $starterSignature.SignerCertificate -or
        -not $starterSignature.SignerCertificate.Thumbprint.Equals(
            $TrustedScriptSignerThumbprint,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Windows Service starter is not signed by the trusted script signer'
}
$pinnedJavaSha256 = [string]$toolchainLock.java.sha256
$resolvedJava = (Resolve-Path -LiteralPath $JavaExecutable).Path
if ($pinnedJavaSha256 -notmatch '^[0-9a-f]{64}$' -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedJava).Hash.Equals(
            $pinnedJavaSha256,
            [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Java executable SHA256 is not pinned or does not match release/toolchain-lock.json'
}
if (-not ([string]$trust.javaSha256).Equals(
        $pinnedJavaSha256, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host-owned Java digest differs from release/toolchain-lock.json'
}
$renderedConfig = $template.Replace('@SERVICE_ROOT@', $serviceRoot).
    Replace('@INSTALL_ROOT@', $resolvedInstall).
    Replace('@DATA_ROOT@', $resolvedData).
    Replace('@JAVA_EXECUTABLE@', $resolvedJava).
    Replace('@SERVICE_ACCOUNT@', $ServiceAccount)

$targetStarter = Join-Path $serviceRoot 'Start-LeanTpmBackend.ps1'
$serviceBinding = Get-CimInstance -ClassName Win32_Service `
    -Filter "Name='$serviceId'" -ErrorAction Stop
if ($null -ne $serviceBinding) {
    $bindingReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmWindowsServiceBinding.ps1') `
        -InstallRoot $resolvedInstall -DataRoot $resolvedData -OutputFormat Json |
        ConvertFrom-Json
    if ([string]$bindingReport.status -cne 'PASS') {
        throw 'Existing Windows Service supply-chain or ACL binding is not healthy'
    }
    $actualImagePath = ([string]$serviceBinding.PathName).Trim().Trim('"')
    $expectedConfigBytes = (New-Object Text.UTF8Encoding($false)).GetBytes($renderedConfig)
    $configHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $expectedConfigHash = [BitConverter]::ToString(
            $configHasher.ComputeHash($expectedConfigBytes)
        ).Replace('-', '')
    }
    finally { $configHasher.Dispose() }
    $installedStarterSignature = if (Test-Path -LiteralPath $targetStarter -PathType Leaf) {
        Get-AuthenticodeSignature -LiteralPath $targetStarter
    }
    else { $null }
    $installationMatches = (
        $actualImagePath.Equals($targetWrapper, [StringComparison]::OrdinalIgnoreCase) -and
        [string]$serviceBinding.StartName -ceq $ServiceAccount -and
        [string]$serviceBinding.StartMode -in @('Auto', 'Automatic') -and
        (Test-Path -LiteralPath $targetWrapper -PathType Leaf) -and
        (Get-FileHash -Algorithm SHA256 -LiteralPath $targetWrapper).Hash.Equals(
            $pinnedWrapperSha256, [StringComparison]::OrdinalIgnoreCase
        ) -and
        $null -ne $installedStarterSignature -and
        $installedStarterSignature.Status -eq 'Valid' -and
        $null -ne $installedStarterSignature.SignerCertificate -and
        $installedStarterSignature.SignerCertificate.Thumbprint.Equals(
            $TrustedScriptSignerThumbprint, [StringComparison]::OrdinalIgnoreCase
        ) -and
        (Test-Path -LiteralPath $targetConfig -PathType Leaf) -and
        (Get-FileHash -Algorithm SHA256 -LiteralPath $targetConfig).Hash.Equals(
            $expectedConfigHash, [StringComparison]::OrdinalIgnoreCase
        )
    )
    if (-not $installationMatches) {
        throw 'Windows Service installation drift detected; refuse to overwrite the existing service'
    }
    $report.status = 'ALREADY_INSTALLED'
    $report | Add-Member -NotePropertyName serviceStatus `
        -NotePropertyValue ([string]$serviceBinding.State)
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}

if ($PSCmdlet.ShouldProcess($serviceId, 'Install pinned WinSW service')) {
    $null = New-Item -ItemType Directory -Path $serviceRoot -Force
    $releasesDirectory = Join-Path $resolvedInstall 'releases'
    $stagingDirectory = Join-Path $resolvedData 'staging'
    $pointersDirectory = Join-Path $resolvedData 'pointers'
    $configDirectory = Join-Path $resolvedData 'config'
    $secretsDirectory = Join-Path $resolvedData 'secrets'
    $uploadsDirectory = Join-Path $resolvedData 'data\uploads'
    $logsDirectory = Join-Path $resolvedData 'logs'
    $auditDirectory = Join-Path $resolvedData 'audit'
    $backupsDirectory = Join-Path $resolvedData 'backups'
    $locksDirectory = Join-Path $resolvedData 'locks'
    $stateDirectory = Join-Path $resolvedData 'state'
    foreach ($directory in @(
            $releasesDirectory,
            (Join-Path $resolvedData 'staging'),
            $pointersDirectory,
            $configDirectory,
            $secretsDirectory,
            $uploadsDirectory,
            $logsDirectory,
            $auditDirectory,
            $backupsDirectory,
            $locksDirectory,
            $stateDirectory
        )) {
        $null = New-Item -ItemType Directory -Path $directory -Force
    }

    & icacls.exe $serviceRoot '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
        "$ServiceAccount`:(OI)(CI)RX"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the service directory ACL' }
    & icacls.exe $releasesDirectory '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
        "$ServiceAccount`:(OI)(CI)RX"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the releases directory ACL' }
    & icacls.exe $resolvedData '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
        "$ServiceAccount`:RX"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to harden the data root ACL' }
    foreach ($readDirectory in @(
            $pointersDirectory, $configDirectory, $secretsDirectory, $stateDirectory
        )) {
        & icacls.exe $readDirectory '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
            "$ServiceAccount`:(OI)(CI)RX"
        if ($LASTEXITCODE -ne 0) { throw "Failed to set read-only ACL: $readDirectory" }
    }
    foreach ($writeDirectory in @($uploadsDirectory, $logsDirectory)) {
        & icacls.exe $writeDirectory '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
            "$ServiceAccount`:(OI)(CI)M"
        if ($LASTEXITCODE -ne 0) { throw "Failed to set runtime write ACL: $writeDirectory" }
    }
    foreach ($adminDirectory in @(
            $stagingDirectory, $auditDirectory, $backupsDirectory, $locksDirectory
        )) {
        & icacls.exe $adminDirectory '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F'
        if ($LASTEXITCODE -ne 0) { throw "Failed to protect control directory: $adminDirectory" }
    }
    foreach ($ownedDirectory in @(
            $serviceRoot, $releasesDirectory, $resolvedData, $pointersDirectory,
            $configDirectory, $secretsDirectory, $uploadsDirectory, $logsDirectory,
            $auditDirectory, $backupsDirectory, $locksDirectory, $stateDirectory
        )) {
        & icacls.exe $ownedDirectory '/setowner' 'Administrators' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to set protected directory owner: $ownedDirectory" }
    }
    Copy-Item -LiteralPath $resolvedWrapper -Destination $targetWrapper
    Copy-Item -LiteralPath $resolvedSignedStarter -Destination $targetStarter
    [System.IO.File]::WriteAllText(
        $targetConfig,
        $renderedConfig,
        (New-Object System.Text.UTF8Encoding($false))
    )
    foreach ($protectedFile in @($targetWrapper, $targetStarter, $targetConfig, $trustConfigPath)) {
        & icacls.exe $protectedFile '/inheritance:r' '/grant:r' `
            'Administrators:F' "$ServiceAccount`:RX" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to harden service supply-chain file: $protectedFile" }
        & icacls.exe $protectedFile '/setowner' 'Administrators' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to set protected file owner: $protectedFile" }
    }

    $targetWrapperHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetWrapper).Hash
    if (-not $targetWrapperHash.Equals($pinnedWrapperSha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Installed WinSW wrapper changed after copying into the hardened directory'
    }
    $targetStarterSignature = Get-AuthenticodeSignature -LiteralPath $targetStarter
    if ($targetStarterSignature.Status -ne 'Valid' -or
            -not $targetStarterSignature.SignerCertificate.Thumbprint.Equals(
                $TrustedScriptSignerThumbprint,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Installed Windows Service starter signature changed after copying'
    }

    & $targetWrapper install
    if ($LASTEXITCODE -ne 0) { throw 'WinSW service installation failed' }
    $expectedServiceSddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
    & sc.exe sdset $serviceId $expectedServiceSddl | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the fixed LeanTPM.Backend SCM DACL' }
    $bindingReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmWindowsServiceBinding.ps1') `
        -InstallRoot $resolvedInstall -DataRoot $resolvedData -OutputFormat Json |
        ConvertFrom-Json
    if ([string]$bindingReport.status -cne 'PASS') {
        throw 'Installed Windows Service failed the final supply-chain and ACL binding check'
    }
    $service = Get-Service -Name $serviceId -ErrorAction Stop
    $report.status = 'INSTALLED'
    $report | Add-Member -NotePropertyName serviceStatus -NotePropertyValue $service.Status.ToString()
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
}
finally { $ownedLock.Dispose() }
