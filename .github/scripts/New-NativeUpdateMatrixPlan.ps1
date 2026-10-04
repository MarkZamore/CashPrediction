<#
.SYNOPSIS
Создаёт только bounded partition plan канонических 612 native клеток, без запуска runner.
.DESCRIPTION
FrozenCanonical - абсолютный путь UpdateEvidence.java; WorkerCount - 1..6.
Выход: новая собственная Temp UUID папка с matrix-plan.json, status=PENDING,
nativeExecuted=false, executable=false. Ни старые config, ни image pins не подставляются.
commandArray=[абсолютный runner path, parameter dictionary] - безопасный PowerShell splat,
не shell command line и не pwsh -File с потерей string[] границ.
MAIN после проверки свежих config/images/source pins и измерения concurrency заменяет ВСЕ
<REQUIRED_FRESH_...> placeholders. JSON читать ConvertFrom-Json -AsHashtable;
$scriptPath=$batch.commandArray[0]; $parameters=$batch.commandArray[1];
& $scriptPath @parameters - только явно разрешённый последующий native запуск MAIN.
Не передавать Signoff: каждая партия оставляет остальные canonical rows PENDING.
Объединение receipts и acceptance всех 612 - отдельная задача, план не является native PASS.
Бюджет caller 11 глобальных web-port slots не измерен/не зарезервирован; multi-client
клетка может держать два Web-клиента. Даже допустимый port budget не доказывает timing:
GUI/CPU/disk/CIM/TCP конкурируют, cold samples обязаны сохранить 1000 ms gate.
При interference MAIN уменьшает concurrency, вплоть до 1; шесть jobs не обещаны PASS.
Порядок canonical keys сохранён, но wall-clock порядок параллельных jobs не гарантируется.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$FrozenCanonical,
    [ValidateRange(1,6)][int]$WorkerCount=1
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3

# Разрешает только обычный абсолютный существующий файл без reparse предков.
function Get-NativeMatrixSourcePin([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or [IO.Path]::GetFullPath($Path) -cne $Path) {throw 'MATRIX_SOURCE_PATH'}
    $item=Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or $item.Length -gt 1048576) {throw 'MATRIX_SOURCE_FILE'}
    for ($node=$item;$null -ne $node;$node=if ($node -is [IO.FileInfo]) {$node.Directory} else {$node.Parent}) {
        if ($node.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'MATRIX_SOURCE_LINK'}
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# AST-only повторно использует реальный report constructor и строгий canonical validator.
function Import-NativeMatrixPlanConstructors([string]$ScriptsRoot) {
    foreach ($entry in @(
        @{file='Test-NativeUpdateLifecycle.ps1';names=@('Get-NativeEvidencePlan')},
        @{file='NativeUpdateScenarioDispatch.ps1';names=@('Assert-NativeDispatchPlan','Get-NativeDispatchRoute')}
    )) {
        $path=Join-Path $ScriptsRoot $entry.file;[void](Get-NativeMatrixSourcePin $path)
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'MATRIX_IMPORT_PARSE'}
        foreach ($name in $entry.names) {
            $defs=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})
            if ($defs.Count -ne 1) {throw ('MATRIX_IMPORT_FUNCTION '+$name)}
            $text=$defs[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
            . ([scriptblock]::Create($text))
        }
    }
}

# Хеширует exact ordered keys, включая завершающий LF, без platform newline зависимости.
function Get-NativeMatrixKeyHash([string[]]$Keys) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($Keys -join "`n")+"`n"))).ToLowerInvariant()
}

