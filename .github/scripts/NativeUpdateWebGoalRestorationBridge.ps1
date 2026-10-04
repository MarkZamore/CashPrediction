<#
.SYNOPSIS
Actual JDK Edge/CDP producer для отдельного Web Goal restoration probe; dot-source не запускает GUI.
.DESCRIPTION
Подготовка javac разрешена отдельно от actual Start. Все sources/core/JDK/classes pinned,
отдельный браузерный профиль внутри observer output. Только actual TestApiBridge shot, не модельный POST.
Нужны AST guards frozen cold/lifecycle и собственные GoalProbe/RestoredWindow definitions.
#>

# Замкнутый JDK-only compile closure: без JUnit, Maven, shared target/classes или новых библиотек.
function Get-NativeWebGoalBridgeSources {
    return @('browser/BrowserSession.java','browser/EdgeLauncher.java','browser/CdpClient.java','browser/ProfileCleanup.java','browser/PngHeader.java',
        'driver/CdpTestApi.java','driver/TestApiBridge.java','driver/UiTestDriver.java','process/ProcessTree.java',
        'io/Dirs.java','pipeline/DumpTrees.java','portable/NativeWebGoalRestorationBridge.java')
}

# Проверяет полный --user-data-dir аргумент, не substring и не похожий чужой профиль.
function Test-NativeWebGoalProfileArgument([string]$CommandLine,[string]$Profile) {
    $quoted=[regex]::Escape($Profile)
    $alternatives='"--user-data-dir='+$quoted+'"|--user-data-dir="'+$quoted+'"'
    if ($Profile -notmatch '\s') {$alternatives+='|--user-data-dir='+$quoted}
    return [regex]::IsMatch($CommandLine,'(?:^|\s)(?:'+$alternatives+')(?=\s|$)',[Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

# UInt32 разрешён только для actual CIM observation, JSON wire guards не меняются.
function Convert-NativeWebGoalCimPid($Value) {
    if (($Value -isnot [uint32] -and $Value -isnot [int] -and $Value -isnot [long]) -or
        $Value -le 0 -or $Value -gt [int]::MaxValue) {throw 'WEB_GOAL_CIM_PID'}
    return [int]$Value
}

# До Start все executable/core/source/class bytes должны совпадать с independently approved pins.
function Assert-NativeWebGoalRuntime($Runtime) {
    foreach ($pin in @(@{path=$Runtime.java;sha=$Runtime.javaSha256},@{path=$Runtime.core;sha=$Runtime.coreSha256})+
        @($Runtime.sourcePins)+@($Runtime.classPins)) {
        [void](Resolve-PortableSafetyPath $pin.path)
        if ($pin.sha -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'WEB_GOAL_RUNTIME_PIN'}
    }
    $actual=@(Get-ChildItem -LiteralPath $Runtime.classes -Recurse -File | ForEach-Object {$_.FullName} | Sort-Object)
    $expected=@($Runtime.classPins.path | Sort-Object)
    if (-not (Test-ColdInventoryEqual $actual $expected)) {throw 'WEB_GOAL_CLASS_SET'}
}

# Настоящий bounded javac/JDK child только в собственном Temp; retained handle не становится native PASS.
function Invoke-NativeWebGoalTool([string]$Executable,[string[]]$Arguments,[string]$Directory,[string]$Label) {
    $safe=Resolve-PortableSafetyPath $Directory;$temp=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    if (-not $safe.StartsWith($temp.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'WEB_GOAL_TOOL_SCOPE'}
    $child=Start-NativeOwned $Executable $Arguments $Directory
    try {
        Wait-NativeCondition {$child.process.HasExited} 60 'WEB_GOAL_TOOL_TIMEOUT'
        Save-NativeOutput $child $Directory $Label
        return [pscustomobject]@{identity=$child.identity;arguments=$Arguments;executable=$Executable;exitCode=$child.process.ExitCode;
            stdout=$child.stdout.GetAwaiter().GetResult();stderr=$child.stderr.GetAwaiter().GetResult()}
    } finally {
        if (-not $child.process.HasExited) {Stop-ColdRetainedProcess $child.process $child.identity $Directory};$child.process.Dispose()
    }
}

# SourcePins - точный independently approved relative-path -> SHA map, не автоматически доверенный HEAD.
function New-NativeWebGoalBridgeRuntime([string]$Java,[string]$JavaSha256,[string]$Javac,[string]$JavacSha256,
    [string]$CoreJar,[string]$CoreSha256,[hashtable]$SourcePins) {
    foreach ($pin in @(@{path=$Java;sha=$JavaSha256},@{path=$Javac;sha=$JavacSha256},@{path=$CoreJar;sha=$CoreSha256})) {
        [void](Resolve-PortableSafetyPath $pin.path)
        if ($pin.sha -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'WEB_GOAL_INPUT_PIN'}
    }
    $relative=@(Get-NativeWebGoalBridgeSources)
    if ($SourcePins.Count -ne $relative.Count -or @($SourcePins.Keys | Where-Object {$_ -cnotin $relative}).Count) {throw 'WEB_GOAL_SOURCE_SET'}
    $directory=Join-Path ([IO.Path]::GetTempPath()) ('cp-web-goal-runtime-'+[guid]::NewGuid().ToString())
    [void][IO.Directory]::CreateDirectory($directory)
    $core=Join-Path $directory 'core.jar';[IO.File]::Copy($CoreJar,$core,$false)
    $classes=Join-Path $directory 'classes';[void][IO.Directory]::CreateDirectory($classes)
    $pins=[Collections.Generic.List[object]]::new();$copies=[Collections.Generic.List[string]]::new()
    $base=Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity'
    foreach ($name in $relative) {
        $original=Join-Path $base $name;[void](Resolve-PortableSafetyPath $original)
        if ((Get-FileHash -LiteralPath $original).Hash.ToLowerInvariant() -cne $SourcePins[$name]) {throw 'WEB_GOAL_SOURCE_PIN'}
        $copy=Join-Path (Join-Path $directory 'sources') $name
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $copy));[IO.File]::Copy($original,$copy,$false)
        $pins.Add([pscustomobject]@{path=$copy;sha=$SourcePins[$name]});$copies.Add($copy)
    }
    $command=Invoke-NativeWebGoalTool $Javac (@('-encoding','UTF-8','-cp',$core,'-d',$classes)+@($copies.ToArray())) $directory 'javac'
    if ($command.exitCode -ne 0) {throw ('WEB_GOAL_JAVAC:'+ $command.stderr)}
    if ((Get-FileHash -LiteralPath $Javac).Hash.ToLowerInvariant() -cne $JavacSha256) {throw 'WEB_GOAL_JAVAC_PIN'}
    $classPins=@(Get-ChildItem -LiteralPath $classes -Recurse -File | ForEach-Object {
        [pscustomobject]@{path=$_.FullName;sha=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
    $runtime=[pscustomobject]@{directory=$directory;java=$Java;javaSha256=$JavaSha256;core=$core;coreSha256=$CoreSha256;
        classes=$classes;classpath=$core+[IO.Path]::PathSeparator+$classes;sourcePins=@($pins.ToArray());classPins=$classPins;compile=$command;nativePass=$false}
    Assert-NativeWebGoalRuntime $runtime
    $receipt=Join-Path $directory 'runtime.json';Write-ColdJson $receipt $runtime
    $runtime | Add-Member receipt $receipt
    $runtime | Add-Member receiptSha256 ((Get-FileHash -LiteralPath $receipt).Hash.ToLowerInvariant())
    return $runtime
}

# Callback вызывается после native PARITY_URL, ДО ожидания UI receipt и до actual DOM shot.
function Start-NativeWebGoalRestorationBridge($Probe,$Native,$Runtime,[string]$Edge,[string]$EdgeSha256,[int]$Timeout=55) {
    if ($Probe.context.client -cne 'web' -or -not $Native.web -or -not $Native.webUrl -or -not $Probe.observer.launchAt -or
        $Timeout -lt 10 -or $Timeout -gt 55) {throw 'WEB_GOAL_ATTACH_ORDER'}
    if ($Native.webUrl -cnotmatch '^http://127\.0\.0\.1:([1-9][0-9]{0,4})/\?t=[A-Za-z0-9_-]+$' -or [int]$Matches[1] -gt 65535) {throw 'WEB_GOAL_ADDRESS'}
    Assert-NativeWebGoalRuntime $Runtime
    $Edge=Resolve-PortableSafetyPath $Edge;[void](Resolve-PortableSafetyPath $Probe.observer.output)
    if ($EdgeSha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $Edge).Hash.ToLowerInvariant() -cne $EdgeSha256) {throw 'WEB_GOAL_EDGE_PIN'}
    $encode={param([string]$value) [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))}
    $arguments=@('-XX:-UsePerfData','-cp',$Runtime.classpath,'ru.cashprediction.parity.portable.NativeWebGoalRestorationBridge',
        (& $encode $Probe.observer.output),(& $encode $Native.webUrl),(& $encode $Edge),[string]$Timeout)
    # Compiled runtime можно разделять между cells, но worker logs/identity receipts всегда принадлежат одной cell.
    $workerDirectory=Join-Path $Probe.context.directory 'web-cdp-worker'
    [void](Resolve-PortableSafetyPath $workerDirectory)
    if (Test-Path -LiteralPath $workerDirectory) {throw 'WEB_GOAL_WORKER_EVIDENCE_EXISTS'}
    [void][IO.Directory]::CreateDirectory($workerDirectory)
    $worker=Start-NativeOwned $Runtime.java $arguments $workerDirectory
    $state=[pscustomobject]@{worker=$worker;workerDirectory=$workerDirectory;runtime=$Runtime;output=$Probe.observer.output;profile=(Join-Path $Probe.observer.output 'browser-profile');
        ownerFile=(Join-Path $Probe.observer.output 'browser-owner.json');edge=$Edge;edgeSha256=$EdgeSha256;browserProcess=$null;browserIdentity=$null;nativePass=$false}
    $Probe | Add-Member webBridge $state -Force
    Write-ColdJson (Join-Path $workerDirectory 'bridge-launch.json') ([ordered]@{arguments=$arguments;identity=$worker.identity;output=$state.output;
        edgeSha256=$EdgeSha256;runtimeReceipt=$Runtime.receipt;runtimeReceiptSha256=$Runtime.receiptSha256;
        nativeLaunchAt=$Probe.observer.launchAt;nativePass=$false})
    Wait-NativeCondition {
        if ($worker.process.HasExited) {throw 'WEB_GOAL_BRIDGE_EARLY_EXIT'}
        return Test-Path -LiteralPath (Join-Path $state.output 'bridge-attached.json') -PathType Leaf
    } 40 'WEB_GOAL_ATTACH_TIMEOUT'
    $attached=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-RestoredWindowBytes (Join-Path $state.output 'bridge-attached.json') $state.output)))
    if ($attached.status -cne 'ACTUAL_CDP_ATTACHED' -or $attached.bridgePid -ne $worker.identity.ProcessId -or
        ((Get-ColdUtcTicks $attached.bridgeStartedAt)-((Get-ColdUtcTicks $attached.bridgeStartedAt)%10)) -ne ($worker.identity.StartedAtTicks-($worker.identity.StartedAtTicks%10)) -or
        $attached.profile -cne $state.profile -or $attached.executable -cne $Edge -or $attached.viewport -cne '1200x800') {throw 'WEB_GOAL_ATTACH_IDENTITY'}
    $owned=@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -ieq $Edge -and
        (Test-NativeWebGoalProfileArgument $_.CommandLine $state.profile) -and $_.CommandLine -notmatch '(?:^|\s)--type='})
    if ($owned.Count -ne 1) {throw 'WEB_GOAL_BROWSER_CENSUS'}
    $pidValue=Convert-NativeWebGoalCimPid $owned[0].ProcessId
    $browser=Open-PortableProcess $pidValue;$state.browserProcess=$browser
    $identity=Get-ColdProcessReceipt $browser $state.profile;$state.browserIdentity=$identity
    if ($identity.StartedAtTicks -lt (Get-ColdUtcTicks $Probe.observer.launchAt) -or $identity.ExecutablePath -cne $Edge -or
        ($owned[0].CreationDate.ToUniversalTime().Ticks-($owned[0].CreationDate.ToUniversalTime().Ticks%10)) -ne
        ($identity.StartedAtTicks-($identity.StartedAtTicks%10))) {throw 'WEB_GOAL_BROWSER_IDENTITY'}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $browser $state.profile) $state.profile $Edge
    return $state
}

