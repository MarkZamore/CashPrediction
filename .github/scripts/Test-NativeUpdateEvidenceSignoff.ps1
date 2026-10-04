<#
.SYNOPSIS
Bounded final assembly frozen native batches; никогда не повышает helper status.
.DESCRIPTION
MAIN создаёт Context: schemaVersion=1, sourceIdentity=fresh UUID, freshAfterUtc,
sourcePins=[path/sha256], coldPin, lifePin, planSourcePin, planReaderPin.
Последние два pin - UpdateEvidence.java и Test-NativeUpdateLifecycle.ps1 из тех же
sourcePins. Все batch/proof записи связываются с SHA canonical Context, не названием.
MAIN после actual acceptor bridge PASS явно применяет verdict к своей row. Этот файл
row НЕ меняет. Save-NativeEvidenceCellProof -Row -Verdict -Context -EvidenceRoot
-EvidencePins -Destination сохраняет verdict и pins всех common artifacts + raw proof
inputs. Retained-handle acceptance MAIN обязан выполнить ДО disposal, не retrospective.
После записи frozen results: Save-NativeEvidenceBatchSeal -ResultsPin -ProofPins
-Context -EvidenceRoot -Destination. Никаких автоматических seals старых results.
Request JSON: schemaVersion=1, context, batches=[seal path/sha256], verifier={javaPin,
corePin, classPins=[path/sha256 для трёх classes]}, outputDirectory. Request pin caller
передаёт независимо. Все pins lowercase SHA256, абсолютные paths без reparse.
Вызов: & <this.ps1> -RequestFile <absolute.json> -RequestSha256 <sha> -Signoff.
Каждый batch может иметь все canonical PENDING rows; выбираются только exact sealed
accepted rows. Повторный accepted key - ошибка, не last-wins. Непринятые helper PASS
не экспортируются. Canonical plan переиспользуется из frozen Get-NativeEvidencePlan,
не определяется заново; окончательный authority - frozen JDK UpdateEvidenceVerifier.
Частичный набор пишет diagnostic.json PENDING без results.json и отвергает -Signoff.
Полный набор сначала проверяется CLI в явно staging directory; только exit0 и повторная
проверка всех pins позволяют non-overwriting publish results.json. После CLI отказа
staging candidate не является signoff. Никаких GUI/native products/Maven/cleanup.
Текущий bridge имеет missing routes: пока он PENDING, соответствующий proof не создаётся.
#>
[CmdletBinding()]param([string]$RequestFile,[string]$RequestSha256,[switch]$Signoff)

