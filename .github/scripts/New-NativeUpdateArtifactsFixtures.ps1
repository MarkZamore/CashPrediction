<#
.SYNOPSIS
Mock контракты artifacts builder, без Java/Maven/native/GUI или сети.
.DESCRIPTION
CLI замещён mock: patch/ZIP - явный ASCII текст, деревья - metadata, не exe bytes.
Проверяются реальные файловые SHA, nooverwrite, schema/identities, порядок CLI,
полный инвентарь, неизменность CommandFile и отсутствие ложного native PASS.
MAIN запускает отдельно; mock output никогда не передаётся native runner.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'ARTIFACT_FIXTURE_WINDOWS_POWERSHELL7'}
$tokens=$null;$errors=$null;$source=Join-Path $PSScriptRoot 'New-NativeUpdateArtifacts.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw ('ARTIFACT_FIXTURE_PARSE '+($errors.Message -join '; '))}
foreach ($definition in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Import-ArtifactContracts $PSScriptRoot
$script:checks=0

# Точное исключение не позволяет посторонней ошибке пройти как guard PASS.
function Assert-ArtifactReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "ARTIFACT_FIXTURE_REJECT expected=$Code actual=$caught"};$script:checks++
}

# Положительные controls доказывают только mock contract, не реальный CLI/native.
function Assert-ArtifactMock([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw "ARTIFACT_FIXTURE_ASSERT $Code"};$script:checks++
}

# Создание независимой копии wire JSON сохраняет числовые/строковые поля.
function Copy-ArtifactMock($Value) {return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64 -Compress))}

