# Постоянные проверки настоящих предикатов; EXE не запускаются, файлы меняются только в собственной Temp-фикстуре.
# Файловые проверки ниже работают только в собственном UUID-каталоге Temp.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'Test-Portable.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Ошибка синтаксиса portable-скрипта.' }
foreach ($name in 'Resolve-PortableSafetyPath','Test-PortablePathContains','Get-ValidatedPortablePaths',
    'Get-PortableRegistryPath','Assert-PortableSourceEntries','Get-PortableCopyPlan',
    'Test-PortableSnapshot','Test-PortableListener','Get-PortableCleanupPath','Invoke-PortableLifecycle',
    'Assert-PortableTreeHasNoLinks','Get-PortableApplicationInventory','Assert-PortableApplicationUnchanged',
    'Open-PortableProcess','Stop-PortableProcess','Stop-CopyProcesses',
    'Export-PortableEvidence','Write-PortableDiagnostic','Fail') {
    $functions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($functions.Count -ne 1) { throw 'Не найден единственный настоящий safety-предикат.' }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$profilePath = [Environment]::GetFolderPath('UserProfile')
$portable = Join-Path $project 'dist/target/dist/CashPrediction'
$safe = Join-Path ([IO.Path]::GetTempPath()) ('cp-portable-fixture-' + [guid]::NewGuid().ToString())
$rejected = @([IO.Path]::GetPathRoot($project), $project, (Join-Path $project 'test-work'),
    [IO.Path]::GetDirectoryName($project), $portable, (Join-Path $portable 'test'),
    [IO.Path]::GetDirectoryName($portable), $profilePath, [IO.Path]::GetTempPath(), (Join-Path $project 'child/..'))
foreach ($work in $rejected) {
    $failed = $false
    try { $null = Get-ValidatedPortablePaths $portable $work $project $profilePath } catch { $failed = $true }
    if (-not $failed) { throw 'Опасная fixture-папка принята.' }
}
$validated = Get-ValidatedPortablePaths $portable $safe $project $profilePath
if ($validated.WorkDir -ne [IO.Path]::GetFullPath($safe)) { throw 'Потеря абсолютной нормализации.' }
if (Test-PortablePathContains $project ($project + '-backup')) { throw 'Префикс без границы каталога принят.' }
if (-not (Test-PortablePathContains $project.ToUpperInvariant() (Join-Path $project 'child'))) { throw 'Потеря сравнения Windows без учёта регистра.' }
# Чистые постоянные fixtures: ни процессов, ни HTTP, ни реестра, ни файлов для удаления.
function Assert-PortableFixture([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:portableFixtureCount++
}
function Assert-PortableFixtureRejects([scriptblock]$Action, [string]$Message) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-PortableFixture $failed $Message
}
$script:portableFixtureCount = 0
$ownNode = 'ru/cashprediction/selftest/01234567-89ab-cdef-0123-456789abcdef'
Assert-PortableFixture ((Get-PortableRegistryPath $ownNode) -eq 'HKCU:\Software\JavaSoft\Prefs\ru\cashprediction\selftest\01234567-89ab-cdef-0123-456789abcdef') 'Неверный собственный путь реестра.'
foreach ($node in @('ru/cashprediction','ru/cashprediction/session/fx','ru/cashprediction/selftest',
    ($ownNode + '/child'),($ownNode + '/..'),'ru/cashprediction/selftest/not-a-uuid',$ownNode.ToUpperInvariant())) {
    Assert-PortableFixtureRejects { Get-PortableRegistryPath $node } 'Чужой или широкий узел реестра принят.'
}
$entries = @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') | ForEach-Object {
    [pscustomobject]@{ Name=$_; PSIsContainer=$false; Attributes=[IO.FileAttributes]::Normal }
}
Assert-PortableSourceEntries $entries
$script:portableFixtureCount++
foreach ($exe in @($entries.Name)) {
    Assert-PortableFixtureRejects { Assert-PortableSourceEntries @($entries | Where-Object Name -ne $exe) } 'Сборка без одного EXE принята.'
}
Assert-PortableFixtureRejects { Assert-PortableSourceEntries @($entries + [pscustomobject]@{ Name='CashMemory'; PSIsContainer=$true; Attributes=[IO.FileAttributes]::Directory }) } 'Сборка с прежними данными принята.'
Assert-PortableFixtureRejects { Assert-PortableSourceEntries @($entries + [pscustomobject]@{ Name='runtime'; PSIsContainer=$true; Attributes=[IO.FileAttributes]::ReparsePoint }) } 'Сборка со ссылкой принята.'
Assert-PortableFixtureRejects { Assert-PortableSourceEntries @($entries + $entries[0]) } 'Дубликат launcher принят.'
$plan = @(Get-PortableCopyPlan)
Assert-PortableFixture ($plan.Count -eq 3 -and @($plan.Id | Sort-Object -Unique).Count -eq 3 -and @($plan.Relative | Sort-Object -Unique).Count -eq 3) 'Потеря трёх уникальных копий.'
Assert-PortableFixture ($plan[1].Relative.Contains('Мои программы') -and $plan[2].Relative.Contains([string][char]0x6D4B)) 'Потеря Unicode-категорий.'
$logs = @(foreach ($entry in $plan) { foreach ($client in 'fx','swing','web') { $entry.Id + '-' + $client } })
Assert-PortableFixture (@($logs | Sort-Object -Unique).Count -eq 9) 'Коллизия журналов девяти запусков.'
$started = [DateTimeOffset]::Parse('2026-10-03T00:00:00Z')
$validText = '<session client="fx" state="running" pid="731" startedAt="2026-10-03T00:00:01Z" savedAt="2026-10-03T00:00:02Z"/>'
[xml]$validXml = $validText
Assert-PortableFixture (Test-PortableSnapshot $validXml 'fx' $started @(731)) 'Свежий собственный снимок отклонён.'
Assert-PortableFixture (-not (Test-PortableSnapshot $validXml 'fx' $started @(999))) 'Чужой PID принят.'
Assert-PortableFixture (-not (Test-PortableSnapshot $validXml 'swing' $started @(731))) 'Чужой клиент принят.'
Assert-PortableFixture (-not (Test-PortableSnapshot $validXml 'fx' $started @())) 'Мёртвый процесс принят.'
foreach ($text in @($validText.Replace('00:00:01Z','00:00:00Z').Replace('2026-10-03T00:00:00Z','2026-10-02T00:00:00Z'),
    $validText.Replace('00:00:02Z','not-a-time'),$validText.Replace('running','closed'),$validText.Replace('session','other'))) {
    [xml]$bad = $text
    Assert-PortableFixture (-not (Test-PortableSnapshot $bad 'fx' $started @(731))) 'Несвежий или неверный снимок принят.'
}
$listener = [pscustomobject]@{ LocalPort=18765; LocalAddress='127.0.0.1'; State='Listen'; OwningProcess=731 }
Assert-PortableFixture (Test-PortableListener 18765 @(731) @($listener)) 'Собственный порт вне старого диапазона отклонён.'
Assert-PortableFixture (-not (Test-PortableListener 18765 @(999) @($listener))) 'Ответ чужого сервера принят.'
Assert-PortableFixture (-not (Test-PortableListener 8765 @(731) @($listener))) 'Другой порт принят.'
Assert-PortableFixture (-not (Test-PortableListener 18765 @() @($listener))) 'Порт без живого PID принят.'
Assert-PortableFixture (-not (Test-PortableListener 18765 @(731) @())) 'Отсутствующий listener принят.'
$foreign = [pscustomobject]@{ LocalPort=18765; LocalAddress='0.0.0.0'; State='Listen'; OwningProcess=999 }
Assert-PortableFixture (-not (Test-PortableListener 18765 @(731) @($listener,$foreign))) 'Смешанная принадлежность порта принята.'
$container = Join-Path ([IO.Path]::GetTempPath()) 'cp-portable-cleanup-fixture'
$run = Join-Path $container 'run-01234567-89ab-cdef-0123-456789abcdef'
Assert-PortableFixture ((Get-PortableCleanupPath $portable $run $container $project $profilePath) -eq [IO.Path]::GetFullPath($run)) 'Собственный run отклонён.'
foreach ($badRun in @($container,(Join-Path $container 'run-not-a-uuid'),(Join-Path $container 'other/run-01234567-89ab-cdef-0123-456789abcdef'),$project)) {
    Assert-PortableFixtureRejects { Get-PortableCleanupPath $portable $badRun $container $project $profilePath } 'Чужой каталог очистки принят.'
}
# Настоящий обход дерева с подставленными записями: ссылка отклоняется до попытки входа в неё.
& {
    $script:treeFixtureCalls = 0
    function Get-ChildItem {
        param($LiteralPath, [switch]$Force)
        $script:treeFixtureCalls++
        return [pscustomobject]@{ Name='junction'; FullName=($LiteralPath + '\junction'); PSIsContainer=$true; Attributes=[IO.FileAttributes]::ReparsePoint }
    }
    Assert-PortableFixtureRejects { Assert-PortableTreeHasNoLinks $safe } 'Очистка следует через junction.'
    Assert-PortableFixture ($script:treeFixtureCalls -eq 1) 'Обход вошёл внутрь запрещённой ссылки.'
}
& {
    function Get-ChildItem { param($LiteralPath, [switch]$Force); return @() }
    Assert-PortableTreeHasNoLinks $safe
    $script:portableFixtureCount++
}
# Настоящее открытие helper: Handle приобретается до возврата; при сбое он освобождается.
foreach ($openVariant in 'success','handle-error','acquire-error') {
    $script:handleEvents = [System.Collections.Generic.List[string]]::new()
    $fakeHandle = [pscustomobject]@{}
    $fakeHandle | Add-Member ScriptProperty Handle {
        $script:handleEvents.Add('handle')
        if ($openVariant -eq 'handle-error') { throw 'fixture-handle-error' }
        return [intptr]731
    }
    $fakeHandle | Add-Member ScriptMethod Dispose { $script:handleEvents.Add('dispose') }
    $openFailed = $false
    try {
        $opened = Open-PortableProcess 731 {
            param($Id)
            if ($Id -ne 731) { throw 'Неверный PID приобретения.' }
            $script:handleEvents.Add('acquire')
            if ($openVariant -eq 'acquire-error') { throw 'fixture-acquire-error' }
            return $fakeHandle
        }
        Assert-PortableFixture ([object]::ReferenceEquals($opened,$fakeHandle)) 'Helper возвратил другой объект Process.'
    } catch { $openFailed = $true }
    $expectedEvents = switch ($openVariant) {
        'success' { 'acquire,handle' }
        'handle-error' { 'acquire,handle,dispose' }
        'acquire-error' { 'acquire' }
    }
    Assert-PortableFixture (($script:handleEvents -join ',') -eq $expectedEvents) "Неверный порядок приобретения handle/Dispose ($openVariant): $($script:handleEvents -join ',')."
    Assert-PortableFixture ($openFailed -eq ($openVariant -ne 'success')) 'Ошибка приобретения дескриптора скрыта.'
}
# Настоящая остановка с подставленным удерживаемым Process: ни одного процесса ОС не открываем.
# Повторное использование PID ПОСЛЕ открытия моделируется изменением таблицы PID;
# Kill всё равно вызывается у первоначального объекта, а не у replacement.
foreach ($variant in 'own','reused','foreign','exited','exits-before-kill','kill-error','path-error','reused-after-open') {
    & {
        $WorkDir = $run
        $copyPath = Join-Path $run 'plain/CashPrediction'
        $script:processFixturePolls = 0
        $script:processFixtureStops = 0
        $script:processFixtureDisposes = 0
        $script:replacementStops = 0
        $birth = [datetime]'2026-10-03T00:00:00Z'
        function Get-CopyProcesses {
            param($Directory)
            $script:processFixturePolls++
            if ($script:processFixturePolls -eq 1) { return [pscustomobject]@{ ProcessId=731; CreationDate=$birth } }
            return @()
        }
        function Open-PortableProcess {
            param($ProcessId)
            $fake = [pscustomobject]@{
                HasExited=($variant -eq 'exited');
                StartTime=$(if ($variant -eq 'reused') {$birth.AddSeconds(1)} else {$birth.AddTicks(1)});
                MainModule=[pscustomobject]@{ FileName=$(if ($variant -eq 'foreign') {Join-Path $project 'foreign.exe'} else {Join-Path $copyPath 'runtime/bin/java.exe'}) }
            }
            if ($variant -eq 'path-error') {
                $fake | Add-Member ScriptProperty MainModule { throw 'fixture-path-error' } -Force
            }
            if ($variant -eq 'exits-before-kill') {
                $fake | Add-Member ScriptProperty MainModule { $this.HasExited=$true; return [pscustomobject]@{ FileName=(Join-Path $copyPath 'runtime/bin/java.exe') } } -Force
            }
            $fake | Add-Member ScriptMethod Kill {
                if ($variant -eq 'kill-error') { throw 'fixture-kill-error' }
                $script:processFixtureStops++
            }
            $fake | Add-Member ScriptMethod Dispose { $script:processFixtureDisposes++ }
            if ($variant -eq 'reused-after-open') {
                # Любой последующий числовой поиск/Stop обнаружит replacement, а не fake.
                $script:replacementPresent = $true
            }
            return $fake
        }
        function Stop-Process { param($Id,[switch]$Force,$ErrorAction); $script:replacementStops++; throw 'Числовое завершение PID запрещено.' }
        function Start-Sleep { param($Milliseconds) }
        Stop-CopyProcesses $copyPath
        Assert-PortableFixture ($script:processFixtureStops -eq $(if ($variant -in 'own','reused-after-open') {1} else {0})) 'Остановка чужого, завершённого или повторно использованного PID.'
        Assert-PortableFixture ($script:processFixtureDisposes -eq 1 -and $script:replacementStops -eq 0) 'Потерян Dispose либо выполнено числовое завершение.'
        Assert-PortableFixtureRejects { Stop-CopyProcesses $project } 'Чужой процессный каталог принят.'
    }
}
# Экспорт настоящим helper с подставленными файловыми cmdlet: ничего не создаётся на диске.
. (Join-Path $PSScriptRoot 'Protect-GateText.ps1')
& {
    $script:evidenceWrites = [System.Collections.Generic.List[object]]::new()
    function Test-Path { param($LiteralPath,$PathType); return ($LiteralPath -eq (Join-Path $run 'logs')) }
    function Assert-PortableTreeHasNoLinks { param($Directory) }
    function Get-ChildItem {
        param($LiteralPath,[switch]$Force,[switch]$File)
        return @(
            [pscustomobject]@{ Name='unicode-CashPrediction-Web.direct.err.txt'; FullName='fake-log' },
            [pscustomobject]@{ Name='private-plan.md'; FullName='never-read' })
    }
    function New-Item { param($ItemType,$Path,[switch]$Force); return $null }
    function Get-Content { param($LiteralPath,[switch]$Raw,$Encoding); if ($LiteralPath -ne 'fake-log') {throw 'Чтение постороннего файла.'}; return 'GET /?token=secret-value Authorization: Bearer secret-header' }
    function Set-Content {
        param($LiteralPath,$Encoding,[Parameter(ValueFromPipeline=$true)]$Value)
        process { $script:evidenceWrites.Add([pscustomobject]@{Path=$LiteralPath; Text=$Value}) }
    }
    Export-PortableEvidence (Join-Path $run 'logs') $safe
    Assert-PortableFixture ($script:evidenceWrites.Count -eq 1) 'Не экспортирован журнал либо экспортированы посторонние файлы.'
    Assert-PortableFixture ($script:evidenceWrites[0].Path -eq (Join-Path $safe 'unicode-CashPrediction-Web.direct.err.txt')) 'Изменено уникальное имя Unicode-копии.'
    Assert-PortableFixture ($script:evidenceWrites[0].Text -notmatch 'secret-value|secret-header' -and $script:evidenceWrites[0].Text -match '\[redacted\]') 'Evidence содержит секреты.'
    $diagnosticLines = [System.Collections.Generic.List[string]]::new()
    $failures = [System.Collections.Generic.List[string]]::new()
    $script:diagnosticConsole = [System.Collections.Generic.List[string]]::new()
    function Write-Host { param($Object); $script:diagnosticConsole.Add([string]$Object) }
    Write-PortableDiagnostic 'Launcher exit: 23; cfg: ?token=diagnostic-secret'
    Fail 'missing session-fx.xml; Authorization: Bearer failure-secret'
    Assert-PortableFixture ($failures.Count -eq 1 -and $diagnosticLines.Count -eq 2) 'Потеря сообщения сбоя или кода выхода.'
    Assert-PortableFixture (($script:diagnosticConsole -join ',') -notmatch 'diagnostic-secret|failure-secret') 'Диагностическая консоль раскрывает секреты.'
    $script:evidenceWrites.Clear()
    Export-PortableEvidence (Join-Path $run 'logs') $safe $diagnosticLines
    Assert-PortableFixture ($script:evidenceWrites.Count -eq 2) 'Нет stdout/stderr и диагностического отчёта вместе.'
    $report = $script:evidenceWrites[1]
    Assert-PortableFixture ($report.Path -eq (Join-Path $safe 'portable-diagnostics.txt') -and $report.Text -match 'Launcher exit: 23' -and $report.Text -match 'missing session-fx.xml') 'Отчёт потерял код выхода/cfg/причину сбоя.'
    Assert-PortableFixture ($report.Text -notmatch 'diagnostic-secret|failure-secret' -and $report.Text -match '\[redacted\]') 'Отчёт сбоя не очищен.'
    $script:evidenceWrites.Clear()
    Export-PortableEvidence (Join-Path $run 'logs-not-created') $safe $diagnosticLines
    Assert-PortableFixture ($script:evidenceWrites.Count -eq 1 -and $script:evidenceWrites[0].Path -eq (Join-Path $safe 'portable-diagnostics.txt')) 'Ошибка до появления stdout/stderr потеряла диагностический отчёт.'
    function Set-Content { param($LiteralPath,$Encoding,[Parameter(ValueFromPipeline=$true)]$Value); process { throw 'fixture-evidence-write-failure' } }
    Assert-PortableFixtureRejects { Export-PortableEvidence (Join-Path $run 'logs-not-created') $safe $diagnosticLines } 'Ошибка записи evidence скрыта.'
}
# Реальный lifecycle: очистка вызывается ровно один раз и при ошибке работы, и при штатном выходе.
$events = [System.Collections.Generic.List[string]]::new()
Invoke-PortableLifecycle { $events.Add('work') } { $events.Add('cleanup') }
Assert-PortableFixture (($events -join ',') -eq 'work,cleanup') 'Потеря штатного cleanup.'
$events.Clear()
$failureMessage = ''
try { Invoke-PortableLifecycle { $events.Add('work'); throw 'fixture-start-failure' } { $events.Add('cleanup') } }
catch { $failureMessage = $_.Exception.Message }
Assert-PortableFixture (($events -join ',') -eq 'work,cleanup' -and $failureMessage -eq 'fixture-start-failure') 'Ошибка старта обошла cleanup либо скрыта.'
Assert-PortableFixtureRejects { Invoke-PortableLifecycle {} { throw 'fixture-cleanup-failure' } } 'Неудача cleanup скрыта за PASS.'
# AST-проверки подключения: все реальные Start-Process используют UUID, а уничтожение корня реестра отсутствует.
$launches = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Start-Process' }, $true))
Assert-PortableFixture ($launches.Count -eq 3) 'Изменился состав обычных и диагностического запусков.'
foreach ($launch in $launches) {
    $text = $launch.Extent.Text
    if ($text.Contains('$javaArgs')) {
        $diagnostics = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Write-Diagnostics' }, $true))[0].Extent.Text
        Assert-PortableFixture ($diagnostics.Contains('--registry-node') -and $diagnostics.Contains('New-PortableRegistryNode')) 'Диагностика без изоляции реестра.'
    } else { Assert-PortableFixture ($text.Contains('--registry-node') -and $text.Contains('$node')) 'EXE без UUID реестра.' }
}
$removals = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Remove-Item' }, $true))
Assert-PortableFixture ($removals.Count -eq 2) 'Неожиданные точки удаления.'
foreach ($removal in $removals) {
    Assert-PortableFixture ($removal.Extent.Text.Contains('-LiteralPath $key') -or $removal.Extent.Text.Contains('-LiteralPath $cleanup')) 'Удаление без проверенного точного пути.'
    Assert-PortableFixture (-not $removal.Extent.Text.Contains('HKCU:')) 'Широкое прямое удаление реестра.'
}
$finalizers = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.TryStatementAst] -and $null -ne $node.Finally }, $true))
Assert-PortableFixture ($finalizers.Count -ge 5) 'Потеря гарантированных lifecycle-finally.'
$sourceText = $ast.Extent.Text
$parameterNames = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
Assert-PortableFixture ($parameterNames -contains 'EvidenceRoot') 'Параметр EvidenceRoot не объявлен.'
$diagnosticsText = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Write-Diagnostics' }, $true))[0].Extent.Text
Assert-PortableFixture ($diagnosticsText.Contains('Write-PortableDiagnostic "cfg:"') -and $diagnosticsText.Contains('Код выхода лаунчера:') -and -not $diagnosticsText.Contains('Write-Host')) 'Диагностика не направлена в сохраняемый отчёт.'
Assert-PortableFixture ($sourceText.Contains('$runCreated = $false') -and $sourceText.Contains('$runCreated = $true') -and
    $sourceText.Contains('$runCreated -and (Test-Path -LiteralPath $cleanup)')) 'Удаление run без успешного создания самим тестом.'
