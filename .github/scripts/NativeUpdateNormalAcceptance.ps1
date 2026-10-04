<#
.SYNOPSIS
Независимый read-only acceptance девяти frozen normal сценариев, не bridge/runner.
.DESCRIPTION
Test-NativeNormalAcceptance -ExpectedFile -ExpectedSha256 -EvidenceFile -EvidenceSha256
  -EvidenceKind UNVERIFIED|NATIVE|UNIT_MOCK (default UNVERIFIED).
Expected MAIN закрепляет ДО native: schemaVersion=1; Scenario/Base/Client/Path/Phase;
SourceRoot/TargetRoot/InstalledRoot/CellEvidence/RegistryNode/Java/PreparedUtc;
File/Sha256 пары BaseManifest,TargetManifest,Command,Lifecycle; InputSnapshotSha256.
Get-NativeNormalInputPin читает эти входы до native; при первичном capture поле
InputSnapshotSha256 может быть пустым. Snapshot не включает сам ExpectedFile.
Evidence MAIN закрепляет после producer finally: schemaVersion=1; CellEvidence;
files=[{path:<relative producer filename>,sha256:<MAIN measured SHA>}]. Не Row return.
Индекс охватывает реальные persisted файлы, отсутствие элемента даёт PENDING.
FAIL имеет приоритет; helper PASS/exitCode=0 никогда не самостоятельное доказательство.
Текущий producer не сохраняет controlled-before/after и independent cleanup census;
полный normal gate остаётся PENDING до реальных collector hooks, не synthetic receipt.
finishedAt относится к окончанию assertions ДО finally; cell.json сохраняется ПОСЛЕ cleanup.
Producers: Invoke-NativeCell -> inventories/cell.json; Connect-NativeClient -> launch-N;
Close-NativeNormally -> exit-N; Stop-NativeFixture -> server-N-stats/trace;
finally -> raw-server-N-server-receipt; Initialize-NativeDomainSession -> domain-session;
Observe-NativeNonPolling -> nonpolling-observation (только delta).
#>

# Изолированный reader импортирует только определения, не тела runners/builders.
function Invoke-NormalAcceptancePrivate([string]$Operation,[object[]]$Arguments) {
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript({param($root,$operation,$arguments)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            . (Join-Path $root 'NativeUpdatePayloadAcceptance.ps1')
            . (Join-Path $root 'NativeUpdateNormalAcceptance.ps1')
            & $operation @arguments
        }).AddArgument($PSScriptRoot).AddArgument($Operation).AddArgument($Arguments)
        $outputs=@($worker.Invoke())
        if ($worker.HadErrors) {throw $worker.Streams.Error[0]}
        if ($outputs.Count -ne 1) {throw 'NORMAL_READER_OUTPUT_COUNT'}
        return $outputs[0]
    } finally {$worker.Dispose()}
}

# Read-only pre-native MAIN capture; не делает запуск и не создаёт evidence.
function Get-NativeNormalInputPin([string]$ExpectedFile,[string]$ExpectedSha256) {
    Invoke-NormalAcceptancePrivate 'Get-NormalAcceptanceInput' @($ExpectedFile,$ExpectedSha256)
}

# Публичный verdict не меняет Row, receipts, bridge либо aggregate gates.
function Test-NativeNormalAcceptance([string]$ExpectedFile,[string]$ExpectedSha256,
    [string]$EvidenceFile,[string]$EvidenceSha256,
    [ValidateSet('UNVERIFIED','NATIVE','UNIT_MOCK')][string]$EvidenceKind='UNVERIFIED') {
    try {Invoke-NormalAcceptancePrivate 'Read-NormalAcceptance' @($ExpectedFile,$ExpectedSha256,$EvidenceFile,$EvidenceSha256,$EvidenceKind)}
    catch {return [pscustomobject]@{status='FAIL';errors=@('NORMAL_READER_ERROR:'+ $_.Exception.Message);
        missing=@();proofComplete=$false;fullMatrix='PENDING';releaseProvenance='PENDING'}}
}

# Пары independently pinned JSON сохраняют существующие strict UTF-8/duplicate-key guards.
function Get-NormalAcceptanceInput($ExpectedFile,$ExpectedSha256) {
    $expected=Read-ColdPinnedJson $ExpectedFile $ExpectedSha256
    $cold=Read-ColdPinnedJson $expected.CommandFile $expected.CommandSha256
    $life=Read-ColdPinnedJson $expected.LifecycleFile $expected.LifecycleSha256
    $roots=@($cold.baseManifests | ForEach-Object portableDir)
    Assert-ColdCommand $cold $expected.Java $roots $expected.TargetRoot;Assert-NativeLifecycleConfig $life
    if ($cold.targetManifest -cne $expected.TargetManifestFile -or $cold.targetManifestSha256 -cne $expected.TargetManifestSha256 -or
        $life.manifestSha256 -cne $expected.TargetManifestSha256 -or $life.artifactDir -cne [IO.Path]::GetDirectoryName($expected.TargetManifestFile)) {throw 'NORMAL_CONFIG_IDENTITY'}
    $index=if ($expected.Base -ceq 'B1') {0} else {1}
    if ($roots.Count -ne 2 -or $roots[$index] -cne $expected.SourceRoot -or
        $cold.baseManifests[$index].manifest -cne $expected.BaseManifestFile -or
        $cold.baseManifests[$index].sha256 -cne $expected.BaseManifestSha256) {throw 'NORMAL_SOURCE_IDENTITY'}
    $paths=@($roots)+@($expected.TargetRoot,$expected.CommandFile,$expected.LifecycleFile,$expected.Java,
        $life.artifactDir,$cold.helperScript)+@($cold.toolFiles | ForEach-Object path)+
        @($cold.baseManifests | ForEach-Object manifest)+@($life.harnessClasspath.Split(';'))
    $paths+=@('NativeUpdateNormalAcceptanceCollector.ps1','NativeUpdateNormalAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1','NativeUpdatePayloadScenarios.ps1',
        'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
        'New-NativeUpdateArtifacts.ps1','New-NativeUpdateLifecycleConfig.ps1','New-UpdateBootstrapCommands.ps1','S7-Release.ps1',
        'NativeUpdateScenarioDispatch.ps1','NativeUpdateAcceptanceDispatch.ps1' |
        ForEach-Object {Join-Path $PSScriptRoot $_})
    $text=Get-NativePayloadSnapshot $paths
    return [pscustomobject]@{sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()}
}

