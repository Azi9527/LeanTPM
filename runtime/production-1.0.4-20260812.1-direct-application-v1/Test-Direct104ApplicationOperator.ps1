[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'Invoke-LeanTpmDirectApplicationDeployment-1.0.4.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw "Direct 1.0.4 application deployment script is missing: $scriptPath"
}

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

$scriptText = [IO.File]::ReadAllText($scriptPath)
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
Assert-True ($errors.Count -eq 0) 'Direct 1.0.4 executor AST parse failed'
Assert-True (-not [regex]::IsMatch($scriptText, '[^\x00-\x7F]')) 'Executor must remain ASCII-only for Windows PowerShell 5.1'
Assert-True ($scriptText.Contains("`$releaseFrom = '1.0.3-20260811.1'")) 'Source release contract is missing'
Assert-True ($scriptText.Contains("`$releaseTo = '1.0.4-20260812.1'")) 'Target release contract is missing'
Assert-True ($scriptText.Contains("`$schemaVersion = 52")) 'V52 application-only contract is missing'
Assert-True ($scriptText.Contains("direct-predeploy-1.0.4-20260812-02")) 'Clean retry must use a fresh backup directory'
Assert-True ($scriptText.Contains("direct-deployment-1.0.4-20260812-02")) 'Clean retry must use a fresh evidence directory'
Assert-True (-not $scriptText.Contains('Invoke-LegacyMigration')) 'Application-only executor must not contain a migration invocation'
Assert-True (-not $scriptText.Contains('Restore-LeanTpmV50')) 'Application-only executor must not contain V50 recovery'
Assert-True (-not $scriptText.Contains('OpsControl')) 'Executor must not use OpsControl'
Assert-True (-not $scriptText.Contains('ReleaseAgent')) 'Executor must not use ReleaseAgent'
Assert-True ($scriptText.Contains('databaseModified=false')) 'Application-only executor must declare that it does not modify the database'

$databaseStateText = Get-FunctionText -Source $scriptText -Name 'Get-DatabaseState'
Assert-True ($databaseStateText.Contains(") FROM flyway_schema_history;")) 'Database identity SELECT must keep one valid FROM clause'
Assert-True (-not $databaseStateText.Contains("); FROM flyway_schema_history;")) 'Database identity SELECT must not terminate before FROM'

Invoke-Expression (Get-FunctionText -Source $scriptText -Name 'New-BackendStarterText')
Invoke-Expression (Get-FunctionText -Source $scriptText -Name 'Get-JarArgument')
Invoke-Expression (Get-FunctionText -Source $scriptText -Name 'Test-ExactExecutableServicePath')

$expectedBackendService = 'D:\LeanTPM\App\service\LeanTPM.Backend.exe'
Assert-True (Test-ExactExecutableServicePath -Actual $expectedBackendService -Expected $expectedBackendService) 'Unquoted exact Backend service path must pass'
Assert-True (Test-ExactExecutableServicePath -Actual ('"' + $expectedBackendService + '"') -Expected $expectedBackendService) 'SCM-quoted exact Backend service path must pass'
Assert-True (-not (Test-ExactExecutableServicePath -Actual ('"' + $expectedBackendService + '" --extra') -Expected $expectedBackendService)) 'Quoted Backend service path with arguments must fail'
Assert-True (-not (Test-ExactExecutableServicePath -Actual ($expectedBackendService + ' --extra') -Expected $expectedBackendService)) 'Unquoted Backend service path with arguments must fail'

