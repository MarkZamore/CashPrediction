<#
.SYNOPSIS
Проверяет review patch только на собственных Temp AST копиях.
.DESCRIPTION
Не применяет patch к repository и не запускает тела runners, Java, native или GUI.
Mock успех означает только AST_AND_MOCK_INTEGRATION_REVIEW_ONLY, не native PASS.
Frozen two-client acceptor проверяется отдельно; незамороженный producer не подменяется.
#>
#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
$script:reviewChecks=0
$script:events=[Collections.Generic.List[string]]::new()
$savedReadyGlobal=Get-Item Function:global:Add-NativeReadyCollectedObservation -ErrorAction SilentlyContinue
$readyGlobalInstalled=$false
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$patchPath=Join-Path $repository '.claude/scratch/s7-lifecycle-acceptance-integration.patch'
$before=@{}
$script:owned=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('cp-acceptance-review-'+[guid]::NewGuid())))
$script:copies=Join-Path $script:owned '.github/scripts'

# Каждое утверждение относится только к fixture, не к native evidence.
function Assert-Review([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('REVIEW_ASSERT '+$Code)}
    $script:reviewChecks++
}

# Отказ должен иметь точный ожидаемый код, а не произвольную ошибку mock.
function Reject-Review([scriptblock]$Action,[string]$Code) {
    $caught=$null
    try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-Review ($caught -ceq $Code) ('reject '+$Code+' actual='+$caught)
}

# Любая запись ограничена собственным UUID root, без links и overwrite по умолчанию.
function Write-ReviewOwned([string]$Path,[string]$Text,[switch]$Replace) {
    $full=[IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($script:owned+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'REVIEW_PATH_ESCAPE'}
    $ancestor=[IO.Path]::GetDirectoryName($full)
    while ($ancestor -and $ancestor.StartsWith($script:owned,[StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'REVIEW_LINK'}
        }
        $ancestor=[IO.Path]::GetDirectoryName($ancestor)
    }
    if ((Test-Path -LiteralPath $full) -and -not $Replace) {throw 'REVIEW_OUTPUT_EXISTS'}
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($full))
    if (Test-Path -LiteralPath $full) {
        if ((Get-Item -LiteralPath $full).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'REVIEW_LINK'}
    }
    [IO.File]::WriteAllText($full,$Text,[Text.UTF8Encoding]::new($false))
}

# Parser возвращает только AST; тело файла никогда не исполняется.
function Read-ReviewAst([string]$Path) {
    $tokens=$null;$issues=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$issues)
    Assert-Review ($issues.Count -eq 0) ('parse '+[IO.Path]::GetFileName($Path))
    return $ast
}

