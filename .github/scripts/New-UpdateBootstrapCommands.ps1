<#
.SYNOPSIS
Готовит закреплённый command JSON для настоящего Test-UpdateBootstrap, не запуская матрицу.
.DESCRIPTION
Только MAIN после свежей dist и проверки base/target запускает этот builder в PowerShell 7.
Нужен полный неизменяемый JDK 25, не сокращённый runtime из portable: java.exe
и lib/modules с заранее проверенными SHA-256. Нужны свежие core/tool jar.
CoreJar должен побайтно совпадать с core jar целевой portable сборки.
CLI не имеет команды helper/export: java source-file mode вызывает реальный
PowerShellHelper.publish(Path). Это временный JDK API bridge, не launcher приложения.
JDK компилирует только этот вызов в памяти при запуске MAIN; worker его не выполняет.
JShell не используется: его обычный CLI открывает общее Preferences-хранилище.
Manifests создаёт настоящий CLI manifest, который проверяет полный ZIP; затем verify.
Три CLI команды передаются как --arguments-base64: JSON string[]/UTF-8/standard Base64.
Четыре toolArguments command JSON и source-file вызов helper остаются прежними.
ZIP содержит только managed inventory с корнем CashPrediction/ и фиксированным временем.
Никаких фальшивых helper, native bytes, journal или receipts builder не создаёт.
Все записи - в новом run-UUID непосредственно в Temp. Входы не изменяются.
Артефакты сохраняются также при ошибке; существующие каталоги не перезаписываются.
Успех подготовки означает PREPARED, nativeMatrix остаётся PENDING до реального runner.
.EXAMPLE
# В PowerShell 7: пути и pins MAIN получает от уже проверенных, замороженных артефактов.
$prepared = & .github/scripts/New-UpdateBootstrapCommands.ps1 @approvedInputs
$runnerParameters = $prepared.RunnerParameters
& .github/scripts/Test-UpdateBootstrap.ps1 @runnerParameters
# approvedInputs: PortableDir (1-2 base), TargetPortableDir, Runtime, RuntimeSha256,
# JdkModulesSha256, CoreJar, CoreJarSha256, ToolJar, ToolJarSha256.
# OutputDirectory необязателен; если задан, это ещё не существующий Temp/run-UUID.
# Helper не запускается во время подготовки. GUI/native runner вызывается отдельно MAIN.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$PortableDir,
    [Parameter(Mandatory)][string]$TargetPortableDir,
    [Parameter(Mandatory)][string]$Runtime,
    [Parameter(Mandatory)][string]$RuntimeSha256,
    [Parameter(Mandatory)][string]$JdkModulesSha256,
    [Parameter(Mandatory)][string]$CoreJar,
    [Parameter(Mandatory)][string]$CoreJarSha256,
    [Parameter(Mandatory)][string]$ToolJar,
    [Parameter(Mandatory)][string]$ToolJarSha256,
    [string]$OutputDirectory,
    [ValidateRange(10,300)][int]$CommandTimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

# Импортируем только выбранные определения AST, никогда не исполняем чужой runner/release.
function Import-BootstrapBuilderFunctions([string]$Path,[string[]]$Names) {
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'BOOTCMD_IMPORT_PARSE' }
    # У runner импортируем все определения: новые приватные guards сохраняют свои зависимости.
    if (-not $Names) {
        $Names=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$true) |
            ForEach-Object {$_.Name} | Select-Object -Unique)
    }
    foreach ($name in $Names) {
        $matches=@($ast.FindAll({param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
        },$true))
        if ($matches.Count -ne 1) { throw "BOOTCMD_IMPORT_FUNCTION $name" }
        $definition=$matches[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
        . ([scriptblock]::Create($definition))
    }
}

# Отвергаем небезопасный ввод, не исправляем dot segments/относительные пути за caller.
function Resolve-BootstrapBuilderInput([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathFullyQualified($Path) -or
        $Path -match '[;\x00-\x1f\x7f-\x9f]' -or $Path.StartsWith('\\') -or
        -not $Path.IsNormalized([Text.NormalizationForm]::FormC)) { throw 'BOOTCMD_ABSOLUTE_PATH' }
    $canonical=Resolve-PortableSafetyPath $Path
    if (-not $canonical.Equals($Path.TrimEnd([char[]]'\/'),[StringComparison]::OrdinalIgnoreCase) -or
        $canonical.Substring(2).Contains(':') -or $canonical -match '(?:^|[\\/])[^\\/]*[. ](?:[\\/]|$)') {
        throw 'BOOTCMD_CANONICAL_PATH'
    }
    return $canonical
}

