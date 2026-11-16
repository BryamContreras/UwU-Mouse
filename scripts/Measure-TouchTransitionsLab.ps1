param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateRange(0,2147483647)][int]$DisplayId,
    [ValidateRange(100,10000)][int]$DurationMs=4000,
    [switch]$Precision,
    [switch]$EnhancedFreeFireOptimization,
    [switch]$Instrumented,
    [string]$Destination
)
. "$PSScriptRoot\AndroidTools.ps1"
if(!$Destination) {
    $Destination=Join-Path (Split-Path -Parent $PSScriptRoot) ('evidence\touch-transitions-lab-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
if(Test-Path -LiteralPath $Destination) {
    $existing=Get-Item -LiteralPath $Destination
    if(!$existing.PSIsContainer -or (Get-ChildItem -LiteralPath $Destination -Force | Select-Object -First 1)) {
        throw 'The evidence destination must be empty.'
    }
}
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination=(Resolve-Path -LiteralPath $Destination).Path
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
$applicationId='dev.uwumouse.app'
$receiverId='dev.uwumouse.inputlab'

function Save-Json($Value,[string]$Name) {
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath (Join-Path $Destination $Name) -Encoding UTF8
}
function Get-Field($Value,[string]$Name,$Default=$null) {
    if($null -eq $Value){return $Default}
    $property=$Value.PSObject.Properties[$Name]
    if($null -eq $property){return $Default}
    return $property.Value
}
function Invoke-LabAdb([string[]]$Arguments,[int]$TimeoutMs=10000,[switch]$AllowFailure) {
    # All arguments below are fixed tokens, numeric extras or verified device/package paths.
    # ProcessStartInfo avoids a visible shell and lets a stalled transport be bounded.
    $tokens=@('-s',$targetSerial)+$Arguments
    foreach($token in $tokens) {
        if(!$token -or $token -match '[\s"''\r\n]'){throw 'Unexpected whitespace/quote in an ADB argument'}
    }
    $info=New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName=$adbPath
    $info.Arguments=$tokens -join ' '
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    $process=New-Object System.Diagnostics.Process
    $process.StartInfo=$info
    try {
        if(!$process.Start()){throw 'Could not start the ADB command'}
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if(!$process.WaitForExit($TimeoutMs)) {
            try {$process.Kill()} catch {}
            [void]$process.WaitForExit(1000)
            throw 'ADB command exceeded its bounded timeout'
        }
        $result=[pscustomobject]@{stdout=$stdout.GetAwaiter().GetResult();stderr=$stderr.GetAwaiter().GetResult();exitCode=$process.ExitCode}
        if($result.exitCode -ne 0 -and !$AllowFailure){throw "ADB failed: $($Arguments -join ' '); $($result.stderr.Trim())"}
        return $result
    } finally {$process.Dispose()}
}
function Save-DeviceText([string]$Name,[string[]]$Arguments) {
    try {
        $value=Invoke-LabAdb -Arguments $Arguments -AllowFailure
        ($value.stdout+$value.stderr+"`nexitCode="+$value.exitCode) | Set-Content -LiteralPath (Join-Path $Destination $Name) -Encoding UTF8
    } catch { $_.Exception.Message | Set-Content -LiteralPath (Join-Path $Destination $Name) -Encoding UTF8 }
}
function Read-Mapper([string]$Method='diagnostics',[int]$TimeoutMs=10000,[string[]]$Extra=@()) {
    $value=Invoke-LabAdb -Arguments (@('shell','content','call','--uri',"content://$applicationId.backend",'--method',$Method)+$Extra) -TimeoutMs $TimeoutMs
    $wire=$value.stdout.Trim()
    if($wire -notmatch '(?s)^Result: Bundle\[\{json=(.*)\}\]$'){throw "No mapper JSON for $Method"}
    return ($Matches[1] | ConvertFrom-Json)
}
function Invoke-MapperAction([string]$Method,[string[]]$Extra=@()) {
    $value=Invoke-LabAdb -Arguments (@('shell','content','call','--uri',"content://$applicationId.backend",'--method',$Method)+$Extra)
    $value.stdout | Set-Content -LiteralPath (Join-Path $Destination ('provider-'+$Method+'.txt')) -Encoding UTF8
    if($value.stdout -notmatch 'accepted=true'){throw "Mapper action was not accepted: $Method"}
}
function Read-LabReceiver {
    $value=Invoke-LabAdb -Arguments @('shell','run-as',$receiverId,'cat','files/input-lab-live.json') -AllowFailure -TimeoutMs 3000
    $wire=$value.stdout.Trim()
    if($value.exitCode -eq 0 -and $wire.StartsWith('{')){return ($wire | ConvertFrom-Json)}
    return $null
}
function Test-Closed($State) {
    return !(Get-Field $State 'active' $true) -and !(Get-Field $State 'transitioning' $true) -and
        !(Get-Field $State 'releaseUnverified' $true) -and (Get-Field $State 'contacts' -1) -eq 0 -and
        (Get-Field $State 'backendPossibleContacts' -1) -eq 0 -and (Get-Field $State 'backendConfirmedContacts' -1) -eq 0
}
function Save-Thermal([string]$Phase) {
    Save-DeviceText "battery-$Phase.txt" @('shell','dumpsys','battery')
    Save-DeviceText "thermal-$Phase.txt" @('shell','dumpsys','thermalservice')
}

$manifest=[ordered]@{
    schemaVersion=1; sourceOrigin='synthetic-app'; syntheticInputConfirmed=$true; physicalInput=$false; physicalInputConfirmed=$false
    workload='lab-touch-transitions'; serial=$targetSerial; applicationId=$applicationId; receiverApplicationId=$receiverId
    displayId=$DisplayId; requestedDurationMs=$DurationMs; precision=[bool]$Precision
    enhancedFreeFireOptimization=[bool]$EnhancedFreeFireOptimization
    requestedClickRateHz=10; sourceBatchHz=250; samplesPerBatch=4; sampleSpacingMs=1; directionPhaseMs=125
    receiverReset='Only InputLab is force-stopped/reopened, on the explicitly selected display; no application data is cleared.'
    profiles='RAM-only diagnostic profile; saved profiles are exported before and after.'
    probeEnabled=[bool]$Instrumented
    probeScope='Optional recorder includes synthetic session setup and closure. Inspect paired RPCs and retained coverage separately; closed endpoints cannot pass the physical stable-session protocol. Source timestamps are synthetic even when legacy stage names mention mouse/native input.'
    latencyScope='App-produced inputs through the mapper and real injector into InputLab. No USB/peripheral, game, display-presentation or photon latency claim.'
    logcatScope='Bounded filtered tails, never a cleared device log or a guaranteed complete trial interval.'
    gameRestore=$false; installsApks=$false; startedUtc=[DateTime]::UtcNow.ToString('o')
}
Save-Json $manifest 'trial-manifest.json'
$failure=$null
$profilesBefore=$null
$receiverBefore=$null
$controllerAfter=$null
$lab=$null
$started=$false
$probe=$null
$polls=New-Object System.Collections.Generic.List[object]
try {
    $initial=Read-Mapper
    Save-Json $initial 'controller-initial.json'
    if(!(Get-Field $initial 'backend' $false)){throw 'The authorized mapper backend must already be running.'}
    $profilesBefore=Read-Mapper 'profiles'
    Save-Json $profilesBefore 'profiles-before.json'
    Save-DeviceText 'package-before.txt' @('shell','dumpsys','package',$applicationId)
    Save-DeviceText 'inputlab-package.txt' @('shell','dumpsys','package',$receiverId)
    Save-DeviceText 'displays-before.txt' @('shell','dumpsys','display')
    Save-DeviceText 'logcat-before.txt' @('shell','logcat','-d','-v','threadtime','-t','300','UwUMouse:I','UwuSupervisor:I','UwuInjectWorker:I','UwuInputLab:I','AndroidRuntime:E','*:S')
    $paths=Invoke-LabAdb -Arguments @('shell','pm','path',$applicationId)
    $apkPaths=@($paths.stdout -split '[\r\n]+' | Where-Object {$_ -match '^package:/.*/base\.apk$'})
    if($apkPaths.Count -eq 1) {
        $apkPath=$apkPaths[0].Substring('package:'.Length)
        if($apkPath -notmatch '^/[A-Za-z0-9_/+=.~:-]+\.apk$'){throw 'Unexpected installed APK path'}
        $hash=Invoke-LabAdb -Arguments @('shell','sha256sum',$apkPath)
        $hash.stdout | Set-Content -LiteralPath (Join-Path $Destination 'installed-apk-sha256.txt') -Encoding UTF8
        if($hash.stdout -match '^([a-fA-F0-9]{64})\s'){ $manifest.installedApkSha256=$Matches[1].ToLowerInvariant() }
    }
    Invoke-MapperAction 'labStop'
    $closing=[Diagnostics.Stopwatch]::StartNew()
    do {
        $closed=Read-Mapper -TimeoutMs 3000
        if(Test-Closed $closed){break}
        if($closing.ElapsedMilliseconds -ge 6000){throw 'The previous stream did not close; no receiver was opened.'}
        Start-Sleep -Milliseconds 200
    } while($true)
    Save-Json $closed 'controller-closed-before.json'
    [void](Invoke-LabAdb -Arguments @('shell','am','force-stop',$receiverId))
    $opened=Invoke-LabAdb -Arguments @('shell','am','start','-W','--display',[string]$DisplayId,'-f','0x10008000','-n',"$receiverId/.InputLabActivity")
    $opened.stdout | Set-Content -LiteralPath (Join-Path $Destination 'receiver-launch.txt') -Encoding UTF8
    if($opened.stdout -match '(?m)^Error:'){throw 'InputLab could not be opened on the selected display'}
    Invoke-MapperAction 'labPrepare' @('--extra',"displayId:i:$DisplayId")
    $preparing=[Diagnostics.Stopwatch]::StartNew()
    do {
        $prepared=Read-Mapper -TimeoutMs 3000
        $receiverBefore=Read-LabReceiver
        $window=Get-Field $prepared 'windowContext'
        $ready=(Get-Field $prepared 'activePackage') -eq $receiverId -and
            (Get-Field $prepared 'targetDisplayId' -1) -eq $DisplayId -and
            (Get-Field $window 'targetPackage') -eq $receiverId -and (Get-Field $window 'displayId' -1) -eq $DisplayId -and
            (Get-Field $window 'trustedFocus' $false) -and (Get-Field $receiverBefore 'displayId' -1) -eq $DisplayId -and
            (Get-Field $receiverBefore 'activityResumed' $false) -and (Get-Field $receiverBefore 'windowFocused' $false)
        if($ready) {
            # The receiver was read after $prepared. Validate it against a later clock,
            # so a fresh heartbeat cannot be mistaken for a snapshot from the future.
            $validation=Read-Mapper -TimeoutMs 3000
            Assert-ProjectLiveReceiver $adbPath $targetSerial $receiverBefore $validation.uptimeMs
            break
        }
        if($preparing.ElapsedMilliseconds -ge 5000){throw 'InputLab was not verified focused on the authorized display.'}
        Start-Sleep -Milliseconds 200
    } while($true)
    Save-Json $prepared 'controller-before.json'
    Save-Json $receiverBefore 'receiver-before.json'
    Save-Thermal 'before'
    if($Instrumented) {
        $probe=Read-Mapper -Method 'latencyStart' -Extra @('--extra','durationMs:i:20000')
        Save-Json $probe 'probe-start.json'
    }
    $precisionWire=([bool]$Precision).ToString().ToLowerInvariant()
    $enhancedWire=([bool]$EnhancedFreeFireOptimization).ToString().ToLowerInvariant()
    $started=$true # A timed-out Binder reply can still leave an accepted laboratory request.
    Invoke-MapperAction 'labTouchTransitions' @('--extra',"displayId:i:$DisplayId",'--extra',"durationMs:i:$DurationMs",'--extra',"precision:b:$precisionWire",'--extra',"enhanced:b:$enhancedWire")
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $remaining=15000-[int]$timer.ElapsedMilliseconds
        if($remaining -le 0){throw 'Laboratory polling exceeded fifteen seconds.'}
        $controllerAfter=Read-Mapper -TimeoutMs ([Math]::Min(3000,$remaining))
        $lab=Get-Field $controllerAfter 'labTouchTransitions'
        $polls.Add([ordered]@{elapsedMs=$timer.ElapsedMilliseconds;stage=Get-Field $lab 'stage';finished=Get-Field $lab 'finished' $false;state=$controllerAfter.state})
        if(Get-Field $lab 'finished' $false){break}
        Start-Sleep -Milliseconds 200
    } while($true)
    Save-Json $controllerAfter 'controller-after.json'
    Save-Json $lab 'lab-result.json'
    if((Get-Field $lab 'packageName') -ne $receiverId -or (Get-Field $lab 'displayId' -1) -ne $DisplayId -or
        (Get-Field $lab 'sourceOrigin') -ne 'synthetic-app' -or (Get-Field $lab 'physicalInput' $true)){throw 'Laboratory result identity/synthetic-source mismatch.'}
    if(Get-Field $lab 'failure'){throw "Laboratory failed: $($lab.failure)"}
    if(!(Get-Field $lab 'internalStreamClosed' $false) -or !(Test-Closed $controllerAfter)){throw 'The laboratory stream did not close cleanly.'}
    $observing=[Diagnostics.Stopwatch]::StartNew()
    do {
        $receiverAfter=Read-LabReceiver
        $fresh=(Get-Field $receiverAfter 'capturedUptimeMs' -1) -ge $controllerAfter.uptimeMs
        if($fresh){break}
        if($observing.ElapsedMilliseconds -ge 4000){throw 'No independent receiver snapshot after the final mapper release.'}
        Start-Sleep -Milliseconds 200
    } while($true)
    Save-Json $receiverAfter 'receiver-after.json'
    $live=Read-Mapper -TimeoutMs 3000
    Assert-ProjectLiveReceiver $adbPath $targetSerial $receiverAfter $live.uptimeMs
    if($receiverAfter.observationId -ne $receiverBefore.observationId -or $receiverAfter.pid -ne $receiverBefore.pid -or
        $receiverAfter.displayId -ne $DisplayId -or !$receiverAfter.activityResumed -or !$receiverAfter.windowFocused){throw 'Independent receiver identity, display or focus changed.'}
    $sequenceErrorDelta=[long]$receiverAfter.sequenceErrors-[long]$receiverBefore.sequenceErrors
    $touchDelta=[long]$receiverAfter.totalTouchEvents-[long]$receiverBefore.totalTouchEvents
    if($sequenceErrorDelta -ne 0 -or $touchDelta -le 0 -or $receiverAfter.maxContacts -lt 3 -or
        @($receiverAfter.pendingContactIds).Count -ne 0 -or @($receiverAfter.validatorPendingContactIds).Count -ne 0){throw 'Independent receiver rejected touch sequence, overlap or final zero-contact criteria.'}
    Save-Json ([ordered]@{sameObserver=$true;displayId=$DisplayId;freshAfterRelease=$true;sequenceErrorDelta=$sequenceErrorDelta;
        touchCallbackDelta=$touchDelta;maxContacts=$receiverAfter.maxContacts;pendingContactIds=@($receiverAfter.pendingContactIds);
        scope='Receive-only InputLab validation; no physical origin or presentation latency inference.'}) 'receiver-validation.json'
} catch {$failure=$_}
finally {
    if($null -ne $probe) {
        try {
            $stopped=Read-Mapper 'latencyStop'
            Save-Json $stopped 'probe-stop.json'
            $rows=New-Object System.Collections.Generic.List[object]
            $page=$stopped
            while($true) {
                if($page.runId -ne $probe.runId -or $page.pid -ne $probe.pid -or $page.enabled) {
                    throw 'Recorder identity changed or the recorder did not stop.'
                }
                foreach($row in $page.records){$rows.Add($row)}
                if($null -eq $page.nextRecordsOffset){break}
                if([int]$page.nextRecordsOffset -ne $rows.Count -or $rows.Count -ge $stopped.storedCount -or $rows.Count -gt 4096) {
                    throw 'Recorder pagination did not advance through the bounded ring.'
                }
                $page=Read-Mapper -Method 'latencySnapshot' -Extra @('--extra',"offset:i:$([int]$page.nextRecordsOffset)",'--extra','limit:i:512')
            }
            if($rows.Count -ne $stopped.storedCount){throw 'Recorder export was incomplete'}
            Save-Json @($rows.ToArray()) 'probe-records.json'
        } catch {if($null -eq $failure){$failure=$_}}
    }
    if($started -and $null -ne $failure) {
        try {
            $cleanupState=Read-Mapper -TimeoutMs 3000
            if((Get-Field $cleanupState 'activePackage') -eq $receiverId -and (Get-Field $cleanupState 'targetDisplayId' -1) -eq $DisplayId) {
                Invoke-MapperAction 'labStop'
            } else {Save-Json ([ordered]@{skipped=$true;reason='The laboratory no longer owns the current target/display'}) 'cleanup-skipped.json'}
        } catch {Save-Json ([ordered]@{error=$_.Exception.Message}) 'cleanup-error.json'}
    }
    Save-Json @($polls.ToArray()) 'polls.json'
    try {
        $profilesAfter=Read-Mapper 'profiles'
        Save-Json $profilesAfter 'profiles-after.json'
        if($null -ne $profilesBefore) {
            $manifest.savedProfilesUnchanged=($profilesBefore | ConvertTo-Json -Depth 80 -Compress) -eq ($profilesAfter | ConvertTo-Json -Depth 80 -Compress)
            if(!$manifest.savedProfilesUnchanged -and $null -eq $failure){$failure=[System.Exception]::new('Saved profile export changed during the laboratory trial')}
        }
    } catch {if($null -eq $failure){$failure=$_}}
    Save-Thermal 'after'
    Save-DeviceText 'logcat-after.txt' @('shell','logcat','-d','-v','threadtime','-t','1500','UwUMouse:I','UwuSupervisor:I','UwuInjectWorker:I','UwuInputLab:I','AndroidRuntime:E','*:S')
    Save-DeviceText 'activities-after.txt' @('shell','dumpsys','activity','activities')
    $manifest.completedUtc=[DateTime]::UtcNow.ToString('o')
    $manifest.success=$null -eq $failure
    if($null -ne $failure){$manifest.failure=if($failure -is [System.Management.Automation.ErrorRecord]){$failure.Exception.Message}else{$failure.Message}}
    Save-Json $manifest 'trial-manifest.json'
}
if($null -ne $failure){throw $failure}
Write-Host "Synthetic InputLab evidence saved: $Destination"
