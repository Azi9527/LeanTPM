[CmdletBinding()]
param(
    [string]$BootstrapRoot = 'C:\ProgramData\LeanTPM-bootstrap',
    [switch]$PlanOnly,
    [switch]$AllowMissing,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$productionBootstrapRoot = [IO.Path]::GetFullPath(
    'C:\ProgramData\LeanTPM-bootstrap'
).TrimEnd('\')

function Get-BytesSha256 {
    param([byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally { $sha.Dispose() }
}

function Get-TextSha256Identity {
    param([string]$Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return 'sha256:' + (Get-BytesSha256 $bytes)
}

function Get-SidValue {
    param($IdentityReference)

    $identity = if ($IdentityReference -is [string]) {
        New-Object -TypeName Security.Principal.NTAccount -ArgumentList $IdentityReference
    }
    else { $IdentityReference }
    return $identity.Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
}

function Assert-ApprovedGmsaAccount {
    param(
        [Parameter(Mandatory)][string]$Account,
        [Parameter(Mandatory)][string]$Role
    )

    if ($Account -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$') {
        throw "$Role service identity must be an approved domain gMSA account"
    }
    $sid = Get-SidValue $Account
    if ($sid -notmatch '^S-1-5-21-(?:[0-9]+-){3}[0-9]+$') {
        throw "$Role service identity must resolve to a domain account SID"
    }
    return $sid
}

function Assert-HostOwnedAcl {
    param(
        [string]$Path,
        [string[]]$AllowedReadOnlySids = @()
    )

    $administratorsSid = 'S-1-5-32-544'
    $systemSid = 'S-1-5-18'
    $acl = Get-Acl -LiteralPath $Path
    $ownerSid = Get-SidValue $acl.Owner
    if ($ownerSid -notin @($administratorsSid, $systemSid) -or
            -not $acl.AreAccessRulesProtected) {
        throw "Host-owned path owner or inheritance is unsafe: $Path"
    }

    $administratorsFullControl = $false
    $writeMask = [int][Security.AccessControl.FileSystemRights]::WriteData -bor
        [int][Security.AccessControl.FileSystemRights]::AppendData -bor
        [int][Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [int][Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [int][Security.AccessControl.FileSystemRights]::Delete -bor
        [int][Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [int][Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [int][Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in $acl.Access) {
        if ($rule.IsInherited) { throw "Host-owned path contains inherited ACL rules: $Path" }
        $sid = Get-SidValue $rule.IdentityReference
        $rights = [int]$rule.FileSystemRights
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            throw "Host-owned path contains an explicit deny rule: $Path"
        }
        if ($sid -in @($administratorsSid, $systemSid)) {
            if (($rights -band [int][Security.AccessControl.FileSystemRights]::FullControl) -eq
                    [int][Security.AccessControl.FileSystemRights]::FullControl) {
                if ($sid -ceq $administratorsSid) { $administratorsFullControl = $true }
            }
            continue
        }
        $readExecute = [int][Security.AccessControl.FileSystemRights]::ReadAndExecute
        $allowedReadMask = $readExecute -bor [int][Security.AccessControl.FileSystemRights]::Synchronize
        $scopeIsExact = $rule.InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]::None -and
            $rule.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None
        if ($sid -notin $AllowedReadOnlySids -or ($rights -band $writeMask) -ne 0 -or
                ($rights -band $readExecute) -ne $readExecute -or
                ($rights -band (-bnot $allowedReadMask)) -ne 0 -or -not $scopeIsExact) {
            throw "Unexpected principal can access or modify a host-owned path: $Path"
        }
    }
    if (-not $administratorsFullControl) {
        throw "Administrators must retain explicit FullControl: $Path"
    }
}

function Assert-NoUnexpectedWriteAcl {
    param([string]$Path)

    $administrativeSids = @('S-1-5-18', 'S-1-5-32-544')
    $acl = Get-Acl -LiteralPath $Path
    if ((Get-SidValue $acl.Owner) -notin $administrativeSids -or
            -not $acl.AreAccessRulesProtected) {
        throw "Host-owned path owner or inheritance is unsafe: $Path"
    }
    $writeMask = [int][Security.AccessControl.FileSystemRights]::WriteData -bor
        [int][Security.AccessControl.FileSystemRights]::AppendData -bor
        [int][Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [int][Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [int][Security.AccessControl.FileSystemRights]::Delete -bor
        [int][Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [int][Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [int][Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        $sid = Get-SidValue $rule.IdentityReference
        if ($sid -notin $administrativeSids -and
                (([int]$rule.FileSystemRights -band $writeMask) -ne 0)) {
            throw "Unexpected principal can modify a host-owned path: $Path"
        }
    }
}

function Assert-ParentChainNoUntrustedMutation {
    param([string]$Path)

    $administrativeSids = @(
        'S-1-5-18',
        'S-1-5-32-544',
        'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
    )
    $creatorOwnerSid = 'S-1-3-0'
    $destructiveParentMask = [int][Security.AccessControl.FileSystemRights]::Delete -bor
        [int][Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [int][Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [int][Security.AccessControl.FileSystemRights]::TakeOwnership
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $volumeRoot = [IO.Path]::GetPathRoot($fullPath)
    $parent = [IO.Directory]::GetParent($fullPath)
    while ($null -ne $parent) {
        $parentPath = $parent.FullName
        $acl = Get-Acl -LiteralPath $parentPath
        $ownerSid = Get-SidValue $acl.Owner
        if ($ownerSid -notin $administrativeSids) {
            throw "Untrusted principal owns a parent path: $parentPath"
        }
        foreach ($rule in $acl.Access) {
            if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
                continue
            }
            $sid = Get-SidValue $rule.IdentityReference
            $inheritOnlyCreatorRule = $sid -ceq $creatorOwnerSid -and
                $rule.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::InheritOnly
            if ($inheritOnlyCreatorRule) { continue }
            if ($sid -notin $administrativeSids -and
                    (([int]$rule.FileSystemRights -band $destructiveParentMask) -ne 0)) {
                throw "Untrusted principal can replace a host-owned path through parent: $parentPath"
            }
        }
        if ($parentPath.Equals($volumeRoot, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = $parent.Parent
    }
}

function Assert-NoReparseChain {
    param([string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $root = [IO.Path]::GetPathRoot($fullPath)
    $current = $fullPath
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Host-owned path chain contains a reparse point: $current"
        }
        if ($current.Equals($root, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) { throw "Unable to walk the host-owned path chain: $Path" }
        $current = if ($parent.Parent) { $parent.FullName.TrimEnd('\') } else { $parent.FullName }
    }
}

function Initialize-NativePathType {
    if ($null -eq ('LeanTpm.NativeDirectoryPath' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace LeanTpm {
    public static class NativeDirectoryPath {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(
            string fileName, uint desiredAccess, uint shareMode, IntPtr securityAttributes,
            uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(
            SafeFileHandle handle, StringBuilder path, uint pathLength, uint flags);

        public static string Resolve(string path) {
            const uint ShareAll = 1u | 2u | 4u;
            const uint OpenExisting = 3u;
            const uint BackupSemantics = 0x02000000u;
            using (SafeFileHandle handle = CreateFile(
                path, 0u, ShareAll, IntPtr.Zero, OpenExisting, BackupSemantics, IntPtr.Zero)) {
                if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                StringBuilder buffer = new StringBuilder(32768);
                uint length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0u);
                if (length == 0u || length >= buffer.Capacity) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                return buffer.ToString();
            }
        }

        public static string ResolveHandle(SafeFileHandle handle) {
            if (handle == null || handle.IsInvalid) throw new ArgumentException("Invalid handle");
            StringBuilder buffer = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0u);
            if (length == 0u || length >= buffer.Capacity) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            return buffer.ToString();
        }
    }
}
'@
    }
}

function ConvertFrom-NativeFinalPath {
    param([string]$Path)

    $finalPath = $Path
    if ($finalPath.StartsWith('\\?\', [StringComparison]::Ordinal)) {
        $finalPath = $finalPath.Substring(4)
    }
    return [IO.Path]::GetFullPath($finalPath).TrimEnd('\')
}

function Get-FinalDirectoryPath {
    param([string]$Path)

    Initialize-NativePathType
    $finalPath = [LeanTpm.NativeDirectoryPath]::Resolve($Path)
    return ConvertFrom-NativeFinalPath $finalPath
}

function Get-FinalHandlePath {
    param([Microsoft.Win32.SafeHandles.SafeFileHandle]$SafeFileHandle)

    Initialize-NativePathType
    return ConvertFrom-NativeFinalPath (
        [LeanTpm.NativeDirectoryPath]::ResolveHandle($SafeFileHandle)
    )
}

function Get-ActualHostId {
    $machineGuid = [string](Get-ItemProperty `
        -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' `
        -Name MachineGuid -ErrorAction Stop).MachineGuid
    $systemProduct = Get-CimInstance -ClassName Win32_ComputerSystemProduct -ErrorAction Stop
    $smbiosUuid = [string]$systemProduct.UUID
    $machineGuidValue = [Guid]::Empty
    $smbiosUuidValue = [Guid]::Empty
    if (-not [Guid]::TryParse($machineGuid.Trim(), [ref]$machineGuidValue) -or
            -not [Guid]::TryParse($smbiosUuid.Trim(), [ref]$smbiosUuidValue) -or
            $machineGuidValue -eq [Guid]::Empty -or $smbiosUuidValue -eq [Guid]::Empty -or
            $smbiosUuidValue.ToString('N') -ceq ('f' * 32)) {
        throw 'Windows host identity is missing or invalid'
    }
    $canonical = "machineGuid={0}`nsmbiosUuid={1}" -f
        $machineGuidValue.ToString('D'), $smbiosUuidValue.ToString('D')
    $bytes = [Text.Encoding]::UTF8.GetBytes($canonical)
    return Get-BytesSha256 $bytes
}

function Get-ActualVolumeIdentity {
    param([string]$InstallRoot, [string]$DataRoot)

    $installDrive = [IO.Path]::GetPathRoot($InstallRoot).TrimEnd('\').ToUpperInvariant()
    $dataDrive = [IO.Path]::GetPathRoot($DataRoot).TrimEnd('\').ToUpperInvariant()
    if ($installDrive -cne $dataDrive -or $installDrive -notmatch '^[A-Z]:$') {
        throw 'InstallRoot and DataRoot must use the same fixed local volume'
    }
    $volume = Get-CimInstance -ClassName Win32_Volume `
        -Filter ("DriveLetter='{0}'" -f $installDrive) -ErrorAction Stop
    if ($null -eq $volume -or [int]$volume.DriveType -ne 3 -or
            [string]$volume.FileSystem -cne 'NTFS' -or
            [string]::IsNullOrWhiteSpace([string]$volume.DeviceID)) {
        throw 'Approved roots must use a fixed NTFS volume with a stable DeviceID'
    }
    return Get-TextSha256Identity ([string]$volume.DeviceID).Trim().ToLowerInvariant()
}

$bootstrap = [IO.Path]::GetFullPath($BootstrapRoot).TrimEnd('\', '/')
if (-not $PlanOnly -and
        -not $bootstrap.Equals($productionBootstrapRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Executable host bootstrap must use the fixed production bootstrap root'
}
if ($AllowMissing -and ($PlanOnly -or
        -not $bootstrap.Equals($productionBootstrapRoot, [StringComparison]::OrdinalIgnoreCase))) {
    throw 'AllowMissing is limited to fixed production bootstrap discovery'
}
if (-not (Test-Path -LiteralPath $bootstrap -PathType Container)) {
    if ($AllowMissing) {
        $missingReport = [pscustomobject]@{
            status = 'MISSING'
            executable = $false
            trustSource = 'FIXED_BOOTSTRAP_NOT_CONFIGURED'
            productionBootstrapRoot = $productionBootstrapRoot
            hostFilesystemVerified = $false
        }
        if ($OutputFormat -eq 'Json') {
            $missingReport | ConvertTo-Json -Depth 4 -Compress
        }
        else { $missingReport | Format-List }
        return
    }
    throw 'Host bootstrap root does not exist'
}
$layoutPath = Join-Path $bootstrap 'host-layout.json'
if (-not (Test-Path -LiteralPath $layoutPath -PathType Leaf)) {
    throw 'Host bootstrap layout does not exist'
}
if (((Get-Item -LiteralPath $bootstrap -Force).Attributes -band
            [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        ((Get-Item -LiteralPath $layoutPath -Force).Attributes -band
            [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Host bootstrap cannot use reparse points'
}
$bootstrapEntries = @(Get-ChildItem -LiteralPath $bootstrap -Force)
if ($bootstrapEntries.Count -ne 1 -or
        $bootstrapEntries[0].Name -cne 'host-layout.json' -or
        $bootstrapEntries[0].PSIsContainer) {
    throw 'Host bootstrap may contain only the fixed host-layout.json policy file'
}

$proxyPolicyVerifiedSha256 = $null
$backendServiceAccount = $null
$proxyServiceAccount = $null
$opsControlServiceAccount = $null
$releaseAgentServiceAccount = $null
$backendSid = $null
$proxySid = $null
$opsControlSid = $null
$releaseAgentSid = $null
$layoutStream = [IO.File]::Open(
    $layoutPath,
    [IO.FileMode]::Open,
    [IO.FileAccess]::Read,
    [IO.FileShare]::Read
)
try {
    $memory = New-Object IO.MemoryStream
    try { $layoutStream.CopyTo($memory); $layoutBytes = $memory.ToArray() }
    finally { $memory.Dispose() }
    $layoutSha256 = Get-BytesSha256 $layoutBytes
    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    $layoutPolicy = $strictUtf8.GetString($layoutBytes) | ConvertFrom-Json
    if ($null -eq $layoutPolicy -or [string]$layoutPolicy.readiness -cne 'READY') {
        throw 'Host bootstrap layout is not ready for validation'
    }

    $actualHostId = if ($PlanOnly) { [string]$layoutPolicy.hostId } else { Get-ActualHostId }
    $actualVolumeIdentity = if ($PlanOnly) {
        [string]$layoutPolicy.volumeIdentity
    }
    else {
        Get-ActualVolumeIdentity `
            -InstallRoot ([string]$layoutPolicy.installRoot) `
            -DataRoot ([string]$layoutPolicy.dataRoot)
    }

    $layoutReport = & (Join-Path $PSScriptRoot 'Resolve-LeanTpmHostLayout.ps1') `
        -LayoutPath $layoutPath `
        -ExpectedLayoutSha256 $layoutSha256 `
        -ExpectedEnvironmentId ([string]$layoutPolicy.environmentId) `
        -ExpectedHostId $actualHostId `
        -ExpectedInstallRoot ([string]$layoutPolicy.installRoot) `
        -ExpectedDataRoot ([string]$layoutPolicy.dataRoot) `
        -ExpectedVolumeIdentity $actualVolumeIdentity `
            -PlanOnly -OutputFormat Json | ConvertFrom-Json
    if (-not $PlanOnly) {
        Assert-NoReparseChain $bootstrap
        Assert-NoReparseChain $layoutPath
        Assert-ParentChainNoUntrustedMutation $bootstrap
        Assert-HostOwnedAcl $bootstrap
        Assert-HostOwnedAcl $layoutPath
        $finalLayoutPath = Get-FinalHandlePath $layoutStream.SafeFileHandle
        if (-not $finalLayoutPath.Equals(
                [IO.Path]::GetFullPath($layoutPath).TrimEnd('\'),
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw 'Host bootstrap layout handle resolves to an unexpected final path'
        }

        $installRoot = [string]$layoutReport.paths.installRoot
        $dataRoot = [string]$layoutReport.paths.dataRoot
        $installParent = [IO.Directory]::GetParent($installRoot)
        $dataParent = [IO.Directory]::GetParent($dataRoot)
        if ($null -eq $installParent -or $null -eq $dataParent -or
                -not $installParent.FullName.Equals(
                    $dataParent.FullName, [StringComparison]::OrdinalIgnoreCase
                ) -or $installParent.FullName.Equals(
                    [IO.Path]::GetPathRoot($installRoot),
                    [StringComparison]::OrdinalIgnoreCase
                )) {
            throw 'Approved production roots must be sibling directories under one protected umbrella'
        }
        $umbrellaRoot = $installParent.FullName.TrimEnd('\')
        foreach ($requiredRoot in @($umbrellaRoot, $installRoot, $dataRoot)) {
            if (-not (Test-Path -LiteralPath $requiredRoot -PathType Container)) {
                throw "Approved production root does not exist: $requiredRoot"
            }
            Assert-NoReparseChain $requiredRoot
            Assert-ParentChainNoUntrustedMutation $requiredRoot
        }
        Assert-HostOwnedAcl $umbrellaRoot
        Assert-NoUnexpectedWriteAcl $installRoot
        Assert-NoUnexpectedWriteAcl $dataRoot

        if ([string]$layoutReport.proxy.mode -ceq 'EXTERNAL_EXISTING') {
            $proxyPolicyPath = [string]$layoutReport.proxy.bindingPolicyPath
            if (-not (Test-Path -LiteralPath $proxyPolicyPath -PathType Leaf)) {
                throw 'External Caddy binding policy does not exist'
            }
            Assert-NoReparseChain $proxyPolicyPath
            $proxyPolicyStream = [IO.File]::Open(
                $proxyPolicyPath,
                [IO.FileMode]::Open,
                [IO.FileAccess]::Read,
                [IO.FileShare]::Read
            )
            try {
                $proxyPolicyMemory = New-Object IO.MemoryStream
                try {
                    $proxyPolicyStream.CopyTo($proxyPolicyMemory)
                    $proxyPolicyBytes = $proxyPolicyMemory.ToArray()
                }
                finally { $proxyPolicyMemory.Dispose() }
                $proxyPolicyVerifiedSha256 = Get-BytesSha256 $proxyPolicyBytes
                if ($proxyPolicyVerifiedSha256 -cne
                        [string]$layoutReport.proxy.bindingPolicySha256) {
                    throw 'External Caddy binding policy differs from the host layout digest'
                }
                Assert-ParentChainNoUntrustedMutation $proxyPolicyPath
                Assert-NoUnexpectedWriteAcl $proxyPolicyPath
                Assert-HostOwnedAcl $proxyPolicyPath
                $finalProxyPolicyPath = Get-FinalHandlePath $proxyPolicyStream.SafeFileHandle
                if (-not $finalProxyPolicyPath.Equals(
                        [IO.Path]::GetFullPath($proxyPolicyPath).TrimEnd('\'),
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
                    throw 'External Caddy binding policy handle resolves to an unexpected path'
                }
            }
            finally { $proxyPolicyStream.Dispose() }
        }

        $releaseTrustPath = Join-Path $dataRoot 'config\release-trust.json'
        if (-not (Test-Path -LiteralPath $releaseTrustPath -PathType Leaf)) {
            throw 'Production roots require the host-owned release trust configuration'
        }
        Assert-NoReparseChain $releaseTrustPath
        $releaseTrustStream = [IO.File]::Open(
            $releaseTrustPath,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::Read
        )
        try {
            $trustMemory = New-Object IO.MemoryStream
            try {
                $releaseTrustStream.CopyTo($trustMemory)
                $releaseTrustBytes = $trustMemory.ToArray()
            }
            finally { $trustMemory.Dispose() }
            $strictTrustUtf8 = New-Object Text.UTF8Encoding($false, $true)
            $releaseTrust = $strictTrustUtf8.GetString($releaseTrustBytes) | ConvertFrom-Json
            Assert-ParentChainNoUntrustedMutation $releaseTrustPath
            Assert-NoUnexpectedWriteAcl $releaseTrustPath
            $backendServiceAccount = [string]$releaseTrust.backendServiceAccount
            $proxyServiceAccount = [string]$releaseTrust.proxyServiceAccount
            $opsControlServiceAccount = [string]$releaseTrust.opsControlServiceAccount
            $releaseAgentServiceAccount = [string]$releaseTrust.releaseAgentServiceAccount
            $backendSid = Assert-ApprovedGmsaAccount `
                -Account $backendServiceAccount -Role 'Backend'
            $proxySid = Assert-ApprovedGmsaAccount `
                -Account $proxyServiceAccount -Role 'Proxy'
            $opsControlSid = Assert-ApprovedGmsaAccount `
                -Account $opsControlServiceAccount -Role 'OpsControl'
            $releaseAgentSid = Assert-ApprovedGmsaAccount `
                -Account $releaseAgentServiceAccount -Role 'ReleaseAgent'
            $serviceSids = @(
                $backendSid,
                $proxySid,
                $opsControlSid,
                $releaseAgentSid
            )
            if (@($serviceSids | Select-Object -Unique).Count -ne
                    $serviceSids.Count) {
                throw 'Backend, Proxy, OpsControl and ReleaseAgent identities must remain distinct'
            }
            Assert-HostOwnedAcl $releaseTrustPath -AllowedReadOnlySids @(
                $backendSid,
                $opsControlSid,
                $releaseAgentSid
            )
            $finalReleaseTrustPath = Get-FinalHandlePath $releaseTrustStream.SafeFileHandle
            if (-not $finalReleaseTrustPath.Equals(
                    [IO.Path]::GetFullPath($releaseTrustPath).TrimEnd('\'),
                    [StringComparison]::OrdinalIgnoreCase
                )) {
                throw 'Release trust handle resolves to an unexpected final path'
            }
        }
        finally { $releaseTrustStream.Dispose() }
        Assert-HostOwnedAcl $installRoot
        Assert-HostOwnedAcl $dataRoot -AllowedReadOnlySids @(
            $backendSid,
            $opsControlSid,
            $releaseAgentSid
        )

        foreach ($rootPath in @($installRoot, $dataRoot)) {
            $finalPath = Get-FinalDirectoryPath $rootPath
            if (-not $finalPath.Equals(
                    [IO.Path]::GetFullPath($rootPath).TrimEnd('\'),
                    [StringComparison]::OrdinalIgnoreCase
                )) {
                throw "Approved production root resolves to an unexpected final path: $rootPath"
            }
        }
    }
}
finally { $layoutStream.Dispose() }

$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN_ONLY' } else { 'PASS' }
    executable = -not $PlanOnly
    trustSource = if ($PlanOnly) {
        'HOST_OWNED_BOOTSTRAP_PLAN_ONLY'
    }
    else { 'HOST_OWNED_BOOTSTRAP' }
    productionBootstrapRoot = $productionBootstrapRoot
    bootstrapRoot = $bootstrap
    layoutSha256 = $layoutSha256
    proxyBindingPolicySha256 = $proxyPolicyVerifiedSha256
    serviceIdentities = if ($PlanOnly) { $null } else {
        [pscustomobject]@{
            backend = [pscustomobject]@{
                account = $backendServiceAccount
                sid = $backendSid
            }
            proxy = [pscustomobject]@{
                account = $proxyServiceAccount
                sid = $proxySid
            }
            opsControl = [pscustomobject]@{
                account = $opsControlServiceAccount
                sid = $opsControlSid
            }
            releaseAgent = [pscustomobject]@{
                account = $releaseAgentServiceAccount
                sid = $releaseAgentSid
            }
        }
    }
    hostFilesystemVerified = -not $PlanOnly
    layout = $layoutReport
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 8 -Compress }
else { $report | Format-List }
