# Только mock/static: ни JVM, ни EXE, ни GUI, ни реестр не запускаются.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Portable-Parity.ps1')
function Assert-ParityRejected([scriptblock]$Action, [string]$Expected = '') {
    $failed = $false
    try { & $Action } catch { if ($Expected -and -not $_.Exception.Message.Contains($Expected)) { throw }; $failed = $true }
    if (-not $failed) { throw 'Отрицательная portable parity fixture принята.' }
}
$nonce = '01234567-89ab-cdef-0123-456789abcdef'
$hash = 'a' * 64
$proof = [ordered]@{ schema='1'; nonce=$nonce; pathClass='ascii'; client='fx'; exeSHA256=$hash;
    scenario='s02-sample-table'; checkpoints='5'; crash='true'; restore='true'; registryClean='true'; processesClean='true' }
foreach ($tag in 'ascii','cyrillic','unicode') {
    foreach ($client in 'fx','swing','web') {
        $proof.pathClass = $tag; $proof.client = $client
        Assert-PortableParityProof $proof $nonce $tag $client $hash
        foreach ($key in @($proof.Keys)) {
            $value = $proof[$key]
            $proof[$key] = 'absent-or-false'
            Assert-ParityRejected { Assert-PortableParityProof $proof $nonce $tag $client $hash }
            $proof[$key] = $value
            $proof.Remove($key)
            Assert-ParityRejected { Assert-PortableParityProof $proof $nonce $tag $client $hash }
            $proof.Add($key, $value)
        }
    }
}
$proof.Add('smoke', 'true')
Assert-ParityRejected { Assert-PortableParityProof $proof $nonce 'unicode' 'web' $hash }
foreach ($file in 'Test-Portable.ps1','Portable-Parity.ps1','Portable-ParitySafety.ps1','Test-PortableParityFixtures.ps1') {
    $tokens = $null; $errors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file), [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw ('PowerShell syntax: ' + ($errors.Message -join '; ')) }
}
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Assert-ParityRejected { Get-PortableParityRuntime $project (Join-Path $project 'nonexistent-parity-fixture') } 'Parity classpath отсутствует'
$scriptText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Test-Portable.ps1') -Raw
if ($scriptText -notmatch 'if \(\$Parity\) \{ Invoke-PortableParity' -or $scriptText -notmatch 'Test-Copy \$copy \$entry.Id') { throw 'Independent parity/smoke checks lost.' }
$ast = [System.Management.Automation.Language.Parser]::ParseInput($scriptText, [ref]$tokens, [ref]$errors)
foreach ($name in 'Resolve-PortableSafetyPath','Test-PortablePathContains','Assert-PortableTreeHasNoLinks') {
    $function = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($function.Count -ne 1) { throw 'Real safety function missing.' }
    . ([scriptblock]::Create($function[0].Extent.Text))
}
. (Join-Path $PSScriptRoot 'Protect-GateText.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('cp-portable-parity-mock-' + [guid]::NewGuid().ToString())
$created = $false
try {
    if (Test-Path -LiteralPath $fixture) { throw 'Fixture already exists.' }
    New-Item -ItemType Directory -Path $fixture | Out-Null; $created = $true
    $source = Join-Path $fixture 'source'; $frozen = Join-Path $fixture 'frozen'
    New-Item -ItemType Directory -Path $source | Out-Null
    Set-Content -LiteralPath (Join-Path $source 'input.txt') -Value 'original'
    $hash = Get-PortableParityFingerprint $source
    Copy-Item -LiteralPath $source -Destination $frozen -Recurse
    $inputs = @{ Entries=@(@{ Source=$source; Frozen=$frozen; Hash=$hash }) }
    Assert-PortableParityInputs $inputs
    Set-Content -LiteralPath (Join-Path $source 'input.txt') -Value 'mutated during capture'
    Assert-ParityRejected { Assert-PortableParityInputs $inputs } 'Вход parity изменился во время capture.'

    # Настоящий freeze отклоняет мутацию во время копирования и изменение между копиями.
    $copyFixture = Join-Path $fixture 'frozen-copy'
    New-Item -ItemType Directory -Path (Join-Path $copyFixture 'runtime') -Force | Out-Null
    $captureRoot = Join-Path $fixture 'capture'; New-Item -ItemType Directory -Path $captureRoot | Out-Null
    $runtime = @{ Sources=@(@{ Name='Golden'; Source=$source; Hash=(Get-PortableParityFingerprint $source) }) }
    Assert-ParityRejected {
        New-PortableParityFrozen $captureRoot $copyFixture $runtime {
            param($SourcePath,$FrozenPath)
            Set-Content -LiteralPath (Join-Path $SourcePath 'input.txt') -Value 'mutation-during-copy'
        }
    } 'Вход parity изменился во время capture.'
    $nextCapture = Join-Path $fixture 'next-capture'; New-Item -ItemType Directory -Path $nextCapture | Out-Null
    Assert-ParityRejected { New-PortableParityFrozen $nextCapture $copyFixture $runtime } 'Parity input changed between copies.'

    # Module preflight использует инвентарь, не запускает заглушку Java.
    Set-Content -LiteralPath (Join-Path $copyFixture 'runtime/release') -Value 'MODULES="java.base ru.cashprediction.core"'
    Assert-ParityRejected { Assert-PortableParityModules $copyFixture } 'Packaged runtime lacks java.net.http'
    New-Item -ItemType Directory -Path (Join-Path $copyFixture 'runtime/bin') | Out-Null
    Set-Content -LiteralPath (Join-Path $copyFixture 'runtime/bin/java.exe') -Value 'inert mock data'
    Set-Content -LiteralPath (Join-Path $copyFixture 'runtime/release') -Value 'MODULES="java.base ru.cashprediction.core java.net.http"'
    Assert-PortableParityModules $copyFixture
    # Новый app-image: HTTP остаётся в runtime, обновляемое ядро находится в app.
    Set-Content -LiteralPath (Join-Path $copyFixture 'runtime/release') -Value 'MODULES="java.base java.net.http"'
    Assert-ParityRejected { Assert-PortableParityModules $copyFixture } 'Packaged core module missing.'
    $appFixture = Join-Path $copyFixture 'app'
    New-Item -ItemType Directory -Path $appFixture | Out-Null
    Assert-ParityRejected { Assert-PortableParityModules $copyFixture } 'Expected exactly one packaged core module.'
    $jarFixture = Join-Path $appFixture 'cashprediction-core-fixture.jar'
    $jarArchive = [IO.Compression.ZipFile]::Open($jarFixture, [IO.Compression.ZipArchiveMode]::Create)
    try { $null = $jarArchive.CreateEntry('not-a-module.class') } finally { $jarArchive.Dispose() }
    Assert-ParityRejected { Assert-PortableParityModules $copyFixture } 'Packaged core must be modular.'
    $jarArchive = [IO.Compression.ZipFile]::Open($jarFixture, [IO.Compression.ZipArchiveMode]::Update)
    try { $null = $jarArchive.CreateEntry('module-info.class') } finally { $jarArchive.Dispose() }
    Assert-PortableParityModules $copyFixture
    $duplicateJar = Join-Path $appFixture 'cashprediction-core-duplicate.jar'
    Copy-Item -LiteralPath $jarFixture -Destination $duplicateJar
    Assert-ParityRejected { Assert-PortableParityModules $copyFixture } 'Expected exactly one packaged core module.'
    Remove-Item -LiteralPath $duplicateJar
    # Детерминированная гонка: браузерный потомок исчезает после обнаружения, перед чтением.
    $volatileRoot = Join-Path $fixture 'volatile-tree'
    $volatileChild = Join-Path $volatileRoot 'browser-cache'
    New-Item -ItemType Directory -Path $volatileChild -Force | Out-Null
    function Get-ChildItem {
        [CmdletBinding()]param([string]$LiteralPath,[switch]$Force)
        if ($LiteralPath -ceq $volatileChild -and [IO.Directory]::Exists($volatileChild)) {
            [IO.Directory]::Delete($volatileChild)
        }
        Microsoft.PowerShell.Management\Get-ChildItem -LiteralPath $LiteralPath -Force:$Force -ErrorAction Stop
    }
    try { Assert-PortableTreeHasNoLinks $volatileRoot }
    finally { Remove-Item Function:\Get-ChildItem }
    if (Test-Path -LiteralPath $volatileChild) { throw 'Volatile-directory fixture did not execute.' }
    $missingRootRejected = $false
    try { Assert-PortableTreeHasNoLinks (Join-Path $fixture 'missing-root') }
    catch { $missingRootRejected = $true }
    if (-not $missingRootRejected) { throw 'Missing root must fail closed.' }
    Copy-Item -LiteralPath (Join-Path $frozen 'input.txt') -Destination (Join-Path $source 'input.txt') -Force
    Set-Content -LiteralPath (Join-Path $frozen 'input.txt') -Value 'mutated frozen input'
    Assert-ParityRejected { Assert-PortableParityInputs $inputs } 'Вход parity изменился во время capture.'

    # Экспорт до report и после report сохраняет очищенные raw/log, но исключает профиль и owner URL.
    $run = Join-Path $fixture 'run'; $sample = Join-Path $run 'web/sample/s02-sample-table'
    New-Item -ItemType Directory -Path $sample -Force | Out-Null
    $secret = 'fixture-token-do-not-export'
    Set-Content -LiteralPath (Join-Path $sample 'table.raw.json') -Value ('{"token":"' + $secret + '"}')
    Set-Content -LiteralPath (Join-Path (Split-Path $sample) 'client.stderr.log') -Value ('PARITY_URL http://127.0.0.1:8765/?t=' + $secret)
    Set-Content -LiteralPath (Join-Path (Split-Path $sample) 'selftest.log') -Value 'SELFTEST failed fixture'
    Set-Content -LiteralPath (Join-Path (Split-Path $sample) 'browser-owner.json') -Value $secret
    $profile = Join-Path (Split-Path $sample) 'browser-profile'
    New-Item -ItemType Directory -Path $profile | Out-Null
    Set-Content -LiteralPath (Join-Path $profile 'private.json') -Value $secret
    # Наблюдения и owner-файлы собираются без входа в изменяемое приватное дерево браузера.
    function Get-ChildItem {
        [CmdletBinding()]param([string]$LiteralPath,[switch]$Force)
        if ($LiteralPath -match '(^|[\\/])browser-profile([\\/]|$)') { throw 'Fixture forbids browser-profile traversal.' }
        Microsoft.PowerShell.Management\Get-ChildItem -LiteralPath $LiteralPath -Force:$Force -ErrorAction Stop
    }
    try {
        $observations = @(Get-PortableParityObservationFiles $run)
        if (-not @($observations | Where-Object Name -eq 'browser-owner.json').Count) { throw 'Owner observation missing.' }
        if (@($observations | Where-Object Name -eq 'private.json').Count) { throw 'Private browser tree traversed.' }
    } finally { Remove-Item Function:\Get-ChildItem }
    foreach ($mode in 'failure','success') {
        if ($mode -eq 'success') {
            New-Item -ItemType Directory -Path (Join-Path $run 'comparison') | Out-Null
            Set-Content -LiteralPath (Join-Path $run 'comparison/report.html') -Value ('<a href="/?token=' + $secret + '">fixture</a>')
        }
        $destination = Join-Path $fixture ('evidence-' + $mode)
        Export-PortableParityEvidence $run $destination
        $files = @(Get-ChildItem -LiteralPath $destination -Recurse -File)
        foreach ($name in 'table.raw.json','client.stderr.log','selftest.log') {
            if (-not @($files | Where-Object Name -eq $name).Count) { throw 'Early-failure evidence missing.' }
        }
        if ($mode -eq 'success' -and -not @($files | Where-Object Name -eq 'report.html').Count) { throw 'Report not retained.' }
        if (@($files | Where-Object { $_.Name -in 'private.json','browser-owner.json' }).Count) { throw 'Private browser evidence exported.' }
        foreach ($file in $files) { if ((Get-Content -LiteralPath $file.FullName -Raw).Contains($secret)) { throw 'Evidence token leak.' } }
    }

    # Mock CIM + retained handles: reparent после смерти supervisor, точный Unicode profile, чужой prefix жив.
    $WorkDir = $fixture
    $copy = Join-Path $fixture 'copy'
    New-Item -ItemType Directory -Path $copy | Out-Null
    $ownedCopies = [Collections.Generic.List[string]]::new(); $ownedCopies.Add($copy)
    $parityRuns = @{}; $parityRuns[$copy] = @{ Root=$run }
    $browserPath = Join-Path $fixture 'external/browser.exe'
    $profile = Join-Path (Split-Path $sample) 'browser-profile'
    $intent = Join-Path (Split-Path $sample) 'browser-owner.json'
    @{ profile=$profile; executable=$browserPath } | ConvertTo-Json | Set-Content -LiteralPath $intent -Encoding UTF8
    $born = (Get-Item -LiteralPath $intent).LastWriteTimeUtc
    $script:mockProcesses = @(
        [pscustomobject]@{ ProcessId=1101; ParentProcessId=1000; CreationDate=$born; ExecutablePath=$browserPath; CommandLine=('browser "--user-data-dir=' + $profile + '"') },
        [pscustomobject]@{ ProcessId=1102; ParentProcessId=1101; CreationDate=$born; ExecutablePath=$browserPath; CommandLine='browser child' },
        [pscustomobject]@{ ProcessId=1103; ParentProcessId=1000; CreationDate=$born; ExecutablePath=$browserPath; CommandLine=('browser "--user-data-dir=' + $profile + '-other"') })
    $script:mockHandles = @{}
    foreach ($process in $mockProcesses) {
        $handle = [pscustomobject]@{ Id=$process.ProcessId; Handle=[intptr]1; HasExited=$false; StartTime=$born; MainModule=[pscustomobject]@{ FileName=$browserPath }; Disposed=$false }
        $handle | Add-Member ScriptMethod Kill { param($Tree) $this.HasExited = $true }
        $handle | Add-Member ScriptMethod WaitForExit { param($Milliseconds) return $this.HasExited }
        $handle | Add-Member ScriptMethod Dispose { $this.Disposed = $true }
        $mockHandles[$process.ProcessId] = $handle
    }
    function Get-CimInstance { param($ClassName) return $script:mockProcesses }
    function Open-PortableProcess { param($Id) return $script:mockHandles[$Id] }
    $owned = @{}
    Update-PortableBrowserOwnership $copy $owned
    if ($owned.Count -ne 2) { throw 'Exact profile/descendant ownership not captured.' }
    # Супервизор уже отсутствует; CIM перестал показывать браузер после kill, но удержанные handles остались.
    $script:mockProcesses = @($mockProcesses | Where-Object ProcessId -eq 1103)
    Stop-PortableBrowsers $copy $owned
    if (-not $mockHandles[1101].HasExited -or -not $mockHandles[1102].HasExited -or $mockHandles[1103].HasExited) { throw 'Mock forced-timeout ownership failure.' }
    if ($owned.Count -ne 0 -or -not $mockHandles[1101].Disposed) { throw 'Retained browser handles not released.' }
} finally {
    if ($created) {
        $safe = Resolve-PortableSafetyPath $fixture
        if ($safe -cne $fixture -or [IO.Path]::GetFileName($safe) -cnotmatch '^cp-portable-parity-mock-[0-9a-f-]{36}$' -or
            [IO.Path]::GetDirectoryName($safe) -cne (Resolve-PortableSafetyPath ([IO.Path]::GetTempPath()))) { throw 'Foreign fixture cleanup path.' }
        Assert-PortableTreeHasNoLinks $safe
        Remove-Item -LiteralPath $safe -Recurse -Force
    }
}
Write-Host 'Portable parity fixtures: 9 proof combinations; mutation, redacted evidence and mock orphan ownership PASS; no JVM/EXE/GUI/registry.'
