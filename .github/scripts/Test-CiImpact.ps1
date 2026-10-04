<# Детерминированные fixtures настоящего planner/CLI. Нет Maven, сети, GUI или Git writes. #>
#requires -Version 7.0
[CmdletBinding()]
param([string]$Repository=(Join-Path $PSScriptRoot '../..'), [string]$ReceiptPath)
$ErrorActionPreference='Stop'
$planner=Join-Path $PSScriptRoot 'Get-CiImpact.ps1'
. $planner
$owned=Join-Path ([IO.Path]::GetTempPath()) ('cp-ci-impact-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $owned
if (-not $ReceiptPath) { $ReceiptPath=Join-Path $owned 'receipt.json' }
if (Test-Path -LiteralPath $ReceiptPath) { throw 'RECEIPT_ALREADY_EXISTS' }
$results=[Collections.Generic.List[object]]::new()
$all='core,update-tool,ui-fx,ui-swing,web,repository-doc-audits'
# Строит независимое ожидаемое значение всех восьми публичных outputs.
function New-Expected([string[]]$Enabled=@(),[string]$Units='') {
    [pscustomobject][ordered]@{compile=('compile' -in $Enabled);docs=('docs' -in $Enabled);unitModules=$Units;
        ui=('ui' -in $Enabled);e2e=('e2e' -in $Enabled);portable=('portable' -in $Enabled);
        preflight=('preflight' -in $Enabled);release=('release' -in $Enabled)}
}
$none=New-Expected
$full=New-Expected @('compile','docs','ui','e2e','portable','preflight','release') $all
$docs=New-Expected @('docs') 'repository-doc-audits'
# Требует точные flags, порядок CSV и отсутствие лишних properties.
function Assert-Impact([string]$Name,$Actual,$Expected) {
    if (($Actual | ConvertTo-Json -Compress) -cne ($Expected | ConvertTo-Json -Compress)) {
        throw "IMPACT_MISMATCH $Name actual=$($Actual | ConvertTo-Json -Compress) expected=$($Expected | ConvertTo-Json -Compress)"
    }
    $results.Add([ordered]@{name=$Name;passed=$true;impact=$Actual})
}
foreach ($path in @('pom.xml','.mvn/jvm.config','.github/workflows/ci.yml','.github/scripts/X.ps1','.github/scripts/Initialize-CiDesktop.ps1','dist/scripts/X.ps1',
    'core/pom.xml','ui-fx/pom.xml','ui-swing/pom.xml','web/pom.xml','update-tool/pom.xml','ui-parity/pom.xml',
    'repository-doc-audits/pom.xml','future-module/pom.xml','unknown.txt','.gitignore','core/unknown.bin','future/src/main/Test.java',
    '/docs/x.md','C:/docs/x.md','docs/../core/x','./docs/x','docs//x.md',' docs/x.md','docs/x.md ',"docs/x`n.md",'',"docs/x`t.md")) {
    Assert-Impact "full:$path" (Get-CiImpact -Paths @($path)) $full
}
foreach ($path in @('docs/design/x.md','README.md','readme','ARCHITECTURE.md','PROJECT_REQUIREMENTS.md','CHANGELOG.md',
    'licenses/a.md','LICENSE','NOTICE.txt','repository-doc-audits/src/test/java/A.java','DoCs/Раздел с пробелами.md')) {
    Assert-Impact "docs:$path" (Get-CiImpact -Paths @($path)) $docs
}
# Technical и repo-only AI/graphify имеют общий docs consumer, несмотря на разный состав source-архива.
$technicalDocs=@('architecture','techstack','edge-cases','db-schema','linx','ui-kit') |
    ForEach-Object { "docs/design/$_.md" }
$aiDocs=@('CurrentSprint','ContextDump','ChangeRequest','LegacyWarning') |
    ForEach-Object { "docs/ai/$_.md" }
$graphifyDocs=@('docs/ai/graphify/README.md','docs/ai/graphify/project_graph.py',
    'docs/ai/graphify/Invoke-Graphify.ps1','docs/ai/graphify/graphify-out/ast.json',
    'docs/ai/graphify/graphify-out/corpus.json','docs/ai/graphify/graphify-out/inventory.json',
    'docs/ai/graphify/graphify-out/file-layer.json','docs/ai/graphify/graphify-out/cache/stat-index.json')
foreach ($path in @($technicalDocs)+@($aiDocs)+@($graphifyDocs)) {
    Assert-Impact "document-consumer:$path" (Get-CiImpact -Paths @($path)) $docs
}
# Planner получает обе стороны rename и пути delete, не требует наличия удалённого файла.
Assert-Impact 'doc-rename technical to AI' (Get-CiImpact -Paths @('docs/design/edge-cases.md','docs/ai/LegacyWarning.md')) $docs
Assert-Impact 'doc-rename AI to graphify' (Get-CiImpact -Paths @('docs/ai/ContextDump.md','docs/ai/graphify/README.md')) $docs
Assert-Impact 'doc-rename graphify nested cache' (Get-CiImpact -Paths @('docs/ai/graphify/graphify-out/cache/deleted-old.json','docs/ai/graphify/graphify-out/cache/new-index.json')) $docs
Assert-Impact 'doc-delete technical missing path' (Get-CiImpact -Paths @('docs/design/deleted-technical-fixture.md')) $docs
Assert-Impact 'doc-delete AI missing path' (Get-CiImpact -Paths @('docs/ai/deleted-ai-fixture.md')) $docs
Assert-Impact 'doc-mixed six technical four AI' (Get-CiImpact -Paths (@($technicalDocs)+@($aiDocs))) $docs
Assert-Impact 'doc-mixed technical AI graphify' (Get-CiImpact -Paths (@($technicalDocs)+@($aiDocs)+@($graphifyDocs))) $docs
Assert-Impact 'doc-mixed AI and ignored scratch still docs' (Get-CiImpact -Paths @('docs/ai/LegacyWarning.md','.claude/scratch/ignored.patch')) $docs
foreach ($path in @('.claude/scratch/x.patch','.codex/a.json','.agents/a.txt','AGENTS.md','CLAUDE.md','core/AGENTS.md','.claude/pom.xml')) {
    Assert-Impact "agent:$path" (Get-CiImpact -Paths @($path)) $none
}
foreach ($module in @('core','update-tool','ui-fx','ui-swing','web')) {
    $unit="$module,repository-doc-audits"
    $mainFlags=@('compile','docs','portable','preflight','release')
    if ($module -ne 'update-tool') { $mainFlags+=@('ui','e2e') }
    $expected=New-Expected $mainFlags $(if($module -eq 'core'){$all}else{$unit})
    foreach ($suffix in @('java/A.java','resources/licenses/architecture.md','resources/имя с пробелом.txt')) {
        Assert-Impact "main:$module/$suffix" (Get-CiImpact -Paths @("$module/src/main/$suffix")) $expected
    }
    $testFlags=@('docs')
    if ($module -in 'ui-fx','ui-swing','web') { $testFlags+=@('ui','e2e') }
    $testExpected=New-Expected $testFlags $unit
    foreach ($suffix in @('java/DeletedTest.java','resources/fixture.json')) {
        Assert-Impact "test:$module/$suffix" (Get-CiImpact -Paths @("$module/src/test/$suffix")) $testExpected
    }
}
$golden=New-Expected @('docs','ui','e2e','portable','preflight') 'core,repository-doc-audits'
foreach ($name in @('ui-golden','ui-goldens','golden','goldens','ui-scenarios','ui-json')) {
    Assert-Impact "golden:$name" (Get-CiImpact -Paths @("core/src/test/resources/$name/scenario/a.json")) $golden
}
$parity=New-Expected @('docs','ui','e2e','portable','preflight') 'repository-doc-audits'
foreach ($path in @('ui-parity/src/test/java/A.java','UI-PARITY/src/test/resources/x','ui-parity/README.md')) {
    Assert-Impact "parity:$path" (Get-CiImpact -Paths @($path)) $parity
}
Assert-Impact 'empty known diff' (Get-CiImpact -Paths @()) $none
Assert-Impact 'empty forced' (Get-CiImpact -Paths @() -ForceFull) $full
Assert-Impact 'null collection' (Get-CiImpact -Paths $null) $none
Assert-Impact 'null entry' (Get-CiImpact -Paths @($null)) $full
Assert-Impact 'forced docs' (Get-CiImpact -Paths @('README.md') -ForceFull) $full
Assert-Impact 'case and Windows separators' (Get-CiImpact -Paths @('CORE\SRC\MAIN\java\A.java')) $full
Assert-Impact 'deletion requires no filesystem' (Get-CiImpact -Paths @('ui-fx/src/test/java/Removed.java')) (New-Expected @('docs','ui','e2e') 'ui-fx,repository-doc-audits')
Assert-Impact 'rename both old/new supplied' (Get-CiImpact -Paths @('docs/old.md','core/src/main/resources/new.md')) $full
Assert-Impact 'rename source into agent retains old impact' (Get-CiImpact -Paths @('core/src/main/java/A.java','.claude/A.java')) $full
Assert-Impact 'rename between clients union' (Get-CiImpact -Paths @('ui-fx/src/main/java/A.java','web/src/main/java/A.java')) (New-Expected @('compile','docs','ui','e2e','portable','preflight','release') 'ui-fx,web,repository-doc-audits')
Assert-Impact 'duplicate ordered union' (Get-CiImpact -Paths @('web/src/test/java/Z.java','core/src/test/java/X.java','web/src/test/java/Z.java','README.md')) (New-Expected @('docs','ui','e2e') 'core,web,repository-doc-audits')
Assert-Impact 'unknown mixed with docs' (Get-CiImpact -Paths @('README.md','new-tool.bin')) $full

# Извлекает настоящий CLI body; только Git transport ниже заменяется явно обозначенным fixture.
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($planner,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'PLANNER_PARSE' }
$cliNode=@($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.IfStatementAst] -and $_.Extent.Text.StartsWith('if ($MyInvocation.InvocationName') })
if ($cliNode.Count -ne 1) { throw 'CLI_BODY_NOT_FOUND' }
$cliText=$cliNode[0].Clauses[0].Item2.Extent.Text
$cli=[scriptblock]::Create($cliText.Substring(1,$cliText.Length-2))
$realGit=(Get-Item Function:Invoke-CiImpactGit).ScriptBlock
$shaA='a'*40; $shaB='b'*40
# Управляемые ответы Git проверяют invalid/history/diff ошибки без создания commits.
function Invoke-FixtureCli([string]$Name,[hashtable]$Spec,$Expected) {
    $Repository=$owned; $BaseSha=$shaA; $HeadSha=$shaB; $ForceFull=$false
    $OutputPath=Join-Path $owned "$Name.json"; $GithubOutput=Join-Path $owned "$Name.github-output"
    if ($Spec.ContainsKey('base')) { $BaseSha=$Spec.base }
    if ($Spec.ContainsKey('head')) { $HeadSha=$Spec.head }
    if ($Spec.ContainsKey('force')) { $ForceFull=$Spec.force }
    $script:transportSpec=$Spec; $script:transportCalls=[Collections.Generic.List[object]]::new()
    function Invoke-CiImpactGit([string]$Directory,[string[]]$Arguments) {
        $script:transportCalls.Add(@($Arguments))
        switch ($Arguments[0]) {
            'rev-parse' {
                if ($script:transportSpec.ContainsKey('resolveError')) { return [pscustomobject]@{exitCode=128;output='';error='missing object'} }
                if ($script:transportSpec.ContainsKey('resolveMalformed')) { return [pscustomobject]@{exitCode=0;output='not-a-sha';error=''} }
                return [pscustomobject]@{exitCode=0;output=$shaB;error=''}
            }
            'merge-base' { return [pscustomobject]@{exitCode=$(if($script:transportSpec.ContainsKey('ancestorError')){$script:transportSpec.ancestorError}else{0});output='';error=''} }
            'diff' {
                $required=@('diff','--no-ext-diff','--no-textconv','--no-renames','--name-only','-z',$shaA,$shaB,'--')
                if (($Arguments -join '|') -cne ($required -join '|')) { throw 'DIFF_ARGUMENT_GUARD' }
                if ($script:transportSpec.ContainsKey('throw')) { throw 'transport exception' }
                return [pscustomobject]@{exitCode=$(if($script:transportSpec.ContainsKey('diffError')){128}else{0});output=$script:transportSpec.diff;error=''}
            }
            default { throw 'UNEXPECTED_GIT_COMMAND' }
        }
    }
    . $cli | Out-Null
    $actual=Get-Content -LiteralPath $OutputPath -Raw | ConvertFrom-Json
    Assert-Impact "transport:$Name" $actual $Expected
    $outputLines=@(Get-Content -LiteralPath $GithubOutput)
    if ($outputLines.Count -ne 8 -or @($outputLines | Where-Object { $_ -cmatch 'True|False|\r|\n' }).Count -or
        ($outputLines -join '|') -cne (@($Expected.PSObject.Properties | ForEach-Object { "$($_.Name)=$(if($_.Value -is [bool]){$_.Value.ToString().ToLowerInvariant()}else{$_.Value})" }) -join '|')) { throw "GITHUB_OUTPUT_$Name" }
    if ($Spec.ContainsKey('noGit') -and $script:transportCalls.Count) { throw 'UNEXPECTED_GIT_ON_FORCE' }
}
Invoke-FixtureCli 'empty' @{diff=''} $none
Invoke-FixtureCli 'deleted-doc' @{diff="docs/deleted.md$([char]0)"} $docs
Invoke-FixtureCli 'desktop-helper' @{diff=".github/scripts/Initialize-CiDesktop.ps1$([char]0)"} $full
# Actual CLI/NUL parsing и все восемь GitHub outputs: transport остаётся явно mock, не remote PASS.
Invoke-FixtureCli 'six-technical-docs' @{diff=(@($technicalDocs) -join [char]0)+[char]0} $docs
Invoke-FixtureCli 'four-ai-docs' @{diff=(@($aiDocs) -join [char]0)+[char]0} $docs
Invoke-FixtureCli 'graphify-docs' @{diff=(@($graphifyDocs) -join [char]0)+[char]0} $docs
Invoke-FixtureCli 'doc-rename-technical-ai' @{diff="docs/design/ui-kit.md$([char]0)docs/ai/LegacyWarning.md$([char]0)"} $docs
Invoke-FixtureCli 'doc-delete-graphify' @{diff="docs/ai/graphify/graphify-out/cache/deleted-cli-fixture.json$([char]0)"} $docs
Invoke-FixtureCli 'doc-mixed-technical-ai-graphify' @{diff=(@($technicalDocs)+@($aiDocs)+@($graphifyDocs) -join [char]0)+[char]0} $docs
Invoke-FixtureCli 'rename-both' @{diff="docs/old.md$([char]0)core/src/main/java/New.java$([char]0)"} $full
Invoke-FixtureCli 'unusual-path' @{diff="web/src/test/resources/русский name.json$([char]0)"} (New-Expected @('docs','ui','e2e') 'web,repository-doc-audits')
foreach ($spec in @(
    @{name='missing-base';base='';diff='';noGit=$true},@{name='invalid-base';base='--evil';diff='';noGit=$true},
    @{name='invalid-head';head='refs/heads/untrusted';diff='';noGit=$true},@{name='forced';force=$true;diff='';noGit=$true},
    @{name='missing-object';resolveError=$true;diff=''},@{name='malformed-resolution';resolveMalformed=$true;diff=''},
    @{name='not-ancestor';ancestorError=1;diff=''},@{name='history-error';ancestorError=128;diff=''},
    @{name='diff-error';diffError=$true;diff=''},@{name='diff-exception';throw=$true;diff=''},
    @{name='missing-nul';diff='docs/x.md'},@{name='empty-path-record';diff=[string][char]0},
    @{name='unknown-diff';diff="unknown$([char]0)"},@{name='newline-path';diff="docs/new`nline.md$([char]0)"}
)) { Invoke-FixtureCli $spec.name $spec $full }
Set-Item Function:Invoke-CiImpactGit $realGit

