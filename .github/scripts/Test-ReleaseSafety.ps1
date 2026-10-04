# Исполняет только publish-блок настоящего workflow с локальными gh/git-моками.
# Ни subprocess, ни сеть, ни чтение productionJARs для этой проверки не нужны.
$ErrorActionPreference = 'Stop'
$workflow = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../workflows/release.yml') -Raw
$publishSection = ($workflow -split '      - name: Publish latest release\r?\n', 2)[1]
if (-not $publishSection) { throw 'Не найден publish-блок workflow.' }
if ($publishSection -notmatch 'GH_REPO: \$\{\{ github.repository \}\}') { throw 'Нет явного owner/repo из github.repository.' }
$publishText = ($publishSection -split '        run: \|\r?\n', 2)[1]
$publishText = [regex]::Replace($publishText, '(?m)^          ', '')
$publishText = $publishText.Replace('${{ github.sha }}', ('a' * 40))
if ($publishText -match '(?m)^\s*(git|gh)\s') { throw 'Обход Invoke-Gh в publish-блоке.' }
$publish = [scriptblock]::Create($publishText)
if ($publishText -notmatch 'S7-Publish\.ps1') { throw 'Workflow не использует единственный publish entry S7.' }

# Создаёт ответ очереди: проверяется каждый вызов, включая повторные попытки.
function New-Reply {
    param([string]$Call, [int]$Status = 200, [string]$Body = '{}', [string]$Problem = '')
    [pscustomobject]@{
        Call = $Call
        Code = $(if ($Status -eq 200) { 0 } else { 1 })
        Output = $(if ($Call -like 'api *--include') { @("HTTP/2.0 $Status", '', $Body) } else { @($Body) })
        Problem = $(if ($Problem) { $Problem } elseif ($Status -ne 200) { "gh: failed (HTTP $Status)" } else { '' })
    }
}

# Полностью перекрывает executable gh; неожиданный вызов немедленно блокируется.
function gh {
    $call = $args -join ' '
    $global:CashPredictionReleaseSafetyMockState.Calls.Add($call)
    if ($global:CashPredictionReleaseSafetyMockState.Replies.Count -eq 0) { throw "Неожиданный вызов gh: $call" }
    $reply = $global:CashPredictionReleaseSafetyMockState.Replies.Dequeue()
    if ($call -cne $reply.Call) { throw "Ожидался '$($reply.Call)', получен '$call'." }
    $global:LASTEXITCODE = $reply.Code
    if ($reply.Problem) { Write-Error $reply.Problem -ErrorAction Continue }
    $reply.Output
}

# Любое возвращение к git вместо API должно провалить проверку, не запуская git.
function git { throw 'В publish-блоке запрещён запуск git.' }

# Проверяет исход выполнения и полное потребление очереди, исключая скрытый fallback.
function Test-PublishCase {
    param([string]$Name, [object[]]$Replies, [bool]$MustFail = $false,
        [string]$ExpectedFailure = '')
    # Отрицательный case обязан закрепить причину, а не принять любое исключение.
    if ($MustFail -and [string]::IsNullOrWhiteSpace($ExpectedFailure)) {
        throw "RELEASE_SAFETY_EXPECTED_FAILURE_REQUIRED: $Name"
    }
    # Вызываемый entry является отдельным script scope; состояние мока явно общее для него.
    $global:CashPredictionReleaseSafetyMockState = @{
        Replies = [System.Collections.Generic.Queue[object]]::new()
        Calls = [System.Collections.Generic.List[string]]::new()
    }
    foreach ($reply in $Replies) { $global:CashPredictionReleaseSafetyMockState.Replies.Enqueue($reply) }
    # Entry создаёт рабочую папку даже при отказе GitHub: каждый case получает новый own Temp.
    $env:RUNNER_TEMP = Join-Path $fixtureRoot ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $env:RUNNER_TEMP | Out-Null
    Copy-Item -LiteralPath $fixtureState -Destination (Join-Path $env:RUNNER_TEMP 's7-state.json')
    $failure = $null
    try { & $publish | Out-Null } catch { $failure = $_ }
    if ($MustFail -ne ($null -ne $failure)) { throw "${Name}: неожиданный исход: $failure`n$($failure.ScriptStackTrace)" }
    if ($failure -and $failure.Exception.Message -match 'Неожиданный вызов|Ожидался|запуск git') { throw $failure }
    if ($ExpectedFailure -and ($null -eq $failure -or $failure.Exception.Message -notmatch $ExpectedFailure)) {
        throw "RELEASE_SAFETY_FAILURE_CATEGORY: ${Name}: отказ не соответствует ожидаемой причине '$ExpectedFailure': $failure"
    }
    if ($global:CashPredictionReleaseSafetyMockState.Replies.Count -ne 0) { throw "${Name}: не выполнена ожидаемая последовательность." }
    Write-Output "PASS: $Name ($($global:CashPredictionReleaseSafetyMockState.Calls.Count) mocked calls)"
}

