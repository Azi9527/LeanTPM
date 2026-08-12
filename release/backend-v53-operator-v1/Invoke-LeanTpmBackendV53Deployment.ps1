[CmdletBinding()]
param(
    [switch]$PlanOnly,
    [string]$ConfirmedPlanSha256 = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$releaseFrom = '1.0.4-20260812.1'
$releaseTo = '1.0.4-20260812.2'
$productVersion = '1.0.4'
$schemaFrom = 52
$schemaTo = 53
$productionUuid = '007df095-92ef-11f1-8f53-00163e059faa'
$releaseZip = 'D:\LeanTPM\temp\LeanTPM-1.0.4-20260812.2-backend-v53.v1.zip'
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
$starterPath = 'D:\LeanTPM\App\service\Start-LeanTpmBackend-Rapid.ps1'
$javaExe = 'D:\tools\jdk-21.0.1\bin\java.exe'
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldumpExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$mysqlSslCa = 'D:\LeanTPM\Data\config\mysql-ca.pem'
$mysqlTrustStore = 'D:\LeanTPM\Data\config\mysql-truststore.jks'
$expectedBackendServicePath = 'D:\LeanTPM\App\service\LeanTPM.Backend.exe'
$backendServiceName = 'LeanTPM.Backend'
$mysqlServiceName = 'MySQL80'
$backupRoot = 'D:\LeanTPM\backups\direct-predeploy-1.0.4-20260812-02'
$evidenceRoot = 'D:\LeanTPM\Runtime\logs\direct-deployment-1.0.4-20260812-02'
$starterBackup = Join-Path $backupRoot 'Start-LeanTpmBackend-Rapid.ps1'
$dumpPath = Join-Path $backupRoot 'leantpm-v52.sql'
$backupManifestPath = Join-Path $backupRoot 'backup-manifest.json'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$rootPassword = $null
$writeStarted = $false
$migrationStarted = $false
$backupManifestSha256 = ''

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
    } finally { $sha.Dispose() }
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
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $allowed -notcontains $sid) {
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
    param([string]$Source, [string]$CurrentJar, [string]$TargetJar)
    $versionLine = "`$env:LEANTPM_RELEASE_VERSION = '$productVersion'"
    $oldSchemaLine = "`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '$schemaFrom'"
    $newSchemaLine = "`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '$schemaTo'"
    $flywayLine = "`$env:LEANTPM_FLYWAY_ENABLED = 'false'"
    foreach ($contract in @(
        [ordered]@{ value = $versionLine; label = 'release version' },
        [ordered]@{ value = $oldSchemaLine; label = 'source database schema' },
        [ordered]@{ value = $flywayLine; label = 'Flyway disabled' },
        [ordered]@{ value = $CurrentJar; label = 'source Backend JAR' }
    )) {
        if ([regex]::Matches($Source, [regex]::Escape([string]$contract.value)).Count -ne 1) {
            throw "Starter must contain exactly one $($contract.label) contract"
        }
    }
    $result = $Source.Replace($oldSchemaLine, $newSchemaLine).Replace($CurrentJar, $TargetJar)
    if ([regex]::Matches($result, [regex]::Escape($versionLine)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($newSchemaLine)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($flywayLine)).Count -ne 1 -or
            [regex]::Matches($result, [regex]::Escape($TargetJar)).Count -ne 1 -or
            $result.Contains($oldSchemaLine) -or $result.Contains($CurrentJar)) {
        throw 'Generated starter did not satisfy the Backend/V53 target contract'
    }
    [void][scriptblock]::Create($result)
    return $result
}

function Invoke-MySqlRows {
    param([string]$Sql, [string]$Database = 'leantpm')
    $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:rootPassword, 'Process')
        $arguments = @(
            '--batch', '--raw', '--skip-column-names', '--host=127.0.0.1', '--port=3306',
            '--user=root', '--ssl-mode=VERIFY_IDENTITY', "--ssl-ca=$mysqlSslCa"
        )
        if (-not [string]::IsNullOrWhiteSpace($Database)) { $arguments += "--database=$Database" }
        $arguments += "--execute=$Sql"
        $output = @(& $mysqlExe @arguments 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ($output -join '; ') }
        return @($output | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process') }
}

