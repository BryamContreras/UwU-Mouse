param([string]$Serial, [string]$Label = 'snapshot')
. "$PSScriptRoot\AndroidTools.ps1"
if ($Label -notmatch '^[A-Za-z0-9_-]{1,48}$') { throw 'Label debe contener solo letras, números, guion o subrayado.' }
$adbPath = Get-ProjectAdb
$targetSerial = Get-ProjectDevice $adbPath $Serial
$destination = Join-Path (Split-Path -Parent $PSScriptRoot) "evidence\$Label"
New-Item -ItemType Directory -Force -Path $destination | Out-Null
& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics |
    Set-Content -LiteralPath (Join-Path $destination 'mapper-status.txt') -Encoding UTF8
& $adbPath -s $targetSerial shell run-as dev.uwumouse.inputlab cat files/input-lab-live.json |
    Set-Content -LiteralPath (Join-Path $destination 'input-lab-live.json') -Encoding UTF8
$processes = @('dev.uwumouse.app', 'dev.uwumouse.inputlab', 'uwumouse_backend', 'uwumouse_injector')
foreach ($processName in $processes) {
    $processIds = @((& $adbPath -s $targetSerial shell pidof $processName) -split '\s+' | Where-Object { $_ -match '^\d+$' })
    foreach ($processId in $processIds) {
        & $adbPath -s $targetSerial shell dumpsys meminfo $processId |
            Set-Content -LiteralPath (Join-Path $destination "$processName-$processId-memory.txt") -Encoding UTF8
        & $adbPath -s $targetSerial shell cat "/proc/$processId/stat" |
            Set-Content -LiteralPath (Join-Path $destination "$processName-$processId-stat.txt") -Encoding UTF8
        & $adbPath -s $targetSerial logcat -d "--pid=$processId" -v brief -s UwUMouse:I UwUMouseShell:I UwuSupervisor:I UwuInjectWorker:I UwuBackend:I UwuActivity:I UwuInputLab:I AndroidRuntime:E '*:S' |
            Set-Content -LiteralPath (Join-Path $destination "$processName-$processId.log") -Encoding UTF8
    }
}
& $adbPath -s $targetSerial shell dumpsys battery | Select-String 'AC powered:|USB powered:|Wireless powered:|level:|temperature:' |
    Set-Content -LiteralPath (Join-Path $destination 'battery.txt') -Encoding UTF8
Write-Host "Diagnóstico guardado en $destination. PSS pertenece a cada proceso; no equivale a RAM física exclusiva. No mide latencia visual."
