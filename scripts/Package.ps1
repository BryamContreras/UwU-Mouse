# Packages existing build outputs only; does not build, install or access a device.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path.TrimEnd('\')
function Workspace-Path([string]$Relative){
    $path=[IO.Path]::GetFullPath((Join-Path $workspace $Relative))
    if(!$path.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Destination escaped workspace: $Relative"}
    return $path
}
$pubspec=[IO.File]::ReadAllText((Workspace-Path 'pubspec.yaml'))
if($pubspec -notmatch '(?m)^version:\s*(\S+)\s*$'){throw 'Missing pubspec version'}
$version=$Matches[1]
if($version -notmatch '^(\d+\.\d+\.\d+)\+\d+$'){throw "Expected a numeric version with build number; found $version"}
$versionName=$Matches[1]
$labGradle=[IO.File]::ReadAllText((Workspace-Path 'android\inputlab\build.gradle.kts'))
if($labGradle -notmatch '(?m)^\s*versionName\s*=\s*"(\d+\.\d+\.\d+)"\s*$'){throw 'Missing numeric InputLab versionName'}
$labVersion=$Matches[1]
$evidenceVersion='v'+$versionName.Replace('.','')
$dist=Workspace-Path 'dist'
New-Item -ItemType Directory -Force -Path $dist|Out-Null
$mapperSource=Workspace-Path 'build\app\outputs\flutter-apk\app-release.apk'
$labSource=Workspace-Path 'build\inputlab\outputs\apk\debug\inputlab-debug.apk'
$buildInfo=Workspace-Path "dist\BUILD_INFO-$versionName.txt"
foreach($inputFile in @($mapperSource,$labSource,$buildInfo)){
    if(!(Test-Path -LiteralPath $inputFile -PathType Leaf)){throw "Required existing artifact is missing: $inputFile"}
}
$mapper=Workspace-Path "dist\UwU-Mouse-$versionName-dev.apk"
$lab=Workspace-Path "dist\UwUMouse-InputLab-$labVersion.apk"
[IO.File]::Copy($mapperSource,$mapper,$true)
if(Test-Path -LiteralPath $lab -PathType Leaf){
    # An unchanged receiver keeps its real version and its previously verified artifact.
    if((Get-FileHash -LiteralPath $labSource -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $lab -Algorithm SHA256).Hash){
        throw "InputLab $labVersion differs from the archived APK; bump its version before replacing it"
    }
} else {[IO.File]::Copy($labSource,$lab,$false)}
$unique=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$staging=Workspace-Path "dist\source-$versionName-$unique"
New-Item -ItemType Directory -Path $staging|Out-Null
$exclusions=@(
    '!dist/**','!build/**','!**/build/**','!.dart_tool/**','!.idea/**','!.git/**',
    '!android/.gradle/**','!android/.kotlin/**','!android/local.properties',
    '!.flutter-plugins-dependencies','!**/.cxx/**','!*.iml','!**/*.jks','!**/*.keystore',
    "!evidence/$evidenceVersion/delivery.json" # Generated after the ZIP; avoid stale self-referencing metadata.
)
$rgArguments=@('--files','--hidden','--no-ignore')
foreach($exclude in $exclusions){$rgArguments+=@('-g',$exclude)}
Push-Location $workspace
try {
    $files=@(& rg @rgArguments|Sort-Object)
    if($LASTEXITCODE -ne 0 -or $files.Count -eq 0){throw 'Unable to enumerate source files with rg'}
} finally {Pop-Location}
foreach($relative in $files){
    $source=Workspace-Path $relative
    $target=Workspace-Path ((Join-Path "dist\source-$versionName-$unique" $relative))
    if(!$target.StartsWith($staging+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Source destination escaped staging: $relative"}
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)|Out-Null
    [IO.File]::Copy($source,$target,$false)
}
$buildInfoName=[IO.Path]::GetFileName($buildInfo)
[IO.File]::Copy($buildInfo,(Workspace-Path "dist\source-$versionName-$unique\$buildInfoName"),$false)
$zip=Workspace-Path "dist\UwU-Mouse-source-$versionName.zip"
$temporaryZip=Workspace-Path "dist\.UwU-Mouse-source-$versionName-$unique.tmp.zip"
if([IO.Path]::GetDirectoryName($zip) -ne [IO.Path]::GetDirectoryName($temporaryZip)){throw 'ZIP replacement must remain in the same directory'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
# .NET enumerates hidden files too, preserving .metadata/.gitignore in the source archive.
[IO.Compression.ZipFile]::CreateFromDirectory($staging,$temporaryZip,[IO.Compression.CompressionLevel]::Optimal,$false)
if(Test-Path -LiteralPath $zip -PathType Leaf){
    # Windows PowerShell 5 can bind $null to an empty backup path in this overload.
    # An explicit task-owned backup keeps the replacement atomic on that runtime.
    $backupZip=Workspace-Path "dist\.UwU-Mouse-source-$versionName-$unique.previous.zip"
    [IO.File]::Replace($temporaryZip,$zip,$backupZip)
    Remove-Item -LiteralPath $backupZip
}
else {[IO.File]::Move($temporaryZip,$zip)}
$artifacts=@()
$checksumLines=@()
foreach($artifact in @($mapper,$lab,$zip)){
    $hash=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    $relative=$artifact.Substring($workspace.Length+1).Replace('\','/')
    $artifacts+=[ordered]@{path=$relative;sha256=$hash;bytes=(Get-Item -LiteralPath $artifact).Length}
    $checksumLines+="$hash  $([IO.Path]::GetFileName($artifact))"
}
$checksumLines|Set-Content -LiteralPath (Workspace-Path "dist\SHA256SUMS-$versionName.txt") -Encoding UTF8
$deliveryDirectory=Workspace-Path "evidence\$evidenceVersion"
New-Item -ItemType Directory -Force -Path $deliveryDirectory|Out-Null
[ordered]@{
    version=$version;inputLabVersion=$labVersion;packagedUtc=[DateTime]::UtcNow.ToString('o');artifacts=$artifacts
    sourceFileCount=($files.Count+1);sourceStaging=$staging.Substring($workspace.Length+1).Replace('\','/')
    buildInfo="dist/$buildInfoName";checksums="dist/SHA256SUMS-$versionName.txt"
}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Workspace-Path "evidence\$evidenceVersion\delivery.json") -Encoding UTF8
Write-Host "Packaged $version; SHA256SUMS-$versionName.txt and evidence/$evidenceVersion/delivery.json written."
