[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [Parameter(Mandatory)][string]$JavaExecutable,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedJavaSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedJarSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')]
    [string]$ExpectedConfigSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-FixedFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Label
    )

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path"
    }
    $item = Get-Item -LiteralPath ([IO.Path]::GetFullPath($Path)) -Force
    if ($item.PSIsContainer -or
            (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "$Label must be a fixed regular file"
    }
    return $item.FullName
}

function Assert-FileSha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )

    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if (-not $actual.Equals($Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label SHA-256 differs from the installed service binding"
    }
}

$install = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\', '/')
$data = [IO.Path]::GetFullPath($DataRoot).TrimEnd('\', '/')
$serviceRoot = Join-Path $install 'ops-services'
$jar = Get-FixedFile -Path (Join-Path $serviceRoot 'ops-control-plane.jar') `
    -Label 'OpsControl JAR'
$config = Get-FixedFile -Path (Join-Path $serviceRoot 'application-production.yml') `
    -Label 'OpsControl configuration'
$java = Get-FixedFile -Path $JavaExecutable -Label 'Java executable'

Assert-FileSha256 -Path $java -Expected $ExpectedJavaSha256 -Label 'Java executable'
Assert-FileSha256 -Path $jar -Expected $ExpectedJarSha256 -Label 'OpsControl JAR'
Assert-FileSha256 -Path $config -Expected $ExpectedConfigSha256 `
    -Label 'OpsControl configuration'

$opsDataRoot = Join-Path $data 'ops-control-plane'
$configUri = ([Uri]::new($config)).AbsoluteUri
foreach ($name in @(
        'JAVA_TOOL_OPTIONS',
        '_JAVA_OPTIONS',
        'JDK_JAVA_OPTIONS',
        'SPRING_APPLICATION_JSON',
        'SPRING_CONFIG_LOCATION',
        'SPRING_CONFIG_ADDITIONAL_LOCATION'
    )) {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
}
& $java '-jar' $jar `
    "--spring.config.additional-location=$configUri" `
    '--server.address=127.0.0.1' `
    '--server.port=18090' `
    "--leantpm.ops.data-root=$opsDataRoot"
exit $LASTEXITCODE
