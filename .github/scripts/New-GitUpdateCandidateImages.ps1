#requires -Version 7.0
<#
.SYNOPSIS
Три candidate images из трёх настоящих Git commits, только в новой own Temp папке.
.DESCRIPTION
CommitIds: полные SHA B1/B2/T; цепочка ancestor и возрастающие rev-list counts обязательны.
Никаких commit/tag/checkout/remote/publication. Dirty worktree не используется как источник.
Git archive каждого commit сверяется с ls-tree и Git blob SHA до извлечения regular files.
Pinned текущий Pack-Source -StageOnly задаёт delivered policy и единственное преобразование POM.
Pinned AST функции New-UpdateCandidateImages подтверждают source closure, image и AppInfo.
По умолчанию только source preparation, PENDING. Явный Build запускает полный install,
затем dist package, в отдельных Maven repo и пустых settings для каждого snapshot.
Даже BUILT не является native/S7/release PASS. Разные commits не обещают разные исходники.
Не создаёт root deliverables; логи и незавершённые own Temp остаются для диагностики.
#>
[CmdletBinding()]
param(
    [string]$Repository,[string]$Git,[string[]]$CommitIds,[string]$OutputRoot,
    [string]$PackSourceSha256,[string]$CandidateValidatorSha256,
    [string]$Maven,[string]$JdkHome,[switch]$Build
)

