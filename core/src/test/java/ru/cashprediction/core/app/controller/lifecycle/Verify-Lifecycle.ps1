# Изолированная компиляция ядра и тестов без Maven и общих каталогов target.
param([string]$JunitJar = 'C:\Users\Oscar\.m2\repository\org\junit\platform\junit-platform-console-standalone\1.14.4\junit-platform-console-standalone-1.14.4.jar')
$ErrorActionPreference = 'Stop'
$lifecycleRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../../../../../../..')).Path
$lifecycleBuild = Join-Path ([System.IO.Path]::GetTempPath()) ('cashprediction-lifecycle-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $lifecycleBuild | Out-Null
Push-Location $lifecycleRoot
try {
    $lifecycleSources = @(rg --files core/src/main/java -g '*.java' | Where-Object { $_ -notmatch 'module-info.java$' })
    $lifecycleTests = @(rg --files core/src/test/java -g '*.java')
    & javac -encoding UTF-8 -cp $JunitJar -d $lifecycleBuild @lifecycleSources @lifecycleTests
    if ($LASTEXITCODE -ne 0) { throw 'Isolated compilation failed' }
    & java '-Dcashprediction.ui.strictText=true' -jar $JunitJar execute --class-path "$lifecycleBuild;core/src/main/resources;core/src/test/resources" --select-package ru.cashprediction.core.app.controller --select-package ru.cashprediction.core.app.file --select-package ru.cashprediction.core.app.session.startup --details summary --disable-ansi-colors
    $lifecycleResult = $LASTEXITCODE
} finally {
    Pop-Location
    $lifecycleResolved = (Resolve-Path -LiteralPath $lifecycleBuild).Path
    $lifecycleTemp = [System.IO.Path]::GetTempPath().TrimEnd('\') + '\'
    if ($lifecycleResolved.StartsWith($lifecycleTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
            [System.IO.Path]::GetFileName($lifecycleResolved).StartsWith('cashprediction-lifecycle-')) {
        Remove-Item -LiteralPath $lifecycleResolved -Recurse -Force
    }
}
exit $lifecycleResult
