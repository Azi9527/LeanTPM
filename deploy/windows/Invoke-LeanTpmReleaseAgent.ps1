[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('VerifyOnly', 'ExecuteSignedDeployment')]
    [string]$Mode,

    [Parameter(Mandatory)]
    [string]$QueueRoot,

    [Parameter(Mandatory)]
    [string]$UploadRoot,

    [string]$ApprovalRoot,

    [Parameter(Mandatory)]
    [string]$PackageVerifierPath,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$PackageVerifierSha256,

    [string]$ApprovalVerifierPath,

    [string]$ApprovalVerifierSha256,

    [string]$ReleaseTrustConfigPath,

    [string]$DeploymentToolkitRoot,

    [string]$DeploymentToolkitLockPath,

    [string]$DeploymentToolkitLockSha256,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-fA-F0-9]{40}$')]
    [string]$TrustedCertificateThumbprint,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9._-]{2,63}$')]
    [string]$AgentId,

    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AgentVersion,

    [ValidateRange(1, 600)]
    [int]$VerifierTimeoutSeconds = 120,

    [ValidateRange(1024, 1048576)]
    [int]$MaximumVerifierOutputBytes = 262144,

    [ValidateRange(60, 7200)]
    [int]$DeploymentTimeoutSeconds = 1800,

    [Parameter(Mandatory)]
    [switch]$RunOnce,

    [ValidateSet('Json')]
    [string]$OutputFormat = 'Json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sha256Pattern = '^[a-f0-9]{64}$'
$releaseIdPattern = '^[0-9A-Za-z][0-9A-Za-z._-]{2,127}$'
$jobNamePattern = '^[a-f0-9]{64}\.json$'
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)

function Get-Sha256Bytes {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $algorithm.ComputeHash($Bytes)
        )).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-Sha256Text {
    param([Parameter(Mandatory)][string]$Text)

    return Get-Sha256Bytes -Bytes $utf8.GetBytes($Text)
}

function Get-FixedDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if (-not $item.PSIsContainer -or
        (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label is not a fixed directory"
    }
    return $item.FullName.TrimEnd('\', '/')
}

function Get-FixedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
        (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label is not a fixed regular file"
    }
    return $item.FullName
}

function Assert-ContainedFile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    $rootFull = Get-FixedDirectory -Path $Root -Label "$Label root"
    $fileFull = Get-FixedFile -Path $Path -Label $Label
    $prefix = $rootFull + [IO.Path]::DirectorySeparatorChar
    if (-not $fileFull.StartsWith(
            $prefix,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Label escaped the approved root"
    }

    $current = (Get-Item -LiteralPath $fileFull -Force).Directory
    $reachedRoot = $false
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label contains a reparse ancestor"
        }
        if ($current.FullName.TrimEnd('\', '/').Equals(
                $rootFull,
                [StringComparison]::OrdinalIgnoreCase
            )) {
            $reachedRoot = $true
            break
        }
        $current = $current.Parent
    }
    if (-not $reachedRoot) {
        throw "$Label did not resolve beneath the approved root"
    }
    return $fileFull
}

function Read-LockedUtf8 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][long]$MaximumBytes,
        [Parameter(Mandatory)][string]$Label
    )

    $fixed = Get-FixedFile -Path $Path -Label $Label
    $stream = New-Object IO.FileStream(
        $fixed,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    try {
        if ($stream.Length -le 0 -or $stream.Length -gt $MaximumBytes) {
            throw "$Label size is invalid"
        }
        $bytes = New-Object byte[] ([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $read = $stream.Read($bytes, $offset, $bytes.Length - $offset)
            if ($read -le 0) {
                throw "$Label was truncated during read"
            }
            $offset += $read
        }
        return $utf8.GetString($bytes)
    }
    finally {
        $stream.Dispose()
    }
}

function ConvertFrom-StrictJson {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Label
    )

    try {
        $value = $Text | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "$Label is invalid JSON"
    }
    if ($null -eq $value -or $value -is [array]) {
        throw "$Label must be one JSON object"
    }
    return $value
}

function Test-OnlyPowerShellProgressOutput {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return $true }
    if (-not $Text.StartsWith('#< CLIXML')) { return $false }
    $xmlText = $Text.Substring('#< CLIXML'.Length).Trim()
    try {
        $settings = New-Object Xml.XmlReaderSettings
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create(
            (New-Object IO.StringReader($xmlText)),
            $settings
        )
        try {
            $document = New-Object Xml.XmlDocument
            $document.XmlResolver = $null
            $document.Load($reader)
        }
        finally {
            $reader.Dispose()
        }
        if ($document.DocumentElement.LocalName -cne 'Objs') {
            return $false
        }
        $objects = @($document.DocumentElement.ChildNodes | Where-Object {
            $_.NodeType -eq [Xml.XmlNodeType]::Element
        })
        if ($objects.Count -lt 1) { return $false }
        foreach ($object in $objects) {
            if ($object.LocalName -cne 'Obj' -or
                $object.GetAttribute('S') -cne 'progress') {
                return $false
            }
        }
        return $true
    }
    catch {
        return $false
    }
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    if ($actual.Count -ne $wanted.Count) {
        throw "$Label properties do not match the fixed schema"
    }
    for ($index = 0; $index -lt $wanted.Count; $index++) {
        if ($actual[$index] -cne $wanted[$index]) {
            throw "$Label properties do not match the fixed schema"
        }
    }
}

function Assert-RequiredProperties {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string[]]$Required,
        [Parameter(Mandatory)][string]$Label
    )

    foreach ($name in $Required) {
        if ($null -eq $Value.PSObject.Properties[$name]) {
            throw "$Label is missing required property $name"
        }
    }
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value,
        [switch]$ReplaceExisting
    )

    $json = $Value | ConvertTo-Json -Depth 8 -Compress
    $bytes = $utf8.GetBytes($json)
    $temporary = Join-Path (
        Split-Path -Parent $Path
    ) ('.agent-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = $null
    try {
        $stream = New-Object IO.FileStream(
            $temporary,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None
        )
        try {
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        }
        finally {
            $stream.Dispose()
        }

        if (Test-Path -LiteralPath $Path) {
            if (-not $ReplaceExisting) {
                throw 'Refusing to replace an existing durable Agent result'
            }
            $backup = Join-Path (
                Split-Path -Parent $Path
            ) ('.agent-' + [guid]::NewGuid().ToString('N') + '.bak')
            [IO.File]::Replace($temporary, $Path, $backup)
            Remove-Item -LiteralPath $backup -Force
            $backup = $null
        }
        else {
            [IO.File]::Move($temporary, $Path)
        }
        $temporary = $null
    }
    finally {
        if ($null -ne $temporary -and
            (Test-Path -LiteralPath $temporary)) {
            Remove-Item -LiteralPath $temporary -Force
        }
        if ($null -ne $backup -and
            (Test-Path -LiteralPath $backup)) {
            Remove-Item -LiteralPath $backup -Force
        }
    }
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Label
    )

    $normalized = $Value.ToLowerInvariant()
    if ($normalized -notmatch $sha256Pattern) {
        throw "$Label is invalid"
    }
    return $normalized
}

