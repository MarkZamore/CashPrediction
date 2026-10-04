<#
.SYNOPSIS
Изолированные отрицательные fixtures и mock command assembly нового bootstrap builder.
.DESCRIPTION
Не запускает JDK, helper, GUI или portable exe. Поддельные native файлы сначала
обязательно отвергает настоящий preflight. Затем только внутри fixture процесса
native preflight и CLI/API execution заменяются явно синтетическими ответами.
Это тест формата/маршрута/отказов, не подтверждение работы Java или cold recovery.
Для timeout/exit/output-limit запускаются только собственные PowerShell mock команды.
Никакие mock данные не выходят за собственные Temp UUID, nativeMatrix всегда PENDING.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) { throw 'BOOTCMD_FIXTURE_POWERSHELL7_REQUIRED' }
$script:checks=0; $script:rejections=0

# Импортируем новый код лишь как AST definitions, не вызываем основной builder.
$builder=Join-Path $PSScriptRoot 'New-UpdateBootstrapCommands.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'BOOTCMD_FIXTURE_PARSE' }
foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'Test-Portable.ps1') @('Resolve-PortableSafetyPath',
    'Test-PortablePathContains','Get-ValidatedPortablePaths','Get-PortableCleanupPath',
    'Assert-PortableSourceEntries','Assert-PortableTreeHasNoLinks')
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1') @()
Import-BootstrapBuilderFunctions (Join-Path $PSScriptRoot 'S7-Release.ps1') @('Assert-S7Path')

# Ожидаем точный отказ, а не произвольное исключение ошибочного fixture.
function Assert-BootstrapCommandMockReject([scriptblock]$Action,[string]$Code) {
    $caught=$null
    try { & $Action | Out-Null } catch { $caught=$_.Exception.Message }
    if ($null -eq $caught -or -not $caught.StartsWith($Code,[StringComparison]::Ordinal)) {
        throw "BOOTCMD_FIXTURE_EXPECTED $Code actual=$caught"
    }
    $script:rejections++; $script:checks++
}

# Утверждение применяется только к mock результатам и сохранённым аргументам.
function Assert-BootstrapCommandMock([bool]$Value,[string]$Code) {
    if (-not $Value) { throw "BOOTCMD_FIXTURE_ASSERT $Code" }
    $script:checks++
}

# Независимый decoder fixture: System.Text.Json сохраняет ISO/Unicode как строки, не DateTime.
function Read-BootstrapCommandMockPayload([string]$Payload) {
    $bytes=[Convert]::FromBase64String($Payload)
    Assert-BootstrapCommandMock ([Convert]::ToBase64String($bytes) -ceq $Payload) 'STANDARD_BASE64'
    $json=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
    $document=[Text.Json.JsonDocument]::Parse($json)
    try {
        Assert-BootstrapCommandMock ($document.RootElement.ValueKind -eq [Text.Json.JsonValueKind]::Array) 'JSON_ARGUMENT_ARRAY'
        $values=[Collections.Generic.List[string]]::new()
        foreach ($element in $document.RootElement.EnumerateArray()) {
            Assert-BootstrapCommandMock ($element.ValueKind -eq [Text.Json.JsonValueKind]::String) 'JSON_ARGUMENT_STRING'
            $values.Add($element.GetString())
        }
        return $values.ToArray()
    } finally { $document.Dispose() }
}

# Собственные тестовые данные, не настоящие helper/runtime/classes и не доказательство запуска.
function Write-BootstrapCommandMock([string]$Path,[string]$Text) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}

