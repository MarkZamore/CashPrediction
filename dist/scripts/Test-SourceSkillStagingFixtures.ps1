<#
.SYNOPSIS
Проверяет полное StageOnly исключение вложенных SKILL инструкций и сохранность ресурсов.
.DESCRIPTION
Настоящий Pack-Source получает синтетическое полное дерево без запуска Maven/архиватора.
Сохранённые input/stage inventories и receipt не означают проверку конечного source7z.
#>
#requires -Version 7.0
[CmdletBinding()]
param([string]$PackPath=(Join-Path $PSScriptRoot 'Pack-Source.ps1'),
    [Parameter(Mandatory)][string]$EvidenceRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$PackPath=[IO.Path]::GetFullPath($PackPath)
$EvidenceRoot=[IO.Path]::GetFullPath($EvidenceRoot)
$tempBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
if(-not $EvidenceRoot.StartsWith($tempBase+[IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $EvidenceRoot)){
    throw 'SKILL_STAGE_EVIDENCE_SCOPE'
}
# Ограничивает все записи стендом и запрещает ссылку в цепочке родителей.
function Assert-Owned([string]$Path){
    $absolute=[IO.Path]::GetFullPath($Path)
    if(-not ($absolute -ceq $EvidenceRoot -or
        $absolute.StartsWith($EvidenceRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase))){
        throw 'SKILL_STAGE_WRITE_SCOPE'
    }
    for($p=$absolute;$p;$p=[IO.Path]::GetDirectoryName($p)){
        if((Test-Path -LiteralPath $p) -and
            ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){
            throw 'SKILL_STAGE_LINK'
        }
    }
}
# Записывает только новый UTF8 fixture или evidence файл.
function Add-Owned([string]$Path,[string]$Text){
    Assert-Owned $Path
    $null=[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
    try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes($Text);$stream.Write($bytes,0,$bytes.Length)}
    finally{$stream.Dispose()}
}
# Фиксирует реальные bytes всех файлов с относительными именами.
function Get-Inventory([string]$Root){
    @(Get-ChildItem -LiteralPath $Root -Recurse -Force -File|ForEach-Object{
        Assert-Owned $_.FullName
        [pscustomobject]@{path=[IO.Path]::GetRelativePath($Root,$_.FullName).Replace('\','/');
            size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
    }|Sort-Object path)
}
$results=[Collections.Generic.List[object]]::new()
# Оставляет каждое несовпадение отдельным FAILED, не останавливая evidence запись.
function Record([string]$Name,[bool]$Passed,[string]$Observation){
    $results.Add([pscustomobject]@{name=$Name;passed=$Passed;observation=$Observation})
}
Assert-Owned $EvidenceRoot
$null=New-Item -ItemType Directory -Path $EvidenceRoot
$source=Join-Path $EvidenceRoot 'source [1]'
$stage=Join-Path $EvidenceRoot 'staged [2]'
$policyBefore=(Get-FileHash -LiteralPath $PackPath).Hash
$modules=@('core','update-tool','ui-fx','ui-swing','web','ui-parity','dist')
$rootPom='<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module><module>repository-doc-audits</module></modules><profiles><profile><id>dist</id><modules><module>dist</module></modules></profile><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles></project>'
$modulePom='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>'
$kept=@('pom.xml','docs/design/architecture.md','docs/design/techstack.md',
    'docs/design/edge-cases.md','docs/design/db-schema.md','docs/design/linx.md','docs/design/ui-kit.md',
    'dist/scripts/Set-LauncherUtf8.ps1','dist/scripts/Test-Icon-Source.ps1',
    'dist/scripts/Test-IconPayloadIntegrity.ps1','dist/scripts/Normalize-AppModules.ps1',
    'dist/icons/make-icon.ps1','dist/launchers/swing.properties','dist/launchers/web.properties',
    '.github/scripts/Test-Portable.ps1','.github/scripts/GhRetry.ps1',
    'core/src/main/java/ru/cashprediction/core/update/Example.java',
    'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
    'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
    'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
    'core/src/test/resources/manual/monthly.plan.md',
    'core/src/main/resources/nested/SKILL.json','web/src/main/resources/web/nested/SKILL.png',
    'web/src/main/resources/web/nested/skill-response.json',
    'update-tool/src/main/resources-filtered/manual/help.md',
    'LICENSE.md','LICENCE','COPYING.md','NOTICE.txt',
    'core/src/main/resources/nested/LICENSE.md','web/src/test/resources/nested/NOTICE.txt')
foreach($module in $modules){$kept+="$module/pom.xml"}
foreach($module in @('core','update-tool','ui-fx','ui-swing','web')){$kept+="$module/src/main/java/Example.java"}
foreach($relative in $kept){
    $content="retained resource fixture: $relative"
    if($relative -eq 'pom.xml'){$content=$rootPom}
    elseif($relative -like '*/pom.xml'){$content=$modulePom}
    elseif($relative -eq 'core/src/test/resources/manual/monthly.plan.md'){
        $content="# План: Ручной пример`n`n## Параметры`n`n- Формат: CashPrediction 1`n- Начало: 2026-01-01`n- Горизонт: 12 месяцев`n- Начальный баланс: 1000,00`n"
    }
    Add-Owned (Join-Path $source $relative) $content
}
Add-Owned (Join-Path $source 'repository-doc-audits/pom.xml') $modulePom
$excluded=@('core/src/main/resources/nested/SKILL.md',
    'core/src/test/resources/docs/deep/skill.MD',
    'update-tool/src/main/resources-filtered/nested/SKILL.markdown',
    'web/src/main/resources/web/nested/SKILL.md',
    'ui-fx/src/test/resources/nested/SKILL.MARKDOWN',
    'ui-swing/src/main/resources/nested/SKILL.md',
    'ui-parity/src/test/resources/nested/SKILL.md',
    'docs/ai/CurrentSprint.md','docs/AI/ContextDump.md','docs/ai/ChangeRequest.md','docs/ai/LegacyWarning.md',
    'core/src/main/resources/nested/cUrReNtSpRiNt.MD','web/src/test/resources/nested/CONTEXTDUMP.md',
    'update-tool/src/main/resources-filtered/nested/changerequest.MARKDOWN',
    'ui-parity/src/test/resources/nested/legacywarning.txt','docs/design/extra.md')
foreach($relative in $excluded){Add-Owned (Join-Path $source $relative) '# Agent skill instructions fixture'}
$before=Get-Inventory $source
Add-Owned (Join-Path $EvidenceRoot 'source-before.json') ($before|ConvertTo-Json -Depth 6)
$started=[DateTime]::UtcNow.ToString('o')
$timer=[Diagnostics.Stopwatch]::StartNew()
$failure=$null;$packResult=$null
try{$packResult=& $PackPath -SourceRoot $source -StageDirectory $stage -StageOnly}
catch{$failure=$_.Exception.Message}
$timer.Stop()
Record 'actual-stage-completed' ($null -eq $failure -and $null -ne $packResult) "$failure"
$inventory=@()
if(Test-Path -LiteralPath $stage){
    $inventory=Get-Inventory $stage
    $names=@($inventory.path)
    foreach($relative in $excluded){Record "excluded:$relative" ($names -cnotcontains $relative) 'Actual staged inventory'}
    Record 'no-skill-documents-anywhere' (@($names|Where-Object {[IO.Path]::GetFileName($_) -match '^SKILL\.(md|markdown)$'}).Count -eq 0) 'Full staged inventory, including nested resources'
    foreach($relative in $kept){
        $match=@($inventory|Where-Object path -CEQ $relative)
        Record "kept:$relative" ($match.Count -eq 1) 'Required staged file exists once'
        $expectedHash=(Get-FileHash -LiteralPath (Join-Path $source $relative)).Hash
        if($relative -eq 'pom.xml'){
            $expectedText=$rootPom.Replace('<module>repository-doc-audits</module>','')
            $expectedHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.UTF8Encoding]::new($false).GetBytes($expectedText)))
        }
        Record "bytes:$relative" ($match.Count -eq 1 -and $match[0].sha256 -ceq $expectedHash) $expectedHash
    }
    Record 'exact-whole-inventory' (($names|Sort-Object|ConvertTo-Json -Compress) -ceq
        ($kept|Sort-Object|ConvertTo-Json -Compress)) 'Every staged file is expected; no extra metadata or loss'
    Record 'returned-file-count' ($null -ne $packResult -and $packResult.FileCount -eq $kept.Count) "Expected=$($kept.Count)"
}
$after=Get-Inventory $source
Record 'source-bytes-unchanged' (($before|ConvertTo-Json -Depth 6) -ceq ($after|ConvertTo-Json -Depth 6)) 'All input files, including excluded SKILL/POM'
Record 'policy-unchanged' ((Get-FileHash -LiteralPath $PackPath).Hash -ceq $policyBefore) $policyBefore
Add-Owned (Join-Path $EvidenceRoot 'source-after.json') ($after|ConvertTo-Json -Depth 6)
Add-Owned (Join-Path $EvidenceRoot 'stage-inventory.json') ($inventory|ConvertTo-Json -Depth 6)
$failed=@($results|Where-Object {-not $_.passed}).Count
$receipt=[ordered]@{status=$(if($failed){'FAILED'}else{'PASS'});
    scope='REAL_PACK_SOURCE_STAGEONLY_SYNTHETIC_TREE';scenarioCount=1;assertions=$results.Count;failures=$failed;
    policyPath=$PackPath;policySha256=$policyBefore;fixtureSha256=(Get-FileHash $PSCommandPath).Hash;
    command=@($PackPath,'-SourceRoot',$source,'-StageDirectory',$stage,'-StageOnly');
    startedUtc=$started;finishedUtc=[DateTime]::UtcNow.ToString('o');durationMs=$timer.ElapsedMilliseconds;
    packError=$failure;packResult=$packResult;retainedRoot=$EvidenceRoot;
    sourceInventorySha256=(Get-FileHash (Join-Path $EvidenceRoot 'source-before.json')).Hash;
    stagedInventorySha256=(Get-FileHash (Join-Path $EvidenceRoot 'stage-inventory.json')).Hash;
    results=@($results);mavenExecuted=$false;archiveExecuted=$false;guiExecuted=$false;nativeExecuted=$false;fullAcceptance=$false}
Add-Owned (Join-Path $EvidenceRoot 'receipt.json') ($receipt|ConvertTo-Json -Depth 10)
Write-Output "Real staging: $($receipt.status), $($receipt.assertions) assertions, $failed failures; inventory=$($inventory.Count), receipt=$(Join-Path $EvidenceRoot 'receipt.json')"
if($failed){exit 1}