# Ожидаемый hash приходит от MAIN; наблюдаемый digest не подменяет предварительный pin.
function Get-BootstrapBuilderPin([string]$Path,[string]$Hash) {
    if ($Hash -cnotmatch '^[0-9a-f]{64}$') { throw 'BOOTCMD_PIN_FORMAT' }
    $resolved=Resolve-BootstrapBuilderInput $Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
        (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Hash) {
        throw 'BOOTCMD_PIN'
    }
    return [pscustomobject]@{path=$resolved;sha256=$Hash}
}

# Повторяем pins перед каждой командой и перед выдачей готового JSON.
function Assert-BootstrapBuilderPins($Pins) {
    foreach ($pin in $Pins) { [void](Get-BootstrapBuilderPin $pin.path $pin.sha256) }
}

# Digest выходного файла вычисляется только после фактического появления ограниченного отчёта.
function Get-BootstrapBuilderProducedPin([string]$Path) {
    [void](Resolve-BootstrapBuilderInput $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'BOOTCMD_REPORT_MISSING' }
    if ((Get-Item -LiteralPath $Path).Length -gt 8MB) { throw 'BOOTCMD_METADATA_LIMIT' }
    return (Get-BootstrapBuilderPin $Path ((Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()))
}

# Проверяем наличие реальных собранных классов API, но не объявляем это запуском JVM.
function Assert-BootstrapBuilderJar([string]$Path,[string[]]$Classes) {
    if ([IO.Path]::GetExtension($Path) -cne '.jar') { throw 'BOOTCMD_JAR_EXTENSION' }
    $archive=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($name in @('module-info.class')+@($Classes)) {
            $entries=@($archive.Entries | Where-Object {$_.FullName -ceq $name})
            if ($entries.Count -ne 1 -or $entries[0].Length -lt 4 -or $entries[0].Length -gt 2MB) { throw 'BOOTCMD_JAR_API_MISSING' }
            $stream=$entries[0].Open()
            try {
                $bytes=[byte[]]::new(4)
                $stream.ReadExactly($bytes,0,4)
                if ([Convert]::ToHexString($bytes) -cne 'CAFEBABE') { throw 'BOOTCMD_JAR_CLASS' }
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

# Общая portable защита остаётся единственным источником проверки ссылок/CashMemory/layout.
function Get-BootstrapBuilderImage([string]$Path) {
    $root=Resolve-BootstrapBuilderInput $Path
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw 'BOOTCMD_IMAGE_DIRECTORY' }
    Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $root -Force)
    Assert-PortableTreeHasNoLinks $root
    Assert-ColdNativeImage $root
    $version=Get-ColdVersion $root
    # Get-ColdVersion используется неизменённым; дополнительно отказываем дублированной metadata.
    $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path (Join-Path $root 'app') $version.jar))
    try {
        $entries=@($archive.Entries | Where-Object {$_.FullName -ceq 'ru/cashprediction/core/app.properties'})
        if ($entries.Count -ne 1) { throw 'BOOTCMD_VERSION_DUPLICATE' }
        $reader=[IO.StreamReader]::new($entries[0].Open(),[Text.UTF8Encoding]::new($false,$true))
        try { $properties=$reader.ReadToEnd() } finally { $reader.Dispose() }
        foreach ($key in 'release','commit') {
            if ([regex]::Matches($properties,('(?m)^'+$key+'\s*[:=]')).Count -ne 1) { throw 'BOOTCMD_VERSION_DUPLICATE' }
        }
    } finally { $archive.Dispose() }
    foreach ($name in 'CashPrediction','CashPrediction-Swing','CashPrediction-Web') {
        [void](Get-ColdMainModule ([IO.File]::ReadAllText((Join-Path $root "app/$name.cfg"))))
    }
    return [pscustomobject]@{root=$root;version=$version}
}

