<#
.SYNOPSIS
Закрытый dispatcher 22 сценариев UpdateEvidence, без исполнения runner при импорте.
.DESCRIPTION
MAIN подключает файл и вызывает Initialize-NativeScenarioDispatch только ПОСЛЕ
терминального завершения прежней native очереди. Invoke-NativeScenarioDispatch
имеет девять параметров Invoke-NativeCell; caller сохраняет frozen pins и свои
nativeTarget/nativeProject/nativeProfile. Этот файл не создаёт Signoff или PASS.
Advanced rows после helper остаются PENDING до независимой проверки receipts.
#>

# Возвращает фиксированные source-файлы, никогда не имя команды из Row.
function Get-NativeDispatchFiles {
    @('Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
      'NativeUpdateConcurrentScenarios.ps1','NativeUpdateReadyScenarios.ps1','NativeUpdatePhaseScenarios.ps1',
      'NativeUpdateRollbackScenarios.ps1','NativeUpdatePayloadScenarios.ps1','New-NativeUpdateArtifacts.ps1',
      'New-NativeUpdateLifecycleConfig.ps1','New-UpdateBootstrapCommands.ps1','S7-Release.ps1',
      'NativeUpdateAcceptanceDispatch.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateReadyAcceptance.ps1',
      'NativeUpdatePhaseAcceptance.ps1','NativeUpdateRollbackAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1',
      'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateRollbackAcceptanceCollector.ps1','Test-NativeUpdateEvidenceSignoff.ps1',
      'NativeUpdateTwoClientAcceptance.ps1','NativeUpdateTwoClientAcceptanceCollector.ps1')
}

# Проверяет literal абсолютный путь и каждого предка, не разрешая reparse обход source root.
function Assert-NativeDispatchPath([string]$Path,[switch]$Directory) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathFullyQualified($Path) -or
        [IO.Path]::GetFullPath($Path).TrimEnd('\','/') -cne $Path.TrimEnd('\','/')) {throw 'DISPATCH_SOURCE_PATH'}
    $item=Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($Directory -and -not $item.PSIsContainer -or -not $Directory -and $item.PSIsContainer) {throw 'DISPATCH_SOURCE_KIND'}
    for ($entry=$item; $null -ne $entry; $entry=if ($entry -is [IO.DirectoryInfo]) {$entry.Parent} else {$entry.Directory}) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'DISPATCH_SOURCE_LINK'}
    }
    return $item.FullName.TrimEnd('\','/')
}

# Разбирает только известный файл под фиксированным root и возвращает верхнеуровневые definitions.
function Read-NativeDispatchFunctions([string]$SourceScriptsRoot,[string]$File) {
    if ($File -cnotin @(Get-NativeDispatchFiles)) {throw 'DISPATCH_UNKNOWN_HELPER_FILE'}
    $path=Join-Path $SourceScriptsRoot $File
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {throw "DISPATCH_MISSING_HELPER $File"}
    $null=Assert-NativeDispatchPath $path
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw "DISPATCH_HELPER_PARSE $File"}
    $functions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($function in $functions) {
        if ($function.Name -cnotmatch '^[A-Za-z][A-Za-z0-9-]*$' -or -not $names.Add($function.Name) -or $function.IsFilter) {
            throw "DISPATCH_HELPER_DEFINITION $File"
        }
    }
    $export=switch -CaseSensitive -Exact ($File) {
        'Test-NativeUpdateLifecycle.ps1' {'Invoke-NativeCell'}
        'NativeUpdateConcurrentScenarios.ps1' {'Invoke-NativeConcurrentScenario'}
        'NativeUpdateReadyScenarios.ps1' {'Invoke-NativeReadyScenario'}
        'NativeUpdatePhaseScenarios.ps1' {'Invoke-NativePhaseScenario'}
        'NativeUpdateRollbackScenarios.ps1' {'Invoke-NativeRollbackScenario'}
        'NativeUpdatePayloadScenarios.ps1' {'Invoke-NativePayloadCell'}
    }
    if ($export) {
        $matches=@($functions | Where-Object Name -CEQ $export)
        if ($matches.Count -ne 1) {throw "DISPATCH_MISSING_FUNCTION $File/$export"}
        Assert-NativeDispatchSignature $matches[0] $export
    }
    return $functions
}

