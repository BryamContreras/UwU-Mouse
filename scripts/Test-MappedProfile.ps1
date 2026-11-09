param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='mapped-profile',[switch]$RequireContinuous)
. "$PSScriptRoot\AndroidTools.ps1"
if($Case -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid case label'}
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$destination=Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\v020\$Case"
New-Item -ItemType Directory -Force $destination|Out-Null
function State {
    $wire=(& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join''
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'No mapper status'}
    $Matches[1]|ConvertFrom-Json
}
function Observe { ((& $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-live.json)-join"`n")|ConvertFrom-Json }
function Save($Value,[string]$Name){$Value|ConvertTo-Json -Depth 22|Set-Content -Encoding UTF8 (Join-Path $destination $Name)}
$before=Observe;$initial=State
Assert-ProjectLiveReceiver $adbPath $targetSerial $before $initial.uptimeMs
if(!$before.activityResumed -or !$before.windowFocused -or @($before.pendingContactIds).Count -ne 0 -or $initial.active -or $initial.releaseUnverified){throw 'InputLab must be visible, empty, and idle'}
Save $before 'before.json'
& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method labMapped --extra automated:b:true
if($LASTEXITCODE -ne 0){throw 'Probe request failed'}
$complete=$false
for($i=0;$i -lt 40;$i++){
    Start-Sleep -Milliseconds 200
    $state=State
    if($state.probe -eq 'Secuencia de perfil completada' -and !$state.active -and $state.contacts -eq 0 -and !$state.releaseUnverified){$complete=$true;break}
    if($state.releaseUnverified){break}
}
Start-Sleep -Milliseconds 600
$after=Observe;$state=State
Save $state 'controller.json';Save $after 'after.json'
if(!$complete){throw "Mapped sequence did not finish: $($state.error)"}
Assert-ProjectLiveReceiver $adbPath $targetSerial $after $state.uptimeMs
if($after.observationId -ne $before.observationId -or $after.pid -ne $before.pid -or $after.sequenceErrors -ne $before.sequenceErrors -or @($after.pendingContactIds).Count -ne 0){throw 'Independent receiver changed or found invalid contacts'}
$events=@($after.samples|Where-Object {$_.sequence -gt $before.totalTouchEvents})
$minimum=if($RequireContinuous){14}else{16}
if(($after.totalTouchEvents-$before.totalTouchEvents) -lt $minimum -or $events.Count -lt $minimum){throw 'Insufficient observed mapped events'}
if($RequireContinuous){
    if(!$state.continuousCameraSession -or $state.backendCoordinatePolicy -ne 'CONTINUOUS_CAMERA_9' -or $state.cameraRestarts -ne 0){throw 'Continuous policy not installed or camera restarted'}
    $cameraStarts=@($events|Where-Object{$_.actionMasked -in @(0,5) -and $_.contacts[$_.actionIndex].id -eq 9})
    $cameraEnds=@($events|Where-Object{$_.actionMasked -in @(1,6) -and $_.contacts[$_.actionIndex].id -eq 9})
    $outside=@($events|Where-Object{$_.actionMasked-eq2}|ForEach-Object{$_.contacts}|Where-Object{$_.id-eq9-and($_.x-lt0-or$_.x-ge$after.width-or$_.y-lt0-or$_.y-ge$after.height)})
    if($cameraStarts.Count-ne1-or$cameraEnds.Count-ne0-or$outside.Count-lt1){throw 'Expected one uninterrupted exterior camera contact until final CANCEL'}
}
$firePoint=$null;$combined=0
foreach($event in $events){
    $ids=@($event.contacts|ForEach-Object{[int]$_.id})
    if($ids -contains 0){
        $fire=@($event.contacts|Where-Object{$_.id -eq 0})[0]
        if(!$firePoint){$firePoint=@([double]$fire.x,[double]$fire.y)}
        if([Math]::Abs($fire.x-$firePoint[0]) -gt 0.02 -or [Math]::Abs($fire.y-$firePoint[1]) -gt 0.02){throw 'Fire contact moved in receiver'}
        if($ids -contains 1){
            $combined++
            if($ids[0] -ne 0 -or $ids[1] -ne 1){throw 'Native receiver changed held fire/joystick indices'}
        }
    }
}
if($combined -lt 4){throw 'No sufficient overlap of held fire and late joystick'}
$fireReleases=@($events|Where-Object { $_.actionMasked -in @(1,6) -and $_.contacts[$_.actionIndex].id -eq 0 })
$joystickStarts=@($events|Where-Object { $_.actionMasked -eq 5 -and $_.contacts[$_.actionIndex].id -eq 1 })
$joystickEnds=@($events|Where-Object { $_.actionMasked -eq 6 -and $_.contacts[$_.actionIndex].id -eq 1 })
if($fireReleases.Count -ne 1 -or $joystickStarts.Count -ne 1 -or $joystickEnds.Count -ne 1 -or $joystickEnds[0].sequence -ge $fireReleases[0].sequence){throw 'Receiver did not observe ordered joystick end and physical-trigger-equivalent release before final cancel'}
Save ([ordered]@{synthetic=$true;physicalKeyboardMouseTest=$false;sameObserver=$after.observationId;eventDelta=$after.totalTouchEvents-$before.totalTouchEvents;combinedFrames=$combined;joystickStart=$joystickStarts[0].sequence;joystickEnd=$joystickEnds[0].sequence;fireRelease=$fireReleases[0].sequence;sequenceErrorDelta=$after.sequenceErrors-$before.sequenceErrors;pending=@($after.pendingContactIds);note='Saved bindings mapped to InputLab window area; not proprietary game events.'}) 'result.json'
Write-Host "PASS $Case lateJoystickWithFire=true events=$($after.totalTouchEvents-$before.totalTouchEvents) combined=$combined errors=0 pending=0"
