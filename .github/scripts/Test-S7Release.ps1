# Исполняемые mocks S7: сеть и subprocess полностью перекрыты локальными функциями.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"

<# .SYNOPSIS Проверяет условие и останавливает тест при нарушении. #>
function Assert-S7Test {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "S7_TEST_FAILED: $Message" }
}

<# .SYNOPSIS Заменяет GitHub; записывает каждый вызов и внедряет отказ на точной границе. #>
function Invoke-Gh {
    $call = @($args | ForEach-Object { [string]$_ })
    $script:Calls.Add($call)
    $global:LASTEXITCODE = 0
    if ($script:FailAt -eq $script:Calls.Count) {
        $global:LASTEXITCODE = 1
        if ($call -contains '--include') { return @('HTTP/2.0 503', '', '{}') }
        return '{}'
    }
    if ($call[0] -eq 'api' -and $call -contains '--include') {
        $present = $script:LatestExists
        if ($call[1] -match 'git/ref/tags/latest') { $present = $script:OrphanExists }
        if ($call[1] -match 'update-base-') {
            $present = $script:BaseExists
            if ($call[1] -match 'git/ref/') { $present = $script:BaseOrphanExists }
        }
        if ($present) { return @('HTTP/2.0 200', '', '{}') }
        $global:LASTEXITCODE = 1
        return @('HTTP/2.0 404', '', '{}')
    }
    if ($call[0] -eq 'release' -and $call[1] -eq 'view') {
        return '{"assets":[{"name":"CashPrediction-portable.zip"},{"name":"release.json"},{"name":"update.json"},{"name":"stale.cpdelta"}]}'
    }
    if ($call[0] -eq 'api' -and $call -contains '--paginate') {
        # Две страницы: retention обязан увидеть старую базу за границей первой страницы.
        $first = @($script:Keep | ForEach-Object { [ordered]@{ tag_name = $_; draft = $false } })
        $second = @([ordered]@{ tag_name = 'update-base-99'; draft = $false },
            [ordered]@{ tag_name = 'update-base-98'; draft = $true })
        return '[' + (ConvertTo-Json -InputObject $first -Compress) + ',' +
            (ConvertTo-Json -InputObject $second -Compress) + ']'
    }
    if ($call[0] -eq 'release' -and $call[1] -in 'create', 'upload', 'delete-asset', 'delete', 'edit') { return '{}' }
    if ($call[0] -eq 'api' -and $call -contains 'PATCH') { return '{}' }
    throw "S7_UNEXPECTED_GITHUB_CALL: $($call -join ' ')"
}

<# .SYNOPSIS Не допускает случайного запуска настоящего executable gh или JDK. #>
function gh { throw 'S7_REAL_GH_FORBIDDEN' }
function java { throw 'S7_REAL_JAVA_FORBIDDEN' }
function jar { throw 'S7_REAL_JAR_FORBIDDEN' }

