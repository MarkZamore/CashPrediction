<#
.SYNOPSIS
Отдельный NEXT helper для настоящих locked/readonly/disk-full rollback клеток.
.DESCRIPTION
Только определения. MAIN подключает после завершения frozen очереди, не в LIVE runner.
Сигнатура совпадает с Invoke-NativeCell. После очереди caller получает отдельный модуль через
Import-NativeRollbackDependencies и задаёт Set-NativeRollbackContext в его scope. Importer
читает актуальный AST closure, не выполняет тела runner и не заменяет функции caller.
Все записи только в новом Temp/run-UUID, файлы сохраняются.
Evidence обозначает каталог caller, но здесь не изменяется: actual evidenceRoot возвращается
в Row. Production helper не изменяется даже в копии: отдельный instrumented helper запускается
после настоящего ordinary exit и подавления automatic helper собственным helper.lock.
Readonly fixture изменяет только атрибут exe собственной копии до baseline/startup; исходный
frozen manifest остаётся неизменным. Disk-full означает simulated-disk-full-IO-boundary,
одноразовый IOException 0x80070070 перед настоящим Flush(true), не физически заполненный диск.
Mock fixtures не запускают Invoke-NativeRollbackScenario и не могут выдавать native PASS.
.NOTES
Интеграция после очереди, без Import-Module/global overwrite:
$adapter=Import-NativeRollbackDependencies $ScriptsRoot
& $adapter {param($T,$P,$H) Set-NativeRollbackContext $T $P $H} $TargetRoot $ProjectRoot $ProfileRoot
& $adapter {param($R,$S,$B,$T,$L,$C,$J,$E,$N) Invoke-NativeRollbackScenario $R $S $B $T $L $C $J $E $N} $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
Module.PrivateData содержит source hashes и closure; штатный Windows NetTCPIP является prerequisite.
При unresolved startup identity handle остаётся в script:nativeRollbackStarted этого модуля;
cleanup receipt фиксирует ошибку, следующая клетка запрещена до разрешения личности. Никакого Kill по одному PID.
#>

# Строит замыкание по фактическим literal вызовам; ambient функции не заменяют missing dependency.
function Get-NativeRollbackDependencyPlan([string]$ScriptsRoot=$PSScriptRoot,
    [string[]]$Roots=@('Invoke-NativeRollbackScenario','Set-NativeRollbackContext')) {
    $index=@{};$sources=[Collections.Generic.List[object]]::new()
    foreach ($file in 'NativeUpdateRollbackScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
        $path=[IO.Path]::GetFullPath((Join-Path $ScriptsRoot $file))
        $bytes=[IO.File]::ReadAllBytes($path);$text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
        if ($text.Length -and $text[0] -eq [char]0xfeff) {$text=$text.Substring(1)}
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'ROLLBACK_DEPENDENCY_PARSE'}
        $sources.Add([pscustomobject]@{path=$path;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()})
        foreach ($node in $ast.EndBlock.Statements) {
            if ($node -isnot [Management.Automation.Language.FunctionDefinitionAst]) {continue}
            if ($index.ContainsKey($node.Name)) {throw 'ROLLBACK_DEPENDENCY_DUPLICATE'}
            $index[$node.Name]=[pscustomobject]@{name=$node.Name;source=$path;ast=$node;text=$node.Extent.Text}
        }
    }
    $wanted=[Collections.Generic.Queue[string]]::new();foreach ($name in $Roots) {$wanted.Enqueue($name)}
    $selected=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    while ($wanted.Count) {
        $name=$wanted.Dequeue();if ($selected.ContainsKey($name)) {continue}
        if (-not $index.ContainsKey($name)) {throw ('ROLLBACK_DEPENDENCY_MISSING '+$name)}
        $entry=$index[$name];$selected.Add($name,$entry)
        foreach ($call in $entry.ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
            $called=$call.GetCommandName();if (-not $called) {continue}
            if ($index.ContainsKey($called)) {$wanted.Enqueue($called)}
            elseif (-not (Get-Command -Name $called -CommandType Cmdlet -ErrorAction SilentlyContinue)) {
                # Штатный Windows CDXML provider - единственная внешняя function dependency, не ambient mock.
                $provider=if ($called -ceq 'Get-NetTCPConnection') {Get-Command -Name $called -CommandType Function -ErrorAction SilentlyContinue} else {$null}
                $providerRoot=if ($env:SystemRoot) {Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules/NetTCPIP'} else {''}
                if ($null -eq $provider -or $provider.ModuleName -cne 'NetTCPIP' -or
                    -not $provider.Module.ModuleBase.Equals($providerRoot,[StringComparison]::OrdinalIgnoreCase)) {throw ('ROLLBACK_DEPENDENCY_MISSING '+$called)}
            }
        }
    }
    # Shared startup получает own retention wrapper; все его функции также входят в проверенный closure.
    if ($selected.ContainsKey('Start-NativeOwned') -and -not $selected.ContainsKey('Start-NativeRollbackOwned')) {throw 'ROLLBACK_STARTUP_WRAPPER_CLOSURE'}
    return [pscustomobject]@{sources=@($sources.ToArray());functions=@($selected.Values);scriptsRoot=[IO.Path]::GetFullPath($ScriptsRoot)}
}

