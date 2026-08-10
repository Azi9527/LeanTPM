import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const powershell = 'powershell.exe'

function invokePowerShell(script, args = []) {
  return spawnSync(
    powershell,
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, ...args],
    { cwd: repositoryRoot, encoding: 'utf8' },
  )
}

function combinedOutput(result) {
  return `${result.stdout || ''}\n${result.stderr || ''}`
}

function invokeExternalIngressRecoveryHarness(scenario) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-ingress-recovery-'))
  const fixtureRoot = path.join(temporaryRoot, 'fixture')
  const installRoot = path.join(temporaryRoot, 'App')
  const dataRoot = path.join(temporaryRoot, 'Runtime')
  const stateRoot = path.join(dataRoot, 'state')
  const locksRoot = path.join(dataRoot, 'locks')
  const eventPath = path.join(temporaryRoot, 'events.log')
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const recoveryMarkerPath = path.join(stateRoot, 'recovery-inhibit.json')
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  const proxyBindingSha256 = 'a'.repeat(64)
  const firewallPolicySha256 = 'b'.repeat(64)
  const layoutSha256 = 'c'.repeat(64)
  const lockToken = 'd'.repeat(64)

  fs.mkdirSync(fixtureRoot)
  fs.mkdirSync(installRoot)
  fs.mkdirSync(stateRoot, { recursive: true })
  fs.mkdirSync(locksRoot, { recursive: true })
  fs.copyFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Restore-LeanTpmExternalIngress.ps1'),
    path.join(fixtureRoot, 'Restore-LeanTpmExternalIngress.ps1'),
  )
  const marker = {
    schemaVersion: 1,
    status: 'RECOVERY_REQUIRED',
    isolationMethod: 'HOST_FIREWALL',
    isolatedServiceId: 'caddy',
    firewallRuleGroup: 'LeanTPM-Public-Isolation',
    proxyBindingSha256,
    firewallPolicySha256,
  }
  const markerBytes = Buffer.from(JSON.stringify(marker), 'utf8')
  fs.writeFileSync(recoveryMarkerPath, markerBytes)
  fs.writeFileSync(path.join(locksRoot, 'deployment.lock'), lockToken, 'ascii')
  const recoveryStateSha256 = crypto.createHash('sha256').update(markerBytes).digest('hex')

  fs.writeFileSync(
    path.join(fixtureRoot, 'Test-LeanTpmProductionRootPolicy.ps1'),
    [
      'param([string]$InstallRoot,[string]$DataRoot,[string]$EnvironmentKind,[switch]$PlanOnly,[switch]$AllowNonProductionCustomRoots,[switch]$ContainmentOnly,[string]$OutputFormat)',
      `[pscustomobject]@{ isProductionRootPair = $true; hostLayoutSha256 = '${layoutSha256}'; proxyBindingSha256 = '${proxyBindingSha256}'; proxy = [pscustomobject]@{ mode = 'EXTERNAL_EXISTING'; serviceId = 'caddy'; bindingPolicyPath = 'D:\\trusted\\caddy-policy.json'; bindingPolicySha256 = '${proxyBindingSha256}' }; proxyBinding = [pscustomobject]@{ firewallRuleGroup = 'LeanTPM-Public-Isolation'; firewallPolicySha256 = '${firewallPolicySha256}'; serviceImagePath = 'D:\\trusted\\caddy.exe' } } | ConvertTo-Json -Depth 5 -Compress`,
      '',
    ].join('\r\n'),
  )
  fs.writeFileSync(
    path.join(fixtureRoot, 'Test-LeanTpmExternalFirewallIsolation.ps1'),
    [
      'param([string]$PolicyPath,[string]$ExpectedPolicySha256,[string]$ExpectedInstallRoot,[string]$ExpectedDataRoot,[string]$ExpectedState,[string]$OutputFormat)',
      '$global:FirewallVerifyCount++',
      "Add-HarnessEvent ('VERIFY_FIREWALL:' + $ExpectedState)",
      "if ($ExpectedState -eq 'ACTIVE' -and $global:FirewallVerifyCount -gt 1 -and $global:HarnessScenario -in @('REENABLE_FAIL','STOP_VERIFICATION_FAIL','DOUBLE_FAIL')) { throw 'simulated firewall re-enable verification failure' }",
      `[pscustomobject]@{ status = 'PASS'; firewallState = $ExpectedState; firewallPolicySha256 = '${firewallPolicySha256}' } | ConvertTo-Json -Compress`,
      '',
    ].join('\r\n'),
  )
  fs.writeFileSync(
    path.join(fixtureRoot, 'Test-LeanTpmExternalCaddyBinding.ps1'),
    [
      'param([string]$InstallRoot,[string]$DataRoot,[string]$PolicyPath,[string]$ExpectedPolicySha256,[string]$ExpectedHostLayoutSha256,[string]$ExpectedFirewallState,[string]$RuntimeTreeMode,[string]$OutputFormat)',
      "Add-HarnessEvent ('VERIFY_BINDING:' + $RuntimeTreeMode + ':' + $ExpectedFirewallState)",
      "if ($global:HarnessScenario -eq 'QUIESCED_SCAN_FAIL' -and $RuntimeTreeMode -eq 'QUIESCED_TREE') { throw 'simulated quiesced runtime-tree failure' }",
      "if ($global:HarnessScenario -in @('GUARDED_BINDING_FAIL','REENABLE_FAIL','STOP_VERIFICATION_FAIL','DOUBLE_FAIL') -and $RuntimeTreeMode -eq 'ROOTS_ONLY' -and $ExpectedFirewallState -eq 'ACTIVE') { throw 'simulated guarded binding failure' }",
      "if ($global:HarnessScenario -eq 'STANDBY_BINDING_FAIL' -and $RuntimeTreeMode -eq 'ROOTS_ONLY' -and $ExpectedFirewallState -eq 'STANDBY_DISABLED') { throw 'simulated standby binding failure' }",
      "$processStartedAtUtc = if ($global:HarnessScenario -eq 'STALE_PID_START') { '2026-08-08T23:59:59.0000000Z' } else { '2026-08-09T00:00:01.0000000Z' }",
      "$quiescenceVerified = $RuntimeTreeMode -eq 'QUIESCED_TREE' -and $global:HarnessScenario -ne 'QUIESCED_FALSE_REPORT'",
      `[pscustomobject]@{ status = 'PASS'; firewallState = $ExpectedFirewallState; firewallPolicySha256 = '${firewallPolicySha256}'; proxyBindingSha256 = '${proxyBindingSha256}'; failClosedCapable = $true; runtimeTreeQuiescenceVerified = $quiescenceVerified; runtimeTreeScanCompletedAtUtc = '2026-08-09T00:00:00.0000000Z'; processStartedAtUtc = $processStartedAtUtc; publicHost = 'tpm.example.test' } | ConvertTo-Json -Compress`,
      '',
    ].join('\r\n'),
  )

  fs.writeFileSync(
    harnessPath,
    [
      `$global:HarnessScenario = ${quotePowerShell(scenario)}`,
      `$global:HarnessEventPath = ${quotePowerShell(eventPath)}`,
      "$global:ServiceStatus = 'Running'",
      '$global:FirewallVerifyCount = 0',
      'function global:Add-HarnessEvent { param([string]$Value) Add-Content -LiteralPath $global:HarnessEventPath -Value $Value }',
      "function global:Enable-NetFirewallRule { param([string]$DisplayGroup,[string]$ErrorAction) Add-HarnessEvent 'ENABLE_FIREWALL' }",
      "function global:Disable-NetFirewallRule { param([string]$DisplayGroup,[string]$ErrorAction) Add-HarnessEvent 'DISABLE_FIREWALL'; if ($global:HarnessScenario -eq 'DISABLE_FAIL') { throw 'simulated firewall disable failure' } }",
      'function global:Get-Service { param([string]$Name,[string]$ErrorAction) $service = [pscustomobject]@{ Status = $global:ServiceStatus }; $service | Add-Member ScriptMethod WaitForStatus { param($Desired,$Timeout) Add-HarnessEvent (\'WAIT_SERVICE:\' + [string]$Desired); if ($global:HarnessScenario -eq \'WAIT_RUNNING_FAIL\' -and [string]$Desired -eq \'Running\') { throw \'simulated wait running failure\' } }; return $service }',
      "function global:Start-Service { param([string]$Name,[string]$ErrorAction) Add-HarnessEvent 'START_SERVICE'; if ($global:HarnessScenario -eq 'START_FAIL') { throw 'simulated start failure' }; $global:ServiceStatus = 'Running' }",
      "function global:Stop-Service { param([string]$Name,[switch]$Force,[string]$ErrorAction) Add-HarnessEvent 'STOP_SERVICE'; if ($global:HarnessScenario -eq 'DOUBLE_FAIL') { throw 'simulated service stop failure' }; $global:ServiceStatus = 'Stopped' }",
      'function global:Get-CimInstance { param([string]$ClassName,[string]$Filter,[string]$ErrorAction) if ($ClassName -eq \'Win32_Service\') { Add-HarnessEvent \'VERIFY_SCM\'; if ($global:HarnessScenario -eq \'STOP_VERIFICATION_FAIL\') { return [pscustomobject]@{ State = \'Running\'; ProcessId = 321 } }; return [pscustomobject]@{ State = $global:ServiceStatus; ProcessId = $(if ($global:ServiceStatus -eq \'Stopped\') { 0 } else { 321 }) } }; if ($ClassName -eq \'Win32_Process\') { Add-HarnessEvent \'VERIFY_PROCESS\'; if ($global:ServiceStatus -eq \'Running\') { return [pscustomobject]@{ ExecutablePath = \'D:\\trusted\\caddy.exe\'; ProcessId = 321; ParentProcessId = 0 } } }; return @() }',
      'function global:Get-NetTCPConnection { param([string]$State,[string]$ErrorAction) Add-HarnessEvent \'VERIFY_LISTENERS\'; if ($global:ServiceStatus -eq \'Running\') { return @([pscustomobject]@{ LocalPort=80; OwningProcess=321 },[pscustomobject]@{ LocalPort=443; OwningProcess=321 }) }; return @() }',
      "function global:Invoke-WebRequest { param([uri]$Uri,[switch]$UseBasicParsing,[int]$TimeoutSec) Add-HarnessEvent 'VERIFY_HTTPS'; if ($global:HarnessScenario -eq 'HTTPS_FAIL') { throw 'simulated HTTPS failure' }; return [pscustomobject]@{ StatusCode = 200 } }",
      'try {',
      `  & ${quotePowerShell(path.join(fixtureRoot, 'Restore-LeanTpmExternalIngress.ps1'))} -InstallRoot ${quotePowerShell(installRoot)} -DataRoot ${quotePowerShell(dataRoot)} -EnvironmentKind PRODUCTION -RecoveryMarkerPath ${quotePowerShell(recoveryMarkerPath)} -ExpectedRecoveryStateSha256 '${recoveryStateSha256}' -ExpectedProxyBindingSha256 '${proxyBindingSha256}' -DeploymentLockToken '${lockToken}' -ConfirmIngressRecovery -Confirm:$false -OutputFormat Json`,
      '} catch { Write-Error $_; exit 19 }',
      '',
    ].join('\r\n'),
  )

  const result = invokePowerShell(harnessPath)
  const events = fs.existsSync(eventPath)
    ? fs.readFileSync(eventPath, 'utf8').split(/\r?\n/u).filter(Boolean)
    : []
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return { result, events }
}

function invokeExternalCaddyRuntimeAclHarness(scenario) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-runtime-acl-'))
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const productionPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  fs.writeFileSync(
    harnessPath,
    [
      `$productionPath = ${quotePowerShell(productionPath)}`,
      `$scenario = ${quotePowerShell(scenario)}`,
      '$tokens = $null; $errors = $null',
      '$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$errors)',
      "if ($errors.Count -ne 0) { throw 'production binding script does not parse' }",
      "$wanted = @('Get-PrincipalSid','Assert-RuntimeChildAcl')",
      '$functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $wanted }, $true))',
      "if ($functions.Count -ne 2) { throw 'runtime ACL function extraction failed' }",
      '$functions | Sort-Object { $wanted.IndexOf($_.Name) } | ForEach-Object { Invoke-Expression $_.Extent.Text }',
      "$script:administratorsSid = 'S-1-5-32-544'",
      "$script:systemSid = 'S-1-5-18'",
      "$proxySid = 'S-1-5-21-111-222-333-444'",
      'function New-Rule {',
      '  param([string]$Sid,[Security.AccessControl.FileSystemRights]$Rights,[Security.AccessControl.InheritanceFlags]$Inheritance,[Security.AccessControl.PropagationFlags]$Propagation)',
      '  [pscustomobject]@{ IdentityReference = New-Object Security.Principal.SecurityIdentifier($Sid); FileSystemRights = $Rights; AccessControlType = [Security.AccessControl.AccessControlType]::Allow; InheritanceFlags = $Inheritance; PropagationFlags = $Propagation }',
      '}',
      '$inherit = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit',
      '$noneInheritance = [Security.AccessControl.InheritanceFlags]::None',
      '$nonePropagation = [Security.AccessControl.PropagationFlags]::None',
      '$rules = @()',
      '$isDirectory = $scenario -like "DIRECTORY_*"',
      'if ($isDirectory) {',
      '  $rules += New-Rule $script:administratorsSid FullControl $inherit $nonePropagation',
      '  $rules += New-Rule $script:systemSid FullControl $inherit $nonePropagation',
      '  $rules += New-Rule $proxySid Modify $inherit $nonePropagation',
      '} else {',
      '  $rules += New-Rule $script:administratorsSid FullControl $noneInheritance $nonePropagation',
      '  $rules += New-Rule $script:systemSid FullControl $noneInheritance $nonePropagation',
      '  $rules += New-Rule $proxySid Modify $noneInheritance $nonePropagation',
      '}',
      'if ($scenario -in @("DIRECTORY_INHERIT_ONLY_EVERYONE","FILE_INHERIT_ONLY_EVERYONE")) {',
      '  $rules += New-Rule "S-1-1-0" FullControl $inherit ([Security.AccessControl.PropagationFlags]::InheritOnly)',
      '}',
      'if ($scenario -eq "DIRECTORY_NO_PROPAGATE") {',
      '  $rules[2].PropagationFlags = [Security.AccessControl.PropagationFlags]::NoPropagateInherit',
      '}',
      'if ($scenario -eq "DIRECTORY_NO_INHERITANCE") { foreach ($rule in $rules) { $rule.InheritanceFlags = $noneInheritance } }',
      'if ($scenario -eq "FILE_INHERITABLE") { foreach ($rule in $rules) { $rule.InheritanceFlags = $inherit } }',
      '$global:AclFixture = [pscustomobject]@{ Owner = New-Object Security.Principal.SecurityIdentifier($proxySid); Access = $rules }',
      'function global:Get-Acl { param([string]$LiteralPath,[string]$ErrorAction) return $global:AclFixture }',
      'Assert-RuntimeChildAcl -Path "Z:\\fixture" -ProxySid $proxySid -Label "runtime fixture" -IsDirectory:$isDirectory',
      "[pscustomobject]@{ status = 'PASS' } | ConvertTo-Json -Compress",
      '',
    ].join('\r\n'),
  )
  const result = invokePowerShell(harnessPath)
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return result
}

function invokeExternalCaddyTraversalLifetimeHarness() {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-tree-lock-'))
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const productionPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  fs.writeFileSync(
    harnessPath,
    [
      `$productionPath = ${quotePowerShell(productionPath)}`,
      '$tokens = $null; $errors = $null',
      '$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$errors)',
      "if ($errors.Count -ne 0) { throw 'production binding script does not parse' }",
      "$functionAst = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-ProtectedRuntimeTree' }, $true))",
      "if ($functionAst.Count -ne 1) { throw 'runtime traversal function extraction failed' }",
      'Invoke-Expression $functionAst[0].Extent.Text',
      "$global:RootPath = 'C:\\runtime'",
      "$global:ChildPath = 'C:\\runtime\\child'",
      '$global:OpenHandles = @{}',
      '$global:TraversalEvents = New-Object System.Collections.Generic.List[string]',
      'function global:Assert-ProtectedRuntimeDirectory { param([string]$Path,[string]$ProxySid,[string]$Label) $global:TraversalEvents.Add(("ROOT_ACL:" + $Path)); return $Path }',
      'function global:Open-DirectoryIdentity {',
      '  param([string]$Path,[string]$Label)',
      '  $global:OpenHandles[$Path] = $true',
      '  $global:TraversalEvents.Add(("OPEN:" + $Path))',
      '  $handle = [pscustomobject]@{ Path = $Path }',
      '  $handle | Add-Member ScriptMethod Dispose { $global:OpenHandles[$this.Path] = $false; $global:TraversalEvents.Add(("DISPOSE:" + $this.Path)) }',
      '  return [pscustomobject]@{ handle = $handle; finalPath = $Path; fileAttributes = [IO.FileAttributes]::Directory }',
      '}',
      'function global:Assert-RuntimeChildAcl { param([string]$Path,[string]$ProxySid,[string]$Label,[bool]$IsDirectory) $global:TraversalEvents.Add(("CHILD_ACL:" + $Path)) }',
      'function global:Assert-DirectoryIdentityStable { param($Identity,[string]$ExpectedPath,[string]$Label) if ($Identity.handle.IsClosed) { throw "identity handle closed before stability check" } }',
      'function global:Get-ChildItem {',
      '  param([string]$LiteralPath,[switch]$Force,[string]$ErrorAction)',
      '  if (-not $global:OpenHandles[$LiteralPath]) { throw ("enumerated without a live identity handle: " + $LiteralPath) }',
      '  if ($LiteralPath -eq $global:ChildPath -and -not $global:OpenHandles[$global:RootPath]) { throw "root handle was released before child traversal completed" }',
      '  $global:TraversalEvents.Add(("ENUM:" + $LiteralPath))',
      '  if ($LiteralPath -eq $global:RootPath) { return [pscustomobject]@{ FullName = $global:ChildPath; PSIsContainer = $true; Attributes = [IO.FileAttributes]::Directory } }',
      '  return @()',
      '}',
      "Assert-ProtectedRuntimeTree -RootPath $global:RootPath -ProxySid 'S-1-5-21-111-222-333-444' -Label 'runtime fixture' | Out-Null",
      'if ($global:OpenHandles[$global:RootPath] -or $global:OpenHandles[$global:ChildPath]) { throw "runtime traversal leaked an identity handle" }',
      '[pscustomobject]@{ status = "PASS"; events = @($global:TraversalEvents) } | ConvertTo-Json -Depth 4 -Compress',
      '',
    ].join('\r\n'),
  )
  const result = invokePowerShell(harnessPath)
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return result
}

function invokeExternalCaddyQuiescedStateHarness(scenario) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-quiesced-'))
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const productionPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  fs.writeFileSync(
    harnessPath,
    [
      `$productionPath = ${quotePowerShell(productionPath)}`,
      `$scenario = ${quotePowerShell(scenario)}`,
      '$tokens = $null; $errors = $null',
      '$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$errors)',
      "if ($errors.Count -ne 0) { throw 'production binding script does not parse' }",
      "$functionAst = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-ExternalCaddyQuiescedState' }, $true))",
      "if ($functionAst.Count -ne 1) { throw 'quiesced-state function extraction failed' }",
      'Invoke-Expression $functionAst[0].Extent.Text',
      "$proxySid = 'S-1-5-21-111-222-333-444'",
      "$imagePath = 'D:\\trusted\\caddy.exe'",
      'function global:Get-CimInstance {',
      '  param([string]$ClassName,[string]$Filter,[string]$ErrorAction)',
      "  if ($ClassName -eq 'Win32_Service') { if ($scenario -eq 'SCM_RUNNING') { return [pscustomobject]@{ State='Running'; ProcessId=321 } }; return [pscustomobject]@{ State='Stopped'; ProcessId=0 } }",
      "  if ($ClassName -eq 'Win32_Process' -and $Filter) { if ($scenario -eq 'OWNER_QUERY_FAIL_LIVE') { return [pscustomobject]@{ ProcessId=444 } }; return @() }",
      "  if ($ClassName -eq 'Win32_Process') { if ($scenario -in @('IMAGE_PROCESS','PROXY_PROCESS','OWNER_QUERY_FAIL_LIVE')) { return [pscustomobject]@{ ProcessId=444; ExecutablePath=$(if ($scenario -eq 'IMAGE_PROCESS') { $imagePath } else { 'D:\\trusted\\other.exe' }) } }; return @() }",
      '  return @()',
      '}',
      'function global:Invoke-CimMethod {',
      '  param($InputObject,[string]$MethodName,[string]$ErrorAction)',
      "  if ($scenario -eq 'OWNER_QUERY_FAIL_LIVE') { return [pscustomobject]@{ ReturnValue=2; Sid=$null } }",
      "  return [pscustomobject]@{ ReturnValue=0; Sid=$(if ($scenario -eq 'PROXY_PROCESS') { $proxySid } else { 'S-1-5-18' }) }",
      '}',
      'function global:Get-NetTCPConnection { param([string]$State,[int[]]$LocalPort,[string]$ErrorAction) if ($scenario -eq "LISTENER") { return [pscustomobject]@{ LocalPort=80; OwningProcess=444 } }; return @() }',
      "Assert-ExternalCaddyQuiescedState 'caddy' $imagePath $proxySid",
      "[pscustomobject]@{ status = 'PASS' } | ConvertTo-Json -Compress",
      '',
    ].join('\r\n'),
  )
  const result = invokePowerShell(harnessPath)
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return result
}

function invokeExternalCaddyDirectoryIdentityHarness(targetPath) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-dir-identity-'))
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const productionPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  fs.writeFileSync(
    harnessPath,
    [
      "$ErrorActionPreference = 'Stop'",
      `$productionPath = ${quotePowerShell(productionPath)}`,
      '$tokens = $null; $errors = $null',
      '$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$errors)',
      "if ($errors.Count -ne 0) { throw 'production binding script does not parse' }",
      "$nativeAst = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Extent.Text -match \"LeanTpm\\.CaddyFileNative\" -and $node.Extent.Text -match \"Add-Type\" }, $true))",
      "if ($nativeAst.Count -ne 1) { throw 'native Caddy identity block extraction failed' }",
      'Invoke-Expression $nativeAst[0].Extent.Text',
      "$wanted = @('Get-FinalDirectoryHandlePath','Open-DirectoryIdentity')",
      '$functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $wanted }, $true))',
      "if ($functions.Count -ne 2) { throw 'directory identity function extraction failed' }",
      '$functions | Sort-Object { $wanted.IndexOf($_.Name) } | ForEach-Object { Invoke-Expression $_.Extent.Text }',
      `$identity = Open-DirectoryIdentity -Path ${quotePowerShell(targetPath)} -Label 'runtime fixture'`,
      'try { [pscustomobject]@{ status = "PASS"; finalPath = $identity.finalPath; fileAttributes = [int64]$identity.fileAttributes } | ConvertTo-Json -Compress } finally { $identity.handle.Dispose() }',
      '',
    ].join('\r\n'),
  )
  const result = invokePowerShell(harnessPath)
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return result
}

function invokeExternalCaddyRealTraversalLockHarness(rootPath) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-real-tree-lock-'))
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const productionPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
  fs.writeFileSync(
    harnessPath,
    [
      "$ErrorActionPreference = 'Stop'",
      `$productionPath = ${quotePowerShell(productionPath)}`,
      `$global:RuntimeRoot = ${quotePowerShell(rootPath)}`,
      '$tokens = $null; $errors = $null',
      '$ast = [Management.Automation.Language.Parser]::ParseFile($productionPath, [ref]$tokens, [ref]$errors)',
      "if ($errors.Count -ne 0) { throw 'production binding script does not parse' }",
      "$nativeAst = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Extent.Text -match \"LeanTpm\\.CaddyFileNative\" -and $node.Extent.Text -match \"Add-Type\" }, $true))",
      "if ($nativeAst.Count -ne 1) { throw 'native Caddy identity block extraction failed' }",
      'Invoke-Expression $nativeAst[0].Extent.Text',
      "$wanted = @('Get-FinalDirectoryHandlePath','Open-DirectoryIdentity','Assert-DirectoryIdentityStable','Assert-ProtectedRuntimeTree')",
      '$functions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $wanted }, $true))',
      "if ($functions.Count -ne 4) { throw 'runtime traversal function extraction failed' }",
      '$functions | Sort-Object { $wanted.IndexOf($_.Name) } | ForEach-Object { Invoke-Expression $_.Extent.Text }',
      '$global:PersistentSwapInjected = $false',
      '$global:LockEvents = New-Object System.Collections.Generic.List[string]',
      'function global:Assert-ProtectedRuntimeDirectory { param([string]$Path,[string]$ProxySid,[string]$Label) return [IO.Path]::GetFullPath($Path).TrimEnd("\\") }',
      'function global:Assert-RuntimeChildAcl { param([string]$Path,[string]$ProxySid,[string]$Label,[bool]$IsDirectory) }',
      'function global:Get-ChildItem {',
      '  param([string]$LiteralPath,[switch]$Force,[string]$ErrorAction)',
      '  if (-not $global:PersistentSwapInjected -and $LiteralPath -eq $global:RuntimeRoot) {',
      '    $destination = $LiteralPath + ".rename-probe"',
      '    Microsoft.PowerShell.Management\\Move-Item -LiteralPath $LiteralPath -Destination $destination -ErrorAction Stop',
      '    Microsoft.PowerShell.Management\\New-Item -ItemType Directory -Path $LiteralPath -ErrorAction Stop | Out-Null',
      '    $global:PersistentSwapInjected = $true',
      '    $global:LockEvents.Add(("PERSISTENT_SWAP:" + $LiteralPath))',
      '  }',
      '  return @(Microsoft.PowerShell.Management\\Get-ChildItem -LiteralPath $LiteralPath -Force -ErrorAction Stop)',
      '}',
      "Assert-ProtectedRuntimeTree -RootPath $global:RuntimeRoot -ProxySid 'S-1-5-21-111-222-333-444' -Label 'runtime fixture' | Out-Null",
      '[pscustomobject]@{ status = "PASS"; events = @($global:LockEvents) } | ConvertTo-Json -Depth 4 -Compress',
      '',
    ].join('\r\n'),
  )
  const result = invokePowerShell(harnessPath)
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return result
}

