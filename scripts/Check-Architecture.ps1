param(
    [string]$GradlePath,
    [string]$AndroidSdk,
    [string]$FlutterRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$platformRoot=Join-Path $projectRoot 'android\platform-build'
$destination=Join-Path $projectRoot 'evidence\architecture'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$logPath=Join-Path $destination 'integration-check.log'
$resultPath=Join-Path $destination 'integration-check.json'
[ordered]@{success=$false;stage='checking_architecture';scope='No integration result available for this invocation yet'} |
    ConvertTo-Json | Set-Content -LiteralPath $resultPath -Encoding UTF8

$python=Get-Command python -ErrorAction SilentlyContinue
if(!$python) { $python=Get-Command python3 -ErrorAction SilentlyContinue }
if(!$python) { throw 'Python 3 is required for architecture and Android JSON ABI verification' }
$toolchainJson=@(& $python.Source (Join-Path $projectRoot 'tools\check_toolchain.py'))
if($LASTEXITCODE -ne 0) { throw 'Shared toolchain configuration is inconsistent' }
$toolchain=($toolchainJson -join "`n" | ConvertFrom-Json).toolchain
$staticOutput=@(& $python.Source (Join-Path $projectRoot 'tools\check_architecture.py') --root $projectRoot --json)
$staticExitCode=$LASTEXITCODE
$staticOutput | Set-Content -LiteralPath (Join-Path $destination 'static-check.json') -Encoding UTF8
$staticOutput | Write-Output
if($staticExitCode -ne 0) { throw 'Static architecture verification failed before the platform build' }

function Assert-SourceRule([string]$Path,[string]$Pattern,[string]$Reason) {
    $violations=@(Get-ChildItem -LiteralPath $Path -Recurse -File -Filter '*.kt' | Select-String -Pattern $Pattern)
    if($violations.Count) {
        $details=($violations|ForEach-Object {"$($_.Path):$($_.LineNumber)"}) -join ', '
        throw "Architecture rule failed: $Reason ($details)"
    }
}

Assert-SourceRule (Join-Path $projectRoot 'android\mapper-core\src\main\kotlin') '^import (android\.|io\.flutter\.)' 'core must remain independent of Android and Flutter'
Assert-SourceRule (Join-Path $projectRoot 'android\mapper-core\src\main\kotlin\dev\mobimapper\mobimapper\engine') '^import dev\.mobimapper\.(capture|injection|windowcontext|presentation)(\.|$)|^import dev\.mobimapper\.mobimapper\.(session\.(CaptureService|SessionController|NativeInputHost)|backend\.(IInjector|BackendRegistry)|game\.GameCoordinator)' 'mapping engine must not depend on capture or injection adapters'
Assert-SourceRule (Join-Path $projectRoot 'android\presentation\src\main\kotlin') '^import (android\.accessibilityservice\.|android\.view\.accessibility\.|dev\.mobimapper\.mobimapper\.(MainActivity|session\.(CaptureService|SessionController|NativeInputHost)|game\.GameCoordinator|profile\.ProfileStore|backend\.BackendRegistry))' 'presentation must not depend on application or accessibility implementations'
$presentationBuild=Get-Content -LiteralPath (Join-Path $projectRoot 'android\presentation\build.gradle.kts') -Raw
if($presentationBuild -match 'project\("(:app|:host)"\)') { throw 'Presentation must not depend on the application project' }
$manifest=Get-Content -LiteralPath (Join-Path $projectRoot 'android\app\src\main\AndroidManifest.xml') -Raw
if($manifest -match 'BIND_ACCESSIBILITY_SERVICE|android\.accessibilityservice\.AccessibilityService|android\.accessibilityservice"') { throw 'App manifest still declares an accessibility service' }

if(!$GradlePath) { $GradlePath=Join-Path $projectRoot 'android\gradlew.bat' }
if(!(Test-Path -LiteralPath $GradlePath -PathType Leaf)) { throw 'Gradle executable not found' }
if(!$AndroidSdk) {
    if($env:ANDROID_HOME) { $AndroidSdk=$env:ANDROID_HOME }
    elseif($env:ANDROID_SDK_ROOT) { $AndroidSdk=$env:ANDROID_SDK_ROOT }
    else { $AndroidSdk=Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
$AndroidSdk=(Resolve-Path -LiteralPath $AndroidSdk).Path
if(!(Test-Path -LiteralPath (Join-Path $AndroidSdk "platforms\android-$($toolchain.sdkPlatform)\android.jar"))) { throw "Android platform $($toolchain.sdkPlatform) not found" }
if(!$env:JAVA_HOME) {
    $studioJdk=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if(Test-Path -LiteralPath (Join-Path $studioJdk 'bin\java.exe')) { $env:JAVA_HOME=$studioJdk }
}
if(!$FlutterRoot) {
    if($env:FLUTTER_ROOT) { $FlutterRoot=$env:FLUTTER_ROOT }
    else {
        $flutterCommand=Get-Command flutter -ErrorAction SilentlyContinue
        if($flutterCommand) { $FlutterRoot=Split-Path -Parent (Split-Path -Parent $flutterCommand.Source) }
        else { throw 'Flutter SDK not found. Pass -FlutterRoot, set FLUTTER_ROOT, or put flutter on PATH.' }
    }
}
$FlutterRoot=(Resolve-Path -LiteralPath $FlutterRoot).Path
$versionFile=Join-Path $destination 'flutter-version.json'
& (Join-Path $FlutterRoot 'bin\flutter.bat') --version --machine | Set-Content -LiteralPath $versionFile -Encoding UTF8
if($LASTEXITCODE -ne 0) { throw 'Flutter version query failed' }
& $python.Source (Join-Path $projectRoot 'tools\check_toolchain.py') --flutter-json $versionFile
if($LASTEXITCODE -ne 0) { throw 'Installed Flutter differs from the shared toolchain' }
$precachePreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    & (Join-Path $FlutterRoot 'bin\flutter.bat') precache --android
    $precacheExitCode=$LASTEXITCODE
} finally { $ErrorActionPreference=$precachePreference }
if($precacheExitCode -ne 0) { throw 'Could not refresh the Android embedding for the pinned Flutter SDK' }
if(!(Test-Path -LiteralPath (Join-Path $FlutterRoot 'bin\cache\artifacts\engine\android-arm64\flutter.jar'))) { throw 'Cached Flutter Android embedding jar not found' }
# Machine-specific configuration belongs only to the standalone build.
$sdkProperty=$AndroidSdk.Replace('\','/').Replace(':','\:')
"sdk.dir=$sdkProperty" | Set-Content -LiteralPath (Join-Path $platformRoot 'local.properties') -Encoding ASCII
$arguments=@('-p',$platformRoot,'--console=plain','-Pandroid.useAndroidX=true',"-Puwu.flutterRoot=$FlutterRoot")
$aapt2=Join-Path $AndroidSdk "build-tools\$($toolchain.buildTools)\aapt2.exe"
if(Test-Path -LiteralPath $aapt2) { $arguments+="-Pandroid.aapt2FromMavenOverride=$aapt2" }
$arguments+=@(':mapper-core:test',':capture-api:test',':capture-native:testDebugUnitTest',':window-context:testDebugUnitTest',':injection-runtime:testDebugUnitTest',':activation-transport:testDebugUnitTest',':host:testDebugUnitTest',':inputlab:testDebugUnitTest',':host:compileDebugKotlin')
$started=[DateTime]::UtcNow
$exitCode=1
$gradleExitCode=1
$jsonAbiChecked=$false
$jsonAbiPassed=$false
$jsonAbiReport=Join-Path $destination 'android-json-abi.json'
try {
    # Native stderr warnings must not stop PowerShell before its exit code is read.
    $savedPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & $GradlePath @arguments 2>&1 | Tee-Object -FilePath $logPath
        $gradleExitCode=$LASTEXITCODE
        $exitCode=$gradleExitCode
        if($exitCode -eq 0) {
            # JVM org.json can expose overloads absent from Android. Test compiled
            # production references; successful JVM unit tests cannot establish this ABI.
            $exitCode=1
            $python=Get-Command python -ErrorAction SilentlyContinue
            if(!$python) { $python=Get-Command python3 -ErrorAction SilentlyContinue }
            if(!$python) { throw 'Python 3 is required to verify the compiled Android JSON ABI' }
            $abiOutput=@(& $python.Source (Join-Path $projectRoot 'tools\check_android_json_abi.py') --classes (Join-Path $projectRoot 'build\platform\mapper-core\classes\kotlin\main') --json)
            $exitCode=$LASTEXITCODE
            $abiOutput | Set-Content -LiteralPath $jsonAbiReport -Encoding UTF8
            $abiOutput | Write-Output
            $jsonAbiChecked=$true
            $jsonAbiPassed=$exitCode -eq 0
        }
    }
    finally { $ErrorActionPreference=$savedPreference }
} finally {
    $testRoot=Join-Path $projectRoot 'build\platform'
    $reports=@()
    if(Test-Path -LiteralPath $testRoot) {
        $reports=@(Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter 'TEST-*.xml' | Where-Object {$_.FullName -match '\\test-results\\'} | ForEach-Object {
            [xml]$xml=Get-Content -LiteralPath $_.FullName
            [ordered]@{
                suite=[string]$xml.testsuite.name; tests=[int]$xml.testsuite.tests
                failures=[int]$xml.testsuite.failures; errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped
                report=$_.FullName.Substring($projectRoot.Length+1).Replace('\','/')
                reportUpdatedUtc=$_.LastWriteTimeUtc.ToString('o'); updatedDuringInvocation=$_.LastWriteTimeUtc -ge $started
            }
        })
    }
    $total=0; $failures=0; $errors=0; $skipped=0
    foreach($report in $reports) { $total+=$report.tests; $failures+=$report.failures; $errors+=$report.errors; $skipped+=$report.skipped }
    [ordered]@{
        success=$exitCode -eq 0; gradleExitCode=$gradleExitCode; startedUtc=$started.ToString('o')
        gradle=$GradlePath; testsFromAvailableXml=$total; failures=$failures; errors=$errors; skipped=$skipped
        reports=$reports; architectureRules='passed'; androidJsonAbiChecked=$jsonAbiChecked
        androidJsonAbiPassed=$jsonAbiPassed; androidJsonAbiReport=$jsonAbiReport
        scope='Actual platform modules and host Kotlin/AIDL/resources/JVM tests. Does not compile Dart or establish full Flutter/native APK packaging or device behavior. XML reports may be cached; timestamps are included.'
    }|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $resultPath -Encoding UTF8
}
if($exitCode -ne 0) { throw "Platform integration or Android JSON ABI verification failed (exit $exitCode). See $logPath and $jsonAbiReport" }
Write-Host "Platform checks passed. $total tests reported. Evidence: $resultPath"