function Assert-SignoffFields($Object,[string[]]$Fields) {
    if ($Object -isnot [pscustomobject] -or @($Object.PSObject.Properties).Count -ne $Fields.Count) {throw 'SIGNOFF_FIELDS'}
    foreach ($field in $Fields) {if ($Object.PSObject.Properties.Name -cnotcontains $field) {throw 'SIGNOFF_FIELDS'}}
}
function Get-SignoffValue($Object,[string]$Name) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {throw 'SIGNOFF_MISSING'}
    return ,$Object.$Name
}
function Test-SignoffInteger($Value) {return ($Value -is [int] -or $Value -is [long])}
function Get-SignoffDigest($Object) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(
        (ConvertTo-Json -InputObject $Object -Compress -Depth 64)))).ToLowerInvariant()
}
function Get-SignoffTicks($Value) {
    if ($Value -isnot [string]) {throw 'SIGNOFF_TIME'}
    $match=[regex]::Match($Value,'^(?<prefix>\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(?<fraction>\d{1,9}))?(?:Z|\+00:00)$')
    if (-not $match.Success) {throw 'SIGNOFF_TIME'}
    $fraction=$match.Groups['fraction'].Value.PadRight(7,'0');if ($fraction.Length -gt 7) {$fraction=$fraction.Substring(0,7)}
    return [DateTimeOffset]::ParseExact(($match.Groups['prefix'].Value+'.'+$fraction+'Z'),"yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
        [Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal).UtcTicks
}

# Ограниченные paths/read/hash проверяются снова после CLI; links не разрешаются.
function Resolve-SignoffPath([string]$Path,[switch]$NewLeaf) {
    if (-not $Path -or -not [IO.Path]::IsPathFullyQualified($Path) -or [IO.Path]::GetFullPath($Path) -cne $Path) {throw 'SIGNOFF_PATH'}
    $cursor=if ($NewLeaf) {[IO.Path]::GetDirectoryName($Path)} else {$Path}
    while ($cursor) {
        $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'SIGNOFF_LINK'}
        $parent=[IO.Path]::GetDirectoryName($cursor);if ($parent -eq $cursor) {break};$cursor=$parent
    }
    return $Path
}
function Assert-SignoffWithin([string]$Path,[string]$Root) {
    if (-not $Path.StartsWith($Root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)) {throw 'SIGNOFF_ARTIFACT_SCOPE'}
}
function Read-SignoffPin($Pin,$Seen) {
    Assert-SignoffFields $Pin @('path','sha256')
    $path=Resolve-SignoffPath $Pin.path
    if ($Pin.sha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'SIGNOFF_PIN'}
    $item=Get-Item -LiteralPath $path
    if ($item.PSIsContainer -or $item.Length -gt 8388608) {throw 'SIGNOFF_SIZE'}
    $stream=[IO.File]::OpenRead($path)
    try {
        $memory=[IO.MemoryStream]::new();$buffer=[byte[]]::new(65536);$total=0
        try {
            while (($count=$stream.Read($buffer,0,$buffer.Length)) -gt 0) {
                $total+=$count;if ($total -gt 8388608) {throw 'SIGNOFF_SIZE'};$memory.Write($buffer,0,$count)
            }
            $bytes=$memory.ToArray()
        } finally {$memory.Dispose()}
    } finally {$stream.Dispose()}
    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Pin.sha256) {throw 'SIGNOFF_CHANGED'}
    if ($null -ne $Seen) {
        if ($Seen.ContainsKey($path) -and $Seen[$path] -cne $Pin.sha256) {throw 'SIGNOFF_PIN_CONFLICT'}
        if ($Seen.Count -ge 30000 -and -not $Seen.ContainsKey($path)) {throw 'SIGNOFF_PIN_LIMIT'};$Seen[$path]=$Pin.sha256
    }
    return ,$bytes
}
function Assert-SignoffJsonNode($Node) {
    if ($Node.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Node.EnumerateObject()) {if (-not $names.Add($property.Name)) {throw 'SIGNOFF_JSON_DUPLICATE'};Assert-SignoffJsonNode $property.Value}
    } elseif ($Node.ValueKind -eq [Text.Json.JsonValueKind]::Array) {foreach ($entry in $Node.EnumerateArray()) {Assert-SignoffJsonNode $entry}}
}
function Read-SignoffJson($Pin,$Seen) {
    $text=[Text.UTF8Encoding]::new($false,$true).GetString((Read-SignoffPin $Pin $Seen))
    $options=[Text.Json.JsonDocumentOptions]::new();$options.MaxDepth=64
    $document=[Text.Json.JsonDocument]::Parse($text,$options)
    try {Assert-SignoffJsonNode $document.RootElement} finally {$document.Dispose()}
    $args=@{InputObject=$text;Depth=64;NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$args.DateKind='String'}
    return ,(ConvertFrom-Json @args)
}
function New-SignoffPin([string]$Path) {
    [void](Resolve-SignoffPath $Path)
    return [pscustomobject]@{path=$Path;sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function Write-SignoffNew([string]$Path,$Object) {
    [void](Resolve-SignoffPath $Path -NewLeaf)
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes((ConvertTo-Json -InputObject $Object -Depth 64 -Compress))
    if ($bytes.Length -gt 8388608) {throw 'SIGNOFF_SIZE'}
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    return New-SignoffPin $Path
}
function Write-SignoffBytesNew([string]$Path,[byte[]]$Bytes) {
    [void](Resolve-SignoffPath $Path -NewLeaf)
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($Bytes,0,$Bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
}
function Get-SignoffCellKey($Row) {
    $parts=@(foreach ($name in 'scenario','base','client','path','phase') {
        $value=Get-SignoffValue $Row $name
        if ($value -isnot [string] -or $value -cnotmatch '^[A-Za-z0-9_-]{1,96}$') {throw 'SIGNOFF_CELL_IDENTITY'};$value
    })
    return $parts -join '/'
}
function Assert-SignoffContext($Context,$Seen) {
    Assert-SignoffFields $Context @('schemaVersion','sourceIdentity','freshAfterUtc','sourcePins','coldPin','lifePin','planSourcePin','planReaderPin')
    if (-not (Test-SignoffInteger $Context.schemaVersion) -or $Context.schemaVersion -ne 1 -or
        $Context.sourceIdentity -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'SIGNOFF_SOURCE_IDENTITY'}
    [void](Get-SignoffTicks $Context.freshAfterUtc)
    if ($Context.sourcePins -isnot [array] -or $Context.sourcePins.Count -lt 4 -or $Context.sourcePins.Count -gt 128) {throw 'SIGNOFF_SOURCE_SET'}
    $sources=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($pin in $Context.sourcePins) {if (-not $sources.Add($pin.path)) {throw 'SIGNOFF_SOURCE_SET'};[void](Read-SignoffPin $pin $Seen)}
    foreach ($pin in @($Context.planSourcePin,$Context.planReaderPin)) {
        if (-not $sources.Contains($pin.path) -or @($Context.sourcePins | Where-Object {$_.path -ceq $pin.path -and $_.sha256 -ceq $pin.sha256}).Count -ne 1) {throw 'SIGNOFF_SOURCE_SET'}
    }
    if ([IO.Path]::GetFileName($Context.planSourcePin.path) -cne 'UpdateEvidence.java' -or
        [IO.Path]::GetFileName($Context.planReaderPin.path) -cne 'Test-NativeUpdateLifecycle.ps1' -or
        @($Context.sourcePins | Where-Object {[IO.Path]::GetFileName($_.path) -ceq 'NativeUpdateAcceptanceDispatch.ps1'}).Count -ne 1 -or
        @($Context.sourcePins | Where-Object {[IO.Path]::GetFileName($_.path) -ceq 'UpdateEvidenceVerifier.java'}).Count -ne 1) {throw 'SIGNOFF_SOURCE_SET'}
    $cold=Read-SignoffJson $Context.coldPin $Seen;$life=Read-SignoffJson $Context.lifePin $Seen
    Assert-SignoffFields $cold @('schemaVersion','runtimeSha256','toolArguments','toolFiles','helperScript','helperSha256','baseManifests','targetManifest','targetManifestSha256')
    Assert-SignoffFields $life @('schemaVersion','artifactDir','manifestSha256','harnessClasspath','harnessFiles')
    if (-not (Test-SignoffInteger $cold.schemaVersion) -or $cold.schemaVersion -ne 1 -or
        -not (Test-SignoffInteger $life.schemaVersion) -or $life.schemaVersion -ne 1) {throw 'SIGNOFF_CONFIG'}
    if ($cold.runtimeSha256 -cnotmatch '^[0-9a-f]{64}$' -or $cold.toolArguments -isnot [array] -or $cold.toolArguments.Count -ne 4 -or
        $cold.toolArguments[0] -cne '--module-path' -or $cold.toolArguments[2] -cne '-m' -or
        $cold.toolArguments[3] -cne 'ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool' -or
        $life.harnessClasspath -isnot [string] -or -not $life.harnessClasspath) {throw 'SIGNOFF_CONFIG'}
    [void](Read-SignoffPin ([pscustomobject]@{path=$cold.helperScript;sha256=$cold.helperSha256}) $Seen)
    if ($cold.toolFiles -isnot [array] -or $life.harnessFiles -isnot [array] -or
        $cold.toolFiles.Count -lt 2 -or $cold.toolFiles.Count -gt 4 -or
        $life.harnessFiles.Count -lt 1 -or $life.harnessFiles.Count -gt 20000) {throw 'SIGNOFF_CONFIG'}
    foreach ($group in @($cold.toolFiles,$life.harnessFiles)) {
        $unique=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($pin in $group) {if (-not $unique.Add($pin.path)) {throw 'SIGNOFF_CONFIG'};[void](Read-SignoffPin $pin $Seen)}
    }
    if ($cold.toolArguments[1] -isnot [string]) {throw 'SIGNOFF_CONFIG'}
    $toolPaths=@($cold.toolArguments[1].Split(';'))
    if ($toolPaths.Count -ne $cold.toolFiles.Count) {throw 'SIGNOFF_CONFIG'}
    $toolSet=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $toolPaths) {
        if (-not $toolSet.Add($path) -or [IO.Path]::GetExtension($path) -cne '.jar' -or
            @($cold.toolFiles | Where-Object {$_.path -ceq $path}).Count -ne 1) {throw 'SIGNOFF_CONFIG'}
    }
    # Не сканируем machine/classpath: проверяем только явно объявленные frozen entries/pins.
    $entries=@($life.harnessClasspath.Split(';'))
    $entrySet=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($entries.Count -gt 4) {throw 'SIGNOFF_CONFIG'}
    foreach ($entry in $entries) {
        [void](Resolve-SignoffPath $entry)
        if (-not $entrySet.Add($entry)) {throw 'SIGNOFF_CONFIG'}
        $directory=(Get-Item -LiteralPath $entry).PSIsContainer
        $matches=@($life.harnessFiles | Where-Object {
            if ($directory) {$_.path.StartsWith($entry+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)} else {$_.path -ceq $entry}
        })
        if (-not $matches.Count -or (-not $directory -and [IO.Path]::GetExtension($entry) -cne '.jar')) {throw 'SIGNOFF_CONFIG'}
    }
    foreach ($pin in $life.harnessFiles) {
        $matches=@($entries | Where-Object {$pin.path -ceq $_ -or $pin.path.StartsWith($_+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)})
        if ($matches.Count -ne 1) {throw 'SIGNOFF_CONFIG'}
    }
    $target=Read-SignoffJson ([pscustomobject]@{path=$cold.targetManifest;sha256=$cold.targetManifestSha256}) $Seen
    $lifeTarget=Read-SignoffJson ([pscustomobject]@{path=(Join-Path $life.artifactDir 'update.json');sha256=$life.manifestSha256}) $Seen
    # Served manifest может иметь новые ZIP/delta descriptors; image identity остаётся exact.
    if ($target.commitSha -cne $lifeTarget.commitSha -or $target.releaseNumber -ne $lifeTarget.releaseNumber -or
        $target.treeSha256 -cne $lifeTarget.treeSha256 -or (Get-SignoffDigest $target.files) -cne (Get-SignoffDigest $lifeTarget.files)) {throw 'SIGNOFF_CONFIG'}
    $bases=@(foreach ($pin in $cold.baseManifests) {Read-SignoffJson ([pscustomobject]@{path=$pin.manifest;sha256=$pin.sha256}) $Seen})
    if ($bases.Count -ne 2) {throw 'SIGNOFF_CONFIG'}
    return [pscustomobject]@{cold=$cold;life=$life;target=$target;bases=$bases;contextSha256=(Get-SignoffDigest $Context)}
}

# Реальный bridge verdict, не helper status; поля родного verdict не реконструируются.
function Assert-SignoffAcceptedProof($Proof,$Row,$Context,[string]$EvidenceRoot,$Seen) {
    Assert-SignoffFields $Proof @('schemaVersion','scope','evidenceKind','sourceIdentity','contextSha256','cellKey','rowSha256','acceptedAt','verdict','evidencePins')
    $key=Get-SignoffCellKey $Row
    if (-not (Test-SignoffInteger $Proof.schemaVersion) -or $Proof.schemaVersion -ne 1 -or
        $Proof.scope -cne 'MAIN_ACCEPTED_NATIVE_CELL' -or $Proof.evidenceKind -cne 'NATIVE' -or
        $Proof.sourceIdentity -cne $Context.sourceIdentity -or $Proof.contextSha256 -cne (Get-SignoffDigest $Context) -or
        $Proof.cellKey -cne $key -or $Proof.rowSha256 -cne (Get-SignoffDigest $Row)) {throw 'SIGNOFF_PROOF_BINDING'}
    $verdict=$Proof.verdict
    foreach ($field in 'status','scope','cellKey','route','routeEvidenceValidated','canonicalRowUnchanged','gaps','contradictions',
        'acceptorVerdict','copiedRow','envelope','evidenceKind','fullMatrix','releaseProvenance') {[void](Get-SignoffValue $verdict $field)}
    if ($verdict.status -cne 'PASS' -or $verdict.scope -cne 'SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY' -or
        $verdict.cellKey -cne $key -or $verdict.evidenceKind -cne 'NATIVE' -or
        $verdict.routeEvidenceValidated -isnot [bool] -or -not $verdict.routeEvidenceValidated -or
        $verdict.canonicalRowUnchanged -isnot [bool] -or -not $verdict.canonicalRowUnchanged -or
        $verdict.gaps -isnot [array] -or $verdict.gaps.Count -ne 0 -or $verdict.contradictions -isnot [array] -or $verdict.contradictions.Count -ne 0 -or
        $verdict.fullMatrix -cne 'PENDING' -or $verdict.releaseProvenance -cne 'PENDING') {throw 'SIGNOFF_ACCEPTANCE_PENDING'}
    if ($null -eq $verdict.acceptorVerdict -or $null -eq $verdict.copiedRow) {throw 'SIGNOFF_ACCEPTANCE_PENDING'}
    # MAIN может применить verdict к status/reason, но actual identity/artifacts не подменяются.
    foreach ($field in 'scenario','base','client','path','phase','executed','exe','args','baseCommit','baseRelease','targetCommit','targetRelease',
        'startedAt','finishedAt','exitCode','failures','skipped','currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {
        if ((Get-SignoffDigest (Get-SignoffValue $Row $field)) -cne (Get-SignoffDigest (Get-SignoffValue $verdict.copiedRow $field))) {throw 'SIGNOFF_ACCEPTED_ROW_CHANGED'}
    }
    if ((Get-SignoffValue $Row 'status') -cne 'PASS' -or (Get-SignoffValue $Row 'executed') -isnot [bool] -or -not $Row.executed) {throw 'SIGNOFF_ROW_NOT_ACCEPTED'}
    foreach ($field in 'exitCode','failures','skipped') {if (-not (Test-SignoffInteger (Get-SignoffValue $Row $field)) -or $Row.$field -ne 0) {throw 'SIGNOFF_ROW_NOT_ACCEPTED'}}
    $start=Get-SignoffTicks (Get-SignoffValue $Row 'startedAt');$finish=Get-SignoffTicks (Get-SignoffValue $Row 'finishedAt');$accepted=Get-SignoffTicks $Proof.acceptedAt
    if ($start -lt (Get-SignoffTicks $Context.freshAfterUtc) -or $finish -lt $start -or $accepted -lt $finish -or $accepted -gt [datetime]::UtcNow.Ticks) {throw 'SIGNOFF_OLD_PROVENANCE'}
    if ($Proof.evidencePins -isnot [array] -or $Proof.evidencePins.Count -lt 9 -or $Proof.evidencePins.Count -gt 256) {throw 'SIGNOFF_PROOF_ARTIFACTS'}
    $paths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($pin in $Proof.evidencePins) {
        Assert-SignoffWithin $pin.path $EvidenceRoot
        if (-not $paths.Add($pin.path)) {throw 'SIGNOFF_PROOF_ARTIFACTS'};[void](Read-SignoffPin $pin $Seen)
    }
    foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {
        $path=Get-SignoffValue $Row $field
        if ($path -isnot [string] -or -not $paths.Contains($path)) {throw 'SIGNOFF_PROOF_ARTIFACTS'}
    }
    return $key
}
function Save-NativeEvidenceCellProof($Row,$Verdict,$Context,[string]$EvidenceRoot,$EvidencePins,[string]$Destination) {
    $seen=@{};[void](Assert-SignoffContext $Context $seen)
    [void](Resolve-SignoffPath $EvidenceRoot);Assert-SignoffWithin $Destination $EvidenceRoot
    $proof=[pscustomobject][ordered]@{schemaVersion=1;scope='MAIN_ACCEPTED_NATIVE_CELL';evidenceKind='NATIVE';
        sourceIdentity=$Context.sourceIdentity;contextSha256=(Get-SignoffDigest $Context);cellKey=(Get-SignoffCellKey $Row);
        rowSha256=(Get-SignoffDigest $Row);acceptedAt=[datetime]::UtcNow.ToString('o');verdict=$Verdict;evidencePins=@($EvidencePins)}
    [void](Assert-SignoffAcceptedProof $proof $Row $Context $EvidenceRoot $seen)
    return Write-SignoffNew $Destination $proof
}
function Save-NativeEvidenceBatchSeal($ResultsPin,$ProofPins,$Context,[string]$EvidenceRoot,[string]$Destination) {
    $seen=@{};[void](Assert-SignoffContext $Context $seen)
    Assert-SignoffWithin $ResultsPin.path $EvidenceRoot;Assert-SignoffWithin $Destination $EvidenceRoot
    [void](Read-SignoffJson $ResultsPin $seen)
    if (@($ProofPins).Count -lt 1 -or @($ProofPins).Count -gt 612) {throw 'SIGNOFF_BATCH_PROOFS'}
    foreach ($pin in $ProofPins) {Assert-SignoffWithin $pin.path $EvidenceRoot;[void](Read-SignoffJson $pin $seen)}
    return Write-SignoffNew $Destination ([pscustomobject][ordered]@{schemaVersion=1;scope='FROZEN_NATIVE_BATCH';
        sourceIdentity=$Context.sourceIdentity;contextSha256=(Get-SignoffDigest $Context);evidenceRoot=$EvidenceRoot;
        sealedAt=[datetime]::UtcNow.ToString('o');results=$ResultsPin;proofs=@($ProofPins)})
}

# Переиспользуется frozen plan reader, а не вторая копия canonical комбинаций.
function Get-SignoffPlan($Context,$Seen) {
    $text=[Text.UTF8Encoding]::new($false,$true).GetString((Read-SignoffPin $Context.planReaderPin $Seen))
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'SIGNOFF_PLAN_PARSE'}
    $functions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Get-NativeEvidencePlan'})
    if ($functions.Count -ne 1) {throw 'SIGNOFF_PLAN_EXPORT'}
    . ([scriptblock]::Create($functions[0].Extent.Text))
    return @(Get-NativeEvidencePlan $Context.planSourcePin.path)
}
function Merge-NativeEvidenceBatches($Context,$Batches,$Plan,$Config,$Seen) {
    $expected=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in $Plan) {if (-not $expected.Add((Get-SignoffCellKey $row))) {throw 'SIGNOFF_PLAN_DUPLICATE'}}
    $accepted=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $batchPaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($Batches -isnot [array] -or $Batches.Count -lt 1 -or $Batches.Count -gt 612) {throw 'SIGNOFF_BATCHES'}
    foreach ($batchPin in $Batches) {
        if (-not $batchPaths.Add($batchPin.path)) {throw 'SIGNOFF_BATCH_DUPLICATE'}
        $seal=Read-SignoffJson $batchPin $Seen
        Assert-SignoffFields $seal @('schemaVersion','scope','sourceIdentity','contextSha256','evidenceRoot','sealedAt','results','proofs')
        if (-not (Test-SignoffInteger $seal.schemaVersion) -or $seal.schemaVersion -ne 1 -or $seal.scope -cne 'FROZEN_NATIVE_BATCH' -or
            $seal.sourceIdentity -cne $Context.sourceIdentity -or $seal.contextSha256 -cne $Config.contextSha256) {throw 'SIGNOFF_BATCH_CONTEXT'}
        [void](Resolve-SignoffPath $seal.evidenceRoot);Assert-SignoffWithin $batchPin.path $seal.evidenceRoot;Assert-SignoffWithin $seal.results.path $seal.evidenceRoot
        if (-not $batchPaths.Add($seal.results.path)) {throw 'SIGNOFF_BATCH_DUPLICATE'}
        $result=Read-SignoffJson $seal.results $Seen;Assert-SignoffFields $result @('schemaVersion','status','cells')
        if (-not (Test-SignoffInteger $result.schemaVersion) -or $result.schemaVersion -ne 1 -or
            $result.status -cnotin @('PENDING','PASS') -or $result.cells -isnot [array] -or $result.cells.Count -ne $expected.Count) {throw 'SIGNOFF_BATCH_RESULTS'}
        $rows=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($row in $result.cells) {
            $key=Get-SignoffCellKey $row
            if (-not $expected.Contains($key) -or $rows.ContainsKey($key)) {throw 'SIGNOFF_CELL_IDENTITY'};$rows[$key]=$row
        }
        if ($seal.proofs -isnot [array] -or $seal.proofs.Count -lt 1 -or $seal.proofs.Count -gt $expected.Count) {throw 'SIGNOFF_BATCH_PROOFS'}
        $proofPaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($pin in $seal.proofs) {
            Assert-SignoffWithin $pin.path $seal.evidenceRoot
            if (-not $proofPaths.Add($pin.path)) {throw 'SIGNOFF_PROOF_DUPLICATE'}
            $proof=Read-SignoffJson $pin $Seen
            if (-not $rows.ContainsKey($proof.cellKey)) {throw 'SIGNOFF_CELL_IDENTITY'}
            $row=$rows[$proof.cellKey];$key=Assert-SignoffAcceptedProof $proof $row $Context $seal.evidenceRoot $Seen
            if ($accepted.ContainsKey($key)) {throw 'SIGNOFF_ACCEPTED_DUPLICATE'}
            if ((Get-SignoffTicks $seal.sealedAt) -lt (Get-SignoffTicks $proof.acceptedAt)) {throw 'SIGNOFF_TIME'}
            $base=$Config.bases[$(if ($row.base -ceq 'B1') {0} else {1})]
            if ($row.baseCommit -cne $base.commitSha -or $row.baseRelease -ne $base.releaseNumber -or
                $row.targetCommit -cne $Config.target.commitSha -or $row.targetRelease -ne $Config.target.releaseNumber) {throw 'SIGNOFF_CONFIG_ROW'}
            $accepted[$key]=$row
        }
    }
    $missing=@($Plan | Where-Object {-not $accepted.ContainsKey((Get-SignoffCellKey $_))} | ForEach-Object {Get-SignoffCellKey $_})
    $ordered=@($Plan | Where-Object {$accepted.ContainsKey((Get-SignoffCellKey $_))} | ForEach-Object {$accepted[(Get-SignoffCellKey $_)]})
    return [pscustomobject]@{status='ASSEMBLY_NOT_SIGNOFF';rows=$ordered;missing=$missing}
}

