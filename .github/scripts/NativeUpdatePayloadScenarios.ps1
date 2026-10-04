<#
.SYNOPSIS
Изолированный adapter payload сценариев для последующего запуска MAIN.
.DESCRIPTION
Dot-source только объявляет функции. Invoke-NativePayloadScenario принимает Context:
Row, Source, PortableDir (B1,B2), TargetPortableDir, CommandFile, CommandFileSha256,
LifecycleFile, LifecycleFileSha256, Java, Evidence (новый прямой Temp/UUID), Timeout.
Работа выполняется в отдельном runspace: импорты/global/script variables caller
не заменяются. Native/CLI выполняются только при явном вызове MAIN, не при импорте.
Unicode target - отдельная копия с неизменённым core JAR и новой fixture tree identity.
Ни derived manifest, ни локальная metadata не являются published release provenance.
Результат adapter всегда nativeStatus=PENDING; полный gate не закрывается.
Девятипараметровый Invoke-NativePayloadCell читает только frozen nativeTarget caller,
создаёт собственные config copies и оставляет pointer в owned Evidence MAIN.
Scoped Row сохраняет artifact/manifest/config/fixture pins и payloadScope;
Context-only/PENDING без receipt-chain не принимается как исполненная клетка.
cashmemory/unmanaged-old-or-new проверяют normal-flow OLD-before/NEW-after и bytes
baseline, не доказательство безопасности всех phase-fault/rollback вариантов.
#>

# Импортирует определения в частный runspace, никогда исполняемые тела runners.
function Import-NativePayloadFunctions([string]$Root,[string]$File,[string[]]$Names=@()) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $Root $File),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'PAYLOAD_IMPORT_PARSE'}
    $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
    foreach ($name in $Names) {if (@($definitions | Where-Object Name -CEQ $name).Count -ne 1) {throw 'PAYLOAD_IMPORT_FUNCTION'}}
    foreach ($definition in $definitions) {
        if ($Names.Count -and $definition.Name -cnotin $Names) {continue}
        $text=$definition.Extent.Text -replace '^function ', 'function global:'
        # Заменяем только variable AST, не строку '$PSScriptRoot' внутри существующих importers.
        $parsed=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
        $variables=@($parsed.FindAll({param($n) $n -is [Management.Automation.Language.VariableExpressionAst] -and
            $n.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object { $_.Extent.StartOffset } -Descending)
        foreach ($variable in $variables) {
            $text=$text.Remove($variable.Extent.StartOffset,$variable.Extent.EndOffset-$variable.Extent.StartOffset).
                Insert($variable.Extent.StartOffset,("'"+$Root.Replace("'","''")+"'"))
        }
        . ([scriptblock]::Create($text))
    }
}

# Публичная граница: caller функции/моки/globals не попадают в частный worker.
function Initialize-NativePayloadDependencies([string]$Root) {
    Import-NativePayloadFunctions $Root 'New-NativeUpdateArtifacts.ps1'
    Import-ArtifactContracts $Root
    Import-NativePayloadFunctions $Root 'Test-NativeUpdateLifecycle.ps1'
    Import-NativePayloadFunctions $Root 'New-UpdateBootstrapCommands.ps1' @('New-BootstrapBuilderArchive')
    Import-NativePayloadFunctions $Root 'S7-Release.ps1' @('Assert-S7Path')
}

# Публичная граница: caller функции/моки/globals не попадают в частный worker.
function Invoke-NativePayloadScenario($Context) {
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'PAYLOAD_WINDOWS_PS7'}
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript({param($root,$context)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            $result=Invoke-NativePayloadWorker $context $root
            Assert-NativePayloadResult $result $context
            return $result
        }).AddArgument($PSScriptRoot).AddArgument($Context)
        $result=$worker.Invoke()
        if ($worker.HadErrors) {throw $worker.Streams.Error[0]}
        return $result
    } finally {$worker.Dispose()}
}

