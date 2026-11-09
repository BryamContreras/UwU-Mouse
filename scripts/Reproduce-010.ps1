param([Parameter(Mandatory=$true)][string]$Serial)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$projectRoot=Split-Path -Parent $PSScriptRoot
function Read-State {
    $wire=(& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join''
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'Native diagnostics unavailable'}
    return $Matches[1]|ConvertFrom-Json
}
$state=Read-State
if($state.active -or $state.contacts -ne 0 -or $state.releaseUnverified){throw 'Stop and recover the existing session before this test'}
if(!$state.backend){throw 'Activate the backend first'}
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','force-stop','dev.uwumouse.inputlab')
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','start','-W','-f','0x10008000','-n','dev.uwumouse.app/.MainActivity')
Start-Sleep -Milliseconds 400
$selected=$null
for($attempt=0;$attempt -lt 4;$attempt++){
    $foreground=(& $adbPath -s $targetSerial shell dumpsys activity activities|Select-String 'topResumedActivity')-join''
    if($foreground -notmatch 'dev\.uwumouse\.app/\.MainActivity'){throw 'Resolve the visible authorization before continuing'}
    & $adbPath -s $targetSerial shell uiautomator dump /data/local/tmp/uwumouse-fault-ui.xml|Out-Null
    [xml]$tree=((& $adbPath -s $targetSerial shell cat /data/local/tmp/uwumouse-fault-ui.xml)-join"`n")
    $selected=$tree.SelectSingleNode('//node[@clickable="true" and starts-with(@content-desc,"Probar ")]')
    if($selected){break}
    & $adbPath -s $targetSerial shell input swipe 550 1900 550 900 300
}
if(!$selected){throw 'Probe button not visible'}
$position=[regex]::Matches($selected.bounds,'\d+')|ForEach-Object{[int]$_.Value}
$backendProcess=((& $adbPath -s $targetSerial shell pidof uwumouse_backend)-join'').Trim()
if($backendProcess -notmatch '^\d+$'){throw 'Backend process not found'}
Invoke-ProjectAdb $adbPath $targetSerial @('push',"$PSScriptRoot\backend-loss-timer.sh",'/data/local/tmp/uwumouse-fault-timer.sh')
Invoke-ProjectAdb $adbPath $targetSerial @('shell','sh','/data/local/tmp/uwumouse-fault-timer.sh',$backendProcess,([int](($position[0]+$position[2])/2)).ToString(),([int](($position[1]+$position[3])/2)).ToString())
Start-Sleep -Milliseconds 300
$state=Read-State
$state|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $projectRoot 'evidence\v011\baseline-backend-loss-mapper.json') -Encoding UTF8
$receiverProcess=((& $adbPath -s $targetSerial shell pidof dev.uwumouse.inputlab)-join'').Trim()
$mapperProcess=((& $adbPath -s $targetSerial shell pidof dev.uwumouse.app)-join'').Trim()
& $adbPath -s $targetSerial logcat -d "--pid=$receiverProcess" -v brief -s UwuInputLab:I '*:S'|Set-Content -LiteralPath (Join-Path $projectRoot 'evidence\v011\baseline-backend-loss-receiver.log') -Encoding UTF8
& $adbPath -s $targetSerial logcat -d "--pid=$mapperProcess" -v brief -s UwUMouse:I '*:S'|Set-Content -LiteralPath (Join-Path $projectRoot 'evidence\v011\baseline-backend-loss-mapper.log') -Encoding UTF8
if($state.active -or $state.backend -or !$state.releaseUnverified -or $state.contacts -lt 1){throw 'Unexpected fault handling; inspect evidence'}
Write-Host 'PASS: backend death detected, session paused, releases explicitly UNVERIFIED. This is not a successful release test.'
Write-Host 'Use the documented laboratory recovery procedure before starting another session.'