# Изолированный модуль импортирует только определения; никакого global overwrite старого runner.
function Import-NativeRollbackDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    $plan=Get-NativeRollbackDependencyPlan $ScriptsRoot
    $module=New-Module -Name ('NativeRollback_'+[guid]::NewGuid().ToString('N')) -ArgumentList $plan -ScriptBlock {
        param($Plan)
        foreach ($entry in $Plan.functions) {
            $text=$entry.text.Replace('$PSScriptRoot',("'"+$Plan.scriptsRoot.Replace("'","''")+"'"))
            if ($entry.name -ceq 'Start-NativeOwned') {
                # Копия API сохраняет actual launch, но регистрирует handle до потенциального identity failure.
                $text=@'
function Start-NativeOwned([string]$Executable,[string[]]$Arguments,[string]$Root,[string]$Endpoint='',[switch]$Web) {
    Start-NativeRollbackOwned $Executable $Arguments $Root $Endpoint -Web:$Web
}
'@
            }
            . ([scriptblock]::Create($text))
        }
        $script:nativeRollbackStarted=[Collections.Generic.List[object]]::new()
        $script:nativeRollbackAdapterReady=$true
        Export-ModuleMember -Function Set-NativeRollbackContext,Invoke-NativeRollbackScenario
    }
    $module.PrivateData=[pscustomobject]@{sources=$plan.sources;functions=@($plan.functions.name);
        scope='AST_DEFINITIONS_ONLY_ISOLATED_MODULE';nativeExecuted=$false;startupWrapper='Start-NativeRollbackOwned'}
    return $module
}

# Context задаётся только в adapter module, без изменения script переменных MAIN.
function Set-NativeRollbackContext([string]$TargetRoot,[string]$ProjectRoot,[string]$ProfileRoot) {
    $script:nativeTarget=Assert-NativeAbsolute $TargetRoot
    $script:nativeProject=Assert-NativeAbsolute $ProjectRoot
    $script:nativeProfile=Assert-NativeAbsolute $ProfileRoot
}

# Handle публикуется caller до identity lookup; отказ не превращается в потерянный процесс или PASS.
function Register-NativeRollbackProcess($Entry,$Retained,[scriptblock]$ReadIdentity) {
    $Retained.Add($Entry)
    $identity=& $ReadIdentity $Entry.process $Entry.root
    if ($identity.ExecutablePath -cne $Entry.expectedExecutable -or $identity.OwnedRoot -cne $Entry.root) {throw 'ROLLBACK_START_IDENTITY'}
    $Entry.identity=$identity
    # Успешный return передаёт ownership обычному caller cleanup; список хранит только потерянные startup failures.
    [void]$Retained.Remove($Entry)
    return $Entry
}

# Actual startup с той же seam/argv политикой; даже ошибка identity оставляет удерживаемый handle.
function Start-NativeRollbackOwned([string]$Executable,[string[]]$Arguments,[string]$Root,[string]$Endpoint='',[switch]$Web) {
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
    if (-not $process.Start()) {$process.Dispose();throw 'ROLLBACK_START'}
    $entry=[pscustomobject]@{process=$process;identity=$null;root=$Root;expectedExecutable=$Executable;
        stderr=$process.StandardError.ReadToEndAsync();stdout=$(if ($Web) {$null} else {$process.StandardOutput.ReadToEndAsync()});
        web=[bool]$Web;webUrl=$null;prefix='';uiProcess=$null;ui=$null;lineTask=$null}
    return (Register-NativeRollbackProcess $entry $script:nativeRollbackStarted {param($Process,$Root) Get-ColdProcessReceipt $Process $Root})
}

# Неподтверждённая личность запрещает Kill; retry читает тот же удерживаемый handle, не чужой PID.
function Stop-NativeRollbackTracked($Entry) {
    if ($Entry.process.HasExited) {return}
    $actual=Get-ColdProcessReceipt $Entry.process $Entry.root
    if ($actual.ExecutablePath -cne $Entry.expectedExecutable -or $actual.OwnedRoot -cne $Entry.root) {throw 'ROLLBACK_CLEANUP_IDENTITY'}
    if ($null -eq $Entry.identity) {$Entry.identity=$actual}
    Assert-ColdProcessIdentity $Entry.identity $actual $Entry.root $Entry.expectedExecutable
    Stop-ColdRetainedProcess $Entry.process $Entry.identity $Entry.root
}

# Проверяет закрытую область до любых файловых изменений и запуска процессов.
function Assert-NativeRollbackRow($Row,[int]$Timeout) {
    if ($Row.scenario -cnotin @('locked-rollback','readonly-rollback','disk-full-rollback') -or
        $Row.base -cnotin @('B1','B2') -or $Row.client -cnotin @('fx','swing','web') -or
        $Row.path -cnotin @('ascii','cyrillic','unicode') -or $Row.phase -cne 'SESSION' -or $Row.status -cne 'PENDING' -or
        $Timeout -lt 30 -or $Timeout -gt 300) {throw 'ROLLBACK_ROW'}
}

