<#
.SYNOPSIS
Read-only acceptance одного payload receipt, не полный native/release signoff.
.DESCRIPTION
Test-NativePayloadAcceptance -ObservationFile -ObservationSha256 -Expected -EvidenceKind.
EvidenceKind обязателен: NATIVE задаётся MAIN только для реально исполненного run;
UNIT_MOCK никогда не даёт PASS. Наличие helper return/PENDING само по себе не доказательство.
Expected содержит Scenario/Base/Client/Path, SourceRoot/OriginalTargetRoot/TargetRoot/
InstalledRoot, RequireFallback (bool), и пары File/Sha256 для BaseManifest,
OriginalTargetManifest, Manifest, OriginalCommand, Command, OriginalLifecycle, Lifecycle.
Ожидания MAIN независимы от непроверенной Row. Все чтения bounded, без процессов,
изменений реестра, файлов, runner/dispatcher. FAIL имеет приоритет над PENDING.
PASS означает только эту native клетку: fullMatrix и releaseProvenance остаются PENDING.
Producers: MAIN frozen preparation/context -> Expected pins; New-NativeUpdateArtifacts
-> preparation.json/command.json/update.json; payload worker -> fixture-identity.json,
payload-observation.json, затем finally -> input-pin-check.json.
Invoke-NativeCell -> launch-0.json после Connect-NativeClient; Close-NativeNormally
-> exit-0.json; Wait-NativeInstalled читает production PowerShellHelper.Phase/AtomicJson
-> update-log.md/last-install.json; затем inventories/phaseLog/httpTrace и finishedAt.
finishedAt ПРЕДШЕСТВУЕТ finally cleanup; cell.json пишется ПОСЛЕ cleanup и только затем
payload worker пишет observation. Cleanup timestamp не существует и не требуется.
Текущий payload worker запускает только delta: RequireFallback=true остаётся PENDING
до настоящего fallback producer/dispatch; один full GET не расширяет scope helper.
#>

# Частный runspace импортирует только определения frozen контрактов, не запускает их тела.
function Test-NativePayloadAcceptance([string]$ObservationFile,[string]$ObservationSha256,$Expected,
    [Parameter(Mandatory)][ValidateSet('NATIVE','UNIT_MOCK')][string]$EvidenceKind) {
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript({param($root,$path,$pin,$expected,$kind)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            . (Join-Path $root 'NativeUpdatePayloadAcceptance.ps1')
            Invoke-NativePayloadAcceptanceRead $path $pin $expected $kind
        }).AddArgument($PSScriptRoot).AddArgument($ObservationFile).AddArgument($ObservationSha256).AddArgument($Expected).AddArgument($EvidenceKind)
        $result=$worker.Invoke()
        if ($worker.HadErrors) {return [pscustomobject]@{status='FAIL';reason='ACCEPTANCE_READER_ERROR';
            errors=@($worker.Streams.Error | ForEach-Object {$_.Exception.Message});fullMatrix='PENDING';releaseProvenance='PENDING'}}
        return $result
    } finally {$worker.Dispose()}
}

# Отсутствующее поле - missing evidence, присутствующее несовпадение - contradiction.
function Compare-PayloadAcceptanceField($Actual,[string]$Name,$Expected,$State,[string]$Code) {
    if ($null -eq $Actual -or $null -eq $Actual.PSObject.Properties[$Name] -or $null -eq $Actual.$Name) {$State.missing.Add($Code);return}
    if (($Expected -is [int] -or $Expected -is [long]) -and -not (Test-ColdInteger $Actual.$Name)) {$State.errors.Add($Code);return}
    if ($Actual.$Name -cne $Expected) {$State.errors.Add($Code)}
}

# Строгий UTF-8 JSON: duplicate keys/нецелые числа отвергаются; null receipt допустим.
function Assert-PayloadAcceptanceJson($Node) {
    if ($Node.ValueKind.ToString() -ceq 'Object') {
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Node.EnumerateObject()) {
            if (-not $seen.Add($property.Name)) {throw 'ACCEPTANCE_DUPLICATE_JSON'}
            Assert-PayloadAcceptanceJson $property.Value
        }
    } elseif ($Node.ValueKind.ToString() -ceq 'Array') {
        if ($Node.GetArrayLength() -gt 20000) {throw 'ACCEPTANCE_ARRAY_LIMIT'}
        foreach ($item in $Node.EnumerateArray()) {Assert-PayloadAcceptanceJson $item}
    } elseif ($Node.ValueKind.ToString() -ceq 'Number' -and $Node.GetRawText() -cnotmatch '^-?(?:0|[1-9][0-9]*)$') {throw 'ACCEPTANCE_INTEGER_JSON'}
}

