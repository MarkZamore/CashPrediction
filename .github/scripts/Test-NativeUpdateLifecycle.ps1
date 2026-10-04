<#
.SYNOPSIS
Нативные S7 download/normal-exit клетки из готовых frozen образов, без сборки.
.DESCRIPTION
CommandFile/Sha256 имеют неизменённый контракт Test-UpdateBootstrap.ps1.
LifecycleFile/Sha256: schemaVersion=1; artifactDir; manifestSha256;
harnessClasspath (абсолютные jar/каталоги через ;); harnessFiles=[{path,sha256}].
Все файлы каждого элемента harnessClasspath перечисляются и закрепляются, без ссылок.
artifactDir содержит неизменённый update.json, полный ZIP и две прямые дельты B1/B2.
Runtime - готовый java.exe для NativeUpdateServer, не альтернативный запуск клиента.
Клиенты запускаются только настоящими versioned jpackage exe из независимых копий.
Полный UpdateEvidence план сохраняется: неподдержанные клетки остаются PENDING.
Dispatcher покрывает все 22 сценария и 612 клеток, включая семь durable fault фаз.
MaxCells ограничивает exact выбранные клетки; default 144 сохраняет прежний bounded бюджет.
Advanced receipts не означают PASS: independent acceptance пока остаётся PENDING.
Скрипт не подтверждает DOM/скриншоты и не подменяет GUI gate MAIN.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$PortableDir,
    [Parameter(Mandatory)][string]$TargetPortableDir,
    [Parameter(Mandatory)][string]$Runtime,
    [Parameter(Mandatory)][string]$CommandFile,
    [Parameter(Mandatory)][string]$CommandFileSha256,
    [Parameter(Mandatory)][string]$LifecycleFile,
    [Parameter(Mandatory)][string]$LifecycleFileSha256,
    [ValidateSet('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts',
        'leases-normal-close','abrupt-ready-restart','launch-applying-safe-args','two-clients','three-clients-pid-root-isolation',
        'journal-fault','per-move-fault','helper-runtime-death','locked-rollback','readonly-rollback','disk-full-rollback',
        'unicode-payload','cashmemory','unmanaged-old-or-new')]
    [string[]]$Scenario=@('delta','corrupt-delta-full','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close'),
    [ValidateRange(30,300)][int]$StepTimeoutSeconds=180,
    [ValidateRange(1,612)][int]$MaxCells=144,
    [switch]$Signoff,
    [string[]]$CellKey=@(),
    [switch]$CollectAcceptance,
    [string]$AcceptanceContextFile,
    [string]$AcceptanceContextSha256,
    [string]$SignoffRequestFile,
    [string]$SignoffRequestSha256
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3

# Только существующие определения, без исполнения тела cold/portable runner или helper injection.
function Import-NativeDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    $source=Join-Path $ScriptsRoot 'Test-UpdateBootstrap.ps1';$tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'NATIVE_DEPENDENCY_PARSE'}
    foreach ($name in 'Import-ColdPortableSafety','Assert-ColdKeys','Get-ColdSelectedPlan','Read-ColdPinnedJson','Assert-ColdCommand',
        'Test-ColdInteger','Get-ColdLauncherName','Assert-ColdInventory','Assert-ColdImageInventory','Test-ColdInventoryEqual',
        'Get-ColdObjectHash','Get-ColdUtcTicks','ConvertFrom-ColdReceiptJson','ConvertFrom-ColdCommandLine','Convert-ColdNativePort','Assert-ColdUiReceipt',
        'Assert-ColdOwnedRun','Get-ColdManagedInventory','Get-ColdTreeHash','Assert-ColdTree','Get-ColdUserInventory',
        'Get-ColdControlledInventory','Assert-ColdNativeImage','Get-ColdVersion','Get-ColdMainModule','Test-ColdExternalModules','Get-ColdProcessReceipt',
        'Assert-ColdProcessIdentity','Stop-ColdRetainedProcess','Get-ColdCurrentProcess','Get-ColdUiReceipt','Stop-ColdCopyProcesses',
        'Assert-ColdHelperCommand','Stop-ColdRecoveryHelpers','Invoke-ColdTool','Get-ColdSessionWords','Assert-ColdControlledChanges','Write-ColdJson') {
        $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw "NATIVE_DEPENDENCY_FUNCTION $name"}
        $definition=$definitions[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
        # AST-импорт не сохраняет file scope PSScriptRoot: общий формат остаётся привязан к исходному repo.
        $definition=$definition.Replace('$PSScriptRoot',("'"+$ScriptsRoot.Replace("'","''")+"'"))
        . ([scriptblock]::Create($definition))
    }
    Import-ColdPortableSafety (Join-Path $ScriptsRoot 'Test-Portable.ps1')
}

# Канонический абсолютный путь дополнительно к общей защите reparse/предков.
function Assert-NativeAbsolute([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path -cne [IO.Path]::GetFullPath($Path).TrimEnd('\','/')) {throw 'NATIVE_ABSOLUTE_PATH'}
    return (Resolve-PortableSafetyPath $Path)
}

# Runtime и tool уже проверяются cold-контрактом; отдельный pin покрывает каждый byte harness.
function Assert-NativeLifecycleConfig($Config) {
    Assert-ColdKeys $Config @('schemaVersion','artifactDir','manifestSha256','harnessClasspath','harnessFiles')
    if (-not (Test-ColdInteger $Config.schemaVersion 1) -or $Config.schemaVersion -ne 1 -or
        $Config.manifestSha256 -cnotmatch '^[0-9a-f]{64}$' -or $Config.harnessClasspath -isnot [string]) {throw 'NATIVE_CONFIG'}
    [void](Assert-NativeAbsolute $Config.artifactDir)
    $entries=@($Config.harnessClasspath.Split(';'));$actual=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($entries.Count -lt 1 -or $entries.Count -gt 4) {throw 'NATIVE_CLASSPATH'}
    foreach ($entry in $entries) {
        [void](Assert-NativeAbsolute $entry);Assert-PortableTreeHasNoLinks $entry
        if (Test-Path -LiteralPath $entry -PathType Container) {$files=@(Get-ChildItem -LiteralPath $entry -Recurse -File -Force)}
        else {
            if ([IO.Path]::GetExtension($entry) -cne '.jar') {throw 'NATIVE_CLASSPATH'}
            $files=@(Get-Item -LiteralPath $entry)
        }
        foreach ($file in $files) {if (-not $actual.Add($file.FullName) -or $actual.Count -gt 20000 -or $file.Length -gt 536870912) {throw 'NATIVE_HARNESS_LIMIT'}}
    }
    $pins=@($Config.harnessFiles);$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($pins.Count -ne $actual.Count -or -not $pins.Count) {throw 'NATIVE_HARNESS_SET'}
    foreach ($pin in $pins) {
        Assert-ColdKeys $pin @('path','sha256');[void](Assert-NativeAbsolute $pin.path)
        if (-not $seen.Add($pin.path) -or -not $actual.Contains($pin.path) -or $pin.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha256) {throw 'NATIVE_HARNESS_PIN'}
    }
    $hasServer=$false
    foreach ($entry in $entries) {
        if (Test-Path -LiteralPath $entry -PathType Container) {$hasServer=$hasServer -or (Test-Path -LiteralPath (Join-Path $entry 'ru/cashprediction/parity/update/NativeUpdateServer.class'))}
        else {
            $archive=[IO.Compression.ZipFile]::OpenRead($entry)
            try {$hasServer=$hasServer -or $null -ne $archive.GetEntry('ru/cashprediction/parity/update/NativeUpdateServer.class')} finally {$archive.Dispose()}
        }
    }
    if (-not $hasServer) {throw 'NATIVE_SERVER_CLASS_MISSING'}
}

# Старый versioned образ без строгого seam не направляется случайно в production GitHub.
function Assert-NativeSeam([string]$Root) {
    $version=Get-ColdVersion $Root;$archive=[IO.Compression.ZipFile]::OpenRead((Join-Path (Join-Path $Root 'app') $version.jar))
    try {
        foreach ($name in 'ru/cashprediction/core/update/lifecycle/UpdateLifecycle.class','ru/cashprediction/core/update/lifecycle/UpdateSessionLifecycle.class') {
            if ($null -eq $archive.GetEntry($name)) {throw 'NATIVE_SEAM_CLASS_MISSING'}
        }
        $stream=$archive.GetEntry('ru/cashprediction/core/update/lifecycle/UpdateLifecycle.class').Open()
        try {$memory=[IO.MemoryStream]::new();$stream.CopyTo($memory);$text=[Text.Encoding]::Latin1.GetString($memory.ToArray());$memory.Dispose()}
        finally {$stream.Dispose()}
        if (-not $text.Contains('cashprediction.update.selftest.manifest')) {throw 'NATIVE_SEAM_MISSING'}
    } finally {$archive.Dispose()}
}

# План читается из текущего UpdateEvidence, не вводит собственную схему/список фаз.
function Get-NativeEvidencePlan([string]$Source) {
    $text=Get-Content -LiteralPath $Source -Raw -Encoding utf8;$lists=@{}
    foreach ($name in 'CLIENTS','PATHS','BASES','PHASES','SCENARIOS') {
        $match=[regex]::Match($text,('public static final List<String> '+$name+' = List\.of\((.*?)\);'),[Text.RegularExpressions.RegexOptions]::Singleline)
        if (-not $match.Success) {throw 'NATIVE_EVIDENCE_CONTRACT'}
        $lists[$name]=@([regex]::Matches($match.Groups[1].Value,'"([a-zA-Z0-9_-]+)"') | ForEach-Object {$_.Groups[1].Value})
        if (-not $lists[$name].Count) {throw 'NATIVE_EVIDENCE_CONTRACT'}
    }
    $rows=[Collections.Generic.List[object]]::new()
    foreach ($scenario in $lists.SCENARIOS) {foreach ($base in $lists.BASES) {foreach ($client in $lists.CLIENTS) {foreach ($path in $lists.PATHS) {
        $phases=if ($scenario -in @('journal-fault','per-move-fault')) {$lists.PHASES} else {@('SESSION')}
        foreach ($phase in $phases) {$rows.Add([pscustomobject][ordered]@{scenario=$scenario;base=$base;client=$client;path=$path;phase=$phase;
            status='PENDING';reason='PORTABLE_EXECUTION_NOT_IMPLEMENTED'})}
    }}}}
    return $rows.ToArray()
}

# Импортирует только определения fixed dispatcher; тело lifecycle/других runners не выполняется.
function Initialize-NativeLifecycleDispatch([string]$ScriptsRoot) {
    $path=Join-Path $ScriptsRoot 'NativeUpdateScenarioDispatch.ps1'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {throw 'NATIVE_DISPATCH_MISSING'}
    [void](Resolve-PortableSafetyPath $path)
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'NATIVE_DISPATCH_PARSE'}
    $definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
    foreach ($name in 'Initialize-NativeScenarioDispatch','Assert-NativeDispatchPlan','Get-NativeDispatchRoute','Invoke-NativeScenarioDispatch') {
        if (@($definitions | Where-Object Name -CEQ $name).Count -ne 1) {throw ('NATIVE_DISPATCH_FUNCTION '+$name)}
    }
    foreach ($definition in $definitions) {
        $text=$definition.Extent.Text -replace ('^function\s+'+[regex]::Escape($definition.Name)+'(?=[\s(])'),('function script:'+$definition.Name)
        . ([scriptblock]::Create($text))
    }
    Initialize-NativeScenarioDispatch -SourceScriptsRoot $ScriptsRoot
}

# Canonical order и row references сохраняются; bound применяется после exact selection, не Scenario.Count*18.
function Get-NativeLifecycleSelectedRows($Rows,[string[]]$Scenarios,[string[]]$Keys,[int]$Limit,[int]$Timeout) {
    Assert-NativeDispatchPlan $Rows
    if ($Scenarios.Count -eq 0 -or $Limit -lt 1 -or $Limit -gt 612 -or $Timeout -lt 30 -or $Timeout -gt 300) {throw 'NATIVE_CELL_BOUND'}
    $known=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($scenario in $Scenarios) {
        $null=Get-NativeDispatchRoute $scenario
        if (-not $known.Add($scenario)) {throw 'NATIVE_TWO_BASES_AND_UNIQUE_SCENARIOS'}
    }
    $selectionPlan=@($Rows | Where-Object {$known.Contains($_.scenario)} | ForEach-Object {
        [pscustomobject]@{key=($_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase);row=$_}
    })
    $selected=@(Get-ColdSelectedPlan $selectionPlan $Keys | ForEach-Object {$_.row})
    if ($selected.Count -eq 0 -or $selected.Count -gt $Limit) {throw 'NATIVE_CELL_BOUND'}
    # Текущий phase helper имеет более строгий верхний предел; весь selector отвергается до первой клетки.
    if ($Timeout -gt 180 -and @($selected | Where-Object {(Get-NativeDispatchRoute $_.scenario) -ceq 'phase'}).Count) {throw 'DISPATCH_TIMEOUT'}
    return $selected
}

# Caller context восстанавливается также при исключении; private ready/payload/rollback ownership не меняется.
function Invoke-NativeLifecycleDispatchedCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $saved=@{}
    foreach ($name in 'nativeTarget','nativeProject','nativeProfile','WorkDir','nativeNode','nativeUi','nativeQuiet') {
        $variable=Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue
        $saved[$name]=[pscustomobject]@{exists=($null -ne $variable);value=$(if ($null -ne $variable) {$variable.Value} else {$null})}
    }
    try {
        Invoke-NativeScenarioDispatch $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
    }
    finally {
        foreach ($name in $saved.Keys) {
            if ($saved[$name].exists) {Set-Variable -Name $name -Scope Script -Value $saved[$name].value}
            else {Remove-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue}
        }
    }
}

