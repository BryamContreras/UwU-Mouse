param([Parameter(Mandatory=$true)][string]$Serial)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
# Development recovery is restricted to the two project packages. No app data is cleared.
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','force-stop','dev.uwumouse.inputlab')
$remainingTarget=((& $adbPath -s $targetSerial shell pidof dev.uwumouse.inputlab)-join'').Trim()
if($remainingTarget){throw 'The laboratory process is still running; aborting recovery'}
$backendProcess=((& $adbPath -s $targetSerial shell pidof uwumouse_backend)-join'').Trim()
if($backendProcess){
    if($backendProcess -notmatch '^\d+$'){throw 'Unexpected backend PID'}
    $identity=(& $adbPath -s $targetSerial shell ps -p $backendProcess -o UID,NAME)-join' '
    if($identity -notmatch '\b2000\s+uwumouse_backend\b'){throw 'Unexpected process identity; refusing termination'}
    Invoke-ProjectAdb $adbPath $targetSerial @('shell','kill',$backendProcess)
}
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','force-stop','dev.uwumouse.app')
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','start','-W','-n','dev.uwumouse.app/.MainActivity')
& "$PSScriptRoot\Activate.ps1" -Serial $targetSerial
Write-Host 'The old laboratory window and mapper process were stopped. This resets the test; it does not prove delivery of the lost releases.'
Write-Host 'Reopen UwU Mouse to initialize its native host. Verify the backend and overlay permission before starting a new session.'