# Единственный Java entrypoint: копируются лишь три pinned classes и core, без ambient CP.
function Invoke-SignoffJdk($Verifier,[string]$Candidate,[string]$Output,$Config,$Seen) {
    Assert-SignoffFields $Verifier @('javaPin','corePin','classPins')
    [void](Read-SignoffPin $Verifier.javaPin $Seen);$core=Read-SignoffPin $Verifier.corePin $Seen
    if ([IO.Path]::GetFileName($Verifier.javaPin.path) -cne 'java.exe' -or $Verifier.javaPin.sha256 -cne $Config.cold.runtimeSha256 -or
        @($Config.cold.toolFiles | Where-Object {$_.path -ceq $Verifier.corePin.path -and $_.sha256 -ceq $Verifier.corePin.sha256}).Count -ne 1 -or
        $Verifier.classPins -isnot [array] -or $Verifier.classPins.Count -ne 3) {throw 'SIGNOFF_VERIFIER_PINS'}
    $classes=Join-Path $Output 'verifier/ru/cashprediction/parity/update';[void][IO.Directory]::CreateDirectory($classes)
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($pin in $Verifier.classPins) {
        $name=[IO.Path]::GetFileName($pin.path)
        if ($name -cnotin @('UpdateEvidenceVerifier.class','UpdateEvidence.class','FixtureAuthority.class') -or -not $names.Add($name)) {throw 'SIGNOFF_VERIFIER_PINS'}
        $copyPath=Join-Path $classes $name
        Write-SignoffBytesNew $copyPath (Read-SignoffPin $pin $Seen)
        [void](Read-SignoffPin ([pscustomobject]@{path=$copyPath;sha256=$pin.sha256}) $Seen)
    }
    $corePath=Join-Path $Output 'verifier-core.jar';Write-SignoffBytesNew $corePath $core
    [void](Read-SignoffPin ([pscustomobject]@{path=$corePath;sha256=$Verifier.corePin.sha256}) $Seen)
    $info=[Diagnostics.ProcessStartInfo]::new($Verifier.javaPin.path);$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.WorkingDirectory=$Output
    # Только окружение собственного child: запрещаем ambient JVM agents/options без изменений host.
    foreach ($name in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH') {[void]$info.Environment.Remove($name)}
    foreach ($arg in @('-XX:-UsePerfData','-cp',((Join-Path $Output 'verifier')+';'+$corePath),'ru.cashprediction.parity.update.UpdateEvidenceVerifier',$Candidate)) {$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$started=$false
    try {
        if (-not $process.Start()) {throw 'SIGNOFF_CLI_START'}
        $started=$true
        # Retained handle принадлежит только этой console JVM, не поиск по чужому PID.
        $null=$process.Handle;$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {$process.Kill();[void]$process.WaitForExit(5000);throw 'SIGNOFF_CLI_TIMEOUT'}
        if (-not [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($stdout,$stderr)).Wait(5000)) {throw 'SIGNOFF_CLI_DRAIN'}
        $out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult()
        if ($out.Length -gt 256 -or $err.Length -gt 256) {throw 'SIGNOFF_CLI_DIAGNOSTICS'}
        # Сохраняются только bounded техкоды, не неизвестные stderr paths/payload.
        if ($process.ExitCode -ne 0 -or $err -cne '' -or $out -cnotmatch '^UPDATE_EVIDENCE_VERIFIED cells=[0-9]+\r?\n$') {throw 'SIGNOFF_CLI_REJECTED'}
        return [pscustomobject]@{exitCode=$process.ExitCode;stdout=$out;stderr='';nativeProductsExecuted=$false}
    } finally {if ($started -and -not $process.HasExited) {$process.Kill();[void]$process.WaitForExit(5000)};$process.Dispose()}
}
function Invoke-NativeUpdateEvidenceSignoff([string]$RequestFile,[string]$RequestSha256,[switch]$Signoff) {
    $ErrorActionPreference='Stop';Set-StrictMode -Version 3
    $seen=@{};$request=Read-SignoffJson ([pscustomobject]@{path=$RequestFile;sha256=$RequestSha256}) $seen
    Assert-SignoffFields $request @('schemaVersion','context','batches','verifier','outputDirectory')
    if (-not (Test-SignoffInteger $request.schemaVersion) -or $request.schemaVersion -ne 1) {throw 'SIGNOFF_SCHEMA'}
    $config=Assert-SignoffContext $request.context $seen
    $plan=@(Get-SignoffPlan $request.context $seen)
    $assembly=Merge-NativeEvidenceBatches $request.context $request.batches $plan $config $seen
    $output=$request.outputDirectory;[void](Resolve-SignoffPath $output -NewLeaf)
    if ([IO.Path]::GetDirectoryName($output) -cne [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') -or
        [IO.Path]::GetFileName($output) -cnotmatch '^cp-native-signoff-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
        (Test-Path -LiteralPath $output)) {throw 'SIGNOFF_OUTPUT'}
    $null=New-Item -ItemType Directory -Path $output -ErrorAction Stop
    if ($assembly.missing.Count) {
        [void](Write-SignoffNew (Join-Path $output 'diagnostic.json') ([pscustomobject]@{schemaVersion=1;status='PENDING';
            accepted=$assembly.rows.Count;missing=$assembly.missing;sourceIdentity=$request.context.sourceIdentity;fullMatrix='PENDING'}))
        if ($Signoff) {throw 'SIGNOFF_INCOMPLETE'}
        return [pscustomobject]@{status='PENDING';diagnostic=(Join-Path $output 'diagnostic.json');results=$null}
    }
    $staging=Join-Path $output 'verification-candidate';[void][IO.Directory]::CreateDirectory($staging)
    $candidate=Join-Path $staging 'results.json'
    $candidatePin=Write-SignoffNew $candidate ([pscustomobject][ordered]@{schemaVersion=1;status='PASS';cells=$assembly.rows})
    $cli=Invoke-SignoffJdk $request.verifier $candidate $output $config $seen
    if ($cli.stdout.TrimEnd([char[]]"`r`n") -cne ('UPDATE_EVIDENCE_VERIFIED cells='+$assembly.rows.Count)) {throw 'SIGNOFF_CLI_COUNT'}
    foreach ($path in @($seen.Keys)) {[void](Read-SignoffPin ([pscustomobject]@{path=$path;sha256=$seen[$path]}) $null)}
    [void](Read-SignoffPin $candidatePin $null)
    $destination=Join-Path $output 'results.json';[IO.File]::Move($candidate,$destination)
    $resultPin=New-SignoffPin $destination
    [void](Write-SignoffNew (Join-Path $output 'signoff.json') ([pscustomobject]@{schemaVersion=1;status='VERIFIED';results=$resultPin;
        request=[pscustomobject]@{path=$RequestFile;sha256=$RequestSha256};sourceIdentity=$request.context.sourceIdentity;cli=$cli;
        inputs=@($seen.Keys | Sort-Object | ForEach-Object {[pscustomobject]@{path=$_;sha256=$seen[$_]}})}))
    return [pscustomobject]@{status='VERIFIED';results=$resultPin;receipt=(Join-Path $output 'signoff.json')}
}
if ($MyInvocation.InvocationName -ne '.') {
    if (-not $RequestFile -or -not $RequestSha256) {throw 'SIGNOFF_REQUEST_REQUIRED'}
    Invoke-NativeUpdateEvidenceSignoff $RequestFile $RequestSha256 -Signoff:$Signoff
}
