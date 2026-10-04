# S5: только заранее собранный Java-стенд; никакой сборки или подмены EXE прямой Java.
. (Join-Path $PSScriptRoot 'Portable-ParitySafety.ps1')
function Get-PortableParityRuntime([string]$Project, [string]$ClassPath) {
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw '-Parity requires PowerShell 7 for retained-handle Kill(tree).' }
    $classes = Join-Path $Project 'ui-parity/target/test-classes'
    if ([string]::IsNullOrWhiteSpace($ClassPath)) {
        $ClassPath = $classes
    }
    foreach ($entry in @($ClassPath.Split([IO.Path]::PathSeparator))) {
        if ([string]::IsNullOrWhiteSpace($entry) -or -not (Test-Path -LiteralPath $entry)) { throw 'Parity classpath отсутствует; MAIN должен сначала собрать ui-parity.' }
    }
    foreach ($name in 'PortableParityHarness','PortableExeProcess','PortableRestoreChecks') {
        $compiled = @($ClassPath.Split([IO.Path]::PathSeparator) | ForEach-Object { Join-Path $_ ('ru/cashprediction/parity/portable/' + $name + '.class') } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
        $source = Join-Path $Project ('ui-parity/src/test/java/ru/cashprediction/parity/portable/' + $name + '.java')
        if ($compiled.Count -ne 1 -or (Get-Item -LiteralPath $compiled[0]).LastWriteTimeUtc -lt (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
            throw 'PortableParityHarness отсутствует или устарел; MAIN должен собрать стенд, smoke не заменяет -Parity.'
        }
    }
    $sources = @(); $index = 0
    foreach ($entry in @($ClassPath.Split([IO.Path]::PathSeparator))) {
        $source = Resolve-PortableSafetyPath $entry
        if (Test-Path -LiteralPath (Join-Path $source 'ru/cashprediction/core/ui/dump/UiDump.class')) { throw 'Core must come from packaged runtime, not classpath.' }
        $sources += @{ Name=('Harness' + $index); Source=$source; Hash=(Get-PortableParityFingerprint $source) }; $index++
    }
    foreach ($entry in @(
        @{ Name='Golden'; Source=(Join-Path $Project 'core/src/test/resources/ui-golden') },
        @{ Name='Sources'; Source=(Join-Path $Project 'ui-parity/src/test/java/ru/cashprediction/parity/portable') },
        @{ Name='Script'; Source=(Join-Path $PSScriptRoot 'Test-Portable.ps1') },
        @{ Name='Helper'; Source=(Join-Path $PSScriptRoot 'Portable-Parity.ps1') },
        @{ Name='Safety'; Source=(Join-Path $PSScriptRoot 'Portable-ParitySafety.ps1') })) {
        $entry.Hash = Get-PortableParityFingerprint $entry.Source; $sources += $entry
    }
    return @{ ClassPath=$ClassPath; Project=$Project; Sources=$sources }
}

# Статический preflight упакованного runtime; отсутствие HTTP нельзя скрыть внешним JDK.
function Assert-PortableParityModules([string]$Portable) {
    $release = Get-Content -LiteralPath (Join-Path $Portable 'runtime/release') -Raw -Encoding UTF8
    if ($release -notmatch '(?m)^MODULES="([^"]+)"') { throw 'Packaged runtime module inventory missing.' }
    $modules = @($Matches[1].Split(' '))
    foreach ($name in 'java.net.http') {
        if ($modules -cnotcontains $name) { throw ('Packaged runtime lacks ' + $name + '; MAIN must rebuild dist with this module.') }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Portable 'runtime/bin/java.exe') -PathType Leaf)) { throw 'Bundled Java missing.' }
    # Обновляемый app-image хранит ядро в app, а не в неизменяемом jlink runtime.
    # Именованный модуль дополнительно проверяет сам Java-стенд при запуске.
    if ($modules -cnotcontains 'ru.cashprediction.core') {
        $app = Join-Path $Portable 'app'
        if (-not (Test-Path -LiteralPath $app -PathType Container)) { throw 'Packaged core module missing.' }
        Assert-PortableTreeHasNoLinks $app
        $coreJars = @(Get-ChildItem -LiteralPath $app -File -Filter 'cashprediction-core-*.jar')
        if ($coreJars.Count -ne 1) { throw 'Expected exactly one packaged core module.' }
        $archive = [IO.Compression.ZipFile]::OpenRead($coreJars[0].FullName)
        try {
            if ($null -eq $archive.GetEntry('module-info.class')) { throw 'Packaged core must be modular.' }
        } finally { $archive.Dispose() }
    }
}

