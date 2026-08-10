[CmdletBinding()]
param(
    [string]$AuthorityPath = 'C:\ProgramData\LeanTPM-bootstrap-authority\init-trust.json',
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedAuthoritySha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9._-]{2,127}$')]
    [string]$ExpectedEnvironmentId,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedHostId,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedInitializerScriptSha256,
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$fixedAuthorityPath = 'C:\ProgramData\LeanTPM-bootstrap-authority\init-trust.json'
$fixedAuthoritySignaturePath = $fixedAuthorityPath + '.p7s'

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
            if ($cursor -ge $Json.Length) { throw 'Authority JSON string is unterminated' }
            if ($stack.Count -gt 0) {
                $context = $stack[$stack.Count - 1]
                if ($context.kind -ceq 'object' -and $context.expectProperty) {
                    if ($escaped) {
                        throw 'Authority JSON property names must be unescaped ASCII literals'
                    }
                    $name = $Json.Substring($start, $cursor - $start)
                    if (-not $context.names.Add($name)) {
                        throw "Authority JSON contains duplicate property $name"
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

function Read-AuthoritySnapshot {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Initialization authority must be a regular non-reparse file'
    }
    $stream = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        [IO.FileShare]::Read)
    try {
        if ($stream.Length -gt 1MB) { throw 'Initialization authority exceeds the 1 MiB limit' }
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    } finally { $stream.Dispose() }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try { $json = $utf8.GetString($bytes) }
    catch { throw 'Initialization authority must be strict UTF-8 JSON' }
    Assert-NoDuplicateJsonProperties $json
    try { $value = $json | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Initialization authority is not valid JSON' }
    return [pscustomobject]@{ Path = $resolved; Sha256 = Get-Sha256 $bytes; Value = $value }
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

function Assert-BooleanValue {
    param($Value, [bool]$Expected, [string]$Label)
    if ($Value -isnot [bool] -or [bool]$Value -ne $Expected) {
        throw "$Label must be the JSON boolean $Expected"
    }
}

function Assert-IntegerValue {
    param($Value, [int]$Minimum, [int]$Maximum, [string]$Label)
    if (($Value -isnot [int] -and $Value -isnot [long]) -or
            [int64]$Value -lt $Minimum -or [int64]$Value -gt $Maximum) {
        throw "$Label must be a JSON integer between $Minimum and $Maximum"
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

function Test-SignerSet {
    param($Signers, [string]$Label, [switch]$RequireIdentity)
    Assert-JsonArray $Signers $Label
    $items = @($Signers)
    if ($items.Count -lt 1) { throw "$Label must contain at least one signer" }
    $thumbprints = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $certificateHashes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $identities = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($signer in $items) {
        if ($RequireIdentity) {
            Assert-ExactProperties $signer @(
                'identity', 'certificateThumbprint', 'certificateSha256'
            ) "$Label signer"
            Assert-JsonString $signer.identity "$Label signer identity"
            if ([string]::IsNullOrWhiteSpace([string]$signer.identity) -or
                    -not $identities.Add([string]$signer.identity)) {
                throw "$Label contains an invalid or duplicate identity"
            }
        }
        else {
            Assert-ExactProperties $signer @('certificateThumbprint', 'certificateSha256') `
                "$Label signer"
        }
        Assert-JsonString $signer.certificateThumbprint "$Label signer certificate thumbprint"
        Assert-JsonString $signer.certificateSha256 "$Label signer certificate SHA-256"
        $thumbprint = ([string]$signer.certificateThumbprint).Replace(' ', '').ToUpperInvariant()
        if ($thumbprint -cnotmatch '^[A-F0-9]{40,128}\z' -or
                -not $thumbprints.Add($thumbprint)) {
            throw "$Label contains an invalid or duplicate certificate thumbprint"
        }
        $certificateSha256 = [string]$signer.certificateSha256
        Assert-Sha256 $certificateSha256 "$Label certificate"
        if (-not $certificateHashes.Add($certificateSha256)) {
            throw "$Label contains a duplicate certificate SHA-256"
        }
    }
    return [pscustomobject]@{
        Thumbprints = @($thumbprints)
        CertificateHashes = @($certificateHashes)
        Identities = @($identities)
    }
}

$resolvedCandidate = [IO.Path]::GetFullPath($AuthorityPath)
if (-not $PlanOnly -and
        -not $resolvedCandidate.Equals($fixedAuthorityPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Executable authority verification uses only the fixed host-owned authority path'
}
if (-not $PlanOnly) {
    throw 'Executable authority verification requires fixed detached CMS and pinned enterprise root support'
}

$snapshot = Read-AuthoritySnapshot $AuthorityPath
if ($snapshot.Sha256 -cne $ExpectedAuthoritySha256) {
    throw 'Initialization authority bytes do not match the expected SHA-256'
}
$authority = $snapshot.Value
Assert-ExactProperties $authority @(
    'schemaVersion', 'authorityId', 'purpose', 'status', 'environmentId', 'hostId',
    'notBeforeUtc', 'notAfterUtc', 'allowedActions', 'initializerScriptSha256',
    'cmsEkuOid', 'revocationMode', 'planMaxValidityMinutes', 'requesterSigners',
    'approverSigners', 'executorSigners', 'receiptSigners', 'minimumApprovals',
    'requireDistinctIdentity', 'requireDistinctCertificate', 'initPlanSchemaSha256',
    'initReceiptSchemaSha256'
) 'Initialization authority'
$authorityStringFields = @(
    'authorityId', 'purpose', 'status', 'environmentId', 'hostId', 'notBeforeUtc',
    'notAfterUtc', 'initializerScriptSha256', 'cmsEkuOid', 'revocationMode',
    'initPlanSchemaSha256', 'initReceiptSchemaSha256'
)
foreach ($name in $authorityStringFields) {
    Assert-JsonString $authority.$name "Initialization authority $name"
}
Assert-JsonArray $authority.allowedActions 'Initialization authority allowedActions'
foreach ($action in $authority.allowedActions) {
    Assert-JsonString $action 'Initialization authority action'
}
if (($authority.schemaVersion -isnot [int] -and $authority.schemaVersion -isnot [long]) -or
        [int64]$authority.schemaVersion -ne 1 -or
        [string]$authority.authorityId -cnotmatch '^[a-z0-9][a-z0-9._-]{2,127}\z' -or
        [string]$authority.purpose -cne 'HOST_BOOTSTRAP_INITIALIZATION' -or
        [string]$authority.status -cne 'ACTIVE' -or
        [string]$authority.environmentId -cne $ExpectedEnvironmentId -or
        [string]$authority.hostId -cne $ExpectedHostId) {
    throw 'Initialization authority identity or purpose is invalid'
}
Assert-Sha256 ([string]$authority.hostId) 'Authority host ID'
Assert-Sha256 ([string]$authority.initializerScriptSha256) 'Authority initializer script'
if ([string]$authority.initializerScriptSha256 -cne $ExpectedInitializerScriptSha256) {
    throw 'Initialization authority does not pin the expected initializer script'
}
$notBefore = Convert-StrictUtcTimestamp ([string]$authority.notBeforeUtc) 'notBeforeUtc'
$notAfter = Convert-StrictUtcTimestamp ([string]$authority.notAfterUtc) 'notAfterUtc'
$now = [DateTimeOffset]::UtcNow
if ($notBefore -gt $now -or $notAfter -le $now -or $notAfter -le $notBefore) {
    throw 'Initialization authority is outside its active validity window'
}
$expectedActions = @(
    'INITIALIZE_HOST_BOOTSTRAP', 'ADOPT_EXTERNAL_CADDY', 'RECOVER_HOST_BOOTSTRAP'
)
$actualActions = @($authority.allowedActions)
if ($actualActions.Count -ne $expectedActions.Count -or
        @($actualActions | Where-Object { $_ -cnotin $expectedActions }).Count -gt 0 -or
        @($expectedActions | Where-Object { $_ -cnotin $actualActions }).Count -gt 0) {
    throw 'Initialization authority action set is invalid'
}
Assert-IntegerValue $authority.planMaxValidityMinutes 1 1440 'Plan maximum validity'
Assert-BooleanValue $authority.requireDistinctIdentity $true 'Distinct identity policy'
Assert-BooleanValue $authority.requireDistinctCertificate $true 'Distinct certificate policy'
if ([string]$authority.cmsEkuOid -cne '1.3.6.1.5.5.7.3.3' -or
        [string]$authority.revocationMode -cne 'ONLINE_FAIL_CLOSED_ENTIRE_CHAIN') {
    throw 'Initialization authority approval policy is invalid'
}
Assert-ExactProperties $authority.minimumApprovals @('requester', 'approver') `
    'Minimum approvals'
Assert-IntegerValue $authority.minimumApprovals.requester 1 1 'Requester approvals'
Assert-IntegerValue $authority.minimumApprovals.approver 1 1 'Approver approvals'
if ([int64]$authority.minimumApprovals.requester -ne 1 -or
        [int64]$authority.minimumApprovals.approver -ne 1) {
    throw 'Initialization authority requires exactly one requester and one approver signature'
}

$requesters = Test-SignerSet $authority.requesterSigners 'Requester' -RequireIdentity
$approvers = Test-SignerSet $authority.approverSigners 'Approver' -RequireIdentity
$null = Test-SignerSet $authority.executorSigners 'Executor'
$null = Test-SignerSet $authority.receiptSigners 'Receipt'
if (@($requesters.Thumbprints | Where-Object { $_ -in $approvers.Thumbprints }).Count -gt 0 -or
        @($requesters.CertificateHashes | Where-Object {
                $_ -in $approvers.CertificateHashes
            }).Count -gt 0 -or
        @($requesters.Identities | Where-Object { $_ -in $approvers.Identities }).Count -gt 0) {
    throw 'Requester and approver signer sets must be disjoint'
}
Assert-Sha256 ([string]$authority.initPlanSchemaSha256) 'Initialization plan schema'
Assert-Sha256 ([string]$authority.initReceiptSchemaSha256) 'Initialization receipt schema'

$report = [pscustomobject][ordered]@{
    status = 'INPUT_REQUIRED'
    executable = $false
    trustSource = 'CALLER_SUPPLIED_AUTHORITY_PLAN_ONLY'
    fixedAuthorityPath = $fixedAuthorityPath
    fixedAuthoritySignaturePath = $fixedAuthoritySignaturePath
    authorityId = [string]$authority.authorityId
    environmentId = [string]$authority.environmentId
    hostId = [string]$authority.hostId
    authoritySha256 = [string]$snapshot.Sha256
    cryptographicallyTrusted = $false
    hostFilesystemVerified = $false
    detachedCmsVerified = $false
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
