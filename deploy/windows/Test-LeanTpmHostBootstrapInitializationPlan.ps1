[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedPlanSha256,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$fixedAuthorityPath = 'C:\ProgramData\LeanTPM-bootstrap-authority\init-trust.json'

if (-not $PlanOnly) {
    throw 'Structural initialization plan validation is non-executable and requires PlanOnly'
}

function Get-Sha256 {
    param([Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').
            ToLowerInvariant()
    } finally { $sha.Dispose() }
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
            $stack.Add([pscustomobject]@{ kind = 'array'; expectProperty = $false; names = $null })
            $index++
            continue
        }
        if ($character -eq '}' -or $character -eq ']') {
            if ($stack.Count -gt 0) { $stack.RemoveAt($stack.Count - 1) }
            $index++
            continue
        }
        if ($character -eq ',') {
            if ($stack.Count -gt 0 -and $stack[$stack.Count - 1].kind -ceq 'object') {
                $stack[$stack.Count - 1].expectProperty = $true
            }
            $index++
            continue
        }
        if ($character -eq '"') {
            $start = $index + 1
            $cursor = $start
            $containsEscape = $false
            while ($cursor -lt $Json.Length) {
                if ($Json[$cursor] -eq '\') { $containsEscape = $true; $cursor += 2; continue }
                if ($Json[$cursor] -eq '"') { break }
                $cursor++
            }
            if ($cursor -ge $Json.Length) { throw 'Plan JSON string is unterminated' }
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object' -and $context.expectProperty) {
                    if ($containsEscape) {
                        throw 'Plan JSON property names must be unescaped ASCII literals'
                    }
                    $name = $Json.Substring($start, $cursor - $start)
                    if (-not $context.names.Add($name)) {
                        throw "Plan JSON contains duplicate property $name"
                    }
                    $context.expectProperty = $false
                }
            }
            $index = $cursor + 1
            continue
        }
        $index++
    }
}

