<# Проверяет условия affected workflow, синтаксис pwsh-блоков и выбор успешной базы без сети/GUI. #>
#requires -Version 7.0
[CmdletBinding()]
param([string]$Repository=(Join-Path $PSScriptRoot '../..'), [string]$ReceiptPath)
$ErrorActionPreference='Stop'
$checks=0
$inputPaths=@('.github/scripts/Get-CiImpact.ps1','.github/scripts/Resolve-CiBaseline.ps1','.github/scripts/Test-CiWorkflowImpact.ps1','.github/workflows/ci.yml','.github/workflows/release.yml')
$inputHashes=@{}
foreach ($path in $inputPaths) { $inputHashes[$path]=(Get-FileHash -LiteralPath (Join-Path $Repository $path)).Hash }
# Проверяет утверждение и считает только выполненные проверки.
function Assert-Ci([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw "CI_WORKFLOW_CONTRACT: $Label" }
    $script:checks++
}
# Извлекает шаги workflow с неизменными границами, не выполняя их run-команды.
function Read-CiSteps([string]$Path) {
    $source=Get-Content -LiteralPath $Path -Raw -Encoding utf8
    @([regex]::Matches($source,'(?ms)^      - name: (?<name>[^\r\n]+)\r?\n(?<body>.*?)(?=^      - name: |\z)') | ForEach-Object {
        [pscustomobject]@{name=$_.Groups['name'].Value;body=$_.Groups['body'].Value}
    })
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
$receipt=[ordered]@{checks=$checks;failed=$findings.Count;findings=@($findings.ToArray());baselineObservations=@($script:baselineObservations.ToArray());network=$false;maven=$false;gui=$false;
    inputs=$inputPaths | ForEach-Object {
        $p=[IO.Path]::GetFullPath((Join-Path $Repository $_)); [ordered]@{path=$p;sha256=$inputHashes[$_];endSha256=(Get-FileHash -LiteralPath $p).Hash}
    }}
if ($ReceiptPath) { [IO.File]::WriteAllText($ReceiptPath,($receipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
Write-Host "RESULT: checks=$checks failed=$($findings.Count); local deterministic fixtures, no Maven/GUI/network."
if ($findings.Count) { throw 'CI_WORKFLOW_AUDIT_FAILED' }
