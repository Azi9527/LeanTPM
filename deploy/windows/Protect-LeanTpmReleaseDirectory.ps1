[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$ReleaseId,
    [switch]$VerifyOnly,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($ReleaseId -notmatch '^[a-z0-9][a-z0-9._-]{2,127}$' -or
        $ReleaseId -match '^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)' -or
        $ReleaseId -match '\.$') {
    throw 'ReleaseId is not safe for an immutable Windows release directory'
}

$install = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\', '/')
$data = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\', '/')
$releasesRoot = Join-Path $install 'releases'
$releaseRoot = Join-Path $releasesRoot $ReleaseId
$payloadRoot = Join-Path $releaseRoot 'payload'
$webRoot = Join-Path $payloadRoot 'web'
$trustPath = Join-Path $data 'config\release-trust.json'
foreach ($requiredPath in @($releaseRoot, $payloadRoot, $webRoot, $trustPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Release ACL prerequisite is missing: $requiredPath"
    }
}

$releaseFull = (Resolve-Path -LiteralPath $releaseRoot).Path.TrimEnd('\', '/')
$expectedReleaseFull = [IO.Path]::GetFullPath($releaseRoot).TrimEnd('\', '/')
$expectedPrefix = [IO.Path]::GetFullPath($releasesRoot).TrimEnd('\', '/') +
    [IO.Path]::DirectorySeparatorChar
if (-not $releaseFull.Equals($expectedReleaseFull, [StringComparison]::OrdinalIgnoreCase) -or
        -not $releaseFull.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release directory escaped the canonical releases root'
}

$trust = Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw | ConvertFrom-Json
$backendAccount = [string]$trust.backendServiceAccount
$proxyAccount = [string]$trust.proxyServiceAccount
foreach ($account in @($backendAccount, $proxyAccount)) {
    if ($account -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$') {
        throw 'Host trust must pin distinct Backend and Proxy gMSA identities'
    }
}
if ($backendAccount.Equals($proxyAccount, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backend and Proxy identities must remain isolated'
}

$adminAccount = New-Object Security.Principal.NTAccount('BUILTIN', 'Administrators')
$systemAccount = New-Object Security.Principal.NTAccount('NT AUTHORITY', 'SYSTEM')
$backendIdentity = New-Object Security.Principal.NTAccount($backendAccount)
$proxyIdentity = New-Object Security.Principal.NTAccount($proxyAccount)
$adminSid = $adminAccount.Translate([Security.Principal.SecurityIdentifier]).Value
$systemSid = $systemAccount.Translate([Security.Principal.SecurityIdentifier]).Value
$backendSid = $backendIdentity.Translate([Security.Principal.SecurityIdentifier]).Value
$proxySid = $proxyIdentity.Translate([Security.Principal.SecurityIdentifier]).Value

$allItems = @((Get-Item -LiteralPath $releaseFull -Force)) +
    @(Get-ChildItem -LiteralPath $releaseFull -Force -Recurse)
foreach ($item in $allItems) {
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Immutable release trees cannot contain reparse points: $($item.FullName)"
    }
}

function Test-ProxyReadRequired {
    param([Parameter(Mandatory)][string]$Path)
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $payloadFull = [IO.Path]::GetFullPath($payloadRoot).TrimEnd('\', '/')
    $webFull = [IO.Path]::GetFullPath($webRoot).TrimEnd('\', '/')
    return $full.Equals($releaseFull, [StringComparison]::OrdinalIgnoreCase) -or
        $full.Equals($payloadFull, [StringComparison]::OrdinalIgnoreCase) -or
        $full.Equals($webFull, [StringComparison]::OrdinalIgnoreCase) -or
        $full.StartsWith(
            $webFull + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )
}

function New-ReleaseDirectoryAcl {
    param([Parameter(Mandatory)][bool]$ProxyReadRequired)
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($adminAccount)
    $inherit = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $propagate = [Security.AccessControl.PropagationFlags]::None
    foreach ($ruleSpec in @(
            @($adminAccount, [Security.AccessControl.FileSystemRights]::FullControl),
            @($systemAccount, [Security.AccessControl.FileSystemRights]::FullControl),
            @($backendIdentity, [Security.AccessControl.FileSystemRights]::ReadAndExecute)
        )) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                    $ruleSpec[0], $ruleSpec[1], $inherit, $propagate,
                    [Security.AccessControl.AccessControlType]::Allow
                )))
    }
    if ($ProxyReadRequired) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                    $proxyIdentity, [Security.AccessControl.FileSystemRights]::ReadAndExecute,
                    $inherit, $propagate, [Security.AccessControl.AccessControlType]::Allow
                )))
    }
    return $acl
}

function New-ReleaseFileAcl {
    param([Parameter(Mandatory)][bool]$ProxyReadRequired)
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($adminAccount)
    foreach ($ruleSpec in @(
            @($adminAccount, [Security.AccessControl.FileSystemRights]::FullControl),
            @($systemAccount, [Security.AccessControl.FileSystemRights]::FullControl),
            @($backendIdentity, [Security.AccessControl.FileSystemRights]::ReadAndExecute)
        )) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                    $ruleSpec[0], $ruleSpec[1],
                    [Security.AccessControl.AccessControlType]::Allow
                )))
    }
    if ($ProxyReadRequired) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                    $proxyIdentity, [Security.AccessControl.FileSystemRights]::ReadAndExecute,
                    [Security.AccessControl.AccessControlType]::Allow
                )))
    }
    return $acl
}

