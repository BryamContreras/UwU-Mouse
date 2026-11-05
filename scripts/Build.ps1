param([switch]$Release)
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
    Invoke-BuildStep { & flutter pub get } 'pub get falló'
    Invoke-BuildStep { & flutter analyze } 'analyze falló'
    Invoke-BuildStep { & flutter test } 'flutter test falló'
    if ($Release) { Invoke-BuildStep { & flutter build apk --release } 'APK falló' }
    else { Invoke-BuildStep { & flutter build apk --debug } 'APK falló' }
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
    if (!$env:JAVA_HOME) {
        $studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath "$studioJdk\bin\java.exe") { $env:JAVA_HOME = $studioJdk }
    }
    Push-Location android
    try {
        Invoke-BuildStep { & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :inputlab:testDebugUnitTest :inputlab:assembleDebug :inputlab:lintDebug } 'Validación Android falló'
    } finally { Pop-Location }
} finally { Pop-Location }
