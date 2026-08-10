[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AuditPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedAuditPath = (Resolve-Path -LiteralPath $AuditPath).Path
if (-not (Test-Path -LiteralPath $resolvedAuditPath -PathType Leaf)) {
    throw 'Deployment audit log must be a regular file'
}

$expectedPreviousHash = ('0' * 64)
$eventCount = 0
$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $resolvedAuditPath -Encoding utf8) {
    $lineNumber++
    if ([string]::IsNullOrWhiteSpace($line) -or $line.Length -gt 1048576) {
        throw "Audit line $lineNumber is empty or exceeds the 1 MiB event limit"
    }
    try { $event = $line | ConvertFrom-Json }
    catch { throw "Audit line $lineNumber is not valid JSON" }
    foreach ($required in @(
            'schemaVersion', 'timestampUtc', 'status', 'actor', 'message', 'previousHash', 'hash'
        )) {
        if ($null -eq $event.PSObject.Properties[$required]) {
            throw "Audit line $lineNumber is missing $required"
        }
    }
    if ([int]$event.schemaVersion -ne 1 -or
            [string]$event.previousHash -cne $expectedPreviousHash -or
            [string]$event.hash -notmatch '^[0-9a-f]{64}$') {
        throw "Audit chain link $lineNumber is invalid"
    }
    $timestamp = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(
            [string]$event.timestampUtc,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$timestamp
        )) {
        throw "Audit line $lineNumber has an invalid timestamp"
    }
    $unsignedEvent = [ordered]@{}
    foreach ($property in $event.PSObject.Properties) {
        if ($property.Name -cne 'hash') { $unsignedEvent[$property.Name] = $property.Value }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($unsignedEvent | ConvertTo-Json -Compress -Depth 8))
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $actualHash = ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace(
            '-', ''
        ).ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
    if (-not $actualHash.Equals([string]$event.hash, [StringComparison]::Ordinal)) {
        throw "Audit line $lineNumber hash does not match its event bytes"
    }
    $expectedPreviousHash = [string]$event.hash
    $eventCount++
}

if ($eventCount -eq 0) { throw 'Deployment audit log contains no events' }
$report = [pscustomobject]@{
    status = 'PASS'
    eventCount = $eventCount
    finalHash = $expectedPreviousHash
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }
