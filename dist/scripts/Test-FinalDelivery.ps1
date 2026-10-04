<#
.SYNOPSIS
Проверяет конечную корневую доставку CashPrediction и сам архив исходников.
.DESCRIPTION
Читает DeliveryRoot/CashPrediction и DeliveryRoot/CashPrediction-source.7z.
Отсутствие артефактов означает FAILED. Не создаёт и не исправляет доставку.
Политику исходников загружает через AST Pack-Source.ps1, без исполнения упаковщика.
Listing, test и извлечение выполняются внешним 7-Zip в новой собственной папке Temp.
Receipts и inventories сохраняются в новом EvidenceRoot без перезаписи.
VerifyBuild дополнительно выполняет полный mvn -B install именно в свежем извлечённом дереве.
Проверка состава не заменяет S6/S7, native smoke, parity, восстановление или release sign-off.
#>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $DeliveryRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $EvidenceRoot,
    [string] $SevenZipPath,
    [string] $MavenPath,
    [switch] $VerifyBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Загружает только проверочные определения единственного владельца политики поставки.
$policy = Join-Path $PSScriptRoot 'Pack-Source.ps1'
$loadedPolicySha = (Get-FileHash -LiteralPath $policy -Algorithm SHA256).Hash
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($policy, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'FINAL_POLICY_PARSE' }
if ((Get-FileHash -LiteralPath $policy -Algorithm SHA256).Hash -cne $loadedPolicySha) { throw 'FINAL_POLICY_CHANGED' }
foreach ($name in @('Test-WithinPath', 'Test-ExcludedDirectory', 'Test-IncludedFile', 'Read-SourcePom',
        'Assert-NoLinkedAncestor', 'Assert-NoSourceSecret', 'Assert-DeliveredSource', 'Resolve-SevenZip')) {
    $definitions = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
    }, $false))
    if ($definitions.Count -ne 1) { throw "FINAL_POLICY_FUNCTION: $name" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}

# Проверяет буквальный абсолютный путь и всю существующую цепочку родителей.
function Assert-FinalPath([string] $Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path -match '[*?]' -or $Path.IndexOf(':', 2) -ge 0) { throw 'FINAL_PATH_STREAM_OR_MASK' }
    Assert-NoLinkedAncestor $Path
    if ((Test-Path -LiteralPath $Path) -and
        ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'FINAL_LINKED_PATH'
    }
}

# Сохраняет каждый evidence-файл ровно один раз, никогда не заменяя существующий.
function Write-FinalEvidence([string] $Name, [string] $Text) {
    $path = Join-Path $evidence $Name
    Assert-FinalPath $path
    $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true)
    } finally { $stream.Dispose() }
}

# Обходит дерево без следования ссылкам и закрепляет файлы, пустые папки и readonly.
function Get-FinalInventory([string] $Root) {
    Assert-FinalPath $Root
    $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($Root)
    $entries = [Collections.Generic.List[object]]::new()
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            Assert-FinalPath $item.FullName
            $relative = [IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/')
            if ($entries.Count -ge 100000) { throw 'FINAL_INVENTORY_LIMIT' }
            if ($item.PSIsContainer) {
                $entries.Add([pscustomobject]@{ path = $relative; directory = $true; size = 0; sha256 = $null; readOnly = $false })
                $pending.Push($item.FullName)
            } else {
                $entries.Add([pscustomobject]@{ path = $relative; directory = $false; size = $item.Length;
                    sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash;
                    readOnly = [bool]($item.Attributes -band [IO.FileAttributes]::ReadOnly) })
            }
        }
    }
    return @($entries | Sort-Object -Property path)
}

# Проверяет DOS/PE сигнатуру каждого доставленного launcher без запуска нативного кода.
function Assert-FinalLauncher([string] $Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $header = [byte[]]::new(64)
        if ($stream.Read($header, 0, 64) -ne 64 -or $header[0] -ne 0x4d -or $header[1] -ne 0x5a) { throw 'FINAL_PORTABLE_PE' }
        $offset = [BitConverter]::ToUInt32($header, 60)
        if ($offset -lt 64 -or $offset -gt $stream.Length - 6) { throw 'FINAL_PORTABLE_PE' }
        $stream.Position = $offset
        $signature = [byte[]]::new(6)
        if ($stream.Read($signature, 0, 6) -ne 6 -or [Convert]::ToHexString($signature[0..3]) -cne '50450000' -or
            [BitConverter]::ToUInt16($signature, 4) -notin @(0x14c, 0x8664, 0xaa64)) { throw 'FINAL_PORTABLE_PE' }
    } finally { $stream.Dispose() }
}

