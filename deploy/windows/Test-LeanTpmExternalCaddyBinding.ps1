[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$PolicyPath,
    [Parameter(Mandatory)][string]$ExpectedPolicySha256,
    [Parameter(Mandatory)][string]$ExpectedHostLayoutSha256,
    [ValidateSet('STANDBY_DISABLED', 'ACTIVE')]
    [string]$ExpectedFirewallState = 'STANDBY_DISABLED',
    [ValidateSet('ROOTS_ONLY', 'QUIESCED_TREE')]
    [string]$RuntimeTreeMode = 'ROOTS_ONLY',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'

if (-not ('LeanTpm.CaddyFileNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;
namespace LeanTpm {
    [StructLayout(LayoutKind.Sequential)]
    public struct ByHandleFileInformation {
        public uint FileAttributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }
    public static class CaddyFileNative {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern uint GetFinalPathNameByHandle(
            SafeFileHandle hFile, StringBuilder lpszFilePath, uint cchFilePath, uint dwFlags);
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool GetFileInformationByHandle(
            SafeFileHandle hFile, out ByHandleFileInformation fileInformation);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern SafeFileHandle CreateFile(
            string fileName, uint desiredAccess, uint shareMode, IntPtr securityAttributes,
            uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, EntryPoint = "RegQueryInfoKeyW")]
        private static extern int RegQueryInfoKey(
            IntPtr hKey, StringBuilder keyClass, ref uint classLength, IntPtr reserved,
            out uint subKeys, out uint maxSubKeyLength, out uint maxClassLength,
            out uint values, out uint maxValueNameLength, out uint maxValueLength,
            out uint securityDescriptorLength, out long lastWriteTime);
        public static DateTime GetRegistryKeyLastWriteUtc(RegistryKey key) {
            uint classLength = 0, subKeys, maxSubKeyLength, maxClassLength, values;
            uint maxValueNameLength, maxValueLength, securityDescriptorLength;
            long lastWriteTime;
            int status = RegQueryInfoKey(
                key.Handle.DangerousGetHandle(), null, ref classLength, IntPtr.Zero, out subKeys,
                out maxSubKeyLength, out maxClassLength, out values,
                out maxValueNameLength, out maxValueLength,
                out securityDescriptorLength, out lastWriteTime);
            if (status != 0) { throw new System.ComponentModel.Win32Exception(status); }
            return DateTime.FromFileTimeUtc(lastWriteTime);
        }
    }
}
'@
}

$script:administratorsSid = 'S-1-5-32-544'
$script:systemSid = 'S-1-5-18'
$script:trustedInstallerSid = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
$script:atomicWriteMask = [int64](
    [Security.AccessControl.FileSystemRights]::WriteData -bor
    [Security.AccessControl.FileSystemRights]::AppendData -bor
    [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
    [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
    [Security.AccessControl.FileSystemRights]::Delete -bor
    [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
    [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
    [Security.AccessControl.FileSystemRights]::TakeOwnership
)

function Get-PrincipalSid {
    param([Parameter(Mandatory)]$Identity)
    try {
        if ($Identity -is [Security.Principal.SecurityIdentifier]) { return $Identity.Value }
        return $Identity.Translate([Security.Principal.SecurityIdentifier]).Value
    }
    catch {
        return (New-Object Security.Principal.NTAccount ([string]$Identity)).
            Translate([Security.Principal.SecurityIdentifier]).Value
    }
}

function Get-ProtectedServiceEnvironment {
    $registryPath = 'SYSTEM\CurrentControlSet\Services\caddy'
    $providerPath = 'Registry::HKEY_LOCAL_MACHINE\' + $registryPath
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($registryPath, $false)
    if ($null -eq $key) { throw 'caddy service registry key is missing' }
    try {
        $acl = Get-Acl -LiteralPath $providerPath -ErrorAction Stop
        $ownerSid = Get-PrincipalSid $acl.Owner
        $allowed = @(
            $script:administratorsSid, $script:systemSid, $script:trustedInstallerSid
        )
        if (-not $acl.AreAccessRulesProtected -or $ownerSid -notin $allowed) {
            throw 'caddy service registry key ACL is inherited or has an untrusted owner'
        }
        $requiredFound = @{}
        foreach ($rule in @($acl.Access)) {
            $sid = Get-PrincipalSid $rule.IdentityReference
            if ($sid -notin $allowed -or
                    $rule.AccessControlType -ne
                        [Security.AccessControl.AccessControlType]::Allow -or
                    $rule.InheritanceFlags -ne
                        [Security.AccessControl.InheritanceFlags]::None -or
                    $rule.PropagationFlags -ne
                        [Security.AccessControl.PropagationFlags]::None -or
                    [int64]$rule.RegistryRights -ne
                        [int64][Security.AccessControl.RegistryRights]::FullControl -or
                    $requiredFound.ContainsKey($sid)) {
                throw 'caddy service registry key ACL is not exact and host-owned'
            }
            $requiredFound[$sid] = $true
        }
        foreach ($requiredSid in @($script:administratorsSid, $script:systemSid)) {
            if (-not $requiredFound.ContainsKey($requiredSid)) {
                throw 'caddy service registry key lacks a required administrator ACE'
            }
        }
        $environment = $key.GetValue(
            'Environment', $null,
            [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames
        )
        if ($environment -isnot [string[]]) {
            throw 'caddy service Environment must be an exact REG_MULTI_SZ value'
        }
        return [pscustomobject]@{
            entries = [string[]]$environment
            lastWriteUtc = [LeanTpm.CaddyFileNative]::GetRegistryKeyLastWriteUtc($key)
        }
    }
    finally { $key.Dispose() }
}

function Convert-CimDateTimeToUtc {
    param([Parameter(Mandatory)]$Value)
    if ($Value -is [DateTime]) { return ([DateTime]$Value).ToUniversalTime() }
    try {
        return [Management.ManagementDateTimeConverter]::ToDateTime([string]$Value).
            ToUniversalTime()
    }
    catch { throw 'caddy process creation time is invalid' }
}

function Get-FinalHandlePath {
    param([Parameter(Mandatory)][IO.FileStream]$Stream)
    $buffer = New-Object Text.StringBuilder 32768
    $length = [LeanTpm.CaddyFileNative]::GetFinalPathNameByHandle(
        $Stream.SafeFileHandle, $buffer, [uint32]$buffer.Capacity, 0
    )
    if ($length -eq 0 -or $length -ge $buffer.Capacity) {
        throw 'GetFinalPathNameByHandle failed for a Caddy trust-bound file'
    }
    $path = $buffer.ToString()
    if ($path.StartsWith('\\?\UNC\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Caddy trust-bound files must not resolve to UNC storage'
    }
    if ($path.StartsWith('\\?\', [StringComparison]::OrdinalIgnoreCase)) {
        $path = $path.Substring(4)
    }
    return [IO.Path]::GetFullPath($path).TrimEnd('\')
}

function Get-FinalDirectoryHandlePath {
    param(
        [Parameter(Mandatory)][Microsoft.Win32.SafeHandles.SafeFileHandle]$Handle,
        [Parameter(Mandatory)][string]$Label
    )
    $buffer = New-Object Text.StringBuilder 32768
    $length = [LeanTpm.CaddyFileNative]::GetFinalPathNameByHandle(
        $Handle, $buffer, [uint32]$buffer.Capacity, 0
    )
    if ($length -eq 0 -or $length -ge $buffer.Capacity) {
        throw "$Label directory final path could not be resolved"
    }
    $finalPath = $buffer.ToString()
    if ($finalPath.StartsWith('\\?\UNC\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label directory must not resolve to UNC storage"
    }
    if ($finalPath.StartsWith('\\?\', [StringComparison]::OrdinalIgnoreCase)) {
        $finalPath = $finalPath.Substring(4)
    }
    return [IO.Path]::GetFullPath($finalPath).TrimEnd('\')
}

function Open-DirectoryIdentity {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Label)
    $readAttributes = [uint32]0x00000080
    $shareReadWrite = [uint32]0x00000003
    $openExisting = [uint32]3
    $backupSemanticsAndOpenReparse = [uint32]0x02200000
    $handle = [LeanTpm.CaddyFileNative]::CreateFile(
        $Path, $readAttributes, $shareReadWrite, [IntPtr]::Zero, $openExisting,
        $backupSemanticsAndOpenReparse, [IntPtr]::Zero
    )
    if ($handle.IsInvalid) {
        $handle.Dispose()
        throw "$Label directory identity handle could not be opened"
    }
    try {
        $finalPath = Get-FinalDirectoryHandlePath $handle $Label
        $fileInformation = New-Object LeanTpm.ByHandleFileInformation
        if (-not [LeanTpm.CaddyFileNative]::GetFileInformationByHandle(
                $handle, [ref]$fileInformation
            )) { throw "$Label directory identity metadata could not be read" }
        $attributes = [IO.FileAttributes][uint32]$fileInformation.FileAttributes
        if (($attributes -band [IO.FileAttributes]::Directory) -eq 0 -or
                ($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label directory identity is not a regular non-reparse directory"
        }
        return [pscustomobject]@{
            handle = $handle
            finalPath = $finalPath
            fileAttributes = $attributes
            volumeSerialNumber = [uint32]$fileInformation.VolumeSerialNumber
            fileIndexHigh = [uint32]$fileInformation.FileIndexHigh
            fileIndexLow = [uint32]$fileInformation.FileIndexLow
        }
    }
    catch {
        $handle.Dispose()
        throw
    }
}

function Assert-DirectoryIdentityStable {
    param(
        [Parameter(Mandatory)]$Identity,
        [Parameter(Mandatory)][string]$ExpectedPath,
        [Parameter(Mandatory)][string]$Label
    )
    $currentFinalPath = Get-FinalDirectoryHandlePath $Identity.handle $Label
    if (-not $currentFinalPath.Equals(
            $ExpectedPath, [StringComparison]::OrdinalIgnoreCase
        )) { throw "$Label directory was renamed during runtime-tree validation" }
    $reopened = Open-DirectoryIdentity $ExpectedPath $Label
    try {
        if ($reopened.volumeSerialNumber -ne $Identity.volumeSerialNumber -or
                $reopened.fileIndexHigh -ne $Identity.fileIndexHigh -or
                $reopened.fileIndexLow -ne $Identity.fileIndexLow) {
            throw "$Label directory path was rebound during runtime-tree validation"
        }
    }
    finally { $reopened.handle.Dispose() }
}

function Assert-RuntimeChildAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ProxySid,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][bool]$IsDirectory
    )
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    $ownerSid = Get-PrincipalSid $acl.Owner
    $allowed = @($script:administratorsSid, $script:systemSid, $ProxySid)
    if ($ownerSid -notin $allowed) { throw "$Label has an untrusted owner" }
    $requiredFound = @{}
    $expectedInheritance = if ($IsDirectory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit
    }
    else { [Security.AccessControl.InheritanceFlags]::None }
    foreach ($rule in @($acl.Access)) {
        $sid = Get-PrincipalSid $rule.IdentityReference
        if ($sid -notin $allowed -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.InheritanceFlags -ne $expectedInheritance -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw "$Label has an unexpected or propagation-only access rule"
        }
        $expectedRights = if ($sid -eq $ProxySid) {
            [int64][Security.AccessControl.FileSystemRights]::Modify
        }
        else { [int64][Security.AccessControl.FileSystemRights]::FullControl }
        if ([int64]$rule.FileSystemRights -ne $expectedRights -or
                $requiredFound.ContainsKey($sid)) {
            throw "$Label must expose one exact effective ACE per approved principal"
        }
        $requiredFound[$sid] = $true
    }
    foreach ($requiredSid in $allowed) {
        if (-not $requiredFound.ContainsKey($requiredSid)) {
            throw "$Label is missing an exact effective access rule"
        }
    }
}

function Assert-ProtectedRuntimeTree {
    param(
        [Parameter(Mandatory)][string]$RootPath,
        [Parameter(Mandatory)][string]$ProxySid,
        [Parameter(Mandatory)][string]$Label
    )
    $root = Assert-ProtectedRuntimeDirectory $RootPath $ProxySid $Label
    $inspectionState = [pscustomobject]@{ count = 0 }
    function Inspect-RuntimeDirectory {
        param(
            [Parameter(Mandatory)][string]$CurrentPath,
            [Parameter(Mandatory)][bool]$IsRoot,
            [Parameter(Mandatory)][int]$Depth
        )
        if ($Depth -gt 64) { throw "$Label exceeds the bounded directory depth" }
        $identity = Open-DirectoryIdentity $CurrentPath $Label
        try {
            if (-not $identity.finalPath.Equals(
                    $CurrentPath, [StringComparison]::OrdinalIgnoreCase
                )) { throw "$Label directory final path drifted" }
            if ($IsRoot) {
                $revalidatedRoot = Assert-ProtectedRuntimeDirectory `
                    $CurrentPath $ProxySid $Label
                if (-not $revalidatedRoot.Equals(
                        $CurrentPath, [StringComparison]::OrdinalIgnoreCase
                    )) { throw "$Label root identity drifted during validation" }
            }
            else {
                Assert-RuntimeChildAcl $CurrentPath $ProxySid $Label -IsDirectory:$true
            }
            Assert-DirectoryIdentityStable $identity $CurrentPath $Label
            $children = @(Get-ChildItem -LiteralPath $CurrentPath -Force -ErrorAction Stop)
            Assert-DirectoryIdentityStable $identity $CurrentPath $Label
            foreach ($child in $children) {
                $inspectionState.count++
                if ($inspectionState.count -gt 10000) {
                    throw "$Label exceeds the bounded inspection limit"
                }
                $childPath = [IO.Path]::GetFullPath($child.FullName).TrimEnd('\')
                if (-not $childPath.StartsWith(
                        $root + '\', [StringComparison]::OrdinalIgnoreCase
                )) { throw "$Label child escaped its approved runtime root" }
                if ($child.PSIsContainer) {
                    Inspect-RuntimeDirectory $childPath $false ($Depth + 1)
                    Assert-DirectoryIdentityStable $identity $CurrentPath $Label
                    continue
                }
                if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "$Label contains a reparse-point child"
                }
                $stream = [IO.File]::Open(
                    $childPath, [IO.FileMode]::Open, [IO.FileAccess]::Read,
                    [IO.FileShare]::ReadWrite
                )
                try {
                    if (-not (Get-FinalHandlePath $stream).Equals(
                            $childPath, [StringComparison]::OrdinalIgnoreCase
                        )) { throw "$Label child file final path drifted" }
                    $fileInformation = New-Object LeanTpm.ByHandleFileInformation
                    if (-not [LeanTpm.CaddyFileNative]::GetFileInformationByHandle(
                            $stream.SafeFileHandle, [ref]$fileInformation
                        ) -or $fileInformation.NumberOfLinks -ne 1) {
                        throw "$Label child file is hard-linked or has unreadable identity metadata"
                    }
                    $fileAttributes = [IO.FileAttributes][uint32]$fileInformation.FileAttributes
                    if (($fileAttributes -band [IO.FileAttributes]::Directory) -ne 0 -or
                            ($fileAttributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                        throw "$Label child file identity is a directory or reparse point"
                    }
                    $streams = @(Get-Item -LiteralPath $childPath -Stream * -ErrorAction Stop)
                    if ($streams.Count -ne 1 -or
                            [string]$streams[0].Stream -notin @(':$DATA', '::$DATA')) {
                        throw "$Label child file contains an alternate data stream"
                    }
                    Assert-RuntimeChildAcl $childPath $ProxySid $Label -IsDirectory:$false
                }
                finally { $stream.Dispose() }
                Assert-DirectoryIdentityStable $identity $CurrentPath $Label
            }
            Assert-DirectoryIdentityStable $identity $CurrentPath $Label
        }
        finally { $identity.handle.Dispose() }
    }
    Inspect-RuntimeDirectory $root $true 0
    return $root
}

function Assert-TrustedParentChain {
    param([Parameter(Mandatory)][string]$LeafPath, [Parameter(Mandatory)][string]$Label)
    $trustedWriters = @(
        $script:administratorsSid, $script:systemSid, $script:trustedInstallerSid
    )
    $current = [IO.Directory]::GetParent($LeafPath)
    while ($null -ne $current) {
        $item = Get-Item -LiteralPath $current.FullName -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label has a reparse-point ancestor: $($current.FullName)"
        }
        $acl = Get-Acl -LiteralPath $current.FullName -ErrorAction Stop
        $ownerSid = Get-PrincipalSid $acl.Owner
        if ($ownerSid -notin $trustedWriters) {
            throw "$Label has an untrusted ancestor owner: $($current.FullName)"
        }
        foreach ($rule in @($acl.Access)) {
            if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
                continue
            }
            $sid = Get-PrincipalSid $rule.IdentityReference
            if ($sid -notin $trustedWriters -and
                    (([int64]$rule.FileSystemRights -band $script:atomicWriteMask) -ne 0)) {
                throw "$Label has an ancestor writable by an untrusted principal: $($current.FullName)"
            }
        }
        $current = $current.Parent
    }
}

function Assert-ProtectedFileAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ProxySid,
        [Parameter(Mandatory)][string]$Label
    )
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    $ownerSid = Get-PrincipalSid $acl.Owner
    $allowed = @(
        $script:administratorsSid, $script:systemSid, $script:trustedInstallerSid, $ProxySid
    )
    if (-not $acl.AreAccessRulesProtected -or
            $ownerSid -notin @($script:administratorsSid, $script:systemSid, $script:trustedInstallerSid)) {
        throw "$Label ACL is inherited or has an untrusted owner"
    }
    $requiredFound = @{}
    foreach ($rule in @($acl.Access)) {
        $sid = Get-PrincipalSid $rule.IdentityReference
        if ($sid -notin $allowed -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw "$Label ACL contains an unexpected or non-applicable access rule"
        }
        $expectedRights = if ($sid -eq $ProxySid) {
            [int64][Security.AccessControl.FileSystemRights]::ReadAndExecute
        }
        else { [int64][Security.AccessControl.FileSystemRights]::FullControl }
        if ([int64]$rule.FileSystemRights -ne $expectedRights -or
                $requiredFound.ContainsKey($sid)) {
            throw "$Label ACL must use one exact applicable ACE per approved principal"
        }
        $requiredFound[$sid] = $true
    }
    foreach ($requiredSid in @($script:administratorsSid, $script:systemSid, $ProxySid)) {
        if (-not $requiredFound.ContainsKey($requiredSid)) {
            throw "$Label ACL is missing a required exact access rule"
        }
    }
}

function Assert-ProtectedRuntimeDirectory {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ProxySid,
        [Parameter(Mandatory)][string]$Label
    )
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path.TrimEnd('\')
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if (-not $item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label must be a regular non-reparse directory"
    }
    Assert-TrustedParentChain $resolved $Label
    $acl = Get-Acl -LiteralPath $resolved -ErrorAction Stop
    $ownerSid = Get-PrincipalSid $acl.Owner
    $allowed = @($script:administratorsSid, $script:systemSid, $ProxySid)
    if (-not $acl.AreAccessRulesProtected -or
            $ownerSid -notin @($script:administratorsSid, $script:systemSid)) {
        throw "$Label ACL is inherited or has an untrusted owner"
    }
    $requiredFound = @{}
    $requiredInheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($rule in @($acl.Access)) {
        $sid = Get-PrincipalSid $rule.IdentityReference
        if ($sid -notin $allowed -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.InheritanceFlags -ne $requiredInheritance -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw "$Label ACL contains an unexpected or non-inheriting access rule"
        }
        $expectedRights = if ($sid -eq $ProxySid) {
            [int64][Security.AccessControl.FileSystemRights]::Modify
        }
        else { [int64][Security.AccessControl.FileSystemRights]::FullControl }
        if ([int64]$rule.FileSystemRights -ne $expectedRights -or
                $requiredFound.ContainsKey($sid)) {
            throw "$Label ACL must use one exact inheritable ACE per approved principal"
        }
        $requiredFound[$sid] = $true
    }
    foreach ($requiredSid in @($script:administratorsSid, $script:systemSid, $ProxySid)) {
        if (-not $requiredFound.ContainsKey($requiredSid)) {
            throw "$Label ACL is missing a required exact inheritable access rule"
        }
    }
    return $resolved
}

function Get-BytesSha256 {
    param([byte[]]$Bytes)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($hasher.ComputeHash($Bytes)).
            Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
}

function Get-TextSha256 {
    param([string]$Text)
    return Get-BytesSha256 ([Text.Encoding]::UTF8.GetBytes($Text))
}

function Get-ExecutableFromServiceCommandLine {
    param([string]$CommandLine)
    $trimmed = $CommandLine.Trim()
    if ($trimmed.StartsWith('"')) {
        $match = [regex]::Match($trimmed, '^"(?<path>[^"]+)"(?:\s|$)')
    }
    else { $match = [regex]::Match($trimmed, '^(?<path>\S+)(?:\s|$)') }
    if (-not $match.Success) { throw 'Caddy SCM command line has no unambiguous executable path' }
    return [IO.Path]::GetFullPath($match.Groups['path'].Value).TrimEnd('\')
}

function Assert-ExternalCaddyQuiescedState {
    param(
        [Parameter(Mandatory)][string]$ServiceId,
        [Parameter(Mandatory)][string]$ServiceImagePath,
        [Parameter(Mandatory)][string]$ProxySid
    )
    $services = @(Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='$ServiceId'" -ErrorAction Stop)
    if ($services.Count -ne 1 -or [string]$services[0].State -cne 'Stopped' -or
            [uint32]$services[0].ProcessId -ne 0) {
        throw 'QUIESCED_TREE validation requires caddy SCM Stopped with PID 0'
    }
    $allProcesses = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    $runningImageProcesses = @($allProcesses | Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_.ExecutablePath) -and
            ([IO.Path]::GetFullPath([string]$_.ExecutablePath).TrimEnd('\')).Equals(
                $ServiceImagePath, [StringComparison]::OrdinalIgnoreCase
            )
        })
    $runningProxyIdentityProcesses = @()
    foreach ($process in $allProcesses) {
        try {
            $owner = Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid `
                -ErrorAction Stop
        }
        catch {
            $owner = $null
        }
        if ($null -eq $owner -or [uint32]$owner.ReturnValue -ne 0) {
            $stillPresent = @(Get-CimInstance -ClassName Win32_Process `
                    -Filter ("ProcessId={0}" -f [uint32]$process.ProcessId) `
                    -ErrorAction Stop)
            if ($stillPresent.Count -ne 0) {
                throw ('QUIESCED_TREE could not determine the owner of live process PID {0}' -f
                    [uint32]$process.ProcessId)
            }
            continue
        }
        if ([string]$owner.Sid -ceq $ProxySid) {
            $runningProxyIdentityProcesses += $process
        }
    }
    $publicListeners = @(Get-NetTCPConnection -State Listen -LocalPort 80, 443 `
            -ErrorAction Stop)
    if ($runningImageProcesses.Count -ne 0 -or
            $runningProxyIdentityProcesses.Count -ne 0 -or
            $publicListeners.Count -ne 0) {
        throw ('QUIESCED_TREE validation requires no Caddy image process, proxy-identity ' +
            'process or public listener')
    }
}

function Open-LockedFileSnapshot {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$ProxySid
    )
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force -ErrorAction Stop
    if (-not $item.PSIsContainer -and
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
        $stream = [IO.File]::Open(
            $resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read
        )
        try {
            $finalPath = Get-FinalHandlePath $stream
            if (-not $finalPath.Equals($resolved, [StringComparison]::OrdinalIgnoreCase)) {
                throw "$Label final handle path differs from the approved path"
            }
            Assert-TrustedParentChain $resolved $Label
            Assert-ProtectedFileAcl $resolved $ProxySid $Label
            $memory = New-Object IO.MemoryStream
            try { $stream.CopyTo($memory); $fileBytes = $memory.ToArray() }
            finally { $memory.Dispose() }
            $sha256 = Get-BytesSha256 $fileBytes
            $stream.Position = 0
            return [pscustomobject]@{
                path = $resolved; finalPath = $finalPath; sha256 = $sha256
                lastWriteUtc = $item.LastWriteTimeUtc
                bytes = $fileBytes; stream = $stream
            }
        }
        catch { $stream.Dispose(); throw }
    }
    throw "$Label must be a regular non-reparse file"
}

$install = (Resolve-Path -LiteralPath $InstallRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot -ErrorAction Stop).Path.TrimEnd('\', '/')
$bootstrap = & (Join-Path $PSScriptRoot 'Test-LeanTpmHostBootstrap.ps1') `
    -OutputFormat Json | ConvertFrom-Json
if ([string]$bootstrap.status -cne 'PASS' -or -not [bool]$bootstrap.executable -or
        [string]$bootstrap.layoutSha256 -cne $ExpectedHostLayoutSha256 -or
        -not $install.Equals(
            [string]$bootstrap.layout.paths.installRoot,
            [StringComparison]::OrdinalIgnoreCase
        ) -or -not $data.Equals(
            [string]$bootstrap.layout.paths.dataRoot,
            [StringComparison]::OrdinalIgnoreCase
        ) -or [string]$bootstrap.layout.proxy.mode -cne 'EXTERNAL_EXISTING' -or
        [string]$bootstrap.layout.proxy.serviceId -cne 'caddy' -or
        [string]$bootstrap.layout.proxy.bindingPolicySha256 -cne $ExpectedPolicySha256) {
    throw 'External Caddy verification is not bound to the executable host layout'
}
$resolvedPolicy = (Resolve-Path -LiteralPath $PolicyPath -ErrorAction Stop).Path
if (-not $resolvedPolicy.Equals(
        [string]$bootstrap.layout.proxy.bindingPolicyPath,
        [StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'External Caddy policy path differs from the host bootstrap layout'
}

$policyStream = [IO.File]::Open(
    $resolvedPolicy,
    [IO.FileMode]::Open,
    [IO.FileAccess]::Read,
    [IO.FileShare]::Read
)
$policyLastWriteUtc = (Get-Item -LiteralPath $resolvedPolicy -Force -ErrorAction Stop).
    LastWriteTimeUtc
$serviceImageSnapshot = $null
$configSnapshot = $null
$observationPath = Join-Path ([IO.Path]::GetTempPath()) (
    'leantpm-external-caddy-observation-{0}.json' -f [Guid]::NewGuid().ToString('N')
)
try {
    $policyMemory = New-Object IO.MemoryStream
    try { $policyStream.CopyTo($policyMemory); $policyBytes = $policyMemory.ToArray() }
    finally { $policyMemory.Dispose() }
    $policySha256 = Get-BytesSha256 $policyBytes
    if ($policySha256 -cne $ExpectedPolicySha256) {
        throw 'External Caddy policy changed after host bootstrap verification'
    }
    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    $policy = $strictUtf8.GetString($policyBytes) | ConvertFrom-Json
    $policyContract = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyContract.ps1') `
        -PolicyPath $resolvedPolicy -ExpectedPolicySha256 $ExpectedPolicySha256 `
        -ExpectedInstallRoot $install -ExpectedDataRoot $data -PolicyOnly `
        -OutputFormat Json | ConvertFrom-Json
    if ([string]$policyContract.status -cne 'PASS' -or
            -not [bool]$policyContract.policyOnly) {
        throw 'External Caddy policy failed strict validation before host inspection'
    }
    if ([string]$policy.serviceAccount -cne
            [string]$bootstrap.serviceIdentities.proxy.account -or
            [string]$policy.serviceAccountSid -cne
                [string]$bootstrap.serviceIdentities.proxy.sid) {
        throw 'External Caddy policy identity differs from the host release trust identity'
    }

    $service = @(Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='caddy'" -ErrorAction Stop)
    if ($service.Count -ne 1) { throw 'Exactly one caddy SCM service must exist' }
    $managedProxy = @(Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='LeanTPM.Proxy'" -ErrorAction Stop)
    if ($managedProxy.Count -ne 0) { throw 'LeanTPM.Proxy must not coexist with adopted caddy' }
    $service = $service[0]
    $serviceCommandLine = ([string]$service.PathName).Trim()
    $serviceImagePath = Get-ExecutableFromServiceCommandLine $serviceCommandLine
    $expectedImagePath = [IO.Path]::GetFullPath([string]$policy.serviceImagePath).TrimEnd('\')
    if (-not $serviceImagePath.Equals($expectedImagePath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'caddy SCM executable path differs from the host policy'
    }
    $proxySid = [string]$policy.serviceAccountSid
    $serviceImageSnapshot = Open-LockedFileSnapshot $serviceImagePath `
        'Caddy executable' $proxySid
    $serviceImageSha256 = [string]$serviceImageSnapshot.sha256
    $configPath = (Resolve-Path -LiteralPath ([string]$policy.configPath) -ErrorAction Stop).Path
    $configSnapshot = Open-LockedFileSnapshot $configPath 'Caddy configuration' $proxySid
    $configSha256 = [string]$configSnapshot.sha256
    $configBytes = [byte[]]$configSnapshot.bytes
    $configText = $strictUtf8.GetString($configBytes)
    $servicePid = [int]$service.ProcessId
    if ($RuntimeTreeMode -eq 'QUIESCED_TREE') {
        Assert-ExternalCaddyQuiescedState 'caddy' $serviceImagePath $proxySid
        $tlsDataRoot = Assert-ProtectedRuntimeTree `
            ([string]$policy.tlsDataRoot) $proxySid 'Caddy TLS data root'
        $logRoot = Assert-ProtectedRuntimeTree `
            ([string]$policy.logRoot) $proxySid 'Caddy log root'
        $xdgConfigRoot = Assert-ProtectedRuntimeTree `
            (Join-Path $data 'proxy\config') $proxySid 'Caddy XDG config root'
        Assert-ExternalCaddyQuiescedState 'caddy' $serviceImagePath $proxySid
        $quiescedScanCompletedAtUtc = [DateTime]::UtcNow
    }
    else {
        $tlsDataRoot = Assert-ProtectedRuntimeDirectory `
            ([string]$policy.tlsDataRoot) $proxySid 'Caddy TLS data root'
        $logRoot = Assert-ProtectedRuntimeDirectory `
            ([string]$policy.logRoot) $proxySid 'Caddy log root'
        $xdgConfigRoot = Assert-ProtectedRuntimeDirectory `
            (Join-Path $data 'proxy\config') $proxySid 'Caddy XDG config root'
    }
    $expectedServiceEnvironment = @(
        'XDG_CONFIG_HOME={0}' -f $xdgConfigRoot
        'XDG_DATA_HOME={0}' -f $tlsDataRoot
    )
    $serviceRegistry = Get-ProtectedServiceEnvironment
    $serviceEnvironment = @([string[]]$serviceRegistry.entries | Sort-Object)
    if ($serviceEnvironment.Count -ne $expectedServiceEnvironment.Count) {
        throw 'caddy service must define only the approved XDG runtime environment'
    }
    for ($environmentIndex = 0; $environmentIndex -lt $expectedServiceEnvironment.Count;
            $environmentIndex++) {
        if (-not [string]$serviceEnvironment[$environmentIndex].Equals(
                [string]$expectedServiceEnvironment[$environmentIndex],
                [StringComparison]::OrdinalIgnoreCase
            )) {
            throw 'caddy service XDG runtime environment differs from the host policy'
        }
    }
    $serviceEnvironmentCanonical = $serviceEnvironment -join "`n"
    $serviceEnvironmentSha256 = Get-TextSha256 $serviceEnvironmentCanonical
    if ($serviceEnvironmentSha256 -cne [string]$policy.serviceEnvironmentSha256) {
        throw 'caddy service environment digest differs from the host policy'
    }
    $expectedServiceCommandLine = '"{0}" run --config "{1}" --adapter caddyfile' -f `
        $serviceImagePath, $configPath
    if (-not $serviceCommandLine.Equals(
            $expectedServiceCommandLine, [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'caddy SCM must run the exact approved config with no extra arguments'
    }
    $caddyTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Caddyfile.template') `
        -Encoding utf8 -Raw
    $expectedCaddyfile = $caddyTemplate.Replace('@SITE_HOST@', [string]$policy.publicHost).
        Replace('@INSTALL_ROOT@', $install).
        Replace('@LOG_ROOT@', $logRoot)
    if ($configText -cne $expectedCaddyfile) {
        throw 'External Caddy configuration must exactly match the dedicated LeanTPM template'
    }
    $serviceAccount = [string]$service.StartName
    $serviceAccountSid = (New-Object Security.Principal.NTAccount $serviceAccount).
        Translate([Security.Principal.SecurityIdentifier]).Value
    if ($RuntimeTreeMode -eq 'ROOTS_ONLY' -and
            ([string]$service.State -cne 'Running' -or $servicePid -le 0)) {
        throw 'caddy must be running for exact listener ownership verification'
    }

    $scmSddlOutput = @(& sc.exe sdshow caddy 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'sc.exe sdshow failed for adopted caddy' }
    $scmSddl = (($scmSddlOutput | ForEach-Object { [string]$_ }) -join "`n").Trim()

    $processTree = New-Object 'System.Collections.Generic.HashSet[int]'
    $processStartedAtUtc = $null
    $listeners = @()
    if ($RuntimeTreeMode -eq 'ROOTS_ONLY') {
        $allProcesses = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
        $null = $processTree.Add($servicePid)
        do {
            $added = $false
            foreach ($process in $allProcesses) {
                if ([int]$process.ParentProcessId -in $processTree -and
                        $processTree.Add([int]$process.ProcessId)) { $added = $true }
            }
        } while ($added)
        $rootProcess = @($allProcesses | Where-Object { [int]$_.ProcessId -eq $servicePid })
        if ($rootProcess.Count -ne 1 -or
                -not ([IO.Path]::GetFullPath(
                        [string]$rootProcess[0].ExecutablePath
                    ).TrimEnd('\')).Equals(
                    $serviceImagePath, [StringComparison]::OrdinalIgnoreCase
                ) -or
                -not ([string]$rootProcess[0].CommandLine).Trim().Equals(
                    $serviceCommandLine, [StringComparison]::OrdinalIgnoreCase
                )) {
            throw 'caddy SCM PID does not resolve to the pinned executable'
        }
        $processStartedAtUtc = Convert-CimDateTimeToUtc $rootProcess[0].CreationDate
        $trustWrites = @(
            [DateTime]$policyLastWriteUtc,
            [DateTime]$serviceImageSnapshot.lastWriteUtc,
            [DateTime]$configSnapshot.lastWriteUtc,
            [DateTime]$serviceRegistry.lastWriteUtc
        )
        if (@($trustWrites | Where-Object { $processStartedAtUtc -le $_ }).Count -gt 0) {
            throw 'caddy current PID predates approved policy, binary, config or service environment'
        }

        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 80, 443 `
                -ErrorAction Stop | ForEach-Object {
                [ordered]@{
                    localAddress = [string]$_.LocalAddress
                    port = [int]$_.LocalPort
                    owningPid = [int]$_.OwningProcess
                }
            })
    }

    $adapted = @(& $serviceImagePath adapt --config $configPath --adapter caddyfile 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Pinned caddy could not adapt the approved configuration' }
    $adaptedJson = (($adapted | ForEach-Object { [string]$_ }) -join "`n") |
        ConvertFrom-Json
    if ($null -eq $adaptedJson.admin -or -not [bool]$adaptedJson.admin.disabled) {
        throw 'Caddy admin endpoint must be disabled in the adapted configuration'
    }

    $firewallRules = @(Get-NetFirewallRule -DisplayGroup ([string]$policy.firewallRuleGroup) `
            -ErrorAction Stop)
    if ($firewallRules.Count -ne 2) {
        throw 'Exactly two pre-created public isolation firewall rules are required'
    }
    $firewallEvidence = @()
    foreach ($rule in $firewallRules) {
        $portFilter = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop
        $addressFilter = Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $rule `
            -ErrorAction Stop
        $applicationFilter = Get-NetFirewallApplicationFilter `
            -AssociatedNetFirewallRule $rule -ErrorAction Stop
        $serviceFilter = Get-NetFirewallServiceFilter -AssociatedNetFirewallRule $rule `
            -ErrorAction Stop
        $interfaceFilter = Get-NetFirewallInterfaceFilter -AssociatedNetFirewallRule $rule `
            -ErrorAction Stop
        $securityFilter = Get-NetFirewallSecurityFilter -AssociatedNetFirewallRule $rule `
            -ErrorAction Stop
        $firewallEvidence += [ordered]@{
            name = [string]$rule.Name
            enabled = [string]$rule.Enabled
            direction = [string]$rule.Direction
            action = [string]$rule.Action
            profile = [string]$rule.Profile
            interfaceType = [string]$rule.InterfaceType
            edgeTraversalPolicy = [string]$rule.EdgeTraversalPolicy
            platform = [string]$rule.Platform
            localOnlyMapping = [string]$rule.LocalOnlyMapping
            looseSourceMapping = [string]$rule.LooseSourceMapping
            dynamicTarget = [string]$rule.DynamicTarget
            remoteDynamicKeywordAddresses = [string]$rule.RemoteDynamicKeywordAddresses
            policyAppId = [string]$rule.PolicyAppId
            protocol = [string]$portFilter.Protocol
            localPort = [string]$portFilter.LocalPort
            remotePort = [string]$portFilter.RemotePort
            localAddress = [string]$addressFilter.LocalAddress
            remoteAddress = [string]$addressFilter.RemoteAddress
            program = [string]$applicationFilter.Program
            package = [string]$applicationFilter.Package
            service = [string]$serviceFilter.Service
            interfaceAlias = [string]$interfaceFilter.InterfaceAlias
            authentication = [string]$securityFilter.Authentication
            encryption = [string]$securityFilter.Encryption
            overrideBlockRules = [string]$securityFilter.OverrideBlockRules
            localUser = [string]$securityFilter.LocalUser
            remoteUser = [string]$securityFilter.RemoteUser
            remoteMachine = [string]$securityFilter.RemoteMachine
        }
    }
    $firewallStaticEvidence = @($firewallEvidence | Sort-Object name | ForEach-Object {
            [ordered]@{
                name = $_.name; direction = $_.direction; action = $_.action
                profile = $_.profile; interfaceType = $_.interfaceType
                edgeTraversalPolicy = $_.edgeTraversalPolicy; protocol = $_.protocol
                platform = $_.platform; localOnlyMapping = $_.localOnlyMapping
                looseSourceMapping = $_.looseSourceMapping; dynamicTarget = $_.dynamicTarget
                remoteDynamicKeywordAddresses = $_.remoteDynamicKeywordAddresses
                policyAppId = $_.policyAppId
                localPort = $_.localPort; remotePort = $_.remotePort
                localAddress = $_.localAddress; remoteAddress = $_.remoteAddress
                program = $_.program; package = $_.package; service = $_.service
                interfaceAlias = $_.interfaceAlias
                authentication = $_.authentication; encryption = $_.encryption
                overrideBlockRules = $_.overrideBlockRules; localUser = $_.localUser
                remoteUser = $_.remoteUser; remoteMachine = $_.remoteMachine
            }
        })
    $firewallCanonical = $firewallStaticEvidence | ConvertTo-Json -Compress
    $firewallPolicySha256 = Get-TextSha256 $firewallCanonical
    $expectedFirewallEnabled = if ($ExpectedFirewallState -eq 'ACTIVE') {
        @('True', '1')
    }
    else { @('False', '0') }
    $unsafeFirewallRules = @($firewallEvidence | Where-Object {
            $_.enabled -notin $expectedFirewallEnabled -or $_.direction -ne 'Inbound' -or
            $_.action -ne 'Block' -or $_.profile -ne 'Any' -or
            $_.interfaceType -ne 'Any' -or $_.edgeTraversalPolicy -ne 'Block' -or
            $_.platform -notin @('', 'Any') -or
            $_.localOnlyMapping -notin @('', 'False', '0') -or
            $_.looseSourceMapping -notin @('', 'False', '0') -or
            $_.dynamicTarget -notin @('', 'Any') -or
            $_.remoteDynamicKeywordAddresses -notin @('', 'Any') -or
            $_.policyAppId -notin @('', 'Any') -or
            $_.protocol -notin @('TCP', '6') -or $_.remotePort -ne 'Any' -or
            $_.localAddress -ne 'Any' -or $_.remoteAddress -ne 'Any' -or
            $_.program -ne 'Any' -or $_.package -ne 'Any' -or
            $_.service -ne 'Any' -or $_.interfaceAlias -ne 'Any'
            -or $_.authentication -notin @('', 'None', 'NotRequired')
            -or $_.encryption -notin @('', 'None', 'NotRequired')
            -or $_.overrideBlockRules -notin @('', 'False', '0')
            -or $_.localUser -notin @('', 'Any') -or $_.remoteUser -notin @('', 'Any')
            -or $_.remoteMachine -notin @('', 'Any')
        })
    $blockedPorts = @($firewallEvidence | ForEach-Object { [string]$_.localPort })
    $firewallReady = $firewallPolicySha256 -ceq [string]$policy.firewallPolicySha256 -and
        $unsafeFirewallRules.Count -eq 0 -and
        @($blockedPorts | Where-Object { $_ -eq '80' }).Count -eq 1 -and
        @($blockedPorts | Where-Object { $_ -eq '443' }).Count -eq 1

    if ($RuntimeTreeMode -eq 'QUIESCED_TREE') {
        if ($ExpectedFirewallState -ne 'ACTIVE' -or -not $firewallReady) {
            throw 'QUIESCED_TREE validation requires the exact ACTIVE public firewall guard'
        }
        $report = [pscustomobject]@{
            status = 'PASS'
            serviceId = 'caddy'
            serviceState = 'STOPPED'
            runtimeTreeMode = 'QUIESCED_TREE'
            runtimeTreeQuiescenceVerified = $true
            runtimeTreeScanCompletedAtUtc = $quiescedScanCompletedAtUtc.ToString('o')
            proxyIdentityProcessCount = 0
            tlsDataRoot = $tlsDataRoot
            logRoot = $logRoot
            xdgConfigRoot = $xdgConfigRoot
            firewallRuleGroup = [string]$policy.firewallRuleGroup
            firewallPolicySha256 = $firewallPolicySha256
            firewallState = 'ACTIVE'
            proxyBindingSha256 = [string]$policyContract.proxyBindingSha256
            failClosedCapable = $true
        }
    }
    else {
        $observation = [ordered]@{
        schemaVersion = 1
        serviceId = 'caddy'
        serviceState = 'RUNNING'
        servicePid = $servicePid
        serviceImagePath = $serviceImagePath
        serviceImageSha256 = $serviceImageSha256
        serviceCommandLine = $serviceCommandLine
            serviceCommandLineSha256 = Get-TextSha256 $serviceCommandLine
            serviceEnvironmentSha256 = $serviceEnvironmentSha256
        serviceAccount = $serviceAccount
        serviceAccountSid = $serviceAccountSid
        startMode = if ([string]$service.StartMode -in @('Auto', 'Automatic')) { 'AUTO' } else {
            ([string]$service.StartMode).ToUpperInvariant()
        }
        scmSddlSha256 = Get-TextSha256 $scmSddl
            configPath = $configPath
            configSha256 = $configSha256
            tlsDataRoot = $tlsDataRoot
            logRoot = $logRoot
        publicHost = [string]$policy.publicHost
        webRoot = [string]$policy.webRoot
        backendUpstream = [string]$policy.backendUpstream
        adminEndpoint = [string]$policy.adminEndpoint
        processTreePids = @($processTree | Sort-Object)
        listeners = $listeners
        managedProxyPresent = $false
        firewallRuleGroup = [string]$policy.firewallRuleGroup
        firewallPolicySha256 = $firewallPolicySha256
        firewallReady = [bool]$firewallReady
            firewallState = $ExpectedFirewallState
            processStartedAtUtc = $processStartedAtUtc.ToString('o')
            policyLastWriteUtc = ([DateTime]$policyLastWriteUtc).ToString('o')
            serviceImageLastWriteUtc = ([DateTime]$serviceImageSnapshot.lastWriteUtc).ToString('o')
            configLastWriteUtc = ([DateTime]$configSnapshot.lastWriteUtc).ToString('o')
            serviceEnvironmentLastWriteUtc = ([DateTime]$serviceRegistry.lastWriteUtc).ToString('o')
            runtimeFreshnessVerified = $true
        }
        $observationJson = $observation | ConvertTo-Json -Depth 8
        $observationStream = New-Object IO.FileStream(
            $observationPath,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None
        )
        try {
            $observationBytes = (New-Object Text.UTF8Encoding($false)).GetBytes($observationJson)
            $observationStream.Write($observationBytes, 0, $observationBytes.Length)
            $observationStream.Flush($true)
        }
        finally { $observationStream.Dispose() }

        $report = & (Join-Path $PSScriptRoot 'Test-LeanTpmExternalCaddyContract.ps1') `
            -PolicyPath $resolvedPolicy -ObservationPath $observationPath `
            -ExpectedPolicySha256 $ExpectedPolicySha256 `
            -ExpectedInstallRoot $install -ExpectedDataRoot $data `
            -ExpectedFirewallState $ExpectedFirewallState `
            -OutputFormat Json | ConvertFrom-Json
        if ([string]$report.status -cne 'PASS' -or -not [bool]$report.failClosedCapable) {
            throw 'External Caddy did not pass the exact host binding contract'
        }
        $report | Add-Member -NotePropertyName runtimeTreeMode `
            -NotePropertyValue 'ROOTS_ONLY'
        $report | Add-Member -NotePropertyName runtimeTreeQuiescenceVerified `
            -NotePropertyValue $false
    }
}
finally {
    if ($null -ne $configSnapshot) { $configSnapshot.stream.Dispose() }
    if ($null -ne $serviceImageSnapshot) { $serviceImageSnapshot.stream.Dispose() }
    $policyStream.Dispose()
    if (Test-Path -LiteralPath $observationPath -PathType Leaf) {
        [IO.File]::Delete($observationPath)
    }
}

if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 8 -Compress }
else { $report | Format-List }
