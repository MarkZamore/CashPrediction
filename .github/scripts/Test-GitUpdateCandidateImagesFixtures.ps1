#requires -Version 7.0
<#
.SYNOPSIS
Focused Git candidate fixtures, actual read-only B1 archive и own Temp отрицательные guards.
.DESCRIPTION
Без Maven/dist/native/GUI, без Git mutations. Mock subprocess never означает native PASS.
Сохраняет own Temp source diagnostics; никаких root delivery folders.
#>
[CmdletBinding()]
param(
    [string]$Repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')),
    [string]$Git=@(Get-Command git.exe -CommandType Application -ErrorAction Stop)[0].Source
)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$builderFile=Join-Path $PSScriptRoot 'New-GitUpdateCandidateImages.ps1'
$builderSha=(Get-FileHash -LiteralPath $builderFile).Hash
. (Join-Path $PSScriptRoot 'New-GitUpdateCandidateImages.ps1') -Repository $Repository -Git $Git
$script:checks=0

# Считает unit checks, не выполненные product/native клетки.
function Assert-GitFixture([bool]$Condition,[string]$Name) {
    if (-not $Condition) {throw "GIT_FIXTURE_ASSERT $Name"};$script:checks++
}

# Проверяет точный отказ на независимых inputs.
function Reject-GitFixture([scriptblock]$Action,[string]$Code) {
    $actual='';try {& $Action | Out-Null} catch {$actual=$_.Exception.Message}
    Assert-GitFixture ($actual -ceq $Code) ($Code+' actual='+$actual)
}

# Создаёт только новый mock input, без перезаписи shared исходников.
function Write-GitFixtureBytes([string]$Path,[byte[]]$Bytes) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($Bytes);$stream.Flush($true)} finally {$stream.Dispose()}
}

# Формирует минимальный tar fixture; никогда не изображает готовую native поставку.
function Write-GitFixtureTar([string]$Path,[string]$Name,[string]$Content,[System.Formats.Tar.TarEntryType]$Type=[System.Formats.Tar.TarEntryType]::RegularFile) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    $writer=[System.Formats.Tar.TarWriter]::new($stream,[System.Formats.Tar.TarEntryFormat]::Pax,$true)
    $data=[IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($Content))
    try {
        $entry=[System.Formats.Tar.PaxTarEntry]::new($Type,$Name)
        if ($Type -eq [System.Formats.Tar.TarEntryType]::SymbolicLink) {$entry.LinkName='../escape'} else {$entry.DataStream=$data}
        $writer.WriteEntry($entry)
    } finally {$writer.Dispose();$data.Dispose();$stream.Dispose()}
}

