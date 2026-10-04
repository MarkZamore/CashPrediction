# Общие операции S7. Подключение файла само по себе не выполняет публикацию.
. "$PSScriptRoot/GhRetry.ps1"

<# .SYNOPSIS Проверяет результат единственного обращения к GitHub. #>
function Invoke-S7Gh {
    $answer = Invoke-Gh @args
    if ($LASTEXITCODE -ne 0) { throw "S7_GITHUB_FAILED: $($args[0]) $($args[1])" }
    return $answer
}

<# .SYNOPSIS Записывает служебный JSON без BOM. #>
function Write-S7Json {
    param([string]$Path, $Value)
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 40), [Text.UTF8Encoding]::new($false))
}

<# .SYNOPSIS Читает ограниченный по размеру JSON. #>
function Read-S7Json {
    param([string]$Path)
    if ((Get-Item -LiteralPath $Path).Length -gt 8MB) { throw 'S7_JSON_LIMIT' }
    $text = [Text.UTF8Encoding]::new($false, $true).GetString([IO.File]::ReadAllBytes($Path))
    return $text | ConvertFrom-Json -ErrorAction Stop
}

<# .SYNOPSIS Проверяет размер и хеш контейнера по опубликованному описанию. #>
function Assert-S7Container {
    param([string]$Path, $Descriptor)
    if ($Descriptor.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [long]$Descriptor.sizeBytes -le 0 -or [long]$Descriptor.sizeBytes -gt 512MB -or
        (Get-Item -LiteralPath $Path).Length -ne [long]$Descriptor.sizeBytes -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Descriptor.sha256) {
        throw 'S7_CONTAINER_MISMATCH'
    }
}

<# .SYNOPSIS Проверяет идентичность старого release.json, не используя новый SHA workflow. #>
function Read-S7ReleaseIdentity {
    param([string]$Path)
    $identity = Read-S7Json $Path
    if ($identity.schemaVersion -ne 1 -or $identity.releaseNumber -isnot [ValueType] -or
        [int]$identity.releaseNumber -lt 1 -or [int]$identity.releaseNumber -ne $identity.releaseNumber -or
        $identity.commitSha -cnotmatch '^[0-9a-f]{40}$' -or
        $identity.assetName -cne 'CashPrediction-portable.zip') { throw 'S7_RELEASE_IDENTITY' }
    return $identity
}

<# .SYNOPSIS Выполняет зафиксированную CLI уже собранного инструмента, без Maven. #>
function Invoke-S7Tool {
    param([string]$Java, [string[]]$ToolArguments, [string[]]$CommandArguments)
    # ASCII Base64 сохраняет Unicode аргументов до декодирования JSON внутри CLI.
    $json = ConvertTo-Json -Compress -InputObject $CommandArguments
    $payload = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false, $true).GetBytes($json))
    & $Java @ToolArguments '--arguments-base64' $payload
    if ($LASTEXITCODE -ne 0) { throw "S7_TOOL_FAILED: $($CommandArguments[0])" }
}

<# .SYNOPSIS Проверяет безопасное имя управляемого файла архива. #>
function Assert-S7Path {
    param([string]$Path, [switch]$Directory)
    if (-not $Path.IsNormalized([Text.NormalizationForm]::FormC) -or
        $Path -match '[\\:\x00-\x1f\x7f-\x9f<>"|?*]' -or $Path.StartsWith('/') -or
        $Path -notmatch '^(CashPrediction(?:-Swing|-Web)?\.exe|app(?:/.*)?|runtime(?:/.*)?)$') {
        throw 'S7_UNSAFE_PATH'
    }
    foreach ($part in $Path.Split('/')) {
        if (-not $part -or $part -in '.', '..' -or $part -match '[. ]$' -or
            $part -match '^(?i:CON|PRN|AUX|NUL|CLOCK\$|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])(?:\.|$)') { throw 'S7_UNSAFE_SEGMENT' }
    }
    if (-not $Directory -and $Path -in 'app', 'runtime') { throw 'S7_FILE_DIRECTORY' }
}

