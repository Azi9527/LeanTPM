param(
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = '',
    [switch]$SkipMySql,
    [switch]$IncludeAndroid,
    [string]$GradleExecutable = $env:LEANTPM_GRADLE_EXECUTABLE,
    [string]$MavenExecutable = $env:LEANTPM_MAVEN_EXECUTABLE
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$results = [System.Collections.Generic.List[object]]::new()
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
        & $Action
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE"
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
    Invoke-ReleaseStep 'Git whitespace check' {
        & git diff --check
    }

    Invoke-ReleaseStep 'Backend unit tests' {
        & $MavenExecutable -q '-Dleantpm.build.directory=target-release' test `
            -f (Join-Path $backendRoot 'pom.xml')
    }

    if (-not $SkipMySql) {
        Invoke-ReleaseStep 'MySQL V1-V24 integration tests' {
            & (Join-Path $PSScriptRoot 'run-mysql-integration.ps1') `
                -MySqlHost $MySqlHost `
                -MySqlPort $MySqlPort `
                -MySqlUser $MySqlUser `
                -MySqlPassword $MySqlPassword `
                -MavenExecutable $MavenExecutable
        }
        Invoke-ReleaseStep 'MySQL critical indexes' {
            & (Join-Path $PSScriptRoot 'check-mysql-indexes.ps1') `
                -MySqlHost $MySqlHost `
                -MySqlPort $MySqlPort `
                -MySqlUser $MySqlUser `
                -MySqlPassword $MySqlPassword
        }
    }

    Push-Location $frontendRoot
    try {
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

    if ($IncludeAndroid) {
        Invoke-ReleaseStep 'Android debug APK' {
            & (Join-Path $PSScriptRoot 'build-android.ps1') `
                -Configuration Debug `
                -GradleExecutable $GradleExecutable
        }
    }
}
finally {
    Pop-Location
    $results | Format-Table -AutoSize
}
