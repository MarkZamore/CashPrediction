<#
.SYNOPSIS
Только mock/path/context проверки нового adapter, без CLI/JVM/native/GUI.
.DESCRIPTION
Не вызывает Invoke-NativePayloadScenario/Worker или существующий native runner.
Итог MOCK_ONLY, nativeStatus=PENDING. Файлы стенда - собственный Temp UUID.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'FIXTURE_DEPENDENCY_PARSE'}
foreach ($name in 'Assert-ColdKeys','Test-ColdInteger','Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson') {
    $definition=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))[0]
    . ([scriptblock]::Create($definition.Extent.Text))
}
$script:checks=0

# Проверка ожидаемого условия не создаёт receipt native PASS.
function Assert-PayloadMock([bool]$Condition,[string]$Name) {
    if (-not $Condition) {throw "MOCK_ASSERT: $Name"};$script:checks++
}

# Negative control должен отказать по ожидаемому guard, не случайной синтаксической ошибке.
function Reject-PayloadMock([scriptblock]$Action,[string]$Code) {
    $reason=$null;try {& $Action} catch {$reason=$_.Exception.Message}
    Assert-PayloadMock ($null -ne $reason -and $reason.Contains($Code)) $Code
}

# Ограниченный mock абсолютного пути: не используем filesystem production inputs.
function Assert-NativeAbsolute([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path -cne [IO.Path]::GetFullPath($Path).TrimEnd('\','/')) {throw 'MOCK_ABSOLUTE'}
    return $Path
}

# Mock containment учитывает separator; C:/base2 не потомок C:/base.
function Test-PortablePathContains([string]$Parent,[string]$Child) {
    return $Parent.Equals($Child,[StringComparison]::OrdinalIgnoreCase) -or
        $Child.StartsWith($Parent.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
}

# Только mock canonical resolver для output guard, не подмена native проверки.
function Resolve-PortableSafetyPath([string]$Path) {return [IO.Path]::GetFullPath($Path).TrimEnd('\','/')}

# Mock link boundary позволяет проверить, что snapshot запрашивает guard.
function Assert-PortableTreeHasNoLinks([string]$Root) {
    $script:linkChecks++;if ($script:rejectLinks) {throw 'MOCK_LINK'}
}

# Копия JSON модели для независимых отрицательных проверок.
function Copy-PayloadMock($Value) {
    return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 32 -Compress))
}

# Запись данных только внутри уже проверенного собственного UUID стенда.
function Write-PayloadMock([string]$Name,$Value) {
    $path=Join-Path $script:mockRoot $Name
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject $Value -Depth 32 -Compress),[Text.UTF8Encoding]::new($false))
    return $path
}

$script:mockRoot=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $script:mockRoot
$script:linkChecks=0;$script:rejectLinks=$false
$script:ownedAdapterDirectories=[Collections.Generic.List[string]]::new()
try {
    $context=[pscustomobject]@{Row=[pscustomobject]@{scenario='unicode-payload';base='B1';client='fx';path='unicode';status='PENDING'};
        Source=(Join-Path $mockRoot 'B1');PortableDir=@((Join-Path $mockRoot 'B1'),(Join-Path $mockRoot 'B2'));
        TargetPortableDir=(Join-Path $mockRoot 'T');CommandFile=(Join-Path $mockRoot 'command.json');CommandFileSha256=('a'*64);
        LifecycleFile=(Join-Path $mockRoot 'life.json');LifecycleFileSha256=('b'*64);Java=(Join-Path $mockRoot 'java.exe');
        Evidence=(Join-Path $mockRoot 'evidence');Timeout=180}
    Assert-NativePayloadContext $context;Assert-PayloadMock $true 'GOOD_CONTEXT'
    foreach ($scenario in 'cashmemory','unmanaged-old-or-new') {
        $c=Copy-PayloadMock $context;$c.Row.scenario=$scenario;Assert-NativePayloadContext $c;Assert-PayloadMock $true $scenario
    }
    $c=Copy-PayloadMock $context;$c.Source=$c.PortableDir[1];Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_BASE_CONTEXT'
    $c=Copy-PayloadMock $context;$c.Row.scenario='delta';Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_CONTEXT'
    $c=Copy-PayloadMock $context;$c.Timeout='180';Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_CONTEXT'
    $c=Copy-PayloadMock $context;$c.Row.status='PASS';Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_CONTEXT'
    $c=Copy-PayloadMock $context;$c.TargetPortableDir=$c.PortableDir[0];Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_INPUT_OVERLAP'
    $c=Copy-PayloadMock $context;$c.TargetPortableDir=Join-Path $c.PortableDir[0] 'nested';Reject-PayloadMock {Assert-NativePayloadContext $c} 'PAYLOAD_INPUT_OVERLAP'
    $c=Copy-PayloadMock $context;$c.Source='relative';$c.PortableDir[0]='relative';Reject-PayloadMock {Assert-NativePayloadContext $c} 'MOCK_ABSOLUTE'
    $fixture=Get-NativePayloadFixture
    Assert-PayloadMock ($fixture.path.StartsWith('app/') -and $fixture.path -match '[^\x00-\x7f]' -and $fixture.bytes.Length -gt 0) 'UNICODE_MANAGED_CONTENT'
    Assert-PayloadMock ([Text.UTF8Encoding]::new($false,$true).GetString($fixture.bytes).Contains('日本')) 'UTF8_CONTENT'
    $oldFile=[pscustomobject]@{path='app/core.jar';sizeBytes=7L;sha256=('c'*64);readOnly=$false}
    $payload=[pscustomobject]@{path=$fixture.path;sizeBytes=[long]$fixture.bytes.Length;sha256=$fixture.sha256;readOnly=$false}
    $old=[pscustomobject]@{releaseNumber=1003;commitSha=('3'*40);version='1.0.0';publishedAtUtc='2026-10-04T00:00:00Z';treeSha256=('d'*64);files=@($oldFile)}
    $new=Copy-PayloadMock $old;$new.treeSha256='e'*64;$new.files=@($oldFile,$payload)
    $version=[pscustomobject]@{releaseNumber=1003;commitSha=('3'*40);jarSha256=('c'*64)}
    Assert-NativePayloadDerived $old $new $fixture $version $version;Assert-PayloadMock $true 'GOOD_DERIVED_UNCHANGED_CORE'
    $n=Copy-PayloadMock $new;$n.files[0].sha256='f'*64;Reject-PayloadMock {Assert-NativePayloadDerived $old $n $fixture $version $version} 'PAYLOAD_ORIGINAL_ENTRY_CHANGED'
    $n=Copy-PayloadMock $new;$n.files[1].sizeBytes=0;Reject-PayloadMock {Assert-NativePayloadDerived $old $n $fixture $version $version} 'PAYLOAD_UNICODE_ENTRY'
    $n=Copy-PayloadMock $new;$n.releaseNumber=1004;Reject-PayloadMock {Assert-NativePayloadDerived $old $n $fixture $version $version} 'PAYLOAD_DERIVED_IDENTITY'
    $v=Copy-PayloadMock $version;$v.jarSha256='f'*64;Reject-PayloadMock {Assert-NativePayloadDerived $old $new $fixture $version $v} 'PAYLOAD_DERIVED_IDENTITY'
    $fixtureInput=Join-Path $mockRoot 'original.txt';[IO.File]::WriteAllText($fixtureInput,'original')
    $snapshot=Get-NativePayloadSnapshot @($fixtureInput)
    Assert-PayloadMock ($snapshot -ceq (Get-NativePayloadSnapshot @($fixtureInput)) -and $script:linkChecks -eq 2) 'SNAPSHOT_STABLE_LINK_GUARDED'
    [IO.File]::WriteAllText($fixtureInput,'mutation');Assert-PayloadMock ($snapshot -cne (Get-NativePayloadSnapshot @($fixtureInput))) 'SNAPSHOT_CHANGED'
    $script:rejectLinks=$true;Reject-PayloadMock {Get-NativePayloadSnapshot @($fixtureInput)} 'MOCK_LINK';$script:rejectLinks=$false
    $user=[ordered]@{}
    foreach ($path in 'CashMemory/protected-user.txt','CashMemory/NativeLifecycle.md','CashMemory/settings.md','protected-root.txt') {
        $user[$path]=[pscustomobject]@{path=$path;directory=$false;sizeBytes=4L;sha256=('a'*64);readOnly=$false}
    }
    $installed=Join-Path $mockRoot 'installed';$unicodeFile=Join-Path $installed $fixture.path
    $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($unicodeFile))
    [IO.File]::WriteAllBytes($unicodeFile,$fixture.bytes)
    $row=[pscustomobject]@{status='PASS';reason='NATIVE_LIFECYCLE_EXECUTED';executed=$true;exitCode=0;failures=0;skipped=0;
        exe=(Join-Path $installed 'CashPrediction.exe');currentBefore=(Write-PayloadMock 'before.json' $old.files);
        currentAfter=(Write-PayloadMock 'after.json' $new.files);targetBefore=(Write-PayloadMock 'tb.json' $new.files);
        targetAfter=(Write-PayloadMock 'ta.json' $new.files);userBefore=(Write-PayloadMock 'ub.json' $user);userAfter=(Write-PayloadMock 'ua.json' $user)}
    # Эта synthetic row используется только как вход assertion, не публикуется как native evidence.
    Assert-NativePayloadObservation $row $old $new $fixture;Assert-PayloadMock $true 'MOCK_POSTCONDITIONS_ACCEPTED'
    $r=Copy-PayloadMock $row;$r.executed=$false;Reject-PayloadMock {Assert-NativePayloadObservation $r $old $new $fixture} 'PAYLOAD_NATIVE_NOT_EXECUTED'
    $r=Copy-PayloadMock $row;$r.currentAfter=Write-PayloadMock 'mixed.json' @($oldFile);Reject-PayloadMock {Assert-NativePayloadObservation $r $old $new $fixture} 'PAYLOAD_NOT_COMPLETE_NEW_TREE'
    $r=Copy-PayloadMock $row;$r.userAfter=Write-PayloadMock 'changed-user.json' @{};Reject-PayloadMock {Assert-NativePayloadObservation $r $old $new $fixture} 'PAYLOAD_USER_CHANGED'
    [IO.File]::WriteAllBytes($unicodeFile,[byte[]]@(1,2));Reject-PayloadMock {Assert-NativePayloadObservation $row $old $new $fixture} 'PAYLOAD_UNICODE_BYTES'
    $helperAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1'),[ref]$tokens,[ref]$errors)
    Assert-PayloadMock ($errors.Count -eq 0) 'HELPER_PARSE'
    $facade=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativePayloadScenario'},$true))[0].Extent.Text
    Assert-PayloadMock ($facade.Contains('[PowerShell]::Create()') -and $facade.Contains('$worker.Dispose()')) 'ISOLATED_CONTEXT_DISPOSAL_STATIC'
    $artifactAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'New-NativeUpdateArtifacts.ps1'),[ref]$tokens,[ref]$errors)
    $outputDefinition=@($artifactAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Assert-ArtifactOutput'},$true))[0]
    . ([scriptblock]::Create($outputDefinition.Extent.Text))
    foreach ($name in 'Write-ArtifactJson','New-ArtifactCommand') {
        $definition=@($artifactAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))[0]
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $freshOutput=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    Assert-PayloadMock ((Assert-ArtifactOutput $freshOutput @($fixtureInput)) -ceq $freshOutput) 'GOOD_FRESH_TEMP_UUID'
    Reject-PayloadMock {Assert-ArtifactOutput $mockRoot @($fixtureInput)} 'ARTIFACT_OUTPUT_EXISTS'
    Reject-PayloadMock {Assert-ArtifactOutput (Join-Path $mockRoot ([guid]::NewGuid().ToString())) @($fixtureInput)} 'ARTIFACT_OUTPUT_SCOPE'
    Reject-PayloadMock {Assert-ArtifactOutput $freshOutput @([IO.Path]::GetTempPath().TrimEnd('\','/'))} 'ARTIFACT_OUTPUT_OVERLAP'
    # Mocked preparation использует реальное копирование/inventory/ZIP, но CLI не запускается.
    foreach ($name in 'Get-ColdManagedInventory','Get-ColdTreeHash','Assert-ColdTree') {
        $definition=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))[0]
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $builderAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'New-UpdateBootstrapCommands.ps1'),[ref]$tokens,[ref]$errors)
    $definition=@($builderAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'New-BootstrapBuilderArchive'},$true))[0]
    . ([scriptblock]::Create($definition.Extent.Text))

    # Mock path validator только для фиксированных файлов стенда.
    function Assert-S7Path([string]$Path) {if ($Path.StartsWith('/') -or $Path.Contains('..')) {throw 'MOCK_PATH'}}
    # Mock command boundary считает проверки, но ничего не запускает.
    function Assert-ColdCommand($Command,[string]$Java,[string[]]$Bases,[string]$TargetRoot) {$script:commandChecks++}
    # Mock core identity берёт настоящие bytes стенда, не production JAR.
    function Get-ColdVersion([string]$Root) {
        return [pscustomobject]@{releaseNumber=1003;commitSha=('3'*40);jar='core.jar';
            jarSha256=(Get-FileHash -LiteralPath (Join-Path $Root 'app/core.jar')).Hash.ToLowerInvariant()}
    }
    # Mock CLI пишет только manifest модели; receipt прямо помечен MOCK_ONLY.
    function Invoke-ColdTool($Command,[string]$Java,[string[]]$Arguments,[string]$Evidence,[string]$Root) {
        if ($script:failMockCli) {throw 'MOCK_CLI_FAILED'}
        $script:mockCliArguments=$Arguments
        $image=$Arguments[[Array]::IndexOf($Arguments,'--root')+1]
        $archive=$Arguments[[Array]::IndexOf($Arguments,'--archive')+1]
        $out=$Arguments[[Array]::IndexOf($Arguments,'--out')+1]
        $manifest=Copy-PayloadMock $script:prepOriginal
        $manifest.files=@(Get-ColdManagedInventory $image);$manifest.treeSha256=Get-ColdTreeHash $manifest.files
        $manifest | Add-Member deltaPatches @() -Force
        $manifest | Add-Member sha256 (Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant() -Force
        $manifest | Add-Member sizeBytes ([long](Get-Item -LiteralPath $archive).Length) -Force
        Write-ArtifactJson $out $manifest
        return [pscustomobject]@{scope='MOCK_ONLY';actualExit=0;nativeStatus='PENDING'}
    }
    # Mock manifest чтение не заменяет production schema validation в настоящем worker.
    function Read-ArtifactManifest([string]$Path,[string]$Hash) {
        if ((Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() -cne $Hash) {throw 'MOCK_PIN'}
        return (ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Path -Raw))
    }
    $prepSource=Join-Path $mockRoot 'prep-original';$null=New-Item -ItemType Directory -Path (Join-Path $prepSource 'app')
    [IO.File]::WriteAllText((Join-Path $prepSource 'app/core.jar'),'coremock')
    $script:prepOriginal=Copy-PayloadMock $old;$script:prepOriginal.files=@(Get-ColdManagedInventory $prepSource)
    $script:prepOriginal.treeSha256=Get-ColdTreeHash $script:prepOriginal.files
    $script:commandChecks=0;$script:failMockCli=$false;$script:mockCliArguments=@()
    $prepContext=Copy-PayloadMock $context;$prepContext.TargetPortableDir=$prepSource
    $mockCold=[pscustomobject]@{schemaVersion=1;targetManifest='old-manifest';targetManifestSha256=('a'*64);toolArguments=@('FROZEN_MOCK_TOOL')}
    $prepWork=Join-Path $mockRoot 'prep-work';$null=New-Item -ItemType Directory -Path $prepWork
    $originalSnapshot=Get-NativePayloadSnapshot @($prepSource)
    $derived=New-NativePayloadDerivedTarget $prepContext $mockCold $script:prepOriginal $prepWork
    Assert-PayloadMock ($script:commandChecks -eq 1 -and $script:mockCliArguments[0] -ceq 'manifest') 'MOCK_PREPARATION_REAL_CONTRACT'
    Assert-PayloadMock ((Get-NativePayloadSnapshot @($prepSource)) -ceq $originalSnapshot) 'PREPARATION_ORIGINAL_IMMUTABLE'
    Assert-PayloadMock ((Read-ArtifactManifest $derived.command $derived.commandSha256).toolArguments[0] -ceq 'FROZEN_MOCK_TOOL') 'COMMAND_TOOL_ARGUMENTS_RETAINED'
    $zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $prepWork 'full/CashPrediction-portable.zip'))
    try {
        $unicodeEntry=$zip.GetEntry('CashPrediction/'+$fixture.path)
        Assert-PayloadMock ($null -ne $unicodeEntry -and $zip.Entries.Count -eq 2) 'ZIP_REAL_UNICODE_ENTRY'
        $zipStream=$unicodeEntry.Open();$zipBytes=[IO.MemoryStream]::new()
        try {$zipStream.CopyTo($zipBytes);Assert-PayloadMock ([Convert]::ToHexString($zipBytes.ToArray()) -ceq [Convert]::ToHexString($fixture.bytes)) 'ZIP_REAL_UTF8_BYTES'}
        finally {$zipStream.Dispose();$zipBytes.Dispose()}
    } finally {$zip.Dispose()}
    $script:failMockCli=$true;$failWork=Join-Path $mockRoot 'prep-failed';$null=New-Item -ItemType Directory -Path $failWork
    Reject-PayloadMock {New-NativePayloadDerivedTarget $prepContext $mockCold $script:prepOriginal $failWork} 'MOCK_CLI_FAILED'
    Assert-PayloadMock ((Get-NativePayloadSnapshot @($prepSource)) -ceq $originalSnapshot) 'CLI_FAILURE_ORIGINAL_IMMUTABLE'
    $cellDefinition=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativePayloadCell'},$true))[0]
    Assert-PayloadMock ($cellDefinition.Parameters.Count -eq 9 -and $cellDefinition.Extent.Text.Contains('Invoke-NativePayloadWorker')) 'NINE_PARAM_REAL_WORKER_ROUTE_STATIC'
    # Строится настоящий Context девятипараметрового adapter, но его native worker не вызывается.
    function Assert-NativeLifecycleConfig($Life) {if ($Life.schemaVersion -ne 1) {throw 'MOCK_LIFECYCLE'}}
    function Read-ColdPinnedJson([string]$Path,[string]$Hash) {return (Read-ArtifactManifest $Path $Hash)}
    function Read-LifecycleManifest([string]$Directory,[string]$Hash) {return (Read-ArtifactManifest (Join-Path $Directory 'update.json') $Hash)}
    $adapterArtifacts=Join-Path $mockRoot 'adapter-artifacts';$null=New-Item -ItemType Directory -Path $adapterArtifacts
    Write-ArtifactJson (Join-Path $adapterArtifacts 'update.json') $script:prepOriginal
    $adapterPin=(Get-FileHash -LiteralPath (Join-Path $adapterArtifacts 'update.json')).Hash.ToLowerInvariant()
    $adapterLife=[pscustomobject]@{schemaVersion=1;artifactDir=$adapterArtifacts;manifestSha256=$adapterPin;harnessClasspath=$context.Java;harnessFiles=@()}
    $adapterBases=@();$adapterEntries=@()
    foreach ($number in 1,2) {
        $image=Join-Path $mockRoot ('adapter-B'+$number);Copy-Item -LiteralPath $prepSource -Destination $image -Recurse
        $manifest=Copy-PayloadMock $script:prepOriginal;$manifest.releaseNumber=1000+$number;$manifest.commitSha=([string]$number)*40
        $basePath=Join-Path $mockRoot ('adapter-base-'+$number+'.json');Write-ArtifactJson $basePath $manifest
        $adapterBases+=@($image);$adapterEntries+=@([pscustomobject]@{portableDir=$image;manifest=$basePath;sha256=(Get-FileHash -LiteralPath $basePath).Hash.ToLowerInvariant()})
    }
    $adapterCold=[pscustomobject]@{schemaVersion=1;targetManifest=(Join-Path $adapterArtifacts 'update.json');targetManifestSha256=$adapterPin;
        baseManifests=$adapterEntries;toolArguments=@('FROZEN_MOCK_TOOL');helperScript=$fixtureInput;
        toolFiles=@([pscustomobject]@{path=(Join-Path $prepSource 'app/core.jar');sha256=('a'*64)})}
    $adapterBase=Read-ColdPinnedJson $adapterEntries[0].manifest $adapterEntries[0].sha256
    $adapterEvidence=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString());$null=New-Item -ItemType Directory -Path $adapterEvidence
    $script:ownedAdapterDirectories.Add($adapterEvidence)
    $script:nativeTarget=$prepSource
    $adapterContext=New-NativePayloadCellContext $context.Row $adapterBases[0] $adapterBase $script:prepOriginal $adapterLife $adapterCold $context.Java $adapterEvidence 180
    $script:ownedAdapterDirectories.Add([IO.Path]::GetDirectoryName($adapterContext.CommandFile))
    Assert-PayloadMock ($adapterContext.TargetPortableDir -ceq $prepSource -and $adapterContext.Evidence -cne $adapterEvidence -and
        -not (Test-Path -LiteralPath $adapterContext.Evidence)) 'ADAPTER_OWN_UUID_NOT_CALLER_OUTPUT'
    Assert-PayloadMock ((Read-ColdPinnedJson $adapterContext.CommandFile $adapterContext.CommandFileSha256).toolArguments[0] -ceq 'FROZEN_MOCK_TOOL') 'ADAPTER_PINNED_COMMAND_COPY'
    Assert-PayloadMock (Test-ColdInventoryEqual (Read-ColdPinnedJson $adapterContext.LifecycleFile $adapterContext.LifecycleFileSha256) $adapterLife) 'ADAPTER_LIFECYCLE_COPY_NOT_REPLACED'
    $wrongBase=Copy-PayloadMock $adapterBase;$wrongBase.commitSha='f'*40
    Reject-PayloadMock {New-NativePayloadCellContext $context.Row $adapterBases[0] $wrongBase $script:prepOriginal $adapterLife $adapterCold $context.Java $adapterEvidence 180} 'PAYLOAD_CALLER_BASE_PIN'
    $wrongTarget=Copy-PayloadMock $script:prepOriginal;$wrongTarget.treeSha256='f'*64
    Reject-PayloadMock {New-NativePayloadCellContext $context.Row $adapterBases[0] $adapterBase $wrongTarget $adapterLife $adapterCold $context.Java $adapterEvidence 180} 'PAYLOAD_CALLER_TARGET_PIN'
    Reject-PayloadMock {New-NativePayloadCellContext $context.Row $adapterBases[0] $adapterBase $script:prepOriginal $adapterLife $adapterCold $context.Java $mockRoot 180} 'PAYLOAD_CALLER_EVIDENCE_OVERLAP'
    # Выполняется префикс настоящего AST Invoke-ColdTool до создания Process, никогда Start/Java.
    $transportDefinition=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-ColdTool'},$true))[0]
    $transportPrefix=@()
    foreach ($statement in $transportDefinition.Body.EndBlock.Statements) {
        if ($statement.Extent.Text -cmatch '^\$process=') {break}
        $transportPrefix+=@($statement.Extent.Text)
    }
    $probeText='function Invoke-PayloadTransportProbe($Config,[string]$Java,[string[]]$Arguments,[string]$Evidence,[string]$Root) {'+
        ($transportPrefix -join "`n")+'; return [pscustomobject]@{effectiveArguments=@($info.ArgumentList);workingDirectory=$info.WorkingDirectory} }'
    . ([scriptblock]::Create($probeText))
    $unicodeRoot=Join-Path $mockRoot 'Δ_日本';$wire=@('manifest','--root',$unicodeRoot,'--out',(Join-Path $unicodeRoot 'манифест.json'))
    $wireConfig=[pscustomobject]@{toolArguments=@('--module-path','C:/mock/core.jar;C:/mock/tool.jar','-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool')}
    $wireProbe=Invoke-PayloadTransportProbe $wireConfig 'java-never-started.exe' $wire $mockRoot $unicodeRoot
    $switchIndex=[Array]::IndexOf($wireProbe.effectiveArguments,'--arguments-base64')
    Assert-PayloadMock ($switchIndex -eq 5 -and $wireProbe.effectiveArguments.Count -eq 7 -and
        @($wireProbe.effectiveArguments | Where-Object {$_ -match '[^\x00-\x7f]'}).Count -eq 0) 'ACTUAL_AST_ASCII_ARGV_NOT_RAW_UNICODE_ROOT'
    $decoded=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString([Convert]::FromBase64String($wireProbe.effectiveArguments[$switchIndex+1])))
    Assert-PayloadMock (Test-ColdInventoryEqual $decoded $wire) 'ACTUAL_AST_UTF8_BASE64_ROUNDTRIP_NOT_JAVA_EXECUTION'
    Assert-PayloadMock ($wireProbe.workingDirectory -ceq $unicodeRoot) 'ACTUAL_AST_WIDE_WORKDIR_RETAINED'
    $newDerivedDefinition=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'New-NativePayloadDerivedTarget'},$true))[0]
    $derivedCommands=@($newDerivedDefinition.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true) | ForEach-Object {$_.GetCommandName()})
    Assert-PayloadMock ('Invoke-ColdTool' -cin $derivedCommands -and 'Start-Process' -cnotin $derivedCommands) 'ACTUAL_DERIVED_AST_FROZEN_TRANSPORT_ROUTE'
    $artifactToolDefinition=@($artifactAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-ArtifactTool'},$true))[0]
    Assert-PayloadMock (@($artifactToolDefinition.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Invoke-ColdTool'},$true)).Count -eq 1) 'ACTUAL_ARTIFACT_AST_SAME_TRANSPORT_ROUTE'
    # Context-only/PENDING результат не должен передать synthetic PASS в Row MAIN.
    Reject-PayloadMock {Assert-NativePayloadResult ([pscustomobject]@{nativeStatus='PENDING'}) $adapterContext} 'PAYLOAD_RESULT_SCOPE'
    $destination=Copy-PayloadMock $context.Row
    $syntheticCell=Copy-PayloadMock $context.Row;$syntheticCell | Add-Member status 'PASS' -Force
    Reject-PayloadMock {Copy-NativePayloadCellEvidence $destination $syntheticCell} 'PAYLOAD_ROW_PROPAGATION_SCOPE'
    Assert-PayloadMock ($destination.status -ceq 'PENDING') 'REJECTED_ROW_UNCHANGED'
    # Только передача synthetic полей в памяти; native receipt/PASS в evidence не создаётся.
    foreach ($name in @{payloadScope='SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF';payloadFullMatrix='PENDING';payloadReleaseProvenance='NOT_PROVEN';
        payloadManifestSha256=$adapterPin;payloadTreeSha256=$script:prepOriginal.treeSha256;payloadObservation='MOCK_LINK';
        payloadArtifactDir=$adapterArtifacts;payloadCommandFile=$adapterContext.CommandFile;payloadLifecycleFile=$adapterContext.LifecycleFile;
        payloadFixtureIdentity='MOCK_FIXTURE';currentAfter='MOCK_INVENTORY'}.GetEnumerator()) {$syntheticCell | Add-Member $name.Key $name.Value -Force}
    Copy-NativePayloadCellEvidence $destination $syntheticCell
    Assert-PayloadMock ($destination.payloadManifestSha256 -ceq $adapterPin -and $destination.payloadArtifactDir -ceq $adapterArtifacts -and
        $destination.payloadCommandFile -ceq $adapterContext.CommandFile -and $destination.currentAfter -ceq 'MOCK_INVENTORY' -and
        $destination.payloadFixtureIdentity -ceq 'MOCK_FIXTURE' -and $destination.payloadFullMatrix -ceq 'PENDING' -and
        $destination.payloadReleaseProvenance -ceq 'NOT_PROVEN') 'ACTUAL_ROW_COPY_FIELDS_NOT_CONTEXT_ONLY'
    $wrongCell=Copy-PayloadMock $syntheticCell;$wrongCell.base='B2'
    Reject-PayloadMock {Copy-NativePayloadCellEvidence $destination $wrongCell} 'PAYLOAD_ROW_PROPAGATION_IDENTITY'
    # Result validator получает только виртуальные mock JSON, не записывает native PASS на диск.
    $script:virtualJson=@{}
    function Get-Content([string]$LiteralPath,[switch]$Raw,[string]$Encoding='utf8') {
        if ($script:virtualJson.ContainsKey($LiteralPath)) {return $script:virtualJson[$LiteralPath]}
        return (Microsoft.PowerShell.Management\Get-Content -LiteralPath $LiteralPath -Raw:$Raw -Encoding $Encoding)
    }
    $resultContext=Copy-PayloadMock $adapterContext;$resultContext.Row.scenario='cashmemory'
    $resultCell=Copy-PayloadMock $syntheticCell;$resultCell.scenario='cashmemory'
    foreach ($entry in @{executed=$true;reason='NATIVE_LIFECYCLE_EXECUTED';payloadObservation=(Join-Path $resultContext.Evidence 'payload-observation.json');
        payloadInputPins=(Join-Path $resultContext.Evidence 'input-pin-check.json');payloadCommandSha256=$resultContext.CommandFileSha256;
        payloadLifecycleSha256=$resultContext.LifecycleFileSha256}.GetEnumerator()) {$resultCell | Add-Member $entry.Key $entry.Value -Force}
    $mockResult=[pscustomobject]@{status='SCOPED_EVIDENCE';nativeStatus='PENDING';scope='cashmemory';fullMatrix='PENDING';releaseProvenance='NOT_PROVEN';cell=$resultCell}
    $mockPins=[pscustomobject]@{status='VERIFIED';nativeStatus='PENDING';commandSha256=$resultContext.CommandFileSha256;lifecycleSha256=$resultContext.LifecycleFileSha256}
    $script:virtualJson[$resultCell.payloadObservation]=ConvertTo-Json -InputObject $mockResult -Depth 32 -Compress
    $script:virtualJson[$resultCell.payloadInputPins]=ConvertTo-Json -InputObject $mockPins -Depth 32 -Compress
    Assert-NativePayloadResult $mockResult $resultContext;Assert-PayloadMock $true 'REQUIRED_GOOD_RESULT_WITH_LINKED_PINS_MOCK_ONLY'
    $wrongPins=Copy-PayloadMock $mockPins;$wrongPins.commandSha256='f'*64
    $script:virtualJson[$resultCell.payloadInputPins]=ConvertTo-Json -InputObject $wrongPins -Depth 32 -Compress
    Reject-PayloadMock {Assert-NativePayloadResult $mockResult $resultContext} 'PAYLOAD_RESULT_ORIGINAL_PINS'
    $script:virtualJson[$resultCell.payloadInputPins]=ConvertTo-Json -InputObject $mockPins -Depth 32 -Compress
    $script:virtualJson[$resultCell.payloadObservation]='{}'
    Reject-PayloadMock {Assert-NativePayloadResult $mockResult $resultContext} 'PAYLOAD_RESULT_OBSERVATION_CHANGED'
    $script:virtualJson[$resultCell.payloadObservation]=ConvertTo-Json -InputObject $mockResult -Depth 32 -Compress
    $wrongResult=Copy-PayloadMock $mockResult;$wrongResult.cell.payloadTreeSha256='f'*64
    $script:virtualJson[$resultCell.payloadObservation]=ConvertTo-Json -InputObject $wrongResult -Depth 32 -Compress
    Reject-PayloadMock {Assert-NativePayloadResult $wrongResult $resultContext} 'PAYLOAD_RESULT_TREE_PIN'
    # Unicode result positive/negative controls тоже виртуальны; release provenance не повышается.
    function Read-ColdPinnedJson([string]$Path,[string]$Hash) {
        if ($script:virtualJson.ContainsKey($Path)) {
            $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes($script:virtualJson[$Path])
            if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Hash) {throw 'MOCK_PIN'}
            return (ConvertFrom-ColdReceiptJson $script:virtualJson[$Path])
        }
        return (Read-ArtifactManifest $Path $Hash)
    }
    $unicodeContext=Copy-PayloadMock $adapterContext
    $unicodeResult=Copy-PayloadMock $mockResult;$unicodeResult.scope='unicode-payload';$unicodeResult.cell.scenario='unicode-payload'
    $unicodeManifest=Join-Path $prepWork 'full/update.json';$unicodePin=(Get-FileHash -LiteralPath $unicodeManifest).Hash.ToLowerInvariant()
    $unicodeLife=Copy-PayloadMock $adapterLife;$unicodeLife.artifactDir=Join-Path $prepWork 'full';$unicodeLife.manifestSha256=$unicodePin
    $unicodeLifePath=Join-Path $mockRoot 'mock-unicode-life.json';Write-ArtifactJson $unicodeLifePath $unicodeLife
    $identityPath=Join-Path $unicodeContext.Evidence 'fixture-identity.json'
    $identity=[pscustomobject]@{scope='DERIVED_UNICODE_PAYLOAD_NOT_RELEASE_PROVENANCE';originalTarget=$prepSource;originalManifestSha256=$adapterPin;
        derivedTarget=$derived.root;derivedManifestSha256=$unicodePin;derivedTreeSha256=$derived.manifest.treeSha256;coreMetadata='UNCHANGED';
        relativePath=$fixture.path;utf8Sha256=$fixture.sha256;utf8Size=$fixture.bytes.Length}
    $script:virtualJson[$identityPath]=ConvertTo-Json -InputObject $identity -Depth 32 -Compress
    $identityHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.UTF8Encoding]::new($false,$true).GetBytes($script:virtualJson[$identityPath]))).ToLowerInvariant()
    foreach ($entry in @{payloadArtifactDir=$unicodeLife.artifactDir;payloadManifestSha256=$unicodePin;payloadTreeSha256=$derived.manifest.treeSha256;
        payloadTargetRoot=$derived.root;payloadCommandFile=$derived.command;payloadCommandSha256=$derived.commandSha256;
        payloadLifecycleFile=$unicodeLifePath;payloadLifecycleSha256=(Get-FileHash -LiteralPath $unicodeLifePath).Hash.ToLowerInvariant();
        payloadFixtureIdentity=$identityPath;payloadFixtureIdentitySha256=$identityHash}.GetEnumerator()) {$unicodeResult.cell | Add-Member $entry.Key $entry.Value -Force}
    $script:virtualJson[$unicodeResult.cell.payloadObservation]=ConvertTo-Json -InputObject $unicodeResult -Depth 32 -Compress
    Assert-NativePayloadResult $unicodeResult $unicodeContext;Assert-PayloadMock $true 'REQUIRED_GOOD_UNICODE_RESULT_MOCK_ONLY'
    $identity.utf8Sha256='f'*64
    $script:virtualJson[$identityPath]=ConvertTo-Json -InputObject $identity -Depth 32 -Compress
    $unicodeResult.cell.payloadFixtureIdentitySha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.UTF8Encoding]::new($false,$true).GetBytes($script:virtualJson[$identityPath]))).ToLowerInvariant()
    $script:virtualJson[$unicodeResult.cell.payloadObservation]=ConvertTo-Json -InputObject $unicodeResult -Depth 32 -Compress
    Reject-PayloadMock {Assert-NativePayloadResult $unicodeResult $unicodeContext} 'PAYLOAD_RESULT_UNICODE_MANIFEST'
    $script:virtualJson.Clear()
    # Snapshot замечает изменение только timestamp при тех же bytes.
    $timestampSnapshot=Get-NativePayloadSnapshot @($fixtureInput)
    [IO.File]::SetLastWriteTimeUtc($fixtureInput,[IO.File]::GetLastWriteTimeUtc($fixtureInput).AddSeconds(5))
    Assert-PayloadMock ($timestampSnapshot -cne (Get-NativePayloadSnapshot @($fixtureInput))) 'ORIGINAL_TIMESTAMP_MUTATION_DETECTED'
    # Реальные helper/runner тела не запускаются: проверяется только isolation function import.
    $sentinel='caller-frozen';$global:nativeTarget=$sentinel
    $isolation=[PowerShell]::Create()
    try {
        [void]$isolation.AddScript({param($root)
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            # Префикс actual Invoke-NativeCell содержит source/target/artifact guard, без mkdir/native.
            $nativeAst=(Get-Command Invoke-NativeCell).ScriptBlock.Ast
            if ($nativeAst -is [Management.Automation.Language.FunctionDefinitionAst]) {$nativeAst=$nativeAst.Body}
            $prefix=@()
            foreach ($statement in $nativeAst.EndBlock.Statements) {
                if ($statement.Extent.Text -cmatch '^\$cellEvidence=') {break}
                $prefix+=@($statement.Extent.Text)
            }
            . ([scriptblock]::Create('function global:Invoke-PayloadRootProbe($Row,[string]$Source,$Life) {'+($prefix -join "`n")+'}'))
            $global:capturedRoots=[Collections.Generic.List[string]]::new()
            function global:Get-ValidatedPortablePaths($Protected,$Run,$Project,$Profile) {$global:capturedRoots.Add($Protected);return $null}
            Set-NativePayloadPrivateRoots 'C:\owned-original' $root
            Set-NativePayloadPrivateRoots 'C:\owned-derived' $root
            Invoke-PayloadRootProbe ([pscustomobject]@{path='unicode'}) 'C:\owned-base' ([pscustomobject]@{artifactDir='C:\owned-artifacts'})
            if ($global:capturedRoots.Count -ne 3 -or $global:capturedRoots[1] -cne 'C:\owned-derived') {throw ('MOCK_ACTUAL_ROOT_PROPAGATION: '+($global:capturedRoots -join '|'))}
            foreach ($name in 'Invoke-NativeCell','Invoke-ColdTool','New-LifecyclePinnedConfig','New-BootstrapBuilderArchive','Assert-ArtifactOutput') {
                [void](Get-Command $name -ErrorAction Stop)
            }
            return (Get-Command Invoke-NativeCell).Name
        }).AddArgument($PSScriptRoot)
        $isolated=$isolation.Invoke()
        Assert-PayloadMock (-not $isolation.HadErrors -and $isolated[0] -ceq 'Invoke-NativeCell' -and $global:nativeTarget -ceq $sentinel) 'REAL_RUNSPACE_IMPORT_ISOLATED'
    } finally {$isolation.Dispose();Remove-Variable nativeTarget -Scope Global}
    Write-Output ('Payload fixtures: '+$script:checks+' checks; MOCK_ONLY; nativeStatus=PENDING; CLI/native not executed.')
} finally {
    foreach ($directory in $script:ownedAdapterDirectories) {
        $absolute=[IO.Path]::GetFullPath($directory)
        if ([IO.Path]::GetDirectoryName($absolute) -cne [IO.Path]::GetTempPath().TrimEnd('\','/') -or
            [IO.Path]::GetFileName($absolute) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'ADAPTER_FIXTURE_CLEANUP_SCOPE'}
        Remove-Item -LiteralPath $absolute -Recurse -Force
    }
    $resolved=[IO.Path]::GetFullPath($script:mockRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -cne [IO.Path]::GetTempPath().TrimEnd('\','/') -or
        [IO.Path]::GetFileName($resolved) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'FIXTURE_CLEANUP_SCOPE'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
