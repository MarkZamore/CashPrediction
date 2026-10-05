<# Узкие проверки настоящего baseline helper: ownTemp Git, закрытый gh seam, без live API. #>
#requires -Version 7.0
[CmdletBinding()]
param([string]$Repository=(Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$source=Join-Path $Repository '.github/scripts/Resolve-CiBaseline.ps1'
$retry=Join-Path $Repository '.github/scripts/GhRetry.ps1'
$before=(Get-FileHash -LiteralPath $source).Hash
$fixtureGit=(Get-Command git -CommandType Application | Select-Object -First 1).Source
$temp=Join-Path ([IO.Path]::GetTempPath()) ('cp-ci-baseline-bounded-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $temp
$repo=Join-Path $temp 'repo'
$null=New-Item -ItemType Directory -Path $repo
$disabledHooks=Join-Path $temp 'disabled-hooks'
$null=New-Item -ItemType Directory -Path $disabledHooks
$utf8=[Text.UTF8Encoding]::new($false)
$checks=0
# Проверяет факт и считает только выполненные assertions.
function Assert-Baseline([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw "BASELINE_FIXTURE_FAILED: $Label; evidence=$temp" }
    $script:checks++
}
# Создаёт историю исключительно ownTemp Git, отключая hooks и signing для каждого вызова.
function Invoke-FixtureGit {
    $result=& $fixtureGit -c "core.hooksPath=$disabledHooks" -c commit.gpgsign=false `
        -c user.name=BaselineFixture -c user.email=baseline-fixture@example.invalid -C $repo @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "FIXTURE_GIT_FAILED: $($args -join ' ')" }
    return $result
}
$null=Invoke-FixtureGit init --initial-branch=work
$null=Invoke-FixtureGit commit --allow-empty -m base
$base="$(Invoke-FixtureGit rev-parse HEAD)".Trim()
$null=Invoke-FixtureGit tag latest $base
$null=Invoke-FixtureGit commit --allow-empty -m head
$head="$(Invoke-FixtureGit rev-parse HEAD)".Trim()
$null=Invoke-FixtureGit checkout --orphan unrelated
$null=Invoke-FixtureGit commit --allow-empty -m unrelated
$unrelated="$(Invoke-FixtureGit rev-parse HEAD)".Trim()
$null=Invoke-FixtureGit checkout work
$copy=Join-Path $temp 'Resolve-CiBaseline.ps1'
[IO.File]::WriteAllBytes($copy,[IO.File]::ReadAllBytes($source))
# Seams используются только рядом с ownTemp копией. Настоящий Invoke-Gh и его retry loop сохранены.
$transport=@'
. $fixtureRetry
function git {
    $fixtureState.git.Add(($args -join '|'))
    if ($fixtureState.case -ceq 'shallow' -and ($args -join '|').EndsWith('rev-parse|--is-shallow-repository')) {
        $global:LASTEXITCODE=0; return 'true'
    }
    & $fixtureGit @args
}
function Start-Sleep {
    param([int]$Seconds)
    $fixtureState.delays.Add($Seconds)
}
function gh {
    $fixtureState.api.Add(($args -join '|'))
    $global:LASTEXITCODE=0
    if ($fixtureState.case -ceq 'network' -or
        ($fixtureState.case -ceq 'transient' -and $fixtureState.api.Count -eq 1)) {
        $global:LASTEXITCODE=1; Write-Error 'HTTP 503: fixture unavailable'; return
    }
    if ($fixtureState.case -ceq 'not-found') {
        $global:LASTEXITCODE=1; Write-Error 'HTTP 404: fixture absent'; return
    }
    $endpoint=[string]$args[1]
    if ($endpoint.Contains('/runs?')) {
        if ($fixtureState.case -ceq 'bad-schema') { return '{"workflow_runs":"not array"}' }
        if ($fixtureState.case -ceq 'malformed') { return '{' }
        $runs=@()
        if ($fixtureState.case -ceq 'duplicate-history' -or $fixtureState.case -ceq 'page-cap') {
            if ($endpoint.EndsWith('page=1') -or $fixtureState.case -ceq 'page-cap') {
                $runs=@(1..100|ForEach-Object {
                    @{id=$_;status='completed';conclusion='success';event='push';head_branch='work';head_sha=$fixtureUnrelated}
                })
            } else {
                $runs=@(@{id=101;status='completed';conclusion='success';event='push';head_branch='work';head_sha=$fixtureBase})
            }
        } else {
            $runs=@(
                @{id=11;status='completed';conclusion='failure';event='push';head_branch='work';head_sha=$fixtureBase},
                @{id=12;status='completed';conclusion='cancelled';event='push';head_branch='work';head_sha=$fixtureBase},
                @{id=13;status='completed';conclusion='success';event='pull_request';head_branch='work';head_sha=$fixtureBase},
                @{id=14;status='completed';conclusion='success';event='push';head_branch='other';head_sha=$fixtureBase},
                @{id=15;status='completed';conclusion='success';event='push';head_branch='work';head_sha=$fixtureHead},
                @{id=16;status='completed';conclusion='success';event='push';head_branch='work';head_sha=$fixtureUnrelated})
            if ($fixtureState.case -cne 'no-ancestor') {
                $runs+=@{id=17;status='completed';conclusion='success';event='push';head_branch='work';head_sha=$fixtureBase}
            }
        }
        return (@{workflow_runs=$runs}|ConvertTo-Json -Depth 6 -Compress)
    }
    if ($endpoint -ceq 'repos/owner/repo/releases/tags/latest') { return ($fixtureRelease|ConvertTo-Json -Depth 6 -Compress) }
    if ($endpoint -ceq 'repos/owner/repo/commits/latest') {
        return (@{sha=$(if($fixtureState.case -ceq 'tag-race'){$fixtureHead}else{$fixtureBase})}|ConvertTo-Json -Compress)
    }
    if ($endpoint -ceq 'repos/owner/repo/releases/assets/22') { return ($fixturePointer|ConvertTo-Json -Compress) }
    throw 'UNEXPECTED_FIXTURE_API_NO_LIVE_ACCESS'
}
'@
[IO.File]::WriteAllText((Join-Path $temp 'GhRetry.ps1'),$transport,$utf8)
$cases=@('success','force-full','network','transient','not-found','bad-schema','malformed','shallow',
    'no-ancestor','duplicate-history','page-cap','release-valid','release-legacy-digest',
    'digest-mismatch','digest-malformed','pointer-mismatch','duplicate-assets','draft-release','tag-race')
$results=[Collections.Generic.List[object]]::new()
foreach ($case in $cases) {
    $fixtureState=@{case=$case;git=[Collections.Generic.List[string]]::new();api=[Collections.Generic.List[string]]::new();delays=[Collections.Generic.List[int]]::new()}
    $fixtureRetry=$retry; $fixtureBase=$base; $fixtureHead=$head; $fixtureUnrelated=$unrelated
    $fixtureRelease=@{tag_name='latest';draft=$false;prerelease=$false;assets=@(
        @{id=21;name='CashPrediction-portable.zip';size=100;digest=('sha256:'+('a'*64))},
        @{id=22;name='release.json';size=300})}
    $fixturePointer=@{schemaVersion=1;releaseNumber=1;commitSha=$base;assetName='CashPrediction-portable.zip';sizeBytes=100;sha256=('a'*64)}
    switch ($case) {
        'release-legacy-digest' { $fixtureRelease.assets[0].Remove('digest') }
        'digest-mismatch' { $fixtureRelease.assets[0].digest='sha256:'+('b'*64) }
        'digest-malformed' { $fixtureRelease.assets[0].digest='not a digest' }
        'pointer-mismatch' { $fixturePointer.commitSha=$head }
        'duplicate-assets' { $fixtureRelease.assets+=@{id=23;name='release.json';size=300} }
        'draft-release' { $fixtureRelease.draft=$true }
    }
    $mode=if ($case -match '^release-|^digest-|^pointer-|^duplicate-assets$|^draft-release$|^tag-race$') {'Release'} else {'CI'}
    $output=Join-Path $temp ($case+'.output')
    [IO.File]::WriteAllText($output,"prior=preserved`n",$utf8)
    $raw=& $copy -Mode $mode -Repository $repo -GithubRepository owner/repo -Branch work `
        -HeadSha $head -GithubOutput $output -ForceFull:($case -ceq 'force-full') -WarningAction SilentlyContinue
    $result=($raw -join "`n")|ConvertFrom-Json
    $expected=$case -in @('success','transient','duplicate-history','release-valid','release-legacy-digest')
    Assert-Baseline ($result.forceFull -eq (-not $expected)) "$case full fallback"
    Assert-Baseline ($result.baseSha -ceq $(if($expected){$base}else{''})) "$case exact baseline or empty"
    $lines=[IO.File]::ReadAllText($output)
    Assert-Baseline ($lines -ceq "prior=preserved`nbase_sha=$($result.baseSha)`nforce_full=$($result.forceFull.ToString().ToLowerInvariant())`n") "$case exact output, append prefix preserved"
    Assert-Baseline ((Invoke-FixtureGit rev-parse HEAD).Trim() -ceq $head) "$case checkout unchanged"
    if ($case -ceq 'network') {
        Assert-Baseline ($fixtureState.api.Count -eq 3) 'network at most three attempts'
        Assert-Baseline (($fixtureState.delays -join ',') -ceq '1,3') 'network bounded delay schedule'
    }
    if ($case -ceq 'not-found') { Assert-Baseline ($fixtureState.api.Count -eq 1 -and $fixtureState.delays.Count -eq 0) '404 not retried' }
    if ($case -ceq 'transient') { Assert-Baseline ($fixtureState.api.Count -eq 2 -and ($fixtureState.delays -join ',') -ceq '1') 'transient retries then succeeds' }
    if ($case -ceq 'force-full') { Assert-Baseline ($fixtureState.api.Count -eq 0 -and $fixtureState.git.Count -eq 0) 'force-full no selection work' }
    if ($case -ceq 'shallow') { Assert-Baseline ($fixtureState.api.Count -eq 0) 'shallow history no API work' }
    if ($case -ceq 'duplicate-history') {
        Assert-Baseline ($fixtureState.api.Count -eq 2 -and $fixtureState.git.Count -eq 5) '100 duplicate SHA runs only one ancestry proof'
    }
    if ($case -ceq 'page-cap') {
        Assert-Baseline ($fixtureState.api.Count -eq 3 -and $fixtureState.git.Count -eq 3) '300 repeated runs bounded three pages, one ancestry proof'
    }
    $results.Add([pscustomobject]@{case=$case;forceFull=$result.forceFull;apiCalls=$fixtureState.api.Count;gitCalls=$fixtureState.git.Count;delays=@($fixtureState.delays)})
}
# Отдельно проверяет restoration scope, включая exception, не меняя общий GhRetry.
. $source
$script:GhRetryDelays=@(5,15,30,60,120)
function Invoke-Gh {
    Assert-Baseline (($script:GhRetryDelays -join ',') -ceq '1,3') 'request-local retry delays'
    if ($script:throwRetryFixture) { throw 'RETRY_SCOPE_FIXTURE' }
    $global:LASTEXITCODE=7
    return 'closed fixture result'
}
$script:throwRetryFixture=$false
$apiResult=Invoke-CiBaselineApi api 'closed-fixture-only'
Assert-Baseline ($apiResult -ceq 'closed fixture result' -and $LASTEXITCODE -eq 7) 'API output and exit preserved'
Assert-Baseline (($script:GhRetryDelays -join ',') -ceq '5,15,30,60,120') 'release delays restored after request'
$script:throwRetryFixture=$true
$caught=$false
try { $null=Invoke-CiBaselineApi api 'closed-fixture-only' } catch { $caught=$_.Exception.Message -ceq 'RETRY_SCOPE_FIXTURE' }
Assert-Baseline ($caught -and ($script:GhRetryDelays -join ',') -ceq '5,15,30,60,120') 'release delays restored after exception'
Assert-Baseline ((Get-FileHash -LiteralPath $source).Hash -ceq $before) 'source stable through fixtures'
[pscustomobject]@{checks=$checks;cases=$cases.Count;result='PASS';temp=$temp;sourceSha256=$before;liveApiCalls=0;results=$results}|ConvertTo-Json -Depth 6