function Get-CanonicalCommandJson {
    param([Parameter(Mandatory)]$Command)

    if ([int]$Command.schemaVersion -eq 2) {
        return ([ordered]@{
            action = [string]$Command.action
            approvalId = [string]$Command.approvalId
            approverSignaturePath = [string]$Command.approverSignaturePath
            approverSignatureSha256 = [string]$Command.approverSignatureSha256
            commandId = [string]$Command.commandId
            databaseSchemaVersion = [int]$Command.databaseSchemaVersion
            deploymentPlanPath = [string]$Command.deploymentPlanPath
            deploymentPlanSha256 = [string]$Command.deploymentPlanSha256
            expiresAt = [string]$Command.expiresAt
            hostSnapshotSha256 = [string]$Command.hostSnapshotSha256
            manifestSha256 = [string]$Command.manifestSha256
            packagePath = [string]$Command.packagePath
            packageSha256 = [string]$Command.packageSha256
            planSha256 = [string]$Command.planSha256
            productVersion = [string]$Command.productVersion
            releaseId = [string]$Command.releaseId
            requesterSignaturePath = [string]$Command.requesterSignaturePath
            requesterSignatureSha256 = [string]$Command.requesterSignatureSha256
            schemaVersion = [int]$Command.schemaVersion
        } | ConvertTo-Json -Depth 4 -Compress)
    }

    return ([ordered]@{
        action = [string]$Command.action
        commandId = [string]$Command.commandId
        databaseSchemaVersion = [int]$Command.databaseSchemaVersion
        expiresAt = [string]$Command.expiresAt
        hostSnapshotSha256 = [string]$Command.hostSnapshotSha256
        manifestSha256 = [string]$Command.manifestSha256
        packagePath = [string]$Command.packagePath
        packageSha256 = [string]$Command.packageSha256
        planSha256 = [string]$Command.planSha256
        productVersion = [string]$Command.productVersion
        releaseId = [string]$Command.releaseId
        schemaVersion = [int]$Command.schemaVersion
    } | ConvertTo-Json -Depth 4 -Compress)
}

