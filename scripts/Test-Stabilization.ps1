param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='normal',[int]$Count=3,[int]$FaultAction=-1,[int]$FaultStage=0,[ValidateSet('v011','v020')][string]$EvidenceVersion='v011')
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$projectRoot=Split-Path -Parent $PSScriptRoot
if($Case -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Invalid case label'}
$destination=Join-Path $projectRoot "evidence\$EvidenceVersion\$Case"
New-Item -ItemType Directory -Force -Path $destination|Out-Null
function State {
    $wire=(& $adbPath -s $targetSerial shell content call --uri content://dev.mobimapper.mobimapper.backend --method diagnostics)-join''
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw "No native status: $wire"}
    return $Matches[1]|ConvertFrom-Json
}
function Observe {
    $wire=(& $adbPath -s $targetSerial shell run-as dev.mobimapper.inputlab cat files/input-lab-live.json)-join"`n"
    return $wire|ConvertFrom-Json
}
function Await-Contacts([int]$Expected,[int]$Attempts=16) {
    for($i=0;$i -lt $Attempts;$i++){
        Start-Sleep -Milliseconds 150
        $snapshot=Observe
        if(@($snapshot.pendingContactIds).Count -eq $Expected){return $snapshot}
    }
    throw "Observer did not reach $Expected contacts"
}
function Call([string]$Method,[string[]]$Extra=@()) {
    & $adbPath -s $targetSerial shell content call --uri content://dev.mobimapper.mobimapper.backend --method $Method @Extra
}
$before=Observe
$before|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $destination 'before.json') -Encoding UTF8
if(!$before.activityResumed -or !$before.windowFocused -or @($before.pendingContactIds).Count -ne 0){throw 'Lab must be visible and empty before the test'}
$initialState=State
Assert-ProjectLiveReceiver $adbPath $targetSerial $before $initialState.uptimeMs
if($initialState.active -or $initialState.releaseUnverified){throw 'Prior session is not recovered'}
if($FaultStage -gt 0){Call 'labFault' @('--extra',"action:i:$FaultAction",'--extra',"stage:i:$FaultStage")}
Call 'labProbe' @('--extra',"count:i:$Count",'--extra','hold:i:15000','--extra',"terminal:b:$($FaultAction -eq 1)")
if($FaultStage -eq 0){
    $active=Await-Contacts $Count
    $active|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $destination 'active.json') -Encoding UTF8
    $live=State
    $live|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'active-controller.json') -Encoding UTF8
    if($Case -like 'worker-*'){
        $workerProcess=[string]$live.workerPid
        $identity=(& $adbPath -s $targetSerial shell ps -p $workerProcess -o UID,NAME)-join' '
        if($identity -notmatch '\b2000\s+mobimapper_injector\b'){throw 'Unexpected injector identity'}
        & $adbPath -s $targetSerial shell kill -9 $workerProcess
    } elseif($Case -like 'activity-recreate*'){
        Call 'labRecreateUi'
        Start-Sleep -Milliseconds 500
        $recreated=State
        if($recreated.uiCreated -le $live.uiCreated -or $recreated.uiDestroyed -le $live.uiDestroyed -or $recreated.uiInstance -eq $live.uiInstance){throw 'Flutter Activity was not actually recreated'}
        $recreated|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'recreated-controller.json') -Encoding UTF8
        $unchanged=Observe
        if($unchanged.observationId -ne $before.observationId -or @($unchanged.pendingContactIds).Count -ne $Count){throw 'Activity recreation interrupted the independent gesture'}
        Call 'labStop'
    } else { Call 'labStop' }
}
$after=Await-Contacts 0 24
$end=State
$after|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $destination 'after.json') -Encoding UTF8
$end|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'controller.json') -Encoding UTF8
if($after.observationId -ne $before.observationId -or $after.activityId -ne $before.activityId -or $after.pid -ne $before.pid -or !$after.activityResumed -or !$after.windowFocused){throw 'Observer/window changed during test'}
if($after.sequenceErrors -ne $before.sequenceErrors){throw 'Receiver sequence validation failed'}
if($end.contacts -ne 0 -or $end.releaseUnverified){throw 'Controller remains uncertain; inspect evidence'}
if($FaultStage -gt 0 -or $Case -like 'worker-*'){
    if($end.recoveryState -ne 'RECOVERED' -or (!$end.cancelAdmitted -and !$end.terminalReconciled)){throw 'Recovery was not admitted/reconciled'}
    # New ordinary session must function after recovery, without reactivating old held actions.
    Call 'labProbe' @('--extra','count:i:1','--extra','hold:i:10000')
    $next=Await-Contacts 1
    Call 'labStale'
    $still=Observe
    if(@($still.pendingContactIds).Count -ne 1){throw 'Stale work affected the new session'}
    $still|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $destination 'after-stale.json') -Encoding UTF8
    Call 'labStop'
    $finished=Await-Contacts 0
    $finished|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $destination 'new-session.json') -Encoding UTF8
    if($finished.observationId -ne $before.observationId -or $finished.sequenceErrors -ne $before.sequenceErrors){throw 'New session did not preserve observer'}
}
foreach($processName in @('dev.mobimapper.mobimapper','dev.mobimapper.inputlab','mobimapper_backend','mobimapper_injector')){
    $processId=((& $adbPath -s $targetSerial shell pidof $processName)-join'').Trim()
    if($processId -match '^\d+$'){
        & $adbPath -s $targetSerial logcat -d "--pid=$processId" -v brief -s MobiMapper:I MobiSupervisor:I MobiInjectWorker:I MobiActivity:I MobiInputLab:I '*:S'|Set-Content -LiteralPath (Join-Path $destination "$processName.log") -Encoding UTF8
    }
}
Write-Host "PASS $Case count=$Count sameObserver=$($before.observationId) zeroContacts=true receiverErrors=$($after.sequenceErrors) recovery=$($end.recoveryState)"