$savedEnvironment = @{}
foreach ($name in 'GITHUB_WORKSPACE', 'GH_REPO', 'GH_RETRY_DELAYS', 'RELEASE_NUMBER', 'RELEASE_NOTE', 'RUNNER_TEMP', 'S7_APPROVED_SHA') {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$savedExitCode = $global:LASTEXITCODE
$savedMockState = Get-Variable -Name CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue
# PSVariable остаётся живой ссылкой: сохраняем прежнее значение до первого назначения мока.
$savedMockValue = if ($savedMockState) { $savedMockState.Value } else { $null }
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-release-safety-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    $env:GITHUB_WORKSPACE = Join-Path $fixtureRoot 'workspace'
    $scriptDirectory = Join-Path $env:GITHUB_WORKSPACE '.github/scripts'
    New-Item -ItemType Directory -Path $scriptDirectory -Force | Out-Null
    foreach ($file in 'S7-Publish.ps1', 'S7-Release.ps1', 'GhRetry.ps1') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $scriptDirectory
    }
    . (Join-Path $scriptDirectory 'S7-Release.ps1')
    $env:GH_REPO = 'fixture-owner/fixture-repo'
    $env:GH_RETRY_DELAYS = '0,0'
    $env:RELEASE_NUMBER = '123'
    $env:RELEASE_NOTE = 'fixture-note'
    $env:S7_APPROVED_SHA = ('a' * 40)
    $releaseGet = 'api repos/fixture-owner/fixture-repo/releases/tags/latest --method GET --include'
    $tagGet = 'api repos/fixture-owner/fixture-repo/git/ref/tags/latest --method GET --include'
    $tagPatch = 'api repos/fixture-owner/fixture-repo/git/refs/tags/latest --method PATCH --raw-field sha=' + ('a' * 40) + ' --field force=true'
    # Настоящий entry проверяет SHA/размеры payload перед вызовом GitHub.
    $out = Join-Path $env:GITHUB_WORKSPACE 's7-artifacts'
    New-Item -ItemType Directory -Path $out | Out-Null
    $zip = Join-Path $out 'CashPrediction-portable.zip'
    $manifest = Join-Path $out 'release.json'
    $pointer = Join-Path $out 'update.json'
    [IO.File]::WriteAllText($zip, 'isolated-full-container-fixture')
    $size = (Get-Item -LiteralPath $zip).Length
    $hash = (Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant()
    Write-S7Json $manifest ([ordered]@{schemaVersion=1;releaseNumber=123;commitSha=('a'*40);
        assetName='CashPrediction-portable.zip';sizeBytes=$size;sha256=$hash})
    Write-S7Json $pointer ([ordered]@{schemaVersion=2;releaseNumber=123;commitSha=('a'*40);
        version='123';publishedAtUtc='2026-10-04T00:00:00Z';treeSha256=('d'*64);files=@();
        assetName='CashPrediction-portable.zip';sizeBytes=$size;sha256=$hash;deltaPatches=@()})
    $fixtureState = Join-Path $fixtureRoot 'state-fixture.json'
    Write-S7Json $fixtureState ([ordered]@{repository=$env:GH_REPO;releaseNumber=123;
        commitSha=('a'*40);bases=@();toolCommand=@{java='unused';arguments=@()}})
    $title = 'CashPrediction, версия 123'
    $notes = "fixture-note`n`nCashPrediction-portable.zip`n$('a' * 40)"
    $create = "release create latest $zip $manifest --target $('a' * 40) --latest --title $title --notes $notes"
    $edit = "release edit latest --title $title --notes $notes --latest"
    $uploads = @(
        (New-Reply "release upload latest $zip $manifest --clobber")
        (New-Reply "release upload latest $pointer --clobber")
    )
    $assetView = New-Reply 'release view latest --json assets' 200 '{"assets":[{"name":"CashPrediction-portable.zip"},{"name":"release.json"},{"name":"old.zip"}]}'
    $cleanup = New-Reply 'release delete-asset latest old.zip --yes'
    $retention = New-Reply 'api repos/fixture-owner/fixture-repo/releases?per_page=100 --paginate --slurp' 200 '[[]]'

    # Проверяем настоящий entry до первого GitHub обращения, а не только helper.
    $env:S7_APPROVED_SHA = ('b' * 40)
    try { Test-PublishCase 'unapproved SHA blocks all GitHub calls' @() $true '^S7_RELEASE_MATRIX_NOT_APPROVED$' }
    finally { $env:S7_APPROVED_SHA = ('a' * 40) }
    $savedZipBytes = [IO.File]::ReadAllBytes($zip)
    try {
        [IO.File]::AppendAllText($zip, 'corrupt')
        Test-PublishCase 'corrupt payload blocks all GitHub calls' @() $true '^S7_CONTAINER_MISMATCH$'
    } finally { [IO.File]::WriteAllBytes($zip, $savedZipBytes) }
    # Точный конец очереди запрещает pointer после payload failure и cleanup после pointer failure.
    Test-PublishCase 'payload upload 403 blocks pointer and cleanup' @(
        (New-Reply $releaseGet), (New-Reply "release upload latest $zip $manifest --clobber" 403)
    ) $true '^S7_GITHUB_FAILED: release upload$'
    Test-PublishCase 'pointer upload 403 blocks cleanup and tag update' @(
        (New-Reply $releaseGet), $uploads[0], (New-Reply "release upload latest $pointer --clobber" 403)
    ) $true '^S7_GITHUB_FAILED: release upload$'
    Test-PublishCase 'pointer exhausted 503 blocks cleanup and tag update' @(
        (New-Reply $releaseGet), $uploads[0],
        (New-Reply "release upload latest $pointer --clobber" 503),
        (New-Reply "release upload latest $pointer --clobber" 503),
        (New-Reply "release upload latest $pointer --clobber" 503)
    ) $true '^S7_GITHUB_FAILED: release upload$'

    Test-PublishCase '404 release + 404 tag: create' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet 404), (New-Reply $create),
        $uploads[1], $assetView, $cleanup, $retention, (New-Reply $tagPatch), (New-Reply $edit)
    )
    Test-PublishCase '404 release + existing tag: PATCH then create, no deletion' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet), (New-Reply $tagPatch), (New-Reply $create),
        $uploads[1], $assetView, $cleanup, $retention, (New-Reply $tagPatch), (New-Reply $edit)
    )
    Test-PublishCase 'existing release: zip, manifest, cleanup, PATCH, edit' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup, $retention, (New-Reply $tagPatch), (New-Reply $edit)))
    foreach ($status in 401, 403, 422) {
        Test-PublishCase "release HTTP $status blocks all writes" @((New-Reply $releaseGet $status)) $true (
            '^' + [regex]::Escape("Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=$status. Публикация остановлена.") + '$')
        Test-PublishCase "tag HTTP $status blocks all writes" @(
            (New-Reply $releaseGet 404), (New-Reply $tagGet $status)
        ) $true ('^' + [regex]::Escape("Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/git/ref/tags/latest: exit=1, HTTP=$status. Публикация остановлена.") + '$')
    }
    Test-PublishCase 'release exhausted 503: three attempts, no writes' @(
        (New-Reply $releaseGet 503), (New-Reply $releaseGet 503), (New-Reply $releaseGet 503)
    ) $true ('^' + [regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=503. Публикация остановлена.') + '$')
    Test-PublishCase 'tag exhausted 503: no writes' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet 503), (New-Reply $tagGet 503), (New-Reply $tagGet 503)
    ) $true ('^' + [regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/git/ref/tags/latest: exit=1, HTTP=503. Публикация остановлена.') + '$')
    Test-PublishCase '503 then 200 uses final response' (@(
        (New-Reply $releaseGet 503), (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup, $retention, (New-Reply $tagPatch), (New-Reply $edit)))
    foreach ($status in 429, 501) {
        Test-PublishCase "HTTP $status retries then confirmed 404 creates" @(
            (New-Reply $releaseGet $status), (New-Reply $releaseGet 404),
            (New-Reply $tagGet 404), (New-Reply $create), $uploads[1], $assetView, $cleanup,
            $retention, (New-Reply $tagPatch), (New-Reply $edit)
        )
    }
    $transport = New-Reply $releaseGet 0 '{}' 'network failure; URL contains 404'
    $transport.Output = @('404 is mentioned, but there is no HTTP status line')
    Test-PublishCase 'network text mentioning 404 is fatal' @($transport) $true (
        '^' + [regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=1, HTTP=0. Публикация остановлена.') + '$')
    $empty = New-Reply $releaseGet
    $empty.Output = @()
    Test-PublishCase 'success exit without HTTP status is fatal' @($empty) $true (
        '^' + [regex]::Escape('Не удалось проверить API-ресурс repos/fixture-owner/fixture-repo/releases/tags/latest: exit=0, HTTP=0. Публикация остановлена.') + '$')
    Test-PublishCase 'asset list 403 stops cleanup and tag update' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @((New-Reply 'release view latest --json assets' 403))) $true '^S7_GITHUB_FAILED: release view$'
    Test-PublishCase 'PATCH exhausted 503 stops edit, no fallback' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup, $retention,
        (New-Reply $tagPatch 503), (New-Reply $tagPatch 503), (New-Reply $tagPatch 503))) $true '^S7_GITHUB_FAILED: api repos/fixture-owner/fixture-repo/git/refs/tags/latest$'
    Test-PublishCase 'orphan PATCH 403 stops create, no fallback' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet), (New-Reply $tagPatch 403)
    ) $true '^S7_GITHUB_FAILED: api repos/fixture-owner/fixture-repo/git/refs/tags/latest$'
    Write-Output 'Release safety: PASS (local PowerShell mocks only; no network mutations)'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
    }
    $global:LASTEXITCODE = $savedExitCode
    if ($savedMockState) { $global:CashPredictionReleaseSafetyMockState = $savedMockValue }
    else { Remove-Variable -Name CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue }
}
