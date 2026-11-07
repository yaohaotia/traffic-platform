param([int]$Port=5001)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$modelRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '../PatchCraft（算法）'))
$modelPackages=[IO.Path]::GetFullPath((Join-Path $projectRoot '../../.cache/python-model'))
if(-not(Test-Path -LiteralPath (Join-Path $modelPackages 'torch'))){throw 'Install the fixed service/requirements-cpu-windows.txt with the specified Anaconda interpreter into .cache/python-model first.'}
$env:PYTHONPATH=$modelPackages
$env:PYTHONIOENCODING='utf-8'
Push-Location -LiteralPath $modelRoot
try { & 'D:/soft/Anaconda/python.exe' -u -m service.runtime --host 127.0.0.1 --port $Port; exit $LASTEXITCODE }
finally { Pop-Location }
