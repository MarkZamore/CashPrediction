<#
.SYNOPSIS
Bounded two-client acceptance fixtures: actual AST imports/pure guards, synthetic data only.
.DESCRIPTION
Никаких native/GUI/Java/Maven/network/registry/write; PASS означает checks, не native evidence.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0;$pins=@{};$asts=@{}
foreach ($file in 'NativeUpdateTwoClientAcceptance.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateConcurrentScenarios.ps1',
    'NativeUpdateConcurrentAcceptanceFixtures.ps1','NativeUpdateAcceptanceDispatch.ps1','Test-UpdateBootstrap.ps1','Test-NativeUpdateLifecycle.ps1','Test-Portable.ps1') {
    $path=Join-Path $PSScriptRoot $file;$pins[$file]=(Get-FileHash -LiteralPath $path).Hash
    $tokens=$null;$errors=$null;$asts[$file]=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw ('TWO_FIXTURE_PARSE '+$file)}
}
# Проверяет только harness результат, никогда Row/native PASS.
function Assert-TwoFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('TWO_FIXTURE_ASSERT '+$Code)};$script:checks++
}
foreach ($def in $asts['NativeUpdateTwoClientAcceptance.ps1'].EndBlock.Statements) {
    if ($def -isnot [Management.Automation.Language.FunctionDefinitionAst]) {throw 'TWO_FIXTURE_IMPORT_BODY'}
    . ([scriptblock]::Create($def.Extent.Text))
}
Import-NativeTwoClientAcceptanceGuards $PSScriptRoot

# Замыкание реальных importer функций проверяется до leaf mocks, без выполнения их тел.
$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);$queue=[Collections.Generic.Queue[string]]::new()
foreach ($name in 'Test-NativeTwoClientAcceptance','Test-NativeTwoClientReceiptContract') {$queue.Enqueue($name)}
while ($queue.Count) {
    $name=$queue.Dequeue();if (-not $seen.Add($name)) {continue}
    $cmd=Get-Command $name -ErrorAction Stop
    if ($cmd.CommandType -ne 'Function' -or $cmd.ModuleName) {continue}
    $matches=@(foreach ($file in $asts.Keys | Where-Object {$_ -cne 'NativeUpdateConcurrentAcceptanceFixtures.ps1'}) {
        $asts[$file].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true)
    })
    if ($name -ceq 'Check') {continue}
    Assert-TwoFixture ($matches.Count -eq 1) ('ONE_SOURCE_'+$name)
    $expected=& {param($text,$n) . ([scriptblock]::Create($text));(Get-Command $n).Definition} $matches[0].Extent.Text $name
    Assert-TwoFixture ($cmd.Definition -ceq $expected) ('EXACT_SOURCE_'+$name)
    foreach ($call in $matches[0].FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
        $callee=$call.GetCommandName();if ($callee -and $callee -cne 'Check') {$queue.Enqueue($callee)}
    }
}
Assert-TwoFixture ($seen.Contains('Assert-ColdProcessIdentity') -and $seen.Contains('Assert-NativeAcceptanceBarrier') -and $seen.Contains('Read-NativeAcceptanceJson')) 'ACTUAL_IMPORT_CLOSURE'

# Импортируются только пять generators прежних fixtures, не suite и не native adapter.
foreach ($name in 'Get-FixtureTime','New-AcceptanceUi','New-AcceptanceIdentity','New-AcceptanceAlive','New-AcceptanceBarrier') {
    $def=@($asts['NativeUpdateConcurrentAcceptanceFixtures.ps1'].EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})[0]
    . ([scriptblock]::Create($def.Extent.Text))
}