# Только opt-in MAIN map; наличие path без independently held SHA не разрешает native.
function Get-NativeLifecyclePinnedIntent($Context,$Row,[string]$Route) {
    if ($null -eq $Context -or -not $Context.enabled -or $Route -cnotin @('normal','payload')) {return $null}
    $name=if ($Route -ceq 'normal') {'normalIntentByCell'} else {'payloadAuthorityByCell'}
    if ($null -eq $Context.PSObject.Properties[$name]) {return $null}
    $map=$Context.$name
    $key=$Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase
    if ($map -isnot [Collections.IDictionary]) {throw 'NATIVE_PINNED_MAP_KIND'}
    if (-not $map.Contains($key)) {return $null}
    $entry=$map[$key]
    Assert-ColdKeys $entry @('key','file','sha256','evidenceKind')
    if ($entry.key -cne $key -or $entry.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        -not [IO.Path]::IsPathFullyQualified($entry.file) -or $entry.evidenceKind -cnotin @('UNVERIFIED','NATIVE','UNIT_MOCK')) {throw 'NATIVE_PINNED_ENTRY'}
    return $entry
}

# Exact9 dispatcher остаётся нетронут; отдельный adapter возвращает receipt, не sealed signoff.
function Invoke-NativeLifecyclePinnedCell($Entry,$Row,[string]$Source,$Base,$Target,$Life,$Cold,
    [string]$Java,[string]$Evidence,[int]$Timeout) {
    $key=$Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase
    if ($Row.status -cne 'PENDING') {throw 'NATIVE_PINNED_CANONICAL_NOT_PENDING'}
    $route=Get-NativeDispatchRoute $Row.scenario
    if ($Entry.evidenceKind -cne 'NATIVE') {
        return [pscustomobject]@{status='PENDING';scope='MAIN_PINNED_SELECTED_CELL_ONLY';cellKey=$key;
            acceptanceVerdict='PENDING';evidenceKind=$Entry.evidenceKind;missing=@('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED');
            errors=@();fullMatrix='PENDING';releaseProvenance='PENDING';canonicalRowUnchanged=$true}
    }
    Assert-NativeDispatchFrozen
    Import-NativeDispatchRoute $PSScriptRoot $route
    if ($route -ceq 'normal') {
        . (Join-Path $PSScriptRoot 'NativeUpdatePayloadAcceptance.ps1')
        . (Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptance.ps1')
        . (Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptanceCollector.ps1')
        $ticket=New-NativeNormalCollector $Entry.file $Entry.sha256 'NATIVE'
        $decision=Invoke-NativeNormalCollectedCell $ticket $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
    } elseif ($route -ceq 'payload') {
        $decision=Invoke-NativePayloadAuthorizedCell $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout $Entry.file $Entry.sha256
    } else {throw 'NATIVE_PINNED_ROUTE'}
    if ($Row.status -cne 'PENDING') {throw 'NATIVE_PINNED_CANONICAL_MUTATED'}
    if ($null -eq $decision -or $decision.status -cnotin @('PASS','PENDING','FAIL')) {throw 'NATIVE_PINNED_DECISION'}
    $helper=$decision.helperRow
    if ($null -ne $helper) {
        if (($helper.scenario+'/'+$helper.base+'/'+$helper.client+'/'+$helper.path+'/'+$helper.phase) -cne $key) {throw 'NATIVE_PINNED_HELPER_IDENTITY'}
        # Переносятся observations, но status/reason/helper PASS не переносятся.
        foreach ($property in $helper.PSObject.Properties) {
            if ($property.Name -cnotin @('status','reason','scenario','base','client','path','phase')) {$Row | Add-Member $property.Name $property.Value -Force}
        }
    }
    return [pscustomobject]@{status='PENDING';scope='MAIN_PINNED_SELECTED_CELL_ONLY';cellKey=$key;
        acceptanceVerdict=$decision.status;evidenceKind='NATIVE';decision=$decision;
        canonicalRowUnchanged=$true;fullMatrix='PENDING';releaseProvenance='PENDING'}
}

# Никакая временная дата или количество polling не заменяют ограничение монотонных часов.
function Wait-NativeCondition([scriptblock]$Condition,[int]$Seconds,[string]$Code) {
    $clock=[Diagnostics.Stopwatch]::StartNew()
    do {if (& $Condition) {return};Start-Sleep -Milliseconds 50} while ($clock.Elapsed.TotalSeconds -lt $Seconds)
    throw $Code
}

# Строгие seam аргументы неизменны для каждой jpackage JVM этой копии.
function Get-NativeArguments([string]$Root,[string]$Client,[string]$Node) {
    [void](Assert-NativeAbsolute $Root);[void](Get-PortableRegistryPath $Node)
    $arguments=@('--test-api','--home',$Root,'--registry-node',$Node)
    if ($Client -ceq 'web') {$arguments+=@('--no-browser','--no-window')} else {[void](Get-ColdLauncherName $Client)}
    return $arguments
}

# URI принимается только в точном виде seam, без альтернативных схем/путей/query.
function Assert-NativeEndpoint([string]$Endpoint) {
    $match=[regex]::Match($Endpoint,'^http://127\.0\.0\.1:([1-9][0-9]{0,4})/update\.json$')
    if (-not $match.Success -or [int]$match.Groups[1].Value -gt 65535) {throw 'NATIVE_ENDPOINT'}
}

# Внешний процесс только executable + ArgumentList; исходное окружение не меняется.
function Start-NativeOwned([string]$Executable,[string[]]$Arguments,[string]$Root,[string]$Endpoint='',[switch]$Web) {
    $info=[Diagnostics.ProcessStartInfo]::new($Executable);$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WorkingDirectory=$Root
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach ($argument in $Arguments) {$info.ArgumentList.Add($argument)}
    foreach ($key in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') {[void]$info.Environment.Remove($key)}
    if ($Endpoint) {
        Assert-NativeEndpoint $Endpoint
        $info.Environment['JAVA_TOOL_OPTIONS']='-XX:-UsePerfData -Dcashprediction.update.selftest.manifest='+$Endpoint+
            ' -Djava.net.useSystemProxies=false -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1 -Dcashprediction.web.port=0'
    }
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    if (-not $process.Start()) {$process.Dispose();throw 'NATIVE_START'}
    $identity=Get-ColdProcessReceipt $process $Root
    return [pscustomobject]@{process=$process;identity=$identity;stderr=$process.StandardError.ReadToEndAsync();
        stdout=$(if ($Web) {$null} else {$process.StandardOutput.ReadToEndAsync()});web=[bool]$Web;webUrl=$null;prefix='';uiProcess=$null;ui=$null;lineTask=$null}
}

# Квитанция сервера только в его пустом Temp UUID, pinned endpoints не берутся из произвольных данных.
function Assert-NativeServerReceipt($Receipt,[string]$Owned,$Identity,[string]$ManifestSha) {
    Assert-ColdKeys $Receipt @('schemaVersion','pid','startedUtc','mode','manifestUri','stopPath','cancelPath','artifactDir','manifestSha256','fixtures')
    if ($Receipt.schemaVersion -ne 1 -or $Receipt.pid -ne $Identity.ProcessId -or $Receipt.manifestSha256 -cne $ManifestSha -or
        $Receipt.stopPath -cne (Join-Path $Owned 'server.stop') -or $Receipt.cancelPath -cne (Join-Path $Owned 'server.cancel') -or
        $Receipt.manifestUri -cnotmatch '^http://127\.0\.0\.1:[1-9][0-9]{0,4}/update\.json$' -or
        [int]([regex]::Match($Receipt.manifestUri,':([0-9]+)/').Groups[1].Value) -gt 65535 -or
        (Get-NativeUtcTicks $Receipt.startedUtc) -lt $Identity.StartedAtTicks) {throw 'NATIVE_SERVER_RECEIPT'}
}

# Готовая файловая фикстура отдаётся существующим Java API через ASCII Base64 UTF-8 JSON.
function Start-NativeFixture($Life,[string]$Java,[string]$Mode,[int]$Delay) {
    $owned=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString());[void](Assert-NativeAbsolute $owned)
    $null=New-Item -ItemType Directory -Path $owned
    $chunkBytes=if ($Mode -ceq 'slowchunks') {32} else {65536}
    $json=ConvertTo-Json -Compress -InputObject ([ordered]@{artifactDir=$Life.artifactDir;ownedTempUuid=$owned;mode=$Mode;
        delayMillis=$Delay;chunkBytes=$chunkBytes;maxLifetimeMillis=600000})
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    $ownedProcess=Start-NativeOwned $Java @('-XX:-UsePerfData','--add-modules','jdk.httpserver','-cp',$Life.harnessClasspath,
        'ru.cashprediction.parity.update.NativeUpdateServer',$encoded) $owned
    $ownedProcess | Add-Member owned $owned
    # Конфигурация совпадает с фактически переданным Base64 UTF-8 аргументом сервера.
    $ownedProcess | Add-Member launchConfig (ConvertFrom-ColdReceiptJson $json)
    $ownedProcess | Add-Member launchConfigBase64 $encoded
    try {
        Wait-NativeCondition {if ($ownedProcess.process.HasExited) {throw 'NATIVE_SERVER_EXIT'};Test-Path -LiteralPath (Join-Path $owned 'server-receipt.json')} 15 'NATIVE_SERVER_TIMEOUT'
        $receipt=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $owned 'server-receipt.json') -Raw -Encoding utf8)
        Assert-NativeServerReceipt $receipt $owned $ownedProcess.identity $Life.manifestSha256
        $ownedProcess | Add-Member receipt $receipt
        return $ownedProcess
    } catch {Stop-ColdRetainedProcess $ownedProcess.process $ownedProcess.identity $owned;$ownedProcess.process.Dispose();throw}
}

