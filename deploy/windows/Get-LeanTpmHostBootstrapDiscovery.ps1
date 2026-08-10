[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EnvironmentId,
    [string]$ObservationPath = '',
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$productionBootstrapRoot = 'C:\ProgramData\LeanTPM-bootstrap'
$installRoot = 'D:\LeanTPM\App'
$dataRoot = 'D:\LeanTPM\Runtime'
$bindingPolicyPath = 'D:\LeanTPM\Runtime\config\external-caddy-binding.json'

function Get-BytesSha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha.ComputeHash($Bytes)).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $sha.Dispose() }
}

function Get-TextSha256 {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)
    return Get-BytesSha256 ([Text.Encoding]::UTF8.GetBytes($Text))
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    if ($null -eq $Value) { throw "$Label is missing" }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) { throw "$Label property count is invalid" }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Assert-NoDuplicateJsonProperties {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Json)
    $stack = [Collections.Generic.List[object]]::new()
    $index = 0
    while ($index -lt $Json.Length) {
        $character = $Json[$index]
        if ([char]::IsWhiteSpace($character)) { $index++; continue }
        if ($character -eq '{') {
            $stack.Add([pscustomobject]@{
                    kind = 'object'
                    expectProperty = $true
                    names = [Collections.Generic.HashSet[string]]::new(
                        [StringComparer]::Ordinal
                    )
                })
            $index++
            continue
        }
        if ($character -eq '[') {
            $stack.Add([pscustomobject]@{
                    kind = 'array'
                    expectProperty = $false
                    names = $null
                })
            $index++
            continue
        }
        if ($character -eq '}' -or $character -eq ']') {
            if ($stack.Count -gt 0) { $stack.RemoveAt($stack.Count - 1) }
            $index++
            continue
        }
        if ($character -eq ',') {
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object') { $context.expectProperty = $true }
            }
            $index++
            continue
        }
        if ($character -eq '"') {
            $start = $index + 1
            $cursor = $start
            $containsEscape = $false
            while ($cursor -lt $Json.Length) {
                if ($Json[$cursor] -eq '\') {
                    $containsEscape = $true
                    $cursor += 2
                    continue
                }
                if ($Json[$cursor] -eq '"') { break }
                $cursor++
            }
            if ($cursor -ge $Json.Length) { throw 'Observation JSON string is unterminated' }
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object' -and $context.expectProperty) {
                    if ($containsEscape) {
                        throw 'Observation JSON property names must be unescaped ASCII literals'
                    }
                    $propertyName = $Json.Substring($start, $cursor - $start)
                    if (-not $context.names.Add($propertyName)) {
                        throw "Observation JSON contains duplicate property $propertyName"
                    }
                    $context.expectProperty = $false
                    $next = $cursor + 1
                    while ($next -lt $Json.Length -and [char]::IsWhiteSpace($Json[$next])) {
                        $next++
                    }
                    if ($next -ge $Json.Length -or $Json[$next] -ne ':') {
                        throw 'Observation JSON property is missing a colon'
                    }
                }
            }
            $index = $cursor + 1
            continue
        }
        $index++
    }
}

function Read-ObservationSnapshot {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw 'ObservationPath must be a regular non-reparse file'
    }
    $stream = [IO.File]::Open(
        $resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read
    )
    try {
        if ($stream.Length -gt 8MB) { throw 'ObservationPath exceeds the 8 MiB limit' }
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    }
    finally { $stream.Dispose() }
    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    $json = $strictUtf8.GetString($bytes)
    Assert-NoDuplicateJsonProperties $json
    $propertyTokens = @([regex]::Matches(
            $json, '"(?<name>(?:\\.|[^"\\])*)"\s*:'
        ))
    foreach ($token in $propertyTokens) {
        if ($token.Groups['name'].Value.Contains('\')) {
            throw 'Observation JSON property names must be unescaped ASCII literals'
        }
    }
    $uniqueProperties = @(
        'schemaVersion', 'collectedAtUtc', 'machineGuid', 'smbiosUuid', 'volume',
        'caddy', 'driveLetter', 'driveType', 'fileSystem', 'deviceId', 'freeBytes',
        'serviceCount', 'managedProxyCount', 'state', 'pid', 'pathName', 'startName',
        'serviceAccountSid', 'startMode', 'scmSddl', 'imagePath', 'imageSha256',
        'configPath', 'configSha256', 'listeners'
    )
    foreach ($name in $uniqueProperties) {
        $count = @($propertyTokens | Where-Object {
                $_.Groups['name'].Value -ceq $name
            }).Count
        if ($count -ne 1) {
            throw "Observation JSON property $name must occur exactly once"
        }
    }
    return $json | ConvertFrom-Json
}