$expectedProcessJar = 'D:\LeanTPM\App\releases\1.0.4-20260812.1\payload\backend\leantpm-backend.jar'
Assert-True ((Get-JarArgument "java.exe -Xmx1g -jar $expectedProcessJar") -ceq $expectedProcessJar) 'Unquoted exact -jar token was not parsed'
Assert-True ((Get-JarArgument "java.exe -jar `"$expectedProcessJar`"") -ceq $expectedProcessJar) 'Quoted exact -jar token was not parsed'
Assert-True ((Get-JarArgument "java.exe -Dnote=$expectedProcessJar -jar D:\wrong.jar") -cne $expectedProcessJar) 'Expected path in a non--jar argument must not satisfy binding'
foreach ($badCommand in @(
    "java.exe -jar $expectedProcessJar -jar D:\wrong.jar",
    "java.exe -Dnote=$expectedProcessJar"
)) {
    $failed = $false
    try { [void](Get-JarArgument $badCommand) } catch { $failed = $true }
    Assert-True $failed 'Ambiguous or non--jar command line must fail closed'
}

$oldJar = 'D:\LeanTPM\App\releases\1.0.3-20260811.1\payload\backend\leantpm-backend.jar'
$newJar = 'D:\LeanTPM\App\releases\1.0.4-20260812.1\payload\backend\leantpm-backend.jar'
$source = @"
`$env:LEANTPM_RELEASE_VERSION = '1.0.3'
`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '52'
`$env:LEANTPM_FLYWAY_ENABLED = 'false'
& 'D:\tools\jdk-21.0.1\bin\java.exe' -jar '$oldJar'
"@
$candidate = New-BackendStarterText -Source $source -CurrentJar $oldJar -TargetJar $newJar -VersionFrom '1.0.3' -VersionTo '1.0.4' -SchemaVersion 52
Assert-True ($candidate.Contains("`$env:LEANTPM_RELEASE_VERSION = '1.0.4'")) 'Release version was not replaced'
Assert-True ($candidate.Contains("`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '52'")) 'Schema contract was not preserved'
Assert-True ($candidate.Contains("`$env:LEANTPM_FLYWAY_ENABLED = 'false'")) 'Flyway disabled contract was not preserved'
Assert-True ($candidate.Contains($newJar)) 'Backend JAR path was not replaced'
Assert-True (-not $candidate.Contains($oldJar)) 'Old Backend JAR path remained'

$invalidCases = @(
    [ordered]@{ name = 'wrong-version'; value = $source.Replace("`$env:LEANTPM_RELEASE_VERSION = '1.0.3'", "`$env:LEANTPM_RELEASE_VERSION = '1.0.2'") },
    [ordered]@{ name = 'duplicate-version'; value = $source + "`r`n`$env:LEANTPM_RELEASE_VERSION = '1.0.3'" },
    [ordered]@{ name = 'wrong-schema'; value = $source.Replace("`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '52'", "`$env:LEANTPM_DATABASE_SCHEMA_VERSION = '50'") },
    [ordered]@{ name = 'flyway-enabled'; value = $source.Replace("`$env:LEANTPM_FLYWAY_ENABLED = 'false'", "`$env:LEANTPM_FLYWAY_ENABLED = 'true'") },
    [ordered]@{ name = 'wrong-jar'; value = $source.Replace($oldJar, 'D:\wrong\backend.jar') }
)
foreach ($invalidCase in $invalidCases) {
    $failed = $false
    try {
        [void](New-BackendStarterText -Source ([string]$invalidCase.value) -CurrentJar $oldJar -TargetJar $newJar -VersionFrom '1.0.3' -VersionTo '1.0.4' -SchemaVersion 52)
    } catch { $failed = $true }
    Assert-True $failed "Invalid starter contract must fail closed: $($invalidCase.name)"
}

$planBranch = $scriptText.IndexOf('if ($PlanOnly -or [string]::IsNullOrWhiteSpace($ConfirmedPlanSha256))')
$planReturn = $scriptText.IndexOf('return', $planBranch)
$planLock = $scriptText.IndexOf('PLAN_LOCK_VERIFIED=', $planReturn)
$firstStop = $scriptText.IndexOf('Stop-Service -Name $backendServiceName', $planLock)
Assert-True ($planBranch -ge 0 -and $planReturn -gt $planBranch) 'PlanOnly must return before mutation'
Assert-True ($planLock -gt $planReturn -and $firstStop -gt $planLock) 'Plan lock must be verified before stopping services'
Assert-True ($scriptText.Contains("runtimeFlywayEnabled = `$false")) 'Plan must declare Flyway runtime disabled'
Assert-True ($scriptText.Contains("databaseMigrationsIncluded = `$false")) 'Plan must declare no database migrations'
Assert-True ($scriptText.Contains("appIncluded = `$false")) 'Plan must exclude APP'
Assert-True ($scriptText.Contains("APPLICATION_ROLLBACK_TO_1.0.3_V52")) 'Application-only rollback contract is missing'
Assert-True ($scriptText.Contains('Assert-BackendProcessBinding')) 'Postflight must bind the listener process to the exact Backend JAR'
Assert-True ($scriptText.Contains('Assert-PublicState')) 'Forward and rollback paths must verify public HTTP and branding'
Assert-True ($scriptText.Contains('Assert-RestrictedDirectoryAcl')) 'Backup and evidence directories must have verified restricted ACLs'
Assert-True ($scriptText.Contains('$expectedBackendServicePath')) 'Backend SCM PathName must be fixed'
Assert-True ($scriptText.Contains('$expectedCaddyServicePath')) 'Caddy SCM PathName must be fixed'
Assert-True ($scriptText.Contains('$backupManifestSha256')) 'Rollback must bind the backup manifest to its creation-time SHA256'
Assert-True ($scriptText.Contains('backupRequired = $true')) 'Production release must create a current V52 backup'
$expandReleaseText = Get-FunctionText -Source $scriptText -Name 'Expand-AndVerifyRelease'
Assert-True (-not $expandReleaseText.Contains('$targetReleaseRoot /inheritance:r /grant:r ''*S-1-5-18:(OI)(CI)F'' ''*S-1-5-32-544:(OI)(CI)F'' ''*S-1-5-20:(OI)(CI)RX'' /T /C')) 'Target ACL must not apply inherit-only directory ACEs directly to files'
$publicStateText = Get-FunctionText -Source $scriptText -Name 'Assert-PublicState'
Assert-True (-not $publicStateText.Contains('ConvertFrom-Json')) 'Public branding verification must not decode UTF-8 JSON through the PowerShell 5.1 console code page'
Assert-True ($publicStateText.Contains('"code"')) 'Public branding verification must check the ASCII code field'

$restoreStart = $scriptText.IndexOf('function Restore-ApplicationV103')
$restoreEnd = $scriptText.IndexOf("Write-Output '[DIRECT_104_BEGIN]", $restoreStart)
Assert-True ($restoreStart -ge 0 -and $restoreEnd -gt $restoreStart) 'Rollback function boundaries must be present'
$restoreText = $scriptText.Substring($restoreStart, $restoreEnd - $restoreStart)
$restoreDatabaseCheck = $restoreText.LastIndexOf('Assert-DatabaseV52 (Get-DatabaseState)')
$restoreBackendStart = $restoreText.LastIndexOf('Start-Service -Name $backendServiceName')
$restoreBackendWait = $restoreText.LastIndexOf('Wait-ApplicationState -ExpectedVersion $versionFrom')
$restoreCaddyStart = $restoreText.LastIndexOf('Start-Service -Name $caddyServiceName')
Assert-True (
    $restoreDatabaseCheck -ge 0 -and
    $restoreDatabaseCheck -lt $restoreBackendStart -and
    $restoreBackendStart -lt $restoreBackendWait -and
    $restoreBackendWait -lt $restoreCaddyStart
) 'Rollback must verify V52, restore a healthy Backend, and only then reopen Caddy'

$releaseBuilderPath = Join-Path $PSScriptRoot 'Build-Direct104ReleasePackage.ps1'
$releaseBuilderText = [IO.File]::ReadAllText($releaseBuilderPath)
$mavenBuild = $releaseBuilderText.IndexOf('mvn.cmd')
$webBuild = $releaseBuilderText.IndexOf('npm.cmd')
$backendPackageRead = $releaseBuilderText.IndexOf("target-codex-104-release\leantpm-backend-1.0.4.jar")
$webPackageRead = $releaseBuilderText.IndexOf("frontend\dist")
Assert-True ($mavenBuild -ge 0 -and $mavenBuild -lt $backendPackageRead) 'Release builder must rebuild Backend before packaging it'
Assert-True ($webBuild -ge 0 -and $webBuild -lt $webPackageRead) 'Release builder must rebuild Web before packaging it'

$operatorBuilderText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Build-Direct104OperatorPackage.ps1'))
Assert-True ($operatorBuilderText.Contains("operatorVersion = 3")) 'Replacement operator package must identify itself as v3'
Assert-True ($operatorBuilderText.Contains('direct-application-v3.zip')) 'Replacement operator package must use a fresh v3 filename'
Assert-True ($operatorBuilderText.Contains("`$operatorTestPath = Join-Path `$sourceRoot 'Test-Direct104ApplicationOperator.ps1'")) 'Operator builder must bind the PowerShell 5.1 safety regression test'
Assert-True ($operatorBuilderText.Contains("System32\WindowsPowerShell\v1.0\powershell.exe")) 'Operator builder must execute its safety regression under Windows PowerShell 5.1'
$operatorTestInvocation = $operatorBuilderText.IndexOf('& $windowsPowerShell51 -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $operatorTestPath')
$operatorPackageCreation = $operatorBuilderText.IndexOf('[IO.File]::Open($partialPath')
Assert-True ($operatorTestInvocation -ge 0 -and $operatorTestInvocation -lt $operatorPackageCreation) 'Operator safety regression must pass before any package is created'
$readmeText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'README.txt'))
Assert-True ($readmeText.Contains('operator v3')) 'Replacement operator README must identify v3'

[ordered]@{
    status = 'PASS'
    scriptBytes = (Get-Item -LiteralPath $scriptPath).Length
    scriptSha256 = (Get-FileHash -LiteralPath $scriptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    starterPositiveCases = 1
    starterNegativeCases = 5
    planOnlyOrderingChecked = $true
} | ConvertTo-Json -Depth 4