# Реальные CLI процессы используют read-only Git: proven empty, missing baseline/object, invalid repo.
$head=Invoke-CiImpactGit $Repository @('rev-parse','--verify','HEAD^{commit}')
if ($head.exitCode -ne 0) { throw 'REAL_TEST_REPOSITORY_REQUIRED' }
$processResults=@()
$pwsh=(Get-Command pwsh).Source
foreach ($case in @(
    @{name='real-empty';base=$head.output.Trim();head=$head.output.Trim();repo=$Repository;expected=$none},
    @{name='real-missing-baseline';base='';head='HEAD';repo=$Repository;expected=$full},
    @{name='real-missing-object';base=('0'*40);head='HEAD';repo=$Repository;expected=$full},
    @{name='real-invalid-repository';base=$head.output.Trim();head='HEAD';repo=$owned;expected=$full}
)) {
    $output=Join-Path $owned "$($case.name).json"; $gh=Join-Path $owned "$($case.name).github-output"
    $info=[Diagnostics.ProcessStartInfo]::new(); $info.FileName=$pwsh; $info.UseShellExecute=$false
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $args=@('-NoProfile','-File',$planner,'-Repository',$case.repo,'-HeadSha',$case.head,'-OutputPath',$output,'-GithubOutput',$gh)
    if ($case.base) { $args+=@('-BaseSha',$case.base) }
    foreach ($arg in $args) { $info.ArgumentList.Add($arg) }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info; $started=[DateTimeOffset]::UtcNow
    try {
        $null=$process.Start(); $pidValue=$process.Id
        $outTask=$process.StandardOutput.ReadToEndAsync(); $errTask=$process.StandardError.ReadToEndAsync(); $process.WaitForExit()
        $outText=$outTask.GetAwaiter().GetResult(); $errText=$errTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText("$owned/$($case.name).stdout.log",$outText)
        [IO.File]::WriteAllText("$owned/$($case.name).stderr.log",$errText)
        if ($process.ExitCode -ne 0) { throw "REAL_CLI_EXIT_$($case.name)" }
        Assert-Impact $case.name (Get-Content $output -Raw | ConvertFrom-Json) $case.expected
        $processResults += [ordered]@{name=$case.name;tool=$pwsh;arguments=$args;pid=$pidValue;startedAt=$started;finishedAt=[DateTimeOffset]::UtcNow;
            exitCode=$process.ExitCode;stdoutSha256=(Get-FileHash "$owned/$($case.name).stdout.log").Hash;stderrSha256=(Get-FileHash "$owned/$($case.name).stderr.log").Hash}
    } finally { $process.Dispose() }
}
[ordered]@{scope='ACTUAL_PLANNER_AND_CLI_WITH_EXPLICIT_GIT_TRANSPORT_FIXTURES_PLUS_READ_ONLY_REAL_GIT';tests=$results.Count;failures=0;
    results=@($results.ToArray());processes=$processResults;plannerSha256=(Get-FileHash $planner).Hash;testSha256=(Get-FileHash $PSCommandPath).Hash;
    ownedTemp=$owned;gitWrites=$false;networkExecuted=$false;mavenExecuted=$false;guiExecuted=$false;nativeExecuted=$false;fullAcceptance=$false} |
    ConvertTo-Json -Depth 9 | Set-Content -LiteralPath $ReceiptPath -Encoding utf8
"PASS: $($results.Count) deterministic impact/CLI checks; receipt=$ReceiptPath"