# Проверяет имена, порядок, типы и отсутствие aliases/defaults точного девятипараметрового API.
function Assert-NativeDispatchSignature($Definition,[string]$Name) {
    if ($Definition -isnot [Management.Automation.Language.FunctionDefinitionAst] -or
        $Definition.Name -cnotin @($Name,('script:'+$Name),('global:'+$Name))) {throw "DISPATCH_SIGNATURE $Name"}
    $parameters=@(if ($null -ne $Definition.Parameters) {$Definition.Parameters} elseif ($null -ne $Definition.Body.ParamBlock) {$Definition.Body.ParamBlock.Parameters})
    $names=@('Row','Source','Base','Target','Life','Cold','Java','Evidence','Timeout')
    $types=@([object],[string],[object],[object],[object],[object],[string],[string],[int])
    if ($parameters.Count -ne 9) {throw "DISPATCH_SIGNATURE $Name"}
    for ($i=0;$i -lt 9;$i++) {
        if ($parameters[$i].Name.VariablePath.UserPath -cne $names[$i] -or $parameters[$i].StaticType -ne $types[$i] -or
            $null -ne $parameters[$i].DefaultValue -or
            @($parameters[$i].Attributes | Where-Object {$_ -isnot [Management.Automation.Language.TypeConstraintAst]}).Count) {throw "DISPATCH_SIGNATURE $Name"}
    }
}

# Отвергает alias и подмену сигнатуры непосредственно перед literal вызовом.
function Assert-NativeDispatchCommand([string]$Name) {
    $command=Get-Command -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $command -or $command.CommandType -ne [Management.Automation.CommandTypes]::Function -or $command.Name -cne $Name) {throw "DISPATCH_COMMAND $Name"}
    Assert-NativeDispatchSignature $command.ScriptBlock.Ast $Name
}

# Реальный importer допускает только один string root, не alias или другой parameter contract.
function Assert-NativeDispatchImporter([string]$Name,[string]$RootParameter) {
    $command=Get-Command -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $command -or $command.CommandType -ne [Management.Automation.CommandTypes]::Function -or $command.Name -cne $Name) {throw "DISPATCH_IMPORTER $Name"}
    $definition=$command.ScriptBlock.Ast
    if ($definition -isnot [Management.Automation.Language.FunctionDefinitionAst]) {throw "DISPATCH_IMPORTER $Name"}
    $parameters=@(if ($null -ne $definition.Parameters) {$definition.Parameters} elseif ($null -ne $definition.Body.ParamBlock) {$definition.Body.ParamBlock.Parameters})
    if ($parameters.Count -ne 1 -or $parameters[0].Name.VariablePath.UserPath -cne $RootParameter -or
        $parameters[0].StaticType -ne [string] -or @($parameters[0].Attributes | Where-Object {$_ -isnot [Management.Automation.Language.TypeConstraintAst]}).Count) {throw "DISPATCH_IMPORTER $Name"}
}

# Rollback owner экспортирует изолированный AST module, не ambient функцию MAIN.
function Assert-NativeDispatchRollbackModule {
    $module=Get-Variable nativeDispatchRollbackModule -Scope Script -ValueOnly -ErrorAction SilentlyContinue
    if ($module -isnot [Management.Automation.PSModuleInfo] -or
        @($module.ExportedFunctions.Keys).Count -ne 2 -or -not $module.ExportedFunctions.ContainsKey('Set-NativeRollbackContext') -or
        -not $module.ExportedFunctions.ContainsKey('Invoke-NativeRollbackScenario')) {throw 'DISPATCH_ROLLBACK_MODULE'}
    $command=$module.SessionState.InvokeCommand.GetCommand('Invoke-NativeRollbackScenario',[Management.Automation.CommandTypes]::All)
    if ($null -eq $command -or $command.CommandType -ne [Management.Automation.CommandTypes]::Function -or $command.Name -cne 'Invoke-NativeRollbackScenario') {throw 'DISPATCH_ROLLBACK_MODULE'}
    Assert-NativeDispatchSignature $command.ScriptBlock.Ast 'Invoke-NativeRollbackScenario'
}