function Read-AgentCommand {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedCommandId
    )

    $text = Read-LockedUtf8 -Path $Path -MaximumBytes 65536 `
        -Label 'release Agent command'
    $command = ConvertFrom-StrictJson -Text $text -Label 'release Agent command'
    $schemaVersion = [int]$command.schemaVersion
    $action = [string]$command.action
    if ($schemaVersion -eq 1 -and $action -ceq 'DEPLOY_RELEASE') {
        $expectedProperties = @(
            'action',
            'commandId',
            'databaseSchemaVersion',
            'expiresAt',
            'hostSnapshotSha256',
            'manifestSha256',
            'packagePath',
            'packageSha256',
            'planSha256',
            'productVersion',
            'releaseId',
            'schemaVersion'
        )
    }
    elseif ($schemaVersion -eq 2 -and $action -ceq 'DEPLOY_SIGNED_RELEASE') {
        $expectedProperties = @(
            'action',
            'approvalId',
            'approverSignaturePath',
            'approverSignatureSha256',
            'commandId',
            'databaseSchemaVersion',
            'deploymentPlanPath',
            'deploymentPlanSha256',
            'expiresAt',
            'hostSnapshotSha256',
            'manifestSha256',
            'packagePath',
            'packageSha256',
            'planSha256',
            'productVersion',
            'releaseId',
            'requesterSignaturePath',
            'requesterSignatureSha256',
            'schemaVersion'
        )
    }
    else {
        throw 'Release Agent command schema or action is unsupported'
    }
    Assert-ExactProperties -Value $command -Label 'release Agent command' `
        -Expected $expectedProperties
    if ($text -cne (Get-CanonicalCommandJson -Command $command)) {
        throw 'Release Agent command bytes are not canonical'
    }
    if ([string]$command.commandId -cne $ExpectedCommandId) {
        throw 'Release Agent command id does not match its file name'
    }
    if ([string]$command.releaseId -notmatch $releaseIdPattern) {
        throw 'Release Agent command release id is invalid'
    }
    if ([string]$command.productVersion -notmatch `
            '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$' -or
        -not ([string]$command.releaseId).StartsWith(
            ([string]$command.productVersion + '-'),
            [StringComparison]::Ordinal
        ) -or
        [int]$command.databaseSchemaVersion -lt 1) {
        throw 'Release Agent command version or database schema is invalid'
    }
    foreach ($property in @(
        'commandId',
        'hostSnapshotSha256',
        'manifestSha256',
        'packageSha256',
        'planSha256'
    )) {
        $null = Assert-Sha256 -Value ([string]$command.$property) `
            -Label "release Agent $property"
    }
    if ($schemaVersion -eq 2) {
        if ([string]$command.approvalId -notmatch `
                '^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$' -or
            [string]$command.approvalId -match `
                '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
            [string]$command.approvalId -match '\.$') {
            throw 'Release Agent approval id is invalid'
        }
        foreach ($property in @(
            'deploymentPlanSha256',
            'requesterSignatureSha256',
            'approverSignatureSha256'
        )) {
            $null = Assert-Sha256 -Value ([string]$command.$property) `
                -Label "release Agent $property"
        }
        if ([string]$command.deploymentPlanSha256 -cne
            [string]$command.planSha256) {
            throw 'Release Agent deployment plan digest is inconsistent'
        }
    }
    $expiresAt = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse(
            [string]$command.expiresAt,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$expiresAt
        )) {
        throw 'Release Agent command expiry is invalid'
    }
    if ($expiresAt -le [datetimeoffset]::UtcNow) {
        throw 'Release Agent command has expired'
    }
    return $command
}

function Invoke-PinnedApprovalVerifier {
    param(
        [Parameter(Mandatory)][string]$VerifierPath,
        [Parameter(Mandatory)][string]$ExpectedVerifierSha256,
        [Parameter(Mandatory)][string]$PlanPath,
        [Parameter(Mandatory)][string]$ExpectedPlanSha256,
        [Parameter(Mandatory)][string]$RequesterSignaturePath,
        [Parameter(Mandatory)][string]$ExpectedRequesterSignatureSha256,
        [Parameter(Mandatory)][string]$ApproverSignaturePath,
        [Parameter(Mandatory)][string]$ExpectedApproverSignatureSha256,
        [Parameter(Mandatory)][string]$TrustConfigPath
    )

    $powershellExecutable = Join-Path $env:SystemRoot `
        'System32\WindowsPowerShell\v1.0\powershell.exe'
    $stdoutPath = Join-Path $resolvedQueueRoot `
        ('.approval-verifier-' + [guid]::NewGuid().ToString('N') + '.out')
    $stderrPath = Join-Path $resolvedQueueRoot `
        ('.approval-verifier-' + [guid]::NewGuid().ToString('N') + '.err')
    $lockedPaths = @(
        $VerifierPath,
        $PlanPath,
        $RequesterSignaturePath,
        $ApproverSignaturePath,
        $TrustConfigPath
    )
    $locks = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
    try {
        foreach ($lockedPath in $lockedPaths) {
            $locks.Add((New-Object IO.FileStream(
                $lockedPath,
                [IO.FileMode]::Open,
                [IO.FileAccess]::Read,
                [IO.FileShare]::Read
            )))
        }
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try {
            $lockedDigests = @()
            foreach ($lock in $locks) {
                $lockedDigests += ([BitConverter]::ToString(
                    $algorithm.ComputeHash($lock)
                )).Replace('-', '').ToLowerInvariant()
                $algorithm.Initialize()
            }
        }
        finally {
            $algorithm.Dispose()
        }
        if ($lockedDigests[0] -cne $ExpectedVerifierSha256 -or
            $lockedDigests[1] -cne $ExpectedPlanSha256 -or
            $lockedDigests[2] -cne $ExpectedRequesterSignatureSha256 -or
            $lockedDigests[3] -cne $ExpectedApproverSignatureSha256) {
            throw 'Pinned approval verifier or signed approval material changed'
        }

        $arguments = @(
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-File',
            ('"' + $VerifierPath + '"'),
            '-PlanPath',
            ('"' + $PlanPath + '"'),
            '-RequesterSignaturePath',
            ('"' + $RequesterSignaturePath + '"'),
            '-ApproverSignaturePath',
            ('"' + $ApproverSignaturePath + '"'),
            '-TrustConfigPath',
            ('"' + $TrustConfigPath + '"'),
            '-OutputFormat',
            'Json'
        )
        $process = Start-Process -FilePath $powershellExecutable `
            -ArgumentList $arguments `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru
        $null = $process.Handle
        if (-not $process.WaitForExit($VerifierTimeoutSeconds * 1000)) {
            try {
                $process.Kill()
                $process.WaitForExit()
            }
            catch { }
            throw 'Pinned release approval verifier timed out'
        }
        $process.WaitForExit()
        $process.Refresh()
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            $outputItem = Get-Item -LiteralPath $outputPath -Force
            if ($outputItem.Length -gt $MaximumVerifierOutputBytes) {
                throw 'Pinned release approval verifier exceeded its output limit'
            }
        }
        $stderrBytes = [IO.File]::ReadAllBytes($stderrPath)
        $stdoutBytes = [IO.File]::ReadAllBytes($stdoutPath)
        if ($process.ExitCode -ne 0) {
            $stderr = [Text.Encoding]::Default.GetString($stderrBytes)
            $stdout = [Text.Encoding]::Default.GetString($stdoutBytes).Trim()
            $diagnostic = ($stderr + [Environment]::NewLine + $stdout).Trim()
            if ($diagnostic.Length -gt 1024) {
                $diagnostic = $diagnostic.Substring(0, 1024)
            }
            throw "Pinned release approval verifier failed: $diagnostic"
        }
        $stderr = $utf8.GetString($stderrBytes)
        $stdout = $utf8.GetString($stdoutBytes).Trim()
        if (Test-OnlyPowerShellProgressOutput -Text $stderr) {
            $stderr = ''
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            throw 'Pinned release approval verifier emitted error output'
        }
        return ConvertFrom-StrictJson -Text $stdout `
            -Label 'release approval verifier report'
    }
    finally {
        foreach ($lock in $locks) {
            if ($null -ne $lock) { $lock.Dispose() }
        }
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $outputPath) {
                Remove-Item -LiteralPath $outputPath -Force
            }
        }
    }
}

function Get-CanonicalToolkitLockJson {
    param([Parameter(Mandatory)]$Lock)

    $files = @()
    foreach ($file in @($Lock.files)) {
        $files += [ordered]@{
            path = [string]$file.path
            sha256 = [string]$file.sha256
        }
    }
    return ([ordered]@{
        executorRelativePath = [string]$Lock.executorRelativePath
        files = $files
        schemaVersion = [int]$Lock.schemaVersion
        toolkitId = [string]$Lock.toolkitId
    } | ConvertTo-Json -Depth 6 -Compress)
}

function Invoke-PinnedDeploymentExecutor {
    param(
        [Parameter(Mandatory)][string]$ToolkitRoot,
        [Parameter(Mandatory)][string]$ToolkitLockPath,
        [Parameter(Mandatory)][string]$ExpectedToolkitLockSha256,
        [Parameter(Mandatory)][string]$PlanPath,
        [Parameter(Mandatory)]$Plan
    )

    $toolkitRootFull = Get-FixedDirectory -Path $ToolkitRoot `
        -Label 'release deployment toolkit root'
    $scriptsRoot = Get-FixedDirectory -Path (
        Join-Path $toolkitRootFull 'scripts'
    ) -Label 'release deployment toolkit scripts root'
    $windowsRoot = Get-FixedDirectory -Path (
        Join-Path $toolkitRootFull 'deploy\windows'
    ) -Label 'release deployment toolkit Windows root'
    $lockPathFull = Assert-ContainedFile -Root $toolkitRootFull `
        -Path $ToolkitLockPath -Label 'release deployment toolkit lock'
    $expectedLockPath = Join-Path $toolkitRootFull `
        'release\release-agent-toolkit-lock.json'
    if (-not $lockPathFull.Equals(
            [IO.Path]::GetFullPath($expectedLockPath),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Release deployment toolkit lock path is not exact'
    }
    $expectedLockSha256 = Assert-Sha256 `
        -Value $ExpectedToolkitLockSha256 `
        -Label 'release deployment toolkit lock digest'
    $lockText = Read-LockedUtf8 -Path $lockPathFull -MaximumBytes 1048576 `
        -Label 'release deployment toolkit lock'
    $lock = ConvertFrom-StrictJson -Text $lockText `
        -Label 'release deployment toolkit lock'
    Assert-ExactProperties -Value $lock `
        -Label 'release deployment toolkit lock' -Expected @(
            'executorRelativePath',
            'files',
            'schemaVersion',
            'toolkitId'
        )
    if ([int]$lock.schemaVersion -ne 1 -or
        [string]$lock.toolkitId -cne 'leantpm-release-agent-toolkit' -or
        [string]$lock.executorRelativePath -cne
            'scripts/Invoke-LeanTpmDeployment.ps1' -or
        $lockText -cne (Get-CanonicalToolkitLockJson -Lock $lock)) {
        throw 'Release deployment toolkit lock is not canonical or supported'
    }
    if (@($lock.files).Count -lt 1 -or @($lock.files).Count -gt 256) {
        throw 'Release deployment toolkit file count is invalid'
    }

    $manifestEntries = New-Object `
        'System.Collections.Generic.Dictionary[string,string]' `
        ([StringComparer]::Ordinal)
    $previousPath = $null
    foreach ($entry in @($lock.files)) {
        Assert-ExactProperties -Value $entry `
            -Label 'release deployment toolkit file' -Expected @(
                'path', 'sha256'
            )
        $relativePath = [string]$entry.path
        if ($relativePath -notmatch `
                '^(?:(?:scripts|deploy/windows)/[A-Za-z0-9._/-]+\.ps1|release/(?:deployment-bundle\.schema|toolchain-lock)\.json)$' -or
            $relativePath.Contains('..') -or
            [IO.Path]::IsPathRooted($relativePath)) {
            throw 'Release deployment toolkit relative path is invalid'
        }
        if ($null -ne $previousPath -and
            [StringComparer]::Ordinal.Compare($previousPath, $relativePath) `
                -ge 0) {
            throw 'Release deployment toolkit files are not strictly ordered'
        }
        $digest = Assert-Sha256 -Value ([string]$entry.sha256) `
            -Label 'release deployment toolkit file digest'
        if ($manifestEntries.ContainsKey($relativePath)) {
            throw 'Release deployment toolkit contains a duplicate file'
        }
        $manifestEntries.Add($relativePath, $digest)
        $previousPath = $relativePath
    }

    $discovered = New-Object 'System.Collections.Generic.List[string]'
    foreach ($root in @($scriptsRoot, $windowsRoot)) {
        foreach ($item in @(Get-ChildItem -LiteralPath $root -Recurse -File `
                -Filter '*.ps1' -Force)) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) `
                -ne 0) {
                throw 'Release deployment toolkit contains a reparse script'
            }
            $relative = $item.FullName.Substring(
                $toolkitRootFull.Length + 1
            ).Replace('\', '/')
            $discovered.Add($relative)
        }
    }
    foreach ($releaseFile in @(
            'release/deployment-bundle.schema.json',
            'release/toolchain-lock.json'
        )) {
        $releasePath = Join-Path $toolkitRootFull $releaseFile.Replace('/', '\')
        $null = Assert-ContainedFile -Root $toolkitRootFull -Path $releasePath `
            -Label 'release deployment toolkit data file'
        $discovered.Add($releaseFile)
    }
    $discovered.Sort([StringComparer]::Ordinal)
    if ($discovered.Count -ne $manifestEntries.Count) {
        throw 'Release deployment toolkit file set differs from its lock'
    }
    for ($index = 0; $index -lt $discovered.Count; $index++) {
        if (-not $manifestEntries.ContainsKey($discovered[$index])) {
            throw 'Release deployment toolkit contains an unlocked script'
        }
    }

    $executorRelativePath = [string]$lock.executorRelativePath
    if ($null -ne $Plan.PSObject.Properties['deploymentMode']) {
        if ([string]$Plan.deploymentMode -cne 'WORKGROUP_RAPID') {
            throw 'Signed deployment plan contains an unsupported deploymentMode'
        }
        $executorRelativePath =
            'scripts/Invoke-LeanTpmWorkgroupRapidDeployment.ps1'
        if (-not $manifestEntries.ContainsKey($executorRelativePath)) {
            throw 'Pinned toolkit does not contain the WORKGROUP_RAPID executor'
        }
    }
    $executorPath = Join-Path $toolkitRootFull `
        $executorRelativePath.Replace('/', '\')
    $executorPath = Assert-ContainedFile -Root $toolkitRootFull `
        -Path $executorPath -Label 'release deployment executor'
    if ((Split-Path -Leaf $executorPath) -cnotin @(
            'Invoke-LeanTpmDeployment.ps1',
            'Invoke-LeanTpmWorkgroupRapidDeployment.ps1'
        )) {
        throw 'Release deployment executor file name is not approved'
    }

    $powershellExecutable = Join-Path $env:SystemRoot `
        'System32\WindowsPowerShell\v1.0\powershell.exe'
    $stdoutPath = Join-Path $resolvedQueueRoot `
        ('.deployment-' + [guid]::NewGuid().ToString('N') + '.out')
    $stderrPath = Join-Path $resolvedQueueRoot `
        ('.deployment-' + [guid]::NewGuid().ToString('N') + '.err')
    $locks = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
    try {
        $orderedLockPaths = @($lockPathFull)
        foreach ($relative in $discovered) {
            $orderedLockPaths += Join-Path $toolkitRootFull `
                $relative.Replace('/', '\')
        }
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try {
            foreach ($lockedPath in $orderedLockPaths) {
                $fixedLockedPath = Assert-ContainedFile `
                    -Root $toolkitRootFull -Path $lockedPath `
                    -Label 'release deployment toolkit file'
                $stream = New-Object IO.FileStream(
                    $fixedLockedPath,
                    [IO.FileMode]::Open,
                    [IO.FileAccess]::Read,
                    [IO.FileShare]::Read
                )
                $locks.Add($stream)
                $actualSha256 = ([BitConverter]::ToString(
                    $algorithm.ComputeHash($stream)
                )).Replace('-', '').ToLowerInvariant()
                $algorithm.Initialize()
                if ($fixedLockedPath.Equals(
                        $lockPathFull,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
                    if ($actualSha256 -cne $expectedLockSha256) {
                        throw 'Release deployment toolkit lock digest changed'
                    }
                }
                else {
                    $relative = $fixedLockedPath.Substring(
                        $toolkitRootFull.Length + 1
                    ).Replace('\', '/')
                    if ($actualSha256 -cne $manifestEntries[$relative]) {
                        throw 'Release deployment toolkit script digest changed'
                    }
                }
            }
        }
        finally {
            $algorithm.Dispose()
        }

        $executorLiteral = "'" + $executorPath.Replace("'", "''") + "'"
        $planLiteral = "'" + $PlanPath.Replace("'", "''") + "'"
        $commandText = @(
            '[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)'
            '$OutputEncoding = [Console]::OutputEncoding'
            "& $executorLiteral -PlanPath $planLiteral " +
                '-ConfirmDeployment -Confirm:$false -OutputFormat Json'
        ) -join '; '
        $encodedCommand = [Convert]::ToBase64String(
            [Text.Encoding]::Unicode.GetBytes($commandText)
        )
        $arguments = @(
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-EncodedCommand',
            $encodedCommand
        )
        $process = Start-Process -FilePath $powershellExecutable `
            -ArgumentList $arguments `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru
        $null = $process.Handle
        if (-not $process.WaitForExit($DeploymentTimeoutSeconds * 1000)) {
            try {
                $process.Kill()
                $process.WaitForExit()
            }
            catch { }
            throw 'Pinned release deployment executor timed out'
        }
        $process.WaitForExit()
        $process.Refresh()
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            $outputItem = Get-Item -LiteralPath $outputPath -Force
            if ($outputItem.Length -gt $MaximumVerifierOutputBytes) {
                throw 'Pinned release deployment executor exceeded its output limit'
            }
        }
        $stderrBytes = [IO.File]::ReadAllBytes($stderrPath)
        $stdoutBytes = [IO.File]::ReadAllBytes($stdoutPath)
        if ($process.ExitCode -ne 0) {
            $stderr = [Text.Encoding]::Default.GetString($stderrBytes)
            $stdout = [Text.Encoding]::Default.GetString($stdoutBytes).Trim()
            $diagnostic = ($stderr + [Environment]::NewLine + $stdout).Trim()
            if ($diagnostic.Length -gt 2048) {
                $diagnostic = $diagnostic.Substring(0, 2048)
            }
            throw "Pinned release deployment executor failed: $diagnostic"
        }
        try {
            $stderr = $utf8.GetString($stderrBytes)
        }
        catch {
            $stderr = [Text.Encoding]::Default.GetString($stderrBytes)
        }
        $stdout = $utf8.GetString($stdoutBytes).Trim()
        if (Test-OnlyPowerShellProgressOutput -Text $stderr) {
            $stderr = ''
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            $diagnostic = $stderr.Trim()
            if ($diagnostic.Length -gt 1024) {
                $diagnostic = $diagnostic.Substring(0, 1024)
            }
            throw "Pinned release deployment executor emitted error output: $diagnostic"
        }
        $report = ConvertFrom-StrictJson -Text $stdout `
            -Label 'release deployment executor report'
        Assert-RequiredProperties -Value $report `
            -Label 'release deployment executor report' -Required @(
                'status',
                'releaseId',
                'approvalId',
                'environmentName',
                'environmentKind',
                'packageSha256',
                'hostLayoutSha256',
                'proxyBindingSha256',
                'steps'
            )
        $allowedProperties = @(
            'status',
            'releaseId',
            'approvalId',
            'environmentName',
            'environmentKind',
            'packageSha256',
            'hostLayoutSha256',
            'proxyBindingSha256',
            'steps',
            'backupId',
            'completedAtUtc'
        )
        foreach ($property in $report.PSObject.Properties.Name) {
            if ($property -cnotin $allowedProperties) {
                throw 'Release deployment executor report has an unknown property'
            }
        }
        if ([string]$report.status -notin @('SUCCEEDED', 'ALREADY_SUCCEEDED') -or
            [string]$report.releaseId -cne [string]$Plan.releaseId -or
            [string]$report.approvalId -cne [string]$Plan.approvalId -or
            [string]$report.environmentKind -cne 'PRODUCTION' -or
            [string]$report.packageSha256 -cne [string]$Plan.packageSha256 -or
            [string]$report.hostLayoutSha256 -cne
                [string]$Plan.hostLayoutSha256 -or
            [string]$report.proxyBindingSha256 -cne
                [string]$Plan.proxyBindingSha256) {
            throw 'Release deployment executor report does not match the signed plan'
        }
        return [pscustomobject]@{
            report = $report
            reportSha256 = Get-Sha256Text -Text $stdout
        }
    }
    finally {
        foreach ($lock in $locks) {
            if ($null -ne $lock) { $lock.Dispose() }
        }
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $outputPath) {
                Remove-Item -LiteralPath $outputPath -Force
            }
        }
    }
}

