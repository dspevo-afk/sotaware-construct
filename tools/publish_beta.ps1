# Publish a BETA build of SOTAware Construct ("SOTAware Beta", a separate app next to the official one).
# Dave 2026-11-20: the coding agent may run this after the full gate passes; it never touches the official
# app's feed (sotaware) or version.properties. Official releases still go through tools/publish_update.ps1.
#
#   .\tools\publish_beta.ps1 -Notes "What changed, in one line"
#
# What it does:
#   1. bumps versionCode in version.properties (always above what is already published)
#   2. builds the debug APK (signed with %USERPROFILE%\.android\debug.keystore, like the installed app)
#   3. refuses to publish if the signing key is not that key (Android would reject the update anyway)
#   4. uploads the APK + latest.json to c4130:/srv/app-updates/sotaware/ (served tailnet-only on :9448)
# The app checks latest.json when opened and shows "App update available" -> Update.
# Settings > "Check for app update" checks on demand.
param(
    [string]$Notes = "",
    [switch]$Force
)
$ErrorActionPreference = "Stop"

$App = "sotaware-beta"
$NamePrefix = "beta-"
$ExpectedCert = "a4b9bfdb7ce9563c2766439e304e279925694b90aabfa7acf8fa585900421243"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$RemoteDir = "/srv/app-updates/$App"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0"

$utc = (Get-Date).ToUniversalTime()
$minutes = $utc.Hour * 60 + $utc.Minute
if (-not $Force -and $minutes -ge 510 -and $minutes -lt 600) {
    throw "08:30-10:00 UTC is the bid-scan window: no app changes. Wait, or pass -Force if Dave said so."
}

function Native([scriptblock]$Block) {
    # PowerShell 5.1 turns any stderr text from programs (like the c4130 login banner) into errors; ignore it here
    # and judge success by $LASTEXITCODE instead.
    $saved = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Block } finally { $ErrorActionPreference = $saved }
}

function Read-Props($path) {
    $p = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') { $p[$matches[1]] = $matches[2] }
        }
    }
    return $p
}

function Write-Utf8($path, $text) {
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}

# 1. next version number: above both the local file and what the server already offers
$propsFile = Join-Path $Root "beta-version.properties"
$props = Read-Props $propsFile
$localCode = [int]$props["versionCode"]
$remoteText = (Native { ssh c4130 "cat $RemoteDir/latest.json 2>/dev/null" 2>$null } | ForEach-Object { "$_" }) -join "`n"
$remoteCode = 0
$brace = $remoteText.IndexOf("{")
if ($brace -ge 0) { $remoteCode = [int](($remoteText.Substring($brace) | ConvertFrom-Json).versionCode) }
$code = [Math]::Max([Math]::Max($localCode, $remoteCode), 1) + 1
$name = "$NamePrefix$code"
$oldProps = if (Test-Path $propsFile) { [IO.File]::ReadAllText($propsFile) } else { $null }
Write-Utf8 $propsFile "# Bumped automatically by tools/publish_beta.ps1 - every published build needs a higher versionCode.`nversionCode=$code`nversionName=$name`n"
Write-Host "Building $App $name (versionCode $code)..."

# 2. build
Push-Location $Root
try {
    Native { .\gradlew.bat --console=plain -q -PsotawareBeta=true :app:assembleDebug 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch "deprecat|unchecked|Recompile with" } | Out-Host }
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
} catch {
    if ($null -eq $oldProps) { Remove-Item $propsFile -Force } else { Write-Utf8 $propsFile $oldProps }
    throw
} finally {
    Pop-Location
}
$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"

# 3. signing key check
$certs = Native { & "$BuildTools\apksigner.bat" verify --print-certs $apk 2>$null }
$digest = ($certs | Select-String "certificate SHA-256 digest: (\w+)").Matches[0].Groups[1].Value
if ($digest -ne $ExpectedCert) { throw "APK is signed with an unexpected key ($digest) - not publishing." }

# 4. upload APK, then latest.json (atomic rename so devices never see a half-written file)
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$apkName = "$App-$code.apk"
Native { scp -q $apk "c4130:$RemoteDir/$apkName.part" 2>$null }
if ($LASTEXITCODE -ne 0) { throw "upload failed" }
$info = [ordered]@{
    versionCode = $code
    versionName = $name
    apk         = $apkName
    sha256      = $sha
    notes       = $Notes
    published   = $utc.ToString("yyyy-MM-ddTHH:mm:ssZ")
}
$tmp = Join-Path $env:TEMP "$App-latest.json"
Write-Utf8 $tmp (($info | ConvertTo-Json) + "`n")
Native { scp -q $tmp "c4130:$RemoteDir/latest.json.tmp" 2>$null }
if ($LASTEXITCODE -ne 0) { throw "upload failed" }
Native { ssh c4130 "cd $RemoteDir && mv -f $apkName.part $apkName && cp -f $apkName SOTAware-Beta.apk && mv -f latest.json.tmp latest.json && ls -1t $App-*.apk | tail -n +6 | xargs -r rm -f"  2>$null }
if ($LASTEXITCODE -ne 0) { throw "could not activate the update on c4130" }

Write-Host "Published $App $name. Devices offer it the next time the app is opened (or Settings > Check for app update)."
