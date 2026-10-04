<#
.SYNOPSIS
Только AST/mock проверки dispatcher; ни одной native клетки или native PASS.
.DESCRIPTION
Создаёт собственный Temp UUID с mock definitions и неизменённой canonical Java
спецификацией. Тела mock runners содержат throw: их выполнение запрещено.
Исходные helpers только читаются; их реальные importers проверяются отдельно
без запуска сценариев. Ошибка текущих dependencies выводится как NOT_READY.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'DISPATCH_FIXTURE_POWERSHELL7'}
. (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')
$script:dispatchFixtureChecks=0
$global:dispatchFixtureImports=[Collections.Generic.List[object]]::new()

# Проверяет точное условие, не преобразуя mock успех в native acceptance.
function Assert-DispatchFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw "DISPATCH_FIXTURE_ASSERT $Code"}
    $script:dispatchFixtureChecks++
}

# Требует точную причину отказа, исключая случайное раннее исключение.
function Assert-DispatchFixtureReject([scriptblock]$Action,[string]$Code) {
    $caught=$null
    try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-DispatchFixture ($caught -ceq $Code) ("expected=$Code actual=$caught")
}

# Получает одно реальное определение для static signature/canonical plan теста, не исполняя файл.
function Get-DispatchFixtureDefinition([string]$Path,[string]$Name) {
    $tokens=$null;$parseIssues=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$parseIssues)
    if ($parseIssues.Count) {throw "DISPATCH_FIXTURE_SOURCE_PARSE $Path"}
    $definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $Name})
    if ($definitions.Count -ne 1) {throw "DISPATCH_FIXTURE_DEFINITION $Name"}
    return $definitions[0]
}

# Формирует mock экспорт с девятью аргументами; даже выставленный mock PASS будет демотирован.
function New-DispatchFixtureFunction([string]$Name,[bool]$Advanced) {
    $body=@'
function NAME($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $Row | Add-Member fixtureCall 'NAME' -Force
    $Row | Add-Member fixtureArguments @($Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout) -Force
    ADVANCED
    $Row | Add-Member fixtureReceipt ([pscustomobject]@{status='PENDING';scope='MOCK_ONLY_NOT_NATIVE_EVIDENCE';helper='NAME'}) -Force
    'MOCK_RETURN_NOT_NATIVE_EXECUTION'
    return $Row.fixtureReceipt
}
'@
    return $body.Replace('NAME',$Name).Replace('ADVANCED',$(if ($Advanced) {"`$Row.status='PASS';`$Row.reason='MOCK_ONLY_NOT_ACCEPTANCE'"} else {''}))
}

# Пишет только literal файл внутри заранее проверенного owned Temp дерева.
function Write-DispatchFixtureFile([string]$Path,[string]$Text) {
    $full=[IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith(($script:dispatchFixtureOwned+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)) {throw 'DISPATCH_FIXTURE_WRITE_ESCAPE'}
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($full))
    [IO.File]::WriteAllText($full,$Text,[Text.UTF8Encoding]::new($false))
}

# Создаёт отдельную canonical row, не заимствуя retained PASS из прежних запусков.
function New-DispatchFixtureRow([string]$Scenario,[string]$Phase='SESSION') {
    [pscustomobject]@{scenario=$Scenario;base='B1';client='fx';path='ascii';phase=$Phase;status='PENDING';reason='MOCK_NOT_EXECUTED'}
}

$tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$script:dispatchFixtureOwned=Join-Path $tempParent ([guid]::NewGuid().ToString())
if ((Test-Path -LiteralPath $script:dispatchFixtureOwned) -or [IO.Path]::GetDirectoryName($script:dispatchFixtureOwned) -cne $tempParent) {throw 'DISPATCH_FIXTURE_TEMP_SCOPE'}
$scripts=Join-Path $script:dispatchFixtureOwned 'project/.github/scripts'
$canonical=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
$canonicalCopy=[IO.Path]::GetFullPath((Join-Path $scripts '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
$initialHashes=@{}
foreach ($file in Get-NativeDispatchFiles) {$initialHashes[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file) -Algorithm SHA256).Hash}
$canonicalHash=(Get-FileHash -LiteralPath $canonical -Algorithm SHA256).Hash
$nativeTarget='MOCK_TARGET';$nativeProject='MOCK_PROJECT';$nativeProfile='MOCK_PROFILE'
$script:nativeTarget=$nativeTarget;$script:nativeProject=$nativeProject;$script:nativeProfile=$nativeProfile
$actualImportReady=$false;$actualImportFailure=$null
try {
    $planDefinition=Get-DispatchFixtureDefinition (Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1') 'Get-NativeEvidencePlan'
    $phaseMapping=Get-DispatchFixtureDefinition (Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1') 'Get-NativePhaseMapping'
    Write-DispatchFixtureFile $canonicalCopy ([IO.File]::ReadAllText($canonical))
    $mockTexts=@{}
    foreach ($file in Get-NativeDispatchFiles) {$mockTexts[$file]="# Mock definitions only.`nthrow 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'`n"}
    $mockTexts['Test-NativeUpdateLifecycle.ps1']=(New-DispatchFixtureFunction 'Invoke-NativeCell' $false)+"`n"+$planDefinition.Extent.Text+@'

# Mock importer регистрирует точный fixed source root, не вызывает ОС.
function Import-NativeDependencies([string]$ScriptsRoot) {
    $global:dispatchFixtureImports.Add([pscustomobject]@{name='normal';root=$ScriptsRoot})
}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@
    $mockTexts['NativeUpdateConcurrentScenarios.ps1']=(New-DispatchFixtureFunction 'Invoke-NativeConcurrentScenario' $true)+"`nthrow 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'"
    $mockTexts['NativeUpdatePhaseScenarios.ps1']=(New-DispatchFixtureFunction 'Invoke-NativePhaseScenario' $true)+"`n"+$phaseMapping.Extent.Text+"`nthrow 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'"
    $mockTexts['NativeUpdateReadyScenarios.ps1']=(New-DispatchFixtureFunction 'Invoke-NativeReadyScenario' $true)+@'

# Mock ready importer не заменяет фазовый или normal сценарий.
function Import-NativeReadyDependencies([string]$ScriptsRoot) {
    $global:dispatchFixtureImports.Add([pscustomobject]@{name='ready';root=$ScriptsRoot})
}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@
    $rollbackExport=New-DispatchFixtureFunction 'Invoke-NativeRollbackScenario' $true
    $mockTexts['NativeUpdateRollbackScenarios.ps1']=$rollbackExport+@'

# Mock isolated module сохраняет API настоящего rollback importer.
function Import-NativeRollbackDependencies([string]$ScriptsRoot) {
    $global:dispatchFixtureImports.Add([pscustomobject]@{name='rollback';root=$ScriptsRoot})
    $module=New-Module -ArgumentList $ScriptsRoot -ScriptBlock {
        param($MockRoot)
        # Контекст регистрируется в модуле, не в caller MAIN.
        function Set-NativeRollbackContext([string]$TargetRoot,[string]$ProjectRoot,[string]$ProfileRoot) {
            $global:dispatchFixtureImports.Add([pscustomobject]@{name='rollback-context';root=$MockRoot;target=$TargetRoot;project=$ProjectRoot;profile=$ProfileRoot})
        }
        ROLLBACK_EXPORT
        Export-ModuleMember -Function Set-NativeRollbackContext,Invoke-NativeRollbackScenario
    }
    return $module
}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@.Replace('ROLLBACK_EXPORT',$rollbackExport)
    $mockTexts['NativeUpdatePayloadScenarios.ps1']=(New-DispatchFixtureFunction 'Invoke-NativePayloadCell' $true)+@'

# Mock payload importer только подтверждает routing и fixed root.
function Initialize-NativePayloadDependencies([string]$Root) {
    $global:dispatchFixtureImports.Add([pscustomobject]@{name='payload';root=$Root})
}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@
    $coldNames=@('New-ColdJournal','New-ColdInstrumentedHelper','Get-ColdCheckpoints','Assert-ColdCheckpoint','Assert-ColdProtectedEvidence',
        'Start-ColdProcess','Save-ColdHelperDiagnostics','Get-ColdRecoveryObservation','Assert-ColdRecoveryEvidence','Assert-ColdSafeArgs')
    $mockTexts['Test-UpdateBootstrap.ps1']=($coldNames | ForEach-Object {"function $_ {throw 'DISPATCH_FIXTURE_NATIVE_FORBIDDEN'}"}) -join "`n"
    # Новые actual importers также получают fixed root; тела adapters запрещены при legacy default.
    $mockTexts['NativeUpdateReadyAcceptanceCollector.ps1']=(New-DispatchFixtureFunction 'Invoke-NativeReadyAcceptedCell' $true)+@'
function Import-NativeReadyCollectorDependencies([string]$ScriptsRoot) {}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@
    $mockTexts['NativeUpdateTwoClientAcceptanceCollector.ps1']=@'
function Import-NativeTwoClientCollectorDependencies([string]$ScriptsRoot) {}
function Invoke-NativeTwoClientCollectedScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,$AcceptancePins,[string]$EvidenceKind) {throw 'DISPATCH_FIXTURE_UNSELECTED_COLLECTOR'}
throw 'DISPATCH_FIXTURE_RUNNER_BODY_EXECUTED'
'@
    foreach ($file in Get-NativeDispatchFiles) {Write-DispatchFixtureFile (Join-Path $scripts $file) $mockTexts[$file]}
    Initialize-NativeScenarioDispatch $scripts
    Assert-DispatchFixture ($script:nativeDispatchState.SourceScriptsRoot -ceq $scripts) 'fixed root'
    foreach ($name in 'normal','ready','rollback','payload') {
        Assert-DispatchFixture (@($global:dispatchFixtureImports | Where-Object {$_.name -ceq $name -and $_.root -ceq $scripts}).Count -gt 0) ('explicit importer '+$name)
    }
    $rows=@(Get-NativeEvidencePlan $canonicalCopy)
    Assert-NativeDispatchPlan $rows
    Assert-DispatchFixture ($rows.Count -eq 612) 'canonical 612'
    $expected=@{normal='Invoke-NativeCell';concurrent='Invoke-NativeConcurrentScenario';ready='Invoke-NativeReadyScenario';phase='Invoke-NativePhaseScenario';
        rollback='Invoke-NativeRollbackScenario';payload='Invoke-NativePayloadCell'}
    $counts=@{normal=0;concurrent=0;ready=0;phase=0;rollback=0;payload=0}
    $base=[pscustomobject]@{mock='base'};$target=[pscustomobject]@{mock='target'};$life=[pscustomobject]@{mock='life'};$cold=[pscustomobject]@{mock='cold'}
    foreach ($scenario in @($rows.scenario | Select-Object -Unique)) {
        $route=Get-NativeDispatchRoute $scenario;$counts[$route]++
        $phases=if ($scenario -cin @('journal-fault','per-move-fault')) {@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')} else {@('SESSION')}
        foreach ($phase in $phases) {
            $row=New-DispatchFixtureRow $scenario $phase
            $output=@(Invoke-NativeScenarioDispatch $row 'MOCK_SOURCE' $base $target $life $cold 'MOCK_JAVA' 'MOCK_EVIDENCE' 180)
            Assert-DispatchFixture ($output.Count -eq 1 -and $output[0].status -ceq 'PENDING' -and
                $output[0].scope -ceq 'NATIVE_HELPER_RECEIPTS_ONLY' -and
                $output[0].cellKey -ceq ($scenario+'/B1/fx/ascii/'+$phase)) 'explicit pending receipt envelope'
            Assert-DispatchFixture ($output[0].helperReceipts.Count -eq 2 -and
                $output[0].helperReceipts[0] -ceq 'MOCK_RETURN_NOT_NATIVE_EXECUTION' -and
                [object]::ReferenceEquals($output[0].helperReceipts[1],$row.fixtureReceipt) -and
                $row.fixtureReceipt.status -ceq 'PENDING') 'raw outputs preserved in order without acceptance'
            Assert-DispatchFixture ($null -eq $row.PSObject.Properties['helperReceipts']) 'receipt not embedded in row matrix'
            Assert-DispatchFixture ($row.fixtureCall -ceq $expected[$route]) ($scenario+'/'+$phase+' route')
            Assert-DispatchFixture ($row.status -ceq 'PENDING') 'mock cannot promote row'
            Assert-DispatchFixture ($null -eq $row.PSObject.Properties['executed']) 'no fabricated execution'
            $args=$row.fixtureArguments
            Assert-DispatchFixture ($args.Count -eq 9 -and [object]::ReferenceEquals($args[0],$row) -and $args[1] -ceq 'MOCK_SOURCE' -and
                [object]::ReferenceEquals($args[2],$base) -and [object]::ReferenceEquals($args[3],$target) -and
                [object]::ReferenceEquals($args[4],$life) -and [object]::ReferenceEquals($args[5],$cold) -and
                $args[6] -ceq 'MOCK_JAVA' -and $args[7] -ceq 'MOCK_EVIDENCE' -and $args[8] -eq 180) 'exact nine arguments'
            if ($route -cne 'normal') {Assert-DispatchFixture ($row.dispatchHelperStatus -ceq 'PASS' -and $row.reason -ceq 'NATIVE_DISPATCH_ADVANCED_ACCEPTANCE_PENDING') 'advanced mock demotion'}
            if ($route -ceq 'phase') {
                $mapping=Get-NativePhaseMapping $scenario $phase
                Assert-DispatchFixture ($mapping.mapped -and $mapping.phase -ceq $phase) 'actual mapping full phases'
            }
        }
    }
    foreach ($entry in @{normal=9;concurrent=2;ready=2;phase=3;rollback=3;payload=3}.GetEnumerator()) {
        Assert-DispatchFixture ($counts[$entry.Key] -eq $entry.Value) ('scenario count '+$entry.Key)
    }
    Assert-DispatchFixture (@($rows | Where-Object status -CNE 'PENDING').Count -eq 0) 'canonical unexecuted rows remain pending after mock dispatch'
    # Отсутствие helper outputs не создаёт пустой receipt или признак acceptance.
    $normalFile=Join-Path $scripts 'Test-NativeUpdateLifecycle.ps1'
    $normalOriginal=$mockTexts['Test-NativeUpdateLifecycle.ps1']
    $normalSilent=$normalOriginal.Replace("'MOCK_RETURN_NOT_NATIVE_EXECUTION'",'').Replace('return $Row.fixtureReceipt','return')
    Write-DispatchFixtureFile $normalFile $normalSilent
    Initialize-NativeScenarioDispatch $scripts
    $silentRow=New-DispatchFixtureRow 'delta'
    $silentOutputs=@(Invoke-NativeScenarioDispatch $silentRow 'S' $base $target $life $cold 'J' 'E' 180)
    Assert-DispatchFixture ($silentOutputs.Count -eq 0 -and $silentRow.status -ceq 'PENDING') 'silent helper produces no receipt or pass'
    Write-DispatchFixtureFile $normalFile $normalOriginal
    Initialize-NativeScenarioDispatch $scripts
    Assert-DispatchFixture (@($global:dispatchFixtureImports | Where-Object {$_.root -cne $scripts}).Count -eq 0) 'no helper root escape'
    foreach ($scenario in 'Delta','unknown','Invoke-Expression','delta;Start-Process') {
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow $scenario) 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_UNKNOWN_SCENARIO'
    }
    foreach ($phase in 'SESSION','waiting','UNKNOWN') {
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'journal-fault' $phase) 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_ROW_IDENTITY'
    }
    Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'delta' 'WAITING') 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_ROW_IDENTITY'
    foreach ($field in 'base','client','path','status') {
        $invalid=New-DispatchFixtureRow 'delta';$invalid.$field='unknown'
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch $invalid 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_ROW_IDENTITY'
    }
    Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'delta') 'S' $base $target $life $cold 'J' 'E' 29} 'DISPATCH_TIMEOUT'
    Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'journal-fault' 'PREPARED') 'S' $base $target $life $cold 'J' 'E' 181} 'DISPATCH_TIMEOUT'
    Assert-DispatchFixtureReject {Assert-NativeDispatchPlan @($rows | Select-Object -First 396)} 'DISPATCH_CANONICAL_PLAN'
    $duplicate=@($rows);$duplicate[-1]=$duplicate[0]
    Assert-DispatchFixtureReject {Assert-NativeDispatchPlan $duplicate} 'DISPATCH_CANONICAL_PLAN'
    $badRow=New-DispatchFixtureRow 'journal-fault';$badPlan=@($rows);$badPlan[0]=$badRow
    Assert-DispatchFixtureReject {Assert-NativeDispatchPlan $badPlan} 'DISPATCH_CANONICAL_PLAN'
    Assert-DispatchFixtureReject {Read-NativeDispatchFunctions $scripts '../Test-Portable.ps1'} 'DISPATCH_UNKNOWN_HELPER_FILE'
    Set-Alias -Name Invoke-NativeCell -Value Write-Output -Scope Script
    try {
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'delta') 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_COMMAND Invoke-NativeCell'
    } finally {Remove-Item -LiteralPath Alias:Invoke-NativeCell}

    $helper=Join-Path $scripts 'NativeUpdateReadyScenarios.ps1';$original=$mockTexts['NativeUpdateReadyScenarios.ps1']
    Write-DispatchFixtureFile $helper ($original+"`n# source changed")
    Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'delta') 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_SOURCE_CHANGED NativeUpdateReadyScenarios.ps1'
    Write-DispatchFixtureFile $helper $original
    foreach ($replacement in @(
        $original.Replace('[int]$Timeout','[string]$Timeout'),
        $original.Replace('$Row,[string]$Source','$Different,[string]$Source'),
        $original.Replace('$Row,[string]$Source',"[Alias('r')]`$Row,[string]`$Source"),
        $original.Replace('[int]$Timeout)','[int]$Timeout=180)'),
        $original.Replace('Invoke-NativeReadyScenario','Invoke-NativeWrongScenario'))) {
        Write-DispatchFixtureFile $helper $replacement
        $code=if ($replacement.Contains('Invoke-NativeWrongScenario')) {'DISPATCH_MISSING_FUNCTION NativeUpdateReadyScenarios.ps1/Invoke-NativeReadyScenario'} else {'DISPATCH_SIGNATURE Invoke-NativeReadyScenario'}
        Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} $code
        Assert-DispatchFixture ($null -eq $script:nativeDispatchState) 'failed init remains unavailable'
    }
    Write-DispatchFixtureFile $helper 'function Broken('
    Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} 'DISPATCH_HELPER_PARSE NativeUpdateReadyScenarios.ps1'
    Write-DispatchFixtureFile $helper ($original+"`nfunction Invoke-NativeReadyScenario {}`n")
    Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} 'DISPATCH_HELPER_DEFINITION NativeUpdateReadyScenarios.ps1'
    Write-DispatchFixtureFile $helper $original
    $missing=Join-Path $scripts 'NativeUpdatePayloadScenarios.ps1'
    [IO.File]::Move($missing,($missing+'.fixture-backup'))
    try {Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} 'DISPATCH_MISSING_HELPER NativeUpdatePayloadScenarios.ps1'}
    finally {[IO.File]::Move(($missing+'.fixture-backup'),$missing)}
    Write-DispatchFixtureFile $helper ($original.Replace("`$global:dispatchFixtureImports.Add([pscustomobject]@{name='ready';root=`$ScriptsRoot})","throw 'MOCK_IMPORT_FAIL'"))
    Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} 'MOCK_IMPORT_FAIL'
    Write-DispatchFixtureFile $helper $original
    Initialize-NativeScenarioDispatch $scripts
    # Helper exception, identity mutation и собственный FAIL не могут пройти как успешный return.
    foreach ($entry in @(
        @("`$Row.status='FAIL';`$Row.reason='MOCK_REPORTED_FAILURE'",'DISPATCH_HELPER_REPORTED_FAILURE'),
        @("`$Row.scenario='delta'",'DISPATCH_HELPER_ROW_CHANGED'),
        @("`$Row.status='UNKNOWN'",'DISPATCH_HELPER_STATUS'),
        @("throw 'MOCK_HELPER_THROW'",'MOCK_HELPER_THROW'))) {
        $changed=$original.Replace("`$Row.status='PASS';`$Row.reason='MOCK_ONLY_NOT_ACCEPTANCE'",$entry[0])
        Write-DispatchFixtureFile $helper $changed
        Initialize-NativeScenarioDispatch $scripts
        $failed=New-DispatchFixtureRow 'abrupt-ready-restart'
        $failedOutputs=[Collections.Generic.List[object]]::new()
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch $failed 'S' $base $target $life $cold 'J' 'E' 180 |
            ForEach-Object {$failedOutputs.Add($_)}} $entry[1]
        Assert-DispatchFixture ($failed.status -ceq 'FAIL') 'helper error stays failed'
        Assert-DispatchFixture ($failedOutputs.Count -eq 0) 'failed helper cannot forward an acceptance-like envelope'
    }
    Write-DispatchFixtureFile $helper $original
    Initialize-NativeScenarioDispatch $scripts
    Set-Alias -Name Import-NativeReadyDependencies -Value Write-Output -Scope Script
    try {
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch (New-DispatchFixtureRow 'abrupt-ready-restart') 'S' $base $target $life $cold 'J' 'E' 180} 'DISPATCH_IMPORTER Import-NativeReadyDependencies'
    } finally {Remove-Item -LiteralPath Alias:Import-NativeReadyDependencies}
    $wrongImporter=$original.Replace('Import-NativeReadyDependencies([string]$ScriptsRoot)','Import-NativeReadyDependencies([string]$Different)')
    Write-DispatchFixtureFile $helper $wrongImporter
    Assert-DispatchFixtureReject {Initialize-NativeScenarioDispatch $scripts} 'DISPATCH_IMPORTER Import-NativeReadyDependencies'
    Write-DispatchFixtureFile $helper $original
    Initialize-NativeScenarioDispatch $scripts
    # Ошибка importer в runtime не вызывает helper и не становится PASS.
    $importRoute=(Get-Command Import-NativeDispatchRoute).ScriptBlock
    function Import-NativeDispatchRoute([string]$SourceScriptsRoot,[string]$Route) {throw 'MOCK_ROUTE_IMPORT_FAIL'}
    try {
        $row=New-DispatchFixtureRow 'two-clients'
        Assert-DispatchFixtureReject {Invoke-NativeScenarioDispatch $row 'S' $base $target $life $cold 'J' 'E' 180} 'MOCK_ROUTE_IMPORT_FAIL'
        Assert-DispatchFixture ($row.status -ceq 'FAIL' -and $null -eq $row.PSObject.Properties['fixtureCall']) 'no route on import failure'
    } finally {Set-Item -LiteralPath Function:Import-NativeDispatchRoute -Value $importRoute}

    # Реальные owner-файлы: static API проверяется на последнем прочитанном source, без выполнения сценариев.
    foreach ($entry in @(
        @('Test-NativeUpdateLifecycle.ps1','Invoke-NativeCell'),@('NativeUpdateConcurrentScenarios.ps1','Invoke-NativeConcurrentScenario'),
        @('NativeUpdateReadyScenarios.ps1','Invoke-NativeReadyScenario'),@('NativeUpdatePhaseScenarios.ps1','Invoke-NativePhaseScenario'),
        @('NativeUpdateRollbackScenarios.ps1','Invoke-NativeRollbackScenario'),@('NativeUpdatePayloadScenarios.ps1','Invoke-NativePayloadCell'))) {
        Assert-NativeDispatchSignature (Get-DispatchFixtureDefinition (Join-Path $PSScriptRoot $entry[0]) $entry[1]) $entry[1]
        $script:dispatchFixtureChecks++
    }
    $dispatcher=Get-DispatchFixtureDefinition (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1') 'Invoke-NativeScenarioDispatch'
    Assert-NativeDispatchSignature $dispatcher 'Invoke-NativeScenarioDispatch'
    foreach ($call in $dispatcher.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst]},$true)) {
        if ($call.GetCommandName() -like 'Invoke-Native*') {Assert-DispatchFixture ($call.GetCommandName() -cin (@($expected.Values)+@('Invoke-NativeReadyAcceptedCell','Invoke-NativeConcurrentScenarioWithAcceptance','Invoke-NativeTwoClientCollectedScenario','Invoke-NativeRollbackCollectedAcceptance','Invoke-NativeUpdateAcceptanceDispatch'))) 'literal fixed route command'}
    }
    # Это только functions/importers. Failure не скрывается за mocks и запрещает MAIN integration.
    try {Initialize-NativeScenarioDispatch $PSScriptRoot;$actualImportReady=$true}
    catch {$actualImportFailure=$_.Exception.Message;Write-Warning ('Actual source dependencies NOT_READY: '+$actualImportFailure)}
    foreach ($file in Get-NativeDispatchFiles) {
        Assert-DispatchFixture ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file) -Algorithm SHA256).Hash -ceq $initialHashes[$file]) ('source unchanged '+$file)
    }
    Assert-DispatchFixture ((Get-FileHash -LiteralPath $canonical -Algorithm SHA256).Hash -ceq $canonicalHash) 'canonical source unchanged'
    [pscustomobject]@{scope='AST_AND_MOCK_ROUTING_ONLY';checks=$script:dispatchFixtureChecks;nativeExecuted=$false;nativeStatus='PENDING';
        actualHelperImportReady=$actualImportReady;actualHelperImportFailure=$actualImportFailure;fullSignoff='PENDING';filesWritten='OWNED_TEMP_FIXTURES_ONLY'}
} finally {
    # Только own прямой Temp UUID; literal путь повторно проверяется до recursive cleanup.
    if (Test-Path -LiteralPath $script:dispatchFixtureOwned) {
        $owned=Assert-NativeDispatchPath $script:dispatchFixtureOwned -Directory
        if ([IO.Path]::GetDirectoryName($owned) -cne $tempParent -or
            [IO.Path]::GetFileName($owned) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'DISPATCH_FIXTURE_CLEANUP_SCOPE'}
        Remove-Item -LiteralPath $owned -Recurse -Force
    }
    Remove-Variable -Name dispatchFixtureImports -Scope Global -ErrorAction SilentlyContinue
}