# Fixed producer filename должен иметь независимый SHA в MAIN index, не путь из Row.
function Read-NormalReceipt([string]$Name,$Index,$Expected,$State,[switch]$Text) {
    $entries=@($Index.files | Where-Object path -CEQ $Name)
    if ($entries.Count -eq 0) {$State.missing.Add('PINNED_RECEIPT:'+ $Name);return $null}
    if ($entries.Count -ne 1) {$State.errors.Add('DUPLICATE_RECEIPT:'+ $Name);return $null}
    $path=Join-Path $Expected.CellEvidence $Name
    if ($Text) {
        try {
            [void](Assert-NativeAbsolute $path);[void](Resolve-PortableSafetyPath $path)
            $item=Get-Item -LiteralPath $path
            if ($item.PSIsContainer -or $item.Length -gt 8388608 -or $entries[0].sha256 -cnotmatch '^[0-9a-f]{64}$' -or
                (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $entries[0].sha256) {throw 'NORMAL_TEXT_PIN'}
            return [Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($path))
        } catch {$State.errors.Add($Name+':'+$_.Exception.Message);return $null}
    }
    $value=Read-PayloadAcceptanceJson $path $entries[0].sha256 $State $Name $Expected.CellEvidence
    if ($null -eq $value) {return $null}
    $keys=switch -Regex ($Name) {
        '^cell\.json$' {@('scenario','base','client','path','phase','status','executed','startedAt','finishedAt')}
        '^launch-[0-9]+\.json$' {@('executable','launcher','ui','args','manifestUri','startedAt')}
        '^launch-started-[0-9]+\.json$' {@('launcher','args','manifestUri','startedAt')}
        '^exit-[0-9]+\.json$' {@('pid','startedAtTicks','exitCode','exitedUtc','remainingClients','kind')}
        '^server-[0-9]+-stats\.json$' {@('schemaVersion','requests','completed','bytes','counts','active','closed','cancelled','healthy')}
        '^raw-server-[0-9]+-server-receipt\.json$' {@('mode','manifestSha256','artifactDir','manifestUri')}
        '^domain-session\.json$' {@('scope','process','arguments','nativeRoot','workingDirectory','coreJar','coreSha256','bridgeCoreJar','bridgeCoreSha256','source','sourceSha256','runtimeSha256','exitCode','files')}
        '^nonpolling-observation\.json$' {@('scope','status','windowMillis','elapsedMillis','startedUtc','finishedUtc','client','samples')}
        default {@()}
    }
    $incomplete=$false
    foreach ($key in $keys) {
        if ($null -eq $value.PSObject.Properties[$key] -or $null -eq $value.$key) {$State.missing.Add('RECEIPT_FIELD:'+ $Name+':'+$key);$incomplete=$true}
    }
    if ($incomplete) {return $null}
    return ,$value
}

# Отдельная disk cross-correlation использует pinned inputs, а не PASS runner assertions.
function Read-NormalAcceptance($ExpectedFile,$ExpectedSha256,$EvidenceFile,$EvidenceSha256,$EvidenceKind) {
    $state=[pscustomobject]@{missing=[Collections.Generic.List[string]]::new();errors=[Collections.Generic.List[string]]::new();
        checked=[Collections.Generic.List[string]]::new();mock=($EvidenceKind -cne 'NATIVE')}
    foreach ($pair in @(@{file=$ExpectedFile;pin=$ExpectedSha256;name='EXPECTED'},@{file=$EvidenceFile;pin=$EvidenceSha256;name='INDEX'})) {
        if (-not $pair.file -or -not $pair.pin) {$state.missing.Add('MAIN_PIN_'+$pair.name)}
        elseif ($pair.pin -cnotmatch '^[0-9a-f]{64}$') {$state.errors.Add('MAIN_PIN_FORMAT_'+$pair.name)}
    }
    $expected=Read-PayloadAcceptanceJson $ExpectedFile $ExpectedSha256 $state 'EXPECTED'
    $index=Read-PayloadAcceptanceJson $EvidenceFile $EvidenceSha256 $state 'EVIDENCE_INDEX'
    $required=@('schemaVersion','Scenario','Base','Client','Path','Phase','SourceRoot','TargetRoot','InstalledRoot','CellEvidence','RegistryNode','Java','PreparedUtc','InputSnapshotSha256')
    foreach ($pair in 'BaseManifest','TargetManifest','Command','Lifecycle') {$required+=@(($pair+'File'),($pair+'Sha256'))}
    foreach ($name in $required) {if ($null -eq $expected -or $null -eq $expected.PSObject.Properties[$name]) {$state.missing.Add('EXPECTED_'+$name)}}
    if ($null -eq $index -or $null -eq $index.PSObject.Properties['files'] -or $null -eq $index.PSObject.Properties['CellEvidence']) {$state.missing.Add('MAIN_EVIDENCE_INDEX')}
    if (-not $state.missing.Count) {
        try {
            if ($expected.schemaVersion -ne 1 -or $index.schemaVersion -ne 1 -or $expected.Scenario -cnotin @('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close') -or
                $expected.Base -cnotin @('B1','B2') -or $expected.Client -cnotin @('fx','swing','web') -or $expected.Path -cnotin @('ascii','cyrillic','unicode') -or
                $expected.Phase -cne 'SESSION' -or $expected.RegistryNode -cnotmatch '^ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'NORMAL_EXPECTED_CONTEXT'}
            foreach ($root in 'SourceRoot','TargetRoot','InstalledRoot','CellEvidence') {[void](Assert-NativeAbsolute $expected.$root)}
            $runRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($expected.InstalledRoot))
            [void](Assert-ColdOwnedRun $runRoot ([IO.Path]::GetTempPath()))
            $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
            if ($expected.InstalledRoot -cne (Join-Path (Join-Path $runRoot $variants[$expected.Path]) 'CashPrediction') -or
                [IO.Path]::GetFileName($expected.CellEvidence) -cne [IO.Path]::GetFileName($runRoot)) {throw 'NORMAL_OWNED_ROOT_IDENTITY'}
            if ($index.CellEvidence -cne $expected.CellEvidence) {throw 'NORMAL_EVIDENCE_ROOT'}
            foreach ($root in @($expected.SourceRoot,$expected.TargetRoot)) {
                if ((Test-PortablePathContains $root $expected.InstalledRoot) -or (Test-PortablePathContains $expected.InstalledRoot $root) -or
                    (Test-PortablePathContains $root $expected.CellEvidence) -or (Test-PortablePathContains $expected.CellEvidence $root)) {throw 'NORMAL_ROOT_OVERLAP'}
            }
            $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            if ($index.files -isnot [array] -or $index.files.Count -gt 128) {throw 'NORMAL_INDEX_LIMIT'}
            foreach ($entry in $index.files) {
                if ($entry.path -isnot [string] -or $entry.path -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or
                    -not $names.Add($entry.path) -or $entry.sha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'NORMAL_INDEX_ESCAPE_DUPLICATE_OR_PIN'}
            }
            if ($expected.InputSnapshotSha256 -cnotmatch '^[0-9a-f]{64}$') {$state.missing.Add('MAIN_PRE_NATIVE_INPUT_SNAPSHOT')}
            elseif ((Get-NormalAcceptanceInput $ExpectedFile $ExpectedSha256).sha256 -cne $expected.InputSnapshotSha256) {throw 'NORMAL_INPUTS_CHANGED'}
            Read-NormalAcceptanceChain $expected $index $state
        } catch {$state.errors.Add($_.Exception.Message)}
    }
    if ($EvidenceKind -cne 'NATIVE' -or $state.mock) {$state.missing.Add('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED')}
    $status=if ($state.errors.Count) {'FAIL'} elseif ($state.missing.Count) {'PENDING'} else {'PASS'}
    $cellKey=if ($null -ne $expected -and $null -ne $expected.PSObject.Properties['Scenario'] -and
        $null -ne $expected.PSObject.Properties['Base'] -and $null -ne $expected.PSObject.Properties['Client'] -and
        $null -ne $expected.PSObject.Properties['Path'] -and $null -ne $expected.PSObject.Properties['Phase']) {
        $expected.Scenario+'/'+$expected.Base+'/'+$expected.Client+'/'+$expected.Path+'/'+$expected.Phase
    } else {''}
    return [pscustomobject]@{status=$status;evidenceKind=$EvidenceKind;proofComplete=($status -ceq 'PASS');
        cellKey=$cellKey;missing=$state.missing.ToArray();errors=$state.errors.ToArray();checked=$state.checked.ToArray();fullMatrix='PENDING';releaseProvenance='PENDING'}
}

# Независимая связь launch/exit/server с MAIN intent; lifecycle process APIs не вызываются.
function Read-NormalAcceptanceChain($Expected,$Index,$State) {
    $base=Read-PayloadAcceptanceJson $Expected.BaseManifestFile $Expected.BaseManifestSha256 $State 'BASE'
    $target=Read-PayloadAcceptanceJson $Expected.TargetManifestFile $Expected.TargetManifestSha256 $State 'TARGET'
    $cold=Read-PayloadAcceptanceJson $Expected.CommandFile $Expected.CommandSha256 $State 'COMMAND'
    $life=Read-PayloadAcceptanceJson $Expected.LifecycleFile $Expected.LifecycleSha256 $State 'LIFECYCLE'
    if ($null -eq $base -or $null -eq $target -or $null -eq $cold -or $null -eq $life) {return}
    Assert-ColdImageInventory $base.files $base.treeSha256;Assert-ColdImageInventory $target.files $target.treeSha256
    foreach ($asset in @([pscustomobject]@{assetName=$target.assetName;sha256=$target.sha256;sizeBytes=$target.sizeBytes})+@($target.deltaPatches)) {
        if ($asset.assetName -cnotmatch '^CashPrediction(?:-portable\.zip|\.from-[1-9][0-9]*\.cpdelta)$') {throw 'NORMAL_ASSET_ESCAPE'}
        $file=Join-Path $life.artifactDir $asset.assetName;[void](Resolve-PortableSafetyPath $file)
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {$State.missing.Add('ARTIFACT:'+ $asset.assetName)}
        elseif ((Get-Item -LiteralPath $file).Length -ne $asset.sizeBytes -or (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -cne $asset.sha256) {throw 'NORMAL_ARTIFACT_PIN'}
    }
    if ($base.releaseNumber -ge $target.releaseNumber -or $base.commitSha -ceq $target.commitSha) {throw 'NORMAL_NON_FORWARD_IDENTITY'}
    Test-PayloadAcceptanceTree $Expected.SourceRoot $base $State 'SOURCE_APPINFO_TREE'
    Test-PayloadAcceptanceTree $Expected.TargetRoot $target $State 'TARGET_APPINFO_TREE'
    $negative=$Expected.Scenario -cin @('corrupt-full-retain','offline','timeout','malformed')
    $final=if ($negative) {$base} else {$target}
    Test-PayloadAcceptanceTree $Expected.InstalledRoot $final $State 'CURRENT_APPINFO_TREE'
    $cell=Read-NormalReceipt 'cell.json' $Index $Expected $State
    foreach ($entry in @{scenario=$Expected.Scenario;base=$Expected.Base;client=$Expected.Client;path=$Expected.Path;phase=$Expected.Phase;
        exe=(Join-Path $Expected.InstalledRoot (Get-ColdLauncherName $Expected.Client));baseRelease=$base.releaseNumber;baseCommit=$base.commitSha;
        targetRelease=$target.releaseNumber;targetCommit=$target.commitSha;executed=$true;status='PASS';reason='NATIVE_LIFECYCLE_EXECUTED';exitCode=0;failures=0;skipped=0}.GetEnumerator()) {
        Compare-PayloadAcceptanceField $cell $entry.Key $entry.Value $State ('CELL_'+$entry.Key)
    }
    if ($null -ne $cell) {
        if ((Get-NativeUtcTicks $Expected.PreparedUtc) -gt (Get-NativeUtcTicks $cell.startedAt) -or
            (Get-NativeUtcTicks $cell.finishedAt) -lt (Get-NativeUtcTicks $cell.startedAt)) {throw 'NORMAL_INTENT_CHRONOLOGY'}
    }
    $inventory=@{}
    foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog') {
        $inventory[$name]=Read-NormalReceipt ($name+'.json') $Index $Expected $State
        Compare-PayloadAcceptanceField $cell $name (Join-Path $Expected.CellEvidence ($name+'.json')) $State ('CELL_PATH_'+$name)
    }
    foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter') {
        $manifest=if ($name -ceq 'currentBefore') {$base} elseif ($name -ceq 'currentAfter') {$final} else {$target}
        if ($null -ne $inventory[$name] -and -not (Test-ColdInventoryEqual $inventory[$name] $manifest.files)) {throw ('NORMAL_MIXED_TREE:'+ $name)}
    }
    if ($null -ne $inventory.userBefore -and $null -ne $inventory.userAfter) {
        if (-not (Test-ColdInventoryEqual $inventory.userBefore $inventory.userAfter) -or
            -not (Test-ColdInventoryEqual $inventory.userAfter (Get-NativeUserObject $Expected.InstalledRoot))) {throw 'NORMAL_USER_CHANGED'}
        foreach ($name in 'CashMemory/NativeLifecycle.md','CashMemory/settings.md','CashMemory/protected-user.txt','protected-root.txt') {
            $entry=$inventory.userBefore.PSObject.Properties[$name]
            if ($null -eq $entry -or $entry.Value.directory -or $entry.Value.sizeBytes -le 0) {throw 'NORMAL_DOMAIN_USER_BASELINE'}
        }
    }
    $sessions=if ($Expected.Scenario -ceq 'cancel-next-session') {2} else {1};$allFinishes=@();$previousExit=0L;$httpComplete=$true
    $delta=@($target.deltaPatches | Where-Object {$_.baseReleaseNumber -eq $base.releaseNumber -and $_.baseCommitSha -ceq $base.commitSha -and $_.baseTreeSha256 -ceq $base.treeSha256})
    if ($delta.Count -ne 1) {throw 'NORMAL_DIRECT_DELTA_IDENTITY'}
    for ($session=0;$session -lt $sessions;$session++) {
        $launch=Read-NormalReceipt ('launch-'+$session+'.json') $Index $Expected $State
        $started=Read-NormalReceipt ('launch-started-'+$session+'.json') $Index $Expected $State
        $exit=Read-NormalReceipt ('exit-'+$session+'.json') $Index $Expected $State
        $server=Read-NormalReceipt ('raw-server-'+$session+'-server-receipt.json') $Index $Expected $State
        $stats=Read-NormalReceipt ('server-'+$session+'-stats.json') $Index $Expected $State
        $trace=Read-NormalReceipt ('server-'+$session+'-trace.json') $Index $Expected $State
        if ($null -ne $launch -and $null -ne $exit) {
            Assert-NativeExitReceipt $exit $launch.ui $Expected.InstalledRoot $Expected.Client
            $args=@('--test-api','--home',$Expected.InstalledRoot,'--registry-node',$Expected.RegistryNode)
            if ($Expected.Client -ceq 'web') {$args+=@('--no-browser','--no-window')}
            if (-not (Test-ColdInventoryEqual $args $launch.args) -or -not (Test-ColdInventoryEqual $args $launch.ui.args) -or
                $launch.executable -cne (Join-Path $Expected.InstalledRoot (Get-ColdLauncherName $Expected.Client))) {throw 'NORMAL_LAUNCH_AUTHORITY'}
            if ((Get-NativeUtcTicks $launch.startedAt) -lt $previousExit -or
                ($null -ne $cell -and (Get-NativeUtcTicks $exit.exitedUtc) -gt (Get-NativeUtcTicks $cell.finishedAt))) {throw 'NORMAL_SESSION_ORDER'}
            $previousExit=Get-NativeUtcTicks $exit.exitedUtc
            foreach ($name in 'launcher','args','manifestUri','startedAt') {
                if ($null -eq $started -or $null -eq $started.PSObject.Properties[$name]) {$State.missing.Add('STARTED_'+$name)}
                elseif (-not (Test-ColdInventoryEqual $started.$name $launch.$name)) {throw ('NORMAL_STARTED_CHANGED:'+ $name)}
            }
            if ($null -ne $server) {Compare-PayloadAcceptanceField $launch 'manifestUri' $server.manifestUri $State 'LAUNCH_SERVER_ENDPOINT';Assert-NativeEndpoint $server.manifestUri}
            $State.checked.Add('BIRTH_UI_ORDINARY_EXIT_SESSION_'+$session)
        }
        if ($null -ne $stats -and $null -ne $trace) {
            Assert-NativeHttp $stats $trace
            $finishes=@($trace | Where-Object event -CEQ 'FINISH');$allFinishes+=@($finishes)
            Test-NormalHttpRoute $Expected.Scenario $session $finishes $delta[0] $target $State
            if ($null -ne $server) {
                $mode=switch ($Expected.Scenario) {'corrupt-full-retain' {'CORRUPTFULL'} 'corrupt-delta-full' {'CORRUPTDELTA'} 'offline' {'OFFLINE_CLOSE'} 'timeout' {'DELAYEDHEADERS'} 'malformed' {'MALFORMEDMANIFEST'} 'once-three-attempts' {'FIRST_TWO_503'} 'cancel-next-session' {if ($session -eq 0) {'SLOWCHUNKS'} else {'VALID'}} default {'VALID'}}
                Compare-PayloadAcceptanceField $server 'mode' $mode $State 'SERVER_MODE'
                if ($Expected.Scenario -cne 'corrupt-full-retain') {
                    Compare-PayloadAcceptanceField $server 'manifestSha256' $Expected.TargetManifestSha256 $State 'SERVER_MANIFEST_PIN'
                    Compare-PayloadAcceptanceField $server 'artifactDir' $life.artifactDir $State 'SERVER_ARTIFACT_ROOT'
                }
            }
            $State.checked.Add('HTTP_COUNTER_TRACE_SESSION_'+$session)
        } else {$httpComplete=$false}
    }
    if ($httpComplete -and $null -ne $inventory.httpTrace -and -not (Test-ColdInventoryEqual $inventory.httpTrace $allFinishes)) {throw 'NORMAL_AGGREGATE_HTTP_CHANGED'}
    Read-NormalDomainAndOutcome $Expected $Index $State $base $target $cold $inventory $previousExit
    $controlledBefore=Read-NormalReceipt 'controlled-before.json' $Index $Expected $State
    $controlledAfter=Read-NormalReceipt 'controlled-after.json' $Index $Expected $State
    if ($null -ne $controlledBefore -and $null -ne $controlledAfter) {
        $lastLaunch=Read-NormalReceipt ('launch-'+($sessions-1)+'.json') $Index $Expected $State
        if ($null -ne $lastLaunch -and $null -ne $cell) {
            Assert-ColdControlledChanges $controlledBefore $controlledAfter $Expected.Client $lastLaunch.ui $Expected.InstalledRoot (Get-NativeUtcTicks $cell.finishedAt)
            $State.checked.Add('CONTROLLED_SESSION_BEFORE_AFTER')
        }
    } else {$State.missing.Add('CONTROLLED_SESSION_BEFORE_AFTER_NOT_PERSISTED')}
    # Нет такого producer: cleanup не выводится из cell.status или современного census.
    $State.missing.Add('POST_FINALLY_CLIENT_HELPER_SERVER_REGISTRY_CENSUS_NOT_PERSISTED')
    if ($Expected.Scenario -ceq 'leases-normal-close') {$State.missing.Add('POST_EXIT_LEASE_REMOVAL_OBSERVATION_NOT_PERSISTED')}
}