# Дополняет window sampler настоящим browser retained identity; missing не становится helper-return PASS.
function Read-NativeWebGoalRestorationObservation($Probe) {
    $state=$Probe.webBridge
    Assert-NativeWebGoalRuntime $state.runtime
    if ($state.worker.process.HasExited -or $state.browserProcess.HasExited) {throw 'WEB_GOAL_OBSERVER_EXITED'}
    Assert-ColdProcessIdentity $state.worker.identity (Get-ColdProcessReceipt $state.worker.process $state.workerDirectory) $state.workerDirectory $state.runtime.java
    Assert-ColdProcessIdentity $state.browserIdentity (Get-ColdProcessReceipt $state.browserProcess $state.profile) $state.profile $state.edge
    if ((Get-FileHash -LiteralPath $state.edge).Hash.ToLowerInvariant() -cne $state.edgeSha256) {throw 'WEB_GOAL_EDGE_CHANGED'}
    $shotFile=Join-Path $state.output 'bridge-shot.json'
    if (-not (Test-Path -LiteralPath $shotFile -PathType Leaf)) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_CDP_SHOT_NOT_DELIVERED')}
    $shot=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-RestoredWindowBytes $shotFile $state.output)))
    if ($shot.status -cne 'DOM_SHOT_DELIVERED_NOT_NATIVE_PASS' -or -not (Test-ColdInteger $shot.deliveredSteps 1) -or
        $shot.deliveredSteps -ne 1 -or (Get-ColdUtcTicks $shot.observedAt) -lt (Get-ColdUtcTicks $Probe.observer.launchAt)) {throw 'WEB_GOAL_SHOT_CONTRADICTION'}
    [void](Read-RestoredWindowBytes (Join-Path $state.output 'phase-restored/restored.raw.json') $state.output $shot.rawSha256)
    [void](Read-RestoredWindowBytes (Join-Path $state.output 'phase-restored/restored.png') $state.output $shot.pngSha256)
    $decision=Read-NativeGoalPostTransactionObservation $Probe $state.browserProcess $state.ownerFile
    return $decision
}