function Assert-ReleaseItemAcl {
    param(
        [Parameter(Mandatory)]$Item,
        [Parameter(Mandatory)][bool]$ProxyReadRequired
    )
    $acl = Get-Acl -LiteralPath $Item.FullName
    if (-not $acl.AreAccessRulesProtected) {
        throw "Release ACL inheritance remains enabled: $($Item.FullName)"
    }
    $ownerSid = (New-Object Security.Principal.NTAccount($acl.Owner)).Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
    if ($ownerSid -ne $adminSid) {
        throw "Release ACL owner is not Administrators: $($Item.FullName)"
    }
    $rules = @($acl.GetAccessRules(
            $true, $true, [Security.Principal.SecurityIdentifier]
        ))
    $allowedReaders = @($adminSid, $systemSid, $backendSid)
    if ($ProxyReadRequired) { $allowedReaders += $proxySid }
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
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $sid -notin $allowedReaders) {
            throw "Unexpected release ACL entry for $sid on $($Item.FullName)"
        }
        if (-not $rightsBySid.ContainsKey($sid)) { $rightsBySid[$sid] = [int64]0 }
        $rightsBySid[$sid] = [int64]$rightsBySid[$sid] -bor [int64]$rule.FileSystemRights
        if ($sid -in @($backendSid, $proxySid) -and
                ([int64]$rule.FileSystemRights -band [int64]$writeMask) -ne 0) {
            throw "Runtime identity has write/delete rights on immutable release: $($Item.FullName)"
        }
    }
    foreach ($sid in @($adminSid, $systemSid)) {
        if (-not $rightsBySid.ContainsKey($sid) -or
                ([int64]$rightsBySid[$sid] -band
                    [int64][Security.AccessControl.FileSystemRights]::FullControl) -ne
                    [int64][Security.AccessControl.FileSystemRights]::FullControl) {
            throw "Administrative release ACL is incomplete: $($Item.FullName)"
        }
    }
    $requiredReadAndExecuteFound = $rightsBySid.ContainsKey($backendSid) -and
        (([int64]$rightsBySid[$backendSid] -band
                [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq
            [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute)
    if (-not $requiredReadAndExecuteFound) {
        throw "Backend lacks required ReadAndExecute on immutable release: $($Item.FullName)"
    }
    $proxyReadFound = $rightsBySid.ContainsKey($proxySid) -and
        (([int64]$rightsBySid[$proxySid] -band
                [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute) -eq
            [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute)
    if ($proxyReadFound -ne $ProxyReadRequired) {
        throw "Proxy release ACL does not match the Web-only read boundary: $($Item.FullName)"
    }
}

$beforeDigest = & (Join-Path $PSScriptRoot '..\..\scripts\Get-LeanTpmDirectoryDigest.ps1') `
    -DirectoryPath $releaseFull -OutputFormat Json | ConvertFrom-Json
if (-not $VerifyOnly) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator privileges are required to protect an immutable release directory'
    }
    if ($PSCmdlet.ShouldProcess($releaseFull, 'Replace release tree ACL with fixed immutable policy')) {
        foreach ($directory in @($allItems | Where-Object { $_.PSIsContainer })) {
            Set-Acl -LiteralPath $directory.FullName -AclObject (
                New-ReleaseDirectoryAcl -ProxyReadRequired (Test-ProxyReadRequired $directory.FullName)
            )
        }
        foreach ($file in @($allItems | Where-Object { -not $_.PSIsContainer })) {
            Set-Acl -LiteralPath $file.FullName -AclObject (
                New-ReleaseFileAcl -ProxyReadRequired (Test-ProxyReadRequired $file.FullName)
            )
        }
    }
}

$allItems = @((Get-Item -LiteralPath $releaseFull -Force)) +
    @(Get-ChildItem -LiteralPath $releaseFull -Force -Recurse)
foreach ($item in $allItems) {
    Assert-ReleaseItemAcl -Item $item -ProxyReadRequired (Test-ProxyReadRequired $item.FullName)
}
$afterDigest = & (Join-Path $PSScriptRoot '..\..\scripts\Get-LeanTpmDirectoryDigest.ps1') `
    -DirectoryPath $releaseFull -OutputFormat Json | ConvertFrom-Json
if ([string]$afterDigest.digest -cne [string]$beforeDigest.digest -or
        [int]$afterDigest.fileCount -ne [int]$beforeDigest.fileCount -or
        [int64]$afterDigest.totalBytes -ne [int64]$beforeDigest.totalBytes) {
    throw 'Release content changed while normalizing or verifying ACLs'
}

$report = [pscustomobject]@{
    status = 'PASS'
    mode = if ($VerifyOnly) { 'VERIFY_ONLY' } else { 'PROTECTED' }
    releaseId = $ReleaseId
    releaseRoot = $releaseFull
    itemCount = $allItems.Count
    contentDigest = [string]$afterDigest.digest
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
else { $report | Format-List }
