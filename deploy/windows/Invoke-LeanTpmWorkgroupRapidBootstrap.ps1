[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$KitRoot,
    [string]$ObservationPath = '',
    [switch]$PlanOnly,
    [ValidatePattern('^(?:|[a-f0-9]{64})$')][string]$ExpectedPlanSha256 = '',
    [switch]$ConfirmBootstrap,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixedInstallRoot = 'D:\LeanTPM\App'
$fixedDataRoot = 'D:\LeanTPM\Runtime'
$fixedBootstrapRoot = 'C:\ProgramData\LeanTPM-bootstrap'
$fixedHostLayoutPath = Join-Path $fixedBootstrapRoot 'host-layout.json'
$fixedTrustPath = Join-Path $fixedDataRoot 'config\release-trust.json'
$fixedCaddyPolicyPath = Join-Path $fixedDataRoot `
    'config\external-caddy-binding.json'
$fixedDbSecretPath = Join-Path $fixedDataRoot 'secrets\db-password.bin'
$fixedBackendStarterPath = Join-Path $fixedInstallRoot `
    'service\Start-LeanTpmBackend-Rapid.ps1'
$fixedCaddyConfigPath = 'D:\LeanTPM\shared\config\Caddyfile'
$serviceIds = @('LeanTPM.OpsControl', 'LeanTPM.ReleaseAgent')
$actions = @(
    'IMPORT_TWO_AUTOMATED_PUBLIC_CERTIFICATES',
    'INSTALL_TWO_FIXED_LOOPBACK_SERVICES',
    'WRITE_RAPID_HOST_BINDING',
    'START_AND_VERIFY_CONTROL_PLANE'
)
$strictUtf8 = New-Object Text.UTF8Encoding($false, $true)

function Get-BytesSha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).
        Hash.ToLowerInvariant()
}

function Get-FixedDirectory {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$Label must be absolute" }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if (-not $item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse directory"
    }
    return $item.FullName.TrimEnd('\', '/')
}

function Get-ContainedFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$Label
    )

    if ($RelativePath -notmatch '^[A-Za-z0-9._/-]+$' -or
            $RelativePath.Contains('..') -or
            [IO.Path]::IsPathRooted($RelativePath)) {
        throw "$Label has an unsafe relative path"
    }
    $candidate = [IO.Path]::GetFullPath((
            Join-Path $Root $RelativePath.Replace('/', '\')
        ))
    if (-not $candidate.StartsWith(
            $Root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Label escaped the kit root"
    }
    $item = Get-Item -LiteralPath $candidate -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a regular non-reparse file"
    }
    return $item.FullName
}

function Read-StrictJsonFile {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
            $item.Length -lt 2 -or $item.Length -gt 8MB) {
        throw "$Label must be a bounded regular non-reparse file"
    }
    try {
        $bytes = [IO.File]::ReadAllBytes($item.FullName)
        return [pscustomobject]@{
            path = $item.FullName
            bytes = $bytes
            sha256 = Get-BytesSha256 $bytes
            value = $strictUtf8.GetString($bytes) | ConvertFrom-Json -ErrorAction Stop
        }
    }
    catch { throw "$Label must be strict UTF-8 JSON" }
}

