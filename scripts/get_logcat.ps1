<#
Fetches device logcat and writes to a timestamped file under app/build/outputs/logs
Usage: .\get_logcat.ps1 [-Filter "Blueprint"]
#>
param(
    [string]$Filter = "",
    [string]$OutDir = "app\\build\\outputs\\logs"
)
$adb = "$Env:ANDROID_HOME\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$ts = (Get-Date).ToString('yyyyMMdd_HHmmss')
$filename = Join-Path $OutDir ("logcat_$ts.txt")
Write-Host "Fetching logcat -> $filename"
if ($Filter -ne "") {
    & $adb logcat -d -v threadtime | Select-String -Pattern $Filter -SimpleMatch | Out-File -FilePath $filename -Encoding utf8
} else {
    & $adb logcat -d -v threadtime | Out-File -FilePath $filename -Encoding utf8
}
if ($LASTEXITCODE -eq 0) { Write-Host "Saved logcat to $filename" } else { Write-Host "adb returned exit code $LASTEXITCODE; check device/adb." }
Write-Host "Tail (last 40 lines):"; Get-Content $filename -Tail 40 | ForEach-Object { Write-Host $_ }
