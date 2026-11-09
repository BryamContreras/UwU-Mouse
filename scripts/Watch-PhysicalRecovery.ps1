param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='physical-worker-final',[int]$TimeoutSeconds=600,[switch]$WaitForSession)
. "$PSScriptRoot\AndroidTools.ps1"
if($Case -notmatch '^[a-zA-Z0-9_-]{1,80}$'){throw 'Invalid case label'}
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$destination=Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\v011\$Case"
New-Item -ItemType Directory -Force $destination|Out-Null
function Observe {
    ((& $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-live.json)-join"`n")|ConvertFrom-Json
}
function State {
    $wire=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join'')
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw $wire}
    $Matches[1]|ConvertFrom-Json
}
function Save($Value,[string]$Name){$Value|ConvertTo-Json -Depth 16|Set-Content (Join-Path $destination $Name) -Encoding UTF8}
function Test-ReadyReceiver($Snapshot,$Controller){
    try {
        if(!$Controller.active -or !$Controller.backend -or !$Snapshot.activityResumed -or !$Snapshot.windowFocused){return $false}
        Assert-ProjectLiveReceiver $adbPath $targetSerial $Snapshot $Controller.uptimeMs
        return $true
    } catch {return $false}
}
# Does not start a session, create touches, launch Activities or recover via the PC.
$readyTimer=[Diagnostics.Stopwatch]::StartNew()
do {
    $ready=$false
    try {
        $before=Observe
        $initial=State
        $ready=Test-ReadyReceiver $before $initial
    } catch {if(!$WaitForSession){throw}}
    if($ready){break}
    if(!$WaitForSession){break}
    Start-Sleep -Milliseconds 250
} while($readyTimer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
if(!$ready){throw 'Start the ordinary physical session with a live, focused InputLab receiver first'}
Save $before 'watch-before.json'
$timer=[Diagnostics.Stopwatch]::StartNew()
do {
    $active=Observe
    if($active.observationId -ne $before.observationId -or !$active.windowFocused){throw 'Observer/focus changed before fault'}
    if(@($active.pendingContactIds).Count -ge 3){break}
    Start-Sleep -Milliseconds 150
} while($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
if(@($active.pendingContactIds).Count -lt 3){throw 'No physical three-contact window; no process was signalled'}
$live=State
Save $active 'active.json'
Save $live 'active-controller.json'
$workerProcess=[string]$live.workerPid
$identity=(& $adbPath -s $targetSerial shell ps -p $workerProcess -o UID,NAME)-join' '
if($identity -notmatch '\b2000\s+uwumouse_injector\b'){throw 'Unexpected injector identity'}
& $adbPath -s $targetSerial shell kill -9 $workerProcess
if($LASTEXITCODE -ne 0){throw 'Could not provoke injector death'}
$timer.Restart()
do {
    Start-Sleep -Milliseconds 150
    $after=Observe
    $end=State
    if(@($after.pendingContactIds).Count -eq 0 -and !$end.releaseUnverified -and $end.recoveryState -eq 'RECOVERED'){break}
} while($timer.Elapsed.TotalSeconds -lt 10)
Save $after 'after.json'
Save $end 'controller.json'
& $adbPath -s $targetSerial logcat -d "--pid=$workerProcess" -v brief -s UwuInjectWorker:I '*:S'|
    Set-Content (Join-Path $destination 'dead-worker.log') -Encoding UTF8
if($after.observationId -ne $before.observationId -or $after.activityId -ne $before.activityId -or
    !$after.windowFocused -or $after.sequenceErrors -ne $before.sequenceErrors -or
    @($after.pendingContactIds).Count -ne 0 -or $end.active -or $end.releaseUnverified -or !$end.cancelAdmitted){throw 'Physical recovery failed; retain evidence'}
Save ([ordered]@{passed=$true;observer=$after.observationId;oldWorker=$workerProcess;newWorker=$end.workerPid;
    receiverZero=$true;autoRecovery=$true;pcRescueCommands=0;durationMs=($end.recoveredUptime-$end.detectedUptime)}) 'recovery-result.json'
Write-Host "PASS physical recovery, same observer $($after.observationId), zero contacts; release physical keys before starting a new session"