# Каждый изменяемый путь проверяется на строгое вложение в собственный Temp UUID и отсутствие ссылок.
function Assert-NativeRollbackOwned([string]$Run,[string]$Path) {
    $owned=Assert-ColdOwnedRun $Run ([IO.Path]::GetTempPath().TrimEnd('\','/'))
    $resolved=Resolve-PortableSafetyPath $Path
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path -cne [IO.Path]::GetFullPath($Path).TrimEnd('\','/') -or
        $resolved -ceq $owned -or -not (Test-PortablePathContains $owned $resolved)) {throw 'ROLLBACK_OWNED_PATH'}
    return $resolved
}

# Readonly baseline отражает реальный атрибут только локального exe, не изменяя frozen manifest.
function Get-NativeRollbackBaseline($Base,[string]$Scenario) {
    $copy=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Base -Depth 64)
    if ($Scenario -ceq 'readonly-rollback') {
        $entries=@($copy.files | Where-Object {$_.path -ceq 'CashPrediction.exe'})
        if ($entries.Count -ne 1) {throw 'ROLLBACK_READONLY_EXE'}
        $entries[0].readOnly=$true
        $copy.treeSha256=Get-ColdTreeHash @($copy.files)
    }
    return $copy
}

# Runtime lock не блокирует чтение/запись, но запрещает Delete-share, необходимый Move/Replace.
function Get-NativeRollbackFaultPath($Base,[string]$Scenario) {
    if ($Scenario -cin @('readonly-rollback','disk-full-rollback')) {return 'CashPrediction.exe'}
    $files=@($Base.files | Where-Object {$_.path.StartsWith('runtime/') -and -not $_.readOnly})
    if (-not $files.Count) {throw 'ROLLBACK_PLAIN_RUNTIME_REQUIRED'}
    if (@($files | Where-Object {$_.path -ceq 'runtime/bin/jli.dll'}).Count) {return 'runtime/bin/jli.dll'}
    return [string]$files[0].path
}

# Открывает только managed file своей копии; дескриптор хранится до фактически наблюдённого отказа.
function Open-NativeRollbackFileLock([string]$Run,[string]$Root,[string]$Relative) {
    if ($Relative -cnotmatch '^(CashPrediction\.exe|runtime/[^:]+)$' -or
        @($Relative.Split('/') | Where-Object {$_ -cin @('','..','.') -or $_.Contains('\')}).Count) {throw 'ROLLBACK_LOCK_PATH'}
    $path=Assert-NativeRollbackOwned $Run (Join-Path $Root $Relative)
    return [IO.FileStream]::new($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
}

# Сериализует запуск automatic helper: диапазон совпадает с production OpenLock(helper.lock,1).
function Open-NativeRollbackHelperBarrier([string]$Run,[string]$Root) {
    $path=Assert-NativeRollbackOwned $Run (Join-Path $Root 'CashMemory/Updates/helper.lock')
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
    try {$stream.Lock(0,1);return $stream} catch {$stream.Dispose();throw}
}

# Только отдельный helper получает точечные catch/hook у реальных Move/Replace/Flush выражений.
function New-NativeRollbackHelperText([string]$Original,[string]$Scenario,[string]$FaultPath,[int]$Timeout) {
    if ($Scenario -cnotin @('locked-rollback','readonly-rollback','disk-full-rollback') -or
        $FaultPath -cnotmatch '^(CashPrediction\.exe|runtime/[A-Za-z0-9_./-]+)$' -or
        $FaultPath.Contains('..') -or $Timeout -lt 30 -or $Timeout -gt 300) {throw 'ROLLBACK_INJECTION_INPUT'}
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($Original,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ROLLBACK_HELPER_PARSE'}
    $definitions=@{}
    foreach ($name in 'Guard','MoveJournal','FileOperation','PortableRollback','AtomicJson') {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw 'ROLLBACK_HELPER_CONTRACT'}
        $definitions[$name]=$nodes[0]
    }
    $edits=[Collections.Generic.List[object]]::new()
    $locks=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and
        $n.GetCommandName() -ceq 'OpenLock' -and $n.Extent.Text.Contains("'helper.lock'")},$true))
    if ($locks.Count -ne 1 -or $locks[0].Extent.Text -cne "OpenLock (Join-Path `$updates 'helper.lock') 1") {throw 'ROLLBACK_HELPER_LOCK_CONTRACT'}
    $edits.Add([pscustomobject]@{offset=$locks[0].Extent.StartOffset;length=$locks[0].Extent.Text.Length;
        text="OpenLock (Join-Path `$updates 'native-rollback-helper.lock') 1"})
    foreach ($pair in @(@('MoveJournal','Move','File.Move'),@('FileOperation','Replace','File.Replace'))) {
        $nodes=@($definitions[$pair[0]].FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and
            $n.Static -and $n.Expression.Extent.Text -ceq '[IO.File]' -and $n.Member.Value -ceq $pair[1]},$true))
        if ($nodes.Count -ne 1) {throw 'ROLLBACK_HELPER_IO_CONTRACT'}
        $node=$nodes[0];$boundary=$pair[2]
        $text="try { "+$node.Extent.Text+" } catch { NativeRollbackCapture `$_ '$boundary'; throw }"
        $edits.Add([pscustomobject]@{offset=$node.Extent.StartOffset;length=$node.Extent.Text.Length;text=$text})
    }
    $flush=@($definitions.FileOperation.FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and
        -not $n.Static -and $n.Expression.Extent.Text -ceq '$output' -and $n.Member.Value -ceq 'Flush'},$true))
    if ($flush.Count -ne 1 -or $flush[0].Extent.Text -cne '$output.Flush($true)') {throw 'ROLLBACK_HELPER_FLUSH_CONTRACT'}
    $flushText=@'
try {
    if ($script:nativeRollbackScenario -ceq 'disk-full-rollback' -and -not $script:nativeRollbackCaptured -and
        $script:journal.phase -ceq 'INSTALLING' -and $kind -ceq 'REPLACE' -and $path -ceq $script:nativeRollbackPath) {
        # Одноразовая симуляция только этой фактической durable I/O границы собственной копии helper.
        throw [IO.IOException]::new('simulated-disk-full-IO-boundary',-2147024784)
    }
    $output.Flush($true)
} catch { NativeRollbackCapture $_ 'FileOperation.Flush(true)'; throw }
'@
    $edits.Add([pscustomobject]@{offset=$flush[0].Extent.StartOffset;length=$flush[0].Extent.Text.Length;text=$flushText})
    $hook=@'