function Assert-ExactProperties {
    param($Value, [string[]]$Expected, [string]$Label)

    if ($null -eq $Value) { throw "$Label is missing" }
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

function Get-LiveObservation {
    $computerSystem = Get-CimInstance -ClassName Win32_ComputerSystem `
        -ErrorAction Stop
    $computerProduct = Get-CimInstance -ClassName Win32_ComputerSystemProduct `
        -ErrorAction Stop
    $volume = Get-CimInstance -ClassName Win32_Volume `
        -Filter "DriveLetter='D:'" -ErrorAction Stop
    $machineGuid = [string](Get-ItemProperty `
        -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' `
        -Name MachineGuid -ErrorAction Stop).MachineGuid
    $backend = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
    $caddy = Get-CimInstance -ClassName Win32_Service `
        -Filter "Name='caddy'" -ErrorAction Stop
    $caddyImage = 'D:\LeanTPM\tools\caddy\caddy.exe'
    $caddyConfig = 'D:\LeanTPM\shared\config\Caddyfile'
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { [int]$_.LocalPort -in @(80, 443, 18080) } |
        ForEach-Object {
            [pscustomobject]@{
                localAddress = [string]$_.LocalAddress
                port = [int]$_.LocalPort
                owningProcess = [int]$_.OwningProcess
            }
        })
    $missingTargets = @(
        $fixedBootstrapRoot,
        $fixedTrustPath,
        $fixedCaddyPolicyPath,
        (Join-Path $fixedInstallRoot 'ops-services'),
        (Join-Path $fixedDataRoot 'ops-control-plane'),
        (Join-Path $fixedDataRoot 'release-agent')
    )
    $opsMissing = $true
    foreach ($serviceId in $serviceIds) {
        if ($null -ne (Get-CimInstance -ClassName Win32_Service `
                    -Filter "Name='$serviceId'" -ErrorAction SilentlyContinue)) {
            $opsMissing = $false
        }
    }
    $pointerPath = Join-Path $fixedDataRoot 'pointers\current-release.json'
    $releaseId = ''
    $packageSha256 = ''
    if (Test-Path -LiteralPath $pointerPath -PathType Leaf) {
        $pointer = Read-StrictJsonFile $pointerPath 'current release pointer'
        $releaseId = [string]$pointer.value.releaseId
        $packageSha256 = [string]$pointer.value.packageSha256
    }
    else {
        $releaseMatch = $null
        if (Test-Path -LiteralPath $fixedBackendStarterPath -PathType Leaf) {
            $starterText = [IO.File]::ReadAllText(
                $fixedBackendStarterPath,
                $strictUtf8
            )
            $releaseMatch = [regex]::Match(
                $starterText,
                "D:\\LeanTPM\\App\\releases\\(?<release>[0-9A-Za-z][0-9A-Za-z._-]{2,127})\\payload\\backend\\leantpm-backend\.jar",
                [Text.RegularExpressions.RegexOptions]::CultureInvariant
            )
        }
        if ($null -ne $releaseMatch -and $releaseMatch.Success) {
            $releaseId = $releaseMatch.Groups['release'].Value
            $currentJar = Join-Path $fixedInstallRoot (
                'releases\' + $releaseId +
                '\payload\backend\leantpm-backend.jar'
            )
            if (-not (Test-Path -LiteralPath $currentJar -PathType Leaf)) {
                throw 'Fixed Backend starter references a missing release JAR'
            }
        }
        else {
            $currentJar = Join-Path $fixedInstallRoot `
                'current\payload\backend\leantpm-backend.jar'
            if (-not (Test-Path -LiteralPath $currentJar -PathType Leaf)) {
                throw 'Current release pointer and fixed Backend release path are both missing'
            }
        }
        $packageSha256 = Get-FileSha256 $currentJar
        if ([string]::IsNullOrWhiteSpace($releaseId)) {
            $releaseId = 'legacy-' + $packageSha256.Substring(0, 16)
        }
    }
    return [pscustomobject][ordered]@{
        schemaVersion = 1
        computerName = [string]$computerSystem.Name
        partOfDomain = [bool]$computerSystem.PartOfDomain
        domain = [string]$computerSystem.Domain
        machineGuid = $machineGuid
        smbiosUuid = [string]$computerProduct.UUID
        volumeDeviceId = [string]$volume.DeviceID
        volumeFileSystem = [string]$volume.FileSystem
        backend = [pscustomobject][ordered]@{
            state = [string]$backend.State
            startMode = [string]$backend.StartMode
            startName = [string]$backend.StartName
            processId = [int]$backend.ProcessId
            pathName = [string]$backend.PathName
        }
        caddy = [pscustomobject][ordered]@{
            state = [string]$caddy.State
            startMode = [string]$caddy.StartMode
            startName = [string]$caddy.StartName
            processId = [int]$caddy.ProcessId
            pathName = [string]$caddy.PathName
            imageSha256 = Get-FileSha256 $caddyImage
            configSha256 = Get-FileSha256 $caddyConfig
        }
        listeners = $listeners
        bootstrapTargetsMissing = @($missingTargets | Where-Object {
                Test-Path -LiteralPath $_
            }).Count -eq 0
        opsServicesMissing = $opsMissing
        currentReleaseId = $releaseId
        currentPackageSha256 = $packageSha256
    }
}

function Assert-Observation {
    param($Observation, $Manifest)

    Assert-ExactProperties $Observation @(
        'schemaVersion', 'computerName', 'partOfDomain', 'domain', 'machineGuid',
        'smbiosUuid', 'volumeDeviceId', 'volumeFileSystem', 'backend', 'caddy',
        'listeners', 'bootstrapTargetsMissing', 'opsServicesMissing',
        'currentReleaseId', 'currentPackageSha256'
    ) 'host observation'
    Assert-ExactProperties $Observation.backend @(
        'state', 'startMode', 'startName', 'processId', 'pathName'
    ) 'backend observation'
    Assert-ExactProperties $Observation.caddy @(
        'state', 'startMode', 'startName', 'processId', 'pathName',
        'imageSha256', 'configSha256'
    ) 'Caddy observation'
    if ([int]$Observation.schemaVersion -ne 1 -or
            [string]$Observation.computerName -cne
                [string]$Manifest.expectedComputerName -or
            [bool]$Observation.partOfDomain -or
            [string]$Observation.domain -cne 'WORKGROUP' -or
            -not [bool]$Observation.bootstrapTargetsMissing -or
            -not [bool]$Observation.opsServicesMissing) {
        throw 'Host is not the expected empty WORKGROUP bootstrap target'
    }
    if ([string]$Observation.backend.state -cne 'Running' -or
            [string]$Observation.backend.startMode -cne 'Auto' -or
            [string]$Observation.backend.startName -cne
                'NT AUTHORITY\NetworkService' -or
            [int]$Observation.backend.processId -le 0 -or
            -not ([string]$Observation.backend.pathName).Trim().Trim('"').Equals(
                'D:\LeanTPM\App\service\LeanTPM.Backend.exe',
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'Backend does not match the existing production service'
    }
    $expectedCaddyCommand = 'D:\LeanTPM\tools\caddy\caddy.exe run --environ ' +
        '--config D:\LeanTPM\shared\config\Caddyfile --adapter caddyfile'
    if ([string]$Observation.caddy.state -cne 'Running' -or
            [string]$Observation.caddy.startMode -cne 'Auto' -or
            [string]$Observation.caddy.startName -cnotin
                @('LocalSystem', 'NT AUTHORITY\SYSTEM') -or
            [int]$Observation.caddy.processId -le 0 -or
            -not ([string]$Observation.caddy.pathName).Equals(
                $expectedCaddyCommand, [StringComparison]::OrdinalIgnoreCase
            ) -or
            [string]$Observation.caddy.imageSha256 -cne
                [string]$Manifest.caddySha256) {
        throw 'Caddy does not match the fixed existing WORKGROUP binding'
    }
    foreach ($port in @(80, 443)) {
        $portListeners = @($Observation.listeners | Where-Object {
                [int]$_.port -eq $port
            })
        if ($portListeners.Count -lt 1 -or @($portListeners | Where-Object {
                    [int]$_.owningProcess -ne [int]$Observation.caddy.processId
                }).Count -gt 0) {
            throw "Port $port is not owned by the current Caddy process"
        }
    }
    $backendListeners = @($Observation.listeners | Where-Object {
            [int]$_.port -eq 18080
        })
    if ($backendListeners.Count -ne 1 -or
            [string]$backendListeners[0].localAddress -ne '127.0.0.1') {
        throw 'Backend 18080 must have one loopback listener'
    }
    if ([string]$Observation.machineGuid -notmatch '^[A-Za-z0-9-]{16,64}$' -or
            [string]$Observation.smbiosUuid -notmatch '^[A-Za-z0-9-]{16,64}$' -or
            [string]$Observation.volumeFileSystem -cne 'NTFS' -or
            [string]$Observation.currentReleaseId -notmatch
                '^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$' -or
            [string]$Observation.currentPackageSha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Host identity, volume or current release observation is invalid'
    }
}

function Get-RenderedState {
    param($Manifest, $Observation, [string]$ConfigTemplate, [string]$TrustTemplate)

    $hostId = Get-BytesSha256 ([Text.Encoding]::UTF8.GetBytes((
                [string]$Observation.machineGuid + "`n" +
                [string]$Observation.smbiosUuid
            )))
    $volumeIdentity = 'sha256:' + (Get-BytesSha256 (
            [Text.Encoding]::UTF8.GetBytes((
                    [string]$Observation.volumeDeviceId + "`n" +
                    [string]$Observation.volumeFileSystem
                ))
        ))
    $caddyPolicy = [ordered]@{
        schemaVersion = 1
        bootstrapMode = 'WORKGROUP_RAPID'
        readiness = 'READY'
        serviceId = 'caddy'
        serviceAccount = [string]$Observation.caddy.startName
        serviceImagePath = 'D:\LeanTPM\tools\caddy\caddy.exe'
        serviceImageSha256 = [string]$Observation.caddy.imageSha256
        serviceCommandLine = [string]$Observation.caddy.pathName
        configPath = 'D:\LeanTPM\shared\config\Caddyfile'
        configSha256 = [string]$Observation.caddy.configSha256
        listenPorts = @(80, 443)
        backendUpstream = 'http://127.0.0.1:18080'
    }
    $caddyPolicyBytes = $strictUtf8.GetBytes((
            $caddyPolicy | ConvertTo-Json -Depth 5 -Compress
        ))
    $caddyPolicySha256 = Get-BytesSha256 $caddyPolicyBytes
    $hostLayout = [ordered]@{
        schemaVersion = 1
        readiness = 'READY'
        environmentKind = 'PRODUCTION'
        environmentId = 'leantpm-production-cn'
        hostId = $hostId
        installRoot = $fixedInstallRoot
        dataRoot = $fixedDataRoot
        volumeIdentity = $volumeIdentity
        proxy = [ordered]@{
            mode = 'EXTERNAL_EXISTING'
            serviceId = 'caddy'
            bindingPolicyPath = $fixedCaddyPolicyPath
            bindingPolicySha256 = $caddyPolicySha256
        }
    }
    $hostLayoutBytes = $strictUtf8.GetBytes((
            $hostLayout | ConvertTo-Json -Depth 5 -Compress
        ))
    $hostLayoutSha256 = Get-BytesSha256 $hostLayoutBytes
    $configText = $ConfigTemplate.Replace('@HOST_LAYOUT_SHA256@', $hostLayoutSha256)
    $trustText = $TrustTemplate.Replace('@HOST_ID@', $hostId)
    return [pscustomobject]@{
        hostId = $hostId
        volumeIdentity = $volumeIdentity
        caddyPolicyBytes = $caddyPolicyBytes
        caddyPolicySha256 = $caddyPolicySha256
        hostLayoutBytes = $hostLayoutBytes
        hostLayoutSha256 = $hostLayoutSha256
        configBytes = $strictUtf8.GetBytes($configText)
        configSha256 = Get-BytesSha256 ($strictUtf8.GetBytes($configText))
        trustBytes = $strictUtf8.GetBytes($trustText)
        trustSha256 = Get-BytesSha256 ($strictUtf8.GetBytes($trustText))
    }
}

