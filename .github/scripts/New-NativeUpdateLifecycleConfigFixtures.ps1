<#
.SYNOPSIS
Контрактные fixtures builder: реальные временные файлы, без процессов/сети/реестра.
.DESCRIPTION
Payload здесь явно mock текст, не native ZIP/дельты. Server.class только виртуальный
Test-Path witness для проверки публикации; class/exe/helper bytes не создаются.
Отдельный отрицательный control вызывает настоящий guard отсутствующего класса.
Mock LifecycleFile не является пригодным входом native runner или native PASS.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'LIFECYCLE_FIXTURE_WINDOWS_POWERSHELL7'}
$builder=Join-Path $PSScriptRoot 'New-NativeUpdateLifecycleConfig.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($builder,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw ('LIFECYCLE_FIXTURE_PARSE '+($errors.Message -join '; '))}
foreach ($definition in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Import-LifecycleConfigContracts $PSScriptRoot
$script:checks=0

# Проверка точного кода исключает успех отрицательного теста по посторонней причине.
function Assert-ConfigReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "LIFECYCLE_FIXTURE_REJECT expected=$Code actual=$caught"}
    $script:checks++
}

# Положительные controls проверяют байты и строгие типы, не native lifecycle.
function Assert-ConfigFixture([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw "LIFECYCLE_FIXTURE_ASSERT $Code"};$script:checks++
}

# Только UTF-8 mock данные в точно принадлежащем fixture UUID.
function Write-ConfigMock([string]$Path,[string]$Text) {
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}

# Изменение манифеста получает собственный SHA, чтобы проверять нужный guard после pin.
function Save-ConfigMockManifest($Manifest,[string]$Directory) {
    Write-ConfigMock (Join-Path $Directory 'update.json') (ConvertTo-Json -InputObject $Manifest -Depth 64 -Compress)
    return (Get-FileHash -LiteralPath (Join-Path $Directory 'update.json')).Hash.ToLowerInvariant()
}