<# .SYNOPSIS Создаёт локальные payload fixtures с настоящими SHA-256 и размерами. #>
function New-S7Fixture {
    param([string]$Directory, [int]$PatchCount)
    $null = New-Item -ItemType Directory -Path $Directory
    [IO.File]::WriteAllText((Join-Path $Directory 'CashPrediction-portable.zip'), 'full-fixture')
    $zip = Join-Path $Directory 'CashPrediction-portable.zip'
    $release = [ordered]@{
        schemaVersion = 1; releaseNumber = 200; commitSha = ('c' * 40)
        assetName = 'CashPrediction-portable.zip'; sizeBytes = (Get-Item -LiteralPath $zip).Length
        sha256 = (Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant()
        publishedAtUtc = '2026-10-03T00:00:00Z'; note = 'fixture'
    }
    Write-S7Json (Join-Path $Directory 'release.json') $release
    $patches = @()
    for ($i = 0; $i -lt $PatchCount; $i++) {
        $number = 101 - $i
        $asset = if ($i -eq 0) { 'CashPrediction.cpdelta' } else { "CashPrediction.from-$number.cpdelta" }
        $path = Join-Path $Directory $asset
        [IO.File]::WriteAllText($path, "patch-$number")
        $patches += [ordered]@{
            algorithm = 'cashprediction-tree-delta'; algorithmVersion = 1
            baseReleaseNumber = $number; baseCommitSha = ('a' * 40); baseTreeSha256 = ('b' * 64)
            assetName = $asset; sizeBytes = (Get-Item -LiteralPath $path).Length
            sha256 = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
        }
    }
    Write-S7Json (Join-Path $Directory 'update.json') ([ordered]@{
        schemaVersion = 2; releaseNumber = 200; commitSha = ('c' * 40); version = '200'
        publishedAtUtc = '2026-10-03T00:00:00Z'; assetName = $release.assetName
        sha256 = $release.sha256; sizeBytes = $release.sizeBytes; treeSha256 = ('d' * 64)
        files = @(); deltaPatches = $patches
    })
}

<# .SYNOPSIS Выполняет production publish с mocks и проверяет последствия отказа. #>
function Test-S7PublishCase {
    param([string]$Directory, [bool]$Exists, [bool]$Orphan, [int]$Failure)
    $script:Calls = [Collections.Generic.List[object]]::new()
    $script:FailAt = $Failure
    $script:LatestExists = $Exists
    $script:OrphanExists = $Orphan
    $script:Keep = @('update-base-101', 'update-base-100')
    $problem = $null
    try { Publish-S7Latest $Directory 'fixture-owner/fixture-repo' ('c' * 40) 'fixture-title' 'fixture-notes' $script:Keep }
    catch { $problem = $_ }
    Assert-S7Test (($Failure -gt 0) -eq ($null -ne $problem)) "unexpected result at $Failure : $problem"
    if ($problem -and $problem.Exception.Message -match 'UNEXPECTED|FORBIDDEN') { throw $problem }
    $pointer = -1
    for ($i = 0; $i -lt $script:Calls.Count; $i++) {
        $call = $script:Calls[$i]
        $names = @($call | ForEach-Object { [IO.Path]::GetFileName($_) })
        if ($call[0] -eq 'release' -and $call[1] -in 'create', 'upload') {
            if ($names -contains 'update.json') {
                Assert-S7Test ($call[1] -eq 'upload' -and $call.Count -eq 5) 'pointer must be a separate upload'
                $pointer = $i
            } else {
                Assert-S7Test ($names -contains 'release.json' -and $names -contains 'CashPrediction-portable.zip') 'full fallback and release metadata missing'
            }
        }
        if ($call[0] -eq 'release' -and $call[1] -in 'delete', 'delete-asset') {
            Assert-S7Test ($pointer -ge 0 -and $pointer -lt $i -and ($Failure -eq 0 -or $Failure -gt $pointer + 1)) 'cleanup before successful pointer'
            Assert-S7Test ($call[2] -notin $script:Keep -and $call[2] -ne 'update-base-98') 'retained or draft base removed'
            Assert-S7Test ($call[1] -ne 'delete' -or $call[2] -ne 'latest') 'latest release removed'
        }
    }
    if (-not $Failure) {
        Assert-S7Test ($pointer -gt 0) 'no pointer upload'
        Assert-S7Test (@($script:Calls | Where-Object { $_[0] -eq 'release' -and $_[1] -eq 'delete' -and $_[2] -eq 'update-base-99' }).Count -eq 1) 'pagination retention missing'
    }
    return $script:Calls.Count
}

$savedExitCode = $global:LASTEXITCODE
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cashprediction-s7-mocks-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $fixtureRoot
try {
    foreach ($patchCount in 0, 1, 2) {
        $directory = Join-Path $fixtureRoot "assets-$patchCount"
        New-S7Fixture $directory $patchCount
        foreach ($configuration in @(@($true, $false), @($false, $false), @($false, $true))) {
            $count = Test-S7PublishCase $directory $configuration[0] $configuration[1] 0
            for ($failure = 1; $failure -le $count; $failure++) {
                Test-S7PublishCase $directory $configuration[0] $configuration[1] $failure | Out-Null
            }
        }
    }
    # Испорченный payload блокирует все GitHub вызовы, в том числе чтение existence.
    $bad = Join-Path $fixtureRoot 'bad-assets'
    New-S7Fixture $bad 2
    [IO.File]::AppendAllText((Join-Path $bad 'CashPrediction.cpdelta'), 'corrupt')
    $script:Calls.Clear()
    $problem = $null
    try { Publish-S7Latest $bad 'fixture-owner/fixture-repo' ('c' * 40) 'title' 'notes' @() } catch { $problem = $_ }
    Assert-S7Test ($null -ne $problem -and $script:Calls.Count -eq 0) 'corrupt patch reached publication'

    # Archive получает номер и target из опубликованного release.json, не из текущего commit.
    $baseDirectory = Join-Path $fixtureRoot 'base'
    New-S7Fixture $baseDirectory 0
    $base = [pscustomobject]@{
        releaseNumber = 200; commitSha = ('c' * 40); treeSha256 = ('d' * 64)
        archive = (Join-Path $baseDirectory 'CashPrediction-portable.zip')
        releasePath = (Join-Path $baseDirectory 'release.json')
        updatePath = (Join-Path $baseDirectory 'update.json')
    }
    # Только транспорт readback заменён; production archive/same-base проверки исполняются.
    function Get-S7Base {
        param($Tag, $Directory, $Java, $ToolArguments)
        $script:Readbacks++
        if ($script:BadReadback -eq 'identity') {
            return [pscustomobject]@{ releaseNumber = 200; commitSha = ('e' * 40); treeSha256 = ('d' * 64) }
        }
        if ($script:BadReadback -in 'assets', 'bytes', 'incomplete') {
            if ($script:BadReadback -eq 'incomplete') { throw 'S7_BASE_INCOMPLETE' }
            $copy = $base.PSObject.Copy()
            if ($script:BadReadback -eq 'assets') { $copy.updatePath = $null }
            else { $copy.archive = Join-Path $fixtureRoot 'wrong-archive.zip' }
            return $copy
        }
        return $base
    }
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'wrong-archive.zip'), 'different published bytes')
    $script:BaseOrphanExists = $false
    foreach ($exists in $true, $false) {
        $script:BaseExists = $exists
        $script:Calls.Clear(); $script:FailAt = 0; $script:Readbacks = 0; $script:BadReadback = $false
        $tag = Save-S7Base $base 'fixture-owner/fixture-repo' $fixtureRoot 'mock-java' @('mock-tool')
        Assert-S7Test ($tag -ceq 'update-base-200' -and $script:Readbacks -eq 1) 'base readback missing'
        $writes = @($script:Calls | Where-Object { $_ -contains 'create' -or $_ -contains 'upload' -or $_ -contains 'delete' -or $_ -contains 'PATCH' })
        if ($exists) { Assert-S7Test ($writes.Count -eq 0) 'existing immutable base was overwritten' }
        else {
            Assert-S7Test ($writes.Count -eq 1 -and $writes[0] -contains ('c' * 40) -and $writes[0][2] -ceq 'update-base-200') 'archive target not from published identity'
        }
        foreach ($mode in 'identity', 'assets', 'bytes', 'incomplete') {
            $script:Calls.Clear(); $script:BadReadback = $mode
            $problem = $null
            try { Save-S7Base $base 'fixture-owner/fixture-repo' $fixtureRoot 'mock-java' @('mock-tool') | Out-Null } catch { $problem = $_ }
            Assert-S7Test ($null -ne $problem) "conflicting immutable base accepted: $mode"
            Assert-S7Test (@($script:Calls | Where-Object { $_ -contains 'delete' -or $_ -contains 'upload' -or $_ -contains 'PATCH' }).Count -eq 0) 'archive conflict caused overwrite/delete'
        }
    }
    # Отказ каждой границы archive creation не разрешает repair через delete/clobber.
    $script:BaseExists = $false; $script:BadReadback = $false
    foreach ($failure in 1, 2, 3) {
        $script:Calls.Clear(); $script:FailAt = $failure
        $problem = $null
        try { Save-S7Base $base 'fixture-owner/fixture-repo' $fixtureRoot 'mock-java' @('mock-tool') | Out-Null } catch { $problem = $_ }
        Assert-S7Test ($null -ne $problem -and $script:Calls.Count -eq $failure) 'archive failure ignored'
        Assert-S7Test (@($script:Calls | Where-Object { $_ -contains 'delete' -or $_ -contains 'upload' -or $_ -contains 'PATCH' }).Count -eq 0) 'failed archive was destructively repaired'
    }
    $script:Calls.Clear(); $script:FailAt = 0; $script:BaseOrphanExists = $true
    $problem = $null
    try { Save-S7Base $base 'fixture-owner/fixture-repo' $fixtureRoot 'mock-java' @('mock-tool') | Out-Null } catch { $problem = $_ }
    Assert-S7Test ($null -ne $problem -and $script:Calls.Count -eq 2) 'orphan immutable tag overwritten'
    # Подмена caller identity должна быть обнаружена до первого gh.
    $base.commitSha = 'f' * 40
    $script:Calls.Clear()
    $problem = $null
    try { Save-S7Base $base 'fixture-owner/fixture-repo' $fixtureRoot 'mock-java' @('mock-tool') | Out-Null } catch { $problem = $_ }
    Assert-S7Test ($null -ne $problem -and $script:Calls.Count -eq 0) 'caller SHA replaced published SHA'
    Write-Output 'S7 release mocks: PASS (local fixtures only)'
} finally {
    # Удаляем только собственный UUID-каталог, предварительно проверив абсолютную границу.
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^cashprediction-s7-mocks-[0-9a-f-]{36}$') { throw 'S7_TEST_CLEANUP_BOUNDARY' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    $global:LASTEXITCODE = $savedExitCode
}
