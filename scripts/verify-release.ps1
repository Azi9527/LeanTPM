param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = $env:LEANTPM_TEST_DB_PASSWORD,
    [switch]$SkipMySql,
    [switch]$ConfirmIsolatedDatabase,
    [string]$ExpectedServerUuid = '',
    [string]$MySqlSslCaPath = $env:LEANTPM_MYSQL_SSL_CA_PATH,
    [string]$MySqlSslTrustStorePath = $env:LEANTPM_MYSQL_SSL_TRUST_STORE_PATH,
    [Alias('IncludeAndroid')][switch]$IncludeLegacyCapacitorAndroid,
    [string]$PackagePath = '',
    [string]$TrustedManifestCertificateThumbprint = '',
    [string]$CanonicalAppApkPath = '',
    [string]$TrustedAppSignerSha256 = '',
    [string]$StageOneEvidencePath = '',
    [string]$StageOneEvidenceSignaturePath = '',
    [string]$TrustedStageOneEvidenceCertificateThumbprint = '',
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$AllowUnsignedTestManifest,
    [switch]$AllowPartialVerification,
    [string]$GradleExecutable = $env:LEANTPM_GRADLE_EXECUTABLE,
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text',
    [string]$EvidencePath = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$appRoot = Join-Path $repositoryRoot 'LeanTPM-APP'
$startedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
$baselineCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$workingTreeDirty = @(& git -C $repositoryRoot status --porcelain).Count -gt 0
$toolchainLockPath = Join-Path $repositoryRoot 'release\toolchain-lock.json'
$toolchainLockSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $toolchainLockPath).
    Hash.ToLowerInvariant()
$resolvedEvidence = ''
$evidenceParent = ''
$releasePlatformTapPath = ''
if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
    $resolvedEvidence = [IO.Path]::GetFullPath($EvidencePath)
    $evidenceParent = Split-Path -Parent $resolvedEvidence
    if (-not (Test-Path -LiteralPath $evidenceParent -PathType Container)) {
        $null = New-Item -ItemType Directory -Path $evidenceParent -Force
    }
    $releasePlatformTapPath = Join-Path $evidenceParent 'release-platform-tests.tap'
}
$version = Get-Content -LiteralPath (Join-Path $repositoryRoot 'VERSION.json') -Encoding utf8 -Raw |
    ConvertFrom-Json
$results = [System.Collections.Generic.List[object]]::new()
$releaseable = $true
$pipelineFailure = $null
$verifiedPackageRoot = ''
$script:verifiedPackageReport = $null
$script:stageOneEvidenceReport = $null
if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenCommand) {
        $MavenExecutable = $mavenCommand.Source
    }
    else {
        $MavenExecutable = Join-Path $repositoryRoot 'runtime\apache-maven-3.9.11\bin\mvn.cmd'
    }
}
if (-not (Test-Path -LiteralPath $MavenExecutable)) {
    throw "Maven executable was not found at $MavenExecutable"
}

function Invoke-ReleaseStep {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $global:LASTEXITCODE = 0
        if ($OutputFormat -eq 'Json') { & $Action | Out-Null }
        else { & $Action }
        $stepSucceeded = $?
        $stepExitCode = $LASTEXITCODE
        if (-not $stepSucceeded -or $stepExitCode -ne 0) {
            throw "$Name failed with exit code $stepExitCode"
        }
        $results.Add([pscustomobject]@{
            Step = $Name
            Result = 'PASS'
            Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
        })
    }
    catch {
        $results.Add([pscustomobject]@{
            Step = $Name
            Result = 'FAIL'
            Seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
        })
        throw
    }
    finally {
        $watch.Stop()
    }
}

