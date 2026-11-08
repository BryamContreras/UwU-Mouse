param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(5,60)][int]$Seconds=30,
    [switch]$Instrumented,
    [string]$Destination,
    [string]$MouseEventPath,
    [ValidatePattern('^dev\.mobimapper\.mobimapper(?:\.[a-z][a-z0-9_]*)?$')]
    [string]$ApplicationId='dev.mobimapper.mobimapper'
)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
if(!$Destination) {
    $Destination=Join-Path (Split-Path -Parent $PSScriptRoot) ("evidence\latency-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
}
if($MouseEventPath -and $MouseEventPath -notmatch '^/dev/input/event\d+$') { throw 'Invalid mouse event path' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination=(Resolve-Path -LiteralPath $Destination).Path
function Save-Json($Value,[string]$Name) {
    $Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $Destination $Name) -Encoding UTF8
}
function Call-Mapper([string]$Method,[string[]]$Extra=@()) {
    $wire=((& $adbPath -s $targetSerial shell content call --uri "content://$ApplicationId.backend" --method $Method @Extra)-join '')
    if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') {
        throw "Mapper method unavailable: $Method. Instrumented mode requires an APK containing InputLatencyProbe."
    }
    $Matches[1] | ConvertFrom-Json
}
function Read-Receiver {
    # Optional, independent observer. It is never launched or installed by this script.
    $wire=((& $adbPath -s $targetSerial shell run-as dev.mobimapper.inputlab cat files/input-lab-live.json 2>$null)-join '')
    if($LASTEXITCODE -eq 0 -and $wire.StartsWith('{')) { return ($wire | ConvertFrom-Json) }
    return $null
}
$before=Call-Mapper 'diagnostics'
$captureAvailable=if($null -ne $before.PSObject.Properties['captureReady']) { $before.captureReady } else { $before.accessibility }
if(!$before.active -or !$captureAvailable -or !$before.backend -or $before.editorVisible -or $before.releaseUnverified) {
    throw 'Start a stable mapping session and close the editor before measuring.'
}
$recording=((& $adbPath -s $targetSerial shell pidof screenrecord)-join '').Trim()
if($recording) { throw 'Stop screen recording before measuring.' }
Save-Json $before 'controller-before.json'
$receiverBefore=Read-Receiver
if($null -ne $receiverBefore) { Save-Json $receiverBefore 'receiver-before.json' }
$probeStarted=$false
$probe=$null
$nativeCapture=$null
$nativeExitCode=$null
try {
    if($MouseEventPath) {
        $nativeCapture=Start-Process -FilePath $adbPath -ArgumentList @('-s',$targetSerial,'shell','timeout',($Seconds+3),'getevent','-lt',$MouseEventPath) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Destination 'mouse-kernel-events.txt') -RedirectStandardError (Join-Path $Destination 'mouse-kernel-errors.txt')
        $nativeHandle=$nativeCapture.Handle
    }
    if($Instrumented) {
        $probe=Call-Mapper 'latencyStart' @('--extra',("durationMs:i:"+($Seconds*1000)))
        $probeStarted=$true
        Save-Json $probe 'probe-start.json'
    }
    Write-Host "Capture started for $Seconds seconds. Move the physical mouse and perform isolated clicks."
    Start-Sleep -Seconds $Seconds
    $after=Call-Mapper 'diagnostics'
    Save-Json $after 'controller-after.json'
    $receiverAfter=Read-Receiver
    if($null -ne $receiverAfter) { Save-Json $receiverAfter 'receiver-after.json' }
    if($null -ne $nativeCapture) {
        if(!$nativeCapture.WaitForExit(10000)) { throw 'Kernel capture did not finish after its timeout' }
        $nativeCapture.Refresh()
        $nativeExitCode=$nativeCapture.ExitCode
        # Android timeout may report 124 for a normal timed recording.
        if($nativeExitCode -notin @(0,124)) { throw "Kernel capture failed (exit $nativeExitCode); inspect mouse-kernel-errors.txt" }
    }
} finally {
    if($probeStarted) {
        $stopped=Call-Mapper 'latencyStop'
        Save-Json $stopped 'probe-stop.json'
        $rows=New-Object System.Collections.Generic.List[object]
        $page=$stopped
        while($true) {
            if($page.runId -ne $probe.runId -or $page.pid -ne $probe.pid -or $page.enabled) {
                throw 'Probe identity changed or recording did not stop; offsets are not comparable.'
            }
            foreach($row in $page.records) { $rows.Add($row) }
            if($null -eq $page.nextRecordsOffset) { break }
            $offset=[int]$page.nextRecordsOffset
            $page=Call-Mapper 'latencySnapshot' @('--extra',"offset:i:$offset",'--extra','limit:i:512')
        }
        if($rows.Count -ne $stopped.storedCount) { throw 'Incomplete probe export' }
        Save-Json @($rows.ToArray()) 'probe-records.json'
    }
}
$stable=$before.active -and $after.active -and !$after.editorVisible -and !$after.releaseUnverified -and
    $before.metricsSessionId -eq $after.metricsSessionId -and $before.backendSessionId -eq $after.backendSessionId -and
    $before.activePackage -eq $after.activePackage -and $before.targetDisplayId -eq $after.targetDisplayId -and
    $before.state -eq $after.state -and $before.mouseCaptured -eq $after.mouseCaptured -and
    $before.recoveryState -eq $after.recoveryState -and
    $before.windowContext.deviceInventory.removedCount -eq $after.windowContext.deviceInventory.removedCount -and
    $before.windowContext.deviceInventory.materialCount -eq $after.windowContext.deviceInventory.materialCount