function Get-AccountSid {
    param([Parameter(Mandatory)][string]$Account)

    $identity = New-Object Security.Principal.NTAccount($Account)
    return $identity.Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
}

function Get-FixedServiceSddl {
    param([Parameter(Mandatory)][string]$ServiceName)

    $output = @(& sc.exe sdshow $ServiceName 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read the fixed $ServiceName service DACL"
    }
    $sddl = [string](@($output | Where-Object {
                ([string]$_).Trim() -match '^D:'
            } | Select-Object -First 1)).Trim()
    if ([string]::IsNullOrWhiteSpace($sddl) -or
            $sddl -notmatch '^D:') {
        throw "The fixed $ServiceName service DACL is invalid"
    }
    return $sddl
}

function Grant-FixedServiceControl {
    param(
        [Parameter(Mandatory)][ValidateSet('LeanTPM.Backend', 'caddy')]
        [string]$ServiceName,
        [Parameter(Mandatory)][string]$AgentSid,
        [Parameter(Mandatory)]$Snapshots
    )

    $sddl = Get-FixedServiceSddl $ServiceName
    $Snapshots.Add([pscustomobject]@{
            serviceName = $ServiceName
            sddl = $sddl
        })
    if ($sddl.Contains(";;;$AgentSid)")) { return }
    # Query configuration/status, enumerate dependents, start, stop,
    # interrogate and read control. Deliberately excludes pause/continue,
    # delete, change-config and user-defined service controls.
    $ace = "(A;;CCLCSWRPWPLOCRRC;;;$AgentSid)"
    $saclIndex = $sddl.IndexOf('S:', [StringComparison]::Ordinal)
    $updated = if ($saclIndex -ge 0) {
        $sddl.Substring(0, $saclIndex) + $ace + $sddl.Substring($saclIndex)
    }
    else { $sddl + $ace }
    & sc.exe sdset $ServiceName $updated | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to grant the fixed Agent control of $ServiceName"
    }
}