Push-Location $repositoryRoot
try {
    Invoke-ReleaseStep 'Unified version contract' {
        & (Join-Path $PSScriptRoot 'Test-LeanTpmVersion.ps1') -RepositoryRoot $repositoryRoot
    }

    if ($workingTreeDirty) {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'Clean release candidate source'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }
    else {
        $results.Add([pscustomobject]@{
            Step = 'Clean release candidate source'
            Result = 'PASS'
            Seconds = 0
        })
    }

    $toolchain = Get-Content -LiteralPath $toolchainLockPath -Encoding utf8 -Raw |
        ConvertFrom-Json
    $toolchainPinned = [string]$toolchain.java.sha256 -match '^[0-9a-f]{64}$' -and
        [string]$toolchain.hbuilderX.compilerDigest -match '^[0-9a-f]{64}$' -and
        [string]$toolchain.winSW.version -match '^\d+\.\d+(?:\.\d+)?$' -and
        [string]$toolchain.winSW.sha256 -match '^[0-9a-f]{64}$' -and
        [string]$toolchain.caddy.version -match '^\d+\.\d+(?:\.\d+)?$' -and
        [string]$toolchain.caddy.sha256 -match '^[0-9a-f]{64}$'
    if (-not $toolchainPinned) {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'Pinned release toolchain'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }
    else {
        $results.Add([pscustomobject]@{
            Step = 'Pinned release toolchain'
            Result = 'PASS'
            Seconds = 0
        })
    }

    Invoke-ReleaseStep 'Git whitespace check' {
        & git diff --check
    }

    Invoke-ReleaseStep 'Release platform contract and fault tests' {
        $nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
        if ($null -eq $nodeCommand) {
            throw 'node.exe is required for release platform contract tests'
        }
        $testPath = Join-Path $repositoryRoot 'scripts\tests\release-platform.test.mjs'
        $tapLines = @(& $nodeCommand.Source --test $testPath 2>&1 | ForEach-Object {
                [string]$_
            })
        $testExitCode = $LASTEXITCODE
        $tapText = ($tapLines -join [Environment]::NewLine) + [Environment]::NewLine
        if (-not [string]::IsNullOrWhiteSpace($releasePlatformTapPath)) {
            [IO.File]::WriteAllText(
                $releasePlatformTapPath,
                $tapText,
                (New-Object Text.UTF8Encoding($false))
            )
        }
        if ($OutputFormat -eq 'Text') { $tapLines | Write-Output }
        $passMatch = [regex]::Match($tapText, '(?m)^# pass (\d+)\s*$')
        $failMatch = [regex]::Match($tapText, '(?m)^# fail (\d+)\s*$')
        $testMatch = [regex]::Match($tapText, '(?m)^# tests (\d+)\s*$')
        $script:releasePlatformMetrics = [ordered]@{
            tests = if ($testMatch.Success) { [int]$testMatch.Groups[1].Value } else { $null }
            passed = if ($passMatch.Success) { [int]$passMatch.Groups[1].Value } else { $null }
            failed = if ($failMatch.Success) { [int]$failMatch.Groups[1].Value } else { $null }
            evidencePath = if ([string]::IsNullOrWhiteSpace($releasePlatformTapPath)) {
                $null
            }
            else { $releasePlatformTapPath }
            evidenceSha256 = if ([string]::IsNullOrWhiteSpace($releasePlatformTapPath)) {
                $null
            }
            else {
                (Get-FileHash -Algorithm SHA256 -LiteralPath $releasePlatformTapPath).
                    Hash.ToLowerInvariant()
            }
        }
        if ($testExitCode -ne 0) {
            throw "Release platform tests failed with exit code $testExitCode"
        }
    }
    $results[$results.Count - 1] | Add-Member -NotePropertyName Details `
        -NotePropertyValue $script:releasePlatformMetrics

    Invoke-ReleaseStep 'No-Redis cross-client authentication contracts' {
        $nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
        if ($null -eq $nodeCommand) {
            throw 'node.exe is required for no-Redis contract tests'
        }
        & $nodeCommand.Source --test `
            (Join-Path $repositoryRoot 'scripts\tests\no-redis-contract.test.mjs') `
            (Join-Path $repositoryRoot 'scripts\tests\auth-e2e-contract.test.mjs') `
            (Join-Path $repositoryRoot 'frontend\tests\*.test.mjs')
    }

    Invoke-ReleaseStep 'Backend unit tests' {
        & $MavenExecutable -q '-Dleantpm.build.directory=target-release' test `
            -f (Join-Path $backendRoot 'pom.xml')
    }

    if (-not $SkipMySql) {
        Invoke-ReleaseStep "MySQL V1-V$($version.databaseSchemaVersion) integration tests" {
            & (Join-Path $PSScriptRoot 'run-mysql-integration.ps1') `
                -MySqlHost $MySqlHost `
                -MySqlPort $MySqlPort `
                -MySqlUser $MySqlUser `
                -MySqlPassword $MySqlPassword `
                -ConfirmIsolatedDatabase:$ConfirmIsolatedDatabase `
                -ExpectedServerUuid $ExpectedServerUuid `
                -MySqlSslCaPath $MySqlSslCaPath `
                -MySqlSslTrustStorePath $MySqlSslTrustStorePath `
                -MavenExecutable $MavenExecutable
        }
        Invoke-ReleaseStep 'MySQL critical indexes' {
            & (Join-Path $PSScriptRoot 'check-mysql-indexes.ps1') `
                -MySqlHost $MySqlHost `
                -MySqlPort $MySqlPort `
                -MySqlUser $MySqlUser `
                -MySqlPassword $MySqlPassword `
                -MySqlSslCaPath $MySqlSslCaPath
        }
        Invoke-ReleaseStep 'Persistent authentication and idempotency E2E' {
            & (Join-Path $PSScriptRoot 'verify-auth-e2e.ps1') `
                -MySqlHost $MySqlHost `
                -MySqlPort $MySqlPort `
                -MySqlUser $MySqlUser `
                -MySqlPassword $MySqlPassword `
                -ConfirmIsolatedDatabase:$ConfirmIsolatedDatabase `
                -ExpectedServerUuid $ExpectedServerUuid `
                -MySqlSslCaPath $MySqlSslCaPath `
                -MySqlSslTrustStorePath $MySqlSslTrustStorePath `
                -MavenExecutable $MavenExecutable
        }
    }
    else {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'MySQL integration tests'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }

    Push-Location $frontendRoot
    try {
        Invoke-ReleaseStep 'Frontend clean dependency install' {
            & npm.cmd ci
        }
        Invoke-ReleaseStep 'Frontend type check' {
            & npm.cmd run typecheck
        }
        Invoke-ReleaseStep 'Frontend production build' {
            & npm.cmd run build
        }
        Invoke-ReleaseStep 'Frontend dependency audit' {
            & npm.cmd audit --audit-level=high
        }
    }
    finally {
        Pop-Location
    }

    Push-Location $appRoot
    try {
        Invoke-ReleaseStep 'LeanTPM-APP checks and tests' {
            & npm.cmd run verify
        }
    }
    finally {
        Pop-Location
    }

    if ([string]::IsNullOrWhiteSpace($PackagePath)) {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'Signed release package'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }
    else {
        $verifiedPackageRoot = Join-Path ([IO.Path]::GetTempPath()) (
            'leantpm-release-verify-' + [Guid]::NewGuid().ToString('N')
        )
        Invoke-ReleaseStep 'Release package integrity and signature' {
            $packageArguments = @{
                PackagePath = $PackagePath
                ExtractTo = $verifiedPackageRoot
                AllowUnsignedTestManifest = [bool]$AllowUnsignedTestManifest
                OutputFormat = 'Json'
            }
            if (-not [string]::IsNullOrWhiteSpace($TrustedManifestCertificateThumbprint)) {
                $packageArguments.TrustedCertificateThumbprint =
                    $TrustedManifestCertificateThumbprint
            }
            $script:verifiedPackageReport =
                & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') @packageArguments |
                ConvertFrom-Json
        }
        if ($AllowUnsignedTestManifest) {
            $releaseable = $false
            $results.Add([pscustomobject]@{
                Step = 'Production manifest signature'
                Result = 'NOT_RELEASEABLE'
                Seconds = 0
            })
        }
    }

    if ($IncludeLegacyCapacitorAndroid) {
        Invoke-ReleaseStep 'Legacy Capacitor Android release evidence (not canonical)' {
            & (Join-Path $PSScriptRoot 'build-android.ps1') `
                -Configuration Release `
                -GradleExecutable $GradleExecutable
        }
    }

    if ([string]::IsNullOrWhiteSpace($CanonicalAppApkPath) -or
            [string]::IsNullOrWhiteSpace($TrustedAppSignerSha256) -or
            [string]::IsNullOrWhiteSpace($verifiedPackageRoot)) {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'Canonical LeanTPM-APP package'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }
    else {
        Invoke-ReleaseStep 'Canonical LeanTPM-APP package' {
            $appReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmAndroidPackage.ps1') `
                -ApkPath $CanonicalAppApkPath `
                -ExpectedPackageName ([string]$version.appPackageName) `
                -ExpectedVersionName ([string]$version.productVersion) `
                -ExpectedVersionCode ([int]$version.appVersionCode) `
                -TrustedSignerSha256 $TrustedAppSignerSha256 `
                -AndroidSdk $AndroidSdk `
                -OutputFormat Json | ConvertFrom-Json
            $packagedApk = Join-Path $verifiedPackageRoot 'payload\app\LeanTPM.apk'
            if (-not (Test-Path -LiteralPath $packagedApk -PathType Leaf) -or
                    -not (Get-FileHash -Algorithm SHA256 -LiteralPath $packagedApk).Hash.Equals(
                        [string]$appReport.sha256,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
                throw 'Verified canonical APK differs from payload/app/LeanTPM.apk'
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($StageOneEvidencePath) -or
            [string]::IsNullOrWhiteSpace($StageOneEvidenceSignaturePath) -or
            [string]::IsNullOrWhiteSpace(
                $TrustedStageOneEvidenceCertificateThumbprint
            ) -or $null -eq $script:verifiedPackageReport) {
        $releaseable = $false
        $results.Add([pscustomobject]@{
            Step = 'Stage-one isolated environment evidence'
            Result = 'NOT_RELEASEABLE'
            Seconds = 0
        })
    }
    else {
        Invoke-ReleaseStep 'Stage-one isolated environment evidence' {
            $script:stageOneEvidenceReport =
                & (Join-Path $PSScriptRoot 'Test-LeanTpmStageOneEvidence.ps1') `
                -EvidencePath $StageOneEvidencePath `
                -SignaturePath $StageOneEvidenceSignaturePath `
                -TrustedCertificateThumbprint `
                    $TrustedStageOneEvidenceCertificateThumbprint `
                -ExpectedBaselineCommit $baselineCommit `
                -ExpectedReleaseId ([string]$script:verifiedPackageReport.releaseId) `
                -ExpectedProductVersion ([string]$version.productVersion) `
                -ExpectedPackageSha256 ([string]$script:verifiedPackageReport.sha256) `
                -ExpectedManifestSha256 `
                    ([string]$script:verifiedPackageReport.manifestSha256) `
                -ExpectedToolchainLockSha256 $toolchainLockSha256 `
                -OutputFormat Json | ConvertFrom-Json
        }
        $results[$results.Count - 1] | Add-Member -NotePropertyName Details `
            -NotePropertyValue $script:stageOneEvidenceReport
    }
}
catch {
    $pipelineFailure = $_
    $releaseable = $false
}
finally {
    Pop-Location
    if (-not [string]::IsNullOrWhiteSpace($verifiedPackageRoot) -and
            (Test-Path -LiteralPath $verifiedPackageRoot -PathType Container)) {
        [System.IO.Directory]::Delete($verifiedPackageRoot, $true)
    }
}

$summary = [ordered]@{
    schemaVersion = 1
    startedAtUtc = $startedAtUtc
    completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    baselineCommit = $baselineCommit
    workingTreeDirty = $workingTreeDirty
    toolchainLockSha256 = $toolchainLockSha256
    productVersion = [string]$version.productVersion
    result = if ($null -ne $pipelineFailure) {
        'FAILED'
    }
    elseif ($releaseable) { 'RELEASEABLE' }
    else { 'NOT_RELEASEABLE' }
    releaseable = $releaseable
    steps = @($results)
}
$summaryJson = $summary | ConvertTo-Json -Depth 7
if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
    [IO.File]::WriteAllText(
        $resolvedEvidence,
        $summaryJson,
        (New-Object Text.UTF8Encoding($false))
    )
}
if ($OutputFormat -eq 'Json') { $summaryJson }
else { $results | Format-Table -AutoSize }

if ($null -ne $pipelineFailure) { throw $pipelineFailure }
if (-not $releaseable -and -not $AllowPartialVerification) {
    throw 'NOT_RELEASEABLE: database, signed package, and canonical signed LeanTPM-APP evidence are required'
}