# Сценарий/контекст проверяются до создания output, CLI либо native процессов.
function New-NativePayloadCellContext($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    # Тот же frozen caller scope, что и у существующих advanced helpers; target не угадывается по manifest пути.
    $binding=Get-Variable nativeTarget -Scope Script -ErrorAction SilentlyContinue
    if ($null -eq $binding) {$binding=Get-Variable nativeTarget -Scope Global -ErrorAction SilentlyContinue}
    if ($null -eq $binding -or $binding.Value -isnot [string]) {throw 'PAYLOAD_CALLER_TARGET_MISSING'}
    $targetRoot=Assert-NativeAbsolute $binding.Value
    $bases=@($Cold.baseManifests | ForEach-Object portableDir)
    Assert-ColdCommand $Cold $Java $bases $targetRoot;Assert-NativeLifecycleConfig $Life
    if ($Row.base -cnotin @('B1','B2') -or $bases.Count -ne 2) {throw 'PAYLOAD_BASE_CONTEXT'}
    $index=if ($Row.base -ceq 'B1') {0} else {1}
    if ($Source -cne $bases[$index]) {throw 'PAYLOAD_BASE_CONTEXT'}
    $baseEntry=@($Cold.baseManifests | Where-Object portableDir -CEQ $Source)
    if ($baseEntry.Count -ne 1 -or -not (Test-ColdInventoryEqual $Base (Read-ColdPinnedJson $baseEntry[0].manifest $baseEntry[0].sha256))) {throw 'PAYLOAD_CALLER_BASE_PIN'}
    $actualTarget=Read-LifecycleManifest $Life.artifactDir $Life.manifestSha256
    if ($Cold.targetManifestSha256 -cne $Life.manifestSha256 -or
        -not (Test-ColdInventoryEqual $Target $actualTarget)) {throw 'PAYLOAD_CALLER_TARGET_PIN'}
    [void](Assert-ColdTree $Source $Base);[void](Assert-ColdTree $targetRoot $Target)
    $parent=Assert-NativeAbsolute $Evidence
    if (-not (Test-Path -LiteralPath $parent -PathType Container) -or
        -not [IO.Path]::GetDirectoryName($parent).Equals((Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())),[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($parent) -cnotmatch '^(?:cp-native-lifecycle-evidence-)?[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'PAYLOAD_CALLER_EVIDENCE_SCOPE'}
    foreach ($path in @($bases)+@($targetRoot,$Life.artifactDir)) {
        if ((Test-PortablePathContains $parent $path) -or (Test-PortablePathContains $path $parent)) {throw 'PAYLOAD_CALLER_EVIDENCE_OVERLAP'}
    }
    $configs=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    $work=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    $context=[pscustomobject]@{Row=$Row;Source=$Source;PortableDir=$bases;TargetPortableDir=$targetRoot;
        CommandFile=(Join-Path $configs 'command.json');CommandFileSha256=('0'*64);
        LifecycleFile=(Join-Path $configs 'lifecycle.json');LifecycleFileSha256=('0'*64);
        Java=$Java;Evidence=$work;Timeout=$Timeout}
    Assert-NativePayloadContext $context
    $protected=@($bases)+@($targetRoot,$Life.artifactDir,$parent,$Java,$Cold.helperScript,$Cold.targetManifest)+
        @($Cold.toolFiles | ForEach-Object path)+@($Cold.baseManifests | ForEach-Object manifest)+@($Life.harnessClasspath.Split(';'))
    [void](Assert-ArtifactOutput $configs $protected)
    [void](Assert-ArtifactOutput $work (@($protected)+@($configs)))
    $null=New-Item -ItemType Directory -Path $configs -ErrorAction Stop
    # Только собственные копии; существующие command/lifecycle JSON и globals не заменяются.
    Write-ArtifactJson $context.CommandFile $Cold;Write-ArtifactJson $context.LifecycleFile $Life
    $context.CommandFileSha256=(Get-FileHash -LiteralPath $context.CommandFile).Hash.ToLowerInvariant()
    $context.LifecycleFileSha256=(Get-FileHash -LiteralPath $context.LifecycleFile).Hash.ToLowerInvariant()
    Write-ArtifactJson (Join-Path $configs 'adapter-context.json') $context
    return $context
}

# Девятипараметровый экспорт для dispatch MAIN: реальные pinned CLI/artifacts/HTTP flow, без callbacks.
function Invoke-NativePayloadCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'PAYLOAD_WINDOWS_PS7'}
    $binding=Get-Variable nativeTarget -Scope Script -ErrorAction SilentlyContinue
    if ($null -eq $binding) {$binding=Get-Variable nativeTarget -Scope Global -ErrorAction SilentlyContinue}
    if ($null -eq $binding -or $binding.Value -isnot [string]) {throw 'PAYLOAD_CALLER_TARGET_MISSING'}
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript({param($root,$cell,$targetRoot)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            $script:nativeTarget=$targetRoot;$global:nativeTarget=$targetRoot
            $context=New-NativePayloadCellContext @cell
            $result=Invoke-NativePayloadWorker $context $root
            Assert-NativePayloadResult $result $context
            $pointer=Join-Path $cell[7] ('payload-'+[IO.Path]::GetFileName($context.Evidence)+'.json')
            Write-ArtifactJson $pointer ([ordered]@{schemaVersion=1;scope=$result.cell.scenario;nativeStatus='PENDING';
                cellStatus=$result.cell.status;cellScope=$result.cell.payloadScope;fullMatrix='PENDING';
                evidence=$context.Evidence;context=(Join-Path ([IO.Path]::GetDirectoryName($context.CommandFile)) 'adapter-context.json');
                observation=(Join-Path $context.Evidence 'payload-observation.json');releaseProvenance='NOT_PROVEN'})
            $result.cell | Add-Member payloadEvidence $pointer -Force
            return $result.cell
        }).AddArgument($PSScriptRoot).AddArgument(@($Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout)).AddArgument($binding.Value)
        $rows=$worker.Invoke()
        if ($worker.HadErrors) {throw $worker.Streams.Error[0]}
        if ($rows.Count -ne 1) {throw 'PAYLOAD_ADAPTER_RESULT_COUNT'}
        Copy-NativePayloadCellEvidence $Row $rows[0]
    } finally {$worker.Dispose()}
}

