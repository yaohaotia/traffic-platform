param()
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$deploymentRoot=Join-Path $projectRoot 'deploy'
$reportRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '../docs/test-reports'))
if(-not(Test-Path -LiteralPath (Join-Path $deploymentRoot '.env'))){throw 'Run deploy/initialize.ps1 before the deployment gate.'}
Push-Location -LiteralPath $deploymentRoot
try {
    & docker compose up -d --build --wait --wait-timeout 180
    if($LASTEXITCODE -ne 0){throw 'Isolated deployment did not become ready.'}
    & docker compose exec -T nginx nginx -t
    if($LASTEXITCODE -ne 0){throw 'Nginx configuration validation failed.'}
    $readiness=Invoke-RestMethod -Uri 'http://127.0.0.1:28090/actuator/health/readiness' -TimeoutSec 15
    if($readiness.status -ne 'UP'){throw 'Reverse-proxied readiness was not UP.'}
    $info=Invoke-RestMethod -Uri 'http://127.0.0.1:28090/api/v1/system/info' -TimeoutSec 15
    if($info.version -ne '0.1.0-SNAPSHOT'){throw 'Reverse-proxied version was unexpected.'}
    $anonymous=Invoke-WebRequest -Uri 'http://127.0.0.1:28090/api/v1/wallet' -SkipHttpErrorCheck -TimeoutSec 15
    if([int]$anonymous.StatusCode -ne 401){throw 'The reverse proxy did not preserve authentication requirements.'}
    & "$PSScriptRoot/test-xxl-login.ps1"
    $maintenanceJobs=0;$actualCallbacks=0;$registered=0
    for($attempt=0;$attempt -lt 25;$attempt++){
        $counts=@(& docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -utraffic_app -N -B -e "SELECT COUNT(*) FROM traffic_platform.maintenance_checkpoint WHERE last_success IS NOT NULL; SELECT COUNT(DISTINCT executor_handler) FROM xxl_job.xxl_job_log WHERE job_group=10 AND handle_code=200; SELECT COUNT(*) FROM xxl_job.xxl_job_registry WHERE registry_key=''traffic-platform'';"')
        if($LASTEXITCODE -ne 0){throw 'Cannot read dedicated deployment maintenance evidence.'}
        $numbers=@($counts | Where-Object { $_ -match '^\d+$' })
        if($numbers.Count -eq 3){$maintenanceJobs=[int]$numbers[0];$actualCallbacks=[int]$numbers[1];$registered=[int]$numbers[2]}
        if($maintenanceJobs -ge 5 -and $actualCallbacks -ge 5 -and $registered -ge 1){break}
        Start-Sleep -Seconds 3
    }
    if($maintenanceJobs -lt 5 -or $actualCallbacks -lt 5 -or $registered -lt 1){throw 'The real XXL admin did not prove registration and all five successful native handlers.'}
    & docker compose restart app
    if($LASTEXITCODE -ne 0){throw 'Application restart failed.'}
    & docker compose up -d --wait --wait-timeout 120 app nginx
    if($LASTEXITCODE -ne 0){throw 'Application did not recover readiness after restart.'}
    $restored=Invoke-RestMethod -Uri 'http://127.0.0.1:28090/actuator/health/readiness' -TimeoutSec 15
    if($restored.status -ne 'UP'){throw 'Restart recovery did not restore readiness.'}
    $summary=[ordered]@{stage='P7';status='PASSED';recordedAt=[DateTimeOffset]::Now.ToString('o');deployment='traffic-platform-deploy';reverseProxy='Nginx actual HTTP';readiness=$restored.status;anonymousProtectedHttpStatus=401;registeredExecutors=$registered;successfulMaintenanceKinds=$maintenanceJobs;successfulRealAdminCallbackKinds=$actualCallbacks;applicationRestart='PASSED';jarSha256=(Get-FileHash -LiteralPath (Join-Path $projectRoot 'target/traffic-platform-0.1.0-SNAPSHOT.jar')).Hash}
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $reportRoot 'P7-deployment-summary.json') -Encoding utf8
    Write-Output 'P7 isolated deployment passed: Nginx, dependency readiness, authentication, real XXL registration/five handlers and restart recovery.'
} finally { Pop-Location }