# Проверяет canonical Windows path и все существующие предки без следования ссылкам.
function Assert-GitCandidatePath([string]$Path) {
    if (-not $Path -or -not [IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\') -or
        [IO.Path]::GetFullPath($Path).TrimEnd('\','/') -cne $Path -or $Path -match '[\x00-\x1f"*?;]' -or $Path.IndexOf(':',2) -ge 0) {throw 'GIT_CANDIDATE_PATH'}
    $cursor=$Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'GIT_CANDIDATE_LINK'}
        }
        $parent=[IO.Path]::GetDirectoryName($cursor);if ($parent -eq $cursor) {break};$cursor=$parent
    }
    return $Path
}

# Читает frozen tooling bytes, а не его верхнее тело.
function Read-GitCandidateTool([string]$Path,[string]$Sha) {
    [void](Assert-GitCandidatePath $Path)
    if ($Sha -cnotmatch '^[0-9a-f]{64}$') {throw 'GIT_CANDIDATE_TOOL_PIN'}
    $bytes=[IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -gt 1048576 -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Sha) {throw 'GIT_CANDIDATE_TOOL_CHANGED'}
    return ,$bytes
}

# Единственная Git subprocess граница: fixed read-only verbs, binary archive в CreateNew stream.
function Invoke-GitCandidateRead([string]$Git,[string]$Repository,[string[]]$Arguments,[string]$ArchivePath='') {
    if ($Arguments.Count -lt 1 -or $Arguments[0] -cnotin @('rev-parse','rev-list','merge-base','ls-tree','archive')) {throw 'GIT_CANDIDATE_GIT_VERB'}
    $info=[Diagnostics.ProcessStartInfo]::new($Git);$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.WorkingDirectory=$Repository
    foreach ($name in @($info.Environment.Keys)) {if ($name -like 'GIT_*') {[void]$info.Environment.Remove($name)}}
    $info.Environment['GIT_NO_REPLACE_OBJECTS']='1';$info.Environment['GIT_OPTIONAL_LOCKS']='0'
    foreach ($arg in @('-c','core.attributesFile=NUL','-c','core.fsmonitor=false','-C',$Repository)+$Arguments) {$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$sink=$null;$started=$false
    try {
        if ($ArchivePath) {$sink=[IO.File]::Open($ArchivePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)}
        else {$sink=[IO.MemoryStream]::new()}
        if (-not $process.Start()) {throw 'GIT_CANDIDATE_GIT_START'};$started=$true
        $copy=$process.StandardOutput.BaseStream.CopyToAsync($sink);$stderr=$process.StandardError.ReadToEndAsync()
        $exited=$false;$limit=if ($ArchivePath) {1073741824L} else {16777216L}
        for ($second=0;$second -lt 60;$second++) {
            if ($sink.Length -gt $limit) {$process.Kill();throw 'GIT_CANDIDATE_GIT_OUTPUT'}
            if ($process.WaitForExit(1000)) {$exited=$true;break}
        }
        if (-not $exited) {$process.Kill();throw 'GIT_CANDIDATE_GIT_TIMEOUT'}
        if (-not [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($copy,$stderr)).Wait(5000)) {throw 'GIT_CANDIDATE_GIT_DRAIN'}
        [void]$copy.GetAwaiter().GetResult();$errorText=$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0 -or $errorText -cne '' -or $sink.Length -gt 1073741824) {throw 'GIT_CANDIDATE_GIT_REJECTED'}
        if ($ArchivePath) {$sink.Flush($true);return ''}
        if ($sink.Length -gt 16777216) {throw 'GIT_CANDIDATE_GIT_OUTPUT'}
        return [Text.UTF8Encoding]::new($false,$true).GetString($sink.ToArray())
    } finally {
        if ($started -and -not $process.HasExited) {$process.Kill();[void]$process.WaitForExit(5000)}
        if ($null -ne $sink) {$sink.Dispose()};$process.Dispose()
    }
}

# Caller даёт реальные commits, не refs/опции; release вычисляется из их полной истории.
function Resolve-GitCandidateChain([string]$Git,[string]$Repository,[string[]]$Commits) {
    if (@($Commits).Count -ne 3 -or @($Commits | Where-Object {$_ -cnotmatch '^[0-9a-f]{40}$'}).Count -or
        @($Commits | Sort-Object -Unique).Count -ne 3) {throw 'GIT_CANDIDATE_THREE_DISTINCT_COMMITS'}
    if ((Invoke-GitCandidateRead $Git $Repository @('rev-parse','--show-object-format')).Trim() -cne 'sha1' -or
        (Invoke-GitCandidateRead $Git $Repository @('rev-parse','--is-shallow-repository')).Trim() -cne 'false') {throw 'GIT_CANDIDATE_FULL_SHA1_HISTORY_REQUIRED'}
    $common=(Invoke-GitCandidateRead $Git $Repository @('rev-parse','--git-common-dir')).Trim()
    if (-not [IO.Path]::IsPathFullyQualified($common)) {$common=Join-Path $Repository $common}
    $common=[IO.Path]::GetFullPath($common);[void](Assert-GitCandidatePath $common)
    if (Test-Path -LiteralPath (Join-Path $common 'info/grafts')) {throw 'GIT_CANDIDATE_HISTORY_REWRITE'}
    $records=@(foreach ($sha in $Commits) {
        $actual=(Invoke-GitCandidateRead $Git $Repository @('rev-parse','--verify','--end-of-options',($sha+'^{commit}'))).Trim()
        if ($actual -cne $sha) {throw 'GIT_CANDIDATE_COMMIT_MISMATCH'}
        $count=(Invoke-GitCandidateRead $Git $Repository @('rev-list','--count',$sha)).Trim()
        $tree=(Invoke-GitCandidateRead $Git $Repository @('rev-parse',($sha+'^{tree}'))).Trim()
        if ($count -cnotmatch '^[1-9][0-9]{0,8}$' -or $tree -cnotmatch '^[0-9a-f]{40}$') {throw 'GIT_CANDIDATE_HISTORY'}
        [pscustomobject]@{commitSha=$sha;releaseNumber=[int]$count;treeSha=$tree}
    })
    for ($index=1;$index -lt 3;$index++) {
        [void](Invoke-GitCandidateRead $Git $Repository @('merge-base','--is-ancestor',$Commits[$index-1],$Commits[$index]))
        if ($records[$index-1].releaseNumber -ge $records[$index].releaseNumber) {throw 'GIT_CANDIDATE_RELEASE_ORDER'}
    }
    return ,$records
}

# Запрещает traversal, ADS, reserved Windows имена и case-collision до извлечения tar.
function Assert-GitCandidateRelative([string]$Name) {
    if (-not $Name -or $Name.Length -gt 240 -or $Name -match '[\\:\x00-\x1f]' -or $Name.StartsWith('/') -or $Name.EndsWith('/')) {throw 'GIT_CANDIDATE_ARCHIVE_PATH'}
    foreach ($part in $Name.Split('/')) {
        if (-not $part -or $part -in @('.','..') -or $part -match '["<>|*?]' -or $part -match '[. ]$' -or
            $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {throw 'GIT_CANDIDATE_ARCHIVE_PATH'}
    }
}

# Сверяет mode/blob/path inventory с Git; никакие symlink/submodule не доставляются.
function Get-GitCandidateTree([string]$Text) {
    $tree=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in $Text.Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)) {
        if ($line -cnotmatch '^(100644|100755) blob ([0-9a-f]{40})\t(.+)$') {throw 'GIT_CANDIDATE_TREE_MODE'}
        $name=$Matches[3];$blob=$Matches[2];Assert-GitCandidateRelative $name
        if ($tree.ContainsKey($name) -or $tree.Count -ge 10000) {throw 'GIT_CANDIDATE_TREE_DUPLICATE'}
        $tree.Add($name,[pscustomobject]@{path=$name;blobSha=$blob})
    }
    if (-not $tree.Count) {throw 'GIT_CANDIDATE_TREE_EMPTY'}
    return ,$tree
}

