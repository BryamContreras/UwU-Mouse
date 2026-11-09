param([Parameter(Mandatory=$true)][string]$Serial,[string]$Case='game-idle',[ValidateRange(5,60)][int]$Seconds=15)
. "$PSScriptRoot\AndroidTools.ps1"
if($Case -notmatch '^[a-zA-Z0-9_-]{1,64}$'){throw 'Invalid case label'}
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$destination=Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\v020\performance-$Case"
New-Item -ItemType Directory -Force $destination|Out-Null
function Read-State {
    $wire=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join'')
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'No mapper diagnostic available'}
    $Matches[1]|ConvertFrom-Json
}
function Save-Json($Value,[string]$Name){$Value|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $destination $Name) -Encoding UTF8}
function Read-Process([string]$ProcessName){
    $processText=((& $adbPath -s $targetSerial shell pidof $ProcessName)-join'').Trim()
    if($processText -notmatch '^\d+$'){throw "Expected one live own process: $ProcessName"}
    $stat=((& $adbPath -s $targetSerial shell cat "/proc/$processText/stat")-join'').Trim()
    # comm is parenthesized and may contain spaces. Fields after ')' start with field 3 (state).
    $close=$stat.LastIndexOf(')')
    if($close -lt 0){throw 'Invalid process stat'}
    $fields=$stat.Substring($close+1).Trim() -split '\s+'
    [ordered]@{name=$ProcessName;processId=[int]$processText;userTicks=[long]$fields[11];systemTicks=[long]$fields[12];startTicks=[long]$fields[19];raw=$stat}
}
if(((& $adbPath -s $targetSerial shell pidof screenrecord)-join'').Trim()){throw 'Stop screen recording before measuring performance'}
$before=Read-State
if($before.editorVisible){throw 'Close the editor before measuring performance'}
$names=@('dev.uwumouse.app','uwumouse_backend','uwumouse_injector')
$processBefore=@($names|ForEach-Object{Read-Process $_})
Save-Json $before 'controller-before.json'
Save-Json $processBefore 'processes-before.json'
$timer=[Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds $Seconds
$processAfter=@($names|ForEach-Object{Read-Process $_})
$elapsed=$timer.Elapsed.TotalSeconds
$after=Read-State
Save-Json $after 'controller-after.json'
Save-Json $processAfter 'processes-after.json'
$clockTicks=((& $adbPath -s $targetSerial shell getconf CLK_TCK)-join'').Trim()
if($clockTicks -notmatch '^\d+$'){throw 'Cannot establish kernel clock ticks'}
$cpu=@()
for($i=0;$i -lt $names.Count;$i++){
    $first=$processBefore[$i];$last=$processAfter[$i]
    if($first.processId -ne $last.processId -or $first.startTicks -ne $last.startTicks){throw 'Own process changed during sample; do not compare deltas'}
    $ticks=($last.userTicks+$last.systemTicks)-($first.userTicks+$first.systemTicks)
    $cpu+=[ordered]@{name=$last.name;processId=$last.processId;cpuSeconds=$ticks/[double]$clockTicks;oneCorePercent=100*$ticks/([double]$clockTicks*$elapsed)}
    & $adbPath -s $targetSerial shell dumpsys meminfo $last.processId|Set-Content -LiteralPath (Join-Path $destination "$($last.name)-memory.txt") -Encoding UTF8
}
$devicesBefore=(@($before.devices|ForEach-Object{$_.descriptor}|Sort-Object)-join'|')
$devicesAfter=(@($after.devices|ForEach-Object{$_.descriptor}|Sort-Object)-join'|')
$stableDevices=$devicesBefore-eq$devicesAfter
$valid=($stableDevices -and !$after.editorVisible -and !$after.releaseUnverified -and $before.active -eq $after.active -and $before.state -eq $after.state -and $before.recoveryState -eq $after.recoveryState -and $before.backendSessionId -eq $after.backendSessionId -and $before.activePackage -eq $after.activePackage)
Save-Json ([ordered]@{validStableSample=$valid;deviceInventoryStable=$stableDevices;durationSeconds=$elapsed;screenRecording=$false;clockTicks=[int]$clockTicks;cpu=$cpu;inputKeyDelta=$after.keys-$before.keys;relativeSampleDelta=$after.relativeSamples-$before.relativeSamples;injectionDelta=$after.injected-$before.injected;maxQueueDepth=$after.maxQueueDepth;queueMs=$after.queueMs;ipcMs=$after.ipcMs;metricsSessionId=$after.metricsSessionId;queueDiagnostics=$after.queueDiagnostics;backendTimingSessionId=$after.backendTimingSessionId;backendFocusMs=$after.backendFocusMs;backendPipeMs=$after.backendPipeMs;backendFocusCount=$after.backendFocusCount;backendPipeCount=$after.backendPipeCount;cameraRequestedRateHz=$after.cameraRequestedRateHz;cameraRate=$after.cameraRate;cameraDeliveryWaitMs=$after.cameraDeliveryWaitMs;note='Internal rolling percentiles for the reported metrics session, not the CPU sample interval or display latency. ADB sampling adds diagnostic overhead. Game processes excluded.'}) 'result.json'
if(!$valid){throw 'Session/editor/release changed during sample; retained as invalid evidence'}
Write-Host "Own-process sample saved: $destination"