# Scoped row не принимается только по Context/nativeStatus=PENDING: требуются связанные receipts и pins.
function Assert-NativePayloadResult($Result,$Context) {
    foreach ($name in 'status','nativeStatus','scope','fullMatrix','releaseProvenance','cell') {
        if ($null -eq $Result.PSObject.Properties[$name]) {throw 'PAYLOAD_RESULT_SCOPE'}
    }
    if ($Result.status -cne 'SCOPED_EVIDENCE' -or $Result.nativeStatus -cne 'PENDING' -or
        $Result.scope -cne $Context.Row.scenario -or $Result.fullMatrix -cne 'PENDING' -or
        $Result.releaseProvenance -cne 'NOT_PROVEN' -or $Result.cell.scenario -cne $Context.Row.scenario) {throw 'PAYLOAD_RESULT_SCOPE'}
    $row=$Result.cell
    foreach ($name in 'status','executed','reason','payloadScope','payloadReleaseProvenance','payloadFullMatrix',
        'payloadObservation','payloadInputPins','payloadArtifactDir','payloadManifestSha256','payloadTreeSha256',
        'payloadCommandFile','payloadCommandSha256','payloadLifecycleFile','payloadLifecycleSha256') {
        if ($null -eq $row.PSObject.Properties[$name]) {throw 'PAYLOAD_RESULT_CELL_NOT_EXECUTED'}
    }
    foreach ($name in 'scenario','base','client','path') {
        if ($row.$name -cne $Context.Row.$name) {throw 'PAYLOAD_RESULT_CELL_IDENTITY'}
    }
    if ($row.status -cne 'PASS' -or $row.executed -isnot [bool] -or -not $row.executed -or
        $row.reason -cne 'NATIVE_LIFECYCLE_EXECUTED' -or $row.payloadScope -cne 'SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF' -or
        $row.payloadReleaseProvenance -cne 'NOT_PROVEN' -or $row.payloadFullMatrix -cne 'PENDING') {throw 'PAYLOAD_RESULT_CELL_NOT_EXECUTED'}
    foreach ($name in 'payloadObservation','payloadInputPins') {
        $expected=Join-Path $Context.Evidence $(if ($name -ceq 'payloadObservation') {'payload-observation.json'} else {'input-pin-check.json'})
        if ($row.$name -cne $expected) {throw 'PAYLOAD_RESULT_RECEIPT_SCOPE'}
        [void](Resolve-PortableSafetyPath $expected)
    }
    $pins=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.payloadInputPins -Raw -Encoding utf8)
    if ($pins.status -cne 'VERIFIED' -or $pins.nativeStatus -cne 'PENDING' -or
        $pins.commandSha256 -cne $Context.CommandFileSha256 -or $pins.lifecycleSha256 -cne $Context.LifecycleFileSha256) {throw 'PAYLOAD_RESULT_ORIGINAL_PINS'}
    $saved=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.payloadObservation -Raw -Encoding utf8)
    if (-not (Test-ColdInventoryEqual $saved $Result)) {throw 'PAYLOAD_RESULT_OBSERVATION_CHANGED'}
    $manifest=Read-LifecycleManifest $row.payloadArtifactDir $row.payloadManifestSha256
    if ($manifest.treeSha256 -cne $row.payloadTreeSha256) {throw 'PAYLOAD_RESULT_TREE_PIN'}
    [void](Read-ColdPinnedJson $row.payloadCommandFile $row.payloadCommandSha256)
    $life=Read-ColdPinnedJson $row.payloadLifecycleFile $row.payloadLifecycleSha256
    if ($life.artifactDir -cne $row.payloadArtifactDir -or $life.manifestSha256 -cne $row.payloadManifestSha256) {throw 'PAYLOAD_RESULT_LIFECYCLE_PIN'}
    if ($Result.scope -ceq 'unicode-payload') {
        if ($row.payloadFixtureIdentity -cne (Join-Path $Context.Evidence 'fixture-identity.json')) {throw 'PAYLOAD_RESULT_FIXTURE_SCOPE'}
        $identity=Read-ColdPinnedJson $row.payloadFixtureIdentity $row.payloadFixtureIdentitySha256
        if ($identity.scope -cne 'DERIVED_UNICODE_PAYLOAD_NOT_RELEASE_PROVENANCE' -or
            $identity.derivedManifestSha256 -cne $row.payloadManifestSha256 -or $identity.derivedTreeSha256 -cne $row.payloadTreeSha256 -or
            $identity.derivedTarget -cne $row.payloadTargetRoot -or $identity.originalTarget -cne $Context.TargetPortableDir -or
            $identity.coreMetadata -cne 'UNCHANGED') {throw 'PAYLOAD_RESULT_FIXTURE_IDENTITY'}
        $original=Read-ColdPinnedJson $Context.CommandFile $Context.CommandFileSha256
        $fixture=Get-NativePayloadFixture
        $entry=@($manifest.files | Where-Object path -CEQ $fixture.path)
        if ($identity.originalManifestSha256 -cne $original.targetManifestSha256 -or $identity.relativePath -cne $fixture.path -or
            $identity.utf8Sha256 -cne $fixture.sha256 -or $identity.utf8Size -ne $fixture.bytes.Length -or
            $entry.Count -ne 1 -or $entry[0].sha256 -cne $fixture.sha256 -or $entry[0].sizeBytes -ne $fixture.bytes.Length) {throw 'PAYLOAD_RESULT_UNICODE_MANIFEST'}
    }
}

