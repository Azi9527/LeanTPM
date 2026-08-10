[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$BackupRoot,
    [Parameter(Mandatory)][string]$PackagePath,
    [Parameter(Mandatory)][uri]$HealthUri,
    [Parameter(Mandatory)][long]$ExpectedDatabaseBytes,
    [Parameter(Mandatory)][long]$ExpectedAttachmentBytes,
    [long]$MinimumFreeBytes = 5368709120,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Backend'
if ($ExpectedDatabaseBytes -lt 0 -or $ExpectedDatabaseBytes -gt 1125899906842624 -or
        $ExpectedAttachmentBytes -lt 0 -or
        $ExpectedAttachmentBytes -gt 1125899906842624 -or
        $MinimumFreeBytes -lt 1073741824 -or $MinimumFreeBytes -gt 1099511627776) {
    throw 'Capacity estimates or the safety reserve are outside the supported bounds'
}
if ($HealthUri.Scheme -ne 'http' -or
        $HealthUri.Host -notin @('127.0.0.1', 'localhost', '::1') -or
        $HealthUri.Port -lt 1 -or $HealthUri.Port -gt 65535) {
    throw 'Deployment preflight health URI must use an explicit loopback port'
}

$resolvedRoots = [ordered]@{}
foreach ($entry in ([ordered]@{
        install = $InstallRoot
        data = $DataRoot
        backup = $BackupRoot
    }).GetEnumerator()) {
    $resolved = (Resolve-Path -LiteralPath $entry.Value).Path.TrimEnd('\', '/')
    $item = Get-Item -LiteralPath $resolved -Force
    if (-not $item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$($entry.Key) root must be a real host-owned directory"
    }
    $resolvedRoots[$entry.Key] = $resolved
}
$packageItem = Get-Item -LiteralPath (Resolve-Path -LiteralPath $PackagePath).Path -Force
if ($packageItem.PSIsContainer -or
        ($packageItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $packageItem.Length -le 0) {
    throw 'Deployment package must be a non-empty regular file'
}

$requiredByVolume = @{}
function Add-VolumeRequirement {
    param([string]$Path, [long]$Bytes)

    $volumeRoot = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Path)).TrimEnd('\') + '\'
    if ([string]::IsNullOrWhiteSpace($volumeRoot)) {
        throw "Cannot resolve a storage volume for $Path"
    }
    if (-not $requiredByVolume.ContainsKey($volumeRoot)) {
        $requiredByVolume[$volumeRoot] = [int64]0
    }
    $requiredByVolume[$volumeRoot] = [int64]$requiredByVolume[$volumeRoot] + $Bytes
}

$packageBytes = [int64]$packageItem.Length
Add-VolumeRequirement $resolvedRoots.install ($packageBytes * 2)
Add-VolumeRequirement $resolvedRoots.data (($packageBytes * 2) + $ExpectedAttachmentBytes)
Add-VolumeRequirement $resolvedRoots.backup (
    ($ExpectedDatabaseBytes * 2) + ($ExpectedAttachmentBytes * 2)
)
$volumeReports = [System.Collections.Generic.List[object]]::new()
foreach ($volumeRoot in @($requiredByVolume.Keys | Sort-Object)) {
    $drive = New-Object IO.DriveInfo($volumeRoot)
    if (-not $drive.IsReady) { throw "Target volume is not ready: $volumeRoot" }
    $required = [int64]$requiredByVolume[$volumeRoot] + $MinimumFreeBytes
    $available = [int64]$drive.AvailableFreeSpace
    if ($available -lt $required) {
        throw "Insufficient free space on $volumeRoot; required=$required available=$available"
    }
    $volumeReports.Add([pscustomobject]@{
        volume = $volumeRoot
        requiredBytes = $required
        availableBytes = $available
    })
}

$service = Get-CimInstance -ClassName Win32_Service `
    -Filter "Name='$serviceId'" -ErrorAction Stop
if ($null -eq $service) { throw "$serviceId is not registered" }
if ([string]$service.State -notin @('Running', 'Stopped')) {
    throw "$serviceId must be in a stable Running or Stopped state before deployment"
}
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $HealthUri.Port -ErrorAction Stop)
$unexpectedAddress = @($listeners | Where-Object {
        [string]$_.LocalAddress -notin @('127.0.0.1', '::1')
    })
if ($unexpectedAddress.Count -gt 0) {
    throw 'Backend port is listening on a non-loopback address'
}

$allowedProcessIds = New-Object 'Collections.Generic.HashSet[uint32]'
if ([string]$service.State -eq 'Running') {
    if ([uint32]$service.ProcessId -eq 0) {
        throw 'Running LeanTPM service has no SCM process identity'
    }
    $null = $allowedProcessIds.Add([uint32]$service.ProcessId)
    $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    do {
        $added = $false
        foreach ($process in $processes) {
            if ($allowedProcessIds.Contains([uint32]$process.ParentProcessId) -and
                    $allowedProcessIds.Add([uint32]$process.ProcessId)) {
                $added = $true
            }
        }
    } while ($added)
}
$unexpectedListeners = @($listeners | Where-Object {
        -not $allowedProcessIds.Contains([uint32]$_.OwningProcess)
    })
if ($unexpectedListeners.Count -gt 0 -or
        ([string]$service.State -eq 'Running' -and $listeners.Count -eq 0)) {
    throw 'Backend port is absent or owned by an unexpected process'
}

$report = [pscustomobject]@{
    status = 'PASS'
    serviceId = $serviceId
    serviceState = [string]$service.State
    backendPort = $HealthUri.Port
    packageBytes = $packageBytes
    expectedDatabaseBytes = $ExpectedDatabaseBytes
    expectedAttachmentBytes = $ExpectedAttachmentBytes
    volumes = @($volumeReports)
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
