param(
    [Parameter(Mandatory=$true)][ValidateSet('P1','P2','P3','P4','P5','P6','P7','P8')][string]$Stage,
    [string]$PythonInterpreterPath = 'D:/soft/Anaconda/python.exe'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$recordRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../docs/test-reports'))
$stageNumber = [int]$Stage.Substring(1)
if($stageNumber -ge 8){
    $deploymentReport=Join-Path $recordRoot 'P7-deployment-summary.json'
    if(-not(Test-Path -LiteralPath $deploymentReport) -or (Get-Content -Raw -LiteralPath $deploymentReport | ConvertFrom-Json).status -ne 'PASSED'){
        throw 'P7 actual deployment gate has not passed; P8 remains locked.'
    }
}
if ($stageNumber -ge 4 -and -not (Test-Path -LiteralPath $PythonInterpreterPath)) {
    throw 'The required fixed Anaconda Python executable was not found.'
}
if ($stageNumber -gt 1) {
    $previousStage = "P$($stageNumber - 1)"
    $previousReport = Join-Path $recordRoot "$previousStage-summary.json"
    if (-not (Test-Path -LiteralPath $previousReport)) { throw "Previous stage evidence is missing: $previousStage" }
    $previous = Get-Content -Raw -LiteralPath $previousReport | ConvertFrom-Json
    if ($previous.totals.tests -le 0 -or $previous.totals.failures -ne 0 -or $previous.totals.errors -ne 0 -or $previous.totals.skipped -ne 0) {
        throw "Previous stage did not pass: $previousStage"
    }
}
$requiredSuites = @{
    P1='com.yao.traffic.FoundationTest'; P2='com.yao.traffic.SecurityIntegrationTest';
    P3='com.yao.traffic.ReportIntegrationTest'; P4='com.yao.traffic.ReliableDetectionIntegrationTest';
    P5='com.yao.traffic.ReviewIntegrationTest'; P6='com.yao.traffic.RewardIntegrationTest';
    P7='com.yao.traffic.OperationsIntegrationTest'; P8='com.yao.traffic.EndToEndTest'
}
New-Item -ItemType Directory -Path $recordRoot -Force | Out-Null
& "$PSScriptRoot/build.ps1" -Goals @('clean','verify') 2>&1 | Tee-Object -FilePath "$recordRoot/$Stage-build.log"
if ($LASTEXITCODE -ne 0) { throw "$Stage build failed; next stage remains locked." }
$reportFiles = @(Get-ChildItem -LiteralPath "$projectRoot/target/surefire-reports" -Filter 'TEST-*.xml')
if ($reportFiles.Count -eq 0) { throw 'No test reports were produced.' }
if (-not ($reportFiles.Name -contains "TEST-$($requiredSuites[$Stage]).xml")) {
    throw "Required stage suite was not executed: $($requiredSuites[$Stage])"
}
$totals = @{ tests=0; failures=0; errors=0; skipped=0 }
foreach ($file in $reportFiles) {
    $suite = ([xml](Get-Content -Raw -LiteralPath $file.FullName)).testsuite
    foreach ($key in @('tests','failures','errors','skipped')) { $totals[$key] += [int]$suite.GetAttribute($key) }
}
if ($totals.tests -eq 0 -or $totals.failures -gt 0 -or $totals.errors -gt 0 -or $totals.skipped -gt 0) {
    throw "Stage gate rejected test totals: $($totals | ConvertTo-Json -Compress)"
}
$pythonProtocolTests = 0
if ($stageNumber -ge 4) {
    $modelRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '../PatchCraft（算法）'))
    if (-not (Test-Path -LiteralPath $PythonInterpreterPath)) { throw 'The required fixed Python interpreter was not found.' }
    Push-Location -LiteralPath $modelRoot
    try {
        & $PythonInterpreterPath -m unittest discover -s tests -p test_service_api.py -v 2>&1 |
            Tee-Object -FilePath "$recordRoot/$Stage-python-protocol.log"
        $pythonExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($pythonExitCode -ne 0) { throw 'Python model protocol tests failed; next stage remains locked.' }
    $pythonLog = Get-Content -Raw -LiteralPath "$recordRoot/$Stage-python-protocol.log"
    if ($pythonLog -notmatch 'Ran (\d+) tests?' -or [int]$Matches[1] -le 0) {
        throw 'Python protocol tests did not produce evidence of executed tests.'
    }
    $pythonProtocolTests = [int]$Matches[1]
    if ($pythonLog -match 'skipped=\d+') { throw 'Skipped Python protocol tests cannot pass the gate.' }
}
$summary = [ordered]@{ stage=$Stage; recordedAt=[DateTimeOffset]::Now.ToString('o'); command='tools/build.ps1 -Goals clean,verify'; totals=$totals; pythonProtocolTests=$pythonProtocolTests; suites=@($reportFiles.Name); externalServices='See execution record; this script does not turn H2/mock results into real-service evidence.' }
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$recordRoot/$Stage-summary.json" -Encoding utf8
Write-Output "$Stage tests passed: $($totals.tests); zero failures/errors/skips. Review external-service requirements before unlocking next stage."
