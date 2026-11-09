param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(5,30)][int]$Seconds=8,
    [string]$Destination,
    [string]$NativeProbePath,
    [string]$MouseEventPath
)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
if(!$Destination) { $Destination=Join-Path (Split-Path -Parent $PSScriptRoot) ("evidence\capture-paths-"+(Get-Date -Format 'yyyyMMdd-HHmmss')) }
if($NativeProbePath -and ($NativeProbePath -notmatch '^/data/local/tmp/[a-zA-Z0-9_-]+$' -or $MouseEventPath -notmatch '^/dev/input/event\d+$')) { throw 'Invalid explicit native probe or device path' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination=(Resolve-Path -LiteralPath $Destination).Path
function Save($Value,[string]$Path) { $Value|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $Path -Encoding UTF8 }
function Helper([string]$Method,[string[]]$Extra=@()) {
    $wire=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.latencyprobe.control --method $Method @Extra)-join '')
    if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') { throw "Helper call failed: $Method" }
    $Matches[1]|ConvertFrom-Json
}
$mapperWire=((& $adbPath -s $targetSerial shell content call --uri content://dev.uwumouse.app.backend --method diagnostics)-join '')
if($mapperWire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') { throw 'Cannot verify mapper state' }
$mapper=$Matches[1]|ConvertFrom-Json
if($mapper.active -or $mapper.contacts -gt 0 -or $mapper.releaseUnverified -or $mapper.mouseCaptured) { throw 'Stop the mapper before comparing capture paths' }
Save $mapper (Join-Path $Destination 'mapper-before.json')
$ready=Helper 'snapshot' @('--extra','limit:i:0')
if(!$ready.activityFocused -or !$ready.accessibilityConnected) { throw 'Open the temporary helper and enable its accessibility service before measuring' }
$trial=0
try {
    # Reversed order on the second pair reduces a simple warm-up/order bias.
    foreach($mode in @('native','accessibility','accessibility','native')) {
        $trial++
        $trialDirectory=Join-Path $Destination ("$trial-$mode")
        New-Item -ItemType Directory -Force -Path $trialDirectory | Out-Null
        $start=Helper 'start' @('--extra',"mode:s:$mode",'--extra',("durationMs:i:"+($Seconds*1000)))
        Save $start (Join-Path $trialDirectory 'start.json')
        $nativeProcess=$null
        if($NativeProbePath) {
            $nativeProcess=Start-Process -FilePath $adbPath -ArgumentList @('-s',$targetSerial,'shell',$NativeProbePath,$MouseEventPath,$Seconds) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $trialDirectory 'kernel-native.json') -RedirectStandardError (Join-Path $trialDirectory 'kernel-native-errors.txt')
            # Keep the Windows process handle so its exit status remains available after exit.
            $nativeHandle=$nativeProcess.Handle
        }
        Write-Host "Trial $trial/4: $mode, $Seconds seconds. Continue moving the mouse."
        Start-Sleep -Milliseconds ($Seconds*1000+300)
        [void](Helper 'stop')
        $page=Helper 'snapshot' @('--extra','limit:i:512')
        Save $page (Join-Path $trialDirectory 'summary.json')
        $rows=New-Object System.Collections.Generic.List[object]
        while($true) {
            if($page.runId -ne $start.runId -or $page.pid -ne $start.pid -or $page.enabled -or $page.helperMode -ne $mode) { throw 'Unstable helper identity' }
            foreach($row in $page.records) { $rows.Add($row) }
            if($null -eq $page.nextRecordsOffset) { break }
            $page=Helper 'snapshot' @('--extra',("offset:i:"+$page.nextRecordsOffset),'--extra','limit:i:512')
        }
        if($rows.Count -ne $page.storedCount -or $rows.Count -eq 0) { throw 'Missing helper samples' }
        Save @($rows.ToArray()) (Join-Path $trialDirectory 'records.json')
        if($null -ne $nativeProcess) {
            if(!$nativeProcess.WaitForExit(5000)) { throw 'Native reader exceeded its deadline' }
            $nativeProcess.Refresh()
            $kernelResult=Get-Content -LiteralPath (Join-Path $trialDirectory 'kernel-native.json') -Raw | ConvertFrom-Json
            if(($null -ne $nativeProcess.ExitCode -and $nativeProcess.ExitCode -ne 0) -or !$kernelResult.ok -or $kernelResult.sampleCount -eq 0) { throw 'Native reader failed or has no samples; inspect its JSON and stderr' }
        }
    }
} finally {
    [void](Helper 'stop')
}
Write-Host "Comparison saved: $Destination"
