# Clears device logcat buffer so you can start a fresh test run
# Usage: .\clear_logcat.ps1
$adb = "$Env:ANDROID_HOME\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
Write-Host "Clearing device logcat buffer..."
& $adb logcat -c
if ($LASTEXITCODE -eq 0) { Write-Host "Cleared logcat." } else { Write-Host "Failed to clear logcat (exit $LASTEXITCODE)." }