# Чистое разбиение полных rows: contiguous ranges, exact set/order, баланс по числу клеток.
function Get-NativeMatrixPartitions($Rows,[int]$Workers) {
    if ($Workers -lt 1 -or $Workers -gt 6) {throw 'MATRIX_WORKER_COUNT'}
    Assert-NativeDispatchPlan $Rows
    $keys=@($Rows | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase})
    $batches=[Collections.Generic.List[object]]::new();$offset=0
    $width=[int][math]::Floor($keys.Count/$Workers);$extra=$keys.Count%$Workers
    for ($worker=0;$worker -lt $Workers;$worker++) {
        $count=$width+$(if ($worker -lt $extra) {1} else {0})
        $selected=@($keys[$offset..($offset+$count-1)])
        $batches.Add([pscustomobject]@{worker=$worker+1;startIndex=$offset;endIndex=$offset+$count-1;count=$count;
            cellKeys=$selected;orderedKeySha256=(Get-NativeMatrixKeyHash $selected)})
        $offset+=$count
    }
    $flat=@($batches | ForEach-Object {$_.cellKeys})
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($i=0;$i -lt $keys.Count;$i++) {
        if ($flat[$i] -cne $keys[$i] -or -not $seen.Add($flat[$i])) {throw 'MATRIX_PARTITION_PROOF'}
    }
    if ($flat.Count -ne 612 -or $seen.Count -ne 612 -or $offset -ne 612) {throw 'MATRIX_PARTITION_PROOF'}
    return $batches.ToArray()
}

# Проверяет только исходный parameter contract runner; его тело не выполняется.
function Assert-NativeMatrixRunnerContract([string]$Path,[string[]]$Scenarios) {
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'MATRIX_RUNNER_PARSE'}
    $parameters=@($ast.ParamBlock.Parameters)
    foreach ($name in 'PortableDir','TargetPortableDir','Runtime','CommandFile','CommandFileSha256','LifecycleFile','LifecycleFileSha256','Scenario','CellKey','MaxCells','StepTimeoutSeconds') {
        $match=@($parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq $name})
        if ($match.Count -ne 1) {throw ('MATRIX_RUNNER_PARAMETER '+$name)}
        $type=if ($name -cin @('PortableDir','Scenario','CellKey')) {[string[]]} elseif ($name -cin @('MaxCells','StepTimeoutSeconds')) {[int]} else {[string]}
        if ($match[0].StaticType -ne $type) {throw ('MATRIX_RUNNER_TYPE '+$name)}
    }
    $scenario=@($parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'Scenario'})[0]
    $allowed=@($scenario.Attributes | Where-Object {$_.TypeName.Name -ceq 'ValidateSet'})
    if ($allowed.Count -ne 1 -or (@($allowed[0].PositionalArguments.Value) -join '/') -cne ($Scenarios -join '/')) {throw 'MATRIX_RUNNER_SCENARIOS'}
}