# ZIP структурных fixtures: CAFEBABE нужен только проверке формы, классы не исполняются.
function New-BootstrapCommandMockJar([string]$Path,[int]$Release,[string]$Commit,[switch]$Tool) {
    $archive=[IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
    try {
        $names=if ($Tool) { @('ru/cashprediction/updatetool/UpdateTool.class') } else {
            @('ru/cashprediction/core/update/install/PowerShellHelper.class',
                'ru/cashprediction/core/update/install/PortableBootstrap.class',
                'ru/cashprediction/core/update/install/BootstrapScript.class')
        }
        foreach ($name in @('module-info.class')+$names) {
            $stream=$archive.CreateEntry($name).Open()
            try { $stream.Write([Convert]::FromHexString('CAFEBABE'),0,4) } finally { $stream.Dispose() }
        }
        if (-not $Tool) {
            $stream=$archive.CreateEntry('ru/cashprediction/core/app.properties').Open()
            try {
                $bytes=[Text.Encoding]::UTF8.GetBytes("release=$Release`ncommit=$Commit`n")
                $stream.Write($bytes,0,$bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

# Fixture image заведомо не PE/JImage; production preflight обязан отвергнуть его.
function New-BootstrapCommandMockImage([string]$Root,[int]$Release,[string]$Commit) {
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'app'))
    foreach ($name in 'CashPrediction','CashPrediction-Swing','CashPrediction-Web') {
        Write-BootstrapCommandMock (Join-Path $Root "$name.exe") 'NOT_NATIVE_MOCK'
        Write-BootstrapCommandMock (Join-Path $Root "app/$name.cfg") "[Application]`napp.mainmodule=ru.example/ru.example.Main`n"
    }
    Write-BootstrapCommandMock (Join-Path $Root 'runtime/lib/modules') 'NOT_JIMAGE_MOCK'
    Write-BootstrapCommandMock (Join-Path $Root 'app/.jpackage.xml') 'MOCK_PACKAGE'
    Write-BootstrapCommandMock (Join-Path $Root 'app/readonly.txt') 'MOCK_READONLY'
    [IO.File]::SetAttributes((Join-Path $Root 'app/readonly.txt'),[IO.FileAttributes]::ReadOnly)
    New-BootstrapCommandMockJar (Join-Path $Root 'app/core.jar') $Release $Commit
}

# Очистка только сохранённого UUID после общей проверки ownership, без удаления broad paths.
function Remove-BootstrapCommandMockDirectory([string]$Path,[string]$Protected,[string]$Project) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $checked=Get-PortableCleanupPath $Protected $Path ([IO.Path]::GetTempPath()) $Project ([Environment]::GetFolderPath('UserProfile'))
    # Windows иногда отпускает cwd только сразу после уведомления о завершении mock процесса.
    for ($attempt=0;$attempt -lt 20;$attempt++) {
        try {
            Assert-PortableTreeHasNoLinks $checked
            Remove-Item -LiteralPath $checked -Recurse -Force
            return
        } catch {
            if ($attempt -eq 19) { throw }
            Start-Sleep -Milliseconds 50
        }
    }
}

$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$outputs=[Collections.Generic.List[string]]::new()
$originalNative=(Get-Item Function:Assert-ColdNativeImage).ScriptBlock
$originalProcess=(Get-Item Function:Invoke-BootstrapBuilderProcess).ScriptBlock
$originalIdentityReader=(Get-Item Function:Read-BootstrapBuilderProcessIdentity).ScriptBlock
try {
    [void](New-BootstrapBuilderDirectory $fixture @($project) $project)
    $base1=Join-Path $fixture 'Моя база Δ 测试'; $base2=Join-Path $fixture 'Вторая база'; $target=Join-Path $fixture '目标 CashPrediction'
    New-BootstrapCommandMockImage $base1 10 ('a'*40)
    New-BootstrapCommandMockImage $base2 11 ('b'*40)
    New-BootstrapCommandMockImage $target 12 ('c'*40)
    $jdk=Join-Path $fixture 'jdk'; $java=Join-Path $jdk 'bin/java.exe'
    $modules=Join-Path $jdk 'lib/modules'
    Write-BootstrapCommandMock $java 'NOT_JAVA_MOCK'
    Write-BootstrapCommandMock $modules 'NOT_JDK_MODULES_MOCK'
    Write-BootstrapCommandMock (Join-Path $jdk 'release') 'JAVA_VERSION="25.0.1"'
    $core=Join-Path $target 'app/core.jar'; $tool=Join-Path $fixture 'tool.jar'
    New-BootstrapCommandMockJar $tool 0 '' -Tool
    $inputs=@{
        Bases=@($base1,$base2);Target=$target;Java=$java;JavaHash=(Get-FileHash -LiteralPath $java).Hash.ToLowerInvariant();
        ModulesHash=(Get-FileHash -LiteralPath $modules).Hash.ToLowerInvariant();
        Core=$core;CoreHash=(Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant();
        Tool=$tool;ToolHash=(Get-FileHash -LiteralPath $tool).Hash.ToLowerInvariant();
        Output='';Project=$project;TimeoutSeconds=10
    }
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'COLD_NOT_NATIVE_PE'
    Assert-BootstrapCommandMockReject {Resolve-BootstrapBuilderInput 'relative.jar'} 'BOOTCMD_ABSOLUTE_PATH'
    Assert-BootstrapCommandMockReject {Resolve-BootstrapBuilderInput ($fixture+'\..\escape.jar')} 'BOOTCMD_CANONICAL_PATH'
    Assert-BootstrapCommandMockReject {Resolve-BootstrapBuilderInput ($fixture+';inject')} 'BOOTCMD_ABSOLUTE_PATH'
    Assert-BootstrapCommandMockReject {Resolve-BootstrapBuilderInput ($fixture+':stream')} 'BOOTCMD_CANONICAL_PATH'
    Assert-BootstrapCommandMockReject {Get-BootstrapBuilderPin $java ('A'*64)} 'BOOTCMD_PIN_FORMAT'
    Assert-BootstrapCommandMockReject {Get-BootstrapBuilderPin $java ('0'*64)} 'BOOTCMD_PIN'
    Assert-BootstrapCommandMockReject {Get-BootstrapBuilderPin (Join-Path $fixture 'missing') ('0'*64)} 'BOOTCMD_PIN'
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderDirectory $fixture @($project) $project} 'BOOTCMD_OUTPUT_EXISTS'
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderDirectory ([IO.Path]::GetTempPath()) @($project) $project} 'BOOTCMD_TEMP_UUID_REQUIRED'
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderDirectory (Join-Path $fixture ('run-'+[guid]::NewGuid())) @($project) $project} 'BOOTCMD_TEMP_UUID_REQUIRED'
    $metadata=Join-Path $fixture 'create-new.json'
    Write-BootstrapBuilderText $metadata '{}'
    Assert-BootstrapCommandMockReject {Write-BootstrapBuilderText $metadata 'overwrite'} 'BOOTCMD_OUTPUT_EXISTS'
    Assert-BootstrapCommandMock ([IO.File]::ReadAllText($metadata) -ceq '{}') 'CREATE_NEW_PRESERVED'
    $unicodeUpdates=Join-Path $fixture "Δ 测试/Мои 'программы'/CashMemory/Updates"
    $exportSource=Get-BootstrapBuilderExportSource $unicodeUpdates
    $encoded=[regex]::Match($exportSource,'decode\("([A-Za-z0-9+/=]+)"\)').Groups[1].Value
    Assert-BootstrapCommandMock ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) -ceq
        (Resolve-BootstrapBuilderInput $unicodeUpdates)) 'UNICODE_EXPORT_ARGUMENT'
    Assert-BootstrapCommandMock (-not $exportSource.Contains($unicodeUpdates) -and $exportSource.Contains('public static void main(String[] args)')) 'NO_JAVA_SOURCE_INJECTION'
    $identity=[pscustomobject]@{pid=123;ticks=456L;path=$java}
    foreach ($field in 'pid','ticks','path') {
        $bad=[pscustomobject]@{pid=123;ticks=456L;path=$java}
        if ($field -eq 'path') { $bad.path=$tool } else { $bad.$field++ }
        Assert-BootstrapCommandMockReject {Assert-BootstrapBuilderProcessIdentity $identity $bad} 'BOOTCMD_PROCESS_IDENTITY'
    }
    # Никакого Kill/настоящего PID в модели переходов: reader возвращает только mock наблюдения.
    $script:identityLoader=Join-Path ([Environment]::GetFolderPath('System')) 'ntdll.dll'
    $script:identityClock=[pscustomobject]@{Elapsed=[TimeSpan]::Zero}
    $script:identitySamples=@(); $script:identityReads=0; $script:identityAdvance=0.01
    function Read-BootstrapBuilderProcessIdentity($Process) {
        $script:identityClock.Elapsed+=[TimeSpan]::FromSeconds($script:identityAdvance)
        $index=[Math]::Min($script:identityReads,$script:identitySamples.Count-1)
        $script:identityReads++
        return $script:identitySamples[$index]
    }
    $mockProcess=[pscustomobject]@{waits=0}
    $mockProcess | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {param($Milliseconds) $this.waits++; return $false}
    $loader=[pscustomobject]@{pid=123;ticks=456L;path=$script:identityLoader}
    $noModule=[pscustomobject]@{pid=123;ticks=456L;path=$null}
    $script:identitySamples=@($noModule,$loader,$loader,$identity)
    $ready=Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10
    Assert-BootstrapCommandMock ($ready.path -ceq $java -and $script:identityReads -eq 4 -and $mockProcess.waits -eq 3) 'NULL_NTDLL_EXPECTED_EXE_TRANSITION'
    foreach ($field in 'pid','ticks','path') {
        $bad=[pscustomobject]@{pid=123;ticks=456L;path=$script:identityLoader}
        if ($field -eq 'path') { $bad.path=Join-Path $fixture 'foreign/ntdll.dll' } else { $bad.$field++ }
        $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identityReads=0
        $script:identitySamples=@($bad,$identity)
        Assert-BootstrapCommandMockReject {Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10} 'BOOTCMD_PROCESS_IDENTITY'
        Assert-BootstrapCommandMock ($script:identityReads -eq 1) 'FOREIGN_IDENTITY_NOT_WAITED'
    }
    foreach ($foreign in @((Join-Path $fixture 'foreign.exe'),(Join-Path ([Environment]::GetFolderPath('System')) 'kernel32.dll'))) {
        $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identityReads=0
        $script:identitySamples=@([pscustomobject]@{pid=123;ticks=456L;path=$foreign},$identity)
        Assert-BootstrapCommandMockReject {Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10} 'BOOTCMD_PROCESS_IDENTITY'
        Assert-BootstrapCommandMock ($script:identityReads -eq 1) 'STABLE_FOREIGN_MODULE_REJECTED_IMMEDIATELY'
    }
    $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identityAdvance=5; $script:identitySamples=@($loader)
    Assert-BootstrapCommandMockReject {Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10} 'BOOTCMD_STARTUP_IDENTITY_TIMEOUT'
    $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identitySamples=@($identity)
    Assert-BootstrapCommandMockReject {Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10} 'BOOTCMD_STARTUP_IDENTITY_TIMEOUT'
    $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identityAdvance=1
    Assert-BootstrapCommandMockReject {Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 1} 'BOOTCMD_PROCESS_TIMEOUT'
    $script:identityClock.Elapsed=[TimeSpan]::Zero; $script:identityAdvance=0.01; $script:identitySamples=@($null)
    Assert-BootstrapCommandMock ($null -eq (Wait-BootstrapBuilderProcessIdentity $mockProcess $identity $script:identityClock 10)) 'EARLY_EXIT_NOT_EXECUTABLE_WITNESS'
    Set-Item Function:Read-BootstrapBuilderProcessIdentity $originalIdentityReader

    $sampleArguments=@('inventory','--root',(Join-Path $fixture 'Мои папки/Δ 测试'), '--out',(Join-Path $fixture 'a "quoted" name.json'))
    $transport=@(Get-BootstrapBuilderToolTransport $sampleArguments)
    $decoded=@(Read-BootstrapCommandMockPayload $transport[1])
    Assert-BootstrapCommandMock ($transport.Count -eq 2 -and $transport[0] -ceq '--arguments-base64' -and $transport[1] -notmatch '[^A-Za-z0-9+/=]') 'ASCII_ENVELOPE_ONLY'
    for ($i=0;$i -lt $sampleArguments.Count;$i++) {
        Assert-BootstrapCommandMock ($decoded[$i] -ceq $sampleArguments[$i]) 'UNICODE_LOGICAL_ARGUMENT_ROUNDTRIP'
    }
    foreach ($invalid in @(@(),@('--arguments-base64','nested'),@('verify',('x'*32769)),@('verify',("nul"+[char]0)))) {
        Assert-BootstrapCommandMockReject {Get-BootstrapBuilderToolTransport $invalid} 'BOOTCMD_TOOL_ARGUMENTS'
    }
    $large=@('verify')+@(1..7 | ForEach-Object {'x'*32768})
    Assert-BootstrapCommandMockReject {Get-BootstrapBuilderToolTransport $large} 'BOOTCMD_TOOL_ARGUMENTS_LIMIT'
    # Реальные процессы - только короткие PowerShell mocks, никакого native/JDK rehearsal.
    $pwsh=(Get-Process -Id $PID).Path
    $powerPin=Get-BootstrapBuilderPin $pwsh ((Get-FileHash -LiteralPath $pwsh).Hash.ToLowerInvariant())
    $processLog=Join-Path $fixture 'process-ok.json'
    Invoke-BootstrapBuilderProcess $pwsh @('-NoProfile','-NonInteractive','-Command','[Console]::Out.Write("mock-ok"); exit 0') `
        $fixture 10 @($powerPin) $processLog
    $captured=Get-Content -LiteralPath $processLog -Raw | ConvertFrom-Json
    Assert-BootstrapCommandMock ($captured.exitCode -eq 0 -and $captured.stdout -ceq 'mock-ok') 'MOCK_PROCESS_CAPTURE'
    # Быстрые реальные PS7 процессы проверяют окно startup/exit, не играют роль native witness.
    for ($repeat=0;$repeat -lt 6;$repeat++) {
        $earlyLog=Join-Path $fixture "process-early-$repeat.json"
        Invoke-BootstrapBuilderProcess $pwsh @('-NoProfile','-NonInteractive','-Command','[Console]::Out.Write("mock-early"); exit 0') `
            $fixture 10 @($powerPin) $earlyLog
        $early=Get-Content -LiteralPath $earlyLog -Raw | ConvertFrom-Json
        Assert-BootstrapCommandMock ($early.exitCode -eq 0 -and $early.stdout -ceq 'mock-early') 'PS7_EARLY_EXIT_CAPTURE'
    }
    for ($repeat=0;$repeat -lt 3;$repeat++) {
        Assert-BootstrapCommandMockReject {
            Invoke-BootstrapBuilderProcess $pwsh @('-NoProfile','-NonInteractive','-Command','exit 7') `
                $fixture 10 @($powerPin) (Join-Path $fixture "process-bad-$repeat.json")
        } 'BOOTCMD_PROCESS_FAILED'
        Assert-BootstrapCommandMockReject {
            Invoke-BootstrapBuilderProcess $pwsh @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 5') `
                $fixture 1 @($powerPin) (Join-Path $fixture "process-timeout-$repeat.json")
        } 'BOOTCMD_PROCESS_TIMEOUT'
    }
    Assert-BootstrapCommandMockReject {
        Invoke-BootstrapBuilderProcess $pwsh @('-NoProfile','-NonInteractive','-Command','[Console]::Out.Write([string]::new([char]65,70000)); exit 0') `
            $fixture 10 @($powerPin) (Join-Path $fixture 'process-overflow.json')
    } 'BOOTCMD_PROCESS_OUTPUT_LIMIT'

    # Только после доказанного отказа fake native меняем preflight для mock схемы команд.
    function global:Assert-ColdNativeImage([string]$Root) { }
    $script:scenario='ok'; $script:invocations=[Collections.Generic.List[object]]::new()
    function Invoke-BootstrapBuilderProcess([string]$Executable,[string[]]$Arguments,[string]$Directory,
        [int]$TimeoutSeconds,$Pins,[string]$Log) {
        Assert-BootstrapBuilderPins $Pins
        $script:invocations.Add([pscustomobject]@{exe=$Executable;arguments=$Arguments})
        Assert-BootstrapCommandMock ($Arguments[0] -ceq '-XX:+PerfDisableSharedMem' -and
            $Arguments[1].StartsWith('-Djava.io.tmpdir='+$Directory+'\') -and
            $Arguments[2].StartsWith('-Duser.home='+$Directory+'\')) 'OWNED_JVM_DIRECTORIES'
        $Arguments=$Arguments[3..($Arguments.Count-1)]
        if ($Arguments[0] -ceq '--class-path') {
            Assert-BootstrapCommandMock ($Arguments.Count -eq 3 -and
                [IO.Path]::GetFileName($Arguments[-1]) -ceq 'BootstrapHelperExport.java') 'SOURCE_FILE_API_COMMAND'
            $source=[IO.File]::ReadAllText($Arguments[-1])
            Assert-BootstrapCommandMock ($source.Contains('PowerShellHelper.publish(java.nio.file.Path.of(') -and
                $source.Contains('throws java.io.IOException')) 'REAL_API_SOURCE_ONLY'
            $match=[regex]::Match($source,'decode\("([A-Za-z0-9+/=]+)"\)')
            $updates=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value))
            if ($script:scenario -eq 'missing-helper') { return }
            $text=if ($script:scenario -eq 'invalid-helper') {'param('} else {'# MOCK ONLY, NEVER EXECUTE AS HELPER'}
            Write-BootstrapCommandMock (Join-Path $updates 'apply-update.ps1') $text
            return
        }
        Assert-BootstrapCommandMock (($Arguments[0] -ceq '--module-path') -and $Arguments[2] -ceq '-m' -and
            $Arguments[3] -ceq 'ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool') 'SUPPORTED_TOOL_ENTRY'
        Assert-BootstrapCommandMock ($Arguments[1] -ceq ((Join-Path $Directory 'tool/core.jar')+';'+(Join-Path $Directory 'tool/update-tool.jar'))) 'TOOL_ARGUMENTS_UNCHANGED'
        Assert-BootstrapCommandMock ($Arguments.Count -eq 6 -and $Arguments[4] -ceq '--arguments-base64') 'TWO_TRANSPORT_ARGUMENTS'
        $logical=@(Read-BootstrapCommandMockPayload $Arguments[5])
        $script:invocations[$script:invocations.Count-1] | Add-Member -NotePropertyName logical -NotePropertyValue $logical
        $operation=$logical[0]; $options=@{}
        for ($i=1;$i -lt $logical.Count;$i+=2) {
            Assert-BootstrapCommandMock (-not $options.ContainsKey($logical[$i])) 'NO_DUPLICATE_TOOL_FLAGS'
            $options[$logical[$i]]=$logical[$i+1]
        }
        $package=[IO.Path]::GetDirectoryName($Log)
        $imageIndex=[int]([regex]::Match([IO.Path]::GetFileName($package),'^image-([0-9]+)$').Groups[1].Value)
        $expectedRoot=$script:expectedImages[$imageIndex]
        $manifestPath=Join-Path $package 'update.json'
        $expectedLogical=switch -CaseSensitive ($operation) {
            'inventory' {@('inventory','--root',$expectedRoot,'--out',(Join-Path $package 'inventory.json'))}
            'manifest' {
                $version=Get-ColdVersion $expectedRoot
                Assert-BootstrapCommandMock ($options['--published-at'] -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$') 'ISO_ARGUMENT_REMAINS_STRING'
                @('manifest','--root',$expectedRoot,'--archive',(Join-Path $package 'CashPrediction-portable.zip'),
                    '--release',[string]$version.releaseNumber,'--commit',$version.commitSha,'--version',[string]$version.releaseNumber,
                    '--published-at',$options['--published-at'],'--out',$manifestPath)
            }
            'verify' {@('verify','--root',$expectedRoot,'--manifest',$manifestPath)}
            default {throw "BOOTCMD_FIXTURE_UNSUPPORTED_COMMAND $operation"}
        }
        Assert-BootstrapCommandMock ($logical.Count -eq $expectedLogical.Count) 'EFFECTIVE_ARGUMENT_COUNT'
        for ($i=0;$i -lt $expectedLogical.Count;$i++) {
            Assert-BootstrapCommandMock ($logical[$i] -ceq $expectedLogical[$i]) 'EFFECTIVE_ARGUMENT_VALUE'
        }
        if ($script:scenario -eq 'process-failure') { throw 'BOOTCMD_PROCESS_FAILED' }
        switch -CaseSensitive ($operation) {
            'inventory' {
                Assert-BootstrapCommandMock (($options.Keys | Sort-Object) -join '|' -ceq '--out|--root') 'INVENTORY_FLAGS'
                if ($script:scenario -eq 'missing-inventory') { return }
                $files=@(Get-ColdManagedInventory $options['--root'])
                Write-BootstrapCommandMock $options['--out'] (ConvertTo-Json -Depth 32 -InputObject ([ordered]@{
                    files=$files;treeSha256=(Get-ColdTreeHash $files)
                }))
            }
            'manifest' {
                Assert-BootstrapCommandMock (($options.Keys | Sort-Object) -join '|' -ceq
                    '--archive|--commit|--out|--published-at|--release|--root|--version') 'MANIFEST_FLAGS'
                if ($script:scenario -eq 'missing-manifest') { return }
                $files=@(Get-ColdManagedInventory $options['--root']); $zip=$options['--archive']
                $value=[ordered]@{schemaVersion=2;releaseNumber=[int]$options['--release'];commitSha=$options['--commit'];
                    version=$options['--version'];publishedAtUtc=$options['--published-at'];assetName=[IO.Path]::GetFileName($zip);
                    sizeBytes=[long](Get-Item -LiteralPath $zip).Length;sha256=(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant();
                    treeSha256=(Get-ColdTreeHash $files);files=$files;deltaPatches=@()}
                if ($script:scenario -eq 'wrong-release') { $value.releaseNumber++ }
                if ($script:scenario -eq 'wrong-commit') { $value.commitSha='f'*40 }
                if ($script:scenario -eq 'wrong-zip-hash') { $value.sha256='0'*64 }
                Write-BootstrapCommandMock $options['--out'] (ConvertTo-Json -Depth 32 -InputObject $value)
            }
            'verify' {
                Assert-BootstrapCommandMock (($options.Keys | Sort-Object) -join '|' -ceq '--manifest|--root') 'VERIFY_FLAGS'
                if ($script:scenario -eq 'verify-failure') { throw 'BOOTCMD_PROCESS_FAILED' }
                if ($script:scenario -eq 'changed-source') {
                    Write-BootstrapCommandMock (Join-Path $options['--root'] 'app/changed.txt') 'CONCURRENT_CHANGE'
                }
            }
            default { throw "BOOTCMD_FIXTURE_UNSUPPORTED_COMMAND $operation" }
        }
    }
    foreach ($count in 1,2) {
        $inputs.Bases=if ($count -eq 1) {@($base1)} else {@($base1,$base2)}
        $script:expectedImages=@($inputs.Bases)+@($target)
        $inputs.Output=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
        $outputs.Add($inputs.Output); $script:invocations.Clear()
        $prepared=New-BootstrapBuilderCommands @inputs
        Assert-BootstrapCommandMock ($prepared.status -ceq 'PREPARED' -and $prepared.nativeMatrix -ceq 'PENDING') 'NEVER_NATIVE_PASS'
        $config=Read-ColdPinnedJson $prepared.CommandFile $prepared.CommandFileSha256
        Assert-ColdCommand $config $java $inputs.Bases $target
        Assert-BootstrapCommandMock (@($config.baseManifests).Count -eq $count) 'BASE_COUNT'
        Assert-BootstrapCommandMock ($script:invocations.Count -eq 1+3*($count+1)) 'EXACT_COMMAND_COUNT'
        $zip=Join-Path $prepared.OutputDirectory 'image-0/CashPrediction-portable.zip'
        $repeat=Join-Path $prepared.OutputDirectory 'repeat.zip'
        New-BootstrapBuilderArchive $base1 @(Get-ColdManagedInventory $base1) $repeat
        Assert-BootstrapCommandMock ((Get-FileHash -LiteralPath $zip).Hash -ceq (Get-FileHash -LiteralPath $repeat).Hash) 'DETERMINISTIC_ZIP'
        $archive=[IO.Compression.ZipFile]::OpenRead($zip)
        try {
            Assert-BootstrapCommandMock (@($archive.Entries | Where-Object {-not $_.FullName.StartsWith('CashPrediction/',[StringComparison]::Ordinal)}).Count -eq 0) 'ZIP_ROOT'
            Assert-BootstrapCommandMock (($archive.GetEntry('CashPrediction/app/readonly.txt').ExternalAttributes -band 1) -eq 1) 'ZIP_READONLY'
            Assert-BootstrapCommandMock ($archive.Entries[0].LastWriteTime.Year -eq 2000) 'ZIP_FIXED_TIME'
        } finally { $archive.Dispose() }
        Assert-BootstrapCommandMockReject {Read-ColdPinnedJson $prepared.CommandFile ('0'*64)} 'COLD_PIN'
        $bad=$config | ConvertTo-Json -Depth 32 | ConvertFrom-Json -Depth 32
        $bad.baseManifests=@()
        Assert-BootstrapCommandMockReject {Assert-ColdCommand $bad $java $inputs.Bases $target} 'COLD_BASE_MANIFESTS'
    }
    $inputs.Bases=@($base1,$base2)
    $script:expectedImages=@($base1,$base2,$target)
    foreach ($case in @(
        @('missing-helper','BOOTCMD_HELPER_MISSING'),@('invalid-helper','BOOTCMD_HELPER_PARSE'),
        @('missing-inventory','BOOTCMD_REPORT_MISSING'),@('missing-manifest','BOOTCMD_REPORT_MISSING'),
        @('wrong-release','BOOTCMD_MANIFEST_IDENTITY'),@('wrong-commit','BOOTCMD_MANIFEST_IDENTITY'),
        @('wrong-zip-hash','BOOTCMD_MANIFEST_IDENTITY'),@('process-failure','BOOTCMD_PROCESS_FAILED'),
        @('verify-failure','BOOTCMD_PROCESS_FAILED'),@('changed-source','COLD_MANAGED_TREE')
    )) {
        $script:scenario=$case[0]
        $inputs.Output=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString()); $outputs.Add($inputs.Output)
        Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} $case[1]
        Assert-BootstrapCommandMock (-not (Test-Path -LiteralPath (Join-Path $inputs.Output 'bootstrap-commands.json'))) 'NO_FAILED_COMMAND_FILE'
        foreach ($root in $base1,$base2,$target) {
            $changed=Join-Path $root 'app/changed.txt'
            if (Test-Path -LiteralPath $changed) { Remove-Item -LiteralPath $changed }
        }
    }
    $script:scenario='ok'
    $inputs.Bases=@()
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_BASE_COUNT'
    $inputs.Bases=@($base1,$base2,$target)
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_BASE_COUNT'
    $inputs.Bases=@($base1,$base1)
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_DUPLICATE_ROOT'
    $inputs.Bases=@($target)
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_DUPLICATE_ROOT'
    $inputs.Bases=@($base1,$base2)
    $image1=[pscustomobject]@{root=$base1;version=[pscustomobject]@{releaseNumber=10;commitSha='a'*40}}
    $image2=[pscustomobject]@{root=$base2;version=[pscustomobject]@{releaseNumber=10;commitSha='b'*40}}
    $imageTarget=[pscustomobject]@{root=$target;version=[pscustomobject]@{releaseNumber=12;commitSha='c'*40}}
    Assert-BootstrapCommandMockReject {Assert-BootstrapBuilderImages @($image1,$image2) $imageTarget} 'BOOTCMD_DUPLICATE_RELEASE'
    $image1.version.releaseNumber=12
    Assert-BootstrapCommandMockReject {Assert-BootstrapBuilderImages @($image1) $imageTarget} 'BOOTCMD_RELEASE_ORDER'
    $image1.version.releaseNumber=10; $image2.root=Join-Path $base1 'nested'
    Assert-BootstrapCommandMockReject {Assert-BootstrapBuilderImages @($image1,$image2) $imageTarget} 'BOOTCMD_SOURCE_OVERLAP'
    $inputs.Core=Join-Path $base1 'app/core.jar'; $inputs.CoreHash=(Get-FileHash -LiteralPath $inputs.Core).Hash.ToLowerInvariant()
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_TARGET_CORE_MISMATCH'
    $inputs.Core=$core; $inputs.CoreHash=(Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant()
    $saved=$inputs.JavaHash; $inputs.JavaHash='0'*64
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_PIN'
    $inputs.JavaHash=$saved
    Write-BootstrapCommandMock (Join-Path $jdk 'release') 'JAVA_VERSION="24"'
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_JDK25_REQUIRED'
    Write-BootstrapCommandMock (Join-Path $jdk 'release') "JAVA_VERSION=`"25`"`nJAVA_VERSION=`"24`""
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'BOOTCMD_JDK25_REQUIRED'
    Write-BootstrapCommandMock (Join-Path $jdk 'release') 'JAVA_VERSION="25.0.1"'
    Write-BootstrapCommandMock (Join-Path $base1 'CashMemory/user.md') 'USER_DATA'
    Assert-BootstrapCommandMockReject {New-BootstrapBuilderCommands @inputs} 'Исходная сборка содержит CashMemory.'
    Assert-BootstrapCommandMock ([IO.File]::ReadAllText((Join-Path $base1 'CashMemory/user.md')) -ceq 'USER_DATA') 'USER_DATA_UNTOUCHED'
    Write-Output "BOOTCMD_MOCK_OK checks=$script:checks exactRejections=$script:rejections nativeMatrix=PENDING JDK=NOT_EXECUTED"
} catch {
    Write-Host ("BOOTCMD_FIXTURE_FAILURE "+$_.Exception.Message+"`n"+$_.ScriptStackTrace)
    throw
} finally {
    Set-Item Function:global:Assert-ColdNativeImage $originalNative
    Set-Item Function:Invoke-BootstrapBuilderProcess $originalProcess
    Set-Item Function:Read-BootstrapBuilderProcessIdentity $originalIdentityReader
    foreach ($output in $outputs) { Remove-BootstrapCommandMockDirectory $output $fixture $project }
    Remove-BootstrapCommandMockDirectory $fixture $project $project
}