# Импортирует definitions, а не script body; AST привязывает реальные PSScriptRoot variables к source.
function Import-NativeDispatchFunctions([string]$SourceScriptsRoot,[string]$File,[string[]]$Names=@()) {
    $functions=@(Read-NativeDispatchFunctions $SourceScriptsRoot $File)
    foreach ($name in $Names) {if (@($functions | Where-Object Name -CEQ $name).Count -ne 1) {throw "DISPATCH_MISSING_FUNCTION $File/$name"}}
    foreach ($definition in $functions) {
        if ($Names.Count -and $definition.Name -cnotin $Names) {continue}
        $text=$definition.Extent.Text
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
        $variables=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.VariableExpressionAst] -and
            $node.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object {$_.Extent.StartOffset} -Descending)
        foreach ($variable in $variables) {
            if ($variable.Parent -is [Management.Automation.Language.ExpandableStringExpressionAst]) {throw 'DISPATCH_ROOT_INTERPOLATION'}
            $text=$text.Remove($variable.Extent.StartOffset,$variable.Extent.EndOffset-$variable.Extent.StartOffset).
                Insert($variable.Extent.StartOffset,("'"+$SourceScriptsRoot.Replace("'","''")+"'"))
        }
        $text=$text -replace ('^function\s+'+[regex]::Escape($definition.Name)+'(?=[\s(])'),('function script:'+$definition.Name)
        . ([scriptblock]::Create($text))
    }
}

# Закрытый switch не допускает динамическую команду или helper path из недоверенной строки.
function Get-NativeDispatchRoute([string]$Scenario) {
    switch -CaseSensitive -Exact ($Scenario) {
        {$_ -cin @('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close')} {return 'normal'}
        {$_ -cin @('two-clients','three-clients-pid-root-isolation')} {return 'concurrent'}
        {$_ -cin @('abrupt-ready-restart','launch-applying-safe-args')} {return 'ready'}
        {$_ -cin @('journal-fault','per-move-fault','helper-runtime-death')} {return 'phase'}
        {$_ -cin @('locked-rollback','readonly-rollback','disk-full-rollback')} {return 'rollback'}
        {$_ -cin @('unicode-payload','cashmemory','unmanaged-old-or-new')} {return 'payload'}
        default {throw 'DISPATCH_UNKNOWN_SCENARIO'}
    }
}

