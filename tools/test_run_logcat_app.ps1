$ErrorActionPreference = 'Stop'
# The function shadows adb for this process. Every invocation exits synchronously;
# no device, log buffer, background process, or real executable is touched.
$global:logcatRegressionCalls = [System.Collections.Generic.List[string]]::new()
$global:logcatRegressionPidOutput = ''
$global:logcatRegressionPidExit = 0
function adb {
    $global:logcatRegressionCalls.Add(($args -join ' '))
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'shell') {
        $global:LASTEXITCODE = $global:logcatRegressionPidExit
        return $global:logcatRegressionPidOutput
    }
    if ($args[0] -eq 'logcat') { return 'bounded synthetic com.sotaware.construct event' }
    throw 'Unexpected mocked ADB operation'
}

$scriptPath = Join-Path $PSScriptRoot 'run_logcat_app.ps1'
$cases = @(
    @{Output='24680'; Exit=0; Expected='logcat --pid 24680 -v time'},
    @{Output=''; Exit=0; Expected='logcat -v time'},
    @{Output='host-pid'; Exit=0; Expected='logcat -v time'},
    @{Output='123 456'; Exit=0; Expected='logcat -v time'},
    @{Output='0'; Exit=0; Expected='logcat -v time'},
    @{Output='999999999999999999'; Exit=0; Expected='logcat -v time'},
    @{Output='24680'; Exit=1; Expected='logcat -v time'}
)
foreach ($case in $cases) {
    $global:logcatRegressionCalls.Clear()
    $global:logcatRegressionPidOutput = $case.Output
    $global:logcatRegressionPidExit = $case.Exit
    & $scriptPath -AppId 'com.sotaware.construct' | Out-Null
    if ($global:logcatRegressionCalls.Count -ne 2 -or
        $global:logcatRegressionCalls[0] -ne 'shell pidof -s com.sotaware.construct' -or
        $global:logcatRegressionCalls[1] -ne $case.Expected) {
        throw "Actual logcat launcher arguments differ: $($global:logcatRegressionCalls -join ', ')"
    }
}
Write-Output "PASS: $($cases.Count) actual-script mocked-ADB cases"
