param([switch]$Release,[switch]$NativePolicyTests)
. "$PSScriptRoot\AndroidTools.ps1"
$projectRoot = Split-Path -Parent $PSScriptRoot
function Invoke-BuildStep([scriptblock]$Command, [string]$Failure) {
    # Windows PowerShell 5 turns redirected native stderr warnings into error records.
    # Native exit status, rather than a warning written to stderr, decides this step.
    $stepErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Command
        $stepExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $stepErrorPreference }
    if ($stepExitCode -ne 0) { throw $Failure }
}
Push-Location $projectRoot
try {
    if (!$env:JAVA_HOME) {
        $studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath "$studioJdk\bin\java.exe") { $env:JAVA_HOME = $studioJdk }
    }
    $python=Get-Command python -ErrorAction SilentlyContinue
    if(!$python) { $python=Get-Command python3 -ErrorAction SilentlyContinue }
    if(!$python) { throw 'Python 3 is required for architecture and compiled Android JSON ABI checks' }
    $flutter=Get-ProjectFlutter $projectRoot
    $versionFile=Join-Path $env:TEMP 'uwu-build-flutter-version.json'
    Invoke-BuildStep { & $flutter --version --machine | Set-Content -LiteralPath $versionFile -Encoding UTF8 } 'Flutter version query failed'
    Invoke-BuildStep { & $python.Source (Join-Path $projectRoot 'tools\check_toolchain.py') --flutter-json $versionFile } 'Shared toolchain differs from installed tools'
    Invoke-BuildStep { & $python.Source (Join-Path $projectRoot 'tools\check_architecture.py') --json } 'Architecture check failed'
    Invoke-BuildStep { & $python.Source -m unittest discover -s tools/tests -v } 'Build helper tests failed'
    Invoke-BuildStep { & $flutter pub get --enforce-lockfile } 'pub get falló'
    Invoke-BuildStep { & $flutter analyze } 'analyze falló'
    Invoke-BuildStep { & $flutter test } 'flutter test falló'
    if ($Release) { Invoke-BuildStep { & $flutter build apk --release } 'APK falló' }
    else { Invoke-BuildStep { & $flutter build apk --debug } 'APK falló' }
    $builtApk=if($Release) { 'build\app\outputs\flutter-apk\app-release.apk' } else { 'build\app\outputs\flutter-apk\app-debug.apk' }
    Invoke-BuildStep {
        & $python.Source (Join-Path $projectRoot 'tools\check_android_native_alignment.py') (Join-Path $projectRoot $builtApk) --json
    } 'Native libraries are incompatible with 16 KB memory pages'
    # Java Properties accepts forward slashes; escaping the drive colon also satisfies Android Lint.
    $localFile = Join-Path $projectRoot 'android\local.properties'
    $propertyLines = Get-Content -LiteralPath $localFile | ForEach-Object {
        if ($_ -match '^(sdk\.dir|flutter\.sdk)=(.+)$') {
            $propertyName = $Matches[1]
            $propertyValue = $Matches[2].Replace('\\', '/').Replace('\:', ':').Replace(':', '\:')
            "$propertyName=$propertyValue"
        } else { $_ }
    }
    $propertyLines | Set-Content -LiteralPath $localFile -Encoding ASCII
    $checkTasks=@(Get-Content -LiteralPath (Join-Path $projectRoot 'tools\android-check-tasks.txt'))
    if(!$checkTasks.Count -or @($checkTasks | Where-Object { $_ -notmatch '^:[a-z][a-z-]*:[A-Za-z]+$' }).Count) {
        throw 'Invalid Android check task manifest'
    }
    Push-Location android
    try {
        Invoke-BuildStep { & .\gradlew.bat --max-workers=2 --console=plain @checkTasks } 'Validación Android falló'
    } finally { Pop-Location }
    if($NativePolicyTests) {
        Invoke-BuildStep { & $python.Source (Join-Path $projectRoot 'tools\test_capture_policy.py') } 'Native capture policy tests failed'
    }
    Invoke-BuildStep {
        & $python.Source (Join-Path $projectRoot 'tools\check_android_json_abi.py') --classes (Join-Path $projectRoot 'build\mapper-core\classes\kotlin\main') --json
    } 'Android JSON ABI incompatible'
} finally { Pop-Location }
