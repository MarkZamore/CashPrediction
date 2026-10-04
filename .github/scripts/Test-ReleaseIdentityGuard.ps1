<#
.SYNOPSIS
Проверяет настоящий guard из release.yml на локальных Git-моках и синтетических JAR/ZIP.
.DESCRIPTION
Не запускает Git, Maven, Java, GUI или сеть и не создаёт коммиты.
Все временные файлы принадлежат уникальному стенду; production-кандидаты не читаются.
#>
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$workflow = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../workflows/release.yml'))
$guardName = 'Verify release Git and embedded AppInfo'
$section = [regex]::Match($workflow, '(?ms)^      - name: ' + $guardName + '\r?\n.*?        run: \|\r?\n(?<body>.*?)(?=^      - name: )')
if (-not $section.Success) { throw 'Не найден самостоятельный guard перед publish.' }
$guardText = [regex]::Replace($section.Groups['body'].Value, '(?m)^          ', '')
$tokens = $null; $parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseInput($guardText, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Синтаксис guard повреждён.' }
$guard = [scriptblock]::Create($guardText)
$checks = 0

# Проверяет статический контракт workflow и считает успешные проверки.
function Assert-GuardFixture {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "RELEASE_GUARD_FIXTURE: $Message" }
    $script:checks++
}

Assert-GuardFixture ($workflow.Contains("if: github.ref == 'refs/heads/main' && (github.event_name == 'push' || github.event_name == 'workflow_dispatch')")) 'Нет main-only job guard.'
Assert-GuardFixture ($workflow -match 'fetch-depth: 0') 'Нет полной истории.'
Assert-GuardFixture ([regex]::Matches($workflow, '& \.github/scripts/Test-ReleaseSafety\.ps1').Count -eq 1) 'Повторный release safety.'
Assert-GuardFixture ([regex]::Matches($workflow, '& \.github/scripts/Test-ReleaseIdentityGuard\.ps1').Count -eq 1) 'Fixture не подключён.'
Assert-GuardFixture ($workflow.IndexOf('name: S7 release acceptance gate') -lt $section.Index -and
    $section.Index -lt $workflow.IndexOf('name: Publish latest release')) 'Guard должен стоять между approval и publish.'
Assert-GuardFixture ($workflow.Contains("if (`$env:S7_APPROVED_SHA -cne '`$`{{ github.sha }}') { throw 'S7_RELEASE_MATRIX_NOT_APPROVED' }")) 'Approval изменён.'
Assert-GuardFixture ($guardText -notmatch 'Invoke-Gh|\b(fetch|push|commit|checkout|reset)\s+--') 'Guard изменяет внешнее состояние.'

# Перекрывает любой вызов Git и проверяет ровно ожидаемую последовательность чтения.
function git {
    $call = @($args)
    if ($call.Count -lt 3 -or $call[0] -cne '-C' -or $call[1] -cne $env:GITHUB_WORKSPACE) {
        throw 'Неожиданный путь Git-мока.'
    }
    $command = $call[2..($call.Count - 1)] -join ' '
    $script:gitCalls++
    $global:LASTEXITCODE = 0
    if ($command -ceq 'rev-parse --is-shallow-repository') { $answer = 'false' }
    elseif ($command -ceq "rev-parse --verify $($env:GITHUB_SHA)^{commit}") { $answer = $env:GITHUB_SHA }
    elseif ($command -ceq 'rev-parse --verify HEAD') { $answer = $script:headSha }
    elseif ($command -ceq "merge-base --is-ancestor $($env:GITHUB_SHA) refs/remotes/origin/main") { $answer = $null }
    elseif ($command -ceq "rev-list --count $($env:GITHUB_SHA)") { $answer = $script:gitCount }
    else { throw "Неожиданный Git-вызов: $command" }
    if ($script:gitCalls -eq $script:gitFailure) { $global:LASTEXITCODE = 128 }
    if ($script:notMain -and $command.StartsWith('merge-base ')) { $global:LASTEXITCODE = 1 }
    if ($script:shallow -and $script:gitCalls -eq 1) { $answer = 'true' }
    if ($script:wrongCommit -and $script:gitCalls -eq 2) { $answer = 'b' * 40 }
    return $answer
}

# Создаёт синтетический ZIP в памяти; entry names могут повторяться для негативных проверок.
function New-GuardZipBytes {
    param([object[]]$Entries)
    $stream = [IO.MemoryStream]::new()
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($item in $Entries) {
            $created = $zip.CreateEntry($item.Name)
            if ($item.ContainsKey('Attributes')) { $created.ExternalAttributes = $item.Attributes }
            $output = $created.Open()
            try { $output.Write([byte[]]$item.Bytes) } finally { $output.Dispose() }
        }
    } finally { $zip.Dispose() }
    try { return ,$stream.ToArray() } finally { $stream.Dispose() }
}