# Читает максимум 8 MiB, проверяет pin и containment до разбора; ничего не записывает.
function Read-PayloadAcceptanceJson([string]$Path,[string]$Pin,$State,[string]$Code,[string]$Root='') {
    if ([string]::IsNullOrWhiteSpace($Path)) {$State.missing.Add($Code);return $null}
    try {
        [void](Assert-NativeAbsolute $Path)
        if ($Root -and -not (Test-PortablePathContains $Root $Path)) {throw 'ACCEPTANCE_RECEIPT_ESCAPE'}
        if (-not (Test-Path -LiteralPath $Path)) {$State.missing.Add($Code);return $null}
        $file=Get-Item -LiteralPath $Path -Force
        if ($file.PSIsContainer -or $file.Length -gt 8388608) {throw 'ACCEPTANCE_FILE_LIMIT'}
        $bytes=[IO.File]::ReadAllBytes($Path)
        if ($bytes.Length -gt 8388608) {throw 'ACCEPTANCE_FILE_LIMIT'}
        if ($Pin -and ($Pin -cnotmatch '^[0-9a-f]{64}$' -or
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Pin)) {throw 'ACCEPTANCE_PIN_MISMATCH'}
        $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
        $options=[Text.Json.JsonDocumentOptions]::new();$options.MaxDepth=64
        $document=[Text.Json.JsonDocument]::Parse($text,$options)
        try {Assert-PayloadAcceptanceJson $document.RootElement} finally {$document.Dispose()}
        $value=ConvertFrom-ColdReceiptJson $text
        if ($null -eq $value) {$State.missing.Add($Code);return $null}
        if ($text -match '"(?:scope|kind|evidenceKind|status)"\s*:\s*"(?:UNIT_MOCK|MOCK_ONLY|TEMPLATE)[^"]*"') {$State.mock=$true}
        return ,$value
    } catch {$State.errors.Add($Code+':'+$_.Exception.Message);return $null}
}

# Полное дерево и AppInfo проверяются чтением файлов/JAR, без java.exe.
function Test-PayloadAcceptanceTree([string]$Root,$Manifest,$State,[string]$Code) {
    if ($null -eq $Manifest) {return}
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {$State.missing.Add($Code);return}
    try {
        [void](Assert-NativeAbsolute $Root);[void](Assert-ColdTree $Root $Manifest)
        $version=Get-ColdVersion $Root
        if ($version.releaseNumber -ne $Manifest.releaseNumber -or $version.commitSha -cne $Manifest.commitSha) {throw 'ACCEPTANCE_APPINFO_IDENTITY'}
        $State.checked.Add($Code)
    } catch {$State.errors.Add($Code+':'+$_.Exception.Message)}
}

