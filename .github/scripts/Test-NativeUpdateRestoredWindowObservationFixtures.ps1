<#
.SYNOPSIS
Изолированные window-contract fixtures; optional реальные JDK codecs/schema, без native/UI/server.
.DESCRIPTION
Синтетический UiDump проверяет только контракт, не actual UI. JDK читает schema/FieldCodec,
не запускает selftest и не повторяет прежние 50 domain/session seed проверок.
#>
[CmdletBinding()]param([string]$Java='', [string]$JavaSha256='', [string]$Javac='', [string]$JavacSha256='',
    [string]$CoreJar='', [string]$CoreSha256='')
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$tokens=$null;$errors=$null
$cold=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($cold,[ref]$tokens,[ref]$errors)
foreach ($name in 'Test-ColdInteger','Get-ColdUtcTicks','Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson','Write-ColdJson') {
    $nodes=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
    if ($nodes.Count -ne 1) {throw 'RESTORED_FIXTURE_GUARD'};. ([scriptblock]::Create($nodes[0].Extent.Text))
}
. (Join-Path $PSScriptRoot 'NativeUpdateRestoredWindowObservation.ps1')
$script:checks=0
function Assert-RestoredFixture([bool]$Condition,[string]$Message) {
    if (-not $Condition) {throw ('RESTORED_FIXTURE:'+ $Message)};$script:checks++
}