<#
.SYNOPSIS Извлекает полный ZIP в новый каталог после проверки всего центрального каталога.
.DESCRIPTION ZIP64, ссылки, опасные пути, дубликаты и превышение лимитов отвергаются до записи.
Атрибут readOnly берётся из опубликованного inventory, либо из DOS-атрибутов старого ZIP.
#>
function Expand-S7Full {
    param([string]$Archive, [string]$Destination, $Manifest = $null)
    if (Test-Path -LiteralPath $Destination) { throw 'S7_DESTINATION_EXISTS' }
    if ((Get-Item -LiteralPath $Archive).Length -gt 512MB) { throw 'S7_ZIP_LIMIT' }
    # Проверяем исходные UTF-8 bytes: ZipArchive может заменять неверные последовательности.
    $stream = [IO.File]::OpenRead($Archive)
    $reader = [IO.BinaryReader]::new($stream)
    try {
        $tailLength = [int][Math]::Min(65557, $stream.Length)
        $stream.Position = $stream.Length - $tailLength
        $tail = $reader.ReadBytes($tailLength)
        $eocd = -1
        for ($i = $tail.Length - 22; $i -ge 0; $i--) {
            if ([BitConverter]::ToUInt32($tail, $i) -eq 0x06054b50 -and
                $i + 22 + [BitConverter]::ToUInt16($tail, $i + 20) -eq $tail.Length) { $eocd = $i; break }
        }
        if ($eocd -lt 0) { throw 'S7_ZIP_END' }
        $count = [BitConverter]::ToUInt16($tail, $eocd + 10)
        $length = [BitConverter]::ToUInt32($tail, $eocd + 12)
        $offset = [BitConverter]::ToUInt32($tail, $eocd + 16)
        if ($count -eq 65535 -or $count -gt 40002 -or $length -eq [uint32]::MaxValue -or
            $offset -eq [uint32]::MaxValue -or [BitConverter]::ToUInt32($tail, $eocd + 4) -ne 0 -or
            [BitConverter]::ToUInt16($tail, $eocd + 8) -ne $count -or
            [long]$offset + $length -ne $stream.Length - $tailLength + $eocd) { throw 'S7_ZIP64_OR_SPLIT' }
        $stream.Position = $offset
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        for ($i = 0; $i -lt $count; $i++) {
            if ($reader.ReadUInt32() -ne 0x02014b50) { throw 'S7_ZIP_CENTRAL' }
            $header = $reader.ReadBytes(42)
            if ($header.Length -ne 42) { throw 'S7_ZIP_SHORT' }
            $flags = [BitConverter]::ToUInt16($header, 4)
            $nameLength = [BitConverter]::ToUInt16($header, 24)
            $extraLength = [BitConverter]::ToUInt16($header, 26)
            $commentLength = [BitConverter]::ToUInt16($header, 28)
            $external = [BitConverter]::ToUInt32($header, 34)
            if (($flags -band 1) -ne 0 -or (($external -shr 16) -band 0xf000) -eq 0xa000 -or
                ($external -band 0x400) -ne 0 -or
                [BitConverter]::ToUInt32($header, 16) -eq [uint32]::MaxValue -or
                [BitConverter]::ToUInt32($header, 20) -eq [uint32]::MaxValue -or
                [BitConverter]::ToUInt32($header, 38) -eq [uint32]::MaxValue) { throw 'S7_ZIP_LINK_OR_FLAGS' }
            $nameBytes = $reader.ReadBytes($nameLength)
            $name = $utf8.GetString($nameBytes)
            if (($flags -band 0x800) -eq 0 -and @($nameBytes | Where-Object { $_ -gt 127 }).Count) {
                throw 'S7_ZIP_ENCODING'
            }
            if (-not $name.StartsWith('CashPrediction/', [StringComparison]::Ordinal)) { throw 'S7_ZIP_ROOT' }
            if ($name -cne 'CashPrediction/') {
                $relative = $name.Substring(15)
                if ($name.EndsWith('/')) { $relative = $relative.Substring(0, $relative.Length - 1) }
                Assert-S7Path $relative -Directory:($name.EndsWith('/'))
            }
            $extra = $reader.ReadBytes($extraLength)
            for ($j = 0; $j -lt $extra.Length;) {
                if ($j + 4 -gt $extra.Length) { throw 'S7_ZIP_EXTRA' }
                $id = [BitConverter]::ToUInt16($extra, $j)
                $size = [BitConverter]::ToUInt16($extra, $j + 2)
                if ($id -eq 1 -or $j + 4 + $size -gt $extra.Length) { throw 'S7_ZIP64_OR_EXTRA' }
                $j += 4 + $size
            }
            $stream.Position += $commentLength
        }
        if ($stream.Position -ne [long]$offset + $length) { throw 'S7_ZIP_CENTRAL_SIZE' }
    } finally { $reader.Dispose(); $stream.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $files = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $directories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $total = 0L
        $fileCount = 0
        foreach ($entry in $zip.Entries) {
            $path = $entry.FullName.Substring(15)
            if ($entry.FullName.EndsWith('/') -and $path.Length) { $path = $path.Substring(0, $path.Length - 1) }
            $key = $path.ToUpperInvariant()
            if (-not $seen.Add($key)) { throw 'S7_ZIP_COLLISION' }
            if (-not $path) { continue }
            $parent = $path
            while ($parent.Contains('/')) {
                $parent = $parent.Substring(0, $parent.LastIndexOf('/'))
                $null = $directories.Add($parent.ToUpperInvariant())
            }
            if ($entry.FullName.EndsWith('/')) { $null = $directories.Add($key); continue }
            $null = $files.Add($key)
            $fileCount++
            $total += $entry.Length
            if ($entry.Length -gt 512MB -or $total -gt 2GB -or $fileCount -gt 20000) { throw 'S7_EXPANSION_LIMIT' }
        }
        foreach ($key in $files) { if ($directories.Contains($key)) { throw 'S7_ZIP_FILE_COLLISION' } }
        foreach ($required in 'CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe') {
            if (-not $files.Contains($required.ToUpperInvariant())) { throw 'S7_ZIP_LAUNCHER_MISSING' }
        }
        $root = [IO.Path]::GetFullPath($Destination)
        # Caller передаёт собственный новый staging; ни один ancestor не может быть ссылкой.
        $ancestor = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($root))
        while ($ancestor) {
            if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'S7_REPARSE' }
            $ancestor = $ancestor.Parent
        }
        $null = [IO.Directory]::CreateDirectory($root)
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $path = $entry.FullName.Substring(15)
            $destinationFile = Join-Path $root $path
            $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destinationFile))
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Open($destinationFile, [IO.FileMode]::CreateNew)
            try {
                $buffer = [byte[]]::new(65536)
                $written = 0L
                while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $written += $read
                    if ($written -gt $entry.Length) { throw 'S7_EXPANSION_MISMATCH' }
                    $outputStream.Write($buffer, 0, $read)
                }
                if ($written -ne $entry.Length) { throw 'S7_EXPANSION_MISMATCH' }
            } finally { $outputStream.Dispose(); $inputStream.Dispose() }
            $readOnly = ($entry.ExternalAttributes -band 1) -ne 0
            if ($null -ne $Manifest) {
                $row = @($Manifest.files | Where-Object { $_.path -ceq $path })
                if ($row.Count -ne 1) { throw 'S7_FULL_INVENTORY_MISMATCH' }
                $readOnly = [bool]$row[0].readOnly
            }
            if ($readOnly) { [IO.File]::SetAttributes($destinationFile, [IO.FileAttributes]::ReadOnly) }
        }
    } finally { $zip.Dispose() }
}

