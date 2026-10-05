<# Планирует affected проверки без сети и сборки. Непроверенная история всегда означает full. #>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Repository = (Join-Path $PSScriptRoot '../..'),
    [string]$BaseSha,
    [string]$HeadSha = 'HEAD',
    [switch]$ForceFull,
    [string]$OutputPath,
    [string]$GithubOutput
)

# Возвращает объединение влияний; пустой доказанный diff допустим, неизвестный путь - нет.
function Get-CiImpact {
    [CmdletBinding()]
    param([AllowEmptyCollection()][AllowNull()][string[]]$Paths = @(), [switch]$ForceFull)
    $order = @('core','update-tool','ui-fx','ui-swing','web','repository-doc-audits')
    $units = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $flags = [ordered]@{compile=$false;docs=$false;unitModules='';compileModules='';ui=$false;e2e=$false;portable=$false;preflight=$false;release=$false}
    $full = [bool]$ForceFull
    $parityHarness = $false
    foreach ($raw in $Paths) {
        if ($full) { break }
        if ([string]::IsNullOrWhiteSpace($raw) -or $raw -match '[\x00-\x1f\x7f]' -or $raw -ne $raw.Trim()) { $full=$true; break }
        $path = $raw.Replace('\','/').ToLowerInvariant()
        if ($path -match '(^/|:|//|(^|/)\.{1,2}(/|$))') { $full=$true; break }
        # Только явно агентские файлы исключаются; произвольные hidden/Markdown файлы не исключаются.
        if ($path -match '^(\.claude|\.codex|\.agents)(/|$)' -or $path -match '(^|/)(agents|claude)\.md$') { continue }
        if ($path -match '(^|/)pom\.xml$' -or $path -match '^(\.mvn|\.github|dist)(/|$)') { $full=$true; break }
        # Graphify не читается продуктом; README остаётся целью ссылки обязательного AI-аудита.
        if ($path -match '^docs/ai/graphify/.+' -and $path -ne 'docs/ai/graphify/readme.md') { continue }
        # Исходники независимого аудитора компилируются/тестируются выбранным модулем, без product deps.
        # Неизвестные скрипты или конфигурация модуля не маскируются широкой docs-only веткой.
        if ($path -match '^repository-doc-audits/src/(main|test)/.+$') { $flags.docs=$true; continue }
        if ($path -match '^repository-doc-audits(/|$)') { $full=$true; break }
        # Вне выделенного dev-tool дерева исполняемые/build файлы документации не считаются прозой.
        if ($path -match '^docs/' -and $path -match '\.(ps1|psm1|psd1|cmd|bat|sh|py|js|mjs|cjs|java|xml|yml|yaml)$') { $full=$true; break }
        if ($path -match '^(docs|licenses)(/|$)' -or
            $path -match '^(readme(?:\.[^/]+)?|architecture\.md|project_requirements\.md|changelog\.md|license(?:\.[^/]+)?|notice(?:\.[^/]+)?)$') {
            $flags.docs=$true; continue
        }
        if ($path -match '^ui-parity/') {
            $parityHarness = $true
            $flags.docs=$true; $flags.ui=$true; $flags.e2e=$true; $flags.portable=$true; $flags.preflight=$true; continue
        }
        if ($path -match '^(core|update-tool|ui-fx|ui-swing|web)/src/(main|test)/.+$') {
            $module=$Matches[1]; $kind=$Matches[2]
            $flags.docs=$true
            $null=$units.Add($module)
            if ($kind -eq 'main') {
                $flags.compile=$true; $flags.portable=$true; $flags.preflight=$true; $flags.release=$true
                if ($module -eq 'core') { foreach ($unit in $order) { $null=$units.Add($unit) } }
                if ($module -ne 'update-tool') { $flags.ui=$true; $flags.e2e=$true }
            } else {
                if ($module -in 'ui-fx','ui-swing','web') { $flags.ui=$true; $flags.e2e=$true }
                if ($module -eq 'core' -and $path -match '^core/src/test/resources/(ui-golden|ui-goldens|golden|goldens|ui-scenarios|ui-json)(/|$)') {
                    $flags.ui=$true; $flags.e2e=$true; $flags.portable=$true; $flags.preflight=$true
                }
            }
            continue
        }
        $full=$true
    }
    if ($full) {
        foreach ($name in @('compile','docs','ui','e2e','portable','preflight','release')) { $flags[$name]=$true }
        foreach ($unit in $order) { $null=$units.Add($unit) }
    }
    if ($flags.docs) { $null=$units.Add('repository-doc-audits') }
    $flags.unitModules=(@($order | Where-Object { $units.Contains($_) }) -join ',')
    # Smoke использует конечный unit-каталог; реальный parity runner отдельно компилирует профиль ui-parity.
    # Все jar клиентов должны быть свежими даже при изменении только harness: это runtime-зависимости,
    # которых нет среди Maven dependencies ui-parity. Не включаем неактивный профиль в default -pl.
    $compilation = [Collections.Generic.HashSet[string]]::new($units, [StringComparer]::Ordinal)
    if ($parityHarness) {
        foreach ($module in @('core','ui-fx','ui-swing','web')) { $null=$compilation.Add($module) }
    }
    $flags.compileModules=(@($order | Where-Object { $compilation.Contains($_) }) -join ',')
    [pscustomobject]$flags
}

# Читает Git через ArgumentList и NUL diff без shell quoting и без изменения репозитория.
function Invoke-CiImpactGit {
    param([string]$Directory, [string[]]$Arguments)
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName='git'; $info.UseShellExecute=$false
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.UTF8Encoding]::new($false,$true)
    $info.ArgumentList.Add('-C'); $info.ArgumentList.Add($Directory)
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        $null=$process.Start()
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [pscustomobject]@{exitCode=$process.ExitCode;output=$stdout.GetAwaiter().GetResult();error=$stderr.GetAwaiter().GetResult()}
    } finally { $process.Dispose() }
}

