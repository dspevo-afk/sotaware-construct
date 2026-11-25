# Android EMULATOR helper for checking the app's screens yourself (Dave OK 2026-11-20).
# Only ever talks to the emulator (emulator-5554) - never a phone or tablet.
#
#   .\tools\emu.ps1 start              start the emulator if it is not running (takes ~1 min)
#   .\tools\emu.ps1 install            install app\build\outputs\apk\debug\app-debug.apk (keeps app data)
#   .\tools\emu.ps1 launch             (re)start SOTAware Construct in landscape
#   .\tools\emu.ps1 shot NAME          screenshot -> build\emu\NAME.png (1200x540). Open it with the read tool.
#   .\tools\emu.ps1 ui [WORD]          list on-screen elements: [x1,y1][x2,y2] 'text' 'description' (filter by WORD)
#   .\tools\emu.ps1 tap X Y            tap
#   .\tools\emu.ps1 hold X Y [MS]      press and hold (default 1200 ms)
#   .\tools\emu.ps1 swipe X1 Y1 X2 Y2 [MS]   drag (default 600 ms)
#   .\tools\emu.ps1 type TEXT          type text into the focused field
#   .\tools\emu.ps1 key back|enter|delete
#
# Coordinates are in SCREENSHOT pixels (1200x540, the same numbers you see in the shot); `ui` bounds use the
# same scale. Every action except `ui` saves build\emu\last.png automatically - read it to see the result.
# Never sign in to any account: if a Google sign-in screen appears, press `key back` (Dave signs in himself).
# Two-finger pinch is not possible on this emulator; test pinch logic with unit tests instead.
# Test PDFs on the emulator: Downloads/fresh.pdf and Downloads/blueprint.pdf ("Open PDF" on the Projects screen,
# or "Recent Drawings" below it - scroll down).
param([Parameter(Position = 0)][string]$Cmd = "help", [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest)
$ErrorActionPreference = "Stop"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Emulator = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$Serial = "emulator-5554"
$Pkg = "com.sotaware.construct"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Out = Join-Path $Root "build\emu"
New-Item -ItemType Directory -Force $Out | Out-Null
$Scale = 2   # screenshot pixels -> device pixels (device is 2400x1080 in landscape)

function A {
    # no param() block on purpose: arguments like -r / -W / -n must reach adb untouched
    $a = @($args)
    $saved = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & $Adb -s $Serial @a 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $saved } }
function Ready { (& $Adb devices 2>$null | Select-String "^$Serial\s+device") -ne $null }
function D([string]$v) { [int]([double]$v * $Scale) }
function Landscape {
    A shell settings put system accelerometer_rotation 0 | Out-Null; A shell settings put system user_rotation 1 | Out-Null
    for ($i = 0; $i -lt 10 -and -not ((A shell dumpsys window displays) -match "mRotation=ROTATION_90"); $i++) { Start-Sleep 1 }
    Start-Sleep 1
}
function Shot([string]$name) {
    Add-Type -AssemblyName System.Drawing
    $tmp = Join-Path $Out "_full.png"
    $p = Start-Process -FilePath $Adb -ArgumentList "-s $Serial exec-out screencap -p" -RedirectStandardOutput $tmp -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "screenshot failed" }
    $img = [System.Drawing.Image]::FromFile($tmp)
    try {
        $w = [int]($img.Width / $Scale); $h = [int]($img.Height / $Scale)
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = "HighQualityBicubic"; $g.DrawImage($img, 0, 0, $w, $h); $g.Dispose()
        $dest = Join-Path $Out "$name.png"; $bmp.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    } finally { $img.Dispose() }
    "saved build\emu\$name.png (${w}x${h})"
}
function After { Start-Sleep -Milliseconds 1500; Shot "last" | Out-Null; "screen saved: build\emu\last.png" }

if ($Cmd -ne "start" -and $Cmd -ne "help" -and -not (Ready)) { throw "Emulator is not running. Run: .\tools\emu.ps1 start" }
switch ($Cmd) {
    "start" {
        if (Ready) { "emulator already running"; break }
        Start-Process -FilePath $Emulator -ArgumentList "-avd Medium_Phone_API_36.1 -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect" -WindowStyle Minimized
        for ($i = 0; $i -lt 60 -and -not ((A shell getprop sys.boot_completed) -match "1"); $i++) { Start-Sleep 3 }
        Landscape; "emulator ready"
    }
    "install" {
        $apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
        if (-not (Test-Path $apk)) { throw "no APK - run :app:assembleDebug first" }
        $aapt = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt.exe" | Select-Object -Last 1).FullName
        $badging = & $aapt dump badging $apk 2>$null | Select-Object -First 1
        if ($badging -notmatch "name='$Pkg'") { throw "app-debug.apk is not the normal app ($badging). Run :app:assembleDebug first (a beta publish leaves a beta APK there)." }
        $r = A install -r $apk; $r | Select-Object -Last 2
        if (-not ($r -match "Success")) { throw "install failed" }
    }
    "launch" {
        Landscape; A shell am force-stop $Pkg | Out-Null
        A shell am start -W -n "$Pkg/com.example.myapplication.MainActivity" | Out-Null
        Start-Sleep 4; Landscape; After
    }
    "shot" { if (-not $Rest) { throw "usage: shot NAME" }; Shot ($Rest[0] -replace '[^\w-]', '_') }
    "ui" {
        A shell uiautomator dump /sdcard/emu-ui.xml | Out-Null
        $xml = (A exec-out cat /sdcard/emu-ui.xml) -join "`n"
        $filter = if ($Rest) { $Rest -join " " } else { "" }
        foreach ($m in [regex]::Matches($xml, '<node [^>]*>')) {
            $n = $m.Value
            $t = [regex]::Match($n, ' text="([^"]*)"').Groups[1].Value
            $d = [regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value
            $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $t -and -not $d) { continue }
            $v = 1..4 | ForEach-Object { [math]::Floor([int]$b.Groups[$_].Value / $Scale) }
            $bb = "[{0},{1}][{2},{3}]" -f $v[0], $v[1], $v[2], $v[3]
            $line = "$bb '$t' '$d'"
            if (-not $filter -or $line -match [regex]::Escape($filter)) { $line }
        }
    }
    "tap" { A shell input tap (D $Rest[0]) (D $Rest[1]) | Out-Null; After }
    "hold" { $ms = if ($Rest.Count -ge 3) { $Rest[2] } else { 1200 }
        A shell input swipe (D $Rest[0]) (D $Rest[1]) (D $Rest[0]) (D $Rest[1]) $ms | Out-Null; After }
    "swipe" { $ms = if ($Rest.Count -ge 5) { $Rest[4] } else { 600 }
        A shell input swipe (D $Rest[0]) (D $Rest[1]) (D $Rest[2]) (D $Rest[3]) $ms | Out-Null; After }
    "type" { $txt = ($Rest -join " ") -replace ' ', '%s' -replace "'", ""; A shell input text "'$txt'" | Out-Null; After }
    "key" { $code = @{ back = 4; enter = 66; delete = 67 }[$Rest[0]]; if (-not $code) { throw "key: back|enter|delete" }
        A shell input keyevent $code | Out-Null; After }
    default { Get-Content $MyInvocation.MyCommand.Path -TotalCount 20 | Where-Object { $_ -match '^#' } }
}
