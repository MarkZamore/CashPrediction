<#
.SYNOPSIS Размещает модульные JAR прямо в app после jpackage.
.DESCRIPTION Проверяет весь план до перемещения. Работает только с новой сборочной папкой
без пользовательских данных, не используется установщиком конечного пользователя.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ImageDir)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$image=[IO.Path]::GetFullPath($ImageDir)
$app=Join-Path $image 'app'
$mods=Join-Path $app 'mods'
foreach ($directory in @($image,$app,$mods)) {
    $probe=$directory
    while ($probe) {
        if (-not (Test-Path -LiteralPath $probe -PathType Container) -or
            ((Get-Item -LiteralPath $probe -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'MODULE_IMAGE_PATH'}
        $parent=[IO.Path]::GetDirectoryName($probe)
        if ($parent -eq $probe) {break};$probe=$parent
    }
}
if (Test-Path -LiteralPath (Join-Path $image 'CashMemory')) {throw 'MODULE_USER_DATA'}
$jars=@(Get-ChildItem -LiteralPath $mods -Force)
if ($jars.Count -ne 4) {throw 'MODULE_COUNT'}
$names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($jar in $jars) {
    if ($jar.PSIsContainer -or ($jar.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $jar.Name -cnotmatch '^cashprediction-(core|ui-fx|ui-swing|web)-[A-Za-z0-9_.-]+\.jar$' -or
        -not $names.Add($Matches[1]) -or (Test-Path -LiteralPath (Join-Path $app $jar.Name))) {throw 'MODULE_JAR_LAYOUT'}
}
$utf8=[Text.UTF8Encoding]::new($false,$true)
$plans=@()
foreach ($name in @('CashPrediction','CashPrediction-Swing','CashPrediction-Web')) {
    $cfg=Join-Path $app ($name+'.cfg')
    if (-not (Test-Path -LiteralPath $cfg -PathType Leaf) -or
        ((Get-Item -LiteralPath $cfg -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'MODULE_CFG_PATH'}
    $text=[IO.File]::ReadAllText($cfg,$utf8)
    $pattern='(?m)^java-options=\$APPDIR\\mods\r?$'
    if ([regex]::Matches($text,$pattern).Count -ne 1 -or $text.Contains('app.classpath=') -or
        [regex]::Matches($text,'(?m)^java-options=--module-path\r?$').Count -ne 1) {throw 'MODULE_CFG_LAYOUT'}
    $replacement=[regex]::Replace($text,$pattern,{
        param($match)
        'java-options=$APPDIR'+$(if ($match.Value.EndsWith("`r")) {"`r"} else {''})
    })
    $plans+=@{path=$cfg;text=$replacement}
}
foreach ($jar in $jars) {Move-Item -LiteralPath $jar.FullName -Destination (Join-Path $app $jar.Name)}
foreach ($plan in $plans) {[IO.File]::WriteAllText($plan.path,$plan.text,$utf8)}
# Только пустая, заранее проверенная директория; рекурсивного удаления нет.
[IO.Directory]::Delete($mods,$false)
