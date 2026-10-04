<#
.SYNOPSIS
Own Temp javac/JDK pure fixtures actual bridge classes; не запускает Edge/CDP/HTTP/native.
#>
[CmdletBinding()]param([Parameter(Mandatory)][string]$Java,[Parameter(Mandatory)][string]$JavaSha256,
    [Parameter(Mandatory)][string]$Javac,[Parameter(Mandatory)][string]$JavacSha256,
    [Parameter(Mandatory)][string]$CoreJar,[Parameter(Mandatory)][string]$CoreSha256)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$imports=@{'Test-Portable.ps1'=@('Resolve-PortableSafetyPath');
    'Test-UpdateBootstrap.ps1'=@('Test-ColdInteger','Assert-ColdProcessIdentity','Get-ColdProcessReceipt','Stop-ColdRetainedProcess',
        'Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson','Write-ColdJson');
    'Test-NativeUpdateLifecycle.ps1'=@('Start-NativeOwned','Wait-NativeCondition','Save-NativeOutput')}
foreach ($file in $imports.Keys) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
    foreach ($name in $imports[$file]) {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($errors.Count -or $nodes.Count -ne 1) {throw 'WEB_GOAL_FIXTURE_IMPORT'};. ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
. (Join-Path $PSScriptRoot 'NativeUpdateWebGoalRestorationBridge.ps1')
$script:checks=0
# Все mocks/pure проверки подтверждают только guards, не actual DOM или native PASS.
function Assert-WebGoalFixture([bool]$Condition,[string]$Label) {
    if (-not $Condition) {throw ('WEB_GOAL_FIXTURE:'+ $Label)};$script:checks++
}
$profile='C:\Temp\own profile\browser-profile'
foreach ($command in @('edge "--user-data-dir='+$profile+'" other','edge --user-data-dir="'+$profile+'" other')) {
    Assert-WebGoalFixture (Test-NativeWebGoalProfileArgument $command $profile) 'quoted exact profile'
}
Assert-WebGoalFixture (-not (Test-NativeWebGoalProfileArgument ('edge "--user-data-dir='+$profile+'-foreign"') $profile)) 'no prefix profile'
Assert-WebGoalFixture (-not (Test-NativeWebGoalProfileArgument ('edge --other="'+$profile+'"') $profile)) 'no substring'
Assert-WebGoalFixture (Test-NativeWebGoalProfileArgument 'edge --user-data-dir=C:\Temp\profile other' 'C:\Temp\profile') 'unquoted exact'
Assert-WebGoalFixture ((Convert-NativeWebGoalCimPid ([uint32]14004)) -eq 14004) 'actual CIM uint32'
foreach ($bad in @(0,[uint32]2147483648,$true,'14004',1.0)) {
    $rejected=$false;try {[void](Convert-NativeWebGoalCimPid $bad)} catch {$rejected=$_.Exception.Message -ceq 'WEB_GOAL_CIM_PID'}
    Assert-WebGoalFixture $rejected 'reject invalid CIM PID'
}
$base=Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity'
$pins=@{};foreach ($name in Get-NativeWebGoalBridgeSources) {$pins[$name]=(Get-FileHash -LiteralPath (Join-Path $base $name)).Hash.ToLowerInvariant()}
$runtime=New-NativeWebGoalBridgeRuntime $Java $JavaSha256 $Javac $JavacSha256 $CoreJar $CoreSha256 $pins
Assert-WebGoalFixture ($runtime.compile.exitCode -eq 0 -and -not $runtime.nativePass) 'actual javac existing APIs'
$fixture=Join-Path $base 'portable/NativeWebGoalRestorationBridgeFixture.java'
$fixturePin=(Get-FileHash -LiteralPath $fixture).Hash.ToLowerInvariant()
$copy=Join-Path $runtime.directory 'NativeWebGoalRestorationBridgeFixture.java';[IO.File]::Copy($fixture,$copy,$false)
$fixtureClasses=Join-Path $runtime.directory 'fixture-classes';[void][IO.Directory]::CreateDirectory($fixtureClasses)
$compiled=Invoke-NativeWebGoalTool $Javac @('-encoding','UTF-8','-cp',$runtime.classpath,'-d',$fixtureClasses,$copy) $runtime.directory 'fixture-javac'
Assert-WebGoalFixture ($compiled.exitCode -eq 0) ('fixture compile '+$compiled.stderr)
$execution=Invoke-NativeWebGoalTool $Java @('-XX:-UsePerfData','-cp',($runtime.classpath+[IO.Path]::PathSeparator+$fixtureClasses),
    'ru.cashprediction.parity.portable.NativeWebGoalRestorationBridgeFixture',$runtime.directory) $runtime.directory 'fixture-java'
Assert-WebGoalFixture ($execution.exitCode -eq 0 -and $execution.stdout.Trim() -ceq 'WEB_GOAL_PURE_FIXTURES=18;NATIVE_EXECUTED=false;ACTUAL_DOM=PENDING') ('pure Java guards '+$execution.stderr)
Assert-NativeWebGoalRuntime $runtime
Assert-WebGoalFixture ((Get-FileHash -LiteralPath $fixture).Hash.ToLowerInvariant() -ceq $fixturePin) 'fixture source unchanged'
# Испорченные class bytes нельзя использовать при real Start; меняется лишь собственная Temp copy.
$class=$runtime.classPins[0].path;$bytes=[IO.File]::ReadAllBytes($class);$bytes[0]=$bytes[0] -bxor 1;[IO.File]::WriteAllBytes($class,$bytes)
$rejected=$false;try {Assert-NativeWebGoalRuntime $runtime} catch {$rejected=$_.Exception.Message -ceq 'WEB_GOAL_RUNTIME_PIN'}
Assert-WebGoalFixture $rejected 'changed class rejected'
$tokens=$null;$errors=$null
$goal=Join-Path $PSScriptRoot 'NativeUpdateGoalRestorationProbe.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($goal,[ref]$tokens,[ref]$errors)
$start=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Start-NativeGoalPostTransactionClient'},$true))[0].Extent.Text
Assert-WebGoalFixture ($errors.Count -eq 0 -and $start.IndexOf('& $WebBridgeAttach $Probe $native') -gt $start.IndexOf("'GOAL_WEB_URL_TIMEOUT'") -and
    $start.IndexOf('& $WebBridgeAttach $Probe $native') -lt $start.IndexOf('$native.ui=Get-ColdUiReceipt')) 'attach before UI wait after handshake'
$result=[ordered]@{status='PURE_GUARDS_AND_REAL_JDK_COMPILE_ONLY';checks=$script:checks;javaPureChecks=18;actualDom='PENDING';nativeExecuted=$false;runtimeReusable=$false;
    javaSha256=$JavaSha256;javacSha256=$JavacSha256;coreSha256=$CoreSha256;sourcePins=$pins;
    compile=$runtime.compile;fixtureCompile=$compiled;fixtureExecution=$execution;diagnostics=$runtime.directory}
Write-ColdJson (Join-Path $runtime.directory 'fixture-results.json') $result
[pscustomobject]@{checks=$script:checks;javaPureChecks=18;nativeExecuted=$false;diagnostics=$runtime.directory} | ConvertTo-Json -Compress
