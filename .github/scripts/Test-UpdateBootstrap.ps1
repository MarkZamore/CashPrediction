<#
.SYNOPSIS
Проверяет cold запуск трёх настоящих jpackage exe после остановки bootstrap helper.
.DESCRIPTION
Запускается MAIN после свежей dist. Не собирает Java и не меняет production.
CommandFile - JSON schema1, закреплённый CommandFileSha256:
 runtimeSha256; toolArguments: ["--module-path","<core.jar>;<update-tool.jar>","-m",
 "ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool"];
 toolFiles: [{path,sha256}]; helperScript; helperSha256;
 baseManifests: [{portableDir,manifest,sha256}]; targetManifest; targetManifestSha256.
Все пути абсолютные. helperScript MAIN экспортирует настоящим PowerShellHelper.publish.
Manifest schema2 и полные деревья MAIN проверяет до передачи runner.
Runner повторяет verify и независимый hash, сохраняет полные квитанции в отдельном Temp UUID.
Checkpoint schema1 включает bootstrapVerified, bootstrapFiles, bootstrapTreeSha256:
true выставляется только после exact проверки старого runtime и четырёх корневых модульных JAR.
Только helper в тестовой копии получает паузы после durable Save/Phase/Boundary.
После Kill нет ручного запуска recovery helper: запускается обычный exe.
Неисполненные клетки остаются PENDING; отсутствие окна/собственного HTTP/квитанции - FAIL.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$PortableDir,
    [Parameter(Mandatory)][string]$TargetPortableDir,
    [Parameter(Mandatory)][string]$Runtime,
    [Parameter(Mandatory)][string]$CommandFile,
    [Parameter(Mandatory)][string]$CommandFileSha256,
    [ValidateRange(10,120)][int]$CheckpointTimeoutSeconds = 120,
    [ValidateRange(10,180)][int]$ColdTimeoutSeconds = 90,
    [ValidateRange(1,400)][int]$MaxCells = 400,
    [string[]]$CellKey = @()
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

# Читаем функции общей защиты без исполнения существующего portable runner.
function Import-ColdPortableSafety([string]$ScriptPath) {
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($ScriptPath,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'COLD_PORTABLE_PARSE' }
    foreach ($name in 'Resolve-PortableSafetyPath','Test-PortablePathContains','Get-ValidatedPortablePaths',
        'Assert-PortableSourceEntries','Assert-PortableTreeHasNoLinks','Get-PortableCleanupPath',
        'Get-PortableRegistryPath','Get-PortableRealRegistrySnapshot','Get-CopyProcesses',
        'Open-PortableProcess','Stop-PortableProcess','Stop-CopyProcesses','Test-PortableListener') {
        $functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true))
        if ($functions.Count -ne 1) { throw "COLD_SAFETY_FUNCTION $name" }
        # Global нужен для вызовов из runner и отдельных отрицательных fixtures.
        $definition=$functions[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
        . ([scriptblock]::Create($definition))
    }
}

# Точный контракт данных; неизвестные или отсутствующие поля не принимаются.
function Assert-ColdKeys($Value,[string[]]$Keys) {
    if ($null -eq $Value -or $Value -isnot [pscustomobject]) { throw 'COLD_OBJECT' }
    $names=@($Value.PSObject.Properties.Name)
    if ($names.Count -ne $Keys.Count) { throw 'COLD_FIELDS' }
    foreach ($key in $Keys) { if ($names -cnotcontains $key) { throw 'COLD_FIELDS' } }
}

# Exact selector предназначен только для диагностики: остальные клетки остаются PENDING.
function Get-ColdSelectedPlan($Plan,[string[]]$Keys) {
    if ($Keys.Count -eq 0) {return @($Plan)}
    $known=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($cell in @($Plan)) {
        if (-not $known.Add($cell.key)) {throw 'COLD_PLAN_DUPLICATE'}
    }
    $selected=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($key in $Keys) {
        if (-not $key -or -not $known.Contains($key) -or -not $selected.Add($key)) {
            throw 'COLD_CELL_SELECTION'
        }
    }
    return @($Plan | Where-Object {$selected.Contains($_.key)})
}

# Пин проверяется до JSON/запуска/копирования; файл и существующие предки без ссылок.
function Read-ColdPinnedJson([string]$Path,[string]$ExpectedHash) {
    if ($ExpectedHash -cnotmatch '^[0-9a-f]{64}$') { throw 'COLD_PIN_FORMAT' }
    if (-not [IO.Path]::IsPathFullyQualified($Path)) {throw 'COLD_ABSOLUTE_METADATA_REQUIRED'}
    $resolved=Resolve-PortableSafetyPath $Path
    $item=Get-Item -LiteralPath $resolved -Force
    if ($item.PSIsContainer -or $item.Length -gt 33554432 -or
        (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ExpectedHash) { throw 'COLD_PIN' }
    return (Get-Content -LiteralPath $resolved -Raw -Encoding utf8 | ConvertFrom-Json -Depth 64)
}

# Ни внешний JDK, ни архивы CLI не выбираются по имени процесса или непроверенному тексту.
function Assert-ColdCommand($Config,[string]$JavaPath,[string[]]$Bases,[string]$Target) {
    Assert-ColdKeys $Config @('schemaVersion','runtimeSha256','toolArguments','toolFiles','helperScript','helperSha256',
        'baseManifests','targetManifest','targetManifestSha256')
    if (($Config.schemaVersion -isnot [int] -and $Config.schemaVersion -isnot [long]) -or
        $Config.schemaVersion -ne 1 -or @($Bases).Count -lt 1 -or @($Bases).Count -gt 2) { throw 'COLD_COMMAND_SCHEMA' }
    foreach ($pin in @($Config.runtimeSha256,$Config.helperSha256,$Config.targetManifestSha256)) {
        if ($pin -isnot [string] -or $pin -cnotmatch '^[0-9a-f]{64}$') { throw 'COLD_PIN_FORMAT' }
    }
    if ((Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $JavaPath) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Config.runtimeSha256) { throw 'COLD_RUNTIME_PIN' }
    $arguments=@($Config.toolArguments)
    if ($arguments.Count -ne 4 -or $arguments[0] -cne '--module-path' -or $arguments[2] -cne '-m' -or
        $arguments[3] -cne 'ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool') { throw 'COLD_TOOL_COMMAND' }
    $jars=@($arguments[1].Split(';'))
    if ($jars.Count -lt 2 -or $jars.Count -gt 4 -or @($Config.toolFiles).Count -ne $jars.Count) { throw 'COLD_TOOL_FILES' }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($jar in $jars) {
        if (-not [IO.Path]::IsPathFullyQualified($jar) -or -not $seen.Add($jar) -or [IO.Path]::GetExtension($jar) -cne '.jar') { throw 'COLD_TOOL_PATH' }
        $pins=@($Config.toolFiles | Where-Object { $_.path -ceq $jar })
        if ($pins.Count -ne 1) { throw 'COLD_TOOL_FILES' }
        Assert-ColdKeys $pins[0] @('path','sha256')
        if ($pins[0].sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $jar)).Hash.ToLowerInvariant() -cne $pins[0].sha256) { throw 'COLD_TOOL_PIN' }
    }
    if (@($Config.baseManifests).Count -ne $Bases.Count) { throw 'COLD_BASE_MANIFESTS' }
    foreach ($base in $Bases) {
        $matches=@($Config.baseManifests | Where-Object { $_.portableDir -ceq $base })
        if ($matches.Count -ne 1 -or $base -ceq $Target) { throw 'COLD_BASE_IDENTITY' }
        Assert-ColdKeys $matches[0] @('portableDir','manifest','sha256')
        [void](Read-ColdPinnedJson $matches[0].manifest $matches[0].sha256)
    }
    [void](Read-ColdPinnedJson $Config.targetManifest $Config.targetManifestSha256)
    if ((Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $Config.helperScript)).Hash.ToLowerInvariant() -cne $Config.helperSha256) { throw 'COLD_HELPER_PIN' }
}

# План покрывает durable состояния, публикацию полного payload и реальные разрывы native загрузки.
function Get-ColdCheckpoints {
    return @('INITIAL','COPYING','PUBLISH_BEFORE','PUBLISH_AFTER','COPIED','ACTIVE',
        'BACKING_UP','JLI_GAP','JVM_GAP','MODULES_GAP','INSTALLING','NATIVE_FX_REPLACE','NATIVE_SWING_REPLACE','NATIVE_WEB_REPLACE',
        'VERIFYING','CFG_FX_SWITCH','CFG_SWING_SWITCH','CFG_WEB_SWITCH','RESTORED',
        'COMMITTED','CLEANED','ROLLING_BACK')
}

# Перед Kill обязателен конкретный checkpoint, привязанный к исходному процессу и транзакции.
function Assert-ColdCheckpoint($Receipt,$Expected,[string]$Root,[string]$Checkpoint,[string]$Transaction) {
    Assert-ColdKeys $Receipt @('schemaVersion','checkpoint','installationRoot','transactionId','pid','startedAtTicks',
        'executablePath','hook','phase','bootstrapState','publishState','operation','journalSha256',
        'bootstrapVerified','bootstrapFiles','bootstrapTreeSha256')
    if (-not (Test-ColdInteger $Receipt.schemaVersion 1) -or -not (Test-ColdInteger $Receipt.pid 1) -or
        -not (Test-ColdInteger $Receipt.startedAtTicks 1) -or $Receipt.schemaVersion -ne 1 -or $Receipt.checkpoint -cne $Checkpoint -or $Receipt.installationRoot -cne $Root -or
        $Receipt.transactionId -cne $Transaction -or $Receipt.pid -ne $Expected.ProcessId -or
        $Receipt.startedAtTicks -ne $Expected.StartedAtTicks -or $Receipt.executablePath -cne $Expected.ExecutablePath -or
        $Receipt.journalSha256 -cnotmatch '^[0-9a-f]{64}$' -or $Receipt.bootstrapVerified -isnot [bool] -or
        $Receipt.bootstrapTreeSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'COLD_CHECKPOINT_IDENTITY' }
    $state=@{INITIAL='INITIAL';COPYING='COPYING';COPIED='COPIED';ACTIVE='ACTIVE';RESTORED='RESTORED';CLEANED='CLEANED'}
    if ($state.ContainsKey($Checkpoint) -and ($Receipt.bootstrapState -cne $state[$Checkpoint] -or
        ($Checkpoint -ne 'INITIAL' -and $Receipt.hook -cne 'SAVE') -or
        ($Checkpoint -eq 'INITIAL' -and $Receipt.hook -cnotin @('SAVE','PHASE')))) { throw 'COLD_CHECKPOINT_STATE' }
    if ($Checkpoint -like 'PUBLISH_*' -and ($Receipt.bootstrapState -cne 'COPYING' -or $Receipt.hook -cne 'SAVE' -or
        $Receipt.publishState -cne $Checkpoint.Substring(8))) { throw 'COLD_CHECKPOINT_PUBLISH' }
    if ($Checkpoint -in @('BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK') -and
        ($Receipt.phase -cne $Checkpoint -or $Receipt.hook -cne 'PHASE')) { throw 'COLD_CHECKPOINT_PHASE' }
    $paths=@{JLI_GAP='runtime/bin/jli.dll';JVM_GAP='runtime/bin/server/jvm.dll';MODULES_GAP='runtime/lib/modules';
        NATIVE_FX_REPLACE='CashPrediction.exe';NATIVE_SWING_REPLACE='CashPrediction-Swing.exe';NATIVE_WEB_REPLACE='CashPrediction-Web.exe';CFG_FX_SWITCH='app/CashPrediction.cfg';
        CFG_SWING_SWITCH='app/CashPrediction-Swing.cfg';CFG_WEB_SWITCH='app/CashPrediction-Web.cfg'}
    if ($paths.ContainsKey($Checkpoint)) {
        Assert-ColdKeys $Receipt.operation @('kind','path','state')
        $kind=if ($Checkpoint -like '*_GAP') {'BACKUP'} elseif ($Checkpoint -like 'NATIVE_*_REPLACE') {'REPLACE'} else {'CFG_SWITCH'}
        if ($Receipt.hook -cne 'BOUNDARY' -or $Receipt.operation.kind -cne $kind -or
            $Receipt.operation.path -cne $paths[$Checkpoint] -or $Receipt.operation.state -cne 'BEFORE') { throw 'COLD_CHECKPOINT_OPERATION' }
    }
}

# Дескриптор удерживается от Start до Kill; неизвестная личность запрещает остановку.
function Assert-ColdProcessIdentity($Expected,$Actual,[string]$OwnedRoot,[string]$ExpectedExecutable) {
    if (-not (Test-ColdInteger $Expected.ProcessId 1) -or -not (Test-ColdInteger $Expected.StartedAtTicks 1) -or
        -not (Test-ColdInteger $Actual.ProcessId 1) -or -not (Test-ColdInteger $Actual.StartedAtTicks 1) -or $Expected.ExecutablePath -cne $ExpectedExecutable -or
        $Actual.ProcessId -ne $Expected.ProcessId -or $Actual.StartedAtTicks -ne $Expected.StartedAtTicks -or
        $Actual.ExecutablePath -cne $ExpectedExecutable -or -not $Expected.OwnedRoot.Equals($OwnedRoot,[StringComparison]::OrdinalIgnoreCase) -or
        -not $Actual.OwnedRoot.Equals($OwnedRoot,[StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Path]::IsPathFullyQualified($OwnedRoot) -or -not [IO.Path]::IsPathFullyQualified($ExpectedExecutable)) { throw 'COLD_PROCESS_IDENTITY' }
}

# Числа wire-контрактов не принимают строки, bool, дроби и отрицательные значения.
function Test-ColdInteger($Value,[long]$Minimum=0) {
    return (($Value -is [int] -or $Value -is [long]) -and $Value -ge $Minimum)
}

# Имя exe определяется одним закрытым списком для наблюдения и проверки квитанций.
function Get-ColdLauncherName([string]$Client) {
    switch -CaseSensitive ($Client) {'fx' {return 'CashPrediction.exe'} 'swing' {return 'CashPrediction-Swing.exe'} 'web' {return 'CashPrediction-Web.exe'} default {throw 'COLD_REPORT_CLIENT'}}
}

# Инвентарь проверяется в canonical порядке unsigned UTF-8, без нормализации опасных путей.
function Assert-ColdInventory($Files,[string]$Hash) {
    if (@($Files).Count -lt 1 -or @($Files).Count -gt 20000 -or $Hash -cnotmatch '^[0-9a-f]{64}$') {throw 'COLD_INVENTORY'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase); $previous=''; $total=0L
    foreach ($file in @($Files)) {
        Assert-ColdKeys $file @('path','sizeBytes','sha256','readOnly')
        $p=$file.path
        if ($p -isnot [string] -or $p -cnotmatch '^(CashPrediction(?:-Swing|-Web)?\.exe|(?:app|runtime)/.+)$' -or
            $p -cne $p.Normalize([Text.NormalizationForm]::FormC) -or $p -match '[\\:<>"|?*\x00-\x1f\x7f]' -or
            -not $seen.Add($p) -or -not (Test-ColdInteger $file.sizeBytes) -or $file.sizeBytes -gt 536870912 -or
            $file.sha256 -isnot [string] -or $file.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $file.readOnly -isnot [bool]) {throw 'COLD_INVENTORY'}
        foreach ($segment in $p.Split('/')) {
            if (-not $segment -or $segment -in @('.','..') -or $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
                $segment -match '^(?i:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\.|$)') {throw 'COLD_INVENTORY'}
        }
        $key=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($p))
        if ($previous -and [StringComparer]::Ordinal.Compare($previous,$key) -ge 0) {throw 'COLD_INVENTORY'}
        $previous=$key; $total+=$file.sizeBytes
    }
    if ($total -gt 2147483648L -or (Get-ColdTreeHash $Files) -cne $Hash) {throw 'COLD_INVENTORY'}
}