# Атомарная по валидации передача всей scoped metadata и inventory links в исходную Row MAIN.
# Проверка MAIN cell против pre-native authority не читает helper return и не запускает процессы.
function Assert-NativePayloadAuthorityCellBinding($Ticket,$Row,[string]$Source,$Base,$Target,$Life,$Cold,
    [string]$Java,[int]$Timeout,[string]$TargetRoot) {
    $context=$Ticket.input.context
    foreach ($name in 'scenario','base','client','path','phase') {
        if ($Row.$name -cne $context.Row.$name) {throw 'PAYLOAD_AUTHORITY_CELL_IDENTITY'}
    }
    if ($Row.status -cne 'PENDING' -or $context.Row.status -cne 'PENDING' -or $context.Source -cne $Source -or
        $context.Java -cne $Java -or $context.Timeout -ne $Timeout -or $context.TargetPortableDir -cne $TargetRoot) {throw 'PAYLOAD_AUTHORITY_CALL_BINDING'}
    $baseIndex=if ($Row.base -ceq 'B1') {0} else {1}
    foreach ($pair in @(@($Base,$Ticket.input.bases[$baseIndex]),@($Target,$Ticket.input.target))) {
        foreach ($field in 'releaseNumber','commitSha','treeSha256') {
            if ($pair[0].$field -cne $pair[1].$field) {throw 'PAYLOAD_AUTHORITY_IMAGE_IDENTITY'}
        }
        if (-not (Test-ColdInventoryEqual $pair[0].files $pair[1].files)) {throw 'PAYLOAD_AUTHORITY_IMAGE_FILES'}
    }
    if (-not (Test-ColdInventoryEqual $Cold $Ticket.input.cold) -or
        -not (Test-ColdInventoryEqual $Life $Ticket.input.life)) {throw 'PAYLOAD_AUTHORITY_CONFIG_BINDING'}
}

# Отдельный authorized adapter не расширяет public Invoke-NativePayloadCell exact9.
function Invoke-NativePayloadAuthorizedCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,
    [string]$Evidence,[int]$Timeout,[string]$AuthorityFile,[string]$AuthoritySha256) {
    . (Join-Path $PSScriptRoot 'NativeUpdatePayloadAuthority.ps1')
    $ticket=Read-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256
    $binding=Get-Variable nativeTarget -Scope Script -ValueOnly -ErrorAction SilentlyContinue
    if ($null -eq $binding) {$binding=Get-Variable nativeTarget -Scope Global -ValueOnly -ErrorAction SilentlyContinue}
    Assert-NativePayloadAuthorityCellBinding $ticket $Row $Source $Base $Target $Life $Cold $Java $Timeout $binding
    if ($ticket.intent.evidenceKind -cne 'NATIVE') {throw 'AUTHORITY_UNIT_MOCK_NO_EXECUTION'}
    # Настоящий existing authority worker удерживает Expected SHA до native invocation.
    $executed=Invoke-NativePayloadAuthorizedScenario $AuthorityFile $AuthoritySha256
    $sealed=Read-ColdPinnedJson $executed.authority.file $executed.authority.sha256
    if ($sealed.authorityFile -cne $AuthorityFile -or $sealed.authoritySha256 -cne $AuthoritySha256 -or
        $sealed.nonce -cne $ticket.owned.Nonce -or $executed.observationFile -cne $sealed.observationFile) {throw 'PAYLOAD_AUTHORITY_RETURN_BINDING'}
    $observationSha=(Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $sealed.observationFile)).Hash.ToLowerInvariant()
    . (Join-Path $PSScriptRoot 'NativeUpdatePayloadAcceptance.ps1')
    $decision=Test-NativePayloadAcceptance $sealed.observationFile $observationSha $sealed.Expected -EvidenceKind NATIVE
    $decision | Add-Member helperRow $executed.payload.cell
    $decision | Add-Member expectedFile $executed.authority.file
    $decision | Add-Member expectedSha256 $executed.authority.sha256
    $decision | Add-Member observationFile $sealed.observationFile
    $decision | Add-Member observationSha256 $observationSha
    return $decision
}

function Copy-NativePayloadCellEvidence($Destination,$Cell) {
    foreach ($name in 'scenario','base','client','path') {
        if ($Destination.$name -cne $Cell.$name) {throw 'PAYLOAD_ROW_PROPAGATION_IDENTITY'}
    }
    foreach ($name in 'payloadScope','payloadFullMatrix','payloadReleaseProvenance') {
        if ($null -eq $Cell.PSObject.Properties[$name]) {throw 'PAYLOAD_ROW_PROPAGATION_SCOPE'}
    }
    if ($Cell.payloadScope -cne 'SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF' -or
        $Cell.payloadFullMatrix -cne 'PENDING' -or $Cell.payloadReleaseProvenance -cne 'NOT_PROVEN') {throw 'PAYLOAD_ROW_PROPAGATION_SCOPE'}
    foreach ($property in $Cell.PSObject.Properties) {$Destination | Add-Member $property.Name $property.Value -Force}
}

