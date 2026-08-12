[CmdletBinding()]
param(
    [switch]$PlanOnly,
    [string]$ConfirmedPlanSha256 = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$releaseFrom = '1.0.3-20260811.1'
$releaseTo = '1.0.4-20260812.1'
$versionFrom = '1.0.3'
$versionTo = '1.0.4'
$schemaVersion = 52
$productionUuid = '007df095-92ef-11f1-8f53-00163e059faa'
$releaseZip = 'D:\LeanTPM\temp\LeanTPM-1.0.4-20260812.1-backend-web-v52.v1.zip'
$releaseZipBytes = 0
$releaseZipSha256 = '__RELEASE_ZIP_SHA256__'
$releaseManifestBytes = 0
$releaseManifestSha256 = '__RELEASE_MANIFEST_SHA256__'
$sourceCommit = '__SOURCE_COMMIT__'
$currentReleaseRoot = "D:\LeanTPM\App\releases\$releaseFrom"
$targetReleaseRoot = "D:\LeanTPM\App\releases\$releaseTo"
$targetReleasePartial = $targetReleaseRoot + '.partial'
$currentJar = Join-Path $currentReleaseRoot 'payload\backend\leantpm-backend.jar'
$targetJar = Join-Path $targetReleaseRoot 'payload\backend\leantpm-backend.jar'
$currentWebRoot = Join-Path $currentReleaseRoot 'payload\web'
$targetWebRoot = Join-Path $targetReleaseRoot 'payload\web'
$starterPath = 'D:\LeanTPM\App\service\Start-LeanTpmBackend-Rapid.ps1'
$caddyPath = 'D:\LeanTPM\shared\config\Caddyfile'
$caddyExe = 'D:\LeanTPM\tools\caddy\caddy.exe'
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldumpExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$expectedBackendServicePath = 'D:\LeanTPM\App\service\LeanTPM.Backend.exe'
$expectedCaddyServicePath = 'D:\LeanTPM\tools\caddy\caddy.exe run --environ --config D:\LeanTPM\shared\config\Caddyfile --adapter caddyfile'
$backendServiceName = 'LeanTPM.Backend'
$caddyServiceName = 'caddy'
$mysqlServiceName = 'MySQL80'
$backupRoot = 'D:\LeanTPM\backups\direct-predeploy-1.0.4-20260812-01'
$evidenceRoot = 'D:\LeanTPM\Runtime\logs\direct-deployment-1.0.4-20260812-01'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$rootPassword = $null
$writeStarted = $false
$backupManifestSha256 = ''
$starterBackup = Join-Path $backupRoot 'Start-LeanTpmBackend-Rapid.ps1'
$caddyBackup = Join-Path $backupRoot 'Caddyfile'

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function Convert-ToSafeError {
    param([object]$ErrorRecord)
    $text = [string]$ErrorRecord
    if (-not [string]::IsNullOrEmpty($script:rootPassword)) {
        $text = $text.Replace($script:rootPassword, '[REDACTED]')
    }
    if ($text.Length -gt 1200) { return $text.Substring(0, 1200) }
    return $text
}

function Assert-FileContract {
    param([string]$Path, [long]$Bytes = -1, [string]$Sha256 = '')
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.PSIsContainer) { throw "Expected a file: $Path" }
    if ($Bytes -ge 0 -and [long]$item.Length -ne $Bytes) { throw "File bytes changed: $Path" }
    if (-not [string]::IsNullOrWhiteSpace($Sha256) -and (Get-Sha256 $Path) -cne $Sha256) {
        throw "File SHA256 changed: $Path"
    }
}

function Assert-DirectoryHasNoReparsePoint {
    param([string]$Root)
    $queue = New-Object Collections.Generic.Queue[string]
    $queue.Enqueue($Root)
    while ($queue.Count -gt 0) {
        $path = $queue.Dequeue()
        foreach ($entry in @(Get-ChildItem -LiteralPath $path -Force -ErrorAction Stop)) {
            if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse point is not allowed: $($entry.FullName)"
            }
            if ($entry.PSIsContainer) { $queue.Enqueue($entry.FullName) }
        }
    }
}