# Frozen inputs адаптированы из draft: заранее собранный harness и golden, без компиляции.
function New-PortableParityFrozen([string]$Root, [string]$Copy, $Runtime, [scriptblock]$CaptureObserver = {}) {
    $base = Join-Path $Root 'frozen'
    New-Item -ItemType Directory -Path $base | Out-Null
    $entries = @(); $classPath = @()
    foreach ($entry in $Runtime.Sources) {
        if ((Get-PortableParityFingerprint $entry.Source) -cne $entry.Hash) { throw 'Parity input changed between copies.' }
        $target = Join-Path $base $entry.Name
        Copy-Item -LiteralPath $entry.Source -Destination $target -Recurse
        $null = & $CaptureObserver $entry.Source $target
        $entries += @{ Source=$entry.Source; Frozen=$target; Hash=$entry.Hash }
        if ($entry.Name -like 'Harness*') { $classPath += $target }
    }
    $runtimePath = Join-Path $Copy 'runtime'
    $entries += @{ Source=$runtimePath; Frozen=$runtimePath; Hash=(Get-PortableParityFingerprint $runtimePath) }
    $appPath = Join-Path $Copy 'app'
    if (Test-Path -LiteralPath $appPath -PathType Container) {
        $entries += @{ Source=$appPath; Frozen=$appPath; Hash=(Get-PortableParityFingerprint $appPath) }
    }
    $inputs = @{ Entries=$entries; Base=$base; ClassPath=($classPath -join [IO.Path]::PathSeparator) }
    Assert-PortableParityInputs $inputs
    $entries | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Root 'fingerprints.json') -Encoding UTF8
    return $inputs
}

