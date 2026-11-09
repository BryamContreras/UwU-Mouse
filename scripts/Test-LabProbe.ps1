param([string]$Serial)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath = Get-ProjectAdb
$targetSerial = Get-ProjectDevice $adbPath $Serial
$projectRoot = Split-Path -Parent $PSScriptRoot
function Read-State {
    $wire = (& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics) -join ''
    if ($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') { throw 'No se pudo leer el diagnóstico nativo' }
    return $Matches[1] | ConvertFrom-Json
}
$state = Read-State
if ($state.active) { throw 'Detén primero la sesión física.' }
if (!$state.backend) { throw 'Activa primero el backend con Activate.ps1.' }
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','force-stop','dev.uwumouse.inputlab')
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','start','-W','-f','0x10008000','-n','dev.uwumouse.app/.MainActivity')
Start-Sleep -Milliseconds 500
$selected = $null
for ($attempt=0; $attempt -lt 4; $attempt++) {
    $foreground = (& $adbPath -s $targetSerial shell dumpsys activity activities | Select-String 'topResumedActivity') -join ''
    if ($foreground -notmatch 'dev\.uwumouse\.app/\.MainActivity') { throw 'UwUMouse no tiene foco; resuelve cualquier autorización visible y repite.' }
    & $adbPath -s $targetSerial shell uiautomator dump /data/local/tmp/uwumouse-test-ui.xml | Out-Null
    [xml]$tree = ((& $adbPath -s $targetSerial shell cat /data/local/tmp/uwumouse-test-ui.xml) -join "`n")
    $selected = $tree.SelectSingleNode('//node[@content-desc="Probar inyección sintética"]')
    if ($selected) { break }
    $bounds = [regex]::Matches($tree.hierarchy.node.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    if ($bounds.Count -ne 4) { throw 'No se obtuvo geometría de UI' }
    $screenWidth=$bounds[2]; $screenHeight=$bounds[3]
    & $adbPath -s $targetSerial shell input swipe ([int]($screenWidth*.5)) ([int]($screenHeight*.82)) ([int]($screenWidth*.5)) ([int]($screenHeight*.4)) 300
}
if (!$selected) { throw 'No se encontró el botón de diagnóstico' }
$position = [regex]::Matches($selected.bounds,'\d+') | ForEach-Object { [int]$_.Value }
& $adbPath -s $targetSerial shell input tap ([int](($position[0]+$position[2])/2)) ([int](($position[1]+$position[3])/2))
for ($attempt=0; $attempt -lt 18; $attempt++) {
    Start-Sleep -Milliseconds 500
    $state=Read-State
    if ($state.state -eq 'Diagnóstico terminado') { break }
    if ($state.state -eq 'Pausada') { throw "Diagnóstico interrumpido: $($state.error)" }
}
if ($state.state -ne 'Diagnóstico terminado') { throw "No terminó el diagnóstico: $($state.state)" }
Invoke-ProjectAdb $adbPath $targetSerial @('shell','am','start','-W','-f','0x14000000','-n','dev.uwumouse.app/.MainActivity')
$wire = & $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-summary.json
$summary = ($wire -join "`n") | ConvertFrom-Json
$wire | Set-Content -LiteralPath (Join-Path $projectRoot 'evidence\release-probe-receiver.json') -Encoding UTF8
$state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $projectRoot 'evidence\release-probe-mapper.json') -Encoding UTF8
if ($summary.maxContacts -lt 3 -or $summary.partialReleases -lt 2 -or $summary.sequenceErrors -ne 0 -or @($summary.pendingContactIds).Count -ne 0) { throw 'El receptor independiente rechazó los criterios multitáctiles; revisa evidencia.' }
Write-Host "PASS: receptor UID $($summary.uid), máximo $($summary.maxContacts) contactos, $($summary.partialReleases) liberaciones parciales, cero errores y cero pendientes. Entrada sintética; no acredita periféricos físicos."