# Устанавливает target/project/profile только в частном runspace для разных AST script scopes.
function Set-NativePayloadPrivateRoots([string]$TargetRoot,[string]$ScriptsRoot) {
    $script:nativeProject=[IO.Path]::GetFullPath((Join-Path $ScriptsRoot '../..'))
    $script:nativeProfile=[Environment]::GetFolderPath('UserProfile');$script:nativeTarget=$TargetRoot
    $global:nativeProject=$script:nativeProject;$global:nativeProfile=$script:nativeProfile;$global:nativeTarget=$TargetRoot
}

# Сценарий/контекст проверяются до создания output, CLI либо native процессов.
function Assert-NativePayloadContext($Context) {
    Assert-ColdKeys $Context @('Row','Source','PortableDir','TargetPortableDir','CommandFile','CommandFileSha256',
        'LifecycleFile','LifecycleFileSha256','Java','Evidence','Timeout')
    if ($Context.Row.scenario -cnotin @('unicode-payload','cashmemory','unmanaged-old-or-new') -or
        $Context.Row.base -cnotin @('B1','B2') -or $Context.Row.client -cnotin @('fx','swing','web') -or
        $Context.Row.path -cnotin @('ascii','cyrillic','unicode') -or
        $Context.Row.status -cne 'PENDING' -or -not (Test-ColdInteger $Context.Timeout 30) -or $Context.Timeout -gt 300 -or
        @($Context.PortableDir).Count -ne 2 -or $Context.PortableDir[0] -ieq $Context.PortableDir[1]) {throw 'PAYLOAD_CONTEXT'}
    $index=if ($Context.Row.base -ceq 'B1') {0} else {1}
    if ($Context.Source -cne $Context.PortableDir[$index]) {throw 'PAYLOAD_BASE_CONTEXT'}
    foreach ($path in @($Context.PortableDir)+@($Context.Source,$Context.TargetPortableDir,$Context.CommandFile,
        $Context.LifecycleFile,$Context.Java,$Context.Evidence)) {[void](Assert-NativeAbsolute $path)}
    $roots=@($Context.PortableDir)+@($Context.TargetPortableDir)
    foreach ($a in $roots) {foreach ($b in $roots) {
        if ($a -cne $b -and ((Test-PortablePathContains $a $b) -or (Test-PortablePathContains $b $a))) {throw 'PAYLOAD_INPUT_OVERLAP'}
    }}
    if ($Context.TargetPortableDir -iin $Context.PortableDir) {throw 'PAYLOAD_INPUT_OVERLAP'}
}

# Снимок всех исходных файлов/директорий, включая unmanaged, обнаруживает изменения после отказа.
function Get-NativePayloadSnapshot([string[]]$Paths) {
    $rows=@(foreach ($path in $Paths) {
        Assert-PortableTreeHasNoLinks $path
        $item=Get-Item -LiteralPath $path -Force
        $items=@($item);if ($item.PSIsContainer) {$items+=@(Get-ChildItem -LiteralPath $path -Force -Recurse)}
        foreach ($entry in $items) {
            [pscustomobject]@{path=$entry.FullName;directory=[bool]$entry.PSIsContainer;attributes=[int]$entry.Attributes;
                lastWriteTicks=$entry.LastWriteTimeUtc.Ticks;creationTicks=$entry.CreationTimeUtc.Ticks;
                size=$(if ($entry.PSIsContainer) {0L} else {[long]$entry.Length});
                sha256=$(if ($entry.PSIsContainer) {'directory'} else {(Get-FileHash -LiteralPath $entry.FullName).Hash.ToLowerInvariant()})}
        }
    })
    return (ConvertTo-Json -InputObject @($rows | Sort-Object path) -Depth 8 -Compress)
}

