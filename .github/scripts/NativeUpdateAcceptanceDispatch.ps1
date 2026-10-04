<#
.SYNOPSIS
Read-only bridge текущих пяти acceptors для одной выбранной native клетки.
.DESCRIPTION
Invoke-NativeUpdateAcceptanceDispatch -Row -Envelope -Base -Target -Cold -Java -Evidence -EvidenceKind.
Evidence MAIN передаёт независимо от helper return. Default UNVERIFIED никогда не PASS.
Ready: Receipt, ReceiptSha256, IndependentFile, IndependentSha256.
Concurrent: CellEvidence, BaseManifest, BaseSha256, TargetManifest, TargetSha256,
ExpectedHelperSha256, SupplementalDirectory. Two-client требует также IndependentSha256,
а при collector route - CollectorObservationStatus=OBSERVED. Не retrospective helper PASS.
Rollback: Authority, RetainedHelper; настоящий retained handle нужен ДО dispose collector.
Payload: ObservationFile, ObservationSha256, Expected. Phase: frozen Base/Target/Cold/Java.
Canonical Row не изменяется даже при положительном verdict. MAIN явно применяет verdict
только к этой строке, сохраняет остальные PENDING и выполняет canonical CLI verifier отдельно.
Envelope и raw receipts возвращаются без реконструкции. Status копии Phase восстанавливается
из dispatchHelperStatus, reason из dispatchHelperReason, но не является доказательством.
Этот bridge не регистрирует retrospective observer и не запускает native adapters.
MAIN callsite ДО cleanup: Ready -> Import-NativeReadyCollectorDependencies затем
Invoke-NativeReadyAcceptedCell (9 args); передать receipt/pins/independentFile в Evidence.
Concurrent -> Invoke-NativeConcurrentScenarioWithAcceptance (9 args + AcceptancePins),
его collector снимает PrimaryBefore/PeerBefore/Ready/Installed/After до disposal.
Rollback -> Invoke-NativeRollbackCollectedAcceptance (Adapter + 9 args + Authority);
его finally dispose witness. Bridge следует вызвать внутри этого retained-handle lifetime,
не передавать collector verdict как независимое доказательство и не восстанавливать PID.
Phase: independent controlled-session observer пока не произведён, полный cell PENDING.
Payload: Expected pins MAIN и SHA observation нужны после worker finally, не из row return.
Helpers, collectors, runner и старый dispatcher этим файлом не меняются.
#>

# Exact route берётся из frozen dispatcher, без команд из непроверенной строки.
function Get-NativeAcceptanceRoute([string]$Scenario) {
    switch -CaseSensitive -Exact ($Scenario) {
        {$_ -cin @('two-clients','three-clients-pid-root-isolation')} {return 'concurrent'}
        {$_ -cin @('abrupt-ready-restart','launch-applying-safe-args')} {return 'ready'}
        {$_ -cin @('journal-fault','per-move-fault','helper-runtime-death')} {return 'phase'}
        {$_ -cin @('locked-rollback','readonly-rollback','disk-full-rollback')} {return 'rollback'}
        {$_ -cin @('unicode-payload','cashmemory','unmanaged-old-or-new')} {return 'payload'}
        {$_ -cin @('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close')} {return 'normal'}
        default {throw 'ACCEPT_DISPATCH_UNKNOWN_SCENARIO'}
    }
}

# Отсутствующее поле безопасно читается и при strict mode; false/0 не считаются отсутствием.
function Get-NativeAcceptanceValue($Value,[string]$Name) {
    if ($null -eq $Value) {return $null}
    $property=$Value.PSObject.Properties[$Name]
    if ($null -ne $property) {return ,$property.Value}
    return $null
}

# Отдельная копия row сохраняет wire типы, вложенные receipts не переписываются acceptor bridge.
function Copy-NativeAcceptanceDispatchRow($Row,[string]$Route) {
    $copy=[pscustomobject]@{}
    foreach ($property in $Row.PSObject.Properties) {$copy | Add-Member $property.Name $property.Value}
    if ($Route -ceq 'phase') {
        $helper=Get-NativeAcceptanceValue $Row 'dispatchHelperStatus'
        if ($helper -ceq 'PASS') {$copy.status=$helper;$copy.reason=Get-NativeAcceptanceValue $Row 'dispatchHelperReason'}
    }
    return $copy
}