# Два проверенных инвентаря сравниваются по всем полям, а не одному digest.
function Test-ColdInventoryEqual($Left,$Right) {
    return (ConvertTo-Json -InputObject @($Left) -Depth 8 -Compress) -ceq (ConvertTo-Json -InputObject @($Right) -Depth 8 -Compress)
}

# Хеш canonical JSON связывает controlled baseline с каждым наблюдением активного recovery.
function Get-ColdObjectHash($Value) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(
        (ConvertTo-Json -InputObject @($Value) -Depth 64 -Compress)))).ToLowerInvariant()
}

# Точное UTC время wire-квитанции исключает locale parse и значения без зоны.
function Get-ColdUtcTicks($Value) {
    if ($Value -isnot [string]) {throw 'COLD_RECEIPT_TIME'}
    $match=[regex]::Match($Value,'^(?<prefix>\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(?<fraction>\d{1,9}))?(?<zone>Z|\+00:00)$')
    if (-not $match.Success) {throw 'COLD_RECEIPT_TIME'}
    # Java Instant имеет наносекунды, .NET tick - 100 ns. Отбрасываем только два
    # младших знака, не округляя момент вперёд и не принимая другую часовую зону.
    $normalized=$match.Groups['prefix'].Value
    $fraction=$match.Groups['fraction'].Value
    if ($fraction.Length) {$normalized+='.'+$fraction.Substring(0,[Math]::Min(7,$fraction.Length))}
    $normalized+=$match.Groups['zone'].Value
    $parsed=[DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($normalized,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$parsed)) {throw 'COLD_RECEIPT_TIME'}
    return $parsed.UtcTicks
}