# Отдельные synthetic observations заново датируют UI, не reuse initial cached observedAt.
function New-TwoWait($Uis,[int]$Start,$Files) {
    $samples=@()
    foreach ($offset in 0,3) {
        $before=@();$after=@()
        foreach ($ui in $Uis) {
            $fresh=ConvertFrom-ColdReceiptJson (ConvertTo-Json -Depth 64 -InputObject $ui)
            $fresh.observedAt=Get-FixtureTime ($Start+$offset)
            $before+=@(New-AcceptanceAlive $fresh ($Start+$offset))
            $freshAfter=ConvertFrom-ColdReceiptJson (ConvertTo-Json -Depth 64 -InputObject $ui)
            $freshAfter.observedAt=Get-FixtureTime ($Start+$offset+1)
            $after+=@(New-AcceptanceAlive $freshAfter ($Start+$offset+1))
        }
        $samples+=@([pscustomobject]@{startedAt=(Get-FixtureTime ($Start+$offset));finishedAt=(Get-FixtureTime ($Start+$offset+1));
            aliveBefore=$before;aliveAfter=$after;treeFiles=$Files;replacementPhases=@();lastInstallPresent=$false})
    }
    return $samples
}

# Фабрика не пишет evidence и не считает synthetic data actual native observations.
function New-TwoFixture([string]$Client='fx') {
    $order=@('fx','swing','web');$index=[Array]::IndexOf($order,$Client)
    $run=Join-Path ([IO.Path]::GetTempPath()) 'run-11111111-1111-1111-1111-111111111111';$root=Join-Path $run 'plain/CashPrediction'
    $old=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=1L;sha256=('a'*64);readOnly=$false})
    $new=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=2L;sha256=('b'*64);readOnly=$false})
    $base=[pscustomobject]@{releaseNumber=1;commitSha=('a'*40);treeSha256=(Get-ColdTreeHash $old);files=$old}
    $target=[pscustomobject]@{releaseNumber=2;commitSha=('b'*40);treeSha256=(Get-ColdTreeHash $new);files=$new}
    $uis=@((New-AcceptanceUi 1 $Client $root),(New-AcceptanceUi 2 $order[($index+1)%3] $root))
    $user=[pscustomobject]@{'CashMemory/settings.md'=[pscustomobject]@{path='CashMemory/settings.md';directory=$false;sizeBytes=1L;sha256=('d'*64);readOnly=$false}}
    $b=[ordered]@{cell=[pscustomobject]@{scenario='two-clients';base='B1';client=$Client;path='ascii';phase='SESSION';status='PASS';executed=$true;
        baseRelease=1;baseCommit=$base.commitSha;targetRelease=2;targetCommit=$target.commitSha;runRoot=$run;
        startedAt=(Get-FixtureTime 0);finishedAt=(Get-FixtureTime 47);exitCode=0;skipped=0;failures=0}}
    for ($i=0;$i -lt 2;$i++) {
        $b['launch'+$i]=[pscustomobject]@{launcher=(New-AcceptanceIdentity $uis[$i]);ui=$uis[$i];args=$uis[$i].args;startedAt=(Get-FixtureTime 1);manifestUri='http://127.0.0.1:19001/update.json'}
        $b['close'+$i]=if ($uis[$i].lease.client -ceq 'web') {$null} else {[pscustomobject]@{observedUtc=(Get-FixtureTime $(if ($i -eq 0) {19} else {31}));ui=$uis[$i];
            window=[pscustomobject]@{handle=$uis[$i].witness.handle;pid=$uis[$i].pid;exists=$true;visible=$true;enabled=$true;processMainHandle=$uis[$i].witness.handle}}}
    }
    $b.exit0=[pscustomobject]@{pid=$uis[0].pid;startedAtTicks=$uis[0].startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 20);remainingUi=1;survivors=@($uis[1]);kind='ordinary-intermediate-no-restart'}
    $b.exitFinal=[pscustomobject]@{pid=$uis[1].pid;startedAtTicks=$uis[1].startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 32);remainingClients=0;kind='ordinary-no-restart'}
    $b.barrierAll=New-AcceptanceBarrier $uis 4 $base.treeSha256;$b.barrierAfter0=New-AcceptanceBarrier @($uis[1]) 21 $base.treeSha256
    $b.currentBefore=$old;$b.currentAfter=$new;$b.targetBefore=$new;$b.targetAfter=$new;$b.userBefore=$user;$b.userAfter=$user
    $b.productionHelperHash='c'*64;$b.updateLog='- '+(Get-FixtureTime 36)+' PHASE_COMMITTED'
    $p=[pscustomobject]@{schemaVersion=1;cellKey=('two-clients/B1/'+$Client+'/ascii/SESSION');root=$root;baselineObservedAt=(Get-FixtureTime 0);readyObservedAt=(Get-FixtureTime 3);
        currentBefore=$old;targetBefore=$new;targetAfter=$new;userBefore=$user;userAfter=$user;readyManifest=$target;readyTree=$new;
        allAlive=(New-TwoWait $uis 4 $old);peerAlive=(New-TwoWait @($uis[1]) 21 $old);
        completion=[pscustomobject]@{observedAt=(Get-FixtureTime 40);lastInstall=[pscustomobject]@{outcome='UPDATED';targetCommitSha=$target.commitSha};
            currentAfter=$new;processes=@();leases=@();requests=@();journalPresent=$false;completedJournalPresent=$false};
        cleanup=[pscustomobject]@{observedAt=(Get-FixtureTime 46);errors=@();processes=@();helpers=@();registryUnchanged=$true;forcedCleanup=$false}}
    return [pscustomobject]@{bundle=$b;independent=$p;base=$base;target=$target}
}