# Полная schema 1 fixture нужна для strict DumpTrees, но происхождение остаётся FIXTURE_ONLY.
function New-RestoredFixture {
    $canonical=[pscustomobject][ordered]@{target='450731,00';byDateEnabled='false';byDate='2026-10-04';extraSaving='bad731'}
    $fields=@(foreach ($pair in @(@{id='target';kind='MONEY';text='450 731,00'},@{id='byDateEnabled';kind='CHECK';text='false'},
        @{id='byDate';kind='DATE';text='04.10.2026'},@{id='extraSaving';kind='MONEY';text='bad731'})) {
        [pscustomobject]@{id=$pair.id;kind=$pair.kind;label='fixture';text=$pair.text;prompt='';tooltip='';suffix='';enabled=$true;
            visible=$true;readOnly=$false;options=@()}
    })
    $window=[pscustomobject]@{id='w11';type='GOAL_CALCULATOR';purpose='';title='fixture';header='fixture';glyph='';modal=$false;
        ownerId='main';page=0;bounds=[pscustomobject]@{x=0;y=0;width=640;height=400};sections=@();hints=@();fields=$fields;
        preview=@();previewSelected=-1;results=@();problem='fixture invalid input';buttons=@();details='';detailsLink='';detailsExpanded=$false}
    $seedWindow=[pscustomobject]@{id='seedGoal';type='GOAL_CALCULATOR';modal=$false;ownerId='main';bounds=$null;
        context=[pscustomobject]@{};fields=$canonical}
    $seed=[pscustomobject]@{schemaVersion=1;savedAt='2026-10-04T00:00:00Z';client='fx';
        main=[pscustomobject]@{bounds=$null;maximized=$false;view='TABLE';planPath='PhaseSession.md';period='ALL';
            filters=[pscustomobject]@{};filterText='phase-731';selectedRowId='';whatIfExtra=''};
        plan=[pscustomobject]@{dirty=$false;markdown=''};windows=@($seedWindow)}
    $fresh=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $seed -Depth 30)
    $fresh.savedAt='2026-10-04T01:00:00Z';$fresh.windows[0].id='w11'
    $expected=[pscustomobject]@{client='fx';seedSavedAt=$seed.savedAt;seedRevisionSha256=('a'*64);
        windows=@([pscustomobject]@{id='seedGoal';type='GOAL_CALCULATOR';coreFormSpecId='goalCalculator';purpose='';modal=$false;
            ownerId='main';page=0;context=[pscustomobject]@{};canonicalFields=$canonical;
            fields=(ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject @($fields) -Depth 20))})}
    $dump=[pscustomobject]@{schema=1;client='fx';scenario='phase-restored';step='restored';frame=$null;menuBar=@();toolbar=$null;
        summary=$null;table=$null;chart=$null;status=@();contextMenus=@();windows=@($window);alerts=@();popups=@();screens=@();
        chooserRequests=@();classCensus=[pscustomobject]@{};counters=[pscustomobject]@{}}
    return [pscustomobject]@{expected=$expected;dump=$dump;fresh=$fresh;seed=$seed}
}
$fixture=New-RestoredFixture
$decision=Test-NativeRestoredWindowContract $fixture.expected $fixture.dump $fixture.fresh
Assert-RestoredFixture ($decision.status -ceq 'WINDOW_CONTRACT_VALIDATED' -and -not $decision.nativePass -and -not $decision.fullCellProofComplete) 'mock not native PASS'
Assert-RestoredFixture ((Test-NativeRestoredWindowContract $fixture.expected $null $fixture.fresh).status -ceq 'PENDING') 'no dump'
Assert-RestoredFixture ((Test-NativeRestoredWindowContract $fixture.expected $fixture.dump $null).status -ceq 'PENDING') 'no fresh context'
$fixture.expected.windows=@()
Assert-RestoredFixture ((Test-NativeRestoredWindowContract $fixture.expected $fixture.dump $fixture.fresh).missing -ccontains 'SEEDED_WINDOW_NOT_PRODUCED') 'frozen empty seed'
$cases=@(
    @{code='DUMP_IDENTITY';edit={param($f) $f.dump.client='model'}},
    @{code='DUMP_IDENTITY';edit={param($f) $f.dump.schema=$true}},
    @{code='DUMP_SCHEMA_KEYS';edit={param($f) $f.dump | Add-Member revision 123}},
    @{code='STALE_SESSION_REVISION';edit={param($f) $f.fresh.savedAt=$f.seed.savedAt}},
    @{code='WINDOW_COUNT';edit={param($f) $f.dump.windows=@()}},
    @{code='WINDOW_COUNT';edit={param($f) $f.fresh.windows=@()}},
    @{code='WINDOW_ID_BIJECTION';edit={param($f) $f.dump.windows[0].id='main'}},
    @{code='WINDOW_OWNER';edit={param($f) $f.dump.windows[0].ownerId='foreign'}},
    @{code='FORM_IDENTITY';edit={param($f) $f.dump.windows[0].purpose='fake goal'}},
    @{code='FORM_IDENTITY';edit={param($f) $f.dump.windows[0].type='RULE_EDITOR'}},
    @{code='FORM_IDENTITY';edit={param($f) $f.dump.windows[0].modal=$true}},
    @{code='WINDOW_NOT_VISIBLE';edit={param($f) $f.dump.windows[0].bounds.width=0}},
    @{code='ACTUAL_FIELD_VALUE';edit={param($f) $f.dump.windows[0].fields[3].text=''}},
    @{code='ACTUAL_FIELD_VALUE';edit={param($f) $f.dump.windows[0].fields[3].visible=$false}},
    @{code='PERSISTED_CONTEXT_FIELDS';edit={param($f) $f.fresh.windows[0].context=[pscustomobject]@{page='1'}}},
    @{code='PERSISTED_CONTEXT_FIELDS';edit={param($f) $f.fresh.windows[0].fields.extraSaving='lost'}},
    @{code='DUPLICATE_FIELD';edit={param($f) $f.dump.windows[0].fields+=@($f.dump.windows[0].fields[0])}}
)
foreach ($case in $cases) {
    $fixture=New-RestoredFixture;& $case.edit $fixture
    $decision=Test-NativeRestoredWindowContract $fixture.expected $fixture.dump $fixture.fresh
    Assert-RestoredFixture ($decision.status -ceq 'FAIL' -and $decision.errors -ccontains $case.code -and -not $decision.nativePass) $case.code
}
$fixture=New-RestoredFixture;$fixture.expected | Add-Member status 'PASS';$fixture.dump | Add-Member helperStatus 'PASS'
Assert-RestoredFixture (-not (Test-NativeRestoredWindowContract $fixture.expected $fixture.dump $fixture.fresh).nativePass) 'helper status ignored'
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('cp-restored-window-schema-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($temporary)
$jdk=$false;$script:commands=[Collections.Generic.List[object]]::new()

# Optional compiler/JDK запускает только schema code в private Temp, никогда actual client/server.
if ($Java -or $Javac -or $CoreJar) {
    foreach ($pin in @(@{path=$Java;sha=$JavaSha256},@{path=$Javac;sha=$JavacSha256},@{path=$CoreJar;sha=$CoreSha256})) {
        if (-not [IO.Path]::IsPathFullyQualified($pin.path) -or $pin.sha -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'RESTORED_FIXTURE_JDK_PIN'}
    }
    function Invoke-RestoredSchemaTool([string]$Executable,[string[]]$Arguments,[string]$Label) {
        $info=[Diagnostics.ProcessStartInfo]::new($Executable);$info.UseShellExecute=$false;$info.CreateNoWindow=$true;
        $info.WorkingDirectory=$temporary;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        foreach ($key in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') {[void]$info.Environment.Remove($key)}
        foreach ($arg in $Arguments) {$info.ArgumentList.Add($arg)}
        $process=[Diagnostics.Process]::Start($info);$identity=[pscustomobject]@{pid=$process.Id;birth=$process.StartTime.ToUniversalTime().Ticks}
        try {
            $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(60000)) {throw 'RESTORED_FIXTURE_JDK_TIMEOUT'}
            $out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult()
            [IO.File]::WriteAllText((Join-Path $temporary ($Label+'.out.txt')),$out)
            [IO.File]::WriteAllText((Join-Path $temporary ($Label+'.err.txt')),$err)
            $receipt=[pscustomobject]@{executable=$Executable;arguments=$Arguments;identity=$identity;exitCode=$process.ExitCode;stdout=$out;stderr=$err}
            $script:commands.Add($receipt);return $receipt
        } finally {
            # Только retained handle своего JDK child; никакого поиска/kill по числовому PID.
            if (-not $process.HasExited) {$process.Kill();[void]$process.WaitForExit(5000)};$process.Dispose()
        }
    }
    $core=Join-Path $temporary 'core.jar';[IO.File]::Copy($CoreJar,$core,$false)
    $dumpSource=Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/pipeline/DumpTrees.java'
    $dumpPin=(Get-FileHash -LiteralPath $dumpSource).Hash.ToLowerInvariant()
    $copy=Join-Path $temporary 'DumpTrees.java';[IO.File]::Copy($dumpSource,$copy,$false)
    $source=Join-Path $temporary 'NativeRestoredWindowCodec.java'
    [IO.File]::WriteAllText($source,(Get-NativeRestoredWindowCodecSource),[Text.UTF8Encoding]::new($false))
    $classes=Join-Path $temporary 'classes';[void][IO.Directory]::CreateDirectory($classes)
    $compiled=Invoke-RestoredSchemaTool $Javac @('-cp',$core,'-d',$classes,$copy,$source) 'compile'
    Assert-RestoredFixture ($compiled.exitCode -eq 0) ('JDK compile '+$compiled.stderr)
    $classPath=$core+[IO.Path]::PathSeparator+$classes
    $fixture=New-RestoredFixture
    $seed=Join-Path $temporary 'seed.json';Write-ColdJson $seed $fixture.seed
    $expected=Invoke-RestoredSchemaTool $Java @('-XX:-UsePerfData','-cp',$classPath,'NativeRestoredWindowCodec','expected',$seed) 'expected'
    Assert-RestoredFixture ($expected.exitCode -eq 0) 'actual snapshot/FieldCodec expected'
    $value=ConvertFrom-ColdReceiptJson $expected.stdout
    Assert-RestoredFixture ($value.windows[0].coreFormSpecId -ceq 'goalCalculator' -and
        @($value.windows[0].fields | Where-Object {$_.id -ceq 'extraSaving' -and $_.text -ceq 'bad731'}).Count -eq 1) 'actual form id and invalid text preserved'
    $fixture.seed.windows=@();Write-ColdJson $seed $fixture.seed
    $empty=Invoke-RestoredSchemaTool $Java @('-XX:-UsePerfData','-cp',$classPath,'NativeRestoredWindowCodec','expected',$seed) 'empty-seed'
    Assert-RestoredFixture ($empty.exitCode -eq 0 -and @((ConvertFrom-ColdReceiptJson $empty.stdout).windows).Count -eq 0) 'empty seed not window'
    $fixture=New-RestoredFixture;$raw=Join-Path $temporary 'dump.json';Write-ColdJson $raw $fixture.dump
    $valid=Invoke-RestoredSchemaTool $Java @('-XX:-UsePerfData','-cp',$classPath,'NativeRestoredWindowCodec','dump',$raw) 'schema-valid'
    Assert-RestoredFixture ($valid.exitCode -eq 0 -and $valid.stdout.Trim() -ceq 'UI_DUMP_SCHEMA_VALIDATED_NOT_UI_ORIGIN') 'actual strict schema not UI origin'
    $fixture.dump | Add-Member revision 123;Write-ColdJson $raw $fixture.dump
    $invalid=Invoke-RestoredSchemaTool $Java @('-XX:-UsePerfData','-cp',$classPath,'NativeRestoredWindowCodec','dump',$raw) 'schema-fake-revision'
    Assert-RestoredFixture ($invalid.exitCode -ne 0 -and $invalid.stderr.Contains('Invalid fields')) 'UiDump has no revision'
    Assert-RestoredFixture ((Get-FileHash -LiteralPath $CoreJar).Hash.ToLowerInvariant() -ceq $CoreSha256 -and
        (Get-FileHash -LiteralPath $dumpSource).Hash.ToLowerInvariant() -ceq $dumpPin) 'core and schema source frozen'
    $jdk=$true
}
$result=[ordered]@{status='ISOLATED_CONTRACTS_VALIDATED';checks=$script:checks;jdkSchemaExecuted=$jdk;nativeExecuted=$false;
    actualWindowObservation='PENDING';commands=@($script:commands.ToArray());diagnostics=$temporary}
Write-ColdJson (Join-Path $temporary 'fixture-results.json') $result
$result | ConvertTo-Json -Depth 5 -Compress