function Assert-RestrictedDirectoryAcl {
    param([string]$Path, [switch]$RequireNetworkServiceRead)
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    if (-not $acl.AreAccessRulesProtected) { throw "ACL inheritance is still enabled: $Path" }
    $allowed = @('S-1-5-18', 'S-1-5-32-544')
    if ($RequireNetworkServiceRead) { $allowed += 'S-1-5-20' }
    $seen = @{}
    foreach ($rule in @($acl.Access)) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or $allowed -notcontains $sid) {
            throw "Unexpected ACL entry on ${Path}: $sid"
        }
        $seen[$sid] = $true
    }
    foreach ($sid in $allowed) {
        if (-not $seen.ContainsKey($sid)) { throw "Required ACL entry is missing on ${Path}: $sid" }
    }
}

function Set-AndAssertRestrictedDirectoryAcl {
    param([string]$Path, [switch]$NetworkServiceRead)
    [IO.Directory]::CreateDirectory($Path) | Out-Null
    $grants = @('*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
    if ($NetworkServiceRead) { $grants += '*S-1-5-20:(OI)(CI)RX' }
    $output = @(& icacls.exe $Path /inheritance:r /grant:r $grants /T /C 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('Restricted ACL failed: ' + ($output -join '; ')) }
    Assert-RestrictedDirectoryAcl -Path $Path -RequireNetworkServiceRead:$NetworkServiceRead
}

function New-BackendStarterText {
    param(
        [string]$Source,
        [string]$CurrentJar,
        [string]$TargetJar,
        [string]$VersionFrom,
        [string]$VersionTo,
        [int]$SchemaVersion
    )
    $oldVersion = "`$env:LEANTPM_RELEASE_VERSION = '$VersionFrom'"
    $newVersion = "`$env:LEANTPM_RELEASE_VERSION = '$VersionTo'"
    $schemaLine = "`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '$SchemaVersion'"
    $flywayLine = "`$env:LEANTPM_FLYWAY_ENABLED = 'false'"
    foreach ($contract in @(
        [ordered]@{ value = $oldVersion; label = 'source release version' },
        [ordered]@{ value = $schemaLine; label = 'database schema version' },
        [ordered]@{ value = $flywayLine; label = 'Flyway disabled' },
        [ordered]@{ value = $CurrentJar; label = 'source Backend JAR' }
    )) {
        if ([regex]::Matches($Source, [regex]::Escape([string]$contract.value)).Count -ne 1) {
            throw "Starter must contain exactly one $($contract.label) contract"
        }
    }
    $result = $Source.Replace($oldVersion, $newVersion).Replace($CurrentJar, $TargetJar)
    if ([regex]::Matches($result, [regex]::Escape($newVersion)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($schemaLine)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($flywayLine)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($TargetJar)).Count -ne 1 -or
            $result.Contains($CurrentJar)) {
        throw 'Generated starter did not satisfy the target version, schema, and JAR contracts'
    }
    [void][scriptblock]::Create($result)
    return $result
}

function New-CaddyText {
    param([string]$Source, [string]$CurrentRoot, [string]$TargetRoot)
    $currentForward = $CurrentRoot.Replace('\', '/')
    $targetForward = $TargetRoot.Replace('\', '/')
    $count = [regex]::Matches($Source, [regex]::Escape($currentForward)).Count
    if ($count -ne 1) { throw 'Caddyfile must contain exactly one source Web root' }
    $result = $Source.Replace($currentForward, $targetForward)
    if (-not $result.Contains($targetForward) -or $result.Contains($currentForward)) {
        throw 'Generated Caddyfile did not satisfy the target Web root contract'
    }
    return $result
}

function Invoke-MySqlRows {
    param([string]$Sql)
    $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:rootPassword, 'Process')
        $output = @(& $mysqlExe --batch --raw --skip-column-names --host=127.0.0.1 --port=3306 --user=root --database=leantpm --execute=$Sql 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ($output -join '; ') }
        return @($output | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process')
    }
}

function Get-DatabaseState {
    $sql = "SELECT @@server_uuid, VERSION(), COALESCE(MAX(CASE WHEN success=1 THEN CAST(version AS UNSIGNED) END),0), SUM(CASE WHEN success=0 THEN 1 ELSE 0 END), SUM(CASE WHEN success=1 AND CAST(version AS UNSIGNED)>52 THEN 1 ELSE 0 END), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='leantpm' AND table_type='BASE TABLE'), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='leantpm' AND table_type='VIEW') FROM flyway_schema_history;"
    $rows = @(Invoke-MySqlRows -Sql $sql)
    if ($rows.Count -ne 1) { throw 'Database identity query returned an unexpected row count' }
    $fields = @($rows[0] -split "`t", -1)
    if ($fields.Count -ne 7) { throw 'Database identity query returned an unexpected field count' }
    return [ordered]@{
        serverUuid = $fields[0]
        mysqlVersion = $fields[1]
        schemaVersion = [int]$fields[2]
        failedMigrations = [int]$fields[3]
        versionsAbove52 = [int]$fields[4]
        baseTableCount = [int]$fields[5]
        viewCount = [int]$fields[6]
        tableCount = [int]$fields[5] + [int]$fields[6]
    }
}

function Assert-DatabaseV52 {
    param($State)
    if ([string]$State.serverUuid -cne $productionUuid -or
            [int]$State.schemaVersion -ne $schemaVersion -or
            [int]$State.failedMigrations -ne 0 -or
            [int]$State.versionsAbove52 -ne 0 -or
            [int]$State.viewCount -ne 0) {
        throw 'Production database is not the approved unchanged V52 instance'
    }
}

function Get-HttpJson {
    param([string]$Uri)
    return Invoke-RestMethod -Uri $Uri -TimeoutSec 10 -ErrorAction Stop
}

function Assert-ApplicationState {
    param([string]$ExpectedVersion)
    $ready = Get-HttpJson 'http://127.0.0.1:18080/actuator/health/readiness'
    $info = Get-HttpJson 'http://127.0.0.1:18080/actuator/info'
    $branding = Get-HttpJson 'http://127.0.0.1:18080/api/v1/public/branding'
    if ([string]$ready.status -cne 'UP' -or
            [string]$info.app.version -cne $ExpectedVersion -or
            [int]$info.app.'database-schema-version' -ne $schemaVersion -or
            [string]$branding.code -cne 'OK') {
        throw "Application state is not $ExpectedVersion / V$schemaVersion / UP"
    }
}

function Get-JarArgument {
    param([string]$CommandLine)
    $matches = [regex]::Matches($CommandLine, '(?i)(?:^|\s)-jar\s+(?:"(?<jar>[^"]+)"|''(?<jar>[^'']+)''|(?<jar>\S+))')
    if ($matches.Count -ne 1) { throw 'Backend Java command line must contain exactly one -jar argument' }
    $jar = [string]$matches[0].Groups['jar'].Value
    if ([string]::IsNullOrWhiteSpace($jar)) { throw 'Backend Java -jar argument was empty' }
    return $jar
}

function Assert-BackendProcessBinding {
    param([string]$ExpectedJar)
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or [string]$listeners[0].LocalAddress -cne '127.0.0.1') {
        throw 'Backend must own exactly one loopback 127.0.0.1:18080 listener'
    }
    $processId = [int]$listeners[0].OwningProcess
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
    if ([string]$process.Name -ine 'java.exe' -or
            [string]::IsNullOrWhiteSpace([string]$process.CommandLine) -or
            (Get-JarArgument ([string]$process.CommandLine)) -ine $ExpectedJar) {
        throw "Backend listener is not bound to the expected JAR: $ExpectedJar"
    }
}

function Assert-PublicState {
    param([string]$ExpectedJar)
    $caddy = Get-ServiceEvidence $caddyServiceName
    if ($caddy.state -cne 'Running') { throw 'Caddy service is not running' }
    $homeCode = [string](& curl.exe --max-time 10 --resolve '8.163.66.164:80:127.0.0.1' -sS -o NUL -w '%{http_code}' 'http://8.163.66.164/')
    if ($LASTEXITCODE -ne 0 -or $homeCode -cne '200') { throw "Local public HTTP verification failed: $homeCode" }
    $brandingText = [string](& curl.exe --max-time 10 --resolve '8.163.66.164:80:127.0.0.1' -sS 'http://8.163.66.164/api/v1/public/branding')
    if ($LASTEXITCODE -ne 0) { throw 'Local public branding request failed' }
    $branding = $brandingText | ConvertFrom-Json
    if ([string]$branding.code -cne 'OK') { throw 'Local public branding response was not OK' }
    Assert-BackendProcessBinding -ExpectedJar $ExpectedJar
}

function Wait-ApplicationState {
    param([string]$ExpectedVersion, [string]$ExpectedJar, [int]$TimeoutSeconds = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            Assert-ApplicationState -ExpectedVersion $ExpectedVersion
            Assert-BackendProcessBinding -ExpectedJar $ExpectedJar
            return
        } catch {
            Start-Sleep -Seconds 3
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Backend did not reach $ExpectedVersion / V$schemaVersion readiness before timeout"
}

function Get-ServiceEvidence {
    param([string]$Name)
    $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction Stop
    return [ordered]@{
        name = $Name
        state = [string]$service.State
        startMode = [string]$service.StartMode
        startName = [string]$service.StartName
        processId = [int]$service.ProcessId
        pathName = [string]$service.PathName
    }
}

function Assert-ServiceContracts {
    $backend = Get-ServiceEvidence $backendServiceName
    $caddy = Get-ServiceEvidence $caddyServiceName
    $mysql = Get-ServiceEvidence $mysqlServiceName
    if ($backend.state -cne 'Running' -or $caddy.state -cne 'Running' -or $mysql.state -cne 'Running') {
        throw 'Backend, Caddy, and MySQL must all be running before PlanOnly'
    }
    if ($backend.startName -cne 'NT AUTHORITY\NetworkService' -or
            $mysql.startName -cne 'NT AUTHORITY\NetworkService' -or
            -not ($caddy.startName -ieq 'LocalSystem' -or $caddy.startName -ieq 'NT AUTHORITY\SYSTEM')) {
        throw 'Production service identity changed'
    }
    if ($backend.pathName -ine $expectedBackendServicePath -or $caddy.pathName -ine $expectedCaddyServicePath) {
        throw 'Production service executable binding changed'
    }
    return [ordered]@{ backend = $backend; caddy = $caddy; mysql = $mysql }
}

function Get-ReleaseManifestFromZip {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($releaseZip)
    try {
        $entry = $zip.GetEntry('direct-release-manifest.json')
        if ($null -eq $entry) { throw 'Release manifest is missing from ZIP' }
        $stream = $entry.Open()
        try {
            $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
    if ($releaseManifestBytes -gt 0 -and [Text.Encoding]::UTF8.GetByteCount($text) -ne $releaseManifestBytes) {
        throw 'Release manifest byte contract failed'
    }
    if ($releaseManifestSha256 -notlike '__*' -and (Get-TextSha256 $text) -cne $releaseManifestSha256) {
        throw 'Release manifest SHA256 contract failed'
    }
    $manifest = $text | ConvertFrom-Json
    if ([string]$manifest.releaseId -cne $releaseTo -or
            [string]$manifest.productVersion -cne $versionTo -or
            [bool]$manifest.scope.appIncluded -or
            [bool]$manifest.scope.databaseMigrationsIncluded -or
            [int]$manifest.database.schemaFrom -ne 52 -or
            [int]$manifest.database.schemaTo -ne 52) {
        throw 'Release manifest is not the approved 1.0.4 application-only V52 package'
    }
    return $manifest
}

function Assert-BackendJarSchemaCeiling {
    param([string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $versions = @()
        foreach ($entry in @($archive.Entries)) {
            if ($entry.FullName -match '^BOOT-INF/classes/db/migration/V([0-9]+)__.+\.sql$') {
                $versions += [int]$Matches[1]
            }
        }
    } finally { $archive.Dispose() }
    if ($versions.Count -eq 0 -or ($versions | Measure-Object -Maximum).Maximum -ne 52) {
        throw 'Backend JAR migration ceiling must be exactly V52'
    }
}

function Expand-AndVerifyRelease {
    param($Manifest)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if ((Test-Path -LiteralPath $targetReleaseRoot) -or (Test-Path -LiteralPath $targetReleasePartial)) {
        throw 'Target release or partial directory already exists'
    }
    [IO.Compression.ZipFile]::ExtractToDirectory($releaseZip, $targetReleasePartial)
    Assert-DirectoryHasNoReparsePoint $targetReleasePartial
    $actualFiles = @(Get-ChildItem -LiteralPath $targetReleasePartial -File -Recurse)
    if ($actualFiles.Count -ne (@($Manifest.artifacts).Count + 1)) { throw 'Extracted release file count changed' }
    foreach ($artifact in @($Manifest.artifacts)) {
        $path = Join-Path $targetReleasePartial ([string]$artifact.path).Replace('/', '\')
        Assert-FileContract -Path $path -Bytes ([long]$artifact.bytes) -Sha256 ([string]$artifact.sha256)
    }
    Assert-BackendJarSchemaCeiling -Path (Join-Path $targetReleasePartial 'payload\backend\leantpm-backend.jar')
    Move-Item -LiteralPath $targetReleasePartial -Destination $targetReleaseRoot -ErrorAction Stop
    $aclOutput = @(& icacls.exe $targetReleaseRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-20:(OI)(CI)RX' /T /C 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ('Target release ACL failed: ' + ($aclOutput -join '; ')) }
    foreach ($artifact in @($Manifest.artifacts)) {
        $path = Join-Path $targetReleaseRoot ([string]$artifact.path).Replace('/', '\')
        Assert-FileContract -Path $path -Bytes ([long]$artifact.bytes) -Sha256 ([string]$artifact.sha256)
    }
}

function Get-PlanCore {
    Assert-FileContract $releaseZip
    if ($releaseZipBytes -gt 0 -and (Get-Item -LiteralPath $releaseZip).Length -ne $releaseZipBytes) { throw 'Release ZIP bytes changed' }
    if ($releaseZipSha256 -notlike '__*' -and (Get-Sha256 $releaseZip) -cne $releaseZipSha256) { throw 'Release ZIP SHA256 changed' }
    foreach ($path in @($currentJar, (Join-Path $currentWebRoot 'index.html'), $starterPath, $caddyPath, $caddyExe, $mysqlExe, $mysqldumpExe)) {
        Assert-FileContract $path
    }
    foreach ($path in @($targetReleaseRoot, $targetReleasePartial, $backupRoot, $evidenceRoot)) {
        if (Test-Path -LiteralPath $path) { throw "Plan target must be absent: $path" }
    }
    $manifest = Get-ReleaseManifestFromZip
    $starterText = [IO.File]::ReadAllText($starterPath)
    [void](New-BackendStarterText -Source $starterText -CurrentJar $currentJar -TargetJar $targetJar -VersionFrom $versionFrom -VersionTo $versionTo -SchemaVersion $schemaVersion)
    $caddyText = [IO.File]::ReadAllText($caddyPath)
    [void](New-CaddyText -Source $caddyText -CurrentRoot $currentWebRoot -TargetRoot $targetWebRoot)
    $database = Get-DatabaseState
    Assert-DatabaseV52 $database
    Assert-ApplicationState $versionFrom
    Assert-BackendProcessBinding -ExpectedJar $currentJar
    $services = Assert-ServiceContracts
    return [ordered]@{
        schemaVersion = 1
        mode = 'DIRECT_APPLICATION_ONLY_V52'
        releaseFrom = $releaseFrom
        releaseTo = $releaseTo
        sourceCommit = $sourceCommit
        executor = [ordered]@{ path = $PSCommandPath; sha256 = Get-Sha256 $PSCommandPath }
        releaseZip = [ordered]@{ path = $releaseZip; bytes = (Get-Item $releaseZip).Length; sha256 = Get-Sha256 $releaseZip }
        scope = [ordered]@{ backendIncluded = $true; webIncluded = $true; databaseMigrationsIncluded = $false; appIncluded = $false }
        database = [ordered]@{ serverUuid = $database.serverUuid; schemaFrom = 52; schemaTo = 52; failedMigrations = $database.failedMigrations; versionsAbove52 = $database.versionsAbove52; runtimeFlywayEnabled = $false; modifiedByDeployment = $false; backupRequired = $true; tableCount = $database.tableCount; baseTableCount = $database.baseTableCount; viewCount = $database.viewCount }
        production = [ordered]@{
            currentJarSha256 = Get-Sha256 $currentJar
            currentWebIndexSha256 = Get-Sha256 (Join-Path $currentWebRoot 'index.html')
            starterSha256 = Get-Sha256 $starterPath
            caddyfileSha256 = Get-Sha256 $caddyPath
            services = $services
        }
        targets = [ordered]@{ backupRoot = $backupRoot; evidenceRoot = $evidenceRoot; targetReleaseRoot = $targetReleaseRoot }
        rollback = 'APPLICATION_ROLLBACK_TO_1.0.3_V52'
        actions = @('VERIFY_PLAN_SHA256','STOP_BACKEND_AND_CADDY','BACKUP_CURRENT_V52_AND_APPLICATION_BINDINGS','CREATE_1.0.4_RELEASE','SWITCH_BACKEND','VERIFY_1.0.4_V52','SWITCH_WEB','VERIFY_HTTP')
    }
}

function New-Backup {
    Set-AndAssertRestrictedDirectoryAcl -Path $backupRoot
    Set-AndAssertRestrictedDirectoryAcl -Path $evidenceRoot
    Copy-Item -LiteralPath $starterPath -Destination $starterBackup -ErrorAction Stop
    Copy-Item -LiteralPath $caddyPath -Destination $caddyBackup -ErrorAction Stop
    $dumpPath = Join-Path $backupRoot 'leantpm-v52.sql'
    $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:rootPassword, 'Process')
        & $mysqldumpExe --host=127.0.0.1 --port=3306 --user=root --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF --result-file=$dumpPath leantpm
        if ($LASTEXITCODE -ne 0) { throw 'Current V52 mysqldump failed' }
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process')
    }
    $dump = Get-Item -LiteralPath $dumpPath -ErrorAction Stop
    $createTableCount = 0
    $reader = [IO.File]::OpenText($dumpPath)
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            if ($line.StartsWith('CREATE TABLE ')) { $createTableCount++ }
        }
    } finally { $reader.Dispose() }
    if ($dump.Length -lt 100000 -or $createTableCount -lt [int]$script:plan.database.baseTableCount -or [int]$script:plan.database.viewCount -ne 0) {
        throw 'Current V52 database backup verification failed'
    }
    $backupManifest = [ordered]@{
        schemaVersion = 1
        planSha256 = $script:planSha256
        database = [ordered]@{ schemaVersion = 52; modifiedByDeployment = $false; backupRequired = $true; dumpPath = $dumpPath; bytes = [long]$dump.Length; sha256 = Get-Sha256 $dumpPath; createTableCount = $createTableCount; baseTableCount = [int]$script:plan.database.baseTableCount; viewCount = 0 }
        starter = [ordered]@{ path = $starterBackup; sha256 = Get-Sha256 $starterBackup }
        caddy = [ordered]@{ path = $caddyBackup; sha256 = Get-Sha256 $caddyBackup }
        currentRelease = [ordered]@{ releaseId = $releaseFrom; sourcePath = $currentReleaseRoot; jarSha256 = Get-Sha256 $currentJar; webIndexSha256 = Get-Sha256 (Join-Path $currentWebRoot 'index.html'); copied = $false; immutable = $true }
        secrets = [ordered]@{ copied = $false; contentRead = $false; unchangedByDeployment = $true }
    }
    $backupManifestPath = Join-Path $backupRoot 'backup-manifest.json'
    [IO.File]::WriteAllText($backupManifestPath, ($backupManifest | ConvertTo-Json -Depth 6), $utf8NoBom)
    $script:backupManifestSha256 = Get-Sha256 $backupManifestPath
    if ($script:backupManifestSha256 -notmatch '^[0-9a-f]{64}$') { throw 'Backup manifest SHA256 was invalid' }
    Assert-RestrictedDirectoryAcl -Path $backupRoot
    Assert-RestrictedDirectoryAcl -Path $evidenceRoot
    Write-Output ('BACKUP_VERIFIED=' + $script:backupManifestSha256)
}

function Restore-ApplicationV103 {
    $errors = @()
    $bindingsRestored = $false
    foreach ($name in @($backendServiceName, $caddyServiceName)) {
        try { Stop-Service -Name $name -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    }
    try {
        $backupManifestPath = Join-Path $backupRoot 'backup-manifest.json'
        if ($script:backupManifestSha256 -notmatch '^[0-9a-f]{64}$' -or (Get-Sha256 $backupManifestPath) -cne $script:backupManifestSha256) {
            throw 'Backup manifest changed after creation'
        }
        $backupManifest = Get-Content -LiteralPath $backupManifestPath -Raw -ErrorAction Stop | ConvertFrom-Json
        if ([int]$backupManifest.schemaVersion -ne 1 -or
                [string]$backupManifest.planSha256 -cne $script:planSha256 -or
                [string]$backupManifest.currentRelease.releaseId -cne $releaseFrom -or
                [string]$backupManifest.currentRelease.sourcePath -cne $currentReleaseRoot -or
                [string]$backupManifest.starter.path -cne $starterBackup -or
                [string]$backupManifest.caddy.path -cne $caddyBackup -or
                [string]$backupManifest.starter.sha256 -notmatch '^[0-9a-f]{64}$' -or
                [string]$backupManifest.caddy.sha256 -notmatch '^[0-9a-f]{64}$' -or
                [string]$backupManifest.currentRelease.jarSha256 -notmatch '^[0-9a-f]{64}$' -or
                [string]$backupManifest.currentRelease.webIndexSha256 -notmatch '^[0-9a-f]{64}$') {
            throw 'Backup manifest contract is invalid'
        }
        Assert-FileContract -Path $starterBackup -Sha256 ([string]$backupManifest.starter.sha256)
        Assert-FileContract -Path $caddyBackup -Sha256 ([string]$backupManifest.caddy.sha256)
        Assert-FileContract -Path $currentJar -Sha256 ([string]$backupManifest.currentRelease.jarSha256)
        Assert-FileContract -Path (Join-Path $currentWebRoot 'index.html') -Sha256 ([string]$backupManifest.currentRelease.webIndexSha256)
        Copy-Item -LiteralPath $starterBackup -Destination $starterPath -Force -ErrorAction Stop
        Copy-Item -LiteralPath $caddyBackup -Destination $caddyPath -Force -ErrorAction Stop
        if ((Get-Sha256 $starterPath) -cne [string]$script:plan.production.starterSha256 -or
                (Get-Sha256 $caddyPath) -cne [string]$script:plan.production.caddyfileSha256 -or
                (Get-Sha256 $currentJar) -cne [string]$script:plan.production.currentJarSha256 -or
                (Get-Sha256 (Join-Path $currentWebRoot 'index.html')) -cne [string]$script:plan.production.currentWebIndexSha256) {
            throw 'Restored live application bindings do not match the approved 1.0.3 Plan'
        }
        $bindingsRestored = $true
    } catch { $errors += (Convert-ToSafeError $_) }
    if (-not $bindingsRestored) {
        try {
            if ((Get-Sha256 $starterPath) -ceq [string]$script:plan.production.starterSha256 -and
                    (Get-Sha256 $caddyPath) -ceq [string]$script:plan.production.caddyfileSha256 -and
                    (Get-Sha256 $currentJar) -ceq [string]$script:plan.production.currentJarSha256 -and
                    (Get-Sha256 (Join-Path $currentWebRoot 'index.html')) -ceq [string]$script:plan.production.currentWebIndexSha256) {
                $bindingsRestored = $true
            }
        } catch { $errors += (Convert-ToSafeError $_) }
    }
    if (-not $bindingsRestored) {
        foreach ($name in @($backendServiceName, $caddyServiceName)) {
            try { Stop-Service -Name $name -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
        }
        $errors += 'Approved 1.0.3 live bindings were not restored; Backend and Caddy remain stopped'
        return @($errors)
    }
try {
    Assert-DatabaseV52 (Get-DatabaseState)
} catch {
    $errors += (Convert-ToSafeError $_)
    foreach ($name in @($backendServiceName, $caddyServiceName)) {
        try { Stop-Service -Name $name -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    }
    return @($errors)
}
try {
    Start-Service -Name $backendServiceName -ErrorAction Stop
    Wait-ApplicationState -ExpectedVersion $versionFrom -ExpectedJar $currentJar -TimeoutSeconds 90
} catch {
    $errors += (Convert-ToSafeError $_)
    foreach ($name in @($backendServiceName, $caddyServiceName)) {
        try { Stop-Service -Name $name -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    }
    return @($errors)
}
try {
    Start-Service -Name $caddyServiceName -ErrorAction Stop
    Start-Sleep -Seconds 5
    Assert-PublicState -ExpectedJar $currentJar
} catch {
    $errors += (Convert-ToSafeError $_)
    foreach ($name in @($backendServiceName, $caddyServiceName)) {
        try { Stop-Service -Name $name -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    }
    return @($errors)
}
return @($errors)
}

Write-Output '[DIRECT_104_BEGIN] LeanTPM 1.0.4 Backend/Web V52 application-only deployment v1'
try {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Elevated Administrator is required'
    }
    if ($PSVersionTable.PSVersion.Major -ne 5) { throw 'Windows PowerShell 5.1 is required' }
    $secure = Read-Host 'Enter the production MySQL root password' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $rootPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw 'MySQL root password was empty' }

    $plan = Get-PlanCore
    $planJson = $plan | ConvertTo-Json -Depth 10
    $planSha256 = Get-TextSha256 $planJson
    Write-Output '[DIRECT_104_PLAN_JSON_BEGIN]'
    Write-Output $planJson
    Write-Output '[DIRECT_104_PLAN_JSON_END]'
    Write-Output "PLAN_SHA256=$planSha256"
    if ($PlanOnly -or [string]::IsNullOrWhiteSpace($ConfirmedPlanSha256)) {
        Write-Output '[DIRECT_104_PLAN_ONLY_END] No files, services, databases, or configurations were changed.'
        return
    }
    if ($ConfirmedPlanSha256 -notmatch '^[0-9a-f]{64}$' -or $ConfirmedPlanSha256 -cne $planSha256) {
        throw 'Confirmed Plan SHA256 did not match; no changes were made'
    }
    Write-Output "PLAN_LOCK_VERIFIED=$planSha256"
    $writeStarted = $true
    Stop-Service -Name $backendServiceName -Force -ErrorAction Stop
    Stop-Service -Name $caddyServiceName -Force -ErrorAction Stop
    New-Backup
    Assert-DatabaseV52 (Get-DatabaseState)
    if ((Get-Sha256 $starterPath) -cne [string]$plan.production.starterSha256 -or
            (Get-Sha256 $caddyPath) -cne [string]$plan.production.caddyfileSha256 -or
            (Get-Sha256 $currentJar) -cne [string]$plan.production.currentJarSha256 -or
            (Get-Sha256 (Join-Path $currentWebRoot 'index.html')) -cne [string]$plan.production.currentWebIndexSha256) {
        throw 'Current application bytes changed after Plan approval'
    }
    Assert-FileContract $releaseZip -Bytes ([long]$plan.releaseZip.bytes) -Sha256 ([string]$plan.releaseZip.sha256)
    $manifest = Get-ReleaseManifestFromZip
    Expand-AndVerifyRelease $manifest

    $starterText = New-BackendStarterText -Source ([IO.File]::ReadAllText($starterBackup)) -CurrentJar $currentJar -TargetJar $targetJar -VersionFrom $versionFrom -VersionTo $versionTo -SchemaVersion $schemaVersion
    $starterCandidate = Join-Path $evidenceRoot 'Start-LeanTpmBackend-Rapid.1.0.4.candidate.ps1'
    [IO.File]::WriteAllText($starterCandidate, $starterText, $utf8NoBom)
    [void][scriptblock]::Create([IO.File]::ReadAllText($starterCandidate))
    Copy-Item -LiteralPath $starterCandidate -Destination $starterPath -Force -ErrorAction Stop
    if ((Get-Sha256 $starterPath) -cne (Get-Sha256 $starterCandidate)) { throw 'Starter copy verification failed' }

    $caddyText = New-CaddyText -Source ([IO.File]::ReadAllText($caddyBackup)) -CurrentRoot $currentWebRoot -TargetRoot $targetWebRoot
    $caddyCandidate = Join-Path $evidenceRoot 'Caddyfile.1.0.4.candidate'
    [IO.File]::WriteAllText($caddyCandidate, $caddyText, $utf8NoBom)
    & $caddyExe validate --config $caddyCandidate --adapter caddyfile
    if ($LASTEXITCODE -ne 0) { throw 'Caddy candidate validation failed' }

    Start-Service -Name $backendServiceName -ErrorAction Stop
    Wait-ApplicationState -ExpectedVersion $versionTo -ExpectedJar $targetJar -TimeoutSeconds 90
    Copy-Item -LiteralPath $caddyCandidate -Destination $caddyPath -Force -ErrorAction Stop
    if ((Get-Sha256 $caddyPath) -cne (Get-Sha256 $caddyCandidate)) { throw 'Caddyfile copy verification failed' }
    Start-Service -Name $caddyServiceName -ErrorAction Stop
    Start-Sleep -Seconds 5
    Assert-PublicState -ExpectedJar $targetJar
    Assert-ApplicationState $versionTo
    Assert-DatabaseV52 (Get-DatabaseState)
    Write-Output "DIRECT_104_SUCCESS release=$releaseTo schema=52 http=200 appIncluded=false databaseModified=false"
} catch {
    $safeError = Convert-ToSafeError $_
    if (-not $writeStarted) {
        throw "DIRECT_104_PREFLIGHT_FAILED_NO_CHANGES: $safeError"
    }
    $recoveryErrors = @(Restore-ApplicationV103)
    throw "DIRECT_104_FAILED_APPLICATION_ROLLBACK_ATTEMPTED: $safeError; RecoveryErrors=$($recoveryErrors -join ' | ')"
} finally {
    $rootPassword = $null
}