# Готовность UI требует действительного native witness и той же birth/lease, не CLI exit=0.
function Connect-NativeClient($Native,[string]$Root,[string]$Client,[datetime]$Started,[int]$Timeout) {
    if ($Native.web) {
        $Native.lineTask=$Native.process.StandardOutput.ReadLineAsync()
        Wait-NativeCondition {
            if ($Native.lineTask.IsCompleted) {
                $value=$Native.lineTask.GetAwaiter().GetResult()
                if ($null -eq $value) {throw 'NATIVE_WEB_OUTPUT_EOF'}
                $Native.prefix+=$value+"`n";if ($Native.prefix.Length -gt 1048576) {throw 'NATIVE_OUTPUT_LIMIT'}
                if ($value -cmatch '^PARITY_URL (http://127\.0\.0\.1:[0-9]+/\?t=[A-Za-z0-9_-]+)$') {$Native.webUrl=$Matches[1];return $true}
                $Native.lineTask=$Native.process.StandardOutput.ReadLineAsync()
            }
            return $false
        } $Timeout 'NATIVE_WEB_URL_TIMEOUT'
        $Native.stdout=$Native.process.StandardOutput.ReadToEndAsync()
    }
    $script:nativeUi=$null
    Wait-NativeCondition {$script:nativeUi=Get-ColdUiReceipt $Root $Client $Started;return $null -ne $script:nativeUi} $Timeout 'NATIVE_UI_TIMEOUT'
    $Native.ui=$script:nativeUi
    $expected=@(Get-NativeArguments $Root $Client $script:nativeNode)
    if (-not (Test-ColdInventoryEqual @($Native.ui.args) $expected)) {throw 'NATIVE_ARGS_CHANGED'}
    $Native.uiProcess=Open-PortableProcess ([int]$Native.ui.pid)
    Assert-ColdProcessIdentity ([pscustomobject]@{ProcessId=$Native.ui.pid;StartedAtTicks=$Native.ui.startedAtTicks;ExecutablePath=$Native.ui.executablePath;OwnedRoot=$Root}) (Get-ColdProcessReceipt $Native.uiProcess $Root) $Root $Native.ui.executablePath
    if ($Native.web) {
        $url=[uri]$Native.webUrl
        if ($url.Port -ne $Native.ui.witness.port) {throw 'NATIVE_WEB_OWNER'}
        $bootstrap=Invoke-RestMethod -Uri ($url.GetLeftPart([UriPartial]::Authority)+'/api/ui/bootstrap?tab=native-lifecycle&'+$url.Query.TrimStart('?')) -TimeoutSec 5 -MaximumRedirection 0
        if ($bootstrap.client -cne 'web' -or $bootstrap.testApi -ne $true) {throw 'NATIVE_WEB_BOOTSTRAP'}
    }
}

# Физическое главное окно проверяется по HWND witness и PID, без активации или обхода модальности.
function Get-NativeMainWindow($Native) {
    if (-not ('CashPredictionNativeWindowProbe' -as [type])) {
        Add-Type -TypeDefinition @'
/** Проверяет только окно существующего процесса, не посылает сообщений интерфейсу. */
public static class CashPredictionNativeWindowProbe {
    /// <summary>Проверяет существование указанного HWND.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool IsWindow(System.IntPtr window);
    /// <summary>Проверяет видимость указанного окна.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool IsWindowVisible(System.IntPtr window);
    /// <summary>Проверяет доступность owner для обычного закрытия.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern bool IsWindowEnabled(System.IntPtr window);
    /// <summary>Возвращает PID фактического владельца HWND.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(System.IntPtr window, out uint pid);
}
'@
    }
    $handle=[IntPtr]::new([long]$Native.ui.witness.handle);$ownerPid=0U
    [void][CashPredictionNativeWindowProbe]::GetWindowThreadProcessId($handle,[ref]$ownerPid)
    $Native.uiProcess.Refresh()
    return [pscustomobject]@{handle=$handle.ToInt64();pid=[long]$ownerPid;
        exists=[CashPredictionNativeWindowProbe]::IsWindow($handle);visible=[CashPredictionNativeWindowProbe]::IsWindowVisible($handle);
        enabled=[CashPredictionNativeWindowProbe]::IsWindowEnabled($handle);processMainHandle=$Native.uiProcess.MainWindowHandle.ToInt64()}
}

# Disabled owner, чужой HWND или другой main handle не подменяются закрытием popup.
function Assert-NativeMainWindow($Window,$Ui) {
    Assert-ColdKeys $Window @('handle','pid','exists','visible','enabled','processMainHandle')
    if ($Window.handle -ne $Ui.witness.handle -or $Window.pid -ne $Ui.pid -or $Window.exists -isnot [bool] -or $Window.exists -cne $true -or
        $Window.visible -isnot [bool] -or $Window.visible -cne $true -or $Window.processMainHandle -ne $Window.handle) {throw 'NATIVE_NORMAL_CLOSE_WINDOW_IDENTITY'}
    if ($Window.enabled -isnot [bool] -or -not $Window.enabled) {throw 'NATIVE_NORMAL_CLOSE_OWNER_DISABLED'}
}

# Нормальное закрытие каждого клиента: WM_CLOSE либо штатный closeMain API, никакого Kill для PASS.
function Close-NativeNormally($Native,[string]$Root,[string]$Client,[int]$Timeout,[string]$Evidence) {
    $identity=[pscustomobject]@{ProcessId=$Native.ui.pid;StartedAtTicks=$Native.ui.startedAtTicks;ExecutablePath=$Native.ui.executablePath;OwnedRoot=$Root}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $Native.uiProcess $Root) $Root $identity.ExecutablePath
    if ($Client -ceq 'web') {
        $url=[uri]$Native.webUrl;$origin=$url.GetLeftPart([UriPartial]::Authority)
        $bootstrap=Invoke-RestMethod -Uri ($origin+'/api/ui/bootstrap?tab=native-lifecycle&'+$url.Query.TrimStart('?')) -TimeoutSec 5 -MaximumRedirection 0
        $body=ConvertTo-Json -Compress -InputObject @{tab='native-lifecycle';afterSeq=$bootstrap.seq;intent=@{type='closeMain'}}
        [void](Invoke-RestMethod -Uri ($origin+'/api/ui/intent'+$url.Query) -Method Post -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 5 -MaximumRedirection 0)
    } else {
        $window=Get-NativeMainWindow $Native
        $windowReceipt=Join-Path $Evidence ('physical-close-window-'+$Native.ui.pid+'-'+$Native.ui.startedAtTicks+'.json')
        Write-ColdJson $windowReceipt ([ordered]@{observedUtc=[datetime]::UtcNow.ToString('o');window=$window;ui=$Native.ui})
        Assert-NativeMainWindow $window $Native.ui
        if (-not $Native.uiProcess.CloseMainWindow()) {throw 'NATIVE_NORMAL_CLOSE_REJECTED'}
    }
    Wait-NativeCondition {$Native.uiProcess.HasExited -and @(Get-CopyProcesses $Root).Count -eq 0} $Timeout 'NATIVE_NORMAL_CLOSE_TIMEOUT'
    if ($Native.uiProcess.ExitCode -ne 0) {throw 'NATIVE_NORMAL_EXIT_CODE'}
    $receipt=[pscustomobject]@{pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;exitCode=$Native.uiProcess.ExitCode;
        exitedUtc=$Native.uiProcess.ExitTime.ToUniversalTime().ToString('o');remainingClients=@(Get-CopyProcesses $Root).Count;kind='ordinary-no-restart'}
    Assert-NativeExitReceipt $receipt $Native.ui $Root $Client
    return $receipt
}