function invokeMySqlIntegrationHarness(
  testPattern,
  { failCreateAfterCommit = false, failDrop = false } = {},
) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-mysql-runner-'))
  const mysqlLog = path.join(temporaryRoot, 'mysql.log')
  const mavenLog = path.join(temporaryRoot, 'maven.log')
  const caPath = path.join(temporaryRoot, 'mysql-ca.pem')
  const trustStorePath = path.join(temporaryRoot, 'mysql-trust.jks')
  const mavenPath = path.join(temporaryRoot, 'mvn.ps1')
  const harnessPath = path.join(temporaryRoot, 'invoke.ps1')
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`

  fs.writeFileSync(caPath, 'test-ca')
  fs.writeFileSync(trustStorePath, 'test-trust-store')
  fs.writeFileSync(
    mavenPath,
    `Add-Content -LiteralPath ${quotePowerShell(mavenLog)} -Value ($env:LEANTPM_TEST_DB_URL + '|' + ($args -join ' '))\r\n$global:LASTEXITCODE = 0\r\n`,
  )
  fs.writeFileSync(
    harnessPath,
    [
      `$mysqlLog = ${quotePowerShell(mysqlLog)}`,
      'function global:mysql.exe {',
      "  $joined = [string]::Join(' ', [string[]]$args)",
      '  Add-Content -LiteralPath $mysqlLog -Value $joined',
      "  if ($joined -like '*SELECT @@server_uuid*') { Write-Output 'test-server-uuid' }",
      "  if ($joined -like '*SELECT COUNT(*) FROM information_schema.schemata*') { Write-Output '0' }",
      failCreateAfterCommit
        ? "  if ($joined -like '*CREATE DATABASE*') { $global:LASTEXITCODE = 24; return }"
        : '',
      failDrop
        ? "  if ($joined -like '*DROP DATABASE*') { $global:LASTEXITCODE = 23 } else { $global:LASTEXITCODE = 0 }"
        : '  $global:LASTEXITCODE = 0',
      '}',
      `& ${quotePowerShell(path.join(repositoryRoot, 'scripts', 'run-mysql-integration.ps1'))} -MySqlPassword 'test-only' -TestPattern ${quotePowerShell(testPattern)} -MavenExecutable ${quotePowerShell(mavenPath)} -BuildDirectory 'target-contract-test' -ConfirmIsolatedDatabase -ExpectedServerUuid 'test-server-uuid' -MySqlSslCaPath ${quotePowerShell(caPath)} -MySqlSslTrustStorePath ${quotePowerShell(trustStorePath)}`,
      'exit $LASTEXITCODE',
      '',
    ].join('\r\n'),
  )

  const result = invokePowerShell(harnessPath)
  const logs = {
    mysql: fs.existsSync(mysqlLog) ? fs.readFileSync(mysqlLog, 'utf8') : '',
    maven: fs.existsSync(mavenLog) ? fs.readFileSync(mavenLog, 'utf8') : '',
  }
  fs.rmSync(temporaryRoot, { recursive: true, force: true })
  return { result, logs }
}

function snapshotTree(root) {
  const snapshot = []
  const visit = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name))) {
      const fullPath = path.join(directory, entry.name)
      const relativePath = path.relative(root, fullPath).replaceAll('\\', '/')
      if (entry.isDirectory()) {
        snapshot.push({ path: `${relativePath}/`, type: 'directory' })
        visit(fullPath)
      } else if (entry.isFile()) {
        const bytes = fs.readFileSync(fullPath)
        snapshot.push({
          path: relativePath,
          type: 'file',
          size: bytes.length,
          sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
        })
      } else {
        snapshot.push({ path: relativePath, type: 'unsupported' })
      }
    }
  }
  visit(root)
  return snapshot
}

test('accepts the canonical version only when every component and schema agrees', () => {
  const result = invokePowerShell('scripts/Test-LeanTpmVersion.ps1', [
    '-RepositoryRoot', repositoryRoot,
    '-OutputFormat', 'Json',
  ])

  assert.equal(result.status, 0, combinedOutput(result))
  const report = JSON.parse(result.stdout.trim())
  assert.equal(report.status, 'PASS')
  assert.equal(report.productVersion, '1.0.1')
  assert.equal(report.appVersionCode, 101)
  assert.equal(report.minimumSupportedAppVersionCode, 101)
  assert.equal(report.appPackageName, 'com.leantpm.mobile')
  assert.equal(report.databaseSchemaVersion, 50)
  const compatibility = JSON.parse(
    fs.readFileSync(path.join(repositoryRoot, 'release', 'compatibility-matrix.json'), 'utf8'),
  )
  assert.equal(compatibility.combinations[0].appVersionCodeRange.minimum, 101)
  assert.equal(compatibility.combinations[1].status, 'BLOCKED')
  const exampleManifest = JSON.parse(
    fs.readFileSync(path.join(repositoryRoot, 'release', 'release-manifest.example.json'), 'utf8'),
  )
  assert.equal(exampleManifest.rollback.class, 'RECOVERY_REQUIRED')
  const toolchain = JSON.parse(
    fs.readFileSync(path.join(repositoryRoot, 'release', 'toolchain-lock.json'), 'utf8'),
  )
  assert.equal(toolchain.java.version, '21.0.1')
  assert.equal(
    toolchain.java.sha256,
    'b39a8fbed442349e831cd14993d7019e0ec684d76b7519dfc3239638638a234a',
  )
  assert.equal(toolchain.java.status, 'PINNED')
  assert.equal(
    toolchain.hbuilderX.compilerDigest,
    '43d8e5db34336902bc45a9a247a62bde6add4aa8831e454f275542b22916ebd9',
  )
  assert.equal(toolchain.hbuilderX.status, 'PINNED')
  const hbuilderBuild = fs.readFileSync(
    path.join(repositoryRoot, 'LeanTPM-APP', 'scripts', 'build-hbuilder.ps1'),
    'utf8',
  )
  assert.match(hbuilderBuild, /Get-LeanTpmDirectoryDigest\.ps1/)
  assert.match(hbuilderBuild, /compilerDigest/)
})

test('rejects an unsigned manifest unless the caller explicitly allows a test manifest', () => {
  const rejected = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
    '-ManifestPath', 'release/release-manifest.example.json',
    '-PackageRoot', 'release/sample-package',
  ])
  assert.notEqual(rejected.status, 0, combinedOutput(rejected))
  assert.match(combinedOutput(rejected), /signature|unsigned/i)

  const accepted = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
    '-ManifestPath', 'release/release-manifest.example.json',
    '-PackageRoot', 'release/sample-package',
    '-AllowUnsignedTestManifest',
    '-OutputFormat', 'Json',
  ])
  assert.equal(accepted.status, 0, combinedOutput(accepted))
  const report = JSON.parse(accepted.stdout.trim())
  assert.equal(report.status, 'PASS')
  assert.equal(report.artifactCount, 5)
})

test('rejects a tampered artifact and an unexpected package file', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-release-test-'))
  try {
    const packageRoot = path.join(temporaryRoot, 'package')
    fs.cpSync(path.join(repositoryRoot, 'release', 'sample-package'), packageRoot, { recursive: true })

    fs.appendFileSync(path.join(packageRoot, 'backend', 'leantpm-backend.jar'), 'tampered')
    let result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', 'release/release-manifest.example.json',
      '-PackageRoot', packageRoot,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /hash|sha-?256/i)

    fs.cpSync(path.join(repositoryRoot, 'release', 'sample-package'), packageRoot, {
      recursive: true,
      force: true,
    })
    fs.writeFileSync(path.join(packageRoot, 'unexpected.txt'), 'must be rejected')
    result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', 'release/release-manifest.example.json',
      '-PackageRoot', packageRoot,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /unexpected|extra/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('keeps the APP profile fallback aligned with the canonical version code', () => {
  const version = JSON.parse(fs.readFileSync(path.join(repositoryRoot, 'VERSION.json'), 'utf8'))
  const profile = fs.readFileSync(
    path.join(repositoryRoot, 'LeanTPM-APP', 'pages', 'profile', 'index.vue'),
    'utf8',
  )

  assert.match(profile, new RegExp(`const versionCode = ref\\(${version.appVersionCode}\\)`))
})

test('verifies restored databases using the actual core table names', () => {
  const verifier = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Test-LeanTpmRestoredApplication.ps1'),
    'utf8',
  )

  assert.match(verifier, /'flyway_schema_history', 'system_user', 'system_parameter'/)
  assert.doesNotMatch(verifier, /'sys_user'|'sys_parameter'/)
})

test('resolves the D drive host layout only as a non-executable PlanOnly contract', () => {
  const schema = JSON.parse(
    fs.readFileSync(path.join(repositoryRoot, 'release', 'host-layout.schema.json'), 'utf8'),
  )
  const example = JSON.parse(
    fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'host-layout.production.example.json'),
      'utf8',
    ),
  )
  assert.equal(schema.additionalProperties, false)
  assert.deepEqual(schema.required, [
    'schemaVersion', 'readiness', 'environmentKind', 'environmentId', 'hostId',
    'installRoot', 'dataRoot', 'volumeIdentity', 'proxy',
  ])
  assert.equal(example.readiness, 'INPUT_REQUIRED')
  assert.equal(example.installRoot, 'D:\\LeanTPM\\App')
  assert.equal(example.dataRoot, 'D:\\LeanTPM\\Runtime')
  assert.equal(example.proxy.mode, 'EXTERNAL_EXISTING')
  assert.equal(example.proxy.serviceId, 'caddy')

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-host-layout-'))
  try {
    const layoutPath = path.join(temporaryRoot, 'host-layout.json')
    const layout = {
      schemaVersion: 1,
      readiness: 'READY',
      environmentKind: 'PRODUCTION',
      environmentId: 'leantpm-production-cn',
      hostId: 'server-2022-host-id',
      installRoot: 'D:\\LeanTPM\\App',
      dataRoot: 'D:\\LeanTPM\\Runtime',
      volumeIdentity: 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
      proxy: {
        mode: 'EXTERNAL_EXISTING',
        serviceId: 'caddy',
        bindingPolicyPath: 'D:\\LeanTPM\\Runtime\\config\\external-caddy-binding.json',
        bindingPolicySha256: 'c'.repeat(64),
      },
    }
    fs.writeFileSync(layoutPath, JSON.stringify(layout))
    const digest = crypto.createHash('sha256').update(fs.readFileSync(layoutPath)).digest('hex')

    const result = invokePowerShell('deploy/windows/Resolve-LeanTpmHostLayout.ps1', [
      '-LayoutPath', layoutPath,
      '-ExpectedLayoutSha256', digest,
      '-ExpectedEnvironmentId', layout.environmentId,
      '-ExpectedHostId', layout.hostId,
      '-ExpectedInstallRoot', layout.installRoot,
      '-ExpectedDataRoot', layout.dataRoot,
      '-ExpectedVolumeIdentity', layout.volumeIdentity,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'PLAN_ONLY')
    assert.equal(report.executable, false)
    assert.equal(report.trustSource, 'CALLER_BOUND_PLAN_ONLY')
    assert.equal(report.layoutSha256, digest)
    assert.equal(report.paths.releases, 'D:\\LeanTPM\\App\\releases')
    assert.equal(report.paths.current, 'D:\\LeanTPM\\App\\current')
    assert.equal(report.paths.config, 'D:\\LeanTPM\\Runtime\\config')
    assert.equal(report.paths.uploads, 'D:\\LeanTPM\\Runtime\\data\\uploads')
    assert.equal(report.paths.pointers, 'D:\\LeanTPM\\Runtime\\pointers')
    assert.equal(report.paths.backups, 'D:\\LeanTPM\\Runtime\\backups')
    assert.equal(report.proxy.mode, 'EXTERNAL_EXISTING')
    assert.equal(report.proxy.serviceId, 'caddy')
    assert.equal(report.proxy.bindingPolicyPath, layout.proxy.bindingPolicyPath)
    assert.equal(report.proxy.bindingPolicySha256, layout.proxy.bindingPolicySha256)
    assert.equal(report.hostFilesystemVerified, false)

    for (const invalidCase of [
      { candidate: { ...layout, dataRoot: layout.installRoot } },
      { candidate: { ...layout, dataRoot: `${layout.installRoot}\\Data` } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM', dataRoot: 'D:\\LeanTPM\\Runtime' } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM\\Runtime\\App', dataRoot: 'D:\\LeanTPM\\Runtime' } },
      { candidate: {
        ...layout,
        dataRoot: 'D:\\LeanTPM\\data',
        proxy: {
          ...layout.proxy,
          bindingPolicyPath: 'D:\\LeanTPM\\data\\config\\external-caddy-binding.json',
        },
      } },
      { candidate: { ...layout, installRoot: '\\\\server\\share\\LeanTPM\\App' } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM\\App \\Release' } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM\\CON\\Release' } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM\\App:stream' } },
      { candidate: { ...layout, installRoot: 'D:\\LeanTPM\\App.\\Release' } },
      { candidate: { ...layout, schemaVersion: '1' } },
      { candidate: { ...layout, proxy: { mode: 'EXTERNAL_EXISTING', serviceId: 'LeanTPM.Proxy' } } },
      { candidate: { ...layout, environmentKind: 'NON_PRODUCTION' } },
    ]) {
      const invalid = invalidCase.candidate
      fs.writeFileSync(layoutPath, JSON.stringify(invalid))
      const invalidDigest = crypto
        .createHash('sha256')
        .update(fs.readFileSync(layoutPath))
        .digest('hex')
      const rejected = invokePowerShell('deploy/windows/Resolve-LeanTpmHostLayout.ps1', [
        '-LayoutPath', layoutPath,
        '-ExpectedLayoutSha256', invalidDigest,
        '-ExpectedEnvironmentId', layout.environmentId,
        '-ExpectedHostId', layout.hostId,
        '-ExpectedInstallRoot', invalid.installRoot,
        '-ExpectedDataRoot', invalid.dataRoot,
        '-ExpectedVolumeIdentity', invalid.volumeIdentity,
        '-PlanOnly',
      ])
      assert.notEqual(rejected.status, 0, combinedOutput(rejected))
    }

    const duplicatePropertyJson = JSON.stringify(layout).replace(
      '"schemaVersion":1',
      '"schemaVersion":1,"schemaVersion":1',
    )
    fs.writeFileSync(layoutPath, duplicatePropertyJson)
    const duplicateDigest = crypto
      .createHash('sha256')
      .update(fs.readFileSync(layoutPath))
      .digest('hex')
    const duplicateRejected = invokePowerShell('deploy/windows/Resolve-LeanTpmHostLayout.ps1', [
      '-LayoutPath', layoutPath,
      '-ExpectedLayoutSha256', duplicateDigest,
      '-ExpectedEnvironmentId', layout.environmentId,
      '-ExpectedHostId', layout.hostId,
      '-ExpectedInstallRoot', layout.installRoot,
      '-ExpectedDataRoot', layout.dataRoot,
      '-ExpectedVolumeIdentity', layout.volumeIdentity,
      '-PlanOnly',
    ])
    assert.notEqual(duplicateRejected.status, 0, combinedOutput(duplicateRejected))

    for (const escapedDuplicateJson of [
      JSON.stringify(layout).replace(
        '"schemaVersion":1',
        '"schemaVersion":2,"\\u0073chemaVersion":1',
      ),
      JSON.stringify(layout).replace(
        '"mode":"EXTERNAL_EXISTING"',
        '"mode":"MANAGED_LEANTPM_PROXY","m\\u006fde":"EXTERNAL_EXISTING"',
      ),
      JSON.stringify(layout).replace(
        '"serviceId":"caddy"',
        '"serviceId":"LeanTPM.Proxy","service\\u0049d":"caddy"',
      ),
    ]) {
      fs.writeFileSync(layoutPath, escapedDuplicateJson)
      const escapedDigest = crypto
        .createHash('sha256')
        .update(fs.readFileSync(layoutPath))
        .digest('hex')
      const escapedRejected = invokePowerShell('deploy/windows/Resolve-LeanTpmHostLayout.ps1', [
        '-LayoutPath', layoutPath,
        '-ExpectedLayoutSha256', escapedDigest,
        '-ExpectedEnvironmentId', layout.environmentId,
        '-ExpectedHostId', layout.hostId,
        '-ExpectedInstallRoot', layout.installRoot,
        '-ExpectedDataRoot', layout.dataRoot,
        '-ExpectedVolumeIdentity', layout.volumeIdentity,
        '-PlanOnly',
      ])
      assert.notEqual(escapedRejected.status, 0, combinedOutput(escapedRejected))
    }
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('derives executable layout trust only from the fixed host-owned bootstrap', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-host-bootstrap-'))
  try {
    const layout = {
      schemaVersion: 1,
      readiness: 'READY',
      environmentKind: 'PRODUCTION',
      environmentId: 'leantpm-production-cn',
      hostId: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      installRoot: 'D:\\LeanTPM\\App',
      dataRoot: 'D:\\LeanTPM\\Runtime',
      volumeIdentity: 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      proxy: {
        mode: 'EXTERNAL_EXISTING',
        serviceId: 'caddy',
        bindingPolicyPath: 'D:\\LeanTPM\\Runtime\\config\\external-caddy-binding.json',
        bindingPolicySha256: 'c'.repeat(64),
      },
    }
    fs.writeFileSync(path.join(temporaryRoot, 'host-layout.json'), JSON.stringify(layout))
    const before = snapshotTree(temporaryRoot)

    const planned = invokePowerShell('deploy/windows/Test-LeanTpmHostBootstrap.ps1', [
      '-BootstrapRoot', temporaryRoot,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(planned.status, 0, combinedOutput(planned))
    const report = JSON.parse(planned.stdout.trim())
    assert.equal(report.status, 'PLAN_ONLY')
    assert.equal(report.executable, false)
    assert.equal(report.trustSource, 'HOST_OWNED_BOOTSTRAP_PLAN_ONLY')
    assert.equal(report.productionBootstrapRoot, 'C:\\ProgramData\\LeanTPM-bootstrap')
    assert.equal(report.layout.paths.installRoot, layout.installRoot)
    assert.equal(report.layout.paths.dataRoot, layout.dataRoot)
    assert.equal(report.layout.volumeIdentity, layout.volumeIdentity)
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const executableWithCallerRoot = invokePowerShell(
      'deploy/windows/Test-LeanTpmHostBootstrap.ps1',
      ['-BootstrapRoot', temporaryRoot],
    )
    assert.notEqual(executableWithCallerRoot.status, 0, combinedOutput(executableWithCallerRoot))

    fs.writeFileSync(
      path.join(temporaryRoot, 'host-layout.json'),
      fs.readFileSync(
        path.join(repositoryRoot, 'deploy', 'windows', 'host-layout.production.example.json'),
      ),
    )
    const unresolved = invokePowerShell('deploy/windows/Test-LeanTpmHostBootstrap.ps1', [
      '-BootstrapRoot', temporaryRoot,
      '-PlanOnly',
    ])
    assert.notEqual(unresolved.status, 0, combinedOutput(unresolved))
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('keeps executable host bootstrap identity and ACL validation fail closed', () => {
  const bootstrap = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmHostBootstrap.ps1'),
    'utf8',
  )
  assert.match(bootstrap, /AllowedReadOnlySids/)
  assert.match(bootstrap, /InheritanceFlags/)
  assert.match(bootstrap, /PropagationFlags/)
  assert.match(bootstrap, /Assert-ParentChainNoUntrustedMutation/)
  assert.match(bootstrap, /DeleteSubdirectoriesAndFiles/)
  assert.match(bootstrap, /GetFinalPathNameByHandle/)
  assert.match(bootstrap, /SafeFileHandle/)
  assert.match(bootstrap, /Get-ChildItem[\s\S]{0,240}host-layout\.json/)
  assert.match(bootstrap, /Get-BytesSha256\s+\$bytes/)
  assert.match(bootstrap, /\$releaseTrustStream\s*=\s*\[IO\.File\]::Open/)
  assert.match(bootstrap, /\$proxyPolicyStream\s*=\s*\[IO\.File\]::Open/)
  assert.match(bootstrap, /Assert-ApprovedGmsaAccount/)
  assert.match(bootstrap, /serviceIdentities/)
  assert.match(bootstrap, /Untrusted principal owns a parent path/)
  assert.doesNotMatch(bootstrap, /return\s+Get-TextSha256Identity\s+\$canonical/)
  assert.doesNotMatch(bootstrap, /Get-Content\s+-LiteralPath\s+\$releaseTrustPath/)

  const streamOpen = bootstrap.indexOf('$layoutStream = [IO.File]::Open')
  const layoutAcl = bootstrap.indexOf('Assert-HostOwnedAcl $layoutPath')
  const streamDispose = bootstrap.indexOf('$layoutStream.Dispose()')
  assert.ok(streamOpen >= 0 && layoutAcl > streamOpen && streamDispose > layoutAcl,
    'layout ACL/final identity must be validated while the non-delete-sharing snapshot is open')

  const trustStreamOpen = bootstrap.indexOf('$releaseTrustStream = [IO.File]::Open')
  const trustParentCheck = bootstrap.indexOf('Assert-ParentChainNoUntrustedMutation $releaseTrustPath')
  const trustAcl = bootstrap.indexOf('Assert-HostOwnedAcl $releaseTrustPath')
  const trustStreamDispose = bootstrap.indexOf('$releaseTrustStream.Dispose()')
  assert.ok(trustStreamOpen >= 0 && trustParentCheck > trustStreamOpen &&
    trustAcl > trustParentCheck && trustStreamDispose > trustAcl,
  'release trust bytes, parent replacement resistance and ACL must share one open snapshot')

  const proxyStreamOpen = bootstrap.indexOf('$proxyPolicyStream = [IO.File]::Open')
  const proxyParentCheck = bootstrap.indexOf('Assert-ParentChainNoUntrustedMutation $proxyPolicyPath')
  const proxyAcl = bootstrap.indexOf('Assert-HostOwnedAcl $proxyPolicyPath')
  const proxyStreamDispose = bootstrap.indexOf('$proxyPolicyStream.Dispose()')
  assert.ok(proxyStreamOpen >= 0 && proxyParentCheck > proxyStreamOpen &&
    proxyAcl > proxyParentCheck && proxyStreamDispose > proxyAcl,
  'external proxy policy bytes and host ownership must share one open snapshot')
})

test('rejects manifest paths that can escape the package root', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-manifest-path-test-'))
  try {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(repositoryRoot, 'release', 'release-manifest.example.json'), 'utf8'),
    )
    manifest.artifacts[0].path = '../outside.jar'
    const manifestPath = path.join(temporaryRoot, 'manifest.json')
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))

    const result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', manifestPath,
      '-PackageRoot', 'release/sample-package',
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /path|relative|traversal/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('builds byte-for-byte deterministic release bundles and verifies the archive', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-package-test-'))
  try {
    const first = path.join(temporaryRoot, 'first.zip')
    const second = path.join(temporaryRoot, 'second.zip')
    for (const outputPath of [first, second]) {
      const result = invokePowerShell('scripts/New-ReleasePackage.ps1', [
        '-ManifestPath', 'release/release-manifest.example.json',
        '-PayloadRoot', 'release/sample-package',
        '-OutputPath', outputPath,
        '-AllowUnsignedTestManifest',
        '-OutputFormat', 'Json',
      ])
      assert.equal(result.status, 0, combinedOutput(result))
    }

    assert.deepEqual(fs.readFileSync(first), fs.readFileSync(second))

    const verified = invokePowerShell('scripts/Test-ReleasePackage.ps1', [
      '-PackagePath', first,
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(verified.status, 0, combinedOutput(verified))
    const report = JSON.parse(verified.stdout.trim())
    assert.equal(report.status, 'PASS')
    assert.equal(report.releaseId, 'leantpm-1.0.1-test.1')

    const extracted = path.join(temporaryRoot, 'verified-extraction')
    const extractedResult = invokePowerShell('scripts/Test-ReleasePackage.ps1', [
      '-PackagePath', first,
      '-AllowUnsignedTestManifest',
      '-ExtractTo', extracted,
      '-OutputFormat', 'Json',
    ])
    assert.equal(extractedResult.status, 0, combinedOutput(extractedResult))
    assert.equal(fs.existsSync(path.join(extracted, 'release-manifest.json')), true)
    assert.equal(fs.existsSync(path.join(extracted, 'payload', 'backend', 'leantpm-backend.jar')), true)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('defines production-safe Flyway and health probe contracts', () => {
  const application = fs.readFileSync(
    path.join(repositoryRoot, 'backend', 'src', 'main', 'resources', 'application.yml'),
    'utf8',
  )
  assert.match(application, /enabled:\s*\$\{LEANTPM_FLYWAY_ENABLED:true\}/)
  assert.match(application, /baseline-on-migrate:\s*\$\{LEANTPM_FLYWAY_BASELINE_ON_MIGRATE:false\}/)
  assert.match(application, /probes:\s*\n\s+enabled:\s*true/)
  assert.match(application, /liveness:[\s\S]*include:\s*livenessState,ping/)
  assert.match(application, /readiness:[\s\S]*include:\s*readinessState,db,diskSpace/)
  assert.match(application, /show-details:\s*never/)

  const security = fs.readFileSync(
    path.join(repositoryRoot, 'backend', 'src', 'main', 'java', 'com', 'leantpm', 'security', 'SecurityConfig.java'),
    'utf8',
  )
  assert.match(security, /requestMatchers\("\/actuator\/health\/\*\*"\)\.permitAll\(\)/)
  const starter = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmBackend.ps1'),
    'utf8',
  )
  assert.match(starter, /ProtectedData.*Unprotect/s)
  assert.match(starter, /secret-references\.json/)
  assert.match(starter, /LEANTPM_DB_PASSWORD[\s\S]*LEANTPM_JWT_SECRET/)
  const envTemplate = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'leantpm.env.production.example'),
    'utf8',
  )
  assert.doesNotMatch(envTemplate, /^LEANTPM_(DB_PASSWORD|JWT_SECRET)=/m)
})

test('keeps database passwords out of process arguments and requires an isolated-write confirmation', () => {
  const integration = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'run-mysql-integration.ps1'),
    'utf8',
  )
  assert.doesNotMatch(integration, /MySqlPassword\s*=\s*['"][^'"]+['"]/)
  assert.doesNotMatch(integration, /-p\$MySqlPassword/)
  assert.match(integration, /ConfirmIsolatedDatabase/)
  assert.match(integration, /MYSQL_PWD/)

  const verification = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-release.ps1'),
    'utf8',
  )
  assert.doesNotMatch(verification, /V1-V32/)
  assert.match(verification, /Test-LeanTpmVersion\.ps1/)
  assert.match(verification, /LeanTPM-APP/)
  assert.match(verification, /Frontend clean dependency install[\s\S]*npm\.cmd ci/)
  assert.match(verification, /TrustedManifestCertificateThumbprint/)
  assert.match(verification, /Test-LeanTpmAndroidPackage\.ps1/)
  assert.match(verification, /Canonical LeanTPM-APP package/)
  assert.match(verification, /payload[\\/]app[\\/]LeanTPM\.apk/)
  assert.match(verification, /NOT_RELEASEABLE/)
})

test('produces a side-effect-free Windows Service installation plan for a pinned wrapper', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-service-plan-'))
  try {
    const wrapper = path.join(temporaryRoot, 'WinSW-x64.exe')
    const installRoot = path.join(temporaryRoot, 'Program Files', 'LeanTPM')
    const dataRoot = path.join(temporaryRoot, 'ProgramData', 'LeanTPM')
    fs.writeFileSync(wrapper, 'synthetic WinSW fixture')
    fs.mkdirSync(installRoot, { recursive: true })
    fs.mkdirSync(dataRoot, { recursive: true })

    const hash = spawnSync(
      powershell,
      ['-NoProfile', '-Command', `(Get-FileHash -Algorithm SHA256 -LiteralPath '${wrapper.replaceAll("'", "''")}').Hash`],
      { encoding: 'utf8' },
    ).stdout.trim()
    const result = invokePowerShell('deploy/windows/Install-LeanTpmWindowsService.ps1', [
      '-WrapperPath', wrapper,
      '-ExpectedWrapperSha256', hash,
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowNonProductionRoots',
      '-AllowUnpinnedTestWrapper',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'PLAN')
    assert.equal(report.serviceId, 'LeanTPM.Backend')
    assert.equal(report.account, 'NT AUTHORITY\\LocalService')
    assert.equal(fs.readdirSync(installRoot).length, 0)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('builds backup, deployment and rollback plans without touching a database or service', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-operations-plan-'))
  try {
    const attachments = path.join(temporaryRoot, 'uploads')
    const config = path.join(temporaryRoot, 'config')
    const pointers = path.join(temporaryRoot, 'pointers')
    const backups = path.join(temporaryRoot, 'backups')
    const install = path.join(temporaryRoot, 'install')
    fs.mkdirSync(attachments, { recursive: true })
    fs.mkdirSync(config, { recursive: true })
    fs.mkdirSync(pointers, { recursive: true })
    fs.mkdirSync(backups, { recursive: true })
    fs.mkdirSync(install, { recursive: true })
    fs.writeFileSync(path.join(attachments, 'fixture.txt'), 'synthetic attachment')
    fs.copyFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'effective-config.production.example.json'),
      path.join(config, 'effective-config.json'),
    )
    const isolatedEffectiveConfig = JSON.parse(fs.readFileSync(
      path.join(config, 'effective-config.json'), 'utf8',
    ))
    isolatedEffectiveConfig.database.url =
      'jdbc:mysql://127.0.0.1:3306/leantpm_test?sslMode=VERIFY_IDENTITY'
    isolatedEffectiveConfig.uploadDir = attachments
    fs.writeFileSync(
      path.join(config, 'effective-config.json'),
      JSON.stringify(isolatedEffectiveConfig, null, 2),
    )
    const isolatedRuntimeEnvironment = fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'leantpm.env.production.example'),
      'utf8',
    ).replace(
      isolatedEffectiveConfig.database.url.replace(
        '127.0.0.1:3306/leantpm_test?sslMode=VERIFY_IDENTITY',
        'mysql.internal:3306/leantpm?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=VERIFY_IDENTITY',
      ),
      isolatedEffectiveConfig.database.url,
    ).replace(
      'LEANTPM_UPLOAD_DIR=D:\\LeanTPM\\Runtime\\data\\uploads',
      `LEANTPM_UPLOAD_DIR=${attachments}`,
    )
    fs.writeFileSync(path.join(config, 'leantpm.env'), isolatedRuntimeEnvironment)
    fs.writeFileSync(path.join(config, 'secret-references.json'), JSON.stringify({
      LEANTPM_DB_PASSWORD: 'dpapi://LEANTPM_DB_PASSWORD.bin',
      LEANTPM_JWT_SECRET: 'dpapi://LEANTPM_JWT_SECRET.bin',
    }))
    fs.writeFileSync(path.join(config, 'backup-protection.json'), JSON.stringify({
      schemaVersion: 1,
      encryptionAtRest: 'BITLOCKER_OR_ENTERPRISE_STORAGE',
      storageIsolation: true,
      offHostCopyRequired: true,
      retentionDays: 30,
    }))
    fs.writeFileSync(path.join(pointers, 'current-release.json'), '{"releaseId":"leantpm-1.0.0"}')

    let beforePlan = snapshotTree(temporaryRoot)
    let result = invokePowerShell('scripts/New-LeanTpmBackupSet.ps1', [
      '-Database', 'leantpm_test',
      '-ConfirmDatabase', 'leantpm_test',
      '-AttachmentRoot', attachments,
      '-ConfigPath', path.join(config, 'effective-config.json'),
      '-RuntimeEnvironmentPath', path.join(config, 'leantpm.env'),
      '-SecretReferencePath', path.join(config, 'secret-references.json'),
      '-PointerRoot', pointers,
      '-ProtectionProfilePath', path.join(config, 'backup-protection.json'),
      '-ReleaseManifestPath', 'release/release-manifest.example.json',
      '-BackupRoot', backups,
      '-EnvironmentName', 'isolated-test',
      '-EnvironmentKind', 'NON_PRODUCTION',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.deepEqual(snapshotTree(temporaryRoot), beforePlan, 'backup PlanOnly changed the target tree')
    const backupPlan = JSON.parse(result.stdout.trim())
    assert.equal(backupPlan.status, 'PLAN')
    assert.deepEqual(backupPlan.components, [
      'database', 'attachments', 'config', 'secret-references', 'release', 'pointers', 'protection',
    ])

    fs.writeFileSync(path.join(config, 'effective-config.json'), JSON.stringify({
      schemaVersion: 1,
      apiKey: 'neutral-key-name-must-still-be-rejected',
    }))
    result = invokePowerShell('scripts/New-LeanTpmBackupSet.ps1', [
      '-Database', 'leantpm_test',
      '-ConfirmDatabase', 'leantpm_test',
      '-AttachmentRoot', attachments,
      '-ConfigPath', path.join(config, 'effective-config.json'),
      '-RuntimeEnvironmentPath', path.join(config, 'leantpm.env'),
      '-SecretReferencePath', path.join(config, 'secret-references.json'),
      '-PointerRoot', pointers,
      '-ProtectionProfilePath', path.join(config, 'backup-protection.json'),
      '-ReleaseManifestPath', 'release/release-manifest.example.json',
      '-BackupRoot', backups,
      '-EnvironmentName', 'isolated-test',
      '-EnvironmentKind', 'NON_PRODUCTION',
      '-PlanOnly',
    ])
    assert.notEqual(result.status, 0, 'unknown effective config keys must be rejected')
    fs.copyFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'effective-config.production.example.json'),
      path.join(config, 'effective-config.json'),
    )

    const packagePath = path.join(temporaryRoot, 'release.zip')
    result = invokePowerShell('scripts/New-ReleasePackage.ps1', [
      '-ManifestPath', 'release/release-manifest.example.json',
      '-PayloadRoot', 'release/sample-package',
      '-OutputPath', packagePath,
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))

    const deploymentPlanPath = path.join(temporaryRoot, 'deployment-plan.json')
    fs.writeFileSync(deploymentPlanPath, JSON.stringify({
      schemaVersion: 1,
      environmentName: 'isolated-test',
      environmentKind: 'NON_PRODUCTION',
      releaseId: 'leantpm-1.0.1-test.1',
      approvalId: 'approval-test-001',
      packagePath,
      installRoot: install,
      dataRoot: temporaryRoot,
      backupRoot: backups,
      serviceId: 'LeanTPM.Backend',
      healthUri: 'http://127.0.0.1:18080/actuator/health/readiness',
      runtimeConfigId: 'leantpm-1.0.1-test.1-config',
      runtimeConfigSha256: 'a'.repeat(64),
    }, null, 2))
    beforePlan = snapshotTree(temporaryRoot)
    result = invokePowerShell('scripts/Invoke-LeanTpmDeployment.ps1', [
      '-PlanPath', deploymentPlanPath,
      '-PlanOnly',
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.deepEqual(snapshotTree(temporaryRoot), beforePlan, 'deployment PlanOnly changed the target tree')
    const deployment = JSON.parse(result.stdout.trim())
    assert.equal(deployment.status, 'PLAN')
    assert.deepEqual(deployment.steps, [
      'LOCK', 'PREFLIGHT', 'VERIFY_PACKAGE', 'STOP_SERVICE', 'BACKUP', 'STAGE', 'MIGRATE',
      'SWITCH_POINTER', 'START_SERVICE', 'VERIFY_READINESS', 'AUDIT',
    ])

    const rollbackPlanPath = path.join(temporaryRoot, 'rollback-plan.json')
    fs.writeFileSync(rollbackPlanPath, JSON.stringify({
      schemaVersion: 1,
      environmentName: 'isolated-test',
      environmentKind: 'NON_PRODUCTION',
      rollbackId: 'rollback-test-001',
      approvalId: 'approval-test-002',
      failedReleaseId: 'leantpm-1.0.1-test.1',
      targetReleaseId: 'leantpm-1.0.0',
      installRoot: install,
      dataRoot: temporaryRoot,
      serviceId: 'LeanTPM.Backend',
      rollbackClass: 'APPLICATION_ONLY',
      healthUri: 'http://127.0.0.1:18080/actuator/health/readiness',
      targetRuntimeConfigId: 'leantpm-1.0.0-config',
      targetRuntimeConfigSha256: 'b'.repeat(64),
    }, null, 2))
    beforePlan = snapshotTree(temporaryRoot)
    result = invokePowerShell('scripts/Invoke-LeanTpmRollback.ps1', [
      '-PlanPath', rollbackPlanPath,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.deepEqual(snapshotTree(temporaryRoot), beforePlan, 'rollback PlanOnly changed the target tree')
    const rollback = JSON.parse(result.stdout.trim())
    assert.equal(rollback.status, 'PLAN')
    assert.equal(rollback.rollbackClass, 'APPLICATION_ONLY')

    const backupSource = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'New-LeanTpmBackupSet.ps1'), 'utf8',
    )
    assert.match(backupSource, /ConfirmApplicationWritesQuiesced/)
    assert.match(backupSource, /backup-manifest\.p7s/)
    assert.match(backupSource, /SignedCms/)
    const backupVerifierSource = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'Test-LeanTpmBackupSet.ps1'), 'utf8',
    )
    assert.match(backupVerifierSource, /TrustedSignerThumbprint/)
    assert.match(backupVerifierSource, /CheckSignature/)
    const deploymentSource = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
    )
    assert.doesNotMatch(deploymentSource, /Expand-Archive/)
    assert.match(deploymentSource, /FileMode\]::OpenOrCreate/)
    assert.match(deploymentSource, /SetLength\(0\)/)
    const rollbackSource = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmRollback.ps1'), 'utf8',
    )
    assert.match(rollbackSource, /FileMode\]::OpenOrCreate/)
    assert.match(rollbackSource, /requestedBy[\s\S]*approvedBy/)
    assert.match(rollbackSource, /failedManifest\.rollback\.class/)
    assert.doesNotMatch(
      rollbackSource,
      /targetManifest\.rollback\.class[\s\S]*plan\.rollbackClass/,
    )
    assert.match(deploymentSource, /Test-LeanTpmAuditLog\.ps1/)
    assert.match(rollbackSource, /Test-LeanTpmAuditLog\.ps1/)
    const starterSource = fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmBackend.ps1'), 'utf8',
    )
    assert.match(starterSource, /payload\\backend\\leantpm-backend\.jar/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('validates every deployment audit hash before accepting the append-only chain', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-audit-chain-'))
  try {
    const auditPath = path.join(temporaryRoot, 'deployments.jsonl')
    let previousHash = '0'.repeat(64)
    const lines = []
    for (const [index, status] of ['PREFLIGHTED', 'SUCCEEDED'].entries()) {
      const event = {
        schemaVersion: 1,
        timestampUtc: `2026-08-08T04:00:0${index}Z`,
        correlationId: 'approval-test-001',
        environmentName: 'isolated-test',
        releaseId: 'leantpm-1.0.1-test.1',
        packageSha256: 'a'.repeat(64),
        status,
        actor: 'TEST\\release-operator',
        message: status,
        previousHash,
      }
      const hash = crypto.createHash('sha256').update(JSON.stringify(event)).digest('hex')
      lines.push(JSON.stringify({ ...event, hash }))
      previousHash = hash
    }
    fs.writeFileSync(auditPath, `${lines.join('\n')}\n`)

    let result = invokePowerShell('scripts/Test-LeanTpmAuditLog.ps1', [
      '-AuditPath', auditPath,
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.equal(JSON.parse(result.stdout.trim()).eventCount, 2)

    fs.writeFileSync(auditPath, `${lines[0].replace('PREFLIGHTED', 'TAMPERED')}\n${lines[1]}\n`)
    result = invokePowerShell('scripts/Test-LeanTpmAuditLog.ps1', [
      '-AuditPath', auditPath,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(result.status, 0, 'tampered audit history must be rejected')
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('generates a canonical manifest from payload bytes instead of hand-maintained hashes', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-manifest-generator-'))
  try {
    const output = path.join(temporaryRoot, 'release-manifest.json')
    const baselinePath = path.join(temporaryRoot, 'baseline.json')
    fs.writeFileSync(baselinePath, JSON.stringify({
      schemaVersion: 1,
      status: 'PASS',
      commit: '2185536ea9da0a323b27f53dcf849b818ea19069',
      dirty: false,
      sourceDateEpoch: 1786161320,
      fileTreeSha256: 'a'.repeat(64),
    }))
    const result = invokePowerShell('scripts/New-ReleaseManifest.ps1', [
      '-PayloadRoot', 'release/sample-package',
      '-OutputPath', output,
      '-ReleaseId', 'leantpm-1.0.1-generated-test',
      '-ReleaseTier', 'TEST',
      '-SourceCommit', '2185536ea9da0a323b27f53dcf849b818ea19069',
      '-BaselinePath', baselinePath,
      '-AllowSyntheticTestBaseline',
      '-CreatedAtUtc', '2026-08-08T04:00:00Z',
      '-SchemaFrom', '50',
      '-DatabasePhase', 'NONE',
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const manifest = JSON.parse(fs.readFileSync(output, 'utf8'))
    assert.equal(manifest.productVersion, '1.0.1')
    assert.equal(manifest.components.database.schemaTo, 50)
    assert.equal(manifest.artifacts.length, 5)
    for (const artifact of manifest.artifacts) {
      const bytes = fs.readFileSync(path.join(repositoryRoot, 'release', 'sample-package', artifact.path))
      assert.equal(artifact.size, bytes.length)
      assert.equal(artifact.sha256, crypto.createHash('sha256').update(bytes).digest('hex'))
    }
    const manifestGenerator = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'New-ReleaseManifest.ps1'),
      'utf8',
    )
    assert.match(manifestGenerator, /AWAITING_SIGNATURE/)
    assert.match(manifestGenerator, /SigningCertificateThumbprint/)
    const signer = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'New-LeanTpmDetachedCmsSignature.ps1'),
      'utf8',
    )
    assert.match(signer, /SignedCms/)
    assert.match(signer, /ConfirmSigning/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('plans a fixed Windows Service action and refuses arbitrary service identifiers', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-service-action-'))
  try {
    const result = invokePowerShell('deploy/windows/Invoke-LeanTpmWindowsService.ps1', [
      '-Action', 'Restart',
      '-InstallRoot', temporaryRoot,
      '-DataRoot', temporaryRoot,
      '-AllowNonProductionRoot',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const plan = JSON.parse(result.stdout.trim())
    assert.equal(plan.status, 'PLAN')
    assert.equal(plan.serviceId, 'LeanTPM.Backend')
    assert.deepEqual(plan.steps, ['VERIFY_SCM_BINDING', 'STOP', 'START', 'QUERY_STATUS'])

    const source = fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1'),
      'utf8',
    )
    assert.doesNotMatch(source, /\[string\]\$Service(Id|Name)/)
    const installer = fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmWindowsService.ps1'),
      'utf8',
    )
    assert.match(installer, /SignedStarterPath/)
    assert.match(installer, /Get-AuthenticodeSignature/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('plans restore only into a different isolated target after backup integrity verification', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-restore-plan-'))
  try {
    const backup = path.join(temporaryRoot, 'backup-valid')
    const restoreRoot = path.join(temporaryRoot, 'restore-target')
    const installRoot = path.join(temporaryRoot, 'install')
    const dataRoot = path.join(temporaryRoot, 'data')
    fs.mkdirSync(path.join(backup, 'database'), { recursive: true })
    fs.mkdirSync(path.join(backup, 'attachments'), { recursive: true })
    fs.mkdirSync(path.join(backup, 'config'), { recursive: true })
    fs.mkdirSync(path.join(backup, 'release'), { recursive: true })
    fs.mkdirSync(restoreRoot)
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    fs.writeFileSync(path.join(backup, 'database', 'database.sql'), '-- synthetic isolated restore fixture\n')
    fs.writeFileSync(path.join(backup, 'attachments', 'fixture.txt'), 'synthetic attachment')
    fs.copyFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'effective-config.production.example.json'),
      path.join(backup, 'config', 'effective-config.json'),
    )
    fs.writeFileSync(path.join(backup, 'config', 'secret-references.json'), JSON.stringify({
      LEANTPM_DB_PASSWORD: 'dpapi://LEANTPM_DB_PASSWORD.bin',
      LEANTPM_JWT_SECRET: 'dpapi://LEANTPM_JWT_SECRET.bin',
    }))
    fs.copyFileSync(
      path.join(repositoryRoot, 'release', 'release-manifest.example.json'),
      path.join(backup, 'release', 'release-manifest.json'),
    )
    const files = []
    for (const relative of [
      'attachments/fixture.txt', 'config/effective-config.json',
      'config/secret-references.json', 'database/database.sql',
      'release/release-manifest.json',
    ]) {
      const bytes = fs.readFileSync(path.join(backup, relative))
      files.push({
        path: relative,
        size: bytes.length,
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      })
    }
    fs.writeFileSync(path.join(backup, 'backup-manifest.json'), JSON.stringify({
      schemaVersion: 1,
      backupId: 'backup-synthetic-001',
      status: 'VALID',
      environmentName: 'isolated-test',
      environmentKind: 'NON_PRODUCTION',
      database: { name: 'leantpm_test_source' },
      releaseId: 'leantpm-1.0.1-test.1',
      databaseSchemaVersion: 50,
      files,
    }, null, 2))

    const result = invokePowerShell('scripts/Restore-LeanTpmBackupSet.ps1', [
      '-BackupSetPath', backup,
      '-ExpectedSourceDatabase', 'leantpm_test_source',
      '-TargetDatabase', 'leantpm_test_restore_001',
      '-ConfirmTargetDatabase', 'leantpm_test_restore_001',
      '-RestoreRoot', restoreRoot,
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowNonProductionHostRoots',
      '-EnvironmentKind', 'NON_PRODUCTION',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const plan = JSON.parse(result.stdout.trim())
    assert.equal(plan.status, 'PLAN')
    assert.equal(plan.targetDatabase, 'leantpm_test_restore_001')
    assert.deepEqual(plan.steps, [
      'VERIFY_BACKUP', 'VERIFY_EMPTY_TARGET', 'RESTORE_DATABASE', 'RESTORE_ATTACHMENTS',
      'RESTORE_CONFIG_REFERENCES', 'VERIFY_FLYWAY', 'VERIFY_APPLICATION', 'AUDIT',
    ])
    assert.deepEqual(fs.readdirSync(restoreRoot), [])

    const rejected = invokePowerShell('scripts/Restore-LeanTpmBackupSet.ps1', [
      '-BackupSetPath', backup,
      '-ExpectedSourceDatabase', 'leantpm_test_source',
      '-TargetDatabase', 'leantpm_test_source',
      '-ConfirmTargetDatabase', 'leantpm_test_source',
      '-RestoreRoot', restoreRoot,
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowNonProductionHostRoots',
      '-EnvironmentKind', 'NON_PRODUCTION',
      '-PlanOnly',
    ])
    assert.notEqual(rejected.status, 0, combinedOutput(rejected))
    assert.match(combinedOutput(rejected), /different|new target/i)
    const restoreSource = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'Restore-LeanTpmBackupSet.ps1'),
      'utf8',
    )
    assert.match(restoreSource, /RESTORE_INVALID\.json/)
    assert.match(restoreSource, /RESTORE_FAILED/)
    assert.match(restoreSource, /BackupTrustConfigPath/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('generates a contiguous checksum-locked migration catalog for an explicit upgrade range', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-migration-catalog-'))
  try {
    const output = path.join(temporaryRoot, 'migrations.json')
    const classificationPath = path.join(temporaryRoot, 'classification.json')
    fs.writeFileSync(classificationPath, JSON.stringify({
      schemaVersion: 1,
      entries: Array.from({ length: 18 }, (_, index) => ({
        version: index + 33,
        phase: 'EXPAND',
        backwardCompatible: true,
        requiresDowntime: false,
        reviewStatus: 'APPROVED',
        reviewedBy: 'synthetic-test-reviewer',
        approvedAtUtc: '2026-08-08T04:00:00Z',
        evidence: 'synthetic unit-test classification only',
      })),
    }, null, 2))
    let result = invokePowerShell('scripts/New-MigrationCatalog.ps1', [
      '-MigrationRoot', 'backend/src/main/resources/db/migration',
      '-SchemaFrom', '32',
      '-OutputPath', output,
      '-ClassificationPath', classificationPath,
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const catalog = JSON.parse(fs.readFileSync(output, 'utf8'))
    assert.equal(catalog.schemaFrom, 32)
    assert.equal(catalog.schemaTo, 50)
    assert.deepEqual(catalog.migrations.map((migration) => migration.version),
      Array.from({ length: 18 }, (_, index) => index + 33))
    for (const migration of catalog.migrations) {
      const bytes = fs.readFileSync(path.join(
        repositoryRoot, 'backend', 'src', 'main', 'resources', 'db', 'migration', migration.script,
      ))
      assert.equal(migration.sha256, crypto.createHash('sha256').update(bytes).digest('hex'))
      assert.equal(migration.phase, 'EXPAND')
    }

    result = invokePowerShell('scripts/Test-MigrationCatalog.ps1', [
      '-CatalogPath', output,
      '-MigrationRoot', 'backend/src/main/resources/db/migration',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.equal(JSON.parse(result.stdout.trim()).status, 'PASS')

    const missingApproval = invokePowerShell('scripts/New-MigrationCatalog.ps1', [
      '-MigrationRoot', 'backend/src/main/resources/db/migration',
      '-SchemaFrom', '47',
      '-OutputPath', path.join(temporaryRoot, 'unapproved.json'),
    ])
    assert.notEqual(missingApproval.status, 0, combinedOutput(missingApproval))
    assert.match(combinedOutput(missingApproval), /classification|evidence|review/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('rejects a self-consistent artifact hash when the migration catalog contradicts the manifest', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-migration-contract-'))
  try {
    const payload = path.join(temporaryRoot, 'payload')
    fs.cpSync(path.join(repositoryRoot, 'release', 'sample-package'), payload, { recursive: true })
    const manifestPath = path.join(temporaryRoot, 'release-manifest.json')
    const manifest = JSON.parse(fs.readFileSync(
      path.join(repositoryRoot, 'release', 'release-manifest.example.json'), 'utf8',
    ))
    const catalogPath = path.join(payload, 'database', 'migrations.json')
    const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'))
    catalog.schemaFrom = 47
    fs.writeFileSync(catalogPath, JSON.stringify(catalog, null, 2))
    const bytes = fs.readFileSync(catalogPath)
    const artifact = manifest.artifacts.find((candidate) => candidate.path === 'database/migrations.json')
    artifact.size = bytes.length
    artifact.sha256 = crypto.createHash('sha256').update(bytes).digest('hex')
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))

    const result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', manifestPath,
      '-PackageRoot', payload,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /migration|schemaFrom|catalog/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('records a clean trusted Git baseline and rejects a dirty source tree', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-baseline-'))
  try {
    const repository = path.join(temporaryRoot, 'repository')
    fs.mkdirSync(repository)
    for (const args of [
      ['init', '--quiet'],
      ['config', 'user.email', 'test@example.invalid'],
      ['config', 'user.name', 'LeanTPM Test'],
    ]) {
      const result = spawnSync('git', args, { cwd: repository, encoding: 'utf8' })
      assert.equal(result.status, 0, combinedOutput(result))
    }
    fs.writeFileSync(path.join(repository, 'tracked.txt'), 'trusted fixture\n')
    let result = spawnSync('git', ['add', 'tracked.txt'], { cwd: repository, encoding: 'utf8' })
    assert.equal(result.status, 0, combinedOutput(result))
    result = spawnSync('git', ['commit', '--quiet', '-m', 'trusted fixture'], {
      cwd: repository, encoding: 'utf8',
    })
    assert.equal(result.status, 0, combinedOutput(result))
    const commit = spawnSync('git', ['rev-parse', 'HEAD'], {
      cwd: repository, encoding: 'utf8',
    }).stdout.trim()

    const output = path.join(temporaryRoot, 'baseline.json')
    result = invokePowerShell('scripts/Get-LeanTpmReleaseBaseline.ps1', [
      '-RepositoryRoot', repository,
      '-ExpectedCommit', commit,
      '-OutputPath', output,
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const baseline = JSON.parse(fs.readFileSync(output, 'utf8'))
    assert.equal(baseline.status, 'PASS')
    assert.equal(baseline.commit, commit)
    assert.equal(baseline.dirty, false)
    assert.match(baseline.fileTreeSha256, /^[0-9a-f]{64}$/)

    fs.writeFileSync(path.join(repository, 'untracked.txt'), 'must block release\n')
    result = invokePowerShell('scripts/Get-LeanTpmReleaseBaseline.ps1', [
      '-RepositoryRoot', repository,
      '-ExpectedCommit', commit,
      '-OutputPath', path.join(temporaryRoot, 'dirty.json'),
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /dirty|untracked|clean/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('enforces the complete manifest contract and rejects unlisted ZIP root entries', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-strict-contract-'))
  try {
    const payload = path.join(temporaryRoot, 'payload')
    fs.cpSync(path.join(repositoryRoot, 'release', 'sample-package'), payload, { recursive: true })
    const invalidManifestPath = path.join(temporaryRoot, 'invalid-manifest.json')
    const invalidManifest = JSON.parse(fs.readFileSync(
      path.join(repositoryRoot, 'release', 'release-manifest.example.json'), 'utf8',
    ))
    invalidManifest.unexpectedRootProperty = true
    invalidManifest.rollback = {}
    invalidManifest.artifacts[0].component = 'invalid-component'
    fs.writeFileSync(invalidManifestPath, JSON.stringify(invalidManifest, null, 2))
    let result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', invalidManifestPath,
      '-PackageRoot', payload,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /unexpected|rollback|component|schema/i)

    const reservedManifest = JSON.parse(fs.readFileSync(
      path.join(repositoryRoot, 'release', 'release-manifest.example.json'), 'utf8',
    ))
    reservedManifest.releaseId = 'con'
    fs.writeFileSync(invalidManifestPath, JSON.stringify(reservedManifest, null, 2))
    result = invokePowerShell('scripts/Test-ReleaseManifest.ps1', [
      '-ManifestPath', invalidManifestPath,
      '-PackageRoot', payload,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, 'Windows reserved release IDs must be rejected')

    const cleanZip = path.join(temporaryRoot, 'clean.zip')
    result = invokePowerShell('scripts/New-ReleasePackage.ps1', [
      '-ManifestPath', 'release/release-manifest.example.json',
      '-PayloadRoot', 'release/sample-package',
      '-OutputPath', cleanZip,
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const poisonedZip = path.join(temporaryRoot, 'poisoned.zip')
    fs.copyFileSync(cleanZip, poisonedZip)
    const escapedZip = poisonedZip.replaceAll("'", "''")
    result = spawnSync(powershell, ['-NoProfile', '-Command', [
      'Add-Type -AssemblyName System.IO.Compression',
      'Add-Type -AssemblyName System.IO.Compression.FileSystem',
      `$archive=[System.IO.Compression.ZipFile]::Open('${escapedZip}',[System.IO.Compression.ZipArchiveMode]::Update)`,
      "$entry=$archive.CreateEntry('root-extra.txt')",
      '$writer=[System.IO.StreamWriter]::new($entry.Open())',
      "$writer.Write('unlisted root entry')",
      '$writer.Dispose()',
      '$archive.Dispose()',
    ].join('; ')], { encoding: 'utf8' })
    assert.equal(result.status, 0, combinedOutput(result))
    result = invokePowerShell('scripts/Test-ReleasePackage.ps1', [
      '-PackagePath', poisonedZip,
      '-AllowUnsignedTestManifest',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /unexpected|root|entry|allow/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('switches the Windows Web current junction only between contained immutable releases', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-junction-switch-'))
  try {
    const installRoot = path.join(temporaryRoot, 'Program Files', 'LeanTPM')
    const dataRoot = path.join(temporaryRoot, 'ProgramData', 'LeanTPM')
    const first = path.join(installRoot, 'releases', 'leantpm-1.0.0')
    const second = path.join(installRoot, 'releases', 'leantpm-1.0.1-test.1')
    fs.mkdirSync(path.join(first, 'payload', 'web'), { recursive: true })
    fs.mkdirSync(path.join(second, 'payload', 'web'), { recursive: true })
    fs.mkdirSync(dataRoot, { recursive: true })
    const uncoordinated = invokePowerShell('deploy/windows/Set-LeanTpmCurrentJunction.ps1', [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-TargetReleaseId', 'leantpm-1.0.0',
      '-AllowNonProductionRoot',
    ])
    assert.notEqual(uncoordinated.status, 0, combinedOutput(uncoordinated))
    for (const releaseId of ['leantpm-1.0.0', 'leantpm-1.0.1-test.1']) {
      const result = invokePowerShell('deploy/windows/Set-LeanTpmCurrentJunction.ps1', [
        '-InstallRoot', installRoot,
        '-DataRoot', dataRoot,
        '-TargetReleaseId', releaseId,
        '-AllowNonProductionRoot',
        '-AllowUncoordinatedNonProductionSwitch',
        '-OutputFormat', 'Json',
      ])
      assert.equal(result.status, 0, combinedOutput(result))
    }
    const current = fs.realpathSync(path.join(installRoot, 'current'))
    assert.equal(current.toLowerCase(), second.toLowerCase())

    const rejected = invokePowerShell('deploy/windows/Set-LeanTpmCurrentJunction.ps1', [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-TargetReleaseId', '..',
      '-AllowNonProductionRoot',
    ])
    assert.notEqual(rejected.status, 0, combinedOutput(rejected))
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('binds production approvals to the exact immutable plan bytes that are executed', () => {
  const approval = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Test-LeanTpmReleaseApproval.ps1'), 'utf8',
  )
  assert.match(
    approval,
    /\$planBytes\s*=\s*\[System\.IO\.File\]::ReadAllBytes\(\$planFile\)/,
    'approval verification must snapshot the plan bytes once before parsing or checking either signer',
  )
  assert.equal(
    (approval.match(/ReadAllBytes\(\$planFile\)/g) ?? []).length,
    1,
    'both detached signatures and the digest must consume the same plan byte snapshot',
  )

  for (const script of ['Invoke-LeanTpmDeployment.ps1', 'Invoke-LeanTpmRollback.ps1']) {
    const source = fs.readFileSync(path.join(repositoryRoot, 'scripts', script), 'utf8')
    assert.match(source, /\$loadedPlanSha256\s*=/, `${script} must digest its loaded plan bytes`)
    assert.match(
      source,
      /approvalReport\.planSha256[\s\S]{0,500}loadedPlanSha256|loadedPlanSha256[\s\S]{0,500}approvalReport\.planSha256/,
      `${script} must reject approval evidence for different plan bytes`,
    )
  }
})

test('binds production approval to package identity, expiry, current state and safe compensation', () => {
  const packageVerifier = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Test-ReleasePackage.ps1'), 'utf8',
  )
  assert.match(packageVerifier, /manifestSha256\s*=/)
  assert.match(packageVerifier, /releaseTier\s*=/)

  const deployment = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
  )
  for (const field of [
    'packageSha256', 'manifestSha256', 'nonce', 'expiresAtUtc',
    'expectedCurrentReleaseId', 'expectedCurrentPackageSha256',
  ]) {
    assert.match(deployment, new RegExp(field), `production deployment must bind ${field}`)
  }
  assert.match(deployment, /releaseTier[\s\S]{0,200}PRODUCTION/)
  assert.match(deployment, /\$junctionSwitched\s*=\s*\$true/)
  assert.match(deployment, /if \(\$junctionSwitched\)/)
  assert.match(deployment, /RECOVERY_REQUIRED/)
  assert.match(deployment, /MIGRATION_IN_PROGRESS/)
  assert.match(deployment, /ACTIVATION_AUTHORIZED/)
  assert.match(deployment, /ROLLBACK_AUTHORIZED/)
  assert.match(deployment, /Join-Path \$stateDirectory 'recovery-inhibit\.json'/)
  assert.match(deployment, /FileOptions\]::WriteThrough/)
  assert.ok(
    deployment.indexOf("Write-RecoveryState 'MIGRATION_IN_PROGRESS'")
      < deployment.indexOf("'Invoke-LeanTpmMigrator.ps1'"),
    'durable recovery inhibition must precede every database migration write',
  )
  assert.ok(
    deployment.indexOf("Write-RecoveryState 'ACTIVATION_AUTHORIZED'")
      < deployment.indexOf('Invoke-BackendService Start'),
    'the exact migrated release must be durably authorized before SCM can start it',
  )
  assert.ok(
    deployment.indexOf("Write-RecoveryState 'ROLLBACK_AUTHORIZED'")
      < deployment.indexOf("'START_PREVIOUS_SERVICE'"),
    'compatible compensation must authorize only the exact previous release before restart',
  )
  assert.match(
    deployment,
    /Write-AuditEvent 'SUCCEEDED'[\s\S]{0,240}\n\s*Remove-RecoveryState/,
    'recovery inhibition may clear only after the new release is healthy and audited',
  )
  assert.match(deployment, /migration target must match/i)
  assert.doesNotMatch(
    deployment,
    /if \(\[int\]\$manifest\.components\.database\.schemaFrom[\s\S]{0,900}Invoke-LeanTpmMigrator/,
    'every deployment must validate the actual Flyway schema, including a no-change release',
  )

  const rollback = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmRollback.ps1'), 'utf8',
  )
  for (const field of ['nonce', 'expiresAtUtc', 'expectedCurrentPackageSha256']) {
    assert.match(rollback, new RegExp(field), `production rollback must bind ${field}`)
  }
  assert.match(rollback, /\$junctionSwitched\s*=\s*\$true/)
  assert.match(rollback, /if \([^\r\n)]*\$junctionSwitched\)/)
  assert.match(rollback, /Write-RecoveryState 'ROLLBACK_AUTHORIZED'/)
  assert.ok(
    rollback.indexOf("Write-RecoveryState 'ROLLBACK_AUTHORIZED'")
      < rollback.indexOf('Invoke-BackendService Start'),
    'explicit rollback must durably authorize the exact target before SCM can start it',
  )
  assert.match(
    rollback,
    /Write-RollbackAudit 'ROLLED_BACK'[\s\S]{0,240}\n\s*Remove-RecoveryState/,
    'explicit rollback may clear recovery inhibition only after readiness and audit',
  )
})

test('recovers stale lock files safely and isolates every compensation failure', () => {
  for (const script of [
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Invoke-LeanTpmRollback.ps1',
    'deploy/windows/Invoke-LeanTpmWindowsService.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, script), 'utf8')
    assert.match(source, /FileMode\]::OpenOrCreate/)
    assert.match(source, /SetLength\(0\)/)
    assert.doesNotMatch(
      source,
      /FileMode\]::CreateNew,[\s\S]{0,180}(?:deployment\.lock|\$lockPath)|(?:deployment\.lock|\$lockPath)[\s\S]{0,180}FileMode\]::CreateNew/,
      `${script} must use the OS handle, not file existence, as the active lock`,
    )
  }

  const deployment = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
  )
  assert.match(deployment, /\$startAttempted\s*=\s*\$true[\s\S]{0,160}Invoke-BackendService Start/)
  assert.match(deployment, /function Invoke-CompensationStep/)
  assert.match(deployment, /Wait-ReleaseReadiness/)
  assert.match(
    deployment,
    /Wait-ReleaseReadiness[\s\S]{0,1200}Write-AuditEvent 'FAILED'[\s\S]{0,240}Remove-RecoveryState/,
    'compatible rollback must verify the restored app before clearing recovery state',
  )
  assert.match(deployment, /COMPENSATION_FAILED/)
})

test('preflights target volumes and rejects an unexpected backend port owner before stop', () => {
  const preflightPath = path.join(
    repositoryRoot, 'scripts', 'Test-LeanTpmDeploymentPreflight.ps1',
  )
  assert.ok(fs.existsSync(preflightPath))
  const preflight = fs.readFileSync(preflightPath, 'utf8')
  assert.match(preflight, /AvailableFreeSpace/)
  assert.match(preflight, /ExpectedDatabaseBytes/)
  assert.match(preflight, /ExpectedAttachmentBytes/)
  assert.match(preflight, /Get-NetTCPConnection/)
  assert.match(preflight, /Win32_Process/)
  assert.match(preflight, /unexpected process/i)

  const deployment = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
  )
  assert.match(deployment, /plan\.capacity/)
  assert.match(deployment, /Test-LeanTpmDeploymentPreflight\.ps1/)
  assert.ok(
    deployment.indexOf('Test-LeanTpmDeploymentPreflight.ps1')
      < deployment.indexOf('Invoke-BackendService Stop'),
    'volume and port preflight must finish before service stop',
  )
})

test('switches an approved immutable runtime configuration with every release pointer', () => {
  const configVerifierPath = path.join(
    repositoryRoot, 'scripts', 'Test-LeanTpmRuntimeConfig.ps1',
  )
  assert.ok(fs.existsSync(configVerifierPath))
  const configVerifier = fs.readFileSync(configVerifierPath, 'utf8')
  assert.match(configVerifier, /Get-LeanTpmDirectoryDigest\.ps1/)
  assert.match(configVerifier, /sslMode=VERIFY_IDENTITY/)
  assert.match(configVerifier, /secret-references\.json/)
  assert.match(configVerifier, /ExpectedReleaseId/)
  assert.match(configVerifier, /ExpectedDatabaseSchemaVersion/)

  const starter = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmBackend.ps1'), 'utf8',
  )
  assert.match(starter, /current-config\.json/)
  assert.match(starter, /config\\versions/)
  assert.match(starter, /configPointer\.releaseId/)
  assert.match(starter, /configPointer\.directorySha256/)

  const deployment = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
  )
  assert.match(deployment, /runtimeConfigSha256/)
  assert.match(deployment, /Test-LeanTpmRuntimeConfig\.ps1/)
  assert.match(deployment, /\$configPointerSwitched\s*=\s*\$true/)
  assert.match(deployment, /previous-config\.json/)
  assert.match(deployment, /RESTORE_CONFIG_POINTER/)

  const rollback = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmRollback.ps1'), 'utf8',
  )
  assert.match(rollback, /current-config\.json/)
  assert.match(rollback, /previous-config\.json/)
  assert.match(rollback, /\$configPointerSwitched\s*=\s*\$true/)
  assert.match(rollback, /RESTORE_FAILED_CONFIG_POINTER/)

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-runtime-config-'))
  try {
    const dataRoot = path.join(temporaryRoot, 'data-root')
    const configId = 'leantpm-1.0.1-config-test'
    const runtimeConfigRoot = path.join(dataRoot, 'config', 'versions', configId)
    const uploadDir = path.join(dataRoot, 'data', 'uploads')
    fs.mkdirSync(runtimeConfigRoot, { recursive: true })
    fs.mkdirSync(uploadDir, { recursive: true })
    const effective = JSON.parse(fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'effective-config.production.example.json'),
      'utf8',
    ))
    effective.uploadDir = uploadDir
    fs.writeFileSync(
      path.join(runtimeConfigRoot, 'effective-config.json'), JSON.stringify(effective, null, 2),
    )
    const environment = fs.readFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'leantpm.env.production.example'), 'utf8',
    ).replace(
      'LEANTPM_UPLOAD_DIR=D:\\LeanTPM\\Runtime\\data\\uploads',
      `LEANTPM_UPLOAD_DIR=${uploadDir}`,
    )
    fs.writeFileSync(path.join(runtimeConfigRoot, 'leantpm.env'), environment)
    fs.copyFileSync(
      path.join(repositoryRoot, 'deploy', 'windows', 'secret-references.production.example.json'),
      path.join(runtimeConfigRoot, 'secret-references.json'),
    )
    const digestResult = invokePowerShell('scripts/Get-LeanTpmDirectoryDigest.ps1', [
      '-DirectoryPath', runtimeConfigRoot,
      '-OutputFormat', 'Json',
    ])
    assert.equal(digestResult.status, 0, combinedOutput(digestResult))
    const digest = JSON.parse(digestResult.stdout.trim()).digest
    const validatorArgs = [
      '-RuntimeConfigRoot', runtimeConfigRoot,
      '-DataRoot', dataRoot,
      '-ExpectedReleaseId', 'leantpm-1.0.1',
      '-ExpectedConfigId', configId,
      '-ExpectedProductVersion', '1.0.1',
      '-ExpectedDatabaseSchemaVersion', '50',
      '-ExpectedDatabaseHost', 'mysql.internal',
      '-ExpectedDatabasePort', '3306',
      '-ExpectedDatabaseName', 'leantpm',
      '-ExpectedDirectorySha256', digest,
      '-OutputFormat', 'Json',
    ]
    let result = invokePowerShell('scripts/Test-LeanTpmRuntimeConfig.ps1', validatorArgs)
    assert.equal(result.status, 0, combinedOutput(result))
    assert.equal(JSON.parse(result.stdout.trim()).directorySha256, digest)

    fs.appendFileSync(
      path.join(runtimeConfigRoot, 'leantpm.env'),
      '\nLEANTPM_DB_PASSWORD=must-not-be-inline\n',
    )
    const poisonedDigestResult = invokePowerShell('scripts/Get-LeanTpmDirectoryDigest.ps1', [
      '-DirectoryPath', runtimeConfigRoot,
      '-OutputFormat', 'Json',
    ])
    const poisonedDigest = JSON.parse(poisonedDigestResult.stdout.trim()).digest
    result = invokePowerShell('scripts/Test-LeanTpmRuntimeConfig.ps1', [
      ...validatorArgs.slice(0, -4),
      '-ExpectedDirectorySha256', poisonedDigest,
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /secret|environment/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('returns the audited result for an exact successful retry and treats schemaTo as a no-op', () => {
  const deployment = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmDeployment.ps1'), 'utf8',
  )
  assert.match(deployment, /ALREADY_SUCCEEDED/)
  assert.match(deployment, /existingSuccess/)
  assert.match(deployment, /packageSha256/)
  assert.match(deployment, /runtimeConfigSha256/)
  assert.match(deployment, /Test-LeanTpmAuditLog\.ps1/)
  assert.ok(
    deployment.indexOf('ALREADY_SUCCEEDED') < deployment.indexOf('Invoke-BackendService Stop'),
    'an exact completed retry must return before the mutating step sequence',
  )

  const migrationMain = fs.readFileSync(path.join(
    repositoryRoot, 'backend', 'src', 'main', 'java', 'com', 'leantpm', 'ops',
    'MigrationMain.java',
  ), 'utf8')
  assert.match(migrationMain, /current\s*==\s*settings\.schemaTo\(\)/)
  assert.match(migrationMain, /validate\(\)/)
  assert.ok(
    migrationMain.indexOf('current == settings.schemaTo()')
      < migrationMain.indexOf('current != settings.schemaFrom()'),
    'an already-upgraded schema must validate and return before schemaFrom mismatch rejection',
  )
})

test('executes only reviewed migration bytes and reports runtime storage readiness', () => {
  const migrator = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmMigrator.ps1'), 'utf8',
  )
  assert.match(migrator, /Test-LeanTpmMigratorPayload\.ps1/)
  assert.doesNotMatch(migrator, /schemaFrom\s*-eq\s*\[int\]\$catalog\.schemaTo[\s\S]{0,400}return/)
  assert.ok(fs.existsSync(path.join(repositoryRoot, 'scripts', 'Test-LeanTpmMigratorPayload.ps1')))
  assert.ok(fs.existsSync(path.join(
    repositoryRoot, 'backend', 'src', 'main', 'java', 'com', 'leantpm', 'ops',
    'ReleaseReadinessHealthIndicator.java',
  )))
  const healthConfig = fs.readFileSync(
    path.join(repositoryRoot, 'backend', 'src', 'main', 'resources', 'application.yml'), 'utf8',
  )
  assert.match(healthConfig, /readiness[\s\S]{0,160}releaseContract/)
})

test('keeps service and restore trust anchors host-owned and all database passwords off argv', () => {
  const serviceControl = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.doesNotMatch(serviceControl, /\$WrapperPath|ExpectedWrapperSha256/)
  assert.match(serviceControl, /Start-Service|Stop-Service/)
  const installer = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(installer, /release-trust\.json/)
  assert.match(installer, /LocalService[\s\S]{0,240}(forbidden|not permitted|PlanOnly)/i)
  assert.match(installer, /java[\s\S]{0,80}sha256/i)
  assert.match(installer, /stateDirectory/)
  assert.match(installer, /\$ServiceAccount`:\(OI\)\(CI\)RX/)

  const starter = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmBackend.ps1'), 'utf8',
  )
  assert.match(starter, /Join-Path \$stateDirectory 'recovery-inhibit\.json'/)
  assert.match(starter, /EnumerateFiles/)
  assert.match(starter, /ACTIVATION_AUTHORIZED/)
  assert.match(starter, /ROLLBACK_AUTHORIZED/)
  assert.match(starter, /authorizedReleaseId/)
  assert.match(starter, /authorizedPackageSha256/)
  assert.match(starter, /sslMode=VERIFY_IDENTITY/)
  assert.match(starter, /mysql-truststore\.jks/)
  assert.match(starter, /javax\.net\.ssl\.trustStore/)

  assert.match(serviceControl, /stateDirectory/)
  assert.match(serviceControl, /recovery-inhibit\.json/)
  assert.match(serviceControl, /EnumerateFiles/)
  assert.match(serviceControl, /ACTIVATION_AUTHORIZED/)
  assert.match(serviceControl, /ROLLBACK_AUTHORIZED/)
  assert.match(serviceControl, /authorizedReleaseId/)

  const secretProtector = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Protect-LeanTpmDpapiSecret.ps1'), 'utf8',
  )
  assert.doesNotMatch(secretProtector, /OutputDirectory/)
  assert.match(secretProtector, /DataRoot/)
  assert.match(secretProtector, /Join-Path \$resolvedData 'secrets'/)
  assert.match(secretProtector, /icacls\.exe/)
  assert.match(secretProtector, /ServiceAccount/)

  const restore = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Restore-LeanTpmBackupSet.ps1'), 'utf8',
  )
  assert.match(restore, /verifiedSnapshotRoot/)
  assert.match(restore, /BackupSetPath\s*=\s*\$verifiedSnapshotRoot/)
  assert.match(restore, /Test-LeanTpmProductionRootPolicy\.ps1/)
  assert.match(restore, /ContainmentOnly/)
  assert.match(restore, /Join-Path \$resolvedData 'config\\release-trust\.json'/)
  assert.match(restore, /Join-Path \$resolvedData 'config\\mysql-ca\.pem'/)
  assert.match(restore, /Join-Path \$resolvedData 'audit'/)
  assert.doesNotMatch(restore, /C:\\ProgramData\\LeanTPM/)

  const authE2e = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-auth-e2e.ps1'), 'utf8',
  )
  assert.doesNotMatch(authE2e, /MySqlPassword\s*=\s*['"][^'"]+['"]/)
  assert.doesNotMatch(authE2e, /-p\$MySqlPassword/)
  const mysqlIntegration = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'run-mysql-integration.ps1'), 'utf8',
  )
  assert.match(mysqlIntegration, /ExpectedServerUuid is required for every/i)
})

test('revalidates mutable release state under the global lock before any service mutation', () => {
  for (const script of ['Invoke-LeanTpmDeployment.ps1', 'Invoke-LeanTpmRollback.ps1']) {
    const source = fs.readFileSync(path.join(repositoryRoot, 'scripts', script), 'utf8')
    assert.match(source, /hostStateBeforeLock/)
    assert.match(source, /Assert-LockedHostState/)
    assert.ok(
      source.indexOf('Assert-LockedHostState') < source.indexOf('Invoke-BackendService Stop'),
      `${script} must revalidate pointers and manifests before stopping the service`,
    )
  }
})

test('binds production approvals to the host identity and exact rollback target', () => {
  const trust = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'release-trust.production.example.json'),
    'utf8',
  ))
  assert.ok(trust.environmentId)
  assert.ok(trust.hostId)

  for (const script of ['Invoke-LeanTpmDeployment.ps1', 'Invoke-LeanTpmRollback.ps1']) {
    const source = fs.readFileSync(path.join(repositoryRoot, 'scripts', script), 'utf8')
    assert.match(source, /environmentId/)
    assert.match(source, /hostId/)
    assert.match(source, /trust\.environmentId/)
    assert.match(source, /trust\.hostId/)
  }
  const rollback = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmRollback.ps1'), 'utf8',
  )
  assert.match(rollback, /targetManifestSha256/)
  assert.match(rollback, /expectedTargetPackageSha256/)
})

test('requires MySQL identity verification and does not claim application recovery before E2E', () => {
  for (const script of ['backup-mysql.ps1', 'restore-mysql.ps1']) {
    const source = fs.readFileSync(path.join(repositoryRoot, 'scripts', script), 'utf8')
    assert.match(source, /MySqlSslCaPath/)
    assert.match(source, /VERIFY_IDENTITY/)
  }
  const migrator = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Invoke-LeanTpmMigrator.ps1'), 'utf8',
  )
  assert.match(migrator, /MySqlSslTrustStorePath/)
  assert.match(migrator, /VERIFY_IDENTITY/)
  assert.match(migrator, /javax\.net\.ssl\.trustStore/)
  const restore = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Restore-LeanTpmBackupSet.ps1'), 'utf8',
  )
  assert.match(restore, /DATA_RESTORED_PENDING_APPLICATION_E2E/)
  assert.doesNotMatch(restore, /status\s*=\s*'RESTORED_AND_VERIFIED'/)
  const integration = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'run-mysql-integration.ps1'), 'utf8',
  )
  assert.doesNotMatch(integration, /useSSL=false/i)
  assert.match(integration, /sslMode=VERIFY_IDENTITY/)
  const authE2e = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-auth-e2e.ps1'), 'utf8',
  )
  assert.doesNotMatch(authE2e, /V1_TO_V24/)
  assert.match(authE2e, /databaseSchemaVersion/)
  assert.match(authE2e, /MySqlSslCaPath/)
  assert.match(authE2e, /MySqlSslTrustStorePath/)
  assert.match(authE2e, /ssl-mode=VERIFY_IDENTITY/)
  assert.match(authE2e, /sslMode=VERIFY_IDENTITY/)
  assert.doesNotMatch(authE2e, /useSSL=false/i)
})

test('runs migration upgrade fixtures in a database isolated from module integration tests', () => {
  const { result, logs } = invokeMySqlIntegrationHarness('*MySqlIntegrationTest')
  assert.equal(result.status, 0, combinedOutput(result))
  const mavenCalls = logs.maven.trim().split(/\r?\n/)
  assert.equal(mavenCalls.length, 2, logs.maven)
  assert.match(mavenCalls[0], /_migration\?[^|]*\|-Dleantpm\.build\.directory=.*-Dtest=MySqlMigrationIntegrationTest/)
  assert.doesNotMatch(mavenCalls[0], /_suite\?/)
  assert.match(mavenCalls[1], /_suite\?[^|]*\|-Dleantpm\.build\.directory=/)
  assert.doesNotMatch(mavenCalls[1], /-Dtest=[^|]*MySqlMigrationIntegrationTest/)
})

test('rejects a custom MySQL test pattern that mixes migration and module tests', () => {
  const { result, logs } = invokeMySqlIntegrationHarness(
    'MySqlMigrationIntegrationTest,EquipmentMySqlIntegrationTest',
  )
  assert.notEqual(result.status, 0, combinedOutput(result))
  assert.match(combinedOutput(result), /must run alone/i)
  assert.doesNotMatch(logs.mysql, /CREATE DATABASE/i)
  assert.equal(logs.maven, '')
})

test('fails loudly and attempts every cleanup when an isolated database drop fails', () => {
  const { result, logs } = invokeMySqlIntegrationHarness('*MySqlIntegrationTest', {
    failDrop: true,
  })
  assert.notEqual(result.status, 0, combinedOutput(result))
  assert.match(combinedOutput(result), /failed to remove isolated MySQL integration database/i)
  const dropCalls = logs.mysql.match(/DROP DATABASE IF EXISTS/gi) || []
  assert.equal(dropCalls.length, 2, logs.mysql)
})

test('cleans a database whose create outcome is unknown after the server may have committed', () => {
  const { result, logs } = invokeMySqlIntegrationHarness('*MySqlIntegrationTest', {
    failCreateAfterCommit: true,
  })
  assert.notEqual(result.status, 0, combinedOutput(result))
  assert.match(combinedOutput(result), /failed to create isolated MySQL integration database/i)
  assert.equal((logs.mysql.match(/CREATE DATABASE/gi) || []).length, 1, logs.mysql)
  assert.equal((logs.mysql.match(/DROP DATABASE IF EXISTS/gi) || []).length, 1, logs.mysql)
})

test('proves Flyway checksum tampering fails closed and interrupted migration recovery is repeatable', () => {
  const migrationTest = fs.readFileSync(
    path.join(
      repositoryRoot,
      'backend', 'src', 'test', 'java', 'com', 'leantpm', 'integration',
      'MySqlMigrationIntegrationTest.java',
    ),
    'utf8',
  )
  assert.match(migrationTest, /rejectsTamperedFlywayChecksumAndRestoresKnownHistory/)
  assert.match(migrationTest, /validateWithResult\(\)\.validationSuccessful/)
  assert.match(migrationTest, /recoversAnInterruptedNonTransactionalMigrationBeforeForwardCompletion/)
  assert.match(migrationTest, /flyway_failure_probe_history/)
  assert.match(migrationTest, /THIS_IS_AN_INTENTIONAL_MIGRATION_FAILURE/)
  assert.match(migrationTest, /migrationsExecuted\)\.isEqualTo\(1\)/)
})

test('requires signed two-person approval for production restore and service uninstall', () => {
  const restore = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Restore-LeanTpmBackupSet.ps1'), 'utf8',
  )
  assert.match(restore, /ApprovalPlanPath/)
  assert.match(restore, /Test-LeanTpmReleaseApproval\.ps1/)
  assert.match(restore, /backupManifestSha256/)
  assert.match(restore, /RESTORE_TO_ISOLATED_TARGET/)
  assert.match(restore, /restore-nonces\.jsonl/)

  const service = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(service, /ApprovalPlanPath/)
  assert.match(service, /Test-LeanTpmReleaseApproval\.ps1/)
  assert.match(service, /expectedImageSha256/)
  assert.match(service, /UNINSTALL/)
  assert.match(service, /service-action-nonces\.jsonl/)
  assert.match(service, /UNINSTALL_COMPLETED/)

  const installer = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(installer, /ALREADY_INSTALLED/)
  assert.match(installer, /installation drift/i)
  assert.match(installer, /Win32_Service/)
})

test('holds verified backup files read-only through restore consumption', () => {
  const restore = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Restore-LeanTpmBackupSet.ps1'), 'utf8',
  )
  assert.match(restore, /snapshotLocks/)
  assert.match(restore, /FileShare\]::Read/)
  assert.match(restore, /Copy-ManifestComponent/)
  assert.doesNotMatch(restore, /AllowUnsignedIsolatedTestBackup/)
  assert.match(restore, /Executable restore requires a signed backup/)
})

test('emits a machine-readable aggregate release gate result', () => {
  const verifier = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-release.ps1'), 'utf8',
  )
  assert.match(verifier, /OutputFormat/)
  assert.match(verifier, /EvidencePath/)
  assert.match(verifier, /NOT_RELEASEABLE/)
  assert.match(verifier, /ConvertTo-Json/)
  assert.match(verifier, /release-platform\.test\.mjs/)
  assert.match(verifier, /no-redis-contract\.test\.mjs/)
  assert.match(verifier, /auth-e2e-contract\.test\.mjs/)
  assert.match(verifier, /frontend\\tests\\\*\.test\.mjs/)
  assert.match(verifier, /verify-auth-e2e\.ps1/)
  assert.match(verifier, /release-platform-tests\.tap/)
  assert.match(verifier, /baselineCommit/)
  assert.match(verifier, /toolchainLockSha256/)
})

test('requires signed stage-one environment evidence before declaring RELEASEABLE', () => {
  const verifier = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-release.ps1'), 'utf8',
  )
  assert.match(verifier, /StageOneEvidencePath/)
  assert.match(verifier, /StageOneEvidenceSignaturePath/)
  assert.match(verifier, /Test-LeanTpmStageOneEvidence\.ps1/)
  assert.match(verifier, /Stage-one isolated environment evidence/)

  const evidenceVerifierPath = path.join(
    repositoryRoot, 'scripts', 'Test-LeanTpmStageOneEvidence.ps1',
  )
  const evidenceSchemaPath = path.join(
    repositoryRoot, 'release', 'stage-one-evidence.schema.json',
  )
  assert.ok(fs.existsSync(evidenceVerifierPath))
  assert.ok(fs.existsSync(evidenceSchemaPath))
  const evidenceVerifier = fs.readFileSync(evidenceVerifierPath, 'utf8')
  assert.match(evidenceVerifier, /SignedCms/)
  assert.match(evidenceVerifier, /CheckSignature\(\$true\)/)
  assert.match(evidenceVerifier, /baselineCommit/)
  assert.match(evidenceVerifier, /packageSha256/)
  assert.match(evidenceVerifier, /toolchainLockSha256/)

  const schema = JSON.parse(fs.readFileSync(evidenceSchemaPath, 'utf8'))
  const requiredScenarios = schema.$defs.scenarioName.enum
  for (const scenario of [
    'WINDOWS_SERVICE_LIFECYCLE',
    'HTTPS_SECRET_HEALTH',
    'BACKUP_OFFHOST_RECEIPT',
    'DATABASE_OLD_SCHEMA_MATRIX',
    'DEPLOYMENT_E2E',
    'ROLLBACK_E2E',
    'RESTORE_E2E',
    'POWER_LOSS_RECOVERY',
    'FAULT_INJECTION',
  ]) {
    assert.ok(requiredScenarios.includes(scenario), `missing stage-one scenario ${scenario}`)
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-stage-one-evidence-'))
  try {
    const startedAtUtc = new Date(Date.now() - 60_000).toISOString()
    const completedAtUtc = new Date().toISOString()
    const baselineCommit = 'd'.repeat(40)
    const packageSha256 = 'a'.repeat(64)
    const manifestSha256 = 'b'.repeat(64)
    const toolchainLockSha256 = 'c'.repeat(64)
    const evidence = {
      schemaVersion: 1,
      evidenceId: 'stage-one-test-001',
      environmentId: 'isolated-win-test',
      hostId: 'host-test-001',
      environmentKind: 'ISOLATED_WINDOWS_SERVER',
      baselineCommit,
      workingTreeClean: true,
      releaseId: 'leantpm-1.0.1',
      productVersion: '1.0.1',
      packageSha256,
      manifestSha256,
      toolchainLockSha256,
      startedAtUtc,
      completedAtUtc,
      scenarios: requiredScenarios.map((name, index) => ({
        name,
        result: 'PASS',
        startedAtUtc,
        completedAtUtc,
        evidenceSha256: index.toString(16).padStart(64, '0'),
        evidenceUri: `evidence://stage-one/${index}`,
      })),
      residualRisks: ['Single-host phase-one deployment remains a planned downtime operation.'],
    }
    const evidencePath = path.join(temporaryRoot, 'stage-one-evidence.json')
    fs.writeFileSync(evidencePath, JSON.stringify(evidence), 'utf8')
    const expectedArgs = [
      '-EvidencePath', evidencePath,
      '-ExpectedBaselineCommit', baselineCommit,
      '-ExpectedReleaseId', evidence.releaseId,
      '-ExpectedProductVersion', evidence.productVersion,
      '-ExpectedPackageSha256', packageSha256,
      '-ExpectedManifestSha256', manifestSha256,
      '-ExpectedToolchainLockSha256', toolchainLockSha256,
    ]
    let result = invokePowerShell('scripts/Test-LeanTpmStageOneEvidence.ps1', [
      ...expectedArgs,
      '-AllowUnsignedTestEvidence',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.equal(JSON.parse(result.stdout.trim()).scenarioCount, requiredScenarios.length)

    result = invokePowerShell('scripts/Test-LeanTpmStageOneEvidence.ps1', expectedArgs)
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /signature/i)

    evidence.scenarios.pop()
    fs.writeFileSync(evidencePath, JSON.stringify(evidence), 'utf8')
    result = invokePowerShell('scripts/Test-LeanTpmStageOneEvidence.ps1', [
      ...expectedArgs,
      '-AllowUnsignedTestEvidence',
    ])
    assert.notEqual(result.status, 0, combinedOutput(result))
    assert.match(combinedOutput(result), /scenario/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('binds the backed-up effective configuration to the actual runtime environment', () => {
  const backup = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'New-LeanTpmBackupSet.ps1'), 'utf8',
  )
  assert.match(backup, /RuntimeEnvironmentPath/)
  assert.match(backup, /LEANTPM_DB_URL/)
  assert.match(backup, /runtime environment differs/i)
  assert.match(backup, /leantpm\.env/)
  assert.match(backup, /sslMode=VERIFY_IDENTITY/)

  const production = fs.readFileSync(
    path.join(repositoryRoot, 'backend', 'src', 'main', 'resources', 'application-prod.yml'), 'utf8',
  )
  assert.doesNotMatch(production, /spring:\s*[\s\S]*data:\s*[\s\S]*redis:/)
})

test('bootstraps only an empty host and empty schema through a dedicated first-install ceremony', () => {
  const firstInstallPath = path.join(
    repositoryRoot, 'scripts', 'Initialize-LeanTpmFirstRelease.ps1',
  )
  assert.ok(fs.existsSync(firstInstallPath), 'dedicated first-install orchestrator is required')
  const firstInstall = fs.readFileSync(firstInstallPath, 'utf8')
  assert.match(firstInstall, /FIRST_INSTALL/)
  assert.match(firstInstall, /UNINITIALIZED/)
  assert.match(firstInstall, /schemaFrom[\s\S]{0,120}-ne 0/)
  assert.match(firstInstall, /current-release\.json/)
  assert.match(firstInstall, /current-config\.json/)
  assert.match(firstInstall, /service[\s\S]{0,200}Stopped/i)
  assert.match(firstInstall, /MIGRATION_IN_PROGRESS/)
  assert.ok(
    firstInstall.indexOf('MIGRATION_IN_PROGRESS')
      < firstInstall.indexOf('Invoke-LeanTpmMigrator.ps1'),
    'durable recovery inhibition must precede the first database write',
  )
  assert.match(firstInstall, /RECOVERY_REQUIRED/)
  assert.match(firstInstall, /FileMode\]::CreateNew/)

  const manifestSchema = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'release', 'release-manifest.schema.json'), 'utf8',
  ))
  assert.equal(manifestSchema.properties.components.properties.database
    .properties.schemaFrom.minimum, 0)
  const manifestGenerator = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'New-ReleaseManifest.ps1'), 'utf8',
  )
  const manifestVerifier = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Test-ReleaseManifest.ps1'), 'utf8',
  )
  assert.match(manifestGenerator, /\$SchemaFrom -lt 0/)
  assert.match(manifestVerifier, /schemaFrom -lt 0/)

  const migrationMain = fs.readFileSync(
    path.join(
      repositoryRoot, 'backend', 'src', 'main', 'java', 'com', 'leantpm', 'ops',
      'MigrationMain.java',
    ),
    'utf8',
  )
  assert.match(migrationMain, /assertEmptyDatabaseForFirstInstall/)
  assert.ok(
    migrationMain.indexOf('assertEmptyDatabaseForFirstInstall')
      < migrationMain.indexOf('flyway.migrate()'),
    'first install must prove an empty target before Flyway creates any objects',
  )
})

test('reconciles recovery only by an approved exact forward completion', () => {
  const recoveryPath = path.join(
    repositoryRoot, 'scripts', 'Resolve-LeanTpmRecovery.ps1',
  )
  assert.ok(fs.existsSync(recoveryPath), 'a recovery reconciliation command is required')
  const recovery = fs.readFileSync(recoveryPath, 'utf8')
  assert.match(recovery, /COMPLETE_FORWARD/)
  assert.match(recovery, /expectedRecoveryStateSha256/)
  assert.match(recovery, /Test-LeanTpmReleaseApproval\.ps1/)
  assert.match(recovery, /FileMode\]::OpenOrCreate/)
  const recoveryLockIndex = recovery.indexOf('FileMode]::OpenOrCreate')
  const lockedRecoveryRead = recovery.lastIndexOf(
    '[IO.File]::ReadAllBytes($recoveryMarker)',
  )
  assert.ok(
    recoveryLockIndex >= 0 && lockedRecoveryRead > recoveryLockIndex,
    'the global lock must be held while re-reading and accepting mutable recovery state',
  )
  assert.match(recovery, /Invoke-LeanTpmMigrator\.ps1/)
  assert.match(recovery, /ACTIVATION_AUTHORIZED/)
  assert.ok(
    recovery.indexOf('Invoke-LeanTpmMigrator.ps1')
      < recovery.indexOf("Write-RecoveryState 'ACTIVATION_AUTHORIZED'"),
    'actual schema validation must precede activation authorization',
  )
  assert.match(recovery, /RECOVERY_COMPLETED/)
  assert.ok(
    recovery.indexOf('Wait-ReconciledReadiness')
      < recovery.lastIndexOf('Delete($recoveryMarker)'),
    'recovery inhibition may be removed only after exact readiness succeeds',
  )
  assert.doesNotMatch(recovery, /CONTRACT[\s\S]{0,300}(?:ROLLBACK|previous)/i)

  for (const relative of [
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Invoke-LeanTpmRollback.ps1',
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /targetPackageSha256/)
  }
})

test('classifies every historical migration before building a first-install catalog', () => {
  const classificationPath = path.join(
    repositoryRoot, 'release', 'migration-classification.json',
  )
  assert.ok(fs.existsSync(classificationPath), 'V1-V50 classification evidence is required')
  const classification = JSON.parse(fs.readFileSync(classificationPath, 'utf8'))
  assert.equal(classification.schemaVersion, 1)
  assert.equal(classification.entries.length, 50)
  assert.deepEqual(
    classification.entries.map((entry) => entry.version),
    Array.from({ length: 50 }, (_, index) => index + 1),
  )
  for (const entry of classification.entries) {
    assert.equal(entry.reviewStatus, 'APPROVED')
    assert.match(entry.reviewedBy, /^codex-/)
    assert.match(entry.evidence, /migration-classification-review\.md/)
  }
  for (const version of [15, 31, 33, 36, 38, 39, 42, 43, 50]) {
    const entry = classification.entries.find((candidate) => candidate.version === version)
    assert.equal(entry.backwardCompatible, false, `V${version} must not claim old-app compatibility`)
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-first-catalog-'))
  try {
    const catalogPath = path.join(temporaryRoot, 'migrations.json')
    const result = invokePowerShell('scripts/New-MigrationCatalog.ps1', [
      '-MigrationRoot', 'backend/src/main/resources/db/migration',
      '-SchemaFrom', '0',
      '-ClassificationPath', classificationPath,
      '-OutputPath', catalogPath,
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.migrationCount, 50)
    assert.equal(report.phase, 'CONTRACT')
    assert.equal(report.backwardCompatible, false)
    const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'))
    assert.equal(catalog.migrations.length, 50)

    const payload = path.join(temporaryRoot, 'payload')
    fs.cpSync(path.join(repositoryRoot, 'release', 'sample-package'), payload, {
      recursive: true,
    })
    const payloadCatalog = path.join(payload, 'database', 'migrations.json')
    fs.rmSync(payloadCatalog)
    const payloadMigrations = path.join(payload, 'database', 'migrations')
    fs.mkdirSync(payloadMigrations)
    for (const migration of fs.readdirSync(path.join(
      repositoryRoot, 'backend', 'src', 'main', 'resources', 'db', 'migration',
    ))) {
      fs.copyFileSync(
        path.join(
          repositoryRoot, 'backend', 'src', 'main', 'resources', 'db', 'migration', migration,
        ),
        path.join(payloadMigrations, migration),
      )
    }
    let generated = invokePowerShell('scripts/New-MigrationCatalog.ps1', [
      '-MigrationRoot', payloadMigrations,
      '-SchemaFrom', '0',
      '-ClassificationPath', classificationPath,
      '-OutputPath', payloadCatalog,
      '-OutputFormat', 'Json',
    ])
    assert.equal(generated.status, 0, combinedOutput(generated))

    const baselinePath = path.join(temporaryRoot, 'baseline.json')
    fs.writeFileSync(baselinePath, JSON.stringify({
      schemaVersion: 1,
      status: 'PASS',
      commit: '2185536ea9da0a323b27f53dcf849b818ea19069',
      dirty: false,
      sourceDateEpoch: 1786161320,
      fileTreeSha256: 'a'.repeat(64),
    }))
    const manifestPath = path.join(temporaryRoot, 'release-manifest.json')
    generated = invokePowerShell('scripts/New-ReleaseManifest.ps1', [
      '-PayloadRoot', payload,
      '-OutputPath', manifestPath,
      '-ReleaseId', 'leantpm-1.0.1-first-test',
      '-ReleaseTier', 'TEST',
      '-SourceCommit', '2185536ea9da0a323b27f53dcf849b818ea19069',
      '-BaselinePath', baselinePath,
      '-AllowSyntheticTestBaseline',
      '-CreatedAtUtc', '2026-08-08T07:30:00Z',
      '-SchemaFrom', '0',
      '-DatabasePhase', 'CONTRACT',
      '-RequiresDowntime',
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(generated.status, 0, combinedOutput(generated))
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    assert.equal(manifest.components.database.schemaFrom, 0)
    assert.equal(manifest.rollback.previousReleaseRequired, false)
    assert.equal(manifest.rollback.class, 'RECOVERY_REQUIRED')

    const packagePath = path.join(temporaryRoot, 'first-install.zip')
    generated = invokePowerShell('scripts/New-ReleasePackage.ps1', [
      '-ManifestPath', manifestPath,
      '-PayloadRoot', payload,
      '-OutputPath', packagePath,
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(generated.status, 0, combinedOutput(generated))
    const installRoot = path.join(temporaryRoot, 'install')
    const dataRoot = path.join(temporaryRoot, 'data')
    const backupRoot = path.join(dataRoot, 'backups')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(backupRoot, { recursive: true })
    const firstPlanPath = path.join(temporaryRoot, 'first-install-plan.json')
    fs.writeFileSync(firstPlanPath, JSON.stringify({
      schemaVersion: 1,
      operation: 'FIRST_INSTALL',
      expectedCurrentState: 'UNINITIALIZED',
      environmentName: 'isolated-first-install',
      environmentKind: 'NON_PRODUCTION',
      releaseId: 'leantpm-1.0.1-first-test',
      approvalId: 'first-install-test-001',
      packagePath,
      installRoot,
      dataRoot,
      backupRoot,
      serviceId: 'LeanTPM.Backend',
      healthUri: 'http://127.0.0.1:18080/actuator/health/readiness',
      runtimeConfigId: 'leantpm-1.0.1-first-test-config',
      runtimeConfigSha256: 'b'.repeat(64),
    }, null, 2))
    const beforePlan = snapshotTree(temporaryRoot)
    const firstPlan = invokePowerShell('scripts/Initialize-LeanTpmFirstRelease.ps1', [
      '-PlanPath', firstPlanPath,
      '-PlanOnly',
      '-AllowUnsignedTestManifest',
      '-OutputFormat', 'Json',
    ])
    assert.equal(firstPlan.status, 0, combinedOutput(firstPlan))
    assert.equal(JSON.parse(firstPlan.stdout.trim()).status, 'PLAN')
    assert.deepEqual(
      snapshotTree(temporaryRoot), beforePlan,
      'first-install PlanOnly changed the target tree',
    )
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('pins and plans an HTTPS proxy service under an identity isolated from Backend secrets', () => {
  const toolchain = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, 'release', 'toolchain-lock.json'), 'utf8',
  ))
  assert.equal(toolchain.caddy.version, '2.11.4')
  assert.equal(
    toolchain.caddy.sha256,
    '92114e8edfbbc5915f508385db44f00881035485464b6da9d85976e63d59ee1e',
  )
  assert.equal(toolchain.caddy.status, 'PINNED')
  const aggregate = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'verify-release.ps1'), 'utf8',
  )
  assert.match(aggregate, /toolchain\.caddy\.version/)
  assert.match(aggregate, /toolchain\.caddy\.sha256/)

  const installerPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmCaddyService.ps1',
  )
  assert.ok(fs.existsSync(installerPath), 'Caddy Windows Service installer is required')
  const installer = fs.readFileSync(installerPath, 'utf8')
  assert.match(installer, /LeanTPM\.Proxy/)
  assert.match(installer, /ProxyServiceAccount/)
  assert.match(installer, /BackendServiceAccount/)
  assert.match(installer, /must be different/i)
  assert.match(installer, /Win32_Service/)
  assert.match(installer, /ALREADY_INSTALLED/)
  assert.match(installer, /current[\s\S]{0,300}\(OI\)\(CI\)RX/)
  assert.doesNotMatch(installer, /secrets[^\n]{0,160}\$ProxyServiceAccount/i)
  assert.match(installer, /Test-LeanTpmProductionRootPolicy\.ps1/)
  assert.match(installer, /MANAGED_LEANTPM_PROXY/)
  assert.match(installer, /deployment\.lock/)
  assert.match(installer, /Flush\(\$true\)/)
  assert.ok([...installer.matchAll(/Test-LeanTpmProductionRootPolicy\.ps1/g)].length >= 2)
  assert.doesNotMatch(installer, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)
  assert.match(installer, /Name='caddy'/)
  assert.match(installer, /Get-NetTCPConnection/)
  assert.match(installer, /ports 80 and 443|public ingress/i)

  const binding = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmCaddyServiceBinding.ps1'),
    'utf8',
  )
  assert.match(binding, /Test-LeanTpmProductionRootPolicy\.ps1/)
  assert.match(binding, /MANAGED_LEANTPM_PROXY/)
  assert.match(binding, /Name='caddy'/)
  assert.match(binding, /Get-NetTCPConnection/)
  assert.doesNotMatch(binding, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-plan-'))
  try {
    const caddy = path.join(temporaryRoot, 'caddy.exe')
    const wrapper = path.join(temporaryRoot, 'WinSW.exe')
    const installRoot = path.join(temporaryRoot, 'install')
    const dataRoot = path.join(temporaryRoot, 'data')
    fs.writeFileSync(caddy, 'synthetic Caddy fixture')
    fs.writeFileSync(wrapper, 'synthetic WinSW fixture')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    const digest = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
    const before = snapshotTree(temporaryRoot)
    const result = invokePowerShell('deploy/windows/Install-LeanTpmCaddyService.ps1', [
      '-CaddyPath', caddy,
      '-ExpectedCaddySha256', digest(caddy),
      '-WrapperPath', wrapper,
      '-ExpectedWrapperSha256', digest(wrapper),
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-SiteHost', 'tpm.test.invalid',
      '-ProxyServiceAccount', 'TEST\\leantpm-proxy$',
      '-BackendServiceAccount', 'TEST\\leantpm-backend$',
      '-AllowUnpinnedTestBinaries',
      '-AllowNonProductionRoots',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    assert.equal(JSON.parse(result.stdout.trim()).status, 'PLAN')
    assert.deepEqual(snapshotTree(temporaryRoot), before, 'proxy PlanOnly changed the target tree')
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('validates an exact external caddy binding and rejects listener ownership drift', () => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-external-caddy-contract-'))
  try {
    const policyPath = path.join(temporaryRoot, 'external-caddy-binding.json')
    const observationPath = path.join(temporaryRoot, 'external-caddy-observation.json')
    const approvedCommandLine = '"D:\\tools\\caddy\\caddy.exe" run --config ' +
      '"D:\\LeanTPM\\Runtime\\proxy\\Caddyfile" --adapter caddyfile'
    const approvedServiceEnvironment = [
      'XDG_CONFIG_HOME=D:\\LeanTPM\\Runtime\\proxy\\config',
      'XDG_DATA_HOME=D:\\LeanTPM\\Runtime\\proxy\\tls',
    ].join('\n')
    const policy = {
      schemaVersion: 1,
      readiness: 'READY',
      serviceId: 'caddy',
      exclusiveIngress: true,
      serviceImagePath: 'D:\\tools\\caddy\\caddy.exe',
      serviceImageSha256: 'a'.repeat(64),
      serviceCommandLineSha256: crypto.createHash('sha256')
        .update(approvedCommandLine).digest('hex'),
      serviceEnvironmentSha256: crypto.createHash('sha256')
        .update(approvedServiceEnvironment).digest('hex'),
      serviceAccount: 'CONTOSO\\leantpm-proxy$',
      serviceAccountSid: 'S-1-5-21-111-222-333-444',
      startMode: 'AUTO',
      scmSddlSha256: 'b'.repeat(64),
      configPath: 'D:\\LeanTPM\\Runtime\\proxy\\Caddyfile',
      configSha256: 'c'.repeat(64),
      publicHost: 'tpm.example.com',
      webRoot: 'D:\\LeanTPM\\App\\current\\payload\\web',
      backendUpstream: 'http://127.0.0.1:18080',
      listenPorts: [80, 443],
      tlsDataRoot: 'D:\\LeanTPM\\Runtime\\proxy\\tls',
      logRoot: 'D:\\LeanTPM\\Runtime\\proxy\\logs',
      adminEndpoint: 'OFF',
      firewallRuleGroup: 'LeanTPM-Public-Isolation',
      firewallPolicySha256: 'd'.repeat(64),
    }
    const observation = {
      schemaVersion: 1,
      serviceId: 'caddy',
      serviceState: 'RUNNING',
      servicePid: 4100,
      serviceImagePath: policy.serviceImagePath,
      serviceImageSha256: policy.serviceImageSha256,
      serviceCommandLine: approvedCommandLine,
      serviceCommandLineSha256: policy.serviceCommandLineSha256,
      serviceEnvironmentSha256: policy.serviceEnvironmentSha256,
      serviceAccount: policy.serviceAccount,
      serviceAccountSid: policy.serviceAccountSid,
      startMode: policy.startMode,
      scmSddlSha256: policy.scmSddlSha256,
      configPath: policy.configPath,
      configSha256: policy.configSha256,
      tlsDataRoot: policy.tlsDataRoot,
      logRoot: policy.logRoot,
      publicHost: policy.publicHost,
      webRoot: policy.webRoot,
      backendUpstream: policy.backendUpstream,
      adminEndpoint: policy.adminEndpoint,
      processTreePids: [4100],
      listeners: [
        { localAddress: '0.0.0.0', port: 80, owningPid: 4100 },
        { localAddress: '0.0.0.0', port: 443, owningPid: 4100 },
      ],
      managedProxyPresent: false,
      firewallRuleGroup: policy.firewallRuleGroup,
      firewallPolicySha256: policy.firewallPolicySha256,
      firewallReady: true,
      firewallState: 'STANDBY_DISABLED',
      processStartedAtUtc: '2026-08-09T00:00:05.0000000Z',
      policyLastWriteUtc: '2026-08-09T00:00:01.0000000Z',
      serviceImageLastWriteUtc: '2026-08-09T00:00:01.0000000Z',
      configLastWriteUtc: '2026-08-09T00:00:02.0000000Z',
      serviceEnvironmentLastWriteUtc: '2026-08-09T00:00:03.0000000Z',
      runtimeFreshnessVerified: true,
    }
    fs.writeFileSync(policyPath, JSON.stringify(policy))
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const expectedPolicySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(policyPath)).digest('hex')
    const accepted = invokePowerShell('deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
      '-PolicyPath', policyPath,
      '-ObservationPath', observationPath,
      '-ExpectedPolicySha256', expectedPolicySha256,
      '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
      '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      '-OutputFormat', 'Json',
    ])
    assert.equal(accepted.status, 0, combinedOutput(accepted))
    const report = JSON.parse(accepted.stdout.trim())
    assert.equal(report.status, 'PASS')
    assert.equal(report.serviceId, 'caddy')
    assert.equal(report.failClosedCapable, true)
    assert.equal(report.serviceEnvironmentSha256, policy.serviceEnvironmentSha256)
    assert.equal(report.tlsDataRoot, policy.tlsDataRoot)
    assert.equal(report.logRoot, policy.logRoot)
    assert.equal(report.runtimeFreshnessVerified, true)
    assert.match(report.proxyBindingSha256, /^[a-f0-9]{64}$/)

    const containmentPolicy = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ExpectedPolicySha256', expectedPolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
        '-PolicyOnly',
        '-OutputFormat', 'Json',
      ],
    )
    assert.equal(containmentPolicy.status, 0, combinedOutput(containmentPolicy))
    const containmentReport = JSON.parse(containmentPolicy.stdout.trim())
    assert.equal(containmentReport.status, 'PASS')
    assert.equal(containmentReport.policyOnly, true)
    assert.equal(containmentReport.firewallRuleGroup, policy.firewallRuleGroup)

    observation.serviceEnvironmentSha256 = 'e'.repeat(64)
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const environmentDrift = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ObservationPath', observationPath,
        '-ExpectedPolicySha256', expectedPolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      ],
    )
    assert.notEqual(environmentDrift.status, 0, combinedOutput(environmentDrift))
    observation.serviceEnvironmentSha256 = policy.serviceEnvironmentSha256

    observation.logRoot = 'D:\\LeanTPM\\Runtime\\proxy\\other-logs'
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const runtimeRootDrift = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ObservationPath', observationPath,
        '-ExpectedPolicySha256', expectedPolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      ],
    )
    assert.notEqual(runtimeRootDrift.status, 0, combinedOutput(runtimeRootDrift))
    observation.logRoot = policy.logRoot

    observation.processStartedAtUtc = '2026-08-09T00:00:02.0000000Z'
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const staleRuntime = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ObservationPath', observationPath,
        '-ExpectedPolicySha256', expectedPolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      ],
    )
    assert.notEqual(staleRuntime.status, 0, combinedOutput(staleRuntime))
    observation.processStartedAtUtc = '2026-08-09T00:00:05.0000000Z'

    const alternateCommandLine = '"D:\\tools\\caddy\\caddy.exe" run --config ' +
      '"D:\\LeanTPM\\Runtime\\proxy\\unapproved.Caddyfile" --adapter caddyfile'
    observation.serviceCommandLine = alternateCommandLine
    observation.serviceCommandLineSha256 = crypto.createHash('sha256')
      .update(alternateCommandLine).digest('hex')
    policy.serviceCommandLineSha256 = observation.serviceCommandLineSha256
    fs.writeFileSync(policyPath, JSON.stringify(policy))
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const alternatePolicySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(policyPath)).digest('hex')
    const alternateConfig = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ObservationPath', observationPath,
        '-ExpectedPolicySha256', alternatePolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      ],
    )
    assert.notEqual(alternateConfig.status, 0, combinedOutput(alternateConfig))

    policy.serviceCommandLineSha256 = crypto.createHash('sha256')
      .update(approvedCommandLine).digest('hex')
    observation.serviceCommandLine = approvedCommandLine
    observation.serviceCommandLineSha256 = policy.serviceCommandLineSha256
    fs.writeFileSync(policyPath, JSON.stringify(policy))
    fs.writeFileSync(observationPath, JSON.stringify(observation))

    observation.firewallState = 'ACTIVE'
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const restoredPolicySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(policyPath)).digest('hex')
    const unsafeFirewall = invokePowerShell(
      'deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
        '-PolicyPath', policyPath,
        '-ObservationPath', observationPath,
        '-ExpectedPolicySha256', restoredPolicySha256,
        '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
        '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
      ],
    )
    assert.notEqual(unsafeFirewall.status, 0, combinedOutput(unsafeFirewall))

    observation.firewallState = 'STANDBY_DISABLED'
    observation.listeners[1].owningPid = 9999
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const rejected = invokePowerShell('deploy/windows/Test-LeanTpmExternalCaddyContract.ps1', [
      '-PolicyPath', policyPath,
      '-ObservationPath', observationPath,
      '-ExpectedPolicySha256', restoredPolicySha256,
      '-ExpectedInstallRoot', 'D:\\LeanTPM\\App',
      '-ExpectedDataRoot', 'D:\\LeanTPM\\Runtime',
    ])
    assert.notEqual(rejected.status, 0, combinedOutput(rejected))
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('rejects propagation-only runtime ACLs and detects persistent directory identity replacement', () => {
  for (const scenario of ['DIRECTORY_VALID', 'FILE_VALID']) {
    const accepted = invokeExternalCaddyRuntimeAclHarness(scenario)
    assert.equal(accepted.status, 0, combinedOutput(accepted))
    assert.equal(JSON.parse(accepted.stdout.trim()).status, 'PASS')
  }
  for (const scenario of [
    'DIRECTORY_INHERIT_ONLY_EVERYONE',
    'DIRECTORY_NO_PROPAGATE',
    'DIRECTORY_NO_INHERITANCE',
    'FILE_INHERIT_ONLY_EVERYONE',
    'FILE_INHERITABLE',
  ]) {
    const rejected = invokeExternalCaddyRuntimeAclHarness(scenario)
    assert.notEqual(rejected.status, 0, `${scenario} unexpectedly passed`)
  }

  const source = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1'),
    'utf8',
  )
  const openDirectory = source.slice(
    source.indexOf('function Open-DirectoryIdentity'),
    source.indexOf('function Assert-RuntimeChildAcl'),
  )
  assert.match(openDirectory, /GetFileInformationByHandle/)
  assert.match(openDirectory, /FileAttributes[\s\S]*Directory/)
  assert.match(openDirectory, /FileAttributes[\s\S]*ReparsePoint/)
  const traversal = source.slice(
    source.indexOf('function Assert-ProtectedRuntimeTree'),
    source.indexOf('function Assert-TrustedParentChain'),
  )
  assert.match(traversal, /function\s+Inspect-RuntimeDirectory/)
  const identityOpen = traversal.indexOf('Open-DirectoryIdentity')
  const enumerate = traversal.indexOf('Get-ChildItem')
  const identityDispose = traversal.lastIndexOf('.handle.Dispose()')
  assert.ok(identityOpen >= 0 && enumerate > identityOpen && identityDispose > enumerate,
    'runtime directory identity handle must remain open while its children are enumerated')

  const traversalResult = invokeExternalCaddyTraversalLifetimeHarness()
  assert.equal(traversalResult.status, 0, combinedOutput(traversalResult))
  assert.deepEqual(JSON.parse(traversalResult.stdout.trim()), {
    status: 'PASS',
    events: [
      'ROOT_ACL:C:\\runtime',
      'OPEN:C:\\runtime',
      'ROOT_ACL:C:\\runtime',
      'ENUM:C:\\runtime',
      'OPEN:C:\\runtime\\child',
      'CHILD_ACL:C:\\runtime\\child',
      'ENUM:C:\\runtime\\child',
      'DISPOSE:C:\\runtime\\child',
      'DISPOSE:C:\\runtime',
    ],
  })

  const identityRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-caddy-dir-object-'))
  try {
    const directoryPath = path.join(identityRoot, 'directory')
    const filePath = path.join(identityRoot, 'file.txt')
    const junctionPath = path.join(identityRoot, 'junction')
    fs.mkdirSync(directoryPath)
    fs.writeFileSync(filePath, 'fixture')
    fs.symlinkSync(directoryPath, junctionPath, 'junction')
    const directoryAccepted = invokeExternalCaddyDirectoryIdentityHarness(directoryPath)
    assert.equal(directoryAccepted.status, 0, combinedOutput(directoryAccepted))
    const directoryReport = JSON.parse(directoryAccepted.stdout.trim())
    assert.equal(directoryReport.status, 'PASS')
    assert.notEqual(directoryReport.fileAttributes & 0x10, 0)
    assert.equal(directoryReport.fileAttributes & 0x400, 0)
    for (const invalidPath of [filePath, junctionPath]) {
      const rejected = invokeExternalCaddyDirectoryIdentityHarness(invalidPath)
      assert.notEqual(rejected.status, 0, `${invalidPath} unexpectedly passed as a directory`)
    }

    const traversalRoot = path.join(identityRoot, 'traversal')
    const traversalChild = path.join(traversalRoot, 'child')
    fs.mkdirSync(traversalChild, { recursive: true })
    const persistentSwap = invokeExternalCaddyRealTraversalLockHarness(traversalRoot)
    assert.notEqual(persistentSwap.status, 0, 'persistent directory replacement was accepted')
    assert.match(combinedOutput(persistentSwap), /renamed during runtime-tree validation/)
  } finally {
    fs.rmSync(identityRoot, { recursive: true, force: true })
  }
})

test('quiesces external caddy behind the exact firewall guard before trusting mutable runtime trees', () => {
  const bindingPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1',
  )
  const ingressPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Restore-LeanTpmExternalIngress.ps1',
  )
  const binding = fs.readFileSync(bindingPath, 'utf8')
  const ingress = fs.readFileSync(ingressPath, 'utf8')
  assert.match(binding, /RuntimeTreeMode/)
  assert.match(binding, /ROOTS_ONLY/)
  assert.match(binding, /QUIESCED_TREE/)
  assert.match(binding, /runtimeTreeQuiescenceVerified/)
  assert.match(binding, /runningProxyIdentityProcesses/)
  assert.match(binding, /Invoke-CimMethod[\s\S]{0,160}GetOwnerSid/)
  assert.equal(
    [...binding.matchAll(/Assert-ExternalCaddyQuiescedState 'caddy'/g)].length,
    2,
    'SCM, process identity and listener quiescence must be proven before and after scanning',
  )
  const quiescedPass = invokeExternalCaddyQuiescedStateHarness('PASS')
  assert.equal(quiescedPass.status, 0, combinedOutput(quiescedPass))
  for (const scenario of [
    'SCM_RUNNING', 'IMAGE_PROCESS', 'PROXY_PROCESS', 'LISTENER', 'OWNER_QUERY_FAIL_LIVE',
  ]) {
    const rejected = invokeExternalCaddyQuiescedStateHarness(scenario)
    assert.notEqual(rejected.status, 0, `${scenario} was accepted as quiescent`)
  }

  const guardIndex = ingress.indexOf('Enable-NetFirewallRule')
  const stopIndex = ingress.indexOf('Stop-Service')
  const quiescedIndex = ingress.indexOf('-RuntimeTreeMode QUIESCED_TREE')
  const startIndex = ingress.indexOf('Start-Service')
  const guardedLiveIndex = ingress.indexOf('-RuntimeTreeMode ROOTS_ONLY', startIndex)
  const unguardIndex = ingress.indexOf('Disable-NetFirewallRule')
  const standbyIndex = ingress.indexOf('-RuntimeTreeMode ROOTS_ONLY', unguardIndex)
  assert.ok(
    guardIndex >= 0 && stopIndex > guardIndex && quiescedIndex > stopIndex &&
      startIndex > quiescedIndex && guardedLiveIndex > startIndex &&
      unguardIndex > guardedLiveIndex && standbyIndex > unguardIndex,
    'external Caddy runtime trust must be established only in a guarded stopped-service window',
  )
  assert.match(ingress, /DeploymentLockToken/)
  assert.match(ingress, /Test-LeanTpmExternalFirewallIsolation\.ps1/)
  const rootPolicy = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmProductionRootPolicy.ps1',
  ), 'utf8')
  assert.doesNotMatch(
    rootPolicy,
    /Test-LeanTpmExternalCaddyBinding\.ps1[\s\S]{0,420}-RuntimeTreeMode QUIESCED_TREE/,
    'ordinary production policy checks must remain read-only and must not quiesce ingress',
  )
})

test('derives production policy from host bootstrap and binds recovery to the original database', () => {
  const rootPolicy = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmProductionRootPolicy.ps1'),
    'utf8',
  )
  assert.match(rootPolicy, /Test-LeanTpmHostBootstrap\.ps1/)
  assert.match(rootPolicy, /AllowMissing/)
  assert.match(rootPolicy, /layoutSha256/)
  assert.match(rootPolicy, /Test-LeanTpmExternalCaddyBinding\.ps1/)
  assert.match(rootPolicy, /proxyBindingSha256/)
  assert.match(rootPolicy, /ContainmentOnly/)
  assert.doesNotMatch(rootPolicy, /production mutations are blocked/i)
  assert.doesNotMatch(rootPolicy, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM(?:\\|')/)
  for (const relative of [
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Invoke-LeanTpmRollback.ps1',
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
    'scripts/Resolve-LeanTpmRecovery.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /\$isProductionRootPair/)
    assert.match(source, /Test-LeanTpmProductionRootPolicy\.ps1/)
    assert.match(source, /hostLayoutSha256/)
    assert.match(source, /proxyBindingSha256/)
    assert.match(source, /layoutSha256/)
    assert.doesNotMatch(source, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)
    assert.match(source, /production root pair[\s\S]{0,180}PRODUCTION/i)
    assert.match(source, /AllowNonProductionCustomRoots[\s\S]{0,220}production root/i)
    const policyChecks = [...source.matchAll(/Test-LeanTpmProductionRootPolicy\.ps1/g)]
    assert.ok(policyChecks.length >= 2,
      `${relative} must revalidate host layout after acquiring the global deployment lock`)
    const lockAcquired = source.search(/Flush\(\$true\)/)
    const revalidationCall = source.lastIndexOf('Assert-HostLayoutPolicyUnchanged')
    assert.ok(lockAcquired >= 0 && revalidationCall > lockAcquired,
      `${relative} revalidated host layout before acquiring the global deployment lock`)
  }
  const service = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(service, /\$isProductionRootPair/)
  assert.match(service, /Test-LeanTpmProductionRootPolicy\.ps1/)
  assert.match(service, /hostLayoutSha256/)
  assert.doesNotMatch(service, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)
  assert.match(service, /AllowNonProductionRoot[\s\S]{0,180}production root/i)
  assert.match(service, /Uninstall[\s\S]{0,500}\$isProductionRootPair/)
  const secret = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Protect-LeanTpmDpapiSecret.ps1'), 'utf8',
  )
  assert.match(secret, /\$isProductionDataRoot/)
  assert.match(secret, /Test-LeanTpmProductionRootPolicy\.ps1/)
  assert.match(secret, /InstallRoot/)
  assert.match(secret, /deployment\.lock/)
  assert.match(secret, /Flush\(\$true\)/)
  assert.ok([...secret.matchAll(/Test-LeanTpmProductionRootPolicy\.ps1/g)].length >= 2)
  assert.doesNotMatch(secret, /C:\\ProgramData\\LeanTPM/)
  assert.match(secret, /AllowNonProductionDataRoot[\s\S]{0,180}production/i)

  for (const relative of [
    'deploy/windows/Install-LeanTpmWindowsService.ps1',
    'deploy/windows/Stop-LeanTpmBackendFailClosed.ps1',
    'deploy/windows/Set-LeanTpmCurrentJunction.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /Test-LeanTpmProductionRootPolicy\.ps1/)
    assert.doesNotMatch(source, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)
  }
  const junction = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Set-LeanTpmCurrentJunction.ps1'), 'utf8',
  )
  assert.match(junction, /DeploymentLockToken/)
  assert.match(junction, /ExpectedHostLayoutSha256/)
  assert.match(junction, /ExpectedManifestSha256/)
  assert.match(junction, /deployment\.lock/)
  assert.match(junction, /release-manifest\.json/)
  assert.match(junction, /ComputeHash/)
  const installer = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(installer, /deployment\.lock/)
  assert.match(installer, /Flush\(\$true\)/)
  assert.ok([...installer.matchAll(/Test-LeanTpmProductionRootPolicy\.ps1/g)].length >= 2)
  const externalCaddy = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmExternalCaddyBinding.ps1'),
    'utf8',
  )
  assert.match(externalCaddy, /Win32_Service/)
  assert.match(externalCaddy, /Win32_Process/)
  assert.match(externalCaddy, /Get-NetTCPConnection/)
  assert.match(externalCaddy, /sc\.exe\s+sdshow/)
  assert.match(externalCaddy, /\badapt\s+--config/)
  assert.match(externalCaddy, /Caddyfile\.template/)
  assert.match(externalCaddy, /expectedCaddyfile/)
  assert.match(externalCaddy, /configBytes/)
  assert.match(externalCaddy, /serviceEnvironmentSha256/)
  assert.match(externalCaddy, /XDG_DATA_HOME/)
  assert.match(externalCaddy, /XDG_CONFIG_HOME/)
  assert.match(externalCaddy, /GetFinalPathNameByHandle/)
  assert.match(externalCaddy, /AreAccessRulesProtected/)
  assert.match(externalCaddy, /Assert-ProtectedRuntimeTree/)
  assert.match(externalCaddy, /Get-ChildItem/)
  assert.match(externalCaddy, /NumberOfLinks/)
  assert.match(externalCaddy, /GetRegistryKeyLastWriteUtc/)
  assert.match(externalCaddy, /processStartedAtUtc/)
  assert.match(externalCaddy, /runtimeFreshnessVerified/)
  assert.match(externalCaddy, /tlsDataRoot/)
  assert.match(externalCaddy, /logRoot/)
  assert.match(externalCaddy, /Get-NetFirewallRule/)
  assert.match(externalCaddy, /Get-NetFirewallAddressFilter/)
  assert.match(externalCaddy, /Get-NetFirewallApplicationFilter/)
  assert.match(externalCaddy, /Get-NetFirewallServiceFilter/)
  assert.match(externalCaddy, /Get-NetFirewallInterfaceFilter/)
  assert.match(externalCaddy, /Get-NetFirewallSecurityFilter/)
  assert.match(externalCaddy, /LocalOnlyMapping/)
  assert.match(externalCaddy, /LooseSourceMapping/)
  assert.match(externalCaddy, /DynamicTarget/)
  assert.match(externalCaddy, /RemoteDynamicKeywordAddresses/)
  assert.match(externalCaddy, /PolicyAppId/)
  assert.match(externalCaddy, /expectedServiceCommandLine/)
  assert.match(externalCaddy, /STANDBY_DISABLED/)
  assert.match(externalCaddy, /serviceIdentities\.proxy/)
  const externalCaddyParameters = externalCaddy.slice(
    0, externalCaddy.indexOf('$ErrorActionPreference'),
  )
  assert.doesNotMatch(externalCaddyParameters, /ObservationPath|AllowMock/)
  const failClosed = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Stop-LeanTpmBackendFailClosed.ps1'),
    'utf8',
  )
  assert.match(failClosed, /ContainmentOnly/)
  assert.match(failClosed, /Enable-NetFirewallRule/)
  assert.match(failClosed, /HOST_FIREWALL/)
  assert.match(failClosed, /RemoteDynamicKeywordAddresses/)
  assert.match(failClosed, /PolicyAppId/)
  assert.doesNotMatch(failClosed, /EXTERNAL_EXISTING proxy fail-closed binding is not implemented/)

  for (const relative of [
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    for (const field of [
      'database', 'mySqlHost', 'mySqlPort', 'expectedServerUuid', 'runtimeConfigSha256',
    ]) assert.match(source, new RegExp(field))
  }
  const recovery = fs.readFileSync(
    path.join(repositoryRoot, 'scripts', 'Resolve-LeanTpmRecovery.ps1'), 'utf8',
  )
  for (const field of [
    'database', 'mySqlHost', 'mySqlPort', 'expectedServerUuid', 'runtimeConfigSha256',
  ]) {
    assert.match(
      recovery,
      new RegExp(`recoveryState\\.${field}[\\s\\S]{0,160}plan\\.migration|recoveryState\\.${field}[\\s\\S]{0,160}plan\\.${field}`),
      `recovery marker does not bind ${field}`,
    )
  }
  assert.match(recovery, /\$mutationStarted\s*=\s*\$false/)
  const recoveryCatch = recovery.lastIndexOf('if ($mutationStarted)')
  const compensationStop = recovery.indexOf('Assert-ServiceStoppedAfterCompensation', recoveryCatch)
  const durableFailureState = recovery.indexOf("Write-RecoveryState 'RECOVERY_REQUIRED'", recoveryCatch)
  assert.ok(
    recoveryCatch >= 0 && compensationStop > recoveryCatch && durableFailureState > compensationStop,
    'recovery must persist the final authoritative isolation state after compensation',
  )
})

test('revalidates the fixed Backend service supply chain and fails loudly on compensation errors', () => {
  const bindingPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmWindowsServiceBinding.ps1',
  )
  assert.ok(fs.existsSync(bindingPath), 'shared Windows Service binding verifier is required')
  const binding = fs.readFileSync(bindingPath, 'utf8')
  assert.match(binding, /winSW\.sha256/)
  assert.match(binding, /Get-AuthenticodeSignature/)
  assert.match(binding, /Win32_Service/)
  assert.match(binding, /StartName/)
  assert.match(binding, /StartMode/)
  assert.match(binding, /sdshow/)
  assert.match(binding, /AreAccessRulesProtected/)
  const service = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(service, /Test-LeanTpmWindowsServiceBinding\.ps1/)
  const installer = fs.readFileSync(
    path.join(repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmWindowsService.ps1'), 'utf8',
  )
  assert.match(installer, /sdset/)
  assert.match(installer, /Test-LeanTpmWindowsServiceBinding\.ps1/)

  for (const relative of [
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
    'scripts/Resolve-LeanTpmRecovery.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /Assert-ServiceStoppedAfterCompensation/)
    assert.match(source, /COMPENSATION_FAILED|RECOVERY_COMPENSATION_FAILED/)
    assert.doesNotMatch(source, /try \{ \$null = Invoke-BackendService Stop \} catch \{ \}/)
  }
})

test('fails closed on ambiguous service stop and protects Backend secrets and proxy TLS state', () => {
  for (const relative of [
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
    'scripts/Resolve-LeanTpmRecovery.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /Assert-ServiceStoppedAfterCompensation/)
    assert.match(source, /Get-Service[\s\S]{0,120}-ErrorAction Stop/)
    assert.match(source, /Get-CimInstance[\s\S]{0,120}Win32_Service/)
    assert.doesNotMatch(
      source,
      /Assert-ServiceStoppedAfterCompensation[\s\S]{0,700}Get-Service[^\n]*SilentlyContinue/,
    )
  }

  const backendBinding = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmWindowsServiceBinding.ps1',
  ), 'utf8')
  assert.match(backendBinding, /function Assert-ProtectedFileAcl/)
  for (const protectedFile of [
    'wrapperPath', 'configPath', 'starterPath', 'trustPath', 'toolchainPath', 'javaPath',
  ]) assert.match(backendBinding, new RegExp(`Assert-ProtectedFileAcl[\\s\\S]{0,240}\\$${protectedFile}`))
  assert.match(backendBinding, /secrets[\s\S]{0,320}ExactReaders/)

  const proxyBindingPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmCaddyServiceBinding.ps1',
  )
  assert.ok(fs.existsSync(proxyBindingPath), 'Caddy requires a shared binding verifier')
  const proxyBinding = fs.readFileSync(proxyBindingPath, 'utf8')
  assert.match(proxyBinding, /Win32_Service/)
  assert.match(proxyBinding, /sdshow/)
  assert.match(proxyBinding, /AreAccessRulesProtected/)
  assert.match(proxyBinding, /proxyDataRoot[\s\S]{0,320}ExactReaders/)
  assert.match(proxyBinding, /publicHost/)

  const proxyInstaller = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmCaddyService.ps1',
  ), 'utf8')
  assert.match(proxyInstaller, /Test-LeanTpmWindowsServiceBinding\.ps1/)
  assert.match(proxyInstaller, /Test-LeanTpmCaddyServiceBinding\.ps1/)
  assert.ok(
    proxyInstaller.indexOf('Test-LeanTpmWindowsServiceBinding.ps1') <
      proxyInstaller.indexOf("New-Item -ItemType Directory"),
    'Backend binding must be verified before Caddy can mutate releases ACLs',
  )
  assert.match(proxyInstaller, /trust\.publicHost[\s\S]{0,180}SiteHost/)
  assert.match(proxyInstaller, /Administrators:\(OI\)\(CI\)F[\s\S]{0,180}BackendServiceAccount[\s\S]{0,180}ProxyServiceAccount/)
  const trustExample = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'release-trust.production.example.json',
  ), 'utf8')
  assert.match(trustExample, /"publicHost"/)
})