# Устаревшие/дублированные базы и пересечение каталогов не образуют независимую матрицу.
function Assert-BootstrapBuilderImages($Bases,$Target) {
    if (@($Bases).Count -lt 1 -or @($Bases).Count -gt 2) { throw 'BOOTCMD_BASE_COUNT' }
    $roots=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $releases=[Collections.Generic.HashSet[int]]::new()
    foreach ($image in @($Bases)+@($Target)) {
        if (-not $roots.Add($image.root)) { throw 'BOOTCMD_DUPLICATE_ROOT' }
        foreach ($other in @($Bases)+@($Target)) {
            if ($image.root -cne $other.root -and (Test-PortablePathContains $image.root $other.root)) { throw 'BOOTCMD_SOURCE_OVERLAP' }
        }
    }
    foreach ($base in $Bases) {
        if ($base.version.releaseNumber -ge $Target.version.releaseNumber -or
            $base.version.commitSha -ceq $Target.version.commitSha) { throw 'BOOTCMD_RELEASE_ORDER' }
        if (-not $releases.Add($base.version.releaseNumber)) { throw 'BOOTCMD_DUPLICATE_RELEASE' }
    }
}

# Новый каталог только в Temp UUID; общие каталоги и любые пересечения запрещены до записи.
function New-BootstrapBuilderDirectory([string]$Path,[string[]]$Protected,[string]$Project) {
    $tempRoot=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    $path=Resolve-BootstrapBuilderInput $Path
    if (-not [IO.Path]::GetDirectoryName($path).Equals($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($path) -cnotmatch '^run-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw 'BOOTCMD_TEMP_UUID_REQUIRED'
    }
    foreach ($input in $Protected) {
        [void](Get-ValidatedPortablePaths $input $path $Project ([Environment]::GetFolderPath('UserProfile')))
    }
    if (Test-Path -LiteralPath $path) { throw 'BOOTCMD_OUTPUT_EXISTS' }
    [void](New-Item -ItemType Directory -Path $path -ErrorAction Stop)
    return $path
}

# CreateNew не позволяет затереть чужой или уже выданный артефакт.
function Write-BootstrapBuilderText([string]$Path,[string]$Text) {
    [void](Resolve-BootstrapBuilderInput $Path)
    if (Test-Path -LiteralPath $Path) { throw 'BOOTCMD_OUTPUT_EXISTS' }
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes($Text)
    if ($bytes.Length -gt 8MB) { throw 'BOOTCMD_METADATA_LIMIT' }
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None,
        4096,[IO.FileOptions]::WriteThrough)
    try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
}

# Останавливаем только удерживаемый собственный Process с совпадением PID/birth/path.
function Assert-BootstrapBuilderProcessIdentity($Expected,$Actual) {
    if ($Expected.pid -le 0 -or $Expected.ticks -le 0 -or $Expected.pid -ne $Actual.pid -or
        $Expected.ticks -ne $Actual.ticks -or -not $Expected.path.Equals($Actual.path,[StringComparison]::OrdinalIgnoreCase)) {
        throw ('BOOTCMD_PROCESS_IDENTITY expected='+ (ConvertTo-Json -Compress -InputObject $Expected)+
            ' actual='+ (ConvertTo-Json -Compress -InputObject $Actual))
    }
}

# Только ранний системный loader/null допускает ожидание; PID/birth проверяются уже здесь.
function Get-BootstrapBuilderIdentityState($Expected,$Actual) {
    Assert-BootstrapBuilderProcessIdentity $Expected ([pscustomobject]@{
        pid=$Actual.pid;ticks=$Actual.ticks;path=$Expected.path
    })
    if ($Expected.path.Equals($Actual.path,[StringComparison]::OrdinalIgnoreCase)) { return 'READY' }
    $loader=Join-Path ([Environment]::GetFolderPath('System')) 'ntdll.dll'
    if ($null -eq $Actual.path -or $loader.Equals($Actual.path,[StringComparison]::OrdinalIgnoreCase)) { return 'STARTING' }
    Assert-BootstrapBuilderProcessIdentity $Expected $Actual
}

