param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateSet('camera-only','camera-clicks')][string]$Workload,
    [Parameter(Mandatory=$true)][string]$TrialLabel,
    [ValidateRange(5,10)][int]$Seconds=5,
    [ValidateRange(1,30)][int]$RequestedClickRateHz=10,
    [switch]$PhysicalInputConfirmed,
    [string]$Destination,
    [string]$MouseEventPath,
    [ValidatePattern('^dev\.uwumouse\.app(?:\.[a-z][a-z0-9_]*)?$')]
    [string]$ApplicationId='dev.uwumouse.app'
)
$ErrorActionPreference='Stop'
if(!$Destination) {
    $Destination=Join-Path (Split-Path -Parent $PSScriptRoot) ('evidence\camera-latency-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
}
if(Test-Path -LiteralPath $Destination) {
    $existing=Get-Item -LiteralPath $Destination
    if(!$existing.PSIsContainer -or (Get-ChildItem -LiteralPath $Destination -Force | Select-Object -First 1)) {
        throw 'Capture destination must be empty; choose a new directory to avoid reusing prior artifacts.'
    }
}
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination=(Resolve-Path -LiteralPath $Destination).Path
$manifest=[ordered]@{
    schemaVersion=1; label=$TrialLabel; workload=$Workload; requestedSeconds=$Seconds
    serial=$Serial; applicationId=$ApplicationId
    requestedClickRateHz=if($Workload -eq 'camera-clicks'){$RequestedClickRateHz}else{0}
    physicalInputConfirmed=[bool]$PhysicalInputConfirmed
    confirmationScope='Operator confirmation only; this wrapper never generates mouse motion or clicks.'
    comparability='Use the same device, APK, profile, mouse polling/capture rate, display, game scene and thermal conditions. Repeat each workload independently.'
}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $Destination 'trial-manifest.json') -Encoding UTF8
if($Workload -eq 'camera-clicks') {
    Write-Host "During capture, move the physical mouse with repeated direction reversals and click M1 near $RequestedClickRateHz clicks/second."
} else {
    Write-Host 'During capture, move the physical mouse with repeated direction reversals without clicking M1.'
}
$captureArgs=@{Serial=$Serial;Seconds=$Seconds;Instrumented=$true;Destination=$Destination;ApplicationId=$ApplicationId}
if($MouseEventPath){$captureArgs.MouseEventPath=$MouseEventPath}
$captureFailure=$null
try { & "$PSScriptRoot\Measure-InputLatency.ps1" @captureArgs } catch { $captureFailure=$_ }
$analyzer=Join-Path (Split-Path -Parent $PSScriptRoot) 'tools\analyze_camera_latency.py'
if(Test-Path -LiteralPath (Join-Path $Destination 'probe-records.json')) {
    & python $analyzer $Destination --output (Join-Path $Destination 'camera-latency-report.json')
    if($LASTEXITCODE -ne 0 -and !$captureFailure){throw 'Latency analysis rejected the capture; inspect camera-latency-report.json.'}
}
if($captureFailure){throw $captureFailure}
Write-Host "Saved analysis: $Destination\camera-latency-report.json"