function Invoke-PinnedPackageVerifier {
    param(
        [Parameter(Mandatory)][string]$VerifierPath,
        [Parameter(Mandatory)][string]$ExpectedVerifierSha256,
        [Parameter(Mandatory)][string]$PackagePath,
        [Parameter(Mandatory)][string]$ExpectedPackageSha256
    )

    $powershellExecutable = Join-Path $env:SystemRoot `
        'System32\WindowsPowerShell\v1.0\powershell.exe'
    $stdoutPath = Join-Path $resolvedQueueRoot `
        ('.verifier-' + [guid]::NewGuid().ToString('N') + '.out')
    $stderrPath = Join-Path $resolvedQueueRoot `
        ('.verifier-' + [guid]::NewGuid().ToString('N') + '.err')
    $verifierLock = New-Object IO.FileStream(
        $VerifierPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    $packageLock = $null
    try {
        $packageLock = New-Object IO.FileStream(
            $PackagePath,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::Read
        )
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try {
            $lockedVerifierSha256 = ([BitConverter]::ToString(
                $algorithm.ComputeHash($verifierLock)
            )).Replace('-', '').ToLowerInvariant()
            $algorithm.Initialize()
            $lockedPackageSha256 = ([BitConverter]::ToString(
                $algorithm.ComputeHash($packageLock)
            )).Replace('-', '').ToLowerInvariant()
        }
        finally {
            $algorithm.Dispose()
        }
        if ($lockedVerifierSha256 -cne $ExpectedVerifierSha256 -or
            $lockedPackageSha256 -cne $ExpectedPackageSha256) {
            throw 'Pinned verifier or release package changed before execution'
        }

        $arguments = @(
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-File',
            ('"' + $VerifierPath + '"'),
            '-PackagePath',
            ('"' + $PackagePath + '"'),
            '-TrustedCertificateThumbprint',
            $TrustedCertificateThumbprint.ToUpperInvariant(),
            '-OutputFormat',
            'Json'
        )
        $process = Start-Process -FilePath $powershellExecutable `
            -ArgumentList $arguments `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru
        # Windows PowerShell 5.1 must retain the native handle before a
        # short-lived verifier exits or ExitCode can remain unavailable.
        $null = $process.Handle
        if (-not $process.WaitForExit($VerifierTimeoutSeconds * 1000)) {
            try {
                $process.Kill()
                $process.WaitForExit()
            }
            catch { }
            throw 'Pinned release package verifier timed out'
        }
        # .NET Framework requires the parameterless wait to flush redirected
        # streams and publish the final exit code after a timed wait.
        $process.WaitForExit()
        $process.Refresh()
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            $outputItem = Get-Item -LiteralPath $outputPath -Force
            if ($outputItem.Length -gt $MaximumVerifierOutputBytes) {
                throw 'Pinned release package verifier exceeded its output limit'
            }
        }
        $stderr = [IO.File]::ReadAllText($stderrPath, $utf8)
        $stdout = [IO.File]::ReadAllText($stdoutPath, $utf8).Trim()
        if ($process.ExitCode -ne 0) {
            $diagnostic = ($stderr + [Environment]::NewLine + $stdout).Trim()
            if ($diagnostic.Length -gt 1024) {
                $diagnostic = $diagnostic.Substring(0, 1024)
            }
            throw "Pinned release package verifier failed: $diagnostic"
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            throw 'Pinned release package verifier emitted error output'
        }
        return ConvertFrom-StrictJson -Text $stdout `
            -Label 'release package verifier report'
    }
    finally {
        if ($null -ne $packageLock) {
            $packageLock.Dispose()
        }
        $verifierLock.Dispose()
        foreach ($outputPath in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $outputPath) {
                Remove-Item -LiteralPath $outputPath -Force
            }
        }
    }
}