# Проверяет относительный путь контейнера до передачи архиватору команды извлечения.
function Assert-FinalArchivePath([string] $Relative) {
    if (-not $Relative -or $Relative.Length -gt 2048 -or $Relative -match '^[\\/]' -or
        $Relative -match '[:\x00-\x1f\x7f<>"|*?]' -or -not $Relative.IsNormalized([Text.NormalizationForm]::FormC)) {
        throw 'FINAL_ARCHIVE_PATH'
    }
    $relativePath = $Relative.Replace('\', '/')
    foreach ($part in $relativePath.Split('/')) {
        if (-not $part -or $part -in @('.', '..') -or $part -match '[. ]$' -or
            $part -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') { throw 'FINAL_ARCHIVE_PATH' }
    }
    return $relativePath
}

# Читает технический listing 7z и отвергает неоднозначные записи, ссылки и коллизии.
function Read-FinalArchiveListing([string[]] $Lines) {
    $separator = [Array]::IndexOf($Lines, '----------')
    if ($separator -lt 0 -or @($Lines[0..$separator] | Where-Object { $_ -ceq 'Type = 7z' }).Count -ne 1) {
        throw 'FINAL_ARCHIVE_TYPE'
    }
    $records = [Collections.Generic.List[object]]::new()
    $names = [Collections.Generic.Dictionary[string,bool]]::new([StringComparer]::OrdinalIgnoreCase)
    $fields = [ordered]@{}; $total = 0L
    # Проверяет одну запись listing, используя исходную политику для файлов и каталогов.
    function Add-ListingRecord($Fields) {
        if (-not $Fields.Count) { return }
        if (-not $Fields.Contains('Path')) { throw 'FINAL_LISTING_RECORD' }
        $path = Assert-FinalArchivePath $Fields['Path']
        foreach ($key in $Fields.Keys) {
            if ($key -match '(?i)link|reparse' -and $Fields[$key]) { throw 'FINAL_ARCHIVE_LINK' }
        }
        $attributes = if ($Fields.Contains('Attributes')) { $Fields['Attributes'] } else { '' }
        if ($attributes -match '(^|\s)l[rwxstST-]{9}|L|Reparse' -or
            ($Fields.Contains('Anti') -and $Fields['Anti'] -eq '+') -or
            ($Fields.Contains('Encrypted') -and $Fields['Encrypted'] -eq '+')) { throw 'FINAL_ARCHIVE_LINK_OR_UNSUPPORTED' }
        $directory = ($Fields.Contains('Folder') -and $Fields['Folder'] -eq '+') -or $attributes -match '^D'
        if ($names.ContainsKey($path)) { throw 'FINAL_ARCHIVE_COLLISION' }
        $names.Add($path, [bool]$directory)
        $parts = $path.Split('/')
        for ($i = 0; $i -lt $parts.Length - $(if ($directory) { 0 } else { 1 }); $i++) {
            if (Test-ExcludedDirectory $parts[$i] ($parts[0..$i] -join '/')) { throw 'FINAL_SOURCE_EXCLUDED_DIRECTORY' }
        }
        if (-not $directory -and -not (Test-IncludedFile $path)) { throw "FINAL_SOURCE_EXCLUDED_FILE: $path" }
        $size = 0L
        if (-not $directory -and (-not $Fields.Contains('Size') -or
            -not [long]::TryParse($Fields['Size'], [ref]$size) -or $size -lt 0 -or $size -gt 536870912)) { throw 'FINAL_ARCHIVE_SIZE' }
        $records.Add([pscustomobject]@{path = $path; directory = [bool]$directory; size = $size})
        if ($records.Count -gt 100000) { throw 'FINAL_ARCHIVE_COUNT' }
    }
    foreach ($line in $Lines[($separator + 1)..($Lines.Length - 1)]) {
        if (-not $line.Trim()) { Add-ListingRecord $fields; $fields = [ordered]@{}; continue }
        if ($line -notmatch '^([^=]+) = (.*)$') { throw 'FINAL_LISTING_SYNTAX' }
        if ($fields.Contains($Matches[1])) { throw 'FINAL_LISTING_DUPLICATE_FIELD' }
        $fields.Add($Matches[1], $Matches[2])
    }
    Add-ListingRecord $fields
    if (-not $records.Count -or $records.Count -gt 100000) { throw 'FINAL_ARCHIVE_COUNT' }
    foreach ($record in $records) {
        $total += $record.size; if ($total -gt 2147483648) { throw 'FINAL_ARCHIVE_TOTAL_SIZE' }
        $parts = $record.path.Split('/')
        for ($i = 1; $i -lt $parts.Length; $i++) {
            $parent = $parts[0..($i - 1)] -join '/'
            if ($names.ContainsKey($parent) -and -not $names[$parent]) { throw 'FINAL_ARCHIVE_FILE_PARENT' }
        }
    }
    return @($records)
}

# Запускает настоящий внешний инструмент с точными аргументами и сохраняет полный журнал и exit.
function Invoke-FinalTool([string] $Tool, [string[]] $Arguments, [string] $LogName, [string] $WorkingDirectory) {
    $started = [DateTime]::UtcNow
    $output = [Collections.Generic.List[string]]::new(); $characters = 0L; $toolFailure = $null
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $global:LASTEXITCODE = 0
        & $Tool @Arguments 2>&1 | ForEach-Object {
            $line = $_.ToString(); $characters += $line.Length
            if ($characters -gt 33554432) { throw 'FINAL_TOOL_OUTPUT_LIMIT' }
            $output.Add($line)
        }
        $code = $LASTEXITCODE
    } catch {
        $toolFailure = $_; $code = -1; $output.Add($_.Exception.Message)
    } finally { Pop-Location }
    Write-FinalEvidence $LogName ($output -join "`n")
    $receipt.commands += [pscustomobject]@{tool = $Tool; arguments = $Arguments; workingDirectory = $WorkingDirectory;
        exit = $code; startedAt = $started.ToString('o'); durationMs = ([DateTime]::UtcNow - $started).TotalMilliseconds;
        log = $LogName; logSha256 = (Get-FileHash -LiteralPath (Join-Path $evidence $LogName)).Hash;
        error = $(if ($toolFailure) { $toolFailure.Exception.Message } else { $null })}
    if ($toolFailure) { throw $toolFailure }
    if ($code -ne 0) { throw "FINAL_TOOL_EXIT: $LogName/$code" }
    return $output
}

# Удаляет только созданный этим запуском Temp-корень после проверки всего дерева без ссылок.
function Remove-FinalOwnedTemp([string] $Root) {
    if ([IO.Path]::GetDirectoryName($Root) -cne $tempRoot -or
        [IO.Path]::GetFileName($Root) -notmatch '^cp-final-delivery-[0-9a-f]{32}$' -or $Root -cne $owned) {
        throw 'FINAL_CLEANUP_SCOPE'
    }
    Assert-FinalPath $Root
    $null = Get-FinalInventory $Root
    Remove-Item -LiteralPath $Root -Recurse -Force
}

$delivery = [IO.Path]::GetFullPath($DeliveryRoot).TrimEnd([char[]]'\/')
Assert-FinalPath $delivery
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$owned = Join-Path $tempRoot ('cp-final-delivery-' + [guid]::NewGuid().ToString('N'))
$evidence = if ($EvidenceRoot) { [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd([char[]]'\/') }
    else { Join-Path $tempRoot ('cp-final-delivery-evidence-' + [guid]::NewGuid().ToString('N')) }
Assert-FinalPath $evidence; Assert-FinalPath $owned
if ((Test-WithinPath $evidence $delivery) -or (Test-WithinPath $delivery $evidence) -or
    (Test-WithinPath $evidence $owned) -or (Test-WithinPath $owned $evidence)) { throw 'FINAL_EVIDENCE_SCOPE' }
if (Test-Path -LiteralPath $evidence) { throw 'FINAL_EVIDENCE_EXISTS' }
if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($evidence)) -PathType Container)) { throw 'FINAL_EVIDENCE_PARENT' }
$null = New-Item -ItemType Directory -Path $evidence
$receipt = [ordered]@{schema = 1; status = 'FAILED'; fullAcceptance = $false; startedAt = [DateTime]::UtcNow.ToString('o');
    deliveryRoot = $delivery; evidenceRoot = $evidence; scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath).Hash;
    policyPath = $policy; policySha256 = $loadedPolicySha; archive = $null; portable = $null;
    source = $null; commands = @(); verifyBuild = [bool]$VerifyBuild; buildStatus = 'NOT_RUN'; cleanup = 'NOT_CREATED'; error = $null}
