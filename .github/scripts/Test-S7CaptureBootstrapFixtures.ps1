<# .SYNOPSIS Исполняет настоящий S7-Capture control flow с подменёнными transport/base verification, без GitHub/JDK/native. #>
param([Parameter(Mandatory)][string]$CaptureScript, [Parameter(Mandatory)][string]$ReleaseLibrary)
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$library = [Management.Automation.Language.Parser]::ParseFile($ReleaseLibrary, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'CAPTURE_FIXTURE_LIBRARY_PARSE' }
foreach ($name in 'Read-S7Json', 'Write-S7Json', 'Assert-S7SameBase') {
    $node = @($library.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name }, $false))
    if ($node.Count -ne 1) { throw 'CAPTURE_FIXTURE_FUNCTION' }
    . ([scriptblock]::Create($node[0].Extent.Text))
}
$capture = [Management.Automation.Language.Parser]::ParseFile($CaptureScript, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'CAPTURE_FIXTURE_SCRIPT_PARSE' }
# Единственный исключённый statement - dot-source, чтобы настоящий library не заменил транспорт mocks.
$body = @($capture.EndBlock.Statements | Where-Object { $_.Extent.Text -cne '. "$PSScriptRoot/S7-Release.ps1"' })
if ($body.Count -ne $capture.EndBlock.Statements.Count - 1) { throw 'CAPTURE_FIXTURE_IMPORT_SHAPE' }
$execute = [scriptblock]::Create(($body | ForEach-Object { $_.Extent.Text }) -join "`n")
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-retention-capture-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $tempRoot
$savedRepo = $env:GH_REPO
<# .SYNOPSIS Возвращает существующие immutable releases текущего mock сценария. #>
function Get-S7Releases { param($Repository) $script:publishedArchives }
<# .SYNOPSIS Возвращает только наблюдаемое existence latest, не делает HTTP. #>
function Test-GhApiResourceExists { param($Endpoint) $null -ne $script:latest }
<# .SYNOPSIS Подменяет download/ZIP/CLI проверку базы; cardinality proof не является проверкой её bytes. #>
function Get-S7Base {
    param($Tag, $Directory, $Java, $ToolArguments)
    if ($Tag -ceq 'latest') { return $script:latest }
    return $script:archivedBases[$Tag]
}
try {
    $fixtureCommandPath = Join-Path $tempRoot 'tool.json'
    Write-S7Json $fixtureCommandPath @{java='NOT_EXECUTED'; arguments=@('NOT_EXECUTED')}
    $base8 = [pscustomobject]@{releaseNumber=8; commitSha='b3569e72487239747fe7bd8857e8cfe992e7a68b'; treeSha256=('8' * 64); root='fixture'; archive=$null; releasePath=$null; updatePath=$null}
    $base9 = [pscustomobject]@{releaseNumber=9; commitSha=('9' * 40); treeSha256=('9' * 64); root='fixture'; archive=$null; releasePath=$null; updatePath=$null}
    foreach ($case in 0,1,2) {
        $script:latest = if ($case -eq 0) { $null } elseif ($case -eq 1) { $base8 } else { $base9 }
        $script:publishedArchives = if ($case -eq 2) { @([pscustomobject]@{tag_name='update-base-8'; draft=$false}) } else { @() }
        $script:archivedBases = @{'update-base-8'=$base8}
        $ReleaseNumber = 9 + [int]($case -eq 2); $CommitSha = 'a' * 40; $Repository = 'fixture/repository'
        $WorkDirectory = Join-Path $tempRoot "work-$case"; $StateFile = Join-Path $tempRoot "state-$case.json"; $ToolCommandFile = $fixtureCommandPath
        . $execute
        $state = Read-S7Json $StateFile
        $expected = if ($case -eq 0) { @() } elseif ($case -eq 1) { @(8) } else { @(9,8) }
        $actual = @($state.bases | ForEach-Object { $_.releaseNumber })
        if ($actual.Count -ne $expected.Count -or ($actual -join ',') -cne ($expected -join ',')) { throw 'CAPTURE_BOOTSTRAP_BASE_SET' }
        Write-Output "PASS capture-retained-$case releases=$($actual -join ',') transport=MOCK verification=MOCK"
    }
    Write-Output 'CAPTURE_BOOTSTRAP_PASS cases=3 actualControlFlow=TRUE native=NOT_EXECUTED'
} finally {
    $env:GH_REPO = $savedRepo
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'cp-retention-capture-*') { throw 'CAPTURE_CLEANUP_SCOPE' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