# Извлекает только проверенные regular bytes; export-ignore/subst не обходят Git proof.
function Expand-GitCandidateArchive([string]$Archive,[string]$Destination,$Tree) {
    [void](Assert-GitCandidatePath $Archive);[void](Assert-GitCandidatePath $Destination)
    if (Test-Path -LiteralPath $Destination) {throw 'GIT_CANDIDATE_OUTPUT_EXISTS'}
    $null=New-Item -ItemType Directory -Path $Destination
    $stream=[IO.File]::OpenRead($Archive);$reader=[System.Formats.Tar.TarReader]::new($stream)
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase);$total=0L
    $directories=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in $Tree.Keys) {
        $parent=$path
        while ($parent.LastIndexOf('/') -ge 0) {$parent=$parent.Substring(0,$parent.LastIndexOf('/'));[void]$directories.Add($parent)}
    }
    try {
        while ($null -ne ($entry=$reader.GetNextEntry())) {
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::GlobalExtendedAttributes) {continue}
            $name=$entry.Name.TrimEnd('/')
            Assert-GitCandidateRelative $name
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::Directory) {
                if (-not $directories.Contains($name)) {throw 'GIT_CANDIDATE_ARCHIVE_EXTRA'}
                continue
            }
            if ($entry.EntryType -notin @([System.Formats.Tar.TarEntryType]::RegularFile,[System.Formats.Tar.TarEntryType]::V7RegularFile) -or
                -not $Tree.ContainsKey($name) -or $Tree[$name].path -cne $name -or -not $seen.Add($name) -or
                $entry.Length -gt 134217728) {throw 'GIT_CANDIDATE_ARCHIVE_ENTRY'}
            $total+=$entry.Length;if ($total -gt 1073741824) {throw 'GIT_CANDIDATE_ARCHIVE_LIMIT'}
            $memory=[IO.MemoryStream]::new()
            try {
                if ($entry.Length) {$entry.DataStream.CopyTo($memory)}
                $bytes=$memory.ToArray();if ($bytes.Length -ne $entry.Length) {throw 'GIT_CANDIDATE_ARCHIVE_LENGTH'}
                $digest=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA1)
                try {$digest.AppendData([Text.Encoding]::UTF8.GetBytes('blob '+$bytes.Length+[char]0));$digest.AppendData($bytes);$sha=[Convert]::ToHexString($digest.GetHashAndReset()).ToLowerInvariant()} finally {$digest.Dispose()}
                if ($sha -cne $Tree[$name].blobSha) {throw 'GIT_CANDIDATE_ARCHIVE_BLOB_MISMATCH'}
                $target=[IO.Path]::GetFullPath((Join-Path $Destination $name));[void](Assert-GitCandidatePath $target)
                if (-not $target.StartsWith($Destination+'\',[StringComparison]::Ordinal)) {throw 'GIT_CANDIDATE_ARCHIVE_ESCAPE'}
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
                $output=[IO.File]::Open($target,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
                try {$output.Write($bytes);$output.Flush($true)} finally {$output.Dispose()}
            } finally {$memory.Dispose()}
        }
        if ($seen.Count -ne $Tree.Count) {throw 'GIT_CANDIDATE_ARCHIVE_INCOMPLETE'}
    } finally {$reader.Dispose();$stream.Dispose()}
}

