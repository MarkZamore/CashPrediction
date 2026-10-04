<# .SYNOPSIS Проверяет отказ перед публикацией и retention; GitHub полностью подменён, native не запускается. #>
param([Parameter(Mandatory)][string]$ScriptUnderTest, [string]$JavaManifestDirectory)
$ErrorActionPreference = 'Stop'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($ScriptUnderTest, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'FIXTURE_SCRIPT_SYNTAX' }
foreach ($name in 'Read-S7Json', 'Assert-S7Container', 'Read-S7ReleaseIdentity', 'Assert-S7Artifacts', 'Publish-S7Latest') {
    $function = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $false))
    if ($function.Count -ne 1) { throw "FIXTURE_FUNCTION_$name" }
    . ([scriptblock]::Create($function[0].Extent.Text))
}
$optional = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-S7DeltaBases' }, $false))
if ($optional.Count -eq 1) { . ([scriptblock]::Create($optional[0].Extent.Text)) }
$publishPath = Join-Path (Split-Path -Parent $ScriptUnderTest) 'S7-Publish.ps1'
$publishAst = [Management.Automation.Language.Parser]::ParseFile($publishPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'FIXTURE_PUBLISH_SYNTAX' }
$preflight = @($publishAst.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Assert-S7DeltaBases' }, $false))
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-req134-155-fixture-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $fixtureRoot
$script:remote = [Collections.Generic.List[string]]::new()
$script:passed = 0; $script:failed = 0

<# .SYNOPSIS Имитирует только существование release latest без обращения к API. #>
function Test-GhApiResourceExists { param([string]$Endpoint) $true }
<# .SYNOPSIS Записывает вызовы GitHub в память, не выполняя внешние действия. #>
function Invoke-S7Gh {
    $script:remote.Add(($args -join ' '))
    if ($args[0] -eq 'release' -and $args[1] -eq 'view') { return '{"assets":[]}' }
}
<# .SYNOPSIS Возвращает три синтетические базы для проверки решения retention. #>
function Get-S7Releases {
    param([string]$Repository)
    foreach ($number in 1,2,3) { [pscustomobject]@{tag_name="update-base-$number"; draft=$false} }
}
<# .SYNOPSIS Создаёт свои байтовые контейнеры и JSON; PE/JVM/ZIP proof отсутствует. #>
function New-FixtureArtifacts {
    param([string]$Name, [string]$Commit1 = ('b' * 40), [string]$Commit2 = ('c' * 40), [int]$Bases = 2)
    $directory = Join-Path $fixtureRoot $Name
    $null = New-Item -ItemType Directory -Path $directory
    $deltas = @()
    foreach ($i in 0..($Bases - 1)) {
        if ($Bases -eq 0) { break }
        $number = 3 - $i
        $asset = if ($i -eq 0) { 'CashPrediction.cpdelta' } else { "CashPrediction.from-$number.cpdelta" }
        $path = Join-Path $directory $asset
        [IO.File]::WriteAllBytes($path, [byte[]](1,2,3,4))
        $deltas += [ordered]@{baseReleaseNumber=$number; baseCommitSha=$(if ($i -eq 0) {$Commit1} else {$Commit2});
            baseTreeSha256=('d' * 64); assetName=$asset; sizeBytes=4;
            sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant(); algorithm='cashprediction-tree-delta'; algorithmVersion=1}
    }
    $zip = Join-Path $directory 'CashPrediction-portable.zip'
    [IO.File]::WriteAllBytes($zip, [byte[]](9,8,7))
    $release = [ordered]@{schemaVersion=1; releaseNumber=4; commitSha=('a' * 40); assetName='CashPrediction-portable.zip';
        sizeBytes=3; sha256=(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant()}
    $update = [ordered]@{schemaVersion=2; releaseNumber=4; commitSha=('a' * 40); version='4';
        publishedAtUtc='2026-10-04T00:00:00Z'; assetName=$release.assetName; sizeBytes=3; sha256=$release.sha256;
        treeSha256=('e' * 64); files=@(); deltaPatches=$deltas}
    [IO.File]::WriteAllText((Join-Path $directory 'release.json'), ($release | ConvertTo-Json -Depth 10))
    [IO.File]::WriteAllText((Join-Path $directory 'update.json'), ($update | ConvertTo-Json -Depth 10))
    return $directory
}
<# .SYNOPSIS Проверяет точный отказ и отсутствие любого вызова внешнего слоя. #>
function Assert-Refusal {
    param([string]$Name, [string]$Reason, [scriptblock]$Action)
    $script:remote.Clear()
    $actual = 'ACCEPTED'
    try { & $Action | Out-Null } catch { $actual = $_.Exception.Message }
    if ($actual -ceq $Reason -and $script:remote.Count -eq 0) {
        $script:passed++; Write-Output "PASS $Name reason=$Reason externalCalls=0"
    } else { $script:failed++; Write-Output "FAIL $Name expected=$Reason actual=$actual externalCalls=$($script:remote.Count)" }
}
<# .SYNOPSIS Подтверждает допустимый набор баз и порядок upload-pointer-retention в подменённом слое. #>
function Assert-Publication {
    param([string]$Name, [string]$Directory, [string[]]$Keep)
    $script:remote.Clear()
    try {
        Publish-S7Latest $Directory 'fixture/repository' ('a' * 40) 'fixture' 'fixture' $Keep
        $calls = $script:remote.ToArray()
        if ($calls.Count -lt 5 -or $calls[0] -notmatch '^release upload latest .*CashPrediction-portable.zip' -or
            $calls[1] -notmatch '^release upload latest .*update.json') { throw 'FIXTURE_PUBLISH_ORDER' }
        foreach ($tag in $Keep) {
            if (@($calls | Where-Object { $_ -like "release delete $tag *" }).Count) { throw 'FIXTURE_RETAINED_BASE_DELETED' }
        }
        $script:passed++; Write-Output "PASS $Name mockOrder=payload,pointer,retention native=NOT_EXECUTED"
    } catch { $script:failed++; Write-Output "FAIL $Name actual=$($_.Exception.Message)" }
}
<# .SYNOPSIS Выполняет только действительное preflight выражение S7-Publish; при отсутствии guard baseline его не исполняет. #>
function Invoke-BasePreflight {
    param($Update, [object[]]$Bases)
    $update = $Update; $state = [pscustomobject]@{ bases=$Bases }
    if ($preflight.Count -eq 1) { . ([scriptblock]::Create($preflight[0].Extent.Text)) }
}
try {
    $valid = New-FixtureArtifacts 'valid'
    $duplicate = New-FixtureArtifacts 'duplicate' ('b' * 40) ('b' * 40)
    $sameTarget = New-FixtureArtifacts 'same-target' ('a' * 40) ('c' * 40)
    Assert-Refusal 'duplicate-base-commit' 'S7_PATCH_IDENTITY' { Assert-S7Artifacts $duplicate }
    Assert-Refusal 'base-equals-target' 'S7_PATCH_IDENTITY' { Assert-S7Artifacts $sameTarget }
    Assert-Refusal 'wrong-retention' 'S7_RETENTION_POINTER_MISMATCH' { Publish-S7Latest $valid 'fixture/repository' ('a' * 40) 'fixture' 'fixture' @('update-base-3','update-base-1') }
    Assert-Refusal 'missing-retention' 'S7_RETENTION_POINTER_MISMATCH' { Publish-S7Latest $valid 'fixture/repository' ('a' * 40) 'fixture' 'fixture' @('update-base-3') }
    Assert-Refusal 'empty-retention' 'S7_RETENTION_POINTER_MISMATCH' { Publish-S7Latest $valid 'fixture/repository' ('a' * 40) 'fixture' 'fixture' @() }
    Assert-Publication 'two-bases' $valid @('update-base-3','update-base-2')
    Assert-Publication 'two-bases-reordered' $valid @('update-base-2','update-base-3')
    $single = New-FixtureArtifacts 'single' -Bases 1
    Assert-Publication 'one-base' $single @('update-base-3')
    Assert-Publication 'one-delta-two-retained-bases' $single @('update-base-3','update-base-2')
    $first = New-FixtureArtifacts 'first' -Bases 0
    Assert-Publication 'first-release' $first @()
    Assert-Publication 'legacy-latest-one-retained-no-delta' $first @('update-base-3')
    Assert-Publication 'no-delta-two-retained-bases' $first @('update-base-3','update-base-2')
    $model = Read-S7Json (Join-Path $valid 'update.json')
    $bases = @([pscustomobject]@{releaseNumber=3; commitSha=('b' * 40); treeSha256=('d' * 64)},
        [pscustomobject]@{releaseNumber=2; commitSha=('c' * 40); treeSha256=('d' * 64)})
    $wrongCommit = @($bases[0].PSObject.Copy(), $bases[1].PSObject.Copy()); $wrongCommit[0].commitSha = 'f' * 40
    $wrongTree = @($bases[0].PSObject.Copy(), $bases[1].PSObject.Copy()); $wrongTree[0].treeSha256 = 'f' * 64
    Assert-Refusal 'delta-retained-commit-mismatch' 'S7_DELTA_RETAINED_BASE_IDENTITY' { Invoke-BasePreflight $model $wrongCommit }
    Assert-Refusal 'delta-retained-tree-mismatch' 'S7_DELTA_RETAINED_BASE_IDENTITY' { Invoke-BasePreflight $model $wrongTree }
    Assert-Refusal 'delta-retained-base-missing' 'S7_DELTA_RETAINED_BASE_MISSING' { Invoke-BasePreflight $model @($bases[0]) }
    Invoke-BasePreflight $model $bases
    $script:passed++; Write-Output 'PASS matching-retained-identities equal-tree-different-commits=ALLOWED'
    if ($preflight.Count -eq 1) {
        $saveCommands = @($publishAst.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Save-S7Base' }, $true))
        if ($saveCommands.Count -ne 1 -or $preflight[0].Extent.StartOffset -ge $saveCommands[0].Extent.StartOffset) { throw 'FIXTURE_PREFLIGHT_AFTER_SAVE' }
        $script:passed++; Write-Output 'PASS preflight-before-Save-S7Base AST_ONLY'
    } else {
        $script:failed++; Write-Output 'FAIL preflight-before-Save-S7Base missing-production-guard AST_ONLY'
    }
    if ($JavaManifestDirectory) {
        foreach ($count in 0,1,2) {
            $javaModel = Read-S7Json (Join-Path $JavaManifestDirectory "java-$count.json")
            Invoke-BasePreflight $javaModel $bases
            $script:passed++; Write-Output "PASS actual-java-manifest-$count-retained-preflight"
        }
        Assert-Refusal 'actual-java-retained-commit-mismatch' 'S7_DELTA_RETAINED_BASE_IDENTITY' { Invoke-BasePreflight $javaModel $wrongCommit }
        Assert-Refusal 'actual-java-retained-tree-mismatch' 'S7_DELTA_RETAINED_BASE_IDENTITY' { Invoke-BasePreflight $javaModel $wrongTree }
    }
    Write-Output "SUMMARY passed=$script:passed failed=$script:failed mock=TRUE native=NOT_EXECUTED"
    if ($script:failed -ne 0) { exit 1 }
} finally {
    # Удаляется только созданный этой fixture абсолютный Temp UUID-каталог.
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'cp-req134-155-fixture-*') { throw 'FIXTURE_CLEANUP_SCOPE' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
