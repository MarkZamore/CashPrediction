<#
.SYNOPSIS
Независимые отрицательные fixtures cold-native runner и короткие процессы cmd, без JVM/GUI приложения.
.DESCRIPTION
Импортирует только определения функций через AST. Временные файлы - данные mock.
PASS относится к mock-отказам и привязке реальных Process, не к bootstrap/native матрице.
#>
[CmdletBinding()]
param(
    [string]$SettingsJava,
    [string]$SettingsCoreJar,
    [switch]$SettingsOnly,
    [switch]$ObserverHandoffOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'PowerShell 7 required.'}
$source=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'Bootstrap runner parse failed.'}
foreach ($function in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
    # ColdCheckpoint принадлежит строковой фикстуре helper и не является AST definition runner.
    $definition=$function.Extent.Text
    if ($function.Name -ceq 'Get-ColdSessionWords') {
        # AST scriptblock не имеет собственного файла: привязываем только resource anchor.
        $definition=$definition.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
    }
    . ([scriptblock]::Create($definition))
}
Import-ColdPortableSafety (Join-Path $PSScriptRoot 'Test-Portable.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('cp-bootstrap-fixtures-'+[guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $fixture
$count=0
$redirectComparisons=0
$processReceiptChecks=0
$helperDiagnosticsChecks=0
$cleanupRaceChecks=0
$observerAcquisitionChecks=0
$cleanupPidTypeChecks=0
$sessionTimeChecks=0
$unicodeSettingsChecks=0
$observerHandoffChecks=0
$observerListenerChecks=0

# Каждый отрицательный тест сверяет код причины, исключая успех из-за посторонней ошибки.
function Assert-ColdMockReject([scriptblock]$Action,[string]$Code) {
    $caught=$null
    try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "Mock rejection mismatch: expected $Code; actual $caught"}
    $script:count++
}

# Новый объект не разделяет вложенные mutable записи с основной фикстурой.
function Copy-ColdMock($Value) {return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64))}