test('isolates public traffic when recovery compensation cannot stop the running backend', () => {
  const failClosedStopPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Stop-LeanTpmBackendFailClosed.ps1',
  )
  assert.ok(fs.existsSync(failClosedStopPath), 'fixed fail-closed stop helper is required')
  const failClosedStop = fs.readFileSync(failClosedStopPath, 'utf8')
  assert.match(failClosedStop, /Name='LeanTPM\.Backend'/)
  assert.match(failClosedStop, /Win32_Process/)
  assert.match(failClosedStop, /Stop-Process[\s\S]{0,120}-Force/)
  assert.match(failClosedStop, /Get-NetTCPConnection[\s\S]{0,160}BackendPort/)
  assert.match(failClosedStop, /LeanTPM\.Proxy/)
  assert.match(failClosedStop, /terminationFailures/)
  assert.match(failClosedStop, /proxyPublicPorts/)
  assert.match(failClosedStop, /caddy\.exe/)
  assert.match(failClosedStop, /PROXY_ISOLATED/)
  assert.match(failClosedStop, /CRITICAL/)
  for (const relative of [
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
    'scripts/Resolve-LeanTpmRecovery.ps1',
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Invoke-LeanTpmRollback.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    assert.match(source, /Stop-LeanTpmBackendFailClosed\.ps1/)
    assert.match(source, /PROXY_ISOLATED/)
  }
  for (const relative of [
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
    'scripts/Resolve-LeanTpmRecovery.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    const functionStart = source.indexOf('function Assert-ServiceStoppedAfterCompensation')
    const authoritativeCheck = source.indexOf('$failClosed =', functionStart)
    assert.ok(functionStart >= 0 && authoritativeCheck > functionStart)
    assert.doesNotMatch(
      source.slice(functionStart, authoritativeCheck), /\breturn\b/,
      `${relative} must not return on SCM state before the authoritative port check`,
    )
  }
  for (const [relative, ordinaryStop, resultVariable] of [
    ['scripts/Invoke-LeanTpmDeployment.ps1', "'STOP_NEW_SERVICE'", 'newServiceStopped'],
    ['scripts/Invoke-LeanTpmRollback.ps1', "'STOP_ROLLBACK_TARGET'", 'targetStopped'],
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    const ordinaryStopIndex = source.indexOf(ordinaryStop)
    const authoritativeCheck = source.indexOf('Invoke-FailClosedBackendStop', ordinaryStopIndex)
    assert.ok(ordinaryStopIndex >= 0 && authoritativeCheck > ordinaryStopIndex)
    assert.doesNotMatch(
      source.slice(ordinaryStopIndex, authoritativeCheck),
      new RegExp(`if \\(-not \\$${resultVariable}\\)`),
      `${relative} must always reconcile SCM success against the backend port`,
    )
  }
})

test('recovery restores an isolated external ingress before clearing the inhibit marker', () => {
  const recoveryPath = path.join(repositoryRoot, 'scripts', 'Resolve-LeanTpmRecovery.ps1')
  const recovery = fs.readFileSync(recoveryPath, 'utf8')
  const ingressPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Restore-LeanTpmExternalIngress.ps1',
  )
  assert.ok(fs.existsSync(ingressPath), 'external ingress recovery helper is required')
  const ingress = fs.readFileSync(ingressPath, 'utf8')

  assert.match(recovery, /-ContainmentOnly:\$containmentRecovery/)
  assert.match(recovery, /-RecoveryContainmentOnly:\$containmentRecovery/)
  assert.match(recovery, /isolationMethod/)
  assert.match(recovery, /Restore-LeanTpmExternalIngress\.ps1/)
  const startIndex = recovery.lastIndexOf('Wait-ReconciledReadiness')
  const restoreIndex = recovery.lastIndexOf('Restore-ExternalIngressIfRequired')
  assert.ok(startIndex >= 0 && restoreIndex > startIndex)
  assert.ok(
    recovery.indexOf('Restore-LeanTpmExternalIngress.ps1') <
      recovery.lastIndexOf('Delete($recoveryMarker)'),
    'recovery must restore public ingress before clearing the inhibit marker',
  )
  assert.match(ingress, /Start-Service/)
  assert.match(ingress, /Disable-NetFirewallRule/)
  assert.match(ingress, /Test-LeanTpmExternalCaddyBinding\.ps1/)
  assert.match(ingress, /https:\/\//)
  assert.match(ingress, /PlanOnly/)
  assert.match(ingress, /isolationMethod/)
  assert.match(ingress, /RecoveryMarkerPath/)
  const guardIndex = ingress.indexOf('Enable-NetFirewallRule')
  const guardedStartIndex = ingress.indexOf('Start-Service')
  const quiescedBindingIndex = ingress.indexOf('-RuntimeTreeMode QUIESCED_TREE')
  const guardedBindingIndex = ingress.indexOf(
    '-RuntimeTreeMode ROOTS_ONLY', guardedStartIndex,
  )
  const unguardIndex = ingress.indexOf('Disable-NetFirewallRule')
  assert.ok(
    guardIndex >= 0 && quiescedBindingIndex > guardIndex &&
      guardedStartIndex > quiescedBindingIndex &&
      guardedBindingIndex > guardedStartIndex && unguardIndex > guardedBindingIndex,
    'public ingress must remain under the exact firewall guard until Caddy binding passes',
  )
  const compensationIndex = ingress.indexOf('catch {', unguardIndex)
  assert.ok(compensationIndex > unguardIndex)
  assert.ok(
    ingress.indexOf('Enable-NetFirewallRule', compensationIndex) > compensationIndex,
    'any ingress restore failure must first re-establish the exact firewall guard',
  )
  assert.ok(
    ingress.indexOf("$report.isolationMethod = 'SERVICE_STOP'", compensationIndex) >
      compensationIndex,
    'service-stop isolation must remain an authoritative fallback',
  )
  assert.match(recovery, /INGRESS_RESTORE_FAILED/)
  assert.match(recovery, /\$script:lastIngressIsolation = \$ingress/)
  const serviceControl = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmWindowsService.ps1',
  ), 'utf8')
  assert.match(serviceControl, /RecoveryContainmentOnly/)
  assert.match(serviceControl, /Recovery containment marker is not bound/)
})