$owned=Join-Path ([IO.Path]::GetTempPath()) ('cp-git-candidate-fixtures-'+[guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $owned
$b1='3bff212fe08aebdf2c6cfb69e62fbb7a40f23309'
$beforeHead=(Invoke-GitCandidateRead $Git $Repository @('rev-parse','HEAD')).Trim()
$realRead=${function:Invoke-GitCandidateRead}
foreach ($commits in @(@($b1,$b1,$b1),@($b1,$b1),@('3bff212',('b'*40),('c'*40)),@('--all',('b'*40),('c'*40)))) {
    Reject-GitFixture {Resolve-GitCandidateChain $Git $Repository $commits} 'GIT_CANDIDATE_THREE_DISTINCT_COMMITS'
}
foreach ($path in '../escape','/root','a\b','a:b','a/./b','a/../b','CON.txt','a/NUL','trailing.','x*') {
    Reject-GitFixture {Assert-GitCandidateRelative $path} 'GIT_CANDIDATE_ARCHIVE_PATH'
}
Reject-GitFixture {Get-GitCandidateTree ("120000 blob "+('a'*40)+"`tlink`0")} 'GIT_CANDIDATE_TREE_MODE'
Reject-GitFixture {Get-GitCandidateTree ("160000 commit "+('a'*40)+"`tsubmodule`0")} 'GIT_CANDIDATE_TREE_MODE'
Reject-GitFixture {Get-GitCandidateTree ("100644 blob "+('a'*40)+"`tA`0"+"100644 blob "+('a'*40)+"`ta`0")} 'GIT_CANDIDATE_TREE_DUPLICATE'
Reject-GitFixture {Invoke-GitCandidateRead $Git $Repository @('commit','--allow-empty')} 'GIT_CANDIDATE_GIT_VERB'
Assert-GitFixture ((Get-GitCandidateSourceComparison @(('a'*64),('a'*64),('a'*64))) -ceq 'IDENTICAL_NO_CODE_DIFFERENCE_CLAIM') 'same code not distinct metadata claim'
Assert-GitFixture ((Get-GitCandidateSourceComparison @(('a'*64),('b'*64),('c'*64))) -ceq 'CONTENT_DIFFERENCES_NOT_BEHAVIOR_PROOF') 'changed bytes not behavior signoff'
foreach ($configText in '-Dmaven.repo.local=shared','-DskipTests','-pl core','--settings shared.xml','-f outside-pom.xml') {
    $configRoot=Join-Path $owned ('config-'+[guid]::NewGuid().ToString());$null=New-Item -ItemType Directory -Path (Join-Path $configRoot '.mvn')
    Write-GitFixtureBytes (Join-Path $configRoot '.mvn/maven.config') ([Text.Encoding]::UTF8.GetBytes($configText))
    Reject-GitFixture {Assert-GitCandidateBuildConfig $configRoot} 'GIT_CANDIDATE_CONFIG_OVERRIDE'
}

# Никакого synthetic Git repo/commit: реально читается единственный caller B1 object.
$resolved=(Invoke-GitCandidateRead $Git $Repository @('rev-parse','--verify','--end-of-options',($b1+'^{commit}'))).Trim()
Assert-GitFixture ($resolved -ceq $b1) 'actual B1 object'
$tree=Get-GitCandidateTree (Invoke-GitCandidateRead $Git $Repository @('ls-tree','-rz',$b1))
$archive=Join-Path $owned 'actual-B1.tar';$raw=Join-Path $owned 'actual-B1-git-source'
[void](Invoke-GitCandidateRead $Git $Repository @('archive','--format=tar',$b1) $archive)
Expand-GitCandidateArchive $archive $raw $tree
Assert-GitFixture (@(Get-ChildItem -LiteralPath $raw -Recurse -File -Force).Count -eq $tree.Count) 'actual archive all tree/blob bytes validated'
Assert-GitFixture (-not (Test-Path -LiteralPath (Join-Path $raw '.git'))) 'archive is not dirty checkout'
$release=(Invoke-GitCandidateRead $Git $Repository @('rev-list','--count',$b1)).Trim()
Assert-GitFixture ($release -ceq '20') 'actual B1 release20 not dummy1001'
$policy=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../dist/scripts/Pack-Source.ps1'))
$policySha=(Get-FileHash -LiteralPath $policy).Hash.ToLowerInvariant()
$candidate=Join-Path $PSScriptRoot 'New-UpdateCandidateImages.ps1'
$candidateSha=(Get-FileHash -LiteralPath $candidate).Hash.ToLowerInvariant()
$policyBytes=Read-GitCandidateTool $policy $policySha
$candidateBytes=Read-GitCandidateTool $candidate $candidateSha
$validators=New-GitCandidateValidators ([Text.Encoding]::UTF8.GetString($candidateBytes)) $policy
try {
    $stage=Join-Path $owned 'actual-B1-delivered-source'
    & ([scriptblock]::Create([Text.Encoding]::UTF8.GetString($policyBytes))) -SourceRoot $raw -StageDirectory $stage -StageOnly | Out-Null
    $targets=& $validators {param($root) Assert-CandidateSourceClosure $root} $stage
    $pins=& $validators {param($root) Get-CandidateSourcePins $root} $stage
    Assert-GitFixture ($pins.Count -gt 100 -and $targets.Count -ge 7) 'actual B1 delivered reactor closure'
    Assert-GitFixture (-not $pins.ContainsKey('AGENTS.md') -and -not $pins.ContainsKey('PROJECT_REQUIREMENTS.md')) 'existing policy excludes agent/dev files'
    Assert-GitFixture (@($pins.Keys | Where-Object {$_ -like 'docs/*'}).Count -eq 1 -and $pins.ContainsKey('docs/design/architecture.md')) 'existing policy only architecture doc'
    Assert-GitFixture ($pins.ContainsKey('core/src/main/java/ru/cashprediction/core/io/AppInfo.java') -and $pins.ContainsKey('ui-parity/pom.xml')) 'real product/tests retained'
} finally {Remove-Module -ModuleInfo $validators -Force}

# Malformed own tar inputs проверяются actual extractor, не zip/tar shell command.
$mockTree=Get-GitCandidateTree ("100644 blob "+('a'*40)+"`tfile.txt`0")
$traversal=Join-Path $owned 'traversal.tar';Write-GitFixtureTar $traversal '../escape.txt' 'MOCK'
Reject-GitFixture {Expand-GitCandidateArchive $traversal (Join-Path $owned 'traversal-output') $mockTree} 'GIT_CANDIDATE_ARCHIVE_PATH'
$link=Join-Path $owned 'link.tar';Write-GitFixtureTar $link 'file.txt' '' ([System.Formats.Tar.TarEntryType]::SymbolicLink)
Reject-GitFixture {Expand-GitCandidateArchive $link (Join-Path $owned 'link-output') $mockTree} 'GIT_CANDIDATE_ARCHIVE_ENTRY'
$changed=Join-Path $owned 'changed.tar';Write-GitFixtureTar $changed 'file.txt' 'MOCK'
Reject-GitFixture {Expand-GitCandidateArchive $changed (Join-Path $owned 'changed-output') $mockTree} 'GIT_CANDIDATE_ARCHIVE_BLOB_MISMATCH'
Reject-GitFixture {Expand-GitCandidateArchive $archive $raw $tree} 'GIT_CANDIDATE_OUTPUT_EXISTS'
Reject-GitFixture {Read-GitCandidateTool $policy ('f'*64)} 'GIT_CANDIDATE_TOOL_CHANGED'

# Read-only subprocess adapter mocks: guards counts/history/ancestry, никогда Maven/native.
$script:mockMode='good';$fakeCommits=@(('a'*40),('b'*40),('c'*40));$script:gitCalls=[Collections.Generic.List[object]]::new()
function Invoke-GitCandidateRead([string]$Git,[string]$Repository,[string[]]$Arguments,[string]$ArchivePath='') {
    $script:gitCalls.Add([pscustomobject]@{arguments=$Arguments;archive=$ArchivePath})
    if ($Arguments[0] -ceq 'archive') {throw 'MOCK_ARCHIVE_MUST_NOT_RUN'}
    if ($Arguments[0] -ceq 'merge-base') {if ($script:mockMode -ceq 'unrelated') {throw 'GIT_CANDIDATE_GIT_REJECTED'};return ''}
    if ($Arguments -ccontains '--show-object-format') {return 'sha1'}
    if ($Arguments -ccontains '--is-shallow-repository') {return $(if ($script:mockMode -ceq 'shallow') {'true'} else {'false'})}
    if ($Arguments -ccontains '--git-common-dir') {return (Join-Path $owned 'mock-git-common')}
    if ($Arguments[0] -ceq 'rev-list') {return $(if ($script:mockMode -ceq 'same-count') {'20'} else {([array]::IndexOf($fakeCommits,$Arguments[-1])+20).ToString()})}
    if ($Arguments[-1].EndsWith('^{tree}')) {return ('d'*40)}
    if ($script:mockMode -ceq 'missing') {throw 'GIT_CANDIDATE_GIT_REJECTED'}
    return $Arguments[-1].Replace('^{commit}','')
}
$chain=Resolve-GitCandidateChain $Git $Repository $fakeCommits
Assert-GitFixture ($chain.Count -eq 3 -and $chain[0].releaseNumber -eq 20 -and $chain[2].releaseNumber -eq 22) 'mock only exact commit release routing'
$script:mockMode='shallow';Reject-GitFixture {Resolve-GitCandidateChain $Git $Repository $fakeCommits} 'GIT_CANDIDATE_FULL_SHA1_HISTORY_REQUIRED'
$script:mockMode='missing';Reject-GitFixture {Resolve-GitCandidateChain $Git $Repository $fakeCommits} 'GIT_CANDIDATE_GIT_REJECTED'
$script:mockMode='unrelated';Reject-GitFixture {Resolve-GitCandidateChain $Git $Repository $fakeCommits} 'GIT_CANDIDATE_GIT_REJECTED'
$script:mockMode='same-count';Reject-GitFixture {Resolve-GitCandidateChain $Git $Repository $fakeCommits} 'GIT_CANDIDATE_RELEASE_ORDER'
$output=Join-Path ([IO.Path]::GetTempPath()) ('cp-git-candidates-'+[guid]::NewGuid().ToString())
$invokePins=@{Repository=$Repository;Git=$Git;CommitIds=$fakeCommits;OutputRoot=$output;PackSourceSha256=$policySha;CandidateValidatorSha256=$candidateSha}
Reject-GitFixture {Invoke-NewGitUpdateCandidateImages @invokePins} 'GIT_CANDIDATE_RELEASE_ORDER'
Assert-GitFixture (-not (Test-Path -LiteralPath $output)) 'preflight fails before owner/archive/Maven'
$script:mockMode='good';$bad=$invokePins.Clone();$bad.OutputRoot=$owned
Reject-GitFixture {Invoke-NewGitUpdateCandidateImages @bad} 'GIT_CANDIDATE_NEW_TEMP_ROOT_REQUIRED'
Assert-GitFixture (@($script:gitCalls | Where-Object {$_.arguments[0] -ceq 'archive'}).Count -eq 0) 'no mock archive claims'
$bad=$invokePins.Clone();$bad.Maven=Join-Path $owned 'missing-maven.cmd';$bad.JdkHome=$owned
Reject-GitFixture {Invoke-NewGitUpdateCandidateImages @bad -Build} 'GIT_CANDIDATE_BUILD_TOOLS'
Assert-GitFixture (-not (Test-Path -LiteralPath $output)) 'missing build tools cannot reserve or build output'

# Failed install boundary mock с actual source pin finally, без процесса Maven/dist.
$script:mavenCalls=[Collections.Generic.List[object]]::new()
function Invoke-GitCandidateMaven([string]$Maven,[string]$Stage,[string]$Jdk,[string]$Repo,[string]$Settings,[int]$Release,[string]$Commit,[string]$Goal,$Writer) {
    $script:mavenCalls.Add([pscustomobject]@{repo=$Repo;settings=$Settings;release=$Release;commit=$Commit;goal=$Goal})
    throw 'MOCK_INSTALL_FAILED_NO_MAVEN'
}
$validators=New-GitCandidateValidators ([Text.Encoding]::UTF8.GetString($candidateBytes)) $policy
$owner=$null
try {
    $negative=Join-Path $owned 'failed-build-owner'
    $owner=& $validators {param($root) New-CandidateOwnership $root} $negative
    $metadata=[pscustomobject]@{releaseNumber=20;commitSha=$b1}
    Reject-GitFixture {Invoke-GitCandidateBuild $validators $owner $stage $pins $targets 'MOCK_NOT_MAVEN' 'MOCK_NOT_JDK' $metadata 'B1'} 'MOCK_INSTALL_FAILED_NO_MAVEN'
    Assert-GitFixture ($script:mavenCalls.Count -eq 1 -and $script:mavenCalls[0].goal -ceq 'install' -and
        $script:mavenCalls[0].repo -ceq (Join-Path $negative 'B1-maven-repo')) 'failed install prevents package and owns separate repo'
    Assert-GitFixture ($script:mavenCalls[0].release -eq 20 -and $script:mavenCalls[0].commit -ceq $b1) 'actual commit/count forwarded to build boundary'
    Assert-GitFixture ((Get-Content -LiteralPath $script:mavenCalls[0].settings -Raw) -ceq '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>') 'empty owned settings not shared settings'
    Assert-GitFixture (-not (Test-Path -LiteralPath (Join-Path $negative 'B1-image')) -and
        -not (Test-Path -LiteralPath (Join-Path $negative 'git-candidate-images.json'))) 'failed mock never image or candidate receipt'
} finally {if ($null -ne $owner) {$owner.stream.Dispose()};Remove-Module -ModuleInfo $validators -Force}

# Static build contract: тесты install обязательны, оба goals используют explicit isolated repo/settings.
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'New-GitUpdateCandidateImages.ps1'),[ref]$tokens,[ref]$errors)
Assert-GitFixture ($errors.Count -eq 0) 'builder parse valid'
$mavenBody=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Invoke-GitCandidateMaven'})[0].Extent.Text
Assert-GitFixture ($mavenBody.Contains('"-Dmaven.repo.local=$Repo"') -and $mavenBody.Contains("'-s',`$Settings,'-gs',`$Settings")) 'explicit isolated repo and empty settings'
Assert-GitFixture ($mavenBody.Contains("if (`$Goal -ceq 'package')") -and $mavenBody.Contains("@('-Pdist','-DskipTests')")) 'skipTests only dist never install'
Assert-GitFixture ($mavenBody.Contains("@('-DskipTests=false','-Dmaven.test.skip=false')")) 'install explicitly not skipped'
Assert-GitFixture ($mavenBody.Contains("'MAVEN_PROJECTBASEDIR'") -and $mavenBody.Contains("'MAVEN_ARGS'") -and $mavenBody.Contains("'JAVA_TOOL_OPTIONS'")) 'ambient base and JVM/Maven injection cleared'
$buildBody=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Invoke-GitCandidateBuild'})[0].Extent.Text
Assert-GitFixture ($buildBody.Contains("foreach (`$goal in 'install','package')") -and $buildBody.Contains('Assert-CandidateVersion')) 'full install before dist and real AppInfo validator'
$afterHead=(& $realRead $Git $Repository @('rev-parse','HEAD')).Trim()
Assert-GitFixture ($beforeHead -ceq $afterHead) 'Git HEAD untouched'
Assert-GitFixture ($builderSha -ceq (Get-FileHash -LiteralPath $builderFile).Hash) 'builder source epoch unchanged during fixture'
[pscustomobject]@{status='PASS';checks=$script:checks;scope='GIT_ARCHIVE_AND_MOCK_GUARDS_ONLY';native=$false;maven=$false;
    actualArchiveCommit=$b1;actualReleaseNumber=20;nativeMatrix='PENDING';ownedTemp=$owned;PSVersion=$PSVersionTable.PSVersion.ToString()} | ConvertTo-Json -Compress