# Импорт только top-level definitions в scope fixture, с AST заменой PSScriptRoot.
function Import-ReviewFunctions([string]$Path,[string[]]$Names=@()) {
    $ast=Read-ReviewAst $Path
    foreach ($def in $ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]}) {
        if ($Names.Count -and $def.Name -cnotin $Names) {continue}
        $text=$def.Extent.Text
        foreach ($node in $def.FindAll({param($n) $n -is [Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object {$_.Extent.StartOffset} -Descending) {
            $text=$text.Remove($node.Extent.StartOffset-$def.Extent.StartOffset,$node.Extent.Text.Length).Insert($node.Extent.StartOffset-$def.Extent.StartOffset,(''''+$script:copies.Replace("'","''")+''''))
        }
        $text=$text -replace ('^function\s+'+[regex]::Escape($def.Name)+'(?=[\s(])'),('function script:'+$def.Name)
        . ([scriptblock]::Create($text))
    }
}

# Строгая реконструкция unified diff: все old context строки и оба размера hunk обязательны.
function Expand-ReviewPatch([string]$Text) {
    $lines=$Text.Replace("`r`n","`n").TrimEnd("`n").Split("`n")
    $result=@{};$i=0
    while ($i -lt $lines.Count) {
        if ($lines[$i] -cnotmatch '^diff --git a/(.+) b/(.+)$' -or $Matches[1] -cne $Matches[2]) {throw 'REVIEW_DIFF_HEADER'}
        $file=$Matches[1]
        if ($file -cnotin @('.github/scripts/NativeUpdateScenarioDispatch.ps1','.github/scripts/Test-NativeUpdateLifecycle.ps1','.github/scripts/NativeUpdateRollbackAcceptanceCollector.ps1') -or $result.ContainsKey($file)) {throw 'REVIEW_DIFF_SCOPE'}
        $path=Join-Path $repository $file
        $before[$path]=(Get-FileHash -LiteralPath $path).Hash
        $old=[IO.File]::ReadAllText($path).Replace("`r`n","`n").TrimEnd("`n").Split("`n")
        $output=[Collections.Generic.List[string]]::new();$position=0;$i++
        while ($i -lt $lines.Count -and $lines[$i] -cnotmatch '^@@ ') {$i++}
        while ($i -lt $lines.Count -and $lines[$i] -cnotmatch '^diff --git ') {
            if ($lines[$i] -cnotmatch '^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@') {throw 'REVIEW_HUNK_HEADER'}
            $start=[int]$Matches[1]-1;$oldCount=if ($Matches[2]) {[int]$Matches[2]} else {1}
            $newStart=[int]$Matches[3]-1;$newCount=if ($Matches[4]) {[int]$Matches[4]} else {1}
            if ($start -lt $position -or $start -gt $old.Count) {throw 'REVIEW_HUNK_OFFSET'}
            while ($position -lt $start) {$output.Add($old[$position]);$position++}
            if ($output.Count -ne $newStart) {throw 'REVIEW_NEW_OFFSET'}
            $consumed=0;$produced=0;$i++
            while ($i -lt $lines.Count -and $lines[$i] -cnotmatch '^(?:@@ |diff --git )') {
                $line=$lines[$i];if (-not $line.Length) {throw 'REVIEW_HUNK_LINE'}
                $kind=$line[0];$value=$line.Substring(1)
                if ($kind -cin @(' ','-')) {
                    if ($position -ge $old.Count -or $old[$position] -cne $value) {throw ('REVIEW_BASE_DRIFT '+$file)}
                    $position++;$consumed++
                }
                if ($kind -cin @(' ','+')) {$output.Add($value);$produced++}
                elseif ($kind -cne '-') {throw 'REVIEW_HUNK_KIND'}
                $i++
            }
            Assert-Review ($consumed -eq $oldCount -and $produced -eq $newCount) ('hunk counts '+$file)
        }
        while ($position -lt $old.Count) {$output.Add($old[$position]);$position++}
        $result[$file]=($output -join "`n")+"`n"
    }
    Assert-Review ($result.Count -eq 3) 'exact three patch targets'
    return $result
}

# Запреты защищают fixture от случайного выхода в native workflow.
function Start-Process {throw 'REVIEW_NATIVE_FORBIDDEN'}
function Start-NativeOwned {throw 'REVIEW_NATIVE_FORBIDDEN'}
function Start-NativeFixture {throw 'REVIEW_NATIVE_FORBIDDEN'}
function Invoke-ColdTool {throw 'REVIEW_JAVA_FORBIDDEN'}
function Invoke-NativeUpdateEvidenceSignoff {throw 'REVIEW_CLI_FORBIDDEN'}

try {
    if (Test-Path -LiteralPath $script:owned) {throw 'REVIEW_TEMP_EXISTS'}
    [void][IO.Directory]::CreateDirectory($script:owned)
    $before[$patchPath]=(Get-FileHash -LiteralPath $patchPath).Hash
    $proposed=Expand-ReviewPatch ([IO.File]::ReadAllText($patchPath))
    foreach ($file in $proposed.Keys) {Write-ReviewOwned (Join-Path $script:owned $file) $proposed[$file]}
    Import-ReviewFunctions (Join-Path $script:copies 'NativeUpdateScenarioDispatch.ps1')
    $inventory=@(Get-NativeDispatchFiles)
    Assert-Review ($inventory.Count -eq 21 -and @($inventory | Select-Object -Unique).Count -eq 21) 'expanded fixed inventory'
    foreach ($required in @('NativeUpdateAcceptanceDispatch.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateReadyAcceptance.ps1',
        'NativeUpdatePhaseAcceptance.ps1','NativeUpdateRollbackAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1',
        'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateRollbackAcceptanceCollector.ps1','Test-NativeUpdateEvidenceSignoff.ps1')) {
        Assert-Review ($required -cin $inventory) ('acceptance source pinned '+$required)
    }
    foreach ($file in $inventory + @('NativeUpdateScenarioDispatch.ps1','NativeUpdateTwoClientAcceptance.ps1')) {
        $source=Join-Path $PSScriptRoot $file;$before[$source]=(Get-FileHash -LiteralPath $source).Hash
        if (-not (Test-Path -LiteralPath (Join-Path $script:copies $file))) {Write-ReviewOwned (Join-Path $script:copies $file) ([IO.File]::ReadAllText($source))}
    }
    $canonical='ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'
    $javaSource=Join-Path $script:owned $canonical
    $before[(Join-Path $repository $canonical)]=(Get-FileHash -LiteralPath (Join-Path $repository $canonical)).Hash
    Write-ReviewOwned $javaSource ([IO.File]::ReadAllText((Join-Path $repository $canonical)))
    $pins=@{}
    foreach ($file in $inventory) {
        $null=Read-NativeDispatchFunctions $script:copies $file
        $pins[$file]=(Get-FileHash -LiteralPath (Join-Path $script:copies $file)).Hash
    }
    $script:nativeDispatchState=[pscustomobject]@{SourceScriptsRoot=$script:copies;Pins=$pins;CanonicalSource=$javaSource;CanonicalSha256=(Get-FileHash -LiteralPath $javaSource).Hash}
    Assert-NativeDispatchFrozen
    foreach ($file in @('NativeUpdateAcceptanceDispatch.ps1','NativeUpdateReadyAcceptanceCollector.ps1','Test-NativeUpdateEvidenceSignoff.ps1')) {
        $path=Join-Path $script:copies $file;$text=[IO.File]::ReadAllText($path)
        Write-ReviewOwned $path ($text+"`n# fixture drift") -Replace
        Reject-Review {Assert-NativeDispatchFrozen} ('DISPATCH_SOURCE_CHANGED '+$file)
        Write-ReviewOwned $path $text -Replace
    }
    Assert-NativeDispatchFrozen
    Import-ReviewFunctions (Join-Path $script:copies 'Test-NativeUpdateLifecycle.ps1') @('Get-NativeEvidencePlan','Get-NativeLifecycleSelectedRows','Invoke-NativeLifecycleDispatchedCell')
    Import-ReviewFunctions (Join-Path $script:copies 'Test-UpdateBootstrap.ps1') @('Get-ColdSelectedPlan')
    $lifeAst=Read-ReviewAst (Join-Path $script:copies 'Test-NativeUpdateLifecycle.ps1')
    $plan=@(Get-NativeEvidencePlan $javaSource);$scenarios=@($plan.scenario | Select-Object -Unique)
    Assert-Review ($plan.Count -eq 612 -and $scenarios.Count -eq 22) 'canonical 22/612'
    $limit=@($lifeAst.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'MaxCells'})[0]
    Assert-Review ($limit.DefaultValue.SafeGetValue() -eq 144) 'legacy default144'
    $collection=@($lifeAst.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'CollectAcceptance'})[0]
    Assert-Review ($null -eq $collection.DefaultValue) 'collection opt-in only'
    $scenarioParameter=@($lifeAst.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'Scenario'})[0]
    Assert-Review (@(Get-NativeLifecycleSelectedRows $plan @($scenarioParameter.DefaultValue.SafeGetValue()) @() 144 180).Count -eq 144) 'actual legacy default selection'
    $keys=@($plan | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase});[array]::Reverse($keys)
    $selected=@(Get-NativeLifecycleSelectedRows $plan $scenarios $keys 612 180)
    Assert-Review ($selected.Count -eq 612 -and [object]::ReferenceEquals($selected[0],$plan[0]) -and [object]::ReferenceEquals($selected[611],$plan[611])) 'exact filters canonical order no truncation'
    foreach ($scenario in @('journal-fault','per-move-fault')) {Assert-Review (@($selected | Where-Object scenario -CEQ $scenario).Count -eq 126) ('seven phases '+$scenario)}
    Reject-Review {Get-NativeLifecycleSelectedRows $plan $scenarios $keys 611 180} 'NATIVE_CELL_BOUND'
    Assert-Review (@($plan | Where-Object status -CNE 'PENDING').Count -eq 0) 'all unexecuted pending'

    # Реальный Ready collector: mock helper видит hook ДО crash/wait, finally восстанавливает его.
    Import-ReviewFunctions (Join-Path $script:copies 'NativeUpdateReadyAcceptanceCollector.ps1') @('Invoke-NativeReadyAcceptedCell')
    function Get-PortableRealRegistrySnapshot {'fixture-registry'}
    function Add-NativeReadyCollectedObservation($State,[string]$Event,$Data) {$script:events.Add('ready-hook:'+ $Event)}
    # GetNewClosure создаёт dynamic module и не видит private функции файла при запуске через &.
    # Экспортируется только mock callback с captured журналом; прежний global всегда восстанавливается.
    $readyEventLog=$script:events
    Set-Item Function:global:Add-NativeReadyCollectedObservation ({param($State,[string]$Event,$Data) $readyEventLog.Add('ready-hook:'+ $Event)}.GetNewClosure())
    $readyGlobalInstalled=$true
    function Complete-NativeReadyCollectedObservation($State,$Row) {$script:events.Add('ready-after-cleanup');return [pscustomobject]@{testOnly=$true}}
    function Write-ColdJson($Path,$Value) {Write-ReviewOwned $Path (ConvertTo-Json -InputObject $Value -Depth 30)}
    function Test-NativeReadyAcceptance($Row,$Receipt,$Base,$Target,[string]$ReceiptSha256,[string]$IndependentFile,[string]$IndependentSha256) {return [pscustomobject]@{status='PENDING';cellEvidenceValidated=$false}}
    function Invoke-NativeReadyScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
        if ($script:nativeAcceptanceContext.enabled) {
            Assert-Review ($null -ne $script:nativeReadyObservationHook) 'ready actual hook before helper'
            & $script:nativeReadyObservationHook READY $null
        }
        $script:events.Add('ready-helper-crash-wait');$script:events.Add('ready-cleanup')
        $Row.status='PENDING';$Row.reason='fixture-ready'
        return [pscustomobject]@{testOnly=$true}
    }
    # Dispatcher import/native boundary замещается только в fixture scope, не в copied файлах.
    function Import-NativeDispatchRoute([string]$SourceScriptsRoot,[string]$Route) {$script:events.Add('import:'+ $Route)}
    function Invoke-NativeConcurrentScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {$script:events.Add('plain-concurrent');$Row.status='PASS';$Row.reason='mock-helper';return 'raw-concurrent'}
    function Invoke-NativePhaseScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {$script:events.Add('phase-helper-cleanup');$Row.status='PASS';$Row.reason='mock-helper';return 'raw-phase'}
    function Invoke-NativePayloadCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {$script:events.Add('payload-helper-cleanup');$Row.status='PASS';$Row.reason='mock-helper';return 'raw-payload'}
    function Invoke-NativeCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {$script:events.Add('normal-helper');return 'raw-normal'}
    function Invoke-NativeUpdateAcceptanceDispatch($Row,$Envelope,$Base,$Target,$Cold,[string]$Java,$Evidence,[string]$EvidenceKind) {
        $script:events.Add('bridge:'+ $Row.scenario)
        Assert-Review ($Row.status -ceq 'PENDING' -and $null -ne $Row.PSObject.Properties['dispatchHelperStatus']) 'pre-demotion provenance retained'
        if ($Row.scenario -ceq 'three-clients-pid-root-isolation') {Assert-Review ($Evidence.BaseSha256 -ceq $Cold.baseManifests[0].sha256) 'independent concurrent pins'}
        if ($Row.scenario -ceq 'cashmemory') {Assert-Review ($null -eq $Evidence) 'payload Expected not derived from helper'}
        if ($Row.scenario -ceq 'locked-rollback') {Assert-Review (-not $disposeLog.Contains('dispose')) 'actual dispatcher bridge before witness Dispose'}
        return [pscustomobject]@{status=$script:mockVerdictStatus;scope='SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY';cellKey=($Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase);
            canonicalRowUnchanged=$true;evidenceKind='UNIT_MOCK';routeEvidenceValidated=$false;gaps=@('MOCK_NOT_NATIVE');contradictions=@();envelope=$Envelope}
    }
    $script:nativeTarget='fixture-target';$script:nativeProject='fixture-project';$script:nativeProfile='fixture-profile'
    $cold=[pscustomobject]@{baseManifests=@([pscustomobject]@{portableDir='fixture-source';manifest='base';sha256=('a'*64)});targetManifest='target';targetManifestSha256=('b'*64);helperScript='helper';helperSha256=('c'*64)}
    $script:nativeAcceptanceContext=[pscustomobject]@{enabled=$true;payloadExpectedByCell=@{}}
    $script:mockVerdictStatus='PENDING'

    # Настоящий concurrent adapter исполняет только специально сгенерированное mock тело.
    Import-ReviewFunctions (Join-Path $script:copies 'NativeUpdateConcurrentAcceptance.ps1') @('Invoke-NativeConcurrentScenarioWithAcceptance','Get-NativeConcurrentCollectedDefinition')
    function Resolve-PortableSafetyPath([string]$Path) {return $Path}
    $generated=Get-NativeConcurrentCollectedDefinition $pins['NativeUpdateConcurrentScenarios.ps1'].ToLowerInvariant()
    foreach ($stage in @('PrimaryBefore','PeerBefore','Ready','Installed','After')) {Assert-Review ($generated.Contains('Save-NativeConcurrentAcceptanceSnapshot -Stage '+$stage)) ('real AST hook '+$stage)}
    $readyAt=$generated.IndexOf('Save-NativeConcurrentAcceptanceSnapshot -Stage Ready')
    Assert-Review ($readyAt -ge 0 -and $readyAt -lt $generated.IndexOf('$proofs=[Collections.Generic.List[string]]::new()')) 'concurrent hook before waits/proofs'
    function Import-NativeConcurrentAcceptanceGuards {}
    function Read-NativeAcceptanceJson([string]$Path,[string]$ExpectedSha256='') {return [pscustomobject]@{releaseNumber=1;commitSha='fixture';treeSha256='fixture';files=@()}}
    function Assert-NativeAcceptanceTree($Files,$Manifest) {}
    function Get-NativeConcurrentCollectedDefinition([string]$ExpectedConcurrentSha256) {
        $script:events.Add('concurrent-register-local-AST')
        return 'function Invoke-NativeConcurrentCollectedScenario($Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout) {$script:events.Add("concurrent-helper-wait");$Row | Add-Member concurrentCellEvidence "fixture-cell" -Force;$Row.status="PASS";$Row.reason="mock-helper";$script:events.Add("concurrent-finally-cleanup")}'
    }
    function Test-NativeConcurrentAcceptance {param($CellEvidence,$BaseManifest,$BaseSha256,$TargetManifest,$TargetSha256,$ExpectedHelperSha256) $script:events.Add('concurrent-accept-after-cleanup');return [pscustomobject]@{status='PENDING'}}
    function Write-NativeAcceptanceArtifact([string]$Directory,[string]$Name,$Value) {$script:events.Add('concurrent-receipt')}
    $manifest=[pscustomobject]@{releaseNumber=1;commitSha='fixture';treeSha256='fixture';files=@()}
    Assert-NativeCollectedAdapter 'Invoke-NativeConcurrentScenarioWithAcceptance' 'Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/AcceptancePins'
    Assert-NativeDispatchCommand 'Invoke-NativeReadyAcceptedCell'
    foreach ($scenario in @('abrupt-ready-restart','three-clients-pid-root-isolation','two-clients','journal-fault','cashmemory','delta')) {
        $row=@($plan | Where-Object scenario -CEQ $scenario)[0] | Select-Object *
        $dir=Join-Path $script:owned $scenario;Write-ReviewOwned (Join-Path $dir 'command.json') '{}'
        $row | Add-Member evidenceDirectory $dir;$row | Add-Member command (Join-Path $dir 'command.json')
        $script:events.Clear()
        $output=@(Invoke-NativeLifecycleDispatchedCell $row 'fixture-source' $manifest $manifest $null $cold 'FORBIDDEN-JAVA' $script:owned 180)
        Assert-Review ($row.status -ceq 'PENDING') ('mock remains pending '+$scenario)
        Assert-Review ($output.Count -eq 1 -and $output[0].helperReceipts.Count -eq 1) ('raw receipt retained '+$scenario)
        $json=ConvertTo-Json -InputObject $output[0] -Depth 60 -Compress
        Assert-Review ($json.Length -lt 20000) ('acyclic envelope '+$scenario)
        if ($scenario -ceq 'abrupt-ready-restart') {
            Assert-Review (($script:events -join '/') -ceq 'import:ready/ready-hook:READY/ready-helper-crash-wait/ready-cleanup/ready-after-cleanup/bridge:abrupt-ready-restart') 'actual ready chronology'
            Assert-Review ($null -eq (Get-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue)) 'ready restoration'
        }
        if ($scenario -ceq 'three-clients-pid-root-isolation') {Assert-Review (($script:events -join '/') -ceq 'import:concurrent/concurrent-register-local-AST/concurrent-helper-wait/concurrent-finally-cleanup/concurrent-accept-after-cleanup/concurrent-receipt/bridge:three-clients-pid-root-isolation') 'actual concurrent adapter chronology'}
        if ($scenario -ceq 'two-clients') {Assert-Review (-not $script:events.Contains('concurrent-register-local-AST')) 'two-client producer not fabricated'}
        if ($scenario -ceq 'delta') {Assert-Review ($null -eq $output[0].PSObject.Properties['acceptanceVerdict']) 'normal legacy no bridge'}
    }
    foreach ($verdictStatus in @('PASS','FAIL')) {
        $script:mockVerdictStatus=$verdictStatus
        $row=@($plan | Where-Object scenario -CEQ 'journal-fault')[0] | Select-Object *
        $code=if ($verdictStatus -ceq 'PASS') {'ACCEPTANCE_INCOMPLETE_PASS'} else {'ACCEPTANCE_CONTRADICTION: '}
        Reject-Review {Invoke-NativeLifecycleDispatchedCell $row 'fixture-source' $manifest $manifest $null $cold 'FORBIDDEN-JAVA' $script:owned 180} $code
        Assert-Review ($row.status -ceq 'FAIL') ('unvalidated verdict never promotes '+$verdictStatus)
    }
    $script:mockVerdictStatus='PENDING'
    $script:nativeAcceptanceContext.enabled=$false
    foreach ($scenario in @('abrupt-ready-restart','three-clients-pid-root-isolation','cashmemory')) {
        $row=@($plan | Where-Object scenario -CEQ $scenario)[0] | Select-Object *;$script:events.Clear()
        $output=@(Invoke-NativeScenarioDispatch $row 'fixture-source' $manifest $manifest $null $cold 'FORBIDDEN-JAVA' $script:owned 180)
        Assert-Review ($row.status -ceq 'PENDING' -and $null -eq $output[0].PSObject.Properties['acceptanceVerdict']) ('legacy disabled '+$scenario)
        Assert-Review (-not (($script:events -join '/') -match 'bridge:|register|ready-hook')) ('no collector disabled '+$scenario)
    }

    # Реальный rollback collector: mocked module не запускает process, retained handle только fixture объект.
    Import-ReviewFunctions (Join-Path $script:copies 'NativeUpdateRollbackAcceptanceCollector.ps1') @('Invoke-NativeRollbackCollectedAcceptance')
    function New-NativeRollbackWriteBoundaryHelper([string]$InputText) {return $InputText}
    function Open-NativeRollbackAcceptanceHandle($Native,[string]$Root,[scriptblock]$ReadIdentity) {return $Native}
    function Test-NativeRollbackAcceptance($Row,$Authority,$RetainedHelper) {$script:events.Add('rollback-accept-after-cleanup');return [pscustomobject]@{status='PENDING';nativePass=$false}}
    $disposeLog=[Collections.Generic.List[string]]::new()
    $fake=[pscustomobject]@{log=$disposeLog};$fake | Add-Member ScriptMethod Dispose {$this.log.Add('dispose')}
    $witness=[pscustomobject]@{process=$fake;identity=[pscustomobject]@{testOnly=$true}}
    $module=New-Module -ScriptBlock {
        param($Witness,$Log)
        $script:witness=$Witness;$script:log=$Log
        function New-NativeRollbackHelperText($Original,$Scenario,$FaultPath,$Timeout) {'mock-renderer'}
        function Start-NativeRollbackOwned($Executable,$Arguments,$Root,$Endpoint='',[switch]$Web) {$script:witness}
        function Set-NativeRollbackContext([string]$TargetRoot,[string]$ProjectRoot,[string]$ProfileRoot) {$script:log.Add('context-before-collector')}
        function Invoke-NativeRollbackScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
            $script:log.Add('helper-before-wait')
            [void](New-NativeRollbackHelperText 'fixture' $Row.scenario '' $Timeout)
            [void](Start-NativeRollbackOwned (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') @('-EncodedCommand','fixture') $Source)
            $script:log.Add('helper-cleanup')
            $Row | Add-Member status 'PASS' -Force;$Row | Add-Member reason 'mock-helper' -Force
        }
        Export-ModuleMember -Function Set-NativeRollbackContext,Invoke-NativeRollbackScenario
    } -ArgumentList $witness,$disposeLog
    $original=& $module {(Get-Command New-NativeRollbackHelperText).ScriptBlock.ToString()}
    foreach ($mode in @('callback','throw','legacy')) {
        $disposeLog.Clear();$row=[pscustomobject]@{scenario='locked-rollback';evidenceRoot=(Join-Path $script:owned 'rollback')}
        $assertReview=${function:Assert-Review}
        $callback=if ($mode -ceq 'legacy') {$null} else {
            {param($R,$A,$W,$D) & $assertReview (-not $disposeLog.Contains('dispose')) 'callback before actual finally Dispose';$disposeLog.Add('callback');if ($mode -ceq 'throw') {throw 'REVIEW_CALLBACK_THROW'};return [pscustomobject]@{status='PENDING'}}.GetNewClosure()
        }
        if ($mode -ceq 'throw') {Reject-Review {Invoke-NativeRollbackCollectedAcceptance $module $row $script:owned $null $null $null $null '' '' 180 ([pscustomobject]@{}) -AcceptanceObserver $callback} 'REVIEW_CALLBACK_THROW'}
        else {$value=Invoke-NativeRollbackCollectedAcceptance $module $row $script:owned $null $null $null $null '' '' 180 ([pscustomobject]@{}) -AcceptanceObserver $callback;Assert-Review ($value.acceptance.status -ceq 'PENDING') ('rollback pending '+$mode)}
        Assert-Review ($disposeLog[$disposeLog.Count-1] -ceq 'dispose' -and $disposeLog[0] -ceq 'helper-before-wait' -and $disposeLog[1] -ceq 'helper-cleanup') ('rollback callback/dispose chronology '+$mode)
        Assert-Review ((& $module {(Get-Command New-NativeRollbackHelperText).ScriptBlock.ToString()}) -ceq $original) ('rollback renderer restored '+$mode)
        $remaining=& $module {Get-Command Open-NativeRollbackAcceptanceHandle -ErrorAction SilentlyContinue}
        Assert-Review ($null -eq $remaining -or $remaining.ModuleName -cne $module.Name) ('rollback temporary module hook removed '+$mode)
    }
    Import-ReviewFunctions (Join-Path $script:copies 'NativeUpdateAcceptanceDispatch.ps1') @('Copy-NativeAcceptanceDispatchRow')
    $script:nativeDispatchRollbackModule=$module
    $script:nativeAcceptanceContext.enabled=$true
    $row=@($plan | Where-Object scenario -CEQ 'locked-rollback')[0] | Select-Object *
    $row | Add-Member evidenceRoot (Join-Path $script:owned 'rollback')
    $disposeLog.Clear();$script:events.Clear()
    $output=@(Invoke-NativeLifecycleDispatchedCell $row 'fixture-source' $manifest $manifest $null $cold 'FORBIDDEN-JAVA' $script:owned 180)
    Assert-Review ($row.status -ceq 'PENDING' -and $row.dispatchHelperStatus -ceq 'PASS') 'actual rollback dispatcher retains helper provenance without promotion'
    Assert-Review ($output.Count -eq 1 -and $output[0].helperReceipts[0].bridgeVerdict.status -ceq 'PENDING') 'actual rollback dispatcher callback receipt retained'
    Assert-Review (($disposeLog -join '/') -ceq 'context-before-collector/helper-before-wait/helper-cleanup/dispose') 'rollback context before collector execution and dispose'
    Assert-Review ($script:events.Contains('bridge:locked-rollback')) 'rollback bridge really invoked inside callback'
    [void](ConvertTo-Json -InputObject $output[0] -Depth 60 -Compress)
    $script:nativeAcceptanceContext.enabled=$false
    $row=@($plan | Where-Object scenario -CEQ 'locked-rollback')[0] | Select-Object *
    $disposeLog.Clear();$script:events.Clear()
    $output=@(Invoke-NativeScenarioDispatch $row 'fixture-source' $manifest $manifest $null $cold 'FORBIDDEN-JAVA' $script:owned 180)
    Assert-Review ($row.status -ceq 'PENDING' -and -not $script:events.Contains('bridge:locked-rollback')) 'rollback legacy no acceptance'
    Assert-Review (-not $disposeLog.Contains('dispose')) 'legacy no retained collector handle'

    # Новый frozen two-client schema не меняет старый bridge и не создаёт early observations после cleanup.
    $twoAst=Read-ReviewAst (Join-Path $script:copies 'NativeUpdateTwoClientAcceptance.ps1')
    $two=@($twoAst.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Test-NativeTwoClientAcceptance'})[0]
    Assert-Review ((@($two.Parameters | ForEach-Object {$_.Name.VariablePath.UserPath}) -join '/') -ceq 'CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory/IndependentSha256/EvidenceKind') 'two-client frozen nine field API'
    Assert-Review ($two.Extent.Text.Contains('two-client-independent.json') -and $two.Extent.Text.Contains('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED')) 'two-client independent pin and origin required'
    $twoContract=@($twoAst.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Test-NativeTwoClientReceiptContract'})[0].Extent.Text
    foreach ($field in @('baselineObservedAt','readyObservedAt','allAlive','peerAlive','completion','cleanup')) {
        Assert-Review ($twoContract.Contains("'"+$field+"'")) ('two-client schema independent field '+$field)
    }
    $bridgeText=[IO.File]::ReadAllText((Join-Path $script:copies 'NativeUpdateAcceptanceDispatch.ps1'))
    Assert-Review ($bridgeText.Contains('TWO_CLIENT_ACCEPTOR_NOT_PRODUCED')) 'current bridge two-client explicit pending'

    # Выполняется только финальный Signoff guard AST, never runner orchestration или assembler CLI.
    $gate=@($lifeAst.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.IfStatementAst] -and $_.Clauses[0].Item1.Extent.Text -ceq '$Signoff'})[-1]
    Assert-Review ($gate.Extent.Text.Contains('Read-ColdPinnedJson') -and $gate.Extent.Text.Contains('NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED')) 'sealed authority not helper-derived'
    $Signoff=$true;$SignoffRequestFile='';$SignoffRequestSha256=''
    $rows=$plan
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'
    # Только in-memory unit guard rows: не записываются как matrix/native proofs.
    $rows=@($plan | Select-Object *);foreach ($r in $rows) {$r.status='PASS';$r | Add-Member executed $true}
    $rows[611].executed=$false
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'
    $rows[611].executed=$true
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_FROZEN_REQUEST_REQUIRED'
    $rows=@($rows | Select-Object -First 611)
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'
    $rows=@($plan | Select-Object *);foreach ($r in $rows) {$r.status='PASS';$r | Add-Member executed $true}
    $SignoffRequestFile='mock-request';$SignoffRequestSha256=('d'*64)
    $CommandFile='mock-cold';$CommandFileSha256=('e'*64);$LifecycleFile='mock-life';$LifecycleFileSha256=('f'*64)
    $evidence=Join-Path $script:owned 'signoff-guard'
    Write-ReviewOwned (Join-Path $evidence 'results.json') '{"testOnly":"not-a-native-matrix"}'
    $script:mockRequest=[pscustomobject]@{context=[pscustomobject]@{coldPin=[pscustomobject]@{path=$CommandFile;sha256=$CommandFileSha256};lifePin=[pscustomobject]@{path=$LifecycleFile;sha256=$LifecycleFileSha256}};batches=@()}
    function Read-ColdPinnedJson([string]$Path,[string]$ExpectedHash) {
        if ($Path -ceq 'mock-request') {return $script:mockRequest}
        return [pscustomobject]@{results=[pscustomobject]@{path='wrong-results';sha256=('0'*64)}}
    }
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED'
    $script:mockRequest.batches=@([pscustomobject]@{path='mock-seal';sha256=('1'*64)})
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED'
    $script:mockRequest.context.coldPin.sha256=('0'*64)
    Reject-Review {& ([scriptblock]::Create($gate.Extent.Text))} 'NATIVE_SIGNOFF_CONTEXT_MISMATCH'
    $signoffAst=Read-ReviewAst (Join-Path $script:copies 'Test-NativeUpdateEvidenceSignoff.ps1')
    $signoffEntry=@($signoffAst.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Invoke-NativeUpdateEvidenceSignoff'})[0].Extent.Text
    Assert-Review ($signoffEntry.IndexOf('Merge-NativeEvidenceBatches') -lt $signoffEntry.IndexOf('Invoke-SignoffJdk') -and
        $signoffEntry.IndexOf('if ($assembly.missing.Count)') -lt $signoffEntry.IndexOf('Invoke-SignoffJdk') -and $signoffEntry.Contains("throw 'SIGNOFF_INCOMPLETE'")) 'actual assembler seals/full coverage before real CLI'
    foreach ($path in $before.Keys) {Assert-Review ((Get-FileHash -LiteralPath $path).Hash -ceq $before[$path]) ('root untouched '+[IO.Path]::GetFileName($path))}
    [pscustomobject]@{status='PASS';scope='AST_AND_MOCK_INTEGRATION_REVIEW_ONLY';checks=$script:reviewChecks;native=$false;canonicalCells=612;
        powershellVersion=$PSVersionTable.PSVersion.ToString();powershellEdition=$PSVersionTable.PSEdition;
        nativeSignoff='PENDING';twoClient='PENDING_PRODUCER_NOT_FROZEN_BRIDGE_NOT_WIRED';patchSha256=$before[$patchPath]}
} finally {
    if ($readyGlobalInstalled) {
        if ($null -ne $savedReadyGlobal) {Set-Item Function:global:Add-NativeReadyCollectedObservation $savedReadyGlobal.ScriptBlock}
        else {Remove-Item Function:global:Add-NativeReadyCollectedObservation}
    }
    # Удаляется только принадлежащий fixture literal UUID root после проверки всех descendant links.
    $resolved=[IO.Path]::GetFullPath($script:owned)
    $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if ([IO.Path]::GetDirectoryName($resolved) -cne $parent -or [IO.Path]::GetFileName($resolved) -cnotmatch '^cp-acceptance-review-[0-9a-f-]{36}$') {throw 'REVIEW_CLEANUP_ROOT'}
    if (Test-Path -LiteralPath $resolved) {
        if ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'REVIEW_CLEANUP_LINK'}
        if (@(Get-ChildItem -LiteralPath $resolved -Recurse -Force | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count) {throw 'REVIEW_CLEANUP_LINK'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