function Get-ResultCore {
    param([Parameter(Mandatory)]$Value)

    return [ordered]@{
        agentId = [string]$Value.agentId
        agentVersion = [string]$Value.agentVersion
        commandId = [string]$Value.commandId
        databaseSchemaVersion = [int]$Value.databaseSchemaVersion
        hostSnapshotSha256 = [string]$Value.hostSnapshotSha256
        manifestSha256 = [string]$Value.manifestSha256
        packageSha256 = [string]$Value.packageSha256
        planSha256 = [string]$Value.planSha256
        productionExecutionEnabled = [bool]$Value.productionExecutionEnabled
        productVersion = [string]$Value.productVersion
        releaseId = [string]$Value.releaseId
        schemaVersion = [int]$Value.schemaVersion
        status = [string]$Value.status
        verifiedAt = [string]$Value.verifiedAt
    }
}

function Get-DeploymentResultCore {
    param([Parameter(Mandatory)]$Value)

    return [ordered]@{
        agentId = [string]$Value.agentId
        agentVersion = [string]$Value.agentVersion
        approvalId = [string]$Value.approvalId
        commandId = [string]$Value.commandId
        databaseSchemaVersion = [int]$Value.databaseSchemaVersion
        deploymentReportSha256 = [string]$Value.deploymentReportSha256
        deploymentStatus = [string]$Value.deploymentStatus
        hostSnapshotSha256 = [string]$Value.hostSnapshotSha256
        manifestSha256 = [string]$Value.manifestSha256
        packageSha256 = [string]$Value.packageSha256
        planSha256 = [string]$Value.planSha256
        productionExecutionEnabled = [bool]$Value.productionExecutionEnabled
        productVersion = [string]$Value.productVersion
        releaseId = [string]$Value.releaseId
        schemaVersion = [int]$Value.schemaVersion
        status = [string]$Value.status
        verifiedAt = [string]$Value.verifiedAt
    }
}

