[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-FunctionText {
    param([string]$Source, [string]$Name)
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($Source, [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) "PowerShell AST errors were found while locating $Name"
    $matches = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $Name
    }, $true))
    Assert-True ($matches.Count -eq 1) "Expected exactly one function named $Name"
    return $matches[0].Extent.Text
}

$executorPath = Join-Path $PSScriptRoot 'Invoke-LeanTpmBackendV53Deployment.ps1'
$builderPath = Join-Path $PSScriptRoot 'Build-BackendV53ReleasePackage.ps1'
$operatorBuilderPath = Join-Path $PSScriptRoot 'Build-BackendV53OperatorPackage.ps1'
$readmePath = Join-Path $PSScriptRoot 'README.txt'
foreach ($path in @($executorPath, $builderPath, $operatorBuilderPath, $readmePath)) {
    Assert-True (Test-Path -LiteralPath $path -PathType Leaf) "Required operator file is missing: $path"
}

$executor = [IO.File]::ReadAllText($executorPath)
$builder = [IO.File]::ReadAllText($builderPath)
$operatorBuilder = [IO.File]::ReadAllText($operatorBuilderPath)
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($executorPath, [ref]$tokens, [ref]$errors)
Assert-True ($errors.Count -eq 0) 'Backend/V53 executor AST parse failed'
Assert-True (-not [regex]::IsMatch($executor, '[^\x00-\x7F]')) 'Executor must remain ASCII-only for Windows PowerShell 5.1'

foreach ($contract in @(
    "`$releaseFrom = '1.0.4-20260812.1'",
    "`$releaseTo = '1.0.4-20260812.2'",
    '$schemaFrom = 52',
    '$schemaTo = 53',
    'BACKEND_ONLY_DATABASE_MIGRATION',
    'RECOVERY_REQUIRED_V52_DATABASE_RESTORE',
    'databaseMigrationsIncluded = $true',
    'webIncluded = $false',
    'appIncluded = $false',
    'runtimeFlywayEnabled = $false',
    'backupRequired = $true',
    'Assert-BackendProcessBinding',
    'Assert-PublicApiState',
    'Assert-DatabaseV53',
    'Restore-V52Backup'
)) {
    Assert-True ($executor.Contains($contract)) "Executor contract is missing: $contract"
}

foreach ($forbidden in @(
    'Set-LeanTpmCurrentJunction',
    'New-CaddyText',
    'Stop-Service -Name $caddyServiceName',
    'Start-Service -Name $caddyServiceName',
    'payload\web',
    'frontend\dist',
    'LeanTPM-APP',
    'npm.cmd'
)) {
    Assert-True (-not $executor.Contains($forbidden)) "Executor must not touch Web/APP/Caddy: $forbidden"
    Assert-True (-not $builder.Contains($forbidden)) "Builder must not package or build Web/APP: $forbidden"
}

$planBranch = $executor.IndexOf('if ($PlanOnly -or [string]::IsNullOrWhiteSpace($ConfirmedPlanSha256))')
$planReturn = $executor.IndexOf('return', $planBranch)
$planLock = $executor.IndexOf('PLAN_LOCK_VERIFIED=', $planReturn)
$firstStop = $executor.IndexOf('Stop-Service -Name $backendServiceName', $planLock)
$backup = $executor.IndexOf('New-V52Backup', $firstStop)
$migration = $executor.IndexOf('Invoke-V53Migration', $backup)
$starterSwitch = $executor.IndexOf('Copy-Item -LiteralPath $starterCandidate -Destination $starterPath', $migration)
$backendStart = $executor.IndexOf('Start-Service -Name $backendServiceName', $starterSwitch)
Assert-True ($planBranch -ge 0 -and $planReturn -gt $planBranch) 'PlanOnly must return before mutation'
Assert-True ($planLock -gt $planReturn -and $firstStop -gt $planLock) 'Plan lock must be verified before stopping Backend'
Assert-True ($backup -gt $firstStop -and $migration -gt $backup) 'Fresh V52 backup must precede V53 migration'
Assert-True ($starterSwitch -gt $migration -and $backendStart -gt $starterSwitch) 'Migration must precede starter switch and Backend start'

