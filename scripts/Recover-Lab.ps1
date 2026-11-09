param([Parameter(Mandatory=$true)][string]$Serial)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
function Observe {
    return ((& $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-live.json)-join"`n")|ConvertFrom-Json
}
$before=Observe
if(!$before.activityResumed -or !$before.windowFocused){throw 'Keep the original InputLab window visible; this script does not restart it'}
# This manual activation is only for loss of ALL authorized processes, not ordinary worker death.
& "$PSScriptRoot\Activate.ps1" -Serial $targetSerial
for($attempt=0;$attempt -lt 15;$attempt++){
    Start-Sleep -Milliseconds 300
    $after=Observe
    if($after.observationId -ne $before.observationId -or $after.activityId -ne $before.activityId -or $after.pid -ne $before.pid){throw 'Observer changed; no recovery claim'}
    $wire=(& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join''
    if($wire -match '^Result: Bundle\[\{json=(.*)\}\]$'){
        $status=$Matches[1]|ConvertFrom-Json
        if(!$status.releaseUnverified -and $status.contacts -eq 0 -and @($after.pendingContactIds).Count -eq 0){
            Write-Host 'Recovered with the same receiver; session remains stopped. No Activity/process reset and no new tap.'
            return
        }
    }
}
throw 'Recovery remains uncertain. Observer was preserved; inspect evidence rather than clearing counters.'