# Никакой случайный путь исполнения fixture не запускает внешний процесс или сеть.
function Start-Process {throw 'ARTIFACT_FIXTURE_FORBIDDEN_PROCESS'}
function Start-NativeOwned {throw 'ARTIFACT_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-WebRequest {throw 'ARTIFACT_FIXTURE_FORBIDDEN_NETWORK'}
function Invoke-RestMethod {throw 'ARTIFACT_FIXTURE_FORBIDDEN_NETWORK'}

$temp=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath());$owned=Join-Path $temp ([guid]::NewGuid().ToString())
$output=Join-Path $temp ([guid]::NewGuid().ToString());$script:calls=[Collections.Generic.List[object]]::new()
$null=New-Item -ItemType Directory -Path $owned
try {
    $cfgTexts=@{}
    foreach ($client in 'CashPrediction','CashPrediction-Swing','CashPrediction-Web') {
        $module=switch ($client) {
            'CashPrediction' {'ru.cashprediction.fx/ru.cashprediction.fx.FxMain'}
            'CashPrediction-Swing' {'ru.cashprediction.swing/ru.cashprediction.swing.SwingMain'}
            'CashPrediction-Web' {'ru.cashprediction.web/ru.cashprediction.web.WebMain'}
        }
        $cfgTexts['app/'+$client+'.cfg']="[Application]`r`napp.mainmodule=$module`r`n[JavaOptions]`r`njava-options=--module-path`r`n"+'java-options=$APPDIR'+"`r`n"
    }
    $paths=@('CashPrediction-Swing.exe','CashPrediction-Web.exe','CashPrediction.exe','app/.jpackage.xml',
        'app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/CashPrediction.cfg',
        'app/cashprediction-core-1.0.0.jar','app/cashprediction-ui-fx-1.0.0.jar',
        'app/cashprediction-ui-swing-1.0.0.jar','app/cashprediction-web-1.0.0.jar',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules')
    # Непустые metadata каждой роли; exe/JAR/runtime bytes не создаются на диске.
    $files=@($paths | ForEach-Object {
        $text=if ($cfgTexts.ContainsKey($_)) {$cfgTexts[$_]} else {'MOCK METADATA ONLY '+$_}
        $bytes=[Text.Encoding]::UTF8.GetBytes($text)
        [pscustomobject]@{path=$_;sizeBytes=[long]$bytes.Length;
            sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();readOnly=$false}
    })
    $cfgRoot=Join-Path $owned 'cfg-image';$null=New-Item -ItemType Directory -Path (Join-Path $cfgRoot 'app')
    foreach ($path in $cfgTexts.Keys) {[IO.File]::WriteAllText((Join-Path $cfgRoot $path),$cfgTexts[$path],[Text.UTF8Encoding]::new($false))}
    Assert-ArtifactLocalConfigs $cfgRoot $cfgRoot;$script:checks++
    foreach ($path in $cfgTexts.Keys) {
        Assert-ArtifactMock (Test-ColdExternalModules (Read-ColdConfig (Join-Path $cfgRoot $path))) 'ACTUAL_LOCAL_MODULE_PAIR'
    }
    foreach ($badPair in @('java-options=--module-path=C:\foreign',
        "java-options=--module-path`r`njava-options=-Xmx512m", "java-options=--module-path`r`n[JavaOptions]`r`n"+'java-options=$APPDIR')) {
        $path=Join-Path $cfgRoot 'app/CashPrediction.cfg'
        $badText=$cfgTexts['app/CashPrediction.cfg'].Replace("java-options=--module-path`r`n"+'java-options=$APPDIR',$badPair)
        [IO.File]::WriteAllText($path,$badText,[Text.UTF8Encoding]::new($false))
        Assert-ArtifactReject {Assert-ArtifactLocalConfigs $cfgRoot $cfgRoot} 'COLD_CFG_MODULE_PATH'
        [IO.File]::WriteAllText($path,$cfgTexts['app/CashPrediction.cfg'],[Text.UTF8Encoding]::new($false))
    }
    foreach ($role in 'core','ui-fx','ui-swing','web') {
        $rolePath='app/cashprediction-'+$role+'-1.0.0.jar'
        $missing=@($files | Where-Object {$_.path -cne $rolePath})
        Assert-ArtifactReject {Assert-ColdImageInventory $missing (Get-ColdTreeHash $missing)} 'COLD_INVENTORY'
        $empty=Copy-ArtifactMock $files;($empty | Where-Object {$_.path -ceq $rolePath}).sizeBytes=0L
        Assert-ArtifactReject {Assert-ColdImageInventory $empty (Get-ColdTreeHash $empty)} 'COLD_INVENTORY'
        $extra=Copy-ArtifactMock ($files | Where-Object {$_.path -ceq $rolePath});$extra.path='app/cashprediction-'+$role+'-2.0.0.jar'
        $duplicate=@(@($files)+@($extra) | Sort-Object {[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($_.path))})
        Assert-ArtifactReject {Assert-ColdImageInventory $duplicate (Get-ColdTreeHash $duplicate)} 'COLD_INVENTORY'
    }
    $archive=Join-Path $owned 'CashPrediction-portable.zip'
    [IO.File]::WriteAllText($archive,'MOCK ASCII FULL ZIP - NOT NATIVE',[Text.UTF8Encoding]::new($false))
    $target=[pscustomobject][ordered]@{schemaVersion=2;releaseNumber=3;commitSha=('c'*40);version='3';publishedAtUtc='2026-10-04T00:00:00Z';
        assetName='CashPrediction-portable.zip';sizeBytes=(Get-Item -LiteralPath $archive).Length;
        sha256=(Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant();treeSha256=(Get-ColdTreeHash $files);files=$files;deltaPatches=@()}
    $bases=@();foreach ($number in 1,2) {$base=Copy-ArtifactMock $target;$base.releaseNumber=$number;$base.commitSha=([string]$number)*40;$bases+=@($base)}
    Assert-ArtifactBases $bases $target;$script:checks++
    $bad=Copy-ArtifactMock $bases;$bad[1].releaseNumber=$bad[0].releaseNumber
    Assert-ArtifactReject {Assert-ArtifactBases $bad $target} 'ARTIFACT_BASE_IDENTITY'
    $bad=Copy-ArtifactMock $bases;$bad[1].commitSha=$bad[0].commitSha
    Assert-ArtifactReject {Assert-ArtifactBases $bad $target} 'ARTIFACT_BASE_IDENTITY'
    $bad=Copy-ArtifactMock $bases;$bad[1].releaseNumber=$target.releaseNumber
    Assert-ArtifactReject {Assert-ArtifactBases $bad $target} 'ARTIFACT_FORWARD_RELEASE'
    Assert-ArtifactReject {Assert-ArtifactBases @($bases[0]) $target} 'ARTIFACT_BASE_COUNT'
    $badTarget=Copy-ArtifactMock $target;$badTarget.deltaPatches=@([pscustomobject]@{mock=$true})
    Assert-ArtifactReject {Assert-ArtifactBases $bases $badTarget} 'ARTIFACT_BASE_COUNT'
    $manifest=Join-Path $owned 'update.json';Write-ArtifactJson $manifest $target
    $manifestSha=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
    $read=Read-ArtifactManifest $manifest $manifestSha
    Assert-ArtifactMock ($read.releaseNumber -eq 3 -and $read.deltaPatches.Count -eq 0) 'ZERO_DELTA_INPUT'
    Assert-ArtifactReject {Read-ArtifactManifest $manifest ('0'*64)} 'COLD_PIN'
    $badManifest=Join-Path $owned 'bad-manifest.json';$bad=Copy-ArtifactMock $target;$bad.schemaVersion=1
    Write-ArtifactJson $badManifest $bad;$badSha=(Get-FileHash -LiteralPath $badManifest).Hash.ToLowerInvariant()
    Assert-ArtifactReject {Read-ArtifactManifest $badManifest $badSha} 'ARTIFACT_MANIFEST_SCHEMA'
    $original=[IO.File]::ReadAllText($manifest)
    $caught=$null;try {Write-ArtifactJson $manifest $bases[0]} catch {$caught=$_.Exception}
    Assert-ArtifactMock ($null -ne $caught -and [IO.File]::ReadAllText($manifest) -ceq $original) 'CREATE_NEW_NO_OVERWRITE'
    [void](Assert-ArtifactOutput $output @($owned));$script:checks++
    Assert-ArtifactReject {Assert-ArtifactOutput (Join-Path $temp 'not-uuid') @($owned)} 'ARTIFACT_OUTPUT_SCOPE'
    Assert-ArtifactReject {Assert-ArtifactOutput $owned @()} 'ARTIFACT_OUTPUT_EXISTS'
    Assert-ArtifactReject {Assert-ArtifactOutput $output @($temp)} 'ARTIFACT_OUTPUT_OVERLAP'
    Assert-ArtifactReject {Assert-ArtifactOutput 'relative' @()} 'NATIVE_ABSOLUTE_PATH'
    # Реальный filesystem inventory и независимый фиксированный big-endian tree-v1
    # vector. Здесь нет mock Assert-ColdTree и нет генерируемых native bytes.
    $byteRoot=Join-Path $owned 'byte-tree';$null=New-Item -ItemType Directory -Path (Join-Path $byteRoot 'app')
    $probe=Join-Path $byteRoot 'app/probe.txt';[IO.File]::WriteAllText($probe,'ABC',[Text.UTF8Encoding]::new($false))
    $abcSha='b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78'
    $vector='2b48b9ff6073630b0bca535dd0de8eb7e085348d491591783f589a50233d19f8'
    $byteFiles=@([pscustomobject]@{path='app/probe.txt';sizeBytes=3L;sha256=$abcSha;readOnly=$false})
    $byteManifest=[pscustomobject]@{files=$byteFiles;treeSha256=$vector}
    $byteActual=@(Assert-ColdTree $byteRoot $byteManifest)
    Assert-ArtifactMock ($byteActual.Count -eq 1 -and $byteActual[0].sha256 -ceq $abcSha -and (Get-ColdTreeHash $byteActual) -ceq $vector) 'INDEPENDENT_BYTE_VECTOR'
    [IO.File]::WriteAllText($probe,'ABD',[Text.UTF8Encoding]::new($false))
    Assert-ArtifactReject {Assert-ColdTree $byteRoot $byteManifest} 'COLD_MANAGED_TREE'
    [IO.File]::WriteAllText($probe,'ABC',[Text.UTF8Encoding]::new($false))
    [IO.File]::SetAttributes($probe,[IO.FileAttributes]::ReadOnly)
    Assert-ArtifactReject {Assert-ColdTree $byteRoot $byteManifest} 'COLD_MANAGED_TREE'
    [IO.File]::SetAttributes($probe,[IO.FileAttributes]::Normal)
    $bmp=[string][char]0xe000;$supplementary=[char]::ConvertFromUtf32(0x10000)
    [IO.File]::WriteAllText((Join-Path $byteRoot ('app/'+$bmp+'.txt')),'A')
    [IO.File]::WriteAllText((Join-Path $byteRoot ('app/'+$supplementary+'.txt')),'A')
    $ordered=@(Get-ColdManagedInventory $byteRoot)
    Assert-ArtifactMock ($ordered.Count -eq 3 -and $ordered[1].path -ceq ('app/'+$bmp+'.txt') -and $ordered[2].path -ceq ('app/'+$supplementary+'.txt')) 'UNSIGNED_UTF8_ORDER'
    $junction=Join-Path $owned 'junction';$null=New-Item -ItemType Junction -Path $junction -Target $owned
    Assert-ArtifactReject {Assert-ArtifactOutput $junction @()} 'Ссылочный путь проверки запрещён.'
    [IO.Directory]::Delete($junction)
    $command=[pscustomobject][ordered]@{schemaVersion=1;runtimeSha256=('a'*64);
        toolArguments=@('--module-path','MOCK_CORE.jar;MOCK_TOOL.jar','-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool');
        toolFiles=@([pscustomobject]@{path='MOCK_CORE.jar';sha256=('b'*64)});helperScript='MOCK_HELPER';helperSha256=('d'*64);
        baseManifests=@([pscustomobject]@{portableDir='MOCK_B1';manifest='MOCK_B1.json';sha256=('e'*64)},
            [pscustomobject]@{portableDir='MOCK_B2';manifest='MOCK_B2.json';sha256=('f'*64)});
        targetManifest=$manifest;targetManifestSha256=$manifestSha}
    $commandPath=Join-Path $owned 'command.json';Write-ArtifactJson $commandPath $command
    $commandSha=(Get-FileHash -LiteralPath $commandPath).Hash.ToLowerInvariant()
    $commandBefore=[IO.File]::ReadAllText($commandPath)
    $copy=New-ArtifactCommand $command 'new-manifest.json' ('1'*64)
    $restored=Copy-ArtifactMock $copy;$restored.targetManifest=$command.targetManifest;$restored.targetManifestSha256=$command.targetManifestSha256
    Assert-ArtifactMock ((ConvertTo-Json -InputObject $restored -Depth 64 -Compress) -ceq (ConvertTo-Json -InputObject $command -Depth 64 -Compress)) 'ONLY_TWO_COMMAND_FIELDS'
    # Runtime/JAR/native trees не генерируются: только CLI boundary и tree witness mock.
    function Assert-ColdCommand($Config,[string]$JavaPath,[string[]]$Bases,[string]$Target) {
        Assert-ColdKeys $Config @('schemaVersion','runtimeSha256','toolArguments','toolFiles','helperScript','helperSha256','baseManifests','targetManifest','targetManifestSha256')
        if ($JavaPath -cne 'MOCK_JAVA' -or $Bases.Count -ne 2 -or $Target -cne 'MOCK_T') {throw 'ARTIFACT_FIXTURE_PIN_CONTEXT'}
        [void](Read-ColdPinnedJson $Config.targetManifest $Config.targetManifestSha256)
    }
    function Assert-ColdTree([string]$Root,$Manifest) {
        if ($script:wrongInventory -and $Root -like '*proof*') {return @($Manifest.files[0])}
        return $Manifest.files
    }
    function Invoke-ColdTool($Config,[string]$Java,[string[]]$Arguments,[string]$Evidence,[string]$Root) {
        $script:calls.Add(@($Arguments));$parameters=@{}
        for ($index=1;$index -lt $Arguments.Count;$index+=2) {
            $key=$Arguments[$index];if (-not $parameters.ContainsKey($key)) {$parameters[$key]=@()}
            $parameters[$key]+=@($Arguments[$index+1])
        }
        switch -CaseSensitive ($Arguments[0]) {
            'create' {
                if ($parameters.Count -ne 6 -or -not $parameters.ContainsKey('--target') -or -not $parameters.ContainsKey('--manifest')) {throw 'ARTIFACT_FIXTURE_CREATE_API'}
                [IO.File]::WriteAllText($parameters['--out'][0],('MOCK ASCII PATCH '+$parameters['--base-release'][0]),[Text.UTF8Encoding]::new($false))
            }
            'manifest' {
                if ($parameters.Count -ne 8 -or $parameters['--delta'].Count -ne 2) {throw 'ARTIFACT_FIXTURE_MANIFEST_API'}
                $generated=Read-ArtifactManifest $Config.targetManifest $Config.targetManifestSha256
                $generated.deltaPatches=@($parameters['--delta'] | ForEach-Object {ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($_))})
                Write-ArtifactJson $parameters['--out'][0] $generated
            }
            'apply' {
                if ($parameters.Count -ne 6 -or -not $parameters.ContainsKey('--patch')) {throw 'ARTIFACT_FIXTURE_APPLY_API'}
                $null=New-Item -ItemType Directory -Path $parameters['--out'][0]
            }
            'verify' {
                if ($parameters.Count -ne 2) {throw 'ARTIFACT_FIXTURE_VERIFY_API'}
                if ($script:lateCorruption -and $parameters['--root'][0].EndsWith('B2')) {
                    $artifactRoot=Join-Path $Root 'artifacts'
                    $corruptPath=switch ($script:lateCorruption) {
                        'patch' {Join-Path $artifactRoot 'CashPrediction.from-1.cpdelta'}
                        'full' {Join-Path $artifactRoot 'CashPrediction-portable.zip'}
                        'descriptor' {Join-Path $Root 'descriptors/B1.json'}
                    }
                    $corruptBytes=[IO.File]::ReadAllBytes($corruptPath)
                    $corruptBytes[$corruptBytes.Length-1]=$corruptBytes[$corruptBytes.Length-1] -bxor 1
                    [IO.File]::WriteAllBytes($corruptPath,$corruptBytes)
                }
            }
            default {throw 'ARTIFACT_FIXTURE_UNKNOWN_CLI'}
        }
        return [pscustomobject]@{actualExit=0;args=$Arguments;mock=$true}
    }
    $script:wrongInventory=$false;$script:lateCorruption='';$null=New-Item -ItemType Directory -Path $output
    $context=[pscustomobject]@{output=$output;logs=(Join-Path $output 'logs');command=$command;commandPath=$commandPath;commandSha=$commandSha;
        java='MOCK_JAVA';sources=@('MOCK_B1','MOCK_B2');targetRoot='MOCK_T';target=$target;bases=$bases;archive=$archive}
    Assert-ArtifactReject {Invoke-ArtifactTool $context @('descriptor','--out','x')} 'ARTIFACT_CLI_COMMAND'
    $result=Invoke-ArtifactPreparation $context
    Assert-ArtifactMock ($result.status -ceq 'PREPARED' -and $result.nativeStatus -ceq 'PENDING') 'NO_NATIVE_PASS'
    Assert-ArtifactMock (($script:calls | ForEach-Object {$_[0]}) -join ',' -ceq 'create,create,manifest,apply,verify,apply,verify') 'ACTUAL_CLI_SEQUENCE'
    Assert-ArtifactMock ($result.proofs.Count -eq 2 -and $result.toolReceipts.Count -eq 7) 'BOTH_PROOFS'
    Assert-ArtifactMock ([IO.File]::ReadAllText($commandPath) -ceq $commandBefore) 'ORIGINAL_COMMAND_UNCHANGED'
    Assert-ArtifactMock ((Get-FileHash -LiteralPath (Join-Path $result.artifactDir 'CashPrediction-portable.zip')).Hash -ceq (Get-FileHash -LiteralPath $archive).Hash) 'FULL_ZIP_IDENTICAL'
    $newCommand=Read-ColdPinnedJson $result.commandFile $result.commandFileSha256
    Assert-ArtifactMock ($newCommand.targetManifest -ceq $result.manifest -and $newCommand.targetManifestSha256 -ceq $result.manifestSha256) 'NEW_COMMAND_PIN'
    $descriptor=ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText((Join-Path $output 'descriptors/B1.json')))
    Assert-ColdKeys $descriptor @('algorithm','algorithmVersion','baseReleaseNumber','baseCommitSha','baseTreeSha256','assetName','sizeBytes','sha256');$script:checks++
    Assert-ArtifactMock ($descriptor.baseReleaseNumber -eq 1 -and $descriptor.baseCommitSha -ceq $bases[0].commitSha -and $descriptor.baseTreeSha256 -ceq $bases[0].treeSha256) 'STRICT_BASE_IDENTITY'
    Assert-ArtifactMock ($descriptor.sha256 -ceq (Get-FileHash -LiteralPath (Join-Path $result.artifactDir $descriptor.assetName)).Hash.ToLowerInvariant()) 'REAL_PATCH_FILE_SHA'
    $wrong=Copy-ArtifactMock $bases[0];$wrong.releaseNumber=2
    Assert-ArtifactReject {New-ArtifactDescriptor $wrong (Join-Path $result.artifactDir $descriptor.assetName)} 'ARTIFACT_DESCRIPTOR'
    $script:wrongInventory=$true
    $actual=@(Assert-ColdTree (Join-Path $output 'proof/B1') $target)
    Assert-ArtifactReject {Assert-ArtifactAppliedInventory $actual $target.files} 'ARTIFACT_APPLIED_INVENTORY'
    $script:wrongInventory=$false
    foreach ($fault in 'patch','full','descriptor') {
        $faultRoot=Join-Path $owned ('late-'+$fault);$null=New-Item -ItemType Directory -Path $faultRoot
        $faultContext=Copy-ArtifactMock $context;$faultContext.output=$faultRoot;$faultContext.logs=Join-Path $faultRoot 'logs'
        $script:lateCorruption=$fault
        $code=if ($fault -ceq 'descriptor') {'COLD_PIN'} else {'LIFECYCLE_PAYLOAD_PIN'}
        Assert-ArtifactReject {Invoke-ArtifactPreparation $faultContext} $code
        Assert-ArtifactMock (-not (Test-Path -LiteralPath (Join-Path $faultRoot 'preparation.json')) -and
            -not (Test-Path -LiteralPath (Join-Path $faultRoot 'command.json'))) 'NO_PREPARED_AFTER_LATE_CORRUPTION'
    }
    $script:lateCorruption=''
    $coldSource=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'))
    Assert-ArtifactMock ($coldSource.Contains("@('--arguments-base64',`$payload)") -and $coldSource.Contains('AddSeconds(60)')) 'FROZEN_ASCII_BOUNDED_CLI'
    $forbidden=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and
        $n.GetCommandName() -in @('Start-Process','mvn','javac','Invoke-WebRequest','Invoke-RestMethod')},$true))
    Assert-ArtifactMock ($forbidden.Count -eq 0) 'NO_BUILD_GUI_NETWORK'
    Write-Output "Native artifacts mock contracts: PASS ($script:checks checks; real CLI/native not executed; native PENDING)."
} finally {
    # Только точно принадлежащие прямые Temp UUID; доказательства реальные builder не удаляет.
    foreach ($directory in $owned,$output) {
        $resolved=[IO.Path]::GetFullPath($directory)
        if (-not [IO.Path]::GetDirectoryName($resolved).Equals($temp,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'ARTIFACT_FIXTURE_CLEANUP_SCOPE'}
        if (Test-Path -LiteralPath $resolved) {
            $junction=Join-Path $resolved 'junction'
            if (Test-Path -LiteralPath $junction) {
                $item=Get-Item -LiteralPath $junction -Force
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {[IO.Directory]::Delete($junction)}
            }
            Assert-PortableTreeHasNoLinks $resolved;Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
