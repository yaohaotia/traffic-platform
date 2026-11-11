param([string]$BaseUrl='http://127.0.0.1:29090')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$environmentPath=Join-Path $projectRoot 'deploy/.env'
if(-not(Test-Path -LiteralPath $environmentPath)){throw 'Run deploy/initialize.ps1 before testing the job admin login.'}
$entries=@(Get-Content -LiteralPath $environmentPath | Where-Object {$_ -match '^TRAFFIC_JOB_ADMIN_PASSWORD='})
if($entries.Count -ne 1){throw 'The deployment must contain exactly one job admin password.'}
$password=($entries[0] -split '=',2)[1]
$loginPage=Invoke-WebRequest -Uri "$BaseUrl/auth/login" -TimeoutSec 15
$inputTag=[regex]::Match($loginPage.Content,'<input\b[^>]*\bname="password"[^>]*>','IgnoreCase').Value
$limit=[regex]::Match($inputTag,'\bmaxlength="(\d+)"','IgnoreCase')
if(-not $limit.Success){throw 'The actual job admin login password limit could not be verified.'}
$maximum=[int]$limit.Groups[1].Value
if($password.Length -lt 4 -or $password.Length -gt $maximum){throw "The generated job admin password violates the actual browser limit ($maximum); API-only login is insufficient."}
$login=Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/doLogin" -ContentType 'application/x-www-form-urlencoded' -Body @{userName='traffic.admin';password=$password;ifRemember='off'} -SessionVariable jobSession -TimeoutSec 15
if($login.code -ne 200){throw 'The configured job admin credentials did not authenticate.'}
$dashboard=Invoke-WebRequest -Uri $BaseUrl -WebSession $jobSession -TimeoutSec 15
$finalPath=$dashboard.BaseResponse.RequestMessage.RequestUri.AbsolutePath
if($finalPath -match '/auth/login' -or $dashboard.Content -notmatch 'jobinfo|joblog'){throw 'An authenticated job admin dashboard was not reached.'}
$summary=[ordered]@{status='PASSED';recordedAt=[DateTimeOffset]::Now.ToString('o');username='traffic.admin';actualBrowserPasswordMaximum=$maximum;configuredPasswordLength=$password.Length;loginHttpCode=$login.code;authenticatedDashboardHttpStatus=[int]$dashboard.StatusCode}
$reportRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '../docs/test-reports'))
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportRoot 'XXL-login-summary.json') -Encoding utf8
Write-Output 'XXL-JOB login passed: configured password fits the real browser form; authentication and protected dashboard succeeded.'
