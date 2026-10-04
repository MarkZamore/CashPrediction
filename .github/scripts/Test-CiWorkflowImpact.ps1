<# Проверяет условия affected workflow, синтаксис pwsh-блоков и выбор успешной базы без сети/GUI. #>
#requires -Version 7.0
[CmdletBinding()]
param([string]$Repository=(Join-Path $PSScriptRoot '../..'), [string]$ReceiptPath, [switch]$BaselineOutputOnly, [switch]$EarlyPlatformOnly)
$ErrorActionPreference='Stop'
if ($BaselineOutputOnly -and $EarlyPlatformOnly) { throw 'CI_FIXTURE_SCOPE_CONFLICT' }
$checks=0
$inputPaths=@('.github/scripts/Get-CiImpact.ps1','.github/scripts/Resolve-CiBaseline.ps1','.github/scripts/Test-CiWorkflowImpact.ps1','.github/scripts/Initialize-CiDesktop.ps1','.github/scripts/CiDesktopProbe.java','.github/workflows/ci.yml','.github/workflows/release.yml')
$inputHashes=@{}
foreach ($path in $inputPaths) { $inputHashes[$path]=(Get-FileHash -LiteralPath (Join-Path $Repository $path)).Hash }
# Проверяет утверждение и считает только выполненные проверки.
function Assert-Ci([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw "CI_WORKFLOW_CONTRACT: $Label" }
    $script:checks++
}
# Запускает полный настоящий CLI в собственной копии; только Git/API заменены закрытым локальным транспортом.
function Test-CiBaselineCliOutput {
    $sourcePath=Join-Path $Repository '.github/scripts/Resolve-CiBaseline.ps1'
    $source=Get-Content -LiteralPath $sourcePath -Raw -Encoding utf8
    $taskTemp=Join-Path ([IO.Path]::GetTempPath()) ('cp-ci-baseline-output-'+[guid]::NewGuid())
    $null=New-Item -ItemType Directory -Path $taskTemp
    $encoding=[Text.UTF8Encoding]::new($false,$true)
    $currentPath=Join-Path $taskTemp 'Resolve-CiBaseline.ps1'
    [IO.File]::WriteAllText($currentPath,$source,$encoding)
    # Шов GhRetry существует лишь рядом с копией: root библиотека и процессные команды не вызываются.
    $transport=@'
function git {
    $fixtureObservation.gitCalls++
    $global:LASTEXITCODE=0
    $request=$args -join '|'
    if ($request -ceq "-C|$Repository|rev-parse|--is-shallow-repository") { return 'false' }
    if ($request -ceq "-C|$Repository|rev-parse|--verify|$('1'*40)^{commit}") { return ('1'*40) }
    if ($request -ceq "-C|$Repository|merge-base|--is-ancestor|$('1'*40)|$('2'*40)") { return }
    $fixtureObservation.unexpected++
    throw 'UNEXPECTED_FIXTURE_GIT'
}
function Invoke-Gh {
    $fixtureObservation.apiCalls++
    if (($args -join '|') -cne 'api|repos/owner/repo/actions/workflows/ci.yml/runs?branch=work&event=push&status=success&per_page=100&page=1') {
        $fixtureObservation.unexpected++
        throw 'UNEXPECTED_FIXTURE_API'
    }
    if ($fixtureCase -ceq 'fallback') { $global:LASTEXITCODE=1; return 'fixture API unavailable' }
    $global:LASTEXITCODE=0
    return ('{"workflow_runs":[{"id":101,"status":"completed","conclusion":"success","event":"push","head_branch":"work","head_sha":"'+('1'*40)+'"}]}')
}
'@
    [IO.File]::WriteAllText((Join-Path $taskTemp 'GhRetry.ps1'),$transport,$encoding)
    # Отрицательный mutant возвращает только прежнюю строку writer; selection/fallback остаются теми же.
    $writer='(?m)^\s*\[IO\.File\]::AppendAllText\(\$GithubOutput,[^\r\n]*$'
    Assert-Ci ([regex]::Matches($source,$writer).Count -eq 1) 'one actual GithubOutput AppendAllText writer'
    $oldWriter='        [IO.File]::AppendAllLines($GithubOutput, @("base_sha=$base", "force_full=$($fallback.ToString().ToLowerInvariant())"), [Text.UTF8Encoding]::new($false))'
    $oldPath=Join-Path $taskTemp 'Resolve-CiBaseline-old.ps1'
    [IO.File]::WriteAllText($oldPath,[regex]::Replace($source,$writer,[Text.RegularExpressions.MatchEvaluator]{ param($match) $oldWriter }),$encoding)
    $observations=[Collections.Generic.List[object]]::new()
    $previousExit=$global:LASTEXITCODE
    try {
        foreach ($variant in 'old','current') {
            foreach ($case in 'success','force-full','fallback') {
                $outputPath=Join-Path $taskTemp "$variant-$case.output"
                $prefix="prior=сохранено`n"
                [IO.File]::WriteAllText($outputPath,$prefix,$encoding)
                $observation=[pscustomobject]@{variant=$variant;case=$case;gitCalls=0;apiCalls=0;unexpected=0;error=$null;errorId=$null;result=$null;output=$outputPath;sha256=$null}
                $path=if ($variant -ceq 'old') { $oldPath } else { $currentPath }
                try {
                    $raw=& {
                        $fixtureCase=$case; $fixtureObservation=$observation
                        & $path -Mode CI -Repository $taskTemp -GithubRepository owner/repo -Branch work `
                            -HeadSha ('2'*40) -WorkflowFile ci.yml -GithubOutput $outputPath `
                            -ForceFull:($case -ceq 'force-full') -WarningAction SilentlyContinue
                    }
                    $observation.result=($raw -join "`n" | ConvertFrom-Json)
                } catch { $observation.error=$_.Exception.Message; $observation.errorId=$_.FullyQualifiedErrorId }
                Assert-Ci ($observation.unexpected -eq 0) "$variant/$case closed mock scope"
                $expectedApi=if ($case -ceq 'force-full') { 0 } else { 1 }
                $expectedGit=if ($case -ceq 'success') { 3 } elseif ($case -ceq 'fallback') { 1 } else { 0 }
                Assert-Ci ($observation.apiCalls -eq $expectedApi -and $observation.gitCalls -eq $expectedGit) "$variant/$case exact transport calls"
                if ($variant -ceq 'old') {
                    Assert-Ci ($observation.errorId -like '*MethodCountCouldNotFindBest*' -and $observation.error -match 'AppendAllLines') "$case reproduces old overload failure"
                    $expected=$prefix
                } else {
                    Assert-Ci ($null -eq $observation.error) "$case actual CLI completes"
                    $base=if ($case -ceq 'success') { '1'*40 } else { '' }
                    $full=$case -cne 'success'
                    Assert-Ci ($observation.result.baseSha -ceq $base -and $observation.result.forceFull -eq $full) "$case actual selection/fallback result"
                    $expected=$prefix+"base_sha=$base`nforce_full=$($full.ToString().ToLowerInvariant())`n"
                }
                $bytes=[IO.File]::ReadAllBytes($outputPath)
                Assert-Ci ([Convert]::ToHexString($bytes) -ceq [Convert]::ToHexString($encoding.GetBytes($expected))) "$variant/$case exact lowercase LF append, preserved prefix, no BOM"
                $observation.sha256=(Get-FileHash -LiteralPath $outputPath).Hash
                $observations.Add($observation)
            }
        }
        Assert-Ci ((Get-FileHash -LiteralPath $sourcePath).Hash -ceq $inputHashes['.github/scripts/Resolve-CiBaseline.ps1']) 'actual baseline source stable during output fixture'
        return [ordered]@{temp=$taskTemp;cases=@($observations.ToArray());mockScope='full CLI copy; only local Git/GhRetry transport; exact call counts';network=$false;maven=$false;gui=$false}
    } finally { $global:LASTEXITCODE=$previousExit }
}
$baselineOutput=if (-not $EarlyPlatformOnly) { Test-CiBaselineCliOutput } else { $null }
if ($BaselineOutputOnly) {
    $outputReceipt=[ordered]@{checks=$checks;failed=0;baselineOutput=$baselineOutput;inputs=@($inputPaths | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetFullPath((Join-Path $Repository $_));sha256=$inputHashes[$_]}
    })}
    if ($ReceiptPath) { [IO.File]::WriteAllText($ReceiptPath,($outputReceipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
    Write-Host "RESULT: baseline-output checks=$checks failed=0; old overload reproduced, three actual CLI cases PASS; no Maven/GUI/network."
    return
}
# Извлекает шаги workflow с неизменными границами, не выполняя их run-команды.
function Read-CiSteps([string]$Path) {
    $source=Get-Content -LiteralPath $Path -Raw -Encoding utf8
    @([regex]::Matches($source,'(?ms)^      - name: (?<name>[^\r\n]+)\r?\n(?<body>.*?)(?=^      - name: |\z)') | ForEach-Object {
        [pscustomobject]@{name=$_.Groups['name'].Value;body=$_.Groups['body'].Value}
    })
}
# Проверяет настоящий порядок CI-кода с локальным mvn seam: никаких процессов Maven или product fixtures.
function Test-CiEarlyPlatformPreflight {
    $steps=Read-CiSteps (Join-Path $Repository '.github/workflows/ci.yml')
    $body=@($steps | Where-Object name -CEQ 'Build and test')[0].body
    $run=[regex]::Match($body,'(?ms)^        run: \|\r?\n(?<code>.*)').Groups['code'].Value -replace '(?m)^          ',''
    $start=$run.IndexOf('$modules =',[StringComparison]::Ordinal)
    Assert-Ci ($start -ge 0) 'actual module planning statements found'
    $code=$run.Substring($start).Replace('$env:GITHUB_WORKSPACE','$fixtureRepository')
    $selector='-Dtest=StartupDataBoundaryIntegrationTest,UpdateLifecycleOutcomeContractTest,ReconnectCredentialsTest'
    $observations=[Collections.Generic.List[object]]::new()
    $previousExit=$global:LASTEXITCODE
    try {
        foreach ($case in @(
            @{modules='core';failure='';expected=3},
            @{modules='core,update-tool,ui-fx,ui-swing,web,repository-doc-audits';failure='';expected=3},
            @{modules='repository-doc-audits';failure='';expected=1},
            @{modules='ui-swing,repository-doc-audits';failure='';expected=2},
            @{modules='';failure='';expected=0},
            @{modules='core';failure='focused';expected=2},
            @{modules='core';failure='compile';expected=1})) {
            $modules=$case.modules
            $actual=$code.Replace('${{ steps.impact.outputs.unitModules }}',$modules)
            $actual=[regex]::Replace($actual,'\$\{\{ steps\.impact\.outputs\.(compile|ui|e2e|portable) \}\}','false')
            $tokens=$null; $errors=$null
            $null=[Management.Automation.Language.Parser]::ParseInput($actual,[ref]$tokens,[ref]$errors)
            Assert-Ci ($errors.Count -eq 0) "early/$modules actual PS syntax"
            $calls=[Collections.Generic.List[object]]::new()
            $failure=$case.failure; $caught=$null
            & {
                $fixtureRepository=$Repository
                # Функция только в дочернем scope; записывает argv и выдаёт заданный exit без запуска mvn.
                function mvn {
                    $calls.Add([string[]]$args)
                    $global:LASTEXITCODE=if (($failure -ceq 'focused' -and $args -contains $selector) -or
                        ($failure -ceq 'compile' -and $args -contains 'install')) { 1 } else { 0 }
                }
                try { & ([scriptblock]::Create($actual)) }
                catch { Set-Variable -Name caught -Value $_.Exception.Message -Scope 1 }
            }
            Assert-Ci ($calls.Count -eq $case.expected) "early/$modules/$failure exact invocation count"
            $core=($modules -split ',') -ccontains 'core'
            $focused=@($calls.ToArray() | Where-Object { $_ -contains $selector })
            Assert-Ci ($focused.Count -eq $(if ($core -and $failure -cne 'compile') { 1 } else { 0 })) "early/$modules exact core token gate"
            if ($core -and $failure -cne 'compile') {
                Assert-Ci (($calls[0] -join '|') -ceq '-B|-ntp|-DskipTests|install' -and
                    ($calls[1] -join '|') -ceq ('-B|-ntp|-pl|core|'+$selector+'|-Dsurefire.reportsDirectory='+(Join-Path $Repository 'core/target/early-platform-preflight-reports')+'|test')) 'compile before exact three-class preflight, separate reports'
            }
            if ($failure) {
                $expectedError=if ($failure -ceq 'focused') { 'Early platform preflight failed.' } else { 'Compilation failed.' }
                Assert-Ci ($caught -ceq $expectedError) "$failure failure blocks later full units"
            } else {
                Assert-Ci ($null -eq $caught) "early/$modules success"
                if ($modules) { Assert-Ci (($calls[-1] -join '|') -ceq "-B|-ntp|-pl|$modules|test") 'full affected unit command remains unchanged, no exclusions' }
            }
            $observations.Add([ordered]@{modules=$modules;failure=$failure;calls=@($calls.ToArray());error=$caught})
        }
        foreach ($name in 'Actual UI gates (S4, blocking)','Actual recovery gates (S4, blocking)','Complete UI and E2E profile lifecycles','Verify portable build') {
            Assert-Ci (@($steps | Where-Object name -CEQ $name).Count -eq 1) "early retains mandatory $name"
        }
        $sanitize=@($steps | Where-Object name -CEQ 'Sanitize default test reports')[0].body
        Assert-Ci ($sanitize.Contains("'early-platform-preflight-reports'") -and $sanitize.Contains('Protect-GateText')) 'early reports preserved through same sanitizer and upload'
        return @($observations.ToArray())
    } finally { $global:LASTEXITCODE=$previousExit }
}
$earlyPlatform=@(Test-CiEarlyPlatformPreflight)
if ($EarlyPlatformOnly) {
    $earlyReceipt=[ordered]@{checks=$checks;failed=0;earlyPlatform=$earlyPlatform;network=$false;maven=$false;gui=$false;inputs=@($inputPaths | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetFullPath((Join-Path $Repository $_));sha256=$inputHashes[$_]}
    })}
    if ($ReceiptPath) { [IO.File]::WriteAllText($ReceiptPath,($earlyReceipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
    Write-Host "RESULT: early-platform checks=$checks failed=0; only local mvn argv seam, no Maven/GUI/network."
    return
}
foreach ($workflow in 'ci','release') {
    $steps=Read-CiSteps (Join-Path $Repository ".github/workflows/$workflow.yml")
    Assert-Ci ($steps.Count -gt 10) "$workflow step inventory"
    foreach ($step in $steps) {
        $run=[regex]::Match($step.body,'(?ms)^        run: \|\r?\n(?<code>.*)')
        if (-not $run.Success) { continue }
        $code=$run.Groups['code'].Value -replace '(?m)^          ',''
        # Подстановка только expressions GitHub: PowerShell не должен разбирать синтаксис Actions.
        $code=[regex]::Replace($code,'\$\{\{.*?\}\}','fixture')
        $tokens=$null; $errors=$null
        $null=[Management.Automation.Language.Parser]::ParseInput($code,[ref]$tokens,[ref]$errors)
        Assert-Ci ($errors.Count -eq 0) "$workflow/$($step.name) PowerShell syntax: $($errors.Message -join '; ')"
    }
    $plan=@($steps | Where-Object name -eq 'Plan affected checks')
    Assert-Ci ($plan.Count -eq 1 -and $plan[0].body.Contains('Test-CiImpact.ps1')) "$workflow planner fixtures wired"
    $lifecycle=@($steps | Where-Object name -eq 'Complete UI and E2E profile lifecycles')[0]
    Assert-Ci ($lifecycle.body.Contains('-pl ui-parity verify')) "$workflow no duplicate full reactor lifecycle"
    foreach ($name in 'Actual UI gates (S4, blocking)','Actual recovery gates (S4, blocking)','Verify portable build') {
        $step=@($steps | Where-Object name -eq $name)[0]
        Assert-Ci ($null -ne $step -and $step.body -match '(?m)^        if: steps\.impact\.outputs\.') "$workflow/$name conditional but retained"
    }
    if ($workflow -eq 'release') {
        foreach ($name in 'Prepare','Prepare S7 update payloads','S7 release acceptance gate',
            'Verify release Git and embedded AppInfo','Publish latest release') {
            $step=@($steps | Where-Object name -eq $name)[0]
            Assert-Ci ($null -ne $step -and $step.body.Contains("if: steps.impact.outputs.release == 'true'")) "release/$name no docs-only publication"
        }
        $acceptance=@($steps | Where-Object name -eq 'S7 release acceptance gate')[0]
        Assert-Ci ($acceptance.body.Contains('S7_RELEASE_MATRIX_NOT_APPROVED')) 'S7 acceptance not bypassed'
    }
}

. (Join-Path $PSScriptRoot 'Resolve-CiBaseline.ps1')
# Подменяется только внешнее наблюдение ancestry: selection/filter остаются настоящими.
function Test-CiBaselineAncestor([string]$Directory,[string]$Candidate,[string]$Head) {
    $Candidate -ceq $script:goodSha
}
# Полностью локальный ответ API. Непредвиденный дополнительный запрос ломает fixture.
function Invoke-Gh {
    if ($script:replies.Count -eq 0) { throw 'UNEXPECTED_API_CALL' }
    $global:LASTEXITCODE=0
    $script:replies.Dequeue()
}
$script:goodSha='1'*40
$head='2'*40
# Формирует ответ API с явными статусами, не используя сеть и не создавая Git commits.
function New-CiRun([string]$Status,[string]$Conclusion,[string]$Event='push',[string]$Branch='work',[string]$Sha=$script:goodSha) {
    @{id=101;status=$Status;conclusion=$Conclusion;event=$Event;head_branch=$Branch;head_sha=$Sha}
}
foreach ($bad in @(
    (New-CiRun 'completed' 'failure'),(New-CiRun 'completed' 'cancelled'),
    (New-CiRun 'in_progress' 'success'),(New-CiRun 'completed' 'success' 'pull_request'),
    (New-CiRun 'completed' 'success' 'push' 'other'),
    (New-CiRun 'completed' 'success' 'push' 'work' $head))) {
    $script:replies=[Collections.Generic.Queue[string]]::new()
    $script:replies.Enqueue((@{workflow_runs=@($bad,(New-CiRun 'completed' 'success'))} | ConvertTo-Json -Depth 6 -Compress))
    Assert-Ci ((Find-CiSuccessfulBaseline 'unused' 'owner/repo' 'work' $head 'ci.yml') -ceq $script:goodSha) 'reject failed/cancelled/wrong branch/event/current base'
    Assert-Ci ($script:replies.Count -eq 0) 'exact API count'
}
$script:replies=[Collections.Generic.Queue[string]]::new()
$script:replies.Enqueue((@{workflow_runs=@((New-CiRun 'completed' 'failure'))} | ConvertTo-Json -Depth 6 -Compress))
Assert-Ci ([string]::IsNullOrEmpty((Find-CiSuccessfulBaseline 'unused' 'owner/repo' 'work' $head 'ci.yml'))) 'no successful base means full checks'
# Дополнительный audit продолжает после найденного дефекта, но завершает suite ненулевым exit.
$findings=[Collections.Generic.List[string]]::new()
function Assert-CiAudit([bool]$Condition,[string]$Label) {
    $script:checks++
    if (-not $Condition) { $script:findings.Add($Label); Write-Host "FAIL: $Label" }
}
# Интерпретирует только закрытую грамматику impact if, не выполняя workflow run-команды.
function Test-CiStepEnabled($Step,$Impact) {
    $condition=[regex]::Match($Step.body,'(?m)^        if: (?<if>[^\r\n]+)')
    if (-not $condition.Success) { return $true }
    $text=$condition.Groups['if'].Value
    $text=[regex]::Replace($text,'steps\.impact\.outputs\.(\w+)',{
        param($match)
        $property=$Impact.PSObject.Properties[$match.Groups[1].Value]
        if ($null -eq $property) { throw 'FIXTURE_UNKNOWN_OUTPUT' }
        $value=if ($property.Value -is [bool]) { $property.Value.ToString().ToLowerInvariant() } else { [string]$property.Value }
        if ($value -cnotmatch '^[a-z0-9,-]*$') { throw 'FIXTURE_OUTPUT_GRAMMAR' }
        "'$value'"
    })
    if ($text -cnotmatch "^[a-z0-9,' ()=!&|-]*$") { throw 'FIXTURE_IF_GRAMMAR' }
    $text=$text.Replace('==','-ceq').Replace('!=','-cne').Replace('&&','-and').Replace('||','-or')
    return [bool](& ([scriptblock]::Create($text)))
}
# Находит один требуемый шаг; потеря шага не становится ложным successful skip.
function Get-CiStep($Steps,[string]$Name) {
    $matching=@($Steps | Where-Object name -CEQ $Name)
    if ($matching.Count -ne 1) { throw "FIXTURE_STEP_$Name" }
    return $matching[0]
}
# Проверяет весь фактический release gate набор для отдельно заданных impact outputs.
function Test-CiReleaseAcceptance($Steps,$Impact) {
    foreach ($name in 'Prepare','Build and test','Actual UI gates (S4, blocking)',
        'Actual recovery gates (S4, blocking)','Complete UI and E2E profile lifecycles',
        'Verify portable build','Prepare S7 update payloads','S7 release acceptance gate',
        'Verify release Git and embedded AppInfo','Publish latest release') {
        if (-not (Test-CiStepEnabled (Get-CiStep $Steps $name) $Impact)) { return $false }
    }
    return $true
}
# Исполняет только настоящий approval comparison/throw, с локальной SHA вместо env/expression.
function Test-CiApprovalRefusal([string]$Body,[string]$Approved) {
    $run=[regex]::Match($Body,'(?ms)^        run: \|\r?\n(?<code>.*)')
    if (-not $run.Success) { return $false }
    $code=($run.Groups['code'].Value -replace '(?m)^          ','').Replace('$env:S7_APPROVED_SHA','$Approved')
    $code=[regex]::Replace($code,'\$\{\{\s*github.sha\s*\}\}',('2'*40))
    try { & ([scriptblock]::Create($code)); return $false }
    catch { return $_.Exception.Message -ceq 'S7_RELEASE_MATRIX_NOT_APPROVED' }
}
. (Join-Path $PSScriptRoot 'Get-CiImpact.ps1')
$ciSteps=Read-CiSteps (Join-Path $Repository '.github/workflows/ci.yml')
$releaseSteps=Read-CiSteps (Join-Path $Repository '.github/workflows/release.yml')
# Обязательные статические связи; desktop/helper/Java probe здесь не исполняются.
foreach ($workflow in 'ci','release') {
    $steps=if ($workflow -eq 'ci') { $ciSteps } else { $releaseSteps }
    $desktop=Get-CiStep $steps 'Prepare interactive desktop'
    $condition="steps.impact.outputs.ui == 'true' || steps.impact.outputs.e2e == 'true' || steps.impact.outputs.portable == 'true'"
    if ($workflow -eq 'release') { $condition="steps.impact.outputs.release == 'true' || "+$condition }
    Assert-CiAudit ($desktop.body.Contains("        if: $condition")) "$workflow desktop exact affected condition"
    Assert-CiAudit ($desktop.body -match '(?m)^          & \.github/scripts/Initialize-CiDesktop\.ps1\s*$' -and
        $desktop.body -notmatch 'continue-on-error: true|ValidateInteropOnly') "$workflow actual blocking helper invocation"
    $names=@($steps.name)
    Assert-CiAudit ([Array]::IndexOf($names,'Prepare interactive desktop') -lt [Array]::IndexOf($names,'Build and test')) "$workflow desktop before compilation"
}
$desktopSource=Get-Content -LiteralPath (Join-Path $Repository '.github/scripts/Initialize-CiDesktop.ps1') -Raw
# Воспроизводим прежний дефект binder без обращения к Win32 или рабочему столу.
Add-Type 'public static class CiDefaultDeviceArgumentProbe { public static bool IsNull(string device) { return device == null; } }'
Assert-CiAudit (-not [CiDefaultDeviceArgumentProbe]::IsNull($null)) 'negative control: PowerShell null becomes empty string for typed device'
Assert-CiAudit ([CiDefaultDeviceArgumentProbe]::IsNull([NullString]::Value)) 'actual NullString preserves null pointer argument through binder'
Assert-CiAudit ([regex]::Matches($desktopSource, '\[CashPrediction\.Ci\.Desktop\]::(?:EnumDisplaySettings|ChangeDisplaySettingsEx)\(\[NullString\]::Value,').Count -eq 5 -and
    $desktopSource -notmatch '::(?:EnumDisplaySettings|ChangeDisplaySettingsEx)\(\$null,') 'all five actual native device calls use true default-device NULL'
Assert-CiAudit ($desktopSource.Contains("& java (Join-Path `$PSScriptRoot 'CiDesktopProbe.java')") -and
    $desktopSource.Contains("if (`$LASTEXITCODE -ne 0) { throw 'JDK logical desktop qualification failed before compilation.' }")) 'actual source-launch probe fail blocks compilation'
Assert-CiAudit ($desktopSource.IndexOf('if ($ValidateInteropOnly)') -lt $desktopSource.IndexOf('[CashPrediction.Ci.Desktop]::EnumDisplaySettings') -and
    $desktopSource.IndexOf('if (-not $IsWindows -or $env:GITHUB_ACTIONS') -lt $desktopSource.IndexOf('[CashPrediction.Ci.Desktop]::EnumDisplaySettings')) 'validation and outside-Actions guard precede native calls'
# Свежие дочерние pwsh проверяют настоящий ABI и отказ без Actions; переменные родителя не меняются.
foreach ($validateOnly in $true,$false) {
    $info=[Diagnostics.ProcessStartInfo]::new(); $info.FileName=(Get-Command pwsh -ErrorAction Stop).Source
    $info.UseShellExecute=$false; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Environment['GITHUB_ACTIONS']='false'
    foreach ($arg in @('-NoProfile','-File',(Join-Path $Repository '.github/scripts/Initialize-CiDesktop.ps1'))) { $info.ArgumentList.Add($arg) }
    if ($validateOnly) { $info.ArgumentList.Add('-ValidateInteropOnly') }
    $child=[Diagnostics.Process]::new(); $child.StartInfo=$info; $childStarted=$false
    try {
        $childStarted=$child.Start(); $stdout=$child.StandardOutput.ReadToEndAsync(); $stderr=$child.StandardError.ReadToEndAsync()
        if (-not $child.WaitForExit(20000)) { throw 'DESKTOP_ABI_FIXTURE_TIMEOUT' }
        $out=$stdout.GetAwaiter().GetResult(); $err=$stderr.GetAwaiter().GetResult()
        if ($validateOnly) {
            Assert-CiAudit ($child.ExitCode -eq 0 -and $out.Contains('DEVMODEW=220; no native calls')) 'actual child ValidateInteropOnly ABI PASS, not desktop acceptance'
        } else {
            Assert-CiAudit ($child.ExitCode -ne 0 -and $err.Contains('Desktop provisioning is restricted to an ephemeral GitHub Actions Windows runner.')) 'actual child rejects outside Actions before native calls'
        }
    } finally {
        if ($childStarted -and -not $child.HasExited) { $child.Kill($true); if (-not $child.WaitForExit(5000)) { throw 'DESKTOP_ABI_FIXTURE_CLEANUP_TIMEOUT' } }
        $child.Dispose()
    }
}
$docs=Get-CiImpact -Paths @('docs/design/design.md')
foreach ($name in 'Prepare','Build and test','Prepare S7 update payloads','S7 release acceptance gate',
    'Verify release Git and embedded AppInfo','Publish latest release') {
    Assert-CiAudit (-not (Test-CiStepEnabled (Get-CiStep $releaseSteps $name) $docs)) "docs-only does not enable $name"
}
Assert-CiAudit (Test-CiStepEnabled (Get-CiStep $releaseSteps 'Non-release affected tests') $docs) 'docs-only still runs docs audit'
foreach ($path in 'core/src/test/resources/ui-json/scenarios/read.json','core/src/test/resources/ui-scenarios/read.json','ui-parity/src/test/java/ChangedTest.java') {
    $impact=Get-CiImpact -Paths @($path)
    foreach ($steps in @($ciSteps,$releaseSteps)) {
        foreach ($name in 'Actual UI gates (S4, blocking)','Actual recovery gates (S4, blocking)','Verify portable build') {
            Assert-CiAudit (Test-CiStepEnabled (Get-CiStep $steps $name) $impact) "$path retains $name"
        }
    }
    Assert-CiAudit (-not $impact.release) "$path test-only no publication"
    Assert-CiAudit (Test-CiStepEnabled (Get-CiStep $releaseSteps 'Non-release portable test image') $impact) "$path fresh non-release portable build"
}
foreach ($client in 'ui-fx','ui-swing','web') {
    $impact=Get-CiImpact -Paths @("$client/src/test/java/ChangedTest.java")
    foreach ($steps in @($ciSteps,$releaseSteps)) {
        foreach ($name in 'Actual UI gates (S4, blocking)','Actual recovery gates (S4, blocking)') {
            Assert-CiAudit (Test-CiStepEnabled (Get-CiStep $steps $name) $impact) "$client test-only retains $name"
        }
    }
    Assert-CiAudit (-not $impact.release) "$client tests-only does not publish"
}
$toolImpact=Get-CiImpact -Paths @('update-tool/src/main/java/Changed.java')
Assert-CiAudit ($toolImpact.release -and -not $toolImpact.ui -and -not $toolImpact.e2e) 'tool-only actual release adversarial flags'
Assert-CiAudit (Test-CiReleaseAcceptance $releaseSteps $toolImpact) 'every actual release forces full acceptance even tool-only change'
$build=(Get-CiStep $releaseSteps 'Build and test').body
Assert-CiAudit ($build.Contains('-Dapp.release=$env:RELEASE_NUMBER') -and $build.Contains('-Dapp.commit=${{ github.sha }}') -and
    $build.Contains('& mvn @common install') -and $build.Contains('& mvn @common -Pdist -pl dist package')) 'fresh release AppInfo passed to both install and portable build'
$approval=(Get-CiStep $releaseSteps 'S7 release acceptance gate').body
Assert-CiAudit (Test-CiApprovalRefusal $approval ('1'*40)) 'mismatched approved SHA hard fails actual approval code'
Assert-CiAudit (Test-CiApprovalRefusal $approval '') 'missing approved SHA hard fails actual approval code'
$names=@($releaseSteps.name)
Assert-CiAudit ([Array]::IndexOf($names,'S7 release acceptance gate') -lt [Array]::IndexOf($names,'Verify release Git and embedded AppInfo') -and
    [Array]::IndexOf($names,'Verify release Git and embedded AppInfo') -lt [Array]::IndexOf($names,'Publish latest release')) 'approval and AppInfo identity precede publication'
Assert-CiAudit ((Get-CiStep $releaseSteps 'Publish latest release').body.Contains('-ApprovedCommitSha $env:S7_APPROVED_SHA')) 'publisher receives exact approved SHA'
# Отрицательные in-memory mutants доказывают, что fixtures не довольствуются наличием имени/marker.
foreach ($gate in 'Actual UI gates (S4, blocking)','Actual recovery gates (S4, blocking)','Verify portable build') {
    $mutants=@($releaseSteps | ForEach-Object { [pscustomobject]@{name=$_.name;body=$_.body} })
    $mutant=Get-CiStep $mutants $gate
    if ($gate -eq 'Verify portable build') {
        # Tool impact уже portable=true: удаление release OR здесь не отключает gate.
        $mutant.body=$mutant.body -replace '(?m)^        if: [^\r\n]+',"        if: 'false' == 'true'"
    } else {
        $mutant.body=$mutant.body -replace "steps.impact.outputs.release == 'true' \|\| ",''
    }
    Assert-CiAudit (-not (Test-CiReleaseAcceptance $mutants $toolImpact)) "negative mutant catches release bypass of $gate"
}
$approvalMutant=$approval.Replace("if (`$env:S7_APPROVED_SHA -cne '`${{ github.sha }}')",'if ($false)')
Assert-CiAudit (-not (Test-CiApprovalRefusal $approvalMutant ('1'*40))) 'negative mutant catches disabled SHA check with marker retained'

# Шов CLI: реальные selection и fallback statements, только read-only Git/API транспорт подменён.
$baselineSource=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Resolve-CiBaseline.ps1') -Raw
$tokens=$null; $errors=$null
$baselineAst=[Management.Automation.Language.Parser]::ParseInput($baselineSource,[ref]$tokens,[ref]$errors)
$ancestorAst=@($baselineAst.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Test-CiBaselineAncestor' })[0]
$script:actualAncestor=[scriptblock]::Create($ancestorAst.Body.Extent.Text.Trim().Substring(1,$ancestorAst.Body.Extent.Text.Trim().Length-2))
# Дальше исполняется настоящий ancestry predicate, а Git остаётся детерминированным read-only seam.
function Test-CiBaselineAncestor([string]$Directory,[string]$Candidate,[string]$Head) {
    & $script:actualAncestor $Directory $Candidate $Head
}
$cli=@($baselineAst.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.IfStatementAst] })[-1].Clauses[0].Item2.Extent.Text
$cli=$cli.Substring(1,$cli.Length-2)
# Dot-source GhRetry заменяется transport seam: настоящая библиотека не должна перезаписать fixture Invoke-Gh.
$cli=$cli.Replace('. (Join-Path $PSScriptRoot ''GhRetry.ps1'')','# fixture Invoke-Gh already injected')
$script:apiCalls=[Collections.Generic.List[string]]::new()
$script:apiFailure=$false; $script:tagMissing=$false; $script:shallow=$false
function git {
    $global:LASTEXITCODE=0
    if ($args -contains '--is-shallow-repository') { return $script:shallow.ToString().ToLowerInvariant() }
    if ($args -contains '--count') { return '8' }
    if ($args -contains '--is-ancestor') {
        if ($args[-2] -cne $script:goodSha) { $global:LASTEXITCODE=1 }
        return
    }
    if ($args -contains '--verify' -and $args[-1] -cne 'refs/tags/latest^{commit}') {
        return ([string]$args[-1]).Replace('^{commit}','')
    }
    if ($script:tagMissing) { $global:LASTEXITCODE=1; return '' }
    return $script:localTagSha
}
function Invoke-Gh {
    $endpoint=[string]$args[1]; $script:apiCalls.Add($endpoint)
    $global:LASTEXITCODE=0
    if ($script:apiFailure) { $global:LASTEXITCODE=1; return 'fixture API unavailable' }
    if ($endpoint -match '/actions/workflows/') { return $script:runsJson }
    if ($endpoint -match '/releases/tags/latest$') { return $script:releaseJson }
    if ($endpoint -match '/commits/latest$') { return $script:tagJson }
    if ($endpoint -match '/releases/assets/22(?:\?.*)?$|release\.json$') {
        $script:pointerAcceptHeader=($args -contains '-H' -and $args -contains 'Accept: application/octet-stream')
        if ($script:metadataFailure) { $global:LASTEXITCODE=1; return 'fixture pointer unavailable' }
        return $script:metadataJson
    }
    throw "UNEXPECTED_API_ENDPOINT: $endpoint"
}
# Метаданные assets отдельно от tag API: согласованный tag не доказывает опубликованную identity.
function Reset-CiPublishedFixture {
    $script:apiFailure=$false; $script:tagMissing=$false; $script:shallow=$false
    $script:metadataFailure=$false
    $script:pointerAcceptHeader=$false
    $script:localTagSha=$script:goodSha
    $script:published=[ordered]@{draft=$false;prerelease=$false;tag_name='latest';assets=@(
        @{id=21;name='CashPrediction-portable.zip';size=100;url='https://api.github.com/repos/owner/repo/releases/assets/21'},
        @{id=22;name='release.json';size=300;url='https://api.github.com/repos/owner/repo/releases/assets/22'})}
    $script:releaseJson=$script:published | ConvertTo-Json -Depth 8 -Compress
    $script:tagJson=@{sha=$script:goodSha} | ConvertTo-Json -Compress
    $script:metadataJson=@{schemaVersion=1;releaseNumber=8;commitSha=$script:goodSha;publishedAtUtc='2026-10-04T00:00:00Z';assetName='CashPrediction-portable.zip';sizeBytes=100;sha256=('a'*64);note='fixture'} | ConvertTo-Json -Compress
    $script:runsJson=@{workflow_runs=@((New-CiRun 'completed' 'success'))} | ConvertTo-Json -Depth 6 -Compress
    $script:apiCalls.Clear()
}
# Исполняет actual CLI fallback без сети, Git writes или исполнения workflow commands.
function Invoke-CiBaselineFixture([string]$FixtureMode) {
    $Mode=$FixtureMode; $ForceFull=$false; $Repository='fixture-unused'; $GithubRepository='owner/repo'
    $Branch='work'; $HeadSha='2'*40; $WorkflowFile='ci.yml'; $GithubOutput=$null
    $result=& ([scriptblock]::Create($script:cli))
    return ($result -join "`n" | ConvertFrom-Json)
}
Reset-CiPublishedFixture
Assert-CiAudit ((Invoke-CiBaselineFixture 'Release').baseSha -ceq $script:goodSha) 'valid published candidate accepted'
foreach ($case in 'network','malformed-json','schema-missing-runs','no-success','shallow') {
    Reset-CiPublishedFixture
    switch ($case) {
        'network' { $script:apiFailure=$true }
        'malformed-json' { $script:runsJson='{' }
        'schema-missing-runs' { $script:runsJson='{}' }
        'no-success' { $script:runsJson='{"workflow_runs":[]}' }
        'shallow' { $script:shallow=$true }
    }
    $result=Invoke-CiBaselineFixture 'CI'
    Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "CI $case actual CLI falls back full"
}
Reset-CiPublishedFixture
$script:runsJson=@{workflow_runs=@((New-CiRun 'completed' 'success' 'push' 'work' ('3'*40)),(New-CiRun 'completed' 'success'))} | ConvertTo-Json -Depth 6 -Compress
Assert-CiAudit ((Invoke-CiBaselineFixture 'CI').baseSha -ceq $script:goodSha) 'non-ancestor success skipped for successful ancestor'
Reset-CiPublishedFixture
$script:runsJson=@{workflow_runs=(New-CiRun 'completed' 'success')} | ConvertTo-Json -Depth 6 -Compress
$result=Invoke-CiBaselineFixture 'CI'
Assert-CiAudit ($result.forceFull -and -not $result.baseSha) 'CI malformed workflow_runs object must not authorize selective skip'
foreach ($case in 'network','malformed-json','wrong-tag','missing-asset','tag-missing','tag-changed','non-ancestor') {
    Reset-CiPublishedFixture
    switch ($case) {
        'network' { $script:apiFailure=$true }
        'malformed-json' { $script:releaseJson='{' }
        'wrong-tag' { $script:published.tag_name='old' }
        'missing-asset' { $script:published.assets=@($script:published.assets[0]) }
        'tag-missing' { $script:tagMissing=$true }
        'tag-changed' { $script:tagJson=@{sha=('3'*40)} | ConvertTo-Json -Compress }
        'non-ancestor' { $script:localTagSha='3'*40; $script:tagJson=@{sha=$script:localTagSha} | ConvertTo-Json -Compress }
    }
    if ($case -notin 'malformed-json','network') { $script:releaseJson=$script:published | ConvertTo-Json -Depth 8 -Compress }
    $result=Invoke-CiBaselineFixture 'Release'
    Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "Release $case actual CLI falls back full"
}
foreach ($case in 'pointer-commit-mismatch','pointer-schema-invalid','duplicate-assets','string-draft-schema') {
    Reset-CiPublishedFixture
    switch ($case) {
        'pointer-commit-mismatch' { $script:metadataJson=$script:metadataJson.Replace($script:goodSha,('3'*40)) }
        'pointer-schema-invalid' { $script:metadataJson='{"schemaVersion":99}' }
        'duplicate-assets' { $script:published.assets+=@($script:published.assets[1]) }
        'string-draft-schema' { $script:published.draft='false' }
    }
    $script:releaseJson=$script:published | ConvertTo-Json -Depth 8 -Compress
    $result=Invoke-CiBaselineFixture 'Release'
    Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "Release $case must not authorize selective skip"
}
# Положительные и отрицательные типы JSON: array comparisons PowerShell не равны scalar comparisons.
$script:baselineObservations=[Collections.Generic.List[object]]::new()
# Raw JSON сохраняет различие object/array до CLI parsing, без предварительной нормализации fixture.
foreach ($case in 'CI-valid-array','CI-runs-object','CI-root-array','Release-root-array','Release-tag-root-array','Release-metadata-root-array','Release-assets-object') {
    Reset-CiPublishedFixture
    $mode='Release'
    switch ($case) {
        'CI-valid-array' { $mode='CI'; $script:runsJson='{"workflow_runs":[{"id":101,"status":"completed","conclusion":"success","event":"push","head_branch":"work","head_sha":"' + $script:goodSha + '"}]}' }
        'CI-runs-object' { $mode='CI'; $script:runsJson='{"workflow_runs":{"id":101,"status":"completed","conclusion":"success","event":"push","head_branch":"work","head_sha":"' + $script:goodSha + '"}}' }
        'CI-root-array' { $mode='CI'; $script:runsJson='['+$script:runsJson+']' }
        'Release-root-array' { $script:releaseJson='['+$script:releaseJson+']' }
        'Release-tag-root-array' { $script:tagJson='['+$script:tagJson+']' }
        'Release-metadata-root-array' { $script:metadataJson='['+$script:metadataJson+']' }
        'Release-assets-object' { $script:published.assets=$script:published.assets[1]; $script:releaseJson=$script:published | ConvertTo-Json -Depth 8 -Compress }
    }
    $result=Invoke-CiBaselineFixture $mode
    $script:baselineObservations.Add([ordered]@{case=$case;result=$result;apiCalls=@($script:apiCalls.ToArray())})
    if ($case -eq 'CI-valid-array') {
        Assert-CiAudit (-not $result.forceFull -and $result.baseSha -ceq $script:goodSha) 'raw JSON single-run array with positive integer id accepted'
    } else {
        Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "$case raw JSON container schema must fail closed"
    }
}
foreach ($case in 'status-array','conclusion-array','event-array','branch-array','sha-array','id-null') {
    Reset-CiPublishedFixture
    $run=New-CiRun 'completed' 'success'
    switch ($case) {
        'status-array' { $run.status=@('completed') }
        'conclusion-array' { $run.conclusion=@('success') }
        'event-array' { $run.event=@('push') }
        'branch-array' { $run.head_branch=@('work') }
        'sha-array' { $run.head_sha=@($script:goodSha) }
        'id-null' { $run.id=$null }
    }
    $script:runsJson=@{workflow_runs=@($run)} | ConvertTo-Json -Depth 8 -Compress
    $result=Invoke-CiBaselineFixture 'CI'
    $script:baselineObservations.Add([ordered]@{case="CI-$case";result=$result;apiCalls=@($script:apiCalls.ToArray())})
    Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "CI $case malformed member must fail closed"
}
foreach ($case in 'int64-valid','metadata-network','metadata-invalid-json','metadata-over-bound',
    'id-string','id-bool','id-fraction','id-zero','id-array',
    'pointer-size-string','pointer-size-bool','pointer-size-fraction','pointer-size-over-bound',
    'archive-size-string','archive-size-fraction','prerelease-string','draft-null','duplicate-case',
    'schema-string','release-string','release-bool','size-string','size-bool',
    'commit-array','asset-name-array','sha-array','sha-empty-array','sha-null','sha-uppercase','tag-array') {
    Reset-CiPublishedFixture
    $metadata=$script:metadataJson | ConvertFrom-Json
    switch ($case) {
        'int64-valid' { $script:published.assets[1].id=[long]22; $metadata.schemaVersion=[long]1; $metadata.releaseNumber=[long]8; $metadata.sizeBytes=[long]100 }
        'metadata-network' { $script:metadataFailure=$true }
        'metadata-invalid-json' { $script:metadataJson='{' }
        'metadata-over-bound' { $metadata.note='x'*65537 }
        'id-string' { $script:published.assets[1].id='22' }
        'id-bool' { $script:published.assets[1].id=$true }
        'id-fraction' { $script:published.assets[1].id=22.5 }
        'id-zero' { $script:published.assets[1].id=0 }
        'id-array' { $script:published.assets[1].id=@(22) }
        'pointer-size-string' { $script:published.assets[1].size='300' }
        'pointer-size-bool' { $script:published.assets[1].size=$true }
        'pointer-size-fraction' { $script:published.assets[1].size=300.5 }
        'pointer-size-over-bound' { $script:published.assets[1].size=65537 }
        'archive-size-string' { $script:published.assets[0].size='100' }
        'archive-size-fraction' { $script:published.assets[0].size=100.1 }
        'prerelease-string' { $script:published.prerelease='false' }
        'draft-null' { $script:published.draft=$null }
        'duplicate-case' { $script:published.assets+=@(@{id=23;name='RELEASE.JSON';size=300}) }
        'schema-string' { $metadata.schemaVersion='1' }
        'release-string' { $metadata.releaseNumber='8' }
        'release-bool' { $metadata.releaseNumber=$true }
        'size-string' { $metadata.sizeBytes='100' }
        'size-bool' { $metadata.sizeBytes=$true }
        'commit-array' { $metadata.commitSha=@($script:goodSha) }
        'asset-name-array' { $metadata.assetName=@('CashPrediction-portable.zip') }
        'sha-array' { $metadata.sha256=@('a'*64) }
        'sha-empty-array' { $metadata.sha256=@() }
        'sha-null' { $metadata.sha256=$null }
        'sha-uppercase' { $metadata.sha256='A'*64 }
        'tag-array' { $script:published.tag_name=@('latest') }
    }
    $script:releaseJson=$script:published | ConvertTo-Json -Depth 8 -Compress
    if ($case -ne 'metadata-invalid-json') { $script:metadataJson=$metadata | ConvertTo-Json -Depth 8 -Compress }
    $result=Invoke-CiBaselineFixture 'Release'
    $script:baselineObservations.Add([ordered]@{case=$case;result=$result;apiCalls=@($script:apiCalls.ToArray())})
    if ($case -eq 'int64-valid') {
        Assert-CiAudit (-not $result.forceFull -and $result.baseSha -ceq $script:goodSha) 'valid integer metadata survives boolean precedence'
        Assert-CiAudit ($script:apiCalls.Contains('repos/owner/repo/releases/assets/22')) 'actual published pointer fetched via bounded id endpoint'
        Assert-CiAudit $script:pointerAcceptHeader 'pointer asset fetch requests octet-stream through Invoke-Gh'
    } else {
        Assert-CiAudit ($result.forceFull -and -not $result.baseSha) "Release $case type/bound must fail closed"
    }
}
# Все GitHub command callsites baseline имеют Invoke-Gh; bare gh не разрешается fixture транспортом.
Assert-CiAudit ($baselineSource -notmatch '(?m)^\s*(?:&\s+)?gh\s+' -and $baselineSource.Contains('Invoke-Gh api')) 'baseline Invoke-Gh only'
foreach ($path in $inputPaths) {
    Assert-CiAudit ((Get-FileHash -LiteralPath (Join-Path $Repository $path)).Hash -ceq $inputHashes[$path]) "input stable during fixture: $path"
}
$receipt=[ordered]@{checks=$checks;failed=$findings.Count;findings=@($findings.ToArray());baselineOutput=$baselineOutput;earlyPlatform=$earlyPlatform;baselineObservations=@($script:baselineObservations.ToArray());network=$false;maven=$false;gui=$false;
    inputs=$inputPaths | ForEach-Object {
        $p=[IO.Path]::GetFullPath((Join-Path $Repository $_)); [ordered]@{path=$p;sha256=$inputHashes[$_];endSha256=(Get-FileHash -LiteralPath $p).Hash}
    }}
if ($ReceiptPath) { [IO.File]::WriteAllText($ReceiptPath,($receipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
Write-Host "RESULT: checks=$checks failed=$($findings.Count); local deterministic fixtures, no Maven/GUI/network."
if ($findings.Count) { throw 'CI_WORKFLOW_AUDIT_FAILED' }