$script:nativeRollbackCaptured=$false
$script:nativeRollbackScenario='__SCENARIO__'
$script:nativeRollbackPath='__PATH__'
function NativeRollbackCapture($record,[string]$boundary) {
    if ($script:nativeRollbackCaptured -or $null -eq $script:journal -or
        -not @($script:journal.operations).Count) {return}
    $operation=@($script:journal.operations)[-1]
    $wanted=if ($script:nativeRollbackScenario -ceq 'locked-rollback') {'BACKUP'} else {'REPLACE'}
    $failureException=$record.Exception.GetBaseException()
    if ($operation.path -cne $script:nativeRollbackPath -or $operation.kind -cne $wanted -or
        $operation.state -cne 'BEFORE' -or $failureException -isnot [IO.IOException]) {return}
    $script:nativeRollbackCaptured=$true
    $process=[Diagnostics.Process]::GetCurrentProcess()
    try {
        $receipt=[ordered]@{schemaVersion=1;installationRoot=$root;transactionId=$script:journal.transactionId;
            scenario=$script:nativeRollbackScenario;boundary=$boundary;phase=$script:journal.phase;operation=$operation;
            hresult=$failureException.HResult.ToString('X8');exceptionType=$failureException.GetType().FullName;message=$failureException.Message;
            pid=[long]$PID;startedAtTicks=$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.MainModule.FileName;
            observedUtc=[DateTime]::UtcNow.ToString('o');operations=@($script:journal.operations);
            scope=$(if ($script:nativeRollbackScenario -ceq 'disk-full-rollback') {'simulated-disk-full-IO-boundary'} else {'actual-managed-file-sharing-failure'})}
        AtomicJson (Join-Path $updates 'native-rollback-fault.json') $receipt
    } finally {$process.Dispose()}
    # Parent освобождает FileStream только после чтения actual failure; rollback не блокируется тем же lock.
    $clock=[Diagnostics.Stopwatch]::StartNew()
    while (-not [IO.File]::Exists((Guard (Join-Path $updates 'native-rollback-release')))) {
        if ($clock.Elapsed.TotalSeconds -ge __TIMEOUT__) {throw 'ROLLBACK_FAULT_RELEASE_TIMEOUT'}
        Start-Sleep -Milliseconds 50
    }
}

'@
    $hook=$hook.Replace('__SCENARIO__',$Scenario).Replace('__PATH__',$FaultPath).Replace('__TIMEOUT__',[string]$Timeout)
    $edits.Add([pscustomobject]@{offset=$definitions.Guard.Extent.StartOffset;length=0;text=$hook})
    foreach ($edit in $edits | Sort-Object offset -Descending) {$Original=$Original.Remove($edit.offset,$edit.length).Insert($edit.offset,$edit.text)}
    [void][Management.Automation.Language.Parser]::ParseInput($Original,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ROLLBACK_INSTRUMENTED_PARSE'}
    return $Original
}

# Fault receipt проверяет конкретную операцию, IOException и личность фактического retained helper.
function Assert-NativeRollbackFault($Fault,$Helper,[string]$Root,[string]$Transaction,[string]$Scenario,[string]$Path) {
    $kind=if ($Scenario -ceq 'locked-rollback') {'BACKUP'} else {'REPLACE'}
    $boundary=switch -CaseSensitive ($Scenario) {'locked-rollback' {'File.Move'} 'readonly-rollback' {'File.Replace'} 'disk-full-rollback' {'FileOperation.Flush(true)'}}
    $phase=if ($Scenario -ceq 'locked-rollback') {'BACKING_UP'} else {'INSTALLING'}
    $codes=if ($Scenario -ceq 'disk-full-rollback') {@('80070070')} else {@('80070020','80070021')}
    $scope=if ($Scenario -ceq 'disk-full-rollback') {'simulated-disk-full-IO-boundary'} else {'actual-managed-file-sharing-failure'}
    if ($Fault.schemaVersion -ne 1 -or $Fault.installationRoot -cne $Root -or $Fault.transactionId -cne $Transaction -or
        $Fault.scenario -cne $Scenario -or $Fault.operation.path -cne $Path -or $Fault.operation.kind -cne $kind -or
        $Fault.operation.state -cne 'BEFORE' -or $Fault.boundary -cne $boundary -or $Fault.phase -cne $phase -or
        $Fault.exceptionType -cne 'System.IO.IOException' -or $Fault.hresult -cnotin $codes -or $Fault.scope -cne $scope -or
        $Fault.pid -ne $Helper.identity.ProcessId -or $Fault.startedAtTicks -ne $Helper.identity.StartedAtTicks -or
        $Fault.executablePath -cne $Helper.identity.ExecutablePath) {throw 'ROLLBACK_FAULT_RECEIPT'}
    if (-not @($Fault.operations | Where-Object {$_.state -ceq 'AFTER' -and $_.kind -cin @('REDIRECT','BACKUP','REPLACE')}).Count) {throw 'ROLLBACK_NO_PARTIAL_MUTATION_OBSERVED'}
    Assert-ColdProcessIdentity $Helper.identity (Get-ColdProcessReceipt $Helper.process $Root) $Root $Helper.identity.ExecutablePath
    if ($Helper.process.HasExited) {throw 'ROLLBACK_HELPER_EXITED_EARLY'}
    [void](Get-ColdUtcTicks $Fault.observedUtc)
}

# Release всегда следует dispose lock; порядок отдельно проверяется fixture без native процессов.
function Release-NativeRollbackFault($Lock,[string]$Run,[string]$Root) {
    $path=Assert-NativeRollbackOwned $Run (Join-Path $Root 'CashMemory/Updates/native-rollback-release')
    if ($null -ne $Lock) {$Lock.Dispose()}
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Flush($true)} finally {$stream.Dispose()}
}