function Grant-FixedPathAccess {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Grant,
        [Parameter(Mandatory)]$Snapshots
    )

    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Fixed permission target is a reparse point: $Path"
    }
    $acl = Get-Acl -LiteralPath $item.FullName -ErrorAction Stop
    $Snapshots.Add([pscustomobject]@{
            path = $item.FullName
            sddl = $acl.Sddl
        })
    & icacls.exe $item.FullName '/grant:r' $Grant | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to grant the fixed Agent path access: $Path"
    }
}

function Write-Result {
    param([Parameter(Mandatory)]$Value)

    if ($OutputFormat -eq 'Json') { $Value | ConvertTo-Json -Depth 8 -Compress }
    else { $Value | Format-List }
}

if (-not $PlanOnly -and -not $ConfirmBootstrap) {
    throw 'Run PlanOnly first, then provide ConfirmBootstrap and ExpectedPlanSha256'
}
if ($PlanOnly -and $ConfirmBootstrap) {
    throw 'PlanOnly cannot be combined with ConfirmBootstrap'
}
if (-not $PlanOnly -and [string]::IsNullOrWhiteSpace($ExpectedPlanSha256)) {
    throw 'ExpectedPlanSha256 is required for confirmed bootstrap execution'
}
if (-not [string]::IsNullOrWhiteSpace($ObservationPath) -and -not $PlanOnly) {
    throw 'ObservationPath is allowed only for side-effect-free PlanOnly testing'
}