# Только фиксированный managed путь; content непустой UTF-8, не вариант пути portable root.
function Get-NativePayloadFixture {
    $text="Проверка содержимого payload 日本 Δ`n"
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes($text)
    return [pscustomobject]@{path='app/данные_日本/проверка_Δ.txt';bytes=$bytes;
        sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
}

# Копия обязана совпасть со всеми original entries; добавляется ровно один managed файл.
function Assert-NativePayloadDerived($Original,$Derived,$Fixture,$OriginalVersion,$DerivedVersion) {
    if ($Original.releaseNumber -ne $Derived.releaseNumber -or $Original.commitSha -cne $Derived.commitSha -or
        $Original.version -cne $Derived.version -or $Original.publishedAtUtc -cne $Derived.publishedAtUtc -or
        $Original.treeSha256 -ceq $Derived.treeSha256 -or @($Derived.files).Count -ne @($Original.files).Count+1 -or
        -not (Test-ColdInventoryEqual $OriginalVersion $DerivedVersion)) {throw 'PAYLOAD_DERIVED_IDENTITY'}
    foreach ($file in $Original.files) {
        $matches=@($Derived.files | Where-Object path -CEQ $file.path)
        if ($matches.Count -ne 1 -or -not (Test-ColdInventoryEqual $file $matches[0])) {throw 'PAYLOAD_ORIGINAL_ENTRY_CHANGED'}
    }
    $payload=@($Derived.files | Where-Object path -CEQ $Fixture.path)
    if ($payload.Count -ne 1 -or $payload[0].sizeBytes -ne $Fixture.bytes.Length -or
        $payload[0].sha256 -cne $Fixture.sha256 -or $payload[0].readOnly -ne $false) {throw 'PAYLOAD_UNICODE_ENTRY'}
}

# Создаёт производный target/full-only manifest; штатный CLI сам валидирует ZIP/tree.
function New-NativePayloadDerivedTarget($Context,$Cold,$Original,[string]$Work) {
    $clone=Join-Path $Work 'target';Copy-Item -LiteralPath $Context.TargetPortableDir -Destination $clone -Recurse
    [void](Assert-ColdTree $clone $Original)
    $fixture=Get-NativePayloadFixture;$file=Join-Path $clone $fixture.path
    if (Test-Path -LiteralPath $file) {throw 'PAYLOAD_FIXTURE_EXISTS'}
    $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($file)) -ErrorAction Stop
    $stream=[IO.FileStream]::new($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($fixture.bytes,0,$fixture.bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    $full=Join-Path $Work 'full';$null=New-Item -ItemType Directory -Path $full
    $archive=Join-Path $full 'CashPrediction-portable.zip';$files=@(Get-ColdManagedInventory $clone)
    New-BootstrapBuilderArchive $clone $files $archive
    $manifest=Join-Path $full 'update.json'
    # Исходный command ещё привязан к original target: его pins проверяются перед CLI.
    Assert-ColdCommand $Cold $Context.Java $Context.PortableDir $Context.TargetPortableDir
    $cli=Invoke-ColdTool $Cold $Context.Java @('manifest','--root',$clone,'--archive',$archive,
        '--release',[string]$Original.releaseNumber,'--commit',$Original.commitSha,'--version',$Original.version,
        '--published-at',$Original.publishedAtUtc,'--out',$manifest) $Work $Work
    $pin=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
    $derived=Read-ArtifactManifest $manifest $pin
    Assert-NativePayloadDerived $Original $derived $fixture (Get-ColdVersion $Context.TargetPortableDir) (Get-ColdVersion $clone)
    [void](Assert-ColdTree $clone $derived)
    $command=New-ArtifactCommand $Cold $manifest $pin;$commandPath=Join-Path $Work 'derived-command.json'
    Write-ArtifactJson $commandPath $command
    return [pscustomobject]@{root=$clone;fixture=$fixture;manifest=$derived;cli=$cli;command=$commandPath;
        commandSha256=(Get-FileHash -LiteralPath $commandPath).Hash.ToLowerInvariant()}
}

# Проверяет реальные linked inventories; смешанный tree не считается old-or-new.
function Assert-NativePayloadObservation($Row,$Base,$Target,$Fixture=$null) {
    if ($Row.status -cne 'PASS' -or $Row.reason -cne 'NATIVE_LIFECYCLE_EXECUTED' -or
        $Row.executed -isnot [bool] -or -not $Row.executed -or $Row.exitCode -ne 0 -or
        $Row.failures -ne 0 -or $Row.skipped -ne 0) {throw 'PAYLOAD_NATIVE_NOT_EXECUTED'}
    $before=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Row.currentBefore -Raw -Encoding utf8)
    $after=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Row.currentAfter -Raw -Encoding utf8)
    if (-not (Test-ColdInventoryEqual $before $Base.files) -or -not (Test-ColdInventoryEqual $after $Target.files)) {throw 'PAYLOAD_NOT_COMPLETE_NEW_TREE'}
    foreach ($name in 'targetBefore','targetAfter') {
        $files=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Row.$name -Raw -Encoding utf8)
        if (-not (Test-ColdInventoryEqual $files $Target.files)) {throw 'PAYLOAD_TARGET_MUTATED'}
    }
    $userBefore=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Row.userBefore -Raw -Encoding utf8)
    $userAfter=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $Row.userAfter -Raw -Encoding utf8)
    if (-not (Test-ColdInventoryEqual $userBefore $userAfter)) {throw 'PAYLOAD_USER_CHANGED'}
    foreach ($path in 'CashMemory/protected-user.txt','CashMemory/NativeLifecycle.md','CashMemory/settings.md','protected-root.txt') {
        $entry=$userBefore.PSObject.Properties[$path]
        if ($null -eq $entry -or $entry.Value.directory -or $entry.Value.sizeBytes -le 0) {throw 'PAYLOAD_USER_BASELINE_MISSING'}
    }
    if ($null -ne $Fixture) {
        $root=[IO.Path]::GetDirectoryName($Row.exe)
        $actual=[IO.File]::ReadAllBytes((Join-Path $root $Fixture.path))
        if ($actual.Length -ne $Fixture.bytes.Length -or
            [Convert]::ToHexString($actual) -cne [Convert]::ToHexString($Fixture.bytes)) {throw 'PAYLOAD_UNICODE_BYTES'}
    }
}