# Только fixed known файлы, top-level function AST и настоящие PSScriptRoot variable nodes.
function Import-NativeAcceptanceDefinitions([string]$Root,[string]$File,[string[]]$Names=@()) {
    if ($File -cnotin @('Test-Portable.ps1','Test-UpdateBootstrap.ps1','Test-NativeUpdateLifecycle.ps1',
        'NativeUpdatePhaseScenarios.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateTwoClientAcceptance.ps1','NativeUpdateReadyAcceptance.ps1',
        'NativeUpdatePhaseAcceptance.ps1','NativeUpdateRollbackAcceptance.ps1','NativeUpdateRollbackAcceptanceCollector.ps1','NativeUpdatePayloadAcceptance.ps1',
        'NativeUpdatePayloadScenarios.ps1','NativeUpdateNormalAcceptance.ps1','NativeUpdateNormalAcceptanceCollector.ps1')) {throw 'ACCEPT_DISPATCH_IMPORT_FILE'}
    $path=Join-Path $Root $File
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {throw ('ACCEPT_DISPATCH_MISSING_SOURCE:'+ $File)}
    $tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$issues)
    if ($issues.Count) {throw ('ACCEPT_DISPATCH_SOURCE_PARSE:'+ $File)}
    $definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($definition in $definitions) {
        if (-not $seen.Add($definition.Name) -or $definition.Name -cnotmatch '^[A-Za-z][A-Za-z0-9-]*$' -or $definition.IsFilter) {throw 'ACCEPT_DISPATCH_SOURCE_EXPORT'}
    }
    foreach ($name in $Names) {if (@($definitions | Where-Object Name -CEQ $name).Count -ne 1) {throw ('ACCEPT_DISPATCH_MISSING_EXPORT:'+ $name)}}
    foreach ($definition in $definitions) {
        if ($Names.Count -and $definition.Name -cnotin $Names) {continue}
        $text=$definition.Extent.Text
        foreach ($variable in @($definition.FindAll({param($node) $node -is [Management.Automation.Language.VariableExpressionAst] -and
            $node.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object {$_.Extent.StartOffset} -Descending)) {
            $offset=$variable.Extent.StartOffset-$definition.Extent.StartOffset
            $text=$text.Remove($offset,$variable.Extent.Text.Length).Insert($offset,("'"+$Root.Replace("'","''")+"'"))
        }
        $text=$text -replace ('^function '+[regex]::Escape($definition.Name)+'\b'),('function global:'+$definition.Name)
        . ([scriptblock]::Create($text))
    }
}

# Реальные disk acceptors исполняются в частном runspace, не в scope runner/его private context.
function Invoke-NativeAcceptanceRouteRead([string]$Route,$Row,$Receipt,$Base,$Target,$Cold,[string]$Java,$Evidence,[string]$EvidenceKind) {
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript(${function:Import-NativeAcceptanceDefinitions}.ToString().Insert(0,'function Import-NativeAcceptanceDefinitions {')+'}')
        [void]$worker.AddScript({param($root,$route,$row,$receipt,$base,$target,$cold,$java,$evidence,$kind)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            Import-NativeAcceptanceDefinitions $root 'Test-Portable.ps1' @('Resolve-PortableSafetyPath')
            Import-NativeAcceptanceDefinitions $root 'Test-UpdateBootstrap.ps1' @('Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson')
            Import-NativeAcceptanceDefinitions $root 'NativeUpdateConcurrentAcceptance.ps1' @('Read-NativeAcceptanceJson')
            # Exact публичный API, не alias и не совместимая по positional binding случайная функция.
            function Assert-Entry([string]$Name,[string]$Parameters) {
                $command=Get-Command $Name -ErrorAction Stop
                if ($command.CommandType -ne 'Function' -or $command.Name -cne $Name) {throw 'ACCEPT_DISPATCH_ENTRY_KIND'}
                $entryAst=$command.ScriptBlock.Ast
                $args=if ($entryAst -is [Management.Automation.Language.FunctionDefinitionAst]) {
                    if ($null -ne $entryAst.Body.ParamBlock) {$entryAst.Body.ParamBlock.Parameters} else {$entryAst.Parameters}
                } elseif ($null -ne $entryAst.ParamBlock) {$entryAst.ParamBlock.Parameters} else {@()}
                if ((@($args | ForEach-Object {$_.Name.VariablePath.UserPath}) -join '/') -cne $Parameters -or
                    @($args | ForEach-Object {$_.Attributes} | Where-Object {$_.TypeName.Name -cin @('Alias','AliasAttribute')}).Count) {throw ('ACCEPT_DISPATCH_ENTRY_SIGNATURE:'+ $Name)}
            }
            $decision=$null;$stored=$null
            switch -CaseSensitive -Exact ($route) {
                'normal' {
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdatePayloadScenarios.ps1'
                    Initialize-NativePayloadDependencies $root
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdatePayloadAcceptance.ps1'
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateNormalAcceptance.ps1'
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateNormalAcceptanceCollector.ps1'
                    Assert-Entry 'Test-NativeNormalCollectedAcceptance' 'Authority'
                    $authority=$evidence.Authority
                    foreach ($file in Get-NativeNormalSourceFiles) {
                        $pin=$evidence.SourcePins[$file]
                        if ($pin -cnotmatch '^[0-9a-f]{64}$' -or $pin -cne $authority.sourcePins[$file] -or
                            $pin -cne (Get-FileHash -LiteralPath (Join-Path $root $file)).Hash.ToLowerInvariant()) {throw ('NORMAL_MAIN_SOURCE_PIN:'+ $file)}
                    }
                    foreach ($pair in @(@('scenario','Scenario'),@('base','Base'),@('client','Client'),@('path','Path'),@('phase','Phase'))) {
                        if ($row.($pair[0]) -cne $authority.intent.($pair[1])) {throw 'NORMAL_MAIN_CELL_IDENTITY'}
                    }
                    if ($authority.kind -cne $kind) {throw 'NORMAL_MAIN_EVIDENCE_KIND'}
                    $decision=Test-NativeNormalCollectedAcceptance $authority
                }
                'concurrent' {
                    if ($row.scenario -ceq 'two-clients') {
                        Import-NativeAcceptanceDefinitions $root 'NativeUpdateTwoClientAcceptance.ps1'
                        Assert-Entry 'Test-NativeTwoClientAcceptance' 'CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory/IndependentSha256/EvidenceKind'
                        $decision=Test-NativeTwoClientAcceptance -CellEvidence $evidence.CellEvidence -BaseManifest $evidence.BaseManifest -BaseSha256 $evidence.BaseSha256 `
                            -TargetManifest $evidence.TargetManifest -TargetSha256 $evidence.TargetSha256 -ExpectedHelperSha256 $evidence.ExpectedHelperSha256 `
                            -SupplementalDirectory $evidence.SupplementalDirectory -IndependentSha256 $evidence.IndependentSha256 -EvidenceKind $kind
                    } else {
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateConcurrentAcceptance.ps1'
                    Assert-Entry 'Test-NativeConcurrentAcceptance' 'CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory'
                    $decision=Test-NativeConcurrentAcceptance -CellEvidence $evidence.CellEvidence -BaseManifest $evidence.BaseManifest -BaseSha256 $evidence.BaseSha256 `
                        -TargetManifest $evidence.TargetManifest -TargetSha256 $evidence.TargetSha256 -ExpectedHelperSha256 $evidence.ExpectedHelperSha256 -SupplementalDirectory $evidence.SupplementalDirectory
                    }
                    $stored=Read-NativeAcceptanceJson (Join-Path $evidence.CellEvidence 'cell.json')
                }
                'ready' {
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateReadyAcceptance.ps1'
                    Import-NativeReadyAcceptanceDependencies $root
                    Assert-Entry 'Test-NativeReadyAcceptance' 'Row/Receipt/Base/Target/ReceiptSha256/IndependentFile/IndependentSha256'
                    $decision=Test-NativeReadyAcceptance $row $receipt $base $target $evidence.ReceiptSha256 $evidence.IndependentFile $evidence.IndependentSha256
                }
                'phase' {
                    Import-NativeAcceptanceDefinitions $root 'Test-UpdateBootstrap.ps1' @('Assert-ColdKeys','Test-ColdInteger','Test-ColdInventoryEqual','Get-ColdTreeHash',
                        'Get-ColdUtcTicks','ConvertFrom-ColdReceiptJson','Get-ColdLauncherName','Assert-ColdInventory','Assert-ColdImageInventory',
                        'Assert-ColdCheckpoint','Test-ColdProtectedPayload','Assert-ColdProtectedEvidence','ConvertFrom-ColdCommandLine',
                        'Assert-ColdUiReceipt','Assert-ColdSafeArgs','Assert-ColdRecoveryEvidence')
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdatePhaseScenarios.ps1' @('Get-NativePhaseMapping','Assert-NativePhaseCheckpoint','Assert-NativePhaseFault','Assert-NativePhaseWaitingLease')
                    Import-NativeAcceptanceDefinitions $root 'Test-NativeUpdateLifecycle.ps1' @('Assert-NativeMainWindow')
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdatePhaseAcceptance.ps1'
                    Assert-Entry 'Assert-NativePhaseAcceptance' 'Row/Base/Target/Cold/Java'
                    $decision=Assert-NativePhaseAcceptance $row $base $target $cold $java
                }
                'rollback' {
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateRollbackAcceptance.ps1'
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdateRollbackAcceptanceCollector.ps1' @('New-NativeRollbackWriteBoundaryHelper')
                    Assert-Entry 'Test-NativeRollbackAcceptance' 'Row/Authority/RetainedHelper'
                    $decision=Test-NativeRollbackAcceptance $row $evidence.Authority $evidence.RetainedHelper
                }
                'payload' {
                    Import-NativeAcceptanceDefinitions $root 'NativeUpdatePayloadAcceptance.ps1'
                    Assert-Entry 'Test-NativePayloadAcceptance' 'ObservationFile/ObservationSha256/Expected/EvidenceKind'
                    $readerKind=if ($kind -ceq 'NATIVE') {'NATIVE'} else {'UNIT_MOCK'}
                    $decision=Test-NativePayloadAcceptance $evidence.ObservationFile $evidence.ObservationSha256 $evidence.Expected -EvidenceKind $readerKind
                    $observation=Read-NativeAcceptanceJson $evidence.ObservationFile $evidence.ObservationSha256
                    if ($null -ne $observation -and $null -ne $observation.PSObject.Properties['cell']) {$stored=$observation.cell}
                }
                default {throw 'ACCEPT_DISPATCH_ROUTE'}
            }
            # Correlation disk cell и copied canonical row не заменяет validators inventories/identity.
            if ($null -ne $stored) {
                foreach ($field in 'scenario','base','client','path','phase','executed','exe','baseCommit','targetCommit','baseRelease','targetRelease',
                    'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command','startedAt','finishedAt','args') {
                    $expected=$row.PSObject.Properties[$field];$actual=$stored.PSObject.Properties[$field]
                    if ($null -ne $expected -and $null -ne $actual -and -not (Test-ColdInventoryEqual $expected.Value $actual.Value)) {throw ('ACCEPT_DISPATCH_STORED_ROW:'+ $field)}
                }
            }
            return $decision
        }).AddArgument($PSScriptRoot).AddArgument($Route).AddArgument($Row).AddArgument($Receipt).AddArgument($Base).AddArgument($Target).AddArgument($Cold).AddArgument($Java).AddArgument($Evidence).AddArgument($EvidenceKind)
        $outputs=@($worker.Invoke())
        if ($worker.HadErrors) {throw $worker.Streams.Error[0].Exception}
        if ($outputs.Count -ne 1) {throw 'ACCEPT_DISPATCH_VERDICT_COUNT'}
        return $outputs[0]
    } finally {$worker.Dispose()}
}

# Read-only gate наличия canonical artifact fields; contents проверяют только существующие acceptors/CLI.
function Test-NativeAcceptanceCommonFields($Row,$Missing,$Errors) {
    foreach ($field in 'exe','baseCommit','targetCommit','startedAt','finishedAt') {
        $value=Get-NativeAcceptanceValue $Row $field
        if ($null -eq $value -or $value -ceq '') {$Missing.Add('COMMON_FIELD:'+ $field)}
        elseif ($value -isnot [string]) {$Errors.Add('COMMON_TYPE:'+ $field)}
    }
    foreach ($field in 'exe','currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {
        $value=Get-NativeAcceptanceValue $Row $field
        if ($null -eq $value -or $value -ceq '') {$Missing.Add('COMMON_ARTIFACT:'+ $field)}
        elseif ($value -isnot [string] -or -not [IO.Path]::IsPathFullyQualified($value)) {$Errors.Add('COMMON_ARTIFACT_PATH:'+ $field)}
        elseif (-not (Test-Path -LiteralPath $value -PathType Leaf)) {$Missing.Add('COMMON_ARTIFACT_FILE:'+ $field)}
    }
    foreach ($field in 'baseRelease','targetRelease','args','executed','exitCode','failures','skipped') {
        if ($null -eq (Get-NativeAcceptanceValue $Row $field)) {$Missing.Add('COMMON_FIELD:'+ $field)}
    }
    $executed=Get-NativeAcceptanceValue $Row 'executed'
    if ($null -ne $executed -and $executed -isnot [bool]) {$Errors.Add('COMMON_TYPE:executed')}
    elseif ($executed -eq $false) {$Missing.Add('ACTUAL_EXECUTION_NOT_PRODUCED')}
    foreach ($field in 'exitCode','failures','skipped') {
        $value=Get-NativeAcceptanceValue $Row $field
        if ($null -ne $value -and ($value -isnot [int] -and $value -isnot [long] -or $value -ne 0)) {$Errors.Add('COMMON_FAILURE:'+ $field)}
    }
}

# Ограниченный verdict не изменяет canonical Row, receipts, counts или общий signoff.
function Invoke-NativeUpdateAcceptanceDispatch($Row,$Envelope,$Base,$Target,$Cold,[string]$Java,$Evidence,
    [ValidateSet('UNVERIFIED','NATIVE','UNIT_MOCK')][string]$EvidenceKind='UNVERIFIED') {
    $missing=[Collections.Generic.List[string]]::new();$errors=[Collections.Generic.List[string]]::new()
    $route='unknown';$decision=$null;$validated=$false;$receipt=$null;$copy=$null;$key=''
    try {
        foreach ($field in 'scenario','base','client','path','phase','status') {
            if ($null -eq (Get-NativeAcceptanceValue $Row $field)) {$missing.Add('ROW:'+ $field)}
        }
        if ($missing.Count) {throw 'ACCEPT_DISPATCH_INPUT_MISSING'}
        $route=Get-NativeAcceptanceRoute $Row.scenario
        $key=$Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase
        $phases=if ($Row.scenario -cin @('journal-fault','per-move-fault')) {@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')} else {@('SESSION')}
        if ($Row.base -cnotin @('B1','B2') -or $Row.client -cnotin @('fx','swing','web') -or $Row.path -cnotin @('ascii','cyrillic','unicode') -or
            $Row.phase -cnotin $phases -or $Row.status -cnotin @('PENDING','FAIL')) {throw 'ACCEPT_DISPATCH_ROW_CONTEXT'}
        if ($Row.status -ceq 'FAIL' -or (Get-NativeAcceptanceValue $Row 'dispatchHelperStatus') -ceq 'FAIL') {$errors.Add('HELPER_REPORTED_FAILURE')}
        if ($null -ne $Envelope) {
            if ((Get-NativeAcceptanceValue $Envelope 'status') -cne 'PENDING' -or (Get-NativeAcceptanceValue $Envelope 'scope') -cne 'NATIVE_HELPER_RECEIPTS_ONLY' -or
                (Get-NativeAcceptanceValue $Envelope 'cellKey') -cne $key) {throw 'ACCEPT_DISPATCH_ENVELOPE_CONTEXT'}
        }
        $copy=Copy-NativeAcceptanceDispatchRow $Row $route
        Test-NativeAcceptanceCommonFields $copy $missing $errors
        if ($EvidenceKind -cne 'NATIVE') {$missing.Add('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED')}
        $required=switch ($route) {
            'normal' {@('Authority','SourcePins')}
            'concurrent' {@('CellEvidence','BaseManifest','BaseSha256','TargetManifest','TargetSha256','ExpectedHelperSha256','SupplementalDirectory')}
            'ready' {@('Receipt','ReceiptSha256','IndependentFile','IndependentSha256')}
            'rollback' {@('Authority','RetainedHelper')}
            'payload' {@('ObservationFile','ObservationSha256','Expected')}
            default {@()}
        }
        $routeMissing=$false
        foreach ($field in $required) {
            $value=Get-NativeAcceptanceValue $Evidence $field
            if ($null -eq $value -or ($value -is [string] -and $value -ceq '')) {$missing.Add('EVIDENCE:'+ $route+':'+$field);$routeMissing=$true}
        }
        if ($route -ceq 'normal' -and -not $routeMissing) {
            $authority=$Evidence.Authority
            foreach ($field in 'origin','sourcePins','intent','intentFile','intentSha256','createdUtc','nonce','bound','sealed') {
                if ($null -eq (Get-NativeAcceptanceValue $authority $field)) {$missing.Add('NORMAL_AUTHORITY:'+ $field);$routeMissing=$true}
            }
            if (-not $routeMissing -and ($authority.origin -cne 'MAIN_PRE_NATIVE_NORMAL_COLLECTOR' -or
                $authority.sourcePins -isnot [Collections.IDictionary] -or $Evidence.SourcePins -isnot [Collections.IDictionary])) {throw 'NORMAL_MAIN_AUTHORITY_CONTEXT'}
            if (-not $routeMissing -and (-not $authority.bound -or -not $authority.sealed)) {$missing.Add('NORMAL_PRE_NATIVE_BIND_POST_CLEANUP_SEAL_REQUIRED');$routeMissing=$true}
            if (-not $routeMissing) {
                foreach ($field in 'expectedFile','expectedSha256','indexFile','indexSha256') {
                    if (-not (Get-NativeAcceptanceValue $authority $field)) {$missing.Add('NORMAL_AUTHORITY:'+ $field);$routeMissing=$true}
                }
            }
        }
        if ($Row.scenario -ceq 'two-clients') {
            $pin=Get-NativeAcceptanceValue $Evidence 'IndependentSha256'
            if ($null -eq $pin -or $pin -ceq '') {$missing.Add('EVIDENCE:concurrent:IndependentSha256');$routeMissing=$true}
            $observed=Get-NativeAcceptanceValue $Evidence 'CollectorObservationStatus'
            if ($null -ne $observed -and $observed -cnotin @('OBSERVED','NOTPROVEN')) {$errors.Add('TWO_CLIENT_OBSERVER_STATUS')}
            elseif ($observed -ceq 'NOTPROVEN') {$missing.Add('TWO_CLIENT_OBSERVATIONS_NOT_PROVEN');$routeMissing=$true}
        }
        if ($route -ceq 'phase') {
            $missing.Add('PHASE_CONTROLLED_SESSION_INDEPENDENT_RECEIPTS_NOT_PRODUCED')
            if ((Get-NativeAcceptanceValue $Row 'dispatchHelperStatus') -cne 'PASS') {$missing.Add('PHASE_TERMINAL_HELPER_RECEIPTS_NOT_PRODUCED');$routeMissing=$true}
            if ($null -eq $Base -or $null -eq $Target -or $null -eq $Cold -or -not $Java) {$missing.Add('PHASE_FROZEN_CONTEXT_REQUIRED');$routeMissing=$true}
        }
        if ($route -ceq 'ready') {
            $receipt=Get-NativeAcceptanceValue $Evidence 'Receipt'
            if ($null -eq $Base -or $null -eq $Target) {$missing.Add('READY_PINNED_MANIFEST_CONTEXT_REQUIRED');$routeMissing=$true}
            foreach ($field in 'command','IndependentFile') {
                $path=if ($field -ceq 'command') {Get-NativeAcceptanceValue $Row 'command'} else {Get-NativeAcceptanceValue $Evidence $field}
                if ($path -and -not (Test-Path -LiteralPath $path -PathType Leaf)) {$missing.Add('READY_FILE_NOT_PRODUCED:'+ $field);$routeMissing=$true}
            }
        }
        # Отсутствующий row/context остаётся missing, а подмена independently supplied identity - FAIL.
        foreach ($pair in @(@{value=$Base;prefix='base'},@{value=$Target;prefix='target'})) {
            foreach ($field in @(@{row=($pair.prefix+'Commit');manifest='commitSha'},@{row=($pair.prefix+'Release');manifest='releaseNumber'})) {
                $actual=Get-NativeAcceptanceValue $Row $field.row;$expected=Get-NativeAcceptanceValue $pair.value $field.manifest
                if ($null -ne $actual -and $null -ne $expected -and $actual -cne $expected) {$errors.Add('MANIFEST_CONTEXT:'+ $field.row)}
            }
        }
        if ($route -ceq 'concurrent' -and -not $routeMissing -and
            (Get-NativeAcceptanceValue $Row 'concurrentCellEvidence') -cne $Evidence.CellEvidence) {$errors.Add('CONCURRENT_CELL_DIRECTORY_MISMATCH')}
        if ($route -ceq 'payload' -and -not $routeMissing) {
            foreach ($field in 'scenario','base','client','path') {
                if ((Get-NativeAcceptanceValue $Evidence.Expected $field) -cne $Row.$field) {$errors.Add('PAYLOAD_EXPECTED_CONTEXT:'+ $field)}
            }
            if ((Get-NativeAcceptanceValue $Row 'payloadObservation') -cne $Evidence.ObservationFile) {$errors.Add('PAYLOAD_OBSERVATION_CONTEXT')}
        }
        # Нельзя превращать absent поля в исключение strict validator и выдавать такой отказ за contradiction.
        if ($route -cin @('ready','phase') -and @($missing | Where-Object {$_ -like 'COMMON_*' -or $_ -ceq 'ACTUAL_EXECUTION_NOT_PRODUCED'}).Count) {$routeMissing=$true}
        if ($route -ceq 'ready') {
            foreach ($field in 'workRoot','evidenceDirectory') {
                if ($null -eq (Get-NativeAcceptanceValue $Row $field)) {$missing.Add('READY_ROW:'+ $field);$routeMissing=$true}
            }
        }
        if (-not $routeMissing) {
            $decision=Invoke-NativeAcceptanceRouteRead $route $copy $receipt $Base $Target $Cold $Java $Evidence $EvidenceKind
            $acceptorMissing=Get-NativeAcceptanceValue $decision 'missing'
            foreach ($gap in $acceptorMissing) {if ($null -ne $gap) {$missing.Add('ACCEPTOR:'+ [string]$gap)}}
            foreach ($field in 'errors','contradictions','reasons') {
                $acceptorErrors=Get-NativeAcceptanceValue $decision $field
                foreach ($errorValue in $acceptorErrors) {if ($null -ne $errorValue) {$errors.Add('ACCEPTOR:'+ [string]$errorValue)}}
            }
            $status=Get-NativeAcceptanceValue $decision 'status'
            if ($status -cnotin @('PASS','PENDING','FAIL','RECEIPT_CONTRACT_VALIDATED')) {throw 'ACCEPT_DISPATCH_INVALID_VERDICT'}
            $expectedScope=switch ($route) {
                'normal' {'NATIVE_NORMAL_TRANSPORT_CELL'}
                'ready' {'READY_CELL_ONLY'}
                'concurrent' {if ($Row.scenario -ceq 'two-clients') {'NATIVE_TWO_CLIENT_WAIT_BARRIER'} else {'NATIVE_STALE_LEASE_BIRTH_MISMATCH'}}
                'rollback' {'SINGLE_CELL_GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE'}
                'payload' {$Row.scenario}
            }
            if ($status -ceq 'PASS' -and $route -ceq 'ready') {throw 'ACCEPT_DISPATCH_READY_STATUS_CONTRADICTION'}
            if (($status -ceq 'PASS' -or ($route -ceq 'ready' -and (Get-NativeAcceptanceValue $decision 'cellEvidenceValidated') -eq $true)) -and
                (Get-NativeAcceptanceValue $decision 'scope') -cne $expectedScope) {throw 'ACCEPT_DISPATCH_VERDICT_SCOPE'}
            if ($status -ceq 'FAIL') {$errors.Add('ACCEPTOR_REPORTED_FAILURE')}
            $validated=switch ($route) {
                'normal' {$flag=Get-NativeAcceptanceValue $decision 'proofComplete';$status -ceq 'PASS' -and $flag -is [bool] -and $flag -and (Get-NativeAcceptanceValue $decision 'scope') -ceq 'NATIVE_NORMAL_TRANSPORT_CELL'}
                'ready' {$flag=Get-NativeAcceptanceValue $decision 'cellEvidenceValidated';$status -ceq 'PENDING' -and $flag -is [bool] -and $flag -and (Get-NativeAcceptanceValue $decision 'scope') -ceq 'READY_CELL_ONLY'}
                'phase' {$status -ceq 'RECEIPT_CONTRACT_VALIDATED'}
                'concurrent' {
                    if ($Row.scenario -ceq 'two-clients') {
                        $flag=Get-NativeAcceptanceValue $decision 'proofComplete'
                        $status -ceq 'PASS' -and $flag -is [bool] -and $flag -and (Get-NativeAcceptanceValue $decision 'scope') -ceq 'NATIVE_TWO_CLIENT_WAIT_BARRIER'
                    } else {$status -ceq 'PASS' -and (Get-NativeAcceptanceValue $decision 'scope') -ceq 'NATIVE_STALE_LEASE_BIRTH_MISMATCH'}
                }
                'rollback' {$flag=Get-NativeAcceptanceValue $decision 'nativePass';$status -ceq 'PASS' -and $flag -is [bool] -and $flag -and (Get-NativeAcceptanceValue $decision 'scope') -ceq 'SINGLE_CELL_GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE'}
                'payload' {$flag=Get-NativeAcceptanceValue $decision 'proofComplete';$status -ceq 'PASS' -and $flag -is [bool] -and $flag -and (Get-NativeAcceptanceValue $decision 'scope') -ceq $Row.scenario}
            }
            if (-not $validated -and $status -cne 'FAIL') {$missing.Add('ROUTE_EVIDENCE_NOT_FULLY_VALIDATED')}
        }
    } catch {
        $failure=$_.Exception
        while ($null -ne $failure.InnerException) {$failure=$failure.InnerException}
        $code=$failure.Message
        if ($code -ceq 'ACCEPT_DISPATCH_INPUT_MISSING') { }
        elseif ($failure -is [Management.Automation.ItemNotFoundException] -or $failure -is [IO.FileNotFoundException] -or
            $failure -is [IO.DirectoryNotFoundException] -or $code -like 'ACCEPT_DISPATCH_MISSING_*' -or $code -ceq 'PHASE_ACCEPT_MISSING') {$missing.Add($code)}
        else {$errors.Add($code)}
    }
    $status=if ($errors.Count) {'FAIL'} elseif ($missing.Count -or -not $validated) {'PENDING'} else {'PASS'}
    return [pscustomobject]@{status=$status;scope='SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY';cellKey=$key;route=$route;
        routeEvidenceValidated=[bool]$validated;canonicalRowUnchanged=$true;gaps=$missing.ToArray();contradictions=$errors.ToArray();
        acceptorVerdict=$decision;copiedRow=$copy;envelope=$Envelope;evidenceKind=$EvidenceKind;fullMatrix='PENDING';releaseProvenance='PENDING'}
}
