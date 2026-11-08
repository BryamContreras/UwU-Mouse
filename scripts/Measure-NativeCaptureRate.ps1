param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(2,15)][int]$SecondsPerRate=5,
    [int[]]$Rates=@(100,500,1000,0),
    [ValidatePattern('^dev\.mobimapper\.mobimapper(?:\.[a-z][a-z0-9_]*)?$')]
    [string]$ApplicationId='dev.mobimapper.mobimapper.nativepreview',
    [string]$Destination
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
if(!$Rates.Count -or @($Rates|Where-Object { $_ -lt 0 -or $_ -gt 1000 }).Count) { throw 'Rates must be between 0 and 1000 Hz' }
function Read-CaptureSnapshot {
    $wire=((& $adbPath -s $targetSerial shell content call --uri "content://$ApplicationId.backend" --method diagnostics)-join '')
    if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') { throw 'Capture diagnostics unavailable' }
    $Matches[1]|ConvertFrom-Json
}
function Set-NativeRate([int]$Rate) {
    $wire=((& $adbPath -s $targetSerial shell content call --uri "content://$ApplicationId.backend" --method nativeCaptureRate --arg $Rate)-join '')
    if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{' -or $wire -notmatch 'accepted=true') { throw "Native rate was rejected: $wire" }
}
$initial=Read-CaptureSnapshot
if(!$initial.nativeCapture.running -or !$initial.nativeCapture.captureMouse) { throw 'Start native mouse capture before measuring' }
if($initial.nativeCapture.PSObject.Properties.Name -notcontains 'mouseRateHz') { throw 'Install a backend with native rate support' }
$captureId=[long]$initial.nativeCapture.captureId
$originalRate=[int]$initial.nativeCapture.mouseRateHz
$rows=@()
$restored=$false
try {
    foreach($rate in $Rates) {
        Set-NativeRate $rate
        $before=Read-CaptureSnapshot
        if([long]$before.nativeCapture.captureId -ne $captureId -or [int]$before.nativeCapture.mouseRateHz -ne $rate) { throw 'Capture changed or native rate has not applied' }
        Start-Sleep -Seconds $SecondsPerRate
        $after=Read-CaptureSnapshot
        if(!$after.nativeCapture.running -or [long]$after.nativeCapture.captureId -ne $captureId -or [int]$after.nativeCapture.mouseRateHz -ne $rate) { throw 'Capture changed during measurement' }
        $seconds=([long]$after.uptimeMs-[long]$before.uptimeMs)/1000.0
        if($seconds -le 0) { throw 'Invalid measurement interval' }
        $raw=[long]$after.nativeCapture.rawMotionSamples-[long]$before.nativeCapture.rawMotionSamples
        $batches=[long]$after.nativeCapture.mouseBatches-[long]$before.nativeCapture.mouseBatches
        $delivered=[long]$after.nativeCapture.deliveredMotionSamples-[long]$before.nativeCapture.deliveredMotionSamples
        $barriers=[long]$after.nativeCapture.barrierFlushes-[long]$before.nativeCapture.barrierFlushes
        $rows+=[pscustomobject]@{
            rateHz=$rate;seconds=$seconds;rawSamples=$raw;deliveredSamples=$delivered;deliveries=$batches
            rawSamplesPerSecond=[Math]::Round($raw/$seconds,2)
            deliveriesPerSecond=[Math]::Round($batches/$seconds,2)
            barrierFlushes=$barriers
            captureId=$captureId;displayId=$after.targetDisplayId
            nativeReadCalls=[long]$after.nativeCapture.nativeReadCalls-[long]$before.nativeCapture.nativeReadCalls
        }
    }
} finally {
    $current=Read-CaptureSnapshot
    if($current.nativeCapture.running -and [long]$current.nativeCapture.captureId -eq $captureId) {
        Set-NativeRate $originalRate
        $verification=Read-CaptureSnapshot
        $restored=($verification.nativeCapture.running -and [long]$verification.nativeCapture.captureId -eq $captureId -and
            [int]$verification.nativeCapture.mouseRateHz -eq $originalRate)
    }
    $report=[ordered]@{
        scope='Physical movement delivery from native reader; edge flushes can exceed the movement cap. Not USB polling or image latency.'
        originalRateHz=$originalRate;originalRateRestored=$restored;profilesModified=$false;measurements=$rows
    }
    if($Destination) {
        $path=[IO.Path]::GetFullPath($Destination)
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force|Out-Null
        $report|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $path -Encoding UTF8
    }
}
$rows|Format-Table rateHz,rawSamplesPerSecond,deliveriesPerSecond,barrierFlushes,seconds -AutoSize
