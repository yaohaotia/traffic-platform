$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$frontend=[IO.Path]::GetFullPath((Join-Path $projectRoot '../SpringBoot-Vue（前端）'))
$previousNodeOptions=$env:NODE_OPTIONS
$previousNodeEnvironment=$env:NODE_ENV
$env:NODE_ENV='production'
if($env:NODE_OPTIONS -notmatch '--openssl-legacy-provider'){$env:NODE_OPTIONS=($env:NODE_OPTIONS+' --openssl-legacy-provider').Trim()}
Push-Location -LiteralPath $frontend
try { & npm.cmd run build:traffic; if($LASTEXITCODE -ne 0){throw 'Traffic frontend build failed.'} }
finally { Pop-Location; $env:NODE_OPTIONS=$previousNodeOptions; $env:NODE_ENV=$previousNodeEnvironment }
