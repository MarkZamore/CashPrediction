<#
.SYNOPSIS
Проверяет Test-Icon-Source.ps1 на независимых искусственных исходниках.
.DESCRIPTION
Не читает дерево приложения, не создаёт архив, не запускает Maven, Java или GUI.
Каждый случай получает отдельную папку во временном каталоге; результаты сохраняются.
Минимальные PNG/ICO проверяют заголовки и совпадение байтов, а не декодирование графики.
.EXAMPLE
./dist/scripts/Test-Icon-SourceFixtures.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$checker = Join-Path $PSScriptRoot 'Test-Icon-Source.ps1'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('CashPrediction-icon-source-' + [guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($fixtureRoot)
$resource = 'core/src/main/resources/ru/cashprediction/core/ui/icons'
$catalogPath = 'core/src/main/java/ru/cashprediction/core/ui/token/UiIcons.java'
$generatorPath = 'dist/icons/GenerateUiIcons.java'
$shared = '../core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico'
$colors = @('ACCENT', 'WHATIF', 'EXPENSE', 'INCOME', 'TEXT_PRIMARY', 'TEXT_MUTED', 'WARN', 'TOOLTIP_TEXT', 'TEXT_PAST')
$colorValues = @('1F6FEB', '8250DF', 'B3261E', '1B7F3B', '1F2328', '57606A', '8A5300', 'FFFFFF', '8A8F98')
$mapping = [ordered]@{
    '↶'='undo'; '↷'='redo'; '▾'='chevron-down'; '▸'='chevron-right'; '✕'='close';
    '✓'='check'; '✗'='close'; '●'='dot'; '◀'='previous'; '▶'='next'; '▦'='calendar';
    '↑'='up'; '✎'='edit'; '→'='arrow-right'; '⇄'='swap'; '≡'='list'; 'Δ'='delta';
    '₽'='ruble'; '⚙'='settings'; '↻'='refresh'; '◎'='target'; '⇩'='download';
    '⟲'='restore'; 'ℹ'='info'; '⚠'='warning'; '✖'='error'; '?'='question';
    '‹'='chevron-left'; '›'='chevron-right'; 'folder'='folder'; 'search'='search'
}

# Пишет только искусственные входы внутри текущего стенда.
function Set-Fixture([string] $Relative, [string] $Text) {
    $path = Join-Path $fixture $Relative
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path, $Text, [Text.UTF8Encoding]::new($false))
}

# Изменяет один искусственный исходник для отрицательного случая.
function Replace-Fixture([string] $Relative, [string] $Before, [string] $After) {
    $path = Join-Path $fixture $Relative
    $text = [IO.File]::ReadAllText($path)
    if (-not $text.Contains($Before)) { throw "Fixture replacement not found: $Relative / $Before" }
    Set-Fixture $Relative ($text.Replace($Before, $After))
}

# Создаёт полный положительный стенд без копирования меняющихся исходников.
function New-Fixture {
    $catalog = '"/ru/cashprediction/core/ui/icons/"; "/app/icons/"; png(String key, ColorToken color);' + "`n"
    foreach ($entry in $mapping.GetEnumerator()) {
        $catalog += 'result.put("' + $entry.Key + '", WEB_ROOT + "' + $entry.Value + '.png");' + "`n"
    }
    foreach ($basename in @($mapping.Values | Sort-Object -Unique)) {
        foreach ($color in $colors) {
            $alias = "$basename-$($color.ToLowerInvariant())"
            $catalog += 'result.put("' + $alias + '", WEB_ROOT + "' + $alias + '.png");' + "`n"
        }
    }
    $catalog += $colors -join ' '
    Set-Fixture $catalogPath $catalog
    Set-Fixture $generatorPath ('"' + $resource + '"; ' + (($colors | ForEach-Object { 'ColorToken.' + $_ }) -join ' ') +
        '; ' + (($mapping.Values | Sort-Object -Unique | ForEach-Object { '"' + $_ + '"' }) -join ' '))
    $enum = ''
    for ($i = 0; $i -lt $colors.Count; $i++) { $enum += $colors[$i] + '("fixture", "#' + $colorValues[$i] + '");' }
    Set-Fixture 'core/src/main/java/ru/cashprediction/core/ui/token/ColorToken.java' $enum
    foreach ($relative in @('ui-fx/src/main/java/ru/cashprediction/fx/ui/FxIcons.java',
            'ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingIcons.java')) {
        Set-Fixture $relative 'UiIcons.png(key, color); UiIcons.applicationPng();'
    }
    Set-Fixture 'web/src/main/java/ru/cashprediction/web/StaticHandler.java' 'UiIcons.manifest(); UiIcons.resource(filename); UiIcons.applicationPng(); "/app/icons/";'
    Set-Fixture 'web/src/main/resources/web/app/icon.js' "import {icons} from './icons.js'; image.src = icons[key];"
    Set-Fixture 'dist/pom.xml' (@'
<project xmlns="http://maven.apache.org/POM/4.0.0"><build><plugins><plugin><executions><execution><id>jpackage</id><configuration><workingDirectory>${project.basedir}</workingDirectory><arguments>
<argument>--icon</argument><argument>ICON</argument>
<argument>--add-launcher</argument><argument>CashPrediction-Swing=launchers/swing.properties</argument>
<argument>--add-launcher</argument><argument>CashPrediction-Web=launchers/web.properties</argument>
</arguments></configuration></execution></executions></plugin></plugins></build></project>
'@).Replace('ICON', $shared)
    foreach ($launcher in @('swing', 'web')) { Set-Fixture "dist/launchers/$launcher.properties" "icon=$shared" }
    $null = [IO.Directory]::CreateDirectory((Join-Path $fixture $resource))
    [byte[]] $png = New-Object byte[] 33
    [Convert]::FromBase64String('iVBORw0KGgo=').CopyTo($png, 0)
    [Text.Encoding]::ASCII.GetBytes('IHDR').CopyTo($png, 12)
    [Convert]::FromBase64String('AAABAAAAAQA=').CopyTo($png, 16)
    [IO.File]::WriteAllBytes((Join-Path $fixture "$resource/application.png"), $png)
    [byte[]] $ico = New-Object byte[] (22 + $png.Length)
    $ico[2] = 1; $ico[4] = 1
    [BitConverter]::GetBytes([uint32]$png.Length).CopyTo($ico, 14)
    [BitConverter]::GetBytes([uint32]22).CopyTo($ico, 18)
    $png.CopyTo($ico, 22)
    [IO.File]::WriteAllBytes((Join-Path $fixture "$resource/application.ico"), $ico)
    foreach ($basename in @($mapping.Values | Sort-Object -Unique)) {
        [IO.File]::WriteAllBytes((Join-Path $fixture "$resource/$basename.png"), $png)
        foreach ($color in $colors) {
            [IO.File]::WriteAllBytes((Join-Path $fixture "$resource/$basename-$($color.ToLowerInvariant()).png"), $png)
        }
    }
}

# Проверяет точную причину отказа; ошибки самого стенда не принимаются за успех.
function Test-Fixture([string] $Name, [scriptblock] $Mutation, [string] $Expected = '') {
    $script:fixture = Join-Path $fixtureRoot $Name
    New-Fixture
    & $Mutation
    $failure = ''
    try { & $checker -SourceRoot $fixture | Out-Null } catch { $failure = $_.Exception.Message }
    if ($Expected.Length -eq 0) {
        if ($failure) { throw "Positive fixture failed: $Name / $failure" }
    } elseif (-not $failure.Contains($Expected)) {
        throw "Expected '$Expected': $Name / actual '$failure'"
    }
    Write-Output "PASS: $Name"
}

Test-Fixture 'positive-explicit-aliases' {}
Test-Fixture 'positive-derived-aliases' {
    $path = Join-Path $fixture $catalogPath
    $text = [IO.File]::ReadAllText($path)
    $text = [regex]::Replace($text, 'result\.put\("[a-z0-9-]+-(?:accent|whatif|expense|income|text_primary|text_muted|warn|tooltip_text|text_past)",[^\r\n]+', '')
    Set-Fixture $catalogPath ($text + "`n" + 'String alias = basename + "-" + token.name().toLowerCase(Locale.ROOT); result.put(alias, WEB_ROOT + alias + ".png");')
}
Test-Fixture 'client-copy' { Set-Fixture 'ui-fx/src/main/resources/nested/copy.PNG' 'copy' } 'Client/dist icon copies:'
Test-Fixture 'dist-copy' { Set-Fixture 'dist/icons/copy.svg' 'copy' } 'Client/dist icon copies:'
Test-Fixture 'missing-overload' { Replace-Fixture $catalogPath 'ColorToken color' 'Object color' } 'Missing UiIcons.png(String, ColorToken).'
Test-Fixture 'retained-glyph' { Replace-Fixture $catalogPath 'result.put("↶",' 'result.put("other",' } 'Missing retained icon key: ↶'
Test-Fixture 'changed-default' { Replace-Fixture $catalogPath 'result.put("↶", WEB_ROOT + "undo.png")' 'result.put("↶", WEB_ROOT + "redo.png")' } 'Changed default glyph mapping: ↶'
Test-Fixture 'generator-missing-icon' { Replace-Fixture $generatorPath '"undo"' '"other"' } 'Missing generator icon: undo'
Test-Fixture 'generator-output' { Replace-Fixture $generatorPath $resource 'elsewhere/icons' } 'Generator must default'
Test-Fixture 'generator-copy' { Set-Fixture $generatorPath ('"' + $resource + '"; ui-fx/src/main ' + ($colors -join ' ')) } 'Generator must not write client copies.'
Test-Fixture 'missing-color' { Replace-Fixture $generatorPath 'WHATIF' 'OTHER' } 'Missing context color declaration: WHATIF'
Test-Fixture 'wrong-color-value' { Replace-Fixture $generatorPath 'ColorToken.WARN' 'WARN 0x123456' } 'Generator must use ColorToken color: WARN'
Test-Fixture 'comment-only-color' { Replace-Fixture $generatorPath 'ColorToken.TOOLTIP_TEXT' '/* ColorToken.TOOLTIP_TEXT */' } 'Missing context color declaration: TOOLTIP_TEXT'
Test-Fixture 'invalid-variant' { Set-Fixture "$resource/undo-accent.png" 'broken' } 'Invalid shared context PNG: undo-accent.png'
Test-Fixture 'absent-variant' { Move-Item -LiteralPath (Join-Path $fixture "$resource/undo-whatif.png") -Destination (Join-Path $fixture "$resource/undo-whatif.saved") } 'Missing shared context PNG: undo-whatif.png'
Test-Fixture 'missing-alias' { Replace-Fixture $catalogPath 'result.put("undo-accent",' 'result.put("wrong",' } 'Missing ASCII context aliases'
Test-Fixture 'extension-key' {
    $path = Join-Path $fixture $catalogPath
    Set-Fixture $catalogPath ([IO.File]::ReadAllText($path) + "`n" + 'result.put("undo-accent.png", WEB_ROOT + "undo-accent.png");')
} 'Manifest keys must not include .png.'
Test-Fixture 'fx-base-only' { Replace-Fixture 'ui-fx/src/main/java/ru/cashprediction/fx/ui/FxIcons.java' 'png(key, color)' 'png(key)' } 'Missing shared context/application API:'
Test-Fixture 'swing-base-only' { Replace-Fixture 'ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingIcons.java' 'png(key, color)' 'png(key)' } 'Missing shared context/application API:'
Test-Fixture 'web-local-route' { Replace-Fixture 'web/src/main/java/ru/cashprediction/web/StaticHandler.java' 'UiIcons.resource(filename)' 'LocalIcons.resource(filename)' } 'Missing shared web icon route:'
Test-Fixture 'web-manifest-copy' { Set-Fixture 'web/src/main/resources/web/app/icons.js' 'export const icons = {};' } 'Web must not contain a copied icons manifest.'
Test-Fixture 'primary-launcher' { Replace-Fixture 'dist/pom.xml' ('<argument>' + $shared + '</argument>') '<argument>local.ico</argument>' } 'Primary launcher must use canonical'
foreach ($launcher in @('swing', 'web')) {
    Test-Fixture "$launcher-launcher" { Set-Fixture "dist/launchers/$launcher.properties" 'icon=local.ico' } 'Launcher must use canonical'
}
Test-Fixture 'ico-frame-mismatch' {
    $path = Join-Path $fixture "$resource/application.ico"
    $bytes = [IO.File]::ReadAllBytes($path); $bytes[$bytes.Length - 1] = 1
    [IO.File]::WriteAllBytes($path, $bytes)
} 'ICO 256x256 frame differs'
Write-Output "OK: isolated source icon fixtures. Retained at: $fixtureRoot"