# Вся проверка read-only; independent expected pins не выводятся из Row.
function Invoke-NativePayloadAcceptanceRead([string]$ObservationFile,[string]$ObservationSha256,$Expected,[string]$EvidenceKind) {
    $state=[pscustomobject]@{missing=[Collections.Generic.List[string]]::new();errors=[Collections.Generic.List[string]]::new();
        checked=[Collections.Generic.List[string]]::new();mock=($EvidenceKind -cne 'NATIVE')}
    $required=@('Scenario','Base','Client','Path','SourceRoot','OriginalTargetRoot','TargetRoot','InstalledRoot','RequireFallback')
    $pairs=@('BaseManifest','OriginalTargetManifest','Manifest','OriginalCommand','Command','OriginalLifecycle','Lifecycle')
    foreach ($pair in $pairs) {$required+=@(($pair+'File'),($pair+'Sha256'))}
    foreach ($name in $required) {if ($null -eq $Expected.PSObject.Properties[$name]) {$state.missing.Add('EXPECTED_'+$name)}}
    if ($state.missing.Count) {return [pscustomobject]@{status='PENDING';missing=$state.missing.ToArray();errors=@();proofComplete=$false;fullMatrix='PENDING';releaseProvenance='PENDING'}}
    if ($Expected.Scenario -cnotin @('unicode-payload','cashmemory','unmanaged-old-or-new') -or
        $Expected.Base -cnotin @('B1','B2') -or $Expected.Client -cnotin @('fx','swing','web') -or
        $Expected.Path -cnotin @('ascii','cyrillic','unicode') -or $Expected.RequireFallback -isnot [bool]) {$state.errors.Add('EXPECTED_CONTEXT')}
    $root=[IO.Path]::GetDirectoryName($ObservationFile)
    if ($ObservationSha256 -cnotmatch '^[0-9a-f]{64}$') {$state.errors.Add('OBSERVATION_EXPECTED_PIN')}
    $observation=Read-PayloadAcceptanceJson $ObservationFile $ObservationSha256 $state 'OBSERVATION'
    $docs=@{}
    foreach ($pair in $pairs) {
        $pin=$Expected.($pair+'Sha256')
        if ($pin -cnotmatch '^[0-9a-f]{64}$') {$state.errors.Add($pair+'_EXPECTED_PIN')}
        $docs[$pair]=Read-PayloadAcceptanceJson $Expected.($pair+'File') $pin $state $pair
    }
    foreach ($pair in 'BaseManifest','OriginalTargetManifest','Manifest') {
        if ($null -ne $docs[$pair]) {try {Assert-ColdImageInventory $docs[$pair].files $docs[$pair].treeSha256} catch {$state.errors.Add($pair+'_SCHEMA:'+ $_.Exception.Message)}}
    }
    if ($null -ne $docs.Manifest -and $null -ne $docs.OriginalTargetManifest) {
        foreach ($name in 'releaseNumber','commitSha','version') {Compare-PayloadAcceptanceField $docs.Manifest $name $docs.OriginalTargetManifest.$name $state ('TARGET_IDENTITY_'+$name)}
    }
    if ($null -ne $docs.Manifest -and $null -ne $docs.BaseManifest -and $docs.BaseManifest.releaseNumber -ge $docs.Manifest.releaseNumber) {$state.errors.Add('NON_FORWARD_SOURCE_TARGET')}
    if ($null -ne $docs.Manifest) {
        foreach ($asset in @([pscustomobject]@{assetName=$docs.Manifest.assetName;sha256=$docs.Manifest.sha256;sizeBytes=$docs.Manifest.sizeBytes})+@($docs.Manifest.deltaPatches)) {
            try {
                if ($asset.assetName -cnotmatch '^CashPrediction(?:-portable\.zip|\.from-[1-9][0-9]*\.cpdelta|\.cpdelta)$') {throw 'ACCEPTANCE_ASSET_NAME'}
                $file=Join-Path ([IO.Path]::GetDirectoryName($Expected.ManifestFile)) $asset.assetName
                [void](Resolve-PortableSafetyPath $file)
                if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {$state.missing.Add('ARTIFACT_'+$asset.assetName)}
                elseif ((Get-Item -LiteralPath $file).Length -ne $asset.sizeBytes -or (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -cne $asset.sha256) {$state.errors.Add('ARTIFACT_PIN_'+$asset.assetName)}
            } catch {$state.errors.Add('ARTIFACT:'+ $_.Exception.Message)}
        }
    }
    foreach ($inputRoot in @($Expected.SourceRoot,$Expected.OriginalTargetRoot,$Expected.TargetRoot)) {
        if ((Test-PortablePathContains $inputRoot $Expected.InstalledRoot) -or (Test-PortablePathContains $Expected.InstalledRoot $inputRoot)) {$state.errors.Add('INSTALLED_INPUT_OVERLAP')}
    }
    foreach ($pair in 'Command','OriginalCommand') {
        $manifestPair=if ($pair -ceq 'Command') {'Manifest'} else {'OriginalTargetManifest'}
        Compare-PayloadAcceptanceField $docs[$pair] 'targetManifest' $Expected.($manifestPair+'File') $state ($pair+'_TARGET_PATH')
        Compare-PayloadAcceptanceField $docs[$pair] 'targetManifestSha256' $Expected.($manifestPair+'Sha256') $state ($pair+'_TARGET_PIN')
        if ($null -ne $docs[$pair]) {
            $entries=@($docs[$pair].baseManifests | Where-Object portableDir -CEQ $Expected.SourceRoot)
            if ($entries.Count -ne 1 -or $entries[0].manifest -cne $Expected.BaseManifestFile -or $entries[0].sha256 -cne $Expected.BaseManifestSha256) {$state.errors.Add($pair+'_SOURCE_IDENTITY')}
            $index=if ($Expected.Base -ceq 'B1') {0} else {1}
            if (@($docs[$pair].baseManifests).Count -ne 2 -or $docs[$pair].baseManifests[$index].portableDir -cne $Expected.SourceRoot) {$state.errors.Add($pair+'_BASE_IDENTITY')}
        }
    }
    foreach ($pair in 'Lifecycle','OriginalLifecycle') {
        $manifestPair=if ($pair -ceq 'Lifecycle') {'Manifest'} else {'OriginalTargetManifest'}
        Compare-PayloadAcceptanceField $docs[$pair] 'artifactDir' ([IO.Path]::GetDirectoryName($Expected.($manifestPair+'File'))) $state ($pair+'_ARTIFACT_PATH')
        Compare-PayloadAcceptanceField $docs[$pair] 'manifestSha256' $Expected.($manifestPair+'Sha256') $state ($pair+'_MANIFEST_PIN')
    }
    Test-PayloadAcceptanceTree $Expected.SourceRoot $docs.BaseManifest $state 'SOURCE_TREE_APPINFO'
    Test-PayloadAcceptanceTree $Expected.OriginalTargetRoot $docs.OriginalTargetManifest $state 'ORIGINAL_TARGET_TREE_APPINFO'
    Test-PayloadAcceptanceTree $Expected.TargetRoot $docs.Manifest $state 'TARGET_TREE_APPINFO'
    Test-PayloadAcceptanceTree $Expected.InstalledRoot $docs.Manifest $state 'INSTALLED_TREE_APPINFO'
    $row=$null
    if ($null -ne $observation) {
        if ($null -eq $observation.PSObject.Properties['cell']) {$state.missing.Add('NATIVE_CELL')} else {$row=$observation.cell}
        Compare-PayloadAcceptanceField $observation 'scope' $Expected.Scenario $state 'SCENARIO'
        Compare-PayloadAcceptanceField $observation 'fullMatrix' 'PENDING' $state 'NO_MATRIX_PROMOTION'
        Compare-PayloadAcceptanceField $observation 'releaseProvenance' 'NOT_PROVEN' $state 'NO_RELEASE_PROMOTION'
        Compare-PayloadAcceptanceField $observation 'schemaVersion' 1 $state 'OBSERVATION_SCHEMA'
        Compare-PayloadAcceptanceField $observation 'nativeStatus' 'PENDING' $state 'NO_AGGREGATE_NATIVE_PROMOTION'
        Compare-PayloadAcceptanceField $observation 'normalFlow' 'delta' $state 'FROZEN_PAYLOAD_NORMAL_FLOW'
        if ($null -eq $observation.PSObject.Properties['status'] -or $observation.status -cin @('PENDING','UNIT_MOCK','MOCK_ONLY','TEMPLATE')) {$state.missing.Add('TERMINAL_SCOPED_OBSERVATION')}
        elseif ($observation.status -cne 'SCOPED_EVIDENCE') {$state.errors.Add('OBSERVATION_STATUS')}
    }
    if ($null -ne $row) {
        # Null placeholders сохраняют PENDING для неполных receipts при strict mode.
        foreach ($name in 'executed','status','reason','exitCode','failures','skipped','payloadInputPins','payloadFixtureIdentity','payloadFixtureIdentitySha256') {
            if ($null -eq $row.PSObject.Properties[$name]) {$row | Add-Member $name $null}
        }
        foreach ($field in @{scenario=$Expected.Scenario;base=$Expected.Base;client=$Expected.Client;path=$Expected.Path;
            payloadScope='SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF';payloadFullMatrix='PENDING';payloadReleaseProvenance='NOT_PROVEN';
            payloadArtifactDir=([IO.Path]::GetDirectoryName($Expected.ManifestFile));payloadManifestSha256=$Expected.ManifestSha256;
            payloadTargetRoot=$Expected.TargetRoot;payloadCommandFile=$Expected.CommandFile;payloadCommandSha256=$Expected.CommandSha256;
            payloadLifecycleFile=$Expected.LifecycleFile;payloadLifecycleSha256=$Expected.LifecycleSha256;
            exe=(Join-Path $Expected.InstalledRoot (Get-ColdLauncherName $Expected.Client))}.GetEnumerator()) {
            Compare-PayloadAcceptanceField $row $field.Key $field.Value $state ('ROW_'+$field.Key)
        }
        if ($null -ne $docs.BaseManifest) {
            Compare-PayloadAcceptanceField $row 'baseRelease' $docs.BaseManifest.releaseNumber $state 'ROW_BASE_RELEASE'
            Compare-PayloadAcceptanceField $row 'baseCommit' $docs.BaseManifest.commitSha $state 'ROW_BASE_COMMIT'
        }
        if ($null -ne $docs.Manifest) {
            Compare-PayloadAcceptanceField $row 'targetRelease' $docs.Manifest.releaseNumber $state 'ROW_TARGET_RELEASE'
            Compare-PayloadAcceptanceField $row 'targetCommit' $docs.Manifest.commitSha $state 'ROW_TARGET_COMMIT'
            Compare-PayloadAcceptanceField $row 'payloadTreeSha256' $docs.Manifest.treeSha256 $state 'ROW_TARGET_TREE'
        }
        if ($null -eq $row.executed -or $null -eq $row.status -or $row.executed -eq $false -or $row.status -ceq 'PENDING') {$state.missing.Add('NATIVE_EXECUTION')}
        elseif ($row.executed -isnot [bool] -or $row.status -cne 'PASS') {$state.errors.Add('NATIVE_EXECUTION_CONTRADICTION')}
        Compare-PayloadAcceptanceField $row 'reason' 'NATIVE_LIFECYCLE_EXECUTED' $state 'NATIVE_EXECUTION_REASON'
        foreach ($name in 'exitCode','failures','skipped') {Compare-PayloadAcceptanceField $row $name 0 $state ('NATIVE_'+$name)}
        $pins=Read-PayloadAcceptanceJson $row.payloadInputPins '' $state 'INPUT_PINS' $root
        if ($null -ne $pins -and $null -ne $pins.PSObject.Properties['status'] -and $pins.status -ceq 'PENDING') {$state.missing.Add('INPUT_INTEGRITY')}
        else {Compare-PayloadAcceptanceField $pins 'status' 'VERIFIED' $state 'INPUT_INTEGRITY'}
        Compare-PayloadAcceptanceField $pins 'commandSha256' $Expected.OriginalCommandSha256 $state 'ORIGINAL_COMMAND_PIN'
        Compare-PayloadAcceptanceField $pins 'lifecycleSha256' $Expected.OriginalLifecycleSha256 $state 'ORIGINAL_LIFECYCLE_PIN'
        foreach ($name in 'payload-failure.json','payload-integrity-failure.json') {
            if (Test-Path -LiteralPath (Join-Path $root $name)) {$state.errors.Add('RETAINED_FAILURE_'+$name)}
        }
        $inventories=@{}
        foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','phaseLog','httpTrace','command') {
            $path=if ($null -ne $row.PSObject.Properties[$name]) {$row.$name} else {''}
            $inventories[$name]=Read-PayloadAcceptanceJson $path '' $state $name $root
        }
        foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter') {
            $manifest=if ($name -ceq 'currentBefore') {$docs.BaseManifest} else {$docs.Manifest}
            if ($null -ne $inventories[$name] -and $null -ne $manifest -and -not (Test-ColdInventoryEqual $inventories[$name] $manifest.files)) {$state.errors.Add($name+'_MIXED_OR_WRONG_TREE')}
        }
        if ($null -ne $inventories.userBefore -and $null -ne $inventories.userAfter) {
            if (-not (Test-ColdInventoryEqual $inventories.userBefore $inventories.userAfter)) {$state.errors.Add('CASHMEMORY_OR_UNMANAGED_CHANGED')}
            if ((Test-Path -LiteralPath $Expected.InstalledRoot -PathType Container) -and
                -not (Test-ColdInventoryEqual $inventories.userAfter (Get-NativeUserObject $Expected.InstalledRoot))) {$state.errors.Add('CURRENT_USERDATA_CHANGED')}
            foreach ($path in 'CashMemory/protected-user.txt','CashMemory/NativeLifecycle.md','CashMemory/settings.md','protected-root.txt') {
                $entry=$inventories.userBefore.PSObject.Properties[$path]
                if ($null -eq $entry) {$state.missing.Add('USER_BASELINE_'+$path)} elseif ($entry.Value.directory -or $entry.Value.sizeBytes -le 0) {$state.errors.Add('USER_BASELINE_'+$path)}
            }
        }
        if ($null -ne $inventories.command) {
            $cellDir=[IO.Path]::GetDirectoryName($row.command)
            Compare-PayloadAcceptanceField $inventories.command 'executable' $row.exe $state 'LAUNCH_EXECUTABLE'
            $terminal=Read-PayloadAcceptanceJson (Join-Path $cellDir 'cell.json') '' $state 'POST_CLEANUP_CELL' $root
            foreach ($name in 'status','reason','executed','base','client','path') {
                if ($null -ne $row.PSObject.Properties[$name]) {Compare-PayloadAcceptanceField $terminal $name $row.$name $state ('POST_CLEANUP_'+$name)}
            }
            if ($null -ne $row.PSObject.Properties['finishedAt']) {
                Compare-PayloadAcceptanceField $terminal 'finishedAt' $row.finishedAt $state 'PRE_CLEANUP_FINISHED_AT'
            } else {$state.missing.Add('PRE_CLEANUP_FINISHED_AT')}
            $exit=Read-PayloadAcceptanceJson (Join-Path $cellDir 'exit-0.json') '' $state 'NATIVE_EXIT' $root
            $ui=if ($null -ne $inventories.command.PSObject.Properties['ui']) {$inventories.command.ui} else {$null}
            $exitReady=($null -ne $exit -and $null -ne $ui)
            foreach ($entry in @(@{value=$exit;fields=@('pid','startedAtTicks','exitCode','exitedUtc','remainingClients','kind');code='EXIT'},
                @{value=$ui;fields=@('pid','startedAtTicks','executablePath','modules','witness','lease','commandLine','args','observedAt');code='UI'})) {
                foreach ($name in $entry.fields) {
                    if ($null -eq $entry.value -or $null -eq $entry.value.PSObject.Properties[$name] -or $null -eq $entry.value.$name) {$state.missing.Add($entry.code+'_'+$name);$exitReady=$false}
                }
            }
            # Вложенные поля берутся из настоящего Assert-ColdUiReceipt, не из нового observer schema.
            if ($null -ne $ui) {
                foreach ($part in @(@{name='lease';fields=@('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client')},
                    @{name='witness';fields=$(if ($Expected.Client -ceq 'web') {@('kind','port','status','bodySha256','owningProcess')} else {@('kind','handle','title')})})) {
                    $value=if ($null -ne $ui.PSObject.Properties[$part.name]) {$ui.($part.name)} else {$null}
                    foreach ($name in $part.fields) {
                        if ($null -eq $value -or $null -eq $value.PSObject.Properties[$name] -or $null -eq $value.$name) {$state.missing.Add('UI_'+$part.name+'_'+$name);$exitReady=$false}
                    }
                }
            }
            if ($null -ne $exit) {
                Compare-PayloadAcceptanceField $exit 'exitCode' 0 $state 'EXIT_CODE'
                Compare-PayloadAcceptanceField $exit 'remainingClients' 0 $state 'EXIT_CLIENTS'
                if ($null -ne $ui -and $null -ne $ui.PSObject.Properties['pid']) {Compare-PayloadAcceptanceField $exit 'pid' $ui.pid $state 'EXIT_UI_PID'}
            }
            if ($exitReady) {try {Assert-NativeExitReceipt $exit $ui $Expected.InstalledRoot $Expected.Client} catch {$state.errors.Add('NATIVE_EXIT:'+ $_.Exception.Message)}}
            $last=Read-PayloadAcceptanceJson (Join-Path $Expected.InstalledRoot 'CashMemory/Updates/last-install.json') '' $state 'NATIVE_INSTALL'
            Compare-PayloadAcceptanceField $last 'outcome' 'UPDATED' $state 'NATIVE_INSTALL_OUTCOME'
            if ($null -ne $docs.Manifest) {Compare-PayloadAcceptanceField $last 'targetCommitSha' $docs.Manifest.commitSha $state 'NATIVE_INSTALL_TARGET'}
            foreach ($journal in 'install-journal.json','completed-journal.json') {if (Test-Path -LiteralPath (Join-Path $Expected.InstalledRoot ('CashMemory/Updates/'+$journal))) {$state.errors.Add('INSTALL_RESIDUE_'+$journal)}}
            if ($null -ne $inventories.phaseLog -and 'COMMITTED' -cnotin $inventories.phaseLog) {$state.errors.Add('INSTALL_NOT_COMMITTED')}
            $logFile=Join-Path $Expected.InstalledRoot 'CashMemory/Updates/update-log.md'
            if (-not (Test-Path -LiteralPath $logFile -PathType Leaf)) {$state.missing.Add('ACTUAL_INSTALL_PHASE_LOG')}
            else {
                try {
                    [void](Resolve-PortableSafetyPath $logFile)
                    if ((Get-Item -LiteralPath $logFile).Length -gt 8388608) {throw 'ACCEPTANCE_LOG_LIMIT'}
                    $phases=@();$previous=0L
                    foreach ($line in Get-Content -LiteralPath $logFile -Encoding utf8) {
                        if ($line -cmatch '^- (\S+) PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED)$') {
                            $ticks=Get-ColdUtcTicks $Matches[1];$phase=$Matches[2]
                            if (($exitReady -and $ticks -lt (Get-ColdUtcTicks $exit.exitedUtc)) -or $ticks -lt $previous) {throw 'ACCEPTANCE_INSTALL_BEFORE_EXIT'}
                            if ($null -ne $row.PSObject.Properties['finishedAt'] -and $ticks -gt (Get-ColdUtcTicks $row.finishedAt)) {throw 'ACCEPTANCE_INSTALL_AFTER_FINISHED_AT'}
                            $previous=$ticks;$phases+=@($phase)
                        }
                    }
                    if (-not (Test-ColdInventoryEqual $phases @('BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED'))) {throw 'ACCEPTANCE_INSTALL_PHASES'}
                } catch {$state.errors.Add('ACTUAL_INSTALL_PHASE_LOG:'+ $_.Exception.Message)}
            }
            if ($null -ne $docs.Manifest -and $null -ne $docs.BaseManifest -and $null -ne $inventories.httpTrace) {
                $delta=@($docs.Manifest.deltaPatches | Where-Object {$_.baseReleaseNumber -eq $docs.BaseManifest.releaseNumber -and $_.baseCommitSha -ceq $docs.BaseManifest.commitSha -and $_.baseTreeSha256 -ceq $docs.BaseManifest.treeSha256})
                if ($delta.Count -ne 1) {$state.errors.Add('DIRECT_DELTA_IDENTITY')}
                else {
                    $full=@($inventories.httpTrace | Where-Object path -CEQ '/CashPrediction-portable.zip')
                    $mode=if ($Expected.RequireFallback) {'CORRUPTDELTA'} else {'VALID'}
                    $server=Read-PayloadAcceptanceJson (Join-Path $cellDir 'raw-server-0-server-receipt.json') '' $state 'SERVER_RECEIPT' $root
                    if ($Expected.RequireFallback) {$state.missing.Add('PAYLOAD_FALLBACK_ROUTE_NOT_PRODUCED')}
                    Compare-PayloadAcceptanceField $server 'manifestSha256' $Expected.ManifestSha256 $state 'SERVER_MANIFEST_PIN'
                    Compare-PayloadAcceptanceField $server 'artifactDir' ([IO.Path]::GetDirectoryName($Expected.ManifestFile)) $state 'SERVER_ARTIFACT_ROOT'
                    if ($Expected.RequireFallback -and $full.Count -eq 0) {
                        $state.missing.Add('VERIFIED_FULL_FALLBACK')
                        try {Assert-NativeScenarioHttp 'delta' ([pscustomobject]@{events=$inventories.httpTrace}) $delta[0].assetName $delta[0].sizeBytes $docs.Manifest.sizeBytes} catch {$state.errors.Add('HTTP:'+ $_.Exception.Message)}
                    }
                    else {
                        Compare-PayloadAcceptanceField $server 'mode' $mode $state 'SERVER_MODE'
                        try {Assert-NativeScenarioHttp $(if ($Expected.RequireFallback) {'corrupt-delta-full'} else {'delta'}) ([pscustomobject]@{events=$inventories.httpTrace}) $delta[0].assetName $delta[0].sizeBytes $docs.Manifest.sizeBytes} catch {$state.errors.Add('HTTP:'+ $_.Exception.Message)}
                        if ($Expected.RequireFallback -and $full.Count -eq 1) {
                            $patch=@($inventories.httpTrace | Where-Object path -CEQ ('/'+$delta[0].assetName))
                            if ($patch.Count -eq 1) {
                                if ($null -eq $full[0].PSObject.Properties['startedNanos'] -or $null -eq $patch[0].PSObject.Properties['finishedNanos']) {$state.missing.Add('FALLBACK_ORDER')}
                                elseif ($full[0].startedNanos -lt $patch[0].finishedNanos) {$state.errors.Add('FALLBACK_ORDER')}
                            }
                        }
                    }
                }
            }
        }
        if ($Expected.Scenario -ceq 'unicode-payload') {
            $identity=Read-PayloadAcceptanceJson $row.payloadFixtureIdentity $row.payloadFixtureIdentitySha256 $state 'DERIVED_FIXTURE' $root
            $fixture=Get-NativePayloadFixture
            foreach ($entry in @{scope='DERIVED_UNICODE_PAYLOAD_NOT_RELEASE_PROVENANCE';originalTarget=$Expected.OriginalTargetRoot;
                derivedTarget=$Expected.TargetRoot;originalManifestSha256=$Expected.OriginalTargetManifestSha256;
                derivedManifestSha256=$Expected.ManifestSha256;coreMetadata='UNCHANGED';relativePath=$fixture.path;
                utf8Size=$fixture.bytes.Length;utf8Sha256=$fixture.sha256}.GetEnumerator()) {Compare-PayloadAcceptanceField $identity $entry.Key $entry.Value $state ('FIXTURE_'+$entry.Key)}
            if ($null -ne $docs.Manifest -and $null -ne $docs.OriginalTargetManifest) {
                Compare-PayloadAcceptanceField $identity 'derivedTreeSha256' $docs.Manifest.treeSha256 $state 'FIXTURE_TREE_PIN'
                try {Assert-NativePayloadDerived $docs.OriginalTargetManifest $docs.Manifest (Get-NativePayloadFixture) (Get-ColdVersion $Expected.OriginalTargetRoot) (Get-ColdVersion $Expected.TargetRoot)} catch {$state.errors.Add('DERIVED_IDENTITY:'+ $_.Exception.Message)}
                $fixture=Get-NativePayloadFixture
                $file=Join-Path $Expected.InstalledRoot $fixture.path
                if (-not (Test-Path -LiteralPath $file)) {$state.missing.Add('INSTALLED_UNICODE_BYTES')}
                elseif ((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -cne $fixture.sha256) {$state.errors.Add('INSTALLED_UNICODE_BYTES')}
            }
        }
    }
    $complete=($state.errors.Count -eq 0 -and $state.missing.Count -eq 0)
    if ($state.mock) {$state.missing.Add('REAL_NATIVE_PROVENANCE_NOT_UNIT_MOCK')}
    $status=if ($state.errors.Count) {'FAIL'} elseif ($state.missing.Count) {'PENDING'} else {'PASS'}
    return [pscustomobject]@{schemaVersion=1;status=$status;scope=$Expected.Scenario;evidenceKind=$EvidenceKind;
        proofComplete=($complete -and -not $state.mock);receiptChainComplete=$complete;
        missing=$state.missing.ToArray();errors=$state.errors.ToArray();checked=$state.checked.ToArray();
        fullMatrix='PENDING';releaseProvenance='PENDING';fallback=$(if (-not $Expected.RequireFallback) {'NOT_REQUIRED'} elseif ($status -ceq 'PASS') {'VERIFIED'} else {$status})}
}
