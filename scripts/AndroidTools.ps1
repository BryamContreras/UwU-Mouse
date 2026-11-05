Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ProjectAdb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @("$env:LOCALAPPDATA\Android\sdk\platform-tools\adb.exe")
    if ($env:ANDROID_HOME) { $candidates += "$env:ANDROID_HOME\platform-tools\adb.exe" }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    throw 'No se encontró ADB. Instala Android SDK Platform Tools o configura ANDROID_HOME.'
}

function Get-ProjectDevice([string]$Adb, [string]$Serial) {
    $lines = & $Adb devices -l
    Write-Host ($lines -join "`n")
    $devices = @($lines | ForEach-Object { if ($_ -match '^(.*?)\s+device(?:\s|$)') { $Matches[1].Trim() } })
    if ($Serial) {
        if ($Serial -notin $devices) { throw 'El dispositivo seleccionado no está autorizado y conectado.' }
        return $Serial
    }
    if ($devices.Count -eq 1) { return $devices[0] }
    if (($lines -join ' ') -match 'unauthorized') { throw 'Acepta la clave de depuración en el teléfono y ejecuta otra vez.' }
    if ($devices.Count -eq 0) { throw 'No hay dispositivo autorizado. Revisa cable, depuración USB o conexión inalámbrica.' }
    throw 'Hay varios dispositivos: indica -Serial explícitamente.'
}

function Invoke-ProjectAdb([string]$Adb, [string]$Serial, [string[]]$Arguments) {
    & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB falló: $($Arguments[0])" }
}

# A persisted JSON file can survive its process and a reboot; flags alone are insufficient.
function Assert-ProjectLiveReceiver([string]$Adb,[string]$Serial,$Snapshot,$ControllerUptime) {
    if($null -eq $Snapshot -or $null -eq $Snapshot.PSObject.Properties['pid'] -or
        $null -eq $Snapshot.PSObject.Properties['capturedUptimeMs']){throw 'Receiver snapshot lacks live identity or capture uptime'}
    $snapshotPid=0L; $captured=0L; $controller=0L
    if(![long]::TryParse([string]$Snapshot.pid,[ref]$snapshotPid) -or $snapshotPid -le 0 -or
        ![long]::TryParse([string]$Snapshot.capturedUptimeMs,[ref]$captured) -or $captured -lt 0 -or
        ![long]::TryParse([string]$ControllerUptime,[ref]$controller) -or $controller -lt 0){throw 'Invalid receiver/controller identity or uptime'}
    $age=$controller-$captured
    # Both values use uptimeMillis; tolerate a capture up to 250 ms after controller status.
    if($age -lt -250 -or $age -gt 2000){throw "Receiver snapshot is stale or from a different boot (ageMs=$age)"}
    $livePid=((& $Adb -s $Serial shell pidof dev.mobimapper.inputlab)-join' ').Trim()
    if($LASTEXITCODE -ne 0 -or $livePid -notmatch '^\d+$' -or [long]$livePid -ne $snapshotPid){
        throw 'Receiver snapshot does not match exactly one currently running InputLab process'
    }
}