function Read-PlanSnapshot {
    param([Parameter(Mandatory)][string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Initialization plan must be a regular non-reparse file'
    }
    $stream = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        [IO.FileShare]::Read)
    try {
        if ($stream.Length -gt 4MB) { throw 'Initialization plan exceeds the 4 MiB limit' }
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    } finally { $stream.Dispose() }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try { $json = $utf8.GetString($bytes) }
    catch { throw 'Initialization plan must be strict UTF-8 JSON' }
    Assert-NoDuplicateJsonProperties $json
    try { $value = $json | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Initialization plan is not valid JSON' }
    return [pscustomobject]@{
        Path = $resolved
        Sha256 = Get-Sha256 $bytes
        Value = $value
    }
}

function Assert-ExactProperties {
    param($Value, [string[]]$Expected, [string]$Label)
    if ($null -eq $Value) { throw "$Label is missing" }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    if ($actual.Count -ne $Expected.Count) { throw "$Label property count is invalid" }
    foreach ($name in $Expected) {
        if (@($actual | Where-Object { $_ -ceq $name }).Count -ne 1) {
            throw "$Label is missing exact property $name"
        }
    }
}

function Assert-Sha256 {
    param([string]$Value, [string]$Label)
    if ($Value -cnotmatch '^[a-f0-9]{64}\z') { throw "$Label must be a lowercase SHA-256" }
}

function Assert-JsonString {
    param($Value, [string]$Label)
    if ($Value -isnot [string]) { throw "$Label must be a JSON string" }
}

function Assert-BooleanValue {
    param($Value, [bool]$Expected, [string]$Label)
    if ($Value -isnot [bool] -or [bool]$Value -ne $Expected) {
        throw "$Label must be the JSON boolean $Expected"
    }
}

function Assert-FileDescriptor {
    param($Value, [string]$ExpectedTarget, [string]$Label)
    Assert-ExactProperties $Value @('targetPath', 'sha256') $Label
    Assert-JsonString $Value.targetPath "$Label target path"
    Assert-JsonString $Value.sha256 "$Label SHA-256"
    if ([string]$Value.targetPath -cne $ExpectedTarget) {
        throw "$Label target path is not the fixed production path"
    }
    Assert-Sha256 ([string]$Value.sha256) "$Label SHA-256"
}

function Convert-StrictUtcTimestamp {
    param([string]$Value, [string]$Label)
    if ($Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?Z\z') {
        throw "$Label must be an explicit UTC timestamp"
    }
    try { return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw "$Label is invalid" }
}

$snapshot = Read-PlanSnapshot $PlanPath
if ($snapshot.Sha256 -cne $ExpectedPlanSha256) {
    throw 'Initialization plan bytes do not match the expected SHA-256'
}
$plan = $snapshot.Value
Assert-ExactProperties $plan @(
    'schemaVersion', 'operation', 'initializationId', 'environmentId', 'hostId',
    'volumeIdentity', 'bootstrapVolumeIdentity', 'issuedAtUtc', 'expiresAtUtc', 'nonce',
    'requestedBy', 'approvedBy',
    'authoritySha256', 'discovery', 'legacyInventory', 'roots', 'expectedHostState',
    'inputs', 'identities', 'toolchain', 'executor', 'externalCaddyAdoption', 'constraints'
) 'Initialization plan'
$planStringFields = @(
    'operation', 'initializationId', 'environmentId', 'hostId', 'volumeIdentity',
    'bootstrapVolumeIdentity', 'issuedAtUtc', 'expiresAtUtc', 'nonce', 'requestedBy',
    'approvedBy', 'authoritySha256'
)
foreach ($name in $planStringFields) {
    Assert-JsonString $plan.$name "Initialization plan $name"
}

if (($plan.schemaVersion -isnot [int] -and $plan.schemaVersion -isnot [long]) -or
        [int64]$plan.schemaVersion -ne 1 -or
        [string]$plan.operation -cne 'HOST_BOOTSTRAP_INITIALIZE') {
    throw 'Initialization plan operation or schema version is invalid'
}
foreach ($identityField in @('initializationId', 'environmentId', 'nonce')) {
    if ([string]$plan.$identityField -cnotmatch '^[a-z0-9][a-z0-9._-]{2,127}\z') {
        throw "Initialization plan $identityField is invalid"
    }
}
Assert-Sha256 ([string]$plan.hostId) 'Host ID'
if ([string]$plan.volumeIdentity -cnotmatch '^sha256:[a-f0-9]{64}\z') {
    throw 'Volume identity is invalid'
}
if ([string]$plan.bootstrapVolumeIdentity -cnotmatch '^sha256:[a-f0-9]{64}\z' -or
        [string]$plan.bootstrapVolumeIdentity -ceq [string]$plan.volumeIdentity) {
    throw 'Bootstrap volume identity is invalid or not independent from the D volume'
}
Assert-Sha256 ([string]$plan.authoritySha256) 'Authority'
if ([string]::IsNullOrWhiteSpace([string]$plan.requestedBy) -or
        [string]::IsNullOrWhiteSpace([string]$plan.approvedBy) -or
        [string]$plan.requestedBy -ceq [string]$plan.approvedBy) {
    throw 'Requester and approver identities must be present and different'
}

$issuedAt = Convert-StrictUtcTimestamp ([string]$plan.issuedAtUtc) 'issuedAtUtc'
$expiresAt = Convert-StrictUtcTimestamp ([string]$plan.expiresAtUtc) 'expiresAtUtc'
$now = [DateTimeOffset]::UtcNow
if ($issuedAt -gt $now.AddMinutes(5) -or $expiresAt -le $now -or
        $expiresAt -le $issuedAt -or ($expiresAt - $issuedAt).TotalMinutes -gt 1440) {
    throw 'Initialization plan validity window is invalid or expired'
}

Assert-ExactProperties $plan.discovery @('sha256', 'collectedAtUtc', 'discoveryMode') `
    'Discovery binding'
foreach ($name in @('sha256', 'collectedAtUtc', 'discoveryMode')) {
    Assert-JsonString $plan.discovery.$name "Discovery $name"
}
Assert-Sha256 ([string]$plan.discovery.sha256) 'Discovery'
$collectedAt = Convert-StrictUtcTimestamp ([string]$plan.discovery.collectedAtUtc) `
    'discovery.collectedAtUtc'
if ([string]$plan.discovery.discoveryMode -cne 'LIVE' -or
        $collectedAt -gt $issuedAt.AddMinutes(5) -or
        $issuedAt - $collectedAt -gt [TimeSpan]::FromHours(24)) {
    throw 'Initialization plan requires a fresh LIVE discovery binding'
}

Assert-ExactProperties $plan.legacyInventory @(
    'sha256', 'status', 'readOnly', 'installRootExists', 'dataRootExists',
    'preservedDataAction'
) 'Legacy inventory binding'
foreach ($name in @('sha256', 'status', 'preservedDataAction')) {
    Assert-JsonString $plan.legacyInventory.$name "Legacy inventory $name"
}
Assert-Sha256 ([string]$plan.legacyInventory.sha256) 'Legacy inventory'
Assert-BooleanValue $plan.legacyInventory.readOnly $true 'Legacy inventory readOnly'
Assert-BooleanValue $plan.legacyInventory.installRootExists $false `
    'Legacy inventory installRootExists'
Assert-BooleanValue $plan.legacyInventory.dataRootExists $false `
    'Legacy inventory dataRootExists'
if ([string]$plan.legacyInventory.status -cnotin @('PASS', 'IMPORT_REQUIRED') -or
        [string]$plan.legacyInventory.preservedDataAction -cne 'PRESERVE_EXTERNAL') {
    throw 'Legacy inventory does not prove absent canonical roots and preserved MySQL data'
}

Assert-ExactProperties $plan.roots @(
    'bootstrapRoot', 'bootstrapStateRoot', 'installRoot', 'dataRoot',
    'preservedMySqlDataRoot'
) 'Production roots'
$fixedRoots = [ordered]@{
    bootstrapRoot = 'C:\ProgramData\LeanTPM-bootstrap'
    bootstrapStateRoot = 'C:\ProgramData\LeanTPM-bootstrap-state'
    installRoot = 'D:\LeanTPM\App'
    dataRoot = 'D:\LeanTPM\Runtime'
    preservedMySqlDataRoot = 'D:\LeanTPM\data'
}
foreach ($name in $fixedRoots.Keys) {
    Assert-JsonString $plan.roots.$name "Production root $name"
    if ([string]$plan.roots.$name -cne [string]$fixedRoots[$name]) {
        throw "Production root $name is not the fixed HostBootstrap path"
    }
}

Assert-ExactProperties $plan.expectedHostState @(
    'umbrellaState', 'canonicalRootsState', 'preservedDataAclState'
) 'Expected host state'
foreach ($name in @('umbrellaState', 'canonicalRootsState', 'preservedDataAclState')) {
    Assert-JsonString $plan.expectedHostState.$name "Expected host state $name"
}
$umbrellaState = [string]$plan.expectedHostState.umbrellaState
$dataAclState = [string]$plan.expectedHostState.preservedDataAclState
if ($umbrellaState -cnotin @('CREATE_NEW', 'PREEXISTING_ALREADY_COMPLIANT') -or
        [string]$plan.expectedHostState.canonicalRootsState -cne 'BOTH_ABSENT' -or
        $dataAclState -cnotin @('NOT_PRESENT', 'VERIFIED_PROTECTED_INDEPENDENT') -or
        ($umbrellaState -ceq 'CREATE_NEW' -and $dataAclState -cne 'NOT_PRESENT') -or
        ($umbrellaState -ceq 'PREEXISTING_ALREADY_COMPLIANT' -and
            $dataAclState -cne 'VERIFIED_PROTECTED_INDEPENDENT')) {
    throw 'Expected umbrella or preserved data ACL state is inconsistent'
}

Assert-ExactProperties $plan.inputs @(
    'hostLayout', 'releaseTrust', 'externalCaddyBinding', 'externalFirewall',
    'backupProtection'
) 'Initialization inputs'
Assert-FileDescriptor $plan.inputs.hostLayout `
    'C:\ProgramData\LeanTPM-bootstrap\host-layout.json' 'Host layout'
Assert-FileDescriptor $plan.inputs.releaseTrust `
    'D:\LeanTPM\Runtime\config\release-trust.json' 'Release trust'
Assert-FileDescriptor $plan.inputs.externalCaddyBinding `
    'D:\LeanTPM\Runtime\config\external-caddy-binding.json' 'External Caddy binding'
Assert-FileDescriptor $plan.inputs.externalFirewall `
    'D:\LeanTPM\Runtime\config\external-caddy-firewall.json' 'External firewall policy'
Assert-FileDescriptor $plan.inputs.backupProtection `
    'D:\LeanTPM\Runtime\config\backup-protection.json' 'Backup protection policy'

Assert-ExactProperties $plan.identities @(
    'backendServiceAccount', 'backendServiceSid', 'proxyServiceAccount', 'proxyServiceSid',
    'publicHost'
) 'Service identities'
foreach ($accountName in @('backendServiceAccount', 'proxyServiceAccount')) {
    Assert-JsonString $plan.identities.$accountName "Service identity $accountName"
    if ([string]$plan.identities.$accountName -cnotmatch
            '^[A-Za-z0-9._-]{1,64}\\[A-Za-z0-9._$-]{1,64}\z') {
        throw "Service identity $accountName is invalid"
    }
}
foreach ($sidName in @('backendServiceSid', 'proxyServiceSid')) {
    Assert-JsonString $plan.identities.$sidName "Service identity $sidName"
    if ([string]$plan.identities.$sidName -cnotmatch '^S-1-(?:[0-9]+-){1,14}[0-9]+\z') {
        throw "Service identity $sidName is invalid"
    }
}
Assert-JsonString $plan.identities.publicHost 'Service identity publicHost'
if ([string]$plan.identities.backendServiceSid -ceq [string]$plan.identities.proxyServiceSid -or
        [string]$plan.identities.publicHost -cnotmatch
            '^(?=.{1,253}\z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\z') {
    throw 'Backend/proxy identities or public host are invalid'
}

Assert-ExactProperties $plan.toolchain @(
    'toolchainLockSha256', 'javaSha256', 'winSWSha256', 'caddySha256',
    'hBuilderCompilerDigest'
) 'Toolchain binding'
foreach ($name in @(
        'toolchainLockSha256', 'javaSha256', 'winSWSha256', 'caddySha256',
        'hBuilderCompilerDigest'
    )) {
    Assert-JsonString $plan.toolchain.$name "Toolchain $name"
    Assert-Sha256 ([string]$plan.toolchain.$name) "Toolchain $name"
}
Assert-ExactProperties $plan.executor @('scriptSha256', 'scriptSignerThumbprint') `
    'Executor binding'
Assert-JsonString $plan.executor.scriptSha256 'Executor script SHA-256'
Assert-JsonString $plan.executor.scriptSignerThumbprint 'Executor signer thumbprint'
Assert-Sha256 ([string]$plan.executor.scriptSha256) 'Executor script'
if ([string]$plan.executor.scriptSignerThumbprint -cnotmatch '^[A-Fa-f0-9]{40,128}\z') {
    throw 'Executor signer thumbprint is invalid'
}

Assert-ExactProperties $plan.externalCaddyAdoption @(
    'required', 'observedStateSha256', 'adoptionPlanSha256', 'executeSeparately'
) 'External Caddy adoption binding'
Assert-JsonString $plan.externalCaddyAdoption.observedStateSha256 `
    'External Caddy observed state SHA-256'
Assert-JsonString $plan.externalCaddyAdoption.adoptionPlanSha256 `
    'External Caddy adoption plan SHA-256'
Assert-BooleanValue $plan.externalCaddyAdoption.required $true `
    'External Caddy adoption required'
Assert-BooleanValue $plan.externalCaddyAdoption.executeSeparately $true `
    'External Caddy adoption separation'
Assert-Sha256 ([string]$plan.externalCaddyAdoption.observedStateSha256) `
    'External Caddy observed state'
Assert-Sha256 ([string]$plan.externalCaddyAdoption.adoptionPlanSha256) `
    'External Caddy adoption plan'

$constraintNames = @(
    'preserveMySqlData', 'neverFollowReparsePoints', 'neverOverwriteExistingTargets',
    'externalCaddyAdoptionSeparate', 'liveDiscoveryRevalidationRequired',
    'liveLegacyInventoryRevalidationRequired', 'nonceReservationBeforeMutation'
)
Assert-ExactProperties $plan.constraints $constraintNames 'Initialization constraints'
foreach ($name in $constraintNames) {
    Assert-BooleanValue $plan.constraints.$name $true "Initialization constraint $name"
}

$report = [pscustomobject][ordered]@{
    status = 'INPUT_REQUIRED'
    executable = $false
    trustSource = 'CALLER_BOUND_PLAN_ONLY'
    approvalReadiness = 'FIXED_AUTHORITY_DOUBLE_CMS_AND_LIVE_REVALIDATION_REQUIRED'
    authorityPath = $fixedAuthorityPath
    initializationId = [string]$plan.initializationId
    environmentId = [string]$plan.environmentId
    hostId = [string]$plan.hostId
    volumeIdentity = [string]$plan.volumeIdentity
    bootstrapVolumeIdentity = [string]$plan.bootstrapVolumeIdentity
    planSha256 = [string]$snapshot.Sha256
    cryptographicallyVerified = $false
    hostFilesystemVerified = $false
    nonceReserved = $false
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