<# .SYNOPSIS Получает все страницы релизов, не ограничивая retention первыми ста строками. #>
function Get-S7Releases {
    param([string]$Repository)
    $pages = Invoke-S7Gh api "repos/$Repository/releases?per_page=100" --paginate --slurp | ConvertFrom-Json
    foreach ($page in $pages) { foreach ($release in $page) { $release } }
}

<# .SYNOPSIS Загружает опубликованную базу в новый каталог и проверяет bytes и дерево. #>
function Get-S7Base {
    param([string]$Tag, [string]$Directory, [string]$Java, [string[]]$ToolArguments)
    if (Test-Path -LiteralPath $Directory) { throw 'S7_CAPTURE_EXISTS' }
    $null = New-Item -ItemType Directory -Path $Directory
    $release = Invoke-S7Gh release view $Tag --json assets,isDraft | ConvertFrom-Json
    if ($release.isDraft) { throw 'S7_BASE_DRAFT' }
    if ($Tag -ne 'latest' -and @($release.assets | Where-Object {
        $_.name -cnotin 'CashPrediction-portable.zip', 'release.json', 'update.json'
    }).Count) { throw 'S7_IMMUTABLE_BASE_ASSET_SET' }
    foreach ($name in 'release.json', 'CashPrediction-portable.zip') {
        if (@($release.assets | Where-Object { $_.name -ceq $name }).Count -ne 1) { throw 'S7_BASE_INCOMPLETE' }
        Invoke-S7Gh release download $Tag --pattern $name --dir $Directory | Out-Null
    }
    $releasePath = Join-Path $Directory 'release.json'
    $identity = Read-S7ReleaseIdentity $releasePath
    if ($Tag -ne 'latest' -and $Tag -cne "update-base-$($identity.releaseNumber)") { throw 'S7_BASE_TAG_MISMATCH' }
    $archive = Join-Path $Directory 'CashPrediction-portable.zip'
    Assert-S7Container $archive $identity
    $update = $null
    $updatePath = $null
    if ($release.assets.name -ccontains 'update.json') {
        Invoke-S7Gh release download $Tag --pattern update.json --dir $Directory | Out-Null
        $updatePath = Join-Path $Directory 'update.json'
        $update = Read-S7Json $updatePath
        if ($update.schemaVersion -ne 2 -or $update.releaseNumber -ne $identity.releaseNumber -or
            $update.commitSha -cne $identity.commitSha -or $update.sha256 -cne $identity.sha256 -or
            $update.sizeBytes -ne $identity.sizeBytes -or $update.assetName -cne $identity.assetName) { throw 'S7_BASE_POINTER_MISMATCH' }
    }
    $root = Join-Path $Directory 'tree'
    Expand-S7Full $archive $root $update
    $inventoryPath = Join-Path $Directory 'inventory.json'
    Invoke-S7Tool $Java $ToolArguments @('inventory', '--root', $root, '--out', $inventoryPath) | Out-Null
    $inventory = Read-S7Json $inventoryPath
    # Даже legacy release.json без inventory проходит полный JDK-аудит ZIP и bytes.
    $verifiedPath = Join-Path $Directory 'verified-manifest.json'
    Invoke-S7Tool $Java $ToolArguments @('manifest', '--root', $root, '--archive', $archive,
        '--release', [string]$identity.releaseNumber, '--commit', $identity.commitSha,
        '--version', [string]$identity.releaseNumber, '--published-at', $identity.publishedAtUtc,
        '--out', $verifiedPath) | Out-Null
    if ($update) {
        Invoke-S7Tool $Java $ToolArguments @('verify', '--root', $root, '--manifest', $updatePath) | Out-Null
        if ($inventory.treeSha256 -cne $update.treeSha256) { throw 'S7_BASE_TREE_MISMATCH' }
    }
    return [pscustomobject]@{
        tag = $Tag; releaseNumber = [int]$identity.releaseNumber; commitSha = $identity.commitSha
        treeSha256 = $inventory.treeSha256; root = $root; archive = $archive
        releasePath = $releasePath; updatePath = $updatePath
    }
}