# Создаёт fixture AppInfo без Java; class bytes здесь только маркер наличия записи.
function New-GuardJarBytes {
    param([string]$Properties, [switch]$Duplicate, [switch]$NoClass, [switch]$NoProperties, [byte]$ClassMarker = 1,
        [object[]]$ExtraEntries = @())
    $entries = @()
    if (-not $NoProperties) {
        $entry = @{ Name = 'ru/cashprediction/core/app.properties'; Bytes = [Text.Encoding]::UTF8.GetBytes($Properties) }
        $entries += $entry
        if ($Duplicate) { $entries += $entry }
    }
    if (-not $NoClass) { $entries += @{ Name = 'ru/cashprediction/core/io/AppInfo.class'; Bytes = [byte[]]@($ClassMarker) } }
    return ,(New-GuardZipBytes ($entries + $ExtraEntries))
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('CashPrediction-release-guard-' + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
foreach ($name in 'GITHUB_WORKSPACE', 'GITHUB_SHA', 'GITHUB_REF', 'GITHUB_EVENT_NAME', 'RELEASE_NUMBER') {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$savedExitCode = $global:LASTEXITCODE
try {
    $fixtureWorkspace = Join-Path $fixtureRoot 'Проверка Δ'
    $appRoot = Join-Path $fixtureWorkspace 'dist/target/dist/CashPrediction/app'
    $artifactRoot = Join-Path $fixtureWorkspace 's7-artifacts'
    [void][IO.Directory]::CreateDirectory($appRoot)
    [void][IO.Directory]::CreateDirectory($artifactRoot)
    $sha = 'a' * 40
    $goodProperties = "# Синтетические свойства`nrelease=23`ncommit=$sha`n"
    $goodJar = New-GuardJarBytes $goodProperties
    $script:headSha = $sha; $script:gitCount = '23'
    $script:shallow = $false; $script:wrongCommit = $false; $script:notMain = $false
    $script:gitFailure = 0
    $env:GITHUB_WORKSPACE = $fixtureWorkspace; $env:GITHUB_SHA = $sha
    $env:GITHUB_REF = 'refs/heads/main'; $env:GITHUB_EVENT_NAME = 'workflow_dispatch'; $env:RELEASE_NUMBER = '23'

    # Исполняет весь настоящий guard, включая сверку app-image и опубликованного ZIP.
    function Test-GuardCase {
        param([string]$Name, [string]$Failure = '', [byte[]]$Jar = $goodJar,
            [byte[]]$PackedJar = $Jar, [switch]$DuplicateZip, [string]$ManifestSha = $sha,
            [int]$ManifestRelease = 23, [switch]$SecondJar, [object[]]$ExtraZipEntries = @())
        $script:gitCalls = 0
        [IO.File]::WriteAllBytes((Join-Path $appRoot 'cashprediction-core-1.0.0.jar'), $Jar)
        $extraJar = Join-Path $appRoot 'cashprediction-core-2.0.0.jar'
        if ($SecondJar) { [IO.File]::WriteAllBytes($extraJar, $Jar) }
        $entry = @{ Name = 'CashPrediction/app/cashprediction-core-1.0.0.jar'; Bytes = $PackedJar }
        $entries = @($entry)
        if ($DuplicateZip) { $entries += $entry }
        [IO.File]::WriteAllBytes((Join-Path $artifactRoot 'CashPrediction-portable.zip'), (New-GuardZipBytes ($entries + $ExtraZipEntries)))
        foreach ($manifest in 'release.json', 'update.json') {
            [IO.File]::WriteAllText((Join-Path $artifactRoot $manifest), (@{
                commitSha = $ManifestSha; releaseNumber = $ManifestRelease
            } | ConvertTo-Json))
        }
        $problem = $null
        try { & $guard } catch { $problem = $_.Exception.Message }
        finally { if ($SecondJar) { [IO.File]::Delete($extraJar) } }
        Assert-GuardFixture ($(if ($Failure) { $problem -ceq $Failure } else { $null -eq $problem })) "${Name}: $problem"
        Write-Output "PASS: $Name"
    }

    Test-GuardCase 'main workflow_dispatch'
    $env:GITHUB_EVENT_NAME = 'push'; Test-GuardCase 'main push'
    $env:GITHUB_EVENT_NAME = 'workflow_dispatch'
    $env:GITHUB_REF = 'refs/heads/feature'; Test-GuardCase 'dispatch feature rejected' 'RELEASE_MAIN_ONLY'
    Assert-GuardFixture ($script:gitCalls -eq 0) 'Feature успела вызвать Git.'
    $env:GITHUB_REF = 'refs/heads/main'
    $env:GITHUB_EVENT_NAME = 'pull_request'; Test-GuardCase 'other event rejected' 'RELEASE_MAIN_ONLY'
    $env:GITHUB_EVENT_NAME = 'workflow_dispatch'
    $env:GITHUB_SHA = 'dummy'; Test-GuardCase 'invalid SHA' 'RELEASE_IDENTITY_FORMAT'; $env:GITHUB_SHA = $sha
    $env:RELEASE_NUMBER = '0'; Test-GuardCase 'development release' 'RELEASE_IDENTITY_FORMAT'; $env:RELEASE_NUMBER = '23'
    $script:shallow = $true; Test-GuardCase 'shallow history' 'RELEASE_SHALLOW_HISTORY'; $script:shallow = $false
    $codes = @('RELEASE_SHALLOW_HISTORY', 'RELEASE_COMMIT_MISSING', 'RELEASE_HEAD_MISMATCH', 'RELEASE_NOT_MAIN_ANCESTOR', 'RELEASE_COUNT_MISMATCH')
    for ($index = 0; $index -lt $codes.Count; $index++) {
        $script:gitFailure = $index + 1
        Test-GuardCase "Git failure $($index + 1)" $codes[$index]
        Assert-GuardFixture ($script:gitCalls -eq $index + 1) 'После Git failure guard продолжился.'
    }
    $script:gitFailure = 0
    $script:notMain = $true; Test-GuardCase 'feature SHA advertised as main' 'RELEASE_NOT_MAIN_ANCESTOR'; $script:notMain = $false
    $script:wrongCommit = $true; Test-GuardCase 'resolved commit mismatch' 'RELEASE_COMMIT_MISSING'; $script:wrongCommit = $false
    $script:headSha = 'b' * 40; Test-GuardCase 'HEAD mismatch' 'RELEASE_HEAD_MISMATCH'; $script:headSha = $sha
    $script:gitCount = '24'; Test-GuardCase 'count mismatch' 'RELEASE_COUNT_MISMATCH'; $script:gitCount = '23'
    Test-GuardCase 'manifest dummy SHA' 'RELEASE_MANIFEST_IDENTITY' -ManifestSha ('b' * 40)
    Test-GuardCase 'manifest candidate1003' 'RELEASE_MANIFEST_IDENTITY' -ManifestRelease 1003
    Test-GuardCase 'ambiguous image JAR' 'RELEASE_CORE_JAR_COUNT' -SecondJar
    Test-GuardCase 'missing resource' 'RELEASE_APPINFO_RESOURCE' -Jar (New-GuardJarBytes '' -NoProperties)
    Test-GuardCase 'missing AppInfo class' 'RELEASE_APPINFO_RESOURCE' -Jar (New-GuardJarBytes $goodProperties -NoClass)
    Test-GuardCase 'duplicate resource' 'RELEASE_ARCHIVE_DUPLICATE' -Jar (New-GuardJarBytes $goodProperties -Duplicate)
    Test-GuardCase 'duplicate key' 'RELEASE_APPINFO_DUPLICATE' -Jar (New-GuardJarBytes ($goodProperties + 'release=23'))
    Test-GuardCase 'unfiltered values' 'RELEASE_APPINFO_MISMATCH' -Jar (New-GuardJarBytes ('release=${app.release}' + "`n" + 'commit=${app.commit}'))
    Test-GuardCase 'dummy AppInfo SHA' 'RELEASE_APPINFO_MISMATCH' -Jar (New-GuardJarBytes "release=23`ncommit=$('b' * 40)")
    Test-GuardCase 'candidate AppInfo release' 'RELEASE_APPINFO_MISMATCH' -Jar (New-GuardJarBytes "release=1003`ncommit=$sha")
    Test-GuardCase 'escaped properties' 'RELEASE_APPINFO_FORMAT' -Jar (New-GuardJarBytes "release=23\`ncommit=$sha")
    Test-GuardCase 'duplicate ZIP JAR' 'RELEASE_ARCHIVE_DUPLICATE' -DuplicateZip
    Test-GuardCase 'same AppInfo different JAR bytes' 'RELEASE_ZIP_CORE_BYTES' -PackedJar (New-GuardJarBytes $goodProperties -ClassMarker 2)
    Test-GuardCase 'required good Unicode workspace and resources' -ExtraZipEntries @(
        @{Name='CashPrediction/';Bytes=[byte[]]@()},
        @{Name='CashPrediction/app/';Bytes=[byte[]]@()},
        @{Name='CashPrediction/app/данные/Δ.txt';Bytes=[byte[]]@(1)},
        @{Name='CashPrediction/runtime/';Bytes=[byte[]]@()}) -Jar (New-GuardJarBytes $goodProperties -ExtraEntries @(
        @{Name='ru/cashprediction/core/ui/text/справка_Δ.txt';Bytes=[byte[]]@(2)},
        @{Name='META-INF/';Bytes=[byte[]]@()}))
    Test-GuardCase 'required good CR-only Java properties and Russian comments' -Jar (
        New-GuardJarBytes "`t# Русский комментарий`rrelease=23`rcommit=$sha`r")
    Test-GuardCase 'required good CRLF Java properties' -Jar (
        New-GuardJarBytes "# Свойства сборки`r`nrelease=23`r`ncommit=$sha`r`n")
    Test-GuardCase 'Unicode fullwidth release rejected' 'RELEASE_APPINFO_MISMATCH' -Jar (
        New-GuardJarBytes "release=２３`ncommit=$sha")
    Test-GuardCase 'Unicode whitespace comment is not Java comment' 'RELEASE_APPINFO_FORMAT' -Jar (
        New-GuardJarBytes ($goodProperties + "`u{2003}# hidden"))
    Test-GuardCase 'CR comment hides nonmatching release' 'RELEASE_APPINFO_DUPLICATE' -Jar (
        New-GuardJarBytes ($goodProperties + "# comment`rrelease=1003"))
    Test-GuardCase 'CR comment hides dummy commit' 'RELEASE_APPINFO_DUPLICATE' -Jar (
        New-GuardJarBytes ($goodProperties + "# comment`rcommit=$('b' * 40)"))
    foreach ($alias in @('CashPrediction/app/../app/cashprediction-core-1.0.0.jar',
            'CashPrediction/app/./cashprediction-core-1.0.0.jar',
            'CashPrediction\app\cashprediction-core-1.0.0.jar',
            '/CashPrediction/app/cashprediction-core-1.0.0.jar',
            'CashPrediction/app/cashprediction-core-1.0.0.jar.')) {
        Test-GuardCase "ZIP traversal or alias: $alias" 'RELEASE_ARCHIVE_PATH' -ExtraZipEntries @(
            @{Name=$alias;Bytes=$goodJar})
    }
    Test-GuardCase 'case insensitive ZIP core duplicate' 'RELEASE_ARCHIVE_DUPLICATE' -ExtraZipEntries @(
        @{Name='CASHPREDICTION/APP/CASHPREDICTION-CORE-1.0.0.JAR';Bytes=$goodJar})
    Test-GuardCase 'case insensitive JAR properties duplicate' 'RELEASE_ARCHIVE_DUPLICATE' -Jar (
        New-GuardJarBytes $goodProperties -ExtraEntries @(
            @{Name='RU/CASHPREDICTION/CORE/APP.PROPERTIES';Bytes=[Text.Encoding]::UTF8.GetBytes('release=1003')}))
    Test-GuardCase 'JAR properties traversal alias' 'RELEASE_ARCHIVE_PATH' -Jar (
        New-GuardJarBytes $goodProperties -ExtraEntries @(
            @{Name='ru/cashprediction/core/../core/app.properties';Bytes=[Text.Encoding]::UTF8.GetBytes('release=1003')}))
    Test-GuardCase 'ZIP core symlink alias' 'RELEASE_ARCHIVE_LINK' -ExtraZipEntries @(
        @{Name='CashPrediction/app/core-alias.jar';Bytes=[byte[]]@(1);Attributes=-1577058304})
    Test-GuardCase 'JAR properties symlink alias' 'RELEASE_ARCHIVE_LINK' -Jar (
        New-GuardJarBytes $goodProperties -ExtraEntries @(
            @{Name='ru/cashprediction/core/alias.properties';Bytes=[byte[]]@(1);Attributes=-1577058304}))
    Test-GuardCase 'Unicode normalized ZIP duplicate' 'RELEASE_ARCHIVE_DUPLICATE' -ExtraZipEntries @(
        @{Name='CashPrediction/app/é.txt';Bytes=[byte[]]@(1)},
        @{Name="CashPrediction/app/e`u{0301}.txt";Bytes=[byte[]]@(2)})
    Test-GuardCase 'JAR class case alias' 'RELEASE_ARCHIVE_DUPLICATE' -Jar (
        New-GuardJarBytes $goodProperties -ExtraEntries @(
            @{Name='RU/CASHPREDICTION/CORE/IO/APPINFO.CLASS';Bytes=[byte[]]@(2)}))
    Write-Output "Release identity guard: PASS ($checks checks; only mocks and owned fixtures)."
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
    $global:LASTEXITCODE = $savedExitCode
    # Удаляет только созданный стенд после проверки абсолютного пути и отсутствия ссылок.
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolved.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^CashPrediction-release-guard-[0-9a-f]{32}$') { throw 'Небезопасный fixture cleanup.' }
    $ancestor = $resolved
    while ($ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Ссылка в пути fixture cleanup.'
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    if (Test-Path -LiteralPath $resolved) {
        if (@(Get-ChildItem -LiteralPath $resolved -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
            throw 'Ссылка внутри fixture cleanup.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
