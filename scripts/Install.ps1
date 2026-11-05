param([string]$Serial, [switch]$Release)
. "$PSScriptRoot\AndroidTools.ps1"
$adbPath = Get-ProjectAdb
$targetSerial = Get-ProjectDevice $adbPath $Serial
$projectRoot = Split-Path -Parent $PSScriptRoot
$flavor = if ($Release) { 'release' } else { 'debug' }
$mapperApk = Join-Path $projectRoot "build\app\outputs\flutter-apk\app-$flavor.apk"
$labApk = Join-Path $projectRoot 'build\inputlab\outputs\apk\debug\inputlab-debug.apk'
foreach ($apk in @($mapperApk, $labApk)) {
    if (!(Test-Path -LiteralPath $apk)) { throw "Compila primero: falta $apk" }
    Invoke-ProjectAdb $adbPath $targetSerial @('install', '-r', $apk)
}
Invoke-ProjectAdb $adbPath $targetSerial @('shell', 'am', 'start', '-n', 'dev.mobimapper.mobimapper/.MainActivity')