function Get-DatabaseState {
    $sql = "SELECT @@server_uuid, VERSION(), COALESCE(MAX(CASE WHEN success=1 THEN CAST(version AS UNSIGNED) END),0), SUM(CASE WHEN success=0 THEN 1 ELSE 0 END), SUM(CASE WHEN success=1 AND CAST(version AS UNSIGNED)>53 THEN 1 ELSE 0 END), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='leantpm' AND table_type='BASE TABLE'), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='leantpm' AND table_type='VIEW'), (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='leantpm' AND table_name='inspection_abnormal' AND column_name IN ('cause_analysis','permanent_countermeasure')) FROM flyway_schema_history;"
    $rows = @(Invoke-MySqlRows -Sql $sql)
    if ($rows.Count -ne 1) { throw 'Database identity query returned an unexpected row count' }
    $fields = @($rows[0] -split "`t", -1)
    if ($fields.Count -ne 8) { throw 'Database identity query returned an unexpected field count' }
    return [ordered]@{
        serverUuid = $fields[0]
        mysqlVersion = $fields[1]
        schemaVersion = [int]$fields[2]
        failedMigrations = [int]$fields[3]
        versionsAbove53 = [int]$fields[4]
        baseTableCount = [int]$fields[5]
        viewCount = [int]$fields[6]
        measureColumnCount = [int]$fields[7]
        tableCount = [int]$fields[5] + [int]$fields[6]
    }
}

function Assert-DatabaseV52 {
    param($State)
    if ([string]$State.serverUuid -cne $productionUuid -or
            [int]$State.schemaVersion -ne $schemaFrom -or
            [int]$State.failedMigrations -ne 0 -or [int]$State.versionsAbove53 -ne 0 -or
            [int]$State.viewCount -ne 0 -or [int]$State.measureColumnCount -ne 0) {
        throw 'Production database is not the approved V52 source instance'
    }
}