# Запрещённые native/network/write операции не могут случайно выполняться через dependencies.
function Start-Process {throw 'TWO_FIXTURE_FORBIDDEN_PROCESS'}
function Get-CimInstance {throw 'TWO_FIXTURE_FORBIDDEN_NATIVE'}
function Get-NetTCPConnection {throw 'TWO_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-RestMethod {throw 'TWO_FIXTURE_FORBIDDEN_NETWORK'}
function Start-Sleep {throw 'TWO_FIXTURE_FORBIDDEN_SLEEP'}
foreach ($client in 'fx','swing','web') {
    $f=New-TwoFixture $client;$before=ConvertTo-Json -Depth 64 -InputObject $f.bundle
    $r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ('c'*64)
    Assert-TwoFixture ($r.contractStatus -ceq 'SATISFIED') ('PURE_'+$client+' '+($r.contradictions -join ';')+' '+($r.missing -join ';'))
    Assert-TwoFixture ($null -eq $r.PSObject.Properties['status'] -and (ConvertTo-Json -Depth 64 -InputObject $f.bundle) -ceq $before) 'PURE_NO_NATIVE_PASS_NO_MUTATION'
}
foreach ($key in @((New-TwoFixture).bundle.Keys | Where-Object {$_ -cnotlike 'close*'})) {
    $f=New-TwoFixture;$f.bundle[$key]=$null
    $r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ('c'*64)
    Assert-TwoFixture ($r.contractStatus -ceq 'PENDING') ('MISSING_'+$key+' '+($r.contradictions -join ';'))
}
$f=New-TwoFixture;$r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $null ('c'*64)
Assert-TwoFixture ($r.contractStatus -ceq 'PENDING' -and $r.missing -ccontains 'independent') 'HELPER_PASS_WITHOUT_INDEPENDENT_PENDING'
$mutations=@(
    @{code='TWO_ACCEPT_ROW';change={param($f) $f.bundle.cell.status='FAIL'}},
    @{code='TWO_ACCEPT_ROOT_VARIANT';change={param($f) $f.bundle.cell.path='unicode'}},
    @{code='TWO_ACCEPT_EXIT_CODE';change={param($f) $f.bundle.cell.exitCode='0'}},
    @{code='TWO_ACCEPT_FRESH_TIME';change={param($f) $f.independent.peerAlive[0].aliveBefore[0].ui.observedAt=Get-FixtureTime 2}},
    @{code='COLD_PROCESS_IDENTITY';change={param($f) $f.independent.peerAlive[0].aliveAfter[0].identity.StartedAtTicks++}},
    @{code='COLD_INVENTORY';change={param($f) $f.independent.peerAlive[0].treeFiles=$f.target.files}},
    @{code='TWO_ACCEPT_REPLACEMENT_WHILE_PEER_ALIVE';change={param($f) $f.independent.peerAlive[0].replacementPhases=@('INSTALLING')}},
    @{code='TWO_ACCEPT_REPLACEMENT_WHILE_PEER_ALIVE';change={param($f) $f.independent.peerAlive[0].lastInstallPresent=$true}},
    @{code='TWO_ACCEPT_WAIT_SAMPLES';change={param($f) $f.independent.peerAlive=@($f.independent.peerAlive[0])}},
    @{code='TWO_ACCEPT_FIRST_EXIT';change={param($f) $f.bundle.exit0.kind='killed'}},
    @{code='TWO_ACCEPT_EARLY_REPLACEMENT';change={param($f) $f.bundle.updateLog='- '+(Get-FixtureTime 22)+' PHASE_INSTALLING'}},
    @{code='TWO_ACCEPT_HELPER_PIN';change={param($f) $f.bundle.productionHelperHash='e'*64}},
    @{code='TWO_ACCEPT_MANIFEST';change={param($f) $f.target.releaseNumber='2'}},
    @{code='TWO_ACCEPT_INDEPENDENT_CONTEXT';change={param($f) $f.independent.root='C:\wrong'}},
    @{code='TWO_ACCEPT_COMPLETION';change={param($f) $f.independent.completion.processes=@(5001)}},
    @{code='TWO_ACCEPT_CLEANUP';change={param($f) $f.independent.cleanup.helpers=@(999)}},
    @{code='TWO_ACCEPT_CLEANUP';change={param($f) $f.independent.cleanup.registryUnchanged=$false}},
    @{code='TWO_ACCEPT_CLEANUP';change={param($f) $f.independent.cleanup.forcedCleanup=$true}},
    @{code='TWO_ACCEPT_READY_PIN';change={param($f) $f.independent.readyManifest=$f.base}},
    @{code='TWO_ACCEPT_FRESH_BINDING_lease';change={param($f) $f.independent.peerAlive[0].aliveAfter[0].ui.lease.leaseId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'}}
)
foreach ($case in $mutations) {
    $f=New-TwoFixture;& $case.change $f
    $r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ('c'*64)
    Assert-TwoFixture ($r.contractStatus -ceq 'FAIL' -and ($r.contradictions -join ';') -clike ('*'+$case.code+'*')) ($case.code+' '+($r.contradictions -join ';'))
}
foreach ($field in 'cleanup','readyTree','peerAlive','completion') {
    $f=New-TwoFixture;$f.independent.PSObject.Properties.Remove($field)
    $r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ('c'*64)
    Assert-TwoFixture ($r.contractStatus -ceq 'PENDING') ('MISSING_NESTED_'+$field)
}
foreach ($change in @(
    {param($f) $f.independent.peerAlive[0].aliveAfter[0].identity.PSObject.Properties.Remove('StartedAtTicks')},
    {param($f) $f.independent.peerAlive[0].aliveAfter[0].ui.witness.PSObject.Properties.Remove('handle')},
    {param($f) $f.bundle.exitFinal.PSObject.Properties.Remove('remainingClients')},
    {param($f) $f.independent.cleanup.PSObject.Properties.Remove('forcedCleanup')}
)) {
    $f=New-TwoFixture;& $change $f
    $r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ('c'*64)
    Assert-TwoFixture ($r.contractStatus -ceq 'PENDING') ('MISSING_DEEP_PENDING '+($r.contradictions -join ';'))
}
$f=New-TwoFixture;$f.bundle.cell.status='FAIL'
$r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $null ('c'*64)
Assert-TwoFixture ($r.contractStatus -ceq 'FAIL') 'CONTRADICTION_OUTRANKS_ABSENCE'
$f=New-TwoFixture
$r=Test-NativeTwoClientReceiptContract $f.bundle $f.base $f.target $f.independent ''
Assert-TwoFixture ($r.contractStatus -ceq 'PENDING' -and $r.missing -ccontains 'fresh helper pin') 'MISSING_HELPER_PIN_NOT_CONTRADICTION'

# Public API проверяет настоящий contract; только disk leaf readers mocks, без записи artifacts.
& {
    $script:diskFixture=New-TwoFixture;$script:diskReads=[Collections.Generic.List[string]]::new()
    function Import-NativeTwoClientAcceptanceGuards {}
    function Read-NativeAcceptanceJson($Path,$ExpectedSha256='') {
        $script:diskReads.Add($Path)
        $f=$script:diskFixture;$name=[IO.Path]::GetFileName($Path)
        switch ($name) {
            'base.json' {return $f.base} 'target.json' {return $f.target} 'two-client-independent.json' {return $f.independent}
            'launch-0.json' {return $f.bundle.launch0} 'launch-1.json' {return $f.bundle.launch1}
            'barrier-all.json' {return $f.bundle.barrierAll} 'barrier-after-exit-0.json' {return $f.bundle.barrierAfter0}
            'exit-final.json' {return $f.bundle.exitFinal} 'exit-5001.json' {return $f.bundle.exit0}
            default {
                if ($name -clike 'physical-close-window-5001-*') {return $f.bundle.close0}
                if ($name -clike 'physical-close-window-5002-*') {return $f.bundle.close1}
                return $f.bundle[[IO.Path]::GetFileNameWithoutExtension($name)]
            }
        }
    }
    function Test-Path {return $true}
    function Resolve-PortableSafetyPath($Path) {return $Path}
    function Get-Item {return [pscustomobject]@{Length=100}}
    function Get-Content {return $script:diskFixture.bundle.updateLog}
    function Get-FileHash {return [pscustomobject]@{Hash=('c'*64)}}
    $args=@('C:\synthetic-evidence','C:\base.json',('a'*64),'C:\target.json',('b'*64),('c'*64),'C:\synthetic-supplement')
    $r=Test-NativeTwoClientAcceptance @args -IndependentSha256 ('d'*64) -EvidenceKind UNIT_MOCK
    Assert-TwoFixture ($r.status -ceq 'PENDING' -and -not $r.proofComplete -and $r.contradictions.Count -eq 0) ('MOCK_NEVER_NATIVE_PASS '+($r.contradictions -join ';'))
    $r=Test-NativeTwoClientAcceptance @args -IndependentSha256 ('d'*64)
    Assert-TwoFixture ($r.status -ceq 'PENDING') 'UNVERIFIED_DEFAULT'
    $r=Test-NativeTwoClientAcceptance @args -EvidenceKind UNIT_MOCK
    Assert-TwoFixture ($r.status -ceq 'PENDING') 'NO_INDEPENDENT_PIN_PENDING'
    $r=Test-NativeTwoClientAcceptance @args -IndependentSha256 'not-a-pin' -EvidenceKind UNIT_MOCK
    Assert-TwoFixture ($r.status -ceq 'FAIL') 'MALFORMED_PIN_FAIL'
    Assert-TwoFixture ($script:diskReads -ccontains 'C:\synthetic-supplement\two-client-independent.json') 'BRIDGE_FIXED_INDEPENDENT_PATH'
}
# Frozen bridge gap и реальный helper producer scope явно зафиксированы, не скрыты новым validator.
Assert-TwoFixture ($asts['NativeUpdateAcceptanceDispatch.ps1'].Extent.Text.Contains("'Test-NativeTwoClientAcceptance' 'CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory/IndependentSha256/EvidenceKind'") -and $asts['NativeUpdateAcceptanceDispatch.ps1'].Extent.Text.Contains('TWO_CLIENT_OBSERVATIONS_NOT_PROVEN')) 'BRIDGE_EXPLICIT_FROZEN_TWO_CLIENT_API_AND_PENDING_GUARD'
Assert-TwoFixture (-not $asts['NativeUpdateConcurrentScenarios.ps1'].Extent.Text.Contains('two-client-independent.json')) 'INDEPENDENT_PRODUCER_STILL_REQUIRED'
foreach ($file in $pins.Keys) {Assert-TwoFixture ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -ceq $pins[$file]) ('SOURCE_FROZEN_'+$file)}
Write-Host ('Two-client acceptance fixtures PASS: '+$script:checks+' checks; pure/AST/mocks only; nativeExecuted=false; no native PASS')
Write-Host ('Acceptor SHA256: '+(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'NativeUpdateTwoClientAcceptance.ps1')).Hash.ToLowerInvariant())
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
