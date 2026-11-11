param()
$ErrorActionPreference='Stop'
$deploymentRoot=$PSScriptRoot
$environmentPath=Join-Path $deploymentRoot '.env'
if(Test-Path -LiteralPath $environmentPath){throw 'Deployment environment already exists. Existing credentials and volumes will not be replaced.'}
function New-TrafficSecret { return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant() }
$trafficEnvironment=[ordered]@{}
foreach($name in @('MYSQL_ROOT_PASSWORD','TRAFFIC_DB_PASSWORD','TRAFFIC_REDIS_PASSWORD','TRAFFIC_RABBIT_PASSWORD','TRAFFIC_JWT_SECRET','TRAFFIC_JOB_ACCESS_TOKEN')){$trafficEnvironment[$name]=New-TrafficSecret}
# XXL-JOB 3.4.2's browser login limits passwords to 20 characters. Fifteen
# random bytes give a 20-character Base64url password with 120 bits of entropy.
$trafficEnvironment['TRAFFIC_JOB_ADMIN_PASSWORD']=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(15)).Replace('+','-').Replace('/','_')
$trafficEnvironment['JAVA_IMAGE']='eclipse-temurin:21-jre'
$trafficEnvironment['NGINX_IMAGE']='nginx:stable-alpine'
$trafficEnvironment['XXL_IMAGE']='xuxueli/xxl-job-admin:3.4.2'
$upstream=Join-Path $deploymentRoot 'vendor/xxl-job-3.4.2.sql'
if((Get-FileHash -LiteralPath $upstream -Algorithm SHA256).Hash -ne '6FB3B764BD63A66E63F23C4CB6254A5CB277FA0DB23BEC14586935699FB60483'){throw 'The fixed upstream XXL-JOB schema has changed; review before initialization.'}
$schema=Get-Content -Raw -LiteralPath $upstream
$marker=$schema.IndexOf('## —————————————————————— for default data')
if($marker -lt 0){throw 'The fixed upstream schema layout was not recognized.'}
$schema=$schema.Substring(0,$marker)
$schema+="`nGRANT ALL PRIVILEGES ON xxl_job.* TO 'traffic_app'@'%';`n"
$schema+="INSERT INTO xxl_job_group(id,app_name,title,address_type,update_time) VALUES(10,'traffic-platform','Traffic platform maintenance',0,NOW());`n"
$passwordBytes=[Text.Encoding]::UTF8.GetBytes($trafficEnvironment['TRAFFIC_JOB_ADMIN_PASSWORD'])
$jobPasswordHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($passwordBytes)).ToLowerInvariant()
$schema+="INSERT INTO xxl_job_user(id,username,password,role) VALUES(1,'traffic.admin','$jobPasswordHash',1);`n"
$schema+="INSERT INTO xxl_job_lock(lock_name) VALUES('schedule_lock');`n"
$jobId=10
foreach($handler in @('trafficOutboxJob','trafficDetectionRecoveryJob','trafficReviewRecoveryJob','trafficRewardRecoveryJob','trafficLedgerReconciliationJob')){
    $cron=if($handler -eq 'trafficLedgerReconciliationJob'){'0 * * * * ?'}else{'0/5 * * * * ?'}
    $schema+="INSERT INTO xxl_job_info(id,job_group,job_desc,add_time,update_time,author,schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_updatetime,trigger_status) VALUES($jobId,10,'$handler',NOW(),NOW(),'traffic-platform','CRON','$cron','DO_NOTHING','ROUND','$handler','','DISCARD_LATER',30,1,'BEAN',NOW(),1);`n"
    $jobId++
}
New-Item -ItemType Directory -Path (Join-Path $deploymentRoot '.generated/mysql') -Force | Out-Null
$schema | Set-Content -LiteralPath (Join-Path $deploymentRoot '.generated/mysql/002_xxl_job.sql') -Encoding utf8
$trafficEnvironment.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath $environmentPath -Encoding ascii
New-Item -ItemType Directory -Path (Join-Path $deploymentRoot 'web') -Force | Out-Null
'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>交通线索平台</title><p>后端部署验证入口；前端将在 P8 验收后装配。</p></html>' | Set-Content -LiteralPath (Join-Path $deploymentRoot 'web/index.html') -Encoding utf8
Write-Output 'Isolated deployment configuration generated. Secrets are stored only in the ignored deploy/.env file.'
