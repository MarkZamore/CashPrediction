<#
.SYNOPSIS
Ограниченная проверка planner: actual AST constructors/selector, pure partitions и own Temp JSON.
.DESCRIPTION
Не выполняет native runner, процессы, сеть, GUI, Java или Maven. Fixture PASS не native PASS.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0
$planner=Join-Path $PSScriptRoot 'New-NativeUpdateMatrixPlan.ps1'
$canonical=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
$pins=@{}
foreach ($path in @($planner,$canonical,(Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'),
    (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1'),(Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'))) {
    $pins[$path]=(Get-FileHash -LiteralPath $path).Hash
}
# Проверяет факты harness, не назначая PASS никакой native строке.
function Assert-MatrixFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('MATRIX_FIXTURE_ASSERT '+$Code)};$script:checks++
}
# Отказ должен быть точным, не случайной ошибкой окружающей среды.
function Assert-MatrixRejected([scriptblock]$Action,[string]$Code) {
    $message=$null;try {& $Action | Out-Null} catch {$message=$_.Exception.Message}
    Assert-MatrixFixture ($message -ceq $Code) ('expected='+$Code+' actual='+$message)
}
# Импортирует только выбранные верхнеуровневые определения; param/body runner не исполняются.
function Import-MatrixFixtureDefinitions([string]$Path,[string[]]$Names=@()) {
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'MATRIX_FIXTURE_PARSE'}
    foreach ($def in $ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]}) {
        if ($Names.Count -and $def.Name -cnotin $Names) {continue}
        . ([scriptblock]::Create(($def.Extent.Text -replace ('^function '+[regex]::Escape($def.Name)),('function global:'+$def.Name))))
    }
    return $ast
}
$ast=Import-MatrixFixtureDefinitions $planner
Import-NativeMatrixPlanConstructors $PSScriptRoot
$null=Import-MatrixFixtureDefinitions (Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1') @('Get-NativeLifecycleSelectedRows')
$null=Import-MatrixFixtureDefinitions (Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1') @('Get-ColdSelectedPlan')

# Перед mocks убеждаемся, что importer вернул actual source body, а не самостоятельно написанную копию.
foreach ($entry in @(
    @{file='Test-NativeUpdateLifecycle.ps1';name='Get-NativeEvidencePlan'},
    @{file='NativeUpdateScenarioDispatch.ps1';name='Assert-NativeDispatchPlan'},
    @{file='NativeUpdateScenarioDispatch.ps1';name='Get-NativeDispatchRoute'}
)) {
    $tokens=$null;$errors=$null;$sourceAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $entry.file),[ref]$tokens,[ref]$errors)
    $defs=@($sourceAst.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $entry.name})
    Assert-MatrixFixture ($defs.Count -eq 1) ('EXACT_SOURCE_COUNT_'+$entry.name)
    $expected=& {param($text,$name) . ([scriptblock]::Create($text));(Get-Command $name).Definition} $defs[0].Extent.Text $entry.name
    Assert-MatrixFixture ((Get-Command $entry.name).Definition -ceq $expected) ('EXACT_IMPORTED_SOURCE_'+$entry.name)
}