# Частный worker удерживает original pins до/после всех действий, включая failure cleanup.
function Invoke-NativePayloadWorker($Context,[string]$ScriptsRoot,[string]$AuthorityFile='',[string]$AuthoritySha256='') {
    Assert-NativePayloadContext $Context
    $authorityTicket=$null
    if ($AuthorityFile) {
        . (Join-Path $ScriptsRoot 'NativeUpdatePayloadAuthority.ps1')
        $authorityTicket=Read-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256
        if ($authorityTicket.intent.evidenceKind -cne 'NATIVE' -or
            -not (Test-ColdInventoryEqual $Context $authorityTicket.input.context)) {throw 'AUTHORITY_WORKER_CONTEXT'}
        Assert-PayloadAuthorityOwned $authorityTicket.owned $authorityTicket.input $authorityTicket.intent.contextFile $authorityTicket.intent.ownedFile
    }
    $cold=Read-ColdPinnedJson $Context.CommandFile $Context.CommandFileSha256
    Assert-ColdCommand $cold $Context.Java $Context.PortableDir $Context.TargetPortableDir
    $life=Read-ColdPinnedJson $Context.LifecycleFile $Context.LifecycleFileSha256;Assert-NativeLifecycleConfig $life
    $target=Read-LifecycleManifest $life.artifactDir $life.manifestSha256
    $originalManifestPin=$life.manifestSha256
    if ($cold.targetManifestSha256 -cne $life.manifestSha256) {throw 'PAYLOAD_FROZEN_MANIFEST'}
    $bases=@(foreach ($root in $Context.PortableDir) {
        $entry=@($cold.baseManifests | Where-Object portableDir -CEQ $root)
        if ($entry.Count -ne 1) {throw 'PAYLOAD_BASE_PIN'}
        Read-ColdPinnedJson $entry[0].manifest $entry[0].sha256
    })
    for ($i=0;$i -lt 3;$i++) {
        $root=(@($Context.PortableDir)+@($Context.TargetPortableDir))[$i]
        $m=if ($i -lt 2) {$bases[$i]} else {$target}
        Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $root -Force)
        Assert-ColdNativeImage $root;Assert-NativeSeam $root;[void](Assert-ColdTree $root $m)
        $version=Get-ColdVersion $root
        if ($version.releaseNumber -ne $m.releaseNumber -or $version.commitSha -cne $m.commitSha) {throw 'PAYLOAD_VERSION_PIN'}
    }
    $protected=@($Context.PortableDir)+@($Context.TargetPortableDir,$Context.CommandFile,$Context.LifecycleFile,$Context.Java,
        $cold.helperScript,$cold.targetManifest,$life.artifactDir)+@($cold.toolFiles | ForEach-Object path)+
        @($cold.baseManifests | ForEach-Object manifest)+@($life.harnessClasspath.Split(';'))
    $work=Assert-ArtifactOutput $Context.Evidence $protected
    $snapshot=Get-NativePayloadSnapshot $protected;$registry=Get-PortableRealRegistrySnapshot
    $null=New-Item -ItemType Directory -Path $work -ErrorAction Stop
    Set-NativePayloadPrivateRoots $Context.TargetPortableDir $ScriptsRoot
    $fixture=$null;$derived=$null;$preparation=$null
    $usedCommand=$Context.CommandFile;$usedCommandSha=$Context.CommandFileSha256
    $usedLifecycle=$Context.LifecycleFile;$usedLifecycleSha=$Context.LifecycleFileSha256
    try {
        if ($Context.Row.scenario -ceq 'unicode-payload') {
            $derived=New-NativePayloadDerivedTarget $Context $cold $target $work
            $output=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
            if ($null -ne $authorityTicket) {$output=$authorityTicket.owned.ArtifactRoot}
            # Штатный builder получает неизменённый parameter contract и свой Temp UUID.
            $preparation=& (Join-Path $ScriptsRoot 'New-NativeUpdateArtifacts.ps1') -CommandFile $derived.command `
                -CommandFileSha256 $derived.commandSha256 -Runtime $Context.Java -PortableDir $Context.PortableDir `
                -TargetPortableDir $derived.root -OutputDir $output
            if ($preparation.status -cne 'PREPARED' -or $preparation.nativeStatus -cne 'PENDING') {throw 'PAYLOAD_ARTIFACT_PREPARATION'}
            $target=Read-LifecycleManifest $preparation.artifactDir $preparation.manifestSha256
            $cold=Read-ColdPinnedJson $preparation.commandFile $preparation.commandFileSha256
            $life=New-LifecyclePinnedConfig $preparation.artifactDir @($life.harnessClasspath.Split(';')) $preparation.manifestSha256
            $usedCommand=$preparation.commandFile;$usedCommandSha=$preparation.commandFileSha256
            $usedLifecycle=Join-Path $work 'derived-lifecycle.json'
            Write-ArtifactJson $usedLifecycle $life;$usedLifecycleSha=(Get-FileHash -LiteralPath $usedLifecycle).Hash.ToLowerInvariant()
            Set-NativePayloadPrivateRoots $derived.root $ScriptsRoot;$fixture=$derived.fixture
            Write-ArtifactJson (Join-Path $work 'fixture-identity.json') ([ordered]@{
                scope='DERIVED_UNICODE_PAYLOAD_NOT_RELEASE_PROVENANCE';originalTarget=$Context.TargetPortableDir;
                originalManifestSha256=$originalManifestPin;derivedTarget=$derived.root;
                derivedManifestSha256=$preparation.manifestSha256;derivedTreeSha256=$target.treeSha256;
                coreMetadata='UNCHANGED';relativePath=$fixture.path;utf8Size=$fixture.bytes.Length;utf8Sha256=$fixture.sha256})
        }
        Assert-ColdCommand $cold $Context.Java $Context.PortableDir $script:nativeTarget
        $base=$bases[$(if ($Context.Row.base -ceq 'B1') {0} else {1})]
        $row=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Context.Row -Depth 32 -Compress)
        $scenario=$row.scenario;$row.scenario='delta'
        # Тот же normal flow: Ready/tree, nonpolling, ordinary exit, installer, userdata, bounded cleanup.
        if ($null -ne $authorityTicket) {
            # Authority перечитывает подготовленные файлы до native, не доверяя preparation return.
            $seal=Complete-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256
            $sealed=Read-ColdPinnedJson $seal.file $seal.sha256
            if ($sealed.Expected.TargetRoot -cne $script:nativeTarget -or
                $sealed.Expected.ManifestSha256 -cne $life.manifestSha256 -or
                $sealed.Expected.CommandSha256 -cne $usedCommandSha -or
                $sealed.Expected.LifecycleSha256 -cne $usedLifecycleSha) {throw 'AUTHORITY_EXECUTION_BINDING'}
            Invoke-NativeCellOwnedContext $row $Context.Source $base $target $life $cold $Context.Java $work $Context.Timeout $authorityTicket.owned.RunRoot $authorityTicket.owned.Nonce
        } else {
            Invoke-NativeCell $row $Context.Source $base $target $life $cold $Context.Java $work $Context.Timeout
        }
        Assert-NativePayloadObservation $row $base $target $fixture
        $row.scenario=$scenario
        $fields=@{payloadScope='SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF';payloadFullMatrix='PENDING';payloadReleaseProvenance='NOT_PROVEN';
            payloadArtifactDir=$life.artifactDir;payloadManifestSha256=$life.manifestSha256;payloadTreeSha256=$target.treeSha256;
            payloadTargetRoot=$script:nativeTarget;payloadCommandFile=$usedCommand;payloadCommandSha256=$usedCommandSha;
            payloadLifecycleFile=$usedLifecycle;payloadLifecycleSha256=$usedLifecycleSha;
            payloadObservation=(Join-Path $work 'payload-observation.json');payloadInputPins=(Join-Path $work 'input-pin-check.json')}
        if ($null -ne $fixture) {
            $fields.payloadFixtureIdentity=Join-Path $work 'fixture-identity.json'
            $fields.payloadFixtureIdentitySha256=(Get-FileHash -LiteralPath $fields.payloadFixtureIdentity).Hash.ToLowerInvariant()
        }
        foreach ($name in $fields.Keys) {$row | Add-Member $name $fields[$name] -Force}
        $result=[pscustomobject]@{schemaVersion=1;status='SCOPED_EVIDENCE';nativeStatus='PENDING';
            scope=$scenario;normalFlow='delta';cell=$row;releaseProvenance='NOT_PROVEN';
            derivedArtifacts=$preparation;fullMatrix='PENDING';safetyScope='NORMAL_FLOW_OLD_BEFORE_NEW_AFTER_NOT_PHASE_FAULTS'}
        Write-ArtifactJson (Join-Path $work 'payload-observation.json') $result
        return $result
    } catch {
        Write-ArtifactJson (Join-Path $work 'payload-failure.json') ([ordered]@{
            schemaVersion=1;status='FAILED';nativeStatus='PENDING';scope=$Context.Row.scenario;
            reason=$_.Exception.Message;fullMatrix='PENDING';releaseProvenance='NOT_PROVEN'})
        throw
    } finally {
        try {
            if ((Get-NativePayloadSnapshot $protected) -cne $snapshot) {throw 'PAYLOAD_ORIGINAL_INPUT_CHANGED'}
            $originalCold=Read-ColdPinnedJson $Context.CommandFile $Context.CommandFileSha256
            Assert-ColdCommand $originalCold $Context.Java $Context.PortableDir $Context.TargetPortableDir
            Assert-NativeLifecycleConfig (Read-ColdPinnedJson $Context.LifecycleFile $Context.LifecycleFileSha256)
            if ((Get-PortableRealRegistrySnapshot) -cne $registry) {throw 'PAYLOAD_REAL_REGISTRY_CHANGED'}
            Write-ArtifactJson (Join-Path $work 'input-pin-check.json') ([ordered]@{
                scope='ORIGINAL_INPUTS_BEFORE_AFTER';status='VERIFIED';nativeStatus='PENDING';
                commandSha256=$Context.CommandFileSha256;lifecycleSha256=$Context.LifecycleFileSha256})
        } catch {
            Write-ArtifactJson (Join-Path $work 'payload-integrity-failure.json') ([ordered]@{
                status='FAILED';nativeStatus='PENDING';reason=$_.Exception.Message;fullMatrix='PENDING'})
            throw
        }
    }
}
