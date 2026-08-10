[CmdletBinding()]
param(
    [string]$ObservationPath = '',
    [switch]$PlanOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$requiredPorts = @(80, 443, 18080, 3306, 15173)
$criticalPaths = @(
    'D:\LeanTPM\packages',
    'D:\LeanTPM\App',
    'D:\LeanTPM\Runtime',
    'D:\LeanTPM\data',
    'D:\LeanTPM\backups',
    'D:\LeanTPM\logs',
    'D:\LeanTPM\tools',
    'D:\LeanTPM\temp'
)

function Get-ExecutablePathFromServiceCommandLine {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$CommandLine)
    $trimmed = $CommandLine.Trim()
    if ($trimmed.StartsWith('"')) {
        $closing = $trimmed.IndexOf('"', 1)
        if ($closing -le 1) { return '' }
        return $trimmed.Substring(1, $closing - 1)
    }
    $space = $trimmed.IndexOf(' ')
    if ($space -lt 0) { return $trimmed }
    return $trimmed.Substring(0, $space)
}

function Get-FileObservation {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or
            -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{ path = $Path; sha256 = ''; version = '' }
    }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Deployment prerequisite executable cannot be a reparse point: $Path"
    }
    return [pscustomobject]@{
        path = $item.FullName
        sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).
            Hash.ToLowerInvariant()
        version = [string]$item.VersionInfo.ProductVersion
    }
}

function Get-ServiceObservation {
    param([Parameter(Mandatory)][string]$ServiceName)
    $services = @(Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='$ServiceName'" -ErrorAction Stop)
    if ($services.Count -ne 1) {
        return [pscustomobject]@{
            serviceName = $ServiceName
            state = ''
            startMode = ''
            path = ''
            sha256 = ''
        }
    }
    $service = $services[0]
    $path = Get-ExecutablePathFromServiceCommandLine ([string]$service.PathName)
    $file = Get-FileObservation $path
    return [pscustomobject]@{
        serviceName = $ServiceName
        state = [string]$service.State
        startMode = [string]$service.StartMode
        path = [string]$file.path
        sha256 = [string]$file.sha256
    }
}

function Read-FixtureObservation {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if ($item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'ObservationPath must be a regular non-reparse file'
    }
    $stream = [IO.File]::Open(
        $resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read
    )
    try {
        if ($stream.Length -gt 2MB) { throw 'ObservationPath exceeds the 2 MiB limit' }
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $memory.Dispose() }
    }
    finally { $stream.Dispose() }
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    try { $json = $utf8.GetString($bytes) }
    catch { throw 'ObservationPath must contain strict UTF-8 JSON' }
    try { $value = $json | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'ObservationPath is not valid JSON' }
    if (($value.schemaVersion -isnot [int] -and $value.schemaVersion -isnot [long]) -or
            [int64]$value.schemaVersion -ne 1) {
        throw 'ObservationPath schemaVersion is invalid'
    }
    foreach ($component in @('java', 'caddy', 'mysql')) {
        if ([string]$value.$component.sha256 -cnotmatch '^[a-f0-9]{64}\z') {
            throw "ObservationPath $component SHA-256 is invalid"
        }
    }
    return $value
}

function Get-LiveObservation {
    $volume = Get-CimInstance -ClassName Win32_Volume -Filter "DriveLetter='D:'" `
        -ErrorAction Stop
    if ($null -eq $volume) { throw 'D volume was not found' }
    $javaPath = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    else {
        $command = Get-Command java.exe -ErrorAction Stop
        [string]$command.Source
    }
    $javaFile = Get-FileObservation $javaPath
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $requiredPorts `
            -ErrorAction Stop | ForEach-Object {
            [pscustomobject]@{
                address = [string]$_.LocalAddress
                port = [int]$_.LocalPort
                pid = [int]$_.OwningProcess
            }
        } | Sort-Object port, address, pid)
    return [pscustomobject]@{
        schemaVersion = 1
        collectedAtUtc = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        volume = [pscustomobject]@{
            drive = [string]$volume.DriveLetter
            fileSystem = [string]$volume.FileSystem
            freeBytes = [int64]$volume.FreeSpace
        }
        java = [pscustomobject]@{
            path = [string]$javaFile.path
            sha256 = [string]$javaFile.sha256
            version = [string]$javaFile.version
        }
        caddy = Get-ServiceObservation 'caddy'
        mysql = Get-ServiceObservation 'MySQL80'
        listeners = $listeners
        paths = @($criticalPaths | ForEach-Object {
                [pscustomobject]@{
                    path = $_
                    exists = Test-Path -LiteralPath $_
                }
            })
    }
}

if ($PlanOnly) {
    if ([string]::IsNullOrWhiteSpace($ObservationPath)) {
        throw 'PlanOnly requires ObservationPath'
    }
    $observation = Read-FixtureObservation $ObservationPath
    $mode = 'PLAN_ONLY_FIXTURE'
}
else {
    if (-not [string]::IsNullOrWhiteSpace($ObservationPath)) {
        throw 'Live discovery does not accept caller-supplied ObservationPath'
    }
    $observation = Get-LiveObservation
    $mode = 'LIVE'
}

$blockers = [Collections.Generic.List[string]]::new()
$blockers.Add('JAVA_SERVER_HASH_NOT_APPROVED')
$blockers.Add('CADDY_SERVER_HASH_NOT_APPROVED')
$blockers.Add('MYSQL_SERVER_UUID_NOT_VERIFIED')
$blockers.Add('REMOTE_WRITE_AUTHORIZATION_NOT_GRANTED')
foreach ($listener in @($observation.listeners)) {
    if ([int]$listener.port -in @(18080, 3306, 15173) -and
            [string]$listener.address -notin @('127.0.0.1', '::1')) {
        $blockers.Add("NON_LOOPBACK_PORT_$([int]$listener.port)")
    }
}

$report = [pscustomobject][ordered]@{
    schemaVersion = 1
    status = 'INPUT_REQUIRED'
    readOnly = $true
    discoveryMode = $mode
    collectedAtUtc = [string]$observation.collectedAtUtc
    volume = $observation.volume
    java = $observation.java
    caddy = $observation.caddy
    mysql = $observation.mysql
    listeners = @($observation.listeners)
    paths = @($observation.paths)
    requiredPorts = @($requiredPorts)
    blockers = @($blockers)
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 6 -Compress }
else { $report | Format-List }