# Любой случайный процесс/network вызов немедленно отказывает, даже если код добавят позже.
function Start-Process {throw 'MATRIX_FIXTURE_FORBIDDEN_PROCESS'}
function Get-CimInstance {throw 'MATRIX_FIXTURE_FORBIDDEN_NATIVE'}
function Get-NetTCPConnection {throw 'MATRIX_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-WebRequest {throw 'MATRIX_FIXTURE_FORBIDDEN_NETWORK'}
$rows=@(Get-NativeEvidencePlan $canonical)
$keys=@($rows | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase})
Assert-MatrixFixture ($keys.Count -eq 612 -and $keys[0] -ceq 'delta/B1/fx/ascii/SESSION' -and $keys[-1] -ceq 'unmanaged-old-or-new/B2/web/unicode/SESSION') 'CANONICAL_612_ENDPOINTS'
$plans=@{}
foreach ($workers in 1..6) {
    $plan=New-NativeUpdateMatrixPlan $canonical $workers $PSScriptRoot;$plans[$workers]=$plan
    Assert-MatrixFixture ($plan.status -ceq 'PENDING' -and -not $plan.executable -and -not $plan.nativeExecuted -and $plan.batches.Count -eq $workers) ('PLAN_ONLY_'+$workers)
    Assert-MatrixFixture ($plan.scenarioOrder.Count -eq 22 -and ($plan.canonicalKeys -join "`n") -ceq ($keys -join "`n")) ('ALL_SCENARIOS_ORDER_'+$workers)
    $flattened=@($plan.batches | ForEach-Object {$_.cellKeys})
    Assert-MatrixFixture (($flattened -join "`n") -ceq ($keys -join "`n") -and @($flattened | Sort-Object -Unique).Count -eq 612) ('EXACT_UNION_ORDER_'+$workers)
    $sizes=@($plan.batches | ForEach-Object {$_.count});$range=$sizes | Measure-Object -Minimum -Maximum
    Assert-MatrixFixture ($range.Maximum-$range.Minimum -le 1 -and ($sizes | Measure-Object -Sum).Sum -eq 612) ('BALANCED_'+$workers)
    Assert-MatrixFixture ($plan.proof.missingCount -eq 0 -and $plan.proof.duplicateCount -eq 0 -and $plan.proof.orderedKeySha256 -ceq (Get-NativeMatrixKeyHash $keys)) ('PARTITION_PROOF_'+$workers)
    $offset=0
    foreach ($batch in $plan.batches) {
        Assert-MatrixFixture ($batch.startIndex -eq $offset -and $batch.endIndex -eq $offset+$batch.count-1 -and $batch.orderedKeySha256 -ceq (Get-NativeMatrixKeyHash $batch.cellKeys)) 'BATCH_RANGE_AND_HASH'
        $params=$batch.commandArray[1]
        Assert-MatrixFixture ($batch.commandArray.Count -eq 2 -and $batch.commandArray[0] -ceq (Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1') -and $params.PortableDir.Count -eq 2) 'SAFE_SPLAT_ARRAY_NOT_SHELL'
        Assert-MatrixFixture ($params.StepTimeoutSeconds -eq 180 -and $params.MaxCells -eq $batch.count -and -not $params.Contains('Signoff') -and $params.Scenario.Count -eq 22) 'EXACT_RUNNER_BOUND_PHASE_TIMEOUT'
        foreach ($field in 'TargetPortableDir','Runtime','CommandFile','CommandFileSha256','LifecycleFile','LifecycleFileSha256') {
            Assert-MatrixFixture ($params[$field] -cmatch '^<REQUIRED_FRESH_[A-Z0-9_]+>$') ('NO_OLD_PINS_'+$field)
        }
        Assert-MatrixFixture (@($params.PortableDir | Where-Object {$_ -cnotmatch '^<REQUIRED_FRESH_[A-Z0-9_]+>$'}).Count -eq 0) 'FRESH_BOTH_BASES_REQUIRED'
        $selected=@(Get-NativeLifecycleSelectedRows $rows $params.Scenario $params.CellKey $params.MaxCells $params.StepTimeoutSeconds)
        $selectedKeys=@($selected | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase})
        Assert-MatrixFixture (($selectedKeys -join "`n") -ceq ($batch.cellKeys -join "`n")) 'ACTUAL_RUNNER_SELECTOR_MATCH'
        $offset+=$batch.count
    }
}
Assert-MatrixFixture ((@($plans[6].batches | ForEach-Object {$_.count}) -join ',') -ceq '102,102,102,102,102,102') 'SIX_102'
Assert-MatrixFixture ((@($plans[5].batches | ForEach-Object {$_.count}) -join ',') -ceq '123,123,122,122,122') 'FIVE_REMAINDER'
Assert-MatrixFixture ($plans[6].resourceCaveats.globalWebPortSlotBudget -eq 11 -and -not $plans[6].resourceCaveats.concurrencyMeasured -and $plans[6].resourceCaveats.recoveryPollingLimitMillis -eq 1000 -and -not $plans[6].resourceCaveats.nativePassPromised) 'RESOURCE_LIMITS_NOT_MEASURED'
foreach ($workers in 0,7,-1) {Assert-MatrixRejected {Get-NativeMatrixPartitions $rows $workers} 'MATRIX_WORKER_COUNT'}
Assert-MatrixRejected {Get-NativeMatrixPartitions @($rows[0..610]) 6} 'DISPATCH_CANONICAL_PLAN'
$duplicate=@($rows);$duplicate[611]=$duplicate[610]
Assert-MatrixRejected {Get-NativeMatrixPartitions $duplicate 6} 'DISPATCH_CANONICAL_PLAN'
$caseRows=@($rows | ForEach-Object {$_ | Select-Object *});$caseRows[0].scenario='Delta'
Assert-MatrixRejected {Get-NativeMatrixPartitions $caseRows 6} 'DISPATCH_CANONICAL_PLAN'
Assert-MatrixRejected {Get-NativeMatrixSourcePin 'relative.java'} 'MATRIX_SOURCE_PATH'
Assert-MatrixRejected {New-NativeUpdateMatrixPlan $planner 1 $PSScriptRoot} 'MATRIX_CANONICAL_NAME'
$invalidPlan=[pscustomobject]@{scope='PARTITION_PLAN_ONLY';status='PASS';nativeExecuted=$false;executable=$false}
Assert-MatrixRejected {Write-NativeUpdateMatrixPlan $invalidPlan} 'MATRIX_PUBLICATION_SCOPE'

# Единственная запись fixture - два своих новых Temp UUID planning JSON; никаких receipts/native PASS.
$outputs=@(Write-NativeUpdateMatrixPlan $plans[6];Write-NativeUpdateMatrixPlan $plans[1])
Assert-MatrixFixture ($outputs[0].planFile -cne $outputs[1].planFile) 'OWN_DISTINCT_TEMP_UUID'
foreach ($output in $outputs) {
    $parent=[IO.Path]::GetDirectoryName($output.planFile)
    Assert-MatrixFixture ($parent.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($parent) -cmatch '^cp-native-matrix-plan-[0-9a-f-]{36}$') 'OUTPUT_ONLY_OWN_TEMP'
    $stored=Get-Content -LiteralPath $output.planFile -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    Assert-MatrixFixture ($stored.status -ceq 'PENDING' -and -not $stored.nativeExecuted -and $output.planSha256 -ceq (Get-FileHash -LiteralPath $output.planFile).Hash.ToLowerInvariant()) 'JSON_PIN_NOT_NATIVE_AUTHORITY'
    foreach ($batch in $stored.batches) {
        $params=$batch.commandArray[1]
        Assert-MatrixFixture ($params -is [Collections.IDictionary] -and $params.CellKey.Count -eq $params.MaxCells -and $params.PortableDir.Count -eq 2 -and $params.Scenario.Count -eq 22) 'JSON_ROUNDTRIP_ARRAY_SPLAT'
    }
    Write-Host ('Planning fixture artifact: '+$output.planFile)
}
foreach ($path in $pins.Keys) {Assert-MatrixFixture ((Get-FileHash -LiteralPath $path).Hash -ceq $pins[$path]) ('SOURCE_UNCHANGED_'+[IO.Path]::GetFileName($path))}
Write-Host ('Matrix planner fixtures PASS: '+$script:checks+' checks; no native execution; no native PASS')
Write-Host ('Ordered canonical keys SHA256: '+$plans[6].proof.orderedKeySha256)
Write-Host ('Planner SHA256: '+(Get-FileHash -LiteralPath $planner).Hash.ToLowerInvariant())
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