# AST-only validators из pinned current tooling, не из потенциально старого Git snapshot.
function New-GitCandidateValidators([string]$CandidateText,[string]$PolicyFile) {
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseInput($CandidateText,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'GIT_CANDIDATE_VALIDATOR_PARSE'}
    $definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($definition in $definitions) {if ($definition.Name.Contains(':') -or -not $names.Add($definition.Name)) {throw 'GIT_CANDIDATE_VALIDATOR_EXPORT'}}
    foreach ($name in 'Assert-CandidateSourceClosure','Get-CandidateSourcePins','Assert-CandidatePins','Assert-CandidateVersion',
        'Get-CandidateImageInventory','Copy-CandidateImage','New-CandidateOwnership','New-CandidateWriter') {
        if (-not $names.Contains($name)) {throw 'GIT_CANDIDATE_VALIDATOR_EXPORT'}
    }
    $module=New-Module -ScriptBlock ([scriptblock]::Create(($definitions.Extent.Text -join "`n")+"`nExport-ModuleMember -Function *"))
    & $module {param($file) $script:CandidateSourceValidator=$file} $PolicyFile
    return $module
}

# Единственная Maven граница. Explicit empty settings и отдельный repo используются в ОБОИХ goals.
function Assert-GitCandidateBuildConfig([string]$Stage) {
    foreach ($relative in '.mvn/maven.config','.mvn/jvm.config') {
        $path=Join-Path $Stage $relative
        if (Test-Path -LiteralPath $path) {
            [void](Assert-GitCandidatePath $path)
            $text=[IO.File]::ReadAllText($path)
            if ($text -match '(?m)(?:^|\s)(?:-D\s*(?:maven\.(?:repo\.local|multiModuleProjectDirectory|home|user\.home)|user\.home|skipTests|maven\.test\.skip|app\.commit|app\.release)(?:=|\s|$)|-(?:pl|rf|gs|s|f|N)(?:\S*|\s|$)|--(?:projects|resume-from|non-recursive|settings|global-settings|file)(?:=|\s|$))') {throw 'GIT_CANDIDATE_CONFIG_OVERRIDE'}
        }
    }
}