$numericStops = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Stop-Process' }, $true))
Assert-PortableFixture ($numericStops.Count -eq 0) 'Обнаружена остановка через числовой PID.'
$openText = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Open-PortableProcess' }, $true))[0].Extent.Text
Assert-PortableFixture ($openText.Contains('$retained.Handle') -and $openText.Contains('$retained.Dispose()')) 'Дескриптор не удерживается либо не освобождается при ошибке открытия.'
Assert-PortableFixture ($sourceText.IndexOf('Export-PortableEvidence (Join-Path $WorkDir') -lt $sourceText.IndexOf('Remove-Item -LiteralPath $cleanup')) 'Evidence сохраняется после удаления run.'
Assert-PortableFixture ($sourceText.Contains('Test-PortablePathContains $workContainer $EvidenceRoot') -and $sourceText.Contains('Get-ValidatedPortablePaths $PortableDir $EvidenceRoot')) 'Evidence не отделён от удаляемого/защищённого пути.'
# Реальные файлы в отдельном UUID-каталоге; никакой сборки или запуска приложения.
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-portable-inventory-' + [guid]::NewGuid().ToString())
$fixtureRoot = Resolve-PortableSafetyPath $fixtureRoot
if ((Test-Path -LiteralPath $fixtureRoot) -or
    [IO.Path]::GetFileName($fixtureRoot) -cnotmatch '^cp-portable-inventory-[0-9a-f-]{36}$' -or
    -not [IO.Path]::GetDirectoryName($fixtureRoot).Equals((Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Не подтверждён собственный каталог файловой фикстуры.'
}
$fixtureCreated = $false
$fileFixtureStart = $portableFixtureCount
try {
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $fixtureCreated = $true
    $fixtureCopy = Join-Path $fixtureRoot 'copy'
    New-Item -ItemType Directory -Path $fixtureCopy | Out-Null
    # Даже пустая копия даёт сравнимый пустой снимок.
    $emptyInventory = @(Get-PortableApplicationInventory $fixtureCopy)
    Assert-PortableFixture ($emptyInventory.Count -eq 0) 'Пустая инвентаризация содержит записи.'
    Assert-PortableApplicationUnchanged $fixtureCopy $emptyInventory
    foreach ($relative in 'app','runtime','app/CashMemory','runtime/CashMemory') {
        New-Item -ItemType Directory -Path (Join-Path $fixtureCopy $relative) -Force | Out-Null
    }
    $locations = @('','app','runtime','app/CashMemory','runtime/CashMemory')
    foreach ($relative in $locations) {
        Set-Content -LiteralPath (Join-Path (Join-Path $fixtureCopy $relative) 'payload.bin') -Value 'original' -Encoding ASCII -NoNewline
    }
    $hidden = Join-Path $fixtureCopy 'hidden.bin'
    Set-Content -LiteralPath $hidden -Value 'hidden' -Encoding ASCII -NoNewline
    (Get-Item -LiteralPath $hidden).Attributes = [IO.FileAttributes]::Hidden -bor [IO.FileAttributes]::ReadOnly
    $before = @(Get-PortableApplicationInventory $fixtureCopy)
    $paths = @($before | ForEach-Object Path)
    $sortedPaths = [string[]]$paths.Clone()
    [Array]::Sort($sortedPaths, [StringComparer]::Ordinal)
    Assert-PortableFixture (($paths -join '|') -ceq ($sortedPaths -join '|')) 'Инвентаризация не отсортирована по путям.'
    $payload = @($before | Where-Object Path -eq 'app/payload.bin')[0]
    Assert-PortableFixture ($payload.Size -eq 8 -and $payload.SHA256 -eq (Get-FileHash -LiteralPath (Join-Path $fixtureCopy 'app/payload.bin') -Algorithm SHA256).Hash -and -not $payload.ReadOnly) 'Неверные поля path/size/SHA256/readonly.'
    Assert-PortableFixture (@($before | Where-Object { $_.Path -eq 'hidden.bin' -and $_.ReadOnly }).Count -eq 1) 'Скрытый readonly-файл не учтён.'
    Assert-PortableFixture ($paths -contains 'app/CashMemory/payload.bin' -and $paths -contains 'runtime/CashMemory/payload.bin') 'Вложенный CashMemory ошибочно исключён.'
    Assert-PortableApplicationUnchanged $fixtureCopy $before
    $script:portableFixtureCount++
    # Создание, изменение и удаление любых пользовательских данных в корневом CashMemory допустимы.
    $userData = Join-Path $fixtureCopy 'CashMemory'
    New-Item -ItemType Directory -Path (Join-Path $userData 'notes') -Force | Out-Null
    $userFile = Join-Path $userData 'notes/plan.md'
    Set-Content -LiteralPath $userFile -Value 'first user data' -Encoding UTF8
    Assert-PortableApplicationUnchanged $fixtureCopy $before
    Set-Content -LiteralPath $userFile -Value 'updated user data' -Encoding UTF8
    Assert-PortableApplicationUnchanged $fixtureCopy $before
    Remove-Item -LiteralPath $userFile
    Assert-PortableApplicationUnchanged $fixtureCopy $before
    $script:portableFixtureCount += 3
    foreach ($relative in $locations) {
        $directory = Join-Path $fixtureCopy $relative
        $file = Join-Path $directory 'payload.bin'
        $timestamp = (Get-Item -LiteralPath $file).LastWriteTimeUtc
        # Размер и время прежние: изменение должно обнаруживаться именно по SHA256.
        Set-Content -LiteralPath $file -Value 'modified' -Encoding ASCII -NoNewline
        (Get-Item -LiteralPath $file).LastWriteTimeUtc = $timestamp
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято изменение SHA256: $relative"
        Set-Content -LiteralPath $file -Value 'original with extra bytes' -Encoding ASCII -NoNewline
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято изменение размера: $relative"
        Set-Content -LiteralPath $file -Value 'original' -Encoding ASCII -NoNewline
        (Get-Item -LiteralPath $file).IsReadOnly = $true
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято изменение readonly: $relative"
        (Get-Item -LiteralPath $file).IsReadOnly = $false
        Remove-Item -LiteralPath $file
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято удаление: $relative"
        Set-Content -LiteralPath $file -Value 'original' -Encoding ASCII -NoNewline
        $added = Join-Path $directory 'added.bin'
        Set-Content -LiteralPath $added -Value 'added' -Encoding ASCII
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято добавление файла: $relative"
        Remove-Item -LiteralPath $added
        $addedDirectory = Join-Path $directory 'added-empty'
        New-Item -ItemType Directory -Path $addedDirectory | Out-Null
        Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принято добавление пустого каталога: $relative"
        Remove-Item -LiteralPath $addedDirectory
        Assert-PortableApplicationUnchanged $fixtureCopy $before
        $script:portableFixtureCount++
    }
    $emptyDirectory = Join-Path $fixtureCopy 'app/empty-original'
    New-Item -ItemType Directory -Path $emptyDirectory | Out-Null
    $withEmpty = @(Get-PortableApplicationInventory $fixtureCopy)
    Remove-Item -LiteralPath $emptyDirectory
    Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $withEmpty } 'Принято удаление пустого каталога.'
    # Настоящие junction не требуют прав создания symbolic link; цель находится в той же фикстуре.
    $linkTarget = Join-Path $fixtureRoot 'link-target'
    New-Item -ItemType Directory -Path $linkTarget | Out-Null
    foreach ($relative in @('','app','runtime','CashMemory')) {
        $link = Join-Path (Join-Path $fixtureCopy $relative) 'forbidden-link'
        try {
            New-Item -ItemType Junction -Path $link -Target $linkTarget | Out-Null
            Assert-PortableFixtureRejects { Get-PortableApplicationInventory $fixtureCopy } "Принята ссылка в инвентаризации: $relative"
            Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } "Принята ссылка при сравнении: $relative"
        } finally {
            # Удаляется только сама подтверждённая junction, без рекурсивного обхода цели.
            if (Test-Path -LiteralPath $link) {
                if (((Get-Item -LiteralPath $link -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) { throw 'Ожидалась собственная junction.' }
                [IO.Directory]::Delete($link)
            }
        }
    }
    Assert-PortableFixture (Test-Path -LiteralPath $linkTarget -PathType Container) 'Обход/очистка ссылки затронули цель.'
    # Проверка lifecycle использует файлы, но процессы заменены событиями.
    $events.Clear()
    $failureMessage = ''
    try {
        Invoke-PortableLifecycle {
            $events.Add('work')
            Set-Content -LiteralPath (Join-Path $fixtureCopy 'app/payload.bin') -Value 'modified' -Encoding ASCII -NoNewline
            throw 'fixture-launch-error'
        } {
            $events.Add('settled')
            Assert-PortableFixtureRejects { Assert-PortableApplicationUnchanged $fixtureCopy $before } 'Ошибка запуска обошла сравнение файлов.'
            $events.Add('compared')
        }
    } catch { $failureMessage = $_.Exception.Message }
    Assert-PortableFixture (($events -join ',') -eq 'work,settled,compared' -and $failureMessage -eq 'fixture-launch-error') 'Неверный порядок сравнения после аварийного завершения.'
} finally {
    if ($fixtureCreated) {
        # Проверяется точный UUID-каталог перед единственной рекурсивной очисткой фикстуры.
        if ((Resolve-PortableSafetyPath $fixtureRoot) -cne $fixtureRoot) { throw 'Изменился путь файловой фикстуры.' }
        Assert-PortableTreeHasNoLinks $fixtureRoot
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
$fileFixtureCount = $portableFixtureCount - $fileFixtureStart
# AST фиксирует baseline до первого launcher и сравнение только после успешной остановки всех процессов.
$lifecycleCalls = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Invoke-PortableLifecycle' }, $true))
Assert-PortableFixture ($lifecycleCalls.Count -eq 1) 'Изменился lifecycle запуска копии.'
$cleanupText = $lifecycleCalls[0].CommandElements[2].Extent.Text
Assert-PortableFixture ($cleanupText.IndexOf('Stop-CopyProcesses $copy') -ge 0 -and
    $cleanupText.IndexOf('Assert-PortableApplicationUnchanged $copy $inventoryBefore') -gt $cleanupText.IndexOf('Stop-CopyProcesses $copy')) 'Сравнение выполняется до завершения процессов.'
Assert-PortableFixture ($sourceText.IndexOf('$inventoryBefore = @(Get-PortableApplicationInventory $copy)') -gt $sourceText.IndexOf('Copy-Item -LiteralPath $PortableDir') -and
    $sourceText.IndexOf('$inventoryBefore = @(Get-PortableApplicationInventory $copy)') -lt $lifecycleCalls[0].Extent.StartOffset) 'Baseline снят не между копированием и первым запуском.'
Write-Output "Portable safety: PASS (10 rejected broad paths; $portableFixtureCount regressions including $fileFixtureCount local file checks; no EXE, HTTP or registry mutation; own Temp fixture removed)"
