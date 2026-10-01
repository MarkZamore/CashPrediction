# Изолированная компиляция и запуск дополнительных тестов без Maven и общих target.
param([string]$JunitJar = 'C:\Users\Oscar\.m2\repository\org\junit\platform\junit-platform-console-standalone\1.14.4\junit-platform-console-standalone-1.14.4.jar')
$ErrorActionPreference = 'Stop'
$sidecarRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../../../..')).Path
$sidecarBuild = Join-Path ([System.IO.Path]::GetTempPath()) ('cashprediction-controller-extra-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $sidecarBuild | Out-Null
Push-Location $sidecarRoot
try {
    # Пакеты дампов, протокола и самотестов не участвуют в этих сценариях контроллера.
    $sidecarSources = @(rg --files core/src/main/java -g '*.java' | Where-Object {
        $_ -notmatch 'module-info.java$' -and $_ -notmatch '[/\\]ui[/\\](dump|json|selftest)[/\\]'
    })
    $sidecarTests = @(rg --files core/src/test/java/ru/cashprediction/core/app/fake core/src/test/java/ru/cashprediction/core/app/controller/extra -g '*.java' | Where-Object { $_ -notmatch 'FakeUiPortTest.java$' })
    & javac -encoding UTF-8 -cp $JunitJar -d $sidecarBuild @sidecarSources @sidecarTests
    if ($LASTEXITCODE -ne 0) { throw 'Isolated compilation failed' }
    & java '-Dcashprediction.ui.strictText=true' -jar $JunitJar execute --class-path "$sidecarBuild;core/src/main/resources" --select-package ru.cashprediction.core.app.controller.extra --details tree --disable-ansi-colors
    $sidecarResult = $LASTEXITCODE
} finally {
    Pop-Location
    # Удаляется только созданный здесь каталог внутри системной временной папки.
    $sidecarResolved = (Resolve-Path -LiteralPath $sidecarBuild).Path
    $sidecarTemp = [System.IO.Path]::GetTempPath().TrimEnd('\') + '\'
    if ($sidecarResolved.StartsWith($sidecarTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
            [System.IO.Path]::GetFileName($sidecarResolved).StartsWith('cashprediction-controller-extra-')) {
        Remove-Item -LiteralPath $sidecarResolved -Recurse -Force
    }
}
exit $sidecarResult