# Проверяет полный canonical plan без SESSION-подмены семи fault фаз и без Signoff defaults.
function Assert-NativeDispatchPlan($Rows) {
    if (@($Rows).Count -ne 612) {throw 'DISPATCH_CANONICAL_PLAN'}
    $scenarios=@('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts',
        'leases-normal-close','abrupt-ready-restart','launch-applying-safe-args','two-clients','three-clients-pid-root-isolation',
        'journal-fault','per-move-fault','helper-runtime-death','locked-rollback','readonly-rollback','disk-full-rollback',
        'unicode-payload','cashmemory','unmanaged-old-or-new')
    $required=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($scenario in $scenarios) {foreach ($base in 'B1','B2') {foreach ($client in 'fx','swing','web') {foreach ($path in 'ascii','cyrillic','unicode') {
        $phases=if ($scenario -cin @('journal-fault','per-move-fault')) {@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')} else {@('SESSION')}
        foreach ($phase in $phases) {[void]$required.Add("$scenario/$base/$client/$path/$phase")}
    }}}}
    foreach ($row in $Rows) {
        if ($row.status -cne 'PENDING' -or -not $required.Remove(($row.scenario+'/'+$row.base+'/'+$row.client+'/'+$row.path+'/'+$row.phase))) {throw 'DISPATCH_CANONICAL_PLAN'}
        $null=Get-NativeDispatchRoute $row.scenario
    }
    if ($required.Count) {throw 'DISPATCH_CANONICAL_PLAN'}
}

# Загружает реальные dependencies явно для каждой ветви; существующие importers выполняют только AST imports.
function Import-NativeDispatchRoute([string]$SourceScriptsRoot,[string]$Route) {
    Import-NativeDispatchFunctions $SourceScriptsRoot 'Test-NativeUpdateLifecycle.ps1'
    Assert-NativeDispatchCommand 'Invoke-NativeCell'
    # Фактический importer normal/cold/portable нужен каждой ветви, включая payload.
    Assert-NativeDispatchImporter 'Import-NativeDependencies' 'ScriptsRoot'
    Import-NativeDependencies $SourceScriptsRoot
    switch -CaseSensitive -Exact ($Route) {
        'normal' {}
        'concurrent' {
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateConcurrentScenarios.ps1'
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateConcurrentAcceptance.ps1'
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateTwoClientAcceptanceCollector.ps1'
            Assert-NativeDispatchImporter 'Import-NativeTwoClientCollectorDependencies' 'ScriptsRoot'
            Import-NativeTwoClientCollectorDependencies $SourceScriptsRoot
            Assert-NativeCollectedAdapter 'Invoke-NativeTwoClientCollectedScenario' 'Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/AcceptancePins/EvidenceKind'
        }
        'ready' {
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateReadyScenarios.ps1'
            Assert-NativeDispatchImporter 'Import-NativeReadyDependencies' 'ScriptsRoot'
            Import-NativeReadyDependencies $SourceScriptsRoot
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateReadyAcceptanceCollector.ps1'
            Assert-NativeDispatchImporter 'Import-NativeReadyCollectorDependencies' 'ScriptsRoot'
            Import-NativeReadyCollectorDependencies $SourceScriptsRoot
            Assert-NativeDispatchCommand 'Invoke-NativeReadyAcceptedCell'
        }
        'phase' {
            Import-NativeDispatchFunctions $SourceScriptsRoot 'Test-UpdateBootstrap.ps1' @('New-ColdJournal','New-ColdInstrumentedHelper',
                'Get-ColdCheckpoints','Assert-ColdCheckpoint','Assert-ColdProtectedEvidence','Start-ColdProcess','Save-ColdHelperDiagnostics',
                'Get-ColdRecoveryObservation','Assert-ColdRecoveryEvidence','Assert-ColdSafeArgs')
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdatePhaseScenarios.ps1'
        }
        'rollback' {
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateRollbackScenarios.ps1'
            Assert-NativeDispatchImporter 'Import-NativeRollbackDependencies' 'ScriptsRoot'
            $script:nativeDispatchRollbackModule=Import-NativeRollbackDependencies $SourceScriptsRoot
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateRollbackAcceptance.ps1'
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdateRollbackAcceptanceCollector.ps1'
            Assert-NativeDispatchRollbackModule
        }
        'payload' {
            Import-NativeDispatchFunctions $SourceScriptsRoot 'NativeUpdatePayloadScenarios.ps1'
            Assert-NativeDispatchImporter 'Initialize-NativePayloadDependencies' 'Root'
            Initialize-NativePayloadDependencies $SourceScriptsRoot
        }
        default {throw 'DISPATCH_UNKNOWN_ROUTE'}
    }
    # Проверяется именно экспорт ветви, а не alias на похожий normal flow.
    switch -CaseSensitive -Exact ($Route) {
        'normal' {Assert-NativeDispatchCommand 'Invoke-NativeCell'}
        'concurrent' {Assert-NativeDispatchCommand 'Invoke-NativeConcurrentScenario'}
        'ready' {Assert-NativeDispatchCommand 'Invoke-NativeReadyScenario'}
        'phase' {Assert-NativeDispatchCommand 'Invoke-NativePhaseScenario'}
        'rollback' {Assert-NativeDispatchRollbackModule}
        'payload' {Assert-NativeDispatchCommand 'Invoke-NativePayloadCell'}
    }
}

# Закрепляет source scripts и canonical Java source; partial/import failure не оставляет готового dispatcher.
function Initialize-NativeScenarioDispatch([string]$SourceScriptsRoot=$PSScriptRoot) {
    $script:nativeDispatchState=$null
    if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'DISPATCH_POWERSHELL7'}
    $root=Assert-NativeDispatchPath $SourceScriptsRoot -Directory
    $pins=@{}
    foreach ($file in Get-NativeDispatchFiles) {
        $null=Read-NativeDispatchFunctions $root $file
        $pins[$file]=(Get-FileHash -LiteralPath (Join-Path $root $file) -Algorithm SHA256).Hash
    }
    $javaSource=[IO.Path]::GetFullPath((Join-Path $root '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
    $null=Assert-NativeDispatchPath $javaSource
    $javaHash=(Get-FileHash -LiteralPath $javaSource -Algorithm SHA256).Hash
    Import-NativeDispatchFunctions $root 'Test-NativeUpdateLifecycle.ps1' @('Get-NativeEvidencePlan')
    Assert-NativeDispatchPlan @(Get-NativeEvidencePlan $javaSource)
    foreach ($route in 'normal','concurrent','ready','phase','rollback','payload') {Import-NativeDispatchRoute $root $route}
    Import-NativeDispatchFunctions $root 'NativeUpdateAcceptanceDispatch.ps1'
    Import-NativeDispatchFunctions $root 'Test-NativeUpdateEvidenceSignoff.ps1'
    $script:nativeDispatchState=[pscustomobject]@{SourceScriptsRoot=$root;Pins=$pins;CanonicalSource=$javaSource;CanonicalSha256=$javaHash}
    try {Assert-NativeDispatchFrozen} catch {$script:nativeDispatchState=$null;throw}
}

# Повторно проверяет frozen source перед любой ветвью; новые owner изменения требуют нового initialize.
function Assert-NativeDispatchFrozen {
    $state=Get-Variable nativeDispatchState -Scope Script -ValueOnly -ErrorAction SilentlyContinue
    if ($null -eq $state) {throw 'DISPATCH_NOT_INITIALIZED'}
    $null=Assert-NativeDispatchPath $state.SourceScriptsRoot -Directory
    foreach ($file in Get-NativeDispatchFiles) {
        $path=Join-Path $state.SourceScriptsRoot $file
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {throw "DISPATCH_MISSING_HELPER $file"}
        $null=Assert-NativeDispatchPath $path
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $state.Pins[$file]) {throw "DISPATCH_SOURCE_CHANGED $file"}
    }
    $null=Assert-NativeDispatchPath $state.CanonicalSource
    if ((Get-FileHash -LiteralPath $state.CanonicalSource -Algorithm SHA256).Hash -cne $state.CanonicalSha256) {throw 'DISPATCH_CANONICAL_CHANGED'}
}

# Независимые pins берутся из уже проверенного Cold, не из Row/receipt.
function Get-NativeCollectedAcceptancePins([string]$Source,$Cold) {
    $entries=@($Cold.baseManifests | Where-Object portableDir -CEQ $Source)
    if ($entries.Count -ne 1) {throw 'ACCEPTANCE_SOURCE_MANIFEST'}
    return [pscustomobject]@{baseManifest=$entries[0].manifest;baseSha256=$entries[0].sha256;
        targetManifest=$Cold.targetManifest;targetSha256=$Cold.targetManifestSha256;helperSha256=$Cold.helperSha256;
        concurrentSha256=$script:nativeDispatchState.Pins['NativeUpdateConcurrentScenarios.ps1'].ToLowerInvariant()}
}

# Дополнительная authority adapter проверяется отдельно от точных девяти args.
function Assert-NativeCollectedAdapter([string]$Name,[string]$Names) {
    $command=Get-Command $Name -ErrorAction Stop
    if ($command.CommandType -ne 'Function' -or $command.Name -cne $Name) {throw 'ACCEPTANCE_ADAPTER_KIND'}
    $ast=$command.ScriptBlock.Ast
    $parameters=@(if ($ast -is [Management.Automation.Language.FunctionDefinitionAst]) {
        if ($null -ne $ast.Parameters) {$ast.Parameters} else {$ast.Body.ParamBlock.Parameters}
    } else {$ast.ParamBlock.Parameters})
    if ((@($parameters | ForEach-Object {$_.Name.VariablePath.UserPath}) -join '/') -cne $Names -or
        @($parameters | ForEach-Object {$_.Attributes} | Where-Object {$_.TypeName.Name -cin @('Alias','AliasAttribute')}).Count) {throw 'ACCEPTANCE_ADAPTER_SIGNATURE'}
}

# Callback после cleanup, но до Dispose witness; canonical row не повышается.
function Invoke-NativeRollbackBridgeObserver($Row,$Authority,$Witness,$OriginalDecision,$Base,$Target,$Cold,[string]$Java) {
    $copy=Copy-NativeAcceptanceDispatchRow $Row 'rollback'
    $copy | Add-Member dispatchHelperStatus $Row.status -Force
    $copy | Add-Member dispatchHelperReason $Row.reason -Force
    if ($Row.status -cne 'FAIL') {$copy.status='PENDING'}
    if ($OriginalDecision.status -ceq 'FAIL') {$copy.status='FAIL'}
    return Invoke-NativeUpdateAcceptanceDispatch $copy $null $Base $Target $Cold $Java (
        [pscustomobject]@{Authority=$Authority;RetainedHelper=$Witness}) -EvidenceKind NATIVE
}

# Девять аргументов MAIN передаются без нового plan/acceptance; advanced return никогда не означает PASS.
function Invoke-NativeScenarioDispatch($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $route=Get-NativeDispatchRoute $Row.scenario
    $phases=if ($Row.scenario -cin @('journal-fault','per-move-fault')) {@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')} else {@('SESSION')}
    if ($Row.base -cnotin @('B1','B2') -or $Row.client -cnotin @('fx','swing','web') -or $Row.path -cnotin @('ascii','cyrillic','unicode') -or
        $Row.phase -cnotin $phases -or $Row.status -cne 'PENDING') {throw 'DISPATCH_ROW_IDENTITY'}
    # Пределы сохраняют текущие контракты helpers, не увеличивая их разрешённые ожидания.
    if ($route -ceq 'phase') {if ($Timeout -lt 10 -or $Timeout -gt 180) {throw 'DISPATCH_TIMEOUT'}}
    elseif ($Timeout -lt 30 -or $Timeout -gt 300) {throw 'DISPATCH_TIMEOUT'}
    Assert-NativeDispatchFrozen
    $identity=$Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase
    try {
        Import-NativeDispatchRoute $script:nativeDispatchState.SourceScriptsRoot $route
        Assert-NativeDispatchFrozen
        $collect=(Get-Variable nativeAcceptanceContext -Scope Script -ValueOnly -ErrorAction SilentlyContinue)
        $collectEnabled=($null -ne $collect -and $collect.enabled -eq $true)
        $routeInputs=$null;$earlyVerdict=$null;$reportedHelperStatus=$null;$reportedHelperReason=$null
        $helperReceipts=@(switch -CaseSensitive -Exact ($route) {
            'normal' {Assert-NativeDispatchCommand 'Invoke-NativeCell';Invoke-NativeCell $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout}
            'concurrent' {
                Assert-NativeDispatchCommand 'Invoke-NativeConcurrentScenario'
                if ($collectEnabled -and $Row.scenario -ceq 'two-clients') {
                    $pins=Get-NativeCollectedAcceptancePins $Source $Cold
                    $sourcePins=@{}
                    foreach ($file in 'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
                        'NativeUpdateConcurrentScenarios.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateTwoClientAcceptance.ps1') {
                        $sourcePins[$file]=$script:nativeDispatchState.Pins[$file].ToLowerInvariant()
                    }
                    $pins | Add-Member sourcePins $sourcePins
                    Assert-NativeCollectedAdapter 'Invoke-NativeTwoClientCollectedScenario' 'Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/AcceptancePins/EvidenceKind'
                    $collected=Invoke-NativeTwoClientCollectedScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout -AcceptancePins $pins -EvidenceKind NATIVE
                    if ($null -eq $collected -or $collected.scope -cne 'TWO_CLIENT_COLLECTOR_ONLY' -or
                        $collected.canonicalRowUnchanged -isnot [bool] -or -not $collected.canonicalRowUnchanged -or
                        $collected.observationStatus -cnotin @('OBSERVED','NOTPROVEN') -or
                        $collected.status -cnotin @('PENDING','PASS','FAIL')) {throw 'ACCEPTANCE_TWO_COLLECTOR_CONTRACT'}
                    $helperRow=$collected.helperRow
                    if ($null -eq $helperRow -or ($helperRow.scenario+'/'+$helperRow.base+'/'+$helperRow.client+'/'+$helperRow.path+'/'+$helperRow.phase) -cne $identity -or
                        $helperRow.status -cnotin @('PENDING','PASS','FAIL')) {throw 'ACCEPTANCE_TWO_HELPER_BINDING'}
                    if ($Row.status -cne 'PENDING' -or ($Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase) -cne $identity) {throw 'ACCEPTANCE_TWO_CANONICAL_MUTATED'}
                    if ($collected.helperError -or $helperRow.status -ceq 'FAIL') {throw 'ACCEPTANCE_TWO_HELPER_FAILURE'}
                    if ($collected.status -ceq 'FAIL') {throw 'ACCEPTANCE_TWO_COLLECTOR_FAILURE'}
                    if ($null -eq $collected.Evidence -or $collected.Evidence.BaseManifest -cne $pins.baseManifest -or
                        $collected.Evidence.BaseSha256 -cne $pins.baseSha256 -or $collected.Evidence.TargetManifest -cne $pins.targetManifest -or
                        $collected.Evidence.TargetSha256 -cne $pins.targetSha256 -or $collected.Evidence.ExpectedHelperSha256 -cne $pins.helperSha256 -or
                        $collected.Evidence.CellEvidence -cne $helperRow.concurrentCellEvidence -or
                        $collected.Evidence.SupplementalDirectory -cne $helperRow.concurrentCellEvidence -or
                        $collected.Evidence.IndependentSha256 -cne $collected.independentSha256) {throw 'ACCEPTANCE_TWO_EVIDENCE_BINDING'}
                    if ($collected.independentFile -and $collected.independentFile -cne (Join-Path $helperRow.concurrentCellEvidence 'two-client-independent.json')) {throw 'ACCEPTANCE_TWO_INDEPENDENT_PATH'}
                    # Только actual helper metadata переносится в canonical row. Статус не импортируется.
                    foreach ($field in 'executed','exe','args','baseRelease','baseCommit','targetRelease','targetCommit','command','startedAt','finishedAt',
                        'exitCode','skipped','failures','currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog',
                        'concurrentEvidence','runRoot','serverDirectories','concurrentCellEvidence') {
                        $property=$helperRow.PSObject.Properties[$field]
                        if ($null -ne $property) {$Row | Add-Member $field $property.Value -Force}
                    }
                    $reportedHelperStatus=$helperRow.status;$reportedHelperReason=$helperRow.reason
                    $routeInputs=$collected.Evidence | Select-Object *
                    $routeInputs | Add-Member CollectorObservationStatus $collected.observationStatus
                    $collected
                } elseif ($collectEnabled -and $Row.scenario -ceq 'three-clients-pid-root-isolation') {
                    $pins=Get-NativeCollectedAcceptancePins $Source $Cold
                    Assert-NativeCollectedAdapter 'Invoke-NativeConcurrentScenarioWithAcceptance' 'Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/AcceptancePins'
                    Invoke-NativeConcurrentScenarioWithAcceptance $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout -AcceptancePins $pins
                    $routeInputs=[pscustomobject]@{CellEvidence=$Row.concurrentCellEvidence;
                        BaseManifest=$pins.baseManifest;BaseSha256=$pins.baseSha256;TargetManifest=$pins.targetManifest;
                        TargetSha256=$pins.targetSha256;ExpectedHelperSha256=$pins.helperSha256;SupplementalDirectory=$Row.concurrentCellEvidence}
                } else {Invoke-NativeConcurrentScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout}
            }
            'ready' {
                if ($collectEnabled) {
                    Assert-NativeDispatchCommand 'Invoke-NativeReadyAcceptedCell'
                    $collected=Invoke-NativeReadyAcceptedCell $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
                    $routeInputs=[pscustomobject]@{Receipt=$collected.receipt;ReceiptSha256=$collected.receiptSha256;
                        IndependentFile=$collected.independentFile;IndependentSha256=$collected.independentSha256}
                    $collected
                } else {
                    Assert-NativeDispatchCommand 'Invoke-NativeReadyScenario'
                    Invoke-NativeReadyScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
                }
            }
            'phase' {Assert-NativeDispatchCommand 'Invoke-NativePhaseScenario';Invoke-NativePhaseScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout}
            'rollback' {
                Assert-NativeDispatchRollbackModule
                # Context isolated module задаётся до collector hooks.
                if ($collectEnabled) {
                    & $script:nativeDispatchRollbackModule {param($T,$P,$U) Set-NativeRollbackContext $T $P $U} $script:nativeTarget $script:nativeProject $script:nativeProfile
                    $pins=Get-NativeCollectedAcceptancePins $Source $Cold
                    $authority=[pscustomobject]@{sourceRoot=$Source;targetRoot=$script:nativeTarget;
                        originalManifest=[pscustomobject]@{path=$pins.baseManifest;sha256=$pins.baseSha256};
                        targetManifest=[pscustomobject]@{path=$pins.targetManifest;sha256=$pins.targetSha256};
                        originalHelper=[pscustomobject]@{path=$Cold.helperScript;sha256=$Cold.helperSha256}}
                    $bridgeObserver=${function:Invoke-NativeRollbackBridgeObserver}
                    $observer={param($R,$A,$W,$D) & $bridgeObserver $R $A $W $D $Base $Target $Cold $Java}.GetNewClosure()
                    Assert-NativeCollectedAdapter 'Invoke-NativeRollbackCollectedAcceptance' 'Adapter/Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/Authority/AcceptanceObserver'
                    $collected=Invoke-NativeRollbackCollectedAcceptance $script:nativeDispatchRollbackModule $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout $authority -AcceptanceObserver $observer
                    $earlyVerdict=$collected.bridgeVerdict
                    if ($collected.adapterFailure) {throw $collected.adapterFailure}
                    if ($collected.collectorFailure) {throw $collected.collectorFailure}
                    $collected
                } else {
                # Plain fallback не получает independent acceptance.
                & $script:nativeDispatchRollbackModule {
                    param($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,
                        [string]$TargetRoot,[string]$ProjectRoot,[string]$ProfileRoot)
                    Set-NativeRollbackContext $TargetRoot $ProjectRoot $ProfileRoot
                    Invoke-NativeRollbackScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
                } $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout $script:nativeTarget $script:nativeProject $script:nativeProfile
                }
            }
            'payload' {Assert-NativeDispatchCommand 'Invoke-NativePayloadCell';Invoke-NativePayloadCell $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout}
        })
        if (($Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase) -cne $identity) {throw 'DISPATCH_HELPER_ROW_CHANGED'}
        if ($Row.status -cnotin @('PENDING','PASS','FAIL')) {throw 'DISPATCH_HELPER_STATUS'}
        if ($Row.status -ceq 'FAIL') {throw 'DISPATCH_HELPER_REPORTED_FAILURE'}
        if ($route -cne 'normal' -or $collectEnabled) {
            $Row | Add-Member dispatchHelperStatus $(if ($null -ne $reportedHelperStatus) {$reportedHelperStatus} else {$Row.status}) -Force
            $Row | Add-Member dispatchHelperReason $(if ($null -ne $reportedHelperReason) {$reportedHelperReason} else {$Row.reason}) -Force
            $Row.status='PENDING';$Row.reason='NATIVE_DISPATCH_ADVANCED_ACCEPTANCE_PENDING'
        }
        # Raw helper outputs не теряются; отсутствие outputs не создаёт proof.
        $envelope=[pscustomobject]@{status='PENDING';scope='NATIVE_HELPER_RECEIPTS_ONLY';cellKey=$identity;helperReceipts=$helperReceipts}
        if ($collectEnabled) {
            if ($route -ceq 'payload') {
                # Expected MAIN закрепляет отдельно, не копируя pins из payload Row.
                $expected=if ($collect.payloadExpectedByCell.ContainsKey($identity)) {$collect.payloadExpectedByCell[$identity]} else {$null}
                $routeInputs=if ($null -ne $expected) {
                    [pscustomobject]@{ObservationFile=$Row.payloadObservation;
                        ObservationSha256=(Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $Row.payloadObservation)).Hash.ToLowerInvariant();Expected=$expected}
                } else {$null}
            }
            $verdict=if ($route -ceq 'rollback') {$earlyVerdict} else {
                Invoke-NativeUpdateAcceptanceDispatch $Row $envelope $Base $Target $Cold $Java $routeInputs -EvidenceKind NATIVE
            }
            if ($null -eq $verdict) {throw 'ACCEPTANCE_COLLECTOR_VERDICT_MISSING'}
            if ($verdict.cellKey -cne $identity -or $verdict.scope -cne 'SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY' -or
                $verdict.canonicalRowUnchanged -isnot [bool] -or -not $verdict.canonicalRowUnchanged) {throw 'ACCEPTANCE_VERDICT_BINDING'}
            $envelope=[pscustomobject]@{status='PENDING';scope='NATIVE_HELPER_RECEIPTS_ONLY';cellKey=$identity;
                helperReceipts=$helperReceipts;acceptanceVerdict=$verdict}
            if ($verdict.status -ceq 'FAIL') {throw ('ACCEPTANCE_CONTRADICTION: '+($verdict.contradictions -join '; '))}
            if ($verdict.status -ceq 'PASS') {
                if ($verdict.evidenceKind -cne 'NATIVE' -or $verdict.routeEvidenceValidated -isnot [bool] -or
                    -not $verdict.routeEvidenceValidated -or @($verdict.gaps).Count -or @($verdict.contradictions).Count -or
                    $Row.executed -isnot [bool] -or -not $Row.executed -or $Row.exitCode -ne 0 -or $Row.failures -ne 0 -or $Row.skipped -ne 0) {throw 'ACCEPTANCE_INCOMPLETE_PASS'}
                $Row.status='PASS';$Row.reason='MAIN_ROUTE_EVIDENCE_ACCEPTED_NOT_MATRIX_SIGNOFF'
            } elseif ($verdict.status -cne 'PENDING') {throw 'ACCEPTANCE_VERDICT_STATUS'}
        }
        if ($helperReceipts.Count -or $collectEnabled) {$envelope}
    } catch {
        $Row.status='FAIL';$Row.reason=$_.Exception.Message
        throw
    }
}