$fresh=$after.relativeSamples -gt $before.relativeSamples -and $after.injected -gt $before.injected
$result=[ordered]@{
    stableEndpoints=$stable; freshMappedWorkload=$fresh; intervalMs=$after.uptimeMs-$before.uptimeMs
    package=$after.activePackage; displayId=$after.targetDisplayId; metricsSessionId=$after.metricsSessionId
    relativeSampleDelta=$after.relativeSamples-$before.relativeSamples
    mouseInputDelta=$after.mouse-$before.mouse; keyDelta=$after.keys-$before.keys
    injectionDelta=$after.injected-$before.injected
    rollingQueueMs=$after.queueMs; rollingIpcMs=$after.ipcMs
    rollingCameraDeliveryWaitMs=$after.cameraDeliveryWaitMs
    rollingScope='p50,p95,p99 of the retained mapper session buffers (up to 2048), not necessarily the capture interval; stages overlap and percentiles must not be added'
    instrumented=[bool]$Instrumented
    kernelCaptureExitCode=$nativeExitCode
    workloadScope='Mapper counters include physical and synthetic sources. A physical trial also requires confirmed device/kernel input and no synthetic test running.'
    limitations='Endpoint checks cannot prove uninterrupted state between snapshots. Android event timestamps exclude peripheral pre-kernel latency. CPU draw and injection ACK do not prove display presentation. Wi-Fi arrival times are not latency measurements.'
}
if($null -ne $receiverBefore -and $null -ne $receiverAfter) {
    $sameObserver=$receiverBefore.observationId -eq $receiverAfter.observationId -and $receiverBefore.pid -eq $receiverAfter.pid
    $receiverFresh=$false
    $receiverLiveError=$null
    $applicable=$before.activePackage -eq 'dev.mobimapper.inputlab' -and $after.activePackage -eq 'dev.mobimapper.inputlab' -and
        $receiverBefore.activityResumed -and $receiverAfter.activityResumed -and
        $receiverBefore.windowFocused -and $receiverAfter.windowFocused -and
        $receiverBefore.displayId -eq $before.targetDisplayId -and $receiverAfter.displayId -eq $after.targetDisplayId
    if($applicable) {
        try {
            # Fetch a new heartbeat after waiting for optional kernel capture, then validate
            # its PID and uptime against a later controller reading. No timed samples change.
            $liveReceiver=Read-Receiver
            $validationController=Call-Mapper 'diagnostics'
            Assert-ProjectLiveReceiver $adbPath $targetSerial $liveReceiver $validationController.uptimeMs
            $receiverFresh=$sameObserver -and $liveReceiver.observationId -eq $receiverAfter.observationId -and
                $liveReceiver.pid -eq $receiverAfter.pid -and $liveReceiver.activityResumed -and $liveReceiver.windowFocused -and
                $receiverAfter.capturedUptimeMs -ge $before.uptimeMs -and [Math]::Abs($after.uptimeMs-$receiverAfter.capturedUptimeMs) -le 2000
        } catch { $receiverLiveError=$_.Exception.Message }
    }
    $result.receiver=[ordered]@{
        sameObserver=$sameObserver; applicableTarget=$applicable; freshLiveObserver=$receiverFresh; liveValidationError=$receiverLiveError
        touchCallbackDelta=$receiverAfter.totalTouchEvents-$receiverBefore.totalTouchEvents
        sequenceErrorDelta=$receiverAfter.sequenceErrors-$receiverBefore.sequenceErrors
        pendingContactIds=@($receiverAfter.pendingContactIds)
        scope='Independent observer callback receipt, not physical display presentation; source origin is not inferred from MotionEvent alone'
    }
    if($sameObserver -and $receiverFresh) {
        $seen=New-Object 'System.Collections.Generic.HashSet[long]'
        $times=@(@($receiverAfter.samples)+@($receiverAfter.transitions) | Where-Object {
            $_.sequence -gt $receiverBefore.totalTouchEvents -and
            $_.receiptUptimeMs -ge $before.uptimeMs -and $_.receiptUptimeMs -le $after.uptimeMs -and
            # Exclude native mouse events from a failed/unmapped trial.
            ($_.source -band 0x2002) -ne 0x2002 -and ($_.source -band 0x20004) -ne 0x20004 -and
            $seen.Add([long]$_.sequence) -and $_.eventToReceiptMs -ge 0
        } | ForEach-Object { [double]$_.eventToReceiptMs } | Sort-Object)
        $result.receiver.retainedEventToReceiptCount=$times.Count
        $result.receiver.retainedScope='Union of last 256 samples and last 512 transitions, deduplicated by sequence and restricted to interval; not a uniform sample of all movement callbacks'
        if($times.Count -gt 0) {
            $result.receiver.eventTimestampToReceiptMs=[ordered]@{
                p50=$times[[int][Math]::Floor(($times.Count-1)*.50)]
                p95=$times[[int][Math]::Floor(($times.Count-1)*.95)]
                p99=$times[[int][Math]::Floor(($times.Count-1)*.99)]
                max=$times[-1]
            }
        }
    }
}
if($Instrumented) { $result.probeRunId=$stopped.runId; $result.probeSummary=$stopped.summaryByStage }
Save-Json $result 'result.json'
Write-Host "Saved: $Destination"
if(!$stable -or !$fresh) { throw 'Capture has no stable, fresh mapped workload; retained for diagnosis.' }
