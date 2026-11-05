param([Parameter(Mandatory=$true)][string]$Serial,[ValidateSet(1,3)][int]$Count=3)
# 0.1.1: kills the real injector, not its surviving authorized guardian.
& "$PSScriptRoot\Test-Stabilization.ps1" -Serial $Serial -Case "worker-recovery-$Count" -Count $Count
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