# Полный reactor запускается без environment injection и без skip в install.
function Invoke-GitCandidateMaven([string]$Maven,[string]$Stage,[string]$Jdk,[string]$Repo,[string]$Settings,[int]$Release,[string]$Commit,[string]$Goal,$Writer) {
    if ($Goal -cnotin @('install','package')) {throw 'GIT_CANDIDATE_MAVEN_GOAL'}
    Assert-GitCandidateBuildConfig $Stage
    $saved=@{};$pushed=$false
    foreach ($name in 'JAVA_HOME','MAVEN_ARGS','MAVEN_OPTS','MAVEN_PROJECTBASEDIR','MAVEN_CMD_LINE_ARGS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH') {
        $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
    }
    try {
        foreach ($name in $saved.Keys) {[Environment]::SetEnvironmentVariable($name,$null,'Process')}
        $env:JAVA_HOME=$Jdk;Push-Location -LiteralPath $Stage;$pushed=$true
        $arguments=@('-B','-s',$Settings,'-gs',$Settings,"-Dmaven.repo.local=$Repo","-Dapp.release=$Release","-Dapp.commit=$Commit")
        if ($Goal -ceq 'package') {$arguments+=@('-Pdist','-DskipTests')}
        else {$arguments+=@('-DskipTests=false','-Dmaven.test.skip=false')}
        & $Maven @arguments $Goal 2>&1 | ForEach-Object {$Writer.WriteLine([string]$_)}
        $Writer.Flush();if ($LASTEXITCODE -ne 0) {throw ('GIT_CANDIDATE_MAVEN_FAILED '+$Goal)}
    } finally {
        if ($pushed) {Pop-Location};foreach ($name in $saved.Keys) {[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    }
}

# Staged snapshot immutable даже после failed Maven; generated targets определены existing reactor.
function Invoke-GitCandidateBuild($Validators,$Owner,[string]$Stage,$Pins,[string[]]$Targets,[string]$Maven,[string]$Jdk,$Commit,[string]$Cell) {
    $repo=Join-Path $Owner.root ($Cell+'-maven-repo');$settings=Join-Path $Owner.root ($Cell+'-settings.xml')
    $null=New-Item -ItemType Directory -Path $repo
    $writer=& $Validators {param($path,$owner) New-CandidateWriter $path $owner} $settings $Owner
    try {$writer.Write('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>')} finally {$writer.Dispose()}
    foreach ($goal in 'install','package') {
        $log=Join-Path $Owner.root ($Cell+'-'+$goal+'.log')
        $writer=& $Validators {param($path,$owner) New-CandidateWriter $path $owner} $log $Owner
        $failure=$null
        try {Invoke-GitCandidateMaven $Maven $Stage $Jdk $repo $settings $Commit.releaseNumber $Commit.commitSha $goal $writer}
        catch {$failure=$_}
        finally {
            $writer.Dispose()
            try {& $Validators {param($stage,$pins,$targets) Assert-CandidatePins $stage $pins 'GIT_CANDIDATE_BUILD_CHANGED_SOURCE' $targets} $Stage $Pins $Targets}
            catch {if ($null -ne $failure) {throw ($failure.Exception.Message+' | '+$_.Exception.Message)};throw}
        }
        if ($null -ne $failure) {throw $failure}
    }
    $image=Join-Path $Stage 'dist/target/dist/CashPrediction'
    $destination=Join-Path $Owner.root ($Cell+'-image')
    $inventory=& $Validators {param($image) Get-CandidateImageInventory $image} $image
    & $Validators {param($image,$release,$sha) [void](Assert-CandidateVersion $image $release $sha)} $image $Commit.releaseNumber $Commit.commitSha
    & $Validators {param($image,$dest,$owner,$inventory) Copy-CandidateImage $image $dest $owner $inventory} $image $destination $Owner $inventory
    $record=& $Validators {param($image,$release,$sha) Assert-CandidateVersion $image $release $sha} $destination $Commit.releaseNumber $Commit.commitSha
    $record | Add-Member files @($inventory.Values)
    return $record
}

# Разные commit metadata не становятся обещанием разных байтов или поведения приложения.
function Get-GitCandidateSourceComparison([string[]]$Digests) {
    if (@($Digests).Count -ne 3 -or @($Digests | Where-Object {$_ -cnotmatch '^[0-9a-f]{64}$'}).Count) {throw 'GIT_CANDIDATE_SOURCE_DIGESTS'}
    if (@($Digests | Sort-Object -Unique).Count -eq 1) {return 'IDENTICAL_NO_CODE_DIFFERENCE_CLAIM'}
    return 'CONTENT_DIFFERENCES_NOT_BEHAVIOR_PROOF'
}

# Архивирование каждого commit и staging выполняются только после полного preflight цепочки.
function Invoke-NewGitUpdateCandidateImages([string]$Repository,[string]$Git,[string[]]$CommitIds,[string]$OutputRoot,
    [string]$PackSourceSha256,[string]$CandidateValidatorSha256,[string]$Maven,[string]$JdkHome,[switch]$Build) {
    $ErrorActionPreference='Stop';Set-StrictMode -Version 3
    if (-not $IsWindows) {throw 'GIT_CANDIDATE_WINDOWS_REQUIRED'}
    foreach ($path in @($Repository,$Git,$OutputRoot)) {[void](Assert-GitCandidatePath $path)}
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if ([IO.Path]::GetDirectoryName($OutputRoot) -cne $temp -or [IO.Path]::GetFileName($OutputRoot) -cnotmatch '^cp-git-candidates-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
        (Test-Path -LiteralPath $OutputRoot) -or $OutputRoot.StartsWith($Repository+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'GIT_CANDIDATE_NEW_TEMP_ROOT_REQUIRED'}
    $policy=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../dist/scripts/Pack-Source.ps1'))
    $candidate=Join-Path $PSScriptRoot 'New-UpdateCandidateImages.ps1'
    $policyBytes=Read-GitCandidateTool $policy $PackSourceSha256;$candidateBytes=Read-GitCandidateTool $candidate $CandidateValidatorSha256
    $chain=Resolve-GitCandidateChain $Git $Repository $CommitIds
    if ($Build) {
        [void](Assert-GitCandidatePath $Maven);[void](Assert-GitCandidatePath $JdkHome)
        if (-not (Test-Path -LiteralPath $Maven -PathType Leaf) -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/jpackage.exe') -PathType Leaf)) {throw 'GIT_CANDIDATE_BUILD_TOOLS'}
    }
    $validators=New-GitCandidateValidators ([Text.UTF8Encoding]::new($false,$true).GetString($candidateBytes)) $policy
    $owner=$null
    try {
        $owner=& $validators {param($root) New-CandidateOwnership $root} $OutputRoot
        $frozenPolicy=Join-Path $OutputRoot 'Pack-Source.frozen.ps1'
        $policyStream=[IO.File]::Open($frozenPolicy,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$policyStream.Write($policyBytes);$policyStream.Flush($true)} finally {$policyStream.Dispose()}
        & $validators {param($file) $script:CandidateSourceValidator=$file} $frozenPolicy
        $records=@(for ($index=0;$index -lt 3;$index++) {
            $cell=@('B1','B2','T')[$index];$commit=$chain[$index]
            $archive=Join-Path $OutputRoot ($cell+'.tar');$raw=Join-Path $OutputRoot ($cell+'-git-source');$stage=Join-Path $OutputRoot ($cell+'-source')
            $tree=Get-GitCandidateTree (Invoke-GitCandidateRead $Git $Repository @('ls-tree','-rz',$commit.commitSha))
            [void](Invoke-GitCandidateRead $Git $Repository @('archive','--format=tar',$commit.commitSha) $archive)
            Expand-GitCandidateArchive $archive $raw $tree
            # Exact pinned Pack-Source body, StageOnly: один stage policy, не новый allowlist.
            & ([scriptblock]::Create([Text.UTF8Encoding]::new($false,$true).GetString($policyBytes))) -SourceRoot $raw -StageDirectory $stage -StageOnly | Out-Null
            $targets=& $validators {param($root) Assert-CandidateSourceClosure $root} $stage
            $pins=& $validators {param($root) Get-CandidateSourcePins $root} $stage
            $serialized=ConvertTo-Json -InputObject @($pins.GetEnumerator() | ForEach-Object {[ordered]@{path=$_.Key;sha256=$_.Value}}) -Compress
            $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($serialized))).ToLowerInvariant()
            $image=$null;if ($Build) {$image=Invoke-GitCandidateBuild $validators $owner $stage $pins $targets $Maven $JdkHome $commit $cell}
            [pscustomobject]@{role=$cell;commitSha=$commit.commitSha;releaseNumber=$commit.releaseNumber;treeSha=$commit.treeSha;
                archive=$archive;archiveSha256=(Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant();archiveBlobValidation='EXACT_GIT_TREE';
                sourceSnapshot=$stage;sourcePins=$pins;deliveredSourceSha256=$digest;
                sourceTransformation='PINNED_PACK_SOURCE_POLICY_AND_DELIVERED_POM_ONLY';image=$image}
        })
        [void](Read-GitCandidateTool $policy $PackSourceSha256);[void](Read-GitCandidateTool $candidate $CandidateValidatorSha256)
        $receipt=[ordered]@{schemaVersion=1;status=$(if ($Build) {'BUILT_GIT_CANDIDATES'} else {'SOURCE_PREPARED'});
            nativeMatrix='PENDING';releaseSignoff='PENDING';gitProvenance='LOCAL_EXACT_GIT_COMMITS_NOT_PUBLICATION';repository=$Repository;
            policyPin=[ordered]@{path=$policy;sha256=$PackSourceSha256};frozenPolicyPin=[ordered]@{path=$frozenPolicy;sha256=$PackSourceSha256};
            validatorPin=[ordered]@{path=$candidate;sha256=$CandidateValidatorSha256};
            deliveredSourceComparison=(Get-GitCandidateSourceComparison $records.deliveredSourceSha256);snapshots=$records}
        $receiptPath=Join-Path $OutputRoot 'git-candidate-images.json'
        $writer=& $validators {param($path,$owner) New-CandidateWriter $path $owner} $receiptPath $owner
        try {$writer.Write((ConvertTo-Json -InputObject $receipt -Depth 24))} finally {$writer.Dispose()}
        return [pscustomobject]@{status=$receipt.status;nativeMatrix='PENDING';receipt=$receiptPath;receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash.ToLowerInvariant();snapshots=$records}
    } finally {if ($null -ne $owner) {$owner.stream.Dispose()};Remove-Module -ModuleInfo $validators -Force}
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-NewGitUpdateCandidateImages -Repository $Repository -Git $Git -CommitIds $CommitIds -OutputRoot $OutputRoot `
        -PackSourceSha256 $PackSourceSha256 -CandidateValidatorSha256 $CandidateValidatorSha256 -Maven $Maven -JdkHome $JdkHome -Build:$Build
}
