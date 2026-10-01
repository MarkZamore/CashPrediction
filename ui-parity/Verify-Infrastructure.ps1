# Изолированная проверка S2 без Maven и без записи в target других модулей.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskOutput = Join-Path $PSScriptRoot ('target/parity/compile-' + [guid]::NewGuid().ToString())
$taskMain = Join-Path $taskOutput 'core'
$taskTests = Join-Path $taskOutput 'tests'
$taskJunit = 'C:/Users/Oscar/.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
New-Item -ItemType Directory -Path $taskMain, $taskTests -Force | Out-Null
$taskCoreSources = @(Get-ChildItem (Join-Path $taskRoot 'core/src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
# Дескриптор нужен фиктивному клиенту, который проверяет настоящий модульный запуск S0.
& javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d $taskMain $taskCoreSources
if ($LASTEXITCODE -ne 0) { throw 'Isolated core compilation failed' }
Copy-Item (Join-Path $taskRoot 'core/src/main/resources/*') $taskMain -Recurse -Force
if (Test-Path (Join-Path $taskRoot 'core/src/main/resources-filtered')) {
    Copy-Item (Join-Path $taskRoot 'core/src/main/resources-filtered/*') $taskMain -Recurse -Force
}
$taskParitySources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d $taskTests -cp "$taskMain;$taskJunit" $taskParitySources
if ($LASTEXITCODE -ne 0) { throw 'Isolated ui-parity compilation failed' }
Copy-Item (Join-Path $PSScriptRoot 'src/test/resources/*') $taskTests -Recurse -Force
& java -XX:-UsePerfData '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Duser.language=ru' '-Duser.country=RU' `
    '-Dcashprediction.ui.strictText=true' "-Dparity.reactor.root=$taskRoot" `
    -jar $taskJunit execute --disable-banner --details=summary -cp "$taskMain;$taskTests" `
    --scan-classpath '--include-classname=.*(Test|ParityPipelineIT)$'
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure tests failed' }
Write-Output "Verified isolated classes: $taskOutput"
