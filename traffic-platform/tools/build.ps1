param(
    [string[]]$Goals = @('verify'),
    [string]$JavaHomePath = 'D:/Service/JDK21',
    [string]$MavenHomePath = 'D:/Service/apache-maven-3.6.3-bin/apache-maven-3.6.3'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCommand = Join-Path $JavaHomePath 'bin/java.exe'
$launcher = Get-ChildItem -LiteralPath (Join-Path $MavenHomePath 'boot') -Filter 'plexus-classworlds-*.jar' | Select-Object -First 1
if (-not (Test-Path -LiteralPath $javaCommand) -or -not $launcher) { throw 'JDK 21 or Maven launcher was not found; pass explicit tool paths.' }
$workspaceCache = [IO.Path]::GetFullPath((Join-Path $projectRoot '../../.cache/maven'))
$arguments = @(
    "-Dmaven.home=$MavenHomePath",
    "-Dclassworlds.conf=$MavenHomePath/bin/m2.conf",
    "-Dmaven.multiModuleProjectDirectory=$projectRoot",
    '-Dfile.encoding=UTF-8',
    '-classpath', $launcher.FullName,
    'org.codehaus.plexus.classworlds.launcher.Launcher',
    '-B', '-ntp', '-s', "$PSScriptRoot/settings.xml",
    "-Dmaven.repo.local=$workspaceCache", '-f', "$projectRoot/pom.xml"
)
& $javaCommand @arguments @Goals
exit $LASTEXITCODE
