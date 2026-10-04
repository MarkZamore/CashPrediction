<#
.SYNOPSIS Локальная A/B -> C репетиция реальных release helpers с двумя дельтами.
.DESCRIPTION Требует уже собранный W4 CLI; Maven, GUI, gh и сеть не запускает.
#>
param([Parameter(Mandatory)][string]$ToolCommandFile)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"
function gh { throw 'S7_NETWORK_FORBIDDEN' }
function Invoke-Gh { throw 'S7_NETWORK_FORBIDDEN' }

<# .SYNOPSIS Создаёт минимальное управляемое дерево, включая readonly и неизменённый файл. #>
function New-S7TestTree {
    param([string]$Root, [string]$Revision)
    $null = [IO.Directory]::CreateDirectory((Join-Path $Root 'app'))
    $null = [IO.Directory]::CreateDirectory((Join-Path $Root 'runtime/bin'))
    foreach ($name in 'CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe') {
        [IO.File]::WriteAllText((Join-Path $Root $name), "$name-$Revision", [Text.UTF8Encoding]::new($false))
    }
    [IO.File]::WriteAllText((Join-Path $Root 'app/common.txt'), 'unchanged')
    [IO.File]::WriteAllText((Join-Path $Root 'runtime/bin/revision.txt'), $Revision)
    [IO.File]::WriteAllBytes((Join-Path $Root 'app/empty.txt'), [byte[]]@())
    [IO.File]::SetAttributes((Join-Path $Root 'CashPrediction.exe'), [IO.FileAttributes]::ReadOnly)
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('cashprediction-s7-pipeline-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $testRoot
$savedExit = $global:LASTEXITCODE
try {
    $command = Read-S7Json $ToolCommandFile
    $bases = @()
    for ($i = 1; $i -le 2; $i++) {
        $root = Join-Path $testRoot "base-$i"
        New-S7TestTree $root "base$i"
        [IO.File]::WriteAllText((Join-Path $root 'app/deleted.txt'), 'removed in target')
        $inventoryPath = Join-Path $testRoot "inventory-$i.json"
        Invoke-S7Tool $command.java $command.arguments @('inventory', '--root', $root, '--out', $inventoryPath) | Out-Null
        $bases += [ordered]@{
            tag = "update-base-$i"; releaseNumber = $i; commitSha = ([string]$i * 40)
            root = $root; treeSha256 = (Read-S7Json $inventoryPath).treeSha256
        }
    }
    # Новейшая база должна идти первой: именно ей соответствует CashPrediction.cpdelta.
    $bases = @($bases[1], $bases[0])
    $target = Join-Path (Join-Path $testRoot ('portable space ' + [char]0x043f + [char]0x8def)) 'CashPrediction'
    New-S7TestTree $target 'goal3'
    [IO.File]::WriteAllText((Join-Path $target 'app/added.txt'), 'target only')
    $null = [IO.Directory]::CreateDirectory((Join-Path $target 'CashMemory'))
    $userFile = Join-Path $target 'CashMemory/user.md'
    [IO.File]::WriteAllText($userFile, 'must not ship')
    $userHash = (Get-FileHash -LiteralPath $userFile).Hash
    $statePath = Join-Path $testRoot 'state.json'
    Write-S7Json $statePath ([ordered]@{
        releaseNumber = 3; commitSha = ('3' * 40); repository = 'fixture-owner/fixture-repo'
        previous = $null; bases = $bases; toolCommand = $command
    })
    foreach ($attempt in 1, 2) {
        & "$PSScriptRoot/S7-Prepare.ps1" -ImageRoot $target -StateFile $statePath `
            -ArtifactDirectory (Join-Path $testRoot "artifacts-$attempt") `
            -WorkDirectory (Join-Path $testRoot "work-$attempt") -Note 'local fixture'
        $manifest = Assert-S7Artifacts (Join-Path $testRoot "artifacts-$attempt")
        if (@($manifest.deltaPatches).Count -ne 2 -or
            $manifest.deltaPatches[0].baseReleaseNumber -ne 2 -or
            $manifest.deltaPatches[1].baseReleaseNumber -ne 1) { throw 'S7_TWO_BASES_NOT_EXERCISED' }
    }
    foreach ($asset in 'CashPrediction-portable.zip', 'CashPrediction.cpdelta', 'CashPrediction.from-1.cpdelta') {
        $first = (Get-FileHash -LiteralPath (Join-Path $testRoot "artifacts-1/$asset")).Hash
        $second = (Get-FileHash -LiteralPath (Join-Path $testRoot "artifacts-2/$asset")).Hash
        if ($first -cne $second) { throw "S7_NONDETERMINISTIC: $asset" }
    }
    if ((Get-FileHash -LiteralPath $userFile).Hash -cne $userHash) { throw 'S7_USER_FILE_CHANGED' }
    # Тот же размер и произвольный timestamp не должны скрыть изменение базового content.
    [IO.File]::WriteAllText((Join-Path $bases[0].root 'runtime/bin/revision.txt'), 'evil2')
    $problem = $null
    try {
        & "$PSScriptRoot/S7-Prepare.ps1" -ImageRoot $target -StateFile $statePath `
            -ArtifactDirectory (Join-Path $testRoot 'artifacts-corrupt') `
            -WorkDirectory (Join-Path $testRoot 'work-corrupt') -Note 'must fail'
    } catch { $problem = $_ }
    if (-not $problem -or (Test-Path -LiteralPath (Join-Path $testRoot 'artifacts-corrupt/update.json'))) {
        throw 'S7_FAILED_JOB_PUBLISHED_POINTER'
    }
    Write-Output 'S7 pipeline: PASS (two actual CLI roundtrips and deterministic containers)'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^cashprediction-s7-pipeline-[0-9a-f-]{36}$') { throw 'S7_TEST_CLEANUP_BOUNDARY' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    $global:LASTEXITCODE = $savedExit
}