# Stop просит actual worker закрыть CDP/своё browser tree; не считается ordinary native exit.
function Stop-NativeWebGoalRestorationBridge($State) {
    if ($null -ne $State.browserProcess -and -not $State.browserProcess.HasExited) {
        Assert-ColdProcessIdentity $State.browserIdentity (Get-ColdProcessReceipt $State.browserProcess $State.profile) $State.profile $State.edge
    }
    if (-not $State.worker.process.HasExited) {
        Assert-ColdProcessIdentity $State.worker.identity (Get-ColdProcessReceipt $State.worker.process $State.workerDirectory) $State.workerDirectory $State.runtime.java
        $stop=Join-Path $State.output 'web-bridge.stop'
        [void](Resolve-PortableSafetyPath $stop)
        $stream=[IO.File]::Open($stop,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$stream.Dispose()
        Wait-NativeCondition {$State.worker.process.HasExited} 55 'WEB_GOAL_CLEANUP_TIMEOUT'
    }
    Save-NativeOutput $State.worker $State.workerDirectory 'bridge'
    $file=Join-Path $State.output 'bridge-cleanup.json'
    $cleanup=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-RestoredWindowBytes $file $State.output)))
    $remaining=@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -ieq $State.edge -and
        (Test-NativeWebGoalProfileArgument $_.CommandLine $State.profile)})
    if ($State.worker.process.ExitCode -ne 0 -or $cleanup.cleanupStatus -cne 'OWNED_BROWSER_CLOSED' -or
        $cleanup.profileRemoved -isnot [bool] -or -not $cleanup.profileRemoved -or $remaining.Count -or
        ($null -ne $State.browserProcess -and -not $State.browserProcess.HasExited) -or
        (Test-Path -LiteralPath $State.profile)) {throw 'WEB_GOAL_CLEANUP_FAILED'}
    $receipt=[pscustomobject]@{receipt=$file;sha256=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant();
        worker=$State.worker.identity;actualExit=$State.worker.process.ExitCode;remainingProfileProcesses=0;nativePass=$false;ordinaryExitProven=$false}
    if ($null -ne $State.browserProcess) {$State.browserProcess.Dispose()};$State.worker.process.Dispose()
    return $receipt
}
