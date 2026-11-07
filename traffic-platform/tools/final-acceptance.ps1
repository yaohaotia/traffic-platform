$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$reportRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '../docs/test-reports'))
$backend=Get-Content -Raw -LiteralPath (Join-Path $reportRoot 'P8-summary.json') | ConvertFrom-Json
$browser=Get-Content -Raw -LiteralPath (Join-Path $reportRoot 'P8-browser-summary.json') | ConvertFrom-Json
$model=Get-Content -Raw -LiteralPath (Join-Path $reportRoot 'P8-model-summary.json') | ConvertFrom-Json
if($backend.totals.failures -ne 0 -or $backend.totals.errors -ne 0 -or $backend.totals.skipped -ne 0 -or $backend.totals.tests -lt 138 -or $backend.pythonProtocolTests -lt 25){throw 'The complete backend gate has not passed.'}
if($browser.status -ne 'PASSED' -or $model.status -ne 'PASSED'){throw 'The actual browser/model gate has not passed.'}
$testedAt=[DateTimeOffset]::Parse($backend.recordedAt).UtcDateTime
$sourceFiles=@(Get-ChildItem -Path (Join-Path $projectRoot 'src') -Recurse -File)+@(Get-Item -LiteralPath (Join-Path $projectRoot 'pom.xml'))
if($sourceFiles | Where-Object {$_.LastWriteTimeUtc -gt $testedAt}){throw 'Backend source changed after the complete gate; rerun P8.'}
$frontendHash=(Get-FileHash -LiteralPath (Join-Path $projectRoot 'deploy/web/index.html')).Hash.ToLowerInvariant()
if($frontendHash -ne $browser.frontendIndexSha256){throw 'The deployed frontend differs from the browser-tested build.'}
$weightHash=(Get-FileHash -LiteralPath (Join-Path $projectRoot '../PatchCraft（算法）/weights/RPTC.pth')).Hash.ToLowerInvariant()
if($model.modelVersion -ne ('rptc-sha256-'+$weightHash)){throw 'The actual weight file differs from the validated model.'}
$jarHash=(Get-FileHash -LiteralPath (Join-Path $projectRoot 'target/traffic-platform-0.1.0-SNAPSHOT.jar')).Hash.ToLowerInvariant()
Push-Location -LiteralPath (Join-Path $projectRoot 'deploy')
try {
    $runningHash=(& docker compose exec -T app sha256sum /app/app.jar).Split(' ')[0]
    if($LASTEXITCODE -ne 0 -or $runningHash -ne $jarHash){throw 'Running backend differs from the final tested Jar.'}
    & docker compose restart app
    if($LASTEXITCODE -ne 0){throw 'Final restart failed.'}
    & docker compose up -d --wait --wait-timeout 120 app nginx
    if($LASTEXITCODE -ne 0){throw 'Final restart did not restore readiness.'}
    $readiness=Invoke-RestMethod -Uri 'http://127.0.0.1:28090/actuator/health/readiness' -TimeoutSec 15
    if($readiness.status -ne 'UP'){throw 'Final readiness is not UP.'}
    $numbers=@()
    for($attempt=0;$attempt -lt 25;$attempt++){
        $rows=@(& docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -utraffic_app -N -B -e "SELECT COUNT(*) FROM traffic_platform.legacy_user_mapping; SELECT COUNT(*) FROM traffic_platform.legacy_report_archive; SELECT COUNT(*) FROM traffic_platform.ledger_drift WHERE active=TRUE; SELECT COUNT(*) FROM traffic_platform.maintenance_checkpoint WHERE last_success >= UTC_TIMESTAMP()-INTERVAL 3 MINUTE;"')
        if($LASTEXITCODE -ne 0){throw 'Cannot read dedicated final evidence.'}
        $numbers=@($rows | Where-Object {$_ -match '^\d+$'})
        if($numbers.Count -eq 4 -and [int]$numbers[3] -ge 5){break}
        Start-Sleep -Seconds 3
    }
    if($numbers.Count -ne 4 -or [int]$numbers[0] -ne $browser.legacyUserCount -or [int]$numbers[1] -ne $browser.legacyReportCount -or [int]$numbers[2] -ne 0 -or [int]$numbers[3] -lt 5){throw 'Archive counts, fresh maintenance or reconciliation evidence failed.'}
    $summary=[ordered]@{stage='P8';status='PASSED';recordedAt=[DateTimeOffset]::Now.ToString('o');javaTests=$backend.totals.tests;pythonProtocolTests=$backend.pythonProtocolTests;browserChecks=@($browser.checks).Count;browserPageErrors=$browser.runtimePageErrors;legacyUsers=[int]$numbers[0];legacyReports=[int]$numbers[1];activeLedgerDrifts=[int]$numbers[2];freshMaintenanceKinds=[int]$numbers[3];readiness=$readiness.status;restartRecovery='PASSED';jarSha256=$jarHash;frontendIndexSha256=$frontendHash;modelVersion=$model.modelVersion;entry='http://127.0.0.1:28090'}
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportRoot 'P8-final-summary.json') -Encoding utf8
    Write-Output 'Final P8 gate passed: tested artifacts match deployment, browser/model passed, archives preserved, no ledger drift and restart readiness restored.'
} finally { Pop-Location }