# Новые PowerShell автоматически превращают ISO строки JSON в DateTime; wire остаётся строкой.
function ConvertFrom-ColdReceiptJson([string]$Text) {
    $options=@{Depth=64;NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
    return ,(ConvertFrom-Json -InputObject $Text @options)
}

# Windows argv разбирается без native API; непарные кавычки и NUL запрещены.
function ConvertFrom-ColdCommandLine([string]$Text) {
    if (-not $Text -or $Text.Contains([char]0) -or $Text.Length -gt 32768) {throw 'COLD_COMMAND_LINE'}
    $result=[Collections.Generic.List[string]]::new(); $i=0
    while ($i -lt $Text.Length) {
        while ($i -lt $Text.Length -and $Text[$i] -in @(' ',"`t")) {$i++}
        if ($i -ge $Text.Length) {break}
        $value=[Text.StringBuilder]::new(); $quoted=$false
        while ($i -lt $Text.Length) {
            if (-not $quoted -and $Text[$i] -in @(' ',"`t")) {break}
            $slashes=0
            while ($i -lt $Text.Length -and $Text[$i] -eq '\') {$slashes++;$i++}
            if ($i -lt $Text.Length -and $Text[$i] -eq '"') {
                [void]$value.Append(('\'*[int][Math]::Floor($slashes/2)))
                if ($slashes%2) {[void]$value.Append('"');$i++} else {
                    if ($quoted -and $i+1 -lt $Text.Length -and $Text[$i+1] -eq '"') {[void]$value.Append('"');$i+=2}
                    else {$quoted=-not $quoted;$i++}
                }
            } else {
                [void]$value.Append(('\'*$slashes))
                if ($i -lt $Text.Length -and ($quoted -or $Text[$i] -notin @(' ',"`t"))) {[void]$value.Append($Text[$i]);$i++}
            }
        }
        if ($quoted) {throw 'COLD_COMMAND_LINE'}
        $result.Add($value.ToString())
    }
    return $result.ToArray()
}

# Рестарт сохраняет исходные Web flags и допускает только ожидаемый target SHA.
function Assert-ColdSafeArgs($Original,$Restart,[string]$Root,[string]$Client,[string]$TargetSha) {
    $args=@($Original); $safe=@('--home',$Root)
    if ($Client -ceq 'web') {foreach ($flag in '--no-browser','--no-window') {if ($args -ccontains $flag) {$safe+=@($flag)}}}
    if (-not (Test-ColdInventoryEqual $args $safe) -or $TargetSha -cnotmatch '^[0-9a-f]{40}$' -or
        -not (Test-ColdInventoryEqual @($Restart) @($safe+@('--updated-from',$TargetSha)))) {throw 'COLD_REPORT_LAUNCH_IDENTITY'}
}

# Typed UI witness связывает exe, клиент, lease, birth, модули и фактическую command line.
# Нормализует только целочисленный native TCP port; JSON wire проверки остаются строгими.
function Convert-ColdNativePort($Value) {
    if (($Value -isnot [uint16] -and -not (Test-ColdInteger $Value 1)) -or $Value -lt 1 -or $Value -gt 65535) {
        throw 'COLD_NATIVE_PORT'
    }
    return [int]$Value
}

function Assert-ColdUiReceipt($Ui,[string]$Root,[string]$Client) {
    try {
        Assert-ColdKeys $Ui @('pid','startedAtTicks','executablePath','modules','witness','lease','commandLine','args','observedAt')
        Assert-ColdKeys $Ui.lease @('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client')
        if (-not (Test-ColdInteger $Ui.pid 1) -or -not (Test-ColdInteger $Ui.startedAtTicks 1) -or
            $Ui.startedAtTicks -gt (Get-ColdUtcTicks $Ui.observedAt) -or
            $Ui.executablePath -cne (Join-Path $Root (Get-ColdLauncherName $Client)) -or
            -not (Test-ColdInteger $Ui.lease.schemaVersion 1) -or $Ui.lease.schemaVersion -ne 1 -or
            $Ui.lease.leaseId -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
            -not (Test-ColdInteger $Ui.lease.pid 1) -or $Ui.lease.pid -ne $Ui.pid -or
            -not (Test-ColdInteger $Ui.lease.startedAtEpochMillis 1) -or $Ui.lease.startedAtEpochMillis -ne
            ([DateTimeOffset]::new([datetime]::new($Ui.startedAtTicks,[DateTimeKind]::Utc))).ToUnixTimeMilliseconds() -or
            $Ui.lease.client -cne $Client -or $Ui.lease.installationRoot -cne $Root) {throw 'COLD_UI_IDENTITY'}
        $modules=@($Ui.modules)
        if ($modules.Count -lt 1 -or $modules.Count -gt 2 -or @($modules | Where-Object {$_ -ceq (Join-Path $Root 'runtime/bin/server/jvm.dll')}).Count -ne 1) {throw 'COLD_UI_MODULES'}
        foreach ($module in $modules) {if ($module -cnotin @((Join-Path $Root 'runtime/bin/server/jvm.dll'),(Join-Path $Root 'runtime/bin/jli.dll'))) {throw 'COLD_UI_MODULES'}}
        if ($Client -ceq 'web') {
            Assert-ColdKeys $Ui.witness @('kind','port','status','bodySha256','owningProcess')
            if ($Ui.witness.kind -cne 'owned-http' -or -not (Test-ColdInteger $Ui.witness.port 1) -or $Ui.witness.port -gt 65535 -or
                -not (Test-ColdInteger $Ui.witness.status 1) -or $Ui.witness.status -ne 200 -or
                -not (Test-ColdInteger $Ui.witness.owningProcess 1) -or $Ui.witness.owningProcess -ne $Ui.pid -or $Ui.witness.bodySha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'COLD_UI_HTTP'}
        } else {
            Assert-ColdKeys $Ui.witness @('kind','handle','title')
            if ($Ui.witness.kind -cne 'native-window' -or -not (Test-ColdInteger $Ui.witness.handle 1) -or
                $Ui.witness.title -isnot [string] -or $Ui.witness.title -cnotlike 'CashPrediction - *') {throw 'COLD_UI_WINDOW'}
        }
        $argv=@(ConvertFrom-ColdCommandLine $Ui.commandLine)
        if ($argv.Count -lt 1 -or $argv[0] -cne $Ui.executablePath -or -not (Test-ColdInventoryEqual @($argv | Select-Object -Skip 1) @($Ui.args))) {throw 'COLD_UI_COMMAND'}
    } catch {Write-Verbose ('Cold UI guard: '+$_.Exception.Message);throw 'COLD_REPORT_LAUNCH_IDENTITY'}
}

# Копии только в собственном UUID непосредственно внутри Temp; опасный path запрещён до копирования.
function Assert-ColdOwnedRun([string]$Run,[string]$TempRoot) {
    $resolved=Resolve-PortableSafetyPath $Run
    if (-not [IO.Path]::GetDirectoryName($resolved).Equals((Resolve-PortableSafetyPath $TempRoot),[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -cnotmatch '^run-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'COLD_OWNED_RUN' }
    return $resolved
}

# Полная проверка files/readonly и отдельная формула tree-v1, независимо от update-tool verify.
function Get-ColdManagedInventory([string]$Root) {
    Assert-PortableTreeHasNoLinks $Root
    $files=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($top in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app','runtime') {
        $path=Join-Path $Root $top
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $entries=if (Test-Path -LiteralPath $path -PathType Container) { @(Get-ChildItem -LiteralPath $path -File -Force -Recurse) } else { @(Get-Item -LiteralPath $path) }
        foreach ($file in $entries) {
            $relative=$file.FullName.Substring($Root.Length+1).Replace('\','/')
            $key=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($relative))
            $files.Add($key,[pscustomobject][ordered]@{path=$relative;sizeBytes=[long]$file.Length;
                sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant();readOnly=[bool](($file.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0)})
        }
    }
    return @($files.Values)
}

# Двоичные поля фиксированного big-endian формата; запись на диск для хеша не нужна.
function Get-ColdTreeHash($Files) {
    $hash=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        $hash.AppendData([Text.Encoding]::UTF8.GetBytes('cashprediction-tree-v1'+[char]0))
        foreach ($file in @($Files)) {
            $bytes=[Text.Encoding]::UTF8.GetBytes($file.path)
            $length=[BitConverter]::GetBytes([int]$bytes.Length); $size=[BitConverter]::GetBytes([long]$file.sizeBytes)
            if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($length); [Array]::Reverse($size) }
            $hash.AppendData($length); $hash.AppendData($bytes); $hash.AppendData($size)
            $hash.AppendData([Convert]::FromHexString($file.sha256)); $hash.AppendData([byte[]]@([byte][bool]$file.readOnly))
        }
        return [Convert]::ToHexString($hash.GetHashAndReset()).ToLowerInvariant()
    } finally { $hash.Dispose() }
}

# Проверяется полный набор файлов, а не только известные launcher и cfg.
function Assert-ColdTree([string]$Root,$Manifest) {
    $actual=@(Get-ColdManagedInventory $Root)
    if ((Get-ColdTreeHash $actual) -cne $Manifest.treeSha256 -or $actual.Count -ne @($Manifest.files).Count) { throw 'COLD_MANAGED_TREE' }
    for ($i=0;$i -lt $actual.Count;$i++) {
        $expected=@($Manifest.files)[$i]; $file=$actual[$i]
        if ($file.path -cne $expected.path -or $file.sizeBytes -ne $expected.sizeBytes -or
            $file.sha256 -cne $expected.sha256 -or $file.readOnly -ne $expected.readOnly) {throw 'COLD_MANAGED_TREE'}
    }
    return $actual
}

# Пользовательские данные и unmanaged корень защищены; session файлы учитываются отдельным controlled baseline.
function Get-ColdUserInventory([string]$Root) {
    Assert-PortableTreeHasNoLinks $Root
    $files=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    $controlled=@('CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md',
        'CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md','CashMemory/lock.md')
    foreach ($entry in Get-ChildItem -LiteralPath $Root -Force -Recurse) {
        $relative=$entry.FullName.Substring($Root.Length+1).Replace('\','/')
        if ($relative -in @('app','runtime','CashMemory/Updates') -or $relative.StartsWith('app/') -or $relative.StartsWith('runtime/') -or
            $relative.StartsWith('CashMemory/Updates/') -or $relative -in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') -or
            $relative -in $controlled) { continue }
        $files.Add($relative,[pscustomobject][ordered]@{path=$relative;directory=[bool]$entry.PSIsContainer;
            sizeBytes=$(if ($entry.PSIsContainer) {0L} else {[long]$entry.Length});
            sha256=$(if ($entry.PSIsContainer) {'directory'} else {(Get-FileHash -LiteralPath $entry.FullName).Hash.ToLowerInvariant()});
            readOnly=[bool](($entry.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0)})
    }
    return @($files.Values)
}

# Создаёт настоящие настройки через API установленного ядра до пользовательского baseline.
# Иначе обычный startup впервые записывает settings.md и ошибочно выглядит как порча данных updater.
function Initialize-ColdSettings([string]$Root,[string]$Java,[string]$Evidence) {
    $version=Get-ColdVersion $Root
    $coreJar=Join-Path $Root ('app/'+$version.jar)
    # java.exe передаёт командную строку через системную кодовую страницу Windows.
    # Служебная копия JAR имеет ASCII-путь; настоящий Unicode-корень передаётся Base64.
    # Пины исходной и служебной копий проверяются, native exe остаётся в исходном пути.
    $settingsCoreJar=Join-Path $Evidence 'settings-core.jar'
    if (Test-Path -LiteralPath $settingsCoreJar) {throw 'COLD_SETTINGS_OUTPUT'}
    Copy-Item -LiteralPath $coreJar -Destination $settingsCoreJar
    if ((Get-FileHash -LiteralPath $settingsCoreJar).Hash.ToLowerInvariant() -cne $version.jarSha256) {
        throw 'COLD_SETTINGS_PREPARATION'
    }
    $source=Join-Path $Evidence 'ColdSettings.java'
    $code=@'
/** Готовит настройки обычного открытия плана без запуска интерфейса. */
final class ColdSettings {
    /** Сохраняет настройки штатным писателем и проверяет их обратное чтение. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("COLD_SETTINGS_ARGUMENTS");
        var root = java.nio.file.Path.of(new String(java.util.Base64.getDecoder().decode(args[0]),
                java.nio.charset.StandardCharsets.UTF_8));
        var home = root.resolve("CashMemory");
        var plan = home.resolve("protected-plan.md");
        var file = home.resolve("settings.md");
        if (!java.nio.file.Files.isRegularFile(plan) || java.nio.file.Files.exists(file))
            throw new java.io.IOException("COLD_SETTINGS_OUTPUT");
        var settings = ru.cashprediction.core.document.AppSettings.defaults().withPlanOpened(plan.toString());
        ru.cashprediction.core.markdown.SettingsMarkdown.save(file, settings);
        if (!ru.cashprediction.core.markdown.SettingsMarkdown.load(file).equals(settings))
            throw new java.io.IOException("COLD_SETTINGS_ROUNDTRIP");
        System.out.println("COLD_SETTINGS_PREPARED");
    }
}
'@
    [IO.File]::WriteAllText($source,$code,[Text.UTF8Encoding]::new($false))
    $info=[Diagnostics.ProcessStartInfo]::new($Java)
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WorkingDirectory=$Root
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach ($argument in @('-XX:-UsePerfData','--module-path',$settingsCoreJar,'--add-modules','ru.cashprediction.core',
        $source,[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Root)))) {$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($info)
    try {
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {$process.Kill();[void]$process.WaitForExit(5000);throw 'COLD_SETTINGS_TIMEOUT'}
        $out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $Evidence 'settings.stdout.txt'),$out,[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $Evidence 'settings.stderr.txt'),$err,[Text.UTF8Encoding]::new($false))
        if ($process.ExitCode -ne 0 -or $out.Trim() -cne 'COLD_SETTINGS_PREPARED' -or
            (Get-FileHash -LiteralPath $coreJar).Hash.ToLowerInvariant() -cne $version.jarSha256 -or
            (Get-FileHash -LiteralPath $settingsCoreJar).Hash.ToLowerInvariant() -cne $version.jarSha256) {throw 'COLD_SETTINGS_PREPARATION'}
        Write-ColdJson (Join-Path $Evidence 'settings-preparation.json') ([ordered]@{
            actualExit=$process.ExitCode;coreJar=$coreJar;coreJarSha256=$version.jarSha256;
            settingsCoreJar=$settingsCoreJar;
            sourceSha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();
            settingsSha256=(Get-FileHash -LiteralPath (Join-Path $Root 'CashMemory/settings.md')).Hash.ToLowerInvariant()})
    } finally {$process.Dispose()}
}

# Разрешённые файлы текущего сеанса не скрываются: отдельная before/after квитанция с точными bytes.
function Get-ColdControlledInventory([string]$Root) {
    $result=@()
    foreach ($relative in 'CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md',
        'CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md','CashMemory/lock.md') {
        $path=Join-Path $Root $relative
        if (Test-Path -LiteralPath $path) {
            $item=Get-Item -LiteralPath (Resolve-PortableSafetyPath $path)
            if ($item.PSIsContainer) {throw 'COLD_CONTROLLED_FILE_TYPE'}
            if ($item.Length -gt 8388608) {throw 'COLD_CONTROLLED_LIMIT'}
            $bytes=[IO.File]::ReadAllBytes($item.FullName)
            $result+=@([pscustomobject][ordered]@{path=$relative;sizeBytes=[long]$bytes.Length;
                sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();contentBase64=[Convert]::ToBase64String($bytes);
                readOnly=[bool](($item.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0)})
        }
    }
    return $result
}

# Короткий заголовок PE/JImage служит ранним отказом; PASS всё равно требует реального cold процесса.
function Assert-ColdNativeImage([string]$Root) {
    if (Test-Path -LiteralPath (Join-Path $Root 'app/mods')) {throw 'COLD_IMAGE_MODULE_LAYOUT'}
    foreach ($relative in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll') {
        $stream=[IO.File]::OpenRead((Join-Path $Root $relative))
        try {
            $reader=[IO.BinaryReader]::new($stream)
            if ($stream.Length -lt 512 -or $reader.ReadUInt16() -ne 0x5a4d) { throw 'COLD_NOT_NATIVE_PE' }
            $stream.Position=0x3c; $offset=$reader.ReadUInt32()
            if ($offset -gt $stream.Length-4) { throw 'COLD_NOT_NATIVE_PE' }
            $stream.Position=$offset
            if ($reader.ReadUInt32() -ne 0x00004550) { throw 'COLD_NOT_NATIVE_PE' }
        } finally { $stream.Dispose() }
    }
    $stream=[IO.File]::OpenRead((Join-Path $Root 'runtime/lib/modules'))
    try { $reader=[IO.BinaryReader]::new($stream); if ($reader.ReadUInt32() -ne 0xcafedadaL) { throw 'COLD_NOT_JIMAGE' } }
    finally { $stream.Dispose() }
    foreach ($cfg in 'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/.jpackage.xml') {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $cfg) -PathType Leaf)) { throw 'COLD_IMAGE_LAYOUT' }
    }
}

# Версия читается из настоящего core jar; её bytes уже входят в полный tree digest.
function Get-ColdVersion([string]$Root) {
    $versions=@()
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $Root 'app') -Filter '*.jar' -File) {
        $archive=[IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $entry=$archive.GetEntry('ru/cashprediction/core/app.properties')
            if ($null -ne $entry) {
                if ($entry.Length -gt 4096) { throw 'COLD_VERSION_LIMIT' }
                $reader=[IO.StreamReader]::new($entry.Open())
                try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
                $release=[regex]::Match($text,'(?m)^release=([0-9]+)\s*$')
                $commit=[regex]::Match($text,'(?m)^commit=([0-9a-f]{40})\s*$')
                if (-not $release.Success -or -not $commit.Success -or [int]$release.Groups[1].Value -le 0) { throw 'COLD_DEV_BUILD' }
                $versions+=@([pscustomobject]@{releaseNumber=[int]$release.Groups[1].Value;commitSha=$commit.Groups[1].Value;
                    jar=$jar.Name;jarSha256=(Get-FileHash -LiteralPath $jar.FullName).Hash.ToLowerInvariant()})
            }
        } finally { $archive.Dispose() }
    }
    if ($versions.Count -ne 1) { throw 'COLD_VERSION_RESOURCE_MISSING' }
    return $versions[0]
}

# Тот же ограниченный payload, что PortableBootstrap.protectedPayload, без app/mods и произвольных JAR.
function Test-ColdProtectedPayload([string]$Path) {
    return ($Path.StartsWith('runtime/') -or $Path -cmatch '^app/cashprediction-(core|ui-fx|ui-swing|web)-[A-Za-z0-9_.-]+\.jar$')
}

# Пара аргументов совпадает с PortableBootstrap.externalModules; legacy runtime-only cfg остаётся читаемым.
function Test-ColdExternalModules([string]$Text) {
    $section=''; $awaitingPath=$false; $paths=0
    foreach ($line in [regex]::Split($Text,'\r?\n')) {
        if ($line.StartsWith('[')) {$section=$line}
        if ($awaitingPath) {
            if ($section -cne '[JavaOptions]' -or $line -cne 'java-options=$APPDIR') {throw 'COLD_CFG_MODULE_PATH'}
            $awaitingPath=$false
            continue
        }
        if ($section -cne '[JavaOptions]') {continue}
        if ($line -ceq 'java-options=--module-path') {
            $paths++
            if ($paths -ne 1) {throw 'COLD_CFG_MODULE_PATH'}
            $awaitingPath=$true
        } elseif ($line.StartsWith('java-options=--module-path=') -or $line.StartsWith('java-options=-p') -or
            $line -ceq 'java-options=$APPDIR') {throw 'COLD_CFG_MODULE_PATH'}
    }
    if ($awaitingPath) {throw 'COLD_CFG_MODULE_PATH'}
    return ($paths -eq 1)
}

# Замороженная семантика cfg: тот же mainmodule и только локальная пара module-path / APPDIR.
function Get-ColdMainModule([string]$Text) {
    if ([Text.Encoding]::UTF8.GetByteCount($Text) -gt 65536 -or $Text.Contains([char]0) -or $Text.Contains([char]0xfeff) -or
        $Text.Replace("`r`n","`n").Contains("`r")) {throw 'COLD_CFG_ENCODING'}
    $section=''; $application=0; $module=$null; $runtimeCount=0
    foreach ($line in [regex]::Split($Text,'\r?\n')) {
        if ($line.StartsWith('[')) {$section=$line; if ($line -ceq '[Application]') {$application++}}
        if ($section -cne '[Application]') {continue}
        if ($line.StartsWith('app.mainmodule=')) {
            if ($null -ne $module) {throw 'COLD_CFG_DUPLICATE'}
            $module=$line.Substring(15)
        } elseif ($line.StartsWith('app.runtime=')) {
            $runtimeCount++
            if ($runtimeCount -gt 1 -or $line -cnotin @('app.runtime=$ROOTDIR/runtime','app.runtime=$ROOTDIR\runtime')) {throw 'COLD_CFG_EXTERNAL_RUNTIME'}
        } elseif ($line.StartsWith('app.') -and -not $line.StartsWith('app.version=')) {throw 'COLD_CFG_EXTERNAL_APPLICATION'}
    }
    if ($application -ne 1 -or $null -eq $module -or $module -cnotmatch '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') {throw 'COLD_CFG_MODULE'}
    [void](Test-ColdExternalModules $Text)
    return $module
}

# Чтение cfg ограничено 65536 bytes даже при росте файла; BOM и неверный UTF-8 не нормализуются молча.
function Read-ColdConfig([string]$Path) {
    $resolved=Resolve-PortableSafetyPath $Path
    $stream=[IO.File]::OpenRead($resolved)
    try {
        $buffer=[byte[]]::new(65537); $length=0
        while ($length -lt $buffer.Length) {
            $count=$stream.Read($buffer,$length,$buffer.Length-$length)
            if ($count -eq 0) {break}
            $length+=$count
        }
        if ($length -gt 65536) {throw 'COLD_CFG_ENCODING'}
        try {$text=[Text.UTF8Encoding]::new($false,$true).GetString($buffer,0,$length)}
        catch [Text.DecoderFallbackException] {throw 'COLD_CFG_ENCODING'}
        if ($text.Contains([char]0xfeff)) {throw 'COLD_CFG_ENCODING'}
        return $text
    } finally {$stream.Dispose()}
}

# Новый журнал точно повторяет schema2/PortableBootstrap.plan в пределах собственной копии.
function New-ColdJournal([string]$Root,$Base,$Target) {
    $redirects=@(); $texts=@()
    Assert-ColdImageInventory $Base.files $Base.treeSha256
    Assert-ColdImageInventory $Target.files $Target.treeSha256
    foreach ($path in 'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg') {
        $original=Read-ColdConfig (Join-Path $Root $path)
        $targetText=Read-ColdConfig (Join-Path (Join-Path $Root 'CashMemory/Updates/Ready/tree') $path)
        if ((Get-ColdMainModule $original) -cne (Get-ColdMainModule $targetText)) {throw 'COLD_CFG_TARGET_MODULE'}
        # Native кандидат имеет ровно четыре корневых модуля; manifest проверяется до запуска helper.
        if (-not (Test-ColdExternalModules $original) -or -not (Test-ColdExternalModules $targetText)) {throw 'COLD_CFG_MODULE_PATH'}
        $section=''; $lines=[Collections.Generic.List[string]]::new(); $modules=0; $applications=0
        foreach ($line in [regex]::Split($original,'\r?\n')) {
            if ($line.StartsWith('[')) {$section=$line}
            if ($line -ceq '[Application]') {$applications++}
            if ($section -ceq '[Application]' -and $line.StartsWith('app.mainmodule=')) {$modules++}
            if ($section -ceq '[Application]' -and $line.StartsWith('app.runtime=')) {
                if ($line -cnotin @('app.runtime=$ROOTDIR/runtime','app.runtime=$ROOTDIR\runtime')) {throw 'COLD_CFG_EXTERNAL_RUNTIME'}
                continue
            }
            if ($section -ceq '[JavaOptions]' -and $line -ceq 'java-options=$APPDIR') {
                $lines.Add('java-options=$ROOTDIR\CashMemory\Updates\Bootstrap\app')
            } else {$lines.Add($line)}
            if ($line -ceq '[Application]') {$lines.Add('app.runtime=$ROOTDIR\CashMemory\Updates\Bootstrap\runtime')}
        }
        if ($applications -ne 1 -or $modules -ne 1) {throw 'COLD_CFG_APPLICATION'}
        $newline=if ($original.Contains("`r`n")) {"`r`n"} else {"`n"}; $text=$lines -join $newline
        if ([Text.Encoding]::UTF8.GetByteCount($text) -gt 65536) {throw 'COLD_CFG_ENCODING'}
        $bytes=[Text.Encoding]::UTF8.GetBytes($text); $old=@($Base.files | Where-Object {$_.path -ceq $path})
        if ($old.Count -ne 1) {throw 'COLD_CFG_INVENTORY'}
        $redirects+=@([pscustomobject][ordered]@{path=$path;sizeBytes=[long]$bytes.Length;
            sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();readOnly=$old[0].readOnly})
        $texts+=@([ordered]@{path=$path;text=$text})
    }
    # UpdateValidation требует unsigned UTF-8 порядок; hex-ключи сравниваются только ordinal.
    $orderedRedirects=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($entry in $redirects) {$orderedRedirects.Add([Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($entry.path)),$entry)}
    $redirects=@($orderedRedirects.Values)
    return [ordered]@{schemaVersion=2;installationRoot=$Root;transactionId=[guid]::NewGuid().ToString();target=$Target;
        phase='PREPARED';oldFiles=@($Base.files);oldTreeSha256=$Base.treeSha256;operations=@();outcome='PENDING';
        bootstrap=[ordered]@{schemaVersion=1;state='INITIAL';publishState='NONE';redirectFiles=$redirects;cfgTexts=$texts}}
}

# Существующий helper не переписывается: hook вставляется в отдельный закреплённый test-only файл.
function New-ColdInstrumentedHelper([string]$Original) {
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($Original,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'COLD_HELPER_PARSE'}
    $insertions=@()
    foreach ($pair in @(@('Save','SAVE'),@('PhaseFault','PHASE'),@('Boundary','BOUNDARY'))) {
        $name=$pair[0]; $hook=$pair[1]
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw 'COLD_HELPER_HOOK_MISSING'}
        $offset=if ($name -eq 'PhaseFault') {$nodes[0].Body.Extent.StartOffset+1} else {$nodes[0].Body.Extent.EndOffset-1}
        $insertions+=@([pscustomobject]@{Offset=$offset;Text="`n ColdCheckpoint '$hook'`n"})
    }
    $guards=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Guard'},$true))
    if ($guards.Count -ne 1 -or $Original -notmatch 'function PrepareBootstrap' -or $Original -notmatch 'function PortableRollback' -or
        $Original -notmatch 'function BootstrapPayload' -or $Original -notmatch 'function VerifyBootstrapRuntime') {throw 'COLD_NOT_BOOTSTRAP_HELPER'}
    $checkpointFunction=@'
function ColdCheckpoint([string]$hook) {
    if ($null -eq $script:journal) { return }
    $control=ReadJson (Join-Path $updates 'cold-control.json') 16384
    $wanted=$control.checkpoint
    $b=$script:journal.bootstrap
    $last=$null
    if (@($script:journal.operations).Count) {$last=@($script:journal.operations)[-1]}
    $match=$false
    if ($hook -ceq 'PHASE') {
        $match=($wanted -ceq $script:journal.phase -or ($wanted -ceq 'INITIAL' -and $script:journal.phase -ceq 'PREPARED'))
    } elseif ($hook -ceq 'SAVE') {
        $match=($wanted -ceq $b.state -or ($wanted -ceq ('PUBLISH_'+$b.publishState) -and $b.state -ceq 'COPYING'))
    } elseif ($hook -ceq 'BOUNDARY' -and $null -ne $last -and $last.state -ceq 'BEFORE') {
        $path=switch -CaseSensitive ($wanted) {
            'JLI_GAP' {'runtime/bin/jli.dll'} 'JVM_GAP' {'runtime/bin/server/jvm.dll'} 'MODULES_GAP' {'runtime/lib/modules'}
            'NATIVE_FX_REPLACE' {'CashPrediction.exe'} 'NATIVE_SWING_REPLACE' {'CashPrediction-Swing.exe'}
            'NATIVE_WEB_REPLACE' {'CashPrediction-Web.exe'} 'CFG_FX_SWITCH' {'app/CashPrediction.cfg'}
            'CFG_SWING_SWITCH' {'app/CashPrediction-Swing.cfg'} 'CFG_WEB_SWITCH' {'app/CashPrediction-Web.cfg'} default {''}
        }
        if ($path -and $last.path -ceq $path) {
            $destination=ResolveManaged $root $path
            if ($wanted.EndsWith('_GAP')) {
                $match=($last.kind -ceq 'BACKUP' -and -not [IO.File]::Exists($destination) -and
                    [IO.File]::Exists((ResolveManaged (Join-Path $updates 'Backup') $path)))
            } else {
                $kind=if ($wanted -like 'NATIVE_*_REPLACE') {'REPLACE'} else {'CFG_SWITCH'}
                $entry=@($script:journal.target.files | Where-Object {$_.path -ceq $path})[0]
                $match=($last.kind -ceq $kind -and $script:step % 2 -eq 0 -and (Content $destination $entry))
            }
        }
    }
    if (-not $match) {return}
    # Файлы квитанции берутся из oldFiles только после фактической exact hash/size/attribute проверки копии.
    $protectedFiles=@(); $protectedVerified=$false
    $bootstrapDirectory=BootstrapPath 'full'
    if ([IO.Directory]::Exists($bootstrapDirectory)) {
        VerifyBootstrapRuntime $bootstrapDirectory
        $protectedFiles=@($script:journal.oldFiles | Where-Object {BootstrapPayload $_.path})
        $protectedVerified=$true
    }
    $process=[Diagnostics.Process]::GetCurrentProcess()
    try {
        # Дочерний Windows PowerShell не обязан иметь Utility в PSModulePath.
        # Хеш тех же durable bytes вычисляется без автозагрузки внешнего cmdlet.
        $journalStream=[IO.File]::OpenRead($journalPath)
        $journalHasher=[Security.Cryptography.SHA256]::Create()
        try {$journalDigest=[BitConverter]::ToString($journalHasher.ComputeHash($journalStream)).Replace('-','').ToLowerInvariant()}
        finally {$journalStream.Dispose();$journalHasher.Dispose()}
        $receipt=[ordered]@{schemaVersion=1;checkpoint=$wanted;installationRoot=$root;transactionId=$script:journal.transactionId;
            pid=[long]$PID;startedAtTicks=$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.MainModule.FileName;
            hook=$hook;phase=$script:journal.phase;bootstrapState=$b.state;publishState=$b.publishState;operation=$last;
            journalSha256=$journalDigest;
            bootstrapVerified=$protectedVerified;bootstrapFiles=$protectedFiles;bootstrapTreeSha256=(InventoryDigest $protectedFiles)}
        AtomicJson (Join-Path $updates 'cold-checkpoint.json') $receipt
    } finally {$process.Dispose()}
    $event=[Threading.EventWaitHandle]::new($false,[Threading.EventResetMode]::ManualReset,('Local\cp-cold-'+$control.nonce))
    try {if (-not $event.WaitOne(180000)) {throw 'COLD_CHECKPOINT_NOT_KILLED'}} finally {$event.Dispose()}
    throw 'COLD_CHECKPOINT_RELEASED_WITHOUT_KILL'
}

'@
    $insertions+=@([pscustomobject]@{Offset=$guards[0].Extent.StartOffset;Text=$checkpointFunction})
    foreach ($insertion in $insertions | Sort-Object Offset -Descending) {$Original=$Original.Insert($insertion.Offset,$insertion.Text)}
    [void][Management.Automation.Language.Parser]::ParseInput($Original,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'COLD_INSTRUMENTED_PARSE'}
    return $Original
}

# Квитанция относится только к retained Process, а не повторному поиску числового PID.
function Get-ColdProcessReceipt($Process,[string]$Root) {
    if ($Process -isnot [Diagnostics.Process]) {throw 'COLD_PROCESS_TYPE'}
    # Путь берётся из того же удерживаемого handle даже после exit. StartInfo изменяем
    # и не доказывает executable ни собственного, ни открытого по PID процесса.
    if ($null -eq ('CashPrediction.ColdProcessImage' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
namespace CashPrediction {
    // Чтение образа процесса по удерживаемому дескриптору, без повторного поиска PID.
    public static class ColdProcessImage {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool QueryFullProcessImageNameW(
            SafeProcessHandle process, uint flags, StringBuilder path, ref uint size);
        [DllImport("ntdll.dll")]
        private static extern int NtQueryInformationProcess(
            SafeProcessHandle process, int informationClass, IntPtr buffer, int size, out int returned);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint QueryDosDeviceW(string device, StringBuilder target, int size);
        [StructLayout(LayoutKind.Sequential)]
        private struct UnicodeString {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }
        // Ошибка чтения запрещает квитанцию; произвольной подстановки пути нет.
        public static string Read(SafeProcessHandle process) {
            var path = new StringBuilder(32768);
            uint size = (uint)path.Capacity;
            if (QueryFullProcessImageNameW(process, 0, path, ref size)) return path.ToString();
            int error = Marshal.GetLastWin32Error();
            // После exit Win32-запрос может отказать. ProcessImageFileName (27)
            // читает сохранённое ядром имя именно по прежнему handle, без поиска PID.
            var buffer = Marshal.AllocHGlobal(65536);
            try {
                int returned;
                if (NtQueryInformationProcess(process, 27, buffer, 65536, out returned) != 0)
                    throw new System.ComponentModel.Win32Exception(error);
                var name = Marshal.PtrToStructure<UnicodeString>(buffer);
                string native = Marshal.PtrToStringUni(name.Buffer, name.Length / 2);
                // DOS-путь восстанавливается только через текущую таблицу устройств ОС.
                // Неизвестный том запрещает квитанцию, StartInfo не используется.
                foreach (string drive in Directory.GetLogicalDrives()) {
                    var target = new StringBuilder(32768);
                    if (QueryDosDeviceW(drive.Substring(0, 2), target, target.Capacity) == 0) continue;
                    string prefix = target.ToString();
                    if (native.StartsWith(prefix + "\\", StringComparison.OrdinalIgnoreCase))
                        return drive.Substring(0, 2) + native.Substring(prefix.Length);
                }
                throw new System.ComponentModel.Win32Exception(error);
            } finally { Marshal.FreeHGlobal(buffer); }
        }
    }
}
'@
    }
    [void]$Process.Handle
    $processId=$Process.Id; $startedAtTicks=$Process.StartTime.ToUniversalTime().Ticks
    $executable=[CashPrediction.ColdProcessImage]::Read($Process.SafeHandle)
    if ($processId -lt 1 -or $startedAtTicks -lt 1 -or -not [IO.Path]::IsPathFullyQualified($executable)) {
        throw 'COLD_PROCESS_IDENTITY'
    }
    return [pscustomobject]@{ProcessId=$processId;StartedAtTicks=$startedAtTicks;
        ExecutablePath=$executable;OwnedRoot=$Root}
}

# Проверка личности непосредственно перед Kill применяется и при аварийном cleanup.
function Stop-ColdRetainedProcess($Process,$Identity,[string]$Root) {
    if ($Process.HasExited) {return}
    $actual=Get-ColdProcessReceipt $Process $Root
    Assert-ColdProcessIdentity $Identity $actual $Root $Identity.ExecutablePath
    try {
        $Process.Kill()
        if (-not $Process.WaitForExit(5000)) {throw 'COLD_RETAINED_ALIVE'}
    } catch {
        # Завершение именно удерживаемого процесса между проверкой и Kill не является отказом.
        # Живой handle с любой ошибкой по-прежнему запрещает успешный cleanup.
        if (-not $Process.HasExited) {throw}
    }
}

# Отдельный ProcessStartInfo не меняет окружение runner и наследуется штатным restart.
function Start-ColdProcess([string]$Executable,[string[]]$Arguments,[string]$Root,[string]$RegistryNode='',
    [string]$DiagnosticsDirectory='') {
    $info=[Diagnostics.ProcessStartInfo]::new($Executable); $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.WorkingDirectory=$Root
    foreach ($argument in $Arguments) {$info.ArgumentList.Add($argument)}
    if ($RegistryNode) {
        [void](Get-PortableRegistryPath $RegistryNode)
        $info.Environment['JAVA_TOOL_OPTIONS']='-Dcashprediction.registry.node='+$RegistryNode+' -Djava.net.useSystemProxies=false -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1'
    }
    $capture=$null
    if ($DiagnosticsDirectory) {
        $capture=New-ColdHelperCapture $DiagnosticsDirectory
        $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info; $started=$false
    try {
        if (-not $process.Start()) {throw 'COLD_START_FAILED'}
        $started=$true
        if ($null -ne $capture) {
            # Оба pipe читаются сразу, до получения личности и ожидания checkpoint.
            $capture.stdoutTask=[CashPrediction.ColdHelperPump]::Drain($process.StandardOutput.BaseStream,$capture.stdoutStream,$capture.limitBytes,$capture.cancel.Token)
            $capture.stderrTask=[CashPrediction.ColdHelperPump]::Drain($process.StandardError.BaseStream,$capture.stderrStream,$capture.limitBytes,$capture.cancel.Token)
            $process | Add-Member -NotePropertyName ColdHelperCapture -NotePropertyValue $capture
        }
    } catch {
        try {
            if ($started) {
                $identity=Get-ColdProcessReceipt $process $Root
                Assert-ColdProcessIdentity $identity $identity $Root $Executable
                Stop-ColdRetainedProcess $process $identity $Root
            }
        } finally {
            if ($null -ne $capture) {$capture.cancel.Cancel();$capture.stdoutStream.Dispose();$capture.stderrStream.Dispose();$capture.cancel.Dispose()}
            $process.Dispose()
        }
        throw
    }
    return $process
}

# Только собственный каталог evidence/fixtures в Temp допускает новые диагностические файлы.
function New-ColdHelperCapture([string]$Directory) {
    $directory=Resolve-PortableSafetyPath $Directory
    $owner=$directory
    if ([IO.Path]::GetFileName($directory) -cmatch '^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {$owner=[IO.Path]::GetDirectoryName($directory)}
    if ([IO.Path]::GetFileName($owner) -cnotmatch '^cp-bootstrap-(?:evidence|fixtures)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
        -not [IO.Path]::GetDirectoryName($owner).Equals((Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())),[StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $directory -PathType Container)) {throw 'COLD_DIAGNOSTICS_SCOPE'}
    Assert-PortableTreeHasNoLinks $owner
    if ($null -eq ('CashPrediction.ColdHelperPump' -as [type])) {
        Add-Type -TypeDefinition @'
using System.IO;
using System.Threading;
using System.Threading.Tasks;
namespace CashPrediction {
    // Размер файлов ограничен; лишние bytes продолжают читаться, освобождая pipe.
    public static class ColdHelperPump {
        // Отдельная async-задача для каждого потока исключает взаимную блокировку.
        public static async Task<long> Drain(Stream source, FileStream destination, int limit, CancellationToken cancel) {
            long total = 0;
            int stored = 0;
            var buffer = new byte[4096];
            try {
                int count;
                while ((count = await source.ReadAsync(buffer, 0, buffer.Length, cancel).ConfigureAwait(false)) != 0) {
                    total += count;
                    int keep = System.Math.Min(count, limit - stored);
                    if (keep > 0) {
                        await destination.WriteAsync(buffer, 0, keep, cancel).ConfigureAwait(false);
                        await destination.FlushAsync(cancel).ConfigureAwait(false);
                        stored += keep;
                    }
                }
                return total;
            } finally { destination.Dispose(); }
        }
    }
}
'@
    }
    $stdout=Join-Path $directory 'helper.stdout.txt'; $stderr=Join-Path $directory 'helper.stderr.txt'
    $outStream=$null; $errStream=$null
    try {
        $outStream=[IO.FileStream]::new($stdout,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,$true)
        $errStream=[IO.FileStream]::new($stderr,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,$true)
        return [pscustomobject]@{directory=$directory;stdout=$stdout;stderr=$stderr;stdoutStream=$outStream;stderrStream=$errStream;
            stdoutTask=$null;stderrTask=$null;limitBytes=1048576;cancel=[Threading.CancellationTokenSource]::new()}
    } catch {
        if ($null -ne $outStream) {$outStream.Dispose()}; if ($null -ne $errStream) {$errStream.Dispose()}; throw
    }
}

# Вызывается после identity cleanup и сохраняет реальные bytes при успехе и отказе клетки.
function Save-ColdHelperDiagnostics($Process,$Identity,[string]$Root,[string]$Directory,[int]$DrainTimeoutMillis=5000) {
    if ($DrainTimeoutMillis -lt 1 -or $DrainTimeoutMillis -gt 5000) {throw 'COLD_DIAGNOSTICS_BOUND'}
    Assert-ColdProcessIdentity $Identity (Get-ColdProcessReceipt $Process $Root) $Root $Identity.ExecutablePath
    $capture=$Process.ColdHelperCapture
    if ($capture.directory -cne (Resolve-PortableSafetyPath $Directory)) {throw 'COLD_DIAGNOSTICS_SCOPE'}
    $failure=$null; $drained=$false
    try {
        if (-not $Process.HasExited) {throw 'COLD_DIAGNOSTICS_PROCESS_ALIVE'}
        $all=[Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($capture.stdoutTask,$capture.stderrTask))
        if (-not $all.Wait($DrainTimeoutMillis)) {throw 'COLD_DIAGNOSTICS_DRAIN_TIMEOUT'}
        $drained=$true
    } catch {$failure=$_.Exception.Message}
    finally {
        # Потомок может удерживать pipe: отмена не ждёт EOF и не ищет чужие PID.
        if (-not $drained) {$capture.cancel.Cancel()}
    }
    $details=[ordered]@{schemaVersion=1;identity=$Identity;actualExit=$(if ($Process.HasExited) {$Process.ExitCode} else {$null});
        actualDiagnostics=[ordered]@{stdout=$capture.stdout;stderr=$capture.stderr;format='raw-process-bytes';limitBytes=$capture.limitBytes;drained=$drained;
            stdoutTruncated=($capture.stdoutTask.IsCompletedSuccessfully -and $capture.stdoutTask.Result -gt $capture.limitBytes);
            stderrTruncated=($capture.stderrTask.IsCompletedSuccessfully -and $capture.stderrTask.Result -gt $capture.limitBytes);
            stdoutBytesRead=$(if ($capture.stdoutTask.IsCompletedSuccessfully) {$capture.stdoutTask.Result} else {$null});
            stderrBytesRead=$(if ($capture.stderrTask.IsCompletedSuccessfully) {$capture.stderrTask.Result} else {$null});failure=$failure}}
    $path=Join-Path $capture.directory 'helper-diagnostics.json'
    Write-ColdJson $path $details
    if ($drained) {$capture.cancel.Dispose()}
    if ($failure) {throw $failure}
    return $path
}

# Только штатный tool с именованными аргументами; ненулевой exit не превращается в skip.
function Invoke-ColdTool($Config,[string]$Java,[string[]]$Arguments,[string]$Evidence,[string]$Root) {
    $out=Join-Path $Evidence ('tool-'+[guid]::NewGuid().ToString()+'.out.txt'); $err=$out+'.err.txt'
    $info=[Diagnostics.ProcessStartInfo]::new($Java); $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.WorkingDirectory=$Root
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    # Внешний java.exe с ANSI native.encoding не должен искажать Unicode путей копии.
    $payload=[Convert]::ToBase64String([Text.UTF8Encoding]::new($false,$true).GetBytes(
        (ConvertTo-Json -InputObject $Arguments -Compress)))
    $effectiveArguments=@('-XX:-UsePerfData')+@($Config.toolArguments)+@('--arguments-base64',$payload)
    foreach ($arg in $effectiveArguments) {$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info; $identity=$null
    try {
        if (-not $process.Start()) {throw 'COLD_TOOL_START'}
        $identity=Get-ColdProcessReceipt $process $Root
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        $deadline=[DateTimeOffset]::UtcNow.AddSeconds(60)
        while (-not $process.WaitForExit(250)) {if ([DateTimeOffset]::UtcNow -ge $deadline) {throw 'COLD_TOOL_TIMEOUT'}}
        [IO.File]::WriteAllText($out,$stdout.GetAwaiter().GetResult()); [IO.File]::WriteAllText($err,$stderr.GetAwaiter().GetResult())
        $receipt=[pscustomobject]@{executable=$Java;args=@($Config.toolArguments)+$Arguments;
            effectiveArguments=$effectiveArguments;actualExit=$process.ExitCode;stdout=$out;stderr=$err}
        if ($process.ExitCode -ne 0) {throw "COLD_TOOL_FAILED $err"}
        return $receipt
    } finally {
        if ($null -ne $identity) {Stop-ColdRetainedProcess $process $identity $Root}
        $process.Dispose()
    }
}

# Окно или listener должны принадлежать живой JVM этой копии после данного cold старта.
function Get-ColdUiReceipt([string]$Root,[string]$Client,[datetime]$Started) {
    $observed=@(Get-CopyProcesses $Root | Where-Object {$_.CreationDate.ToUniversalTime() -ge $Started.ToUniversalTime()})
    foreach ($entry in $observed) {
        $retained=$null
        try {
            try {
                $retained=Open-PortableProcess ([int]$entry.ProcessId)
            } catch {
                # Между CIM-снимком и открытием дескриптора launcher/JVM может штатно выйти.
                # Пропускаем только подтверждённо исчезнувший PID; живой либо повторно
                # использованный PID не превращает ошибку доступа/принадлежности в успех.
                $cause=$_.Exception
                while ($null -ne $cause.InnerException) {$cause=$cause.InnerException}
                $remaining=@(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $entry.ProcessId})
                if (($cause -is [ArgumentException] -or $cause -is [InvalidOperationException]) -and
                    $remaining.Count -eq 0) {continue}
                throw
            }
            if ($retained.HasExited) {continue}
            $actualBirth=$retained.StartTime.ToUniversalTime().Ticks; $observedBirth=$entry.CreationDate.ToUniversalTime().Ticks
            if (($actualBirth-($actualBirth%10)) -ne ($observedBirth-($observedBirth%10))) {continue}
            $expectedExe=Join-Path $Root (Get-ColdLauncherName $Client)
            if ($entry.ExecutablePath -cne $expectedExe -or $retained.MainModule.FileName -cne $expectedExe -or $retained.Id -ne $entry.ProcessId) {continue}
            if ($Client -ne 'web') {
                if ($retained.MainWindowHandle -eq [intptr]::Zero -or $retained.MainWindowTitle -notlike 'CashPrediction - *') {continue}
                $witness=[pscustomobject]@{kind='native-window';handle=$retained.MainWindowHandle.ToInt64();title=$retained.MainWindowTitle}
            } else {
                $witness=$null
                foreach ($listener in @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {$_.OwningProcess -eq $entry.ProcessId -and $_.LocalAddress -in @('127.0.0.1','0.0.0.0','::')})) {
                    try {
                        $response=Invoke-WebRequest -Uri ('http://127.0.0.1:'+$listener.LocalPort+'/') -TimeoutSec 2 -UseBasicParsing
                        if ($response.StatusCode -eq 200 -and $response.Content -match '<title>[^<]*CashPrediction') {
                            $witness=[pscustomobject]@{kind='owned-http';port=(Convert-ColdNativePort $listener.LocalPort);status=200;owningProcess=[long]$retained.Id;bodySha256=[Convert]::ToHexString(
                                [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($response.Content))).ToLowerInvariant()};break
                        }
                    } catch {}
                }
                if ($null -eq $witness) {continue}
            }
            $modules=@($retained.Modules | Where-Object {$_.ModuleName -in @('jvm.dll','jli.dll')} | ForEach-Object {$_.FileName})
            if (@($modules | Where-Object {[IO.Path]::GetFileName($_) -ieq 'jvm.dll'}).Count -ne 1) {continue}
            foreach ($module in $modules) {if (-not (Test-PortablePathContains $Root $module)) {throw 'COLD_FOREIGN_JVM'}}
            $leases=Join-Path $Root 'CashMemory/Updates/processes'; $leaseMatches=@()
            if (Test-Path -LiteralPath $leases) {
                foreach ($leaseFile in Get-ChildItem -LiteralPath $leases -File -Filter '*.json') {
                    $lease=Get-Content -LiteralPath $leaseFile.FullName -Raw | ConvertFrom-Json
                    if ($lease.schemaVersion -eq 1 -and $lease.pid -eq $retained.Id -and $lease.client -ceq $Client -and
                        $lease.installationRoot -ceq $Root -and $lease.startedAtEpochMillis -eq
                        ([DateTimeOffset]$retained.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()) {$leaseMatches+=@($lease)}
                }
            }
            if ($leaseMatches.Count -ne 1) {continue}
            # Повторный CIM связывает command line с тем же удержанным дескриптором.
            $current=Get-ColdCurrentProcess $entry $retained $expectedExe
            $argv=@(ConvertFrom-ColdCommandLine $current.CommandLine)
            $receipt=[pscustomobject]@{pid=$retained.Id;startedAtTicks=$retained.StartTime.ToUniversalTime().Ticks;
                executablePath=$retained.MainModule.FileName;modules=$modules;witness=$witness;lease=$leaseMatches[0];
                commandLine=$current.CommandLine;args=@($argv | Select-Object -Skip 1);observedAt=[datetime]::UtcNow.ToString('o')}
            Assert-ColdUiReceipt $receipt $Root $Client
            return $receipt
        } finally {if ($null -ne $retained) {$retained.Dispose()}}
    }
    return $null
}

# Независимое повторное наблюдение сохраняет birth/path/PID перед UI и cleanup kill.
function Get-ColdCurrentProcess($Observed,$Retained,[string]$ExpectedExe,[switch]$AllowExited) {
    $current=@(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $Observed.ProcessId})
    try {
    if ($AllowExited -and $Retained.HasExited) {return $null}
    if ($current.Count -ne 1 -or $Retained.HasExited -or $Retained.Id -ne $Observed.ProcessId -or
        $Retained.MainModule.FileName -cne $ExpectedExe -or $current[0].ExecutablePath -cne $ExpectedExe -or
        $current[0].CommandLine -isnot [string] -or $current[0].CommandLine -cne $Observed.CommandLine) {throw 'COLD_PROCESS_IDENTITY'}
    $ticks=$Retained.StartTime.ToUniversalTime().Ticks
    foreach ($value in @($Observed.CreationDate,$current[0].CreationDate)) {
        $birth=$value.ToUniversalTime().Ticks
        if (($ticks-($ticks%10)) -ne ($birth-($birth%10))) {throw 'COLD_HELPER_PID_REUSED'}
    }
    return $current[0]
    } catch {
        # Cleanup и read-only observer могут пропустить exit удержанного дескриптора.
        # PID reuse, другой путь и ошибки живого процесса не превращаются в разрешение Kill.
        if ($AllowExited -and $Retained.HasExited) {return $null}
        throw
    }
}

# Команда helper допускает только точный PowerShellHelper.start, без дополнительных сценариев.
function Assert-ColdHelperCommand([string]$Line,[string]$PowerShell,[string]$Encoded) {
    $argv=@(ConvertFrom-ColdCommandLine $Line)
    if (-not (Test-ColdInventoryEqual $argv @($PowerShell,'-NoProfile','-NonInteractive','-WindowStyle','Hidden',
        '-ExecutionPolicy','Bypass','-EncodedCommand',$Encoded))) {throw 'COLD_PROCESS_IDENTITY'}
}

# Cleanup копии убивает только три собственных launcher после повторной проверки личности.
function Stop-ColdCopyProcesses([string]$Root) {
    if (-not (Test-PortablePathContains $WorkDir $Root) -or $Root -ceq $WorkDir) {throw 'COLD_PROCESS_IDENTITY'}
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(20)
    do {
        $observed=@(Get-CopyProcesses $Root)
        if (-not $observed.Count) {return}
        foreach ($entry in $observed) {
            if ($entry.ExecutablePath -cnotin @((Join-Path $Root 'CashPrediction.exe'),(Join-Path $Root 'CashPrediction-Swing.exe'),(Join-Path $Root 'CashPrediction-Web.exe'))) {throw 'COLD_PROCESS_IDENTITY'}
            $retained=$null
            try {
                # Win32_Process возвращает PID как UInt32; JSON receipts остаются Int32/Int64.
                if (-not (Test-ColdInteger $entry.ProcessId 1) -and
                    ($entry.ProcessId -isnot [uint32] -or $entry.ProcessId -eq 0)) {throw 'COLD_PROCESS_IDENTITY'}
                if ([long]$entry.ProcessId -gt [int]::MaxValue) {throw 'COLD_PROCESS_IDENTITY'}
                try {$retained=Open-PortableProcess ([int]$entry.ProcessId)} catch {
                    $cause=$_.Exception
                    while ($null -ne $cause.InnerException) {$cause=$cause.InnerException}
                    # Исчезновение до открытия подтверждается новым census. Reused PID не пропускается.
                    if (($cause -is [ArgumentException] -or $cause -is [InvalidOperationException]) -and
                        @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $entry.ProcessId}).Count -eq 0) {continue}
                    throw
                }
                if ($retained.HasExited) {continue}
                if ($null -eq (Get-ColdCurrentProcess $entry $retained $entry.ExecutablePath -AllowExited)) {continue}
                $identity=[pscustomobject]@{ProcessId=[int]$entry.ProcessId;StartedAtTicks=$retained.StartTime.ToUniversalTime().Ticks;
                    ExecutablePath=$entry.ExecutablePath;OwnedRoot=$Root}
                Stop-ColdRetainedProcess $retained $identity $Root
            } finally {if ($null -ne $retained) {$retained.Dispose()}}
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'COLD_COPY_PROCESSES_ALIVE'
}

# Автономные helper потомки узнаются по точному encoded command из PowerShellHelper.start и birth time.
function Stop-ColdRecoveryHelpers([string]$Root,[string]$PowerShell,[datetime]$Started) {
    [void](Resolve-PortableSafetyPath $Root)
    [void](Resolve-PortableSafetyPath $PowerShell)
    $path=Join-Path $Root 'CashMemory/Updates/apply-update.ps1'
    [void](Resolve-PortableSafetyPath $path)
    $command="& '"+$path.Replace("'","''")+"' -InstallationRoot '"+$Root.Replace("'","''")+"'"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10)
    do {
        $matches=@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and
            $_.ExecutablePath.Equals($PowerShell,[StringComparison]::OrdinalIgnoreCase) -and
            $_.CreationDate.ToUniversalTime() -ge $Started.ToUniversalTime() -and $_.CommandLine -match
            ('(?i)-EncodedCommand\s+"?'+[regex]::Escape($encoded)+'"?\s*$')})
        if (-not $matches.Count) {return}
        foreach ($observed in $matches) {
            $retained=$null
            try {
                # Принимается только дополнительный целочисленный тип настоящего CIM-снимка.
                if (-not (Test-ColdInteger $observed.ProcessId 1) -and
                    ($observed.ProcessId -isnot [uint32] -or $observed.ProcessId -eq 0)) {throw 'COLD_PROCESS_IDENTITY'}
                if ([long]$observed.ProcessId -gt [int]::MaxValue) {throw 'COLD_PROCESS_IDENTITY'}
                try {$retained=Open-PortableProcess ([int]$observed.ProcessId)} catch {
                    $cause=$_.Exception
                    while ($null -ne $cause.InnerException) {$cause=$cause.InnerException}
                    if (($cause -is [ArgumentException] -or $cause -is [InvalidOperationException]) -and
                        @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $observed.ProcessId}).Count -eq 0) {continue}
                    throw
                }
                if ($retained.HasExited) {continue}
                $actual=Get-ColdProcessReceipt $retained $Root
                $birth=$observed.CreationDate.ToUniversalTime().Ticks
                if (($actual.StartedAtTicks-($actual.StartedAtTicks%10)) -ne ($birth-($birth%10))) {throw 'COLD_HELPER_PID_REUSED'}
                if ($null -eq (Get-ColdCurrentProcess $observed $retained $PowerShell -AllowExited)) {continue}
                Assert-ColdHelperCommand $observed.CommandLine $PowerShell $encoded
                $expected=[pscustomobject]@{ProcessId=[int]$observed.ProcessId;StartedAtTicks=$actual.StartedAtTicks;ExecutablePath=$PowerShell;OwnedRoot=$Root}
                Stop-ColdRetainedProcess $retained $expected $Root
            } finally {if ($null -ne $retained) {$retained.Dispose()}}
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'COLD_RECOVERY_HELPERS_ALIVE'
}

# Ранний UI наблюдается независимо от lease: окно или listener запрещены при живом журнале.
function Get-ColdRecoveryObservation([string]$Root,[datetime]$Started) {
    $path=Join-Path $Root 'CashMemory/Updates/install-journal.json'
    $begin=[datetime]::UtcNow; $before=[bool](Test-Path -LiteralPath $path); $visible=0
    $listeners=$null
    foreach ($entry in @(Get-CopyProcesses $Root | Where-Object {$_.CreationDate.ToUniversalTime() -ge $Started.ToUniversalTime()})) {
        $retained=$null
        try {
            try {$retained=Open-PortableProcess ([int]$entry.ProcessId)} catch {
                # При handoff исходная JVM должна выйти; устаревший CIM-снимок не является сбоем.
                $cause=$_.Exception
                while ($null -ne $cause.InnerException) {$cause=$cause.InnerException}
                if (($cause -is [ArgumentException] -or $cause -is [InvalidOperationException]) -and
                    @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $entry.ProcessId}).Count -eq 0) {continue}
                throw
            }
            if ($retained.HasExited) {continue}
            $current=Get-ColdCurrentProcess $entry $retained $entry.ExecutablePath -AllowExited
            if ($null -eq $current -or $retained.HasExited) {continue}
            if (-not (Test-PortablePathContains $Root $retained.MainModule.FileName)) {throw 'COLD_PROCESS_IDENTITY'}
            if ($retained.MainWindowHandle -ne [intptr]::Zero) {$visible++}
            else {
                # Один системный snapshot на наблюдение вместо повторного CIM-запроса для каждой JVM.
                if ($null -eq $listeners) {$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop)}
                if (@($listeners | Where-Object {$_.OwningProcess -eq $entry.ProcessId}).Count) {$visible++}
            }
        } catch {
            # JVM исходного launcher штатно выходит во время recovery handoff.
            # Подтверждённый exit не даёт UI witness; ошибки живого PID сохраняются.
            if ($null -ne $retained -and $retained.HasExited) {continue}
            throw
        } finally {if ($null -ne $retained) {$retained.Dispose()}}
    }
    $controlledHash=Get-ColdObjectHash @(Get-ColdControlledInventory $Root)
    $after=[bool](Test-Path -LiteralPath $path)
    if ($before -and $after -and $visible) {throw 'COLD_UI_WHILE_RECOVERY_ACTIVE'}
    return [pscustomobject]@{startedAt=$begin.ToString('o');finishedAt=[datetime]::UtcNow.ToString('o');
        journalBefore=$before;journalAfter=$after;visibleCount=$visible;controlledSha256=$controlledHash}
}

# Отчёт сохраняет ограничение polling и отказывает при пробелах/раннем UI/неверном времени.
function Assert-ColdRecoveryEvidence($Evidence,[string]$Root,[string]$Transaction,[long]$Killed,[long]$Launched,[long]$Observed) {
    try {
        Assert-ColdKeys $Evidence @('schemaVersion','installationRoot','transactionId','pollingLimitMillis','controlledSha256','samples')
        if (-not (Test-ColdInteger $Evidence.schemaVersion 1) -or $Evidence.schemaVersion -ne 1 -or $Evidence.installationRoot -cne $Root -or $Evidence.transactionId -cne $Transaction -or
            -not (Test-ColdInteger $Evidence.pollingLimitMillis 1) -or $Evidence.pollingLimitMillis -ne 1000 -or $Evidence.controlledSha256 -cnotmatch '^[0-9a-f]{64}$' -or @($Evidence.samples).Count -lt 2) {throw 'COLD_RECOVERY_EVIDENCE'}
        $previous=$Killed; $active=$false; $clear=$false
        foreach ($sample in @($Evidence.samples)) {
            Assert-ColdKeys $sample @('startedAt','finishedAt','journalBefore','journalAfter','visibleCount','controlledSha256')
            $begin=Get-ColdUtcTicks $sample.startedAt; $end=Get-ColdUtcTicks $sample.finishedAt
            if ($begin -lt $previous -or $end -lt $begin -or $end-$begin -gt 10000000L -or $begin-$previous -gt 10000000L -or
                $sample.journalBefore -isnot [bool] -or $sample.journalAfter -isnot [bool] -or -not (Test-ColdInteger $sample.visibleCount) -or
                $sample.controlledSha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'COLD_RECOVERY_EVIDENCE'}
            if ($clear -and ($sample.journalBefore -or $sample.journalAfter)) {throw 'COLD_RECOVERY_EVIDENCE'}
            if ($sample.journalBefore -and $sample.journalAfter) {
                if ($sample.visibleCount -ne 0 -or $sample.controlledSha256 -cne $Evidence.controlledSha256) {throw 'COLD_RECOVERY_EVIDENCE'}
                $active=$true
            }
            if (-not $sample.journalBefore -and -not $sample.journalAfter -and $begin -ge $Launched) {$clear=$true}
            $previous=$end
        }
        if (-not $active -or -not $clear -or $previous -gt $Observed) {throw 'COLD_RECOVERY_EVIDENCE'}
    } catch {throw 'COLD_REPORT_RECOVERY_EVIDENCE'}
}

# Список полон для portable image; ни один обязательный bootstrap файл не пропускается.
function Assert-ColdImageInventory($Files,[string]$Hash) {
    Assert-ColdInventory $Files $Hash
    foreach ($path in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app/.jpackage.xml',
        'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules') {
        if (@($Files | Where-Object {$_.path -ceq $path -and $_.sizeBytes -gt 0}).Count -ne 1) {throw 'COLD_INVENTORY'}
    }
    $jars=@($Files | Where-Object {$_.path -match '^app/.*\.jar$'})
    if ($jars.Count -ne 4) {throw 'COLD_INVENTORY'}
    foreach ($role in 'core','ui-fx','ui-swing','web') {
        if (@($jars | Where-Object {$_.path -cmatch ('^app/cashprediction-'+$role+'-[A-Za-z0-9_.-]+\.jar$') -and
            $_.sizeBytes -gt 0}).Count -ne 1) {throw 'COLD_INVENTORY'}
    }
}

# Cold наблюдение доказывает полный старый payload, а не присваивает успех по ожидаемому BOOT_COPY.
function Assert-ColdProtectedEvidence($Receipt,$Journal) {
    foreach ($operation in @($Journal.operations)) {
        if ($operation.kind -cne 'BOOT_COPY') {continue}
        Assert-ColdKeys $operation @('kind','path','state')
        if ($operation.state -cnotin @('BEFORE','AFTER') -or -not (Test-ColdProtectedPayload $operation.path) -or
            @($Journal.oldFiles | Where-Object {$_.path -ceq $operation.path}).Count -ne 1) {throw 'COLD_BOOT_COPY_PATH'}
    }
    $expected=@($Journal.oldFiles | Where-Object {Test-ColdProtectedPayload $_.path})
    $actual=@($Receipt.bootstrapFiles)
    if ($Receipt.bootstrapVerified -isnot [bool]) {throw 'COLD_BOOTSTRAP_PAYLOAD'}
    if ($Receipt.bootstrapVerified) {
        Assert-ColdInventory $actual $Receipt.bootstrapTreeSha256
        if (-not (Test-ColdInventoryEqual $actual $expected)) {throw 'COLD_BOOTSTRAP_PAYLOAD'}
    } elseif ($actual.Count -ne 0 -or $Receipt.bootstrapTreeSha256 -cne (Get-ColdTreeHash @()) -or
        $Receipt.checkpoint -cnotin @('INITIAL','COPYING','PUBLISH_BEFORE','CLEANED')) {throw 'COLD_BOOTSTRAP_PAYLOAD'}
}

# Снимки остальных клиентов/планы/lock неизменны; текущая сессия принадлежит наблюдаемому UI.
function Get-ColdSessionWords {
    $words=@{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/format/format.properties')) {
        if ($line -match '^(session\.md\.[^=]+)=(.*)$') {$words[$Matches[1]]=$Matches[2]}
    }
    return $words
}

# Разрешённые изменения доказываются содержимым текущей сессии, не только именем файла.
function Assert-ColdControlledChanges($Before,$After,[string]$Client,$Ui,[string]$Root,[long]$Finished) {
    try {
        $maps=@(@{},@{});$index=0
        foreach ($list in @(@{items=@($Before)},@{items=@($After)})) {
            foreach ($entry in $list.items) {
                Assert-ColdKeys $entry @('path','sizeBytes','sha256','contentBase64','readOnly')
                if (-not (Test-ColdInteger $entry.sizeBytes) -or $entry.sizeBytes -gt 8388608 -or $entry.readOnly -isnot [bool]) {throw 'COLD_CONTROLLED'}
                if ($maps[$index].ContainsKey($entry.path) -or $entry.path -cnotin @('CashMemory/session-fx.xml','CashMemory/session-swing.xml',
                    'CashMemory/web-session.md','CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md','CashMemory/lock.md')) {throw 'COLD_CONTROLLED'}
                $bytes=[Convert]::FromBase64String($entry.contentBase64)
                if ($bytes.Length -ne $entry.sizeBytes -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $entry.sha256) {throw 'COLD_CONTROLLED'}
                $maps[$index].Add($entry.path,$entry)
            };$index++
        }
        foreach ($path in @(@($maps[0].Keys)+@($maps[1].Keys) | Sort-Object -Unique)) {
            $old=$maps[0][$path];$new=$maps[1][$path]
            if ($null -ne $old -and $null -ne $new -and $old.readOnly -ne $new.readOnly) {throw 'COLD_CONTROLLED'}
            if ($null -ne $old -and $null -ne $new -and $old.sha256 -ceq $new.sha256) {continue}
            if ($null -eq $new) {throw 'COLD_CONTROLLED'}
            $text=[Text.UTF8Encoding]::new($false,$true).GetString([Convert]::FromBase64String($new.contentBase64))
            if ($path -ceq ('CashMemory/session-'+$Client+'.xml') -and $Client -in @('fx','swing')) {
                $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
                $reader=[Xml.XmlReader]::Create([IO.StringReader]::new($text),$settings)
                try {$doc=[Xml.XmlDocument]::new();$doc.XmlResolver=$null;$doc.Load($reader)} finally {$reader.Dispose()}
                $s=$doc.DocumentElement
                $sessionStarted=Get-ColdUtcTicks $s.GetAttribute('startedAt')
                if ($s.Name -cne 'session' -or $s.GetAttribute('schema') -cne '1' -or $s.GetAttribute('client') -cne $Client -or
                    $s.GetAttribute('pid') -cne [string]$Ui.pid -or $s.GetAttribute('state') -cnotin @('running','closed') -or
                    $sessionStarted -lt $Ui.startedAtTicks -or $sessionStarted -gt $Finished) {throw 'COLD_CONTROLLED'}
                # Recorder начинается после JVM/toolkit, а не в момент рождения процесса.
                if ($s.HasAttribute('savedAt')) {$saved=Get-ColdUtcTicks $s.GetAttribute('savedAt');if ($saved -lt $sessionStarted -or $saved -gt $Finished) {throw 'COLD_CONTROLLED'}}
            } elseif ($Client -ceq 'web' -and $path -ceq 'CashMemory/web-session.md') {
                # Грамматика читается из общего неизменяемого ресурса, не из UI-локализации.
                $words=Get-ColdSessionWords
                if (-not $text.StartsWith($words['session.md.title.prefix']+'web)'+"`n")) {throw 'COLD_CONTROLLED'}
                $values=@{}
                foreach ($key in 'state','pid','started','schema','saved') {
                    $matches=[regex]::Matches($text,'(?m)^- '+[regex]::Escape($words['session.md.key.'+$key])+': (.*)$')
                    if ($matches.Count -gt 1 -or ($key -ne 'saved' -and $matches.Count -ne 1)) {throw 'COLD_CONTROLLED'}
                    if ($matches.Count) {$values[$key]=$matches[0].Groups[1].Value}
                }
                $sessionStarted=Get-ColdUtcTicks $values['started']
                if ($values['schema'] -cne '1' -or $values['pid'] -cne [string]$Ui.pid -or $values['state'] -cnotin @('running','closed') -or
                    $sessionStarted -lt $Ui.startedAtTicks -or $sessionStarted -gt $Finished) {throw 'COLD_CONTROLLED'}
                if ($values.ContainsKey('saved')) {$saved=Get-ColdUtcTicks $values['saved'];if ($saved -lt $sessionStarted -or $saved -gt $Finished) {throw 'COLD_CONTROLLED'}}
            } elseif ($Client -ceq 'web' -and $null -eq $old -and $path -ceq 'CashMemory/web-reconnect-lock.md' -and $new.sizeBytes -eq 0) {continue}
            elseif ($Client -ceq 'web' -and $null -eq $old -and $path -ceq 'CashMemory/web-reconnect.md') {
                $binding=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Join-Path $Root 'CashMemory').ToLowerInvariant()))).ToLowerInvariant()
                if ($text -cnotmatch ('\A# CashPrediction web reconnect\nVersion: 1\nInstallation: [0-9a-f]{32}\nKey: [0-9a-f]{64}\nPath-SHA256: '+$binding+'\n\z')) {throw 'COLD_CONTROLLED'}
            } else {throw 'COLD_CONTROLLED'}
        }
    } catch {Write-Verbose ('Cold controlled guard: '+$_.Exception.Message);throw 'COLD_REPORT_CONTROLLED'}
}

# Итог не может пройти по отсутствующим/шаблонным результатам или незапущенному exe.
function Assert-ColdMatrix($Rows,$Plan) {
    if (@($Rows).Count -ne @($Plan).Count -or @($Plan).Count -eq 0) {throw 'COLD_REPORT_MISSING'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in $Rows) {
        foreach ($required in 'base','checkpoint','client','pathVariant','status','nativeExecuted','actualExit','helperActualExit',
            'treeOutcome','managedSha256','baseTreeSha256','targetTreeSha256','userBeforeSha256','userAfterSha256','uiReceipt',
            'checkpointReceipt','journalReceipt','inventoryReceipt','launchReceipt','userBeforeReceipt','userAfterReceipt',
            'workRoot','helperIdentity','transactionId','baseReleaseNumber','baseCommitSha','targetReleaseNumber','targetCommitSha',
            'phase','bootstrapState','publishState') {
            if ($null -eq $row.PSObject.Properties[$required]) {throw 'COLD_REPORT_PENDING_OR_FAILED'}
        }
        $key=$row.base+'/'+$row.checkpoint+'/'+$row.client+'/'+$row.pathVariant
        if (-not $seen.Add($key) -or @($Plan | Where-Object {$_.key -ceq $key}).Count -ne 1 -or $row.status -cne 'PASS' -or
            $row.nativeExecuted -isnot [bool] -or $row.nativeExecuted -ne $true -or -not (Test-ColdInteger $row.actualExit) -or $row.actualExit -ne 0 -or
            -not (Test-ColdInteger $row.helperActualExit ([long]::MinValue)) -or $row.helperActualExit -eq 0 -or
            $row.treeOutcome -cnotin @('OLD','NEW') -or $row.managedSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $row.baseTreeSha256 -cnotmatch '^[0-9a-f]{64}$' -or $row.targetTreeSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $row.userBeforeSha256 -cne $row.userAfterSha256 -or $row.userBeforeSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $null -eq $row.uiReceipt -or $row.uiReceipt.pid -le 0 -or $row.uiReceipt.startedAtTicks -le 0) {throw 'COLD_REPORT_PENDING_OR_FAILED'}
        foreach ($file in @($row.checkpointReceipt,$row.journalReceipt,$row.inventoryReceipt,$row.launchReceipt,$row.userBeforeReceipt,$row.userAfterReceipt)) {
            if (-not $file -or -not (Test-Path -LiteralPath (Resolve-PortableSafetyPath $file) -PathType Leaf)) {throw 'COLD_REPORT_ARTIFACT_MISSING'}
        }
        $launch=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.launchReceipt -Raw)
        Assert-ColdKeys $launch @('schemaVersion','base','checkpoint','client','pathVariant','nativeExe','args','startedAt',
            'launcher','uiReceipt','version','treeSha256','actualExit','helperKilledAt','finishedAt',
            'recoveryReceipt','postCleanupReceipt','controlledBeforeReceipt','controlledAfterReceipt')
        $exe=switch ($row.client) {'fx' {'CashPrediction.exe'} 'swing' {'CashPrediction-Swing.exe'} 'web' {'CashPrediction-Web.exe'} default {throw 'COLD_REPORT_CLIENT'}}
        if (-not (Test-ColdInteger $launch.schemaVersion 1) -or -not (Test-ColdInteger $launch.actualExit) -or
            -not (Test-ColdInteger $launch.launcher.ProcessId 1) -or -not (Test-ColdInteger $launch.launcher.StartedAtTicks 1) -or
            $launch.schemaVersion -ne 1 -or $launch.base -cne $row.base -or $launch.checkpoint -cne $row.checkpoint -or
            $launch.client -cne $row.client -or $launch.pathVariant -cne $row.pathVariant -or $launch.actualExit -ne $row.actualExit -or
            $launch.nativeExe -cne (Join-Path $row.workRoot $exe) -or $launch.launcher.ProcessId -le 0 -or
            $launch.launcher.StartedAtTicks -le 0 -or $launch.launcher.ExecutablePath -cne $launch.nativeExe -or
            $launch.treeSha256 -cne $row.managedSha256 -or $launch.uiReceipt.pid -ne $row.uiReceipt.pid -or
            $launch.uiReceipt.startedAtTicks -ne $row.uiReceipt.startedAtTicks -or @($launch.args).Count -lt 2 -or
            $launch.args[0] -cne '--home' -or $launch.args[1] -cne $row.workRoot -or
            $launch.uiReceipt.lease.client -cne $row.client -or $launch.uiReceipt.lease.installationRoot -cne $row.workRoot) {throw 'COLD_REPORT_LAUNCH_IDENTITY'}
        try {
            if ($row.workRoot -cne (Resolve-PortableSafetyPath $row.workRoot)) {throw 'COLD_REPORT_LAUNCH_IDENTITY'}
            Assert-ColdUiReceipt $launch.uiReceipt $row.workRoot $row.client
            Assert-ColdUiReceipt $row.uiReceipt $row.workRoot $row.client
            if (-not (Test-ColdInventoryEqual $launch.uiReceipt $row.uiReceipt)) {throw 'COLD_REPORT_LAUNCH_IDENTITY'}
            Assert-ColdSafeArgs $launch.args $launch.uiReceipt.args $row.workRoot $row.client $row.targetCommitSha
            $killed=Get-ColdUtcTicks $launch.helperKilledAt; $started=Get-ColdUtcTicks $launch.startedAt
            $observed=Get-ColdUtcTicks $launch.uiReceipt.observedAt; $finished=Get-ColdUtcTicks $launch.finishedAt
            if ($row.helperIdentity.StartedAtTicks -ge $killed -or $killed -gt $started -or $started -gt $launch.launcher.StartedAtTicks -or
                $launch.launcher.StartedAtTicks -gt $launch.uiReceipt.startedAtTicks -or $launch.uiReceipt.startedAtTicks -gt $observed -or
                $observed -gt $finished -or $finished -gt [datetime]::UtcNow.Ticks) {throw 'COLD_REPORT_LAUNCH_IDENTITY'}
        } catch {Write-Verbose ('Cold launch guard: '+$_.Exception.Message);throw 'COLD_REPORT_LAUNCH_IDENTITY'}
        $checkpoint=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.checkpointReceipt -Raw)
        Assert-ColdCheckpoint $checkpoint $row.helperIdentity $row.workRoot $row.checkpoint $row.transactionId
        if ((Get-FileHash -LiteralPath $row.journalReceipt).Hash.ToLowerInvariant() -cne $checkpoint.journalSha256) {throw 'COLD_REPORT_JOURNAL_PIN'}
        $journal=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.journalReceipt -Raw)
        if (-not (Test-ColdInteger $journal.schemaVersion 1) -or $journal.schemaVersion -ne 2 -or $journal.installationRoot -cne $row.workRoot -or $journal.transactionId -cne $row.transactionId -or
            $journal.oldTreeSha256 -cne $row.baseTreeSha256 -or $journal.target.treeSha256 -cne $row.targetTreeSha256) {throw 'COLD_REPORT_JOURNAL_IDENTITY'}
        try {
            if (-not (Test-ColdInteger $row.baseReleaseNumber 1) -or -not (Test-ColdInteger $row.targetReleaseNumber 1) -or
                $row.targetReleaseNumber -le $row.baseReleaseNumber -or $journal.target.releaseNumber -ne $row.targetReleaseNumber -or
                $journal.target.commitSha -cne $row.targetCommitSha) {throw 'COLD_REPORT_JOURNAL_IDENTITY'}
            Assert-ColdImageInventory $journal.oldFiles $journal.oldTreeSha256
            Assert-ColdImageInventory $journal.target.files $journal.target.treeSha256
        } catch {throw 'COLD_REPORT_JOURNAL_IDENTITY'}
        Assert-ColdProtectedEvidence $checkpoint $journal
        if ($row.phase -cne $checkpoint.phase -or $row.bootstrapState -cne $checkpoint.bootstrapState -or
            $row.publishState -cne $checkpoint.publishState) {throw 'COLD_REPORT_PHASE_IDENTITY'}
        $inventory=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.inventoryReceipt -Raw)
        if ((Get-ColdTreeHash @($inventory)) -cne $row.managedSha256 -or
            ($row.treeOutcome -eq 'OLD' -and $row.managedSha256 -cne $row.baseTreeSha256) -or
            ($row.treeOutcome -eq 'NEW' -and $row.managedSha256 -cne $row.targetTreeSha256)) {throw 'COLD_REPORT_TREE_PIN'}
        $chosenFiles=if ($row.treeOutcome -ceq 'OLD') {$journal.oldFiles} else {$journal.target.files}
        if (-not (Test-ColdInventoryEqual $inventory $chosenFiles)) {throw 'COLD_REPORT_TREE_PIN'}
        foreach ($file in @($launch.recoveryReceipt,$launch.postCleanupReceipt,$launch.controlledBeforeReceipt,$launch.controlledAfterReceipt)) {
            if (-not $file -or -not (Test-Path -LiteralPath (Resolve-PortableSafetyPath $file) -PathType Leaf)) {throw 'COLD_REPORT_ARTIFACT_MISSING'}
        }
        $recovery=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launch.recoveryReceipt -Raw)
        Assert-ColdRecoveryEvidence $recovery $row.workRoot $row.transactionId $killed $started $observed
        $post=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launch.postCleanupReceipt -Raw)
        try {
            Assert-ColdKeys $post @('schemaVersion','installationRoot','transactionId','observedAt','treeSha256','files','toolExit','processesRemaining')
            if ($post.schemaVersion -ne 1 -or $post.installationRoot -cne $row.workRoot -or $post.transactionId -cne $row.transactionId -or
                $post.treeSha256 -cne $row.managedSha256 -or -not (Test-ColdInteger $post.schemaVersion 1) -or
                -not (Test-ColdInteger $post.toolExit) -or $post.toolExit -ne 0 -or
                -not (Test-ColdInteger $post.processesRemaining) -or $post.processesRemaining -ne 0 -or
                (Get-ColdUtcTicks $post.observedAt) -lt $observed -or (Get-ColdUtcTicks $post.observedAt) -gt $finished -or
                -not (Test-ColdInventoryEqual $post.files $chosenFiles)) {throw 'COLD_POST_CLEANUP'}
            Assert-ColdImageInventory $post.files $post.treeSha256
        } catch {throw 'COLD_REPORT_POST_CLEANUP'}
        $controlledBefore=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launch.controlledBeforeReceipt -Raw)
        $controlledAfter=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launch.controlledAfterReceipt -Raw)
        if ($recovery.controlledSha256 -cne (Get-ColdObjectHash $controlledBefore)) {throw 'COLD_REPORT_RECOVERY_EVIDENCE'}
        Assert-ColdControlledChanges $controlledBefore $controlledAfter $row.client $launch.uiReceipt $row.workRoot $finished
        if ((Get-FileHash -LiteralPath $row.userBeforeReceipt).Hash.ToLowerInvariant() -cne $row.userBeforeSha256 -or
            (Get-FileHash -LiteralPath $row.userAfterReceipt).Hash.ToLowerInvariant() -cne $row.userAfterSha256) {throw 'COLD_REPORT_USER_PIN'}
        $release=if ($row.treeOutcome -eq 'OLD') {$row.baseReleaseNumber} else {$row.targetReleaseNumber}
        $commit=if ($row.treeOutcome -eq 'OLD') {$row.baseCommitSha} else {$row.targetCommitSha}
        try {
            Assert-ColdKeys $launch.version @('releaseNumber','commitSha','jar','jarSha256')
            $jars=@($chosenFiles | Where-Object {$_.path -ceq ('app/'+$launch.version.jar)})
            if (-not (Test-ColdInteger $launch.version.releaseNumber 1) -or $jars.Count -ne 1 -or
                $launch.version.jar -cnotmatch '^[^/\\:]+\.jar$' -or $launch.version.jarSha256 -cne $jars[0].sha256) {throw 'COLD_REPORT_VERSION'}
        } catch {throw 'COLD_REPORT_VERSION'}
        if ($launch.version.releaseNumber -ne $release -or $launch.version.commitSha -cne $commit -or
            $commit -cnotmatch '^[0-9a-f]{40}$' -or $launch.version.jarSha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'COLD_REPORT_VERSION'}
    }
}

# Запись доказательства только в новый собственный evidence каталог.
function Write-ColdJson([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 64),[Text.UTF8Encoding]::new($false))
}

# Основной запуск находится отдельно от функций: fixtures импортируют лишь AST definitions.
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'COLD_WINDOWS_POWERSHELL7_REQUIRED'}
Import-ColdPortableSafety (Join-Path $PSScriptRoot 'Test-Portable.ps1')
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$userProfileRoot=[Environment]::GetFolderPath('UserProfile')
$bases=@($PortableDir | ForEach-Object {Resolve-PortableSafetyPath $_})
$target=Resolve-PortableSafetyPath $TargetPortableDir; $java=Resolve-PortableSafetyPath $Runtime
if (@($bases | Sort-Object -Unique).Count -ne $bases.Count) {throw 'COLD_DUPLICATE_BASE'}
$config=Read-ColdPinnedJson $CommandFile $CommandFileSha256
Assert-ColdCommand $config $java $bases $target
$targetManifest=Read-ColdPinnedJson $config.targetManifest $config.targetManifestSha256
$baseManifests=@{}
foreach ($source in @($bases)+@($target)) {
    Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $source -Force)
    Assert-PortableTreeHasNoLinks $source; Assert-ColdNativeImage $source
    foreach ($other in @($bases)+@($target)) {
        if ($source -cne $other -and (Test-PortablePathContains $source $other)) {throw 'COLD_SOURCE_OVERLAP'}
    }
}
foreach ($base in $bases) {
    $descriptor=@($config.baseManifests | Where-Object {$_.portableDir -ceq $base})[0]
    $manifest=Read-ColdPinnedJson $descriptor.manifest $descriptor.sha256
    if ($manifest.schemaVersion -ne 2 -or $manifest.releaseNumber -le 0 -or $manifest.releaseNumber -ge $targetManifest.releaseNumber) {throw 'COLD_RELEASE_ORDER'}
    $baseManifests[$base]=$manifest
    [void](Assert-ColdTree $base $manifest)
    $version=Get-ColdVersion $base
    if ($version.releaseNumber -ne $manifest.releaseNumber -or $version.commitSha -cne $manifest.commitSha) {throw 'COLD_BASE_VERSION'}
}
[void](Assert-ColdTree $target $targetManifest)
$version=Get-ColdVersion $target
if ($version.releaseNumber -ne $targetManifest.releaseNumber -or $version.commitSha -cne $targetManifest.commitSha) {throw 'COLD_TARGET_VERSION'}
$helperText=[IO.File]::ReadAllText((Resolve-PortableSafetyPath $config.helperScript))
$instrumented=New-ColdInstrumentedHelper $helperText
$plan=@(); $rows=[Collections.Generic.List[object]]::new(); $copyPlan=@(
    @{id='ascii';relative='plain'},@{id='cyrillic';relative='Мои программы'},@{id='unicode';relative='Δ 测试'})
for ($b=0;$b -lt $bases.Count;$b++) {foreach ($phase in Get-ColdCheckpoints) {foreach ($client in 'fx','swing','web') {foreach ($variant in $copyPlan) {
    $key='B'+($b+1)+'/'+$phase+'/'+$client+'/'+$variant.id
    $plan+=@([pscustomobject]@{key=$key;base='B'+($b+1);source=$bases[$b];checkpoint=$phase;client=$client;variant=$variant})
    $rows.Add([pscustomobject][ordered]@{base='B'+($b+1);checkpoint=$phase;client=$client;pathVariant=$variant.id;status='PENDING';nativeExecuted=$false})
}}}}
if ($plan.Count -gt $MaxCells) {throw 'COLD_CELL_BOUND'}
$executionPlan=@(Get-ColdSelectedPlan $plan $CellKey)
$rowIndices=[Collections.Generic.Dictionary[string,int]]::new([StringComparer]::Ordinal)
for ($position=0;$position -lt $plan.Count;$position++) {$rowIndices.Add($plan[$position].key,$position)}
$evidence=Join-Path ([IO.Path]::GetTempPath()) ('cp-bootstrap-evidence-'+[guid]::NewGuid().ToString())
[void](Resolve-PortableSafetyPath $evidence); $null=New-Item -ItemType Directory -Path $evidence
$registryBefore=Get-PortableRealRegistrySnapshot; $fatal=$null; $index=0
Write-ColdJson (Join-Path $evidence 'plan.json') $plan
try {
    foreach ($cell in $executionPlan) {
        $row=$rows[$rowIndices[$cell.key]]
        $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
        [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
        foreach ($source in @($bases)+@($target)) {[void](Get-ValidatedPortablePaths $source $run $project $userProfileRoot)}
        $cellEvidence=Join-Path $evidence ([IO.Path]::GetFileName($run)); $null=New-Item -ItemType Directory -Path $cellEvidence
        $root=Join-Path (Join-Path $run $cell.variant.relative) 'CashPrediction'
        $WorkDir=$run
        $cellStarted=[datetime]::UtcNow
        $systemPowerShell=Resolve-PortableSafetyPath (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe')
        $helperProcess=$null; $helperIdentity=$null; $launcher=$null; $launcherIdentity=$null; $node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
        $row | Add-Member -NotePropertyName workRoot -NotePropertyValue $root
        try {
            $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($root))
            Copy-Item -LiteralPath $cell.source -Destination $root -Recurse
            Assert-PortableTreeHasNoLinks $root
            $base=$baseManifests[$cell.source]
            $updates=Join-Path $root 'CashMemory/Updates'; $ready=Join-Path $updates 'Ready'
            $null=New-Item -ItemType Directory -Path $ready
            Copy-Item -LiteralPath $target -Destination (Join-Path $ready 'tree') -Recurse
            # Пользовательский baseline обязан быть настоящим редактируемым планом:
            # startup автоматически открывает первый .md, произвольный sentinel давал Alert.
            $protectedPlan=@'
# План: Bootstrap

## Параметры

- Формат: CashPrediction 2
- Валюта: RUB
- Начало: 2031-01-01
- Горизонт: 12 месяцев
- Начальный баланс: 0
'@
            [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-plan.md'),$protectedPlan,[Text.UTF8Encoding]::new($false))
            Initialize-ColdSettings $root $java $cellEvidence
            [IO.File]::WriteAllText((Join-Path $root 'protected-root.txt'),"bootstrap-unmanaged`n")
            Write-ColdJson (Join-Path $ready 'update.json') $targetManifest
            $journal=New-ColdJournal $root $base $targetManifest
            Write-ColdJson (Join-Path $updates 'install-journal.json') $journal
            [IO.File]::WriteAllText((Join-Path $updates 'apply-update.ps1'),$helperText,[Text.UTF8Encoding]::new($false))
            $faultHelper=Join-Path $updates 'cold-fault-helper.ps1'
            [IO.File]::WriteAllText($faultHelper,$instrumented,[Text.UTF8Encoding]::new($false))
            Write-ColdJson (Join-Path $updates 'cold-control.json') ([ordered]@{checkpoint=$cell.checkpoint;nonce=[guid]::NewGuid().ToString()})
            $userBefore=@(Get-ColdUserInventory $root); Write-ColdJson (Join-Path $cellEvidence 'user-before.json') $userBefore
            $controlledBefore=@(Get-ColdControlledInventory $root)
            Write-ColdJson (Join-Path $cellEvidence 'controlled-before.json') $controlledBefore
            $tools=@(Invoke-ColdTool $config $java @('verify','--root',$root,'--manifest',@($config.baseManifests | Where-Object {$_.portableDir -ceq $cell.source})[0].manifest) $cellEvidence $root)
            $tools+=@(Invoke-ColdTool $config $java @('verify','--root',(Join-Path $ready 'tree'),'--manifest',$config.targetManifest) $cellEvidence $root)
            [void](Assert-ColdTree $root $base); [void](Assert-ColdTree (Join-Path $ready 'tree') $targetManifest)
            $helperArguments=@('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$faultHelper,'-InstallationRoot',$root,'-Diagnostics')
            if ($cell.checkpoint -eq 'ROLLING_BACK') {$helperArguments+=@('-FaultPhase','INSTALLING')}
            $helperProcess=Start-ColdProcess $systemPowerShell $helperArguments $root -DiagnosticsDirectory $cellEvidence
            $helperIdentity=Get-ColdProcessReceipt $helperProcess $root
            $checkpointPath=Join-Path $updates 'cold-checkpoint.json'; $deadline=[DateTimeOffset]::UtcNow.AddSeconds($CheckpointTimeoutSeconds)
            while (-not (Test-Path -LiteralPath $checkpointPath)) {
                if ($helperProcess.HasExited -or [DateTimeOffset]::UtcNow -ge $deadline) {throw 'COLD_CHECKPOINT_NOT_REACHED'}
                Start-Sleep -Milliseconds 50
            }
            $checkpoint=Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json
            Assert-ColdCheckpoint $checkpoint $helperIdentity $root $cell.checkpoint $journal.transactionId
            $stoppedJournal=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $updates 'install-journal.json') -Raw)
            Assert-ColdProtectedEvidence $checkpoint $stoppedJournal
            if ((Get-FileHash -LiteralPath (Join-Path $updates 'install-journal.json')).Hash.ToLowerInvariant() -cne $checkpoint.journalSha256) {throw 'COLD_CHECKPOINT_JOURNAL_CHANGED'}
            $checkpointReceipt=Join-Path $cellEvidence 'checkpoint.json'; $journalReceipt=Join-Path $cellEvidence 'journal-at-kill.json'
            Copy-Item -LiteralPath $checkpointPath -Destination $checkpointReceipt
            Copy-Item -LiteralPath (Join-Path $updates 'install-journal.json') -Destination $journalReceipt
            # Первый вызов сетевого CIM provider прогревается до измеряемого polling окна.
            # Последующие наблюдения всё равно читают свежий snapshot и не используют этот результат.
            [void](Get-NetTCPConnection -State Listen -ErrorAction Stop)
            Stop-ColdRetainedProcess $helperProcess $helperIdentity $root
            $helperExit=$helperProcess.ExitCode
            if ($helperExit -eq 0) {throw 'COLD_HELPER_NOT_KILLED'}
            $helperKilledAt=[datetime]::UtcNow
            $observations=[Collections.Generic.List[object]]::new()
            $observations.Add((Get-ColdRecoveryObservation $root $cellStarted))
            if ($observations[0].controlledSha256 -cne (Get-ColdObjectHash $controlledBefore)) {throw 'COLD_REPORT_CONTROLLED'}
            $exe=switch ($cell.client) {'fx' {'CashPrediction.exe'} 'swing' {'CashPrediction-Swing.exe'} 'web' {'CashPrediction-Web.exe'}}
            $nativePath=Join-Path $root $exe; $nativeArgs=@('--home',$root)
            if ($cell.client -eq 'web') {$nativeArgs+=@('--no-browser','--no-window')}
            $coldStarted=[datetime]::UtcNow; $launcher=Start-ColdProcess $nativePath $nativeArgs $root $node
            $row.nativeExecuted=$true; $launcherIdentity=Get-ColdProcessReceipt $launcher $root
            $ui=$null; $deadline=[DateTimeOffset]::UtcNow.AddSeconds($ColdTimeoutSeconds)
            while ([DateTimeOffset]::UtcNow -lt $deadline) {
                $observation=Get-ColdRecoveryObservation $root $coldStarted
                $observations.Add($observation)
                if ($observation.journalBefore -and $observation.journalAfter -and
                    $observation.controlledSha256 -cne (Get-ColdObjectHash $controlledBefore)) {throw 'COLD_REPORT_CONTROLLED'}
                if (-not (Test-Path -LiteralPath (Join-Path $updates 'install-journal.json'))) {
                    $ui=Get-ColdUiReceipt $root $cell.client $coldStarted
                    if ($null -ne $ui) {break}
                }
                Start-Sleep -Milliseconds 100
            }
            if ($null -eq $ui) {throw 'COLD_NATIVE_RECOVERY_NOT_READY'}
            Assert-ColdSafeArgs $nativeArgs $ui.args $root $cell.client $targetManifest.commitSha
            $recoveryReceipt=Join-Path $cellEvidence 'recovery-observations.json'
            $recoveryEvidence=[pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId=$journal.transactionId;
                pollingLimitMillis=1000;controlledSha256=(Get-ColdObjectHash $controlledBefore);samples=@($observations.ToArray())}
            # Сохраняем наблюдения и при отказе валидатора, не выдавая им статус acceptance.
            Write-ColdJson (Join-Path $cellEvidence 'recovery-observations-unvalidated.json') $recoveryEvidence
            Assert-ColdRecoveryEvidence $recoveryEvidence $root $journal.transactionId $helperKilledAt.Ticks $coldStarted.Ticks (Get-ColdUtcTicks $ui.observedAt)
            Write-ColdJson $recoveryReceipt $recoveryEvidence
            $actual=@(Get-ColdManagedInventory $root); $treeHash=Get-ColdTreeHash $actual
            $outcome=if ($treeHash -ceq $base.treeSha256) {'OLD'} elseif ($treeHash -ceq $targetManifest.treeSha256) {'NEW'} else {throw 'COLD_PARTIAL_TREE'}
            $chosen=if ($outcome -eq 'OLD') {$base} else {$targetManifest}
            [void](Assert-ColdTree $root $chosen)
            $version=Get-ColdVersion $root
            if ($version.releaseNumber -ne $chosen.releaseNumber -or $version.commitSha -cne $chosen.commitSha) {throw 'COLD_RECOVERED_VERSION'}
            $manifestFile=if ($outcome -eq 'OLD') {@($config.baseManifests | Where-Object {$_.portableDir -ceq $cell.source})[0].manifest} else {$config.targetManifest}
            $tools+=@(Invoke-ColdTool $config $java @('verify','--root',$root,'--manifest',$manifestFile) $cellEvidence $root)
            $launchReceipt=Join-Path $cellEvidence 'native-launch.json'
            Write-ColdJson $launchReceipt ([ordered]@{schemaVersion=1;base=$cell.base;checkpoint=$cell.checkpoint;client=$cell.client;pathVariant=$cell.variant.id;
                nativeExe=$nativePath;args=$nativeArgs;startedAt=$coldStarted.ToString('o');
                launcher=$launcherIdentity;uiReceipt=$ui;version=$version;treeSha256=$treeHash;actualExit=$null;
                helperKilledAt=$helperKilledAt.ToString('o');finishedAt=$null;recoveryReceipt=$recoveryReceipt;
                postCleanupReceipt=(Join-Path $cellEvidence 'managed-after-cleanup.json');
                controlledBeforeReceipt=(Join-Path $cellEvidence 'controlled-before.json');controlledAfterReceipt=(Join-Path $cellEvidence 'controlled-after.json')})
            # Завершение дерева собственной копии выполняется только после получения UI-квитанции.
            Stop-ColdRecoveryHelpers $root $systemPowerShell $cellStarted
            Stop-ColdCopyProcesses $root
            if (-not $launcher.WaitForExit(5000)) {throw 'COLD_LAUNCHER_ALIVE'}
            $actualExit=$launcher.ExitCode
            # PID окна при cleanup завершается принудительно; exit лаунчера recovery должен быть 0.
            if ($actualExit -ne 0) {throw 'COLD_NATIVE_EXIT'}
            # После остановки всех процессов повторяются полный inventory и настоящий CLI verify.
            $postFiles=@(Assert-ColdTree $root $chosen)
            $postTool=Invoke-ColdTool $config $java @('verify','--root',$root,'--manifest',$manifestFile) $cellEvidence $root
            $tools+=@($postTool)
            $remaining=@(Get-CopyProcesses $root).Count
            if ($remaining) {throw 'COLD_COPY_PROCESSES_ALIVE'}
            Write-ColdJson (Join-Path $cellEvidence 'managed-after-cleanup.json') ([ordered]@{schemaVersion=1;installationRoot=$root;
                transactionId=$journal.transactionId;observedAt=[datetime]::UtcNow.ToString('o');treeSha256=$treeHash;files=$postFiles;
                toolExit=$postTool.actualExit;processesRemaining=$remaining})
            $userAfter=@(Get-ColdUserInventory $root)
            if ((ConvertTo-Json -InputObject $userBefore -Depth 8 -Compress) -cne (ConvertTo-Json -InputObject $userAfter -Depth 8 -Compress)) {throw 'COLD_USER_CHANGED'}
            Write-ColdJson (Join-Path $cellEvidence 'user-after.json') $userAfter
            $controlledAfter=@(Get-ColdControlledInventory $root)
            $finishedAt=[datetime]::UtcNow
            Assert-ColdControlledChanges $controlledBefore $controlledAfter $cell.client $ui $root $finishedAt.Ticks
            Write-ColdJson (Join-Path $cellEvidence 'controlled-after.json') $controlledAfter
            $inventoryReceipt=Join-Path $cellEvidence 'managed.json'; Write-ColdJson $inventoryReceipt $actual
            $launch=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launchReceipt -Raw); $launch.actualExit=$actualExit;$launch.finishedAt=$finishedAt.ToString('o'); Write-ColdJson $launchReceipt $launch
            Write-ColdJson (Join-Path $cellEvidence 'tool-receipts.json') $tools
            $recoveryLog=Join-Path $updates 'update-log.md'
            if (Test-Path -LiteralPath $recoveryLog) {Copy-Item -LiteralPath $recoveryLog -Destination (Join-Path $cellEvidence 'recovery-log.md')}
            $properties=@{actualExit=$actualExit;helperActualExit=$helperExit;treeOutcome=$outcome;managedSha256=$treeHash;
                phase=$checkpoint.phase;bootstrapState=$checkpoint.bootstrapState;publishState=$checkpoint.publishState;
                baseTreeSha256=$base.treeSha256;targetTreeSha256=$targetManifest.treeSha256;
                userBeforeSha256=(Get-FileHash -LiteralPath (Join-Path $cellEvidence 'user-before.json')).Hash.ToLowerInvariant();
                userAfterSha256=(Get-FileHash -LiteralPath (Join-Path $cellEvidence 'user-after.json')).Hash.ToLowerInvariant();
                userBeforeReceipt=(Join-Path $cellEvidence 'user-before.json');userAfterReceipt=(Join-Path $cellEvidence 'user-after.json');
                baseReleaseNumber=$base.releaseNumber;baseCommitSha=$base.commitSha;targetReleaseNumber=$targetManifest.releaseNumber;targetCommitSha=$targetManifest.commitSha;
                uiReceipt=$ui;version=$version;checkpointReceipt=$checkpointReceipt;journalReceipt=$journalReceipt;
                inventoryReceipt=$inventoryReceipt;launchReceipt=$launchReceipt;helperIdentity=$helperIdentity;transactionId=$journal.transactionId}
            foreach ($key in $properties.Keys) {$row | Add-Member -NotePropertyName $key -NotePropertyValue $properties[$key]}
            # PASS публикуется только после полного acceptance этой клетки, не после записи шаблона.
            $candidate=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $row -Depth 64)
            $candidate.status='PASS'
            Assert-ColdMatrix @($candidate) @($cell)
            $row.status='PASS'
        } catch {
            $row.status='FAIL'; $row | Add-Member -NotePropertyName failure -NotePropertyValue $_.Exception.Message
            # Сам код причины не различает несколько identity guards. Сохраняем точную
            # строку и стек до cleanup, чтобы следующая диагностика не зависела от догадок.
            $primaryError=$_
            try {
                Write-ColdJson (Join-Path $cellEvidence 'failure-detail.json') ([ordered]@{
                    message=$primaryError.Exception.Message;exceptionType=$primaryError.Exception.GetType().FullName;
                    fullyQualifiedErrorId=$primaryError.FullyQualifiedErrorId;
                    scriptStackTrace=$primaryError.ScriptStackTrace;
                    scriptName=$primaryError.InvocationInfo.ScriptName;
                    scriptLineNumber=$primaryError.InvocationInfo.ScriptLineNumber})
            } catch {Write-Warning ('COLD_FAILURE_DETAIL_WRITE: '+$_.Exception.Message)}
            throw
        } finally {
            $cleanupErrors=[Collections.Generic.List[string]]::new()
            try {
                if ($null -ne $helperProcess -and $null -eq $helperIdentity) {
                    $helperIdentity=Get-ColdProcessReceipt $helperProcess $root
                    Assert-ColdProcessIdentity $helperIdentity $helperIdentity $root $systemPowerShell
                }
            } catch {$helperIdentity=$null;$cleanupErrors.Add($_.Exception.Message)}
            try {if ($null -ne $helperIdentity) {Stop-ColdRetainedProcess $helperProcess $helperIdentity $root}} catch {$cleanupErrors.Add($_.Exception.Message)}
            try {Stop-ColdRecoveryHelpers $root $systemPowerShell $cellStarted} catch {$cleanupErrors.Add($_.Exception.Message)}
            try {if (Test-Path -LiteralPath $root) {Stop-ColdCopyProcesses $root}} catch {$cleanupErrors.Add($_.Exception.Message)}
            if ($null -ne $helperProcess) {
                if ($helperProcess.HasExited) {$row | Add-Member -NotePropertyName helperActualExit -NotePropertyValue $helperProcess.ExitCode -Force}
                $row | Add-Member -NotePropertyName actualDiagnostics -NotePropertyValue (Join-Path $cellEvidence 'helper-diagnostics.json') -Force
                try {
                    [void](Save-ColdHelperDiagnostics $helperProcess $helperIdentity $root $cellEvidence)
                } catch {$cleanupErrors.Add($_.Exception.Message)}
                $helperLog=Join-Path $root 'CashMemory/Updates/update-log.md'
                try {if (Test-Path -LiteralPath $helperLog) {Copy-Item -LiteralPath $helperLog -Destination (Join-Path $cellEvidence 'helper-log.md')}} catch {$cleanupErrors.Add($_.Exception.Message)}
                $helperProcess.Dispose()
            }
            if ($null -ne $launcher) {
                if ($launcher.HasExited) {$row | Add-Member -NotePropertyName actualExit -NotePropertyValue $launcher.ExitCode -Force}
                $launcher.Dispose()
            }
            if ($cleanupErrors.Count -eq 0) {
                try {
                    $registryPath=Get-PortableRegistryPath $node
                    if (Test-Path -LiteralPath $registryPath) {Remove-Item -LiteralPath $registryPath -Recurse -Force}
                    if ($row.status -eq 'PASS') {
                        $cleanup=Get-PortableCleanupPath $cell.source $run ([IO.Path]::GetTempPath()) $project $userProfileRoot
                        [void](Assert-ColdOwnedRun $cleanup ([IO.Path]::GetTempPath())); Assert-PortableTreeHasNoLinks $cleanup
                        Remove-Item -LiteralPath $cleanup -Recurse -Force
                    }
                } catch {$cleanupErrors.Add($_.Exception.Message)}
            }
            if ($cleanupErrors.Count) {
                $row.status='FAIL'; $row | Add-Member -NotePropertyName cleanupFailure -NotePropertyValue ($cleanupErrors -join '; ') -Force
            }
            Write-ColdJson (Join-Path $evidence 'results.json') ([ordered]@{schemaVersion=1;status='PENDING';cells=@($rows.ToArray())})
            # Вторичный отказ записан отдельно и не заменяет уже летящий первичный throw клетки.
            if ($cleanupErrors.Count -and $null -eq $row.PSObject.Properties['failure']) {throw ($cleanupErrors -join '; ')}
        }
        Write-Host ('Cold bootstrap cell complete: '+$cell.key)
    }
    $executedRows=@($executionPlan | ForEach-Object {$rows[$rowIndices[$_.key]]})
    Assert-ColdMatrix $executedRows $executionPlan
} catch {$fatal=$_.Exception.Message}
finally {
    if ((Get-PortableRealRegistrySnapshot) -cne $registryBefore) {$fatal='COLD_REAL_REGISTRY_CHANGED'}
    Write-ColdJson (Join-Path $evidence 'results.json') ([ordered]@{schemaVersion=1;
        status=$(if ($fatal) {'FAIL'} elseif ($executionPlan.Count -ne $plan.Count) {'PARTIAL_PASS'} else {'PASS'});
        selectedKeys=@($executionPlan | ForEach-Object {$_.key});fullPlanCellCount=$plan.Count;
        cells=@($rows.ToArray());failure=$fatal})
    Write-Host ('Cold bootstrap evidence: '+$evidence)
}
if ($fatal) {throw $fatal}
