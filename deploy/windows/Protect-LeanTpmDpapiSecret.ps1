[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)]
    [ValidateSet(
        'LEANTPM_DB_PASSWORD',
        'LEANTPM_JWT_SECRET',
        'LEANTPM_BOOTSTRAP_ADMIN_PASSWORD'
    )]
    [string]$Name,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$ServiceAccount,
    [switch]$AllowNonProductionDataRoot,
    [Security.SecureString]$SecureValue,
    [switch]$ConfirmProtect,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$resolvedInstall = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$resolvedData = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionDataRoot) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $resolvedInstall -DataRoot $resolvedData `
    -EnvironmentKind $environmentKind `
    -AllowNonProductionCustomRoots:$AllowNonProductionDataRoot `
    -OutputFormat Json | ConvertFrom-Json
$isProductionDataRoot = [bool]$rootPolicy.isProductionRootPair
if ($isProductionDataRoot -and $AllowNonProductionDataRoot) {
    throw 'AllowNonProductionDataRoot cannot be used with the production DataRoot'
}
if ($ServiceAccount -notmatch '^[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+\$$' -and
        -not (-not $isProductionDataRoot -and $AllowNonProductionDataRoot -and
            $ServiceAccount -ceq 'NT AUTHORITY\LocalService')) {
    throw 'ServiceAccount must be the approved gMSA identity'
}
if ($isProductionDataRoot) {
    $trustPath = Join-Path $resolvedData 'config\release-trust.json'
    if (-not (Test-Path -LiteralPath $trustPath -PathType Leaf) -or
            [string](Get-Content -LiteralPath $trustPath -Encoding utf8 -Raw |
                ConvertFrom-Json).backendServiceAccount -cne $ServiceAccount) {
        throw 'Production secret protection requires the host-owned Backend gMSA identity'
    }
}
$resolvedOutput = (Resolve-Path -LiteralPath (Join-Path $resolvedData 'secrets')).Path.
    TrimEnd('\', '/')
if (((Get-Item -LiteralPath $resolvedOutput).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Secret output directory cannot be a reparse point'
}
$outputPath = Join-Path $resolvedOutput "$Name.bin"
if (Test-Path -LiteralPath $outputPath) {
    throw 'DPAPI secret output already exists; rotate with a new reviewed ceremony'
}
if (-not $ConfirmProtect) { throw 'ConfirmProtect is required before protecting a secret' }
if (-not $PSCmdlet.ShouldProcess($outputPath, 'Create machine-bound DPAPI secret blob')) { return }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator privileges are required to protect host DPAPI secrets and ACLs'
}
$lockPath = Join-Path $resolvedData 'locks\deployment.lock'
if (-not (Test-Path -LiteralPath (Split-Path -Parent $lockPath) -PathType Container)) {
    throw 'Host mutation lock directory must be initialized before protecting secrets'
}
$ownedLock = New-Object IO.FileStream(
    $lockPath,
    [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite,
    [IO.FileShare]::Read
)
$lockTokenBytes = New-Object byte[] 32
$lockTokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $lockTokenGenerator.GetBytes($lockTokenBytes) }
finally { $lockTokenGenerator.Dispose() }
$lockToken = [Text.Encoding]::ASCII.GetBytes(
    [BitConverter]::ToString($lockTokenBytes).Replace('-', '').ToLowerInvariant()
)
$ownedLock.SetLength(0)
$ownedLock.Write($lockToken, 0, $lockToken.Length)
$ownedLock.Flush($true)
try {
    $lockedPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $resolvedInstall -DataRoot $resolvedData `
        -EnvironmentKind $environmentKind `
        -AllowNonProductionCustomRoots:$AllowNonProductionDataRoot `
        -OutputFormat Json | ConvertFrom-Json
    if ([bool]$lockedPolicy.isProductionRootPair -ne $isProductionDataRoot -or
            [string]$lockedPolicy.installRoot -cne [string]$rootPolicy.installRoot -or
            [string]$lockedPolicy.dataRoot -cne [string]$rootPolicy.dataRoot -or
            [string]$lockedPolicy.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256) {
        throw 'Host layout changed after acquiring the global mutation lock'
    }
& icacls.exe $resolvedOutput '/inheritance:r' '/grant:r' 'Administrators:(OI)(CI)F' `
    "$ServiceAccount`:(OI)(CI)RX" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the host secrets directory ACL' }
& icacls.exe $resolvedOutput '/setowner' 'Administrators' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to set the host secrets directory owner' }
if ($null -eq $SecureValue) {
    $SecureValue = Read-Host "Enter $Name without echo" -AsSecureString
}

Add-Type -AssemblyName System.Security -ErrorAction Stop
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
$chars = $null
$clearBytes = $null
$protectedBytes = $null
try {
    $charCount = [Runtime.InteropServices.Marshal]::ReadInt32($bstr, -4) / 2
    if ($charCount -lt 1) { throw 'Secret must not be empty' }
    $chars = New-Object char[] $charCount
    for ($index = 0; $index -lt $charCount; $index++) {
        $chars[$index] = [char][Runtime.InteropServices.Marshal]::ReadInt16($bstr, $index * 2)
    }
    $clearBytes = [Text.Encoding]::UTF8.GetBytes($chars)
    $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
        $clearBytes,
        $null,
        [Security.Cryptography.DataProtectionScope]::LocalMachine
    )
    $stream = New-Object IO.FileStream(
        $outputPath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try { $stream.Write($protectedBytes, 0, $protectedBytes.Length) }
    finally { $stream.Dispose() }
    & icacls.exe $outputPath '/inheritance:r' '/grant:r' 'Administrators:F' `
        "$ServiceAccount`:R" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to protect the DPAPI secret blob ACL' }
    & icacls.exe $outputPath '/setowner' 'Administrators' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to set the DPAPI secret blob owner' }
}
catch {
    if (Test-Path -LiteralPath $outputPath -PathType Leaf) {
        [IO.File]::Delete($outputPath)
    }
    throw
}
finally {
    if ($null -ne $chars) { [Array]::Clear($chars, 0, $chars.Length) }
    if ($null -ne $clearBytes) { [Array]::Clear($clearBytes, 0, $clearBytes.Length) }
    if ($null -ne $protectedBytes) { [Array]::Clear($protectedBytes, 0, $protectedBytes.Length) }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

$report = [pscustomobject]@{
    status = 'PASS'
    name = $Name
    reference = "dpapi://$Name.bin"
    outputPath = $outputPath
    encryptedSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath).Hash.ToLowerInvariant()
    hostLayoutSha256 = if ($isProductionDataRoot) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }
}
finally { $ownedLock.Dispose() }