try {
    # Только read-only observer: held handle и переход exit детерминированы scoped providers.
    # Get-ColdCurrentProcess остаётся настоящим; никакого OS Kill, сети или native запуска.
    & {
        $handoffRoot=Join-Path $fixture 'handoff-copy'
        $handoffBirth=[datetime]::UtcNow
        $handoffExe=Join-Path $handoffRoot 'CashPrediction.exe'
        function Get-CopyProcesses {return $script:handoffEntry}
        function Test-Path([string]$LiteralPath) {
            if ($LiteralPath -cne (Join-Path $handoffRoot 'CashMemory/Updates/install-journal.json')) {throw 'FIXTURE_WRONG_JOURNAL'}
            return $true
        }
        function Get-ColdControlledInventory {return @()}
        function Get-NetTCPConnection {
            if ($script:handoffCase -eq 'exit-witness') {
                $script:handoffExitReads++; $script:handoffRetained.HasExited=$true
                throw [InvalidOperationException]::new('exit-during-listener-read')
            }
            throw 'FIXTURE_UNEXPECTED_NETWORK'
        }
        function Stop-ColdRetainedProcess {throw 'FIXTURE_UNKNOWN_KILL'}
        function Open-PortableProcess([int]$ProcessId) {
            if ($ProcessId -ne $script:handoffEntry.ProcessId) {throw 'FIXTURE_WRONG_PID'}
            $script:handoffOpens++
            return $script:handoffRetained
        }
        function Get-CimInstance {
            $script:handoffCensusReads++
            if ($script:handoffCase -in @('exit-census','exit-census-error')) {
                $script:handoffRetained.HasExited=$true
                if ($script:handoffCase -eq 'exit-census-error') {throw 'exit-census-access-error'}
                return @()
            }
            if ($script:handoffCase -eq 'live-access-error') {throw 'live-access-error'}
            return $script:handoffCurrent
        }
        foreach ($case in 'exit-acquired','exit-census','exit-identity','exit-after-current','exit-witness',
            'exit-census-error','live-wrong-path','live-reused-pid','live-access-error') {
            $script:handoffCase=$case; $script:handoffOpens=0; $script:handoffCensusReads=0
            $script:handoffDisposes=0; $script:handoffExitReads=0
            $script:handoffEntry=[pscustomobject]@{ProcessId=731;CreationDate=$handoffBirth;
                ExecutablePath=$handoffExe;CommandLine='owned-handoff'}
            $script:handoffCurrent=[pscustomobject]@{ProcessId=731;CreationDate=$handoffBirth;
                ExecutablePath=$handoffExe;CommandLine='owned-handoff'}
            $script:handoffRetained=[pscustomobject]@{Id=731;HasExited=($case -eq 'exit-acquired');
                StartTime=$handoffBirth;MainModule=[pscustomobject]@{FileName=$handoffExe};MainWindowHandle=[intptr]::Zero}
            $script:handoffRetained | Add-Member -MemberType ScriptMethod -Name Dispose -Value {$script:handoffDisposes++}
            switch ($case) {
                'exit-identity' {
                    $script:handoffRetained | Add-Member -MemberType ScriptProperty -Name MainModule -Value {
                        $script:handoffExitReads++; $this.HasExited=$true
                        throw [InvalidOperationException]::new('exit-during-module-read')
                    } -Force
                }
                'exit-after-current' {
                    $script:handoffRetained | Add-Member -MemberType ScriptProperty -Name StartTime -Value {
                        $script:handoffExitReads++; $this.HasExited=$true; return $handoffBirth
                    } -Force
                }
                'live-wrong-path' {$script:handoffCurrent.ExecutablePath='C:\foreign\other.exe'}
                'live-reused-pid' {$script:handoffCurrent.CreationDate=$handoffBirth.AddTicks(10)}
            }
            $observe={Get-ColdRecoveryObservation $handoffRoot $handoffBirth.AddSeconds(-1)}
            if ($case.StartsWith('exit-')) {
                $sample=& $observe
                if ($sample.visibleCount -ne 0 -or -not $sample.journalBefore -or -not $sample.journalAfter -or
                    $sample.controlledSha256 -cne (Get-ColdObjectHash @()) -or -not $script:handoffRetained.HasExited) {
                    throw 'Exited handoff became UI witness or corrupted recovery observation.'
                }
                $expectedReads=if ($case -eq 'exit-acquired') {0} else {1}
                if ($script:handoffCensusReads -ne $expectedReads) {throw 'Handoff exit used unexpected identity census.'}
                if ($case -in @('exit-identity','exit-after-current','exit-witness') -and $script:handoffExitReads -ne 1) {
                    throw 'Handoff did not exercise retained read exit.'
                }
            } else {
                $code=switch ($case) {
                    'live-wrong-path' {'COLD_PROCESS_IDENTITY'}
                    'live-reused-pid' {'COLD_HELPER_PID_REUSED'}
                    'live-access-error' {'live-access-error'}
                }
                Assert-ColdMockReject $observe $code
                if ($script:handoffRetained.HasExited -or $script:handoffCensusReads -ne 1) {throw 'Live handoff failure was not identity-bound.'}
            }
            if ($script:handoffOpens -ne 1 -or $script:handoffDisposes -ne 1) {throw 'Handoff retained handle was not disposed exactly once.'}
            $script:observerHandoffChecks++
        }
    }
    # Три живых PID разделяют snapshot внутри poll, но не между observation вызовами.
    & {
        $listenerRoot=Join-Path $fixture 'listener-Ж_日本'
        $listenerBirth=[datetime]::UtcNow
        $script:listenerEntries=@(foreach ($number in 731,732,733) {
            [pscustomobject]@{ProcessId=$number;CreationDate=$listenerBirth;
                ExecutablePath=(Join-Path $listenerRoot 'CashPrediction.exe');CommandLine=('owned-listener-'+$number)}
        })
        function Get-CopyProcesses {return $script:listenerEntries}
        function Get-CimInstance {$script:listenerCensusReads++; return $script:listenerEntries}
        function Test-Path([string]$LiteralPath) {
            if ($LiteralPath -cne (Join-Path $listenerRoot 'CashMemory/Updates/install-journal.json')) {throw 'FIXTURE_WRONG_JOURNAL'}
            return $false
        }
        function Get-ColdControlledInventory {return @()}
        function Stop-ColdRetainedProcess {throw 'FIXTURE_UNKNOWN_KILL'}
        function Open-PortableProcess([int]$ProcessId) {
            $entry=@($script:listenerEntries | Where-Object ProcessId -eq $ProcessId)
            if ($entry.Count -ne 1) {throw 'FIXTURE_FOREIGN_PID'}
            $script:listenerOpens++
            $handle=[pscustomobject]@{Id=$ProcessId;HasExited=$false;StartTime=$listenerBirth;
                MainModule=[pscustomobject]@{FileName=$entry[0].ExecutablePath};MainWindowHandle=[intptr]::Zero}
            $handle | Add-Member -MemberType ScriptMethod -Name Dispose -Value {$script:listenerDisposes++}
            return $handle
        }
        function Get-NetTCPConnection {
            [CmdletBinding()]param([string]$State)
            if ($State -cne 'Listen' -or $ErrorActionPreference -ne 'Stop') {throw 'FIXTURE_WRONG_LISTENER_QUERY'}
            $script:listenerQueries++
            switch ($script:listenerPoll) {
                1 {
                    # Несколько портов одного PID считаются одним witness; чужой PID не считается.
                    return @([pscustomobject]@{OwningProcess=731;LocalPort=8101},
                        [pscustomobject]@{OwningProcess=731;LocalPort=8102},[pscustomobject]@{OwningProcess=999;LocalPort=8103})
                }
                2 {return @()}
                3 {return @([pscustomobject]@{OwningProcess=732;LocalPort=8201},
                    [pscustomobject]@{OwningProcess=733;LocalPort=8202},[pscustomobject]@{OwningProcess=999;LocalPort=8203})}
                4 {return @([pscustomobject]@{OwningProcess=999;LocalPort=8301})}
                default {throw 'FIXTURE_UNEXPECTED_POLL'}
            }
        }
        foreach ($poll in 1,2,3,4) {
            $script:listenerPoll=$poll; $script:listenerQueries=0; $script:listenerOpens=0
            $script:listenerDisposes=0; $script:listenerCensusReads=0
            $sample=Get-ColdRecoveryObservation $listenerRoot $listenerBirth.AddSeconds(-1)
            $expectedVisible=@(1,0,2,0)[$poll-1]
            if ($sample.visibleCount -ne $expectedVisible -or $sample.journalBefore -or $sample.journalAfter -or
                $sample.controlledSha256 -cne (Get-ColdObjectHash @()) -or $script:listenerQueries -ne 1 -or
                $script:listenerOpens -ne 3 -or $script:listenerDisposes -ne 3 -or $script:listenerCensusReads -ne 3) {
                throw 'Listener snapshot was reused across polls, queried per PID, or associated with a foreign PID.'
            }
            $script:observerListenerChecks++
        }
    }
    if ($ObserverHandoffOnly) {
        Write-Output "Recovery observer handoff fixtures: PASS ($observerHandoffChecks deterministic cases; 6 exit cases without UI witness; 3 live identity/access failures rejected; $observerListenerChecks multi-process listener polls with one fresh TCP query each; retained handles disposed once; GUI/native/Maven not executed)."
        return
    }
    if ($SettingsOnly -or $SettingsJava -or $SettingsCoreJar) {
        # Только явные frozen JDK/JAR inputs: сборка и обнаружение случайного runtime запрещены.
        if (-not $SettingsJava -or -not $SettingsCoreJar) {throw 'Unicode settings fixtures require SettingsJava and SettingsCoreJar.'}
        & {
            $settingsJavaPath=Resolve-PortableSafetyPath $SettingsJava
            $settingsInputJar=Resolve-PortableSafetyPath $SettingsCoreJar
            if (-not (Test-Path -LiteralPath $settingsJavaPath -PathType Leaf) -or
                -not (Test-Path -LiteralPath $settingsInputJar -PathType Leaf)) {throw 'Unicode settings fixture input missing.'}
            $inputJarPin=(Get-FileHash -LiteralPath $settingsInputJar).Hash.ToLowerInvariant()
            $javaPin=(Get-FileHash -LiteralPath $settingsJavaPath).Hash.ToLowerInvariant()
            $settingsFunction=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $n.Name -ceq 'Initialize-ColdSettings'},$true))
            if ($settingsFunction.Count -ne 1) {throw 'Settings source fixture definition missing.'}
            $sourceMatch=[regex]::Match($settingsFunction[0].Extent.Text,"(?s)\`$code=@'\r?\n(?<body>.*?)\r?\n'@")
            if (-not $sourceMatch.Success) {throw 'Settings source fixture anchor changed.'}
            $sourcePin=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
                [Text.UTF8Encoding]::new($false).GetBytes($sourceMatch.Groups['body'].Value))).ToLowerInvariant()
            $unicodeRoot=Join-Path $fixture 'settings-Ж_日本'
            $asciiEvidence=Join-Path $fixture 'settings-evidence'
            foreach ($directory in (Join-Path $unicodeRoot 'app'),(Join-Path $unicodeRoot 'CashMemory'),$asciiEvidence) {
                [void][IO.Directory]::CreateDirectory($directory)
            }
            if ($asciiEvidence -match '[^\x00-\x7f]') {throw 'Settings fixture evidence must be ASCII.'}
            $ownedCore=Join-Path $unicodeRoot ('app/'+[IO.Path]::GetFileName($settingsInputJar))
            Copy-Item -LiteralPath $settingsInputJar -Destination $ownedCore
            $plan=Join-Path $unicodeRoot 'CashMemory/protected-plan.md'
            [IO.File]::WriteAllText($plan,"fixture-plan`n",[Text.UTF8Encoding]::new($false))
            $planPin=(Get-FileHash -LiteralPath $plan).Hash
            Initialize-ColdSettings $unicodeRoot $settingsJavaPath $asciiEvidence
            $receipt=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $asciiEvidence 'settings-preparation.json') -Raw)
            $copiedCore=Join-Path $asciiEvidence 'settings-core.jar'
            $settingsPath=Join-Path $unicodeRoot 'CashMemory/settings.md'
            if ($receipt.actualExit -ne 0 -or $receipt.coreJar -cne $ownedCore -or $receipt.settingsCoreJar -cne $copiedCore -or
                $receipt.coreJarSha256 -cne $inputJarPin -or $receipt.sourceSha256 -cne $sourcePin -or
                (Get-FileHash -LiteralPath $ownedCore).Hash.ToLowerInvariant() -cne $inputJarPin -or
                (Get-FileHash -LiteralPath $copiedCore).Hash.ToLowerInvariant() -cne $inputJarPin -or
                (Get-FileHash -LiteralPath (Join-Path $asciiEvidence 'ColdSettings.java')).Hash.ToLowerInvariant() -cne $sourcePin -or
                (Get-FileHash -LiteralPath $settingsPath).Hash.ToLowerInvariant() -cne $receipt.settingsSha256 -or
                (Get-FileHash -LiteralPath $plan).Hash -cne $planPin -or
                -not (Get-Content -LiteralPath $settingsPath -Raw).Contains('Ж_日本')) {throw 'Unicode settings actual API/pins mismatch.'}
            $script:unicodeSettingsChecks++
            # Повреждаем только новую собственную ASCII-копию сразу после Copy-Item.
            # Проверка pin должна отказать до генерации source и запуска Java/API.
            $tamperEvidence=Join-Path $fixture 'settings-tampered'
            [void][IO.Directory]::CreateDirectory($tamperEvidence)
            $tamperCore=Join-Path $tamperEvidence 'settings-core.jar'
            & {
                function Copy-Item([string]$LiteralPath,[string]$Destination) {
                    if ($LiteralPath -cne $ownedCore -or $Destination -cne $tamperCore) {throw 'FIXTURE_FOREIGN_COPY'}
                    Microsoft.PowerShell.Management\Copy-Item -LiteralPath $LiteralPath -Destination $Destination
                    $stream=[IO.File]::Open($Destination,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
                    try {$first=$stream.ReadByte();$stream.Position=0;$stream.WriteByte([byte]($first -bxor 1))} finally {$stream.Dispose()}
                    $script:settingsTamperCopies++
                }
                $script:settingsTamperCopies=0
                Assert-ColdMockReject {Initialize-ColdSettings $unicodeRoot $settingsJavaPath $tamperEvidence} 'COLD_SETTINGS_PREPARATION'
                if ($script:settingsTamperCopies -ne 1 -or
                    (Get-FileHash -LiteralPath $tamperCore).Hash.ToLowerInvariant() -ceq $inputJarPin -or
                    (Test-Path -LiteralPath (Join-Path $tamperEvidence 'ColdSettings.java')) -or
                    (Test-Path -LiteralPath (Join-Path $tamperEvidence 'settings-preparation.json'))) {throw 'Copied-JAR tamper did not stop before Java.'}
                $script:unicodeSettingsChecks++
            }
            if ((Get-FileHash -LiteralPath $settingsInputJar).Hash.ToLowerInvariant() -cne $inputJarPin -or
                (Get-FileHash -LiteralPath $ownedCore).Hash.ToLowerInvariant() -cne $inputJarPin -or
                (Get-FileHash -LiteralPath $settingsJavaPath).Hash.ToLowerInvariant() -cne $javaPin -or
                (Get-FileHash -LiteralPath $settingsPath).Hash.ToLowerInvariant() -cne $receipt.settingsSha256) {throw 'Unicode settings fixture changed frozen inputs.'}
            $script:unicodeSettingsChecks++
            Write-Output "Unicode settings API fixtures: PASS ($unicodeSettingsChecks checks; sourceSha256=$sourcePin; coreJarSha256=$inputJarPin; actualExit=$($receipt.actualExit); copied-JAR tamper rejected before Java; GUI/native/Maven not executed)."
        }
        if ($SettingsOnly) {return}
    }
    $root=Join-Path $fixture 'copy'; $null=New-Item -ItemType Directory -Path $root
    # Реальный cmd ждёт EOF на stdin: проверяем живой и завершившийся retained handle.
    # Чужой объект открыт по PID, его поддельный StartInfo не служит доказательством пути.
    # Win32 возвращает канонический регистр System32; сравнение личности остаётся строгим.
    $processExe=Join-Path ([IO.Path]::GetDirectoryName([Environment]::SystemDirectory)) 'System32/cmd.exe'
    $process=[Diagnostics.Process]::new(); $foreign=$null; $processExpected=$null
    $process.StartInfo=[Diagnostics.ProcessStartInfo]::new($processExe)
    $process.StartInfo.UseShellExecute=$false; $process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardInput=$true; $process.StartInfo.RedirectStandardOutput=$true
    foreach ($argument in '/d','/q','/c','set /p cp_receipt_gate=') {$process.StartInfo.ArgumentList.Add($argument)}
    try {
        if (-not $process.Start()) {throw 'Receipt fixture process start failed.'}
        [void]$process.Handle
        $processExpected=[pscustomobject]@{ProcessId=$process.Id;StartedAtTicks=$process.StartTime.ToUniversalTime().Ticks;
            ExecutablePath=$processExe;OwnedRoot=$root}
        Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $process $root) $root $processExe
        $processReceiptChecks++
        $foreign=[Diagnostics.Process]::GetProcessById($process.Id); [void]$foreign.Handle
        Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $foreign $root) $root $processExe
        $processReceiptChecks++
        # У opened Process StartInfo отсутствует; ETS-подстановка также не должна помочь.
        $foreign | Add-Member -MemberType NoteProperty -Name StartInfo -Value ([Diagnostics.ProcessStartInfo]::new('C:\foreign\wrong.exe')) -Force
        $foreignReceipt=Get-ColdProcessReceipt $foreign $root
        Assert-ColdProcessIdentity $processExpected $foreignReceipt $root $processExe
        Assert-ColdMockReject {Assert-ColdProcessIdentity $processExpected $foreignReceipt $root $foreign.StartInfo.FileName} 'COLD_PROCESS_IDENTITY'
        $processReceiptChecks++
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(5000)) {throw 'Receipt fixture process did not exit.'}
        $process.StartInfo.FileName='C:\foreign\changed-after-start.exe'
        Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $process $root) $root $processExe
        Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $foreign $root) $root $processExe
        $processReceiptChecks+=2
        $foreign.PSObject.Properties.Remove('StartInfo')
        Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $foreign $root) $root $processExe
        $processReceiptChecks++
        # Детерминированная гонка: устаревшее false от HasExited, но реальный MainModule уже null.
        # Исходная функция падает на FileName под StrictMode; новая не зависит от HasExited.
        if ($null -ne $process.MainModule) {throw 'Exited process still has a main module.'}
        $process | Add-Member -MemberType NoteProperty -Name HasExited -Value $false -Force
        try {
            $raceReceipt=Get-ColdProcessReceipt $process $root
            Assert-ColdProcessIdentity $processExpected $raceReceipt $root $processExe
            $processReceiptChecks++
            foreach ($field in 'ProcessId','StartedAtTicks','ExecutablePath') {
                $badReceipt=Copy-ColdMock $raceReceipt
                if ($field -eq 'ExecutablePath') {$badReceipt.$field=$process.StartInfo.FileName} else {$badReceipt.$field++}
                Assert-ColdMockReject {Assert-ColdProcessIdentity $processExpected $badReceipt $root $processExe} 'COLD_PROCESS_IDENTITY'
            }
        } finally {$process.PSObject.Properties.Remove('HasExited')}
    } finally {
        if ($null -ne $foreign) {$foreign.Dispose()}
        if ($null -ne $processExpected -and -not $process.HasExited) {
            Assert-ColdProcessIdentity $processExpected (Get-ColdProcessReceipt $process $root) $root $processExe
            $process.Kill(); [void]$process.WaitForExit(5000)
        }
        $process.Dispose()
    }
    Assert-ColdMockReject {Get-ColdProcessReceipt ([pscustomobject]@{Id=731;Handle=1;StartInfo=[pscustomobject]@{FileName=$processExe}}) $root} 'COLD_PROCESS_TYPE'
    # Короткие процессы проверяют фактическую гонку между Start и чтением квитанции.
    for ($attempt=0;$attempt -lt 16;$attempt++) {
        $short=[Diagnostics.Process]::new()
        $short.StartInfo=[Diagnostics.ProcessStartInfo]::new($processExe)
        $short.StartInfo.UseShellExecute=$false; $short.StartInfo.CreateNoWindow=$true
        foreach ($argument in '/d','/q','/c','exit 0') {$short.StartInfo.ArgumentList.Add($argument)}
        try {
            if (-not $short.Start()) {throw 'Short receipt fixture start failed.'}
            $shortReceipt=Get-ColdProcessReceipt $short $root
            if (-not $short.WaitForExit(5000)) {throw 'Short receipt fixture did not exit.'}
            $shortExpected=[pscustomobject]@{ProcessId=$short.Id;StartedAtTicks=$short.StartTime.ToUniversalTime().Ticks;
                ExecutablePath=$processExe;OwnedRoot=$root}
            Assert-ColdProcessIdentity $shortExpected $shortReceipt $root $processExe
            Assert-ColdProcessIdentity $shortExpected (Get-ColdProcessReceipt $short $root) $root $processExe
            $processReceiptChecks+=2
        } finally {$short.Dispose()}
    }
    # Два заполненных pipe должны дренироваться параллельно, включая ненулевой exit.
    $diagnosticPowerShell=Join-Path ([IO.Path]::GetDirectoryName([Environment]::SystemDirectory)) 'System32/WindowsPowerShell/v1.0/powershell.exe'
    foreach ($exit in 0,7) {
        $diagnosticDir=Join-Path $fixture ('run-'+[guid]::NewGuid().ToString())
        $null=New-Item -ItemType Directory -Path $diagnosticDir
        $diagnosticProcess=$null; $diagnosticIdentity=$null
        $body="[byte[]]`$o=[Text.Encoding]::ASCII.GetBytes('O'*2097152);[Console]::OpenStandardOutput().Write(`$o,0,`$o.Length);[byte[]]`$e=[Text.Encoding]::ASCII.GetBytes('E'*2097152);[Console]::OpenStandardError().Write(`$e,0,`$e.Length);exit $exit"
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($body))
        try {
            $diagnosticProcess=Start-ColdProcess $diagnosticPowerShell @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-EncodedCommand',$encoded) $root -DiagnosticsDirectory $diagnosticDir
            $diagnosticIdentity=Get-ColdProcessReceipt $diagnosticProcess $root
            if (-not $diagnosticProcess.WaitForExit(10000)) {throw 'Diagnostic pipes blocked helper exit.'}
            $badIdentity=Copy-ColdMock $diagnosticIdentity; $badIdentity.ProcessId++
            Assert-ColdMockReject {Save-ColdHelperDiagnostics $diagnosticProcess $badIdentity $root $diagnosticDir} 'COLD_PROCESS_IDENTITY'
            if (Test-Path -LiteralPath (Join-Path $diagnosticDir 'helper-diagnostics.json')) {throw 'Foreign identity published diagnostics.'}
            Assert-ColdMockReject {Save-ColdHelperDiagnostics $diagnosticProcess $diagnosticIdentity $root $diagnosticDir 5001} 'COLD_DIAGNOSTICS_BOUND'
            $diagnosticPath=Save-ColdHelperDiagnostics $diagnosticProcess $diagnosticIdentity $root $diagnosticDir
            $diagnostic=Get-Content -LiteralPath $diagnosticPath -Raw | ConvertFrom-Json
            if ($diagnostic.actualExit -ne $exit -or -not $diagnostic.actualDiagnostics.drained -or
                -not $diagnostic.actualDiagnostics.stdoutTruncated -or -not $diagnostic.actualDiagnostics.stderrTruncated -or
                $diagnostic.actualDiagnostics.stdoutBytesRead -ne 2097152 -or $diagnostic.actualDiagnostics.stderrBytesRead -ne 2097152 -or
                (Get-Item -LiteralPath $diagnostic.actualDiagnostics.stdout).Length -ne 1048576 -or
                (Get-Item -LiteralPath $diagnostic.actualDiagnostics.stderr).Length -ne 1048576) {throw 'Bounded diagnostics lost actual output/exit.'}
            $helperDiagnosticsChecks++
        } finally {
            if ($null -ne $diagnosticIdentity) {Stop-ColdRetainedProcess $diagnosticProcess $diagnosticIdentity $root}
            if ($null -ne $diagnosticProcess) {$diagnosticProcess.Dispose()}
        }
    }
    # Незавершённая задача моделирует EOF, удерживаемый потомком, без чужих процессов.
    $diagnosticDir=Join-Path $fixture ('run-'+[guid]::NewGuid().ToString())
    $null=New-Item -ItemType Directory -Path $diagnosticDir
    $diagnosticProcess=$null; $diagnosticIdentity=$null
    try {
        $diagnosticProcess=Start-ColdProcess $diagnosticPowerShell @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command','[Console]::Error.WriteLine("kept-before-timeout");exit 1') $root -DiagnosticsDirectory $diagnosticDir
        $diagnosticIdentity=Get-ColdProcessReceipt $diagnosticProcess $root
        if (-not $diagnosticProcess.WaitForExit(5000)) {throw 'Diagnostic timeout fixture did not exit.'}
        $capture=$diagnosticProcess.ColdHelperCapture
        if (-not ([Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($capture.stdoutTask,$capture.stderrTask))).Wait(5000)) {throw 'Diagnostic fixture pipes did not close.'}
        $pending=[Threading.Tasks.TaskCompletionSource[long]]::new()
        $capture.stderrTask=$pending.Task
        $watch=[Diagnostics.Stopwatch]::StartNew()
        Assert-ColdMockReject {Save-ColdHelperDiagnostics $diagnosticProcess $diagnosticIdentity $root $diagnosticDir 100} 'COLD_DIAGNOSTICS_DRAIN_TIMEOUT'
        $watch.Stop()
        $diagnostic=Get-Content -LiteralPath (Join-Path $diagnosticDir 'helper-diagnostics.json') -Raw | ConvertFrom-Json
        if ($watch.ElapsedMilliseconds -gt 3000 -or $diagnostic.actualExit -ne 1 -or $diagnostic.actualDiagnostics.drained -or
            $diagnostic.actualDiagnostics.failure -cne 'COLD_DIAGNOSTICS_DRAIN_TIMEOUT' -or
            -not (Get-Content -LiteralPath $capture.stderr -Raw).Contains('kept-before-timeout') -or -not $capture.cancel.IsCancellationRequested) {
            throw 'Drain timeout lost diagnostics or was not bounded.'
        }
        $helperDiagnosticsChecks++
        $capture.cancel.Dispose()
    } finally {
        if ($null -ne $diagnosticIdentity) {Stop-ColdRetainedProcess $diagnosticProcess $diagnosticIdentity $root}
        if ($null -ne $diagnosticProcess) {$diagnosticProcess.Dispose()}
    }
    Assert-ColdMockReject {New-ColdHelperCapture $root} 'COLD_DIAGNOSTICS_SCOPE'
    # Устаревший CIM допускает пропуск только после нового census без этого PID.
    # Провайдеры ограничены дочерней областью; никаких окон, сети либо OS Kill.
    & {
        $observerBirth=[datetime]::UtcNow
        $script:observerEntry=[pscustomobject]@{ProcessId=731;CreationDate=$observerBirth;
            ExecutablePath=(Join-Path $root 'CashPrediction.exe');CommandLine='owned-launcher'}
        function Get-CopyProcesses {return $script:observerEntry}
        function Open-PortableProcess([int]$ProcessId) {
            if ($ProcessId -ne $script:observerEntry.ProcessId) {throw 'FIXTURE_WRONG_PID'}
            $script:observerOpens++; throw $script:observerOpenError
        }
        function Get-CimInstance { $script:observerCensusReads++; return $script:observerCensus }
        function Get-ColdControlledInventory {return @()}
        function Test-Path([string]$LiteralPath) {
            if ($LiteralPath -cne (Join-Path $root 'CashMemory/Updates/install-journal.json')) {throw 'FIXTURE_WRONG_JOURNAL'}
            return $true
        }
        function Get-NetTCPConnection {throw 'FIXTURE_UNEXPECTED_NETWORK'}
        function Stop-ColdRetainedProcess {throw 'FIXTURE_UNKNOWN_KILL'}
        foreach ($observer in 'ui','recovery') {
            $observe={
                if ($observer -eq 'ui') {Get-ColdUiReceipt $root 'fx' $observerBirth.AddSeconds(-1)}
                else {Get-ColdRecoveryObservation $root $observerBirth.AddSeconds(-1)}
            }
            foreach ($kind in 'argument','invalid-operation','wrapped-argument') {
                $script:observerOpens=0; $script:observerCensusReads=0; $script:observerCensus=@()
                $script:observerOpenError=switch ($kind) {
                    'argument' {[ArgumentException]::new('observer-gone')}
                    'invalid-operation' {[InvalidOperationException]::new('observer-gone')}
                    'wrapped-argument' {[Exception]::new('observer-wrapper',[ArgumentException]::new('observer-gone'))}
                }
                $observation=& $observe
                if ($observer -eq 'ui') {
                    if ($null -ne $observation) {throw 'Vanished PID became UI ready.'}
                } elseif ($observation.visibleCount -ne 0 -or -not $observation.journalBefore -or
                    -not $observation.journalAfter -or $observation.controlledSha256 -cne (Get-ColdObjectHash @())) {
                    throw 'Vanished PID corrupted recovery observation.'
                }
                if ($script:observerOpens -ne 1 -or $script:observerCensusReads -ne 1) {throw 'Observer did not recheck vanished PID.'}
                $script:observerAcquisitionChecks++
            }
            foreach ($case in 'live-access','gone-access','reused-argument','reused-invalid-operation') {
                $script:observerOpens=0; $script:observerCensusReads=0
                $script:observerCensus=@($script:observerEntry)
                if ($case -eq 'gone-access') {$script:observerCensus=@()}
                if ($case.StartsWith('reused-')) {
                    $script:observerCensus=@([pscustomobject]@{ProcessId=731;CreationDate=$observerBirth.AddSeconds(1);
                        ExecutablePath='C:\foreign\other.exe';CommandLine='foreign-process'})
                }
                $script:observerOpenError=if ($case -eq 'reused-argument') {[ArgumentException]::new($case)}
                    elseif ($case -eq 'reused-invalid-operation') {[InvalidOperationException]::new($case)}
                    else {[ComponentModel.Win32Exception]::new($case)}
                Assert-ColdMockReject $observe $case
                # Recovery проверяет census только для двух разрешённых типов exit race.
                $expectedCensusReads=if ($observer -eq 'recovery' -and $case.EndsWith('access')) {0} else {1}
                if ($script:observerOpens -ne 1 -or $script:observerCensusReads -ne $expectedCensusReads) {throw 'Observer fatal case used unexpected census.'}
                $script:observerAcquisitionChecks++
            }
        }
    }
    $productionStopProvider=${function:Stop-ColdRetainedProcess}
    # Тип PID сохраняется без JSON round-trip: настоящий CIM использует UInt32.
    # Mock handle не вызывает ОС; успешный cleanup обязан дойти до проверки личности.
    & {
        $WorkDir=$fixture
        $pidBirth=[datetime]::UtcNow
        $pidHelperCommand="& '"+(Join-Path $root 'CashMemory/Updates/apply-update.ps1').Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
        $pidHelperEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($pidHelperCommand))
        function Get-CopyProcesses {if (-not $script:pidStopped) {return $script:pidEntry}}
        function Get-CimInstance {if (-not $script:pidStopped) {return $script:pidEntry}}
        function Open-PortableProcess([int]$ProcessId) {
            if ($ProcessId -ne $script:pidEntry.ProcessId) {throw 'FIXTURE_WRONG_PID'}
            $script:pidOpens++
            $handle=[pscustomobject]@{Id=$ProcessId;HasExited=$false;StartTime=$pidBirth;
                MainModule=[pscustomobject]@{FileName=$script:pidEntry.ExecutablePath}}
            $handle | Add-Member -MemberType ScriptMethod -Name Dispose -Value {$script:pidDisposes++}
            return $handle
        }
        function Get-ColdProcessReceipt($Process,[string]$OwnedRoot) {
            return [pscustomobject]@{ProcessId=$Process.Id;StartedAtTicks=$Process.StartTime.ToUniversalTime().Ticks;
                ExecutablePath=$Process.MainModule.FileName;OwnedRoot=$OwnedRoot}
        }
        function Stop-ColdRetainedProcess($Process,$Identity,[string]$OwnedRoot) {
            Assert-ColdProcessIdentity $Identity (Get-ColdProcessReceipt $Process $OwnedRoot) $root $script:pidEntry.ExecutablePath
            $script:pidStops++; $script:pidStopped=$true
        }
        foreach ($scope in 'copy','helper') {
            $cleanup={
                if ($scope -eq 'copy') {Stop-ColdCopyProcesses $root}
                else {Stop-ColdRecoveryHelpers $root $diagnosticPowerShell $pidBirth.AddSeconds(-1)}
            }
            foreach ($value in @([uint32]731,[uint32][int]::MaxValue,[uint32]0,[uint32]2147483648,$true,'731',[double]731)) {
                $script:pidStopped=$false; $script:pidOpens=0; $script:pidStops=0; $script:pidDisposes=0
                $pidExe=if ($scope -eq 'copy') {Join-Path $root 'CashPrediction.exe'} else {$diagnosticPowerShell}
                $pidCommand=if ($scope -eq 'copy') {'owned-launcher'} else {
                    '"'+$diagnosticPowerShell+'" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand '+$pidHelperEncoded
                }
                $script:pidEntry=[pscustomobject]@{ProcessId=$value;CreationDate=$pidBirth;
                    ExecutablePath=$pidExe;CommandLine=$pidCommand}
                $accepted=$value -is [uint32] -and $value -gt 0 -and $value -le [int]::MaxValue
                if ($accepted) {
                    & $cleanup
                    if ($script:pidOpens -ne 1 -or $script:pidStops -ne 1 -or $script:pidDisposes -ne 1) {
                        throw 'UInt32 PID did not complete identity-bound cleanup.'
                    }
                } else {
                    Assert-ColdMockReject $cleanup 'COLD_PROCESS_IDENTITY'
                    if ($script:pidOpens -ne 0 -or $script:pidStops -ne 0 -or $script:pidDisposes -ne 0) {
                        throw 'Invalid CIM PID reached process acquisition.'
                    }
                }
                $script:cleanupPidTypeChecks++
            }
        }
    }
    # Mock census/open ограничены дочерней областью; OS Kill в этих тестах запрещён.
    & {
        $WorkDir=$fixture
        $cleanupBirth=[datetime]::UtcNow
        $script:cleanupEntry=[pscustomobject]@{ProcessId=731;CreationDate=$cleanupBirth;ExecutablePath=(Join-Path $root 'CashPrediction.exe');CommandLine='owned-launcher'}
        function Get-CopyProcesses {
            $script:cleanupReads++
            if ($script:cleanupReads -eq 1) {return $script:cleanupEntry}
            return @()
        }
        function Get-CimInstance {return $script:cleanupCensus}
        function Open-PortableProcess {$script:cleanupOpens++;throw $script:cleanupOpenError}
        function Stop-ColdRetainedProcess {throw 'FIXTURE_UNKNOWN_KILL'}
        foreach ($openError in @([ArgumentException]::new('gone-before-open'),[InvalidOperationException]::new('gone-before-handle'))) {
            $script:cleanupReads=0; $script:cleanupOpens=0; $script:cleanupCensus=@(); $script:cleanupOpenError=$openError
            Stop-ColdCopyProcesses $root
            if ($script:cleanupOpens -ne 1) {throw 'Cleanup did not exercise vanished PID.'}
            $script:cleanupRaceChecks++
        }
        foreach ($case in 'reused-pid','access-denied') {
            $script:cleanupReads=0; $script:cleanupOpens=0
            $script:cleanupCensus=$(if ($case -eq 'reused-pid') {@($script:cleanupEntry)} else {@()})
            $script:cleanupOpenError=$(if ($case -eq 'reused-pid') {[ArgumentException]::new('reused-pid')} else {[ComponentModel.Win32Exception]::new('access-denied')})
            Assert-ColdMockReject {Stop-ColdCopyProcesses $root} $case
        }
        # Для helper исчезновение происходит между первоначальным CIM и открытием handle.
        $helperCommand="& '"+(Join-Path $root 'CashMemory/Updates/apply-update.ps1').Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
        $helperEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($helperCommand))
        $script:cleanupHelperEntry=[pscustomobject]@{ProcessId=731;CreationDate=$cleanupBirth;ExecutablePath=$diagnosticPowerShell;CommandLine=('"'+$diagnosticPowerShell+'" -EncodedCommand '+$helperEncoded)}
        function Get-CimInstance {
            $script:cleanupCimReads++
            if ($script:cleanupCimReads -eq 1) {return $script:cleanupHelperEntry}
            return $script:cleanupCensus
        }
        foreach ($case in 'gone','reused-pid','access-denied') {
            $script:cleanupCimReads=0; $script:cleanupOpens=0
            $script:cleanupCensus=$(if ($case -eq 'reused-pid') {@($script:cleanupHelperEntry)} else {@()})
            $script:cleanupOpenError=$(if ($case -eq 'access-denied') {[ComponentModel.Win32Exception]::new($case)} else {[ArgumentException]::new($case)})
            if ($case -eq 'gone') {
                Stop-ColdRecoveryHelpers $root $diagnosticPowerShell $cleanupBirth.AddSeconds(-1)
                if ($script:cleanupOpens -ne 1) {throw 'Helper cleanup did not exercise vanished PID.'}
                $script:cleanupRaceChecks++
            } else {Assert-ColdMockReject {Stop-ColdRecoveryHelpers $root $diagnosticPowerShell $cleanupBirth.AddSeconds(-1)} $case}
        }
        # Retained Process реален: exit допускается только в cleanup, обычный observer строгий.
        function Get-CimInstance {return $script:cleanupCensus}
        $ended=[Diagnostics.Process]::new()
        $ended.StartInfo=[Diagnostics.ProcessStartInfo]::new($processExe)
        $ended.StartInfo.UseShellExecute=$false; $ended.StartInfo.CreateNoWindow=$true
        foreach ($argument in '/d','/q','/c','exit 0') {$ended.StartInfo.ArgumentList.Add($argument)}
        try {
            if (-not $ended.Start()) {throw 'Cleanup race process did not start.'}
            [void]$ended.Handle
            $endedEntry=[pscustomobject]@{ProcessId=$ended.Id;CreationDate=$ended.StartTime;ExecutablePath=$processExe;CommandLine='own-fixture'}
            if (-not $ended.WaitForExit(5000)) {throw 'Cleanup race process did not exit.'}
            $script:cleanupCensus=@()
            if ($null -ne (Get-ColdCurrentProcess $endedEntry $ended $processExe -AllowExited)) {throw 'Exited cleanup returned a live identity.'}
            Assert-ColdMockReject {Get-ColdCurrentProcess $endedEntry $ended $processExe} 'COLD_PROCESS_IDENTITY'
            $script:cleanupRaceChecks++
            # У реального завершённого Process моделируем только устаревший HasExited и отказ Kill.
            $endedIdentity=Get-ColdProcessReceipt $ended $root
            $ended | Add-Member -MemberType NoteProperty -Name HasExited -Value $false -Force
            $ended | Add-Member -MemberType ScriptMethod -Name Kill -Value {
                $this.PSObject.Properties['HasExited'].Value=$true
                throw [InvalidOperationException]::new('ended-at-kill')
            } -Force
            try {
                & $productionStopProvider $ended $endedIdentity $root
                $script:cleanupRaceChecks++
            } finally {$ended.PSObject.Properties.Remove('HasExited');$ended.PSObject.Members.Remove('Kill')}
        } finally {$ended.Dispose()}
        # Реальный собственный живой handle не допускает смену birth/path/PID.
        $self=[Diagnostics.Process]::GetCurrentProcess()
        try {
            $selfExe=$self.MainModule.FileName
            $selfEntry=[pscustomobject]@{ProcessId=$self.Id;CreationDate=$self.StartTime;ExecutablePath=$selfExe;CommandLine='fixture-current'}
            $script:cleanupCensus=@($selfEntry)
            [void](Get-ColdCurrentProcess $selfEntry $self $selfExe -AllowExited)
            $script:cleanupRaceChecks++
            $badEntry=Copy-ColdMock $selfEntry; $badEntry.CreationDate=$self.StartTime.AddTicks(10)
            Assert-ColdMockReject {Get-ColdCurrentProcess $badEntry $self $selfExe -AllowExited} 'COLD_HELPER_PID_REUSED'
            Assert-ColdMockReject {Get-ColdCurrentProcess $selfEntry $self 'C:\foreign\process.exe' -AllowExited} 'COLD_PROCESS_IDENTITY'
            $badEntry=Copy-ColdMock $selfEntry; $badEntry.ProcessId++
            Assert-ColdMockReject {Get-ColdCurrentProcess $badEntry $self $selfExe -AllowExited} 'COLD_PROCESS_IDENTITY'
            # ОС Kill не вызывается: отказ шва живого собственного handle обязан сохраняться.
            $selfIdentity=Get-ColdProcessReceipt $self $root
            $self | Add-Member -MemberType ScriptMethod -Name Kill -Value {throw 'live-kill-refused'} -Force
            try {Assert-ColdMockReject {& $productionStopProvider $self $selfIdentity $root} 'Exception calling "Kill" with "0" argument(s): "live-kill-refused"'}
            finally {$self.PSObject.Members.Remove('Kill')}
        } finally {$self.Dispose()}
        # Исполняются точные AST-ветви finally runner, без запуска тела матрицы.
        $recordNodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.IfStatementAst] -and
            $n.Extent.Text.StartsWith('if ($cleanupErrors.Count)') -and $n.Extent.Text.Contains('NotePropertyName cleanupFailure')},$true))
        $throwNodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.IfStatementAst] -and
            $n.Extent.Text.StartsWith('if ($cleanupErrors.Count -and $null -eq $row.PSObject.Properties')},$true))
        if ($recordNodes.Count -ne 1 -or $throwNodes.Count -ne 1) {throw 'Cleanup failure AST changed.'}
        $recordCleanup=[scriptblock]::Create($recordNodes[0].Extent.Text)
        $throwCleanup=[scriptblock]::Create($throwNodes[0].Extent.Text)
        $cleanupErrors=@('SECONDARY_CLEANUP_FAILURE'); $row=[pscustomobject]@{status='FAIL';failure='PRIMARY_RECOVERY_FAILURE'}
        $caught=$null
        try {try {throw 'PRIMARY_RECOVERY_FAILURE'} finally {. $recordCleanup; . $throwCleanup}} catch {$caught=$_.Exception.Message}
        if ($caught -cne 'PRIMARY_RECOVERY_FAILURE' -or $row.cleanupFailure -cne 'SECONDARY_CLEANUP_FAILURE') {throw 'Cleanup masked primary failure.'}
        $script:cleanupRaceChecks++
        $row=[pscustomobject]@{status='PASS'}
        . $recordCleanup
        Assert-ColdMockReject {. $throwCleanup} 'SECONDARY_CLEANUP_FAILURE'
        if ($row.status -cne 'FAIL') {throw 'Cleanup-only failure accepted PASS.'}
    }
    # Wire-снимки используют грамматику XML/Markdown, реальные UTF-8 bytes и их хеши.
    # Recorder начинается через 1.28 секунды после birth; nanos не округляются вперёд.
    & {
        $birthTicks=([datetime]::new(2026,10,4,1,13,30,[DateTimeKind]::Utc)).Ticks
        $sessionUi=[pscustomobject]@{pid=731;startedAtTicks=$birthTicks}
        $sessionFinished=$birthTicks+50000000L
        $sessionWireWords=Get-ColdSessionWords
        function New-ColdSessionWire([string]$Client,[string]$Started,[string]$Saved,[string]$ProcessId='731',
            [string]$Schema='1',[string]$WireClient=$Client) {
            if ($Client -eq 'fx') {
                $text='<?xml version="1.0" encoding="UTF-8"?>'+"`n"+
                    '<session schema="'+$Schema+'" client="'+$WireClient+'" state="running" pid="'+$ProcessId+
                    '" startedAt="'+$Started+'" savedAt="'+$Saved+'">'+"`n"+
                    '  <main x="0" y="0" width="1200" height="800" maximized="false" view="TABLE" period="M12" filterText="">'+
                    '<plan path=""/><filters/><selection rowId=""/></main>'+"`n"+'  <windows/>'+"`n"+'</session>'+"`n"
                $path='CashMemory/session-fx.xml'
            } else {
                $lines=@(($sessionWireWords['session.md.title.prefix']+$WireClient+')'),'')
                foreach ($pair in @(@('state','running'),@('pid',$ProcessId),@('started',$Started),@('saved',$Saved),@('schema',$Schema))) {
                    $lines+=('- '+$sessionWireWords['session.md.key.'+$pair[0]]+': '+$pair[1])
                }
                $lines+=@('',$sessionWireWords['session.md.section.main'],'')
                foreach ($pair in @(@('view','TABLE'),@('plan',''),@('dirty',$sessionWireWords['session.md.no']),@('period','M12'),
                    @('filters',''),@('filterText',''),@('selected',''),@('bounds','0, 0, 1200, 800'),@('maximized',$sessionWireWords['session.md.no']))) {
                    $lines+=('- '+$sessionWireWords['session.md.key.'+$pair[0]]+': '+$pair[1])
                }
                $lines+=@('',$sessionWireWords['session.md.section.windows'])
                $text=($lines -join "`n")+"`n"; $path='CashMemory/web-session.md'
            }
            $bytes=[Text.UTF8Encoding]::new($false).GetBytes($text)
            return [pscustomobject]@{path=$path;sizeBytes=[long]$bytes.Length;
                sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();
                contentBase64=[Convert]::ToBase64String($bytes);readOnly=$false}
        }
        $started='2026-10-04T01:13:31.280123499Z'; $saved='2026-10-04T01:13:32.560987699Z'
        foreach ($zone in 'Z','+00:00') {
            for ($digits=1;$digits -le 9;$digits++) {
                $fraction='280123499'.Substring(0,$digits)
                $expectedFraction=$fraction.Substring(0,[Math]::Min(7,$digits)).PadRight(7,'0')
                if ((Get-ColdUtcTicks ('2026-10-04T01:13:31.'+$fraction+$zone)) -ne
                    ($birthTicks+10000000L+[long]$expectedFraction)) {throw 'Java Instant fraction was rounded or misparsed.'}
                $script:sessionTimeChecks++
            }
        }
        foreach ($client in 'fx','web') {
            $wire=New-ColdSessionWire $client $started $saved
            Assert-ColdControlledChanges @() @($wire) $client $sessionUi $root $sessionFinished
            $script:sessionTimeChecks++
            foreach ($case in 'old-start','bad-pid','future-saved','saved-before-session','bad-schema','bad-client',
                'too-many-digits','non-utc','malformed') {
                $badStarted=$started; $badSaved=$saved; $badPid='731'; $badSchema='1'; $badClient=$client
                switch ($case) {
                    'old-start' {$badStarted='2026-10-04T01:13:29.999999999Z'}
                    'bad-pid' {$badPid='732'}
                    'future-saved' {$badSaved='2026-10-04T01:13:35.000000100Z'}
                    'saved-before-session' {$badSaved='2026-10-04T01:13:31.280123300Z'}
                    'bad-schema' {$badSchema='2'}
                    'bad-client' {$badClient='swing'}
                    'too-many-digits' {$badStarted='2026-10-04T01:13:31.2801234990Z'}
                    'non-utc' {$badSaved='2026-10-04T01:13:32.560987699+05:00'}
                    'malformed' {$badStarted='not-an-instant'}
                }
                $badWire=New-ColdSessionWire $client $badStarted $badSaved $badPid $badSchema $badClient
                Assert-ColdMockReject {Assert-ColdControlledChanges @() @($badWire) $client $sessionUi $root $sessionFinished} 'COLD_REPORT_CONTROLLED'
                $script:sessionTimeChecks++
            }
        }
        foreach ($badTime in '2026-10-04T01:13:31.2801234990Z','2026-10-04T01:13:31.280123499+05:00',
            '2026-10-04T01:13:31.280123499','2026-10-04T01:13:31.Z','not-an-instant') {
            Assert-ColdMockReject {Get-ColdUtcTicks $badTime} 'COLD_RECEIPT_TIME'
            $script:sessionTimeChecks++
        }
    }
    $expected=[pscustomobject]@{ProcessId=731;StartedAtTicks=123450;ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe';OwnedRoot=$root}
    $transaction=[guid]::NewGuid().ToString()
    $receipt=[pscustomobject][ordered]@{schemaVersion=1;checkpoint='ACTIVE';installationRoot=$root;transactionId=$transaction;
        pid=731;startedAtTicks=123450;executablePath=$expected.ExecutablePath;hook='SAVE';phase='BOOTSTRAPPING';
        bootstrapState='ACTIVE';publishState='AFTER';operation=$null;journalSha256=('a'*64);
        bootstrapVerified=$false;bootstrapFiles=@();bootstrapTreeSha256=(Get-ColdTreeHash @())}
    Assert-ColdCheckpoint $receipt $expected $root 'ACTIVE' $transaction
    foreach ($field in 'pid','startedAtTicks','executablePath','installationRoot','transactionId','checkpoint','bootstrapState','journalSha256') {
        $bad=Copy-ColdMock $receipt
        switch ($field) {
            'pid' {$bad.pid=999} 'startedAtTicks' {$bad.startedAtTicks=123451} 'bootstrapState' {$bad.bootstrapState='COPIED'}
            default {$bad.$field='foreign'}
        }
        $code=if ($field -eq 'bootstrapState') {'COLD_CHECKPOINT_STATE'} else {'COLD_CHECKPOINT_IDENTITY'}
        Assert-ColdMockReject {Assert-ColdCheckpoint $bad $expected $root 'ACTIVE' $transaction} $code
    }
    $bad=Copy-ColdMock $receipt; $bad.PSObject.Properties.Remove('journalSha256')
    Assert-ColdMockReject {Assert-ColdCheckpoint $bad $expected $root 'ACTIVE' $transaction} 'COLD_FIELDS'
    $publish=Copy-ColdMock $receipt; $publish.checkpoint='PUBLISH_AFTER'; $publish.bootstrapState='COPYING'; $publish.publishState='BEFORE'
    Assert-ColdMockReject {Assert-ColdCheckpoint $publish $expected $root 'PUBLISH_AFTER' $transaction} 'COLD_CHECKPOINT_PUBLISH'
    $gap=Copy-ColdMock $receipt; $gap.checkpoint='JVM_GAP'; $gap.hook='BOUNDARY';
    $gap.operation=[pscustomobject]@{kind='BACKUP';path='runtime/bin/server/jvm.dll';state='BEFORE'}
    Assert-ColdCheckpoint $gap $expected $root 'JVM_GAP' $transaction
    foreach ($field in 'kind','path','state') {
        $bad=Copy-ColdMock $gap; $bad.operation.$field='foreign'
        Assert-ColdMockReject {Assert-ColdCheckpoint $bad $expected $root 'JVM_GAP' $transaction} 'COLD_CHECKPOINT_OPERATION'
    }
    foreach ($field in 'ProcessId','StartedAtTicks','ExecutablePath') {
        $actual=Copy-ColdMock $expected
        if ($field -eq 'ExecutablePath') {$actual.$field='C:\foreign\powershell.exe'} else {$actual.$field++}
        Assert-ColdMockReject {Assert-ColdProcessIdentity $expected $actual $root $expected.ExecutablePath} 'COLD_PROCESS_IDENTITY'
    }
    Assert-ColdMockReject {Assert-ColdOwnedRun $fixture ([IO.Path]::GetTempPath())} 'COLD_OWNED_RUN'
    Assert-ColdMockReject {Assert-ColdOwnedRun ([IO.Path]::GetTempPath()) ([IO.Path]::GetTempPath())} 'COLD_OWNED_RUN'
    $data=Join-Path $fixture 'pinned.json'; [IO.File]::WriteAllText($data,'{"schemaVersion":1}')
    $hash=(Get-FileHash -LiteralPath $data).Hash.ToLowerInvariant()
    [void](Read-ColdPinnedJson $data $hash)
    Assert-ColdMockReject {Read-ColdPinnedJson $data ('0'*64)} 'COLD_PIN'
    Assert-ColdMockReject {Read-ColdPinnedJson $data 'invalid'} 'COLD_PIN_FORMAT'
    $command=[pscustomobject]@{schemaVersion=1;runtimeSha256=$hash;toolArguments=@('--module-path','invalid','-m','foreign.Main');
        toolFiles=@();helperScript=$data;helperSha256=$hash;baseManifests=@();targetManifest=$data;targetManifestSha256=$hash}
    Assert-ColdMockReject {Assert-ColdCommand $command $data @($root) (Join-Path $fixture 'target')} 'COLD_TOOL_COMMAND'
    $badCommand=Copy-ColdMock $command; $badCommand.runtimeSha256=('0'*64)
    Assert-ColdMockReject {Assert-ColdCommand $badCommand $data @($root) (Join-Path $fixture 'target')} 'COLD_RUNTIME_PIN'
    $badCommand=Copy-ColdMock $command; $badCommand.schemaVersion=2
    Assert-ColdMockReject {Assert-ColdCommand $badCommand $data @($root) (Join-Path $fixture 'target')} 'COLD_COMMAND_SCHEMA'
    $cfg="[Application]`napp.mainmodule=ru.example/ru.example.Main`n"
    if ((Get-ColdMainModule $cfg) -cne 'ru.example/ru.example.Main') {throw 'Valid module fixture rejected.'}
    foreach ($line in @('app.runtime=C:\foreign\runtime',('app.runtime=$ROOTDIR/runtime' + "`n" + 'app.runtime=$ROOTDIR/runtime'))) {
        Assert-ColdMockReject {Get-ColdMainModule ($cfg+$line)} 'COLD_CFG_EXTERNAL_RUNTIME'
    }
    foreach ($line in 'app.mainjar=foreign.jar','app.classpath=$APPDIR','app.modulepath=$APPDIR') {
        Assert-ColdMockReject {Get-ColdMainModule ($cfg+$line)} 'COLD_CFG_EXTERNAL_APPLICATION'
    }
    Assert-ColdMockReject {Get-ColdMainModule ($cfg+$cfg)} 'COLD_CFG_DUPLICATE'
    # Это только ограниченное чтение данных из собственной fixture, без exe/JVM/GUI.
    $cfgFile=Join-Path $fixture 'bounded.cfg'
    $plainUtf8=[Text.UTF8Encoding]::new($false,$true)
    [IO.File]::WriteAllBytes($cfgFile,$plainUtf8.GetBytes($cfg))
    if ((Read-ColdConfig $cfgFile) -cne $cfg) {throw 'Bounded cfg bytes changed.'}
    [IO.File]::WriteAllBytes($cfgFile,$plainUtf8.GetBytes('x'*65536))
    if ((Read-ColdConfig $cfgFile).Length -ne 65536) {throw 'Exact cfg byte bound rejected.'}
    foreach ($bytes in @($plainUtf8.GetBytes('x'*65537),$plainUtf8.GetBytes('я'*32769),
        [byte[]]@(0xef,0xbb,0xbf,65),[byte[]]@(0xc3,0x28))) {
        [IO.File]::WriteAllBytes($cfgFile,$bytes)
        Assert-ColdMockReject {Read-ColdConfig $cfgFile} 'COLD_CFG_ENCODING'
    }
    # Никакие синтетические bytes не исполняются и не считаются native image.
    foreach ($path in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') {[IO.File]::WriteAllText((Join-Path $root $path),'fake')}
    Assert-ColdMockReject {Assert-ColdNativeImage $root} 'COLD_NOT_NATIVE_PE'

    # План/квитанция только для проверки отказов. Fixture PASS не доказывает запуск приложения.
    $plan=@([pscustomobject]@{key='B1/ACTIVE/fx/ascii'})
    $journalPath=Join-Path $fixture 'journal.json'; $checkpointPath=Join-Path $fixture 'checkpoint.json'
    $inventoryPath=Join-Path $fixture 'inventory.json'; $launchPath=Join-Path $fixture 'launch.json'; $userPath=Join-Path $fixture 'user.json'
    $inventory=@('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app/.jpackage.xml',
        'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg',
        'app/cashprediction-core-1.0.0.jar','app/cashprediction-ui-fx-1.0.0.jar',
        'app/cashprediction-ui-swing-1.0.0.jar','app/cashprediction-web-1.0.0.jar',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules' |
        Sort-Object {[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($_))} | ForEach-Object {
        [pscustomobject][ordered]@{path=$_;sizeBytes=1L;sha256=('a'*64);readOnly=$false}})
    Write-ColdJson $inventoryPath $inventory; $tree=Get-ColdTreeHash $inventory
    $targetInventory=Copy-ColdMock $inventory;foreach ($entry in $targetInventory) {$entry.sha256='b'*64}
    $targetTree=Get-ColdTreeHash $targetInventory
    # Сверяем bytes с текущей чистой RedirectText из frozen core, не исполняя тело helper.
    $coreSource=[IO.File]::ReadAllText((Join-Path $PSScriptRoot '../../core/src/main/java/ru/cashprediction/core/update/install/BootstrapScript.java'))
    $redirectBegin=$coreSource.IndexOf('function RedirectText(')
    $redirectEnd=$coreSource.IndexOf('function VerifyBootstrapRuntime(')
    if ($redirectBegin -lt 0 -or $redirectEnd -le $redirectBegin) {throw 'Frozen core redirect function not found.'}
    $redirectDefinition=$coreSource.Substring($redirectBegin,$redirectEnd-$redirectBegin).Replace('\\','\').Replace('\"','"')
    $coreTokens=$null; $coreErrors=$null
    $coreAst=[Management.Automation.Language.Parser]::ParseInput($redirectDefinition,[ref]$coreTokens,[ref]$coreErrors)
    if ($coreErrors.Count -or @($coreAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)).Count -ne 1) {
        throw 'Frozen core redirect parse changed.'
    }
    foreach ($command in @($coreAst.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true))) {
        if ($command.GetCommandName() -cne 'New-Object') {throw 'Core redirect mock forbids non-pure commands.'}
    }
    . ([scriptblock]::Create($redirectDefinition))
    $mockBase=[pscustomobject]@{files=$inventory;treeSha256=$tree}
    $mockTarget=[pscustomobject]@{files=$targetInventory;treeSha256=$targetTree}
    $readyRoot=Join-Path $root 'CashMemory/Updates/Ready/tree'
    foreach ($newline in @("`n","`r`n")) {
        foreach ($runtime in @('',('app.runtime=$ROOTDIR/runtime'+$newline),('app.runtime=$ROOTDIR\runtime'+$newline))) {
            $original='[Application]'+$newline+$runtime+'app.mainmodule=ru.example/ru.example.Main'+$newline+
                '[JavaOptions]'+$newline+'java-options=--module-path'+$newline+'java-options=$APPDIR'+$newline+'java-options=-Xmx512m'+$newline
            foreach ($path in 'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg') {
                foreach ($basePath in @($root,$readyRoot)) {
                    $destination=Join-Path $basePath $path
                    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
                    [IO.File]::WriteAllText($destination,$original,$plainUtf8)
                }
            }
            $built=New-ColdJournal $root $mockBase $mockTarget
            $expectedRedirect=RedirectText $original
            foreach ($cfgText in $built.bootstrap.cfgTexts) {
                if ($cfgText.text -cne $expectedRedirect) {throw 'Harness/core runtime+module redirect bytes differ.'}
                $redirectComparisons++
            }
        }
    }
    $mockJournal=[ordered]@{schemaVersion=2;installationRoot=$root;transactionId=$transaction;
        oldFiles=$inventory;oldTreeSha256=$tree;operations=@();target=[ordered]@{files=$targetInventory;treeSha256=$targetTree;releaseNumber=2;commitSha=('b'*40)}}
    Write-ColdJson $journalPath $mockJournal
    $checkpoint=Copy-ColdMock $receipt; $checkpoint.journalSha256=(Get-FileHash -LiteralPath $journalPath).Hash.ToLowerInvariant()
    $checkpoint.bootstrapVerified=$true
    $checkpoint.bootstrapFiles=@($inventory | Where-Object {Test-ColdProtectedPayload $_.path})
    $checkpoint.bootstrapTreeSha256=Get-ColdTreeHash $checkpoint.bootstrapFiles
    Write-ColdJson $checkpointPath $checkpoint; Write-ColdJson $userPath ([ordered]@{'user.md'='mock-user-data'})
    $userHash=(Get-FileHash -LiteralPath $userPath).Hash.ToLowerInvariant()
    $native=Join-Path $root 'CashPrediction.exe'
    $matrixBirth=[datetime]::UtcNow.AddMinutes(-1)
    $ui=[pscustomobject]@{pid=731;startedAtTicks=$matrixBirth.Ticks;executablePath=$native;
        modules=@((Join-Path $root 'runtime/bin/server/jvm.dll'));witness=[pscustomobject]@{kind='native-window';handle=123;title='CashPrediction - mock'};
        lease=[pscustomobject]@{schemaVersion=1;leaseId=$transaction;pid=731;startedAtEpochMillis=([DateTimeOffset]$matrixBirth).ToUnixTimeMilliseconds();client='fx';installationRoot=$root};
        commandLine=('"'+$native+'" --home "'+$root+'" --updated-from '+('b'*40));args=@('--home',$root,'--updated-from',('b'*40));observedAt=$matrixBirth.AddMilliseconds(20).ToString('o')}
    $recoveryPath=Join-Path $fixture 'recovery.json';$postPath=Join-Path $fixture 'post.json';$controlledPath=Join-Path $fixture 'controlled.json'
    Write-ColdJson $controlledPath @()
    Write-ColdJson $recoveryPath ([ordered]@{schemaVersion=1;installationRoot=$root;transactionId=$transaction;pollingLimitMillis=1000;controlledSha256=(Get-ColdObjectHash @());samples=@(
        [ordered]@{startedAt=$matrixBirth.AddMilliseconds(-90).ToString('o');finishedAt=$matrixBirth.AddMilliseconds(-80).ToString('o');journalBefore=$true;journalAfter=$true;visibleCount=0;controlledSha256=(Get-ColdObjectHash @())},
        [ordered]@{startedAt=$matrixBirth.AddMilliseconds(5).ToString('o');finishedAt=$matrixBirth.AddMilliseconds(10).ToString('o');journalBefore=$false;journalAfter=$false;visibleCount=1;controlledSha256=(Get-ColdObjectHash @())})})
    Write-ColdJson $postPath ([ordered]@{schemaVersion=1;installationRoot=$root;transactionId=$transaction;observedAt=$matrixBirth.AddMilliseconds(30).ToString('o');
        treeSha256=$tree;files=$inventory;toolExit=0;processesRemaining=0})
    $version=[pscustomobject]@{releaseNumber=1;commitSha=('a'*40);jar='cashprediction-core-1.0.0.jar';jarSha256=('a'*64)}
    Write-ColdJson $launchPath ([ordered]@{schemaVersion=1;base='B1';checkpoint='ACTIVE';client='fx';pathVariant='ascii';
        nativeExe=$native;args=@('--home',$root);startedAt=$matrixBirth.AddMilliseconds(-10).ToString('o');
        launcher=[pscustomobject]@{ProcessId=732;StartedAtTicks=$matrixBirth.AddMilliseconds(-10).Ticks;ExecutablePath=$native};uiReceipt=$ui;
        version=$version;treeSha256=$tree;actualExit=0;helperKilledAt=$matrixBirth.AddMilliseconds(-100).ToString('o');finishedAt=$matrixBirth.AddMilliseconds(50).ToString('o');
        recoveryReceipt=$recoveryPath;postCleanupReceipt=$postPath;controlledBeforeReceipt=$controlledPath;controlledAfterReceipt=$controlledPath})
    $row=[pscustomobject]@{base='B1';checkpoint='ACTIVE';client='fx';pathVariant='ascii';status='PASS';nativeExecuted=$true;
        actualExit=0;helperActualExit=-1;treeOutcome='OLD';managedSha256=$tree;baseTreeSha256=$tree;targetTreeSha256=$targetTree;
        userBeforeSha256=$userHash;userAfterSha256=$userHash;userBeforeReceipt=$userPath;userAfterReceipt=$userPath;uiReceipt=$ui;
        checkpointReceipt=$checkpointPath;journalReceipt=$journalPath;inventoryReceipt=$inventoryPath;launchReceipt=$launchPath;
        workRoot=$root;helperIdentity=$expected;transactionId=$transaction;baseReleaseNumber=1;baseCommitSha=('a'*40);targetReleaseNumber=2;targetCommitSha=('b'*40)}
    $row | Add-Member -NotePropertyName phase -NotePropertyValue 'BOOTSTRAPPING'
    $row | Add-Member -NotePropertyName bootstrapState -NotePropertyValue 'ACTIVE'
    $row | Add-Member -NotePropertyName publishState -NotePropertyValue 'AFTER'
    Assert-ColdMatrix @($row) $plan
    Assert-ColdMockReject {Assert-ColdMatrix @() $plan} 'COLD_REPORT_MISSING'
    foreach ($field in 'nativeExecuted','actualExit','helperActualExit','userAfterSha256','managedSha256','status') {
        $bad=Copy-ColdMock $row
        switch ($field) {
            'nativeExecuted' {$bad.nativeExecuted=$false} 'actualExit' {$bad.actualExit=1}
            'helperActualExit' {$bad.helperActualExit=0} 'userAfterSha256' {$bad.userAfterSha256='foreign'}
            'managedSha256' {$bad.managedSha256='foreign'} 'status' {$bad.status='PENDING'}
        }
        Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    }
    $bad=Copy-ColdMock $row; $bad.launchReceipt=Join-Path $fixture 'missing.json'
    Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_ARTIFACT_MISSING'
    $bad=Copy-ColdMock $row; $bad.pathVariant='cyrillic'
    Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    $bad=Copy-ColdMock $row; $bad.PSObject.Properties.Remove('actualExit')
    Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    $bad=Copy-ColdMock $row; $bad.helperActualExit=$null
    Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    foreach ($status in 'FAIL','SKIP','TIMEOUT') {
        $bad=Copy-ColdMock $row; $bad.status=$status
        Assert-ColdMockReject {Assert-ColdMatrix @($bad) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    }
    $launch=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $launchPath -Raw)
    $badLaunch=Copy-ColdMock $launch; $badLaunch.client='web'; Write-ColdJson $launchPath $badLaunch
    Assert-ColdMockReject {Assert-ColdMatrix @($row) $plan} 'COLD_REPORT_LAUNCH_IDENTITY'
    Write-ColdJson $launchPath $launch
    [IO.File]::AppendAllText($journalPath,"`n")
    Assert-ColdMockReject {Assert-ColdMatrix @($row) $plan} 'COLD_REPORT_JOURNAL_PIN'
    Write-ColdJson $journalPath $mockJournal
    Write-ColdJson $inventoryPath @()
    Assert-ColdMockReject {Assert-ColdMatrix @($row) $plan} 'COLD_REPORT_TREE_PIN'
    Write-ColdJson $inventoryPath $inventory
    Assert-ColdMockReject {Assert-ColdMatrix @($row,$row) $plan} 'COLD_REPORT_MISSING'
    Assert-ColdMockReject {New-ColdInstrumentedHelper 'function Guard {}'} 'COLD_HELPER_HOOK_MISSING'
    $checkpoints=@(Get-ColdCheckpoints)
    if ($checkpoints.Count -ne 22 -or @($checkpoints | Sort-Object -Unique).Count -ne 22) {throw 'Unexpected bounded checkpoint plan.'}
    $mockHelper="param([string]`$InstallationRoot)`nfunction Guard {}`nfunction Save {}`nfunction PhaseFault {}`nfunction Boundary {}`nfunction PrepareBootstrap {}`nfunction PortableRollback {}`nfunction BootstrapPayload {}`nfunction VerifyBootstrapRuntime {}`n"
    $injected=New-ColdInstrumentedHelper $mockHelper
    $injectedAst=[Management.Automation.Language.Parser]::ParseInput($injected,[ref]$tokens,[ref]$errors)
    if ($errors.Count -or @($injectedAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'ColdCheckpoint'},$true)).Count -ne 1) {throw 'Checkpoint injector structural fixture failed.'}
    $hook=@($injectedAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'ColdCheckpoint'},$true))[0].Extent.Text
    if ($hook.IndexOf('VerifyBootstrapRuntime $bootstrapDirectory') -lt 0 -or
        $hook.IndexOf('VerifyBootstrapRuntime $bootstrapDirectory') -ge $hook.IndexOf('$protectedVerified=$true') -or
        -not $hook.Contains('Where-Object {BootstrapPayload $_.path}') -or -not $hook.Contains('bootstrapTreeSha256=(InventoryDigest $protectedFiles)')) {
        throw 'Protected observation must follow actual full payload verification.'
    }
    Write-Output "Bootstrap mock fixtures: PASS ($count exact negative rejections; $redirectComparisons frozen-core redirect byte comparisons; $processReceiptChecks actual Process receipt checks; $helperDiagnosticsChecks bounded helper diagnostics checks; $cleanupRaceChecks cleanup race/primary failure checks; $observerAcquisitionChecks observer acquisition race checks; $cleanupPidTypeChecks cleanup CIM PID type checks; $sessionTimeChecks session wire/time checks; $observerHandoffChecks observer retained-exit checks; application EXE/JVM/GUI not executed; cold matrix PENDING)."
} finally {
    $resolved=Resolve-PortableSafetyPath $fixture
    if ($resolved -cne $fixture -or [IO.Path]::GetFileName($resolved) -cnotmatch '^cp-bootstrap-fixtures-[0-9a-f-]{36}$' -or
        -not [IO.Path]::GetDirectoryName($resolved).Equals((Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())),[StringComparison]::OrdinalIgnoreCase)) {throw 'Fixture cleanup ownership changed.'}
    Assert-PortableTreeHasNoLinks $resolved
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
