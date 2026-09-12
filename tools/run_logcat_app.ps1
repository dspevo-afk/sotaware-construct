param(
    [string]$AppId = '',
    [string]$OutFile = ''
)

try {
    if (-not $AppId) {
        $AppId = & .\tools\android_env.ps1
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $AppId = $AppId.Trim()
    }
} catch {
    Write-Error "Failed to determine applicationId: $_"
    exit 1
}

# locate adb
$adbCmd = 'adb'
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) { $adbCmd = $sdkAdb } else { Write-Error 'adb not found. Install Android SDK platform-tools or add it to PATH.'; exit 1 }
}

# try pidof
$appProcessId = ''
try {
    $appProcessIdRaw = & $adbCmd shell pidof -s $AppId 2>$null
    $pidofExitCode = $LASTEXITCODE
    $pidofText = ($appProcessIdRaw -join "`n").Trim()
    $parsedAppProcessId = 0
    if ($pidofExitCode -eq 0 -and $pidofText -match '^[1-9][0-9]*$' -and
        [int]::TryParse($pidofText, [ref]$parsedAppProcessId)) {
        $appProcessId = $parsedAppProcessId.ToString()
    }
} catch { $appProcessId = '' }

if ($appProcessId) {
    Write-Output "Streaming logcat for PID $appProcessId (app=$AppId)"
    if ($OutFile) { & $adbCmd logcat --pid $appProcessId -v time | Tee-Object -FilePath $OutFile } else { & $adbCmd logcat --pid $appProcessId -v time }
} else {
    Write-Output "Could not get PID via pidof. Falling back to filtering by package name ($AppId)."
    if ($OutFile) { & $adbCmd logcat -v time | Select-String $AppId | Tee-Object -FilePath $OutFile } else { & $adbCmd logcat -v time | Select-String $AppId }
}
