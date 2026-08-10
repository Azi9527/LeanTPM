[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Backend'
$install = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\', '/')
$data = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\', '/')
$serviceRoot = Join-Path $install 'service'
$wrapperPath = Join-Path $serviceRoot "$serviceId.exe"
$configPath = Join-Path $serviceRoot "$serviceId.xml"
$starterPath = Join-Path $serviceRoot 'Start-LeanTpmBackend.ps1'
$trustPath = Join-Path $data 'config\release-trust.json'
$toolchainPath = Join-Path $PSScriptRoot '..\..\release\toolchain-lock.json'

foreach ($path in @($wrapperPath, $configPath, $starterPath, $trustPath, $toolchainPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            ((Get-Item -LiteralPath $path -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw "Windows Service supply-chain file is missing or unsafe: $path"
    }
}

$toolchain = Get-Content -LiteralPath $toolchainPath -Encoding utf8 -Raw | ConvertFrom-Json
$wrapperPin = [string]$toolchain.winSW.sha256
$javaPin = [string]$toolchain.java.sha256
$trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
$serviceAccount = [string]$trust.backendServiceAccount
$scriptSigner = [string]$trust.scriptSignerThumbprint
if ($wrapperPin -notmatch '^[a-f0-9]{64}$' -or $javaPin -notmatch '^[a-f0-9]{64}$') {
    throw 'WinSW or Java is not pinned in release/toolchain-lock.json'
}
if ($serviceAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -or
        $scriptSigner -notmatch '^[A-Fa-f0-9]{40,128}$') {
    throw 'Host trust does not contain an approved Backend gMSA and script signer'
}
if (-not ([string]$trust.winSWSha256).Equals(
        $wrapperPin, [StringComparison]::OrdinalIgnoreCase
    ) -or -not ([string]$trust.javaSha256).Equals(
        $javaPin, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Host trust WinSW/Java digests differ from release/toolchain-lock.json'
}
if (-not (Get-FileHash -Algorithm SHA256 -LiteralPath $wrapperPath).Hash.Equals(
        $wrapperPin, [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'Installed WinSW wrapper differs from the pinned digest'
}

$starterSignature = Get-AuthenticodeSignature -LiteralPath $starterPath
if ($starterSignature.Status -ne 'Valid' -or $null -eq $starterSignature.SignerCertificate -or
        -not $starterSignature.SignerCertificate.Thumbprint.Equals(
            $scriptSigner, [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Installed Backend starter is not signed by the host-pinned signer'
}

$configText = Get-Content -LiteralPath $configPath -Encoding utf8 -Raw
[xml]$config = $configText
if ([string]$config.service.id -cne $serviceId -or
        [string]$config.service.serviceaccount.username -cne $serviceAccount -or
        [string]$config.service.startmode -cne 'Automatic' -or
        [string]$config.service.delayedAutoStart -cne 'true') {
    throw 'Installed WinSW XML identity, account or start policy drifted'
}
$arguments = [string]$config.service.arguments
$javaMatch = [regex]::Match($arguments, '-JavaExecutable\s+"([^"]+)"')
if (-not $javaMatch.Success -or $arguments -notmatch '-ExecutionPolicy\s+AllSigned' -or
        $arguments -notmatch '-File\s+"[^"]*Start-LeanTpmBackend\.ps1"') {
    throw 'Installed WinSW XML does not bind the AllSigned fixed starter and Java executable'
}
$javaPath = [IO.Path]::GetFullPath($javaMatch.Groups[1].Value)
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf) -or
        -not (Get-FileHash -Algorithm SHA256 -LiteralPath $javaPath).Hash.Equals(
            $javaPin, [StringComparison]::OrdinalIgnoreCase
        )) {
    throw 'Installed Backend Java executable differs from the pinned digest'
}
$template = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'LeanTPM.Backend.xml.template') `
    -Encoding utf8 -Raw
$expectedConfig = $template.Replace('@SERVICE_ROOT@', $serviceRoot).
    Replace('@INSTALL_ROOT@', $install).
    Replace('@DATA_ROOT@', $data).
    Replace('@JAVA_EXECUTABLE@', $javaPath).
    Replace('@SERVICE_ACCOUNT@', $serviceAccount)
if ($configText -cne $expectedConfig) {
    throw 'Installed WinSW XML bytes differ from the host-rendered fixed template'
}

$service = Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceId'" -ErrorAction Stop
if ($null -eq $service) { throw 'LeanTPM.Backend is not registered in SCM' }
$imagePath = ([string]$service.PathName).Trim().Trim('"')
if (-not $imagePath.Equals($wrapperPath, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$service.StartName -cne $serviceAccount -or
        [string]$service.StartMode -notin @('Auto', 'Automatic')) {
    throw 'Win32_Service image, StartName or StartMode differs from the approved binding'
}

function Assert-ProtectedDirectoryAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][ValidateSet('Read', 'Modify', 'None')][string]$ServiceAccess,
        [Parameter(Mandatory)][string]$ServiceSid,
        [string[]]$ExactReaders = @()
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Container) -or
            ((Get-Item -LiteralPath $Path -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw "Protected service directory is missing or unsafe: $Path"
    }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) {
        throw "ACL inheritance is enabled on protected service directory: $Path"
    }
    $ownerSid = try {
        (New-Object Security.Principal.NTAccount($acl.Owner)).Translate(
            [Security.Principal.SecurityIdentifier]
        ).Value
    }
    catch { '' }
    if ($ownerSid -notin @('S-1-5-18', 'S-1-5-32-544')) {
        throw "Protected service directory has an unapproved owner: $Path"
    }
    $rules = @($acl.GetAccessRules(
            $true, $true, [Security.Principal.SecurityIdentifier]
        ))
    $writeMask = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $serviceRules = @($rules | Where-Object {
            $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            $_.IdentityReference.Value -eq $ServiceSid
        })
    foreach ($rule in $rules) {
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Deny) {
            if ($rule.IdentityReference.Value -eq $ServiceSid -and $ServiceAccess -ne 'None') {
                throw "Backend service account has an explicit deny ACE: $Path"
            }
            continue
        }
        $sid = $rule.IdentityReference.Value
        if ($ExactReaders.Count -gt 0 -and $sid -notin $ExactReaders) {
            throw "Unexpected reader $sid on restricted service directory: $Path"
        }
        $hasWrite = ([int64]$rule.FileSystemRights -band [int64]$writeMask) -ne 0
        $serviceMayWrite = $ServiceAccess -eq 'Modify' -and $sid -eq $ServiceSid
        if ($hasWrite -and $sid -notin @('S-1-5-18', 'S-1-5-32-544') -and
                -not $serviceMayWrite) {
            throw "Unexpected writable principal $sid on protected service directory: $Path"
        }
    }
    if ($ServiceAccess -eq 'None' -and $serviceRules.Count -ne 0) {
        throw "Backend service account must not access control directory: $Path"
    }
    $serviceRights = [int64]0
    foreach ($serviceRule in $serviceRules) {
        $serviceRights = $serviceRights -bor [int64]$serviceRule.FileSystemRights
    }
    $requiredReadAndExecuteFound =
        ($serviceRights -band
            [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq
        [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute
    if ($ServiceAccess -eq 'Read' -and (
            -not $requiredReadAndExecuteFound -or
            ($serviceRights -band [int64]$writeMask) -ne 0
        )) {
        throw "Backend service account must have read-only access: $Path"
    }
    if ($ServiceAccess -eq 'Modify' -and
            ($serviceRights -band
                [int64][Security.AccessControl.FileSystemRights]::Modify) -ne
                [int64][Security.AccessControl.FileSystemRights]::Modify) {
        throw "Backend service account must have Modify access: $Path"
    }
}

function Assert-ProtectedFileAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$AllowedWriters,
        [string[]]$ExactReaders = @(),
        [string[]]$RequiredReadAndExecuteSids = @(),
        [switch]$RequireProtected
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or
            ((Get-Item -LiteralPath $Path -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint)) {
        throw "Protected service file is missing or unsafe: $Path"
    }
    $acl = Get-Acl -LiteralPath $Path
    if ($RequireProtected -and -not $acl.AreAccessRulesProtected) {
        throw "ACL inheritance is enabled on protected service file: $Path"
    }
    $ownerSid = try {
        (New-Object Security.Principal.NTAccount($acl.Owner)).Translate(
            [Security.Principal.SecurityIdentifier]
        ).Value
    }
    catch { '' }
    $trustedInstallerSid = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
    if ($ownerSid -notin @('S-1-5-18', 'S-1-5-32-544', $trustedInstallerSid)) {
        throw "Protected service file has an unapproved owner: $Path"
    }
    $rules = @($acl.GetAccessRules(
            $true, $true, [Security.Principal.SecurityIdentifier]
        ))
    $writeMask = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $rightsBySid = @{}
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Value
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Deny) {
            if ($sid -in $RequiredReadAndExecuteSids) {
                throw "Required service reader has an explicit deny ACE: $Path"
            }
            continue
        }
        if ($ExactReaders.Count -gt 0 -and $sid -notin $ExactReaders) {
            throw "Unexpected reader $sid on restricted service file: $Path"
        }
        if (-not $rightsBySid.ContainsKey($sid)) { $rightsBySid[$sid] = [int64]0 }
        $rightsBySid[$sid] = [int64]$rightsBySid[$sid] -bor [int64]$rule.FileSystemRights
        if (([int64]$rule.FileSystemRights -band [int64]$writeMask) -ne 0 -and
                $sid -notin $AllowedWriters) {
            throw "Unexpected writer $sid on protected service file: $Path"
        }
    }
    $requiredReadAndExecuteFound = $true
    foreach ($requiredSid in $RequiredReadAndExecuteSids) {
        if (-not $rightsBySid.ContainsKey($requiredSid) -or
                ([int64]$rightsBySid[$requiredSid] -band
                    [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) -ne
                    [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) {
            $requiredReadAndExecuteFound = $false
        }
    }
    if (-not $requiredReadAndExecuteFound) {
        throw "Required service reader lacks ReadAndExecute: $Path"
    }
}

$serviceSid = (New-Object Security.Principal.NTAccount($serviceAccount)).Translate(
    [Security.Principal.SecurityIdentifier]
).Value
foreach ($readPath in @(
        $serviceRoot, (Join-Path $install 'releases'), $data,
        (Join-Path $data 'pointers'), (Join-Path $data 'config'), (Join-Path $data 'state')
    )) {
    Assert-ProtectedDirectoryAcl -Path $readPath -ServiceAccess Read -ServiceSid $serviceSid
}
$exactSecretReaders = @('S-1-5-18', 'S-1-5-32-544', $serviceSid)
$secretsPath = Join-Path $data 'secrets'
Assert-ProtectedDirectoryAcl -Path $secretsPath -ServiceAccess Read -ServiceSid $serviceSid `
    -ExactReaders $exactSecretReaders
foreach ($writePath in @((Join-Path $data 'data\uploads'), (Join-Path $data 'logs'))) {
    Assert-ProtectedDirectoryAcl -Path $writePath -ServiceAccess Modify -ServiceSid $serviceSid
}
foreach ($controlPath in @(
        (Join-Path $data 'staging'), (Join-Path $data 'audit'),
        (Join-Path $data 'backups'), (Join-Path $data 'locks')
    )) {
    Assert-ProtectedDirectoryAcl -Path $controlPath -ServiceAccess None -ServiceSid $serviceSid
}

$administrativeWriters = @(
    'S-1-5-18', 'S-1-5-32-544',
    'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
)
$installedFileReaders = @('S-1-5-18', 'S-1-5-32-544', $serviceSid)
Assert-ProtectedFileAcl -Path $wrapperPath -AllowedWriters $administrativeWriters `
    -ExactReaders $installedFileReaders -RequiredReadAndExecuteSids @($serviceSid) `
    -RequireProtected
Assert-ProtectedFileAcl -Path $configPath -AllowedWriters $administrativeWriters `
    -ExactReaders $installedFileReaders -RequiredReadAndExecuteSids @($serviceSid) `
    -RequireProtected
Assert-ProtectedFileAcl -Path $starterPath -AllowedWriters $administrativeWriters `
    -ExactReaders $installedFileReaders -RequiredReadAndExecuteSids @($serviceSid) `
    -RequireProtected
Assert-ProtectedFileAcl -Path $trustPath -AllowedWriters $administrativeWriters `
    -ExactReaders $installedFileReaders -RequireProtected
Assert-ProtectedFileAcl -Path $toolchainPath -AllowedWriters $administrativeWriters
Assert-ProtectedFileAcl -Path $javaPath -AllowedWriters $administrativeWriters `
    -RequiredReadAndExecuteSids @($serviceSid)
foreach ($secretFile in @(Get-ChildItem -LiteralPath $secretsPath -File -Force)) {
    Assert-ProtectedFileAcl -Path $secretFile.FullName -AllowedWriters $administrativeWriters `
        -ExactReaders $exactSecretReaders -RequireProtected
}
foreach ($releaseDirectory in @(Get-ChildItem -LiteralPath (Join-Path $install 'releases') `
            -Directory -Force)) {
    $releaseAcl = & (Join-Path $PSScriptRoot 'Protect-LeanTpmReleaseDirectory.ps1') `
        -InstallRoot $install -DataRoot $data -ReleaseId $releaseDirectory.Name `
        -VerifyOnly -OutputFormat Json | ConvertFrom-Json
    if ([string]$releaseAcl.status -cne 'PASS') {
        throw "Immutable release ACL verification failed: $($releaseDirectory.Name)"
    }
}

$expectedServiceSddl = 'D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)'
$sddlOutput = @(& sc.exe sdshow $serviceId 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'sc.exe sdshow failed for LeanTPM.Backend' }
$actualServiceSddl = [string]($sddlOutput | Where-Object {
        ([string]$_).Trim() -match '^[OGDS]:'
    } | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($actualServiceSddl) -or
        $actualServiceSddl.Trim() -cne $expectedServiceSddl) {
    throw 'LeanTPM.Backend SCM DACL differs from the fixed least-privilege SDDL'
}

$report = [pscustomobject]@{
    status = 'PASS'
    serviceId = $serviceId
    state = [string]$service.State
    startName = [string]$service.StartName
    startMode = [string]$service.StartMode
    wrapperSha256 = $wrapperPin
    javaSha256 = $javaPin
    serviceSddl = $expectedServiceSddl
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
