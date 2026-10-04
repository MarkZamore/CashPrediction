<#
.SYNOPSIS
Отвергает посторонние исключения после полного потребления очереди release mocks.
.DESCRIPTION
Загружает только настоящие функции New-Reply, gh и Test-PublishCase через AST.
Publisher заменён локальным мутантом; GitHub, workflow, JVM и продукт не запускаются.
#>
#requires -Version 7.0
param([string]$SafetyPath=(Join-Path $PSScriptRoot 'Test-ReleaseSafety.ps1'),
    [Parameter(Mandatory)][string]$ReceiptPath)
$ErrorActionPreference='Stop'
$sourcePin=(Get-FileHash -LiteralPath $SafetyPath).Hash
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($SafetyPath,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'ERROR_FIXTURE_PARSE'}
foreach($name in 'New-Reply','gh','Test-PublishCase'){
    $nodes=@($ast.FindAll({param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name
    },$false))
    if($nodes.Count -ne 1){throw "ERROR_FIXTURE_FUNCTION: $name"}
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('cp-release-error-mutant-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $fixtureRoot
$fixtureState=Join-Path $fixtureRoot 'state.json'
[IO.File]::WriteAllText($fixtureState,'{}')
$savedTemp=$env:RUNNER_TEMP
$savedVariable=Get-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue
$hadVariable=$null -ne $savedVariable
$savedValue=if($hadVariable){$savedVariable.Value}else{$null}
$savedCode=$global:LASTEXITCODE
$specs=@()
foreach($http in 401,403,422){
    $specs+=@{name="release HTTP $http"; pattern='^'+[regex]::Escape("Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=$http. Публикация остановлена.")+'$'}
    $specs+=@{name="tag HTTP $http"; pattern='^'+[regex]::Escape("Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/git/ref/tags/latest: exit=1, HTTP=$http. Публикация остановлена.")+'$'}
}
$specs+=@(
    @{name='release exhausted503';pattern='^'+[regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=503. Публикация остановлена.')+'$'},
    @{name='tag exhausted503';pattern='^'+[regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/git/ref/tags/latest: exit=1, HTTP=503. Публикация остановлена.')+'$'},
    @{name='transport text404';pattern='^'+[regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=0. Публикация остановлена.')+'$'},
    @{name='success without HTTP';pattern='^'+[regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=0, HTTP=0. Публикация остановлена.')+'$'},
    @{name='asset list403';pattern='^S7_GITHUB_FAILED: release view$'},
    @{name='PATCH exhausted503';pattern='^S7_GITHUB_FAILED: api repos/fixture-owner/fixture-repo/git/refs/tags/latest$'},
    @{name='orphan PATCH403';pattern='^S7_GITHUB_FAILED: api repos/fixture-owner/fixture-repo/git/refs/tags/latest$'}
)
$publish={
    # Расходуем настоящую очередь мока до unrelated throw, исключая остаток как причину FAIL.
    $null=gh 'api fixture-consume --method GET --include'
    throw 'RELEASE_REVIEW_UNRELATED_EXCEPTION'
}
$results=@()
try {
    foreach($spec in $specs){
        $failure=$null
        try{
            Test-PublishCase $spec.name @((New-Reply 'api fixture-consume --method GET --include')) $true $spec.pattern | Out-Null
        }catch{$failure=$_}
        $state=$global:CashPredictionReleaseSafetyMockState
        $passed=$null -ne $failure -and
            $failure.Exception.Message.StartsWith('RELEASE_SAFETY_FAILURE_CATEGORY:') -and
            $state.Replies.Count -eq 0 -and $state.Calls.Count -eq 1
        $results+=[pscustomobject]@{name=$spec.name;expectedFailure=$spec.pattern;
            queueRemaining=$state.Replies.Count;calls=$state.Calls.Count;
            observedError=$(if($failure){$failure.Exception.Message}else{'ACCEPTED_UNRELATED_EXCEPTION'});
            passed=$passed}
    }
    # Нельзя снова добавить legacy MustFail без категории.
    $failure=$null
    try{Test-PublishCase 'missing category' @() $true | Out-Null}catch{$failure=$_}
    $results+=[pscustomobject]@{name='missing category';
        passed=($null -ne $failure -and $failure.Exception.Message -ceq 'RELEASE_SAFETY_EXPECTED_FAILURE_REQUIRED: missing category')}
}finally{
    $env:RUNNER_TEMP=$savedTemp
    $global:LASTEXITCODE=$savedCode
    if($hadVariable){$global:CashPredictionReleaseSafetyMockState=$savedValue}
    else{Remove-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue}
}
if((Get-FileHash -LiteralPath $SafetyPath).Hash -cne $sourcePin){throw 'ERROR_FIXTURE_INPUT_CHANGED'}
$failed=@($results|Where-Object {-not $_.passed}).Count
$receipt=[ordered]@{scope='ACTUAL_CASE_FUNCTION_UNRELATED_THROW_MUTANT';source=$SafetyPath;
    sourceSha256=$sourcePin;fixtureSha256=(Get-FileHash $PSCommandPath).Hash;
    tests=$results.Count;failures=$failed;results=$results;nativeExecuted=$false;networkExecuted=$false;
    publisherExecuted=$false;fixtureRoot=$fixtureRoot}
$stream=[IO.File]::Open($ReceiptPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($receipt|ConvertTo-Json -Depth 8));
    $stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
Write-Output "Unrelated exception mutants: $($results.Count) tests, $failed failures; receipt=$ReceiptPath"
if($failed){exit 1}
