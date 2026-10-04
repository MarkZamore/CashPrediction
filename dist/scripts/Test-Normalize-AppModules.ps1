<# .SYNOPSIS Проверки нормализации на изолированных синтетических файлах, не запуск exe. #>
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('CashPrediction-module-layout-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($testRoot)
try {
    $image=Join-Path $testRoot 'image'
    $app=Join-Path $image 'app'
    $mods=Join-Path $app 'mods'
    [void][IO.Directory]::CreateDirectory($mods)
    foreach ($name in @('core','ui-fx','ui-swing','web')) {
        [IO.File]::WriteAllText((Join-Path $mods ('cashprediction-'+$name+'-1.0.0.jar')),$name)
    }
    $cfg="[Application]`r`napp.mainmodule=ru.example/ru.example.Main`r`n[JavaOptions]`r`njava-options=--module-path`r`njava-options=`$APPDIR\mods`r`n"
    foreach ($name in @('CashPrediction','CashPrediction-Swing','CashPrediction-Web')) {
        [IO.File]::WriteAllText((Join-Path $app ($name+'.cfg')),$cfg)
    }
    & (Join-Path $PSScriptRoot 'Normalize-AppModules.ps1') -ImageDir $image
    if (Test-Path -LiteralPath $mods) {throw 'TEST_DUPLICATE_DIRECTORY'}
    if (@(Get-ChildItem -LiteralPath $app -Filter '*.jar').Count -ne 4) {throw 'TEST_JAR_COUNT'}
    foreach ($name in @('core','ui-fx','ui-swing','web')) {
        if ([IO.File]::ReadAllText((Join-Path $app ('cashprediction-'+$name+'-1.0.0.jar'))) -cne $name) {throw 'TEST_CONTENT'}
    }
    foreach ($name in @('CashPrediction','CashPrediction-Swing','CashPrediction-Web')) {
        $actual=[IO.File]::ReadAllText((Join-Path $app ($name+'.cfg')))
        if ($actual -cne $cfg.Replace('java-options=$APPDIR\mods','java-options=$APPDIR')) {throw 'TEST_CFG'}
    }
    Write-Output 'Normalize-AppModules: 8 layout/content assertions PASS (synthetic only)'
} finally {
    # Единственный точный путь создан этим тестом, расположен непосредственно в Temp.
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $expected=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\CashPrediction-module-layout-'
    if (-not $resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase)) {throw 'TEST_CLEANUP_PATH'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
