# look.ps1 - see SEVERAL files / line ranges / searches in ONE step (Dave, 2026-11-20).
# Every step costs ~6 s of model time, so ask for everything you need at once instead of one read per step.
#
#   .\tools\look.ps1 TARGET [TARGET ...]
#
# TARGET can be:
#   PdfPageRenderer.kt:1590-1680    lines 1590-1680 (a bare file name is found under app/src; a path works too)
#   SubDrawingModels.kt:120         lines 120-199 (80 lines from there)
#   SubDrawingBadge.kt              the whole file (first 600 lines if it is longer)
#   "grep:badgeRatio"               regex search (case-insensitive) in app/src + tools: file:line: text, max 80 hits
#   "grep:fun resize@stage8"        same, only in paths containing "stage8"
#
# Example:  .\tools\look.ps1 SubDrawingModels.kt "grep:assignIndex" PdfPageRenderer.kt:1330-1360 AnnotationReducer.kt:400-470
param()
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$WholeMax = 600; $DefaultSpan = 80; $GrepMax = 80
if (-not $args) { Get-Content $MyInvocation.MyCommand.Path -TotalCount 14 | Where-Object { $_ -match '^#' }; exit }

$script:SrcFiles = $null
function Src-Files {
    if (-not $script:SrcFiles) {
        $script:SrcFiles = @(foreach ($d in "app\src", "tools") {
            $p = Join-Path $Root $d
            if (Test-Path $p) { Get-ChildItem $p -Recurse -File | Where-Object { $_.FullName -notmatch '\\build\\' } }
        })
    }
    $script:SrcFiles
}
function Find-File([string]$p) {
    foreach ($c in @($p, (Join-Path $Root $p))) { if (Test-Path -LiteralPath $c -PathType Leaf) { return (Resolve-Path -LiteralPath $c).Path } }
    $name = Split-Path -Leaf $p
    $hits = @(Src-Files | Where-Object { $_.Name -eq $name -and ($p -eq $name -or $_.FullName.Replace('\', '/') -like "*$($p.Replace('\', '/'))") })
    if ($hits.Count -eq 1) { return $hits[0].FullName }
    if ($hits.Count -gt 1) { $script:FindMsg = @("===== ${p}: several files match - give more of the path =====") + @($hits | ForEach-Object { "  " + $_.FullName.Substring($Root.Length + 1) }); return $null }
    $script:FindMsg = @("===== ${p}: file not found ====="); return $null
}
function Rel([string]$full) { if ($full.StartsWith($Root)) { $full.Substring($Root.Length + 1) } else { $full } }

foreach ($t in $args) {
    $t = [string]$t
    if ($t -like "grep:*") {
        $pat = $t.Substring(5); $scope = $null
        if ($pat -match '^(.*)@([^@]+)$') { $pat = $Matches[1]; $scope = $Matches[2] }
        $files = Src-Files
        if ($scope) { $files = @($files | Where-Object { $_.FullName.Replace('\', '/') -like "*$($scope.Replace('\', '/'))*" }) }
        $hits = @($files | Select-String -Pattern $pat -Encoding UTF8 -ErrorAction SilentlyContinue)
        "===== grep '$pat'$(if ($scope) { " in *$scope*" }): $($hits.Count) hits$(if ($hits.Count -gt $GrepMax) { " (first $GrepMax shown)" }) ====="
        $hits | Select-Object -First $GrepMax | ForEach-Object { "{0}:{1}: {2}" -f (Rel $_.Path), $_.LineNumber, $_.Line.Trim() }
        continue
    }
    $from = $null; $to = $null; $p = $t
    if ($t -match '^(.+?):(\d+)(?:-(\d+))?$') { $p = $Matches[1]; $from = [int]$Matches[2]; $to = if ($Matches[3]) { [int]$Matches[3] } else { $from + $DefaultSpan - 1 } }
    $script:FindMsg = $null
    $f = Find-File $p
    if (-not $f) { $script:FindMsg; continue }
    $lines = @(Get-Content -LiteralPath $f -Encoding UTF8)
    $n = $lines.Count
    if (-not $from) { $from = 1; $to = [Math]::Min($n, $WholeMax) }
    $from = [Math]::Max(1, $from); $to = [Math]::Min($n, $to)
    "===== $(Rel $f)  lines $from-$to of $n$(if ($to -lt $n -and $from -eq 1 -and $t -notmatch ':\d') { " (ask for a range to see more)" }) ====="
    for ($i = $from; $i -le $to; $i++) { "{0,5}  {1}" -f $i, $lines[$i - 1] }
}