# Route проверяется независимо по transport bytes/order и исходным manifest identities.
function Test-NormalHttpRoute($Scenario,$Session,$Events,$Delta,$Target,$State) {
    $metadata=@($Events | Where-Object path -CEQ '/update.json');$patch=@($Events | Where-Object path -CEQ ('/'+$Delta.assetName))
    $full=@($Events | Where-Object path -CEQ '/CashPrediction-portable.zip')
    if ($Scenario -ceq 'cancel-next-session' -and $Session -eq 0) {
        if ($metadata.Count -ne 1 -or $patch.Count -ne 1 -or $full.Count -or $patch[0].bytes -le 0 -or $patch[0].bytes -ge $Delta.sizeBytes) {throw 'NORMAL_CANCEL_PARTIAL_HTTP'}
        $State.missing.Add('CANCEL_NO_READY_AND_OLD_TREE_BETWEEN_SESSIONS_NOT_PERSISTED');return
    }
    if ($Scenario -cin @('offline','timeout','malformed')) {
        if ($Events.Count -ne $metadata.Count -or $patch.Count -or $full.Count -or $metadata.Count -lt 3 -or $metadata.Count -gt 6) {throw 'NORMAL_NEGATIVE_HTTP'}
        if ($Scenario -ceq 'offline' -and @($metadata | Where-Object {$_.status -ne 0 -or $_.bytes -ne 0}).Count) {throw 'NORMAL_OFFLINE_TRANSPORT'}
        if ($Scenario -ceq 'malformed' -and ($metadata.Count -ne 3 -or @($metadata | Where-Object {$_.status -ne 200 -or $_.bytes -ne 1}).Count)) {throw 'NORMAL_MALFORMED_TRANSPORT'}
        $State.missing.Add('PREPARATION_FINISHED_NO_READY_AT_EXIT_NOT_PERSISTED');return
    }
    if ($Scenario -ceq 'corrupt-full-retain') {
        if ($Events.Count -ne 2 -or $metadata.Count -ne 1 -or $metadata[0].status -ne 200 -or $patch.Count -or $full.Count -ne 1 -or $full[0].bytes -ne $Target.sizeBytes -or $full[0].status -ne 200) {throw 'NORMAL_CORRUPT_FULL_ROUTE'}
        return
    }
    $attempts=if ($Scenario -ceq 'once-three-attempts') {3} else {1}
    if ($metadata.Count -ne $attempts -or $patch.Count -ne 1 -or $patch[0].status -ne 200 -or $patch[0].bytes -ne $Delta.sizeBytes) {throw 'NORMAL_DELTA_ROUTE'}
    if ($Scenario -ceq 'once-three-attempts') {
        if (($metadata.status -join '/') -cne '503/503/200') {throw 'NORMAL_RETRY_ORDER'}
    } elseif ($metadata[0].status -ne 200) {throw 'NORMAL_METADATA_STATUS'}
    if ($Scenario -ceq 'corrupt-delta-full') {
        if ($full.Count -ne 1 -or $full[0].status -ne 200 -or $full[0].bytes -ne $Target.sizeBytes -or
            $full[0].startedNanos -lt $patch[0].finishedNanos) {throw 'NORMAL_FALLBACK_ORDER_OR_BYTES'}
    } elseif ($full.Count) {throw 'NORMAL_UNEXPECTED_FULL'}
    if ($Events.Count -ne ($attempts+1+$full.Count)) {throw 'NORMAL_EXTRA_HTTP'}
}

