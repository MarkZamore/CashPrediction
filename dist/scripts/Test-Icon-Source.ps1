<#
.SYNOPSIS
Проверяет единственный источник иконок трёх клиентов без сборки и запуска приложений.
.DESCRIPTION
Проверяет пути jpackage, отсутствие клиентских ресурсов и старых копий dist,
а также точное совпадение application.png с PNG-кадром 256x256 внутри application.ico.
Читает только исходники; не требует графики, Maven или внешних библиотек.
Проверяет наличие PNG девяти контекстных цветов и подключение общего каталога.
Проверки Java/JS статические: выполнение API и геометрия GUI проверяются отдельно.
.EXAMPLE
./dist/scripts/Test-Icon-Source.ps1
#>
[CmdletBinding()]
param([string] $SourceRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $SourceRoot) { $SourceRoot = Join-Path $PSScriptRoot '../..' }
$source = (Get-Item -LiteralPath $SourceRoot).FullName
$sharedPath = '../core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico'
$iconDir = Join-Path $source 'core/src/main/resources/ru/cashprediction/core/ui/icons'

# Сообщает нарушенную проверку, чтобы сборка завершалась с ошибкой.
function Assert-IconSource([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

# Проверяет все исходные ресурсы клиентов, включая вложенные папки и альтернативные форматы.
$copies = @(
    foreach ($relative in @('ui-fx/src/main', 'ui-swing/src/main', 'web/src/main', 'dist/icons')) {
        $directory = Join-Path $source $relative
        if (Test-Path -LiteralPath $directory -PathType Container) {
            Get-ChildItem -LiteralPath $directory -Recurse -File |
                Where-Object { $_.Extension -match '^\.(png|ico|svg|gif|jpg|jpeg|bmp|webp|icns|cur|avif)$' }
        }
    }
)
Assert-IconSource ($copies.Count -eq 0) ("Client/dist icon copies: " + (($copies | ForEach-Object { $_.FullName }) -join ', '))

# Читает обязательный исходник с диагностикой его точного пути.
function Read-IconSource([string] $Relative) {
    $path = Join-Path $source $Relative
    Assert-IconSource (Test-Path -LiteralPath $path -PathType Leaf) "Missing icon source: $Relative"
    return [IO.File]::ReadAllText($path)
}

# Убирает комментарии, чтобы описание намерения не считалось подключением API.
function Remove-IconComments([string] $Text) {
    return [regex]::Replace($Text, '(?s)/\*.*?\*/|(?m)//[^\r\n]*', '')
}

$catalog = Remove-IconComments (Read-IconSource 'core/src/main/java/ru/cashprediction/core/ui/token/UiIcons.java')
$generator = Remove-IconComments (Read-IconSource 'dist/icons/GenerateUiIcons.java')
$colors = @('ACCENT', 'WHATIF', 'EXPENSE', 'INCOME', 'TEXT_PRIMARY', 'TEXT_MUTED', 'WARN', 'TOOLTIP_TEXT', 'TEXT_PAST')
$colorSource = Remove-IconComments (Read-IconSource 'core/src/main/java/ru/cashprediction/core/ui/token/ColorToken.java')
Assert-IconSource ($catalog -cmatch 'png\s*\(\s*String\s+\w+\s*,\s*ColorToken\s+\w+\s*\)') 'Missing UiIcons.png(String, ColorToken).'
Assert-IconSource ($catalog.Contains('"/ru/cashprediction/core/ui/icons/"') -and $catalog.Contains('"/app/icons/"')) 'UiIcons must use canonical core and web roots.'
Assert-IconSource ($generator.Contains('"core/src/main/resources/ru/cashprediction/core/ui/icons"')) 'Generator must default to canonical core resources.'
Assert-IconSource ($generator -notmatch '(ui-fx|ui-swing|web)/src/main') 'Generator must not write client copies.'

# Старые семантические ключи остаются в каталоге; имена файлов извлекаются из этих записей.
$entries = @([regex]::Matches($catalog, 'result\.put\("([^"\r\n]+)",\s*WEB_ROOT\s*\+\s*"([a-z0-9-]+)\.png"\)'))
$glyphs = @('↶', '↷', '▾', '▸', '✕', '✓', '✗', '●', '◀', '▶', '▦', '↑', '✎', '→', '⇄', '≡', 'Δ', '₽', '⚙', '↻', '◎', '⇩', '⟲', 'ℹ', '⚠', '✖', '?', '‹', '›', 'folder', 'search')
$defaultNames = @('undo', 'redo', 'chevron-down', 'chevron-right', 'close', 'check', 'close', 'dot',
    'previous', 'next', 'calendar', 'up', 'edit', 'arrow-right', 'swap', 'list', 'delta', 'ruble',
    'settings', 'refresh', 'target', 'download', 'restore', 'info', 'warning', 'error', 'question',
    'chevron-left', 'chevron-right', 'folder', 'search')
$keys = @($entries | ForEach-Object { $_.Groups[1].Value })
for ($i = 0; $i -lt $glyphs.Count; $i++) {
    $key = $glyphs[$i]
    Assert-IconSource ($keys -ccontains $key) "Missing retained icon key: $key"
    $entry = @($entries | Where-Object { $_.Groups[1].Value -ceq $key })
    Assert-IconSource ($entry.Count -eq 1 -and $entry[0].Groups[2].Value -ceq $defaultNames[$i]) "Changed default glyph mapping: $key"
}
$basenames = @($entries | Where-Object { $glyphs -ccontains $_.Groups[1].Value } |
    ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique)
foreach ($basename in $basenames) {
    Assert-IconSource ($generator.Contains('"' + $basename + '"')) "Missing generator icon: $basename"
    Assert-IconSource (Test-Path -LiteralPath (Join-Path $iconDir "$basename.png") -PathType Leaf) "Missing shared default PNG: $basename.png"
}
foreach ($color in $colors) {
    $suffix = $color.ToLowerInvariant()
    foreach ($text in @($catalog, $generator)) {
        Assert-IconSource ($text -cmatch ("\b" + $color + "\b") -or $text.Contains('"' + $suffix + '"')) "Missing context color declaration: $color"
    }
    $definition = [regex]::Match($colorSource, '\b' + $color + '\s*\(\s*"[^"\r\n]+"\s*,\s*"#([0-9A-Fa-f]{6})"')
    Assert-IconSource $definition.Success "Missing authoritative ColorToken hex: $color"
    $hex = $definition.Groups[1].Value
    Assert-IconSource ($generator -cmatch ('ColorToken\.' + $color + '\b') -or
        $generator -imatch ('(?:0x|#)' + $hex + '\b')) "Generator must use ColorToken color: $color / #$hex"
    foreach ($basename in $basenames) {
        $filename = "$basename-$suffix.png"
        $path = Join-Path $iconDir $filename
        Assert-IconSource (Test-Path -LiteralPath $path -PathType Leaf) "Missing shared context PNG: $filename"
        $bytes = [IO.File]::ReadAllBytes($path)
        Assert-IconSource ($bytes.Length -ge 33 -and [Convert]::ToBase64String($bytes, 0, 8) -ceq 'iVBORw0KGgo=' -and
            [Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ceq 'IHDR') "Invalid shared context PNG: $filename"
    }
}
# Допускает явные ASCII-псевдонимы или формирование имени через Locale.ROOT.
$explicitAliases = $true
foreach ($basename in $basenames) {
    foreach ($color in $colors) {
        $alias = "$basename-$($color.ToLowerInvariant())"
        if (-not ($catalog -cmatch ('\.put\("' + [regex]::Escape($alias) + '",\s*WEB_ROOT\s*\+\s*"' + [regex]::Escape($alias) + '\.png"\)'))) {
            $explicitAliases = $false
        }
    }
}
Assert-IconSource ($explicitAliases -or ($catalog -cmatch 'toLowerCase\(Locale\.ROOT\)' -and
    $catalog -cmatch '\+\s*"-"\s*\+' -and $catalog -cmatch '\.put\([^,]+,\s*WEB_ROOT\s*\+' -and
    $catalog -cmatch '\+\s*"\.png"')) 'Missing ASCII context aliases without extension in manifest keys.'
Assert-IconSource ($catalog -notmatch '\.put\("[^"\r\n]+\.png"\s*,') 'Manifest keys must not include .png.'

# Клиенты получают изображения через ядро, а web получает его манифест и байты.
foreach ($relative in @('ui-fx/src/main/java/ru/cashprediction/fx/ui/FxIcons.java',
        'ui-swing/src/main/java/ru/cashprediction/swing/ui/SwingIcons.java')) {
    $adapter = Remove-IconComments (Read-IconSource $relative)
    Assert-IconSource ($adapter -cmatch 'UiIcons\.png\([^();]+,' -and $adapter.Contains('UiIcons.applicationPng()')) "Missing shared context/application API: $relative"
}
$handler = Remove-IconComments (Read-IconSource 'web/src/main/java/ru/cashprediction/web/StaticHandler.java')
foreach ($fragment in @('UiIcons.manifest()', 'UiIcons.resource(', 'UiIcons.applicationPng()', '"/app/icons/"')) {
    Assert-IconSource ($handler.Contains($fragment)) "Missing shared web icon route: $fragment"
}
$webIcon = Remove-IconComments (Read-IconSource 'web/src/main/resources/web/app/icon.js')
Assert-IconSource ($webIcon -match 'import\s*\{[^}]*icons[^}]*\}\s*from\s*[''"]\./icons\.js[''"]' -and
    $webIcon -match 'icons\[') 'Web adapter must consume shared icons manifest.'
Assert-IconSource (-not (Test-Path -LiteralPath (Join-Path $source 'web/src/main/resources/web/app/icons.js'))) 'Web must not contain a copied icons manifest.'

# XML читается с учётом пространства имён Maven; проверяется именно исполнение jpackage.
[xml] $pom = [IO.File]::ReadAllText((Join-Path $source 'dist/pom.xml'))
$ns = New-Object Xml.XmlNamespaceManager($pom.NameTable)
$ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
$executions = @($pom.SelectNodes('//m:execution[m:id="jpackage"]', $ns))
Assert-IconSource ($executions.Count -eq 1) 'Expected one jpackage execution.'
$execution = $executions[0]
$workingDirectory = $execution.SelectSingleNode('m:configuration/m:workingDirectory', $ns)
Assert-IconSource ($null -ne $workingDirectory -and $workingDirectory.InnerText -ceq '${project.basedir}') 'jpackage must resolve icon paths from dist.'
$arguments = @($execution.SelectNodes('m:configuration/m:arguments/m:argument', $ns) | ForEach-Object { $_.InnerText })
$iconOptions = @($arguments | Where-Object { $_ -ceq '--icon' })
Assert-IconSource ($iconOptions.Count -eq 1) 'Expected one primary launcher icon.'
$index = [Array]::IndexOf($arguments, '--icon')
Assert-IconSource ($index + 1 -lt $arguments.Count -and $arguments[$index + 1] -ceq $sharedPath) 'Primary launcher must use canonical core application.ico.'
$launchers = @($arguments | Where-Object { $_ -ceq '--add-launcher' })
Assert-IconSource ($launchers.Count -eq 2) 'Expected two additional launchers.'
foreach ($launcher in @('swing', 'web')) {
    $launcherName = if ($launcher -eq 'swing') { 'CashPrediction-Swing' } else { 'CashPrediction-Web' }
    Assert-IconSource ($arguments -ccontains "$launcherName=launchers/$launcher.properties") "Missing shared launcher input: $launcher"
    $properties = [IO.File]::ReadAllLines((Join-Path $source "dist/launchers/$launcher.properties"))
    $icons = @($properties | Where-Object { $_ -match '^\s*icon\s*[:=]' })
    Assert-IconSource ($icons.Count -eq 1 -and $icons[0] -ceq "icon=$sharedPath") "Launcher must use canonical core application.ico: $launcher"
}

# ICO разбирается без System.Drawing; все смещения проверяются до чтения кадров.
$png = [IO.File]::ReadAllBytes((Join-Path $iconDir 'application.png'))
$ico = [IO.File]::ReadAllBytes((Join-Path $iconDir 'application.ico'))
Assert-IconSource ($png.Length -ge 33 -and [Convert]::ToBase64String($png, 0, 8) -ceq 'iVBORw0KGgo=') 'Canonical application.png must be PNG.'
Assert-IconSource ([Text.Encoding]::ASCII.GetString($png, 12, 4) -ceq 'IHDR' -and
    [Convert]::ToBase64String($png, 16, 8) -ceq 'AAABAAAAAQA=') 'Canonical application.png must be 256x256.'
Assert-IconSource ($ico.Length -ge 6) 'Truncated ICO header.'
Assert-IconSource ([BitConverter]::ToUInt16($ico, 0) -eq 0 -and [BitConverter]::ToUInt16($ico, 2) -eq 1) 'Invalid ICO header.'
$count = [BitConverter]::ToUInt16($ico, 4)
$directoryEnd = 6 + 16 * $count
Assert-IconSource ($count -gt 0 -and $directoryEnd -le $ico.Length) 'Invalid ICO directory.'
$canonicalFrames = 0
for ($i = 0; $i -lt $count; $i++) {
    $entry = 6 + 16 * $i
    $length = [BitConverter]::ToUInt32($ico, $entry + 8)
    $offset = [BitConverter]::ToUInt32($ico, $entry + 12)
    Assert-IconSource ($length -gt 0 -and $offset -ge $directoryEnd -and [long]$offset + $length -le $ico.Length) 'Invalid ICO frame bounds.'
    if ($ico[$entry] -eq 0 -and $ico[$entry + 1] -eq 0) {
        $canonicalFrames++
        Assert-IconSource ($length -eq $png.Length -and
            [Convert]::ToBase64String($ico, [int]$offset, [int]$length) -ceq [Convert]::ToBase64String($png)) 'ICO 256x256 frame differs from canonical application.png.'
    }
}
Assert-IconSource ($canonicalFrames -eq 1) 'Expected exactly one canonical 256x256 PNG frame in ICO.'
Write-Output 'OK: shared context PNG sources, manifest/generator/client wiring, three launcher inputs and canonical ICO PNG frame (static checks only).'