Assert-True ($builder.Contains('mvn.cmd')) 'Builder must rebuild Backend'
Assert-True ($builder.Contains('V53__inspection_abnormal_measures.sql')) 'Builder must include the reviewed V53 migration'
Assert-True ($builder.Contains('schemaFrom = 52')) 'Builder manifest must pin schemaFrom 52'
Assert-True ($builder.Contains('schemaTo = 53')) 'Builder manifest must pin schemaTo 53'
Assert-True ($builder.Contains('compatibility-matrix.json')) 'Release builder must read the reviewed compatibility matrix'
Assert-True ($builder.Contains("status -cne 'SUPPORTED'")) 'Release builder must reject an unverified compatibility combination'
foreach ($placeholder in @('__RELEASE_ZIP_SHA256__', '__RELEASE_MANIFEST_SHA256__', '__SOURCE_COMMIT__')) {
    Assert-True ([regex]::Matches($executor, [regex]::Escape($placeholder)).Count -eq 1) "Executor placeholder count changed: $placeholder"
    Assert-True ($operatorBuilder.Contains($placeholder)) "Operator builder does not bind placeholder: $placeholder"
}
Assert-True ($operatorBuilder.Contains('source must be a completely clean committed tree')) 'Operator builder must reject dirty source'
Assert-True ($operatorBuilder.Contains("'Build-BackendV53ReleasePackage.ps1'")) 'Operator package must include the release builder required by its server-side test'
Assert-True ($operatorBuilder.Contains("'Build-BackendV53OperatorPackage.ps1'")) 'Operator package must include its builder required by its server-side test'
Assert-True ($builder.Contains("'runtime\production-1.0.4-20260812.2-backend-v53-operator-v1'")) 'Release build output must stay under ignored runtime evidence'
Assert-True ($operatorBuilder.Contains("'runtime\production-1.0.4-20260812.2-backend-v53-operator-v1'")) 'Operator build output must stay under ignored runtime evidence'
$restoreFunction = Get-FunctionText -Source $executor -Name 'Restore-V52Backup'
$noMigrationGuard = $restoreFunction.IndexOf('if (-not $script:migrationStarted)')
$destructiveRestore = $restoreFunction.IndexOf('DROP DATABASE IF EXISTS leantpm')
Assert-True ($noMigrationGuard -ge 0 -and $destructiveRestore -gt $noMigrationGuard) 'Database recreation must be guarded when migration never started'
$publicFunction = Get-FunctionText -Source $executor -Name 'Assert-PublicApiState'
Assert-True ($publicFunction.Contains("--resolve '8.163.66.164:80:127.0.0.1'")) 'Public verification must avoid relying on cloud IP hairpin routing'
Assert-True (-not $publicFunction.Contains('Invoke-RestMethod')) 'Public verification must bind the public Host header to loopback explicitly'

Invoke-Expression (Get-FunctionText -Source $executor -Name 'New-BackendStarterText')
Invoke-Expression (Get-FunctionText -Source $executor -Name 'Get-JarArgument')
Invoke-Expression (Get-FunctionText -Source $executor -Name 'Assert-BackendJarV53Payload')
$productVersion = '1.0.4'
$schemaFrom = 52
$schemaTo = 53
$oldJar = 'D:\LeanTPM\App\releases\1.0.4-20260812.1\payload\backend\leantpm-backend.jar'
$newJar = 'D:\LeanTPM\App\releases\1.0.4-20260812.2\payload\backend\leantpm-backend.jar'
$starter = @"
`$env:LEANTPM_RELEASE_VERSION = '1.0.4'
`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '52'
`$env:LEANTPM_FLYWAY_ENABLED = 'false'
& 'D:\tools\jdk-21.0.1\bin\java.exe' -jar '$oldJar'
"@
$candidate = New-BackendStarterText -Source $starter -CurrentJar $oldJar -TargetJar $newJar
Assert-True ($candidate.Contains("`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '53'")) 'Starter schema was not upgraded to V53'
Assert-True ($candidate.Contains($newJar) -and -not $candidate.Contains($oldJar)) 'Starter JAR binding was not upgraded'
foreach ($invalidCase in @(
    [ordered]@{ name = 'wrong-schema'; value = $starter.Replace("`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '52'", "`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '51'") },
    [ordered]@{ name = 'duplicate-flyway'; value = $starter + "`r`n`$env:LEANTPM_FLYWAY_ENABLED = 'false'" },
    [ordered]@{ name = 'wrong-jar'; value = $starter.Replace($oldJar, 'D:\wrong.jar') }
)) {
    $failed = $false
    try { [void](New-BackendStarterText -Source ([string]$invalidCase.value) -CurrentJar $oldJar -TargetJar $newJar) }
    catch { $failed = $true }
    Assert-True $failed "Invalid source starter must fail closed: $($invalidCase.name)"
}
Assert-True ((Get-JarArgument "java.exe -jar `"$newJar`"") -ceq $newJar) 'Quoted exact JAR argument was not parsed'
$jarFailed = $false
try { [void](Get-JarArgument "java.exe -jar $newJar -jar D:\wrong.jar") } catch { $jarFailed = $true }
Assert-True $jarFailed 'Ambiguous JAR arguments must fail closed'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$builtJar = Join-Path $repositoryRoot 'backend\target\leantpm-backend-1.0.4.jar'
$v53Sql = Join-Path $repositoryRoot 'backend\src\main\resources\db\migration\V53__inspection_abnormal_measures.sql'
if (Test-Path -LiteralPath $builtJar -PathType Leaf) {
    Assert-BackendJarV53Payload -JarPath $builtJar -MigrationPath $v53Sql
}

[ordered]@{
    status = 'PASS'
    executorSha256 = (Get-FileHash -LiteralPath $executorPath -Algorithm SHA256).Hash.ToLowerInvariant()
    planOnlyOrderingChecked = $true
    backendOnlyBoundaryChecked = $true
    recoveryRequiredChecked = $true
    starterPositiveCases = 1
    starterNegativeCases = 3
    jarArgumentNegativeCases = 1
    jarMigrationBytesChecked = (Test-Path -LiteralPath $builtJar -PathType Leaf)
} | ConvertTo-Json -Depth 4