function Get-SidValue {
    param([Parameter(Mandatory)][string]$Account)
    return (New-Object Security.Principal.NTAccount $Account).
        Translate([Security.Principal.SecurityIdentifier]).Value
}

function Get-LiveObservation {
    $machineGuid = [string](Get-ItemProperty `
        -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' `
        -Name MachineGuid -ErrorAction Stop).MachineGuid
    $smbiosUuid = [string](Get-CimInstance -ClassName Win32_ComputerSystemProduct `
            -ErrorAction Stop).UUID
    $volume = Get-CimInstance -ClassName Win32_Volume -Filter "DriveLetter='D:'" `
        -ErrorAction Stop
    $services = @(Get-CimInstance -ClassName Win32_Service -Filter "Name='caddy'" `
            -ErrorAction Stop)
    $managed = @(Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='LeanTPM.Proxy'" -ErrorAction Stop)
    $service = if ($services.Count -eq 1) { $services[0] } else { $null }
    $pathName = if ($null -ne $service) { ([string]$service.PathName).Trim() } else { '' }
    $imagePath = ''
    $configPath = ''
    $command = [regex]::Match(
        $pathName,
        '^"(?<image>[A-Za-z]:\\[^"\r\n]+)" run --config "(?<config>[A-Za-z]:\\[^"\r\n]+)" --adapter caddyfile$'
    )
    if ($command.Success) {
        $imagePath = [IO.Path]::GetFullPath($command.Groups['image'].Value).TrimEnd('\')
        $configPath = [IO.Path]::GetFullPath($command.Groups['config'].Value).TrimEnd('\')
    }
    $sddl = ''
    if ($null -ne $service) {
        $sddlLines = @(& sc.exe sdshow caddy 2>&1 | ForEach-Object { [string]$_ })
        if ($LASTEXITCODE -ne 0) { throw 'sc.exe sdshow failed during read-only discovery' }
        $sddl = ($sddlLines -join "`n").Trim()
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 80, 443 `
            -ErrorAction Stop | ForEach-Object {
            [pscustomobject]@{
                localAddress = [string]$_.LocalAddress
                port = [int]$_.LocalPort
                owningPid = [int]$_.OwningProcess
            }
        })
    return [pscustomobject]@{
        schemaVersion = 1
        collectedAtUtc = [DateTime]::UtcNow.ToString('o')
        machineGuid = $machineGuid
        smbiosUuid = $smbiosUuid
        volume = [pscustomobject]@{
            driveLetter = [string]$volume.DriveLetter
            driveType = [int]$volume.DriveType
            fileSystem = [string]$volume.FileSystem
            deviceId = [string]$volume.DeviceID
            freeBytes = [int64]$volume.FreeSpace
        }
        caddy = [pscustomobject]@{
            serviceCount = $services.Count
            managedProxyCount = $managed.Count
            state = if ($null -ne $service) { [string]$service.State } else { '' }
            pid = if ($null -ne $service) { [int]$service.ProcessId } else { 0 }
            pathName = $pathName
            startName = if ($null -ne $service) { [string]$service.StartName } else { '' }
            serviceAccountSid = if ($null -ne $service) {
                Get-SidValue ([string]$service.StartName)
            }
            else { '' }
            startMode = if ($null -ne $service) { [string]$service.StartMode } else { '' }
            scmSddl = $sddl
            imagePath = $imagePath
            imageSha256 = if (Test-Path -LiteralPath $imagePath -PathType Leaf) {
                (Get-FileHash -Algorithm SHA256 -LiteralPath $imagePath).Hash.ToLowerInvariant()
            }
            else { '' }
            configPath = $configPath
            configSha256 = if (Test-Path -LiteralPath $configPath -PathType Leaf) {
                (Get-FileHash -Algorithm SHA256 -LiteralPath $configPath).Hash.ToLowerInvariant()
            }
            else { '' }
            listeners = $listeners
        }
    }
}

if ($EnvironmentId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}\z') {
    throw 'EnvironmentId must be a stable lowercase production identifier'
}
if (-not [string]::IsNullOrWhiteSpace($ObservationPath) -and -not $PlanOnly) {
    throw 'ObservationPath mock input is allowed only with PlanOnly'
}
if ($PlanOnly -and [string]::IsNullOrWhiteSpace($ObservationPath)) {
    throw 'PlanOnly discovery requires an explicit ObservationPath fixture'
}
$observation = if ($PlanOnly) {
    Read-ObservationSnapshot $ObservationPath
}
else { Get-LiveObservation }

Assert-ExactProperties $observation @(
    'schemaVersion', 'collectedAtUtc', 'machineGuid', 'smbiosUuid', 'volume', 'caddy'
) 'Host observation'
Assert-ExactProperties $observation.volume @(
    'driveLetter', 'driveType', 'fileSystem', 'deviceId', 'freeBytes'
) 'Volume observation'
Assert-ExactProperties $observation.caddy @(
    'serviceCount', 'managedProxyCount', 'state', 'pid', 'pathName', 'startName',
    'serviceAccountSid', 'startMode', 'scmSddl', 'imagePath', 'imageSha256',
    'configPath', 'configSha256', 'listeners'
) 'Caddy observation'
foreach ($listener in @($observation.caddy.listeners)) {
    Assert-ExactProperties $listener @('localAddress', 'port', 'owningPid') `
        'Caddy listener observation'
}
if ([int]$observation.schemaVersion -ne 1) { throw 'Observation schemaVersion must be 1' }
$collectedAt = [DateTime]::MinValue
if (-not [DateTime]::TryParse(
        [string]$observation.collectedAtUtc,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind,
        [ref]$collectedAt
    )) { throw 'Observation collectedAtUtc is invalid' }
$machineGuid = [Guid]::Empty
$smbiosUuid = [Guid]::Empty
if (-not [Guid]::TryParse(([string]$observation.machineGuid).Trim(), [ref]$machineGuid) -or
        -not [Guid]::TryParse(([string]$observation.smbiosUuid).Trim(), [ref]$smbiosUuid) -or
        $machineGuid -eq [Guid]::Empty -or $smbiosUuid -eq [Guid]::Empty -or
        $smbiosUuid.ToString('N') -ceq ('f' * 32)) {
    throw 'Host observation identities are invalid'
}
$hostCanonical = "machineGuid={0}`nsmbiosUuid={1}" -f
    $machineGuid.ToString('D'), $smbiosUuid.ToString('D')
$hostId = Get-TextSha256 $hostCanonical
$deviceId = ([string]$observation.volume.deviceId).Trim().ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($deviceId)) { throw 'Volume DeviceID is missing' }
$volumeIdentity = 'sha256:' + (Get-TextSha256 $deviceId)

$blockers = [System.Collections.Generic.List[string]]::new()
$blockers.Add('HOST_BOOTSTRAP_NOT_INITIALIZED')
$blockers.Add('HOST_LAYOUT_NOT_APPROVED_OR_WRITTEN')
$blockers.Add('CANONICAL_ROOTS_NOT_CREATED_OR_VERIFIED')
$blockers.Add('LEGACY_LAYOUT_INVENTORY_NOT_BOUND')
$blockers.Add('RELEASE_TRUST_AND_SERVICE_IDENTITIES_NOT_VERIFIED')
$blockers.Add('EXTERNAL_CADDY_POLICY_AND_FIREWALL_NOT_APPROVED')
if ([string]$observation.volume.driveLetter -cne 'D:' -or
        [int]$observation.volume.driveType -ne 3 -or
        [string]$observation.volume.fileSystem -cne 'NTFS') {
    $blockers.Add('D_VOLUME_IS_NOT_FIXED_NTFS')
}
$caddy = $observation.caddy
$sensitiveRawValues = @(
    ([string]$observation.machineGuid).Trim(),
    ([string]$observation.smbiosUuid).Trim(),
    ([string]$observation.volume.deviceId).Trim()
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
foreach ($sensitiveValue in $sensitiveRawValues) {
    if ($EnvironmentId.IndexOf(
            $sensitiveValue, [StringComparison]::OrdinalIgnoreCase
        ) -ge 0) {
        throw 'EnvironmentId must not contain a raw host or volume identity'
    }
}
$rawIdentityDetected = $false
foreach ($fieldName in @(
        'state', 'pathName', 'startName', 'serviceAccountSid', 'startMode', 'scmSddl',
        'imagePath', 'imageSha256', 'configPath', 'configSha256'
    )) {
    $candidate = [string]$caddy.$fieldName
    foreach ($sensitiveValue in $sensitiveRawValues) {
        if ($candidate.IndexOf(
                $sensitiveValue, [StringComparison]::OrdinalIgnoreCase
            ) -ge 0) {
            $candidate = ''
            $rawIdentityDetected = $true
            break
        }
    }
    $caddy.$fieldName = $candidate
}
if ($rawIdentityDetected) {
    $blockers.Add('CADDY_OBSERVATION_CONTAINS_RAW_IDENTITY')
}
if ([int]$caddy.serviceCount -ne 1) { $blockers.Add('CADDY_SERVICE_COUNT_INVALID') }
if ([int]$caddy.managedProxyCount -ne 0) { $blockers.Add('MANAGED_PROXY_COEXISTS') }
if ([string]$caddy.state -cne 'Running' -or [int]$caddy.pid -le 0) {
    $blockers.Add('CADDY_IS_NOT_RUNNING_WITH_A_STABLE_PID')
}
$expectedCommand = '"{0}" run --config "{1}" --adapter caddyfile' -f
    [string]$caddy.imagePath, [string]$caddy.configPath
if (-not ([string]$caddy.pathName).Equals(
        $expectedCommand, [StringComparison]::OrdinalIgnoreCase
    ) -or -not ([IO.Path]::GetFullPath([string]$caddy.configPath)).Equals(
        'D:\LeanTPM\Runtime\proxy\Caddyfile',
        [StringComparison]::OrdinalIgnoreCase
    )) {
    $blockers.Add('CADDY_COMMAND_LINE_OR_CONFIG_PATH_DRIFT')
}
if ([string]$caddy.imageSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$caddy.configSha256 -notmatch '^[a-f0-9]{64}$') {
    $blockers.Add('CADDY_IMAGE_OR_CONFIG_DIGEST_MISSING')
}
if ([string]$caddy.startName -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -or
        [string]$caddy.serviceAccountSid -notmatch '^S-1-5-21-(?:[0-9]+-){3}[0-9]+$') {
    $blockers.Add('CADDY_SERVICE_IDENTITY_IS_NOT_APPROVED_GMSA_SHAPED')
}
if ([string]$caddy.startMode -notin @('Auto', 'Automatic')) {
    $blockers.Add('CADDY_START_MODE_IS_NOT_AUTOMATIC')
}
if ([string]::IsNullOrWhiteSpace([string]$caddy.scmSddl)) {
    $blockers.Add('CADDY_SCM_SDDL_IS_MISSING')
}
$listenerPorts = @($caddy.listeners | ForEach-Object { [int]$_.port } | Sort-Object -Unique)
$listenerPids = @($caddy.listeners | ForEach-Object { [int]$_.owningPid } | Sort-Object -Unique)
if ($listenerPorts.Count -ne 2 -or 80 -notin $listenerPorts -or 443 -notin $listenerPorts -or
        $listenerPids.Count -ne 1 -or $listenerPids[0] -ne [int]$caddy.pid) {
    $blockers.Add('CADDY_PUBLIC_LISTENER_OWNERSHIP_IS_NOT_EXACT')
}

$layoutInputs = [ordered]@{
    environmentKind = 'PRODUCTION'
    environmentId = $EnvironmentId
    hostId = $hostId
    installRoot = $installRoot
    dataRoot = $dataRoot
    volumeIdentity = $volumeIdentity
    proxyMode = 'EXTERNAL_EXISTING'
    serviceId = 'caddy'
    bindingPolicyPath = $bindingPolicyPath
}
$caddyObservation = [ordered]@{
    serviceCount = [int]$caddy.serviceCount
    managedProxyCount = [int]$caddy.managedProxyCount
    serviceState = [string]$caddy.state
    servicePid = [int]$caddy.pid
    serviceImagePath = [string]$caddy.imagePath
    serviceImageSha256 = [string]$caddy.imageSha256
    serviceCommandLineSha256 = Get-TextSha256 ([string]$caddy.pathName)
    serviceAccount = [string]$caddy.startName
    serviceAccountSid = [string]$caddy.serviceAccountSid
    startMode = if ([string]$caddy.startMode -in @('Auto', 'Automatic')) { 'AUTO' } else {
        ([string]$caddy.startMode).ToUpperInvariant()
    }
    scmSddlSha256 = Get-TextSha256 ([string]$caddy.scmSddl)
    configPath = [string]$caddy.configPath
    configSha256 = [string]$caddy.configSha256
    listenerPorts = $listenerPorts
    listenerPids = $listenerPids
}
$requiredNextSteps = @(
    'INDEPENDENTLY_APPROVE_HOST_AND_VOLUME_IDENTITIES',
    'RUN_READ_ONLY_LEGACY_LAYOUT_INVENTORY',
    'PIN_TOOLCHAIN_AND_CREATE_HOST_OWNED_RELEASE_TRUST',
    'CREATE_AND_APPROVE_EXTERNAL_CADDY_POLICY_AND_FIREWALL',
    'RUN_SIGNED_BOOTSTRAP_INITIALIZATION_CEREMONY',
    'RUN_EXECUTABLE_HOST_BOOTSTRAP_AND_CADDY_BINDING_VERIFIERS'
)
$core = [ordered]@{
    schemaVersion = 1
    status = 'INPUT_REQUIRED'
    executable = $false
    trustSource = 'UNTRUSTED_READ_ONLY_DISCOVERY'
    discoveryMode = if ($PlanOnly) { 'PLAN_ONLY_FIXTURE' } else { 'LIVE' }
    planOnly = [bool]$PlanOnly
    collectedAtUtc = $collectedAt.ToUniversalTime().ToString('o')
    productionBootstrapRoot = $productionBootstrapRoot
    rawIdentifiersRedacted = $true
    hostFilesystemVerified = $false
    layoutInputs = $layoutInputs
    preservedExternalPaths = @([ordered]@{
            path = 'D:\LeanTPM\data'
            purpose = 'MYSQL_DATA'
            action = 'PRESERVE_EXTERNAL'
        })
    caddyObservation = $caddyObservation
    blockers = @($blockers | Sort-Object -Unique)
    requiredNextSteps = $requiredNextSteps
}
$report = [ordered]@{}
foreach ($entry in $core.GetEnumerator()) { $report[$entry.Key] = $entry.Value }
$report.discoverySha256 = Get-TextSha256 ($core | ConvertTo-Json -Depth 8 -Compress)
$output = [pscustomobject]$report
if ($OutputFormat -eq 'Json') { $output | ConvertTo-Json -Depth 8 -Compress }
else { $output | Format-List }
