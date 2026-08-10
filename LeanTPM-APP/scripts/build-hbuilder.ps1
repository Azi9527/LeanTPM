param(
    [ValidateSet('h5', 'mp-weixin', 'app')]
    [string]$Platform = 'app'
)

$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$defaultRoot = 'D:\tools\HBuilderX.5.15\HBuilderX'
$hbuilderRoot = if ($env:LEANTPM_HBUILDERX_ROOT) { $env:LEANTPM_HBUILDERX_ROOT } else { $defaultRoot }
$compilerDir = Join-Path $hbuilderRoot 'plugins\uniapp-cli-vite'
$compiler = Join-Path $compilerDir 'node_modules\.bin\uni.cmd'

if (-not (Test-Path -LiteralPath $compiler)) {
    throw "HBuilderX uni-app compiler not found: $compiler. Set LEANTPM_HBUILDERX_ROOT."
}
$repositoryRoot = (Resolve-Path (Join-Path $projectDir '..')).Path
$toolchain = Get-Content -LiteralPath (Join-Path $repositoryRoot 'release\toolchain-lock.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
$compilerPackage = Get-Content -LiteralPath (Join-Path $compilerDir 'package.json') `
    -Encoding utf8 -Raw | ConvertFrom-Json
if ([string]$toolchain.hbuilderX.compilerVersion -cne [string]$compilerPackage.version -or
        [string]$toolchain.hbuilderX.compilerDigest -notmatch '^[0-9a-f]{64}$') {
    throw 'HBuilderX compiler version or digest is not pinned in release/toolchain-lock.json'
}
$compilerEvidence = & (Join-Path $repositoryRoot 'scripts\Get-LeanTpmDirectoryDigest.ps1') `
    -DirectoryPath $compilerDir -OutputFormat Json | ConvertFrom-Json
if ([string]$compilerEvidence.digest -cne [string]$toolchain.hbuilderX.compilerDigest) {
    throw 'HBuilderX compiler directory differs from the approved toolchain digest'
}

$env:UNI_INPUT_DIR = $projectDir
$env:UNI_OUTPUT_DIR = Join-Path $projectDir "unpackage\dist\build\$Platform"
$env:UNI_PLATFORM = $Platform
$env:NODE_ENV = 'production'

Push-Location $compilerDir
try {
    & $compiler build --platform $Platform
    if ($LASTEXITCODE -ne 0) {
        throw "uni-app $Platform build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host "LeanTPM-APP $Platform build completed: $env:UNI_OUTPUT_DIR"
