param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = '',
    [string]$Database = 'leantpm',
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$OutputFile = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$resolvedSslCa = (Resolve-Path -LiteralPath $MySqlSslCaPath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedSslCa -PathType Leaf)) {
    throw 'MySqlSslCaPath must identify the host-owned MySQL CA certificate'
}
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $backupDirectory = Join-Path $repositoryRoot 'runtime\backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $OutputFile = Join-Path $backupDirectory (
        '{0}-{1}.sql' -f $Database, (Get-Date -Format 'yyyyMMddHHmmss')
    )
}
$resolvedParent = (Resolve-Path -LiteralPath (
    New-Item -ItemType Directory -Path (Split-Path -Parent $OutputFile) -Force
)).Path
$resolvedOutput = Join-Path $resolvedParent (Split-Path -Leaf $OutputFile)
if ((Test-Path -LiteralPath $resolvedOutput) -and -not $Force) {
    throw "Backup already exists: $resolvedOutput"
}

$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $MySqlPassword
    & mysqldump.exe `
        "--host=$MySqlHost" `
        "--port=$MySqlPort" `
        "--user=$MySqlUser" `
        '--ssl-mode=VERIFY_IDENTITY' `
        "--ssl-ca=$resolvedSslCa" `
        '--single-transaction' `
        '--routines' `
        '--triggers' `
        '--events' `
        '--hex-blob' `
        '--set-gtid-purged=OFF' `
        '--default-character-set=utf8mb4' `
        "--result-file=$resolvedOutput" `
        $Database
    if ($LASTEXITCODE -ne 0) {
        throw 'mysqldump failed'
    }
}
finally {
    $env:MYSQL_PWD = $previousPassword
}

$file = Get-Item -LiteralPath $resolvedOutput
$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedOutput
[pscustomobject]@{
    Backup = $file.FullName
    Bytes = $file.Length
    Sha256 = $hash.Hash
}
