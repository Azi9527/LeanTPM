[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PolicyPath,
    [string]$ObservationPath = '',
    [Parameter(Mandatory)][string]$ExpectedPolicySha256,
    [Parameter(Mandatory)][string]$ExpectedInstallRoot,
    [Parameter(Mandatory)][string]$ExpectedDataRoot,
    [ValidateSet('STANDBY_DISABLED', 'ACTIVE')]
    [string]$ExpectedFirewallState = 'STANDBY_DISABLED',
    [switch]$PolicyOnly,
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

function Read-StrictJsonSnapshot {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
            ((Get-Item -LiteralPath $resolved -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Contract input must be a regular non-reparse file: $Path"
    }
    $stream = [IO.File]::Open(
        $resolved,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
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
        json = $utf8.GetString($bytes)
    }
}

function Assert-ExactProperties {
    param($Object, [string[]]$Expected, [string]$Label)

    if ($null -eq $Object) { throw "$Label is missing" }
    $actual = @($Object.PSObject.Properties | ForEach-Object { $_.Name })
    if ($actual.Count -ne $Expected.Count) { throw "$Label has an unexpected property count" }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing or has a case-mismatched property: $name"
        }
    }
}

function Assert-UnescapedPropertyTokens {
    param(
        [string]$Json,
        [hashtable]$ExpectedCounts,
        [string]$Label
    )

    $tokens = [regex]::Matches($Json, '"(?<name>(?:\\.|[^"\\])*)"\s*:')
    if (@($tokens | Where-Object { $_.Groups['name'].Value.Contains('\') }).Count -gt 0) {
        throw "$Label property names must use unescaped ASCII"
    }
    $expectedTotal = 0
    foreach ($entry in $ExpectedCounts.GetEnumerator()) {
        $expectedTotal += [int]$entry.Value
        $count = @($tokens | Where-Object {
                $_.Groups['name'].Value -ceq [string]$entry.Key
            }).Count
        if ($count -ne [int]$entry.Value) {
            throw "$Label property must occur exactly $($entry.Value) time(s): $($entry.Key)"
        }
    }
    if ($tokens.Count -ne $expectedTotal) {
        throw "$Label contains an unknown or duplicate property"
    }
}

function Resolve-SafeLocalPath {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)

    if ($Path -notmatch '^[A-Za-z]:\\' -or $Path.StartsWith('\\') -or
            $Path.StartsWith('\\?\') -or $Path.Contains('..')) {
        throw "$Label must be an absolute local Windows path"
    }
    foreach ($part in $Path.Substring(3).Split('\')) {
        if ([string]::IsNullOrWhiteSpace($part) -or $part.EndsWith(' ') -or
                $part.EndsWith('.') -or $part.Contains(':') -or
                $part -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)') {
            throw "$Label contains an unsafe Windows path component"
        }
    }
    return [IO.Path]::GetFullPath($Path).TrimEnd('\')
}

$policySnapshot = Read-StrictJsonSnapshot $PolicyPath
if ($ExpectedPolicySha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$policySnapshot.sha256 -cne $ExpectedPolicySha256) {
    throw 'External Caddy policy bytes differ from the expected SHA-256'
}
$policyPropertyCounts = @{
    schemaVersion = 1; readiness = 1; serviceId = 1; exclusiveIngress = 1
    serviceImagePath = 1; serviceImageSha256 = 1; serviceCommandLineSha256 = 1
    serviceEnvironmentSha256 = 1
    serviceAccount = 1
    serviceAccountSid = 1; startMode = 1; scmSddlSha256 = 1; configPath = 1
    configSha256 = 1; publicHost = 1; webRoot = 1; backendUpstream = 1
    listenPorts = 1; tlsDataRoot = 1; logRoot = 1; adminEndpoint = 1
    firewallRuleGroup = 1; firewallPolicySha256 = 1
}
Assert-UnescapedPropertyTokens $policySnapshot.json $policyPropertyCounts 'External Caddy policy'
$policy = $policySnapshot.json | ConvertFrom-Json
$policyProperties = @($policyPropertyCounts.Keys | ForEach-Object { [string]$_ })
Assert-ExactProperties $policy $policyProperties 'External Caddy policy'

$installRoot = Resolve-SafeLocalPath $ExpectedInstallRoot 'ExpectedInstallRoot'
$dataRoot = Resolve-SafeLocalPath $ExpectedDataRoot 'ExpectedDataRoot'
$serviceImagePath = Resolve-SafeLocalPath ([string]$policy.serviceImagePath) 'serviceImagePath'
$configPath = Resolve-SafeLocalPath ([string]$policy.configPath) 'configPath'
$webRoot = Resolve-SafeLocalPath ([string]$policy.webRoot) 'webRoot'
$tlsDataRoot = Resolve-SafeLocalPath ([string]$policy.tlsDataRoot) 'tlsDataRoot'
$logRoot = Resolve-SafeLocalPath ([string]$policy.logRoot) 'logRoot'
$expectedWebRoot = Join-Path $installRoot 'current\payload\web'
$expectedConfigPath = Join-Path $dataRoot 'proxy\Caddyfile'
$expectedTlsDataRoot = Join-Path $dataRoot 'proxy\tls'
$expectedLogRoot = Join-Path $dataRoot 'proxy\logs'
$expectedServiceCommandLine = '"{0}" run --config "{1}" --adapter caddyfile' -f `
    $serviceImagePath, $configPath
$expectedServiceEnvironment = @(
    'XDG_CONFIG_HOME={0}' -f (Join-Path $dataRoot 'proxy\config')
    'XDG_DATA_HOME={0}' -f $tlsDataRoot
) -join "`n"
foreach ($dataPath in @($configPath, $tlsDataRoot, $logRoot)) {
    if (-not $dataPath.StartsWith($dataRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'External Caddy mutable paths must remain inside the approved DataRoot'
    }
}
if (-not $configPath.Equals($expectedConfigPath, [StringComparison]::OrdinalIgnoreCase) -or
        -not $tlsDataRoot.Equals($expectedTlsDataRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not $logRoot.Equals($expectedLogRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'External Caddy mutable paths must use the dedicated proxy config, TLS and log roots'
}
if (($policy.schemaVersion -isnot [int] -and $policy.schemaVersion -isnot [long]) -or
        [int64]$policy.schemaVersion -ne 1 -or
        $policy.readiness -isnot [string] -or [string]$policy.readiness -cne 'READY' -or
        $policy.serviceId -isnot [string] -or [string]$policy.serviceId -cne 'caddy' -or
        $policy.exclusiveIngress -isnot [bool] -or -not [bool]$policy.exclusiveIngress -or
        $policy.serviceImageSha256 -isnot [string] -or
        [string]$policy.serviceImageSha256 -notmatch '^[a-f0-9]{64}$' -or
        $policy.serviceCommandLineSha256 -isnot [string] -or
        [string]$policy.serviceCommandLineSha256 -notmatch '^[a-f0-9]{64}$' -or
        $policy.serviceEnvironmentSha256 -isnot [string] -or
        [string]$policy.serviceEnvironmentSha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$policy.serviceEnvironmentSha256 -cne
            (Get-TextSha256 $expectedServiceEnvironment) -or
        $policy.serviceAccount -isnot [string] -or
        [string]$policy.serviceAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -or
        $policy.serviceAccountSid -isnot [string] -or
        [string]$policy.serviceAccountSid -notmatch '^S-1-5-21-(?:[0-9]+-){3}[0-9]+$' -or
        $policy.startMode -isnot [string] -or [string]$policy.startMode -cne 'AUTO' -or
        $policy.scmSddlSha256 -isnot [string] -or
        [string]$policy.scmSddlSha256 -notmatch '^[a-f0-9]{64}$' -or
        $policy.configSha256 -isnot [string] -or
        [string]$policy.configSha256 -notmatch '^[a-f0-9]{64}$' -or
        $policy.publicHost -isnot [string] -or
        [string]$policy.publicHost -notmatch '^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$' -or
        -not $webRoot.Equals($expectedWebRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $policy.backendUpstream -isnot [string] -or
        [string]$policy.backendUpstream -cne 'http://127.0.0.1:18080' -or
        $policy.adminEndpoint -isnot [string] -or [string]$policy.adminEndpoint -cne 'OFF' -or
        $policy.firewallRuleGroup -isnot [string] -or
        [string]::IsNullOrWhiteSpace([string]$policy.firewallRuleGroup) -or
        $policy.firewallPolicySha256 -isnot [string] -or
        [string]$policy.firewallPolicySha256 -notmatch '^[a-f0-9]{64}$') {
    throw 'External Caddy policy identity or security boundary is invalid'
}
$listenPorts = @($policy.listenPorts)
if ($listenPorts.Count -ne 2 -or [int]$listenPorts[0] -ne 80 -or
        [int]$listenPorts[1] -ne 443) {
    throw 'External Caddy policy must exclusively bind ports 80 and 443'
}

if ($PolicyOnly) {
    $report = [pscustomobject]@{
        status = 'PASS'
        policyOnly = $true
        policyPath = [string]$policySnapshot.path
        policySha256 = [string]$policySnapshot.sha256
        proxyBindingSha256 = [string]$policySnapshot.sha256
        serviceId = [string]$policy.serviceId
        serviceImagePath = $serviceImagePath
        serviceImageSha256 = [string]$policy.serviceImageSha256
        serviceEnvironmentSha256 = [string]$policy.serviceEnvironmentSha256
        configPath = $configPath
        configSha256 = [string]$policy.configSha256
        tlsDataRoot = $tlsDataRoot
        logRoot = $logRoot
        firewallRuleGroup = [string]$policy.firewallRuleGroup
        firewallPolicySha256 = [string]$policy.firewallPolicySha256
        listenPorts = $listenPorts
    }
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
    else { $report | Format-List }
    return
}
if ([string]::IsNullOrWhiteSpace($ObservationPath)) {
    throw 'ObservationPath is required unless PolicyOnly is selected'
}

$observationSnapshot = Read-StrictJsonSnapshot $ObservationPath
$observationPropertyCounts = @{
    schemaVersion = 1; serviceId = 1; serviceState = 1; servicePid = 1
    serviceImagePath = 1; serviceImageSha256 = 1; serviceCommandLine = 1
    serviceCommandLineSha256 = 1; serviceEnvironmentSha256 = 1
    serviceAccount = 1
    serviceAccountSid = 1; startMode = 1; scmSddlSha256 = 1; configPath = 1
    configSha256 = 1; tlsDataRoot = 1; logRoot = 1
    publicHost = 1; webRoot = 1; backendUpstream = 1
    adminEndpoint = 1; processTreePids = 1; listeners = 1; localAddress = 2
    port = 2; owningPid = 2; managedProxyPresent = 1; firewallRuleGroup = 1
    firewallPolicySha256 = 1; firewallReady = 1; firewallState = 1
    processStartedAtUtc = 1; policyLastWriteUtc = 1; serviceImageLastWriteUtc = 1
    configLastWriteUtc = 1; serviceEnvironmentLastWriteUtc = 1
    runtimeFreshnessVerified = 1
}
Assert-UnescapedPropertyTokens $observationSnapshot.json $observationPropertyCounts `
    'External Caddy observation'
$observation = $observationSnapshot.json | ConvertFrom-Json
$observationRootProperties = @(
    'schemaVersion', 'serviceId', 'serviceState', 'servicePid', 'serviceImagePath',
    'serviceImageSha256', 'serviceCommandLine', 'serviceCommandLineSha256',
    'serviceEnvironmentSha256', 'serviceAccount',
    'serviceAccountSid', 'startMode',
    'scmSddlSha256', 'configPath', 'configSha256', 'tlsDataRoot', 'logRoot',
    'publicHost', 'webRoot',
    'backendUpstream', 'adminEndpoint', 'processTreePids', 'listeners',
    'managedProxyPresent', 'firewallRuleGroup', 'firewallPolicySha256', 'firewallReady',
    'firewallState', 'processStartedAtUtc', 'policyLastWriteUtc',
    'serviceImageLastWriteUtc', 'configLastWriteUtc',
    'serviceEnvironmentLastWriteUtc', 'runtimeFreshnessVerified'
)
Assert-ExactProperties $observation $observationRootProperties 'External Caddy observation'
$listeners = @($observation.listeners)
if ($listeners.Count -ne 2) { throw 'External Caddy observation must contain two listeners' }
foreach ($listener in $listeners) {
    Assert-ExactProperties $listener @('localAddress', 'port', 'owningPid') `
        'External Caddy listener'
}
$processTreePids = @($observation.processTreePids | ForEach-Object { [int]$_ })
if ($observation.schemaVersion -isnot [int] -and $observation.schemaVersion -isnot [long]) {
    throw 'External Caddy observation schemaVersion is invalid'
}
$observedServiceCommandLine = [string]$observation.serviceCommandLine
if (-not $observedServiceCommandLine.Equals(
        $expectedServiceCommandLine, [StringComparison]::OrdinalIgnoreCase
    ) -or
        (Get-TextSha256 $observedServiceCommandLine) -cne
            [string]$observation.serviceCommandLineSha256) {
    throw 'External Caddy must run the exact approved config with no extra SCM arguments'
}
$expectedPairs = @(
    @('serviceId', [string]$policy.serviceId),
    @('serviceImagePath', $serviceImagePath),
    @('serviceImageSha256', [string]$policy.serviceImageSha256),
    @('serviceCommandLineSha256', [string]$policy.serviceCommandLineSha256),
    @('serviceEnvironmentSha256', [string]$policy.serviceEnvironmentSha256),
    @('serviceAccount', [string]$policy.serviceAccount),
    @('serviceAccountSid', [string]$policy.serviceAccountSid),
    @('startMode', [string]$policy.startMode),
    @('scmSddlSha256', [string]$policy.scmSddlSha256),
    @('configPath', $configPath),
    @('configSha256', [string]$policy.configSha256),
    @('tlsDataRoot', $tlsDataRoot),
    @('logRoot', $logRoot),
    @('publicHost', [string]$policy.publicHost),
    @('webRoot', $webRoot),
    @('backendUpstream', [string]$policy.backendUpstream),
    @('adminEndpoint', [string]$policy.adminEndpoint),
    @('firewallRuleGroup', [string]$policy.firewallRuleGroup),
    @('firewallPolicySha256', [string]$policy.firewallPolicySha256)
)
foreach ($pair in $expectedPairs) {
    $name = [string]$pair[0]
    if (-not ([string]$observation.$name).Equals(
            [string]$pair[1], [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "External Caddy observation differs from policy: $name"
    }
}
$servicePid = [int]$observation.servicePid
$processStartedAtUtc = [DateTime]::MinValue
$trustWriteTimes = @()
try {
    $processStartedAtUtc = [DateTime]::Parse(
        [string]$observation.processStartedAtUtc,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind
    ).ToUniversalTime()
    foreach ($name in @(
            'policyLastWriteUtc', 'serviceImageLastWriteUtc', 'configLastWriteUtc',
            'serviceEnvironmentLastWriteUtc'
        )) {
        $trustWriteTimes += [DateTime]::Parse(
            [string]$observation.$name,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind
        ).ToUniversalTime()
    }
}
catch { throw 'External Caddy runtime freshness timestamps are invalid' }
if ([int64]$observation.schemaVersion -ne 1 -or
        [string]$observation.serviceState -cne 'RUNNING' -or $servicePid -le 0 -or
        $servicePid -notin $processTreePids -or [bool]$observation.managedProxyPresent -or
        $observation.firewallReady -isnot [bool] -or -not [bool]$observation.firewallReady -or
        $observation.runtimeFreshnessVerified -isnot [bool] -or
        -not [bool]$observation.runtimeFreshnessVerified -or
        @($trustWriteTimes | Where-Object { $processStartedAtUtc -le $_ }).Count -gt 0) {
    throw 'External Caddy runtime or fail-closed observation is unsafe'
}
if ([string]$observation.firewallState -cne $ExpectedFirewallState) {
    throw 'External Caddy isolation firewall state differs from the requested verification state'
}
foreach ($port in @(80, 443)) {
    $portListeners = @($listeners | Where-Object { [int]$_.port -eq $port })
    if ($portListeners.Count -ne 1 -or
            [int]$portListeners[0].owningPid -notin $processTreePids -or
            [string]$portListeners[0].localAddress -notin @('0.0.0.0', '::')) {
        throw "External Caddy listener ownership drifted for port $port"
    }
}

$report = [pscustomobject]@{
    status = 'PASS'
    serviceId = 'caddy'
    policyPath = [string]$policySnapshot.path
    policySha256 = [string]$policySnapshot.sha256
    observationSha256 = [string]$observationSnapshot.sha256
    proxyBindingSha256 = [string]$policySnapshot.sha256
    failClosedCapable = $true
    servicePid = $servicePid
    processTreePids = $processTreePids
    listenerPorts = @(80, 443)
    serviceImagePath = $serviceImagePath
    configPath = $configPath
    publicHost = [string]$policy.publicHost
    webRoot = $webRoot
    backendUpstream = [string]$policy.backendUpstream
    firewallRuleGroup = [string]$policy.firewallRuleGroup
    firewallPolicySha256 = [string]$policy.firewallPolicySha256
    firewallState = $ExpectedFirewallState
    serviceEnvironmentSha256 = [string]$policy.serviceEnvironmentSha256
    tlsDataRoot = $tlsDataRoot
    logRoot = $logRoot
    processStartedAtUtc = $processStartedAtUtc.ToString('o')
    runtimeFreshnessVerified = $true
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 6 -Compress }
else { $report | Format-List }
