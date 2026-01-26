# Determine applicationId, ensure JAVA_HOME and adb for this session
try {
    $id = ''
    $kts = 'app\build.gradle.kts'
    $g = 'app\build.gradle'
    if (Test-Path $kts) {
        $t = Get-Content $kts -Raw
        if ($t -match 'applicationId\s*=\s*"([^"]+)"') { $id = $matches[1] }
        elseif ($t -match 'namespace\s*=\s*"([^"]+)"' -and -not $id) { $id = $matches[1] }
    }
    if (-not $id -and Test-Path $g) {
        $t = Get-Content $g -Raw
        if ($t -match 'applicationId\s+"([^"]+)"') { $id=$matches[1] }
        elseif ($t -match "applicationId\s+'([^']+)'") { $id=$matches[1] }
        elseif ($t -match 'namespace\s+"([^"]+)"' -and -not $id) { $id=$matches[1] }
    }
    if (-not $id) {
        Write-Error 'applicationId not found in app/build.gradle(.kts)'
        exit 1
    }

    # Ensure JAVA_HOME for this session if missing
    if (-not $env:JAVA_HOME -or $env:JAVA_HOME.Trim() -eq '') {
        $jbr = 'C:\Program Files\Android\Android Studio\jbr'
        if (Test-Path $jbr) {
            $env:JAVA_HOME = $jbr
            $env:Path = "$jbr\bin;$env:Path"
            Write-Output "JAVA_HOME set to $jbr for this session"
        } else {
            Write-Error 'JAVA_HOME is not set. Set JAVA_HOME to a JDK path (e.g. C:\Program Files\Android\Android Studio\jbr) or install a JDK.'
            exit 2
        }
    }

    # Ensure adb available for this session
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        if (Test-Path $sdkAdb) {
            $sdkBin = Split-Path $sdkAdb
            $env:PATH = "$sdkBin;$env:PATH"
            Write-Output "ADB path added: $sdkBin"
        } else {
            Write-Error 'adb not found in PATH. Install Android SDK platform-tools or add it to PATH.'
            exit 3
        }
    }

    # Print only the applicationId for callers
    Write-Output $id
    exit 0
} catch {
    Write-Error "android_env.ps1 failed: $_"
    exit 4
}
