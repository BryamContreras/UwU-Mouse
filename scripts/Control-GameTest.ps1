param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('Prepare','Start','Edit','Pause','Menu','Stop','Snapshot')][string]$Action='Snapshot',
    [ValidatePattern('^[a-zA-Z0-9_-]{1,128}$')][string]$ProfileId,
    [ValidateRange(0,32)][int]$DisplayId=0,
    [ValidatePattern('^dev\.mobimapper\.mobimapper(?:\.[a-z][a-z0-9_]*)?$')]
    [string]$ApplicationId='dev.mobimapper.mobimapper.nativepreview',
    [string]$Destination
)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath=Get-ProjectAdb
$targetSerial=Get-ProjectDevice $adbPath $Serial
if($Action -in @('Start','Edit') -and !$ProfileId) { throw 'Start/Edit require a saved ProfileId' }
if($Action -ne 'Snapshot') {
    $method=@{Prepare='gamePrepare';Start='gameStart';Edit='gameEdit';Pause='gamePause';Menu='gameMenu';Stop='gameStop'}[$Action]
    $arguments=@('-s',$targetSerial,'shell','content','call','--uri',"content://$ApplicationId.backend",'--method',$method,'--extra',"displayId:i:$DisplayId")
    if($ProfileId) { $arguments+=@('--arg',$ProfileId) }
    $wire=((& $adbPath @arguments)-join '')
    if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{accepted=true\}\]$') {
        throw "Game command failed: $wire. Install a preview with GameDiagnostics support."
    }
}
$wire=((& $adbPath -s $targetSerial shell content call --uri "content://$ApplicationId.backend" --method diagnostics)-join '')
if($LASTEXITCODE -ne 0 -or $wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$') { throw 'Game diagnostics unavailable' }
$snapshot=$Matches[1] | ConvertFrom-Json
if($Destination) {
    $parent=Split-Path -Parent ([IO.Path]::GetFullPath($Destination))
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $snapshot | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $Destination -Encoding UTF8
}
# Acceptance starts the normal asynchronous UI flow; it does not prove physical gameplay.
$snapshot | Select-Object state,active,activePackage,profileName,targetDisplayId,contacts,
    mouseCaptured,cursorVisible,pointerMode,editorVisible,captureError,contextError,
    releaseUnverified,keyReleaseUnverified
