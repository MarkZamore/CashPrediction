<# .SYNOPSIS Проверяет guards builder без Maven, Java, exe и GUI; не подтверждает нативную сборку. #>
#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$script:CandidateSourceValidator=Join-Path $PSScriptRoot '../../dist/scripts/Pack-Source.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'New-UpdateCandidateImages.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'CANDIDATE_FIXTURE_PARSE'}
foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
# Cross-builder проверяет реальные frozen guards, импортируя только два определения, без запуска builders.
foreach ($contract in @(@('New-NativeUpdateArtifacts.ps1','Assert-ArtifactBases'),@('Test-UpdateBootstrap.ps1','Test-ColdInteger'))) {
    $contractAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $contract[0]),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'CANDIDATE_ARTIFACT_CONTRACT_PARSE'}
    $definitions=@($contractAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $contract[1]},$true))
    if ($definitions.Count -ne 1) {throw 'CANDIDATE_ARTIFACT_CONTRACT_MISSING'}
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$checks=0
# Отказ должен совпадать с конкретным guard, а не с посторонней ошибкой mock-окружения.
function Assert-CandidateReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "CANDIDATE_FIXTURE_EXPECTED $Code actual=$caught"}
    $script:checks++
}
# Счётчик включает только независимые утверждения и точные ожидаемые отказы.
function Assert-CandidateFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw "CANDIDATE_FIXTURE_ASSERT $Code"};$script:checks++
}
# Создаёт данные внутри стенда; не компилирует исходник и не запускает исполняемые файлы.
function Write-CandidateFixture([string]$Path,[string]$Text) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
# Минимальное замкнутое delivered дерево для настоящего структурного preflight.
function New-CandidateFixtureSource([string]$Path) {
    $rootPom='<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module></modules><profiles><profile><id>dist</id><modules><module>dist</module></modules></profile><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles></project>'
    Write-CandidateFixture (Join-Path $Path 'pom.xml') $rootPom
    foreach ($module in 'core','update-tool','ui-fx','ui-swing','web','dist','ui-parity') {
        Write-CandidateFixture (Join-Path $Path "$module/pom.xml") '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><relativePath>../pom.xml</relativePath></parent></project>'
        if ($module -notin 'dist','ui-parity') {Write-CandidateFixture (Join-Path $Path "$module/src/main/java/Example.java") '// Только данные fixture.'}
    }
    foreach ($file in 'docs/design/architecture.md','dist/scripts/Set-LauncherUtf8.ps1','dist/scripts/Test-Icon-Source.ps1',
        'dist/icons/make-icon.ps1','dist/launchers/swing.properties','dist/launchers/web.properties',
        '.github/scripts/Test-Portable.ps1','.github/scripts/GhRetry.ps1',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
        'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
        'core/src/main/java/ru/cashprediction/core/update/Example.java') {
        Write-CandidateFixture (Join-Path $Path $file) 'fixture-data'
    }
}
# Минимальный бинарный module-info для mock: не компилируется и не запускается.
function Write-CandidateFixtureModule($Zip,[string]$Module) {
    $prefix='cafebabe0000003d000601000b6d6f64756c652d696e666f0700010100064d6f64756c6501'
    $name=[Text.Encoding]::UTF8.GetBytes($Module)
    $hex=$prefix+('{0:x4}' -f $name.Length)+[Convert]::ToHexString($name)+'130004800000020000000000000000000100030000001000050000000000000000000000000000'
    $entry=$Zip.CreateEntry('module-info.class');$output=$entry.Open()
    try {$output.Write([Convert]::FromHexString($hex))} finally {$output.Dispose()}
}
# Искусственные JAR проверяют guards формата, но не доказывают работоспособность нативного образа.
function Write-CandidateFixtureJar([string]$Jar,[string[]]$Documents,[string]$Module='ru.cashprediction.core',[string]$MainClass='') {
    if (Test-Path -LiteralPath $Jar) {[IO.File]::Delete($Jar)}
    $zip=[IO.Compression.ZipFile]::Open($Jar,[IO.Compression.ZipArchiveMode]::Create)
    try {
        Write-CandidateFixtureModule $zip $Module
        if ($MainClass) {[void]$zip.CreateEntry($MainClass.Replace('.','/')+'.class')}
        foreach ($text in $Documents) {
            $entry=$zip.CreateEntry('ru/cashprediction/core/app.properties')
            $writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false))
            try {$writer.Write($text)} finally {$writer.Dispose()}
        }
    } finally {$zip.Dispose()}
}
# Синтетический нормализованный образ: три cfg, четыре модульных JAR и неисполняемые launcher/runtime.
function Write-CandidateFixtureImage([string]$Image,[int]$Release,[string]$Sha) {
    foreach ($launcher in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') {
        Write-CandidateFixture (Join-Path $Image $launcher) 'mock-not-a-launcher'
    }
    [void][IO.Directory]::CreateDirectory((Join-Path $Image 'app'))
    Write-CandidateFixtureJar (Join-Path $Image 'app/cashprediction-core-1.0.0.jar') @("release=$Release`ncommit=$Sha`n")
    foreach ($pair in @(@('CashPrediction','ui-fx','fx','FxMain'),@('CashPrediction-Swing','ui-swing','swing','SwingMain'),@('CashPrediction-Web','web','web','WebMain'))) {
        $module='ru.cashprediction.'+$pair[2];$main=$module+'.'+$pair[3]
        Write-CandidateFixtureJar (Join-Path $Image ('app/cashprediction-'+$pair[1]+'-1.0.0.jar')) @() $module $main
        $cfg="[Application]`napp.mainmodule=$module/$main`n`n[JavaOptions]`njava-options=-Djpackage.app-version=1.0.$Release`njava-options=--module-path`njava-options=`$APPDIR`n"
        foreach ($option in '-XX:-UsePerfData','-XX:-CreateCoredumpOnCrash','-XX:+SuppressFatalErrorMessage',
            '-XX:-DumpReplayDataOnError','-Duser.language=ru','-Duser.country=RU','-Xmx512m') {$cfg+="java-options=$option`n"}
        if ($pair[1] -ceq 'ui-fx') {$cfg+="java-options=--enable-native-access=javafx.graphics`n"}
        Write-CandidateFixture (Join-Path $Image ('app/'+$pair[0]+'.cfg')) $cfg
    }
    Write-CandidateFixture (Join-Path $Image 'runtime/lib/modules') 'mock-runtime'
}
$root=Join-Path ([IO.Path]::GetTempPath()) ('candidate-fixtures-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($root)
try {
    $localCommits=@(('a'*40),('b'*40),('c'*40))
    $resolved=Resolve-CandidateCommits '' $localCommits
    Assert-CandidateFixture ($resolved.Count -eq 3 -and ($resolved -join ',') -ceq ($localCommits -join ',')) 'LOCAL_COMMIT_ORDER'
    $resolved[0]='d'*40
    Assert-CandidateFixture ($localCommits[0] -ceq ('a'*40)) 'LOCAL_COMMIT_COPY'
    $shared=Resolve-CandidateCommits ('a'*40)
    Assert-CandidateFixture ($shared.Count -eq 3 -and @($shared | Where-Object {$_ -cne ('a'*40)}).Count -eq 0) 'SHARED_COMMIT_COMPATIBILITY'
    Assert-CandidateReject {Resolve-CandidateCommits ''} 'CANDIDATE_COMMITS_REQUIRED'
    Assert-CandidateReject {Resolve-CandidateCommits ('a'*40) $localCommits} 'CANDIDATE_COMMIT_MODE'
    foreach ($wrongCount in @(@(('a'*40)),@(('a'*40),('b'*40)),@(('a'*40),('b'*40),('c'*40),('d'*40)))) {
        Assert-CandidateReject {Resolve-CandidateCommits '' $wrongCount} 'CANDIDATE_COMMIT_COUNT'
    }
    foreach ($badSha in @('',('a'*39),('a'*41),('g'*40),('A'*40),('a'*39+"`n"),('a'*40+"`n"))) {
        Assert-CandidateReject {Resolve-CandidateCommits '' @($localCommits[0],$badSha,$localCommits[2])} 'CANDIDATE_COMMIT_FORMAT'
    }
    Assert-CandidateReject {Resolve-CandidateCommits ('g'*40)} 'CANDIDATE_COMMIT_FORMAT'
    foreach ($duplicates in @(@(('a'*40),('a'*40),('c'*40)),@(('a'*40),('b'*40),('a'*40)),@(('a'*40),('b'*40),('b'*40)))) {
        Assert-CandidateReject {Resolve-CandidateCommits '' $duplicates} 'CANDIDATE_COMMIT_DUPLICATE'
    }
    if ((Assert-CandidatePath $root) -cne $root) {throw 'CANDIDATE_FIXTURE_PATH'};$checks++
    Assert-CandidateReject {Assert-CandidatePath 'relative'} 'CANDIDATE_CANONICAL_PATH'
    Assert-CandidateReject {Assert-CandidatePath ($root+'\..\child')} 'CANDIDATE_CANONICAL_PATH'
    Assert-CandidateReject {Assert-CandidatePath ($root+';foreign')} 'CANDIDATE_CANONICAL_PATH'
    $source=Join-Path $root 'source';[void][IO.Directory]::CreateDirectory($source)
    [IO.File]::WriteAllText((Join-Path $source 'pom.xml'),'<project/>')
    $pins=Get-CandidateSourcePins $source
    if ($pins.Count -ne 1 -or $pins['pom.xml'] -cne (Get-FileHash -LiteralPath (Join-Path $source 'pom.xml')).Hash.ToLowerInvariant()) {throw 'CANDIDATE_FIXTURE_PIN'};$checks++
    [void][IO.Directory]::CreateDirectory((Join-Path $source 'target'))
    Assert-CandidateReject {Get-CandidateSourcePins $source} 'CANDIDATE_SOURCE_HAS_TARGET'
    Assert-CandidateReject {Get-CandidateSourcePins $source -AllowGeneratedTargets} 'CANDIDATE_UNEXPECTED_TARGET'
    $withGenerated=Get-CandidateSourcePins $source -AllowGeneratedTargets -GeneratedTargetPaths @('target')
    if ($withGenerated.Count -ne 1) {throw 'CANDIDATE_FIXTURE_TARGET'};$checks++
    $image=Join-Path $root ([string][char]0x8def)
    [void][IO.Directory]::CreateDirectory((Join-Path $image 'app'))
    foreach ($launcher in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe') {
        # Эти тексты намеренно не являются PE и никогда не запускаются.
        [IO.File]::WriteAllText((Join-Path $image $launcher),'mock-not-a-launcher')
    }
    $sha='a'*40;$jar=Join-Path $image 'app/cashprediction-core-1.0.0.jar'
    Write-CandidateFixtureImage $image 101 $sha
    $version=Assert-CandidateVersion $image 101 $sha
    if ($version.releaseNumber -ne 101 -or $version.commitSha -cne $sha) {throw 'CANDIDATE_FIXTURE_VERSION'};$checks++
    Assert-CandidateReject {Assert-CandidateVersion $image 102 $sha} 'CANDIDATE_VERSION_MISMATCH'
    Assert-CandidateReject {Assert-CandidateVersion $image 101 ('b'*40)} 'CANDIDATE_VERSION_MISMATCH'
    [void][IO.Directory]::CreateDirectory((Join-Path $image 'CashMemory'))
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_USER_FILES'
    [IO.Directory]::Delete((Join-Path $image 'CashMemory'))
    foreach ($text in @("release=101`ncommit=$sha`nrelease=0`n", "release=101`ncommit=$sha`ncommit=local`n")) {
        Write-CandidateFixtureJar $jar @($text)
        Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_INFO_DUPLICATE'
    }
    foreach ($text in @("release=101`ncommit=$sha`nreleas\u0065=0`n", "release=101\`n0`ncommit=$sha`n",
        "release=101`ncommit=$sha`n release=0`n", "release=101`ncommit=$sha`nforeign=x`n", "release=101`n")) {
        Write-CandidateFixtureJar $jar @($text)
        Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_INFO'
    }
    Write-CandidateFixtureJar $jar @("release=101`ncommit=$sha`n", "release=101`ncommit=$sha`n")
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_INFO_DUPLICATE'
    Write-CandidateFixtureJar $jar @("# Заголовок`r`nrelease=101`r`ncommit=$sha`r`n")
    Assert-CandidateFixture ((Assert-CandidateVersion $image 101 $sha).releaseNumber -eq 101) 'CANONICAL_CRLF'

    # Каждый launcher проверяется отдельно, чтобы слабая проверка только FX не проходила fixture.
    foreach ($name in 'CashPrediction','CashPrediction-Swing','CashPrediction-Web') {
        $cfgPath=Join-Path $image ('app/'+$name+'.cfg');$cfg=[IO.File]::ReadAllText($cfgPath)
        foreach ($bad in @(
            $cfg.Replace('java-options=$APPDIR','java-options=$APPDIR\mods'),
            $cfg.Replace('java-options=--module-path',"java-options=--module-path`n"),
            $cfg.Replace('java-options=--module-path','java-options=--module-path=$APPDIR'),
            $cfg.Replace('java-options=--module-path','java-options=-p'),
            $cfg.Replace('java-options=--module-path',"java-options=--module-path`njava-options=`$APPDIR`njava-options=--module-path"),
            $cfg.Replace('[JavaOptions]',"app.classpath=`$APPDIR/foreign.jar`n[JavaOptions]"),
            $cfg.Replace('app.mainmodule=ru.cashprediction.','app.mainmodule=foreign.'),
            ($cfg+"java-options=--patch-module=ru.cashprediction.core=foreign.jar`n"),
            ($cfg+"java-options=-javaagent:foreign.jar`n"),
            ($cfg+"java-options=-cp`njava-options=foreign.jar`n"),
            ($cfg+"[ArgOptions]`narguments=--foreign`n"),
            ($cfg+"java-options=-Xmx512m`n"),
            $cfg.Replace('-Djpackage.app-version=1.0.101','-Djpackage.app-version=1.0.999'),
            $cfg.Replace('[Application]',"[Application]`n[Application]"),
            $cfg.Replace('java-options=$APPDIR','java-options=$APPDIR;foreign'),
            $cfg.Replace('java-options=$APPDIR','java-options=$APPDIR/foreign')
        )) {
            Write-CandidateFixture $cfgPath $bad
            Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_CFG'
        }
        Write-CandidateFixture $cfgPath ($cfg.Replace("`n","`r`n"))
        Assert-CandidateFixture ((Assert-CandidateVersion $image 101 $sha).releaseNumber -eq 101) ('CFG_CRLF_'+$name)
        Write-CandidateFixture $cfgPath $cfg
    }
    $mods=Join-Path $image 'app/mods';[void][IO.Directory]::CreateDirectory($mods)
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_LAYOUT'
    [IO.Directory]::Delete($mods)
    $extra=Join-Path $image 'app/foreign.jar';Write-CandidateFixture $extra 'foreign'
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_LAYOUT';[IO.File]::Delete($extra)
    $webJar=Join-Path $image 'app/cashprediction-web-1.0.0.jar';$webBytes=[IO.File]::ReadAllBytes($webJar)
    $duplicate=Join-Path $image 'app/cashprediction-core-2.jar';[IO.File]::Move($webJar,$duplicate)
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_LAYOUT';[IO.File]::Move($duplicate,$webJar)
    $nested=Join-Path $image 'app/nested';[void][IO.Directory]::CreateDirectory($nested)
    [IO.File]::Move($webJar,(Join-Path $nested 'cashprediction-web-1.0.0.jar'))
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_APP_LAYOUT'
    [IO.File]::Move((Join-Path $nested 'cashprediction-web-1.0.0.jar'),$webJar);[IO.Directory]::Delete($nested)
    Write-CandidateFixtureJar $webJar @() 'ru.cashprediction.foreign' 'ru.cashprediction.web.WebMain'
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_JAR_MODULE'
    Write-CandidateFixtureJar $webJar @() 'ru.cashprediction.web'
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_JAR_MAIN'
    [IO.File]::WriteAllBytes($webJar,$webBytes)
    $archive=[IO.Compression.ZipFile]::Open($webJar,[IO.Compression.ZipArchiveMode]::Update)
    try {$archive.GetEntry('module-info.class').Delete()} finally {$archive.Dispose()}
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_JAR_MODULE'
    [IO.File]::WriteAllBytes($webJar,$webBytes)
    $archive=[IO.Compression.ZipFile]::Open($webJar,[IO.Compression.ZipArchiveMode]::Update)
    try {Write-CandidateFixtureModule $archive 'ru.cashprediction.web'} finally {$archive.Dispose()}
    Assert-CandidateReject {Assert-CandidateVersion $image 101 $sha} 'CANDIDATE_JAR_MODULE'
    [IO.File]::WriteAllBytes($webJar,$webBytes)

    # Inventory проверяется на runtime, а не только на проверяемом AppInfo JAR.
    $runtime=Join-Path $image 'runtime/lib/modules';$runtimeText=[IO.File]::ReadAllText($runtime)
    [IO.File]::SetAttributes($runtime,[IO.FileAttributes]::ReadOnly)
    $inventory=Get-CandidateImageInventory $image
    Assert-CandidateFixture ($inventory.Count -eq 11 -and $inventory['runtime/lib/modules'].readOnly -and
        $inventory['runtime/lib/modules'].sizeBytes -eq 12 -and $inventory['runtime/lib/modules'].sha256.Length -eq 64) 'COMPLETE_FILE_INVENTORY'
    $copyImpl=(Get-Command Copy-CandidateTree).ScriptBlock
    try {
        foreach ($case in 'exact','before-source','after-source','size','hash','readonly','missing','extra','rename') {
            $copyOwner=New-CandidateOwnership (Join-Path $root ('inventory-'+$case))
            $target=Join-Path $copyOwner.root 'image'
            try {
                if ($case -eq 'before-source') {
                    [IO.File]::SetAttributes($runtime,[IO.FileAttributes]::Normal);Write-CandidateFixture $runtime 'different'
                } elseif ($case -ne 'exact') {
                    Set-Item Function:Copy-CandidateTree {
                        param($Source,$Destination,$Owner)
                        & $copyImpl $Source $Destination $Owner
                        $file=Join-Path $Destination 'runtime/lib/modules'
                        switch ($case) {
                            'after-source' {[IO.File]::SetAttributes($runtime,[IO.FileAttributes]::Normal);Write-CandidateFixture $runtime 'different'}
                            'size' {[IO.File]::SetAttributes($file,[IO.FileAttributes]::Normal);Write-CandidateFixture $file 'longer-runtime';[IO.File]::SetAttributes($file,[IO.FileAttributes]::ReadOnly)}
                            'hash' {[IO.File]::SetAttributes($file,[IO.FileAttributes]::Normal);Write-CandidateFixture $file 'fake-runtime';[IO.File]::SetAttributes($file,[IO.FileAttributes]::ReadOnly)}
                            'readonly' {[IO.File]::SetAttributes($file,[IO.FileAttributes]::Normal)}
                            'missing' {[IO.File]::SetAttributes($file,[IO.FileAttributes]::Normal);[IO.File]::Delete($file)}
                            'extra' {Write-CandidateFixture (Join-Path $Destination 'unexpected.bin') 'extra'}
                            'rename' {[IO.File]::Move($file,$file+'.renamed')}
                        }
                    }
                }
                $copyAction={Copy-CandidateImage $image $target $copyOwner $inventory}
                if ($case -eq 'exact') {
                    & $copyAction
                    Assert-CandidateFixture ((Get-CandidateImageInventory $target).Count -eq 11 -and
                        (Get-Item -LiteralPath (Join-Path $target 'runtime/lib/modules')).IsReadOnly) 'COPY_INVENTORY_READONLY'
                } else {
                    $code=if ($case -in 'before-source','after-source') {'CANDIDATE_IMAGE_CHANGED'} else {'CANDIDATE_IMAGE_COPY_MISMATCH'}
                    Assert-CandidateReject $copyAction $code
                    if ($case -eq 'before-source') {Assert-CandidateFixture (-not (Test-Path -LiteralPath $target)) 'SOURCE_PIN_BEFORE_COPY'}
                }
            } finally {
                $copyOwner.stream.Dispose();Set-Item Function:Copy-CandidateTree $copyImpl
                [IO.File]::SetAttributes($runtime,[IO.FileAttributes]::Normal);Write-CandidateFixture $runtime $runtimeText
                [IO.File]::SetAttributes($runtime,[IO.FileAttributes]::ReadOnly)
            }
        }
    } finally {Set-Item Function:Copy-CandidateTree $copyImpl;[IO.File]::SetAttributes($runtime,[IO.FileAttributes]::Normal)}

    $closed=Join-Path $root 'closed-source';New-CandidateFixtureSource $closed
    $targets=Assert-CandidateSourceClosure $closed
    Assert-CandidateFixture ($targets.Count -eq 8 -and $targets -contains 'core/target' -and $targets -contains 'dist/target') 'SOURCE_CLOSURE'
    $pomPath=Join-Path $closed 'pom.xml';$originalPom=[IO.File]::ReadAllText($pomPath)
    foreach ($module in '../foreign','C:/foreign','nested/../../foreign','core\nested','${foreign}') {
        Write-CandidateFixture $pomPath ($originalPom.Replace('<module>core</module>',"<module>$module</module>"))
        Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_MODULE_PATH'
    }
    Write-CandidateFixture $pomPath ($originalPom.Replace('<module>core</module>','<module>core</module><module>CORE</module>'))
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_MODULE_DUPLICATE'
    Write-CandidateFixture $pomPath ($originalPom.Replace('<module>core</module>','<module>missing</module>'))
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_POM'
    Write-CandidateFixture $pomPath ($originalPom.Replace('<id>dist</id>','<id>wrong</id>'))
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_DIST_PROFILE'
    Write-CandidateFixture $pomPath ($originalPom.Replace('<module>core</module>',''))
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_CLOSURE'
    Write-CandidateFixture $pomPath $originalPom
    $corePom=Join-Path $closed 'core/pom.xml';$originalCore=[IO.File]::ReadAllText($corePom)
    foreach ($relative in '../../pom.xml','C:/foreign/pom.xml','') {
        Write-CandidateFixture $corePom ($originalCore.Replace('../pom.xml',$relative))
        Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_PARENT_PATH'
    }
    Write-CandidateFixture $corePom $originalCore
    $java=Join-Path $closed 'web/src/main/java/Example.java'
    [IO.File]::Delete($java)
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_CLOSURE'
    Write-CandidateFixture $java '// Только данные fixture.'
    $binary=Join-Path $closed 'core/src/main/java/stale.jar';Write-CandidateFixture $binary 'stale'
    Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_SOURCE_CLOSURE'
    [IO.File]::Delete($binary)
    $link=Join-Path $closed 'core/src/linked'
    [void](New-Item -ItemType Junction -Path $link -Target $image)
    try {Assert-CandidateReject {Assert-CandidateSourceClosure $closed} 'CANDIDATE_LINK'} finally {[IO.Directory]::Delete($link)}
    $pins=Get-CandidateSourcePins $closed
    $generated=Join-Path $closed 'core/target';[void][IO.Directory]::CreateDirectory($generated)
    $link=Join-Path $generated 'linked';[void](New-Item -ItemType Junction -Path $link -Target $image)
    try {Assert-CandidateReject {Get-CandidateSourcePins $closed -AllowGeneratedTargets -GeneratedTargetPaths $targets} 'CANDIDATE_LINK'}
    finally {[IO.Directory]::Delete($link);[IO.Directory]::Delete($generated)}
    $unexpected=Join-Path $closed 'core/src/target';[void][IO.Directory]::CreateDirectory($unexpected)
    try {Assert-CandidateReject {Get-CandidateSourcePins $closed -AllowGeneratedTargets -GeneratedTargetPaths $targets} 'CANDIDATE_UNEXPECTED_TARGET'}
    finally {[IO.Directory]::Delete($unexpected)}
    Assert-CandidateReject {Assert-CandidateRunPins $closed (Join-Path $root 'missing-build') $pins $targets -RequireBuild} 'CANDIDATE_BUILD_CHANGED_SOURCE'

    # Проверяет основной preflight: плохое дерево не вызывает даже заведомо запрещённый mock Maven.
    $mockMaven=Join-Path $root 'never-maven.ps1'
    Write-CandidateFixture $mockMaven 'throw "CANDIDATE_FIXTURE_MAVEN_MUST_NOT_RUN"'
    $mockJdk=Join-Path $root 'never-jdk';Write-CandidateFixture (Join-Path $mockJdk 'bin/jpackage.exe') 'not-executable'
    $invalidOutput=Join-Path $root ('invalid-output-'+[guid]::NewGuid().ToString())
    Write-CandidateFixture $pomPath ($originalPom.Replace('<module>core</module>','<module>../foreign</module>'))
    try {
        Assert-CandidateReject {& (Join-Path $PSScriptRoot 'New-UpdateCandidateImages.ps1') -SourceSnapshot $closed -OutputRoot $invalidOutput -Maven $mockMaven -JdkHome $mockJdk -ReleaseNumber 101,102,103 -Commit $sha} 'CANDIDATE_SOURCE_MODULE_PATH'
        Assert-CandidateFixture (-not (Test-Path -LiteralPath $invalidOutput)) 'PREFLIGHT_NO_OUTPUT'
        Assert-CandidateReject {& (Join-Path $PSScriptRoot 'New-UpdateCandidateImages.ps1') -SourceSnapshot $closed -OutputRoot $invalidOutput -Maven $mockMaven -JdkHome $mockJdk -ReleaseNumber 101,102,103 -CommitByRelease $localCommits} 'CANDIDATE_SOURCE_MODULE_PATH'
        Assert-CandidateFixture (-not (Test-Path -LiteralPath $invalidOutput)) 'LOCAL_PREFLIGHT_NO_OUTPUT'
    } finally {Write-CandidateFixture $pomPath $originalPom}

    $output=Join-Path $root 'owned';$owner=New-CandidateOwnership $output
    try {
        Assert-CandidateReject {New-CandidateOwnership $output} 'CANDIDATE_OUTPUT_EXISTS'
        $foreign=Join-Path $root 'foreign';[void][IO.Directory]::CreateDirectory($foreign)
        Write-CandidateFixture (Join-Path $foreign 'keep') 'untouched'
        Assert-CandidateReject {New-CandidateDirectory $foreign} 'CANDIDATE_OUTPUT_EXISTS'
        Assert-CandidateFixture ([IO.File]::ReadAllText((Join-Path $foreign 'keep')) -ceq 'untouched') 'FOREIGN_UNCHANGED'
        $log=Join-Path $output 'build-101.log';Write-CandidateFixture $log 'foreign-log'
        Assert-CandidateReject {New-CandidateWriter $log $owner} 'CANDIDATE_OUTPUT_EXISTS'
        $receipt=Join-Path $output 'candidate-images.json';Write-CandidateFixture $receipt 'foreign-receipt'
        Assert-CandidateReject {New-CandidateWriter $receipt $owner} 'CANDIDATE_OUTPUT_EXISTS'
        Assert-CandidateFixture ([IO.File]::ReadAllText($log) -ceq 'foreign-log' -and [IO.File]::ReadAllText($receipt) -ceq 'foreign-receipt') 'FILES_UNCHANGED'
        $destination=Join-Path $output 'release-101';[void][IO.Directory]::CreateDirectory($destination)
        Write-CandidateFixture (Join-Path $destination 'keep') 'foreign-release'
        Assert-CandidateReject {Copy-CandidateTree $image $destination $owner} 'CANDIDATE_OUTPUT_EXISTS'
        Assert-CandidateFixture ([IO.File]::ReadAllText((Join-Path $destination 'keep')) -ceq 'foreign-release') 'RELEASE_UNCHANGED'
        $destination=Join-Path $output 'linked';[void](New-Item -ItemType Junction -Path $destination -Target $image)
        try {Assert-CandidateReject {Copy-CandidateTree $image $destination $owner} 'CANDIDATE_LINK'} finally {[IO.Directory]::Delete($destination)}
        $writer=New-CandidateWriter (Join-Path $output 'new.json') $owner
        try {$writer.Write('new')} finally {$writer.Dispose()}
        Assert-CandidateFixture ([IO.File]::ReadAllText((Join-Path $output 'new.json')) -ceq 'new') 'CREATE_NEW'
    } finally {$owner.stream.Dispose()}
    Assert-CandidateReject {Assert-CandidateOwnership $owner} 'CANDIDATE_OUTPUT_OWNERSHIP'

    # Два независимых runspace одновременно пытаются занять один UUID output.
    $race=Join-Path $root ('race-'+[guid]::NewGuid().ToString())
    $gate=[Threading.ManualResetEventSlim]::new($false);$ready=[Threading.CountdownEvent]::new(2)
    # Extent сохраняет имя функции без зависимости от автоматических переменных вложенного pipeline.
    $definitions=(@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -in @('Assert-CandidatePath','New-CandidateDirectory','New-CandidateOwnership')},$true)) |
        ForEach-Object {$_.Extent.Text}) -join "`n"
    $workers=@();$handles=@();$results=@()
    try {
        foreach ($number in 1,2) {
            $worker=[PowerShell]::Create();$workers+=,$worker
            [void]$worker.AddScript('param($definitions,$root,$gate,$ready); $ErrorActionPreference="Stop"; . ([scriptblock]::Create($definitions)); [void]$ready.Signal(); if (-not $gate.Wait(10000)) {throw "RACE_TIMEOUT"}; try { [pscustomobject]@{status="OWNED";owner=(New-CandidateOwnership $root)} } catch { [pscustomobject]@{status=$_.Exception.Message;owner=$null} }')
            [void]$worker.AddArgument($definitions).AddArgument($race).AddArgument($gate).AddArgument($ready)
            $handles+=,$worker.BeginInvoke()
        }
        if (-not $ready.Wait(10000)) {throw 'CANDIDATE_FIXTURE_RACE_TIMEOUT'}
        $gate.Set()
        for ($i=0;$i -lt 2;$i++) {$results+=@($workers[$i].EndInvoke($handles[$i]))}
        Assert-CandidateFixture (@($results | Where-Object status -CEQ 'OWNED').Count -eq 1 -and
            @($results | Where-Object status -CEQ 'CANDIDATE_OUTPUT_EXISTS').Count -eq 1) 'EXCLUSIVE_RACE'
        $winner=@($results | Where-Object status -CEQ 'OWNED')[0].owner
        Assert-CandidateOwnership $winner
        Assert-CandidateFixture (-not (Test-Path -LiteralPath (Join-Path $race 'candidate-images.json'))) 'RACE_NO_RECEIPT'
    } finally {
        $gate.Set()
        foreach ($result in $results) {if ($null -ne $result.owner) {$result.owner.stream.Dispose()}}
        foreach ($worker in $workers) {$worker.Dispose()}
        $ready.Dispose();$gate.Dispose()
    }

    # Настоящие orchestration/pins/copy работают; заменена только граница Maven mock-данными.
    $originalPackage=(Get-Command Invoke-CandidatePackage).ScriptBlock
    $originalCopy=(Get-Command Copy-CandidateTree).ScriptBlock
    $script:mockCalls=0;$script:scenario='success';$script:mockSource=$closed;$script:sharedSha=$sha
    $script:mockIdentities=[Collections.Generic.List[object]]::new()
    Set-Item Function:Invoke-CandidatePackage {
        param($MavenPath,$Release,$Sha,$Writer)
        $script:mockCalls++;$Writer.WriteLine('MOCK: no Maven execution')
        $script:mockIdentities.Add([pscustomobject]@{releaseNumber=$Release;commitSha=$Sha})
        if ($script:scenario -eq 'source-failure') {
            Write-CandidateFixture (Join-Path $script:mockSource 'web/src/main/java/Example.java') 'changed'
            throw "CANDIDATE_BUILD_FAILED $Release"
        }
        if ($script:scenario -eq 'failure') {throw "CANDIDATE_BUILD_FAILED $Release"}
        if ($script:scenario -eq 'build-change') {Write-CandidateFixture (Join-Path $PWD 'web/src/main/java/Example.java') 'changed'}
        $mockImage=Join-Path $PWD 'dist/target/dist/CashPrediction'
        $imageSha=if ($script:scenario -eq 'wrong-local-commit') {$script:sharedSha} else {$Sha}
        Write-CandidateFixtureImage $mockImage $Release $imageSha
    }
    $savedJava=$env:JAVA_HOME;$savedLocation=$PWD.Path
    try {
        foreach ($case in 'success','failure','build-change','source-failure','initial-copy','local-metadata','wrong-local-commit') {
            $script:scenario=$case;$script:mockCalls=0;$script:mockIdentities.Clear()
            $run=Join-Path $root ('run-'+$case);$owner=New-CandidateOwnership $run
            if ($case -eq 'initial-copy') {
                Set-Item Function:Copy-CandidateTree {
                    param($Source,$Destination,$Owner)
                    & $originalCopy $Source $Destination $Owner
                    Write-CandidateFixture (Join-Path $Destination 'web/src/main/java/Example.java') 'changed'
                }
            }
            try {
                $action={Invoke-CandidateBuilds $closed $owner $pins $targets @(101,102,103) 'NOT_EXECUTED' 'MOCK_JDK' $sha}
                if ($case -in 'local-metadata','wrong-local-commit') {
                    $action={Invoke-CandidateBuilds $closed $owner $pins $targets @(101,102,103) 'NOT_EXECUTED' 'MOCK_JDK' '' $localCommits}
                }
                if ($case -in 'success','local-metadata') {
                    $records=& $action
                    Assert-CandidateFixture ($records.Count -eq 3 -and $script:mockCalls -eq 3 -and
                        $records[2].releaseNumber -eq 103) 'THREE_MOCK_BUILDS'
                    Assert-CandidateFixture ($records[0].files.Count -eq 11 -and
                        @($records[2].files | Where-Object {$_.path -ceq 'runtime/lib/modules'}).Count -eq 1) 'RECEIPT_FULL_INVENTORIES'
                    $receipt=New-CandidateReceipt $closed $pins $records ($case -eq 'local-metadata')
                    $wire=$receipt | ConvertTo-Json -Depth 16 | ConvertFrom-Json
                    Assert-CandidateFixture ($wire.gitProvenance -ceq 'NOT_ASSERTED' -and $wire.nativeMatrix -ceq 'PENDING' -and
                        $wire.sourcePins.'core/src/main/java/Example.java' -ceq $pins['core/src/main/java/Example.java'] -and
                        @($wire.images | Where-Object {$_.commitIdentityKind -cne 'LOCAL_METADATA'}).Count -eq 0) 'LOCAL_NOT_GIT_RECEIPT'
                    $artifactIdentities=@($wire.images | ForEach-Object {
                        [pscustomobject]@{releaseNumber=$_.releaseNumber;commitSha=$_.commitSha;deltaPatches=@()}
                    })
                    if ($case -eq 'local-metadata') {
                        Assert-CandidateFixture ($wire.commitIdentityMode -ceq 'LOCAL_DISTINCT_METADATA') 'LOCAL_DISTINCT_RECEIPT_MODE'
                        for ($index=0;$index -lt 3;$index++) {
                            $release=101+$index;$actual=Assert-CandidateVersion $records[$index].portableDir $release $localCommits[$index]
                            Assert-CandidateFixture ($script:mockIdentities[$index].releaseNumber -eq $release -and
                                $script:mockIdentities[$index].commitSha -ceq $localCommits[$index] -and
                                $wire.images[$index].commitSha -ceq $localCommits[$index] -and
                                $actual.commitSha -ceq $localCommits[$index]) ('LOCAL_RELEASE_COMMIT_'+$release)
                        }
                        Assert-ArtifactBases @($artifactIdentities[0],$artifactIdentities[1]) $artifactIdentities[2]
                        Assert-CandidateFixture $true 'FROZEN_ARTIFACT_BASES_ACCEPT_LOCAL'
                        $artifactIdentities[2].commitSha=$artifactIdentities[0].commitSha
                        Assert-CandidateReject {Assert-ArtifactBases @($artifactIdentities[0],$artifactIdentities[1]) $artifactIdentities[2]} 'ARTIFACT_FORWARD_RELEASE'
                    } else {
                        Assert-CandidateFixture ($wire.commitIdentityMode -ceq 'LOCAL_SHARED_METADATA' -and
                            @($wire.images | Where-Object {$_.commitSha -cne $sha}).Count -eq 0) 'SHARED_COMMIT_RECEIPT_COMPATIBILITY'
                        Assert-CandidateReject {Assert-ArtifactBases @($artifactIdentities[0],$artifactIdentities[1]) $artifactIdentities[2]} 'ARTIFACT_BASE_IDENTITY'
                    }
                } else {
                    $code=switch ($case) {
                        'failure' {'CANDIDATE_BUILD_FAILED 101'}
                        'build-change' {'CANDIDATE_BUILD_CHANGED_SOURCE'}
                        'source-failure' {'CANDIDATE_BUILD_FAILED 101 | CANDIDATE_SOURCE_CHANGED'}
                        'initial-copy' {'CANDIDATE_INITIAL_COPY_CHANGED | CANDIDATE_BUILD_CHANGED_SOURCE'}
                        'wrong-local-commit' {'CANDIDATE_VERSION_MISMATCH'}
                    }
                    Assert-CandidateReject $action $code
                    Assert-CandidateFixture ($script:mockCalls -eq $(if ($case -eq 'initial-copy') {0} elseif ($case -eq 'wrong-local-commit') {2} else {1}) -and
                        -not (Test-Path -LiteralPath (Join-Path $run 'release-102')) -and
                        -not (Test-Path -LiteralPath (Join-Path $run 'candidate-images.json'))) ('NO_CONTINUATION_'+$case)
                }
                Assert-CandidateFixture ($env:JAVA_HOME -ceq $savedJava -and $PWD.Path -ceq $savedLocation) ('RESTORED_'+$case)
            } finally {
                $owner.stream.Dispose();Set-Item Function:Copy-CandidateTree $originalCopy
                Write-CandidateFixture $java '// Только данные fixture.'
            }
        }
    } finally {Set-Item Function:Invoke-CandidatePackage $originalPackage;Set-Item Function:Copy-CandidateTree $originalCopy}
    Write-Output "Candidate image builder guards: $checks PASS; no Maven/native execution."
} finally {
    # Только проверенный UUID fixture-каталог, созданный этим процессом, без пользовательских данных.
    if ($root.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($root) -match '^candidate-fixtures-[0-9a-f-]{36}$') {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
