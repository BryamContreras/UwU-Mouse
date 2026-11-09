param([string]$Serial, [switch]$CheckOnly)
. "$PSScriptRoot\AndroidTools.ps1"

$adbPath = Get-ProjectAdb

function Find-UwUPhone {
    if ($Serial) {
        $confirmed = Get-ProjectDevice $adbPath $Serial
        if ($confirmed -like 'emulator-*') { throw 'Selecciona el telefono fisico, no un emulador.' }
        return $confirmed
    }
    $lines = @(& $adbPath devices -l)
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo consultar ADB.' }
    $candidates = @($lines | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+device(?:\s|$)' -and $Matches[1] -notlike 'emulator-*') { $Matches[1] }
    } | Select-Object -Unique)
    if ($candidates.Count -eq 0) {
        if (($lines -join ' ') -match '\bunauthorized\b') {
            throw 'Desbloquea el telefono y acepta la autorizacion de depuracion de esta PC.'
        }
        throw 'No aparece un telefono autorizado. Activa Depuracion inalambrica y conecta telefono y PC a la misma red Wi-Fi. Si no reconecta, usa adb connect con su direccion IP y puerto.'
    }
    if ($candidates.Count -eq 1) { return $candidates[0] }
    # The same phone may advertise two mDNS transport names. Never deduplicate by model.
    $identities = @{}
    foreach ($candidate in $candidates) {
        $hardware = ((& $adbPath -s $candidate shell getprop ro.serialno) -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or !$hardware -or $hardware -eq 'unknown') { $hardware = "transport:$candidate" }
        if (!$identities.ContainsKey($hardware)) { $identities[$hardware] = $candidate }
    }
    if ($identities.Count -ne 1) {
        throw 'Hay varios telefonos conectados. No se modifico ninguno. Ejecuta scripts\Start-UwUMouse.ps1 -Serial <serial-del-telefono> para elegir uno.'
    }
    return @($identities.Values)[0]
}

function Read-UwUStatus {
    $wire = ((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics) -join "`n").Trim()
    if ($LASTEXITCODE -ne 0 -or $wire -notmatch '(?s)^Result: Bundle\[\{json=(.*)\}\]$') {
        throw 'No se pudo consultar UwU Mouse. Comprueba que la app esta instalada y abrela una vez.'
    }
    return $Matches[1] | ConvertFrom-Json
}

function Test-UwUReady($Status) {
    return $Status.backend -and $Status.backendUid -eq 2000 -and $Status.injectPermission
}
function Test-UwUCaptureReady($Status) {
    $property=$Status.PSObject.Properties['captureReady']
    return $null -ne $property -and [bool]$property.Value
}

function Test-UwUBusy($Status) {
    return $Status.active -or $Status.editorVisible -or $Status.releaseUnverified -or $Status.mouseCaptured -or
        $Status.contacts -gt 0 -or $Status.backendPossibleContacts -gt 0
}

function Assert-UwUIdleOrPreserved($Status) {
    if ($Status.releaseUnverified) {
        throw 'Hay una liberacion pendiente. Recupera la sesion desde UwU Mouse antes de volver a activar.'
    }
    if (!(Test-UwUBusy $Status)) { return $false }
    if (($Status.active -or $Status.editorVisible) -and (Test-UwUReady $Status) -and (Test-UwUCaptureReady $Status)) {
        Write-Host 'Servicio ya activo; sesion en curso conservada. No se abrio otra pantalla.'
        return $true
    }
    throw 'Hay una sesion, un editor o una liberacion pendiente. No se inicio ni reemplazo el servicio. Termina o recupera esa sesion desde UwU Mouse.'
}

$targetSerial = Find-UwUPhone
$status = Read-UwUStatus
if (Assert-UwUIdleOrPreserved $status) { return }

if (!(Test-UwUReady $status)) {
    if ($status.backend) { throw 'El motor responde, pero su autorizacion no es valida. No se reemplazo el proceso.' }
    # Give the app's asynchronous Binder reconnection time before attempting activation.
    for ($attempt = 0; $attempt -lt 6 -and !$status.backend; $attempt++) {
        Start-Sleep -Milliseconds 250
        $status = Read-UwUStatus
        if (Assert-UwUIdleOrPreserved $status) { return }
    }
    if (!(Test-UwUReady $status)) {
        if ($status.backend) { throw 'El motor responde, pero su autorizacion no es valida.' }
        if ($CheckOnly) { throw 'El servicio esta apagado. Ejecuta Activar-UwU-Mouse.cmd para activarlo desde esta PC.' }
        $existing = ((& $adbPath -s $targetSerial shell pidof uwumouse_backend) -join ' ').Trim()
        $probeExit = $LASTEXITCODE
        if ($probeExit -eq 0) {
            if ($existing -notmatch '^\d+(?:\s+\d+)*$') { throw 'No se pudo confirmar el proceso existente. No se inicio otra copia.' }
            throw 'El motor ya se esta ejecutando, pero la app no pudo reconectarlo. No se inicio una segunda copia. Abre UwU Mouse y revisa su estado.'
        }
        if ($probeExit -ne 1) { throw 'No se pudo comprobar si el motor sigue ejecutandose. Revisa la conexion ADB.' }
        $status = Read-UwUStatus
        if (Assert-UwUIdleOrPreserved $status) { return }
        if (!$status.backend) { & "$PSScriptRoot\Activate.ps1" -Serial $targetSerial }
    }
}

$status = Read-UwUStatus
if (!(Test-UwUReady $status)) { throw 'No se pudo verificar el servicio con UID 2000 y permiso de inyeccion.' }
if (Assert-UwUIdleOrPreserved $status) { return }
Write-Host 'Motor de toques activo y verificado.'
if (!$CheckOnly) {
    Invoke-ProjectAdb $adbPath $targetSerial @('shell', 'am', 'start', '-n', 'dev.uwumouse.app/.MainActivity')
}
if (!(Test-UwUCaptureReady $status)) {
    throw 'La captura nativa no esta preparada. Instala el APK actualizado y reinicia el motor desde UwU Mouse; revisa su mensaje de captura.'
}
if (!$status.overlayPermission) {
    throw 'En Ajustes de conexion permite Mostrar sobre otras apps, tambien para usar el telefono sin DeX.'
}
Write-Host 'Captura nativa preparada. En UwU Mouse toca el circulo de tu juego para empezar.'
Write-Host 'Este activador no inicia el juego ni una sesion de mapeo y no cambia tus perfiles.'