# CLI не исполняется при dot-source: тесты используют ту же настоящую функцию planner.
if ($MyInvocation.InvocationName -ne '.') {
    $fallback=[bool]$ForceFull
    $paths=@()
    if (-not $fallback) {
        try {
            if ($BaseSha -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$' -or
                ($HeadSha -cne 'HEAD' -and $HeadSha -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$')) { throw 'INVALID_BASE_OR_HEAD' }
            $directory=[IO.Path]::GetFullPath($Repository)
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw 'INVALID_REPOSITORY' }
            foreach ($sha in @($BaseSha,$HeadSha)) {
                $resolved=Invoke-CiImpactGit $directory @('rev-parse','--verify',"$sha`^{commit}")
                if ($resolved.exitCode -ne 0 -or $resolved.output.Trim() -notmatch '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$') { throw 'UNUSABLE_COMMIT' }
                if ($sha -ceq 'HEAD') { $HeadSha=$resolved.output.Trim() }
            }
            $ancestor=Invoke-CiImpactGit $directory @('merge-base','--is-ancestor',$BaseSha,$HeadSha)
            if ($ancestor.exitCode -ne 0) { throw 'UNPROVEN_ANCESTOR' }
            $diff=Invoke-CiImpactGit $directory @('diff','--no-ext-diff','--no-textconv','--no-renames','--name-only','-z',$BaseSha,$HeadSha,'--')
            if ($diff.exitCode -ne 0) { throw 'DIFF_FAILED' }
            if ($diff.output.Length) {
                if (-not $diff.output.EndsWith([string][char]0,[StringComparison]::Ordinal)) { throw 'INVALID_NUL_DIFF' }
                $paths=@($diff.output.Substring(0,$diff.output.Length-1).Split([char]0))
            }
        } catch { $fallback=$true; Write-Warning "CI impact full fallback: $($_.Exception.Message)" }
    }
    $impact=Get-CiImpact -Paths $paths -ForceFull:$fallback
    if ($OutputPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath),($impact | ConvertTo-Json),[Text.UTF8Encoding]::new($false)) }
    if ($GithubOutput) {
        $lines=@($impact.PSObject.Properties | ForEach-Object {
            $value=if ($_.Value -is [bool]) { $_.Value.ToString().ToLowerInvariant() } else { [string]$_.Value }
            "$($_.Name)=$value"
        })
        [IO.File]::AppendAllText([IO.Path]::GetFullPath($GithubOutput),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    }
    $impact
}
