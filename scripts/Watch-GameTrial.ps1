param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='training-first',[ValidateRange(30,900)][int]$TimeoutSeconds=600,[switch]$WaitForSession,[switch]$NoVideo,[ValidateRange(5,30)][int]$SampleSeconds=10)
. "$PSScriptRoot\AndroidTools.ps1"
if($Case -notmatch '^[a-zA-Z0-9_-]{1,64}$'){throw 'Invalid case label'}
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$destination=Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\v020\$Case"
New-Item -ItemType Directory -Force $destination|Out-Null
function Read-State {
    $wire=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join'')
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'No mapper diagnostic available'}
    $Matches[1]|ConvertFrom-Json
}
function Save-Json($Value,[string]$Name){$Value|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $destination $Name) -Encoding UTF8}
$before=Read-State
if($WaitForSession){
    $readyTimer=[Diagnostics.Stopwatch]::StartNew()
    while(!$before.active -and $readyTimer.Elapsed.TotalSeconds -lt $TimeoutSeconds){
        if($before.releaseUnverified){throw 'Unverified release while waiting for a session'}
        Start-Sleep -Milliseconds 1000
        $before=Read-State
    }
}
if(!$before.active -or !$before.activeProfileId -or $before.editorVisible -or $before.releaseUnverified){throw 'Prepare an ordinary game session first'}
Save-Json $before 'before.json'
$timer=[Diagnostics.Stopwatch]::StartNew()
do {
    $current=Read-State
    if(!$current.active -or $current.backendSessionId -ne $before.backendSessionId){throw 'Session ended before physical input; no recording started'}
    if($current.keys -gt $before.keys -or $current.relativeSamples -gt $before.relativeSamples){break}
    Start-Sleep -Milliseconds 1000
} while($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
if($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds){throw 'No physical input observed; no recording started'}
Save-Json $current 'recording-trigger.json'
$timeline=New-Object Collections.Generic.List[object]
$recordTimer=[Diagnostics.Stopwatch]::StartNew()
if($NoVideo){
    # Two diagnostic snapshots plus /proc deltas; do not record or continuously poll during CPU sampling.
    try { & "$PSScriptRoot\Measure-OwnProcesses.ps1" -Serial $targetSerial -Case "game-$Case" -Seconds $SampleSeconds }
    catch { Save-Json ([ordered]@{valid=$false;reason=$_.Exception.Message}) 'performance-sample-incomplete.json' }
    do {
        $sample=Read-State
        $timeline.Add([ordered]@{uptimeMs=$sample.uptimeMs;active=$sample.active;mode=$sample.mode;contacts=$sample.contacts;keys=$sample.keys;mouse=$sample.mouse;relativeSamples=$sample.relativeSamples;injected=$sample.injected;releaseUnverified=$sample.releaseUnverified;recoveryState=$sample.recoveryState;error=$sample.error;session=$sample.backendSessionId})
        if(!$sample.active){break}
        Start-Sleep -Milliseconds 1000
    } while($recordTimer.Elapsed.TotalSeconds -lt 60)
} else {
    $remote="/data/local/tmp/uwumouse-$Case.mp4"
    # Recording is evidence only, excluded from performance samples.
    $recorder=Start-Process -FilePath $adbPath -ArgumentList @('-s',$targetSerial,'shell','screenrecord','--size','1158x540','--bit-rate','4000000','--time-limit','45',$remote) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $destination 'screenrecord-stdout.txt') -RedirectStandardError (Join-Path $destination 'screenrecord-stderr.txt')
    while(!$recorder.HasExited -and $recordTimer.Elapsed.TotalSeconds -lt 60){
        $sample=Read-State
        $timeline.Add([ordered]@{uptimeMs=$sample.uptimeMs;active=$sample.active;mode=$sample.mode;contacts=$sample.contacts;keys=$sample.keys;mouse=$sample.mouse;relativeSamples=$sample.relativeSamples;injected=$sample.injected;releaseUnverified=$sample.releaseUnverified;recoveryState=$sample.recoveryState;error=$sample.error;session=$sample.backendSessionId})
        Start-Sleep -Milliseconds 1000
    }
    if(!$recorder.HasExited){throw 'Recorder exceeded its fixed limit; inspect its own process before proceeding'}
    & $adbPath -s $targetSerial pull $remote (Join-Path $destination 'visible-game.mp4')
    if($LASTEXITCODE -ne 0){throw 'Could not pull game evidence recording'}
}
Save-Json @($timeline.ToArray()) 'timeline.json'
$after=Read-State
Save-Json $after 'after.json'
foreach($name in @('dev.uwumouse.app','uwumouse_backend','uwumouse_injector')){
    $processText=((& $adbPath -s $targetSerial shell pidof $name)-join'').Trim()
    if($processText -match '^\d+$'){
        & $adbPath -s $targetSerial logcat -d "--pid=$processText" -v threadtime -s UwUMouse:I UwUMouseKeys:I UwuSupervisor:I UwuInjectWorker:I '*:S'|Set-Content -LiteralPath (Join-Path $destination "$name.log") -Encoding UTF8
    }
}
Save-Json ([ordered]@{screenRecording=(!$NoVideo);performanceSample=[bool]$NoVideo;package=$before.activePackage;profileId=$before.activeProfileId;session=$before.backendSessionId;maxSampledMapperContacts=($timeline.ToArray()|ForEach-Object{$_.contacts}|Measure-Object -Maximum).Maximum;finalActive=$after.active;finalContacts=$after.contacts;releaseUnverified=$after.releaseUnverified;note='Mapper diagnostics only; visible behaviour requires user observation or optional video. No internal game events or receiver acknowledgement are read.'}) 'result.json'
Write-Host "Game evidence saved: $destination; review user observation before declaring behaviour."