# Создаёт только planning object; source SHA - текущий snapshot, НЕ image/config/native authority.
function New-NativeUpdateMatrixPlan([string]$Canonical,[int]$Workers,[string]$ScriptsRoot) {
    if ($Workers -lt 1 -or $Workers -gt 6) {throw 'MATRIX_WORKER_COUNT'}
    if ([IO.Path]::GetFileName($Canonical) -cne 'UpdateEvidence.java') {throw 'MATRIX_CANONICAL_NAME'}
    $pins=[ordered]@{}
    $pins[$Canonical]=Get-NativeMatrixSourcePin $Canonical
    foreach ($file in 'Test-NativeUpdateLifecycle.ps1','NativeUpdateScenarioDispatch.ps1') {
        $path=Join-Path $ScriptsRoot $file;$pins[$path]=Get-NativeMatrixSourcePin $path
    }
    Import-NativeMatrixPlanConstructors $ScriptsRoot
    $text=Get-Content -LiteralPath $Canonical -Raw -Encoding utf8
    # Существующий report constructor не является Java parser: изменение traversal/phases требует review.
    if ($text -cnotmatch 'for \(String scenario : SCENARIOS\) for \(String base : BASES\) for \(String client : CLIENTS\)\s*for \(String path : PATHS\) for \(String phase : phases\(scenario\)\)' -or
        $text -cnotmatch 'return scenario\.equals\("journal-fault"\) \|\| scenario\.equals\("per-move-fault"\) \? PHASES : List\.of\("SESSION"\);') {throw 'MATRIX_CANONICAL_TRAVERSAL'}
    $rows=@(Get-NativeEvidencePlan $Canonical);$partitions=@(Get-NativeMatrixPartitions $rows $Workers)
    $scenarios=@($rows | Select-Object -ExpandProperty scenario -Unique)
    if ($scenarios.Count -ne 22) {throw 'MATRIX_SCENARIO_COUNT'}
    $runner=Join-Path $ScriptsRoot 'Test-NativeUpdateLifecycle.ps1'
    Assert-NativeMatrixRunnerContract $runner $scenarios
    foreach ($batch in $partitions) {
        $parameters=[ordered]@{PortableDir=@('<REQUIRED_FRESH_B1_PORTABLE_DIR>','<REQUIRED_FRESH_B2_PORTABLE_DIR>');
            TargetPortableDir='<REQUIRED_FRESH_TARGET_PORTABLE_DIR>';Runtime='<REQUIRED_FRESH_JAVA_EXE>';
            CommandFile='<REQUIRED_FRESH_COMMAND_CONFIG_PATH>';CommandFileSha256='<REQUIRED_FRESH_COMMAND_CONFIG_SHA256>';
            LifecycleFile='<REQUIRED_FRESH_LIFECYCLE_CONFIG_PATH>';LifecycleFileSha256='<REQUIRED_FRESH_LIFECYCLE_CONFIG_SHA256>';
            Scenario=$scenarios;CellKey=@($batch.cellKeys);MaxCells=[int]$batch.count;StepTimeoutSeconds=180}
        $batch | Add-Member commandArray @($runner,$parameters)
        $batch | Add-Member executable $false
        # Консервативный бюджет, не измерение: в root-isolation возможны два web clients.
        $batch | Add-Member webClientBudget $(if (@($batch.cellKeys | Where-Object {$_ -cmatch '^three-clients-pid-root-isolation/[^/]+/web/'}).Count) {2} else {1})
    }
    foreach ($path in $pins.Keys) {if ((Get-NativeMatrixSourcePin $path) -cne $pins[$path]) {throw 'MATRIX_SOURCE_CHANGED'}}
    $keys=@($rows | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase})
    return [pscustomobject]@{schemaVersion=1;status='PENDING';scope='PARTITION_PLAN_ONLY';nativeExecuted=$false;executable=$false;
        canonicalPath=$Canonical;sourcePins=$pins;workerCount=$Workers;scenarioOrder=$scenarios;canonicalKeys=$keys;batches=$partitions;
        proof=[pscustomobject]@{canonicalCount=612;partitionCount=612;uniqueCount=612;missingCount=0;duplicateCount=0;
            orderedCoverage=$true;orderedKeySha256=(Get-NativeMatrixKeyHash $keys);balanceDifference=$(if (612%$Workers) {1} else {0})};
        resourceCaveats=[pscustomobject]@{globalWebPortSlotBudget=11;portBudgetMeasured=$false;
            conservativeWebClientBudget=[int](($partitions | Measure-Object webClientBudget -Sum).Sum);
            recoveryPollingLimitMillis=1000;concurrencyMeasured=$false;recommendedInitialConcurrency=1;
            reduceConcurrencyOnInterference=$true;nativePassPromised=$false};
        freshInputsRequired=@('two base images and manifests','target image and manifest','runtime/tool/harness pins',
            'new CommandFile/SHA256','new LifecycleFile/SHA256','fresh frozen dispatcher/helper pins','MAIN concurrency measurement')}
}

# Публикует JSON только в новой своей UUID папке, никогда не перезаписывает runner/config/evidence.
function Write-NativeUpdateMatrixPlan($Plan) {
    if ($Plan.scope -cne 'PARTITION_PLAN_ONLY' -or $Plan.status -cne 'PENDING' -or $Plan.nativeExecuted -or $Plan.executable) {throw 'MATRIX_PUBLICATION_SCOPE'}
    $directory=Join-Path ([IO.Path]::GetTempPath()) ('cp-native-matrix-plan-'+[guid]::NewGuid().ToString())
    if (Test-Path -LiteralPath $directory) {throw 'MATRIX_OUTPUT_EXISTS'}
    [void][IO.Directory]::CreateDirectory($directory)
    $file=Join-Path $directory 'matrix-plan.json'
    # CreateNew не допускает перезапись даже при неожиданной гонке файловой системы.
    $stream=[IO.File]::Open($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $Plan -Depth 32))
        $stream.Write($bytes,0,$bytes.Length)
    } finally {$stream.Dispose()}
    return [pscustomobject]@{status='PENDING';nativeExecuted=$false;planFile=$file;planSha256=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant();proof=$Plan.proof}
}

Write-NativeUpdateMatrixPlan (New-NativeUpdateMatrixPlan $FrozenCanonical $WorkerCount $PSScriptRoot)
