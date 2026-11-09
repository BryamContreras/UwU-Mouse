param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../../scripts/AndroidTools.ps1')
$adb=Get-ProjectAdb
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
function Read-State {
  $wire=(& $adb -s $Serial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join''
  [regex]::Match($wire,'(?s)Result: Bundle\[\{json=(.*)\}\]\s*$').Groups[1].Value|ConvertFrom-Json
}
$state=Read-State
if($state.active -or $state.editorVisible -or $state.contacts-ne 0 -or $state.releaseUnverified -or $state.mouseCaptured){throw 'Idle measurement requires stopped input and closed editor'}
$focus=((& $adb -s $Serial shell dumpsys window)|Select-String 'mCurrentFocus=')-join''
if($focus -notmatch 'dev\.uwumouse\.app/(?:dev\.uwumouse\.app)?\.MainActivity'){throw 'Main library required'}
$appPid=[int]((& $adb -s $Serial shell pidof dev.uwumouse.app)-join'')
$processIds=@($appPid,[int]$state.supervisorPid,[int]$state.workerPid)
if(@($processIds|Where-Object{$_-le 0}).Count){throw 'Missing own process'}
$names=@('dev.uwumouse.app','uwumouse_backend','uwumouse_injector')
function Read-Processes {
  $paths=@($processIds|ForEach-Object{"/proc/$_/stat"})+@('/proc/uptime')
  $lines=@(& $adb -s $Serial shell cat @paths)
  if($LASTEXITCODE-ne 0 -or $lines.Count-ne 4){throw 'Cannot read own process counters'}
  $rows=@()
  for($i=0;$i-lt 3;$i++) {
    $line=$lines[$i];$fields=$line.Substring($line.LastIndexOf(')')+2).Split(' ')
    $rows+=[pscustomobject]@{name=$names[$i];processId=$processIds[$i];ticks=([long]$fields[11]+[long]$fields[12]);startTime=[long]$fields[19];residentPages=[long]$fields[21]}
  }
  [pscustomobject]@{uptime=[double]::Parse($lines[3].Split(' ')[0],[Globalization.CultureInfo]::InvariantCulture);processes=$rows}
}
$ticks=[int]((& $adb -s $Serial shell getconf CLK_TCK)-join'')
$before=Read-Processes
Start-Sleep -Seconds 10
$after=Read-Processes
$end=Read-State
$before|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 (Join-Path $Destination 'processes-before.json')
$after|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 (Join-Path $Destination 'processes-after.json')
$duration=$after.uptime-$before.uptime
$rows=@()
for($i=0;$i-lt 3;$i++) {
  if($before.processes[$i].startTime-ne$after.processes[$i].startTime){throw 'Process restarted'}
  $seconds=($after.processes[$i].ticks-$before.processes[$i].ticks)/$ticks
  $rows+=[pscustomobject]@{name=$names[$i];processId=$processIds[$i];cpuSeconds=$seconds;oneCorePercent=(100*$seconds/$duration)}
}
$valid=!$end.active -and !$end.editorVisible -and $end.contacts-eq 0 -and !$end.mouseCaptured -and $end.keys-eq$state.keys -and $end.relativeSamples-eq$state.relativeSamples -and $end.injected-eq$state.injected
if(!$valid){throw 'Not a stable idle interval'}
$state|ConvertTo-Json -Depth 15|Set-Content -Encoding UTF8 (Join-Path $Destination 'controller-before.json')
$end|ConvertTo-Json -Depth 15|Set-Content -Encoding UTF8 (Join-Path $Destination 'controller-after.json')
[pscustomobject]@{validStableIdle=$valid;durationSeconds=$duration;clockTicks=$ticks;screenRecording=$false;editorClosed=$true;foreground='UwUMouse Main library';cpu=$rows;inputDelta=0;injectionDelta=0;gameCpuExcluded=$true;scope='Idle own processes only; not game play throughput or end-to-end latency'}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 (Join-Path $Destination 'result.json')
for($i=0;$i-lt$names.Count;$i++){
  # app_process aliases are not package names; meminfo resolves these daemons by PID.
  & $adb -s $Serial shell dumpsys meminfo $processIds[$i]|Set-Content -Encoding UTF8 (Join-Path $Destination "$($names[$i])-memory.txt")
}
Get-Content -Raw -Encoding UTF8 (Join-Path $Destination 'result.json')
