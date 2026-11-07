param([string]$BaseUrl='http://127.0.0.1:28090')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$credentialPath=Join-Path $projectRoot 'deploy/.generated/demo-accounts.json'
if(-not(Test-Path -LiteralPath $credentialPath)){
    $accounts=@()
    foreach($entry in @(@('bootstrap','traffic.bootstrap'),@('manager','traffic.manager'),@('user','traffic.user'),@('reviewer','traffic.reviewer'),@('rechecker','traffic.rechecker'))){
        $accounts+=[ordered]@{kind=$entry[0];username=$entry[1];password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()}
    }
    $accounts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $credentialPath -Encoding utf8
}
$accounts=@(Get-Content -Raw -LiteralPath $credentialPath | ConvertFrom-Json)
$bootstrap=$accounts | Where-Object kind -eq 'bootstrap'
Push-Location -LiteralPath (Join-Path $projectRoot 'deploy')
try {
    $exists=& docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -utraffic_app -N -B traffic_platform -e "SELECT COUNT(*) FROM identity_user WHERE username=''traffic.bootstrap'';"'
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect the dedicated deployment identity table.'}
    if([int]$exists -eq 0){
        $classpath='D:/Project/java/.cache/maven/org/springframework/security/spring-security-crypto/7.1.2/spring-security-crypto-7.1.2.jar;D:/Project/java/.cache/maven/commons-logging/commons-logging/1.3.6/commons-logging-1.3.6.jar'
        $passwordHash=$bootstrap.password | & 'D:/Service/JDK21/bin/java.exe' -classpath $classpath (Join-Path $projectRoot 'tools/HashPassword.java')
        if($LASTEXITCODE -ne 0 -or $passwordHash -notmatch '^\$2[abxy]\$12\$[./A-Za-z0-9]{53}$'){throw 'BCrypt bootstrap hashing failed.'}
        $permissions=@('identity:user:manage','region:read','campaign:manage','report:submit','report:read','report:review','detection:manage','message:manage','review:claim','review:decide','review:recheck','review:manage','wallet:read','wallet:withdraw','reward:manage','withdrawal:manage','notification:read','operations:read')
        $sql="START TRANSACTION; INSERT INTO identity_user(username,password_hash,enabled,auth_version) VALUES('traffic.bootstrap','$passwordHash',TRUE,0); INSERT IGNORE INTO identity_role(code) VALUES('ADMIN');"
        foreach($permission in $permissions){$sql+="INSERT IGNORE INTO identity_permission(code) VALUES('$permission'); INSERT IGNORE INTO identity_role_permission(role_id,permission_id) SELECT r.id,p.id FROM identity_role r JOIN identity_permission p WHERE r.code='ADMIN' AND p.code='$permission';"}
        $sql+="INSERT INTO identity_user_role(user_id,role_id) SELECT u.id,r.id FROM identity_user u JOIN identity_role r WHERE u.username='traffic.bootstrap' AND r.code='ADMIN'; COMMIT;"
        $sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -utraffic_app --default-character-set=utf8mb4 traffic_platform'
        if($LASTEXITCODE -ne 0){throw 'Explicit bootstrap failed. Existing users were not reset.'}
    }
    $loginBody=@{username=$bootstrap.username;password=$bootstrap.password}|ConvertTo-Json
    $auth=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body $loginBody
    $headers=@{Authorization='Bearer '+$auth.accessToken}
    function Invoke-TrafficApi([string]$Method,[string]$Path,$Body){
        $arguments=@{Method=$Method;Uri="$BaseUrl/api/v1$Path";Headers=$headers;ContentType='application/json'}
        if($null -ne $Body){$arguments.Body=$Body|ConvertTo-Json -Depth 8}
        return Invoke-RestMethod @arguments
    }
    $catalog=Invoke-TrafficApi Get '/identity/catalog' $null
    if(-not($catalog.regions | Where-Object code -eq 'NORTH')){Invoke-TrafficApi Post '/identity/regions' @{code='NORTH';name='北校区'} | Out-Null}
    $roles=@{
        CITIZEN=@('report:submit','report:read','wallet:read','wallet:withdraw','notification:read')
        REVIEWER=@('report:review','review:claim','review:decide','notification:read')
        RECHECKER=@('report:review','review:claim','review:decide','review:recheck','notification:read')
    }
    foreach($role in $roles.GetEnumerator()){if(-not($catalog.roles | Where-Object code -eq $role.Key)){Invoke-TrafficApi Post '/identity/roles' @{code=$role.Key;permissionCodes=@($role.Value)} | Out-Null}}
    $catalog=Invoke-TrafficApi Get '/identity/catalog' $null
    $existing=(Invoke-TrafficApi Get '/identity/users?size=100' $null).items
    foreach($account in $accounts | Where-Object kind -ne 'bootstrap'){
        if($existing | Where-Object username -eq $account.username){continue}
        $roleCode=switch($account.kind){manager{'ADMIN'} user{'CITIZEN'} reviewer{'REVIEWER'} rechecker{'RECHECKER'}}
        $roleId=($catalog.roles | Where-Object code -eq $roleCode).id
        Invoke-TrafficApi Post '/identity/users' @{username=$account.username;initialPassword=$account.password;roleIds=@($roleId);regionCodes=@('NORTH')} | Out-Null
    }
    foreach($account in $accounts){$null=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{username=$account.username;password=$account.password}|ConvertTo-Json)}
    Write-Output 'Five explicit demo accounts verified. Credentials remain in ignored deploy/.generated/demo-accounts.json; no existing passwords were overwritten.'
} finally { Pop-Location }
