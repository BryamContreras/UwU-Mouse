param(
    [Parameter(Mandatory=$true)][string]$CaptureDirectory,
    [int]$PointerId=0,
    [ValidatePattern('^BTN_[A-Z_]+$')][string]$Button='BTN_MOUSE'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$before=Get-Content -LiteralPath (Join-Path $CaptureDirectory 'controller-before.json') -Raw | ConvertFrom-Json
$after=Get-Content -LiteralPath (Join-Path $CaptureDirectory 'controller-after.json') -Raw | ConvertFrom-Json
$receiverBefore=Get-Content -LiteralPath (Join-Path $CaptureDirectory 'receiver-before.json') -Raw | ConvertFrom-Json
$receiverAfter=Get-Content -LiteralPath (Join-Path $CaptureDirectory 'receiver-after.json') -Raw | ConvertFrom-Json
if(!$before.active -or !$after.active -or $before.metricsSessionId -ne $after.metricsSessionId -or
    $before.backendSessionId -ne $after.backendSessionId -or $before.activePackage -ne 'dev.uwumouse.inputlab' -or
    $after.activePackage -ne 'dev.uwumouse.inputlab' -or $before.targetDisplayId -ne $after.targetDisplayId -or
    $receiverBefore.observationId -ne $receiverAfter.observationId -or $receiverBefore.pid -ne $receiverAfter.pid -or
    $receiverBefore.sequenceErrors -ne $receiverAfter.sequenceErrors) { throw 'Unstable session or independent receiver' }
$physical=@(Get-Content -LiteralPath (Join-Path $CaptureDirectory 'mouse-kernel-events.txt') | ForEach-Object {
    if($_ -match ('^\[\s*([0-9]+\.[0-9]+)\]\s+EV_KEY\s+'+[regex]::Escape($Button)+'\s+(DOWN|UP)\s*$')) {
        $time=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)*1000
        if($time -ge $before.uptimeMs -and $time -le $after.uptimeMs) {
            [pscustomobject]@{kernelUptimeMs=$time;edge=$Matches[2]}
        }
    }
})
$mapped=@(@($receiverAfter.samples)+@($receiverAfter.transitions) | Where-Object {
    $_.sequence -gt $receiverBefore.totalTouchEvents -and $_.source -eq 4098 -and $_.deviceId -eq -1 -and
    $_.actionMasked -in @(0,1,5,6) -and $_.contacts[$_.actionIndex].id -eq $PointerId -and
    $_.receiptUptimeMs -ge $before.uptimeMs -and $_.receiptUptimeMs -le $after.uptimeMs
} | Sort-Object sequence -Unique)
if($physical.Count -lt 2 -or $physical.Count -ne $mapped.Count) {
    throw "Unpaired button edges: kernel=$($physical.Count), receiver=$($mapped.Count). No latency estimate produced."
}
$used=New-Object 'System.Collections.Generic.HashSet[long]'
$pairs=@(foreach($edge in $physical) {
    $candidates=@($mapped | Where-Object {
        $sameEdge=if($edge.edge -eq 'DOWN') {$_.actionMasked -in @(0,5)} else {$_.actionMasked -in @(1,6)}
        $delay=$_.receiptUptimeMs-$edge.kernelUptimeMs
        $sameEdge -and $delay -ge 0 -and $delay -le 250 -and !$used.Contains([long]$_.sequence)
    })
    if($candidates.Count -ne 1) { throw 'Ambiguous or missing correlation; use isolated clicks at least 500ms apart.' }
    $event=$candidates[0]
    [void]$used.Add([long]$event.sequence)
    [pscustomobject][ordered]@{
        edge=$edge.edge; kernelUptimeMs=$edge.kernelUptimeMs; receiverSequence=$event.sequence
        compositionUptimeMs=$event.eventTimeUptimeMs; receiverUptimeMs=$event.receiptUptimeMs
        kernelToReceiverMs=$event.receiptUptimeMs-$edge.kernelUptimeMs
        kernelToCompositionMs=$event.eventTimeUptimeMs-$edge.kernelUptimeMs
        compositionToReceiverMs=$event.receiptUptimeMs-$event.eventTimeUptimeMs
    }
})
$pairs | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $CaptureDirectory 'correlated-button-events.json') -Encoding UTF8
function Summary($Rows,[string]$Field) {
    $sorted=@($Rows | ForEach-Object { [double]$_.$Field } | Sort-Object)
    [ordered]@{
        count=$sorted.Count; minMs=$sorted[0]; meanMs=($sorted|Measure-Object -Average).Average
        p50Ms=$sorted[[int][Math]::Floor(($sorted.Count-1)*.50)]
        p95Ms=$sorted[[int][Math]::Floor(($sorted.Count-1)*.95)]
        p99Ms=$sorted[[int][Math]::Floor(($sorted.Count-1)*.99)]; maxMs=$sorted[-1]
    }
}
$result=[ordered]@{
    validCorrelation=$true; physicalButton=$Button; mappedPointerId=$PointerId; pairedEdges=$pairs.Count
    completeClicks=@($pairs|Where-Object{$_.edge -eq 'DOWN'}).Count
    kernelToMappedReceiver=Summary $pairs 'kernelToReceiverMs'
    kernelToSyntheticComposition=Summary $pairs 'kernelToCompositionMs'
    syntheticCompositionToReceiver=Summary $pairs 'compositionToReceiverMs'
    scope='Physical kernel button report to mapped touch callback in independent InputLab; isolated button edges only, not continuous camera movement'
    correlation='One unique matching edge and pointer ID within 250ms, equal counts, same live observer/session; fail on missing or ambiguous pairs'
    limitations='Kernel timestamp excludes radio/USB/peripheral time before kernel. Receiver uses integer uptime milliseconds. No callback-entry timestamp in installed mapper, no drawing/presentation/photon timestamp. Does not isolate accessibility cost.'
}
$result | ConvertTo-Json -Depth 8 | Tee-Object -FilePath (Join-Path $CaptureDirectory 'correlated-button-summary.json')
