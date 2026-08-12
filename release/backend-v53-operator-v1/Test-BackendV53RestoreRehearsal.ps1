[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$scriptPath = Join-Path $repositoryRoot 'scripts\run-backend-v53-restore-rehearsal.ps1'
$javaPath = Join-Path $repositoryRoot 'backend\src\test\java\com\leantpm\integration\BackendV53RestoreRehearsalMySqlIntegrationTest.java'
foreach ($path in @($scriptPath, $javaPath)) {
    Assert-True (Test-Path -LiteralPath $path -PathType Leaf) "Required restore rehearsal file is missing: $path"
}

$scriptText = [IO.File]::ReadAllText($scriptPath)
$javaText = [IO.File]::ReadAllText($javaPath)
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
Assert-True ($errors.Count -eq 0) 'Restore rehearsal PowerShell AST parse failed'

foreach ($required in @(
    'ConfirmIsolatedDatabase',
    'ExpectedServerUuid',
    'VERIFY_IDENTITY',
    'LEANTPM_V53_REHEARSAL_PHASE',
    'PREPARE_V52',
    'UPGRADE_V53',
    'VERIFY_RESTORED_V52',
    'mysqldump.exe',
    'DROP DATABASE IF EXISTS',
    'finally',
    'cleanupFailures',
    'backupSha256',
    'sourceDatabase',
    'restoredDatabase'
)) {
    Assert-True ($scriptText.Contains($required)) "Restore rehearsal contract is missing: $required"
}
Assert-True ($scriptText.Contains('leantpm_v53_rehearsal_')) 'Restore database names must use the dedicated prefix'
Assert-True ($scriptText.Contains('[Guid]::NewGuid()')) 'Restore database names must include unpredictable uniqueness'
Assert-True (-not $scriptText.Contains('--password=')) 'Database password must not be placed on process arguments'
Assert-True (-not $scriptText.Contains('leantpm;')) 'Restore rehearsal must not address the production database name'

foreach ($required in @(
    '@EnabledIfEnvironmentVariable',
    'PREPARE_V52',
    'UPGRADE_V53',
    'VERIFY_RESTORED_V52',
    'MigrationVersion.fromVersion("52")',
    'V53-HISTORY-RESTORE-FIXTURE',
    'cause_analysis',
    'permanent_countermeasure',
    'rerun.migrationsExecuted',
    'final_result'
)) {
    Assert-True ($javaText.Contains($required)) "Java restore fixture contract is missing: $required"
}

[ordered]@{
    status = 'PASS'
    scriptSha256 = (Get-FileHash -LiteralPath $scriptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    uniqueDatabaseBoundaryChecked = $true
    v52BackupRestoreChecked = $true
    v53NoOpChecked = $true
} | ConvertTo-Json -Depth 4