# Refresh устраняет ранний кеш Modules; завершившийся процесс не даёт права на Kill.
function Read-BootstrapBuilderProcessIdentity($Process) {
    $Process.Refresh()
    if ($Process.HasExited) { return $null }
    $startedAt=$Process.StartTime.ToUniversalTime().Ticks
    $module=$Process.MainModule
    if ($Process.HasExited) { return $null }
    return [pscustomobject]@{pid=$Process.Id;ticks=$startedAt;path=$(if ($null -eq $module) {$null} else {$module.FileName})}
}

# Ожидание заканчивается на expected exe/exit, через 5 секунд или раньше по общему deadline.
# Иное готовое имя сразу отвергается, ntdll не является успешной executable identity.
function Wait-BootstrapBuilderProcessIdentity($Process,$Expected,$Clock,[double]$TimeoutSeconds) {
    $identityDeadline=[Math]::Min($Clock.Elapsed.TotalSeconds+5,$TimeoutSeconds)
    while ($true) {
        if ($Clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw 'BOOTCMD_PROCESS_TIMEOUT' }
        if ($Clock.Elapsed.TotalSeconds -ge $identityDeadline) { throw 'BOOTCMD_STARTUP_IDENTITY_TIMEOUT' }
        $actual=Read-BootstrapBuilderProcessIdentity $Process
        if ($null -eq $actual) { return $null }
        $state=Get-BootstrapBuilderIdentityState $Expected $actual
        if ($Clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw 'BOOTCMD_PROCESS_TIMEOUT' }
        if ($Clock.Elapsed.TotalSeconds -ge $identityDeadline) { throw 'BOOTCMD_STARTUP_IDENTITY_TIMEOUT' }
        if ($state -ceq 'READY') { return $actual }
        $remaining=[Math]::Max(1,[Math]::Min(25,($identityDeadline-$Clock.Elapsed.TotalSeconds)*1000))
        [void]$Process.WaitForExit([int]$remaining)
    }
}

# Точный ASCII transport actual UpdateTool: не argfile и не изменение module/main arguments.
function Get-BootstrapBuilderToolTransport([string[]]$LogicalArguments) {
    if (@($LogicalArguments).Count -lt 1 -or $LogicalArguments.Count -gt 64 -or
        $LogicalArguments[0] -ceq '--arguments-base64') { throw 'BOOTCMD_TOOL_ARGUMENTS' }
    foreach ($argument in $LogicalArguments) {
        if ($null -eq $argument -or $argument.Length -gt 32768 -or $argument.Contains([char]0)) { throw 'BOOTCMD_TOOL_ARGUMENTS' }
    }
    $json=ConvertTo-Json -InputObject ([string[]]$LogicalArguments) -Depth 3 -Compress
    try { $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes($json) }
    catch { throw 'BOOTCMD_TOOL_ARGUMENT_ENCODING' }
    $payload=[Convert]::ToBase64String($bytes)
    if ($payload.Length -gt 262144) { throw 'BOOTCMD_TOOL_ARGUMENTS_LIMIT' }
    return @('--arguments-base64',$payload)
}