function Assert-DatabaseV53 {
    param($State)
    if ([string]$State.serverUuid -cne $productionUuid -or
            [int]$State.schemaVersion -ne $schemaTo -or
            [int]$State.failedMigrations -ne 0 -or [int]$State.versionsAbove53 -ne 0 -or
            [int]$State.viewCount -ne 0 -or [int]$State.measureColumnCount -ne 2) {
        throw 'Production database did not reach the approved V53 state'
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

function Assert-ApplicationState {
    param([int]$ExpectedSchema, [string]$ExpectedJar)
    $ready = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health/readiness' -TimeoutSec 10 -ErrorAction Stop
    $info = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/info' -TimeoutSec 10 -ErrorAction Stop
    $branding = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/v1/public/branding' -TimeoutSec 10 -ErrorAction Stop
    if ([string]$ready.status -cne 'UP' -or [string]$info.app.version -cne $productVersion -or
            [int]$info.app.'database-schema-version' -ne $ExpectedSchema -or
            [string]$branding.code -cne 'OK') {
        throw "Application state is not $productVersion / V$ExpectedSchema / UP"
    }
    Assert-BackendProcessBinding -ExpectedJar $ExpectedJar
}

function Wait-ApplicationState {
    param([int]$ExpectedSchema, [string]$ExpectedJar, [int]$TimeoutSeconds = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try { Assert-ApplicationState -ExpectedSchema $ExpectedSchema -ExpectedJar $ExpectedJar; return }
        catch { Start-Sleep -Seconds 3 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Backend did not reach $productVersion / V$ExpectedSchema readiness before timeout"
}

function Assert-PublicApiState {
    $brandingText = [string](& curl.exe --max-time 15 --resolve '8.163.66.164:80:127.0.0.1' -sS 'http://8.163.66.164/api/v1/public/branding')
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($brandingText)) {
        throw 'Public API branding request failed through the existing proxy'
    }
    $branding = $brandingText | ConvertFrom-Json
    if ([string]$branding.code -cne 'OK') {
        throw 'Public API verification failed through the existing proxy'
    }
}

function Get-ServiceEvidence {
    param([string]$Name)
    $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction Stop
    return [ordered]@{
        name = $Name; state = [string]$service.State; startMode = [string]$service.StartMode
        startName = [string]$service.StartName; processId = [int]$service.ProcessId
        pathName = [string]$service.PathName
    }
}

function Assert-ServiceContracts {
    $backend = Get-ServiceEvidence $backendServiceName
    $mysql = Get-ServiceEvidence $mysqlServiceName
    if ($backend.state -cne 'Running' -or $mysql.state -cne 'Running') {
        throw 'Backend and MySQL must be running before PlanOnly'
    }
    if ($backend.startName -cne 'NT AUTHORITY\NetworkService' -or
            $mysql.startName -cne 'NT AUTHORITY\NetworkService' -or
            $backend.pathName -ine $expectedBackendServicePath) {
        throw 'Production service identity or Backend executable binding changed'
    }
    return [ordered]@{ backend = $backend; mysql = $mysql }
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
    if ([string]$manifest.mode -cne 'BACKEND_ONLY_DATABASE_MIGRATION' -or
            [string]$manifest.releaseId -cne $releaseTo -or
            [string]$manifest.productVersion -cne $productVersion -or
            [string]$manifest.source.commit -cne $sourceCommit -or
            -not [bool]$manifest.scope.backendIncluded -or [bool]$manifest.scope.webIncluded -or
            [bool]$manifest.scope.appIncluded -or -not [bool]$manifest.scope.databaseMigrationsIncluded -or
            [int]$manifest.database.schemaFrom -ne $schemaFrom -or
            [int]$manifest.database.schemaTo -ne $schemaTo -or
            [string]$manifest.database.expectedServerUuid -cne $productionUuid -or
            [string]$manifest.database.rollbackClass -cne 'RECOVERY_REQUIRED') {
        throw 'Release manifest is not the approved Backend-only V52 to V53 package'
    }
    return $manifest
}

function Assert-BackendJarV53Payload {
    param([string]$JarPath, [string]$MigrationPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $versions = @()
        $targetEntry = $null
        foreach ($entry in @($archive.Entries)) {
            if ($entry.FullName -match '^BOOT-INF/classes/db/migration/V([0-9]+)__.+\.sql$') {
                $versions += [int]$Matches[1]
            }
            if ($entry.FullName -ceq 'BOOT-INF/classes/db/migration/V53__inspection_abnormal_measures.sql') {
                $targetEntry = $entry
            }
        }
        if ($versions.Count -eq 0 -or ($versions | Measure-Object -Maximum).Maximum -ne $schemaTo -or
                $null -eq $targetEntry) {
            throw 'Backend JAR migration ceiling and V53 entry must be exact'
        }
        $stream = $targetEntry.Open()
        try {
            $memory = New-Object IO.MemoryStream
            try { $stream.CopyTo($memory); $jarBytes = $memory.ToArray() }
            finally { $memory.Dispose() }
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
    $fileBytes = [IO.File]::ReadAllBytes($MigrationPath)
    if (-not [Linq.Enumerable]::SequenceEqual([byte[]]$jarBytes, [byte[]]$fileBytes)) {
        throw 'Backend JAR V53 migration bytes differ from the reviewed package script'
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
    $jar = Join-Path $targetReleasePartial 'payload\backend\leantpm-backend.jar'
    $migration = Join-Path $targetReleasePartial 'payload\database\migrations\V53__inspection_abnormal_measures.sql'
    Assert-BackendJarV53Payload -JarPath $jar -MigrationPath $migration
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
    if ($releaseZipBytes -gt 0 -and (Get-Item -LiteralPath $releaseZip).Length -ne $releaseZipBytes) {
        throw 'Release ZIP bytes changed'
    }
    if ($releaseZipSha256 -notlike '__*' -and (Get-Sha256 $releaseZip) -cne $releaseZipSha256) {
        throw 'Release ZIP SHA256 changed'
    }
    foreach ($path in @($currentJar, $starterPath, $javaExe, $mysqlExe, $mysqldumpExe, $mysqlSslCa, $mysqlTrustStore)) {
        Assert-FileContract $path
    }
    foreach ($path in @($targetReleaseRoot, $targetReleasePartial, $backupRoot, $evidenceRoot)) {
        if (Test-Path -LiteralPath $path) { throw "Plan target must be absent: $path" }
    }
    $manifest = Get-ReleaseManifestFromZip
    [void](New-BackendStarterText -Source ([IO.File]::ReadAllText($starterPath)) -CurrentJar $currentJar -TargetJar $targetJar)
    $database = Get-DatabaseState
    Assert-DatabaseV52 $database
    Assert-ApplicationState -ExpectedSchema $schemaFrom -ExpectedJar $currentJar
    $services = Assert-ServiceContracts
    return [ordered]@{
        schemaVersion = 1
        mode = 'BACKEND_ONLY_DATABASE_MIGRATION'
        releaseFrom = $releaseFrom
        releaseTo = $releaseTo
        sourceCommit = $sourceCommit
        executor = [ordered]@{ path = $PSCommandPath; sha256 = Get-Sha256 $PSCommandPath }
        releaseZip = [ordered]@{ path = $releaseZip; bytes = (Get-Item $releaseZip).Length; sha256 = Get-Sha256 $releaseZip }
        scope = [ordered]@{ backendIncluded = $true; webIncluded = $false; databaseMigrationsIncluded = $true; appIncluded = $false }
        database = [ordered]@{
            serverUuid = $database.serverUuid; schemaFrom = $schemaFrom; schemaTo = $schemaTo
            failedMigrations = $database.failedMigrations; runtimeFlywayEnabled = $false
            backupRequired = $true; tableCount = $database.tableCount
            baseTableCount = $database.baseTableCount; viewCount = $database.viewCount
        }
        production = [ordered]@{
            currentJarSha256 = Get-Sha256 $currentJar; starterSha256 = Get-Sha256 $starterPath
            mysqlCaSha256 = Get-Sha256 $mysqlSslCa; trustStoreSha256 = Get-Sha256 $mysqlTrustStore
            services = $services
        }
        targets = [ordered]@{ backupRoot = $backupRoot; evidenceRoot = $evidenceRoot; targetReleaseRoot = $targetReleaseRoot }
        rollback = 'RECOVERY_REQUIRED_V52_DATABASE_RESTORE'
        actions = @('VERIFY_PLAN_SHA256','STOP_BACKEND','CREATE_FRESH_V52_BACKUP','CREATE_BACKEND_ONLY_RELEASE','MIGRATE_V52_TO_V53','SWITCH_BACKEND_STARTER','START_AND_VERIFY_BACKEND_V53','VERIFY_PUBLIC_API')
    }
}

function New-V52Backup {
    Set-AndAssertRestrictedDirectoryAcl -Path $backupRoot
    Set-AndAssertRestrictedDirectoryAcl -Path $evidenceRoot
    Copy-Item -LiteralPath $starterPath -Destination $starterBackup -ErrorAction Stop
    $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:rootPassword, 'Process')
        & $mysqldumpExe '--host=127.0.0.1' '--port=3306' '--user=root' '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$mysqlSslCa" '--single-transaction' '--routines' '--triggers' '--events' '--hex-blob' '--set-gtid-purged=OFF' '--default-character-set=utf8mb4' "--result-file=$dumpPath" 'leantpm'
        if ($LASTEXITCODE -ne 0) { throw 'Fresh V52 mysqldump failed' }
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process') }
    $dump = Get-Item -LiteralPath $dumpPath -ErrorAction Stop
    $createTableCount = 0
    $reader = [IO.File]::OpenText($dumpPath)
    try { while (($line = $reader.ReadLine()) -ne $null) { if ($line.StartsWith('CREATE TABLE ')) { $createTableCount++ } } }
    finally { $reader.Dispose() }
    if ($dump.Length -lt 100000 -or $createTableCount -lt [int]$script:plan.database.baseTableCount -or
            [int]$script:plan.database.viewCount -ne 0) {
        throw 'Fresh V52 database backup verification failed'
    }
    $backupManifest = [ordered]@{
        schemaVersion = 1; planSha256 = $script:planSha256
        database = [ordered]@{
            schemaVersion = $schemaFrom; serverUuid = $productionUuid; dumpPath = $dumpPath
            bytes = [long]$dump.Length; sha256 = Get-Sha256 $dumpPath
            createTableCount = $createTableCount; baseTableCount = [int]$script:plan.database.baseTableCount
        }
        starter = [ordered]@{ path = $starterBackup; sha256 = Get-Sha256 $starterBackup }
        currentRelease = [ordered]@{ releaseId = $releaseFrom; jarPath = $currentJar; jarSha256 = Get-Sha256 $currentJar }
        recovery = 'DROP_AND_RESTORE_V52_BEFORE_OLD_BACKEND_START'
    }
    [IO.File]::WriteAllText($backupManifestPath, ($backupManifest | ConvertTo-Json -Depth 6), $utf8NoBom)
    $script:backupManifestSha256 = Get-Sha256 $backupManifestPath
    Assert-RestrictedDirectoryAcl -Path $backupRoot
    Assert-RestrictedDirectoryAcl -Path $evidenceRoot
    Write-Output ('BACKUP_VERIFIED=' + $script:backupManifestSha256)
}

function Invoke-V53Migration {
    $catalogPath = Join-Path $targetReleaseRoot 'payload\database\migrations.json'
    $catalog = Get-Content -LiteralPath $catalogPath -Encoding UTF8 -Raw | ConvertFrom-Json
    if ([int]$catalog.schemaFrom -ne $schemaFrom -or [int]$catalog.schemaTo -ne $schemaTo -or
            [string]$catalog.phase -cne 'EXPAND' -or @($catalog.migrations).Count -ne 1) {
        throw 'Packaged V53 migration catalog is invalid'
    }
    $jarPath = Join-Path $targetReleaseRoot 'payload\backend\leantpm-backend.jar'
    $migrationPath = Join-Path $targetReleaseRoot 'payload\database\migrations\V53__inspection_abnormal_measures.sql'
    Assert-BackendJarV53Payload -JarPath $jarPath -MigrationPath $migrationPath
    $previous = @{}
    foreach ($name in @('LEANTPM_MIGRATOR_JDBC_URL','LEANTPM_MIGRATOR_DB_USERNAME','LEANTPM_MIGRATOR_DB_PASSWORD','LEANTPM_MIGRATOR_SCHEMA_FROM','LEANTPM_MIGRATOR_SCHEMA_TO','LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID')) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_JDBC_URL', 'jdbc:mysql://127.0.0.1:3306/leantpm?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&sslMode=VERIFY_IDENTITY', 'Process')
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_DB_USERNAME', 'root', 'Process')
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_DB_PASSWORD', $script:rootPassword, 'Process')
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_SCHEMA_FROM', [string]$schemaFrom, 'Process')
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_SCHEMA_TO', [string]$schemaTo, 'Process')
        [Environment]::SetEnvironmentVariable('LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID', $productionUuid, 'Process')
        $script:migrationStarted = $true
        $output = @(& $javaExe "-Djavax.net.ssl.trustStore=$mysqlTrustStore" '-Dloader.main=com.leantpm.ops.MigrationMain' '-cp' $jarPath 'org.springframework.boot.loader.launch.PropertiesLauncher' 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ('Isolated V53 Flyway migrator failed: ' + ($output -join '; ')) }
    } finally {
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
    Assert-DatabaseV53 (Get-DatabaseState)
}

function Restore-V52Backup {
    $errors = @()
    try { Stop-Service -Name $backendServiceName -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    try {
        if ($script:backupManifestSha256 -notmatch '^[0-9a-f]{64}$' -or
                (Get-Sha256 $backupManifestPath) -cne $script:backupManifestSha256) {
            throw 'Backup manifest changed after creation'
        }
        $backup = Get-Content -LiteralPath $backupManifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
        if ([string]$backup.planSha256 -cne $script:planSha256 -or
                [int]$backup.database.schemaVersion -ne $schemaFrom -or
                [string]$backup.database.serverUuid -cne $productionUuid -or
                [string]$backup.database.dumpPath -cne $dumpPath -or
                [string]$backup.starter.path -cne $starterBackup -or
                [string]$backup.currentRelease.jarPath -cne $currentJar) {
            throw 'Backup manifest recovery contract is invalid'
        }
        Assert-FileContract -Path $dumpPath -Bytes ([long]$backup.database.bytes) -Sha256 ([string]$backup.database.sha256)
        Assert-FileContract -Path $starterBackup -Sha256 ([string]$backup.starter.sha256)
        Assert-FileContract -Path $currentJar -Sha256 ([string]$backup.currentRelease.jarSha256)
        Copy-Item -LiteralPath $starterBackup -Destination $starterPath -Force -ErrorAction Stop
        if ((Get-Sha256 $starterPath) -cne [string]$script:plan.production.starterSha256) {
            throw 'V52 starter restore verification failed'
        }
        if (-not $script:migrationStarted) {
            Assert-DatabaseV52 (Get-DatabaseState)
            Start-Service -Name $backendServiceName -ErrorAction Stop
            Wait-ApplicationState -ExpectedSchema $schemaFrom -ExpectedJar $currentJar -TimeoutSeconds 90
            return @($errors)
        }
        $previous = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
        try {
            [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:rootPassword, 'Process')
            $recreate = @(& $mysqlExe '--host=127.0.0.1' '--port=3306' '--user=root' '--ssl-mode=VERIFY_IDENTITY' "--ssl-ca=$mysqlSslCa" '--batch' '--skip-column-names' '--execute=DROP DATABASE IF EXISTS leantpm; CREATE DATABASE leantpm CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' 2>&1)
            if ($LASTEXITCODE -ne 0) { throw ('V52 recovery database recreation failed: ' + ($recreate -join '; ')) }
            $startInfo = New-Object Diagnostics.ProcessStartInfo
            $startInfo.FileName = $mysqlExe
            $startInfo.Arguments = "--host=127.0.0.1 --port=3306 --user=root --ssl-mode=VERIFY_IDENTITY --ssl-ca=`"$mysqlSslCa`" --database=leantpm"
            $startInfo.UseShellExecute = $false
            $startInfo.RedirectStandardInput = $true
            $process = New-Object Diagnostics.Process
            $process.StartInfo = $startInfo
            $input = $null
            try {
                if (-not $process.Start()) { throw 'Failed to start V52 restore client' }
                $input = [IO.File]::OpenRead($dumpPath)
                $input.CopyTo($process.StandardInput.BaseStream)
                $process.StandardInput.Close()
                $process.WaitForExit()
                if ($process.ExitCode -ne 0) { throw 'V52 restore client failed' }
            } finally { if ($null -ne $input) { $input.Dispose() }; $process.Dispose() }
        } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous, 'Process') }
        Assert-DatabaseV52 (Get-DatabaseState)
        Start-Service -Name $backendServiceName -ErrorAction Stop
        Wait-ApplicationState -ExpectedSchema $schemaFrom -ExpectedJar $currentJar -TimeoutSeconds 90
    } catch { $errors += (Convert-ToSafeError $_) }
    if ($errors.Count -gt 0) {
        try { Stop-Service -Name $backendServiceName -Force -ErrorAction SilentlyContinue } catch { $errors += (Convert-ToSafeError $_) }
    }
    return @($errors)
}

Write-Output '[BACKEND_V53_BEGIN] LeanTPM 1.0.4 Backend-only V52 to V53 deployment v1'
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
    Write-Output '[BACKEND_V53_PLAN_JSON_BEGIN]'
    Write-Output $planJson
    Write-Output '[BACKEND_V53_PLAN_JSON_END]'
    Write-Output "PLAN_SHA256=$planSha256"
    if ($PlanOnly -or [string]::IsNullOrWhiteSpace($ConfirmedPlanSha256)) {
        Write-Output '[BACKEND_V53_PLAN_ONLY_END] No files, services, databases, or configurations were changed.'
        return
    }
    if ($ConfirmedPlanSha256 -notmatch '^[0-9a-f]{64}$' -or $ConfirmedPlanSha256 -cne $planSha256) {
        throw 'Confirmed Plan SHA256 did not match; no changes were made'
    }
    Write-Output "PLAN_LOCK_VERIFIED=$planSha256"
    $writeStarted = $true
    Stop-Service -Name $backendServiceName -Force -ErrorAction Stop
    New-V52Backup
    Assert-DatabaseV52 (Get-DatabaseState)
    if ((Get-Sha256 $starterPath) -cne [string]$plan.production.starterSha256 -or
            (Get-Sha256 $currentJar) -cne [string]$plan.production.currentJarSha256 -or
            (Get-Sha256 $mysqlSslCa) -cne [string]$plan.production.mysqlCaSha256 -or
            (Get-Sha256 $mysqlTrustStore) -cne [string]$plan.production.trustStoreSha256) {
        throw 'Current production bytes changed after Plan approval'
    }
    Assert-FileContract $releaseZip -Bytes ([long]$plan.releaseZip.bytes) -Sha256 ([string]$plan.releaseZip.sha256)
    $manifest = Get-ReleaseManifestFromZip
    Expand-AndVerifyRelease $manifest
    Invoke-V53Migration

    $starterText = New-BackendStarterText -Source ([IO.File]::ReadAllText($starterBackup)) -CurrentJar $currentJar -TargetJar $targetJar
    $starterCandidate = Join-Path $evidenceRoot 'Start-LeanTpmBackend-Rapid.1.0.4.v53.candidate.ps1'
    [IO.File]::WriteAllText($starterCandidate, $starterText, $utf8NoBom)
    [void][scriptblock]::Create([IO.File]::ReadAllText($starterCandidate))
    Copy-Item -LiteralPath $starterCandidate -Destination $starterPath -Force -ErrorAction Stop
    if ((Get-Sha256 $starterPath) -cne (Get-Sha256 $starterCandidate)) { throw 'Starter V53 copy verification failed' }

    Start-Service -Name $backendServiceName -ErrorAction Stop
    Wait-ApplicationState -ExpectedSchema $schemaTo -ExpectedJar $targetJar -TimeoutSeconds 90
    Assert-DatabaseV53 (Get-DatabaseState)
    Assert-PublicApiState
    $result = [ordered]@{
        status = 'PASS'; release = $releaseTo; schema = $schemaTo
        planSha256 = $planSha256; backupManifestSha256 = $backupManifestSha256
        backendJarSha256 = Get-Sha256 $targetJar
        webModified = $false; appIncluded = $false; databaseModified = $true
    }
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'deployment-result.json'), ($result | ConvertTo-Json -Depth 5), $utf8NoBom)
    Write-Output "BACKEND_V53_SUCCESS release=$releaseTo schema=53 webModified=false appIncluded=false databaseModified=true"
} catch {
    $safeError = Convert-ToSafeError $_
    if (-not $writeStarted) { throw "BACKEND_V53_PREFLIGHT_FAILED_NO_CHANGES: $safeError" }
    if ($script:backupManifestSha256 -notmatch '^[0-9a-f]{64}$') {
        try { Start-Service -Name $backendServiceName -ErrorAction SilentlyContinue } catch {}
        throw "BACKEND_V53_FAILED_BEFORE_VERIFIED_BACKUP: $safeError"
    }
    $recoveryErrors = @(Restore-V52Backup)
    throw "BACKEND_V53_FAILED_V52_RESTORE_ATTEMPTED: $safeError; RecoveryErrors=$($recoveryErrors -join ' | ')"
} finally { $rootPassword = $null }