$root = Get-FixedDirectory $KitRoot 'bootstrap kit root'
$manifestSnapshot = Read-StrictJsonFile `
    (Join-Path $root 'workgroup-rapid-bootstrap.json') 'bootstrap manifest'
$manifest = $manifestSnapshot.value
Assert-ExactProperties $manifest @(
    'schemaVersion', 'bootstrapMode', 'expectedComputerName', 'productVersion',
    'mainCommit', 'javaExecutablePath', 'javaSha256', 'caddySha256',
    'requesterThumbprint', 'approverThumbprint', 'operatorTokenSha256',
    'webConfirmationCount', 'entries'
) 'bootstrap manifest'
if ([int]$manifest.schemaVersion -ne 1 -or
        [string]$manifest.bootstrapMode -cne 'WORKGROUP_RAPID' -or
        [string]$manifest.expectedComputerName -cnotmatch '^[A-Za-z0-9-]{3,63}$' -or
        [string]$manifest.productVersion -cnotmatch
            '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$' -or
        [string]$manifest.mainCommit -cnotmatch '^[a-f0-9]{40}$' -or
        [string]$manifest.javaSha256 -cnotmatch '^[a-f0-9]{64}$' -or
        [string]$manifest.caddySha256 -cnotmatch '^[a-f0-9]{64}$' -or
        [string]$manifest.requesterThumbprint -cnotmatch '^[A-F0-9]{40}$' -or
        [string]$manifest.approverThumbprint -cnotmatch '^[A-F0-9]{40}$' -or
        [string]$manifest.requesterThumbprint -ceq
            [string]$manifest.approverThumbprint -or
        [string]$manifest.operatorTokenSha256 -cnotmatch '^[a-f0-9]{64}$' -or
        [int]$manifest.webConfirmationCount -ne 1) {
    throw 'Bootstrap manifest identity or fixed workflow contract is invalid'
}
$seenEntries = New-Object 'Collections.Generic.HashSet[string]' `
    ([StringComparer]::Ordinal)
