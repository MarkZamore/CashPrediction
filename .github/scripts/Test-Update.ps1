<#
.SYNOPSIS
Локальная репетиция frozen update-tool от двух portable баз без сборки и GUI.
.DESCRIPTION
Main передаёт готовые Java 25 test-classes/core и tool/core classpath.
Mode Rehearsal подтверждает только CLI/HTTP, Mode Signoff всегда отклоняет PENDING.
Копии и доказательства сохраняются для main. Скрипт не удаляет каталоги или реестр.
Три exe планируются для plain/кириллицы/non-ANSI; UI-фазы пока не реализованы.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Base1Dir,
    [Parameter(Mandatory)][int]$Base1Release,
    [Parameter(Mandatory)][string]$Base1Commit,
    [Parameter(Mandatory)][string]$Base2Dir,
    [Parameter(Mandatory)][int]$Base2Release,
    [Parameter(Mandatory)][string]$Base2Commit,
    [Parameter(Mandatory)][string]$TargetDir,
    [Parameter(Mandatory)][int]$TargetRelease,
    [Parameter(Mandatory)][string]$TargetCommit,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$HarnessClasspath,
    [Parameter(Mandatory)][string]$ToolClasspath,
    [string]$WorkDir = (Join-Path ([IO.Path]::GetTempPath()) 'cp-update-rehearsal'),
    [string]$EvidenceRoot = (Join-Path ([IO.Path]::GetTempPath()) 'cp-update-evidence'),
    [ValidateSet('Rehearsal','Signoff')][string]$Mode = 'Signoff',
    [ValidateRange(10,1800)][int]$TimeoutSeconds = 900
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 required for structured process arguments.' }

# Подключаем только существующие функции безопасности, не исполняя portable gate.
$tokens = $null; $parseErrors = $null
$portableAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-Portable.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Portable safety source has parse errors.' }
foreach ($name in 'Resolve-PortableSafetyPath','Test-PortablePathContains','Get-ValidatedPortablePaths',
    'Assert-PortableSourceEntries','Assert-PortableTreeHasNoLinks','Get-PortableRealRegistrySnapshot','Get-PortableCleanupPath') {
    $found = @($portableAst.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true))
    if ($found.Count -ne 1) { throw "Portable safety function missing or ambiguous: $name" }
    . ([scriptblock]::Create($found[0].Extent.Text))
}
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$profileRoot = [Environment]::GetFolderPath('UserProfile')
$sources = @($Base1Dir,$Base2Dir,$TargetDir | ForEach-Object { Resolve-PortableSafetyPath $_ })
if (@($sources | Sort-Object -Unique).Count -ne 3) { throw 'Independent B1/B2/T roots required.' }
for ($i = 0; $i -lt 3; $i++) {
    Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $sources[$i] -Force)
    Assert-PortableTreeHasNoLinks $sources[$i]
    foreach ($other in $sources) {
        if ($other -ne $sources[$i] -and (Test-PortablePathContains $sources[$i] $other)) { throw 'Source roots overlap.' }
    }
    $null = Get-ValidatedPortablePaths $sources[$i] $WorkDir $project $profileRoot
    $null = Get-ValidatedPortablePaths $sources[$i] $EvidenceRoot $project $profileRoot
}
foreach ($sha in $Base1Commit,$Base2Commit,$TargetCommit) {
    if ($sha -cnotmatch '^[0-9a-f]{40}$') { throw 'Expected lowercase commit40hex.' }
}
if ($Base1Release -le 0 -or $Base2Release -le 0 -or $Base1Release -eq $Base2Release -or
    $TargetRelease -le [Math]::Max($Base1Release,$Base2Release)) { throw 'Expected two distinct older positive releases.' }
$container = Resolve-PortableSafetyPath $WorkDir
$evidenceContainer = Resolve-PortableSafetyPath $EvidenceRoot
if ((Test-PortablePathContains $container $evidenceContainer) -or (Test-PortablePathContains $evidenceContainer $container)) { throw 'Evidence and work overlap.' }
$run = Join-Path $container ('run-' + [guid]::NewGuid().ToString())
$evidence = Join-Path $evidenceContainer ([IO.Path]::GetFileName($run))
$null = Get-PortableCleanupPath $sources[0] $run $container $project $profileRoot
$javaPath = Resolve-PortableSafetyPath $Java
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) { throw 'Local JDK java missing.' }
$beforeRegistry = Get-PortableRealRegistrySnapshot
$null = New-Item -ItemType Directory -Path $run
$null = New-Item -ItemType Directory -Path $evidence
$info = [Diagnostics.ProcessStartInfo]::new($javaPath)
$info.UseShellExecute = $false; $info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
foreach ($argument in @('-XX:-UsePerfData','--add-modules','jdk.httpserver','-cp',$HarnessClasspath,
    'ru.cashprediction.parity.update.TwoBaseRehearsal',$ToolClasspath,$run,$evidence,
    $sources[0],"$Base1Release",$Base1Commit,$sources[1],"$Base2Release",$Base2Commit,
    $sources[2],"$TargetRelease",$TargetCommit)) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::new(); $process.StartInfo = $info
$processStarted = $false
try {
    if (-not $process.Start()) { throw 'Harness did not start.' }
    $processStarted = $true
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not $process.WaitForExit(1000)) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Bounded rehearsal timeout.' }
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'harness.out.txt'), $stdout.GetAwaiter().GetResult())
    [IO.File]::WriteAllText((Join-Path $evidence 'harness.err.txt'), $stderr.GetAwaiter().GetResult())
    if ($process.ExitCode -ne 0) { throw "Rehearsal failed: exit $($process.ExitCode), evidence $evidence" }
    $result = Get-Content -LiteralPath (Join-Path $evidence 'results.json') -Raw | ConvertFrom-Json
    if ($result.schemaVersion -ne 1 -or $result.rehearsalScope -cne 'CLI_HTTP_ONLY' -or $result.portableExecuted -ne $false -or
        $result.status -cne 'PENDING' -or $result.rehearsal -cne 'PASS' -or @($result.toolCommands).Count -ne 36 -or
        @($result.toolCommands | Where-Object { $_.status -cne 'PASS' -or $_.exitCode -ne 0 }).Count) { throw 'Incomplete CLI rehearsal receipts.' }
    Write-Host "CLI/HTTP rehearsal complete; portable T17 PENDING. Evidence: $evidence"
    if ($Mode -eq 'Signoff') { throw 'S7 signoff rejected: portable lifecycle/GUI/phase execution remains PENDING.' }
} finally {
    # Удерживается собственный Process; нет поиска по имени или убийства чужого PID.
    if ($processStarted -and -not $process.HasExited) {
        $process.Kill($true)
        if (-not $process.WaitForExit(5000)) { throw 'Owned harness process tree still alive; retained work.' }
    }
    $process.Dispose()
    if ((Get-PortableRealRegistrySnapshot) -cne $beforeRegistry) { throw 'Real session registry changed.' }
    Write-Host "Retained owned rehearsal files: $run; evidence: $evidence"
}
