<#
Fault tests against an already visible, empty InputLab. No Activity launch, input tap,
observer restart, data reset or build is performed. Receiver observations only read its file.
Guardian/AllAuthority explicitly run Activate.ps1 once after recording total authority loss.
That is manual restoration of authorization, not automatic injector-worker recovery.
ForceStop deliberately leaves the mapper stopped and never calls its provider afterward.
#>
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateSet('Guardian','AllAuthority','Controller','ForceStop')][string]$Mode,
    [string]$Case = ''
)
. "$PSScriptRoot\AndroidTools.ps1"
if(!$Case){$Case="control-$($Mode.ToLowerInvariant())-$(Get-Date -Format 'yyyyMMdd-HHmmss')"}
if($Case -notmatch '^[a-zA-Z0-9_-]{1,80}$'){throw 'Case must contain only letters, numbers, underscore or hyphen (1-80 characters)'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$destination=Join-Path $projectRoot "evidence\v011\$Case"
if(Test-Path -LiteralPath $destination){throw 'Evidence directory already exists; choose a new Case'}
New-Item -ItemType Directory -Path $destination|Out-Null
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$mapperPackage='dev.mobimapper.mobimapper'
$providerForbidden=$false
$before=$null
$initialProcessIds=@()
$failure=$null
$result=[ordered]@{
    case=$Case; mode=$Mode; serial=$targetSerial; startedUtc=[DateTime]::UtcNow.ToString('o')
    passed=$false; sameObserver=$false; receiverZero=$false; activateCalls=0
    authorityRestoration='none'; workerAutoRecoveryClaimed=$false; receiverVerifiedByBackend=$false
    packageLeftStopped=$false; failure=$null
}
function Save-Json($Value,[string]$Name){
    $Value|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $destination $Name) -Encoding UTF8
}
function Invoke-ControlAdb([string[]]$Arguments){
    $output=& $adbPath -s $targetSerial @Arguments
    if($LASTEXITCODE -ne 0){throw "ADB failed: $($Arguments -join ' ')"}
    return $output
}
function Read-State {
    if($script:providerForbidden){throw 'Mapper provider calls are forbidden after ForceStop'}
    $wire=(Invoke-ControlAdb @('shell','content','call','--uri','content://dev.mobimapper.mobimapper.backend','--method','diagnostics'))-join''
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw "No native status: $wire"}
    return $Matches[1]|ConvertFrom-Json
}
function Call([string]$Method,[string[]]$Extra=@()){
    if($script:providerForbidden){throw 'Mapper provider calls are forbidden after ForceStop'}
    $wire=(Invoke-ControlAdb (@('shell','content','call','--uri','content://dev.mobimapper.mobimapper.backend','--method',$Method)+$Extra))-join''
    if($wire -notmatch 'accepted=true'){throw "Diagnostic command was not accepted: $Method $wire"}
}
function Observe {
    $wire=(Invoke-ControlAdb @('shell','run-as','dev.mobimapper.inputlab','cat','files/input-lab-live.json'))-join"`n"
    return $wire|ConvertFrom-Json
}
function Assert-Observer($Snapshot){
    if($null -eq $before){return}
    if($Snapshot.observationId -ne $before.observationId -or $Snapshot.activityId -ne $before.activityId -or
        $Snapshot.pid -ne $before.pid -or !$Snapshot.activityResumed -or !$Snapshot.windowFocused){
        throw 'The independent observer, Activity, process or focused window changed'
    }
    if($Snapshot.sequenceErrors -ne $before.sequenceErrors){throw 'Receiver sequence validation failed'}
}
function Await-Contacts([int]$Expected,[int]$TimeoutSeconds=15){
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot=Observe
        Assert-Observer $snapshot
        if(@($snapshot.pendingContactIds).Count -eq $Expected){return $snapshot}
        Start-Sleep -Milliseconds 150
    } while($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Receiver did not reach $Expected contacts"
}
function Await-State([scriptblock]$Predicate,[int]$TimeoutSeconds=15){
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $state=Read-State
        if(& $Predicate $state){return $state}
        Start-Sleep -Milliseconds 200
    } while($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    Save-Json $state 'last-unsatisfied-controller.json'
    throw 'Controller did not reach the expected state'
}
function Process-Ids([string]$Name){
    # pidof returns 1 for an absent process; this is expected after crash/ForceStop.
    $wire=((& $adbPath -s $targetSerial shell pidof $Name)-join' ').Trim()
    if($LASTEXITCODE -notin @(0,1)){throw "Unable to observe process $Name"}
    if(!$wire){return @()}
    if($wire -notmatch '^\d+(\s+\d+)*$'){throw "Unexpected pidof response for $Name"}
    return @($wire -split '\s+')
}
function Assert-OwnedProcess([long]$ProcessId,[string]$Name){
    if($ProcessId -le 0){throw "Missing $Name PID"}
    $identity=(Invoke-ControlAdb @('shell','ps','-p',[string]$ProcessId,'-o','UID,NAME'))-join"`n"
    if($identity -notmatch ("(?m)^\s*2000\s+"+[regex]::Escape($Name)+"\s*$")){
        throw "Refusing to signal unexpected process $ProcessId ($Name)"
    }
    $identity|Set-Content -LiteralPath (Join-Path $destination "$Name-identity.txt") -Encoding UTF8
}
function Read-PackageState {
    $wire=(Invoke-ControlAdb @('shell','dumpsys','package',$mapperPackage))-join"`n"
    $wire|Set-Content -LiteralPath (Join-Path $destination 'mapper-package-state.txt') -Encoding UTF8
    if($wire -notmatch '(?m)^\s*User 0:.*\bstopped=(true|false)\b'){throw 'Unable to read mapper stopped flag for user 0'}
    return [pscustomobject]@{stopped=($Matches[1] -eq 'true'); processIds=@(Process-Ids $mapperPackage)}
}
function Collect-Logs {
    $ids=@($script:initialProcessIds)
    foreach($name in @($mapperPackage,'dev.mobimapper.inputlab','mobimapper_backend','mobimapper_injector')){
        $ids+=@(Process-Ids $name)
    }
    foreach($processId in @($ids|Sort-Object -Unique)){
        if([string]$processId -notmatch '^\d+$' -or [long]$processId -le 0){continue}
        Invoke-ControlAdb @('logcat','-d',"--pid=$processId",'-v','brief','-s','MobiMapper:I','MobiSupervisor:I','MobiInjectWorker:I','MobiBackend:I','MobiActivity:I','MobiInputLab:I','*:S')|
            Set-Content -LiteralPath (Join-Path $destination "process-$processId.log") -Encoding UTF8
    }
}
try {
    $before=Observe
    Save-Json $before 'before.json'
    if(!$before.activityResumed -or !$before.windowFocused -or @($before.pendingContactIds).Count -ne 0){
        throw 'InputLab must already be visible, focused and empty'
    }
    $initialState=Read-State
    Save-Json $initialState 'before-controller.json'
    Assert-ProjectLiveReceiver $adbPath $targetSerial $before $initialState.uptimeMs
    if(!$initialState.backend -or !$initialState.injectPermission -or $initialState.active -or $initialState.releaseUnverified -or $initialState.contacts -ne 0){
        throw 'Requires authorized backend and an inactive, recovered controller'
    }
    $initialMapperIds=@(Process-Ids $mapperPackage)
    if($initialMapperIds.Count -ne 1){throw 'Expected one live mapper controller process'}
    $initialProcessIds=@($initialMapperIds)+@([string]$before.pid,[string]$initialState.supervisorPid,[string]$initialState.workerPid)
    Call 'labProbe' @('--extra','count:i:3','--extra','hold:i:30000')
    $active=Await-Contacts 3
    Save-Json $active 'active.json'
    $live=Read-State
    Save-Json $live 'active-controller.json'
    if(!$live.backend -or $live.releaseUnverified -or $live.contacts -ne 3){throw 'Three-contact controller stream was not confirmed before fault'}
    $guardianPid=[long]$live.supervisorPid
    $workerPid=[long]$live.workerPid
    $initialProcessIds+=@([string]$guardianPid,[string]$workerPid)
    Assert-OwnedProcess $guardianPid 'mobimapper_backend'
    Assert-OwnedProcess $workerPid 'mobimapper_injector'
    switch($Mode){
        'Guardian' {
            Invoke-ControlAdb @('shell','kill','-9',[string]$guardianPid)|Out-Null
            $lostReceiver=Await-Contacts 0
            Save-Json $lostReceiver 'authority-lost-receiver.json'
            $lost=Await-State {param($s) !$s.backend -and $s.releaseUnverified -and !$s.active}
            Save-Json $lost 'authority-lost-controller.json'
        }
        'AllAuthority' {
            $remoteScript='/data/local/tmp/mobimapper-drop-privilege-test.sh'
            Invoke-ControlAdb @('push',(Join-Path $PSScriptRoot 'drop-privilege-test.sh'),$remoteScript)|
                Set-Content -LiteralPath (Join-Path $destination 'drop-push.txt') -Encoding UTF8
            Invoke-ControlAdb @('shell','sh',$remoteScript,[string]$workerPid,[string]$guardianPid)|
                Set-Content -LiteralPath (Join-Path $destination 'drop-output.txt') -Encoding UTF8
            $lost=Await-State {param($s) !$s.backend -and $s.releaseUnverified -and !$s.active}
            Save-Json $lost 'authority-lost-controller.json'
            $held=Observe
            Assert-Observer $held
            Save-Json $held 'authority-lost-receiver.json'
            if(@($held.pendingContactIds).Count -ne 3){throw 'Complete authority loss did not preserve the three unresolved receiver contacts'}
            Start-Sleep -Milliseconds 500
            $stillHeld=Observe
            Assert-Observer $stillHeld
            Save-Json $stillHeld 'authority-lost-receiver-confirmed.json'
            if(@($stillHeld.pendingContactIds).Count -ne 3){throw 'Receiver contacts changed before explicit authority restoration'}
        }
        'Controller' {
            Invoke-ControlAdb @('shell','am','crash','--user','0',$mapperPackage)|
                Set-Content -LiteralPath (Join-Path $destination 'crash-output.txt') -Encoding UTF8
            $cleared=Await-Contacts 0
            Save-Json $cleared 'controller-lost-receiver.json'
            # Observe automatic process return before diagnostics, which could otherwise start a provider.
            $timer=[Diagnostics.Stopwatch]::StartNew()
            do {
                $returned=@(Process-Ids $mapperPackage)
                if($returned.Count -eq 1 -and $returned[0] -notin $initialMapperIds){break}
                Start-Sleep -Milliseconds 200
            } while($timer.Elapsed.TotalSeconds -lt 20)
            if($returned.Count -ne 1 -or $returned[0] -in $initialMapperIds){throw 'Controller did not restart automatically; no provider launch was attempted'}
            Save-Json ([ordered]@{before=$initialMapperIds; after=$returned}) 'controller-processes.json'
        }
        'ForceStop' {
            # From this line onward diagnostics, labStop and Activate are deliberately forbidden.
            $providerForbidden=$true
            Invoke-ControlAdb @('shell','am','force-stop','--user','0',$mapperPackage)|Out-Null
            $stoppedReceiver=Await-Contacts 0
            Save-Json $stoppedReceiver 'post.json'
            $timer=[Diagnostics.Stopwatch]::StartNew()
            do {
                $package=Read-PackageState
                if($package.stopped -and @($package.processIds).Count -eq 0){break}
                Start-Sleep -Milliseconds 200
            } while($timer.Elapsed.TotalSeconds -lt 12)
            Save-Json $package 'post-package.json'
            if(!$package.stopped -or @($package.processIds).Count -ne 0){throw 'ForceStop did not leave package stopped with no controller process'}
            Start-Sleep -Milliseconds 1500
            $unchanged=Observe
            Assert-Observer $unchanged
            if(@($unchanged.pendingContactIds).Count -ne 0){throw 'Contacts resumed after ForceStop'}
            $package=Read-PackageState
            Save-Json $package 'final-package.json'
            if(!$package.stopped -or @($package.processIds).Count -ne 0){throw 'Mapper was resurrected after explicit ForceStop'}
            Save-Json $unchanged 'final-receiver.json'
            $result.packageLeftStopped=$true
        }
    }
    if($Mode -in @('Guardian','AllAuthority')){
        Write-Host 'Restoring lost authority once with Activate.ps1; this is explicit restoration, not automatic worker recovery.'
        $result.activateCalls=1
        $result.authorityRestoration='explicit Activate.ps1 after total backend loss'
        & "$PSScriptRoot\Activate.ps1" -Serial $targetSerial|
            Set-Content -LiteralPath (Join-Path $destination 'activation.txt') -Encoding UTF8
    }
    if($Mode -ne 'ForceStop'){
        $post=Await-Contacts 0
        Save-Json $post 'post.json'
        $end=Await-State {param($s) $s.backend -and $s.injectPermission -and !$s.active -and !$s.releaseUnverified -and $s.contacts -eq 0}
        Save-Json $end 'post-controller.json'
        if($Mode -in @('Guardian','AllAuthority')){
            if($end.recoveryState -ne 'RECOVERED' -or (!$end.cancelAdmitted -and !$end.terminalReconciled)){
                throw 'Restored authority did not admit cancellation or reconcile an already empty OS stream'
            }
        } elseif([long]$end.supervisorPid -ne $guardianPid -or [long]$end.workerPid -ne $workerPid){
            throw 'Controller crash unexpectedly replaced the surviving guardian or injector'
        }
        $result.cancelAdmitted=[bool]$end.cancelAdmitted
        $result.terminalReconciled=[bool]$end.terminalReconciled
        $result.recoveryState=$end.recoveryState
        # Only this explicit new probe may create contacts after recovery.
        Call 'labProbe' @('--extra','count:i:1','--extra','hold:i:10000')
        $next=Await-Contacts 1
        Save-Json $next 'new-session-active.json'
        $nextState=Read-State
        Save-Json $nextState 'new-session-controller.json'
        if([long]$nextState.backendSessionId -le [long]$live.backendSessionId){throw 'New gesture did not use a newer session id'}
        Call 'labStop'
        $finished=Await-Contacts 0
        Save-Json $finished 'new-session-stopped.json'
        $finishedState=Await-State {param($s) !$s.active -and !$s.releaseUnverified -and $s.contacts -eq 0}
        Save-Json $finishedState 'final-controller.json'
    }
    $result.sameObserver=$true
    $result.receiverZero=$true
    $result.passed=$true
} catch {
    $failure=$_
    $result.failure=$_.Exception.Message
} finally {
    try {
        $last=Observe
        Save-Json $last 'last-receiver.json'
        if(!(Test-Path -LiteralPath (Join-Path $destination 'post.json'))){Save-Json $last 'post.json'}
    } catch { $_.Exception.Message|Set-Content -LiteralPath (Join-Path $destination 'observer-collection-error.txt') -Encoding UTF8 }
    try { Collect-Logs } catch { $_.Exception.Message|Set-Content -LiteralPath (Join-Path $destination 'log-collection-error.txt') -Encoding UTF8 }
    $result.finishedUtc=[DateTime]::UtcNow.ToString('o')
    Save-Json $result 'result.json'
}
if($null -ne $failure){throw $failure}
Write-Host "PASS $Mode sameObserver=$($before.observationId) receiverZero=true evidence=$destination"
if($Mode -eq 'ForceStop'){Write-Host 'Mapper remains explicitly stopped; no provider call or activation followed ForceStop.'}

