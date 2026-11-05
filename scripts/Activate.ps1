param([string]$Serial)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath = Get-ProjectAdb
$targetSerial = Get-ProjectDevice $adbPath $Serial
function Read-BackendStatus {
    $wire = (& $adbPath -s $targetSerial shell content call --uri content://dev.mobimapper.mobimapper.backend --method diagnostics 2>$null) -join ''
    if ($wire -match '^Result: Bundle\[\{json=(.*)\}\]$') { return $Matches[1] | ConvertFrom-Json }
    return $null
}
$status = Read-BackendStatus
if ($status -and $status.backend) { Write-Host "Backend ya activo, UID $($status.backendUid), permiso efectivo $($status.injectPermission)."; return }
Invoke-ProjectAdb $adbPath $targetSerial @('push', "$PSScriptRoot\activate-backend.sh", '/data/local/tmp/mobimapper-activate.sh')
Invoke-ProjectAdb $adbPath $targetSerial @('shell', 'sh', '/data/local/tmp/mobimapper-activate.sh')
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    Start-Sleep -Milliseconds 250
    $status = Read-BackendStatus
    if ($status -and $status.backend -and $status.injectPermission) {
        Write-Host "Backend verificado: UID $($status.backendUid), receptor UID $($status.targetUid), INJECT_EVENTS efectivo."
        Write-Host 'El motor queda local en el teléfono. Esta activación no controla el móvil desde la PC.'
        return
    }
}
$backendProcess = ((& $adbPath -s $targetSerial shell cat /data/local/tmp/mobimapper-backend.pid) -join '').Trim()
if ($backendProcess -match '^\d+$') { & $adbPath -s $targetSerial logcat -d "--pid=$backendProcess" -v brief -s MobiMapperShell:I '*:S' }
throw 'La activación no pudo verificarse. Revisa el error del backend; no continúes como si estuviese activo.'
