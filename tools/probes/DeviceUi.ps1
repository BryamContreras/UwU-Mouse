function Assert-DeviceFocus([string]$Adb,[string]$Serial,[string]$Expected) {
    $focus=((& $Adb -s $Serial shell dumpsys window)|Select-String 'mCurrentFocus=')-join' '
    if($focus -notmatch [regex]::Escape($Expected)){throw "Unexpected focused window; no input sent: $focus"}
}
function Invoke-DeviceTap([string]$Adb,[string]$Serial,[string]$Expected,[int]$X,[int]$Y) {
    Assert-DeviceFocus $Adb $Serial $Expected
    if($Expected -eq 'com.dts.freefireth'){
        $wire=((& $Adb -s $Serial shell content call --uri content://dev.mobimapper.mobimapper.backend --method diagnostics)-join'')
        if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'Cannot confirm paused mapping; no game touch sent'}
        $state=$Matches[1]|ConvertFrom-Json
        if($state.active -or $state.contacts -gt 0 -or $state.releaseUnverified -or $state.backendPossibleContacts -gt 0 -or $state.mode -notin @('paused','stopped')){throw 'Mapping is active, transitioning or uncertain; no ADB game touch sent'}
    }
    & $Adb -s $Serial shell input tap $X $Y
    if($LASTEXITCODE-ne0){throw 'Device tap failed'}
}
function Get-DeviceUi([string]$Adb,[string]$Serial,[string]$Expected) {
    Assert-DeviceFocus $Adb $Serial $Expected
    # Stock uiautomator attaches UiAutomation and temporarily suppresses accessibility services.
    # Never use it to inspect a live editor or mapping gesture; use screencap + diagnostics there.
    if($Expected -ne 'dev.mobimapper.mobimapper'){throw 'UI automation is restricted to the stopped mapper panel'}
    $wire=((& $Adb -s $Serial shell content call --uri content://dev.mobimapper.mobimapper.backend --method diagnostics)-join'')
    if($wire -notmatch '^Result: Bundle\[\{json=(.*)\}\]$'){throw 'Cannot confirm stopped mapping before UI automation'}
    $state=$Matches[1]|ConvertFrom-Json
    if($state.active -or $state.editorVisible -or $state.contacts -gt 0 -or $state.releaseUnverified){throw 'Do not suppress accessibility while the editor or a gesture is active'}
    & $Adb -s $Serial shell uiautomator dump /data/local/tmp/mobimapper-ui.xml|Out-Null
    [xml]$tree=((& $Adb -s $Serial shell cat /data/local/tmp/mobimapper-ui.xml)-join'')
    Start-Sleep -Seconds 2
    return $tree
}
function Invoke-DeviceLabel([string]$Adb,[string]$Serial,[string]$Expected,[string]$Label) {
    $tree=Get-DeviceUi $Adb $Serial $Expected
    $node=$tree.SelectNodes('//node')|Where-Object{$_.'content-desc'-eq$Label -or $_.text-eq$Label}|Select-Object -First 1
    if(!$node -or $node.bounds-notmatch'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw "UI label not visible: $Label"}
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2);$y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-DeviceTap $Adb $Serial $Expected $x $y
}
