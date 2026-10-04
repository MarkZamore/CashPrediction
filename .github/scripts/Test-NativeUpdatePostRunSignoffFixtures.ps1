#requires -Version 7.0
<#
.SYNOPSIS
Focused post-run wrapper fixtures: own Temp, AST, реальные pin readers, scoped mocks.
.DESCRIPTION
Ни native products, ни JDK CLI, ни Maven не запускаются. Полные accepted rows
только в памяти через scoped mock merge; finalizer заменён throwing sentinel.
Диск содержит PENDING results и dummy metadata, никогда native PASS publication.
#>
[CmdletBinding()]param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0

# Считает только focused mock проверки.
function Assert-PostRunFixture([bool]$Condition,[string]$Name) {
    if (-not $Condition) {throw "POSTRUN_FIXTURE_ASSERT $Name"};$script:checks++
}

# Требует точный отказ без получения native verdict.
function Reject-PostRunFixture([scriptblock]$Action,[string]$Code) {
    $message='';try {& $Action | Out-Null} catch {$message=$_.Exception.Message}
    Assert-PostRunFixture ($message -ceq $Code) ('reject '+$Code+' actual='+$message)
}

# Копирует JSON wire object, не запускает старые fixture suites.
function Copy-PostRunFixture($Object) {
    $options=@{InputObject=(ConvertTo-Json -InputObject $Object -Depth 64);Depth=64;NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
    return ,(ConvertFrom-Json @options)
}

$owned=Join-Path ([IO.Path]::GetTempPath()) ('cp-postrun-signoff-fixtures-'+[guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $owned
$copies=@('Invoke-NativeUpdatePostRunSignoff.ps1','Test-NativeUpdateEvidenceSignoff.ps1',
    'Test-NativeUpdateLifecycle.ps1','NativeUpdateAcceptanceDispatch.ps1')
$sourcePins=@(foreach ($name in $copies) {
    $source=Join-Path $PSScriptRoot $name;$before=(Get-FileHash -LiteralPath $source).Hash
    $destination=Join-Path $owned $name;Copy-Item -LiteralPath $source -Destination $destination
    Assert-PostRunFixture ($before -ceq (Get-FileHash -LiteralPath $source).Hash -and $before -ceq (Get-FileHash -LiteralPath $destination).Hash) ('stable copy '+$name)
    [pscustomobject]@{path=$destination;sha256=$before.ToLowerInvariant()}
})
foreach ($name in 'UpdateEvidence.java','UpdateEvidenceVerifier.java') {
    $source=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../../ui-parity/src/test/java/ru/cashprediction/parity/update/'+$name)))
    $before=(Get-FileHash -LiteralPath $source).Hash;$destination=Join-Path $owned $name
    Copy-Item -LiteralPath $source -Destination $destination
    Assert-PostRunFixture ($before -ceq (Get-FileHash -LiteralPath $source).Hash -and $before -ceq (Get-FileHash -LiteralPath $destination).Hash) ('stable copy '+$name)
    $sourcePins+=([pscustomobject]@{path=$destination;sha256=$before.ToLowerInvariant()})
}
. (Join-Path $owned 'Invoke-NativeUpdatePostRunSignoff.ps1')
$helperPin=$sourcePins[1]
$realFactory=${function:New-PostRunSignoffModule}
$wire=New-PostRunSignoffModule $helperPin.path $helperPin.sha256
try {
    # Вспомогательный module читает и пишет только bounded mock input files в own Temp.
    $core=& $wire {param($root) Write-SignoffNew (Join-Path $root 'core.jar') ([pscustomobject]@{kind='MOCK_NOT_JAR'})} $owned
    $tool=& $wire {param($root) Write-SignoffNew (Join-Path $root 'tool.jar') ([pscustomobject]@{kind='MOCK_NOT_JAR'})} $owned
    $helper=& $wire {param($root) Write-SignoffNew (Join-Path $root 'helper.json') ([pscustomobject]@{kind='MOCK_ONLY'})} $owned
    $target=[pscustomobject]@{commitSha=('b'*40);releaseNumber=1003;treeSha256=('c'*64);files=@()}
    $base1=[pscustomobject]@{commitSha=('a'*40);releaseNumber=1001};$base2=[pscustomobject]@{commitSha=('d'*40);releaseNumber=1002}
    $targetPin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'update.json') $obj} $owned $target
    $b1=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'b1.json') $obj} $owned $base1
    $b2=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'b2.json') $obj} $owned $base2
    $cold=[pscustomobject]@{schemaVersion=1;runtimeSha256=('a'*64);helperScript=$helper.path;helperSha256=$helper.sha256;
        toolFiles=@($core,$tool);toolArguments=@('--module-path',($core.path+';'+$tool.path),'-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool');
        baseManifests=@([pscustomobject]@{manifest=$b1.path;sha256=$b1.sha256},[pscustomobject]@{manifest=$b2.path;sha256=$b2.sha256});
        targetManifest=$targetPin.path;targetManifestSha256=$targetPin.sha256}
    $life=[pscustomobject]@{schemaVersion=1;artifactDir=$owned;manifestSha256=$targetPin.sha256;harnessClasspath=$core.path;harnessFiles=@($core)}
    $coldPin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'cold.json') $obj} $owned $cold
    $lifePin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'life.json') $obj} $owned $life
    $context=[pscustomobject]@{schemaVersion=1;sourceIdentity=[guid]::NewGuid().ToString();freshAfterUtc=[datetime]::UtcNow.AddMinutes(-1).ToString('o');
        sourcePins=$sourcePins;coldPin=$coldPin;lifePin=$lifePin;planSourcePin=$sourcePins[4];planReaderPin=$sourcePins[2]}
    $digest=& $wire {param($obj) Get-SignoffDigest $obj} $context
    $plan=@(& $wire {param($ctx) Get-SignoffPlan $ctx @{}} $context)
    Assert-PostRunFixture ($plan.Count -eq 612 -and @($plan.scenario | Sort-Object -Unique).Count -eq 22) 'actual canonical22/612'
    $resultPin=& $wire {param($root,$rows) Write-SignoffNew (Join-Path $root 'batch-results.json') ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=$rows})} $owned $plan
    $seal=[pscustomobject]@{schemaVersion=1;scope='FROZEN_NATIVE_BATCH';sourceIdentity=$context.sourceIdentity;contextSha256=$digest;
        evidenceRoot=$owned;sealedAt=[datetime]::UtcNow.ToString('o');results=$resultPin;proofs=@()}
    $sealPin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'empty-proof-seal.json') $obj} $owned $seal
    $request=[pscustomobject]@{schemaVersion=1;context=$context;batches=@($sealPin);verifier=[pscustomobject]@{};
        outputDirectory=(Join-Path ([IO.Path]::GetTempPath()) ('cp-native-signoff-'+[guid]::NewGuid().ToString()))}
    $requestPin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'postrun-request.json') $obj} $owned $request
    $invokePins=@{RequestFile=$requestPin.path;RequestSha256=$requestPin.sha256;FinalizerSha256=$helperPin.sha256;
        ExpectedSourceIdentity=$context.sourceIdentity;ExpectedContextSha256=$digest;ExpectedBaseCommit=@($base1.commitSha,$base2.commitSha);ExpectedTargetCommit=$target.commitSha}
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins} 'SIGNOFF_BATCH_PROOFS'
    $bad=$invokePins.Clone();$bad.RequestSha256='f'*64
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'SIGNOFF_CHANGED'
    $bad=$invokePins.Clone();$bad.FinalizerSha256='f'*64
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_HELPER_CHANGED'
    $bad=$invokePins.Clone();$bad.ExpectedSourceIdentity=[guid]::NewGuid().ToString()
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_SOURCE_EPOCH'
    $bad=$invokePins.Clone();$bad.ExpectedContextSha256='f'*64
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_SOURCE_EPOCH'
    $bad=$invokePins.Clone();$bad.ExpectedTargetCommit='e'*40
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_COMMIT_MISMATCH'
    $bad=$invokePins.Clone();$bad.ExpectedBaseCommit=@($base2.commitSha,$base1.commitSha)
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_COMMIT_MISMATCH'
    $bad=$invokePins.Clone();$bad.ExpectedBaseCommit=@($base1.commitSha,$base1.commitSha)
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_COMMIT_PIN'
    $bad=$invokePins.Clone();$bad.RequestSha256='F'*64
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_CALLER_PIN'
    $bad=$invokePins.Clone();$bad.RequestFile='relative-request.json'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'SIGNOFF_PATH'
    $requestMissingHelper=Copy-PostRunFixture $request
    $requestMissingHelper.context.sourcePins=@($requestMissingHelper.context.sourcePins | Where-Object {$_.path -cne $helperPin.path})
    $missingHelperPin=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'missing-helper-request.json') $obj} $owned $requestMissingHelper
    $bad=$invokePins.Clone();$bad.RequestFile=$missingHelperPin.path;$bad.RequestSha256=$missingHelperPin.sha256
    $bad.ExpectedContextSha256=& $wire {param($obj) Get-SignoffDigest $obj} $requestMissingHelper.context
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_SOURCE_INVENTORY'
    $helperText=[IO.File]::ReadAllText($helperPin.path)
    foreach ($case in @(
        @{name='bad-parse';code='POSTRUN_HELPER_PARSE';text='function Broken {'},
        @{name='missing-export';code='POSTRUN_HELPER_EXPORT';text='function Read-SignoffJson {}'},
        @{name='duplicate';code='POSTRUN_HELPER_EXPORT';text=($helperText+"`nfunction Read-SignoffJson {}")},
        @{name='wrong-signature';code='POSTRUN_HELPER_SIGNATURE';text=$helperText.Replace('function Invoke-NativeUpdateEvidenceSignoff([string]$RequestFile','function Invoke-NativeUpdateEvidenceSignoff([int]$RequestFile')},
        @{name='alias';code='POSTRUN_HELPER_SIGNATURE';text=$helperText.Replace('function Invoke-NativeUpdateEvidenceSignoff([string]$RequestFile',"function Invoke-NativeUpdateEvidenceSignoff([Alias('Wrong')][string]`$RequestFile")}
    )) {
        $testPin=& $wire {param($root,$name,$text) Write-SignoffBytesNew (Join-Path $root ($name+'.ps1')) ([Text.Encoding]::UTF8.GetBytes($text)); New-SignoffPin (Join-Path $root ($name+'.ps1'))} $owned $case.name $case.text
        Reject-PostRunFixture {New-PostRunSignoffModule $testPin.path $testPin.sha256} $case.code
    }
    $bodyPin=& $wire {param($root,$text) Write-SignoffBytesNew (Join-Path $root 'body-forbidden.ps1') ([Text.Encoding]::UTF8.GetBytes($text+"`nthrow 'BODY_MUST_NOT_RUN'`n")); New-SignoffPin (Join-Path $root 'body-forbidden.ps1')} $owned $helperText
    $bodyModule=New-PostRunSignoffModule $bodyPin.path $bodyPin.sha256
    try {Assert-PostRunFixture ($bodyModule.ExportedFunctions.ContainsKey('Invoke-NativeUpdateEvidenceSignoff')) 'helper runner body not invoked'}
    finally {Remove-Module -ModuleInfo $bodyModule -Force}
    $request2=Copy-PostRunFixture $request
    $request2.context.sourcePins=@($request2.context.sourcePins | Where-Object {$_.path -cne $sourcePins[0].path})
    $pin2=& $wire {param($root,$obj) Write-SignoffNew (Join-Path $root 'missing-wrapper-request.json') $obj} $owned $request2
    $bad=$invokePins.Clone();$bad.RequestFile=$pin2.path;$bad.RequestSha256=$pin2.sha256
    $bad.ExpectedContextSha256=& $wire {param($obj) Get-SignoffDigest $obj} $request2.context
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @bad} 'POSTRUN_SOURCE_INVENTORY'

    # Только fixture factory подменяет merge/plan/CLI внутри нового изолированного module.
    $script:mode='partial'
    function New-PostRunSignoffModule([string]$HelperFile,[string]$HelperSha256) {
        $module=& $script:realFactory $HelperFile $HelperSha256
        & $module {
            param($mode)
            $script:fixtureMode=$mode;$script:originalPlan=${function:Get-SignoffPlan}
            function script:Get-SignoffPlan($Context,$Seen) {
                $rows=@(& $script:originalPlan $Context $Seen)
                if ($script:fixtureMode -ceq '611') {return $rows[0..610]}
                if ($script:fixtureMode -ceq 'duplicate') {$rows[611]=$rows[0]}
                return $rows
            }
            function script:Merge-NativeEvidenceBatches($Context,$Batches,$Plan,$Config,$Seen) {
                if ($script:fixtureMode -ceq 'partial') {return [pscustomobject]@{rows=@();missing=@($Plan | ForEach-Object {Get-SignoffCellKey $_})}}
                if ($script:fixtureMode -ceq 'row611') {return [pscustomobject]@{rows=@($Plan[0..610]);missing=@()}}
                return [pscustomobject]@{rows=$Plan;missing=@()}
            }
            function script:Invoke-NativeUpdateEvidenceSignoff([string]$RequestFile,[string]$RequestSha256,[switch]$Signoff) {
                if (-not $Signoff) {throw 'MOCK_SIGNOFF_SWITCH_LOST'}
                throw 'MOCK_FINALIZER_CALLED_NO_CLI_NO_NATIVE_PASS'
            }
        } $script:mode
        return $module
    }
    $preflight=Invoke-NativeUpdatePostRunSignoff @invokePins
    Assert-PostRunFixture ($preflight.status -ceq 'PENDING' -and $preflight.accepted -eq 0 -and $preflight.missing.Count -eq 612 -and
        -not $preflight.finalizerInvoked -and -not $preflight.nativeProductsExecuted -and $null -eq $preflight.results) 'partial pending default'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins -Signoff} 'POSTRUN_INCOMPLETE_612'
    $script:mode='611'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins -Signoff} 'POSTRUN_PLAN_COUNT'
    $script:mode='duplicate'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins -Signoff} 'POSTRUN_PLAN_DUPLICATE'
    $script:mode='row611'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins -Signoff} 'POSTRUN_INCOMPLETE_612'
    $script:mode='full'
    $preflight=Invoke-NativeUpdatePostRunSignoff @invokePins
    Assert-PostRunFixture ($preflight.status -ceq 'PENDING' -and $preflight.accepted -eq 612 -and -not $preflight.finalizerInvoked -and
        $null -eq $preflight.results -and $preflight.gap -ceq 'EXPLICIT_POSTRUN_SIGNOFF_REQUIRED') 'full mock remains pending default'
    Reject-PostRunFixture {Invoke-NativeUpdatePostRunSignoff @invokePins -Signoff} 'MOCK_FINALIZER_CALLED_NO_CLI_NO_NATIVE_PASS'
    Assert-PostRunFixture (-not (Test-Path -LiteralPath $request.outputDirectory)) 'no finalizer output even with mocks'
    Assert-PostRunFixture ((Get-FileHash -LiteralPath $resultPin.path).Hash.ToLowerInvariant() -ceq $resultPin.sha256) 'frozen pending results unchanged'
    Assert-PostRunFixture (@(Get-Module | Where-Object {$_.ExportedFunctions.ContainsKey('Invoke-NativeUpdateEvidenceSignoff')}).Count -eq 0) 'no imported ambient module'
    [pscustomobject]@{status='PASS';checks=$script:checks;scope='POSTRUN_PIN_AST_AND_MOCK_ONLY';native=$false;cli=$false;
        fullMatrix='PENDING';PSVersion=$PSVersionTable.PSVersion.ToString();ownedTemp=$owned;sourcePins=$sourcePins} | ConvertTo-Json -Depth 6 -Compress
} finally {
    Remove-Module -ModuleInfo $wire -Force -ErrorAction Stop
    # Срез сохраняет own Temp как MOCK_ONLY diagnostics; shared targets и чужие Temp не удаляются.
}
