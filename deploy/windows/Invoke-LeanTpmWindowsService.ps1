[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][ValidateSet('Status', 'Start', 'Stop', 'Restart', 'Uninstall')]
    [string]$Action,
    [Parameter(Mandatory)][string]$InstallRoot,
    [Parameter(Mandatory)][string]$DataRoot,
    [string]$DeploymentLockToken = '',
    [switch]$AllowNonProductionRoot,
    [switch]$RecoveryContainmentOnly,
    [switch]$PlanOnly,
    [switch]$ConfirmServiceAction,
    [string]$ConfirmUninstallServiceId = '',
    [string]$ApprovalPlanPath = '',
    [string]$RequesterSignaturePath = '',
    [string]$ApproverSignaturePath = '',
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$serviceId = 'LeanTPM.Backend'
$install = (Resolve-Path -LiteralPath $InstallRoot).Path.TrimEnd('\', '/')
$data = (Resolve-Path -LiteralPath $DataRoot).Path.TrimEnd('\', '/')
$environmentKind = if ($AllowNonProductionRoot) { 'NON_PRODUCTION' } else { 'PRODUCTION' }
$rootPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
    -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
    -PlanOnly:$PlanOnly `
    -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
    -ContainmentOnly:$RecoveryContainmentOnly `
    -OutputFormat Json | ConvertFrom-Json
$isProductionRootPair = [bool]$rootPolicy.isProductionRootPair
if ($isProductionRootPair -and $AllowNonProductionRoot) {
    throw 'AllowNonProductionRoot cannot be used with the production root pair'
}

function Assert-HostLayoutPolicyUnchanged {
    $lockedPolicy = & (Join-Path $PSScriptRoot 'Test-LeanTpmProductionRootPolicy.ps1') `
        -InstallRoot $install -DataRoot $data -EnvironmentKind $environmentKind `
        -AllowNonProductionCustomRoots:$AllowNonProductionRoot `
        -ContainmentOnly:$RecoveryContainmentOnly `
        -OutputFormat Json | ConvertFrom-Json
    if ([bool]$lockedPolicy.isProductionRootPair -ne $isProductionRootPair -or
            ($isProductionRootPair -and (
                [string]$lockedPolicy.hostLayoutSha256 -cne
                    [string]$rootPolicy.hostLayoutSha256 -or
                [string]$lockedPolicy.hostId -cne [string]$rootPolicy.hostId -or
                [string]$lockedPolicy.volumeIdentity -cne [string]$rootPolicy.volumeIdentity -or
                [string]$lockedPolicy.proxyBindingSha256 -cne
                    [string]$rootPolicy.proxyBindingSha256
            ))) {
        throw 'Host layout policy changed before the Windows Service action'
    }
}
if ($RecoveryContainmentOnly -and (
        $Action -notin @('Start', 'Stop') -or -not $isProductionRootPair -or
        [string]$rootPolicy.proxy.mode -cne 'EXTERNAL_EXISTING' -or
        $DeploymentLockToken -notmatch '^[a-f0-9]{64}$')) {
    throw 'RecoveryContainmentOnly requires a locked production Start/Stop operation'
}
$expectedImagePath = Join-Path $install 'service\LeanTPM.Backend.exe'
$steps = switch ($Action) {
    'Status' { @('VERIFY_SCM_BINDING', 'QUERY_STATUS') }
    'Start' { @('VERIFY_SCM_BINDING', 'START', 'QUERY_STATUS') }
    'Stop' { @('VERIFY_SCM_BINDING', 'STOP', 'QUERY_STATUS') }
    'Restart' { @('VERIFY_SCM_BINDING', 'STOP', 'START', 'QUERY_STATUS') }
    'Uninstall' { @('VERIFY_SCM_BINDING', 'STOP', 'UNINSTALL', 'QUERY_STATUS') }
}
$report = [pscustomobject]@{
    status = if ($PlanOnly) { 'PLAN' } else { 'READY' }
    serviceId = $serviceId
    action = $Action.ToUpperInvariant()
    expectedImagePath = $expectedImagePath
    hostLayoutSha256 = if ($isProductionRootPair) {
        [string]$rootPolicy.hostLayoutSha256
    }
    else { $null }
    steps = $steps
}
if ($PlanOnly) {
    if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 4 -Compress }
    else { $report | Format-List }
    return
}

$bindingReport = & (Join-Path $PSScriptRoot 'Test-LeanTpmWindowsServiceBinding.ps1') `
    -InstallRoot $install -DataRoot $data -OutputFormat Json | ConvertFrom-Json
if ([string]$bindingReport.status -cne 'PASS') {
    throw 'LeanTPM.Backend service supply-chain binding validation failed'
}
$serviceBinding = Get-CimInstance -ClassName Win32_Service `
    -Filter "Name='LeanTPM.Backend'" -ErrorAction Stop
if ($null -eq $serviceBinding) { throw 'LeanTPM.Backend is not registered in SCM' }
$actualImagePath = ([string]$serviceBinding.PathName).Trim().Trim('"')
if (-not $actualImagePath.Equals(
        $expectedImagePath,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw 'SCM LeanTPM.Backend image path differs from the host-owned fixed wrapper path'
}
if ($Action -ne 'Status' -and -not $ConfirmServiceAction) {
    throw 'ConfirmServiceAction is required for a mutating Windows Service action'
}
if ($Action -eq 'Uninstall' -and $ConfirmUninstallServiceId -cne $serviceId) {
    throw "ConfirmUninstallServiceId must exactly equal $serviceId"
}
if ($Action -eq 'Uninstall' -and $isProductionRootPair) {
    foreach ($approvalPath in @(
            $ApprovalPlanPath, $RequesterSignaturePath, $ApproverSignaturePath
        )) {
        if ([string]::IsNullOrWhiteSpace($approvalPath)) {
            throw 'PRODUCTION uninstall requires a signed two-person approval plan'
        }
    }
    $approvalPlanFile = (Resolve-Path -LiteralPath $ApprovalPlanPath).Path
    $approvalPlanBytes = [IO.File]::ReadAllBytes($approvalPlanFile)
    $approvalPlan = (New-Object Text.UTF8Encoding($false, $true)).
        GetString($approvalPlanBytes) | ConvertFrom-Json
    $trustConfigPath = Join-Path $data 'config\release-trust.json'
    $trust = Get-Content -LiteralPath (Resolve-Path -LiteralPath $trustConfigPath).Path `
        -Encoding utf8 -Raw | ConvertFrom-Json
    $expiresAt = [DateTimeOffset]::MinValue
    if ([string]$approvalPlan.action -cne 'UNINSTALL' -or
            [string]$approvalPlan.serviceId -cne $serviceId -or
            [string]$approvalPlan.environmentId -cne [string]$trust.environmentId -or
            [string]$approvalPlan.hostId -cne [string]$trust.hostId -or
            [string]$approvalPlan.hostLayoutSha256 -cne
                [string]$rootPolicy.hostLayoutSha256 -or
            [string]$approvalPlan.volumeIdentity -cne [string]$rootPolicy.volumeIdentity -or
            [string]$approvalPlan.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
            -not [DateTimeOffset]::TryParse([string]$approvalPlan.expiresAtUtc, [ref]$expiresAt) -or
            $expiresAt -le [DateTimeOffset]::UtcNow -or
            $expiresAt -gt [DateTimeOffset]::UtcNow.AddHours(24) -or
            -not (Get-FileHash -Algorithm SHA256 -LiteralPath $expectedImagePath).Hash.Equals(
                [string]$approvalPlan.expectedImageSha256,
                [StringComparison]::OrdinalIgnoreCase
            )) {
        throw 'PRODUCTION uninstall approval is expired or not bound to this fixed service image/host'
    }
    $approvalReport = & (Join-Path $PSScriptRoot '..\..\scripts\Test-LeanTpmReleaseApproval.ps1') `
        -PlanPath $approvalPlanFile `
        -RequesterSignaturePath $RequesterSignaturePath `
        -ApproverSignaturePath $ApproverSignaturePath `
        -TrustConfigPath $trustConfigPath `
        -OutputFormat Json | ConvertFrom-Json
    $approvalHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $loadedApprovalSha256 = [BitConverter]::ToString(
            $approvalHasher.ComputeHash($approvalPlanBytes)
        ).Replace('-', '').ToLowerInvariant()
    }
    finally { $approvalHasher.Dispose() }
    if (-not ([string]$approvalReport.planSha256).Equals(
            $loadedApprovalSha256,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'PRODUCTION uninstall approval differs from the loaded plan bytes'
    }
}
if (-not $PSCmdlet.ShouldProcess($serviceId, $Action)) { return }

$ownedLock = $null
$serviceActionLedger = $null
$lockPath = Join-Path $data 'locks\deployment.lock'
try {
    if ($Action -ne 'Status') {
        if ([string]::IsNullOrWhiteSpace($DeploymentLockToken)) {
            $ownedLock = New-Object System.IO.FileStream(
                $lockPath,
                [System.IO.FileMode]::OpenOrCreate,
                [System.IO.FileAccess]::ReadWrite,
                [System.IO.FileShare]::Read
            )
            $ownedLock.SetLength(0)
            $ownedLock.Position = 0
            $ownedTokenBytes = New-Object byte[] 32
            $ownedTokenGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $ownedTokenGenerator.GetBytes($ownedTokenBytes) }
            finally { $ownedTokenGenerator.Dispose() }
            $ownedToken = [Text.Encoding]::ASCII.GetBytes(
                [BitConverter]::ToString($ownedTokenBytes).Replace('-', '').ToLowerInvariant()
            )
            $ownedLock.Write($ownedToken, 0, $ownedToken.Length)
            $ownedLock.Flush($true)
        }
        else {
            if ($DeploymentLockToken -notmatch '^[a-f0-9]{64}$' -or
                    -not (Test-Path -LiteralPath $lockPath -PathType Leaf) -or
                    (Get-Content -LiteralPath $lockPath -Encoding ascii -Raw).Trim() -cne
                        $DeploymentLockToken) {
                throw 'Caller deployment lock token is invalid'
            }
        }
        Assert-HostLayoutPolicyUnchanged
        if ($RecoveryContainmentOnly) {
            $containmentMarkerPath = Join-Path $data 'state\recovery-inhibit.json'
            if (-not (Test-Path -LiteralPath $containmentMarkerPath -PathType Leaf) -or
                    ((Get-Item -LiteralPath $containmentMarkerPath -Force).Attributes -band
                        [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Recovery containment requires the host-owned recovery marker'
            }
            $containmentState = Get-Content -LiteralPath $containmentMarkerPath `
                -Encoding utf8 -Raw -ErrorAction Stop | ConvertFrom-Json
            if ([int]$containmentState.schemaVersion -ne 1 -or
                    [string]$containmentState.status -notin @(
                        'ACTIVATION_AUTHORIZED', 'RECOVERY_REQUIRED'
                    ) -or
                    [string]$containmentState.isolationMethod -notin @(
                        'SERVICE_STOP', 'HOST_FIREWALL'
                    ) -or
                    [string]$containmentState.proxyBindingSha256 -cne
                        [string]$rootPolicy.proxyBindingSha256 -or
                    [string]$containmentState.firewallPolicySha256 -cne
                        [string]$rootPolicy.proxyBinding.firewallPolicySha256) {
                throw 'Recovery containment marker is not bound to the exact external proxy'
            }
        }
    }

    function Get-LedgerEventHash {
        param([Parameter(Mandatory)]$EventWithoutHash)
        $canonical = $EventWithoutHash | ConvertTo-Json -Compress
        $hasher = [Security.Cryptography.SHA256]::Create()
        try {
            return [BitConverter]::ToString(
                $hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
            ).Replace('-', '').ToLowerInvariant()
        }
        finally { $hasher.Dispose() }
    }

    function Add-ServiceActionLedgerEvent {
        param(
            [Parameter(Mandatory)][IO.FileStream]$Stream,
            [Parameter(Mandatory)][string]$Status,
            [Parameter(Mandatory)][string]$PreviousHash
        )
        $eventWithoutHash = [ordered]@{
            schemaVersion = 1
            timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
            action = 'UNINSTALL'
            status = $Status
            serviceId = $serviceId
            nonce = [string]$approvalPlan.nonce
            planSha256 = $loadedApprovalSha256
            imageSha256 = ([string]$approvalPlan.expectedImageSha256).ToLowerInvariant()
            previousHash = $PreviousHash
        }
        $eventHash = Get-LedgerEventHash -EventWithoutHash $eventWithoutHash
        $event = [ordered]@{}
        foreach ($key in $eventWithoutHash.Keys) { $event[$key] = $eventWithoutHash[$key] }
        $event.hash = $eventHash
        $bytes = [Text.Encoding]::UTF8.GetBytes(
            (($event | ConvertTo-Json -Compress) + [Environment]::NewLine)
        )
        $Stream.Position = $Stream.Length
        $Stream.Write($bytes, 0, $bytes.Length)
        $Stream.Flush($true)
        return $eventHash
    }

    $serviceActionPreviousHash = ('0' * 64)
    if ($Action -eq 'Uninstall' -and $isProductionRootPair) {
        $ledgerPath = Join-Path $data 'audit\service-action-nonces.jsonl'
        $ledgerDirectory = Split-Path -Parent $ledgerPath
        if (-not (Test-Path -LiteralPath $ledgerDirectory -PathType Container) -or
                ((Get-Item -LiteralPath $ledgerDirectory -Force).Attributes -band
                    [IO.FileAttributes]::ReparsePoint)) {
            throw 'Host-owned service action audit directory is missing or unsafe'
        }
        $serviceActionLedger = New-Object IO.FileStream(
            $ledgerPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::Read
        )
        $reader = New-Object IO.StreamReader(
            $serviceActionLedger,
            (New-Object Text.UTF8Encoding($false, $true)),
            $true,
            4096,
            $true
        )
        try {
            $serviceActionLedger.Position = 0
            $ledgerText = $reader.ReadToEnd()
        }
        finally { $reader.Dispose() }
        foreach ($line in @($ledgerText -split "`r?`n" | Where-Object { $_.Length -gt 0 })) {
            try { $existingEvent = $line | ConvertFrom-Json -ErrorAction Stop }
            catch { throw 'Service action nonce ledger contains invalid JSON' }
            $existingWithoutHash = [ordered]@{
                schemaVersion = [int]$existingEvent.schemaVersion
                timestampUtc = [string]$existingEvent.timestampUtc
                action = [string]$existingEvent.action
                status = [string]$existingEvent.status
                serviceId = [string]$existingEvent.serviceId
                nonce = [string]$existingEvent.nonce
                planSha256 = [string]$existingEvent.planSha256
                imageSha256 = [string]$existingEvent.imageSha256
                previousHash = [string]$existingEvent.previousHash
            }
            $eventTime = [DateTimeOffset]::MinValue
            if ([int]$existingEvent.schemaVersion -ne 1 -or
                    [string]$existingEvent.action -cne 'UNINSTALL' -or
                    [string]$existingEvent.status -notin @(
                        'UNINSTALL_STARTED', 'UNINSTALL_COMPLETED'
                    ) -or
                    [string]$existingEvent.serviceId -cne $serviceId -or
                    [string]$existingEvent.nonce -notmatch '^[A-Fa-f0-9-]{16,64}$' -or
                    [string]$existingEvent.planSha256 -notmatch '^[a-f0-9]{64}$' -or
                    [string]$existingEvent.imageSha256 -notmatch '^[a-f0-9]{64}$' -or
                    -not [DateTimeOffset]::TryParse(
                        [string]$existingEvent.timestampUtc, [ref]$eventTime
                    ) -or
                    [string]$existingEvent.previousHash -cne $serviceActionPreviousHash -or
                    [string]$existingEvent.hash -cne
                        (Get-LedgerEventHash -EventWithoutHash $existingWithoutHash)) {
                throw 'Service action nonce ledger hash chain is invalid'
            }
            if ([string]$existingEvent.nonce -ceq [string]$approvalPlan.nonce -or
                    [string]$existingEvent.planSha256 -ceq $loadedApprovalSha256) {
                throw 'PRODUCTION uninstall approval nonce or plan has already been consumed'
            }
            $serviceActionPreviousHash = [string]$existingEvent.hash
        }
        $serviceActionPreviousHash = Add-ServiceActionLedgerEvent `
            -Stream $serviceActionLedger `
            -Status 'UNINSTALL_STARTED' `
            -PreviousHash $serviceActionPreviousHash
    }

    if ($Action -in @('Start', 'Restart')) {
        $stateDirectory = Join-Path $data 'state'
        $recoveryMarker = Join-Path $stateDirectory 'recovery-inhibit.json'
        try {
            if (-not [IO.Directory]::Exists($stateDirectory)) {
                throw 'Recovery state directory is missing'
            }
            $recoveryMarkers = @([IO.Directory]::EnumerateFiles(
                    $stateDirectory,
                    'recovery-inhibit.json',
                    [IO.SearchOption]::TopDirectoryOnly
                ))
        }
        catch {
            throw 'Recovery state directory cannot be read; service start is inhibited'
        }
        if ($recoveryMarkers.Count -gt 1) {
            throw 'Recovery state is ambiguous; service start is inhibited'
        }
        if ($recoveryMarkers.Count -eq 1) {
            try {
                $pointer = Get-Content -LiteralPath (Join-Path $data 'pointers\current-release.json') `
                    -Encoding utf8 -Raw -ErrorAction Stop | ConvertFrom-Json
                $recoveryState = Get-Content -LiteralPath $recoveryMarker -Encoding utf8 -Raw `
                    -ErrorAction Stop | ConvertFrom-Json
            }
            catch {
                throw 'Recovery state or release pointer cannot be read; service start is inhibited'
            }
            if ([int]$recoveryState.schemaVersion -ne 1 -or
                    [string]$recoveryState.status -notin @(
                        'ACTIVATION_AUTHORIZED', 'ROLLBACK_AUTHORIZED'
                    ) -or
                    [string]$recoveryState.authorizedReleaseId -cne [string]$pointer.releaseId -or
                    [string]$recoveryState.authorizedPackageSha256 -cne
                        [string]$pointer.packageSha256 -or
                    [string]$recoveryState.planSha256 -notmatch '^[a-f0-9]{64}$') {
                throw 'Recovery state does not authorize this exact release; service start is inhibited'
            }
        }
    }

    function Get-FixedService {
        Get-Service -Name $serviceId -ErrorAction Stop
    }

    switch ($Action) {
        'Status' { }
        'Start' {
            if ((Get-FixedService).Status -ne 'Running') {
                Start-Service -Name $serviceId -ErrorAction Stop
            }
            (Get-FixedService).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        }
        'Stop' {
            if ((Get-FixedService).Status -ne 'Stopped') {
                Stop-Service -Name $serviceId -ErrorAction Stop
            }
            (Get-FixedService).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        }
        'Restart' {
            if ((Get-FixedService).Status -ne 'Stopped') {
                Stop-Service -Name $serviceId -ErrorAction Stop
                (Get-FixedService).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
            }
            Start-Service -Name $serviceId -ErrorAction Stop
            (Get-FixedService).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        }
        'Uninstall' {
            $service = Get-FixedService
            if ($service.Status -ne 'Stopped') {
                Stop-Service -Name $serviceId -ErrorAction Stop
                (Get-FixedService).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
            }
            & sc.exe delete $serviceId | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'SCM failed to delete the fixed LeanTPM.Backend service'
            }
            if ($null -ne $serviceActionLedger) {
                $serviceActionPreviousHash = Add-ServiceActionLedgerEvent `
                    -Stream $serviceActionLedger `
                    -Status 'UNINSTALL_COMPLETED' `
                    -PreviousHash $serviceActionPreviousHash
            }
        }
    }
}
finally {
    if ($null -ne $serviceActionLedger) {
        $serviceActionLedger.Dispose()
    }
    if ($null -ne $ownedLock) {
        $ownedLock.Dispose()
    }
}
$remaining = Get-Service -Name $serviceId -ErrorAction SilentlyContinue
$report.status = 'COMPLETED'
$report | Add-Member -NotePropertyName serviceStatus -NotePropertyValue (
    if ($null -eq $remaining) { 'NOT_INSTALLED' } else { $remaining.Status.ToString() }
)
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Depth 5 -Compress }
else { $report | Format-List }
