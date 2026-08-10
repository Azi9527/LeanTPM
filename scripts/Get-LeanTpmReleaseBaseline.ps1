[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepositoryRoot,
    [Parameter(Mandatory)][string]$ExpectedCommit,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
if ($ExpectedCommit -cnotmatch '^[0-9a-f]{40}$') {
    throw 'ExpectedCommit must be a lowercase 40-character Git commit'
}
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
$outputParent = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "Output directory does not exist: $outputParent"
}
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Baseline output already exists: $outputFullPath"
}

$commit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -cne $ExpectedCommit) {
    throw "HEAD does not exactly match the trusted commit $ExpectedCommit"
}
$statusLines = @(& git -C $root status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw 'Unable to read Git worktree status' }
if ($statusLines.Count -ne 0) {
    throw 'Release source tree is dirty or contains untracked files'
}
$tree = @(& git -C $root ls-tree -r --full-tree HEAD)
if ($LASTEXITCODE -ne 0 -or $tree.Count -eq 0) { throw 'Unable to enumerate the trusted Git tree' }
$treeText = ($tree -join "`n") + "`n"
$treeBytes = [System.Text.Encoding]::UTF8.GetBytes($treeText)
$sha = [System.Security.Cryptography.SHA256]::Create()
try { $treeDigest = ([BitConverter]::ToString($sha.ComputeHash($treeBytes))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }
$epochText = (& git -C $root show -s --format=%ct HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $epochText -notmatch '^[0-9]+$') {
    throw 'Unable to read the trusted commit timestamp'
}
$branch = (& git -C $root symbolic-ref --short -q HEAD 2>$null)
$detached = $LASTEXITCODE -ne 0
$gitVersion = (& git --version).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Unable to read Git version' }
$record = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    repositoryRoot = $root
    commit = $commit
    branch = if ($detached) { $null } else { [string]$branch }
    detached = $detached
    dirty = $false
    sourceDateEpoch = [int64]$epochText
    trackedFileCount = $tree.Count
    fileTreeSha256 = $treeDigest
    tools = [ordered]@{
        git = $gitVersion
        powershell = $PSVersionTable.PSVersion.ToString()
    }
    capturedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
}
$json = $record | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
    $outputFullPath,
    $json,
    (New-Object System.Text.UTF8Encoding($false))
)
if ($OutputFormat -eq 'Json') { $json }
else { ([pscustomobject]$record) | Format-List }
