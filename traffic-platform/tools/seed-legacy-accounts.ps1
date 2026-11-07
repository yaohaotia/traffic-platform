param([string]$BaseUrl='http://127.0.0.1:28090',[string]$Archive='D:/Project/java/car/docs/migration/private/legacy-full-export.json')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$demo=Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'deploy/.generated/demo-accounts.json') | ConvertFrom-Json
$operator=$demo | Where-Object kind -eq 'bootstrap'
$auth=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{username=$operator.username;password=$operator.password}|ConvertTo-Json)
$headers=@{Authorization='Bearer '+$auth.accessToken}
$bundle=Get-Content -Raw -LiteralPath $Archive | ConvertFrom-Json
$catalog=Invoke-RestMethod -Uri "$BaseUrl/api/v1/identity/catalog" -Headers $headers
$roleId=($catalog.roles | Where-Object code -eq 'CITIZEN').id
if(-not $roleId){throw 'Run seed-demo.ps1 first.'}
$privatePath=Join-Path $projectRoot 'deploy/.generated/legacy-accounts.json'
if(Test-Path -LiteralPath $privatePath){$accounts=@(Get-Content -Raw -LiteralPath $privatePath | ConvertFrom-Json)}else{
    $accounts=@($bundle.users | ForEach-Object {[pscustomobject]@{legacyId=$_.legacyId;username=('legacy.'+$_.legacyId);password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant();sourceEnabled=[bool]$_.sourceEnabled;userId=$null}})
    $accounts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $privatePath -Encoding utf8
}
$existing=(Invoke-RestMethod -Uri "$BaseUrl/api/v1/identity/users?size=100" -Headers $headers).items
foreach($account in $accounts){
    $found=$existing | Where-Object username -eq $account.username
    if($found){if(-not $account.userId -or [long]$account.userId -ne [long]$found.id){throw 'An unmanaged legacy target username already exists; no password will be overwritten.'}}
    else {
        $created=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/identity/users" -Headers $headers -ContentType 'application/json' -Body (@{username=$account.username;initialPassword=$account.password;roleIds=@($roleId);regionCodes=@('NORTH')}|ConvertTo-Json -Depth 5)
        $account.userId=$created.id
        $accounts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $privatePath -Encoding utf8
    }
    if(-not $account.sourceEnabled){
        $null=Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/v1/identity/users/$($account.userId)/enabled" -Headers $headers -ContentType 'application/json' -Body '{"enabled":false}'
    }
}
$mappings=[ordered]@{};foreach($account in $accounts){$mappings[$account.legacyId]=[long]$account.userId}
[ordered]@{archive=$Archive;userMappings=$mappings;expectedUsers=@($bundle.users).Count;expectedReports=@($bundle.reports).Count} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $projectRoot 'deploy/.generated/legacy-mapping.json') -Encoding utf8
Write-Output ('Explicit target accounts prepared: '+$accounts.Count+'. No source roles/passwords/balances imported. Archive import still requires preview and confirmation.')