# Source-file bridge вызывает только реальные domain writers из замороженного core, не запускает UI.
function Get-NativeDomainSessionSource {
    return @'
/** Готовит обычный сохранённый план до запуска клиента, используя frozen domain API. */
final class NativeDomainSession {
    /** Записывает и повторно читает план и настройки; ошибки не дают успешной квитанции. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("NATIVE_DOMAIN_ARGUMENTS");
        var root = java.nio.file.Path.of(new String(java.util.Base64.getDecoder().decode(args[0]),
                java.nio.charset.StandardCharsets.UTF_8));
        var home = root.resolve("CashMemory");
        var file = home.resolve("NativeLifecycle.md");
        var settingsFile = home.resolve("settings.md");
        if (java.nio.file.Files.exists(file) || java.nio.file.Files.exists(settingsFile))
            throw new java.io.IOException("NATIVE_DOMAIN_OUTPUT_EXISTS");
        var today = java.time.LocalDate.of(2026, 10, 4);
        var plan = ru.cashprediction.core.model.Plan.empty("NativeLifecycle", today);
        var repository = new ru.cashprediction.core.io.PlanRepository(home);
        repository.save(plan, file);
        var loaded = repository.load(file, today);
        if (!loaded.plan().equals(plan) || loaded.hasWarnings()) throw new java.io.IOException("NATIVE_DOMAIN_PLAN_ROUNDTRIP");
        var settings = ru.cashprediction.core.document.AppSettings.defaults().withPlanOpened(file.toString());
        ru.cashprediction.core.markdown.SettingsMarkdown.save(settingsFile, settings);
        if (!ru.cashprediction.core.markdown.SettingsMarkdown.load(settingsFile).equals(settings))
            throw new java.io.IOException("NATIVE_DOMAIN_SETTINGS_ROUNDTRIP");
        System.out.println("NATIVE_DOMAIN_SESSION_PREPARED");
    }
}
'@
}

# Source-file launcher получает только ASCII пути; настоящий Unicode root передаётся как UTF-8 Base64.
function Get-NativeDomainBridgeArguments([string]$Root,[string]$BridgeCore,[string]$Source) {
    [void](Assert-NativeAbsolute $Root)
    foreach ($path in @($BridgeCore,$Source)) {
        [void](Assert-NativeAbsolute $path)
        if ($path -cmatch '[^\x20-\x7e]') {throw 'NATIVE_DOMAIN_ASCII_PATH_REQUIRED'}
    }
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Root))
    return @('-XX:-UsePerfData','-cp',$BridgeCore,$Source,$encoded)
}

# Копируется ровно frozen core, без overwrite и без изменения класса/ресурсов ради seed bridge.
function Copy-NativeDomainCore([string]$Core,[string]$CoreSha,[string]$Evidence) {
    [void](Assert-NativeAbsolute $Evidence)
    if ($Evidence -cmatch '[^\x20-\x7e]') {throw 'NATIVE_DOMAIN_ASCII_PATH_REQUIRED'}
    $copy=Join-Path $Evidence 'domain-core.jar'
    [void](Assert-NativeAbsolute $copy)
    if (Test-Path -LiteralPath $copy) {throw 'NATIVE_DOMAIN_CORE_COPY_EXISTS'}
    if ($CoreSha -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $Core).Hash.ToLowerInvariant() -cne $CoreSha) {throw 'NATIVE_DOMAIN_CORE_PIN'}
    [IO.File]::Copy($Core,$copy,$false)
    if ((Get-FileHash -LiteralPath $copy).Hash.ToLowerInvariant() -cne $CoreSha -or
        (Get-FileHash -LiteralPath $Core).Hash.ToLowerInvariant() -cne $CoreSha) {throw 'NATIVE_DOMAIN_CORE_PIN'}
    return $copy
}

# Входы frozen; только новая копия получает domain-файлы до фиксации пользовательского baseline.
function Initialize-NativeDomainSession([string]$Root,[string]$Java,$Cold,[string]$Evidence) {
    $version=Get-ColdVersion $Root;$core=Join-Path (Join-Path $Root 'app') $version.jar
    $coreSha=$version.jarSha256
    foreach ($relative in 'CashMemory/NativeLifecycle.md','CashMemory/settings.md') {
        if (Test-Path -LiteralPath (Join-Path $Root $relative)) {throw 'NATIVE_DOMAIN_OUTPUT_EXISTS'}
    }
    if ((Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -cne $Cold.runtimeSha256) {throw 'NATIVE_DOMAIN_RUNTIME_PIN'}
    $bridgeCore=Copy-NativeDomainCore $core $coreSha $Evidence
    $source=Join-Path $Evidence 'NativeDomainSession.java'
    [IO.File]::WriteAllText($source,(Get-NativeDomainSessionSource),[Text.UTF8Encoding]::new($false))
    $arguments=@(Get-NativeDomainBridgeArguments $Root $bridgeCore $source)
    $bridge=Start-NativeOwned $Java $arguments $Evidence
    try {
        Wait-NativeCondition {$bridge.process.HasExited} 60 'NATIVE_DOMAIN_TIMEOUT'
        Save-NativeOutput $bridge $Evidence 'domain-session'
        if ($bridge.process.ExitCode -ne 0 -or $bridge.stdout.GetAwaiter().GetResult().Trim() -cne 'NATIVE_DOMAIN_SESSION_PREPARED') {throw 'NATIVE_DOMAIN_FAILED'}
        if ((Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant() -cne $coreSha -or
            (Get-FileHash -LiteralPath $bridgeCore).Hash.ToLowerInvariant() -cne $coreSha -or
            (Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -cne $Cold.runtimeSha256) {throw 'NATIVE_DOMAIN_INPUT_CHANGED'}
        $files=@(foreach ($relative in 'CashMemory/NativeLifecycle.md','CashMemory/settings.md') {
            $path=Join-Path $Root $relative;[void](Resolve-PortableSafetyPath $path)
            $item=Get-Item -LiteralPath $path
            if ($item.Length -le 0) {throw 'NATIVE_DOMAIN_EMPTY_OUTPUT'}
            [pscustomobject]@{path=$path;sizeBytes=$item.Length;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
        })
        $receipt=Join-Path $Evidence 'domain-session.json'
        Write-ColdJson $receipt ([ordered]@{scope='FROZEN_CORE_DOMAIN_WRITERS';process=$bridge.identity;arguments=$arguments;
            nativeRoot=$Root;workingDirectory=$Evidence;coreJar=$core;coreSha256=$coreSha;
            bridgeCoreJar=$bridgeCore;bridgeCoreSha256=(Get-FileHash -LiteralPath $bridgeCore).Hash.ToLowerInvariant();
            source=$source;sourceSha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();
            runtimeSha256=$Cold.runtimeSha256;exitCode=$bridge.process.ExitCode;files=$files})
        return $receipt
    } finally {
        if (-not $bridge.process.HasExited) {Stop-ColdRetainedProcess $bridge.process $bridge.identity $Evidence}
        $bridge.process.Dispose()
    }
}

# Отчёт exit=0 без native witness/birth и пустого census не подтверждает lifecycle.
function Assert-NativeExitReceipt($Exit,$Ui,[string]$Root,[string]$Client) {
    Assert-ColdUiReceipt $Ui $Root $Client
    Assert-ColdKeys $Exit @('pid','startedAtTicks','exitCode','exitedUtc','remainingClients','kind')
    if (-not (Test-ColdInteger $Exit.pid 1) -or $Exit.pid -ne $Ui.pid -or -not (Test-ColdInteger $Exit.startedAtTicks 1) -or $Exit.startedAtTicks -ne $Ui.startedAtTicks -or
        -not (Test-ColdInteger $Exit.exitCode) -or $Exit.exitCode -ne 0 -or -not (Test-ColdInteger $Exit.remainingClients) -or $Exit.remainingClients -ne 0 -or
        $Exit.kind -cne 'ordinary-no-restart' -or (Get-ColdUtcTicks $Exit.exitedUtc) -lt $Ui.startedAtTicks) {throw 'NATIVE_EXIT_RECEIPT'}
}

# Чужой PID и даже повторно использованный собственный PID отвергаются существующими cold guards.
function Save-NativeOutput($Native,[string]$Directory,[string]$Name) {
    if ($null -eq $Native.stdout) {
        if ($null -ne $Native.lineTask) {
            if (-not $Native.lineTask.Wait(5000)) {throw 'NATIVE_OUTPUT_TIMEOUT'}
            $pending=$Native.lineTask.GetAwaiter().GetResult();if ($null -ne $pending) {$Native.prefix+=$pending+"`n"}
        }
        $Native.stdout=$Native.process.StandardOutput.ReadToEndAsync()
    }
    foreach ($task in @($Native.stdout,$Native.stderr)) {if (-not $task.Wait(5000)) {throw 'NATIVE_OUTPUT_TIMEOUT'}}
    [IO.File]::WriteAllText((Join-Path $Directory ($Name+'.out.txt')),$Native.prefix+$Native.stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $Directory ($Name+'.err.txt')),$Native.stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
}

# Контейнер ещё в работе только пока prepare.lock действительно занят JVM; Ready не подделывается.
function Test-NativePreparationFinished([string]$Root) {
    $path=Join-Path $Root 'CashMemory/Updates/prepare.lock'
    if (-not (Test-Path -LiteralPath $path)) {return $false}
    $stream=$null;$locked=$false
    try {
        $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
        $stream.Lock(0,1);$locked=$true;return $true
    } catch [IO.IOException] {return $false}
    finally {if ($locked) {$stream.Unlock(0,1)};if ($null -ne $stream) {$stream.Dispose()}}
}

# Instant Java сохраняется в исходном журнале; для сравнения DateTime ticks отсекаются лишь наносекунды.
function Get-NativeUtcTicks([string]$Text) {
    if ($Text -cnotmatch '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$') {throw 'NATIVE_UTC'}
    return (Get-ColdUtcTicks ([regex]::Replace($Text,'(\.\d{7})\d{1,2}Z$','$1Z')))
}

# Реальные START/FINISH и итоговый stats сверяются, без дополнительных HTTP probes к fixture.
function Assert-NativeHttp($Stats,$Events) {
    Assert-ColdKeys $Stats @('schemaVersion','requests','completed','bytes','counts','active','closed','cancelled','healthy')
    if (-not (Test-ColdInteger $Stats.schemaVersion 1) -or $Stats.schemaVersion -ne 1 -or
        $Stats.closed -isnot [bool] -or $Stats.healthy -isnot [bool] -or $Stats.cancelled -isnot [bool] -or
        -not (Test-ColdInteger $Stats.active) -or -not (Test-ColdInteger $Stats.requests 1) -or
        -not (Test-ColdInteger $Stats.completed) -or -not (Test-ColdInteger $Stats.bytes) -or
        $Stats.closed -ne $true -or $Stats.healthy -ne $true -or $Stats.active -ne 0 -or $Stats.requests -lt 1 -or
        $Stats.requests -ne $Stats.completed) {throw 'NATIVE_HTTP_STATS'}
    $starts=@{};$finishes=@{};$counts=@{};$bytes=0L;$previous=-1L
    foreach ($event in @($Events)) {
        if ($event.event -ceq 'START') {
            if (-not (Test-ColdInteger $event.id 1) -or $starts.ContainsKey([long]$event.id) -or $event.nanos -lt $previous) {throw 'NATIVE_HTTP_START'}
            $previous=$event.nanos;[void](Get-NativeUtcTicks $event.utc);$starts[[long]$event.id]=$event
            if (-not $counts.ContainsKey($event.path)) {$counts[$event.path]=0L};$counts[$event.path]++
        } elseif ($event.event -ceq 'FINISH') {
            if (-not $starts.ContainsKey([long]$event.id) -or $finishes.ContainsKey([long]$event.id) -or
                $event.path -cne $starts[[long]$event.id].path -or $event.finishedNanos -lt $event.startedNanos -or
                $event.startedNanos -ne $starts[[long]$event.id].nanos -or $event.startedUtc -cne $starts[[long]$event.id].utc -or
                $event.query -cne $starts[[long]$event.id].query -or $event.method -cne $starts[[long]$event.id].method -or
                -not (Test-ColdInteger $event.bytes) -or $event.status -lt 0 -or $event.status -gt 599) {throw 'NATIVE_HTTP_FINISH'}
            [void](Get-NativeUtcTicks $event.startedUtc);[void](Get-NativeUtcTicks $event.finishedUtc)
            $finishes[[long]$event.id]=$event;$bytes+=$event.bytes
        } else {throw 'NATIVE_HTTP_EVENT'}
    }
    if ($starts.Count -ne $Stats.requests -or $finishes.Count -ne $Stats.completed -or $bytes -ne $Stats.bytes -or
        $counts.Count -ne @($Stats.counts.PSObject.Properties).Count) {throw 'NATIVE_HTTP_COUNTERS'}
    foreach ($key in $counts.Keys) {if ($Stats.counts.$key -ne $counts[$key]) {throw 'NATIVE_HTTP_COUNTERS'}}
}

# Путь stop/cancel строго из квитанции, но сверяется с own UUID до создания файла.
function Stop-NativeFixture($Server,[string]$Evidence,[string]$Name) {
    $stop=Join-Path $Server.owned 'server.stop'
    if (-not (Test-Path -LiteralPath $stop)) {$null=New-Item -ItemType File -Path $stop}
    Wait-NativeCondition {$Server.process.HasExited} 15 'NATIVE_SERVER_STOP_TIMEOUT'
    Save-NativeOutput $Server $Evidence $Name
    if ($Server.process.ExitCode -ne 0) {throw 'NATIVE_SERVER_FAILED'}
    $stats=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $Server.owned 'server-stats.json') -Raw -Encoding utf8)
    $events=@(Get-Content -LiteralPath (Join-Path $Server.owned 'server-trace.jsonl') -Encoding utf8 | ForEach-Object {ConvertFrom-ColdReceiptJson $_})
    Assert-NativeHttp $stats $events
    Write-ColdJson (Join-Path $Evidence ($Name+'-stats.json')) $stats
    Write-ColdJson (Join-Path $Evidence ($Name+'-trace.json')) $events
    return [pscustomobject]@{stats=$stats;events=$events}
}

# Exact transport ожидания отделены от доказательства native UI/ordinary exit.
function Assert-NativeScenarioHttp([string]$Scenario,$Http,[string]$Delta,[long]$DeltaSize,[long]$FullSize) {
    $finishes=@($Http.events | Where-Object {$_.event -ceq 'FINISH'})
    $metadata=@($finishes | Where-Object {$_.path -ceq '/update.json'})
    $patch=@($finishes | Where-Object {$_.path -ceq ('/'+$Delta)})
    $full=@($finishes | Where-Object {$_.path -ceq '/CashPrediction-portable.zip'})
    if ($Scenario -ceq 'corrupt-full-retain') {
        if ($metadata.Count -ne 1 -or $metadata[0].status -ne 200 -or $metadata[0].bytes -le 0 -or $patch.Count -or
            $full.Count -ne 1 -or $full[0].status -ne 200 -or $full[0].bytes -ne $FullSize -or $finishes.Count -ne 2) {throw 'NATIVE_HTTP_CORRUPT_FULL'}
    } elseif ($Scenario -in @('offline','timeout','malformed')) {
        if ($patch.Count -or $full.Count -or $metadata.Count -lt 3 -or $metadata.Count -gt 6) {throw 'NATIVE_HTTP_NEGATIVE'}
        if ($Scenario -ceq 'malformed' -and ($metadata.Count -ne 3 -or @($metadata | Where-Object {$_.status -ne 200 -or $_.bytes -ne 1}).Count)) {throw 'NATIVE_HTTP_MALFORMED'}
        if ($Scenario -ceq 'offline' -and @($metadata | Where-Object {$_.status -ne 0 -or $_.bytes -ne 0}).Count) {throw 'NATIVE_HTTP_OFFLINE'}
    } else {
        $attempts=if ($Scenario -ceq 'once-three-attempts') {3} else {1}
        if ($metadata.Count -ne $attempts -or $patch.Count -ne 1 -or $patch[0].status -ne 200 -or $patch[0].bytes -ne $DeltaSize) {throw 'NATIVE_HTTP_POSITIVE'}
        if ($Scenario -ceq 'once-three-attempts' -and (@($metadata | Where-Object {$_.status -eq 503}).Count -ne 2 -or @($metadata | Where-Object {$_.status -eq 200}).Count -ne 1)) {throw 'NATIVE_HTTP_RETRY'}
        if ($Scenario -ceq 'corrupt-delta-full') {
            if ($full.Count -ne 1 -or $full[0].status -ne 200 -or $full[0].bytes -ne $FullSize) {throw 'NATIVE_HTTP_FALLBACK'}
        } elseif ($full.Count) {throw 'NATIVE_HTTP_UNEXPECTED_FULL'}
    }
}

# Полный контейнер проверяется отдельно: меняется только список дельт, никогда identity или inventory T.
function New-NativeFullOnlyManifest($Target) {
    $manifest=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Target -Depth 64 -Compress)
    $manifest.deltaPatches=@()
    Assert-NativeFullOnlyManifest $manifest $Target
    return $manifest
}

# Производный wire-манифест не имеет права незаметно изменить ни одно другое поле frozen T.
function Assert-NativeFullOnlyManifest($Manifest,$Target) {
    Assert-ColdKeys $Manifest @('schemaVersion','releaseNumber','commitSha','version','publishedAtUtc','assetName','sizeBytes','sha256','treeSha256','files','deltaPatches')
    if ($Manifest.deltaPatches -isnot [array] -or $Manifest.deltaPatches.Count) {throw 'NATIVE_FULL_ONLY_DELTAS'}
    $expected=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Target -Depth 64 -Compress)
    $expected.deltaPatches=@()
    if (-not (Test-ColdInventoryEqual $Manifest $expected)) {throw 'NATIVE_FULL_ONLY_IDENTITY'}
}

# Подтверждение fault mode привязано к реальному серверу и закреплённому полному контейнеру.
function Assert-NativeFullServer($Receipt,$Life,$Target) {
    $fixtures=@($Receipt.fixtures)
    $zip=@($fixtures | Where-Object {$_.path -ceq '/CashPrediction-portable.zip'})
    $metadata=@($fixtures | Where-Object {$_.path -ceq '/update.json'})
    if ($Receipt.mode -cne 'CORRUPTFULL' -or $Receipt.artifactDir -cne $Life.artifactDir -or
        $Receipt.manifestSha256 -cne $Life.manifestSha256 -or $fixtures.Count -ne 2 -or $zip.Count -ne 1 -or
        $metadata.Count -ne 1 -or $metadata[0].sha256 -cne $Life.manifestSha256 -or
        $zip[0].sha256 -cne $Target.sha256 -or $zip[0].size -ne $Target.sizeBytes) {throw 'NATIVE_FULL_SERVER_FIXTURE'}
}

# Читается только исходная длина, максимум 8 MiB и 2 секунды; append/rename writer не блокируется.
function Read-NativeSharedBytes([string]$Path) {
    $path=Resolve-PortableSafetyPath $Path
    $share=[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)
    try {
        $length=$stream.Length
        if ($length -gt 8388608) {throw 'NATIVE_TRACE_LIMIT'}
        $bytes=[byte[]]::new([int]$length);$offset=0;$clock=[Diagnostics.Stopwatch]::StartNew()
        while ($offset -lt $bytes.Length) {
            if ($clock.ElapsedMilliseconds -ge 2000) {throw 'NATIVE_TRACE_READ_TIMEOUT'}
            $read=$stream.Read($bytes,$offset,[Math]::Min(65536,$bytes.Length-$offset))
            if ($read -eq 0) {throw 'NATIVE_TRACE_UNSTABLE'}
            $offset+=$read
        }
        if ($clock.ElapsedMilliseconds -ge 2000) {throw 'NATIVE_TRACE_READ_TIMEOUT'}
        if ($stream.Length -ne $length) {throw 'NATIVE_TRACE_UNSTABLE'}
        return ,$bytes
    } finally {$stream.Dispose()}
}

# JSONL обязан завершаться LF: неполный хвост не отбрасывается и не становится ложным trace.
function Read-NativeTraceSnapshot([string]$Path) {
    $bytes=Read-NativeSharedBytes $Path
    if (-not $bytes.Length -or $bytes[-1] -ne 10) {throw 'NATIVE_TRACE_INCOMPLETE'}
    try {$text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)}
    catch [Text.DecoderFallbackException] {throw 'NATIVE_TRACE_UTF8'}
    $lines=$text.Split("`n");$events=[Collections.Generic.List[object]]::new()
    if ($lines.Length -gt 4097) {throw 'NATIVE_TRACE_LIMIT'}
    for ($index=0;$index -lt $lines.Length-1;$index++) {
        $line=$lines[$index]
        if (-not $line -or $line.Length -gt 65536) {throw 'NATIVE_TRACE_RECORD'}
        try {$event=ConvertFrom-ColdReceiptJson $line}
        catch {throw 'NATIVE_TRACE_RECORD'}
        if ($event -isnot [pscustomobject] -or $null -eq $event.PSObject.Properties['event'] -or
            $event.event -cnotin @('START','FINISH')) {throw 'NATIVE_TRACE_RECORD'}
        $events.Add($event)
    }
    return [pscustomobject]@{trace=$text;traceBytes=[long]$bytes.Length;
        traceSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();events=$events.ToArray()}
}

# Snapshot содержит durable START/FINISH и неизменные counters до/после чтения, без HTTP probes.
function Read-NativeLiveHttp($Server) {
    $statsPath=Join-Path $Server.owned 'server-stats.json';$utf8=[Text.UTF8Encoding]::new($false,$true)
    $stats=ConvertFrom-ColdReceiptJson ($utf8.GetString((Read-NativeSharedBytes $statsPath)))
    $snapshot=Read-NativeTraceSnapshot (Join-Path $Server.owned 'server-trace.jsonl')
    $after=ConvertFrom-ColdReceiptJson ($utf8.GetString((Read-NativeSharedBytes $statsPath)))
    if (-not (Test-ColdInventoryEqual $stats $after)) {throw 'NATIVE_TRACE_UNSTABLE'}
    if ($stats.closed -ne $false -or $stats.healthy -ne $true -or $stats.cancelled -ne $false -or
        $stats.active -ne 0 -or $stats.requests -ne $stats.completed) {throw 'NATIVE_NONPOLLING_BUSY'}
    # Общий guard проверяет законченный trace; snapshot сохраняет исходное closed=false.
    $finished=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $stats -Depth 64 -Compress);$finished.closed=$true
    Assert-NativeHttp $finished $snapshot.events
    return [pscustomobject]@{stats=$stats;trace=$snapshot.trace;traceBytes=$snapshot.traceBytes;
        traceSha256=$snapshot.traceSha256;events=$snapshot.events}
}

# Изменение даже одного START, FINISH или counter отвергает отсутствие polling.
function Assert-NativeNonPollingSnapshot($Before,$After) {
    if ($Before.traceSha256 -cne $After.traceSha256 -or $Before.traceBytes -ne $After.traceBytes -or
        $Before.trace -cne $After.trace -or -not (Test-ColdInventoryEqual $Before.stats $After.stats)) {throw 'NATIVE_NONPOLLING_CHANGED'}
}

# Короткое или пустое окно и переставленные samples не являются доказательством наблюдения.
function Assert-NativeNonPollingReceipt($Receipt) {
    $samples=@($Receipt.samples)
    if ($Receipt.scope -cne 'LIVE_HTTP_AFTER_READY' -or $Receipt.status -cne 'PASS' -or $Receipt.windowMillis -ne 5000 -or
        -not (Test-ColdInteger $Receipt.elapsedMillis 5000) -or $samples.Count -lt 2 -or
        $samples[0].elapsedMillis -ne 0 -or $samples[-1].elapsedMillis -lt 5000 -or
        $samples[-1].elapsedMillis -gt $Receipt.elapsedMillis) {throw 'NATIVE_NONPOLLING_WINDOW'}
    $previous=-1L
    foreach ($sample in $samples) {
        if (-not (Test-ColdInteger $sample.elapsedMillis) -or $sample.elapsedMillis -le $previous) {throw 'NATIVE_NONPOLLING_SAMPLE_ORDER'}
        $previous=$sample.elapsedMillis
        Assert-NativeNonPollingSnapshot $samples[0].http $sample.http
    }
}

# Окно измеряется монотонными часами; native клиент и fixture должны жить всё время после READY.
function Observe-NativeNonPolling($Server,$Native,[string]$Root,$Base,$Target,$Delta,[string]$Evidence,[int]$Timeout) {
    $path=Join-Path $Evidence 'nonpolling-observation.json';$samples=[Collections.Generic.List[object]]::new()
    $receipt=[ordered]@{schemaVersion=1;scope='LIVE_HTTP_AFTER_READY';windowMillis=5000;status='PENDING';samples=$null;
        server=$Server.identity;client=$Native.ui;readyManifestSha256=$null}
    try {
        $script:nativeQuiet=$null
        Wait-NativeCondition {
            if ($Server.process.HasExited -or $Native.uiProcess.HasExited) {throw 'NATIVE_NONPOLLING_PROCESS_EXIT'}
            if (-not (Test-NativePreparationFinished $Root)) {return $false}
            try {$script:nativeQuiet=Read-NativeLiveHttp $Server;return $true}
            catch {if ($_.Exception.Message -cin @('NATIVE_NONPOLLING_BUSY','NATIVE_HTTP_COUNTERS','NATIVE_TRACE_UNSTABLE','NATIVE_TRACE_INCOMPLETE')) {return $false};throw}
        } $Timeout 'NATIVE_NONPOLLING_SETTLE_TIMEOUT'
        $baseline=$script:nativeQuiet
        Assert-NativeScenarioHttp 'delta' $baseline $Delta.assetName $Delta.sizeBytes $Target.sizeBytes
        $ready=Join-Path $Root 'CashMemory/Updates/Ready/update.json';$readyHash=(Get-FileHash -LiteralPath $ready).Hash
        $receipt.readyManifestSha256=$readyHash.ToLowerInvariant()
        $receipt.startedUtc=[datetime]::UtcNow.ToString('o');$clock=[Diagnostics.Stopwatch]::StartNew()
        $samples.Add([pscustomobject]@{elapsedMillis=0L;observedUtc=$receipt.startedUtc;http=$baseline})
        do {
            Start-Sleep -Milliseconds 250
            if ($Server.process.HasExited -or $Native.uiProcess.HasExited) {throw 'NATIVE_NONPOLLING_PROCESS_EXIT'}
            $snapshot=Read-NativeLiveHttp $Server
            $samples.Add([pscustomobject]@{elapsedMillis=$clock.ElapsedMilliseconds;observedUtc=[datetime]::UtcNow.ToString('o');http=$snapshot})
            Assert-NativeNonPollingSnapshot $baseline $snapshot
            if (-not (Test-Path -LiteralPath $ready) -or (Get-FileHash -LiteralPath $ready).Hash -cne $readyHash) {throw 'NATIVE_NONPOLLING_READY_CHANGED'}
        } while ($clock.ElapsedMilliseconds -lt 5000)
        $receipt.elapsedMillis=$clock.ElapsedMilliseconds;$receipt.finishedUtc=[datetime]::UtcNow.ToString('o')
        [void](Assert-ColdTree $Root $Base);[void](Assert-ColdTree (Join-Path $Root 'CashMemory/Updates/Ready/tree') $Target)
        $receipt.status='PASS'
        $receipt.samples=$samples.ToArray();Assert-NativeNonPollingReceipt ([pscustomobject]$receipt)
    } catch {$receipt.status='FAIL';$receipt.reason=$_.Exception.Message;throw}
    finally {$receipt.samples=$samples.ToArray();Write-ColdJson $path $receipt}
    return $path
}

# Отрицательный full не публикует Ready и не оставляет незавершённую загрузку/установку.
function Assert-NativeFullRetained([string]$Root,$Base) {
    [void](Assert-ColdTree $Root $Base)
    $version=Get-ColdVersion $Root
    if ($version.releaseNumber -ne $Base.releaseNumber -or $version.commitSha -cne $Base.commitSha) {throw 'NATIVE_FULL_RETAIN_VERSION'}
    foreach ($relative in 'Ready','PreviousReady','Staging','DeltaBase','payload.download','prepare-journal.json',
        'install-journal.json','completed-journal.json','last-install.json') {
        if (Test-Path -LiteralPath (Join-Path $Root ('CashMemory/Updates/'+$relative))) {throw 'NATIVE_FULL_RETAIN_RESIDUE'}
    }
    $log=Join-Path $Root 'CashMemory/Updates/update-log.md'
    if ((Test-Path -LiteralPath $log) -and @(Get-Content -LiteralPath $log | Where-Object {$_ -match ' PHASE_[A-Z_]+$'}).Count) {throw 'NATIVE_FULL_RETAIN_INSTALL_PHASE'}
}

# Пользовательское доказательство остаётся JSON object как в существующем UpdateEvidence.
function Get-NativeUserObject([string]$Root) {
    $map=[ordered]@{};foreach ($entry in @(Get-ColdUserInventory $Root)) {$map[$entry.path]=$entry};return $map
}

# Установка на ordinary exit разрешена лишь после исчезновения всех native клиентов этой копии.
function Wait-NativeInstalled([string]$Root,$Target,$Exit,[int]$Timeout) {
    Wait-NativeCondition {
        if (@(Get-CopyProcesses $Root).Count) {throw 'NATIVE_UNEXPECTED_RESTART'}
        $path=Join-Path $Root 'CashMemory/Updates/last-install.json'
        if (-not (Test-Path -LiteralPath $path)) {return $false}
        $last=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $path -Raw -Encoding utf8)
        if ($last.outcome -cne 'UPDATED' -or $last.targetCommitSha -cne $Target.commitSha) {throw 'NATIVE_INSTALL_OUTCOME'}
        return -not (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/install-journal.json')) -and
            -not (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/completed-journal.json'))
    } $Timeout 'NATIVE_INSTALL_TIMEOUT'
    $log=Get-Content -LiteralPath (Join-Path $Root 'CashMemory/Updates/update-log.md') -Encoding utf8
    $observed=@($log | Where-Object {$_ -match ' PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED)$'})
    if (@($observed | Where-Object {$_ -match ' PHASE_COMMITTED$'}).Count -ne 1) {throw 'NATIVE_COMMIT_LOG'}
    foreach ($line in $observed) {
        if ($line -cnotmatch '^- (\S+) PHASE_[A-Z_]+$' -or (Get-ColdUtcTicks $Matches[1]) -lt (Get-ColdUtcTicks $Exit.exitedUtc)) {throw 'NATIVE_INSTALL_BEFORE_EXIT'}
    }
    foreach ($directory in 'requests','processes') {
        $path=Join-Path $Root ('CashMemory/Updates/'+$directory)
        if ((Test-Path -LiteralPath $path) -and @(Get-ChildItem -LiteralPath $path -File).Count) {throw 'NATIVE_STALE_REQUEST_OR_LEASE'}
    }
    [void](Assert-ColdTree $Root $Target)
    $version=Get-ColdVersion $Root
    if ($version.releaseNumber -ne $Target.releaseNumber -or $version.commitSha -cne $Target.commitSha) {throw 'NATIVE_INSTALLED_VERSION'}
    $clock=[Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt 3) {if (@(Get-CopyProcesses $Root).Count) {throw 'NATIVE_UNEXPECTED_RESTART'};Start-Sleep -Milliseconds 100}
}

# Файлы остаются для MAIN; удаляется только собственный selftest UUID после bounded cleanup.
# Resolver выбирает единственное реальное тело, сохраняя exact9 public и private owned API.
function Get-NativeLifecycleBodyDefinition($SourceAst) {
    if ($SourceAst.Parent -is [Management.Automation.Language.FunctionDefinitionAst]) {$SourceAst=$SourceAst.Parent}
    $definitions=@($SourceAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
    $public=@($definitions | Where-Object {($_.Name -creplace '^(global:|script:)','') -ceq 'Invoke-NativeCell'})
    if ($public.Count -ne 1) {throw 'NATIVE_BODY_PUBLIC'}
    $names=@('Row','Source','Base','Target','Life','Cold','Java','Evidence','Timeout')
    if (@($public[0].Parameters).Count -ne 9 -or
        (@($public[0].Parameters | ForEach-Object {$_.Name.VariablePath.UserPath}) -join '/') -cne ($names -join '/')) {throw 'NATIVE_BODY_EXACT9'}
    $private=@($definitions | Where-Object {($_.Name -creplace '^(global:|script:)','') -ceq 'Invoke-NativeCellOwnedContext'})
    if ($public[0].Extent.Text.Contains('Start-NativeOwned')) {
        if ($private.Count) {throw 'NATIVE_BODY_DUPLICATED'}
        return $public[0]
    }
    $statements=@($public[0].Body.EndBlock.Statements)
    if ($statements.Count -ne 1 -or $statements[0].Extent.Text.Trim() -cne
        'Invoke-NativeCellOwnedContext $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout') {throw 'NATIVE_BODY_WRAPPER'}
    if (-not $private.Count) {
        $command=Get-Command Invoke-NativeCellOwnedContext -ErrorAction Stop
        $privateAst=$command.ScriptBlock.Ast
        if ($privateAst.Parent -is [Management.Automation.Language.FunctionDefinitionAst]) {$privateAst=$privateAst.Parent}
        $private=@($privateAst)
    }
    if ($private.Count -ne 1 -or @($private[0].Parameters).Count -ne 11 -or
        -not $private[0].Extent.Text.Contains('Start-NativeOwned') -or
        -not $private[0].Extent.Text.Contains('Close-NativeNormally') -or
        -not $private[0].Extent.Text.Contains('Wait-NativeInstalled')) {throw 'NATIVE_BODY_PRIVATE'}
    return $private[0]
}
function Invoke-NativeCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    Invoke-NativeCellOwnedContext $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
}

# Единственный настоящий lifecycle body; private owned путь не меняет exact9 dispatcher API.
function Invoke-NativeCellOwnedContext($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,[string]$OwnedRunRoot='',[string]$OwnedNodeNonce='') {
    $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString());[void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
    if ($OwnedRunRoot -or $OwnedNodeNonce) {
        # Предвыделенные MAIN run-root/registry nonce проверяются до копирования/процессов.
        if (-not $OwnedRunRoot -or $OwnedNodeNonce -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'AUTHORITY_NATIVE_OWNERSHIP'}
        $run=Assert-ColdOwnedRun $OwnedRunRoot ([IO.Path]::GetTempPath())
        if (Test-Path -LiteralPath $run) {throw 'AUTHORITY_NATIVE_ROOT_ALREADY_USED'}
    }
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $root=Join-Path (Join-Path $run $variants[$Row.path]) 'CashPrediction';$script:WorkDir=$run
    foreach ($protected in @($Source,$script:nativeTarget,$Life.artifactDir)) {[void](Get-ValidatedPortablePaths $protected $run $script:nativeProject $script:nativeProfile)}
    $cellEvidence=Join-Path $Evidence ([IO.Path]::GetFileName($run));$null=New-Item -ItemType Directory -Path $cellEvidence
    $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($root));Copy-Item -LiteralPath $Source -Destination $root -Recurse
    $null=New-Item -ItemType Directory -Path (Join-Path $root 'CashMemory/Updates')
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-user.txt'),"native-lifecycle-user`n")
    [IO.File]::WriteAllText((Join-Path $root 'protected-root.txt'),"native-lifecycle-root`n")
    $script:nativeNode='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    if ($OwnedRunRoot) {$script:nativeNode='ru/cashprediction/selftest/'+$OwnedNodeNonce}
    $servers=[Collections.Generic.List[object]]::new();$clients=[Collections.Generic.List[object]]::new();$events=[Collections.Generic.List[object]]::new()
    # Callback принадлежит MAIN scope/runspace; authority удерживается независимо от Row.
    $normalAuthority=$null;$normalRetainedSamples=[Collections.Generic.List[object]]::new()
    $normalBinding=Get-Variable NativeNormalBeforeNativeCollector -Scope Script -ErrorAction SilentlyContinue
    if ($null -ne $normalBinding) {
        if ($normalBinding.Value -isnot [scriptblock]) {throw 'NORMAL_COLLECTOR_KIND'}
        $normalAuthority=& $normalBinding.Value ([pscustomobject]@{scenario=$Row.scenario;base=$Row.base;client=$Row.client;
            path=$Row.path;phase=$Row.phase;source=$Source;target=$script:nativeTarget;installedRoot=$root;
            cellEvidence=$cellEvidence;registryNode=$script:nativeNode;java=$Java;baseManifest=$Base;targetManifest=$Target;cold=$Cold;life=$Life})
        if ($null -eq $normalAuthority -or -not $normalAuthority.bound) {throw 'NORMAL_COLLECTOR_UNBOUND'}
    }
    $started=[datetime]::UtcNow;$failure=$null;$cleanup=[Collections.Generic.List[string]]::new();$lastClient=$null
    $systemPowerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    try {
        [void](Assert-ColdTree $root $Base)
        $domain=Initialize-NativeDomainSession $root $Java $Cold $cellEvidence
        $Row | Add-Member domainEvidence $domain -Force
        $before=@(Assert-ColdTree $root $Base);$userBefore=Get-NativeUserObject $root;$targetBefore=@(Assert-ColdTree $script:nativeTarget $Target)
        $controlled=@(Get-ColdControlledInventory $root)
        if ($null -ne $normalAuthority) {
            Write-ColdJson (Join-Path $cellEvidence 'controlled-before.json') $controlled
            $null=Save-NormalStage $normalAuthority 'BEFORE' -1 $root $Base $Target
        }
        $delta=@($Target.deltaPatches | Where-Object {$_.baseReleaseNumber -eq $Base.releaseNumber -and $_.baseCommitSha -ceq $Base.commitSha -and $_.baseTreeSha256 -ceq $Base.treeSha256})
        if ($delta.Count -ne 1) {throw 'NATIVE_DELTA_BASE'}
        $cellLife=$Life
        if ($Row.scenario -ceq 'corrupt-full-retain') {
            $directory=Join-Path $run 'full-only-artifacts';$null=New-Item -ItemType Directory -Path $directory
            $manifest=New-NativeFullOnlyManifest $Target;$manifestPath=Join-Path $directory 'update.json'
            Write-ColdJson $manifestPath $manifest
            $zip=Join-Path $directory $Target.assetName;Copy-Item -LiteralPath (Join-Path $Life.artifactDir $Target.assetName) -Destination $zip
            if ((Get-Item -LiteralPath $zip).Length -ne $Target.sizeBytes -or (Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant() -cne $Target.sha256) {throw 'NATIVE_FULL_ONLY_PAYLOAD_PIN'}
            $cellLife=[pscustomobject]@{artifactDir=$directory;manifestSha256=(Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant();harnessClasspath=$Life.harnessClasspath}
            $fixtureEvidence=Join-Path $cellEvidence 'full-only-fixture.json'
            Write-ColdJson $fixtureEvidence ([ordered]@{scope='FULL_ONLY_CORRUPT_WIRE';sourceManifest=(Join-Path $Life.artifactDir 'update.json');
                sourceManifestSha256=$Life.manifestSha256;servedManifest=$manifestPath;servedManifestSha256=$cellLife.manifestSha256;
                manifest=$manifest;payload=$zip;payloadSha256=$Target.sha256;mode='CORRUPTFULL'})
            $Row | Add-Member fixtureEvidence $fixtureEvidence -Force
        }
        $mode=switch ($Row.scenario) {'corrupt-full-retain' {'corruptfull'} 'corrupt-delta-full' {'corruptdelta'} 'offline' {'offline-close'} 'timeout' {'delayedheaders'} 'malformed' {'malformedmanifest'} 'once-three-attempts' {'503-firsttwo'} 'cancel-next-session' {'slowchunks'} default {'valid'}}
        $delay=if ($mode -in @('delayedheaders','slowchunks')) {60000} else {0}
        $passes=if ($Row.scenario -ceq 'cancel-next-session') {2} else {1}
        for ($session=0;$session -lt $passes;$session++) {
            $server=Start-NativeFixture $cellLife $Java $mode $delay;$servers.Add($server)
            if ($null -ne $normalAuthority) {
                Write-ColdJson (Join-Path $cellEvidence ('normal-server-config-'+$session+'.json')) ([ordered]@{
                    config=$server.launchConfig;encoded=$server.launchConfigBase64;identity=$server.identity;
                    capturedUtc=[datetime]::UtcNow.ToString('o')})
            }
            if ($Row.scenario -ceq 'corrupt-full-retain') {Assert-NativeFullServer $server.receipt $cellLife $Target}
            $arguments=@(Get-NativeArguments $root $Row.client $script:nativeNode);$launchAt=[datetime]::UtcNow
            $native=Start-NativeOwned (Join-Path $root (Get-ColdLauncherName $Row.client)) $arguments $root $server.receipt.manifestUri -Web:($Row.client -ceq 'web')
            $clients.Add($native);$lastClient=$native
            Write-ColdJson (Join-Path $cellEvidence ('launch-started-'+$session+'.json')) ([ordered]@{
                launcher=$native.identity;args=$arguments;manifestUri=$server.receipt.manifestUri;startedAt=$launchAt.ToString('o')})
            Connect-NativeClient $native $root $Row.client $launchAt $Timeout
            Write-ColdJson (Join-Path $cellEvidence ('launch-'+$session+'.json')) ([ordered]@{executable=$native.identity.ExecutablePath;
                launcher=$native.identity;ui=$native.ui;args=$arguments;manifestUri=$server.receipt.manifestUri;startedAt=$launchAt.ToString('o')})
            $Row | Add-Member executed $true -Force
            $ready=Join-Path $root 'CashMemory/Updates/Ready/update.json'
            if ($Row.scenario -ceq 'cancel-next-session' -and $session -eq 0) {
                $download=Join-Path $root 'CashMemory/Updates/payload.download'
                Wait-NativeCondition {
                    $stats=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $server.owned 'server-stats.json') -Raw -Encoding utf8)
                    return $null -ne $stats.counts.PSObject.Properties['/'+$delta[0].assetName] -and
                        (Test-Path -LiteralPath $download) -and (Get-Item -LiteralPath $download).Length -gt 0 -and
                        (Get-Item -LiteralPath $download).Length -lt $delta[0].sizeBytes
                } $Timeout 'NATIVE_CANCEL_DOWNLOAD_NOT_STARTED'
                Write-ColdJson (Join-Path $cellEvidence 'cancel-download-observed.json') ([ordered]@{
                    path=$download;bytes=(Get-Item -LiteralPath $download).Length;observedUtc=[datetime]::UtcNow.ToString('o')})
            } elseif ($Row.scenario -ceq 'corrupt-full-retain') {
                Wait-NativeCondition {
                    $stats=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $server.owned 'server-stats.json') -Raw -Encoding utf8)
                    return $null -ne $stats.counts.PSObject.Properties['/CashPrediction-portable.zip'] -and
                        $stats.completed -eq $stats.requests -and $stats.active -eq 0 -and (Test-NativePreparationFinished $root)
                } $Timeout 'NATIVE_CORRUPT_FULL_PREPARATION_TIMEOUT'
                Assert-NativeFullRetained $root $Base
            } elseif ($Row.scenario -in @('offline','timeout','malformed')) {
                Wait-NativeCondition {
                    $stats=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $server.owned 'server-stats.json') -Raw -Encoding utf8)
                    return $null -ne $stats.counts.PSObject.Properties['/update.json'] -and $stats.counts.'/update.json' -ge 3 -and (Test-NativePreparationFinished $root)
                } $Timeout 'NATIVE_NEGATIVE_PREPARATION_TIMEOUT'
                if (Test-Path -LiteralPath $ready) {throw 'NATIVE_NEGATIVE_READY'}
            } else {
                Wait-NativeCondition {Test-Path -LiteralPath $ready} $Timeout 'NATIVE_READY_TIMEOUT'
                $readyManifest=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $ready -Raw -Encoding utf8)
                if ($readyManifest.commitSha -cne $Target.commitSha) {throw 'NATIVE_READY_IDENTITY'}
                [void](Assert-ColdTree (Join-Path $root 'CashMemory/Updates/Ready/tree') $Target)
                [void](Assert-ColdTree $root $Base)
                if ($Row.scenario -ceq 'delta') {
                    $observation=Observe-NativeNonPolling $server $native $root $Base $Target $delta[0] $cellEvidence $Timeout
                    $Row | Add-Member nonpollingEvidence $observation -Force
                }
            }
            if ($null -ne $normalAuthority) {
                $normalStage=if ($Row.scenario -ceq 'cancel-next-session' -and $session -eq 0) {'cancel'} elseif (Test-Path -LiteralPath $ready) {'READY'} else {'nonReady'}
                $null=Save-NormalStage $normalAuthority $normalStage $session $root $Base $Target $native
                if ($normalStage -ceq 'READY') {
                    Write-ColdJson (Join-Path $cellEvidence 'ready-old-tree.json') @(Get-ColdManagedInventory $root)
                    Write-ColdJson (Join-Path $cellEvidence 'ready-staged-tree.json') @(Get-ColdManagedInventory (Join-Path $root 'CashMemory/Updates/Ready/tree'))
                }
                Observe-NormalQuiet $normalAuthority $server $native $root $Base $Target $delta[0] $session $Timeout
            }
            $exit=Close-NativeNormally $native $root $Row.client $Timeout $cellEvidence
            Write-ColdJson (Join-Path $cellEvidence ('exit-'+$session+'.json')) $exit
            if ($Row.scenario -ceq 'cancel-next-session' -and $session -eq 0) {$null=New-Item -ItemType File -Path $server.receipt.cancelPath}
            $http=Stop-NativeFixture $server $cellEvidence ('server-'+$session)
            foreach ($event in $http.events) {if ($event.event -ceq 'FINISH') {$events.Add($event)}}
            if ($Row.scenario -ceq 'cancel-next-session' -and $session -eq 0) {
                $partial=@($http.events | Where-Object {$_.event -ceq 'FINISH' -and $_.path -ceq ('/'+$delta[0].assetName)})
                if ($partial.Count -ne 1 -or $partial[0].bytes -le 0 -or $partial[0].bytes -ge $delta[0].sizeBytes -or
                    (Test-Path -LiteralPath $ready) -or (Test-Path -LiteralPath (Join-Path $root 'CashMemory/Updates/payload.download')) -or
                    (Test-Path -LiteralPath (Join-Path $root 'CashMemory/Updates/Staging'))) {throw 'NATIVE_CANCEL_NOT_PROVED'}
                [void](Assert-ColdTree $root $Base);$mode='valid';$delay=0
                if ($null -ne $normalAuthority) {$null=Save-NormalStage $normalAuthority 'sessionBoundary' $session $root $Base $Target $native}
            } else {
                Assert-NativeScenarioHttp $Row.scenario $http $delta[0].assetName $delta[0].sizeBytes $Target.sizeBytes
                if ($Row.scenario -ceq 'corrupt-full-retain') {
                    $retainedClock=[Diagnostics.Stopwatch]::StartNew()
                    do {
                        if (@(Get-CopyProcesses $root).Count) {throw 'NATIVE_FULL_RETAIN_RESTART'}
                        Assert-NativeFullRetained $root $Base
                        if ($null -ne $normalAuthority) {
                            $normalRetainedSamples.Add([pscustomobject]@{elapsedMillis=$retainedClock.ElapsedMilliseconds;
                                observedUtc=[datetime]::UtcNow.ToString('o');clients=@(Get-CopyProcesses $root);files=@(Get-ColdManagedInventory $root)})
                        }
                        Start-Sleep -Milliseconds 250
                    } while ($retainedClock.ElapsedMilliseconds -lt 3000 -or
                        ($null -ne $normalAuthority -and $normalRetainedSamples.Count -and
                            ($retainedClock.ElapsedMilliseconds-$normalRetainedSamples[0].elapsedMillis) -lt 3001))
                    if ($null -ne $normalAuthority) {
                        $normalRetainedSamples.Add([pscustomobject]@{elapsedMillis=$retainedClock.ElapsedMilliseconds;
                            observedUtc=[datetime]::UtcNow.ToString('o');clients=@(Get-CopyProcesses $root);files=@(Get-ColdManagedInventory $root)})
                        Write-ColdJson (Join-Path $cellEvidence 'normal-retained-interval.json') $normalRetainedSamples.ToArray()
                    }
                    $retained=Join-Path $cellEvidence 'full-retained.json'
                    Write-ColdJson $retained ([ordered]@{status='PASS';observedUtc=[datetime]::UtcNow.ToString('o');version=(Get-ColdVersion $root);
                        files=@(Assert-ColdTree $root $Base);exit=$exit;scope='CORRUPT_FULL_OLD_TREE';elapsedMillis=$retainedClock.ElapsedMilliseconds})
                    $Row | Add-Member retainedEvidence $retained -Force
                } elseif ($Row.scenario -in @('offline','timeout','malformed')) {[void](Assert-ColdTree $root $Base)}
                else {Wait-NativeInstalled $root $Target $exit $Timeout}
            }
            Save-NativeOutput $native $cellEvidence ('client-'+$session)
        }
        $userAfter=Get-NativeUserObject $root
        if ((ConvertTo-Json -InputObject $userBefore -Depth 16 -Compress) -cne (ConvertTo-Json -InputObject $userAfter -Depth 16 -Compress)) {throw 'NATIVE_USER_CHANGED'}
        Assert-ColdControlledChanges $controlled @(Get-ColdControlledInventory $root) $Row.client $lastClient.ui $root ([datetime]::UtcNow.Ticks)
        if ($null -ne $normalAuthority) {Write-ColdJson (Join-Path $cellEvidence 'controlled-after.json') @(Get-ColdControlledInventory $root)}
        $phases=@('SESSION');$nativeLog=Join-Path $root 'CashMemory/Updates/update-log.md'
        if (Test-Path -LiteralPath $nativeLog) {
            Copy-Item -LiteralPath $nativeLog -Destination (Join-Path $cellEvidence 'update-log.md')
            foreach ($line in Get-Content -LiteralPath $nativeLog -Encoding utf8) {if ($line -cmatch ' PHASE_([A-Z_]+)$') {$phases+=@($Matches[1])}}
        }
        $values=@{currentBefore=$before;currentAfter=@(Get-ColdManagedInventory $root);targetBefore=$targetBefore;
            targetAfter=@(Assert-ColdTree $script:nativeTarget $Target);userBefore=$userBefore;userAfter=$userAfter;httpTrace=@($events.ToArray());phaseLog=$phases}
        foreach ($name in $values.Keys) {
            $path=Join-Path $cellEvidence ($name+'.json');Write-ColdJson $path $values[$name];$Row | Add-Member $name $path -Force
        }
        $fields=@{exe=(Join-Path $root (Get-ColdLauncherName $Row.client));args=@(Get-NativeArguments $root $Row.client $script:nativeNode);
            baseRelease=$Base.releaseNumber;baseCommit=$Base.commitSha;targetRelease=$Target.releaseNumber;targetCommit=$Target.commitSha;
            command=(Join-Path $cellEvidence 'launch-0.json');startedAt=$started.ToString('o');finishedAt=[datetime]::UtcNow.ToString('o');exitCode=0;skipped=0;failures=0}
        foreach ($name in $fields.Keys) {$Row | Add-Member $name $fields[$name] -Force}
    } catch {$failure=$_.Exception.Message}
    finally {
        $serverDirectories=@($servers | ForEach-Object {$_.owned});$Row | Add-Member serverDirectories $serverDirectories -Force
        $serverIndex=0
        foreach ($server in $servers) {
            try {if (-not $server.process.HasExited) {[void](Stop-NativeFixture $server $cellEvidence ('cleanup-server-'+$serverIndex))}} catch {$cleanup.Add($_.Exception.Message)}
            try {if (-not $server.process.HasExited) {Stop-ColdRetainedProcess $server.process $server.identity $server.owned}} catch {$cleanup.Add($_.Exception.Message)}
            foreach ($file in 'server-receipt.json','server-stats.json','server-trace.jsonl') {
                try {
                    $raw=Join-Path $server.owned $file
                    if (Test-Path -LiteralPath $raw) {Copy-Item -LiteralPath (Resolve-PortableSafetyPath $raw) -Destination (Join-Path $cellEvidence ('raw-server-'+$serverIndex+'-'+$file))}
                } catch {$cleanup.Add($_.Exception.Message)}
            }
            $serverIndex++
            if ($null -ne $normalAuthority) {
                try {Save-NormalRetainedExit $normalAuthority 'server' ($serverIndex-1) $server.process $server.identity $server.owned} catch {$cleanup.Add($_.Exception.Message)}
            }
            $server.process.Dispose()
        }
        try {Stop-ColdRecoveryHelpers $root $systemPowerShell $started} catch {$cleanup.Add($_.Exception.Message)}
        try {Stop-ColdCopyProcesses $root} catch {$cleanup.Add($_.Exception.Message)}
        $clientIndex=0
        foreach ($native in $clients) {
            try {if ($native.process.HasExited) {Save-NativeOutput $native $cellEvidence ('cleanup-client-'+$clientIndex)}} catch {$cleanup.Add($_.Exception.Message)}
            if ($null -ne $normalAuthority) {
                try {
                    Save-NormalRetainedExit $normalAuthority 'launcher' $clientIndex $native.process $native.identity $root
                    if ($null -ne $native.uiProcess -and $null -ne $native.ui) {
                        Save-NormalRetainedExit $normalAuthority 'ui' $clientIndex $native.uiProcess ([pscustomobject]@{
                            ProcessId=$native.ui.pid;StartedAtTicks=$native.ui.startedAtTicks;ExecutablePath=$native.ui.executablePath}) $root
                    } else {$normalAuthority.pending.Add('ACTUAL_UI:cleanup')}
                } catch {$cleanup.Add($_.Exception.Message)}
            }
            if ($null -ne $native.uiProcess) {$native.uiProcess.Dispose()};$native.process.Dispose();$clientIndex++
        }
        if (-not $cleanup.Count) {
            try {$nodePath=Get-PortableRegistryPath $script:nativeNode;if (Test-Path -LiteralPath $nodePath) {
                [void](Get-PortableRegistryPath $script:nativeNode);Remove-Item -LiteralPath $nodePath -Recurse -Force
            }} catch {$cleanup.Add($_.Exception.Message)}
        }
        $Row.status=if ($failure -or $cleanup.Count) {'FAIL'} else {'PASS'}
        $Row.reason=if ($failure) {$failure} elseif ($cleanup.Count) {$cleanup -join '; '} else {'NATIVE_LIFECYCLE_EXECUTED'}
        Write-ColdJson (Join-Path $cellEvidence 'cell.json') $Row
        # finishedAt относится к assertions до cleanup; индекс запечатывается только после cell.json.
        if ($null -ne $normalAuthority) {Complete-NormalCollector $normalAuthority $root $started $cleanup.ToArray()}
    }
    if ($failure -or $cleanup.Count) {throw $Row.reason}
}

# Перед первым native запуском проверяются source version, frozen pins и независимое verify.
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'NATIVE_WINDOWS_POWERSHELL7_REQUIRED'}
Import-NativeDependencies
$script:nativeProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'));$script:nativeProfile=[Environment]::GetFolderPath('UserProfile')
$bases=@($PortableDir | ForEach-Object {Assert-NativeAbsolute $_});$script:nativeTarget=Assert-NativeAbsolute $TargetPortableDir;$java=Assert-NativeAbsolute $Runtime
if ($bases.Count -ne 2 -or $bases[0] -ceq $bases[1] -or @($Scenario | Sort-Object -Unique).Count -ne $Scenario.Count) {throw 'NATIVE_TWO_BASES_AND_UNIQUE_SCENARIOS'}
$cold=Read-ColdPinnedJson $CommandFile $CommandFileSha256;Assert-ColdCommand $cold $java $bases $script:nativeTarget
$life=Read-ColdPinnedJson $LifecycleFile $LifecycleFileSha256;Assert-NativeLifecycleConfig $life
$target=Read-ColdPinnedJson $cold.targetManifest $cold.targetManifestSha256
$frozen=Read-ColdPinnedJson (Join-Path $life.artifactDir 'update.json') $life.manifestSha256
if ($life.manifestSha256 -cne $cold.targetManifestSha256 -or -not (Test-ColdInventoryEqual $target $frozen)) {throw 'NATIVE_FROZEN_TARGET_PIN'}
$sources=@($bases)+@($script:nativeTarget);$baseManifests=@{}
foreach ($source in $sources) {
    Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $source -Force);Assert-ColdNativeImage $source;Assert-NativeSeam $source
    foreach ($other in $sources) {if ($source -cne $other -and (Test-PortablePathContains $source $other)) {throw 'NATIVE_SOURCE_OVERLAP'}}
    $manifest=if ($source -ceq $script:nativeTarget) {$target} else {
        $entry=@($cold.baseManifests | Where-Object {$_.portableDir -ceq $source})[0];Read-ColdPinnedJson $entry.manifest $entry.sha256
    }
    Assert-ColdImageInventory $manifest.files $manifest.treeSha256;[void](Assert-ColdTree $source $manifest)
    $version=Get-ColdVersion $source
    if ($version.releaseNumber -ne $manifest.releaseNumber -or $version.commitSha -cne $manifest.commitSha) {throw 'NATIVE_VERSION_PIN'}
    if ($source -cne $script:nativeTarget) {
        if ($manifest.releaseNumber -ge $target.releaseNumber) {throw 'NATIVE_RELEASE_ORDER'};$baseManifests[$source]=$manifest
    }
}
if ($baseManifests[$bases[0]].releaseNumber -eq $baseManifests[$bases[1]].releaseNumber -or $target.deltaPatches.Count -ne 2) {throw 'NATIVE_DIRECT_BASES'}
Assert-PortableTreeHasNoLinks $life.artifactDir
foreach ($asset in @([pscustomobject]@{assetName=$target.assetName;sizeBytes=$target.sizeBytes;sha256=$target.sha256})+@($target.deltaPatches)) {
    if ($asset.assetName -cnotmatch '^CashPrediction(?:-portable\.zip|\.from-[1-9][0-9]*\.cpdelta|\.cpdelta)$') {throw 'NATIVE_ASSET_PATH'}
    $file=Get-Item -LiteralPath (Join-Path $life.artifactDir $asset.assetName)
    if ($file.Length -ne $asset.sizeBytes -or (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() -cne $asset.sha256) {throw 'NATIVE_ASSET_PIN'}
}
Initialize-NativeLifecycleDispatch $PSScriptRoot
# Opt-in context существует до helper запуска. Независимый Expected не выводится из Row.
$script:nativeAcceptanceContext=[pscustomobject]@{enabled=[bool]$CollectAcceptance;payloadExpectedByCell=@{};
    normalIntentByCell=@{};payloadAuthorityByCell=@{}}
if ([bool]$AcceptanceContextFile -ne [bool]$AcceptanceContextSha256) {throw 'ACCEPTANCE_CONTEXT_PIN_PAIR'}
if ($AcceptanceContextFile) {
    $authority=Read-ColdPinnedJson $AcceptanceContextFile $AcceptanceContextSha256
    Assert-ColdKeys $authority @('schemaVersion','payloadExpectedByCell')
    if ($authority.schemaVersion -ne 1) {throw 'ACCEPTANCE_CONTEXT_SCHEMA'}
    foreach ($entry in @($authority.payloadExpectedByCell)) {
        Assert-ColdKeys $entry @('key','expected')
        if ($script:nativeAcceptanceContext.payloadExpectedByCell.ContainsKey($entry.key)) {throw 'ACCEPTANCE_CONTEXT_DUPLICATE_KEY'}
        $script:nativeAcceptanceContext.payloadExpectedByCell.Add($entry.key,$entry.expected)
    }
    # Дополнительные opt-in intents закреплены MAIN context SHA, не создаются из helper Row.
    foreach ($name in 'normalIntentByCell','payloadAuthorityByCell') {
        if ($null -eq $authority.PSObject.Properties[$name]) {continue}
        foreach ($entry in @($authority.$name)) {
            Assert-ColdKeys $entry @('key','file','sha256','evidenceKind')
            if ($script:nativeAcceptanceContext.$name.ContainsKey($entry.key)) {throw 'NATIVE_PINNED_DUPLICATE_KEY'}
            $script:nativeAcceptanceContext.$name.Add($entry.key,$entry)
        }
    }
}
$rows=@(Get-NativeEvidencePlan (Join-Path $script:nativeProject 'ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
foreach ($name in 'normalIntentByCell','payloadAuthorityByCell') {
    $route=if ($name -ceq 'normalIntentByCell') {'normal'} else {'payload'}
    foreach ($key in $script:nativeAcceptanceContext.$name.Keys) {
        $matches=@($rows | Where-Object {($_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase) -ceq $key -and
            (Get-NativeDispatchRoute $_.scenario) -ceq $route})
        if ($matches.Count -ne 1) {throw 'NATIVE_PINNED_UNKNOWN_CELL'}
        $null=Get-NativeLifecyclePinnedIntent $script:nativeAcceptanceContext $matches[0] $route
    }
}
# Authority keys не могут добавлять сценарий/phase за пределами canonical payload rows.
foreach ($key in $script:nativeAcceptanceContext.payloadExpectedByCell.Keys) {
    $matches=@($rows | Where-Object {($_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase) -ceq $key -and
        (Get-NativeDispatchRoute $_.scenario) -ceq 'payload'})
    if ($matches.Count -ne 1) {throw 'ACCEPTANCE_CONTEXT_UNKNOWN_CELL'}
}
# Диагностический exact selector не импортирует прежние PASS и не меняет полный план.
$selected=@(Get-NativeLifecycleSelectedRows $rows $Scenario $CellKey $MaxCells $StepTimeoutSeconds)
$evidence=Join-Path ([IO.Path]::GetTempPath()) ('cp-native-lifecycle-evidence-'+[guid]::NewGuid().ToString());$null=New-Item -ItemType Directory -Path $evidence
$registryBefore=Get-PortableRealRegistrySnapshot;$fatal=$null
try {
    foreach ($source in $sources) {
        $entry=if ($source -ceq $script:nativeTarget) {$cold.targetManifest} else {@($cold.baseManifests | Where-Object {$_.portableDir -ceq $source})[0].manifest}
        [void](Invoke-ColdTool $cold $java @('verify','--root',$source,'--manifest',$entry) $evidence $source)
    }
    Write-ColdJson (Join-Path $evidence 'results.json') ([ordered]@{schemaVersion=1;status='PENDING';cells=$rows})
    foreach ($row in $selected) {
        $source=$bases[$(if ($row.base -ceq 'B1') {0} else {1})]
        try {
            $outputs=@(Invoke-NativeLifecycleDispatchedCell $row $source $baseManifests[$source] $target $life $cold $java $evidence $StepTimeoutSeconds)
            foreach ($output in $outputs) {
                if ($null -ne $output.PSObject.Properties['acceptanceVerdict']) {
                    # Это receipt, не sealed cell proof: assembler требует отдельный MAIN proof.
                    $path=Join-Path $evidence ('acceptance-'+[array]::IndexOf($rows,$row)+'.json')
                    if (Test-Path -LiteralPath $path) {throw 'ACCEPTANCE_OUTPUT_EXISTS'}
                    Write-ColdJson $path $output
                }
                $output
            }
        }
        finally {Write-ColdJson (Join-Path $evidence 'results.json') ([ordered]@{schemaVersion=1;status='PENDING';cells=$rows})}
        Write-Host ('Native lifecycle cell observed: '+$row.scenario+'/'+$row.base+'/'+$row.client+'/'+$row.path+'/'+$row.phase+'; status='+$row.status)
    }
} catch {$fatal=$_.Exception.Message}
finally {
    if ((Get-PortableRealRegistrySnapshot) -cne $registryBefore) {$fatal='NATIVE_REAL_REGISTRY_CHANGED'}
    Write-ColdJson (Join-Path $evidence 'results.json') ([ordered]@{schemaVersion=1;status=$(if ($fatal) {'FAIL'} else {'PENDING'});cells=$rows})
    Write-Host ('Native lifecycle evidence: '+$evidence+'; full S7 signoff remains PENDING.')
}
if ($fatal) {throw $fatal}
if ($Signoff) {
    # CLI не запускается по selected count или частичному/helper-only PASS.
    if ($rows.Count -ne 612 -or @($rows | Where-Object {$_.status -cne 'PASS' -or
        $null -eq $_.PSObject.Properties['executed'] -or $_.executed -isnot [bool] -or -not $_.executed}).Count) {
        throw 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'
    }
    if (-not $SignoffRequestFile -or -not $SignoffRequestSha256) {throw 'NATIVE_SIGNOFF_FROZEN_REQUEST_REQUIRED'}
    $request=Read-ColdPinnedJson $SignoffRequestFile $SignoffRequestSha256
    if ($request.context.coldPin.path -cne $CommandFile -or $request.context.coldPin.sha256 -cne $CommandFileSha256 -or
        $request.context.lifePin.path -cne $LifecycleFile -or $request.context.lifePin.sha256 -cne $LifecycleFileSha256) {throw 'NATIVE_SIGNOFF_CONTEXT_MISMATCH'}
    $resultsPath=Join-Path $evidence 'results.json'
    $resultsSha=(Get-FileHash -LiteralPath $resultsPath).Hash.ToLowerInvariant()
    $bound=$false
    foreach ($pin in @($request.batches)) {
        $seal=Read-ColdPinnedJson $pin.path $pin.sha256
        if ($seal.results.path -ceq $resultsPath -and $seal.results.sha256 -ceq $resultsSha) {$bound=$true}
    }
    if (-not $bound) {throw 'NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED'}
    # Existing assembler проверит все source/proof pins и только затем вызовет реальную JDK CLI.
    Invoke-NativeUpdateEvidenceSignoff $SignoffRequestFile $SignoffRequestSha256 -Signoff
}
