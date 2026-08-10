[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ReceiptPath,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedReceiptSha256,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if (-not $PlanOnly) {
    throw 'Unsigned initialization receipt inspection is PlanOnly and non-executable'
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
            $escaped = $false
            while ($cursor -lt $Json.Length) {
                if ($Json[$cursor] -eq '\') { $escaped = $true; $cursor += 2; continue }
                if ($Json[$cursor] -eq '"') { break }
                $cursor++
            }
            if ($cursor -ge $Json.Length) { throw 'Receipt JSON string is unterminated' }
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object' -and $context.expectProperty) {
                    if ($escaped) {
                        throw 'Receipt JSON property names must be unescaped ASCII literals'
                    }
                    $name = $Json.Substring($start, $cursor - $start)
                    if (-not $context.names.Add($name)) {
                        throw "Receipt JSON contains duplicate property $name"
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

function Read-ReceiptSnapshot {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Initialization receipt must be a regular non-reparse file'
    }
    $stream = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        [IO.FileShare]::Read)
    try {
        if ($stream.Length -gt 4MB) { throw 'Initialization receipt exceeds the 4 MiB limit' }
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    } finally { $stream.Dispose() }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try { $json = $utf8.GetString($bytes) }
    catch { throw 'Initialization receipt must be strict UTF-8 JSON' }
    Assert-NoDuplicateJsonProperties $json
    try { $value = $json | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Initialization receipt is not valid JSON' }
    return [pscustomobject]@{ Sha256 = Get-Sha256 $bytes; Value = $value }
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

function Assert-JsonArray {
    param($Value, [string]$Label)
    if ($Value -isnot [Array]) { throw "$Label must be a JSON array" }
}

function Resolve-StrictLocalPath {
    param($Value, [string]$Label)
    Assert-JsonString $Value $Label
    $path = [string]$Value
    if ($path -cnotmatch '^[A-Za-z]:\\' -or
            $path -cmatch '^(?:\\\\|\\\\\?\\|\\\\\.\\)' -or
            $path -cmatch '[\x00-\x1f"<>|?*]') {
        throw "$Label must be an absolute local Windows path"
    }
    $parts = @($path.Substring(3).Split('\'))
    foreach ($part in $parts) {
        if ([string]::IsNullOrEmpty($part) -or $part -in @('.', '..') -or
                $part.EndsWith(' ') -or $part.EndsWith('.') -or $part.Contains(':') -or
                $part -cmatch '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?\z') {
            throw "$Label contains an unsafe Windows path component"
        }
    }
    try { $canonical = [IO.Path]::GetFullPath($path) }
    catch { throw "$Label cannot be canonicalized" }
    if (-not $canonical.Equals($path, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must already be in canonical form"
    }
    return $canonical
}

function Assert-BooleanValue {
    param($Value, [bool]$Expected, [string]$Label)
    if ($Value -isnot [bool] -or [bool]$Value -ne $Expected) {
        throw "$Label must be the JSON boolean $Expected"
    }
}

function Convert-StrictUtcTimestamp {
    param([string]$Value, [string]$Label)
    if ($Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?Z\z') {
        throw "$Label must be an explicit UTC timestamp"
    }
    try { return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw "$Label is invalid" }
}

$snapshot = Read-ReceiptSnapshot $ReceiptPath
if ($snapshot.Sha256 -cne $ExpectedReceiptSha256) {
    throw 'Initialization receipt bytes do not match the expected SHA-256'
}
$receipt = $snapshot.Value
Assert-ExactProperties $receipt @(
    'schemaVersion', 'operation', 'initializationId', 'status', 'productionReady',
    'externalCaddyAdopted', 'mysqlDataTouched', 'planSha256', 'authoritySha256',
    'nonce', 'environmentId', 'hostId', 'volumeIdentity', 'bootstrapVolumeIdentity',
    'startedAtUtc',
    'completedAtUtc', 'requesterCertificateThumbprint', 'approverCertificateThumbprint',
    'executorScriptSha256', 'stateJournalSha256', 'roots', 'artifacts', 'createdObjects',
    'preservedMySqlData', 'legacy', 'verification', 'nextRequiredCeremonies'
) 'Initialization receipt'
$receiptStringFields = @(
    'operation', 'initializationId', 'status', 'planSha256', 'authoritySha256', 'nonce',
    'environmentId', 'hostId', 'volumeIdentity', 'bootstrapVolumeIdentity',
    'startedAtUtc', 'completedAtUtc', 'requesterCertificateThumbprint',
    'approverCertificateThumbprint', 'executorScriptSha256', 'stateJournalSha256'
)
foreach ($name in $receiptStringFields) {
    Assert-JsonString $receipt.$name "Initialization receipt $name"
}
Assert-JsonArray $receipt.createdObjects 'Initialization receipt createdObjects'
Assert-JsonArray $receipt.nextRequiredCeremonies `
    'Initialization receipt nextRequiredCeremonies'
foreach ($ceremony in $receipt.nextRequiredCeremonies) {
    Assert-JsonString $ceremony 'Initialization receipt next ceremony'
}
Assert-BooleanValue $receipt.productionReady $false 'productionReady'
Assert-BooleanValue $receipt.externalCaddyAdopted $false 'externalCaddyAdopted'
Assert-BooleanValue $receipt.mysqlDataTouched $false 'mysqlDataTouched'
if (($receipt.schemaVersion -isnot [int] -and $receipt.schemaVersion -isnot [long]) -or
        [int64]$receipt.schemaVersion -ne 1 -or
        [string]$receipt.operation -cne 'HOST_BOOTSTRAP_INITIALIZE' -or
        [string]$receipt.initializationId -cnotmatch
            '^[a-z0-9][a-z0-9._-]{2,127}\z' -or
        [string]$receipt.status -cnotin @(
            'BOOTSTRAP_COMMITTED_ADOPTION_REQUIRED',
            'BOOTSTRAP_COMMITTED_LEGACY_IMPORT_AND_ADOPTION_REQUIRED'
        )) {
    throw 'Initialization receipt must remain non-production-ready until separate adoption'
}
foreach ($name in @(
        'planSha256', 'authoritySha256', 'hostId', 'executorScriptSha256',
        'stateJournalSha256'
    )) {
    Assert-Sha256 ([string]$receipt.$name) "Receipt $name"
}
if ([string]$receipt.nonce -cnotmatch '^[a-z0-9][a-z0-9._-]{2,127}\z' -or
        [string]$receipt.environmentId -cnotmatch '^[a-z0-9][a-z0-9._-]{2,127}\z' -or
        [string]$receipt.volumeIdentity -cnotmatch '^sha256:[a-f0-9]{64}\z' -or
        [string]$receipt.bootstrapVolumeIdentity -cnotmatch '^sha256:[a-f0-9]{64}\z' -or
        [string]$receipt.bootstrapVolumeIdentity -ceq [string]$receipt.volumeIdentity) {
    throw 'Initialization receipt host or approval identity is invalid'
}
foreach ($name in @('requesterCertificateThumbprint', 'approverCertificateThumbprint')) {
    if ([string]$receipt.$name -cnotmatch '^[A-Fa-f0-9]{40,128}\z') {
        throw "Receipt $name is invalid"
    }
}
if ([string]$receipt.requesterCertificateThumbprint -ceq
        [string]$receipt.approverCertificateThumbprint) {
    throw 'Receipt requester and approver certificates must differ'
}
$startedAt = Convert-StrictUtcTimestamp ([string]$receipt.startedAtUtc) 'startedAtUtc'
$completedAt = Convert-StrictUtcTimestamp ([string]$receipt.completedAtUtc) 'completedAtUtc'
if ($completedAt -lt $startedAt -or $completedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5)) {
    throw 'Initialization receipt timeline is invalid'
}

Assert-ExactProperties $receipt.roots @(
    'bootstrapRoot', 'bootstrapStateRoot', 'installRoot', 'dataRoot',
    'preservedMySqlDataRoot'
) 'Receipt roots'
$fixedRoots = [ordered]@{
    bootstrapRoot = 'C:\ProgramData\LeanTPM-bootstrap'
    bootstrapStateRoot = 'C:\ProgramData\LeanTPM-bootstrap-state'
    installRoot = 'D:\LeanTPM\App'
    dataRoot = 'D:\LeanTPM\Runtime'
    preservedMySqlDataRoot = 'D:\LeanTPM\data'
}
foreach ($name in $fixedRoots.Keys) {
    Assert-JsonString $receipt.roots.$name "Receipt root $name"
    if ([string]$receipt.roots.$name -cne [string]$fixedRoots[$name]) {
        throw "Receipt root $name is not canonical"
    }
}
Assert-ExactProperties $receipt.artifacts @(
    'hostLayoutSha256', 'releaseTrustSha256', 'externalCaddyBindingSha256',
    'externalFirewallSha256', 'backupProtectionSha256', 'toolchainLockSha256'
) 'Receipt artifacts'
foreach ($name in @($receipt.artifacts.PSObject.Properties.Name)) {
    Assert-JsonString $receipt.artifacts.$name "Receipt artifact $name"
    Assert-Sha256 ([string]$receipt.artifacts.$name) "Receipt artifact $name"
}

$created = @($receipt.createdObjects)
if ($created.Count -lt 1) { throw 'Receipt must identify at least one created object' }
$seenPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($object in $created) {
    Assert-ExactProperties $object @(
        'path', 'finalPath', 'volumeIdentity', 'fileId', 'aclSha256', 'objectKind',
        'contentSha256'
    ) `
        'Created object'
    foreach ($name in @(
            'path', 'finalPath', 'volumeIdentity', 'fileId', 'aclSha256', 'objectKind',
            'contentSha256'
        )) {
        Assert-JsonString $object.$name "Created object $name"
    }
    $path = Resolve-StrictLocalPath $object.path 'Created object path'
    $finalPath = Resolve-StrictLocalPath $object.finalPath 'Created object finalPath'
    $isBootstrapObject = $path.Equals(
        'C:\ProgramData\LeanTPM-bootstrap', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.StartsWith(
        'C:\ProgramData\LeanTPM-bootstrap\', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.Equals(
        'C:\ProgramData\LeanTPM-bootstrap-state', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.StartsWith(
        'C:\ProgramData\LeanTPM-bootstrap-state\', [StringComparison]::OrdinalIgnoreCase
    )
    $isApplicationObject = $path.Equals(
        'D:\LeanTPM\App', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.StartsWith(
        'D:\LeanTPM\App\', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.Equals(
        'D:\LeanTPM\Runtime', [StringComparison]::OrdinalIgnoreCase
    ) -or $path.StartsWith(
        'D:\LeanTPM\Runtime\', [StringComparison]::OrdinalIgnoreCase
    )
    $expectedObjectVolume = if ($isBootstrapObject) {
        [string]$receipt.bootstrapVolumeIdentity
    } else { [string]$receipt.volumeIdentity }
    if (-not $seenPaths.Add($path) -or $path -cne $finalPath -or
            (-not $isBootstrapObject -and -not $isApplicationObject) -or
            $path.StartsWith('D:\LeanTPM\data\', [StringComparison]::OrdinalIgnoreCase) -or
            $path.Equals('D:\LeanTPM\data', [StringComparison]::OrdinalIgnoreCase) -or
            [string]$object.volumeIdentity -cne $expectedObjectVolume -or
            [string]$object.fileId -cnotmatch '^[A-Fa-f0-9]{32}\z' -or
            [string]$object.objectKind -cnotin @('DIRECTORY', 'FILE')) {
        throw 'Created object identity is unsafe or inconsistent'
    }
    Assert-Sha256 ([string]$object.aclSha256) 'Created object ACL'
    Assert-Sha256 ([string]$object.contentSha256) 'Created object content'
}
$requiredCreatedRoots = @(
    'C:\ProgramData\LeanTPM-bootstrap', 'D:\LeanTPM\App', 'D:\LeanTPM\Runtime'
)
foreach ($requiredRoot in $requiredCreatedRoots) {
    $matches = @($created | Where-Object {
            [string]$_.path -ceq $requiredRoot -and
            [string]$_.objectKind -ceq 'DIRECTORY'
        })
    if ($matches.Count -ne 1) {
        throw "Receipt must contain exactly one created canonical root: $requiredRoot"
    }
}

Assert-ExactProperties $receipt.preservedMySqlData @(
    'path', 'beforeIdentitySha256', 'afterIdentitySha256', 'unchanged'
) 'Preserved MySQL data proof'
foreach ($name in @('path', 'beforeIdentitySha256', 'afterIdentitySha256')) {
    Assert-JsonString $receipt.preservedMySqlData.$name "Preserved MySQL data $name"
}
Assert-BooleanValue $receipt.preservedMySqlData.unchanged $true `
    'Preserved MySQL data unchanged'
if ([string]$receipt.preservedMySqlData.path -cne 'D:\LeanTPM\data' -or
        $receipt.preservedMySqlData.unchanged -ne $true) {
    throw 'Receipt does not preserve the fixed MySQL data root'
}
Assert-Sha256 ([string]$receipt.preservedMySqlData.beforeIdentitySha256) `
    'MySQL data before identity'
Assert-Sha256 ([string]$receipt.preservedMySqlData.afterIdentitySha256) `
    'MySQL data after identity'
if ([string]$receipt.preservedMySqlData.beforeIdentitySha256 -cne
        [string]$receipt.preservedMySqlData.afterIdentitySha256) {
    throw 'MySQL data root identity changed during HostBootstrap initialization'
}

Assert-ExactProperties $receipt.legacy @('inventorySha256', 'status', 'importRequired') `
    'Legacy disposition'
Assert-JsonString $receipt.legacy.inventorySha256 'Legacy inventory SHA-256'
Assert-JsonString $receipt.legacy.status 'Legacy status'
Assert-Sha256 ([string]$receipt.legacy.inventorySha256) 'Legacy inventory'
Assert-ExactProperties $receipt.verification @('hostBootstrapReportSha256') `
    'Bootstrap verification'
Assert-JsonString $receipt.verification.hostBootstrapReportSha256 `
    'HostBootstrap verification report SHA-256'
Assert-Sha256 ([string]$receipt.verification.hostBootstrapReportSha256) `
    'HostBootstrap verification report'
$next = @($receipt.nextRequiredCeremonies)
if ([string]$receipt.legacy.status -ceq 'PASS') {
    Assert-BooleanValue $receipt.legacy.importRequired $false 'Legacy importRequired'
    if ($receipt.legacy.importRequired -or
            [string]$receipt.status -cne 'BOOTSTRAP_COMMITTED_ADOPTION_REQUIRED' -or
            $next.Count -ne 1 -or [string]$next[0] -cne 'EXTERNAL_CADDY_ADOPTION') {
        throw 'Clean legacy state must require only the separate external Caddy adoption'
    }
}
elseif ([string]$receipt.legacy.status -ceq 'IMPORT_REQUIRED') {
    Assert-BooleanValue $receipt.legacy.importRequired $true 'Legacy importRequired'
    if (-not $receipt.legacy.importRequired -or
            [string]$receipt.status -cne
                'BOOTSTRAP_COMMITTED_LEGACY_IMPORT_AND_ADOPTION_REQUIRED' -or
            $next.Count -ne 2 -or 'LEGACY_IMPORT' -cnotin $next -or
            'EXTERNAL_CADDY_ADOPTION' -cnotin $next) {
        throw 'Legacy import receipt must keep both follow-up ceremonies pending'
    }
}
else { throw 'Receipt legacy status is invalid' }

$report = [pscustomobject][ordered]@{
    status = 'INPUT_REQUIRED'
    productionReady = $false
    cryptographicallyVerified = $false
    trustSource = 'CALLER_BOUND_RECEIPT_PLAN_ONLY'
    receiptSha256 = [string]$snapshot.Sha256
    initializationId = [string]$receipt.initializationId
    receiptStatus = [string]$receipt.status
    nextRequiredCeremonies = @($next)
    detachedReceiptSignatureRequired = $true
    productionRootPolicyGateSatisfied = $false
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
