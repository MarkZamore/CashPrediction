<#
.SYNOPSIS
Проверяет готовую портативную папку CashPrediction так, как её увидит пользователь.

.DESCRIPTION
Копирует папку в путь с пробелами и кириллицей (классическая ловушка лаунчеров), запускает
каждый из трёх exe и убеждается, что:
  * JavaFX- и Swing-клиенты стартуют и начинают запись сессии (появляется session-<клиент>.xml);
  * web-сервер отвечает страницей CashPrediction, а API без токена закрыт (403);
  * рядом с exe появилась только папка CashMemory, и в ней только ожидаемые файлы.
Процессы завершаются вместе с дочерними: лаунчер jpackage перезапускает себя дочерним процессом.

Скрипт используется и в CI (release.yml), и локально:
  powershell -ExecutionPolicy Bypass -File .github\scripts\Test-Portable.ps1 -PortableDir dist\target\dist\CashPrediction

.PARAMETER CleanRegistry
Удалить узел HKCU\Software\JavaSoft\Prefs\ru\cashprediction после проверки. В CI машина одноразовая;
локально ключ не указывают, чтобы не стереть снимки настоящих сеансов.
#>
param(
    [Parameter(Mandatory = $true)][string]$PortableDir,
    [string]$WorkDir = (Join-Path ([IO.Path]::GetTempPath()) 'CashPrediction portable test'),
    [int]$StartTimeoutSeconds = 90,
    [switch]$CleanRegistry
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Fail([string]$message) {
    Write-Host "ОШИБКА: $message"
    $failures.Add($message)
}

# Завершает процесс и всех его потомков.
function Stop-Tree([int]$rootId) {
    $all = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId)
    $ids = [System.Collections.Generic.HashSet[int]]::new()
    [void]$ids.Add($rootId)
    do {
        $added = $false
        foreach ($p in $all) {
            if ($ids.Contains([int]$p.ParentProcessId) -and $ids.Add([int]$p.ProcessId)) { $added = $true }
        }
    } while ($added)
    foreach ($id in $ids) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
}

if (-not (Test-Path -LiteralPath (Join-Path $PortableDir 'CashPrediction.exe') -PathType Leaf)) {
    throw "В папке нет CashPrediction.exe: $PortableDir"
}

# Копия в путь с пробелами и кириллицей.
$target = Join-Path $WorkDir 'Мои программы\CashPrediction'
if (Test-Path -LiteralPath $WorkDir) { Remove-Item -LiteralPath $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
Copy-Item -LiteralPath $PortableDir -Destination $target -Recurse
$originalEntries = @(Get-ChildItem -LiteralPath $target -Force | ForEach-Object Name)
$cashMemory = Join-Path $target 'CashMemory'
Write-Host "Проверяем копию: $target"

foreach ($client in @(
        @{ Exe = 'CashPrediction.exe'; Marker = 'session-fx.xml'; Name = 'JavaFX' },
        @{ Exe = 'CashPrediction-Swing.exe'; Marker = 'session-swing.xml'; Name = 'Swing' })) {
    $process = Start-Process -FilePath (Join-Path $target $client.Exe) -PassThru
    $markerPath = Join-Path $cashMemory $client.Marker
    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $markerPath)) {
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path -LiteralPath $markerPath) {
        # Процесс должен продолжать работать, а не упасть сразу после старта записи.
        Start-Sleep -Seconds 3
        $alive = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq (Join-Path $target $client.Exe) }).Count -gt 0
        if ($alive) { Write-Host "$($client.Name): запустился, запись сессии идёт." }
        else { Fail "$($client.Name): процесс завершился после старта." }
    }
    else {
        Fail "$($client.Name): за $StartTimeoutSeconds с не появился $($client.Marker) (процесс завершён: $($process.HasExited))."
    }
    Stop-Tree $process.Id
    Start-Sleep -Seconds 2
}

# Web-сервер: окно статуса и браузер отключены, порт по умолчанию 8765, при занятости выбирается свободный.
$web = Start-Process -FilePath (Join-Path $target 'CashPrediction-Web.exe') -ArgumentList '--no-browser', '--no-window' -PassThru
$found = $null
$deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
while ((Get-Date) -lt $deadline -and -not $found -and -not $web.HasExited) {
    foreach ($port in 8765..8775) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/" -TimeoutSec 2
            if ($response.StatusCode -eq 200 -and $response.Content -match '<title>[^<]*CashPrediction') { $found = $port; break }
        } catch { }
    }
    if (-not $found) { Start-Sleep -Milliseconds 700 }
}
if ($found) {
    Write-Host "Web: сервер отвечает на порту $found."
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$found/api/state" -TimeoutSec 5 | Out-Null
        Fail "Web: API ответил без токена."
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 403) { Write-Host "Web: API без токена закрыт (403)." }
        else { Fail "Web: API без токена вернул $code вместо 403." }
    }
}
else {
    Fail "Web: сервер не ответил за $StartTimeoutSeconds с (процесс завершён: $($web.HasExited))."
}
Stop-Tree $web.Id
Start-Sleep -Seconds 2

# Рядом с exe должна появиться только CashMemory, а в ней только файлы приложения.
$newEntries = @(Get-ChildItem -LiteralPath $target -Force | ForEach-Object Name | Where-Object { $originalEntries -notcontains $_ })
if ((@($newEntries) -join ',') -ne 'CashMemory') { Fail "Рядом с exe появилось лишнее: $($newEntries -join ', ')" }
$allowed = '^(session-(fx|swing)\.xml|web-session(\.plan)?\.md|settings\.md|[^\\/]+\.md)$'
$unexpected = @(Get-ChildItem -LiteralPath $cashMemory -Force -Recurse -ErrorAction SilentlyContinue |
    ForEach-Object Name | Where-Object { $_ -notmatch $allowed })
if ($unexpected.Count -gt 0) { Fail "В CashMemory неожиданные файлы: $($unexpected -join ', ')" }
Write-Host ("CashMemory: " + ((Get-ChildItem -LiteralPath $cashMemory -Force -ErrorAction SilentlyContinue | ForEach-Object Name) -join ', '))

if ($CleanRegistry) {
    Remove-Item -Path 'HKCU:\Software\JavaSoft\Prefs\ru\cashprediction' -Recurse -Force -ErrorAction SilentlyContinue
}
Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue

if ($failures.Count -gt 0) {
    Write-Host "Проверка портативной сборки: $($failures.Count) ошибок."
    exit 1
}
Write-Host "Проверка портативной сборки пройдена."
exit 0