# Ни импорт, ни отрицательные guards не имеют права запускать внешние операции.
function Start-Process {throw 'LIFECYCLE_FIXTURE_FORBIDDEN_PROCESS'}
function Invoke-ColdTool {throw 'LIFECYCLE_FIXTURE_FORBIDDEN_JAVA'}
function Start-NativeOwned {throw 'LIFECYCLE_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-WebRequest {throw 'LIFECYCLE_FIXTURE_FORBIDDEN_NETWORK'}
function Invoke-RestMethod {throw 'LIFECYCLE_FIXTURE_FORBIDDEN_NETWORK'}

$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$owned=Join-Path $temp ([guid]::NewGuid().ToString())
$outputs=[Collections.Generic.List[string]]::new()
$null=New-Item -ItemType Directory -Path $owned
try {
    $artifacts=Join-Path $owned 'artifacts';$harness=Join-Path $owned 'harness'
    $null=New-Item -ItemType Directory -Path $artifacts
    $null=New-Item -ItemType Directory -Path $harness
    # Настоящий существующий текстовый файл для точной инвентаризации classpath.
    [IO.File]::Copy($builder,(Join-Path $harness 'contract-source.ps1'))
    $emptySha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]@())).ToLowerInvariant()
    $paths=@('CashPrediction-Swing.exe','CashPrediction-Web.exe','CashPrediction.exe','app/.jpackage.xml',
        'app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/CashPrediction.cfg','app/core.jar',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules')
    # Только metadata обязательного дерева: никакие exe/JAR/runtime bytes не генерируются.
    $files=@($paths | ForEach-Object {[pscustomobject]@{path=$_;sizeBytes=0L;sha256=$emptySha;readOnly=$false}})
    $manifest=[pscustomobject][ordered]@{schemaVersion=2;releaseNumber=3;commitSha=('c'*40);version='3';publishedAtUtc='2026-10-04T00:00:00Z';
        assetName='CashPrediction-portable.zip';sizeBytes=0L;sha256='';treeSha256=(Get-ColdTreeHash $files);files=$files;deltaPatches=@()}
    foreach ($base in 1,2) {
        $manifest.deltaPatches+=@([pscustomobject][ordered]@{algorithm='cashprediction-tree-delta';algorithmVersion=1;baseReleaseNumber=$base;
            baseCommitSha=([string]$base)*40;baseTreeSha256=([string]$base)*64;assetName=('CashPrediction.from-'+$base+'.cpdelta');sizeBytes=0L;sha256=''})
    }
    foreach ($asset in @($manifest)+@($manifest.deltaPatches)) {
        $path=Join-Path $artifacts $asset.assetName;Write-ConfigMock $path ('MOCK-CONTRACT-NOT-NATIVE '+$asset.assetName)
        $asset.sizeBytes=(Get-Item -LiteralPath $path).Length;$asset.sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    }
    $pin=Save-ConfigMockManifest $manifest $artifacts
    $project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    [void](Read-LifecycleManifest $artifacts $pin);$script:checks++
    Assert-ConfigReject {New-LifecyclePinnedConfig $artifacts @($harness) $pin} 'NATIVE_SERVER_CLASS_MISSING'
    # Единственный mock: наличие server class. Остальные filesystem/hash guards настоящие.
    $script:virtualClass=Join-Path $harness 'ru/cashprediction/parity/update/NativeUpdateServer.class'
    function Test-Path {
        [CmdletBinding()]param([string]$LiteralPath,[string]$PathType)
        if ($LiteralPath -ceq $script:virtualClass) {return $true}
        $parameters=@{LiteralPath=$LiteralPath};if ($PathType) {$parameters.PathType=$PathType}
        return (Microsoft.PowerShell.Management\Test-Path @parameters)
    }
    $config=New-LifecyclePinnedConfig $artifacts @($harness) $pin
    Assert-ConfigFixture ($config.harnessFiles.Count -eq 1 -and $config.harnessFiles[0].path -ceq (Join-Path $harness 'contract-source.ps1')) 'EXACT_HARNESS'
    $outRoot=Join-Path $temp ([guid]::NewGuid().ToString());$outputs.Add($outRoot)
    $destination=Assert-LifecycleOutput (Join-Path $outRoot 'lifecycle.json') $project @($artifacts,$harness)
    $receipt=Publish-LifecycleConfig $config $destination
    Assert-ConfigFixture ($receipt.path -ceq $destination.path -and $receipt.sha256 -ceq (Get-FileHash -LiteralPath $receipt.path).Hash.ToLowerInvariant()) 'OUTPUT_SHA'
    $written=Read-ColdPinnedJson $receipt.path $receipt.sha256;Assert-NativeLifecycleConfig $written;$script:checks++
    Assert-ConfigFixture ([IO.File]::ReadAllBytes($receipt.path)[0] -eq [byte][char]'{') 'UTF8_NO_BOM'
    $original=[IO.File]::ReadAllText($receipt.path)
    Assert-ConfigReject {Assert-LifecycleOutput $receipt.path $project @($artifacts,$harness)} 'LIFECYCLE_OUTPUT_EXISTS'
    Assert-ConfigFixture ([IO.File]::ReadAllText($receipt.path) -ceq $original) 'NO_OVERWRITE'
    # Даже гонка после раннего scope guard не разрешает replace при публикации.
    $raced=[pscustomobject]@{path=$receipt.path;parent=$outRoot;newTemp=$false}
    $caught=$null;try {Publish-LifecycleConfig $config $raced | Out-Null} catch {$caught=$_.Exception}
    Assert-ConfigFixture ($null -ne $caught -and [IO.File]::ReadAllText($receipt.path) -ceq $original) 'ATOMIC_NO_REPLACE'
    Assert-ConfigReject {Assert-LifecycleOutput (Join-Path $outRoot 'other.json') $project @($artifacts,$harness)} 'LIFECYCLE_TEMP_EXISTS'
    Assert-ConfigReject {Assert-LifecycleOutput (Join-Path $harness 'bad.json') $project @($artifacts,$harness)} 'LIFECYCLE_OUTPUT_OVERLAP'
    Assert-ConfigReject {Assert-LifecycleOutput (Join-Path $temp 'broad.json') $project @($artifacts,$harness)} 'LIFECYCLE_OUTPUT_SCOPE'
    Assert-ConfigReject {Assert-LifecycleOutput 'relative.json' $project @($artifacts,$harness)} 'NATIVE_ABSOLUTE_PATH'
    Assert-ConfigReject {New-LifecyclePinnedConfig $artifacts @($harness,$harness.ToUpperInvariant()) $pin} 'LIFECYCLE_CLASSPATH_DUPLICATE'
    Assert-ConfigReject {New-LifecyclePinnedConfig $artifacts @() $pin} 'LIFECYCLE_CLASSPATH_COUNT'
    Assert-ConfigReject {New-LifecyclePinnedConfig $artifacts @($harness,$harness,$harness,$harness,$harness) $pin} 'LIFECYCLE_CLASSPATH_COUNT'
    Assert-ConfigReject {Read-LifecycleManifest $artifacts ('0'*64)} 'LIFECYCLE_MANIFEST_PIN'
    $bad=ConvertFrom-ColdReceiptJson (ConvertTo-Json $manifest -Depth 64);$bad.schemaVersion=1
    $badPin=Save-ConfigMockManifest $bad $artifacts
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_MANIFEST_SCHEMA'
    $bad=ConvertFrom-ColdReceiptJson (ConvertTo-Json $manifest -Depth 64);$bad.deltaPatches=@($bad.deltaPatches[0])
    $badPin=Save-ConfigMockManifest $bad $artifacts
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_MANIFEST_SCHEMA'
    $bad=ConvertFrom-ColdReceiptJson (ConvertTo-Json $manifest -Depth 64);$bad.deltaPatches[1].baseCommitSha=$bad.deltaPatches[0].baseCommitSha
    $badPin=Save-ConfigMockManifest $bad $artifacts
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_DIRECT_DELTAS'
    $bad=ConvertFrom-ColdReceiptJson (ConvertTo-Json $manifest -Depth 64);$bad.deltaPatches[1].assetName='../escape.cpdelta'
    $badPin=Save-ConfigMockManifest $bad $artifacts
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_DIRECT_DELTAS'
    $bad=ConvertFrom-ColdReceiptJson (ConvertTo-Json $manifest -Depth 64);$bad.treeSha256='0'*64
    $badPin=Save-ConfigMockManifest $bad $artifacts
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'COLD_INVENTORY'
    $pin=Save-ConfigMockManifest $manifest $artifacts
    Write-ConfigMock (Join-Path $artifacts 'extra.txt') 'extra'
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $pin} 'LIFECYCLE_ARTIFACT_SET'
    [IO.File]::Delete((Join-Path $artifacts 'extra.txt'))
    $payload=Join-Path $artifacts $manifest.deltaPatches[0].assetName;$saved=[IO.File]::ReadAllBytes($payload)
    Write-ConfigMock $payload 'CORRUPT'
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $pin} 'LIFECYCLE_PAYLOAD_PIN'
    [IO.File]::WriteAllBytes($payload,$saved)
    $manifestPath=Join-Path $artifacts 'update.json';$text=[IO.File]::ReadAllText($manifestPath)
    Write-ConfigMock $manifestPath ($text.Replace('"schemaVersion":2','"schemaVersion":2,"schemaVersion":2'))
    $badPin=(Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant()
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_JSON_DUPLICATE'
    Write-ConfigMock $manifestPath ($text.Replace('"schemaVersion":2','"schemaVersion":2e0'))
    $badPin=(Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant()
    Assert-ConfigReject {Read-LifecycleManifest $artifacts $badPin} 'LIFECYCLE_JSON_INTEGER'
    Write-ConfigMock $manifestPath $text
    # После pin дерево изменилось: publication обязана повторно проверить точный набор.
    $config=New-LifecyclePinnedConfig $artifacts @($harness) $pin
    $harnessFile=Join-Path $harness 'contract-source.ps1';$harnessBytes=[IO.File]::ReadAllBytes($harnessFile)
    Write-ConfigMock $harnessFile 'CHANGED AFTER PIN'
    Assert-ConfigReject {Assert-NativeLifecycleConfig $config} 'NATIVE_HARNESS_PIN'
    [IO.File]::WriteAllBytes($harnessFile,$harnessBytes)
    Write-ConfigMock (Join-Path $harness 'late.txt') 'late'
    Assert-ConfigReject {Assert-NativeLifecycleConfig $config} 'NATIVE_HARNESS_SET'
    [IO.File]::Delete((Join-Path $harness 'late.txt'))
    $link=Join-Path $owned 'junction';$null=New-Item -ItemType Junction -Path $link -Target $harness
    $caught=$null;try {New-LifecyclePinnedConfig $artifacts @($link) $pin | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-ConfigFixture ($caught -ceq 'Ссылочный путь проверки запрещён.') 'JUNCTION_REJECTED'
    # Junction удаляется без рекурсивного обхода целевого дерева.
    [IO.Directory]::Delete($link)
    Assert-ConfigFixture (Test-Path -LiteralPath (Join-Path $harness 'contract-source.ps1')) 'LINK_TARGET_RETAINED'
    # AST не должен содержать реальных вызовов процессов/build или сетевых cmdlets.
    $forbidden=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and
        $n.GetCommandName() -in @('Start-Process','java','javac','mvn','Invoke-WebRequest','Invoke-RestMethod')},$true))
    Assert-ConfigFixture ($forbidden.Count -eq 0) 'NO_PROCESS_NETWORK_BUILD'
    Write-Output "Lifecycle config mock contracts prepared: PASS ($script:checks checks; no Java/native/GUI; no native PASS)."
} finally {
    # Удаляются только собственные UUID, проверенные по абсолютному Temp и без ссылок.
    foreach ($directory in @($owned)+@($outputs.ToArray())) {
        $resolved=[IO.Path]::GetFullPath($directory)
        if (-not [IO.Path]::GetDirectoryName($resolved).Equals($temp,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'LIFECYCLE_FIXTURE_CLEANUP_SCOPE'}
        if (Microsoft.PowerShell.Management\Test-Path -LiteralPath $resolved) {
            $junction=Join-Path $resolved 'junction'
            if (Microsoft.PowerShell.Management\Test-Path -LiteralPath $junction) {
                $item=Get-Item -LiteralPath $junction -Force
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {[IO.Directory]::Delete($junction)}
            }
            Assert-PortableTreeHasNoLinks $resolved
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