test('executes external ingress recovery and preserves isolation across injected failures', () => {
  const success = invokeExternalIngressRecoveryHarness('SUCCESS')
  assert.equal(success.result.status, 0, combinedOutput(success.result))
  assert.deepEqual(JSON.parse(success.result.stdout.trim()), {
    status: 'INGRESS_RESTORED',
    isolationMethod: null,
    serviceId: 'caddy',
    isolatedServiceId: 'caddy',
    firewallRuleGroup: 'LeanTPM-Public-Isolation',
    firewallPolicySha256: 'b'.repeat(64),
    proxyBindingSha256: 'a'.repeat(64),
    recoveryStateSha256: JSON.parse(success.result.stdout.trim()).recoveryStateSha256,
    steps: [
      'ENABLE_AND_VERIFY_EXACT_FIREWALL_GUARD',
      'STOP_AND_PROVE_EXTERNAL_CADDY_QUIESCENT',
      'VERIFY_MUTABLE_RUNTIME_TREE_WHILE_QUIESCENT',
      'START_EXACT_EXTERNAL_CADDY',
      'VERIFY_EXTERNAL_CADDY_BEHIND_GUARD',
      'DISABLE_EXACT_FIREWALL_GUARD',
      'VERIFY_EXTERNAL_CADDY_BINDING',
      'VERIFY_PUBLIC_HTTPS',
    ],
    runtimeTreeQuiescenceVerified: true,
    runtimeTreeScanCompletedAtUtc:
      JSON.parse(success.result.stdout.trim()).runtimeTreeScanCompletedAtUtc,
    publicHttpsVerified: true,
    publicUri: 'https://tpm.example.test/',
  })
  assert.deepEqual(success.events, [
    'ENABLE_FIREWALL',
    'VERIFY_FIREWALL:ACTIVE',
    'STOP_SERVICE',
    'WAIT_SERVICE:Stopped',
    'VERIFY_SCM',
    'VERIFY_PROCESS',
    'VERIFY_LISTENERS',
    'VERIFY_BINDING:QUIESCED_TREE:ACTIVE',
    'START_SERVICE',
    'WAIT_SERVICE:Running',
    'VERIFY_BINDING:ROOTS_ONLY:ACTIVE',
    'DISABLE_FIREWALL',
    'VERIFY_BINDING:ROOTS_ONLY:STANDBY_DISABLED',
    'VERIFY_HTTPS',
  ])

  for (const scenario of [
    'QUIESCED_SCAN_FAIL',
    'QUIESCED_FALSE_REPORT',
    'START_FAIL',
    'WAIT_RUNNING_FAIL',
    'STALE_PID_START',
    'GUARDED_BINDING_FAIL',
    'DISABLE_FAIL',
    'STANDBY_BINDING_FAIL',
    'HTTPS_FAIL',
  ]) {
    const guardedFailure = invokeExternalIngressRecoveryHarness(scenario)
    assert.equal(guardedFailure.result.status, 0, combinedOutput(guardedFailure.result))
    const report = JSON.parse(guardedFailure.result.stdout.trim())
    assert.equal(report.status, 'INGRESS_RESTORE_FAILED')
    assert.equal(report.isolationMethod, 'HOST_FIREWALL')
    assert.equal(report.firewallPolicySha256, 'b'.repeat(64))
    assert.equal(guardedFailure.events.at(-2), 'ENABLE_FIREWALL')
    assert.equal(guardedFailure.events.at(-1), 'VERIFY_FIREWALL:ACTIVE')
    if (scenario === 'QUIESCED_SCAN_FAIL' || scenario === 'QUIESCED_FALSE_REPORT') {
      assert.equal(guardedFailure.events.includes('START_SERVICE'), false)
      assert.equal(guardedFailure.events.includes('DISABLE_FIREWALL'), false)
    }
    if (scenario === 'START_FAIL' || scenario === 'WAIT_RUNNING_FAIL') {
      assert.equal(guardedFailure.events.includes('START_SERVICE'), true)
      assert.equal(guardedFailure.events.includes('DISABLE_FIREWALL'), false)
    }
    if (scenario === 'STALE_PID_START') {
      assert.equal(guardedFailure.events.includes('START_SERVICE'), true)
      assert.equal(guardedFailure.events.includes('DISABLE_FIREWALL'), false)
    }
    if (scenario === 'DISABLE_FAIL') {
      assert.equal(guardedFailure.events.includes('DISABLE_FIREWALL'), true)
      assert.equal(
        guardedFailure.events.includes('VERIFY_BINDING:ROOTS_ONLY:STANDBY_DISABLED'),
        false,
      )
      assert.equal(guardedFailure.events.includes('VERIFY_HTTPS'), false)
    }
  }

  const serviceFallback = invokeExternalIngressRecoveryHarness('REENABLE_FAIL')
  assert.equal(serviceFallback.result.status, 0, combinedOutput(serviceFallback.result))
  const serviceFallbackReport = JSON.parse(serviceFallback.result.stdout.trim())
  assert.equal(serviceFallbackReport.status, 'INGRESS_RESTORE_FAILED')
  assert.equal(serviceFallbackReport.isolationMethod, 'SERVICE_STOP')
  assert.ok(serviceFallback.events.includes('STOP_SERVICE'))
  for (const evidenceEvent of ['VERIFY_SCM', 'VERIFY_PROCESS', 'VERIFY_LISTENERS']) {
    assert.ok(serviceFallback.events.includes(evidenceEvent))
  }

  const verificationFailure = invokeExternalIngressRecoveryHarness('STOP_VERIFICATION_FAIL')
  assert.notEqual(verificationFailure.result.status, 0)
  assert.match(
    combinedOutput(verificationFailure.result),
    /CRITICAL_INGRESS_ISOLATION_UNPROVEN/,
  )
  assert.ok(verificationFailure.events.includes('VERIFY_SCM'))
  assert.equal(
    verificationFailure.events.some((event) => event.startsWith('VERIFY_BINDING:')),
    false,
  )
  assert.equal(verificationFailure.events.includes('START_SERVICE'), false)
  assert.equal(verificationFailure.events.includes('DISABLE_FIREWALL'), false)

  const critical = invokeExternalIngressRecoveryHarness('DOUBLE_FAIL')
  assert.notEqual(critical.result.status, 0)
  assert.match(combinedOutput(critical.result), /CRITICAL_INGRESS_ISOLATION_UNPROVEN/)
  assert.ok(critical.events.includes('STOP_SERVICE'))
})