# Native исполнение использует только frozen входы и новую копию; никаких mock adapters для PASS.
function Invoke-NativeRollbackScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    Assert-NativeRollbackRow $Row $Timeout
    if (-not (Get-Variable -Name nativeRollbackAdapterReady -Scope Script -ErrorAction SilentlyContinue) -or
        -not $script:nativeRollbackAdapterReady) {throw 'ROLLBACK_IMPORT_REQUIRED'}
    if ($script:nativeRollbackStarted.Count) {throw 'ROLLBACK_PREVIOUS_STARTUP_IDENTITY_UNRESOLVED'}
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'ROLLBACK_WINDOWS_POWERSHELL7'}
    foreach ($name in 'nativeTarget','nativeProject','nativeProfile') {
        if (-not (Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue)) {throw 'ROLLBACK_CALLER_CONTEXT_REQUIRED'}
    }
    foreach ($path in @($Source,$script:nativeTarget,$Java,$Evidence)) {[void](Assert-NativeAbsolute $path)}
    $bases=@($Cold.baseManifests | ForEach-Object {$_.portableDir})
    Assert-ColdCommand $Cold $Java $bases $script:nativeTarget;Assert-NativeLifecycleConfig $Life
    $entry=@($Cold.baseManifests | Where-Object {$_.portableDir -ceq $Source})
    if ($entry.Count -ne 1 -or -not (Test-ColdInventoryEqual $Base (Read-ColdPinnedJson $entry[0].manifest $entry[0].sha256)) -or
        -not (Test-ColdInventoryEqual $Target (Read-ColdPinnedJson $Cold.targetManifest $Cold.targetManifestSha256)) -or
        $Life.manifestSha256 -cne $Cold.targetManifestSha256 -or
        -not (Test-ColdInventoryEqual $Target (Read-ColdPinnedJson (Join-Path $Life.artifactDir 'update.json') $Life.manifestSha256))) {throw 'ROLLBACK_INPUT_PINS'}
    Assert-ColdNativeImage $Source;Assert-NativeSeam $Source
    [void](Assert-ColdTree $Source $Base);[void](Assert-ColdTree $script:nativeTarget $Target)
    $sourceVersion=Get-ColdVersion $Source;$targetVersion=Get-ColdVersion $script:nativeTarget
    if ($sourceVersion.releaseNumber -ne $Base.releaseNumber -or $sourceVersion.commitSha -cne $Base.commitSha -or
        $targetVersion.releaseNumber -ne $Target.releaseNumber -or $targetVersion.commitSha -cne $Target.commitSha -or
        $Target.releaseNumber -le $Base.releaseNumber) {throw 'ROLLBACK_INPUT_VERSION'}
    $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
    [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath().TrimEnd('\','/')))
    foreach ($protected in @($bases)+@($script:nativeTarget,$Life.artifactDir,$Cold.helperScript,$Java,$Evidence)) {
        if ((Test-PortablePathContains $run $protected) -or (Test-PortablePathContains $protected $run)) {throw 'ROLLBACK_SOURCE_OVERLAP'}
    }
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $root=Join-Path (Join-Path $run $variants[$Row.path]) 'CashPrediction'
    $cellEvidence=Join-Path $run 'evidence';$script:WorkDir=$run
    [void](Assert-NativeRollbackOwned $run $root);[void](Assert-NativeRollbackOwned $run $cellEvidence)
    [void][IO.Directory]::CreateDirectory($cellEvidence);[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($root))
    Copy-Item -LiteralPath $Source -Destination $root -Recurse
    [void][IO.Directory]::CreateDirectory((Join-Path $root 'CashMemory/Updates'))
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-user.txt'),"native-rollback-user`n")
    [IO.File]::WriteAllText((Join-Path $root 'protected-root.txt'),"native-rollback-root`n")
    $script:nativeNode='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    $started=[datetime]::UtcNow;$server=$null;$native=$null;$helper=$null;$barrier=$null;$lock=$null
    $failure=$null;$cleanup=[Collections.Generic.List[string]]::new();$succeeded=$false
    $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $Row.status='PENDING';$Row.reason='NATIVE_ROLLBACK_NOT_EXECUTED'
    $Row | Add-Member executed $false -Force;$Row | Add-Member evidenceRoot $cellEvidence -Force
    $Row | Add-Member faultModel $(if ($Row.scenario -ceq 'disk-full-rollback') {'simulated-disk-full-IO-boundary'} else {'actual-managed-file-sharing-failure'}) -Force
    $Row | Add-Member physicalDiskFilled $false -Force
    try {
        $baseline=Get-NativeRollbackBaseline $Base $Row.scenario
        if ($Row.scenario -ceq 'readonly-rollback') {
            $exe=Assert-NativeRollbackOwned $run (Join-Path $root 'CashPrediction.exe')
            [IO.File]::SetAttributes($exe,([IO.File]::GetAttributes($exe) -bor [IO.FileAttributes]::ReadOnly))
        }
        [void](Assert-ColdTree $root $baseline)
        $domain=Initialize-NativeDomainSession $root $Java $Cold $cellEvidence
        $before=@(Assert-ColdTree $root $baseline);$userBefore=Get-NativeUserObject $root
        $targetBefore=@(Assert-ColdTree $script:nativeTarget $Target);$controlled=@(Get-ColdControlledInventory $root)
        $faultPath=Get-NativeRollbackFaultPath $baseline $Row.scenario
        Write-ColdJson (Join-Path $cellEvidence 'fixture-baseline.json') ([ordered]@{scenario=$Row.scenario;
            source=$Source;sourceManifest=$entry[0].manifest;sourceManifestSha256=$entry[0].sha256;
            originalTreeSha256=$Base.treeSha256;fixtureTreeSha256=$baseline.treeSha256;faultPath=$faultPath;
            readonlyFixture=($Row.scenario -ceq 'readonly-rollback');domain=$domain;files=$before})
        $server=Start-NativeFixture $Life $Java 'valid' 0
        $arguments=@(Get-NativeArguments $root $Row.client $script:nativeNode);$launchAt=[datetime]::UtcNow
        $native=Start-NativeRollbackOwned (Join-Path $root (Get-ColdLauncherName $Row.client)) $arguments $root $server.receipt.manifestUri -Web:($Row.client -ceq 'web')
        if ($native.process -isnot [Diagnostics.Process] -or $server.process -isnot [Diagnostics.Process]) {throw 'ROLLBACK_REAL_PROCESS_REQUIRED'}
        Connect-NativeClient $native $root $Row.client $launchAt $Timeout
        $Row | Add-Member executed $true -Force
        $commandPath=Join-Path $cellEvidence 'launch.json'
        Write-ColdJson $commandPath ([ordered]@{launcher=$native.identity;ui=$native.ui;args=$arguments;manifestUri=$server.receipt.manifestUri})
        Wait-NativeCondition {
            if ($native.uiProcess.HasExited) {throw 'ROLLBACK_PREPARATION_EXITED'}
            $ready=Join-Path $root 'CashMemory/Updates/Ready/update.json'
            if (-not (Test-Path -LiteralPath $ready)) {return $false}
            if (-not (Test-ColdInventoryEqual $Target (Read-ColdPinnedJson $ready $Cold.targetManifestSha256))) {throw 'ROLLBACK_READY_PIN'}
            [void](Assert-ColdTree (Join-Path $root 'CashMemory/Updates/Ready/tree') $Target)
            [void](Assert-ColdTree $root $baseline);return $true
        } $Timeout 'ROLLBACK_READY_TIMEOUT'
        $barrier=Open-NativeRollbackHelperBarrier $run $root
        if ($Row.scenario -cne 'disk-full-rollback') {$lock=Open-NativeRollbackFileLock $run $root $faultPath}
        $exit=Close-NativeNormally $native $root $Row.client $Timeout $cellEvidence
        Write-ColdJson (Join-Path $cellEvidence 'ordinary-exit.json') $exit
        $journalPath=Join-Path $root 'CashMemory/Updates/install-journal.json'
        Wait-NativeCondition {Test-Path -LiteralPath $journalPath} $Timeout 'ROLLBACK_PRODUCTION_JOURNAL_TIMEOUT'
        # Подавленный automatic helper принадлежит только этой копии; cleanup сверяет birth/exe/encoded command.
        Stop-ColdRecoveryHelpers $root $powerShell $started
        $journal=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $journalPath -Raw -Encoding utf8)
        if ($journal.schemaVersion -ne 2 -or $journal.installationRoot -cne $root -or $journal.phase -cnotin @('PREPARED','WAITING') -or
            $journal.oldTreeSha256 -cne $baseline.treeSha256 -or -not (Test-ColdInventoryEqual $journal.target $Target)) {throw 'ROLLBACK_PRODUCTION_JOURNAL'}
        [void][guid]::Parse($journal.transactionId)
        Copy-Item -LiteralPath $journalPath -Destination (Join-Path $cellEvidence 'production-journal-before.json')
        $productionHelper=Join-Path $root 'CashMemory/Updates/apply-update.ps1'
        if ((Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $productionHelper)).Hash.ToLowerInvariant() -cne $Cold.helperSha256) {throw 'ROLLBACK_PRODUCTION_HELPER_PIN'}
        $originalBytes=[IO.File]::ReadAllBytes((Resolve-PortableSafetyPath $Cold.helperScript))
        if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($originalBytes)).ToLowerInvariant() -cne $Cold.helperSha256) {throw 'ROLLBACK_ORIGINAL_HELPER_BYTES_PIN'}
        $original=[Text.UTF8Encoding]::new($false,$true).GetString($originalBytes)
        $ownHelper=Join-Path $cellEvidence 'rollback-helper.ps1'
        $text=New-NativeRollbackHelperText $original $Row.scenario $faultPath $Timeout
        [IO.File]::WriteAllText($ownHelper,$text,[Text.UTF8Encoding]::new($false))
        $ownHelperSha=(Get-FileHash -LiteralPath $ownHelper).Hash.ToLowerInvariant()
        $injectionPath=Join-Path $cellEvidence 'injection-receipt.json'
        Write-ColdJson $injectionPath ([ordered]@{originalHelper=$Cold.helperScript;originalHelperSha256=$Cold.helperSha256;
            portableOriginalHelper=$productionHelper;portableOriginalHelperSha256=(Get-FileHash -LiteralPath $productionHelper).Hash.ToLowerInvariant();
            ownHelper=$ownHelper;ownHelperSha256=$ownHelperSha;scenario=$Row.scenario;
            faultPath=$faultPath;lockShare=$(if ($null -ne $lock) {'ReadWrite, no Delete'} else {'not used'});
            scope=$(if ($Row.scenario -ceq 'disk-full-rollback') {'simulated-disk-full-IO-boundary'} else {'actual-managed-file-sharing-failure'});
            physicalDiskFilled=$false;automaticHelperSuppressedBy='own helper.lock held through rollback and cleanup';
            instrumentedHelperLock='native-rollback-helper.lock';transactionId=$journal.transactionId})
        $command="& '"+$ownHelper.Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"' -Diagnostics"
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
        if ((Get-FileHash -LiteralPath $ownHelper).Hash.ToLowerInvariant() -cne $ownHelperSha) {throw 'ROLLBACK_OWN_HELPER_CHANGED'}
        $helper=Start-NativeRollbackOwned $powerShell @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) $root
        if ($helper.process -isnot [Diagnostics.Process]) {throw 'ROLLBACK_REAL_HELPER_REQUIRED'}
        Write-ColdJson (Join-Path $cellEvidence 'helper-launch.json') ([ordered]@{identity=$helper.identity;args=@($helper.process.StartInfo.ArgumentList);helperSha256=(Get-FileHash -LiteralPath $ownHelper).Hash.ToLowerInvariant()})
        $faultFile=Join-Path $root 'CashMemory/Updates/native-rollback-fault.json'
        Wait-NativeCondition {if ($helper.process.HasExited) {throw 'ROLLBACK_NATIVE_FAULT_NOT_OBSERVED'};Test-Path -LiteralPath $faultFile} $Timeout 'ROLLBACK_NATIVE_FAULT_TIMEOUT'
        $fault=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $faultFile -Raw -Encoding utf8)
        Assert-NativeRollbackFault $fault $helper $root $journal.transactionId $Row.scenario $faultPath
        Copy-Item -LiteralPath $faultFile -Destination (Join-Path $cellEvidence 'fault-observed.json')
        Release-NativeRollbackFault $lock $run $root;$lock=$null
        Wait-NativeCondition {$helper.process.HasExited} $Timeout 'ROLLBACK_HELPER_TIMEOUT'
        Save-NativeOutput $helper $cellEvidence 'helper'
        if ($helper.process.ExitCode -ne 0) {throw 'ROLLBACK_HELPER_FAILED'}
        $last=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $root 'CashMemory/Updates/last-install.json') -Raw -Encoding utf8)
        if ($last.outcome -cne 'ROLLED_BACK' -or $last.transactionId -cne $journal.transactionId -or $last.targetCommitSha -cne $Target.commitSha) {throw 'ROLLBACK_OUTCOME'}
        Copy-Item -LiteralPath (Join-Path $root 'CashMemory/Updates/last-install.json') -Destination (Join-Path $cellEvidence 'last-install-observed.json')
        [void](Assert-ColdTree (Join-Path $root 'CashMemory/Updates/Ready/tree') $Target)
        foreach ($relative in 'install-journal.json','completed-journal.json') {
            if (Test-Path -LiteralPath (Join-Path $root ('CashMemory/Updates/'+$relative))) {throw 'ROLLBACK_JOURNAL_RESIDUE'}
        }
        foreach ($relative in 'requests','processes') {
            $directory=Join-Path $root ('CashMemory/Updates/'+$relative)
            if ((Test-Path -LiteralPath $directory) -and @(Get-ChildItem -LiteralPath $directory -Force).Count) {throw 'ROLLBACK_LEASE_OR_REQUEST_RESIDUE'}
        }
        foreach ($relative in @('Bootstrap',('Bootstrap.partial-'+$journal.transactionId),('Work-'+$journal.transactionId),'Backup')) {
            if (Test-Path -LiteralPath (Join-Path $root ('CashMemory/Updates/'+$relative))) {throw 'ROLLBACK_BOOTSTRAP_OR_BACKUP_RESIDUE'}
        }
        if (@(Get-CopyProcesses $root).Count) {throw 'ROLLBACK_UNEXPECTED_RESTART'}
        $after=@(Assert-ColdTree $root $baseline);$version=Get-ColdVersion $root
        if ($version.releaseNumber -ne $Base.releaseNumber -or $version.commitSha -cne $Base.commitSha) {throw 'ROLLBACK_OLD_VERSION'}
        $userAfter=Get-NativeUserObject $root
        if (-not (Test-ColdInventoryEqual $userBefore $userAfter)) {throw 'ROLLBACK_USER_CHANGED'}
        Assert-ColdControlledChanges $controlled @(Get-ColdControlledInventory $root) $Row.client $native.ui $root ([datetime]::UtcNow.Ticks)
        $http=Stop-NativeFixture $server $cellEvidence 'server'
        $phases=@('SESSION');$logPath=Join-Path $root 'CashMemory/Updates/update-log.md'
        Copy-Item -LiteralPath $logPath -Destination (Join-Path $cellEvidence 'update-log.md')
        foreach ($line in Get-Content -LiteralPath $logPath -Encoding utf8) {
            if ($line -cmatch '^- (\S+) PHASE_([A-Z_]+)$') {
                $phase=$Matches[2];$phaseTime=$Matches[1];$phases+=@($phase)
                if ($phase -cin @('BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK') -and
                    (Get-ColdUtcTicks $phaseTime) -lt (Get-ColdUtcTicks $exit.exitedUtc)) {throw 'ROLLBACK_MUTATION_BEFORE_EXIT'}
            }
        }
        if ($phases -cnotcontains 'ROLLING_BACK' -or $phases -ccontains 'COMMITTED') {throw 'ROLLBACK_PHASE_LOG'}
        $values=@{currentBefore=$before;currentAfter=$after;targetBefore=$targetBefore;targetAfter=@(Assert-ColdTree $script:nativeTarget $Target);
            userBefore=$userBefore;userAfter=$userAfter;httpTrace=@($http.events | Where-Object {$_.event -ceq 'FINISH'});phaseLog=$phases}
        foreach ($name in $values.Keys) {$path=Join-Path $cellEvidence ($name+'.json');Write-ColdJson $path $values[$name];$Row | Add-Member $name $path -Force}
        $fields=@{exe=(Join-Path $root (Get-ColdLauncherName $Row.client));args=$arguments;baseRelease=$Base.releaseNumber;baseCommit=$Base.commitSha;
            targetRelease=$Target.releaseNumber;targetCommit=$Target.commitSha;command=$commandPath;startedAt=$started.ToString('o');
            finishedAt=[datetime]::UtcNow.ToString('o');exitCode=$helper.process.ExitCode;skipped=0;failures=0;
            injectionEvidence=$injectionPath;faultEvidence=(Join-Path $cellEvidence 'fault-observed.json')}
        foreach ($name in $fields.Keys) {$Row | Add-Member $name $fields[$name] -Force}
        # Frozen входы и production helper повторно проверяются после фактического rollback.
        Assert-ColdCommand $Cold $Java $bases $script:nativeTarget;Assert-NativeLifecycleConfig $Life
        [void](Assert-ColdTree $Source $Base)
        if ((Get-FileHash -LiteralPath $productionHelper).Hash.ToLowerInvariant() -cne $Cold.helperSha256) {throw 'ROLLBACK_PRODUCTION_HELPER_CHANGED'}
        if ((Get-FileHash -LiteralPath $ownHelper).Hash.ToLowerInvariant() -cne $ownHelperSha) {throw 'ROLLBACK_OWN_HELPER_CHANGED'}
        $succeeded=$true
    } catch {$failure=$_.Exception.Message}
    finally {
        if ($null -ne $lock) {$lock.Dispose()}
        if ($null -ne $helper) {
            try {if (-not $helper.process.HasExited) {Stop-ColdRetainedProcess $helper.process $helper.identity $root}} catch {$cleanup.Add($_.Exception.Message)}
            try {Save-NativeOutput $helper $cellEvidence 'cleanup-helper'} catch {$cleanup.Add($_.Exception.Message)}
            $helper.process.Dispose()
        }
        try {Stop-ColdCopyProcesses $root;if (@(Get-CopyProcesses $root).Count) {throw 'ROLLBACK_CLEANUP_CLIENT_ALIVE'}} catch {$cleanup.Add($_.Exception.Message)}
        # После исчезновения клиентов новые production helpers больше не создаются; barrier пока удерживается.
        try {Stop-ColdRecoveryHelpers $root $powerShell $started} catch {$cleanup.Add($_.Exception.Message)}
        if ($null -ne $native) {
            try {Save-NativeOutput $native $cellEvidence 'client'} catch {$cleanup.Add($_.Exception.Message)}
            if ($null -ne $native.uiProcess) {$native.uiProcess.Dispose()};$native.process.Dispose()
        }
        if ($null -ne $server) {
            try {if (-not $server.process.HasExited) {[void](Stop-NativeFixture $server $cellEvidence 'cleanup-server')}} catch {$cleanup.Add($_.Exception.Message)}
            try {if (-not $server.process.HasExited) {Stop-ColdRetainedProcess $server.process $server.identity $server.owned}} catch {$cleanup.Add($_.Exception.Message)}
            $server.process.Dispose();$Row | Add-Member serverDirectories @($server.owned) -Force
        }
        foreach ($entry in @($script:nativeRollbackStarted.ToArray())) {
            try {
                Stop-NativeRollbackTracked $entry
                $entry.process.Dispose();[void]$script:nativeRollbackStarted.Remove($entry)
            } catch {$cleanup.Add('ROLLBACK_RETAINED_STARTUP: '+$_.Exception.Message)}
        }
        # Production lock освобождается последним, не между close и own helper Start.
        if ($null -ne $barrier) {try {$barrier.Unlock(0,1)} catch {$cleanup.Add($_.Exception.Message)} finally {$barrier.Dispose()}}
        if (-not $cleanup.Count) {
            try {
                $nodePath=Get-PortableRegistryPath $script:nativeNode
                if (Test-Path -LiteralPath $nodePath) {[void](Get-PortableRegistryPath $script:nativeNode);Remove-Item -LiteralPath $nodePath -Recurse -Force}
                if (Test-Path -LiteralPath $nodePath) {throw 'ROLLBACK_REGISTRY_RESIDUE'}
            } catch {$cleanup.Add($_.Exception.Message)}
        }
        $cleanupPath=Join-Path $cellEvidence 'cleanup.json'
        Write-ColdJson $cleanupPath ([ordered]@{scope='OWN_RUN_UUID_AND_SELFTEST_NODE';root=$root;
            node=$script:nativeNode;errors=@($cleanup.ToArray());nativeCompleted=$succeeded;filesRetained=$true;
            helperIdentity=$(if ($null -ne $helper) {$helper.identity} else {$null});
            launcherIdentity=$(if ($null -ne $native) {$native.identity} else {$null});
            uiIdentity=$(if ($null -ne $native) {$native.ui} else {$null});
            serverIdentity=$(if ($null -ne $server) {$server.identity} else {$null});
            unresolvedStartups=@($script:nativeRollbackStarted | ForEach-Object {
                [pscustomobject]@{root=$_.root;expectedExecutable=$_.expectedExecutable;identity=$_.identity;retained=$true}
            })})
        $Row | Add-Member cleanupEvidence $cleanupPath -Force
        $Row.status=if ($succeeded -and -not $failure -and -not $cleanup.Count) {'PASS'} else {'FAIL'}
        $Row.reason=if ($failure) {$failure} elseif ($cleanup.Count) {$cleanup -join '; '} else {'NATIVE_ROLLBACK_EXECUTED'}
        Write-ColdJson (Join-Path $cellEvidence 'cell.json') $Row
    }
    if ($Row.status -cne 'PASS') {throw $Row.reason}
    return $Row
}