# Один ограниченный процесс без shell/дочерней JVM; stdout/stderr <=65536 UTF-16 символов каждый.
function Invoke-BootstrapBuilderProcess([string]$Executable,[string[]]$Arguments,[string]$Directory,
    [int]$TimeoutSeconds,$Pins,[string]$Log) {
    Assert-BootstrapBuilderPins $Pins
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=Resolve-BootstrapBuilderInput $Executable
    $start.WorkingDirectory=$Directory; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach ($name in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH') { [void]$start.Environment.Remove($name) }
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$start
    $expected=$null; $clock=[Diagnostics.Stopwatch]::StartNew()
    try {
        if (-not $process.Start()) { throw 'BOOTCMD_PROCESS_START' }
        $process.StandardInput.Close()
        if (-not $process.HasExited) {
            $startedAt=$process.StartTime.ToUniversalTime().Ticks
            $expected=[pscustomobject]@{pid=$process.Id;ticks=$startedAt;path=$start.FileName}
            [void](Wait-BootstrapBuilderProcessIdentity $process $expected $clock $TimeoutSeconds)
        }
        $streams=@($process.StandardOutput,$process.StandardError)
        $buffers=@([char[]]::new(4096),[char[]]::new(4096))
        $texts=@([Text.StringBuilder]::new(),[Text.StringBuilder]::new())
        $pending=@($streams[0].ReadAsync($buffers[0],0,4096),$streams[1].ReadAsync($buffers[1],0,4096))
        while (-not $process.HasExited -or $null -ne $pending[0] -or $null -ne $pending[1]) {
            if ($clock.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw 'BOOTCMD_PROCESS_TIMEOUT' }
            for ($i=0;$i -lt 2;$i++) {
                if ($null -ne $pending[$i] -and $pending[$i].IsCompleted) {
                    $count=$pending[$i].GetAwaiter().GetResult()
                    if ($count -eq 0) { $pending[$i]=$null; continue }
                    if ($texts[$i].Length+$count -gt 65536) { throw 'BOOTCMD_PROCESS_OUTPUT_LIMIT' }
                    [void]$texts[$i].Append($buffers[$i],0,$count)
                    $pending[$i]=$streams[$i].ReadAsync($buffers[$i],0,4096)
                }
            }
            $tasks=@($pending | Where-Object {$null -ne $_})
            if ($tasks.Count) { [void][Threading.Tasks.Task]::WaitAny([Threading.Tasks.Task[]]$tasks,25) }
            else { [void]$process.WaitForExit(25) }
        }
        Write-BootstrapBuilderText $Log (ConvertTo-Json -Depth 10 -InputObject ([ordered]@{
            executable=$start.FileName;arguments=$Arguments;exitCode=$process.ExitCode;
            stdout=$texts[0].ToString();stderr=$texts[1].ToString()
        }))
        if ($process.ExitCode -ne 0) { throw 'BOOTCMD_PROCESS_FAILED' }
    } finally {
        if ($null -ne $expected -and -not $process.HasExited) {
            $actual=Read-BootstrapBuilderProcessIdentity $process
            if ($null -ne $actual -and -not $process.HasExited) {
                Assert-BootstrapBuilderProcessIdentity $expected $actual
                $process.Kill()
                if (-not $process.WaitForExit(5000)) { throw 'BOOTCMD_PROCESS_CLEANUP_TIMEOUT' }
            }
        }
        $process.Dispose()
    }
}

# Никакого вывода Java text blocks: helper публикуется API из закреплённого compiled core jar.
# Путь передаётся как Base64 UTF-8, чтобы quotes/Unicode не стали Java командами.
function Get-BootstrapBuilderExportSource([string]$Updates) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Resolve-BootstrapBuilderInput $Updates)))
    return @'
/** Временный вызов API публикации helper, не клиент и не часть portable сборки. */
final class BootstrapHelperExport {
    /** Публикует настоящий helper; исключение или ошибка компиляции даёт ненулевой exit JDK. */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length != 0) throw new IllegalArgumentException("BOOTCMD_EXPORT_ARGUMENTS");
        ru.cashprediction.core.update.install.PowerShellHelper.publish(java.nio.file.Path.of(new String(
            java.util.Base64.getDecoder().decode("BASE64_UPDATES"), java.nio.charset.StandardCharsets.UTF_8)));
    }
}
'@.Replace('BASE64_UPDATES',$encoded)
}

# Детерминированный full ZIP только из проверенного inventory, без копирования пользовательских данных.
function New-BootstrapBuilderArchive([string]$Root,$Files,[string]$Path) {
    if (@($Files).Count -lt 1 -or @($Files).Count -gt 20000) { throw 'BOOTCMD_FILES_LIMIT' }
    $total=0L
    foreach ($file in $Files) {
        Assert-S7Path $file.path
        if ($file.sizeBytes -lt 0 -or $file.sizeBytes -gt 512MB) { throw 'BOOTCMD_FILE_LIMIT' }
        $total+=$file.sizeBytes
        if ($total -gt 2GB) { throw 'BOOTCMD_TREE_LIMIT' }
    }
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true,[Text.Encoding]::UTF8)
        try {
            foreach ($file in $Files) {
                $entry=$archive.CreateEntry('CashPrediction/'+$file.path,[IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime=[DateTimeOffset]::new(2000,1,1,0,0,0,[TimeSpan]::Zero)
                $entry.ExternalAttributes=[int][bool]$file.readOnly
                $destination=$entry.Open(); $source=[IO.File]::OpenRead((Join-Path $Root $file.path))
                try { $source.CopyTo($destination) } finally { $source.Dispose(); $destination.Dispose() }
                if ($stream.Length -gt 512MB) { throw 'BOOTCMD_ARCHIVE_LIMIT' }
            }
        } finally { $archive.Dispose() }
        if ($stream.Length -gt 512MB) { throw 'BOOTCMD_ARCHIVE_LIMIT' }
        $stream.Flush($true)
    } finally { $stream.Dispose() }
}

