<#
Interactive cycle script:
1) Saves current logcat to app/build/outputs/logs
2) Clears device logcat
3) Waits for you to run the test and press Enter
4) Saves logcat after the test

Usage: .\cycle_logcat.ps1 -Filter "Blueprint"
Filter: optional string passed to get_logcat.ps1 to narrow results (defaults to empty = full log)
#>
param(
    [string]$Filter = "",
    [string]$OutDir = "app\\build\\outputs\\logs"
)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$clear = Join-Path $scriptDir "clear_logcat.ps1"
$get = Join-Path $scriptDir "get_logcat.ps1"
if (-not (Test-Path $get) -or -not (Test-Path $clear)) { Write-Error "Required scripts not found in $scriptDir"; exit 1 }
Write-Host "Logcat cycle started. Filter='$Filter'"
while ($true) {
    Write-Host "\n--- Saving current logcat snapshot ---"
    if ($Filter -ne "") { powershell -ExecutionPolicy Bypass -File $get -Filter $Filter -OutDir $OutDir } else { powershell -ExecutionPolicy Bypass -File $get -OutDir $OutDir }

    Write-Host "--- Clearing device logcat ---"
    powershell -ExecutionPolicy Bypass -File $clear

    Write-Host "Run your test now on the device. When finished, press Enter to capture post-test log (type 'q' then Enter to quit)."
    $entry = Read-Host
    if ($entry -eq 'q') { break }

    Write-Host "--- Saving post-test logcat snapshot ---"
    if ($Filter -ne "") { powershell -ExecutionPolicy Bypass -File $get -Filter $Filter -OutDir $OutDir } else { powershell -ExecutionPolicy Bypass -File $get -OutDir $OutDir }

    Write-Host "Cycle complete. Press Enter to repeat, or type 'q' to quit."
    $again = Read-Host
    if ($again -eq 'q') { break }
}
Write-Host "Cycle ended."