# Дампы, отчёты и ошибки сохраняются даже до создания report; профили и бинарные файлы не экспортируются.
function Export-PortableParityEvidence([string]$Root, [string]$Destination) {
    $base = Resolve-PortableSafetyPath $Root
    if (-not (Test-Path -LiteralPath $base -PathType Container)) { return }
    $safe = Resolve-PortableSafetyPath $Destination
    foreach ($file in @(Get-PortableParityObservationFiles $base | Where-Object {
        $relative = $_.FullName.Substring($base.Length + 1).Replace('\','/')
        $relative -notmatch '(^|/)(frozen|browser-profile)/' -and $_.Name -ne 'browser-owner.json' -and
        ($_.Extension -in '.json','.html','.log','.proof' -or $_.Name -eq 'selftest.log')
    })) {
        $target = Join-Path $safe $file.FullName.Substring($base.Length + 1)
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Protect-GateText (Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8) | Set-Content -LiteralPath $target -Encoding UTF8
    }
}

# Чистая проверка свежего доказательства: отсутствующий, частичный или чужой результат является ошибкой.
function Assert-PortableParityProof($Proof, [string]$Nonce, [string]$Tag, [string]$Client, [string]$ExeHash) {
    $required = @('schema','nonce','pathClass','client','exeSHA256','scenario','checkpoints','crash','restore','registryClean','processesClean')
    if ($Proof.Count -ne $required.Count) { throw 'Неполная схема portable parity proof.' }
    foreach ($key in $required) { if (-not $Proof.Contains($key)) { throw 'Отсутствует поле portable parity proof.' } }
    if ($Proof.schema -cne '1' -or $Proof.nonce -cne $Nonce -or $Proof.pathClass -cne $Tag -or
        $Proof.client -cne $Client -or $Proof.exeSHA256 -cne $ExeHash.ToLowerInvariant() -or
        $Proof.scenario -cne 's02-sample-table' -or $Proof.checkpoints -cne '5' -or
        $Proof.crash -cne 'true' -or $Proof.restore -cne 'true' -or
        $Proof.registryClean -cne 'true' -or $Proof.processesClean -cne 'true') { throw 'Не подтверждён полный portable parity запуск.' }
}

# Все три EXE данной копии; узлы заранее принадлежат внешнему cleanup.
function Invoke-PortableParity([string]$Copy, [string]$Tag, $Runtime) {
    if ($Tag -cnotin @('ascii','cyrillic','unicode')) { throw 'Неизвестный класс portable-пути.' }
    if (-not $ownedCopies.Contains($Copy) -or -not (Test-PortablePathContains $WorkDir $Copy)) { throw 'Foreign portable parity copy.' }
    $nonce = [guid]::NewGuid().ToString()
    $root = Join-Path $WorkDir ('parity-' + $Tag + '-' + $nonce)
    $logs = Join-Path $WorkDir 'logs'
    New-Item -ItemType Directory -Path $root | Out-Null
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $parityRuns[$Copy] = @{ Root=$root; Owners=@{}; Evidence=(Join-Path $evidenceDirectory ($Tag + '-parity')) }
    $inputs = New-PortableParityFrozen $root $Copy $Runtime
    $nodes = @(); for ($i = 0; $i -lt 6; $i++) { $nodes += New-PortableRegistryNode }
    $arguments = @('--module-path', ('"' + (Join-Path $Copy 'app') + '"'),
        '--add-modules', 'ru.cashprediction.core,java.net.http', '-XX:-UsePerfData', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-cp', ('"' + $inputs.ClassPath + '"'), 'ru.cashprediction.parity.portable.PortableParityHarness',
        ('"' + $Copy + '"'), ('"' + $root + '"'), ('"' + $inputs.Base + '"'), $Tag, $nonce,
        [string]$StartTimeoutSeconds) + $nodes
    $base = Join-Path $logs ($Tag + '-CashPrediction.parity')
    $saved = @{}
    foreach ($name in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $runner = $null
    try {
        Assert-PortableParityInputs $inputs
        $runner = Start-Process -FilePath (Join-Path $Copy 'runtime/bin/java.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput ($base + '.out.txt') -RedirectStandardError ($base + '.err.txt')
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(12 * $StartTimeoutSeconds + 180)
        $nextFingerprint = [DateTimeOffset]::UtcNow
        while (-not $runner.WaitForExit(500)) {
            Update-PortableBrowserOwnership $Copy $parityRuns[$Copy].Owners
            if ([DateTimeOffset]::UtcNow -ge $nextFingerprint) {
                Assert-PortableParityInputs $inputs
                $nextFingerprint = [DateTimeOffset]::UtcNow.AddSeconds(2)
            }
            if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Portable parity harness timeout.' }
        }
        if ($runner.ExitCode -ne 0) { throw 'Portable parity harness failed; see redacted evidence.' }
        Assert-PortableParityInputs $inputs
        foreach ($client in @(
            @{ Id='fx'; Exe='CashPrediction.exe' },
            @{ Id='swing'; Exe='CashPrediction-Swing.exe' },
            @{ Id='web'; Exe='CashPrediction-Web.exe' })) {
            $file = Join-Path $root ($client.Id + '.proof')
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Нет portable parity proof для каждого EXE.' }
            $proof = [ordered]@{}
            foreach ($line in @(Get-Content -LiteralPath $file -Encoding UTF8)) {
                $parts = $line.Split('=', 2)
                if ($parts.Count -ne 2 -or $proof.Contains($parts[0])) { throw 'Повреждён portable parity proof.' }
                $proof.Add($parts[0], $parts[1])
            }
            Assert-PortableParityProof $proof $nonce $Tag $client.Id (Get-FileHash -LiteralPath (Join-Path $Copy $client.Exe) -Algorithm SHA256).Hash
            Write-PortableDiagnostic ('S5 portable parity verified: ' + $Tag + '/' + $client.Id + '; ' + ((Get-Content -LiteralPath $file) -join '; '))
        }
    } finally {
        $errors = [Collections.Generic.List[string]]::new()
        try { Update-PortableBrowserOwnership $Copy $parityRuns[$Copy].Owners } catch { $errors.Add($_.Exception.Message) }
        if ($null -ne $runner) {
            try {
                if (-not $runner.HasExited) { $runner.Kill(); if (-not $runner.WaitForExit(20000)) { throw 'Parity runner survived cleanup.' } }
            } catch { $errors.Add($_.Exception.Message) }
            finally { $runner.Dispose() }
        }
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        try { Stop-CopyProcesses $Copy } catch { $errors.Add($_.Exception.Message) }
        try { Stop-PortableBrowsers $Copy $parityRuns[$Copy].Owners } catch { $errors.Add($_.Exception.Message) }
        try { Export-PortableParityEvidence $root $parityRuns[$Copy].Evidence } catch { $errors.Add($_.Exception.Message) }
        if ($errors.Count) { throw ($errors -join '; ') }
    }
}