# Domain files и update outcome проверяются actual persisted bytes; missing observers не выдумываются.
function Read-NormalDomainAndOutcome($Expected,$Index,$State,$Base,$Target,$Cold,$Inventory,$ExitTicks) {
    $domain=Read-NormalReceipt 'domain-session.json' $Index $Expected $State
    $output=Read-NormalReceipt 'domain-session.out.txt' $Index $Expected $State -Text
    if ($null -ne $domain) {
        $version=Get-ColdVersion $Expected.SourceRoot
        foreach ($entry in @{scope='FROZEN_CORE_DOMAIN_WRITERS';nativeRoot=$Expected.InstalledRoot;workingDirectory=$Expected.CellEvidence;
            exitCode=0;runtimeSha256=$Cold.runtimeSha256;coreJar=(Join-Path (Join-Path $Expected.InstalledRoot 'app') $version.jar);
            coreSha256=$version.jarSha256;bridgeCoreJar=(Join-Path $Expected.CellEvidence 'domain-core.jar');
            source=(Join-Path $Expected.CellEvidence 'NativeDomainSession.java')}.GetEnumerator()) {Compare-PayloadAcceptanceField $domain $entry.Key $entry.Value $State ('DOMAIN_'+$entry.Key)}
        if (@($domain.files).Count -ne 2) {throw 'NORMAL_DOMAIN_FILE_SET'}
        if (-not (Test-ColdInteger $domain.process.ProcessId 1) -or -not (Test-ColdInteger $domain.process.StartedAtTicks 1) -or
            $domain.process.ExecutablePath -cne $Expected.Java -or $domain.process.OwnedRoot -cne $Expected.CellEvidence) {throw 'NORMAL_DOMAIN_PROCESS_IDENTITY'}
        $launch=Read-NormalReceipt 'launch-0.json' $Index $Expected $State
        if ($null -ne $launch -and $domain.process.StartedAtTicks -gt (Get-NativeUtcTicks $launch.startedAt)) {throw 'NORMAL_DOMAIN_AFTER_NATIVE_LAUNCH'}
        if ($domain.source -cne (Join-Path $Expected.CellEvidence 'NativeDomainSession.java') -or
            $domain.bridgeCoreJar -cne (Join-Path $Expected.CellEvidence 'domain-core.jar')) {throw 'NORMAL_DOMAIN_CODE_ESCAPE'}
        foreach ($file in @($domain.source,$domain.bridgeCoreJar)) {
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {$State.missing.Add('DOMAIN_FILE:'+ $file);return}
        }
        if (-not (Test-ColdInventoryEqual $domain.arguments (Get-NativeDomainBridgeArguments $Expected.InstalledRoot $domain.bridgeCoreJar $domain.source))) {throw 'NORMAL_DOMAIN_ARGUMENTS'}
        [void](Resolve-PortableSafetyPath $domain.bridgeCoreJar)
        if ((Get-FileHash -LiteralPath $domain.bridgeCoreJar).Hash.ToLowerInvariant() -cne $version.jarSha256 -or
            $domain.bridgeCoreSha256 -cne $version.jarSha256 -or
            (Get-FileHash -LiteralPath $domain.source).Hash.ToLowerInvariant() -cne $domain.sourceSha256) {throw 'NORMAL_DOMAIN_CORE_SOURCE_PIN'}
        if ($null -ne $output -and $output.Trim() -cne 'NATIVE_DOMAIN_SESSION_PREPARED') {throw 'NORMAL_DOMAIN_OUTPUT'}
        $source=Read-NormalReceipt 'NativeDomainSession.java' $Index $Expected $State -Text
        if ($null -ne $source -and $source -cne (Get-NativeDomainSessionSource)) {throw 'NORMAL_DOMAIN_SOURCE'}
        foreach ($file in $domain.files) {
            if ($file.sizeBytes -le 0 -or $file.path -cnotin @((Join-Path $Expected.InstalledRoot 'CashMemory/NativeLifecycle.md'),(Join-Path $Expected.InstalledRoot 'CashMemory/settings.md'))) {throw 'NORMAL_DOMAIN_PATH'}
            [void](Resolve-PortableSafetyPath $file.path)
            if ((Get-FileHash -LiteralPath $file.path).Hash.ToLowerInvariant() -cne $file.sha256 -or (Get-Item -LiteralPath $file.path).Length -ne $file.sizeBytes) {throw 'NORMAL_DOMAIN_BYTES'}
        }
    }
    if ($Expected.Scenario -ceq 'delta') {
        $quiet=Read-NormalReceipt 'nonpolling-observation.json' $Index $Expected $State
        if ($null -ne $quiet) {
            Assert-NativeNonPollingReceipt $quiet
            if ((Get-NativeUtcTicks $quiet.finishedUtc) -gt $ExitTicks -or
                (Get-NativeUtcTicks $quiet.finishedUtc) -lt (Get-NativeUtcTicks $quiet.startedUtc)) {throw 'NORMAL_NONPOLLING_CHRONOLOGY'}
            $launch=Read-NormalReceipt 'launch-0.json' $Index $Expected $State
            if ($null -ne $launch -and -not (Test-ColdInventoryEqual $quiet.client $launch.ui)) {throw 'NORMAL_NONPOLLING_UI_IDENTITY'}
            foreach ($sample in $quiet.samples) {
                $bytes=[Text.Encoding]::UTF8.GetBytes($sample.http.trace)
                if ($bytes.Length -ne $sample.http.traceBytes -or
                    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $sample.http.traceSha256) {throw 'NORMAL_NONPOLLING_TRACE_PIN'}
                if (-not $sample.http.trace.EndsWith("`n") -or $sample.http.stats.closed -ne $false -or
                    $sample.http.stats.active -ne 0 -or $sample.http.stats.healthy -ne $true -or $sample.http.stats.cancelled -ne $false) {throw 'NORMAL_NONPOLLING_LIVE_STATE'}
                $events=@(foreach ($line in $sample.http.trace.TrimEnd("`n").Split("`n")) {
                    if ($line.Length -gt 65536) {throw 'NORMAL_NONPOLLING_TRACE_LIMIT'}
                    $json=[Text.Json.JsonDocument]::Parse($line)
                    try {Assert-PayloadAcceptanceJson $json.RootElement} finally {$json.Dispose()}
                    ConvertFrom-ColdReceiptJson $line
                })
                if (-not (Test-ColdInventoryEqual $events $sample.http.events)) {throw 'NORMAL_NONPOLLING_EVENTS_CHANGED'}
                $stats=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $sample.http.stats -Depth 64)
                $stats.closed=$true;Assert-NativeHttp $stats $events
                if (-not (Test-ColdInventoryEqual @($events | Where-Object event -CEQ 'FINISH') $Inventory.httpTrace)) {throw 'NORMAL_NONPOLLING_FINAL_TRACE_CHANGED'}
            }
            $State.checked.Add('LIVE_NONPOLLING_5000MS')
        }
    } else {$State.missing.Add('LIVE_NONPOLLING_OBSERVATION_NOT_PRODUCED:'+ $Expected.Scenario)}
    if ($Expected.Scenario -ceq 'corrupt-full-retain') {
        $fixture=Read-NormalReceipt 'full-only-fixture.json' $Index $Expected $State
        $retained=Read-NormalReceipt 'full-retained.json' $Index $Expected $State
        if ($null -ne $fixture) {
            Assert-NativeFullOnlyManifest $fixture.manifest $Target
            $runRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($Expected.InstalledRoot))
            $served=Join-Path $runRoot 'full-only-artifacts/update.json'
            if ($fixture.servedManifest -cne $served -or $fixture.payload -cne (Join-Path ([IO.Path]::GetDirectoryName($served)) $Target.assetName)) {throw 'NORMAL_FULL_FIXTURE_ESCAPE'}
            $wire=Read-PayloadAcceptanceJson $served $fixture.servedManifestSha256 $State 'FULL_ONLY_SERVED_MANIFEST' $runRoot
            if ($null -ne $wire) {Assert-NativeFullOnlyManifest $wire $Target}
            if ($fixture.sourceManifest -cne $Expected.TargetManifestFile -or $fixture.sourceManifestSha256 -cne $Expected.TargetManifestSha256 -or
                $fixture.payloadSha256 -cne $Target.sha256 -or $fixture.mode -cne 'CORRUPTFULL') {throw 'NORMAL_FULL_FIXTURE_IDENTITY'}
            $server=Read-NormalReceipt 'raw-server-0-server-receipt.json' $Index $Expected $State
            if ($null -ne $server) {Assert-NativeFullServer $server ([pscustomobject]@{artifactDir=[IO.Path]::GetDirectoryName($fixture.servedManifest);manifestSha256=$fixture.servedManifestSha256}) $Target}
        }
        if ($null -ne $retained -and ($retained.scope -cne 'CORRUPT_FULL_OLD_TREE' -or $retained.elapsedMillis -lt 3000 -or
            -not (Test-ColdInventoryEqual $retained.files $Base.files))) {throw 'NORMAL_FULL_NOT_RETAINED'}
        $State.missing.Add('RETAINED_INTERVAL_PROCESS_CENSUS_NOT_PERSISTED')
    } elseif ($Expected.Scenario -cnotin @('offline','timeout','malformed')) {
        $last=Read-PayloadAcceptanceJson (Join-Path $Expected.InstalledRoot 'CashMemory/Updates/last-install.json') '' $State 'ACTUAL_LAST_INSTALL' $Expected.InstalledRoot
        Compare-PayloadAcceptanceField $last 'outcome' 'UPDATED' $State 'INSTALL_OUTCOME'
        Compare-PayloadAcceptanceField $last 'targetCommitSha' $Target.commitSha $State 'INSTALL_COMMIT'
        foreach ($residue in 'install-journal.json','completed-journal.json') {
            if (Test-Path -LiteralPath (Join-Path $Expected.InstalledRoot ('CashMemory/Updates/'+$residue))) {throw 'NORMAL_INSTALL_RESIDUE'}
        }
        $log=Read-NormalReceipt 'update-log.md' $Index $Expected $State -Text
        if ($null -ne $log) {
            $phases=@();$previous=$ExitTicks
            foreach ($line in $log.Split("`n")) {
                if ($line.TrimEnd("`r") -cmatch '^- (\S+) PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED)$') {
                    $ticks=Get-NativeUtcTicks $Matches[1]
                    if ($ticks -lt $previous) {throw 'NORMAL_INSTALL_BEFORE_EXIT_OR_ORDER'};$previous=$ticks;$phases+=@($Matches[2])
                }
            }
            if (($phases -join '/') -cne 'BOOTSTRAPPING/BACKING_UP/INSTALLING/VERIFYING/COMMITTED') {throw 'NORMAL_INSTALL_PHASES'}
            $cell=Read-NormalReceipt 'cell.json' $Index $Expected $State
            if ($null -ne $cell -and $previous -gt (Get-NativeUtcTicks $cell.finishedAt)) {throw 'NORMAL_INSTALL_AFTER_FINISHED_AT'}
            if ($null -ne $Inventory.phaseLog -and -not (Test-ColdInventoryEqual $Inventory.phaseLog (@('SESSION')+$phases))) {throw 'NORMAL_PHASE_RECEIPT_CHANGED'}
        }
        $oldReady=Read-NormalReceipt 'ready-old-tree.json' $Index $Expected $State
        $staged=Read-NormalReceipt 'ready-staged-tree.json' $Index $Expected $State
        if ($null -ne $oldReady -and $null -ne $staged) {
            if (-not (Test-ColdInventoryEqual $oldReady $Base.files) -or -not (Test-ColdInventoryEqual $staged $Target.files)) {throw 'NORMAL_READY_MIXED_TREE'}
            $State.checked.Add('READY_OLD_AND_STAGED_TREES')
        } else {$State.missing.Add('READY_OLD_AND_STAGED_TREE_OBSERVATION_NOT_PERSISTED')}
    }
    if ($Expected.Scenario -ceq 'timeout') {$State.missing.Add('CLIENT_TIMEOUT_BUDGET_SERVER_DELAY_CONFIG_NOT_PERSISTED')}
}