# Отсутствующий отчёт или неверная identity после exit0 не считается успешной командой.
function Read-BootstrapBuilderManifest([string]$Path,$Image,[string]$Archive,[string]$Published) {
    $pin=Get-BootstrapBuilderProducedPin $Path
    $manifest=Read-ColdPinnedJson $pin.path $pin.sha256
    Assert-ColdKeys $manifest @('schemaVersion','releaseNumber','commitSha','version','publishedAtUtc','assetName',
        'sizeBytes','sha256','treeSha256','files','deltaPatches')
    # PowerShell 7.5 автоматически превращает ISO Z в DateTime; прежние 7.x оставляют строку.
    $manifestPublished=$manifest.publishedAtUtc
    if ($manifestPublished -is [DateTime]) {
        $manifestPublished=$manifestPublished.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture)
    }
    if ($manifest.schemaVersion -ne 2 -or $manifest.releaseNumber -ne $Image.version.releaseNumber -or
        $manifest.commitSha -cne $Image.version.commitSha -or $manifest.version -cne [string]$Image.version.releaseNumber -or
        $manifestPublished -cne $Published -or $manifest.assetName -cne [IO.Path]::GetFileName($Archive) -or
        $manifest.sizeBytes -ne (Get-Item -LiteralPath $Archive).Length -or @($manifest.deltaPatches).Count -ne 0 -or
        $manifest.sha256 -cne (Get-FileHash -LiteralPath $Archive).Hash.ToLowerInvariant()) { throw 'BOOTCMD_MANIFEST_IDENTITY' }
    [void](Assert-ColdTree $Image.root $manifest)
    return [pscustomobject]@{value=$manifest;pin=$pin}
}

