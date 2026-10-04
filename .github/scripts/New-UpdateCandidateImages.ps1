<#
.SYNOPSIS Собирает три настоящих versioned app-image из отдельного снимка исходников для S7.
.DESCRIPTION Не запускает приложение и не публикует релиз. Maven package использует полный reactor.
SourceSnapshot предварительно создаётся Pack-Source -StageOnly после завершения изменений.
Исходный снимок и рабочий репозиторий не меняются. Результаты и логи остаются в новой Temp-папке.
Положительные номера служат только локальной матрице; успешная сборка не означает S7 sign-off.
CommitByRelease задаёт три разных локальных SHA-метаданных в порядке ReleaseNumber (B1, B2, T).
Commit сохраняет прежний режим общего SHA. Оба режима не утверждают Git/publication provenance:
идентификаторы различают сборки одного кода, а код привязан к общим замороженным sourcePins.
#>
#requires -Version 7.0
[CmdletBinding(DefaultParameterSetName='SharedCommit')]
param(
    [Parameter(Mandatory)][string]$SourceSnapshot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$Maven,
    [Parameter(Mandatory)][string]$JdkHome,
    [Parameter(Mandatory)][ValidateCount(3,3)][int[]]$ReleaseNumber,
    [Parameter(Mandatory,ParameterSetName='SharedCommit')][ValidatePattern('^[0-9a-f]{40}\z')][string]$Commit,
    [Parameter(Mandatory,ParameterSetName='LocalCommits')][ValidateCount(3,3)]
    [ValidatePattern('^[0-9a-f]{40}\z')][string[]]$CommitByRelease
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:CandidateSourceValidator=Join-Path $PSScriptRoot '../../dist/scripts/Pack-Source.ps1'

# Проверяет режим и порядок SHA без Git: local identities не являются доказательством опубликованных коммитов.
function Resolve-CandidateCommits([string]$Commit,[string[]]$CommitByRelease=@()) {
    $count=if ($null -eq $CommitByRelease) {0} else {$CommitByRelease.Count}
    if ($Commit -and $count) {throw 'CANDIDATE_COMMIT_MODE'}
    if (-not $Commit -and $count -eq 0) {throw 'CANDIDATE_COMMITS_REQUIRED'}
    if ($Commit) {
        if ($Commit -cnotmatch '^[0-9a-f]{40}\z') {throw 'CANDIDATE_COMMIT_FORMAT'}
        return ,([string[]]@($Commit,$Commit,$Commit))
    }
    if ($count -ne 3) {throw 'CANDIDATE_COMMIT_COUNT'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($sha in $CommitByRelease) {
        if ($sha -cnotmatch '^[0-9a-f]{40}\z') {throw 'CANDIDATE_COMMIT_FORMAT'}
        if (-not $seen.Add($sha)) {throw 'CANDIDATE_COMMIT_DUPLICATE'}
    }
    return ,([string[]]$CommitByRelease.Clone())
}

# Receipt описывает локальные build identities и общее pinned дерево, без Git/publication утверждений.
function New-CandidateReceipt([string]$Source,$Pins,$Records,[bool]$DistinctCommits) {
    return [ordered]@{schemaVersion=1;status='BUILT';nativeMatrix='PENDING';createdUtc=[DateTime]::UtcNow.ToString('O');
        commitIdentityMode=$(if ($DistinctCommits) {'LOCAL_DISTINCT_METADATA'} else {'LOCAL_SHARED_METADATA'});
        gitProvenance='NOT_ASSERTED';sourceSnapshot=$Source;sourcePins=$Pins;images=$Records}
}

# Не исправляет ввод caller и проверяет каждый существующий предок на reparse point.
function Assert-CandidatePath([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\') -or
        $Path -cne [IO.Path]::GetFullPath($Path).TrimEnd('\','/') -or $Path -match '[\x00-\x1f";]') {
        throw 'CANDIDATE_CANONICAL_PATH'
    }
    $probe=$Path
    while ($probe) {
        if (Test-Path -LiteralPath $probe) {
            if ((Get-Item -LiteralPath $probe -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'CANDIDATE_LINK' }
        }
        $parent=[IO.Path]::GetDirectoryName($probe)
        if ($parent -eq $probe) {break};$probe=$parent
    }
    return $Path
}

# Обходит только исходники: generated target не участвует в подтверждении неизменности снимка.
function Get-CandidateSourcePins([string]$Root,[switch]$AllowGeneratedTargets,[string[]]$GeneratedTargetPaths=@()) {
    [void](Assert-CandidatePath $Root)
    $pins=[Collections.Generic.SortedDictionary[string,string]]::new([StringComparer]::Ordinal)
    $allowed=[Collections.Generic.HashSet[string]]::new($GeneratedTargetPaths,[StringComparer]::OrdinalIgnoreCase)
    $directories=[Collections.Generic.Stack[object]]::new();$directories.Push(@{path=$Root;generated=$false;depth=0})
    $visited=0
    while ($directories.Count) {
        $directory=$directories.Pop()
        foreach ($item in Get-ChildItem -LiteralPath $directory.path -Force) {
            if (++$visited -gt 100000 -or $directory.depth -ge 32) {throw 'CANDIDATE_SOURCE_LIMIT'}
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'CANDIDATE_LINK'}
            $relative=[IO.Path]::GetRelativePath($Root,$item.FullName).Replace('\','/')
            if ($item.PSIsContainer) {
                $generated=$directory.generated
                if (-not $generated -and $item.Name -ieq 'target') {
                    if (-not $AllowGeneratedTargets) {throw 'CANDIDATE_SOURCE_HAS_TARGET'}
                    if (-not $allowed.Contains($relative)) {throw 'CANDIDATE_UNEXPECTED_TARGET'}
                    $generated=$true
                }
                # Даже в generated дереве проверяются все ссылки, но его байты не являются source pins.
                $directories.Push(@{path=$item.FullName;generated=$generated;depth=($directory.depth+1)})
                continue
            }
            if ($directory.generated) {continue}
            if ($pins.Count -ge 10000) {throw 'CANDIDATE_SOURCE_LIMIT'}
            $pins.Add($relative,
                (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant())
        }
    }
    return ,$pins
}

# Проверяет delivered source теми же правилами, что упаковщик, не исполняя его основной код.
function Assert-CandidateSourceClosure([string]$Root) {
    [void](Get-CandidateSourcePins $Root)
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($script:CandidateSourceValidator,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'CANDIDATE_SOURCE_VALIDATOR'}
    foreach ($name in 'Test-WithinPath','Test-ExcludedDirectory','Test-IncludedFile','Read-SourcePom','Assert-NoSourceSecret','Assert-DeliveredSource') {
        $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw 'CANDIDATE_SOURCE_VALIDATOR'}
        . ([scriptblock]::Create($definitions[0].Extent.Text))
    }
    $ns=[Xml.XmlNamespaceManager]::new([Xml.NameTable]::new());$ns.AddNamespace('m','http://maven.apache.org/POM/4.0.0')
    $pending=[Collections.Generic.Stack[string]]::new();$pending.Push('pom.xml')
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $targets=[Collections.Generic.List[string]]::new();$references=0
    while ($pending.Count) {
        $relative=$pending.Pop()
        if (-not $seen.Add($relative)) {continue}
        if ($seen.Count -gt 64) {throw 'CANDIDATE_SOURCE_LIMIT'}
        $path=Join-Path $Root $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {throw 'CANDIDATE_SOURCE_POM'}
        if ((Get-Item -LiteralPath $path).Length -gt 1048576) {throw 'CANDIDATE_SOURCE_LIMIT'}
        $document=Read-SourcePom ([IO.File]::ReadAllText($path))
        if (-not $document.SelectSingleNode('/m:project',$ns)) {throw 'CANDIDATE_SOURCE_POM'}
        $dir=[IO.Path]::GetDirectoryName($path)
        $targets.Add([IO.Path]::GetRelativePath($Root,(Join-Path $dir 'target')).Replace('\','/'))
        foreach ($group in $document.SelectNodes('//m:modules',$ns)) {
            $registered=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($node in $group.SelectNodes('m:module',$ns)) {
                if (++$references -gt 128) {throw 'CANDIDATE_SOURCE_LIMIT'}
                $module=$node.InnerText.Trim()
                if ($module -cnotmatch '^(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$') {throw 'CANDIDATE_SOURCE_MODULE_PATH'}
                if (-not $registered.Add($module)) {throw 'CANDIDATE_SOURCE_MODULE_DUPLICATE'}
                $child=[IO.Path]::GetFullPath((Join-Path $dir "$module/pom.xml"))
                if (-not $child.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'CANDIDATE_SOURCE_MODULE_PATH'}
                $pending.Push([IO.Path]::GetRelativePath($Root,$child).Replace('\','/'))
            }
        }
        $parent=$document.SelectSingleNode('/m:project/m:parent',$ns)
        if ($null -ne $parent) {
            $node=$parent.SelectSingleNode('m:relativePath',$ns)
            $parentRelative=if ($null -eq $node) {'../pom.xml'} else {$node.InnerText.Trim()}
            # Для proof родительский POM обязан замыкаться в snapshot, включая явный relativePath.
            if (-not $parentRelative) {throw 'CANDIDATE_SOURCE_PARENT_PATH'}
            if ($parentRelative) {
                if ($parentRelative -cnotmatch '^(?:(?:\.\.|[A-Za-z0-9_-]+)/)*(?:pom\.xml|[A-Za-z0-9_-]+)$') {throw 'CANDIDATE_SOURCE_PARENT_PATH'}
                $parentPath=[IO.Path]::GetFullPath((Join-Path $dir $parentRelative))
                if (Test-Path -LiteralPath $parentPath -PathType Container) {$parentPath=Join-Path $parentPath 'pom.xml'}
                if (-not $parentPath.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase) -or
                    -not (Test-Path -LiteralPath $parentPath -PathType Leaf)) {throw 'CANDIDATE_SOURCE_PARENT_PATH'}
                $pending.Push([IO.Path]::GetRelativePath($Root,$parentPath).Replace('\','/'))
            }
        }
    }
    $rootPom=Read-SourcePom ([IO.File]::ReadAllText((Join-Path $Root 'pom.xml')))
    if ($null -eq $rootPom.SelectSingleNode('/m:project/m:profiles/m:profile[m:id="dist"]/m:modules/m:module[normalize-space(.)="dist"]',$ns)) {throw 'CANDIDATE_SOURCE_DIST_PROFILE'}
    try {Assert-DeliveredSource $Root} catch {throw 'CANDIDATE_SOURCE_CLOSURE'}
    return ,$targets.ToArray()
}

# Сравнивает замороженные исходники; generated roots определены проверенным reactor, а не именем папки.
function Assert-CandidatePins([string]$Root,$Expected,[string]$Code,[string[]]$GeneratedTargetPaths=@()) {
    $actual=Get-CandidateSourcePins $Root -AllowGeneratedTargets:($GeneratedTargetPaths.Count -gt 0) -GeneratedTargetPaths $GeneratedTargetPaths
    if ($actual.Count -ne $Expected.Count) {throw $Code}
    foreach ($pin in $Expected.GetEnumerator()) {
        if (-not $actual.ContainsKey($pin.Key) -or $actual[$pin.Key] -cne $pin.Value) {throw $Code}
    }
}

# Разрешает только канонические свойства сборки: escape, continuation и повторные ключи неоднозначны.
function Read-CandidateAppInfo([string]$Text) {
    $values=@{}
    foreach ($line in [regex]::Split($Text,'\r?\n')) {
        if ($line -match '^\s*(?:[#!].*)?$') {continue}
        if ($line -cnotmatch '^(release|commit)=([^\r\n\\\x00\ufeff]+)$') {throw 'CANDIDATE_APP_INFO'}
        $key=$Matches[1];$value=$Matches[2]
        if ($values.ContainsKey($key)) {throw 'CANDIDATE_APP_INFO_DUPLICATE'}
        $values[$key]=$value
    }
    if ($values.Count -ne 2 -or $values.release -cnotmatch '^(0|[1-9][0-9]*)$' -or
        $values.commit -cnotmatch '^[0-9a-f]{40}$') {throw 'CANDIDATE_APP_INFO'}
    return $values
}

# Читает unsigned big-endian поля classfile без запуска Java.
function Read-CandidateClassNumber($Reader,[int]$Count) {
    [long]$value=0
    for ($i=0;$i -lt $Count;$i++) {$value=($value -shl 8) -bor $Reader.ReadByte()}
    return $value
}

# Имя берётся из Module attribute, а не из имени файла или случайной строки constant pool.
function Assert-CandidateJarModule([string]$Jar,[string]$Module,[string]$MainClass='') {
    $zip=[IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entries=@($zip.Entries | Where-Object {$_.FullName -ceq 'module-info.class'})
        if ($entries.Count -ne 1 -or $entries[0].Length -gt 65536) {throw 'CANDIDATE_JAR_MODULE'}
        if ($MainClass -and @($zip.Entries | Where-Object {$_.FullName -ceq ($MainClass.Replace('.','/')+'.class')}).Count -ne 1) {throw 'CANDIDATE_JAR_MAIN'}
        $stream=[IO.MemoryStream]::new();$input=$entries[0].Open()
        try {$input.CopyTo($stream)} finally {$input.Dispose()}
        $stream.Position=0;$reader=[IO.BinaryReader]::new($stream)
        try {
            if ((Read-CandidateClassNumber $reader 4) -ne 3405691582) {throw 'CANDIDATE_JAR_MODULE'}
            [void](Read-CandidateClassNumber $reader 4)
            $count=Read-CandidateClassNumber $reader 2;$pool=@{};$tags=@{}
            for ($i=1;$i -lt $count;$i++) {
                $tag=$reader.ReadByte();$tags[$i]=$tag
                switch ($tag) {
                    1 {$length=Read-CandidateClassNumber $reader 2;$bytes=$reader.ReadBytes($length)
                        if ($bytes.Length -ne $length) {throw 'CANDIDATE_JAR_MODULE'}
                        $pool[$i]=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)}
                    {$_ -in 7,8,16,19,20} {$pool[$i]=[int](Read-CandidateClassNumber $reader 2)}
                    {$_ -in 3,4,9,10,11,12,17,18} {[void](Read-CandidateClassNumber $reader 4)}
                    {$_ -in 5,6} {[void](Read-CandidateClassNumber $reader 4);[void](Read-CandidateClassNumber $reader 4);$i++}
                    15 {[void](Read-CandidateClassNumber $reader 3)}
                    default {throw 'CANDIDATE_JAR_MODULE'}
                }
            }
            $flags=Read-CandidateClassNumber $reader 2;$this=Read-CandidateClassNumber $reader 2
            if ($flags -ne 32768 -or $tags[[int]$this] -ne 7 -or $pool[$pool[[int]$this]] -cne 'module-info' -or
                (Read-CandidateClassNumber $reader 2) -ne 0 -or (Read-CandidateClassNumber $reader 2) -ne 0 -or
                (Read-CandidateClassNumber $reader 2) -ne 0 -or (Read-CandidateClassNumber $reader 2) -ne 0) {throw 'CANDIDATE_JAR_MODULE'}
            $attributes=Read-CandidateClassNumber $reader 2;$modules=0
            for ($i=0;$i -lt $attributes;$i++) {
                $name=[int](Read-CandidateClassNumber $reader 2);$length=Read-CandidateClassNumber $reader 4
                if ($tags[$name] -ne 1 -or $length -gt ($stream.Length-$stream.Position)) {throw 'CANDIDATE_JAR_MODULE'}
                $end=$stream.Position+$length
                if ($pool[$name] -ceq 'Module') {
                    if (++$modules -ne 1 -or $length -lt 16) {throw 'CANDIDATE_JAR_MODULE'}
                    $index=[int](Read-CandidateClassNumber $reader 2)
                    if ($tags[$index] -ne 19 -or $tags[$pool[$index]] -ne 1 -or $pool[$pool[$index]] -cne $Module) {throw 'CANDIDATE_JAR_MODULE'}
                }
                $stream.Position=$end
            }
            if ($modules -ne 1 -or $stream.Position -ne $stream.Length) {throw 'CANDIDATE_JAR_MODULE'}
        } finally {$reader.Dispose()}
    } catch {
        if ($_.Exception.Message -in 'CANDIDATE_JAR_MAIN','CANDIDATE_JAR_MODULE') {throw}
        throw 'CANDIDATE_JAR_MODULE'
    } finally {$zip.Dispose()}
}

# Строгая грамматика cfg: единственный соседний module-path/$APPDIR, без внешнего кода и аргументов.
function Assert-CandidateConfig([string]$Path,[string]$MainModule,[int]$Release,[bool]$Fx) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {throw 'CANDIDATE_CFG'}
    $file=Get-Item -LiteralPath $Path
    if ($file.Length -gt 16384 -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'CANDIDATE_CFG'}
    try {$text=[IO.File]::ReadAllText($Path,[Text.UTF8Encoding]::new($false,$true))} catch {throw 'CANDIDATE_CFG'}
    $allowed=@('-XX:-UsePerfData','-XX:-CreateCoredumpOnCrash','-XX:+SuppressFatalErrorMessage',
        '-XX:-DumpReplayDataOnError','-Duser.language=ru','-Duser.country=RU','-Xmx512m',"-Djpackage.app-version=1.0.$Release")
    if ($Fx) {$allowed+= '--enable-native-access=javafx.graphics'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $sections=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $section='';$main=0;$paths=0;$awaiting=$false
    foreach ($line in [regex]::Split($text,'\r?\n')) {
        if ($awaiting) {
            if ($line -cne 'java-options=$APPDIR') {throw 'CANDIDATE_CFG'}
            $awaiting=$false;continue
        }
        if ($line -ceq '') {continue}
        if ($line -ceq '[Application]' -or $line -ceq '[JavaOptions]') {
            if (-not $sections.Add($line)) {throw 'CANDIDATE_CFG'};$section=$line;continue
        }
        if ($section -ceq '[Application]' -and $line -ceq ('app.mainmodule='+$MainModule)) {
            if (++$main -ne 1) {throw 'CANDIDATE_CFG'};continue
        }
        if ($section -cne '[JavaOptions]' -or -not $line.StartsWith('java-options=',[StringComparison]::Ordinal)) {throw 'CANDIDATE_CFG'}
        $option=$line.Substring(13)
        if ($option -ceq '--module-path') {if (++$paths -ne 1) {throw 'CANDIDATE_CFG'};$awaiting=$true;continue}
        if ($option -cnotin $allowed -or -not $seen.Add($option)) {throw 'CANDIDATE_CFG'}
    }
    if ($awaiting -or $main -ne 1 -or $paths -ne 1 -or $sections.Count -ne 2 -or $seen.Count -ne $allowed.Count) {throw 'CANDIDATE_CFG'}
}

# Связывает AppInfo с единственным core JAR в нормализованном module-path всех трёх лаунчеров.
function Assert-CandidateVersion([string]$Image,[int]$Release,[string]$Sha) {
    [void](Get-CandidateImageInventory $Image)
    foreach ($launcher in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') {
        if (-not (Test-Path -LiteralPath (Join-Path $Image $launcher) -PathType Leaf)) {throw 'CANDIDATE_LAUNCHER_MISSING'}
    }
    if (Test-Path -LiteralPath (Join-Path $Image 'CashMemory')) {throw 'CANDIDATE_USER_FILES'}
    [void](Assert-CandidatePath $Image)
    $app=Join-Path $Image 'app';[void](Assert-CandidatePath $app)
    if (Test-Path -LiteralPath (Join-Path $app 'mods')) {throw 'CANDIDATE_APP_LAYOUT'}
    $roles=@{core='ru.cashprediction.core';'ui-fx'='ru.cashprediction.fx';'ui-swing'='ru.cashprediction.swing';web='ru.cashprediction.web'}
    $mains=@{'ui-fx'='ru.cashprediction.fx.FxMain';'ui-swing'='ru.cashprediction.swing.SwingMain';web='ru.cashprediction.web.WebMain'}
    $jars=@(Get-ChildItem -LiteralPath $app -Force -Recurse | Where-Object {$_.Name -imatch '\.jar$'})
    $found=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if ($jars.Count -ne 4) {throw 'CANDIDATE_APP_LAYOUT'}
    foreach ($file in $jars) {
        if ($file.PSIsContainer -or $file.DirectoryName -cne $app -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $file.Name -cnotmatch '^cashprediction-(core|ui-fx|ui-swing|web)-[A-Za-z0-9_.-]+\.jar$' -or -not $found.Add($Matches[1])) {throw 'CANDIDATE_APP_LAYOUT'}
        $role=$Matches[1];$mainClass=if ($role -ceq 'core') {''} else {$mains[$role]}
        Assert-CandidateJarModule $file.FullName $roles[$role] $mainClass
    }
    $core=@(Get-ChildItem -LiteralPath (Join-Path $Image 'app') -Filter 'cashprediction-core-*.jar')
    if ($core.Count -ne 1) {throw 'CANDIDATE_CORE_JAR'}
    $zip=[IO.Compression.ZipFile]::OpenRead($core[0].FullName)
    try {
        $entries=@($zip.Entries | Where-Object {$_.FullName -ceq 'ru/cashprediction/core/app.properties'})
        if ($entries.Count -gt 1) {throw 'CANDIDATE_APP_INFO_DUPLICATE'}
        if ($entries.Count -ne 1 -or $entries[0].Length -gt 4096) {throw 'CANDIDATE_APP_INFO'}
        $entry=$entries[0]
        $reader=[IO.StreamReader]::new($entry.Open(),[Text.UTF8Encoding]::new($false,$true))
        try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()}
        $info=Read-CandidateAppInfo $text
        if ($info.release -cne [string]$Release -or $info.commit -cne $Sha) {throw 'CANDIDATE_VERSION_MISMATCH'}
    } finally {$zip.Dispose()}
    foreach ($pair in @(@('CashPrediction','ui-fx'),@('CashPrediction-Swing','ui-swing'),@('CashPrediction-Web','web'))) {
        $role=$pair[1]
        Assert-CandidateConfig (Join-Path $app ($pair[0]+'.cfg')) ($roles[$role]+'/'+$mains[$role]) $Release ($role -ceq 'ui-fx')
    }
    return [pscustomobject]@{releaseNumber=$Release;commitSha=$Sha;portableDir=$Image;
        coreJar=$core[0].FullName;coreSha256=(Get-FileHash -LiteralPath $core[0].FullName).Hash.ToLowerInvariant()}
}

# Полный файловый inventory не исключает runtime и служебные файлы; ссылки и чрезмерные деревья запрещены.
function Get-CandidateImageInventory([string]$Image) {
    [void](Assert-CandidatePath $Image)
    $files=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    $pending=[Collections.Generic.Stack[object]]::new();$pending.Push(@{path=$Image;depth=0});$count=0
    while ($pending.Count) {
        $directory=$pending.Pop()
        foreach ($item in Get-ChildItem -LiteralPath $directory.path -Force) {
            if (++$count -gt 100000 -or $directory.depth -ge 32) {throw 'CANDIDATE_IMAGE_LIMIT'}
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'CANDIDATE_LINK'}
            if ($item.PSIsContainer) {$pending.Push(@{path=$item.FullName;depth=($directory.depth+1)});continue}
            $relative=[IO.Path]::GetRelativePath($Image,$item.FullName).Replace('\','/')
            $files.Add($relative,[pscustomobject]@{path=$relative;sizeBytes=$item.Length;
                sha256=(Get-FileHash -LiteralPath $item.FullName).Hash.ToLowerInvariant();
                readOnly=[bool]($item.Attributes -band [IO.FileAttributes]::ReadOnly)})
        }
    }
    return ,$files
}

# Сравнивает точный набор путей, размер, содержимое и read-only; одинаковые JAR не скрывают потерю runtime.
function Assert-CandidateImageInventory([string]$Image,$Expected,[string]$Code) {
    $actual=Get-CandidateImageInventory $Image
    if ($actual.Count -ne $Expected.Count) {throw $Code}
    foreach ($entry in $Expected.GetEnumerator()) {
        if (-not $actual.ContainsKey($entry.Key)) {throw $Code}
        $file=$actual[$entry.Key];$pin=$entry.Value
        if ($file.sizeBytes -ne $pin.sizeBytes -or $file.sha256 -cne $pin.sha256 -or $file.readOnly -ne $pin.readOnly) {throw $Code}
    }
}

# Замороженный built image проверяется перед копией и после неё, целевая копия - независимо.
function Copy-CandidateImage([string]$Source,[string]$Destination,$Owner,$Inventory) {
    Assert-CandidateImageInventory $Source $Inventory 'CANDIDATE_IMAGE_CHANGED'
    Copy-CandidateTree $Source $Destination $Owner
    Assert-CandidateImageInventory $Source $Inventory 'CANDIDATE_IMAGE_CHANGED'
    Assert-CandidateImageInventory $Destination $Inventory 'CANDIDATE_IMAGE_COPY_MISMATCH'
}

# Публикует пустую собственную папку атомарным rename: существующую цель Directory.Move не заменяет.
function New-CandidateDirectory([string]$Path) {
    [void](Assert-CandidatePath $Path)
    if (Test-Path -LiteralPath $Path) {throw 'CANDIDATE_OUTPUT_EXISTS'}
    $reservation=$Path+'.claim-'+[guid]::NewGuid().ToString()
    [void](New-Item -ItemType Directory -Path $reservation -ErrorAction Stop)
    try {
        [IO.Directory]::Move($reservation,$Path)
    } catch {throw 'CANDIDATE_OUTPUT_EXISTS'}
    finally {
        # Только собственная пустая reservation; никакого рекурсивного удаления чужого дерева.
        if (Test-Path -LiteralPath $reservation) {
            [void](Assert-CandidatePath $reservation)
            [IO.Directory]::Delete($reservation,$false)
        }
    }
}

# Занимает output один раз и держит маркер открытым без права записи/удаления другими процессами.
function New-CandidateOwnership([string]$Root) {
    New-CandidateDirectory $Root
    $path=Join-Path $Root '.candidate-owner'
    $stream=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    try {
        $token=[guid]::NewGuid().ToString()
        $bytes=[Text.Encoding]::UTF8.GetBytes($token);$stream.Write($bytes);$stream.Flush($true)
        return [pscustomobject]@{root=$Root;token=$token;stream=$stream}
    } catch {$stream.Dispose();throw}
}

# Перед каждой публикацией проверяет живой handle, буквальный путь и собственный маркер.
function Assert-CandidateOwnership($Owner) {
    [void](Assert-CandidatePath $Owner.root)
    if ($Owner.stream.SafeFileHandle.IsClosed) {throw 'CANDIDATE_OUTPUT_OWNERSHIP'}
    $path=Join-Path $Owner.root '.candidate-owner';[void](Assert-CandidatePath $path)
    # Читающий handle разрешает уже открытому владельцу запись; handle владельца запрещает новых писателей.
    $reader=[IO.StreamReader]::new([IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite))
    try {if ($reader.ReadToEnd() -cne $Owner.token) {throw 'CANDIDATE_OUTPUT_OWNERSHIP'}} finally {$reader.Dispose()}
}

# Копирует только в новую папку; каждый файл создаётся без замены, ссылки не обходятся.
function Copy-CandidateTree([string]$Source,[string]$Destination,$Owner) {
    Assert-CandidateOwnership $Owner
    if (-not $Destination.StartsWith($Owner.root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'CANDIDATE_OUTPUT_OWNERSHIP'}
    [void](Assert-CandidatePath $Source)
    New-CandidateDirectory $Destination
    $pending=[Collections.Generic.Stack[object]]::new();$pending.Push(@{source=$Source;destination=$Destination;depth=0})
    $count=0
    while ($pending.Count) {
        $pair=$pending.Pop()
        foreach ($item in Get-ChildItem -LiteralPath $pair.source -Force) {
            if (++$count -gt 100000 -or $pair.depth -ge 32) {throw 'CANDIDATE_SOURCE_LIMIT'}
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'CANDIDATE_LINK'}
            Assert-CandidateOwnership $Owner
            $target=Join-Path $pair.destination $item.Name
            [void](Assert-CandidatePath $target)
            if ($item.PSIsContainer) {
                New-CandidateDirectory $target
                $pending.Push(@{source=$item.FullName;destination=$target;depth=($pair.depth+1)})
            } else {
                if (Test-Path -LiteralPath $target) {throw 'CANDIDATE_OUTPUT_EXISTS'}
                try {[IO.File]::Copy($item.FullName,$target,$false)} catch {throw 'CANDIDATE_COPY_FAILED'}
            }
        }
    }
}

# Открывает лог или receipt с CreateNew; запись не может затереть появившиеся чужие данные.
function New-CandidateWriter([string]$Path,$Owner) {
    Assert-CandidateOwnership $Owner
    if (-not $Path.StartsWith($Owner.root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'CANDIDATE_OUTPUT_OWNERSHIP'}
    [void](Assert-CandidatePath $Path)
    if (Test-Path -LiteralPath $Path) {throw 'CANDIDATE_OUTPUT_EXISTS'}
    try {$stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
    catch {throw 'CANDIDATE_OUTPUT_EXISTS'}
    return [IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false))
}

# Единственный запуск Maven; fixture подменяет только эту границу, не orchestration и guards.
function Invoke-CandidatePackage([string]$MavenPath,[int]$Release,[string]$Sha,$Writer) {
    & $MavenPath -B -Pdist -DskipTests "-Dapp.release=$Release" "-Dapp.commit=$Sha" package 2>&1 |
        ForEach-Object {$Writer.WriteLine([string]$_);$_ | Out-Host}
    $Writer.Flush()
    if ($LASTEXITCODE -ne 0) {throw "CANDIDATE_BUILD_FAILED $Release"}
}

# Проверяет обе копии даже при ошибке, чтобы отказ Maven не обходил контроль неизменности.
function Assert-CandidateRunPins([string]$Source,[string]$Build,$Pins,[string[]]$Targets,[switch]$RequireBuild) {
    $failures=[Collections.Generic.List[string]]::new()
    try {Assert-CandidatePins $Source $Pins 'CANDIDATE_SOURCE_CHANGED'} catch {$failures.Add($_.Exception.Message)}
    if (Test-Path -LiteralPath $Build) {
        try {Assert-CandidatePins $Build $Pins 'CANDIDATE_BUILD_CHANGED_SOURCE' $Targets} catch {$failures.Add($_.Exception.Message)}
    } elseif ($RequireBuild) {
        $failures.Add('CANDIDATE_BUILD_CHANGED_SOURCE')
    }
    if ($failures.Count) {throw ($failures -join ' | ')}
}

# Сборки выполняются последовательно; до первой и на границах каждой проверяются source pins.
function Invoke-CandidateBuilds([string]$Source,$Owner,$Pins,[string[]]$Targets,[int[]]$Releases,[string]$MavenPath,[string]$Jdk,[string]$Sha,[string[]]$CommitByRelease=@()) {
    $commits=Resolve-CandidateCommits $Sha $CommitByRelease
    if ($Releases.Count -ne 3) {throw 'CANDIDATE_RELEASE_COUNT'}
    $build=Join-Path $Owner.root 'source'
    $records=[Collections.Generic.List[object]]::new();$previousJava=$env:JAVA_HOME;$pushed=$false;$failure=$null;$copied=$false
    try {
        Copy-CandidateTree $Source $build $Owner
        $copied=$true
        # Здесь generated directories ещё не разрешены: начальная копия должна быть точной.
        Assert-CandidatePins $build $Pins 'CANDIDATE_INITIAL_COPY_CHANGED'
        Assert-CandidatePins $Source $Pins 'CANDIDATE_SOURCE_CHANGED'
        Push-Location -LiteralPath $build;$pushed=$true;$env:JAVA_HOME=$Jdk
        for ($releaseIndex=0;$releaseIndex -lt $Releases.Count;$releaseIndex++) {
            $release=$Releases[$releaseIndex];$releaseCommit=$commits[$releaseIndex]
            Assert-CandidateRunPins $Source $build $Pins $Targets -RequireBuild
            $log=Join-Path $Owner.root ('build-'+$release+'.log')
            $writer=New-CandidateWriter $log $Owner
            try {Invoke-CandidatePackage $MavenPath $release $releaseCommit $writer} finally {$writer.Dispose()}
            Assert-CandidateRunPins $Source $build $Pins $Targets -RequireBuild
            $image=Join-Path $build 'dist/target/dist/CashPrediction'
            $inventory=Get-CandidateImageInventory $image
            [void](Assert-CandidateVersion $image $release $releaseCommit)
            $destination=Join-Path $Owner.root ('release-'+$release)
            Copy-CandidateImage $image $destination $Owner $inventory
            $record=Assert-CandidateVersion $destination $release $releaseCommit
            Assert-CandidateImageInventory $destination $inventory 'CANDIDATE_IMAGE_COPY_MISMATCH'
            $record | Add-Member -NotePropertyName files -NotePropertyValue @($inventory.Values)
            $record | Add-Member -NotePropertyName commitIdentityKind -NotePropertyValue 'LOCAL_METADATA'
            $records.Add($record)
            Assert-CandidateRunPins $Source $build $Pins $Targets -RequireBuild
        }
    } catch {$failure=$_}
    finally {
        try {
            try {Assert-CandidateRunPins $Source $build $Pins $Targets -RequireBuild:$copied}
            catch {
                # Сохраняет исходную причину отказа вместе с нарушением pins, не объявляя run успешным.
                if ($null -ne $failure -and $failure.Exception.Message -cne $_.Exception.Message) {
                    throw ($failure.Exception.Message+' | '+$_.Exception.Message)
                }
                throw
            }
        }
        finally {$env:JAVA_HOME=$previousJava;if ($pushed) {Pop-Location}}
    }
    if ($null -ne $failure) {throw $failure}
    return ,$records.ToArray()
}

if (-not $IsWindows) {throw 'CANDIDATE_WINDOWS_REQUIRED'}
[void](Resolve-CandidateCommits $Commit $CommitByRelease)
$source=Assert-CandidatePath $SourceSnapshot;$output=Assert-CandidatePath $OutputRoot
$mavenPath=Assert-CandidatePath $Maven;$jdk=Assert-CandidatePath $JdkHome
$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
if (-not $output.StartsWith($temp+'\',[StringComparison]::OrdinalIgnoreCase) -or
    $output -notmatch '-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or (Test-Path -LiteralPath $output)) {throw 'CANDIDATE_NEW_TEMP_UUID_REQUIRED'}
if ($source.StartsWith($output+'\',[StringComparison]::OrdinalIgnoreCase) -or
    $output.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath (Join-Path $source 'pom.xml') -PathType Leaf) -or
    -not (Test-Path -LiteralPath $mavenPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $jdk 'bin/jpackage.exe') -PathType Leaf)) {throw 'CANDIDATE_INPUTS'}
if ($ReleaseNumber[0] -lt 1 -or $ReleaseNumber[0] -ge $ReleaseNumber[1] -or $ReleaseNumber[1] -ge $ReleaseNumber[2]) {throw 'CANDIDATE_INCREASING_RELEASES'}
$before=Get-CandidateSourcePins $source
if (-not $before.Count) {throw 'CANDIDATE_EMPTY_SOURCE'}
$targets=Assert-CandidateSourceClosure $source
Assert-CandidatePins $source $before 'CANDIDATE_SOURCE_CHANGED'
$owner=New-CandidateOwnership $output
try {
    $records=Invoke-CandidateBuilds $source $owner $before $targets $ReleaseNumber $mavenPath $jdk $Commit $CommitByRelease
    $receipt=New-CandidateReceipt $source $before $records ($PSCmdlet.ParameterSetName -ceq 'LocalCommits')
    $receiptPath=Join-Path $output 'candidate-images.json'
    $writer=New-CandidateWriter $receiptPath $owner
    try {$writer.Write(($receipt | ConvertTo-Json -Depth 16))} finally {$writer.Dispose()}
    [pscustomobject]@{OutputRoot=$output;Receipt=$receiptPath;ReceiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash.ToLowerInvariant();Images=$records}
} finally {$owner.stream.Dispose()}