function Read-ExistingResult {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Command
    )

    $text = Read-LockedUtf8 -Path $Path -MaximumBytes 65536 `
        -Label 'release Agent result'
    $result = ConvertFrom-StrictJson -Text $text -Label 'release Agent result'
    if ([int]$result.schemaVersion -eq 1) {
        Assert-ExactProperties -Value $result -Label 'release Agent result' `
            -Expected @(
                'agentId',
                'agentVersion',
                'commandId',
                'databaseSchemaVersion',
                'hostSnapshotSha256',
                'manifestSha256',
                'packageSha256',
                'planSha256',
                'productionExecutionEnabled',
                'productVersion',
                'releaseId',
                'resultSha256',
                'schemaVersion',
                'status',
                'verifiedAt'
            )
        $core = Get-ResultCore -Value $result
        $modeValid = [string]$result.status -ceq 'VERIFIED_ONLY' -and
            [bool]$result.productionExecutionEnabled -eq $false
    }
    elseif ([int]$result.schemaVersion -eq 2) {
        Assert-ExactProperties -Value $result -Label 'release Agent result' `
            -Expected @(
                'agentId',
                'agentVersion',
                'approvalId',
                'commandId',
                'databaseSchemaVersion',
                'deploymentReportSha256',
                'deploymentStatus',
                'hostSnapshotSha256',
                'manifestSha256',
                'packageSha256',
                'planSha256',
                'productionExecutionEnabled',
                'productVersion',
                'releaseId',
                'resultSha256',
                'schemaVersion',
                'status',
                'verifiedAt'
            )
        $core = Get-DeploymentResultCore -Value $result
        $modeValid = [string]$result.status -ceq 'DEPLOYED' -and
            [bool]$result.productionExecutionEnabled -eq $true -and
            [string]$result.deploymentStatus -in @(
                'SUCCEEDED', 'ALREADY_SUCCEEDED'
            ) -and
            [string]$result.approvalId -ceq [string]$Command.approvalId -and
            [string]$result.deploymentReportSha256 -match $sha256Pattern -and
            [int]$Command.schemaVersion -eq 2
    }
    else {
        throw 'Existing release Agent result schema is unsupported'
    }
    $expectedResultSha256 = Get-Sha256Text -Text (
        $core | ConvertTo-Json -Depth 4 -Compress
    )
    if ([string]$result.resultSha256 -cne $expectedResultSha256 -or
        -not $modeValid -or
        [string]$result.commandId -cne [string]$Command.commandId -or
        [int]$result.databaseSchemaVersion -ne
            [int]$Command.databaseSchemaVersion -or
        [string]$result.hostSnapshotSha256 -cne
            [string]$Command.hostSnapshotSha256 -or
        [string]$result.packageSha256 -cne [string]$Command.packageSha256 -or
        [string]$result.manifestSha256 -cne [string]$Command.manifestSha256 -or
        [string]$result.planSha256 -cne [string]$Command.planSha256 -or
        [string]$result.productVersion -cne
            [string]$Command.productVersion -or
        [string]$result.releaseId -cne [string]$Command.releaseId -or
        [string]$result.agentId -cne $AgentId -or
        [string]$result.agentVersion -cne $AgentVersion) {
        throw 'Existing release Agent result does not match the pending command'
    }
    return $result
}

if (-not $RunOnce) {
    throw 'Continuous release Agent execution is not enabled in this build'
}

$resolvedQueueRoot = Get-FixedDirectory -Path $QueueRoot -Label 'release queue root'
$resolvedUploadRoot = Get-FixedDirectory -Path $UploadRoot -Label 'release upload root'
$pendingRoot = Get-FixedDirectory -Path (
    Join-Path $resolvedQueueRoot 'pending'
) -Label 'release pending queue'
$resolvedVerifierPath = Get-FixedFile -Path $PackageVerifierPath `
    -Label 'release package verifier'
if ((Split-Path -Leaf $resolvedVerifierPath) -cne 'Test-ReleasePackage.ps1') {
    throw 'Release package verifier file name is not approved'
}
$actualVerifierSha256 = (
    Get-FileHash -LiteralPath $resolvedVerifierPath -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($actualVerifierSha256 -cne $PackageVerifierSha256.ToLowerInvariant()) {
    throw 'Release package verifier digest does not match the approved SHA-256'
}

$heartbeat = [ordered]@{
    schemaVersion = 1
    agentId = $AgentId
    agentVersion = $AgentVersion
    mode = if ($Mode -eq 'ExecuteSignedDeployment') {
        'PRODUCTION_ENABLED'
    }
    else { 'VERIFY_ONLY' }
    lastSeenAt = [datetimeoffset]::UtcNow.ToString('o')
}
Write-AtomicJson -Path (
    Join-Path $resolvedQueueRoot 'agent-heartbeat.json'
) -Value $heartbeat -ReplaceExisting

$pendingJobs = @(
    Get-ChildItem -LiteralPath $pendingRoot -Force -File |
        Where-Object { $_.Name -cmatch $jobNamePattern } |
        Sort-Object Name
)
if ($pendingJobs.Count -eq 0) {
    [ordered]@{
        status = 'IDLE'
        mode = [string]$heartbeat.mode
        pendingJobs = 0
        productionExecutionEnabled = $false
    } | ConvertTo-Json -Depth 4 -Compress
    return
}

$job = $pendingJobs[0]
if (($job.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Pending release Agent command is a reparse point'
}
$expectedCommandId = [IO.Path]::GetFileNameWithoutExtension($job.Name)
$command = Read-AgentCommand -Path $job.FullName `
    -ExpectedCommandId $expectedCommandId
if ($Mode -eq 'ExecuteSignedDeployment' -and
    [int]$command.schemaVersion -ne 2) {
    throw 'Production execution accepts only a signed schema v2 command'
}
$approvalReport = $null
if ([int]$command.schemaVersion -eq 2) {
    foreach ($requiredValue in @(
        @{ Name = 'approval root'; Value = $ApprovalRoot },
        @{ Name = 'approval verifier path'; Value = $ApprovalVerifierPath },
        @{ Name = 'approval verifier digest'; Value = $ApprovalVerifierSha256 },
        @{ Name = 'release trust config path'; Value = $ReleaseTrustConfigPath }
    )) {
        if ([string]::IsNullOrWhiteSpace([string]$requiredValue.Value)) {
            throw "Signed release command requires $($requiredValue.Name)"
        }
    }

    $resolvedApprovalRoot = Get-FixedDirectory -Path $ApprovalRoot `
        -Label 'release approval root'
    $resolvedApprovalVerifierPath = Get-FixedFile `
        -Path $ApprovalVerifierPath -Label 'release approval verifier'
    if ((Split-Path -Leaf $resolvedApprovalVerifierPath) -cne
        'Test-LeanTpmReleaseApproval.ps1') {
        throw 'Release approval verifier file name is not approved'
    }
    $expectedApprovalVerifierSha256 = Assert-Sha256 `
        -Value $ApprovalVerifierSha256 `
        -Label 'release approval verifier digest'
    $actualApprovalVerifierSha256 = (
        Get-FileHash -LiteralPath $resolvedApprovalVerifierPath `
            -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($actualApprovalVerifierSha256 -cne
        $expectedApprovalVerifierSha256) {
        throw 'Release approval verifier digest does not match the approved SHA-256'
    }
    $resolvedReleaseTrustConfigPath = Get-FixedFile `
        -Path $ReleaseTrustConfigPath -Label 'release trust config'
    if ((Split-Path -Leaf $resolvedReleaseTrustConfigPath) -cne
        'release-trust.json') {
        throw 'Release trust config file name is not approved'
    }

    $approvalDirectory = Get-FixedDirectory -Path (
        Join-Path $resolvedApprovalRoot ([string]$command.approvalId)
    ) -Label 'signed release approval directory'
    $expectedApprovalDirectory = Join-Path $resolvedApprovalRoot `
        ([string]$command.approvalId)
    if (-not $approvalDirectory.Equals(
            [IO.Path]::GetFullPath($expectedApprovalDirectory).TrimEnd('\', '/'),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'Signed release approval directory is not exact'
    }
    $approvalEntries = @(Get-ChildItem -LiteralPath $approvalDirectory -Force)
    if ($approvalEntries.Count -ne 3 -or
        @($approvalEntries | Where-Object { $_.PSIsContainer }).Count -ne 0) {
        throw 'Signed release approval directory must contain exactly three files'
    }

    $expectedPlanPath = Join-Path $approvalDirectory 'deployment-plan.json'
    $expectedRequesterSignaturePath = Join-Path $approvalDirectory `
        'deployment-plan.requester.p7s'
    $expectedApproverSignaturePath = Join-Path $approvalDirectory `
        'deployment-plan.approver.p7s'
    $resolvedPlanPath = Assert-ContainedFile -Root $resolvedApprovalRoot `
        -Path ([string]$command.deploymentPlanPath) `
        -Label 'signed deployment plan'
    $resolvedRequesterSignaturePath = Assert-ContainedFile `
        -Root $resolvedApprovalRoot `
        -Path ([string]$command.requesterSignaturePath) `
        -Label 'signed requester signature'
    $resolvedApproverSignaturePath = Assert-ContainedFile `
        -Root $resolvedApprovalRoot `
        -Path ([string]$command.approverSignaturePath) `
        -Label 'signed approver signature'
    foreach ($binding in @(
        @{ Actual = $resolvedPlanPath; Expected = $expectedPlanPath },
        @{
            Actual = $resolvedRequesterSignaturePath
            Expected = $expectedRequesterSignaturePath
        },
        @{
            Actual = $resolvedApproverSignaturePath
            Expected = $expectedApproverSignaturePath
        }
    )) {
        if (-not $binding.Actual.Equals(
                [IO.Path]::GetFullPath($binding.Expected),
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw 'Signed release approval material path is not exact'
        }
    }

    $actualPlanSha256 = (
        Get-FileHash -LiteralPath $resolvedPlanPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $actualRequesterSignatureSha256 = (
        Get-FileHash -LiteralPath $resolvedRequesterSignaturePath `
            -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $actualApproverSignatureSha256 = (
        Get-FileHash -LiteralPath $resolvedApproverSignaturePath `
            -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($actualPlanSha256 -cne [string]$command.deploymentPlanSha256 -or
        $actualRequesterSignatureSha256 -cne
            [string]$command.requesterSignatureSha256 -or
        $actualApproverSignatureSha256 -cne
            [string]$command.approverSignatureSha256) {
        throw 'Signed release approval material no longer matches the command'
    }

    $deploymentPlanText = Read-LockedUtf8 -Path $resolvedPlanPath `
        -MaximumBytes 1048576 -Label 'signed deployment plan'
    $deploymentPlan = ConvertFrom-StrictJson -Text $deploymentPlanText `
        -Label 'signed deployment plan'
    Assert-RequiredProperties -Value $deploymentPlan `
        -Label 'signed deployment plan' -Required @(
            'schemaVersion',
            'environmentName',
            'environmentKind',
            'environmentId',
            'hostId',
            'releaseId',
            'approvalId',
            'packagePath',
            'packageSha256',
            'manifestSha256',
            'opsHostSnapshotSha256',
            'requestedBy',
            'approvedBy',
            'requesterSignaturePath',
            'approverSignaturePath',
            'hostLayoutSha256',
            'proxyBindingSha256'
        )
    if ([int]$deploymentPlan.schemaVersion -ne 1 -or
        [string]$deploymentPlan.environmentKind -cne 'PRODUCTION' -or
        [string]$deploymentPlan.releaseId -cne [string]$command.releaseId -or
        [string]$deploymentPlan.approvalId -cne [string]$command.approvalId -or
        [string]$deploymentPlan.packagePath -cne [string]$command.packagePath -or
        [string]$deploymentPlan.packageSha256 -cne
            [string]$command.packageSha256 -or
        [string]$deploymentPlan.manifestSha256 -cne
            [string]$command.manifestSha256 -or
        [string]$deploymentPlan.opsHostSnapshotSha256 -cne
            [string]$command.hostSnapshotSha256 -or
        -not ([string]$deploymentPlan.requesterSignaturePath).Equals(
            $resolvedRequesterSignaturePath,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        -not ([string]$deploymentPlan.approverSignaturePath).Equals(
            $resolvedApproverSignaturePath,
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        [string]$deploymentPlan.hostLayoutSha256 -notmatch $sha256Pattern -or
        [string]$deploymentPlan.proxyBindingSha256 -notmatch $sha256Pattern) {
        throw 'Signed deployment plan does not match the Agent command'
    }

    $approvalReport = Invoke-PinnedApprovalVerifier `
        -VerifierPath $resolvedApprovalVerifierPath `
        -ExpectedVerifierSha256 $actualApprovalVerifierSha256 `
        -PlanPath $resolvedPlanPath `
        -ExpectedPlanSha256 $actualPlanSha256 `
        -RequesterSignaturePath $resolvedRequesterSignaturePath `
        -ExpectedRequesterSignatureSha256 $actualRequesterSignatureSha256 `
        -ApproverSignaturePath $resolvedApproverSignaturePath `
        -ExpectedApproverSignatureSha256 $actualApproverSignatureSha256 `
        -TrustConfigPath $resolvedReleaseTrustConfigPath
    Assert-ExactProperties -Value $approvalReport `
        -Label 'release approval verifier report' -Expected @(
            'status',
            'requestedBy',
            'approvedBy',
            'requesterCertificateThumbprint',
            'approverCertificateThumbprint',
            'planSha256'
        )
    $requestedBy = [string]$approvalReport.requestedBy
    $approvedBy = [string]$approvalReport.approvedBy
    $requesterThumbprint = [string]$approvalReport.requesterCertificateThumbprint
    $approverThumbprint = [string]$approvalReport.approverCertificateThumbprint
    if ([string]$approvalReport.status -cne 'PASS' -or
        [string]$approvalReport.planSha256 -cne $actualPlanSha256 -or
        [string]::IsNullOrWhiteSpace($requestedBy) -or
        [string]::IsNullOrWhiteSpace($approvedBy) -or
        $requestedBy -ceq $approvedBy -or
        $requesterThumbprint -notmatch '^[A-Fa-f0-9]{40}$' -or
        $approverThumbprint -notmatch '^[A-Fa-f0-9]{40}$' -or
        $requesterThumbprint -ceq $approverThumbprint) {
        throw 'Release approval verifier report is not a valid dual-CMS approval'
    }
}
$resolvedPackagePath = Assert-ContainedFile -Root $resolvedUploadRoot `
    -Path ([string]$command.packagePath) -Label 'queued release package'
$packageItem = Get-Item -LiteralPath $resolvedPackagePath -Force
$packageSha256 = (
    Get-FileHash -LiteralPath $resolvedPackagePath -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($packageSha256 -cne [string]$command.packageSha256) {
    throw 'Queued release package bytes no longer match the command digest'
}

$resultsRoot = Join-Path $resolvedQueueRoot 'results'
if (-not (Test-Path -LiteralPath $resultsRoot)) {
    New-Item -ItemType Directory -Path $resultsRoot -ErrorAction Stop |
        Out-Null
}
$resultsRoot = Get-FixedDirectory -Path $resultsRoot `
    -Label 'release Agent results root'
$resultPath = Join-Path $resultsRoot ($expectedCommandId + '.json')
if (Test-Path -LiteralPath $resultPath) {
    $existingResult = Read-ExistingResult -Path $resultPath -Command $command
    if ([int]$existingResult.schemaVersion -eq 2 -or
        $Mode -eq 'VerifyOnly') {
        if ([int]$existingResult.schemaVersion -eq 2 -and
            (Test-Path -LiteralPath $job.FullName)) {
            $completedRootPath = Join-Path $resolvedQueueRoot 'completed'
            if (-not (Test-Path -LiteralPath $completedRootPath)) {
                New-Item -ItemType Directory -Path $completedRootPath `
                    -ErrorAction Stop | Out-Null
            }
            $completedRoot = Get-FixedDirectory -Path $completedRootPath `
                -Label 'release Agent completed root'
            $completedPath = Join-Path $completedRoot $job.Name
            if (Test-Path -LiteralPath $completedPath) {
                throw 'Pending and completed release Agent commands collide'
            }
            [IO.File]::Move($job.FullName, $completedPath)
        }
        $existingResult | ConvertTo-Json -Depth 5 -Compress
        return
    }
}

$verifierReport = Invoke-PinnedPackageVerifier `
    -VerifierPath $resolvedVerifierPath `
    -ExpectedVerifierSha256 $actualVerifierSha256 `
    -PackagePath $resolvedPackagePath `
    -ExpectedPackageSha256 $packageSha256
Assert-ExactProperties -Value $verifierReport `
    -Label 'release package verifier report' -Expected @(
        'artifactCount',
        'bytes',
        'databaseSchemaFrom',
        'databaseSchemaVersion',
        'expandedBytes',
        'manifestSha256',
        'package',
        'productVersion',
        'releaseId',
        'releaseTier',
        'schemaSha256',
        'sha256',
        'status'
    )
$verifiedPackagePath = Get-FixedFile -Path ([string]$verifierReport.package) `
    -Label 'verified release package'
$verifiedSha256 = Assert-Sha256 -Value ([string]$verifierReport.sha256) `
    -Label 'verified package digest'
$verifiedManifestSha256 = Assert-Sha256 `
    -Value ([string]$verifierReport.manifestSha256) `
    -Label 'verified manifest digest'
if ([string]$verifierReport.status -cne 'PASS' -or
    [string]$verifierReport.releaseTier -cne 'PRODUCTION' -or
    [string]$verifierReport.releaseId -cne [string]$command.releaseId -or
    -not $verifiedPackagePath.Equals(
        $resolvedPackagePath,
        [StringComparison]::OrdinalIgnoreCase
    ) -or
    [long]$verifierReport.bytes -ne $packageItem.Length -or
    $verifiedSha256 -cne [string]$command.packageSha256 -or
    $verifiedManifestSha256 -cne [string]$command.manifestSha256 -or
    [int]$verifierReport.databaseSchemaVersion -ne
        [int]$command.databaseSchemaVersion -or
    [string]$verifierReport.productVersion -cne
        [string]$command.productVersion) {
    throw 'Release package verifier report does not match the pending command'
}

if ($Mode -eq 'ExecuteSignedDeployment') {
    foreach ($requiredValue in @(
        @{ Name = 'deployment toolkit root'; Value = $DeploymentToolkitRoot },
        @{
            Name = 'deployment toolkit lock path'
            Value = $DeploymentToolkitLockPath
        },
        @{
            Name = 'deployment toolkit lock digest'
            Value = $DeploymentToolkitLockSha256
        }
    )) {
        if ([string]::IsNullOrWhiteSpace([string]$requiredValue.Value)) {
            throw "Production execution requires $($requiredValue.Name)"
        }
    }
    $completedRootPath = Join-Path $resolvedQueueRoot 'completed'
    if (-not (Test-Path -LiteralPath $completedRootPath)) {
        New-Item -ItemType Directory -Path $completedRootPath `
            -ErrorAction Stop | Out-Null
    }
    $completedRoot = Get-FixedDirectory -Path $completedRootPath `
        -Label 'release Agent completed root'
    $completedPath = Join-Path $completedRoot $job.Name
    if (Test-Path -LiteralPath $completedPath) {
        throw 'Release Agent completed command already exists'
    }
    $execution = Invoke-PinnedDeploymentExecutor `
        -ToolkitRoot $DeploymentToolkitRoot `
        -ToolkitLockPath $DeploymentToolkitLockPath `
        -ExpectedToolkitLockSha256 $DeploymentToolkitLockSha256 `
        -PlanPath $resolvedPlanPath `
        -Plan $deploymentPlan
    $deploymentResultCore = [ordered]@{
        agentId = $AgentId
        agentVersion = $AgentVersion
        approvalId = [string]$command.approvalId
        commandId = [string]$command.commandId
        databaseSchemaVersion = [int]$verifierReport.databaseSchemaVersion
        deploymentReportSha256 = [string]$execution.reportSha256
        deploymentStatus = [string]$execution.report.status
        hostSnapshotSha256 = [string]$command.hostSnapshotSha256
        manifestSha256 = $verifiedManifestSha256
        packageSha256 = $verifiedSha256
        planSha256 = [string]$command.planSha256
        productionExecutionEnabled = $true
        productVersion = [string]$verifierReport.productVersion
        releaseId = [string]$command.releaseId
        schemaVersion = 2
        status = 'DEPLOYED'
        verifiedAt = [datetimeoffset]::UtcNow.ToString('o')
    }
    $deploymentResult = [ordered]@{}
    foreach ($entry in $deploymentResultCore.GetEnumerator()) {
        $deploymentResult[$entry.Key] = $entry.Value
    }
    $deploymentResult['resultSha256'] = Get-Sha256Text -Text (
        $deploymentResultCore | ConvertTo-Json -Depth 4 -Compress
    )
    Write-AtomicJson -Path $resultPath -Value $deploymentResult `
        -ReplaceExisting

    [IO.File]::Move($job.FullName, $completedPath)

    $heartbeat.lastSeenAt = [datetimeoffset]::UtcNow.ToString('o')
    Write-AtomicJson -Path (
        Join-Path $resolvedQueueRoot 'agent-heartbeat.json'
    ) -Value $heartbeat -ReplaceExisting
    $deploymentResult | ConvertTo-Json -Depth 5 -Compress
    return
}

$resultCore = [ordered]@{
    agentId = $AgentId
    agentVersion = $AgentVersion
    commandId = [string]$command.commandId
    databaseSchemaVersion = [int]$verifierReport.databaseSchemaVersion
    hostSnapshotSha256 = [string]$command.hostSnapshotSha256
    manifestSha256 = $verifiedManifestSha256
    packageSha256 = $verifiedSha256
    planSha256 = [string]$command.planSha256
    productionExecutionEnabled = $false
    productVersion = [string]$verifierReport.productVersion
    releaseId = [string]$command.releaseId
    schemaVersion = 1
    status = 'VERIFIED_ONLY'
    verifiedAt = [datetimeoffset]::UtcNow.ToString('o')
}
$result = [ordered]@{}
foreach ($entry in $resultCore.GetEnumerator()) {
    $result[$entry.Key] = $entry.Value
}
$result['resultSha256'] = Get-Sha256Text -Text (
    $resultCore | ConvertTo-Json -Depth 4 -Compress
)
Write-AtomicJson -Path $resultPath -Value $result

$heartbeat.lastSeenAt = [datetimeoffset]::UtcNow.ToString('o')
Write-AtomicJson -Path (
    Join-Path $resolvedQueueRoot 'agent-heartbeat.json'
) -Value $heartbeat -ReplaceExisting
$result | ConvertTo-Json -Depth 5 -Compress