$manifestFiles = @{}
foreach ($entry in @($manifest.entries)) {
    Assert-ExactProperties $entry @('path', 'bytes', 'sha256') `
        'bootstrap manifest entry'
    $relative = [string]$entry.path
    if (-not $seenEntries.Add($relative) -or [int64]$entry.bytes -lt 1 -or
            [string]$entry.sha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Bootstrap manifest contains a duplicate or invalid entry'
    }
    $file = Get-ContainedFile $root $relative "bootstrap entry $relative"
    if ((Get-Item -LiteralPath $file).Length -ne [int64]$entry.bytes -or
            (Get-FileSha256 $file) -cne [string]$entry.sha256) {
        throw "Bootstrap entry differs from its manifest: $relative"
    }
    $manifestFiles[$relative] = $file
}
$discoveredFiles = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force |
    ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') } |
    Where-Object { $_ -cne 'workgroup-rapid-bootstrap.json' } | Sort-Object)
if ($discoveredFiles.Count -ne $seenEntries.Count -or
        @($discoveredFiles | Where-Object { -not $seenEntries.Contains($_) }).Count -gt 0) {
    throw 'Bootstrap kit file set differs from the manifest'
}
foreach ($required in @(
        'inputs/ops-control-plane.jar',
        'inputs/WinSW.exe',
        'inputs/Start-LeanTpmOpsControl.ps1',
        'inputs/Start-LeanTpmReleaseAgentService.ps1',
        'inputs/application-production.yml.template',
        'inputs/release-trust.json.template',
        'certificates/requester-public.cer',
        'certificates/approver-public.cer',
        'toolkit/deploy/windows/Install-LeanTpmOpsServices.ps1',
        'toolkit/release/release-agent-toolkit-lock.json',
        'toolkit/scripts/Invoke-LeanTpmWorkgroupRapidDeployment.ps1',
        'Invoke-LeanTpmWorkgroupRapidBootstrap.ps1'
    )) {
    if (-not $seenEntries.Contains($required)) {
        throw "Bootstrap kit is missing required file $required"
    }
}
$javaPath = [IO.Path]::GetFullPath([string]$manifest.javaExecutablePath)
if (-not $PlanOnly -or [string]::IsNullOrWhiteSpace($ObservationPath)) {
    if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf) -or
            (Get-FileSha256 $javaPath) -cne [string]$manifest.javaSha256) {
        throw 'Pinned Java executable is missing or changed'
    }
}

$observation = if ([string]::IsNullOrWhiteSpace($ObservationPath)) {
    Get-LiveObservation
}
else {
    (Read-StrictJsonFile $ObservationPath 'host observation').value
}
Assert-Observation $observation $manifest
$configTemplate = $strictUtf8.GetString([IO.File]::ReadAllBytes(
        $manifestFiles['inputs/application-production.yml.template']
    ))
$trustTemplate = $strictUtf8.GetString([IO.File]::ReadAllBytes(
        $manifestFiles['inputs/release-trust.json.template']
    ))
if (-not $configTemplate.Contains('@HOST_LAYOUT_SHA256@') -or
        -not $trustTemplate.Contains('@HOST_ID@')) {
    throw 'Bootstrap templates are missing their single host bindings'
}
$rendered = Get-RenderedState $manifest $observation $configTemplate $trustTemplate
$planCore = [ordered]@{
    schemaVersion = 1
    bootstrapMode = 'WORKGROUP_RAPID'
    computerName = [string]$observation.computerName
    productVersion = [string]$manifest.productVersion
    mainCommit = [string]$manifest.mainCommit
    manifestSha256 = [string]$manifestSnapshot.sha256
    hostId = [string]$rendered.hostId
    hostLayoutSha256 = [string]$rendered.hostLayoutSha256
    caddyBindingSha256 = [string]$rendered.caddyPolicySha256
    releaseTrustSha256 = [string]$rendered.trustSha256
    opsConfigSha256 = [string]$rendered.configSha256
    currentReleaseId = [string]$observation.currentReleaseId
    currentPackageSha256 = [string]$observation.currentPackageSha256
    serviceIds = $serviceIds
    actions = $actions
}
$planSha256 = Get-BytesSha256 ($strictUtf8.GetBytes((
            $planCore | ConvertTo-Json -Depth 6 -Compress
        )))
$planReport = [pscustomobject][ordered]@{
    status = 'PLAN_READY'
    executable = $false
    bootstrapMode = 'WORKGROUP_RAPID'
    computerName = [string]$observation.computerName
    productVersion = [string]$manifest.productVersion
    mainCommit = [string]$manifest.mainCommit
    currentReleaseId = [string]$observation.currentReleaseId
    planSha256 = $planSha256
    hostLayoutSha256 = [string]$rendered.hostLayoutSha256
    caddyBindingSha256 = [string]$rendered.caddyPolicySha256
    serviceIds = $serviceIds
    webConfirmationCount = 1
    operatorCertificateSteps = 0
    actions = $actions
    productionMutationAuthorized = $false
}
if ($PlanOnly) {
    Write-Result $planReport
    return
}
if ($ExpectedPlanSha256 -cne $planSha256) {
    throw 'ExpectedPlanSha256 differs from the fresh live bootstrap plan'
}
if (-not $PSCmdlet.ShouldProcess(
        $env:COMPUTERNAME,
        'Install fixed WORKGROUP OpsControl and ReleaseAgent services'
    )) {
    return
}

$createdPaths = New-Object 'Collections.Generic.List[string]'
$importedCertificates = New-Object 'Collections.Generic.List[string]'
$aclSnapshots = New-Object 'Collections.Generic.List[object]'
$serviceSddlSnapshots = New-Object 'Collections.Generic.List[object]'
$tempRoot = Join-Path $fixedDataRoot `
    ('temp\ops-bootstrap-' + [Guid]::NewGuid().ToString('N'))
try {
    foreach ($directory in @(
            (Join-Path $fixedDataRoot 'config'),
            (Join-Path $fixedDataRoot 'pointers'),
            (Split-Path -Parent $tempRoot)
        )) {
        if (-not (Test-Path -LiteralPath $directory)) {
            $null = New-Item -ItemType Directory -Path $directory
            $createdPaths.Add($directory)
        }
    }
    $null = New-Item -ItemType Directory -Path $tempRoot
    $createdPaths.Add($tempRoot)
    foreach ($certificateRelative in @(
            'certificates/requester-public.cer',
            'certificates/approver-public.cer'
        )) {
        foreach ($store in @('Cert:\LocalMachine\Root', 'Cert:\LocalMachine\TrustedPublisher')) {
            $imported = Import-Certificate `
                -FilePath $manifestFiles[$certificateRelative] `
                -CertStoreLocation $store
            $importedCertificates.Add($store + '\' + $imported.Thumbprint)
        }
    }
    [IO.File]::WriteAllBytes($fixedTrustPath, $rendered.trustBytes)
    $createdPaths.Add($fixedTrustPath)
    $renderedConfigPath = Join-Path $tempRoot 'application-production.yml'
    [IO.File]::WriteAllBytes($renderedConfigPath, $rendered.configBytes)
    $installer = $manifestFiles[
        'toolkit/deploy/windows/Install-LeanTpmOpsServices.ps1'
    ]
    if ([string]::IsNullOrWhiteSpace($installer)) {
        throw 'Bootstrap toolkit does not contain the Ops service installer'
    }
    $installReport = & $installer `
        -WrapperPath $manifestFiles['inputs/WinSW.exe'] `
        -ExpectedWrapperSha256 (Get-FileSha256 $manifestFiles['inputs/WinSW.exe']) `
        -InstallRoot $fixedInstallRoot -DataRoot $fixedDataRoot `
        -JavaExecutable $javaPath `
        -ExpectedJavaSha256 ([string]$manifest.javaSha256) `
        -OpsControlPlaneJarPath $manifestFiles['inputs/ops-control-plane.jar'] `
        -ExpectedOpsControlPlaneJarSha256 (
            Get-FileSha256 $manifestFiles['inputs/ops-control-plane.jar']
        ) `
        -OpsControlPlaneConfigPath $renderedConfigPath `
        -ExpectedOpsControlPlaneConfigSha256 ([string]$rendered.configSha256) `
        -SignedOpsStarterPath $manifestFiles['inputs/Start-LeanTpmOpsControl.ps1'] `
        -SignedReleaseAgentStarterPath `
            $manifestFiles['inputs/Start-LeanTpmReleaseAgentService.ps1'] `
        -DeploymentToolkitRoot (Join-Path $root 'toolkit') `
        -DeploymentToolkitLockPath `
            $manifestFiles['toolkit/release/release-agent-toolkit-lock.json'] `
        -ExpectedDeploymentToolkitLockSha256 (
            Get-FileSha256 $manifestFiles[
                'toolkit/release/release-agent-toolkit-lock.json'
            ]
        ) `
        -OpsServiceAccount 'NT SERVICE\LeanTPM.OpsControl' `
        -ReleaseAgentServiceAccount 'NT SERVICE\LeanTPM.ReleaseAgent' `
        -BackendServiceAccount 'NT AUTHORITY\NetworkService' `
        -ProxyServiceAccount 'LocalSystem' `
        -ServiceAccountMode WORKGROUP_VIRTUAL `
        -AgentId ('workgroup-' + ([string]$observation.computerName).ToLowerInvariant()) `
        -AgentVersion ([string]$manifest.productVersion) `
        -AllowNonProductionRoots -ConfirmInstallation -Confirm:$false `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$installReport.status -notin @('INSTALLED', 'ALREADY_INSTALLED')) {
        throw 'Ops service installer did not complete'
    }
    [IO.File]::WriteAllBytes($fixedCaddyPolicyPath, $rendered.caddyPolicyBytes)
    $createdPaths.Add($fixedCaddyPolicyPath)
    $null = New-Item -ItemType Directory -Path $fixedBootstrapRoot
    $createdPaths.Add($fixedBootstrapRoot)
    [IO.File]::WriteAllBytes($fixedHostLayoutPath, $rendered.hostLayoutBytes)
    $createdPaths.Add($fixedHostLayoutPath)
    & icacls.exe $fixedBootstrapRoot '/inheritance:r' '/grant:r' `
        'Administrators:(OI)(CI)F' 'SYSTEM:(OI)(CI)F' `
        'NT SERVICE\LeanTPM.OpsControl:(OI)(CI)RX' `
        'NT SERVICE\LeanTPM.ReleaseAgent:(OI)(CI)RX' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to protect the fixed bootstrap root'
    }
    & icacls.exe $fixedHostLayoutPath '/inheritance:r' '/grant:r' `
        'Administrators:F' 'SYSTEM:F' `
        'NT SERVICE\LeanTPM.OpsControl:RX' `
        'NT SERVICE\LeanTPM.ReleaseAgent:RX' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to grant the fixed services host-layout read access'
    }
    & icacls.exe $fixedBootstrapRoot '/setowner' 'Administrators' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to set the fixed bootstrap root owner'
    }
    foreach ($fixedDirectory in @(
            (Join-Path $fixedInstallRoot 'releases'),
            (Join-Path $fixedDataRoot 'backups'),
            (Join-Path $fixedDataRoot 'staging'),
            (Join-Path $fixedDataRoot 'pointers'),
            (Join-Path $fixedDataRoot 'locks'),
            (Join-Path $fixedDataRoot 'audit')
        )) {
        if (-not (Test-Path -LiteralPath $fixedDirectory -PathType Container)) {
            $null = New-Item -ItemType Directory -Path $fixedDirectory
            $createdPaths.Add($fixedDirectory)
        }
        Grant-FixedPathAccess -Path $fixedDirectory `
            -Grant 'NT SERVICE\LeanTPM.ReleaseAgent:(OI)(CI)M' `
            -Snapshots $aclSnapshots
    }
    foreach ($traversalRoot in @(
            $fixedInstallRoot,
            (Join-Path $fixedInstallRoot 'service'),
            'D:\LeanTPM\shared',
            'D:\LeanTPM\shared\config',
            (Join-Path $fixedDataRoot 'secrets')
        )) {
        Grant-FixedPathAccess -Path $traversalRoot `
            -Grant 'NT SERVICE\LeanTPM.ReleaseAgent:RX' `
            -Snapshots $aclSnapshots
    }
    Grant-FixedPathAccess -Path $fixedDbSecretPath `
        -Grant 'NT SERVICE\LeanTPM.ReleaseAgent:R' -Snapshots $aclSnapshots
    Grant-FixedPathAccess -Path $fixedBackendStarterPath `
        -Grant 'NT SERVICE\LeanTPM.ReleaseAgent:M' -Snapshots $aclSnapshots
    Grant-FixedPathAccess -Path $fixedCaddyConfigPath `
        -Grant 'NT SERVICE\LeanTPM.ReleaseAgent:M' -Snapshots $aclSnapshots
    $releaseAgentSid = Get-AccountSid 'NT SERVICE\LeanTPM.ReleaseAgent'
    Grant-FixedServiceControl -ServiceName 'LeanTPM.Backend' `
        -AgentSid $releaseAgentSid -Snapshots $serviceSddlSnapshots
    Grant-FixedServiceControl -ServiceName 'caddy' `
        -AgentSid $releaseAgentSid -Snapshots $serviceSddlSnapshots
    $pointerPath = Join-Path $fixedDataRoot 'pointers\current-release.json'
    if (-not (Test-Path -LiteralPath $pointerPath)) {
        $pointerBytes = $strictUtf8.GetBytes((
                [ordered]@{
                    schemaVersion = 1
                    releaseId = [string]$observation.currentReleaseId
                    packageSha256 = [string]$observation.currentPackageSha256
                } | ConvertTo-Json -Compress
            ))
        [IO.File]::WriteAllBytes($pointerPath, $pointerBytes)
        $createdPaths.Add($pointerPath)
    }
    foreach ($serviceId in $serviceIds) {
        Start-Service -Name $serviceId
        (Get-Service -Name $serviceId).WaitForStatus('Running', [TimeSpan]::FromSeconds(45))
    }
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 1
        $opsListener = @(Get-NetTCPConnection -State Listen -LocalPort 18090 `
                -ErrorAction SilentlyContinue)
    }
    while ($opsListener.Count -ne 1 -and (Get-Date) -lt $deadline)
    if ($opsListener.Count -ne 1 -or
            [string]$opsListener[0].LocalAddress -ne '127.0.0.1') {
        throw 'OpsControl did not create one fixed loopback listener on port 18090'
    }
    Write-Result ([pscustomobject][ordered]@{
            status = 'INSTALLED'
            bootstrapMode = 'WORKGROUP_RAPID'
            planSha256 = $planSha256
            hostLayoutSha256 = [string]$rendered.hostLayoutSha256
            caddyBindingSha256 = [string]$rendered.caddyPolicySha256
            serviceIds = $serviceIds
            opsUrl = 'http://127.0.0.1:18090/'
            webConfirmationCount = 1
        })
}
catch {
    foreach ($serviceId in $serviceIds) {
        Stop-Service -Name $serviceId -Force -ErrorAction SilentlyContinue
        $wrapper = Join-Path $fixedInstallRoot "ops-services\$serviceId.exe"
        if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
            & $wrapper uninstall | Out-Null
        }
    }
    $rollbackPaths = @($createdPaths)
    [array]::Reverse($rollbackPaths)
    foreach ($path in $rollbackPaths) {
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path -Force
            if ($item.PSIsContainer) {
                if (@(Get-ChildItem -LiteralPath $path -Force).Count -eq 0) {
                    Remove-Item -LiteralPath $path -Force
                }
            }
            else { Remove-Item -LiteralPath $path -Force }
        }
    }
    foreach ($certificatePath in $importedCertificates) {
        Remove-Item -LiteralPath $certificatePath -Force -ErrorAction SilentlyContinue
    }
    $serviceRollbacks = @($serviceSddlSnapshots)
    [array]::Reverse($serviceRollbacks)
    foreach ($snapshot in $serviceRollbacks) {
        & sc.exe sdset ([string]$snapshot.serviceName) `
            ([string]$snapshot.sddl) | Out-Null
    }
    $aclRollbacks = @($aclSnapshots)
    [array]::Reverse($aclRollbacks)
    foreach ($snapshot in $aclRollbacks) {
        if (Test-Path -LiteralPath ([string]$snapshot.path)) {
            $acl = Get-Acl -LiteralPath ([string]$snapshot.path)
            $acl.SetSecurityDescriptorSddlForm([string]$snapshot.sddl)
            Set-Acl -LiteralPath ([string]$snapshot.path) -AclObject $acl
        }
    }
    throw
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
