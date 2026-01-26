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

# clear logcat buffer
& $adbCmd logcat -c

# try pidof
$pid = ''
try {
    $pidRaw = & $adbCmd shell pidof -s $AppId 2>$null
    if ($pidRaw) { $pid = $pidRaw -join "`n"; $pid = $pid.Trim() }
} catch { $pid = '' }

if ($pid) {
    Write-Output "Streaming logcat for PID $pid (app=$AppId)"
    if ($OutFile) { & $adbCmd logcat --pid $pid -v time | Tee-Object -FilePath $OutFile } else { & $adbCmd logcat --pid $pid -v time }
} else {
    Write-Output "Could not get PID via pidof. Falling back to filtering by package name ($AppId)."
    if ($OutFile) { & $adbCmd logcat -v time | Select-String $AppId | Tee-Object -FilePath $OutFile } else { & $adbCmd logcat -v time | Select-String $AppId }
}