$archiveStream = $null; $created = $false; $failure = $null
try {
    $portable = Join-Path $delivery 'CashPrediction'; $archive = Join-Path $delivery 'CashPrediction-source.7z'
    Assert-FinalPath $portable; Assert-FinalPath $archive
    if (-not (Test-Path -LiteralPath $portable -PathType Container) -or
        -not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'FINAL_ARTIFACTS_MISSING' }
    $portableInventory = @(Get-FinalInventory $portable)
    $portableJson = ConvertTo-Json -InputObject $portableInventory -Depth 8
    Write-FinalEvidence 'portable-inventory.json' $portableJson
    $receipt.portable = @{path = $portable; inventory = 'portable-inventory.json'; count = $portableInventory.Count;
        inventorySha256 = (Get-FileHash -LiteralPath (Join-Path $evidence 'portable-inventory.json')).Hash}
    if (@($portableInventory | Where-Object { ($_.path.Split('/') -contains 'CashMemory') }).Count) { throw 'FINAL_PORTABLE_CASHMEMORY' }
    foreach ($exe in @('CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe')) {
        $entry = @($portableInventory | Where-Object { $_.path -ceq $exe -and -not $_.directory -and $_.size -gt 0 })
        if ($entry.Count -ne 1) { throw "FINAL_PORTABLE_EXE: $exe" }
        Assert-FinalLauncher (Join-Path $portable $exe)
    }
    foreach ($tree in @('app', 'runtime')) {
        if (-not (Test-Path -LiteralPath (Join-Path $portable $tree) -PathType Container) -or
            -not @($portableInventory | Where-Object { -not $_.directory -and $_.size -gt 0 -and $_.path.StartsWith($tree + '/', [StringComparison]::Ordinal) }).Count) {
            throw "FINAL_PORTABLE_TREE: $tree"
        }
    }
    $archiveStream = [IO.File]::Open($archive, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $archiveSha = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    $receipt.archive = @{path = $archive; size = $archiveStream.Length; sha256 = $archiveSha; listing = 'archive-listing.log'; tested = $false}
    if ($archiveStream.Length -gt 536870912) { throw 'FINAL_ARCHIVE_CONTAINER_SIZE' }
    $signature = [byte[]]::new(6)
    if ($archiveStream.Read($signature, 0, 6) -ne 6 -or [Convert]::ToHexString($signature) -cne '377ABCAF271C') { throw 'FINAL_ARCHIVE_SIGNATURE' }
    $sevenZip = Resolve-SevenZip; Assert-FinalPath $sevenZip
    $receipt.archive.tool = $sevenZip; $receipt.archive.toolSha256 = (Get-FileHash -LiteralPath $sevenZip).Hash
    $listing = @(Invoke-FinalTool $sevenZip @('l', '-slt', '-sccUTF-8', '-p-', '--', $archive) 'archive-listing.log' $evidence)
    $records = @(Read-FinalArchiveListing $listing)
    Write-FinalEvidence 'archive-entries.json' (ConvertTo-Json -InputObject $records -Depth 8)
    $receipt.archive.entries = 'archive-entries.json'
    $receipt.archive.entriesSha256 = (Get-FileHash -LiteralPath (Join-Path $evidence 'archive-entries.json')).Hash
    $null = Invoke-FinalTool $sevenZip @('t', '-bd', '-p-', '--', $archive) 'archive-test.log' $evidence
    $receipt.archive.tested = $true
    $null = New-Item -ItemType Directory -Path $owned; $created = $true
    $extracted = Join-Path $owned 'source'
    $null = Invoke-FinalTool $sevenZip @('x', '-bd', '-y', '-p-', ('-o' + $extracted), '--', $archive) 'archive-extract.log' $evidence
    if (-not (Test-Path -LiteralPath $extracted -PathType Container)) { throw 'FINAL_EXTRACTION_MISSING' }
    $sourceInventory = @(Get-FinalInventory $extracted)
    $actual = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $allowedDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($record in $records) {
        if ($record.directory) { $null = $allowedDirectories.Add($record.path) }
        $parts = $record.path.Split('/')
        for ($i = 1; $i -lt $parts.Length; $i++) { $null = $allowedDirectories.Add(($parts[0..($i - 1)] -join '/')) }
    }
    foreach ($entry in $sourceInventory) {
        if ($entry.directory -and -not $allowedDirectories.Contains($entry.path)) { throw 'FINAL_EXTRACTION_EXTRA_DIRECTORY' }
        $actual.Add($entry.path, $entry)
    }
    foreach ($record in $records) {
        if (-not $actual.ContainsKey($record.path) -or $actual[$record.path].directory -ne $record.directory -or
            (-not $record.directory -and $actual[$record.path].size -ne $record.size)) { throw 'FINAL_LISTING_EXTRACTION_MISMATCH' }
    }
    if (@($sourceInventory | Where-Object { -not $_.directory }).Count -ne @($records | Where-Object { -not $_.directory }).Count) {
        throw 'FINAL_EXTRACTION_EXTRA_FILE'
    }
    Write-FinalEvidence 'source-inventory.json' (ConvertTo-Json -InputObject $sourceInventory -Depth 8)
    $receipt.source = @{origin = 'archive-extraction'; inventory = 'source-inventory.json'; count = $sourceInventory.Count;
        inventorySha256 = (Get-FileHash -LiteralPath (Join-Path $evidence 'source-inventory.json')).Hash; composition = 'FAILED'}
    Assert-DeliveredSource $extracted
    $receipt.source.composition = 'VERIFIED'
    if ($VerifyBuild) {
        $maven = if ($MavenPath) { (Get-Item -LiteralPath $MavenPath).FullName } else { (Get-Command mvn -ErrorAction Stop).Source }
        Assert-FinalPath $maven
        $receipt.buildStatus = 'FAILED'
        $receipt.maven = @{path = $maven; sha256 = (Get-FileHash -LiteralPath $maven).Hash}
        $null = Invoke-FinalTool $maven @('-B', 'install') 'source-build.log' $extracted
        foreach ($entry in $sourceInventory | Where-Object { -not $_.directory }) {
            $path = Join-Path $extracted $entry.path; Assert-FinalPath $path
            if ((Get-FileHash -LiteralPath $path).Hash -cne $entry.sha256) { throw 'FINAL_BUILD_CHANGED_SOURCE' }
        }
        $receipt.buildStatus = 'VERIFIED'
    }
    if ((Get-FileHash -LiteralPath $archive).Hash -cne $archiveSha -or
        (ConvertTo-Json -InputObject @(Get-FinalInventory $portable) -Depth 8) -cne $portableJson) { throw 'FINAL_ARTIFACTS_CHANGED' }
    if ((Get-FileHash -LiteralPath $policy).Hash -cne $receipt.policySha256) { throw 'FINAL_POLICY_CHANGED' }
    $receipt.status = if ($VerifyBuild) { 'VERIFIED_COMPOSITION_AND_BUILD' } else { 'VERIFIED_COMPOSITION' }
} catch { $failure = $_; $receipt.status = 'FAILED'; $receipt.error = $_.Exception.Message }
finally {
    if ($archiveStream) { $archiveStream.Dispose() }
    if ($created) {
        try { Remove-FinalOwnedTemp $owned; $receipt.cleanup = 'REMOVED' }
        catch { $receipt.cleanup = 'FAILED'; $receipt.status = 'FAILED'; $receipt.error = $_.Exception.Message; $failure = $_ }
    }
    $receipt.finishedAt = [DateTime]::UtcNow.ToString('o')
    Write-FinalEvidence 'receipt.json' (ConvertTo-Json -InputObject $receipt -Depth 12)
}
if ($failure) { throw $failure }
[pscustomobject]@{status = $receipt.status; fullAcceptance = $false; evidenceRoot = $evidence; receipt = (Join-Path $evidence 'receipt.json')}