test('normalizes every promoted release ACL and revalidates bytes before migration', () => {
  const protectorPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Protect-LeanTpmReleaseDirectory.ps1',
  )
  assert.ok(fs.existsSync(protectorPath), 'release ACL normalization helper is required')
  const protector = fs.readFileSync(protectorPath, 'utf8')
  assert.match(protector, /AreAccessRulesProtected/)
  assert.match(protector, /ReparsePoint/)
  assert.match(protector, /DeleteSubdirectoriesAndFiles/)
  assert.match(protector, /ReadAndExecute/)
  assert.match(protector, /Join-Path \$payloadRoot 'web'/)

  for (const relative of [
    'scripts/Invoke-LeanTpmDeployment.ps1',
    'scripts/Initialize-LeanTpmFirstRelease.ps1',
  ]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), 'utf8')
    const moved = source.indexOf('Move-Item -LiteralPath $stageRoot -Destination $releaseRoot')
    const protectedRelease = source.indexOf('Protect-LeanTpmReleaseDirectory.ps1', moved)
    const revalidated = source.indexOf('Test-ReleaseManifest.ps1', protectedRelease)
    const migration = source.indexOf("Write-RecoveryState 'MIGRATION_IN_PROGRESS'", protectedRelease)
    assert.ok(moved >= 0 && protectedRelease > moved, `${relative} must normalize ACL after promotion`)
    assert.ok(revalidated > protectedRelease, `${relative} must revalidate release bytes after ACL changes`)
    assert.ok(migration > revalidated, `${relative} must finish ACL and byte gates before DB writes`)
  }
})

test('requires full RX and treats DeleteChild as write access in service ACL gates', () => {
  const backendBinding = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmWindowsServiceBinding.ps1',
  ), 'utf8')
  const proxyBinding = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmCaddyServiceBinding.ps1',
  ), 'utf8')
  const releaseProtector = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Protect-LeanTpmReleaseDirectory.ps1',
  ), 'utf8')
  for (const source of [backendBinding, proxyBinding, releaseProtector]) {
    assert.match(source, /DeleteSubdirectoriesAndFiles/)
    assert.match(source, /ReadAndExecute/)
    assert.match(source, /requiredReadAndExecuteFound/)
    const writeMasks = source.match(/\$writeMask\s*=[\s\S]*?TakeOwnership/g) || []
    assert.ok(writeMasks.length > 0, 'ACL verifier must define a forbidden write mask')
    for (const writeMask of writeMasks) {
      assert.doesNotMatch(
        writeMask,
        /FileSystemRights\]::(?:Write|Modify|FullControl)(?![A-Za-z])/,
        'composite rights include read bits and cannot be used as a write mask',
      )
      for (const atomicRight of [
        'WriteData', 'AppendData', 'WriteExtendedAttributes', 'WriteAttributes',
        'Delete', 'DeleteSubdirectoriesAndFiles', 'ChangePermissions', 'TakeOwnership',
      ]) assert.match(writeMask, new RegExp(`FileSystemRights\\]::${atomicRight}`))
    }
  }

  const aclEnumCheck = spawnSync(powershell, [
    '-NoProfile', '-Command',
    `$mask=[Security.AccessControl.FileSystemRights]::WriteData -bor ` +
      `[Security.AccessControl.FileSystemRights]::AppendData -bor ` +
      `[Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor ` +
      `[Security.AccessControl.FileSystemRights]::WriteAttributes -bor ` +
      `[Security.AccessControl.FileSystemRights]::Delete -bor ` +
      `[Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor ` +
      `[Security.AccessControl.FileSystemRights]::ChangePermissions -bor ` +
      `[Security.AccessControl.FileSystemRights]::TakeOwnership; ` +
      `$rx=[Security.AccessControl.FileSystemRights]::ReadAndExecute; ` +
      `if(([int64]$rx -band [int64]$mask)-ne 0){exit 21}; ` +
      `if(([int64][Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles ` +
      `-band [int64]$mask)-eq 0){exit 22}`,
  ], { encoding: 'utf8' })
  assert.equal(aclEnumCheck.status, 0, combinedOutput(aclEnumCheck))
})

test('installs the proxy disabled and removes an owned registration on final gate failure', () => {
  const proxyTemplate = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'LeanTPM.Proxy.xml.template',
  ), 'utf8')
  assert.match(proxyTemplate, /<startmode>Disabled<\/startmode>/)
  assert.doesNotMatch(proxyTemplate, /<startmode>Automatic<\/startmode>/)

  const proxyBinding = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmCaddyServiceBinding.ps1',
  ), 'utf8')
  assert.match(proxyBinding, /AllowDisabledForInstallation/)

  const installer = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmCaddyService.ps1',
  ), 'utf8')
  assert.match(installer, /\$ownsNewService\s*=\s*\$false/)
  assert.match(installer, /AllowDisabledForInstallation/)
  assert.match(installer, /sc\.exe config \$serviceId start= delayed-auto/)
  assert.match(installer, /sc\.exe config \$serviceId start= disabled/)
  assert.match(installer, /catch[\s\S]{0,1200}\$targetWrapper uninstall/)
  assert.match(installer, /\$remainingProxy[\s\S]{0,600}must not remain registered/i)
  assert.match(installer, /remainingProxy\.StartMode[\s\S]{0,300}Disabled/)
  assert.match(installer, /remainingProxy\.State[\s\S]{0,300}Stopped/)
  assert.match(installer, /remainingProxy\.ProcessId[\s\S]{0,300}-ne 0/)
})

