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
    $script:Calls.Add($call)
    if ($script:Replies.Count -eq 0) { throw "Неожиданный вызов gh: $call" }
    $reply = $script:Replies.Dequeue()
    if ($call -cne $reply.Call) { throw "Ожидался '$($reply.Call)', получен '$call'." }
    $global:LASTEXITCODE = $reply.Code
    if ($reply.Problem) { Write-Error $reply.Problem -ErrorAction Continue }
    $reply.Output
}

# Любое возвращение к git вместо API должно провалить проверку, не запуская git.
function git { throw 'В publish-блоке запрещён запуск git.' }

# Проверяет исход выполнения и полное потребление очереди, исключая скрытый fallback.
function Test-PublishCase {
    param([string]$Name, [object[]]$Replies, [bool]$MustFail = $false)
    $script:Replies = [System.Collections.Generic.Queue[object]]::new()
    foreach ($reply in $Replies) { $script:Replies.Enqueue($reply) }
    $script:Calls = [System.Collections.Generic.List[string]]::new()
    $failure = $null
    try { & $publish | Out-Null } catch { $failure = $_ }
    if ($MustFail -ne ($null -ne $failure)) { throw "${Name}: неожиданный исход: $failure" }
    if ($failure -and $failure.Exception.Message -match 'Неожиданный вызов|Ожидался|запуск git') { throw $failure }
    if ($script:Replies.Count -ne 0) { throw "${Name}: не выполнена ожидаемая последовательность." }
    Write-Output "PASS: $Name ($($script:Calls.Count) mocked calls)"
}

$savedEnvironment = @{}
foreach ($name in 'GITHUB_WORKSPACE', 'GH_REPO', 'GH_RETRY_DELAYS', 'RELEASE_NUMBER', 'RELEASE_NOTE') {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$savedExitCode = $global:LASTEXITCODE
try {
    $env:GITHUB_WORKSPACE = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $env:GH_REPO = 'fixture-owner/fixture-repo'
    $env:GH_RETRY_DELAYS = '0,0'
    $env:RELEASE_NUMBER = '123'
    $env:RELEASE_NOTE = 'fixture-note'
    $releaseGet = 'api repos/fixture-owner/fixture-repo/releases/tags/latest --method GET --include'
    $tagGet = 'api repos/fixture-owner/fixture-repo/git/ref/tags/latest --method GET --include'
    $tagPatch = 'api repos/fixture-owner/fixture-repo/git/refs/tags/latest --method PATCH --raw-field sha=' + ('a' * 40) + ' --field force=true'
    # Пути и notes вычисляем ровно так же, как workflow; assets на диске не нужны.
    $out = Join-Path $env:GITHUB_WORKSPACE 'artifacts'
    $zip = Join-Path $out 'CashPrediction-portable.zip'
    $manifest = Join-Path $out 'release.json'
    $title = 'CashPrediction, версия 123'
    $notes = @"
fixture-note

Скачайте CashPrediction-portable.zip, распакуйте в любую папку и запустите CashPrediction.exe (JavaFX), CashPrediction-Swing.exe или CashPrediction-Web.exe (откроется браузер). Установка и Java не нужны.

Сборка из коммита $('a' * 40).
"@
    $create = "release create latest $zip $manifest --target $('a' * 40) --latest --title $title --notes $notes"
    $edit = "release edit latest --title $title --notes $notes --latest"
    $uploads = @(
        (New-Reply "release upload latest $zip --clobber")
        (New-Reply "release upload latest $manifest --clobber")
    )
    $assetView = New-Reply 'release view latest --json assets' 200 '{"assets":[{"name":"CashPrediction-portable.zip"},{"name":"release.json"},{"name":"old.zip"}]}'
    $cleanup = New-Reply 'release delete-asset latest old.zip --yes'

    Test-PublishCase '404 release + 404 tag: create' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet 404), (New-Reply $create)
    )
    Test-PublishCase '404 release + existing tag: PATCH then create, no deletion' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet), (New-Reply $tagPatch), (New-Reply $create)
    )
    Test-PublishCase 'existing release: zip, manifest, cleanup, PATCH, edit' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup, (New-Reply $tagPatch), (New-Reply $edit)))
    foreach ($status in 401, 403, 422) {
        Test-PublishCase "release HTTP $status blocks all writes" @((New-Reply $releaseGet $status)) $true
        Test-PublishCase "tag HTTP $status blocks all writes" @(
            (New-Reply $releaseGet 404), (New-Reply $tagGet $status)
        ) $true
    }
    Test-PublishCase 'release exhausted 503: three attempts, no writes' @(
        (New-Reply $releaseGet 503), (New-Reply $releaseGet 503), (New-Reply $releaseGet 503)
    ) $true
    Test-PublishCase 'tag exhausted 503: no writes' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet 503), (New-Reply $tagGet 503), (New-Reply $tagGet 503)
    ) $true
    Test-PublishCase '503 then 200 uses final response' (@(
        (New-Reply $releaseGet 503), (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup, (New-Reply $tagPatch), (New-Reply $edit)))
    foreach ($status in 429, 501) {
        Test-PublishCase "HTTP $status retries then confirmed 404 creates" @(
            (New-Reply $releaseGet $status), (New-Reply $releaseGet 404),
            (New-Reply $tagGet 404), (New-Reply $create)
        )
    }
    $transport = New-Reply $releaseGet 0 '{}' 'network failure; URL contains 404'
    $transport.Output = @('404 is mentioned, but there is no HTTP status line')
    Test-PublishCase 'network text mentioning 404 is fatal' @($transport) $true
    $empty = New-Reply $releaseGet
    $empty.Output = @()
    Test-PublishCase 'success exit without HTTP status is fatal' @($empty) $true
    Test-PublishCase 'asset list 403 stops cleanup and tag update' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @((New-Reply 'release view latest --json assets' 403))) $true
    Test-PublishCase 'PATCH exhausted 503 stops edit, no fallback' (@(
        (New-Reply $releaseGet)
    ) + $uploads + @($assetView, $cleanup,
        (New-Reply $tagPatch 503), (New-Reply $tagPatch 503), (New-Reply $tagPatch 503))) $true
    Test-PublishCase 'orphan PATCH 403 stops create, no fallback' @(
        (New-Reply $releaseGet 404), (New-Reply $tagGet), (New-Reply $tagPatch 403)
    ) $true
    Write-Output 'Release safety: PASS (local PowerShell mocks only; no network mutations)'
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
    }
    $global:LASTEXITCODE = $savedExitCode
}
