param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='continuous-worker')
. "$PSScriptRoot\AndroidTools.ps1"
if($Case -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid case'}
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$destination=Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\v020\$Case"
New-Item -ItemType Directory -Force $destination|Out-Null
function State {
    $wire=(& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join''
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'No mapper status'}
    $Matches[1]|ConvertFrom-Json
}
function Observe { ((& $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-live.json)-join'')|ConvertFrom-Json }
function Save($Value,[string]$Name){$Value|ConvertTo-Json -Depth 22|Set-Content -Encoding UTF8 (Join-Path $destination $Name)}
$before=Observe;$initial=State
Assert-ProjectLiveReceiver $adbPath $targetSerial $before $initial.uptimeMs
if(!$before.windowFocused-or!$before.activityResumed-or@($before.pendingContactIds).Count-ne0-or$initial.active-or$initial.releaseUnverified){throw 'Visible empty InputLab required'}
Save $before 'before.json'
& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method labMapped --extra hold:b:true
$held=$null
for($i=0;$i-lt40;$i++){
    Start-Sleep -Milliseconds 150
    $observed=Observe
    if(@($observed.pendingContactIds).Count-eq3){
        $sample=$observed.samples[-1]
        $camera=@($sample.contacts|Where-Object id -eq 9)
        if($camera.Count-eq1-and($camera[0].x-lt0-or$camera[0].x-ge$observed.width-or$camera[0].y-lt0-or$camera[0].y-ge$observed.height)){$held=$observed;break}
    }
}
if(!$held){throw 'Three held contacts with exterior camera were not observed'}
$active=State
if(!$active.active-or!$active.continuousCameraSession-or$active.backendCoordinatePolicy-ne'CONTINUOUS_CAMERA_9'){throw 'Wrong active capability'}
Save $held 'held.json';Save $active 'held-controller.json'
$workerProcess=[string]$active.workerPid
$identity=(& $adbPath -s $targetSerial shell ps -p $workerProcess -o UID,NAME)-join' '
if($identity-notmatch '\b2000\s+uwumouse_injector\b'){throw 'Unexpected worker identity'}
& $adbPath -s $targetSerial shell kill -9 $workerProcess
$after=$null
for($i=0;$i-lt40;$i++){
    Start-Sleep -Milliseconds 150
    $candidate=Observe
    if(@($candidate.pendingContactIds).Count-eq0){$after=$candidate;break}
}
if(!$after){throw 'Native contacts did not close'}
$end=State
Save $after 'after.json';Save $end 'controller.json'
Assert-ProjectLiveReceiver $adbPath $targetSerial $after $end.uptimeMs
if($after.observationId-ne$before.observationId-or$after.pid-ne$before.pid-or$after.activityId-ne$before.activityId-or$after.sequenceErrors-ne$before.sequenceErrors){throw 'Receiver changed or sequence failed'}
if($end.active-or$end.contacts-ne0-or$end.backendPossibleContacts-ne0-or$end.releaseUnverified-or$end.recoveryState-ne'RECOVERED'){throw 'Mapper recovery incomplete'}
$cancel=@($after.samples|Where-Object{$_.sequence-gt$held.totalTouchEvents-and$_.actionMasked-eq3})
if($cancel.Count-ne1){throw 'Expected one receiver-observed recovery CANCEL'}
$oldFrame=$held.samples[-1]
if($null-eq$oldFrame.downTimeUptimeMs-or$cancel[0].downTimeUptimeMs-ne$oldFrame.downTimeUptimeMs-or$cancel[0].contacts.Count-ne3){throw 'Recovery changed downTime or contact count'}
for($i=0;$i-lt3;$i++){
    $old=$oldFrame.contacts[$i];$current=$cancel[0].contacts[$i]
    if($old.id-ne$current.id-or[Math]::Abs($old.x-$current.x)-gt.05-or[Math]::Abs($old.y-$current.y)-gt.05){throw 'Recovery changed original IDs or exterior coordinates'}
}
Start-Sleep -Milliseconds 500
& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method labMapped --extra hold:b:true
$newHeld=$null
for($i=0;$i-lt40;$i++){
    Start-Sleep -Milliseconds 150
    $candidate=Observe
    if(@($candidate.pendingContactIds).Count-eq3){$newHeld=$candidate;break}
}
if(!$newHeld){throw 'New held session did not start'}
$newActive=State
if(!$newActive.active-or$newActive.backendSessionId-eq$active.backendSessionId){throw 'New session did not receive a new ID'}
$stale=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method labStale)-join'')
$stale|Set-Content (Join-Path $destination 'stale-result.txt') -Encoding UTF8
if($stale-notmatch'staleReleaseRejected=true'-or$stale-notmatch'staleInjectRejected=true'){throw 'Old work was not explicitly rejected'}
$still=Observe
if(@($still.pendingContactIds).Count-ne3-or$still.observationId-ne$before.observationId-or$still.sequenceErrors-ne$before.sequenceErrors){throw 'Old work changed the new gesture'}
Save $still 'new-held-after-stale.json'
& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method labStop
for($i=0;$i-lt40;$i++){
    Start-Sleep -Milliseconds 150
    $candidate=Observe
    if(@($candidate.pendingContactIds).Count-eq0){break}
}
& "$PSScriptRoot\Test-MappedProfile.ps1" -Serial $targetSerial -Case "$Case-new-session" -RequireContinuous
$final=State
if($final.active-or$final.contacts-ne0-or$final.releaseUnverified-or$final.backendSessionId-eq$active.backendSessionId){throw 'New continuous session did not finish safely'}
Save ([ordered]@{sameObserver=$after.observationId;synthetic=$true;physicalKeyboardMouse=$false;outsideCameraId=9;originalDownTimeMs=$oldFrame.downTimeUptimeMs;cancelSequence=$cancel[0].sequence;receiverErrorDelta=$after.sequenceErrors-$before.sequenceErrors;pending=@($after.pendingContactIds);cancelAdmitted=$end.cancelAdmitted;recoveryMilliseconds=$end.recoveredUptime-$end.detectedUptime;newSessionId=$final.backendSessionId;note='Original exterior coordinates observed in native receiver CANCEL; no proprietary game events.'}) 'result.json'
Write-Host "PASS $Case exactExteriorCancel=true sameObserver=$($after.observationId) newContinuousSession=true"