test('inventories legacy D layout without touching MySQL data or legacy files', () => {
  const schemaPath = path.join(repositoryRoot, 'release', 'legacy-layout-inventory.schema.json')
  const scriptPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Get-LeanTpmLegacyLayoutInventory.ps1',
  )
  assert.ok(fs.existsSync(schemaPath), 'machine-readable legacy inventory schema is required')
  assert.ok(fs.existsSync(scriptPath), 'read-only legacy inventory command is required')
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'))
  assert.equal(schema.properties.canonicalRoots.additionalProperties, false)
  assert.deepEqual(schema.properties.canonicalRoots.required, [
    'installRootExists', 'dataRootExists', 'bothAbsent', 'missingAllowed',
  ])

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-legacy-layout-'))
  try {
    const installRoot = path.join(temporaryRoot, 'App')
    const dataRoot = path.join(temporaryRoot, 'Runtime')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    fs.mkdirSync(path.join(temporaryRoot, 'data', 'mysql', 'Data'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'data', 'mysql', 'Data', 'ibdata1'), 'preserve')

    const beforePreserve = snapshotTree(temporaryRoot)
    const preserveOnly = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(preserveOnly.status, 0, combinedOutput(preserveOnly))
    const preserveReport = JSON.parse(preserveOnly.stdout.trim())
    assert.equal(preserveReport.status, 'PASS')
    assert.equal(preserveReport.readOnly, true)
    assert.equal(preserveReport.legacyRoot, temporaryRoot)
    assert.equal(
      preserveReport.entries.find((entry) => entry.relativePath === 'data').classification,
      'PRESERVE_EXTERNAL',
    )
    assert.deepEqual(snapshotTree(temporaryRoot), beforePreserve)

    fs.rmSync(installRoot, { recursive: true })
    fs.rmSync(dataRoot, { recursive: true })
    const beforeMissingRoots = snapshotTree(temporaryRoot)
    const missingRootsWithoutApproval = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(missingRootsWithoutApproval.status, 0)
    assert.deepEqual(snapshotTree(temporaryRoot), beforeMissingRoots)

    const missingRoots = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowMissingCanonicalRoots',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(missingRoots.status, 0, combinedOutput(missingRoots))
    const missingRootsReport = JSON.parse(missingRoots.stdout.trim())
    assert.equal(missingRootsReport.status, 'PASS')
    assert.equal(missingRootsReport.installRoot, installRoot)
    assert.equal(missingRootsReport.dataRoot, dataRoot)
    assert.deepEqual(missingRootsReport.canonicalRoots, {
      installRootExists: false,
      dataRootExists: false,
      bothAbsent: true,
      missingAllowed: true,
    })
    assert.equal(
      missingRootsReport.entries.find((entry) => entry.relativePath === 'data').classification,
      'PRESERVE_EXTERNAL',
    )
    assert.deepEqual(snapshotTree(temporaryRoot), beforeMissingRoots)

    const executableCustomMissingRoots = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowMissingCanonicalRoots',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(executableCustomMissingRoots.status, 0)
    assert.deepEqual(snapshotTree(temporaryRoot), beforeMissingRoots)

    fs.mkdirSync(installRoot)
    const oneMissingRoot = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-AllowMissingCanonicalRoots',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(oneMissingRoot.status, 0)
    fs.rmSync(installRoot, { recursive: true })
    assert.deepEqual(snapshotTree(temporaryRoot), beforeMissingRoots)

    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)

    fs.mkdirSync(path.join(temporaryRoot, 'current', 'frontend'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'current', 'frontend', 'index.html'), 'legacy web')
    fs.mkdirSync(path.join(temporaryRoot, 'shared', 'config'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'shared', 'config', 'leantpm.env'), 'legacy config')
    const beforeBlocked = snapshotTree(temporaryRoot)
    const blocked = invokePowerShell(scriptPath, [
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(blocked.status, 0, combinedOutput(blocked))
    const blockedReport = JSON.parse(blocked.stdout.trim())
    assert.equal(blockedReport.status, 'IMPORT_REQUIRED')
    assert.equal(blockedReport.blockingCount, 2)
    assert.deepEqual(snapshotTree(temporaryRoot), beforeBlocked)

    const firstInstall = fs.readFileSync(
      path.join(repositoryRoot, 'scripts', 'Initialize-LeanTpmFirstRelease.ps1'), 'utf8',
    )
    assert.match(firstInstall, /Get-LeanTpmLegacyLayoutInventory\.ps1/)
    assert.ok(
      [...firstInstall.matchAll(/Assert-LegacyLayoutReady/g)].length >= 3,
      'first install must check legacy state before approval and again inside the global lock',
    )
    assert.match(firstInstall, /IMPORT_REQUIRED/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('validates a caller-bound HostBootstrap initialization plan without self-authorizing trust', () => {
  const authoritySchemaPath = path.join(
    repositoryRoot, 'release', 'host-bootstrap-init-authority.schema.json',
  )
  const planSchemaPath = path.join(
    repositoryRoot, 'release', 'host-bootstrap-initialization-plan.schema.json',
  )
  const validatorPath = path.join(
    repositoryRoot, 'deploy', 'windows',
    'Test-LeanTpmHostBootstrapInitializationPlan.ps1',
  )
  const authorityValidatorPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmHostBootstrapInitAuthority.ps1',
  )
  assert.ok(fs.existsSync(authoritySchemaPath), 'external initialization authority schema is required')
  assert.ok(fs.existsSync(planSchemaPath), 'HostBootstrap initialization plan schema is required')
  assert.ok(fs.existsSync(validatorPath), 'non-executable initialization plan validator is required')
  assert.ok(
    fs.existsSync(authorityValidatorPath),
    'fixed external initialization authority validator is required',
  )

  const authoritySchema = JSON.parse(fs.readFileSync(authoritySchemaPath, 'utf8'))
  const planSchema = JSON.parse(fs.readFileSync(planSchemaPath, 'utf8'))
  assert.equal(authoritySchema.additionalProperties, false)
  assert.equal(planSchema.additionalProperties, false)
  assert.equal(planSchema.properties.operation.const, 'HOST_BOOTSTRAP_INITIALIZE')
  assert.equal(planSchema.properties.roots.properties.installRoot.const, 'D:\\LeanTPM\\App')
  assert.equal(planSchema.properties.roots.properties.dataRoot.const, 'D:\\LeanTPM\\Runtime')
  assert.equal(
    planSchema.properties.roots.properties.preservedMySqlDataRoot.const,
    'D:\\LeanTPM\\data',
  )
  assert.equal(
    Object.prototype.hasOwnProperty.call(planSchema.properties, 'authorityPath'), false,
    'a signed plan must not choose its own trust-anchor path',
  )

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-bootstrap-init-plan-'))
  try {
    const issuedAt = new Date(Date.now() - 60_000)
    const expiresAt = new Date(Date.now() + 30 * 60_000)
    const hash = (character) => character.repeat(64)
    const descriptor = (targetPath, character) => ({
      targetPath,
      sha256: hash(character),
    })
    const authority = {
      schemaVersion: 1,
      authorityId: 'leantpm-bootstrap-init-authority',
      purpose: 'HOST_BOOTSTRAP_INITIALIZATION',
      status: 'ACTIVE',
      environmentId: 'leantpm-production-cn',
      hostId: hash('1'),
      notBeforeUtc: new Date(Date.now() - 60_000).toISOString(),
      notAfterUtc: new Date(Date.now() + 24 * 60 * 60_000).toISOString(),
      allowedActions: [
        'INITIALIZE_HOST_BOOTSTRAP',
        'ADOPT_EXTERNAL_CADDY',
        'RECOVER_HOST_BOOTSTRAP',
      ],
      initializerScriptSha256: hash('f'),
      cmsEkuOid: '1.3.6.1.5.5.7.3.3',
      revocationMode: 'ONLINE_FAIL_CLOSED_ENTIRE_CHAIN',
      planMaxValidityMinutes: 1440,
      requesterSigners: [{
        identity: 'release.requester@example.com',
        certificateThumbprint: 'B'.repeat(40),
        certificateSha256: hash('b'),
      }],
      approverSigners: [{
        identity: 'release.approver@example.com',
        certificateThumbprint: 'C'.repeat(40),
        certificateSha256: hash('c'),
      }],
      executorSigners: [{ certificateThumbprint: 'A'.repeat(40), certificateSha256: hash('a') }],
      receiptSigners: [{ certificateThumbprint: 'D'.repeat(40), certificateSha256: hash('d') }],
      minimumApprovals: { requester: 1, approver: 1 },
      requireDistinctIdentity: true,
      requireDistinctCertificate: true,
      initPlanSchemaSha256: hash('e'),
      initReceiptSchemaSha256: hash('f'),
    }
    const authorityPath = path.join(temporaryRoot, 'init-trust.json')
    fs.writeFileSync(authorityPath, JSON.stringify(authority), 'utf8')
    const authoritySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(authorityPath)).digest('hex')
    const authorityResult = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', authoritySha256,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(authorityResult.status, 0, combinedOutput(authorityResult))
    const authorityReport = JSON.parse(authorityResult.stdout.trim())
    assert.equal(authorityReport.status, 'INPUT_REQUIRED')
    assert.equal(authorityReport.executable, false)
    assert.equal(authorityReport.trustSource, 'CALLER_SUPPLIED_AUTHORITY_PLAN_ONLY')
    assert.equal(authorityReport.cryptographicallyTrusted, false)
    assert.equal(
      authorityReport.fixedAuthorityPath,
      'C:\\ProgramData\\LeanTPM-bootstrap-authority\\init-trust.json',
    )

    const executableCustomAuthority = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', authoritySha256,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(executableCustomAuthority.status, 0)

    const sameSignerAuthority = structuredClone(authority)
    sameSignerAuthority.approverSigners[0].certificateThumbprint =
      sameSignerAuthority.requesterSigners[0].certificateThumbprint
    sameSignerAuthority.approverSigners[0].certificateSha256 =
      sameSignerAuthority.requesterSigners[0].certificateSha256
    fs.writeFileSync(authorityPath, JSON.stringify(sameSignerAuthority), 'utf8')
    const sameSignerSha = crypto.createHash('sha256')
      .update(fs.readFileSync(authorityPath)).digest('hex')
    const sameSigner = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', sameSignerSha,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(sameSigner.status, 0)

    const stringPolicyAuthority = structuredClone(authority)
    stringPolicyAuthority.requireDistinctIdentity = 'false'
    stringPolicyAuthority.planMaxValidityMinutes = '1440'
    fs.writeFileSync(authorityPath, JSON.stringify(stringPolicyAuthority), 'utf8')
    const stringPolicySha = crypto.createHash('sha256')
      .update(fs.readFileSync(authorityPath)).digest('hex')
    const stringPolicy = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', stringPolicySha,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(stringPolicy.status, 0)

    const numericIdentityAuthority = structuredClone(authority)
    numericIdentityAuthority.requesterSigners[0].identity = 123
    fs.writeFileSync(authorityPath, JSON.stringify(numericIdentityAuthority), 'utf8')
    const numericIdentitySha = crypto.createHash('sha256')
      .update(fs.readFileSync(authorityPath)).digest('hex')
    const numericIdentity = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', numericIdentitySha,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(numericIdentity.status, 0)

    const scalarSignerAuthority = structuredClone(authority)
    scalarSignerAuthority.requesterSigners = scalarSignerAuthority.requesterSigners[0]
    fs.writeFileSync(authorityPath, JSON.stringify(scalarSignerAuthority), 'utf8')
    const scalarSignerSha = crypto.createHash('sha256')
      .update(fs.readFileSync(authorityPath)).digest('hex')
    const scalarSigner = invokePowerShell(authorityValidatorPath, [
      '-AuthorityPath', authorityPath,
      '-ExpectedAuthoritySha256', scalarSignerSha,
      '-ExpectedEnvironmentId', authority.environmentId,
      '-ExpectedHostId', authority.hostId,
      '-ExpectedInitializerScriptSha256', authority.initializerScriptSha256,
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(scalarSigner.status, 0)
    fs.writeFileSync(authorityPath, JSON.stringify(authority), 'utf8')
    const validPlan = {
      schemaVersion: 1,
      operation: 'HOST_BOOTSTRAP_INITIALIZE',
      initializationId: 'bootstrap-init-contract-001',
      environmentId: 'leantpm-production-cn',
      hostId: hash('1'),
      volumeIdentity: `sha256:${hash('2')}`,
      bootstrapVolumeIdentity: `sha256:${hash('0')}`,
      issuedAtUtc: issuedAt.toISOString(),
      expiresAtUtc: expiresAt.toISOString(),
      nonce: 'bootstrap-init-nonce-contract-001',
      requestedBy: 'release.requester@example.com',
      approvedBy: 'release.approver@example.com',
      authoritySha256,
      discovery: {
        sha256: hash('4'),
        collectedAtUtc: issuedAt.toISOString(),
        discoveryMode: 'LIVE',
      },
      legacyInventory: {
        sha256: hash('5'),
        status: 'PASS',
        readOnly: true,
        installRootExists: false,
        dataRootExists: false,
        preservedDataAction: 'PRESERVE_EXTERNAL',
      },
      roots: {
        bootstrapRoot: 'C:\\ProgramData\\LeanTPM-bootstrap',
        bootstrapStateRoot: 'C:\\ProgramData\\LeanTPM-bootstrap-state',
        installRoot: 'D:\\LeanTPM\\App',
        dataRoot: 'D:\\LeanTPM\\Runtime',
        preservedMySqlDataRoot: 'D:\\LeanTPM\\data',
      },
      expectedHostState: {
        umbrellaState: 'PREEXISTING_ALREADY_COMPLIANT',
        canonicalRootsState: 'BOTH_ABSENT',
        preservedDataAclState: 'VERIFIED_PROTECTED_INDEPENDENT',
      },
      inputs: {
        hostLayout: descriptor('C:\\ProgramData\\LeanTPM-bootstrap\\host-layout.json', '6'),
        releaseTrust: descriptor('D:\\LeanTPM\\Runtime\\config\\release-trust.json', '7'),
        externalCaddyBinding: descriptor(
          'D:\\LeanTPM\\Runtime\\config\\external-caddy-binding.json', '8',
        ),
        externalFirewall: descriptor(
          'D:\\LeanTPM\\Runtime\\config\\external-caddy-firewall.json', '9',
        ),
        backupProtection: descriptor(
          'D:\\LeanTPM\\Runtime\\config\\backup-protection.json', 'a',
        ),
      },
      identities: {
        backendServiceAccount: 'CONTOSO\\leantpm-backend$',
        backendServiceSid: 'S-1-5-21-1000-1000-1000-1001',
        proxyServiceAccount: 'CONTOSO\\leantpm-proxy$',
        proxyServiceSid: 'S-1-5-21-1000-1000-1000-1002',
        publicHost: 'tpm.example.com',
      },
      toolchain: {
        toolchainLockSha256: hash('a'),
        javaSha256: hash('b'),
        winSWSha256: hash('c'),
        caddySha256: hash('d'),
        hBuilderCompilerDigest: hash('e'),
      },
      executor: {
        scriptSha256: hash('f'),
        scriptSignerThumbprint: 'A'.repeat(40),
      },
      externalCaddyAdoption: {
        required: true,
        observedStateSha256: hash('1'),
        adoptionPlanSha256: hash('2'),
        executeSeparately: true,
      },
      constraints: {
        preserveMySqlData: true,
        neverFollowReparsePoints: true,
        neverOverwriteExistingTargets: true,
        externalCaddyAdoptionSeparate: true,
        liveDiscoveryRevalidationRequired: true,
        liveLegacyInventoryRevalidationRequired: true,
        nonceReservationBeforeMutation: true,
      },
    }

    const invokePlan = (plan) => {
      const planPath = path.join(temporaryRoot, 'initialization-plan.json')
      fs.writeFileSync(planPath, JSON.stringify(plan), 'utf8')
      const expectedSha = crypto.createHash('sha256').update(fs.readFileSync(planPath)).digest('hex')
      return invokePowerShell(validatorPath, [
        '-PlanPath', planPath,
        '-ExpectedPlanSha256', expectedSha,
        '-PlanOnly',
        '-OutputFormat', 'Json',
      ])
    }

    const before = snapshotTree(temporaryRoot)
    const accepted = invokePlan(validPlan)
    assert.equal(accepted.status, 0, combinedOutput(accepted))
    const report = JSON.parse(accepted.stdout.trim())
    assert.equal(report.status, 'INPUT_REQUIRED')
    assert.equal(report.executable, false)
    assert.equal(report.trustSource, 'CALLER_BOUND_PLAN_ONLY')
    assert.equal(
      report.approvalReadiness,
      'FIXED_AUTHORITY_DOUBLE_CMS_AND_LIVE_REVALIDATION_REQUIRED',
    )
    assert.equal(
      report.authorityPath,
      'C:\\ProgramData\\LeanTPM-bootstrap-authority\\init-trust.json',
    )
    const afterAccepted = snapshotTree(temporaryRoot)
    assert.deepEqual(
      afterAccepted.filter((entry) => entry.path !== 'initialization-plan.json'),
      before,
    )

    for (const mutate of [
      (plan) => { plan.authorityPath = 'D:\\LeanTPM\\Runtime\\config\\release-trust.json' },
      (plan) => { plan.roots.installRoot = 'D:\\LeanTPM2\\App' },
      (plan) => { plan.requestedBy = plan.approvedBy },
      (plan) => { plan.discovery.discoveryMode = 'PLAN_ONLY_FIXTURE' },
      (plan) => { plan.expiresAtUtc = new Date(Date.now() - 1000).toISOString() },
      (plan) => { plan.inputs.releaseTrust.targetPath = 'D:\\LeanTPM\\data\\release-trust.json' },
      (plan) => { plan.constraints.preserveMySqlData = 'false' },
      (plan) => { plan.externalCaddyAdoption.required = 'false' },
      (plan) => { plan.legacyInventory.readOnly = 'true' },
      (plan) => { plan.initializationId = 123 },
      (plan) => { plan.requestedBy = 456 },
    ]) {
      const forged = structuredClone(validPlan)
      mutate(forged)
      const rejected = invokePlan(forged)
      assert.notEqual(rejected.status, 0, combinedOutput(rejected))
    }
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('validates a non-production-ready HostBootstrap initialization receipt and journal contract', () => {
  const receiptSchemaPath = path.join(
    repositoryRoot, 'release', 'host-bootstrap-initialization-receipt.schema.json',
  )
  const journalSchemaPath = path.join(
    repositoryRoot, 'release', 'host-bootstrap-initialization-journal-event.schema.json',
  )
  const validatorPath = path.join(
    repositoryRoot, 'deploy', 'windows',
    'Test-LeanTpmHostBootstrapInitializationReceipt.ps1',
  )
  assert.ok(fs.existsSync(receiptSchemaPath), 'initialization receipt schema is required')
  assert.ok(fs.existsSync(journalSchemaPath), 'initialization journal event schema is required')
  assert.ok(fs.existsSync(validatorPath), 'initialization receipt validator is required')
  const receiptSchema = JSON.parse(fs.readFileSync(receiptSchemaPath, 'utf8'))
  const journalSchema = JSON.parse(fs.readFileSync(journalSchemaPath, 'utf8'))
  assert.equal(receiptSchema.additionalProperties, false)
  assert.deepEqual(receiptSchema.properties.productionReady, { const: false })
  assert.deepEqual(receiptSchema.properties.externalCaddyAdopted, { const: false })
  assert.deepEqual(receiptSchema.properties.mysqlDataTouched, { const: false })
  assert.ok(
    journalSchema.properties.state.enum.includes('INITIALIZATION_RECOVERY_REQUIRED'),
  )

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-bootstrap-receipt-'))
  try {
    const hash = (character) => character.repeat(64)
    const receipt = {
      schemaVersion: 1,
      operation: 'HOST_BOOTSTRAP_INITIALIZE',
      initializationId: 'bootstrap-init-contract-001',
      status: 'BOOTSTRAP_COMMITTED_ADOPTION_REQUIRED',
      productionReady: false,
      externalCaddyAdopted: false,
      mysqlDataTouched: false,
      planSha256: hash('1'),
      authoritySha256: hash('2'),
      nonce: 'bootstrap-init-nonce-contract-001',
      environmentId: 'leantpm-production-cn',
      hostId: hash('3'),
      volumeIdentity: `sha256:${hash('4')}`,
      bootstrapVolumeIdentity: `sha256:${hash('0')}`,
      startedAtUtc: new Date(Date.now() - 60_000).toISOString(),
      completedAtUtc: new Date().toISOString(),
      requesterCertificateThumbprint: 'A'.repeat(40),
      approverCertificateThumbprint: 'B'.repeat(40),
      executorScriptSha256: hash('5'),
      stateJournalSha256: hash('6'),
      roots: {
        bootstrapRoot: 'C:\\ProgramData\\LeanTPM-bootstrap',
        bootstrapStateRoot: 'C:\\ProgramData\\LeanTPM-bootstrap-state',
        installRoot: 'D:\\LeanTPM\\App',
        dataRoot: 'D:\\LeanTPM\\Runtime',
        preservedMySqlDataRoot: 'D:\\LeanTPM\\data',
      },
      artifacts: {
        hostLayoutSha256: hash('7'),
        releaseTrustSha256: hash('8'),
        externalCaddyBindingSha256: hash('9'),
        externalFirewallSha256: hash('a'),
        backupProtectionSha256: hash('b'),
        toolchainLockSha256: hash('c'),
      },
      createdObjects: [
        {
          path: 'C:\\ProgramData\\LeanTPM-bootstrap',
          finalPath: 'C:\\ProgramData\\LeanTPM-bootstrap',
          volumeIdentity: `sha256:${hash('0')}`,
          fileId: '00000000000000000000000000000001',
          aclSha256: hash('d'),
          objectKind: 'DIRECTORY',
          contentSha256: hash('1'),
        },
        {
          path: 'D:\\LeanTPM\\App',
          finalPath: 'D:\\LeanTPM\\App',
          volumeIdentity: `sha256:${hash('4')}`,
          fileId: '00000000000000000000000000000002',
          aclSha256: hash('e'),
          objectKind: 'DIRECTORY',
          contentSha256: hash('2'),
        },
        {
          path: 'D:\\LeanTPM\\Runtime',
          finalPath: 'D:\\LeanTPM\\Runtime',
          volumeIdentity: `sha256:${hash('4')}`,
          fileId: '00000000000000000000000000000003',
          aclSha256: hash('f'),
          objectKind: 'DIRECTORY',
          contentSha256: hash('3'),
        },
      ],
      preservedMySqlData: {
        path: 'D:\\LeanTPM\\data',
        beforeIdentitySha256: hash('e'),
        afterIdentitySha256: hash('e'),
        unchanged: true,
      },
      legacy: {
        inventorySha256: hash('f'),
        status: 'PASS',
        importRequired: false,
      },
      verification: {
        hostBootstrapReportSha256: hash('1'),
      },
      nextRequiredCeremonies: ['EXTERNAL_CADDY_ADOPTION'],
    }
    const invokeReceipt = (candidate) => {
      const receiptPath = path.join(temporaryRoot, 'host-bootstrap-init-receipt.json')
      fs.writeFileSync(receiptPath, JSON.stringify(candidate), 'utf8')
      const sha256 = crypto.createHash('sha256').update(fs.readFileSync(receiptPath)).digest('hex')
      return invokePowerShell(validatorPath, [
        '-ReceiptPath', receiptPath,
        '-ExpectedReceiptSha256', sha256,
        '-PlanOnly',
        '-OutputFormat', 'Json',
      ])
    }
    const accepted = invokeReceipt(receipt)
    assert.equal(accepted.status, 0, combinedOutput(accepted))
    const report = JSON.parse(accepted.stdout.trim())
    assert.equal(report.status, 'INPUT_REQUIRED')
    assert.equal(report.productionReady, false)
    assert.equal(report.cryptographicallyVerified, false)
    assert.deepEqual(report.nextRequiredCeremonies, ['EXTERNAL_CADDY_ADOPTION'])

    for (const mutate of [
      (value) => { value.productionReady = true },
      (value) => { value.externalCaddyAdopted = true },
      (value) => { value.mysqlDataTouched = true },
      (value) => { value.status = 'COMMITTED' },
      (value) => { value.nextRequiredCeremonies = [] },
      (value) => { value.preservedMySqlData.afterIdentitySha256 = hash('0') },
      (value) => { value.preservedMySqlData.unchanged = 'false' },
      (value) => {
        value.createdObjects[0].path = 'C:\\Windows\\System32'
        value.createdObjects[0].finalPath = 'C:\\Windows\\System32'
      },
      (value) => {
        value.createdObjects[1].path = 'D:\\LeanTPM\\App\\..\\data'
        value.createdObjects[1].finalPath = 'D:\\LeanTPM\\App\\..\\data'
      },
      (value) => { value.initializationId = 123 },
      (value) => { value.nextRequiredCeremonies = 'EXTERNAL_CADDY_ADOPTION' },
      (value) => { value.createdObjects = value.createdObjects[0] },
    ]) {
      const forged = structuredClone(receipt)
      mutate(forged)
      const rejected = invokeReceipt(forged)
      assert.notEqual(rejected.status, 0, combinedOutput(rejected))
    }
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('builds a bound PlanOnly legacy import plan without copying or overwriting files', () => {
  const schemaPath = path.join(repositoryRoot, 'release', 'legacy-import-plan.schema.json')
  const plannerPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'New-LeanTpmLegacyImportPlan.ps1',
  )
  const validatorPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmLegacyImportPlan.ps1',
  )
  assert.ok(fs.existsSync(schemaPath), 'machine-readable legacy import plan schema is required')
  assert.ok(fs.existsSync(plannerPath), 'PlanOnly legacy import planner is required')
  assert.ok(fs.existsSync(validatorPath), 'independent legacy import plan validator is required')
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'))
  assert.equal(schema.additionalProperties, false)
  assert.deepEqual(schema.properties.executable, { const: false })
  assert.deepEqual(schema.properties.constraints.properties.overwriteTarget, { const: false })

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-legacy-plan-root-'))
  const controlRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-legacy-plan-control-'))
  try {
    const installRoot = path.join(temporaryRoot, 'App')
    const dataRoot = path.join(temporaryRoot, 'Runtime')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    fs.mkdirSync(path.join(temporaryRoot, 'current', 'frontend'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'current', 'frontend', 'index.html'), 'legacy web')
    fs.mkdirSync(path.join(temporaryRoot, 'shared', 'config'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'shared', 'config', 'leantpm.env'), 'legacy config')
    fs.mkdirSync(path.join(temporaryRoot, 'data', 'mysql', 'Data'), { recursive: true })
    fs.writeFileSync(path.join(temporaryRoot, 'data', 'mysql', 'Data', 'ibdata1'), 'preserve')

    const inventoryResult = invokePowerShell(
      path.join(repositoryRoot, 'deploy', 'windows', 'Get-LeanTpmLegacyLayoutInventory.ps1'),
      [
        '-InstallRoot', installRoot,
        '-DataRoot', dataRoot,
        '-PlanOnly',
        '-OutputFormat', 'Json',
      ],
    )
    assert.equal(inventoryResult.status, 0, combinedOutput(inventoryResult))
    const inventoryPath = path.join(controlRoot, 'legacy-inventory.json')
    fs.writeFileSync(inventoryPath, inventoryResult.stdout.trim(), 'utf8')
    const inventorySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(inventoryPath)).digest('hex')

    const backupReceiptPath = path.join(controlRoot, 'backup-manifest.json')
    fs.writeFileSync(backupReceiptPath, JSON.stringify({
      schemaVersion: 1,
      status: 'VALID',
      backupId: 'backup-legacy-contract',
    }), 'utf8')
    const backupReceiptSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(backupReceiptPath)).digest('hex')
    const before = snapshotTree(temporaryRoot)

    const planned = invokePowerShell(plannerPath, [
      '-InventoryPath', inventoryPath,
      '-ExpectedInventorySha256', inventorySha256,
      '-PlanId', 'legacy-import-contract-001',
      '-BackupReceiptPath', backupReceiptPath,
      '-ExpectedBackupReceiptSha256', backupReceiptSha256,
      '-ConfirmedRelativePath', 'current\\frontend,shared\\config',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(planned.status, 0, combinedOutput(planned))
    const report = JSON.parse(planned.stdout.trim())
    assert.equal(report.status, 'INPUT_REQUIRED')
    assert.equal(report.executable, false)
    assert.equal(report.planOnly, true)
    assert.equal(report.trustSource, 'CALLER_BOUND_PLAN_ONLY')
    assert.equal(report.approvalReadiness, 'TRUSTED_BACKUP_HOST_BINDING_AND_EXECUTOR_REQUIRED')
    assert.equal(report.hostFilesystemVerified, false)
    assert.equal(report.quarantineFilesystemVerified, false)
    assert.equal(report.sourceInventory.sha256, inventorySha256)
    assert.equal(report.backupReceipt.sha256, backupReceiptSha256)
    assert.equal(report.backupReceipt.backupId, 'backup-legacy-contract')
    assert.equal(report.backupReceipt.cryptographicallyVerified, false)
    assert.equal(report.backupReceipt.recoverabilityVerified, false)
    assert.deepEqual(report.preservedExternalPaths.map((entry) => entry.relativePath), ['data'])
    assert.equal(report.constraints.preserveMySqlData, true)
    assert.equal(report.entries.length, 2)
    assert.deepEqual(
      report.entries.map((entry) => entry.relativePath).sort(),
      ['current\\frontend', 'shared\\config'],
    )
    for (const entry of report.entries) {
      assert.equal(entry.action, 'COPY_TO_QUARANTINE')
      assert.equal(entry.confirmed, true)
      assert.match(entry.sourceDigest.sha256, /^[a-f0-9]{64}$/)
      assert.ok(entry.targetPath.startsWith(
        path.join(dataRoot, 'staging', 'legacy-import', 'legacy-import-contract-001'),
      ))
    }
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const planPath = path.join(controlRoot, 'legacy-import-plan.json')
    fs.writeFileSync(planPath, JSON.stringify(report), 'utf8')
    const validated = invokePowerShell(validatorPath, [
      '-PlanPath', planPath,
      '-ExpectedPlanSha256', report.planSha256,
      '-OutputFormat', 'Json',
    ])
    assert.equal(validated.status, 0, combinedOutput(validated))
    assert.equal(JSON.parse(validated.stdout.trim()).status, 'PASS')

    const writeForgedPlan = (name, mutate) => {
      const forged = JSON.parse(JSON.stringify(report))
      mutate(forged)
      delete forged.planSha256
      forged.planSha256 = crypto.createHash('sha256')
        .update(JSON.stringify(forged)).digest('hex')
      const forgedPath = path.join(controlRoot, `${name}.json`)
      fs.writeFileSync(forgedPath, JSON.stringify(forged), 'utf8')
      return { forged, forgedPath }
    }
    for (const { forged, forgedPath } of [
      writeForgedPlan('forged-ready', (value) => { value.status = 'READY_FOR_APPROVAL' }),
      writeForgedPlan('forged-target', (value) => {
        value.entries[0].targetPath = path.join(temporaryRoot, 'outside-quarantine')
      }),
    ]) {
      const rejectedPlan = invokePowerShell(validatorPath, [
        '-PlanPath', forgedPath,
        '-ExpectedPlanSha256', forged.planSha256,
        '-OutputFormat', 'Json',
      ])
      assert.notEqual(rejectedPlan.status, 0, combinedOutput(rejectedPlan))
      assert.match(combinedOutput(rejectedPlan), /status|target|quarantine|INPUT_REQUIRED/i)
    }

    const missingConfirmation = invokePowerShell(plannerPath, [
      '-InventoryPath', inventoryPath,
      '-ExpectedInventorySha256', inventorySha256,
      '-PlanId', 'legacy-import-contract-002',
      '-BackupReceiptPath', backupReceiptPath,
      '-ExpectedBackupReceiptSha256', backupReceiptSha256,
      '-ConfirmedRelativePath', 'current\\frontend',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.equal(missingConfirmation.status, 0, combinedOutput(missingConfirmation))
    assert.equal(JSON.parse(missingConfirmation.stdout.trim()).status, 'INPUT_REQUIRED')

    const executableAttempt = invokePowerShell(plannerPath, [
      '-InventoryPath', inventoryPath,
      '-ExpectedInventorySha256', inventorySha256,
      '-PlanId', 'legacy-import-contract-003',
    ])
    assert.notEqual(executableAttempt.status, 0, combinedOutput(executableAttempt))
    assert.match(combinedOutput(executableAttempt), /PlanOnly/i)

    for (const invalidPlanId of ['con', 'legacy-import-contract.']) {
      const invalidPlanIdAttempt = invokePowerShell(plannerPath, [
        '-InventoryPath', inventoryPath,
        '-ExpectedInventorySha256', inventorySha256,
        '-PlanId', invalidPlanId,
        '-PlanOnly',
        '-OutputFormat', 'Json',
      ])
      assert.notEqual(invalidPlanIdAttempt.status, 0, combinedOutput(invalidPlanIdAttempt))
      assert.match(combinedOutput(invalidPlanIdAttempt), /plan id|device|trailing/i)
    }

    const duplicateInventory = JSON.parse(inventoryResult.stdout.trim())
    duplicateInventory.entries.push({
      ...duplicateInventory.entries.find((entry) => entry.relativePath === 'current\\frontend'),
    })
    duplicateInventory.blockingCount += 1
    const duplicateInventoryPath = path.join(controlRoot, 'duplicate-inventory.json')
    fs.writeFileSync(duplicateInventoryPath, JSON.stringify(duplicateInventory), 'utf8')
    const duplicateInventorySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(duplicateInventoryPath)).digest('hex')
    const duplicateAttempt = invokePowerShell(plannerPath, [
      '-InventoryPath', duplicateInventoryPath,
      '-ExpectedInventorySha256', duplicateInventorySha256,
      '-PlanId', 'legacy-import-contract-duplicate',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(duplicateAttempt.status, 0, combinedOutput(duplicateAttempt))
    assert.match(combinedOutput(duplicateAttempt), /duplicate/i)

    const forgedDataInventory = JSON.parse(inventoryResult.stdout.trim())
    forgedDataInventory.entries.find((entry) => entry.relativePath === 'data').path =
      path.join(controlRoot, 'not-mysql-data')
    const forgedDataInventoryPath = path.join(controlRoot, 'forged-data-inventory.json')
    fs.writeFileSync(forgedDataInventoryPath, JSON.stringify(forgedDataInventory), 'utf8')
    const forgedDataInventorySha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(forgedDataInventoryPath)).digest('hex')
    const forgedDataAttempt = invokePowerShell(plannerPath, [
      '-InventoryPath', forgedDataInventoryPath,
      '-ExpectedInventorySha256', forgedDataInventorySha256,
      '-PlanId', 'legacy-import-contract-forged-data',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(forgedDataAttempt.status, 0, combinedOutput(forgedDataAttempt))
    assert.match(combinedOutput(forgedDataAttempt), /data path|inventory.*current/i)

    const targetCollision = path.join(
      dataRoot, 'staging', 'legacy-import', 'legacy-import-contract-004',
      'current', 'frontend',
    )
    fs.mkdirSync(targetCollision, { recursive: true })
    fs.writeFileSync(path.join(targetCollision, 'existing.txt'), 'must not overwrite')
    const collisionAttempt = invokePowerShell(plannerPath, [
      '-InventoryPath', inventoryPath,
      '-ExpectedInventorySha256', inventorySha256,
      '-PlanId', 'legacy-import-contract-004',
      '-BackupReceiptPath', backupReceiptPath,
      '-ExpectedBackupReceiptSha256', backupReceiptSha256,
      '-ConfirmedRelativePath', 'current\\frontend,shared\\config',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(collisionAttempt.status, 0, combinedOutput(collisionAttempt))
    assert.match(combinedOutput(collisionAttempt), /target.*empty|overwrite/i)
    assert.equal(
      fs.readFileSync(path.join(targetCollision, 'existing.txt'), 'utf8'),
      'must not overwrite',
    )

    fs.mkdirSync(path.join(temporaryRoot, 'late-added-unknown'))
    const staleInventoryAttempt = invokePowerShell(plannerPath, [
      '-InventoryPath', inventoryPath,
      '-ExpectedInventorySha256', inventorySha256,
      '-PlanId', 'legacy-import-contract-stale',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(staleInventoryAttempt.status, 0, combinedOutput(staleInventoryAttempt))
    assert.match(combinedOutput(staleInventoryAttempt), /inventory.*current|changed/i)
  } finally {
    fs.rmSync(controlRoot, { recursive: true, force: true })
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('keeps Windows production examples on the approved D App and Runtime roots', () => {
  const environment = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'leantpm.env.production.example',
  ), 'utf8')
  const caddy = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Caddyfile.example',
  ), 'utf8')
  const binding = fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmWindowsServiceBinding.ps1',
  ), 'utf8')
  const architecture = fs.readFileSync(path.join(
    repositoryRoot, 'docs', 'release-platform', 'architecture.md',
  ), 'utf8')
  const runbook = fs.readFileSync(path.join(
    repositoryRoot, 'docs', 'release-platform', 'windows-runbook.md',
  ), 'utf8')

  assert.match(environment, /LEANTPM_UPLOAD_DIR=D:\\LeanTPM\\Runtime\\data\\uploads/)
  assert.doesNotMatch(environment, /C:\\ProgramData\\LeanTPM/)
  const effective = JSON.parse(fs.readFileSync(path.join(
    repositoryRoot, 'deploy', 'windows', 'effective-config.production.example.json',
  ), 'utf8'))
  assert.equal(effective.uploadDir, 'D:\\LeanTPM\\Runtime\\data\\uploads')
  assert.match(caddy, /D:\\LeanTPM\\App\\current\\payload\\web/)
  assert.doesNotMatch(caddy, /C:\\Program Files\\LeanTPM/)
  assert.match(binding, /\[Parameter\(Mandatory\)\]\[string\]\$InstallRoot/)
  assert.match(binding, /\[Parameter\(Mandatory\)\]\[string\]\$DataRoot/)
  assert.doesNotMatch(binding, /C:\\Program Files\\LeanTPM|C:\\ProgramData\\LeanTPM/)
  assert.match(architecture, /D:\\LeanTPM\\App/)
  assert.match(architecture, /D:\\LeanTPM\\Runtime/)
  assert.match(runbook, /D:\\LeanTPM\\App/)
  assert.match(runbook, /D:\\LeanTPM\\Runtime/)
  assert.doesNotMatch(runbook, /Program Files\\LeanTPM|ProgramData\\LeanTPM(?!-bootstrap)/)
  assert.match(runbook, /New-LeanTpmLegacyImportPlan\.ps1/)
  assert.match(runbook, /CALLER_BOUND_PLAN_ONLY/)
  assert.match(runbook, /不得执行复制|不具备执行权限/)
})

test('discovers fixed host bootstrap inputs without creating trust or leaking raw identities', () => {
  const schemaPath = path.join(
    repositoryRoot, 'release', 'host-bootstrap-discovery.schema.json',
  )
  const discoveryPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Get-LeanTpmHostBootstrapDiscovery.ps1',
  )
  assert.ok(fs.existsSync(schemaPath), 'host bootstrap discovery schema is required')
  assert.ok(fs.existsSync(discoveryPath), 'read-only host bootstrap discovery command is required')
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'))
  assert.equal(schema.additionalProperties, false)
  assert.equal(schema.properties.status.const, 'INPUT_REQUIRED')
  assert.equal(schema.properties.executable.const, false)
  assert.equal(schema.properties.trustSource.const, 'UNTRUSTED_READ_ONLY_DISCOVERY')
  assert.deepEqual(schema.properties.discoveryMode.enum, ['LIVE', 'PLAN_ONLY_FIXTURE'])
  assert.equal(schema.properties.preservedExternalPaths.minItems, 1)
  assert.equal(schema.properties.preservedExternalPaths.maxItems, 1)

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-bootstrap-discovery-'))
  try {
    const observationPath = path.join(temporaryRoot, 'observation.json')
    const rawMachineGuid = '11111111-1111-1111-1111-111111111111'
    const rawSmbiosUuid = '22222222-2222-2222-2222-222222222222'
    const rawDeviceId = '\\\\?\\Volume{33333333-3333-3333-3333-333333333333}\\'
    const observation = {
      schemaVersion: 1,
      collectedAtUtc: '2026-08-09T00:00:00.0000000Z',
      machineGuid: rawMachineGuid,
      smbiosUuid: rawSmbiosUuid,
      volume: {
        driveLetter: 'D:', driveType: 3, fileSystem: 'NTFS',
        deviceId: rawDeviceId, freeBytes: 300000000000,
      },
      caddy: {
        serviceCount: 1, managedProxyCount: 0, state: 'Running', pid: 4321,
        pathName: '"D:\\tools\\caddy\\caddy.exe" run --config "D:\\LeanTPM\\Runtime\\proxy\\Caddyfile" --adapter caddyfile',
        startName: 'CONTOSO\\leantpm-proxy$',
        serviceAccountSid: 'S-1-5-21-111111111-222222222-333333333-4444',
        startMode: 'Auto', scmSddl: 'D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)',
        imagePath: 'D:\\tools\\caddy\\caddy.exe', imageSha256: 'a'.repeat(64),
        configPath: 'D:\\LeanTPM\\Runtime\\proxy\\Caddyfile',
        configSha256: 'b'.repeat(64),
        listeners: [
          { localAddress: '0.0.0.0', port: 80, owningPid: 4321 },
          { localAddress: '0.0.0.0', port: 443, owningPid: 4321 },
        ],
      },
    }
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const before = snapshotTree(temporaryRoot)
    const result = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', observationPath, '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'INPUT_REQUIRED')
    assert.equal(report.executable, false)
    assert.equal(report.trustSource, 'UNTRUSTED_READ_ONLY_DISCOVERY')
    assert.equal(report.discoveryMode, 'PLAN_ONLY_FIXTURE')
    assert.equal(report.planOnly, true)
    assert.equal(report.hostFilesystemVerified, false)
    assert.equal(report.rawIdentifiersRedacted, true)
    assert.equal(report.layoutInputs.installRoot, 'D:\\LeanTPM\\App')
    assert.equal(report.layoutInputs.dataRoot, 'D:\\LeanTPM\\Runtime')
    assert.match(report.layoutInputs.hostId, /^[a-f0-9]{64}$/u)
    assert.match(report.layoutInputs.volumeIdentity, /^sha256:[a-f0-9]{64}$/u)
    assert.equal(report.caddyObservation.listenerPorts.join(','), '80,443')
    assert.equal(report.caddyObservation.listenerPids.join(','), '4321')
    assert.deepEqual(report.preservedExternalPaths, [{
      path: 'D:\\LeanTPM\\data',
      purpose: 'MYSQL_DATA',
      action: 'PRESERVE_EXTERNAL',
    }])
    assert.ok(report.blockers.length > 0)
    assert.ok(report.blockers.includes('LEGACY_LAYOUT_INVENTORY_NOT_BOUND'))
    assert.ok(report.blockers.includes('CANONICAL_ROOTS_NOT_CREATED_OR_VERIFIED'))
    assert.ok(report.requiredNextSteps.includes('RUN_READ_ONLY_LEGACY_LAYOUT_INVENTORY'))
    assert.match(report.discoverySha256, /^[a-f0-9]{64}$/u)
    assert.deepEqual(Object.keys(report).sort(), [...schema.required].sort())
    assert.deepEqual(
      Object.keys(report.layoutInputs).sort(),
      [...schema.properties.layoutInputs.required].sort(),
    )
    assert.deepEqual(
      Object.keys(report.caddyObservation).sort(),
      [...schema.properties.caddyObservation.required].sort(),
    )
    const { discoverySha256, ...discoveryCore } = report
    assert.equal(
      discoverySha256,
      crypto.createHash('sha256').update(JSON.stringify(discoveryCore)).digest('hex'),
    )
    const output = result.stdout + result.stderr
    for (const raw of [rawMachineGuid, rawSmbiosUuid, rawDeviceId]) {
      assert.equal(output.includes(raw), false, 'raw host or volume identity leaked')
    }
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const duplicateObservationPath = path.join(temporaryRoot, 'duplicate-observation.json')
    const duplicateObservation = JSON.stringify(observation).replace(
      '"schemaVersion":1',
      '"schemaVersion":1,"schemaVersion":1',
    )
    fs.writeFileSync(duplicateObservationPath, duplicateObservation)
    const duplicate = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', duplicateObservationPath, '-OutputFormat', 'Json',
    ])
    assert.notEqual(duplicate.status, 0, 'duplicate observation property was accepted')
    assert.match(combinedOutput(duplicate), /duplicate|property.*exact|ambiguous/i)
    fs.rmSync(duplicateObservationPath)

    const duplicateListenerPath = path.join(temporaryRoot, 'duplicate-listener.json')
    const duplicateListener = JSON.stringify(observation).replace(
      '"localAddress":"0.0.0.0","port":80',
      '"localAddress":"0.0.0.0","port":9999,"port":80',
    )
    fs.writeFileSync(duplicateListenerPath, duplicateListener)
    const duplicateListenerResult = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', duplicateListenerPath, '-OutputFormat', 'Json',
    ])
    assert.notEqual(
      duplicateListenerResult.status,
      0,
      'duplicate listener property was accepted',
    )
    assert.match(combinedOutput(duplicateListenerResult), /duplicate|property.*exact|ambiguous/i)
    fs.rmSync(duplicateListenerPath)

    const unknownObservationPath = path.join(temporaryRoot, 'unknown-observation.json')
    fs.writeFileSync(unknownObservationPath, JSON.stringify({ ...observation, unexpected: true }))
    const unknown = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', unknownObservationPath, '-OutputFormat', 'Json',
    ])
    assert.notEqual(unknown.status, 0, 'unknown observation property was accepted')
    assert.match(combinedOutput(unknown), /property count|unexpected|exact property/i)
    fs.rmSync(unknownObservationPath)

    const degradedObservationPath = path.join(temporaryRoot, 'degraded-observation.json')
    const degradedObservation = JSON.parse(JSON.stringify(observation))
    degradedObservation.volume.driveType = 2
    degradedObservation.volume.fileSystem = 'FAT32'
    degradedObservation.caddy.serviceCount = 0
    degradedObservation.caddy.state = 'Stopped'
    degradedObservation.caddy.pid = 0
    degradedObservation.caddy.listeners = []
    fs.writeFileSync(degradedObservationPath, JSON.stringify(degradedObservation))
    const degraded = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', degradedObservationPath, '-OutputFormat', 'Json',
    ])
    assert.equal(degraded.status, 0, combinedOutput(degraded))
    const degradedReport = JSON.parse(degraded.stdout.trim())
    assert.equal(degradedReport.status, 'INPUT_REQUIRED')
    assert.equal(degradedReport.executable, false)
    for (const blocker of [
      'D_VOLUME_IS_NOT_FIXED_NTFS',
      'CADDY_SERVICE_COUNT_INVALID',
      'CADDY_IS_NOT_RUNNING_WITH_A_STABLE_PID',
      'CADDY_PUBLIC_LISTENER_OWNERSHIP_IS_NOT_EXACT',
    ]) {
      assert.ok(degradedReport.blockers.includes(blocker), `missing blocker ${blocker}`)
    }
    fs.rmSync(degradedObservationPath)

    const taintedObservationPath = path.join(temporaryRoot, 'tainted-observation.json')
    const taintedObservation = JSON.parse(JSON.stringify(observation))
    taintedObservation.caddy.imagePath = `D:\\tools\\${rawMachineGuid}\\caddy.exe`
    taintedObservation.caddy.configPath = `D:\\LeanTPM\\Runtime\\proxy\\${rawDeviceId}`
    taintedObservation.caddy.pathName = `"${taintedObservation.caddy.imagePath}" run --config "${taintedObservation.caddy.configPath}" --adapter caddyfile`
    taintedObservation.caddy.imageSha256 = rawMachineGuid
    taintedObservation.caddy.configSha256 = rawSmbiosUuid
    taintedObservation.caddy.startName = rawMachineGuid
    taintedObservation.caddy.serviceAccountSid = rawSmbiosUuid
    taintedObservation.caddy.startMode = rawDeviceId
    fs.writeFileSync(taintedObservationPath, JSON.stringify(taintedObservation))
    const tainted = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn',
      '-PlanOnly', '-ObservationPath', taintedObservationPath, '-OutputFormat', 'Json',
    ])
    assert.equal(tainted.status, 0, combinedOutput(tainted))
    const taintedReport = JSON.parse(tainted.stdout.trim())
    assert.equal(taintedReport.status, 'INPUT_REQUIRED')
    assert.ok(taintedReport.blockers.includes('CADDY_OBSERVATION_CONTAINS_RAW_IDENTITY'))
    const taintedOutput = tainted.stdout + tainted.stderr
    for (const raw of [rawMachineGuid, rawSmbiosUuid, rawDeviceId]) {
      assert.equal(taintedOutput.includes(raw), false, 'cross-field raw identity leaked')
    }
    fs.rmSync(taintedObservationPath)

    const identityEnvironment = invokePowerShell(discoveryPath, [
      '-EnvironmentId', rawMachineGuid,
      '-PlanOnly', '-ObservationPath', observationPath, '-OutputFormat', 'Json',
    ])
    assert.notEqual(identityEnvironment.status, 0, 'raw identity accepted as EnvironmentId')
    assert.equal(
      combinedOutput(identityEnvironment).includes(rawMachineGuid),
      false,
      'rejected EnvironmentId leaked the raw identity',
    )

    const injected = invokePowerShell(discoveryPath, [
      '-EnvironmentId', 'leantpm-production-cn', '-ObservationPath', observationPath,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(injected.status, 0, 'executable discovery accepted a caller observation')
    assert.match(combinedOutput(injected), /ObservationPath.*PlanOnly|mock.*PlanOnly/i)
    assert.deepEqual(snapshotTree(temporaryRoot), before)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }

  const source = fs.readFileSync(discoveryPath, 'utf8')
  assert.doesNotMatch(
    source,
    /New-Item|Set-Acl|icacls|Set-ItemProperty|New-Service|Start-Service|Stop-Service|Set-NetFirewall|Enable-NetFirewall|Disable-NetFirewall/,
  )
})

test('discovers Aliyun deployment prerequisites without mutating the Windows host', () => {
  const discoveryPath = path.join(
    repositoryRoot, 'scripts', 'Get-LeanTpmAliyunDeploymentDiscovery.ps1',
  )
  assert.ok(fs.existsSync(discoveryPath), 'Aliyun read-only deployment discovery is required')
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-aliyun-discovery-'))
  try {
    const observationPath = path.join(temporaryRoot, 'observation.json')
    const observation = {
      schemaVersion: 1,
      collectedAtUtc: '2026-08-09T00:00:00Z',
      volume: { drive: 'D:', fileSystem: 'NTFS', freeBytes: 290000000000 },
      java: {
        path: 'D:\\tools\\jdk-21.0.1\\bin\\java.exe',
        sha256: 'a'.repeat(64), version: '21.0.1',
      },
      caddy: {
        serviceName: 'caddy', state: 'Running', startMode: 'Auto',
        path: 'D:\\tools\\caddy\\caddy.exe', sha256: 'b'.repeat(64),
      },
      mysql: {
        serviceName: 'MySQL80', state: 'Running', startMode: 'Auto',
        path: 'D:\\tools\\mysql\\bin\\mysqld.exe', sha256: 'c'.repeat(64),
      },
      listeners: [
        { address: '0.0.0.0', port: 80, pid: 100 },
        { address: '0.0.0.0', port: 443, pid: 100 },
        { address: '127.0.0.1', port: 3306, pid: 200 },
      ],
      paths: [
        { path: 'D:\\LeanTPM\\packages', exists: true },
        { path: 'D:\\LeanTPM\\App', exists: false },
        { path: 'D:\\LeanTPM\\Runtime', exists: false },
        { path: 'D:\\LeanTPM\\data', exists: true },
      ],
    }
    fs.writeFileSync(observationPath, JSON.stringify(observation))
    const before = snapshotTree(temporaryRoot)
    const result = invokePowerShell(discoveryPath, [
      '-PlanOnly', '-ObservationPath', observationPath, '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'INPUT_REQUIRED')
    assert.equal(report.readOnly, true)
    assert.equal(report.discoveryMode, 'PLAN_ONLY_FIXTURE')
    assert.equal(report.java.sha256, 'a'.repeat(64))
    assert.equal(report.caddy.serviceName, 'caddy')
    assert.equal(report.mysql.serviceName, 'MySQL80')
    assert.deepEqual(report.requiredPorts, [80, 443, 18080, 3306, 15173])
    assert.ok(report.blockers.includes('JAVA_SERVER_HASH_NOT_APPROVED'))
    assert.ok(report.blockers.includes('CADDY_SERVER_HASH_NOT_APPROVED'))
    assert.ok(report.blockers.includes('MYSQL_SERVER_UUID_NOT_VERIFIED'))
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const source = fs.readFileSync(discoveryPath, 'utf8')
    assert.match(source, /Get-CimInstance[\s\S]*Win32_Service/)
    assert.match(source, /Get-NetTCPConnection/)
    assert.match(source, /Get-FileHash/)
    assert.doesNotMatch(source, /Start-Service|Stop-Service|Set-Service|New-NetFirewallRule/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('runs the release agent through verify-only and pinned signed deployment modes', () => {
  const agentPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmReleaseAgent.ps1',
  )
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-release-agent-'))
  try {
    const queueRoot = path.join(temporaryRoot, 'queue')
    const pendingRoot = path.join(queueRoot, 'pending')
    const uploadRoot = path.join(temporaryRoot, 'uploads')
    const approvalRoot = path.join(temporaryRoot, 'approvals')
    const packageRoot = path.join(uploadRoot, 'upload-001')
    const packagePath = path.join(packageRoot, 'release.zip')
    const verifierRoot = path.join(temporaryRoot, 'trusted')
    const toolkitRoot = path.join(temporaryRoot, 'deployment-toolkit')
    const toolkitScriptsRoot = path.join(toolkitRoot, 'scripts')
    const toolkitWindowsRoot = path.join(toolkitRoot, 'deploy', 'windows')
    const toolkitReleaseRoot = path.join(toolkitRoot, 'release')
    const deploymentExecutorPath = path.join(
      toolkitScriptsRoot, 'Invoke-LeanTpmDeployment.ps1',
    )
    const toolkitLockPath = path.join(
      toolkitReleaseRoot, 'release-agent-toolkit-lock.json',
    )
    const verifierPath = path.join(verifierRoot, 'Test-ReleasePackage.ps1')
    const approvalVerifierPath = path.join(verifierRoot, 'Test-LeanTpmReleaseApproval.ps1')
    const releaseTrustPath = path.join(temporaryRoot, 'release-trust.json')
    const invocationLog = path.join(temporaryRoot, 'verifier-invocations.log')
    const approvalInvocationLog = path.join(temporaryRoot, 'approval-invocations.log')
    const deploymentInvocationLog = path.join(temporaryRoot, 'deployment-invocations.log')
    fs.mkdirSync(pendingRoot, { recursive: true })
    fs.mkdirSync(packageRoot, { recursive: true })
    fs.mkdirSync(approvalRoot, { recursive: true })
    fs.mkdirSync(verifierRoot, { recursive: true })
    fs.mkdirSync(toolkitScriptsRoot, { recursive: true })
    fs.mkdirSync(toolkitWindowsRoot, { recursive: true })
    fs.mkdirSync(toolkitReleaseRoot, { recursive: true })
    fs.writeFileSync(packagePath, Buffer.from('signed-release-fixture', 'utf8'))
    fs.writeFileSync(releaseTrustPath, '{"schemaVersion":1}', 'utf8')
    const packageSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(packagePath)).digest('hex')
    const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
    fs.writeFileSync(
      verifierPath,
      [
        'param([string]$PackagePath,[string]$TrustedCertificateThumbprint,[string]$OutputFormat)',
        '$selfMutationBlocked = $false',
        "try { Add-Content -LiteralPath $PSCommandPath -Value '# verifier drift' -ErrorAction Stop } catch { $selfMutationBlocked = $true }",
        "if (-not $selfMutationBlocked) { throw 'verifier file was not locked' }",
        '$packageMutationBlocked = $false',
        "try { [IO.File]::WriteAllText($PackagePath, 'package drift') } catch { $packageMutationBlocked = $true }",
        "if (-not $packageMutationBlocked) { throw 'release package was not locked' }",
        `Add-Content -LiteralPath ${quotePowerShell(invocationLog)} -Value $PackagePath`,
        '$item = Get-Item -LiteralPath $PackagePath -ErrorAction Stop',
        '$sha = (Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash.ToLowerInvariant()',
        "[pscustomobject]@{ status='PASS'; releaseId='1.0.1-abcdef123456'; releaseTier='PRODUCTION'; productVersion='1.0.1'; databaseSchemaFrom=1; databaseSchemaVersion=50; artifactCount=3; package=$item.FullName; bytes=$item.Length; expandedBytes=1234; sha256=$sha; manifestSha256=('d' * 64); schemaSha256=('e' * 64) } | ConvertTo-Json -Compress",
        'exit 0',
        '',
      ].join('\r\n'),
    )
    const verifierSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(verifierPath)).digest('hex')
    fs.writeFileSync(
      approvalVerifierPath,
      [
        'param([string]$PlanPath,[string]$RequesterSignaturePath,[string]$ApproverSignaturePath,[string]$TrustConfigPath,[string]$OutputFormat)',
        `Add-Content -LiteralPath ${quotePowerShell(approvalInvocationLog)} -Value $PlanPath`,
        '$sha = (Get-FileHash -LiteralPath $PlanPath -Algorithm SHA256).Hash.ToLowerInvariant()',
        "$plan = Get-Content -LiteralPath $PlanPath -Encoding utf8 -Raw | ConvertFrom-Json",
        "[pscustomobject]@{ status='PASS'; requestedBy=[string]$plan.requestedBy; approvedBy=[string]$plan.approvedBy; requesterCertificateThumbprint=('D' * 40); approverCertificateThumbprint=('E' * 40); planSha256=$sha } | ConvertTo-Json -Compress",
        '',
      ].join('\r\n'),
    )
    const approvalVerifierSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(approvalVerifierPath)).digest('hex')
    fs.writeFileSync(
      deploymentExecutorPath,
      [
        '[CmdletBinding(SupportsShouldProcess)]',
        'param([string]$PlanPath,[switch]$ConfirmDeployment,[string]$OutputFormat)',
        "if (-not $ConfirmDeployment) { throw 'confirmation was not forwarded' }",
        `Add-Content -LiteralPath ${quotePowerShell(deploymentInvocationLog)} -Value $PlanPath`,
        "$plan = Get-Content -LiteralPath $PlanPath -Encoding utf8 -Raw | ConvertFrom-Json",
        "[pscustomobject]@{ status='SUCCEEDED'; releaseId=[string]$plan.releaseId; approvalId=[string]$plan.approvalId; environmentName=[string]$plan.environmentName; environmentKind=[string]$plan.environmentKind; packageSha256=[string]$plan.packageSha256; hostLayoutSha256=[string]$plan.hostLayoutSha256; proxyBindingSha256=[string]$plan.proxyBindingSha256; steps=@('LOCK','AUDIT'); backupId='backup-001' } | ConvertTo-Json -Depth 5 -Compress",
        '',
      ].join('\r\n'),
    )
    const deploymentExecutorSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(deploymentExecutorPath)).digest('hex')
    const toolkitLock = {
      executorRelativePath: 'scripts/Invoke-LeanTpmDeployment.ps1',
      files: [{
        path: 'scripts/Invoke-LeanTpmDeployment.ps1',
        sha256: deploymentExecutorSha256,
      }],
      schemaVersion: 1,
      toolkitId: 'leantpm-release-agent-toolkit',
    }
    fs.writeFileSync(toolkitLockPath, JSON.stringify(toolkitLock), 'utf8')
    const toolkitLockSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(toolkitLockPath)).digest('hex')
    const commonArgs = [
      '-Mode', 'VerifyOnly',
      '-RunOnce',
      '-QueueRoot', queueRoot,
      '-UploadRoot', uploadRoot,
      '-ApprovalRoot', approvalRoot,
      '-PackageVerifierPath', verifierPath,
      '-PackageVerifierSha256', verifierSha256,
      '-ApprovalVerifierPath', approvalVerifierPath,
      '-ApprovalVerifierSha256', approvalVerifierSha256,
      '-ReleaseTrustConfigPath', releaseTrustPath,
      '-DeploymentToolkitRoot', toolkitRoot,
      '-DeploymentToolkitLockPath', toolkitLockPath,
      '-DeploymentToolkitLockSha256', toolkitLockSha256,
      '-TrustedCertificateThumbprint', 'A'.repeat(40),
      '-AgentId', 'release-agent-01',
      '-AgentVersion', '1.0.1',
      '-OutputFormat', 'Json',
    ]

    const idle = invokePowerShell(agentPath, commonArgs)
    assert.equal(idle.status, 0, combinedOutput(idle))
    const idleReport = JSON.parse(idle.stdout.trim())
    assert.equal(idleReport.status, 'IDLE')
    const heartbeat = JSON.parse(
      fs.readFileSync(path.join(queueRoot, 'agent-heartbeat.json'), 'utf8'),
    )
    assert.equal(heartbeat.mode, 'VERIFY_ONLY')
    assert.equal(heartbeat.agentId, 'release-agent-01')

    const commandId = 'a'.repeat(64)
    const jobPath = path.join(pendingRoot, `${commandId}.json`)
    const job = {
      action: 'DEPLOY_RELEASE',
      commandId,
      databaseSchemaVersion: 50,
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      hostSnapshotSha256: 'c'.repeat(64),
      manifestSha256: 'd'.repeat(64),
      packagePath,
      packageSha256,
      planSha256: 'b'.repeat(64),
      productVersion: '1.0.1',
      releaseId: '1.0.1-abcdef123456',
      schemaVersion: 1,
    }
    fs.writeFileSync(jobPath, JSON.stringify(job), 'utf8')
    const packageBefore = fs.readFileSync(packagePath)

    const verified = invokePowerShell(agentPath, commonArgs)
    assert.equal(verified.status, 0, combinedOutput(verified))
    const verifiedReport = JSON.parse(verified.stdout.trim())
    assert.equal(verifiedReport.status, 'VERIFIED_ONLY')
    assert.equal(verifiedReport.commandId, commandId)
    assert.equal(verifiedReport.packageSha256, packageSha256)
    assert.equal(verifiedReport.productionExecutionEnabled, false)
    assert.ok(fs.existsSync(jobPath), 'verify-only agent consumed the pending job')
    assert.deepEqual(fs.readFileSync(packagePath), packageBefore)
    const resultPath = path.join(queueRoot, 'results', `${commandId}.json`)
    const durableResult = JSON.parse(fs.readFileSync(resultPath, 'utf8'))
    assert.equal(durableResult.status, 'VERIFIED_ONLY')
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
    )

    const approvalId = 'approval-001'
    const approvalDirectory = path.join(approvalRoot, approvalId)
    fs.mkdirSync(approvalDirectory)
    const deploymentPlanPath = path.join(approvalDirectory, 'deployment-plan.json')
    const requesterSignaturePath = path.join(
      approvalDirectory, 'deployment-plan.requester.p7s',
    )
    const approverSignaturePath = path.join(
      approvalDirectory, 'deployment-plan.approver.p7s',
    )
    fs.writeFileSync(deploymentPlanPath, JSON.stringify({
      schemaVersion: 1,
      environmentName: 'LeanTPM Production',
      environmentKind: 'PRODUCTION',
      environmentId: 'leantpm-production-cn',
      hostId: 'aliyun-host-001',
      releaseId: '1.0.1-abcdef123456',
      approvalId,
      packagePath,
      packageSha256,
      manifestSha256: 'd'.repeat(64),
      opsHostSnapshotSha256: 'c'.repeat(64),
      requestedBy: 'release-requester',
      approvedBy: 'release-approver',
      requesterSignaturePath,
      approverSignaturePath,
      hostLayoutSha256: '1'.repeat(64),
      proxyBindingSha256: '2'.repeat(64),
    }), 'utf8')
    fs.writeFileSync(requesterSignaturePath, 'requester-signature', 'utf8')
    fs.writeFileSync(approverSignaturePath, 'approver-signature', 'utf8')
    const fileSha256 = (file) => crypto.createHash('sha256')
      .update(fs.readFileSync(file)).digest('hex')
    const signedCommandId = '9'.repeat(64)
    const signedJobPath = path.join(pendingRoot, `${signedCommandId}.json`)
    const signedJob = {
      action: 'DEPLOY_SIGNED_RELEASE',
      approvalId,
      approverSignaturePath,
      approverSignatureSha256: fileSha256(approverSignaturePath),
      commandId: signedCommandId,
      databaseSchemaVersion: 50,
      deploymentPlanPath,
      deploymentPlanSha256: fileSha256(deploymentPlanPath),
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      hostSnapshotSha256: 'c'.repeat(64),
      manifestSha256: 'd'.repeat(64),
      packagePath,
      packageSha256,
      planSha256: fileSha256(deploymentPlanPath),
      productVersion: '1.0.1',
      releaseId: '1.0.1-abcdef123456',
      requesterSignaturePath,
      requesterSignatureSha256: fileSha256(requesterSignaturePath),
      schemaVersion: 2,
    }
    fs.writeFileSync(signedJobPath, JSON.stringify(signedJob), 'utf8')
    const signedVerified = invokePowerShell(agentPath, commonArgs)
    assert.equal(signedVerified.status, 0, combinedOutput(signedVerified))
    const signedReport = JSON.parse(signedVerified.stdout.trim())
    assert.equal(signedReport.status, 'VERIFIED_ONLY')
    assert.equal(signedReport.commandId, signedCommandId)
    assert.equal(
      fs.readFileSync(approvalInvocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
    )
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      2,
    )

    const executeArgs = [...commonArgs]
    executeArgs[executeArgs.indexOf('VerifyOnly')] = 'ExecuteSignedDeployment'
    const deployed = invokePowerShell(agentPath, executeArgs)
    assert.equal(deployed.status, 0, combinedOutput(deployed))
    const deployedReport = JSON.parse(deployed.stdout.trim())
    assert.equal(deployedReport.status, 'DEPLOYED')
    assert.equal(deployedReport.deploymentStatus, 'SUCCEEDED')
    assert.equal(deployedReport.productionExecutionEnabled, true)
    assert.equal(deployedReport.approvalId, approvalId)
    assert.match(deployedReport.deploymentReportSha256, /^[a-f0-9]{64}$/u)
    assert.equal(
      fs.readFileSync(deploymentInvocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
    )
    assert.equal(fs.existsSync(signedJobPath), false)
    assert.equal(
      fs.existsSync(path.join(queueRoot, 'completed', `${signedCommandId}.json`)),
      true,
    )
    const durableDeploymentResult = JSON.parse(fs.readFileSync(
      path.join(queueRoot, 'results', `${signedCommandId}.json`), 'utf8',
    ))
    assert.equal(durableDeploymentResult.status, 'DEPLOYED')

    const completedSignedJobPath = path.join(
      queueRoot, 'completed', `${signedCommandId}.json`,
    )
    fs.renameSync(completedSignedJobPath, signedJobPath)
    const forgedDeploymentResult = {
      ...durableDeploymentResult,
      productVersion: '9.9.9',
    }
    const forgedDeploymentCore = {
      agentId: forgedDeploymentResult.agentId,
      agentVersion: forgedDeploymentResult.agentVersion,
      approvalId: forgedDeploymentResult.approvalId,
      commandId: forgedDeploymentResult.commandId,
      databaseSchemaVersion: forgedDeploymentResult.databaseSchemaVersion,
      deploymentReportSha256: forgedDeploymentResult.deploymentReportSha256,
      deploymentStatus: forgedDeploymentResult.deploymentStatus,
      hostSnapshotSha256: forgedDeploymentResult.hostSnapshotSha256,
      manifestSha256: forgedDeploymentResult.manifestSha256,
      packageSha256: forgedDeploymentResult.packageSha256,
      planSha256: forgedDeploymentResult.planSha256,
      productionExecutionEnabled: forgedDeploymentResult.productionExecutionEnabled,
      productVersion: forgedDeploymentResult.productVersion,
      releaseId: forgedDeploymentResult.releaseId,
      schemaVersion: forgedDeploymentResult.schemaVersion,
      status: forgedDeploymentResult.status,
      verifiedAt: forgedDeploymentResult.verifiedAt,
    }
    forgedDeploymentResult.resultSha256 = crypto.createHash('sha256')
      .update(JSON.stringify(forgedDeploymentCore)).digest('hex')
    fs.writeFileSync(
      path.join(queueRoot, 'results', `${signedCommandId}.json`),
      JSON.stringify(forgedDeploymentResult),
      'utf8',
    )
    const forgedReplay = invokePowerShell(agentPath, executeArgs)
    assert.notEqual(
      forgedReplay.status,
      0,
      'agent accepted a replay result bound to a different product version',
    )
    assert.equal(fs.existsSync(signedJobPath), true)
    assert.equal(
      fs.readFileSync(deploymentInvocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
      'forged replay result caused a second deployment',
    )
    fs.writeFileSync(
      path.join(queueRoot, 'results', `${signedCommandId}.json`),
      JSON.stringify(durableDeploymentResult),
      'utf8',
    )

    const replayedDeployment = invokePowerShell(agentPath, executeArgs)
    assert.equal(replayedDeployment.status, 0, combinedOutput(replayedDeployment))
    assert.equal(JSON.parse(replayedDeployment.stdout.trim()).status, 'DEPLOYED')
    assert.equal(fs.existsSync(signedJobPath), false)
    assert.equal(fs.existsSync(completedSignedJobPath), true)
    assert.equal(
      fs.readFileSync(deploymentInvocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
      'completed signed deployment was executed twice',
    )

    fs.renameSync(completedSignedJobPath, signedJobPath)
    fs.rmSync(path.join(queueRoot, 'results', `${signedCommandId}.json`))
    fs.appendFileSync(deploymentExecutorPath, '# toolkit drift\r\n')
    const driftedToolkit = invokePowerShell(agentPath, executeArgs)
    assert.notEqual(driftedToolkit.status, 0, 'agent executed a drifted deployment toolkit')
    assert.match(combinedOutput(driftedToolkit), /toolkit|digest|lock/i)
    assert.equal(fs.existsSync(signedJobPath), true)
    assert.equal(
      fs.readFileSync(deploymentInvocationLog, 'utf8').trim().split(/\r?\n/u).length,
      1,
      'drifted deployment executor was invoked',
    )
    fs.renameSync(signedJobPath, completedSignedJobPath)

    fs.rmSync(resultPath)
    fs.writeFileSync(
      jobPath,
      JSON.stringify({ ...job, releaseId: '1.0.1-fedcba654321' }),
      'utf8',
    )
    const mismatchedRelease = invokePowerShell(agentPath, commonArgs)
    assert.notEqual(
      mismatchedRelease.status,
      0,
      'agent accepted a package whose manifest releaseId differs from the job',
    )
    assert.match(combinedOutput(mismatchedRelease), /verifier report|release.*id/i)
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      5,
      'release identity mismatch did not reach the pinned verifier exactly once',
    )

    fs.writeFileSync(jobPath, JSON.stringify({ ...job, shell: 'whoami' }), 'utf8')
    const injected = invokePowerShell(agentPath, commonArgs)
    assert.notEqual(injected.status, 0, 'agent accepted an unknown command field')
    assert.match(combinedOutput(injected), /properties|field|schema|command/i)
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      5,
      'invalid command reached the verifier',
    )

    fs.writeFileSync(jobPath, JSON.stringify(job), 'utf8')
    fs.appendFileSync(verifierPath, '# drift\r\n')
    const drifted = invokePowerShell(agentPath, commonArgs)
    assert.notEqual(drifted.status, 0, 'agent executed a drifted verifier')
    assert.match(combinedOutput(drifted), /verifier.*digest|SHA-?256/i)
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      5,
      'drifted verifier was executed',
    )

    const source = fs.readFileSync(agentPath, 'utf8')
    assert.match(source, /ExecuteSignedDeployment/)
    assert.match(source, /Invoke-LeanTpmDeployment\.ps1/)
    assert.doesNotMatch(source, /Invoke-LeanTpmRollback/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('builds a canonical complete release agent PowerShell toolkit lock', () => {
  const generatorPath = path.join(
    repositoryRoot, 'scripts', 'New-LeanTpmReleaseAgentToolkitLock.ps1',
  )
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-agent-toolkit-'))
  try {
    const toolkitRoot = path.join(temporaryRoot, 'toolkit')
    const scriptsRoot = path.join(toolkitRoot, 'scripts')
    const windowsRoot = path.join(toolkitRoot, 'deploy', 'windows')
    const releaseRoot = path.join(toolkitRoot, 'release')
    const outputPath = path.join(releaseRoot, 'release-agent-toolkit-lock.json')
    fs.mkdirSync(scriptsRoot, { recursive: true })
    fs.mkdirSync(windowsRoot, { recursive: true })
    fs.mkdirSync(releaseRoot, { recursive: true })
    const executorPath = path.join(scriptsRoot, 'Invoke-LeanTpmDeployment.ps1')
    const helperPath = path.join(windowsRoot, 'Helper.ps1')
    fs.writeFileSync(executorPath, 'param()\r\n', 'utf8')
    fs.writeFileSync(helperPath, 'param()\r\n', 'utf8')

    const result = invokePowerShell(generatorPath, [
      '-ToolkitRoot', toolkitRoot,
      '-OutputPath', outputPath,
      '-OutputFormat', 'Json',
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'CREATED')
    assert.equal(report.fileCount, 2)
    const lockBytes = fs.readFileSync(outputPath)
    assert.equal(
      report.lockSha256,
      crypto.createHash('sha256').update(lockBytes).digest('hex'),
    )
    const lock = JSON.parse(lockBytes.toString('utf8'))
    assert.equal(lock.schemaVersion, 1)
    assert.equal(lock.toolkitId, 'leantpm-release-agent-toolkit')
    assert.equal(lock.executorRelativePath, 'scripts/Invoke-LeanTpmDeployment.ps1')
    assert.deepEqual(
      lock.files.map((entry) => entry.path),
      ['deploy/windows/Helper.ps1', 'scripts/Invoke-LeanTpmDeployment.ps1'],
    )
    for (const entry of lock.files) {
      const sourcePath = path.join(toolkitRoot, ...entry.path.split('/'))
      assert.equal(
        entry.sha256,
        crypto.createHash('sha256').update(fs.readFileSync(sourcePath)).digest('hex'),
      )
    }
    assert.deepEqual(
      Object.keys(lock),
      ['executorRelativePath', 'files', 'schemaVersion', 'toolkitId'],
    )

    const duplicate = invokePowerShell(generatorPath, [
      '-ToolkitRoot', toolkitRoot,
      '-OutputPath', outputPath,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(duplicate.status, 0, 'toolkit lock generator overwrote an existing lock')
    assert.match(combinedOutput(duplicate), /exists|replace|overwrite/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('builds one exact production deployment bundle without rewriting source material', () => {
  const generatorPath = path.join(
    repositoryRoot, 'scripts', 'New-LeanTpmDeploymentBundle.ps1',
  )
  const schemaPath = path.join(
    repositoryRoot, 'release', 'deployment-bundle.schema.json',
  )
  assert.ok(fs.existsSync(generatorPath), 'deployment bundle generator is required')
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-bundle-build-'))
  try {
    const sources = path.join(temporaryRoot, 'sources')
    const expanded = path.join(temporaryRoot, 'expanded')
    const packagePath = path.join(sources, 'formal-release.zip')
    const planPath = path.join(sources, 'deployment-plan.json')
    const requesterPath = path.join(sources, 'requester.p7s')
    const approverPath = path.join(sources, 'approver.p7s')
    const outputPath = path.join(temporaryRoot, 'deployment-bundle.zip')
    fs.mkdirSync(sources)
    fs.writeFileSync(packagePath, 'formal-release-bytes', 'utf8')
    fs.writeFileSync(requesterPath, 'requester-signature', 'utf8')
    fs.writeFileSync(approverPath, 'approver-signature', 'utf8')
    const packageSha256 = crypto.createHash('sha256')
      .update(fs.readFileSync(packagePath)).digest('hex')
    const hostSnapshotSha256 = 'a'.repeat(64)
    const expiresAtUtc = new Date(Date.now() + 60 * 60 * 1000).toISOString()
    const plan = {
      schemaVersion: 1,
      environmentName: 'LeanTPM Production',
      environmentKind: 'PRODUCTION',
      environmentId: 'leantpm-production-cn',
      hostId: 'aliyun-host-001',
      releaseId: '1.0.2-abcdef123456',
      approvalId: 'approval-build-001',
      packagePath: `D:\\LeanTPM\\Runtime\\ops-control-plane\\uploads\\releases\\${packageSha256}\\release-package.zip`,
      packageSha256,
      manifestSha256: 'b'.repeat(64),
      opsHostSnapshotSha256: hostSnapshotSha256,
      nonce: '01234567-89ab-cdef-0123-456789abcdef',
      requestedBy: 'release-requester',
      approvedBy: 'release-approver',
      requesterSignaturePath: 'D:\\LeanTPM\\Runtime\\ops-control-plane\\approvals\\approval-build-001\\deployment-plan.requester.p7s',
      approverSignaturePath: 'D:\\LeanTPM\\Runtime\\ops-control-plane\\approvals\\approval-build-001\\deployment-plan.approver.p7s',
      expiresAtUtc,
    }
    fs.writeFileSync(planPath, JSON.stringify(plan), 'utf8')
    const before = snapshotTree(sources)
    const built = invokePowerShell(generatorPath, [
      '-ReleasePackagePath', packagePath,
      '-DeploymentPlanPath', planPath,
      '-RequesterSignaturePath', requesterPath,
      '-ApproverSignaturePath', approverPath,
      '-ExpectedHostSnapshotSha256', hostSnapshotSha256,
      '-OutputPath', outputPath,
      '-OutputFormat', 'Json',
    ])
    assert.equal(built.status, 0, combinedOutput(built))
    const report = JSON.parse(built.stdout.trim())
    assert.equal(report.status, 'CREATED')
    assert.equal(report.releaseId, plan.releaseId)
    assert.equal(report.entryCount, 6)
    assert.equal(report.packageSha256, packageSha256)
    assert.equal(
      report.bundleSha256,
      crypto.createHash('sha256').update(fs.readFileSync(outputPath)).digest('hex'),
    )
    assert.deepEqual(snapshotTree(sources), before)

    const expandScript = path.join(temporaryRoot, 'expand.ps1')
    fs.writeFileSync(
      expandScript,
      [
        'param([string]$Archive,[string]$Destination)',
        'Expand-Archive -LiteralPath $Archive -DestinationPath $Destination -ErrorAction Stop',
        '',
      ].join('\r\n'),
      'utf8',
    )
    const expandedResult = invokePowerShell(expandScript, [
      '-Archive', outputPath, '-Destination', expanded,
    ])
    assert.equal(expandedResult.status, 0, combinedOutput(expandedResult))
    assert.deepEqual(
      fs.readdirSync(expanded).sort(),
      [
        'deployment-bundle.json',
        'deployment-bundle.schema.json',
        'deployment-plan.approver.p7s',
        'deployment-plan.json',
        'deployment-plan.requester.p7s',
        'release-package.zip',
      ],
    )
    assert.deepEqual(
      fs.readFileSync(path.join(expanded, 'deployment-bundle.schema.json')),
      fs.readFileSync(schemaPath),
    )
    const metadata = JSON.parse(fs.readFileSync(
      path.join(expanded, 'deployment-bundle.json'), 'utf8',
    ))
    assert.equal(metadata.hostSnapshotSha256, hostSnapshotSha256)
    assert.equal(metadata.releasePackage.sha256, packageSha256)
    assert.equal(
      metadata.deploymentPlan.sha256,
      crypto.createHash('sha256').update(fs.readFileSync(planPath)).digest('hex'),
    )

    const duplicate = invokePowerShell(generatorPath, [
      '-ReleasePackagePath', packagePath,
      '-DeploymentPlanPath', planPath,
      '-RequesterSignaturePath', requesterPath,
      '-ApproverSignaturePath', approverPath,
      '-ExpectedHostSnapshotSha256', hostSnapshotSha256,
      '-OutputPath', outputPath,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(duplicate.status, 0, 'bundle generator overwrote an existing bundle')

    const driftedPlan = { ...plan, packageSha256: '0'.repeat(64) }
    const driftedPlanPath = path.join(sources, 'drifted-plan.json')
    fs.writeFileSync(driftedPlanPath, JSON.stringify(driftedPlan), 'utf8')
    const rejectedOutput = path.join(temporaryRoot, 'rejected.zip')
    const rejected = invokePowerShell(generatorPath, [
      '-ReleasePackagePath', packagePath,
      '-DeploymentPlanPath', driftedPlanPath,
      '-RequesterSignaturePath', requesterPath,
      '-ApproverSignaturePath', approverPath,
      '-ExpectedHostSnapshotSha256', hostSnapshotSha256,
      '-OutputPath', rejectedOutput,
      '-OutputFormat', 'Json',
    ])
    assert.notEqual(rejected.status, 0, 'bundle generator accepted package digest drift')
    assert.equal(fs.existsSync(rejectedOutput), false)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('verifies one host-bound production deployment bundle without executing it', () => {
  const verifierSource = path.join(
    repositoryRoot, 'scripts', 'Test-LeanTpmDeploymentBundle.ps1',
  )
  const schemaSource = path.join(
    repositoryRoot, 'release', 'deployment-bundle.schema.json',
  )
  assert.ok(fs.existsSync(verifierSource), 'deployment bundle verifier is required')
  assert.ok(fs.existsSync(schemaSource), 'deployment bundle schema is required')

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-deployment-bundle-'))
  try {
    const fixtureRoot = path.join(temporaryRoot, 'fixture')
    const scriptsRoot = path.join(fixtureRoot, 'scripts')
    const releaseRoot = path.join(fixtureRoot, 'release')
    const approvalRoot = path.join(temporaryRoot, 'approvals')
    const uploadRoot = path.join(temporaryRoot, 'uploads')
    const trustPath = path.join(temporaryRoot, 'release-trust.json')
    const invocationLog = path.join(temporaryRoot, 'dependency-invocations.log')
    fs.mkdirSync(scriptsRoot, { recursive: true })
    fs.mkdirSync(releaseRoot, { recursive: true })
    fs.mkdirSync(approvalRoot)
    fs.mkdirSync(uploadRoot)
    fs.copyFileSync(verifierSource, path.join(scriptsRoot, 'Test-LeanTpmDeploymentBundle.ps1'))
    fs.copyFileSync(schemaSource, path.join(releaseRoot, 'deployment-bundle.schema.json'))
    fs.writeFileSync(trustPath, '{"schemaVersion":1}', 'utf8')

    const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`
    const packageVerifier = path.join(scriptsRoot, 'Test-ReleasePackage.ps1')
    const approvalVerifier = path.join(scriptsRoot, 'Test-LeanTpmReleaseApproval.ps1')
    const trustedBundleSchema = path.join(releaseRoot, 'deployment-bundle.schema.json')
    fs.writeFileSync(
      packageVerifier,
      [
        'param([string]$PackagePath,[string]$TrustedCertificateThumbprint,[string]$OutputFormat)',
        `Add-Content -LiteralPath ${quotePowerShell(invocationLog)} -Value ('PACKAGE:' + $PackagePath)`,
        '$item = Get-Item -LiteralPath $PackagePath -ErrorAction Stop',
        '$sha = (Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash.ToLowerInvariant()',
        "[pscustomobject]@{ status='PASS'; releaseId='1.0.2-abcdef123456'; releaseTier='PRODUCTION'; productVersion='1.0.2'; databaseSchemaFrom=50; databaseSchemaVersion=51; artifactCount=4; package=$item.FullName; bytes=$item.Length; expandedBytes=2048; sha256=$sha; manifestSha256=('b' * 64); schemaSha256=('c' * 64) } | ConvertTo-Json -Compress",
        '',
      ].join('\r\n'),
    )
    fs.writeFileSync(
      approvalVerifier,
      [
        'param([string]$PlanPath,[string]$RequesterSignaturePath,[string]$ApproverSignaturePath,[string]$TrustConfigPath,[string]$OutputFormat)',
        `Add-Content -LiteralPath ${quotePowerShell(invocationLog)} -Value ('APPROVAL:' + $PlanPath + ':' + $RequesterSignaturePath + ':' + $ApproverSignaturePath)`,
        "$plan = Get-Content -LiteralPath $PlanPath -Encoding utf8 -Raw | ConvertFrom-Json",
        "$sha = (Get-FileHash -LiteralPath $PlanPath -Algorithm SHA256).Hash.ToLowerInvariant()",
        "[pscustomobject]@{ status='PASS'; requestedBy=[string]$plan.requestedBy; approvedBy=[string]$plan.approvedBy; requesterCertificateThumbprint=('D' * 40); approverCertificateThumbprint=('E' * 40); planSha256=$sha } | ConvertTo-Json -Compress",
        '',
      ].join('\r\n'),
    )

    const zipScript = path.join(temporaryRoot, 'zip.ps1')
    fs.writeFileSync(
      zipScript,
      [
        'param([string]$Source,[string]$Destination)',
        'Add-Type -AssemblyName System.IO.Compression.FileSystem',
        '[IO.Compression.ZipFile]::CreateFromDirectory($Source,$Destination,[IO.Compression.CompressionLevel]::Optimal,$false)',
        '',
      ].join('\r\n'),
    )

    const hostSnapshotSha256 = 'a'.repeat(64)
    const approvalId = 'approval-20260809-001'
    const buildBundle = (name, options = {}) => {
      const staging = path.join(temporaryRoot, `${name}-staging`)
      const bundlePath = path.join(temporaryRoot, `${name}.zip`)
      fs.mkdirSync(staging)
      const releasePackage = path.join(staging, 'release-package.zip')
      const requesterSignature = path.join(staging, 'deployment-plan.requester.p7s')
      const approverSignature = path.join(staging, 'deployment-plan.approver.p7s')
      fs.writeFileSync(releasePackage, Buffer.from('production-release-package', 'utf8'))
      fs.writeFileSync(requesterSignature, Buffer.from('requester-signature', 'utf8'))
      fs.writeFileSync(approverSignature, Buffer.from('approver-signature', 'utf8'))
      const packageSha256 = crypto.createHash('sha256')
        .update(fs.readFileSync(releasePackage)).digest('hex')
      const approvalDirectory = path.join(approvalRoot, approvalId)
      const createdAtUtc = new Date(Date.now() - 60_000).toISOString()
      const expiresAtUtc = new Date(Date.now() + 3_600_000).toISOString()
      const plan = {
        schemaVersion: 1,
        environmentName: 'LeanTPM Production',
        environmentKind: 'PRODUCTION',
        environmentId: 'leantpm-production-cn',
        hostId: 'aliyun-host-001',
        releaseId: '1.0.2-abcdef123456',
        approvalId,
        packagePath: path.join(
          uploadRoot, 'releases', packageSha256, 'release-package.zip',
        ),
        packageSha256,
        manifestSha256: 'b'.repeat(64),
        opsHostSnapshotSha256: hostSnapshotSha256,
        nonce: '01234567-89ab-cdef-0123-456789abcdef',
        requestedBy: 'release-requester',
        approvedBy: 'release-approver',
        requesterSignaturePath: path.join(
          approvalDirectory, 'deployment-plan.requester.p7s',
        ),
        approverSignaturePath: path.join(
          approvalDirectory, 'deployment-plan.approver.p7s',
        ),
        expiresAtUtc,
      }
      const planPath = path.join(staging, 'deployment-plan.json')
      fs.writeFileSync(planPath, JSON.stringify(plan), 'utf8')
      const metadata = {
        schemaVersion: 1,
        action: 'DEPLOY_RELEASE',
        releaseId: plan.releaseId,
        environmentId: 'leantpm-production-cn',
        hostId: 'aliyun-host-001',
        hostSnapshotSha256,
        createdAtUtc,
        expiresAtUtc: plan.expiresAtUtc,
        releasePackage: {
          path: 'release-package.zip',
          bytes: fs.statSync(releasePackage).size,
          sha256: options.packageSha256 || packageSha256,
          manifestSha256: 'b'.repeat(64),
        },
        deploymentPlan: {
          path: 'deployment-plan.json',
          bytes: fs.statSync(planPath).size,
          sha256: crypto.createHash('sha256').update(fs.readFileSync(planPath)).digest('hex'),
          requesterSignaturePath: 'deployment-plan.requester.p7s',
          requesterSignatureBytes: fs.statSync(requesterSignature).size,
          requesterSignatureSha256: crypto.createHash('sha256')
            .update(fs.readFileSync(requesterSignature)).digest('hex'),
          approverSignaturePath: 'deployment-plan.approver.p7s',
          approverSignatureBytes: fs.statSync(approverSignature).size,
          approverSignatureSha256: crypto.createHash('sha256')
            .update(fs.readFileSync(approverSignature)).digest('hex'),
        },
      }
      fs.writeFileSync(
        path.join(staging, 'deployment-bundle.json'), JSON.stringify(metadata), 'utf8',
      )
      fs.copyFileSync(schemaSource, path.join(staging, 'deployment-bundle.schema.json'))
      if (options.extraEntry) fs.writeFileSync(path.join(staging, 'unexpected.txt'), 'blocked')
      const zipped = invokePowerShell(zipScript, ['-Source', staging, '-Destination', bundlePath])
      assert.equal(zipped.status, 0, combinedOutput(zipped))
      fs.rmSync(staging, { recursive: true, force: true })
      return { bundlePath, packageSha256 }
    }

    const verifierPath = path.join(scriptsRoot, 'Test-LeanTpmDeploymentBundle.ps1')
    const commonArgs = [
      '-ExpectedHostSnapshotSha256', hostSnapshotSha256,
      '-ApprovalRoot', approvalRoot,
      '-UploadRoot', uploadRoot,
      '-TrustedManifestCertificateThumbprint', 'F'.repeat(40),
      '-ReleaseTrustConfigPath', trustPath,
      '-TrustedSchemaSha256', crypto.createHash('sha256')
        .update(fs.readFileSync(trustedBundleSchema)).digest('hex'),
      '-PackageVerifierSha256', crypto.createHash('sha256')
        .update(fs.readFileSync(packageVerifier)).digest('hex'),
      '-ApprovalVerifierSha256', crypto.createHash('sha256')
        .update(fs.readFileSync(approvalVerifier)).digest('hex'),
      '-OutputFormat', 'Json',
    ]
    const approvalBefore = snapshotTree(approvalRoot)
    const uploadBefore = snapshotTree(uploadRoot)
    const valid = buildBundle('valid')
    const result = invokePowerShell(verifierPath, [
      '-BundlePath', valid.bundlePath, ...commonArgs,
    ])
    assert.equal(result.status, 0, combinedOutput(result))
    const report = JSON.parse(result.stdout.trim())
    assert.equal(report.status, 'PASS')
    assert.equal(report.readOnly, true)
    assert.equal(report.releaseId, '1.0.2-abcdef123456')
    assert.equal(report.hostSnapshotSha256, hostSnapshotSha256)
    assert.equal(report.releasePackageSha256, valid.packageSha256)
    assert.equal(report.manifestSha256, 'b'.repeat(64))
    assert.equal(report.productVersion, '1.0.2')
    assert.equal(report.databaseSchemaVersion, 51)
    assert.equal(report.releasePackageBytes, 'production-release-package'.length)
    assert.equal(report.requesterSignatureSha256,
      crypto.createHash('sha256').update('requester-signature').digest('hex'))
    assert.equal(report.approverSignatureSha256,
      crypto.createHash('sha256').update('approver-signature').digest('hex'))
    assert.equal(report.bundlePath, path.resolve(valid.bundlePath))
    assert.match(report.deploymentPlanSha256, /^[a-f0-9]{64}$/u)
    assert.equal(
      fs.readFileSync(invocationLog, 'utf8').trim().split(/\r?\n/u).length,
      2,
    )
    assert.deepEqual(
      snapshotTree(approvalRoot),
      approvalBefore,
      'read-only verifier modified the approval root',
    )
    assert.deepEqual(
      snapshotTree(uploadRoot),
      uploadBefore,
      'read-only verifier modified the upload root',
    )

    const wrongHash = buildBundle('wrong-hash', { packageSha256: '0'.repeat(64) })
    const drifted = invokePowerShell(verifierPath, [
      '-BundlePath', wrongHash.bundlePath, ...commonArgs,
    ])
    assert.notEqual(drifted.status, 0, 'bundle accepted a drifted package digest')
    assert.match(combinedOutput(drifted), /package.*digest|sha-?256|hash/i)

    const extra = buildBundle('extra-entry', { extraEntry: true })
    const extraResult = invokePowerShell(verifierPath, [
      '-BundlePath', extra.bundlePath, ...commonArgs,
    ])
    assert.notEqual(extraResult.status, 0, 'bundle accepted an extra archive entry')
    assert.match(combinedOutput(extraResult), /entry|layout|unexpected|exact/i)

    const wrongHost = invokePowerShell(verifierPath, [
      '-BundlePath', valid.bundlePath,
      ...commonArgs.map((value) => value === hostSnapshotSha256 ? '9'.repeat(64) : value),
    ])
    assert.notEqual(wrongHost.status, 0, 'bundle accepted a different host snapshot')
    assert.match(combinedOutput(wrongHost), /host.*snapshot|binding/i)

    fs.appendFileSync(approvalVerifier, '# drift\r\n')
    const driftedDependency = invokePowerShell(verifierPath, [
      '-BundlePath', valid.bundlePath, ...commonArgs,
    ])
    assert.notEqual(
      driftedDependency.status,
      0,
      'bundle verifier executed a drifted approval verifier',
    )
    assert.match(combinedOutput(driftedDependency), /approval.*verifier.*digest|sha-?256/i)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('plans isolated OpsControl and ReleaseAgent Windows services without side effects', () => {
  const installerPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Install-LeanTpmOpsServices.ps1',
  )
  const bindingPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmOpsServicesBinding.ps1',
  )
  const controllerPath = path.join(
    repositoryRoot, 'deploy', 'windows', 'Invoke-LeanTpmOpsServices.ps1',
  )
  const opsTemplatePath = path.join(
    repositoryRoot, 'deploy', 'windows', 'LeanTPM.OpsControl.xml.template',
  )
  const agentTemplatePath = path.join(
    repositoryRoot, 'deploy', 'windows', 'LeanTPM.ReleaseAgent.xml.template',
  )
  const opsStarterSource = path.join(
    repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmOpsControl.ps1',
  )
  const agentStarterSource = path.join(
    repositoryRoot, 'deploy', 'windows', 'Start-LeanTpmReleaseAgentService.ps1',
  )
  for (const required of [
    installerPath,
    bindingPath,
    controllerPath,
    opsTemplatePath,
    agentTemplatePath,
    opsStarterSource,
    agentStarterSource,
  ]) {
    assert.ok(fs.existsSync(required), `missing Ops service asset: ${required}`)
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-ops-services-'))
  try {
    const installRoot = path.join(temporaryRoot, 'App')
    const dataRoot = path.join(temporaryRoot, 'Runtime')
    const inputRoot = path.join(temporaryRoot, 'inputs')
    const toolkitRoot = path.join(inputRoot, 'toolkit')
    const toolkitReleaseRoot = path.join(toolkitRoot, 'release')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    fs.mkdirSync(toolkitReleaseRoot, { recursive: true })

    const inputs = {
      wrapper: path.join(inputRoot, 'WinSW.exe'),
      java: path.join(inputRoot, 'java.exe'),
      jar: path.join(inputRoot, 'ops-control-plane.jar'),
      config: path.join(inputRoot, 'application-production.yml'),
      opsStarter: path.join(inputRoot, 'Start-LeanTpmOpsControl.ps1'),
      agentStarter: path.join(inputRoot, 'Start-LeanTpmReleaseAgentService.ps1'),
      toolkitLock: path.join(toolkitReleaseRoot, 'release-agent-toolkit-lock.json'),
    }
    fs.mkdirSync(inputRoot, { recursive: true })
    fs.writeFileSync(inputs.wrapper, 'synthetic-winsw', 'utf8')
    fs.writeFileSync(inputs.java, 'synthetic-java', 'utf8')
    fs.writeFileSync(inputs.jar, 'synthetic-ops-jar', 'utf8')
    fs.writeFileSync(inputs.config, 'server:\n  address: 127.0.0.1\n  port: 18090\n', 'utf8')
    fs.copyFileSync(opsStarterSource, inputs.opsStarter)
    fs.copyFileSync(agentStarterSource, inputs.agentStarter)
    fs.writeFileSync(
      inputs.toolkitLock,
      JSON.stringify({
        schemaVersion: 1,
        toolkitId: 'leantpm-release-agent-toolkit',
        executorRelativePath: 'scripts/Invoke-LeanTpmDeployment.ps1',
        files: [],
      }),
      'utf8',
    )
    const hash = (file) => crypto.createHash('sha256')
      .update(fs.readFileSync(file)).digest('hex')
    const args = [
      '-WrapperPath', inputs.wrapper,
      '-ExpectedWrapperSha256', hash(inputs.wrapper),
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-JavaExecutable', inputs.java,
      '-ExpectedJavaSha256', hash(inputs.java),
      '-OpsControlPlaneJarPath', inputs.jar,
      '-ExpectedOpsControlPlaneJarSha256', hash(inputs.jar),
      '-OpsControlPlaneConfigPath', inputs.config,
      '-ExpectedOpsControlPlaneConfigSha256', hash(inputs.config),
      '-SignedOpsStarterPath', inputs.opsStarter,
      '-SignedReleaseAgentStarterPath', inputs.agentStarter,
      '-DeploymentToolkitRoot', toolkitRoot,
      '-DeploymentToolkitLockPath', inputs.toolkitLock,
      '-ExpectedDeploymentToolkitLockSha256', hash(inputs.toolkitLock),
      '-OpsServiceAccount', 'CONTOSO\\leantpm-ops$',
      '-ReleaseAgentServiceAccount', 'CONTOSO\\leantpm-agent$',
      '-BackendServiceAccount', 'CONTOSO\\leantpm-backend$',
      '-ProxyServiceAccount', 'CONTOSO\\leantpm-proxy$',
      '-AgentId', 'leantpm-production-agent',
      '-AgentVersion', '1.0.1',
      '-AllowNonProductionRoots',
      '-AllowUnpinnedTestInputs',
      '-PlanOnly',
      '-OutputFormat', 'Json',
    ]
    const before = snapshotTree(temporaryRoot)
    const plan = invokePowerShell(installerPath, args)
    assert.equal(plan.status, 0, combinedOutput(plan))
    const report = JSON.parse(plan.stdout.trim())
    assert.equal(report.status, 'PLAN')
    assert.equal(report.executable, false)
    assert.equal(report.opsControl.serviceId, 'LeanTPM.OpsControl')
    assert.equal(report.opsControl.listenAddress, '127.0.0.1')
    assert.equal(report.opsControl.listenPort, 18090)
    assert.equal(report.releaseAgent.serviceId, 'LeanTPM.ReleaseAgent')
    assert.equal(report.releaseAgent.mode, 'ExecuteSignedDeployment')
    assert.notEqual(report.opsControl.account, report.releaseAgent.account)
    assert.ok(report.actions.includes('INSTALL_DISABLED_SERVICES'))
    assert.ok(report.actions.includes('VERIFY_FIXED_BINDING'))
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const duplicateAccount = invokePowerShell(installerPath, [
      ...args.slice(0, args.indexOf('-ReleaseAgentServiceAccount') + 1),
      'CONTOSO\\leantpm-ops$',
      ...args.slice(args.indexOf('-ReleaseAgentServiceAccount') + 2),
    ])
    assert.notEqual(duplicateAccount.status, 0)
    assert.match(combinedOutput(duplicateAccount), /distinct|different/i)
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const driftedHashArgs = [...args]
    driftedHashArgs[driftedHashArgs.indexOf('-ExpectedOpsControlPlaneJarSha256') + 1] =
      '0'.repeat(64)
    const drifted = invokePowerShell(installerPath, driftedHashArgs)
    assert.notEqual(drifted.status, 0)
    assert.match(combinedOutput(drifted), /OpsControlPlaneJar|JAR|SHA-256/i)
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const installerSource = fs.readFileSync(installerPath, 'utf8')
    const bindingSource = fs.readFileSync(bindingPath, 'utf8')
    const controllerSource = fs.readFileSync(controllerPath, 'utf8')
    const opsTemplate = fs.readFileSync(opsTemplatePath, 'utf8')
    const agentTemplate = fs.readFileSync(agentTemplatePath, 'utf8')
    const opsStarter = fs.readFileSync(opsStarterSource, 'utf8')
    const agentStarter = fs.readFileSync(agentStarterSource, 'utf8')
    const hostBootstrap = fs.readFileSync(path.join(
      repositoryRoot, 'deploy', 'windows', 'Test-LeanTpmHostBootstrap.ps1',
    ), 'utf8')
    const releaseTrustExample = fs.readFileSync(path.join(
      repositoryRoot, 'deploy', 'windows', 'release-trust.production.example.json',
    ), 'utf8')
    assert.match(installerSource, /Test-LeanTpmProductionRootPolicy\.ps1/)
    assert.match(installerSource, /deployment\.lock/)
    assert.match(installerSource, /INSTALL_DISABLED_SERVICES/)
    assert.match(installerSource, /sc\.exe[\s\S]*delayed-auto/)
    const protectRootIndex = installerSource.indexOf(
      "icacls.exe $serviceRoot '/inheritance:r'",
    )
    const firstProtectedCopyIndex = installerSource.indexOf(
      'Copy-Item -LiteralPath $resolvedWrapper -Destination $targetOpsWrapper',
    )
    const copyRehashIndex = installerSource.indexOf(
      'changed during protected copy',
    )
    const firstInstallIndex = installerSource.indexOf(
      '& $targetOpsWrapper install',
    )
    const manualBindingIndex = installerSource.indexOf(
      '-ExpectedStartPolicy Manual',
    )
    const delayedAutoIndex = installerSource.indexOf(
      "sc.exe config $serviceId 'start=' 'delayed-auto'",
    )
    const automaticBindingIndex = installerSource.indexOf(
      '-ExpectedStartPolicy Automatic',
    )
    for (const index of [
      protectRootIndex,
      firstProtectedCopyIndex,
      copyRehashIndex,
      firstInstallIndex,
      manualBindingIndex,
      delayedAutoIndex,
      automaticBindingIndex,
    ]) {
      assert.ok(index >= 0, 'missing protected Ops service installation phase')
    }
    assert.ok(protectRootIndex < firstProtectedCopyIndex)
    assert.ok(firstProtectedCopyIndex < copyRehashIndex)
    assert.ok(copyRehashIndex < firstInstallIndex)
    assert.ok(manualBindingIndex < delayedAutoIndex)
    assert.ok(delayedAutoIndex < automaticBindingIndex)
    assert.match(bindingSource, /LeanTPM\.OpsControl/)
    assert.match(bindingSource, /LeanTPM\.ReleaseAgent/)
    assert.match(bindingSource, /Win32_Service/)
    assert.match(controllerSource, /ConfirmServiceAction/)
    assert.match(controllerSource, /ConfirmUninstallServiceIds/)
    assert.match(opsTemplate, /127\.0\.0\.1/)
    assert.match(opsTemplate, /18090/)
    assert.match(agentTemplate, /ExecuteSignedDeployment/)
    assert.match(agentStarter, /Invoke-LeanTpmReleaseAgent\.ps1/)
    assert.match(agentStarter, /-Mode[\s\r\n]+ExecuteSignedDeployment/)
    assert.doesNotMatch(opsStarter, /Invoke-Expression|cmd\.exe|Start-Process/)
    assert.doesNotMatch(agentStarter, /Invoke-Expression|cmd\.exe|Start-Process/)
    assert.match(hostBootstrap, /opsControlServiceAccount/)
    assert.match(hostBootstrap, /releaseAgentServiceAccount/)
    assert.match(releaseTrustExample, /opsControlServiceAccount/)
    assert.match(releaseTrustExample, /releaseAgentServiceAccount/)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})

test('discovers Ops service installation readiness and builds only a side-effect-free plan', () => {
  const readinessPath = path.join(
    repositoryRoot,
    'deploy',
    'windows',
    'Get-LeanTpmOpsServicesInstallationReadiness.ps1',
  )
  assert.ok(fs.existsSync(readinessPath), 'missing Ops services readiness script')

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'leantpm-ops-ready-'))
  try {
    const installRoot = path.join(temporaryRoot, 'App')
    const dataRoot = path.join(temporaryRoot, 'Runtime')
    const inputRoot = path.join(temporaryRoot, 'inputs')
    const toolkitRoot = path.join(inputRoot, 'toolkit')
    const toolkitReleaseRoot = path.join(toolkitRoot, 'release')
    fs.mkdirSync(installRoot)
    fs.mkdirSync(dataRoot)
    fs.mkdirSync(toolkitReleaseRoot, { recursive: true })

    const inputs = {
      wrapper: path.join(inputRoot, 'WinSW.exe'),
      java: path.join(inputRoot, 'java.exe'),
      jar: path.join(inputRoot, 'ops-control-plane.jar'),
      config: path.join(inputRoot, 'application-production.yml'),
      opsStarter: path.join(inputRoot, 'Start-LeanTpmOpsControl.ps1'),
      agentStarter: path.join(inputRoot, 'Start-LeanTpmReleaseAgentService.ps1'),
      lock: path.join(toolkitReleaseRoot, 'release-agent-toolkit-lock.json'),
    }
    for (const [name, filePath] of Object.entries(inputs)) {
      if (name !== 'lock') fs.writeFileSync(filePath, `synthetic-${name}\n`, 'utf8')
    }

    const toolkitFiles = [
      'deploy/windows/Invoke-LeanTpmReleaseAgent.ps1',
      'scripts/Invoke-LeanTpmDeployment.ps1',
      'scripts/Test-LeanTpmReleaseApproval.ps1',
      'scripts/Test-ReleasePackage.ps1',
    ]
    const toolkitEntries = toolkitFiles.map((relativePath) => {
      const filePath = path.join(toolkitRoot, ...relativePath.split('/'))
      fs.mkdirSync(path.dirname(filePath), { recursive: true })
      fs.writeFileSync(filePath, `synthetic-${relativePath}\n`, 'utf8')
      return {
        path: relativePath,
        sha256: crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex'),
      }
    })
    fs.writeFileSync(inputs.lock, JSON.stringify({
      executorRelativePath: 'scripts/Invoke-LeanTpmDeployment.ps1',
      files: toolkitEntries,
      schemaVersion: 1,
      toolkitId: 'leantpm-release-agent-toolkit',
    }), 'utf8')

    const args = [
      '-WrapperPath', inputs.wrapper,
      '-InstallRoot', installRoot,
      '-DataRoot', dataRoot,
      '-JavaExecutable', inputs.java,
      '-OpsControlPlaneJarPath', inputs.jar,
      '-OpsControlPlaneConfigPath', inputs.config,
      '-SignedOpsStarterPath', inputs.opsStarter,
      '-SignedReleaseAgentStarterPath', inputs.agentStarter,
      '-DeploymentToolkitRoot', toolkitRoot,
      '-DeploymentToolkitLockPath', inputs.lock,
      '-OpsServiceAccount', 'TEST\\OpsControl$',
      '-ReleaseAgentServiceAccount', 'TEST\\ReleaseAgent$',
      '-BackendServiceAccount', 'TEST\\Backend$',
      '-ProxyServiceAccount', 'TEST\\Proxy$',
      '-AgentId', 'ops-agent-01',
      '-AgentVersion', '1.0.1',
      '-AllowNonProductionRoots',
      '-AllowUnverifiedTestHostState',
      '-OutputFormat', 'Json',
    ]
    const before = snapshotTree(temporaryRoot)
    const ready = invokePowerShell(readinessPath, args)
    assert.equal(ready.status, 0, combinedOutput(ready))
    const report = JSON.parse(ready.stdout.trim())
    assert.equal(report.status, 'PLAN_READY', JSON.stringify(report))
    assert.equal(report.readOnly, true)
    assert.equal(report.executable, false)
    assert.deepEqual(report.blockers, [])
    assert.equal(report.plan.status, 'PLAN')
    assert.equal(report.plan.opsControl.serviceId, 'LeanTPM.OpsControl')
    assert.equal(report.plan.releaseAgent.serviceId, 'LeanTPM.ReleaseAgent')
    assert.equal(report.plan.releaseAgent.mode, 'ExecuteSignedDeployment')
    assert.equal(
      report.pins.winSWSha256,
      crypto.createHash('sha256').update(fs.readFileSync(inputs.wrapper)).digest('hex'),
    )
    assert.equal(
      report.pins.toolkitLockSha256,
      crypto.createHash('sha256').update(fs.readFileSync(inputs.lock)).digest('hex'),
    )
    assert.deepEqual(snapshotTree(temporaryRoot), before)

    const driftedToolkitFile = path.join(
      toolkitRoot,
      'scripts',
      'Invoke-LeanTpmDeployment.ps1',
    )
    fs.appendFileSync(driftedToolkitFile, 'drift\n', 'utf8')
    const driftBefore = snapshotTree(temporaryRoot)
    const drifted = invokePowerShell(readinessPath, args)
    assert.equal(drifted.status, 0, combinedOutput(drifted))
    const driftReport = JSON.parse(drifted.stdout.trim())
    assert.equal(driftReport.status, 'INPUT_REQUIRED')
    assert.ok(driftReport.blockers.some(
      (blocker) => blocker.code === 'TOOLKIT_FILE_HASH_MISMATCH',
    ))
    assert.deepEqual(snapshotTree(temporaryRoot), driftBefore)

    const duplicateArgs = [...args]
    duplicateArgs[
      duplicateArgs.indexOf('-ReleaseAgentServiceAccount') + 1
    ] = 'TEST\\OpsControl$'
    const duplicate = invokePowerShell(readinessPath, duplicateArgs)
    assert.equal(duplicate.status, 0, combinedOutput(duplicate))
    const duplicateReport = JSON.parse(duplicate.stdout.trim())
    assert.equal(duplicateReport.status, 'INPUT_REQUIRED')
    assert.ok(duplicateReport.blockers.some(
      (blocker) => blocker.code === 'SERVICE_ACCOUNTS_NOT_DISTINCT',
    ))

    const source = fs.readFileSync(readinessPath, 'utf8')
    assert.match(source, /Install-LeanTpmOpsServices\.ps1/)
    assert.match(source, /PlanOnly\s*=\s*\$true/)
    assert.match(source, /AllowUnverifiedTestHostState[\s\S]*AllowNonProductionRoots/)
    assert.doesNotMatch(
      source,
      /\b(?:New-Item|Set-Content|Add-Content|Copy-Item|Move-Item|Remove-Item|Start-Service|Stop-Service|Restart-Service|Set-Service|New-Service)\b|sc\.exe\s+(?:create|config|start|stop|delete)|icacls\.exe/i,
    )
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
})