# Отдельная функция позволяет fixtures заменить только выполнение CLI, не добавить mock флаг к runner.
function New-BootstrapBuilderCommands([string[]]$Bases,[string]$Target,[string]$Java,[string]$JavaHash,
    [string]$ModulesHash,[string]$Core,[string]$CoreHash,[string]$Tool,[string]$ToolHash,
    [string]$Output,[string]$Project,[int]$TimeoutSeconds) {
    if (@($Bases).Count -lt 1 -or @($Bases).Count -gt 2) { throw 'BOOTCMD_BASE_COUNT' }
    $javaPin=Get-BootstrapBuilderPin $Java $JavaHash
    if ([IO.Path]::GetFileName($javaPin.path) -cne 'java.exe' -or
        [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($javaPin.path)) -cne 'bin') { throw 'BOOTCMD_FULL_JDK_REQUIRED' }
    $jdk=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($javaPin.path))
    $modulePin=Get-BootstrapBuilderPin (Join-Path $jdk 'lib/modules') $ModulesHash
    $release=[IO.File]::ReadAllText((Resolve-BootstrapBuilderInput (Join-Path $jdk 'release')))
    if ([regex]::Matches($release,'(?m)^JAVA_VERSION=').Count -ne 1 -or
        [regex]::Matches($release,'(?m)^JAVA_VERSION="25(?:[.+-][^"\r\n]*)?"\r?$').Count -ne 1) { throw 'BOOTCMD_JDK25_REQUIRED' }
    $corePin=Get-BootstrapBuilderPin $Core $CoreHash; $toolPin=Get-BootstrapBuilderPin $Tool $ToolHash
    if ($corePin.path.Equals($toolPin.path,[StringComparison]::OrdinalIgnoreCase)) { throw 'BOOTCMD_JAR_DUPLICATE' }
    Assert-BootstrapBuilderJar $corePin.path @('ru/cashprediction/core/update/install/PowerShellHelper.class',
        'ru/cashprediction/core/update/install/PortableBootstrap.class','ru/cashprediction/core/update/install/BootstrapScript.class')
    Assert-BootstrapBuilderJar $toolPin.path @('ru/cashprediction/updatetool/UpdateTool.class')
    $baseImages=@($Bases | ForEach-Object {Get-BootstrapBuilderImage $_})
    $targetImage=Get-BootstrapBuilderImage $Target
    Assert-BootstrapBuilderImages $baseImages $targetImage
    if ($targetImage.version.jarSha256 -cne $corePin.sha256) { throw 'BOOTCMD_TARGET_CORE_MISMATCH' }
    if (-not $Output) { $Output=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString()) }
    $images=@($baseImages)+@($targetImage)
    $directory=New-BootstrapBuilderDirectory $Output (@($images.root)+@($jdk,$corePin.path,$toolPin.path)) $Project
    try {
        $tools=Join-Path $directory 'tool'; [void][IO.Directory]::CreateDirectory($tools)
        $frozenCore=Join-Path $tools 'core.jar'; $frozenTool=Join-Path $tools 'update-tool.jar'
        [IO.File]::Copy($corePin.path,$frozenCore,$false); [IO.File]::Copy($toolPin.path,$frozenTool,$false)
        $toolPins=@((Get-BootstrapBuilderPin $frozenCore $corePin.sha256),(Get-BootstrapBuilderPin $frozenTool $toolPin.sha256))
        $pins=@($javaPin,$modulePin,$corePin,$toolPin)+$toolPins
        $toolArguments=@('--module-path',($frozenCore+';'+$frozenTool),'-m',
            'ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool')
        $export=Join-Path $directory 'BootstrapHelperExport.java'
        $updates=Join-Path $directory 'export/CashMemory/Updates'
        $jdkTemp=Join-Path $directory 'jdk-temp'; $jdkHome=Join-Path $directory 'jdk-home'
        [void][IO.Directory]::CreateDirectory($jdkTemp); [void][IO.Directory]::CreateDirectory($jdkHome)
        # Только preparation JVM получает собственные temp/home; контракт runner не расширяется.
        $javaOptions=@('-XX:+PerfDisableSharedMem',('-Djava.io.tmpdir='+$jdkTemp),('-Duser.home='+$jdkHome))
        $exportSource=Get-BootstrapBuilderExportSource $updates
        Write-BootstrapBuilderText $export $exportSource
        $exportHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($exportSource))).ToLowerInvariant()
        $pins+=@(Get-BootstrapBuilderPin $export $exportHash)
        Invoke-BootstrapBuilderProcess $javaPin.path ($javaOptions+@('--class-path',$frozenCore,$export)) `
            $directory $TimeoutSeconds $pins (Join-Path $directory 'export-log.json')
        $helper=Join-Path $updates 'apply-update.ps1'
        if (-not (Test-Path -LiteralPath $helper -PathType Leaf) -or (Get-Item -LiteralPath $helper).Length -gt 32MB -or
            (Get-Item -LiteralPath $helper).Length -eq 0) { throw 'BOOTCMD_HELPER_MISSING' }
        $helperPin=Get-BootstrapBuilderPin $helper ((Get-FileHash -LiteralPath $helper).Hash.ToLowerInvariant())
        $tokens=$null; $errors=$null
        [void][Management.Automation.Language.Parser]::ParseFile($helperPin.path,[ref]$tokens,[ref]$errors)
        if ($errors.Count) { throw 'BOOTCMD_HELPER_PARSE' }
        $published=[DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture)
        $records=@(); $targets=$null
        for ($i=0;$i -lt $images.Count;$i++) {
            $image=$images[$i]; $package=Join-Path $directory ('image-'+$i)
            [void][IO.Directory]::CreateDirectory($package)
            $inventoryPath=Join-Path $package 'inventory.json'
            $logicalArguments=@('inventory','--root',$image.root,'--out',$inventoryPath)
            Invoke-BootstrapBuilderProcess $javaPin.path ($javaOptions+$toolArguments+@(Get-BootstrapBuilderToolTransport $logicalArguments)) `
                $directory $TimeoutSeconds $pins (Join-Path $package 'inventory-log.json')
            $inventoryPin=Get-BootstrapBuilderProducedPin $inventoryPath
            $inventory=Read-ColdPinnedJson $inventoryPin.path $inventoryPin.sha256
            Assert-ColdKeys $inventory @('files','treeSha256')
            $files=@(Assert-ColdTree $image.root $inventory)
            $archive=Join-Path $package 'CashPrediction-portable.zip'
            New-BootstrapBuilderArchive $image.root $files $archive
            $manifestPath=Join-Path $package 'update.json'
            $logicalArguments=@('manifest','--root',$image.root,'--archive',$archive,
                '--release',[string]$image.version.releaseNumber,'--commit',$image.version.commitSha,
                '--version',[string]$image.version.releaseNumber,'--published-at',$published,'--out',$manifestPath)
            Invoke-BootstrapBuilderProcess $javaPin.path ($javaOptions+$toolArguments+@(Get-BootstrapBuilderToolTransport $logicalArguments)) `
                $directory $TimeoutSeconds $pins (Join-Path $package 'manifest-log.json')
            $checked=Read-BootstrapBuilderManifest $manifestPath $image $archive $published
            $logicalArguments=@('verify','--root',$image.root,'--manifest',$manifestPath)
            Invoke-BootstrapBuilderProcess $javaPin.path ($javaOptions+$toolArguments+@(Get-BootstrapBuilderToolTransport $logicalArguments)) `
                $directory $TimeoutSeconds $pins (Join-Path $package 'verify-log.json')
            $records+=@([pscustomobject]@{portableDir=$image.root;manifest=$checked.pin.path;sha256=$checked.pin.sha256})
            if ($i -eq $images.Count-1) { $targets=$checked }
        }
        for ($i=0;$i -lt $images.Count;$i++) {
            [void](Assert-ColdTree $images[$i].root (Read-ColdPinnedJson $records[$i].manifest $records[$i].sha256))
        }
        Assert-BootstrapBuilderPins (@($pins)+@($helperPin))
        $configuration=[pscustomobject][ordered]@{
            schemaVersion=1;runtimeSha256=$javaPin.sha256;toolArguments=$toolArguments;toolFiles=$toolPins;
            helperScript=$helperPin.path;helperSha256=$helperPin.sha256;baseManifests=@($records[0..($baseImages.Count-1)]);
            targetManifest=$targets.pin.path;targetManifestSha256=$targets.pin.sha256
        }
        Assert-ColdCommand $configuration $javaPin.path @($baseImages.root) $targetImage.root
        $commandPath=Join-Path $directory 'bootstrap-commands.json'
        Write-BootstrapBuilderText $commandPath (ConvertTo-Json -InputObject $configuration -Depth 32)
        $commandHash=(Get-FileHash -LiteralPath $commandPath).Hash.ToLowerInvariant()
        [void](Read-ColdPinnedJson $commandPath $commandHash)
        return [pscustomobject]@{status='PREPARED';nativeMatrix='PENDING';OutputDirectory=$directory;
            CommandFile=$commandPath;CommandFileSha256=$commandHash;RunnerParameters=@{
                PortableDir=[string[]]$baseImages.root;TargetPortableDir=$targetImage.root;Runtime=$javaPin.path;
                CommandFile=$commandPath;CommandFileSha256=$commandHash
            }}
    } catch {
        Write-Warning "BOOTCMD_FAILED_ARTIFACTS_RETAINED $directory"
        throw
    }
}

# Fixtures импортируют лишь функции. Настоящий вход не имеет mock/skip/force переключателей.
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) { throw 'BOOTCMD_WINDOWS_POWERSHELL7_REQUIRED' }
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'Test-Portable.ps1') @('Resolve-PortableSafetyPath',
    'Test-PortablePathContains','Get-ValidatedPortablePaths','Assert-PortableSourceEntries','Assert-PortableTreeHasNoLinks')
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1') @()
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'S7-Release.ps1') @('Assert-S7Path')
New-BootstrapBuilderCommands $PortableDir $TargetPortableDir $Runtime $RuntimeSha256 $JdkModulesSha256 `
    $CoreJar $CoreJarSha256 $ToolJar $ToolJarSha256 $OutputDirectory `
    ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))) $CommandTimeoutSeconds