<# .SYNOPSIS Проверяет отсутствие изменения идентичности неизменяемой базы. #>
function Assert-S7SameBase {
    param($Expected, $Actual)
    foreach ($field in 'releaseNumber', 'commitSha', 'treeSha256') {
        if ($Expected.$field -cne $Actual.$field) { throw "S7_IMMUTABLE_BASE_CONFLICT: $field" }
    }
    foreach ($field in 'archive', 'releasePath', 'updatePath') {
        if ([bool]$Expected.$field -ne [bool]$Actual.$field) { throw 'S7_IMMUTABLE_BASE_ASSETS' }
        if ($Expected.$field -and
            (Get-FileHash -LiteralPath $Expected.$field).Hash -cne (Get-FileHash -LiteralPath $Actual.$field).Hash) {
            throw "S7_IMMUTABLE_BASE_BYTES: $field"
        }
    }
}

<# .SYNOPSIS Сохраняет предыдущий successful latest; существующий base никогда не перезаписывается. #>
function Save-S7Base {
    param($Base, [string]$Repository, [string]$WorkDirectory, [string]$Java, [string[]]$ToolArguments)
    # Повторно читаем именно опубликованный release.json, даже если caller подменил поля объекта.
    $identity = Read-S7ReleaseIdentity $Base.releasePath
    if ($identity.releaseNumber -ne $Base.releaseNumber -or $identity.commitSha -cne $Base.commitSha) { throw 'S7_ARCHIVE_IDENTITY' }
    Assert-S7Container $Base.archive $identity
    $tag = "update-base-$($identity.releaseNumber)"
    if (Test-GhApiResourceExists "repos/$Repository/releases/tags/$tag") {
        $existing = Get-S7Base $tag (Join-Path $WorkDirectory ([guid]::NewGuid().ToString())) $Java $ToolArguments
        Assert-S7SameBase $Base $existing
        return $tag
    }
    # Чужой orphan tag не переносим на новый SHA и не удаляем.
    if (Test-GhApiResourceExists "repos/$Repository/git/ref/tags/$tag") { throw 'S7_ORPHAN_BASE_TAG' }
    $assets = @($Base.archive, $Base.releasePath)
    if ($Base.updatePath) { $assets += $Base.updatePath }
    Invoke-S7Gh release create $tag @assets --target $identity.commitSha --prerelease --latest=false `
        --title "Update base $($identity.releaseNumber)" --notes "Immutable base $($identity.commitSha)" | Out-Null
    # Проверка фактически опубликованных bytes обязательна до clobber latest.
    $saved = Get-S7Base $tag (Join-Path $WorkDirectory ([guid]::NewGuid().ToString())) $Java $ToolArguments
    Assert-S7SameBase $Base $saved
    return $tag
}

<# .SYNOPSIS Проверяет точный состав готового пакета и согласованность обоих описаний. #>
function Assert-S7Artifacts {
    param([string]$Directory)
    $update = Read-S7Json (Join-Path $Directory 'update.json')
    $release = Read-S7ReleaseIdentity (Join-Path $Directory 'release.json')
    if ($update.schemaVersion -ne 2 -or $update.releaseNumber -ne $release.releaseNumber -or
        $update.commitSha -cne $release.commitSha -or $update.sha256 -cne $release.sha256 -or
        $update.sizeBytes -ne $release.sizeBytes -or $update.assetName -cne $release.assetName -or
        $update.treeSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'S7_ARTIFACT_IDENTITY' }
    $patches = @($update.deltaPatches)
    if ($patches.Count -gt 2) { throw 'S7_PATCH_COUNT' }
    $expected = @('CashPrediction-portable.zip', 'release.json', 'update.json')
    $seen = [Collections.Generic.HashSet[int]]::new()
    for ($i = 0; $i -lt $patches.Count; $i++) {
        $patch = $patches[$i]
        $name = if ($i -eq 0) { 'CashPrediction.cpdelta' } else { "CashPrediction.from-$($patch.baseReleaseNumber).cpdelta" }
        if ($patch.assetName -cne $name -or $patch.algorithm -cne 'cashprediction-tree-delta' -or
            $patch.algorithmVersion -ne 1 -or $patch.baseReleaseNumber -lt 1 -or
            $patch.baseReleaseNumber -ge $update.releaseNumber -or -not $seen.Add([int]$patch.baseReleaseNumber) -or
            $patch.baseCommitSha -cnotmatch '^[0-9a-f]{40}$' -or $patch.baseTreeSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'S7_PATCH_IDENTITY' }
        Assert-S7Container (Join-Path $Directory $name) $patch
        $expected += $name
    }
    Assert-S7Container (Join-Path $Directory $release.assetName) $release
    $actual = @(Get-ChildItem -LiteralPath $Directory)
    if ($actual.Count -ne $expected.Count -or @($actual | Where-Object { $_.PSIsContainer -or $_.Name -cnotin $expected }).Count) {
        throw 'S7_ARTIFACT_SET'
    }
    return $update
}

<#
.SYNOPSIS Публикует payload, release.json и последним update.json, затем выполняет retention.
.DESCRIPTION KeepBases содержит две проверенные successful базы; никакое удаление не предшествует pointer.
#>
function Publish-S7Latest {
    param([string]$Directory, [string]$Repository, [string]$CommitSha, [string]$Title, [string]$Notes,
        [string[]]$KeepBases)
    if ($Repository -notmatch '^[^/\s]+/[^/\s]+$') { throw 'S7_REPOSITORY' }
    $update = Assert-S7Artifacts $Directory
    if ($CommitSha -cne $update.commitSha) { throw 'S7_PUBLISH_COMMIT' }
    if (@($KeepBases).Count -gt 2 -or @($KeepBases | Select-Object -Unique).Count -ne @($KeepBases).Count -or
        @($KeepBases | Where-Object { $_ -notmatch '^update-base-[1-9][0-9]*$' }).Count) { throw 'S7_RETENTION_SET' }
    $payload = @((Join-Path $Directory 'CashPrediction-portable.zip'))
    foreach ($patch in $update.deltaPatches) { $payload += Join-Path $Directory $patch.assetName }
    $payload += Join-Path $Directory 'release.json'
    $exists = Test-GhApiResourceExists "repos/$Repository/releases/tags/latest"
    if (-not $exists) {
        if (Test-GhApiResourceExists "repos/$Repository/git/ref/tags/latest") {
            Invoke-S7Gh api "repos/$Repository/git/refs/tags/latest" --method PATCH --raw-field "sha=$CommitSha" --field force=true | Out-Null
        }
        Invoke-S7Gh release create latest @payload --target $CommitSha --latest --title $Title --notes $Notes | Out-Null
    } else {
        Invoke-S7Gh release upload latest @payload --clobber | Out-Null
    }
    Invoke-S7Gh release upload latest (Join-Path $Directory 'update.json') --clobber | Out-Null
    # После commit-pointer разрешено удалять только assets, которые он больше не называет.
    $keep = @('CashPrediction-portable.zip', 'release.json', 'update.json') + @($update.deltaPatches.assetName)
    $assets = Invoke-S7Gh release view latest --json assets | ConvertFrom-Json
    foreach ($asset in $assets.assets) {
        if ($asset.name -cnotin $keep) { Invoke-S7Gh release delete-asset latest $asset.name --yes | Out-Null }
    }
    # Проверенные retained tags должны всё ещё существовать до первого удаления.
    $releases = @(Get-S7Releases $Repository)
    foreach ($tag in $KeepBases) {
        if (@($releases | Where-Object { $_.tag_name -ceq $tag -and -not $_.draft }).Count -ne 1) { throw 'S7_RETENTION_BASE_MISSING' }
    }
    foreach ($release in $releases) {
        if (-not $release.draft -and $release.tag_name -match '^update-base-[1-9][0-9]*$' -and
            $release.tag_name -cnotin $KeepBases) {
            Invoke-S7Gh release delete $release.tag_name --cleanup-tag --yes | Out-Null
        }
    }
    Invoke-S7Gh api "repos/$Repository/git/refs/tags/latest" --method PATCH --raw-field "sha=$CommitSha" --field force=true | Out-Null
    Invoke-S7Gh release edit latest --title $Title --notes $Notes --latest | Out-Null
}
