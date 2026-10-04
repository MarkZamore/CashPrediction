<#
.SYNOPSIS Создаёт детерминированный full ZIP и обе дельты с независимым round-trip.
.DESCRIPTION Использует только существующий JDK и уже собранный update-tool. GitHub не изменяет.
#>
param(
    [Parameter(Mandatory)][string]$ImageRoot,
    [Parameter(Mandatory)][string]$StateFile,
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [Parameter(Mandatory)][string]$Note
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"
$state = Read-S7Json $StateFile
$command = $state.toolCommand
$java = [string]$command.java
$toolArguments = [string[]]$command.arguments
$bases = @($state.bases | Sort-Object releaseNumber -Descending)
if ($bases.Count -gt 2) { throw 'S7_BASE_COUNT' }
if (Test-Path -LiteralPath $ArtifactDirectory) { throw 'S7_ARTIFACT_DIRECTORY_EXISTS' }
if (Test-Path -LiteralPath $WorkDirectory) { throw 'S7_PREPARE_WORK_EXISTS' }
$null = New-Item -ItemType Directory -Path $ArtifactDirectory
$null = New-Item -ItemType Directory -Path $WorkDirectory
$ImageRoot = (Resolve-Path -LiteralPath $ImageRoot).Path
if ([IO.Path]::GetFileName($ImageRoot) -cne 'CashPrediction') { throw 'S7_IMAGE_ROOT_NAME' }
$inventoryPath = Join-Path $WorkDirectory 'target-inventory.json'
Invoke-S7Tool $java $toolArguments @('inventory', '--root', $ImageRoot, '--out', $inventoryPath) | Out-Null
$inventory = Read-S7Json $inventoryPath
# jar получает явный список inventory, не рекурсивный каталог с возможным CashMemory.
# @argfile исключает лимит командной строки Windows; UTF-8 и timestamp фиксированы.
$jar = Join-Path ([IO.Path]::GetDirectoryName($java)) 'jar.exe'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'S7_JDK_JAR_MISSING' }
$zip = Join-Path $ArtifactDirectory 'CashPrediction-portable.zip'
$parent = [IO.Path]::GetDirectoryName($ImageRoot).Replace('\', '/')
$arguments = [Collections.Generic.List[string]]::new()
$arguments.Add('--create'); $arguments.Add('--no-manifest'); $arguments.Add('--date=2000-01-01T00:00:00Z')
$arguments.Add('--file'); $arguments.Add('"' + $zip.Replace('\', '/') + '"')
foreach ($entry in $inventory.files) {
    Assert-S7Path $entry.path
    $arguments.Add('-C'); $arguments.Add('"' + $parent + '"')
    $arguments.Add('"CashPrediction/' + $entry.path + '"')
}
$argumentFile = Join-Path $WorkDirectory 'jar.args'
[IO.File]::WriteAllLines($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
& $jar "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw 'S7_FULL_ZIP_FAILED' }
$published = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
$seedManifest = Join-Path $WorkDirectory 'target.json'
$manifestArguments = @('manifest', '--root', $ImageRoot, '--archive', $zip,
    '--release', [string]$state.releaseNumber, '--commit', [string]$state.commitSha,
    '--version', [string]$state.releaseNumber, '--published-at', $published)
Invoke-S7Tool $java $toolArguments ($manifestArguments + @('--out', $seedManifest)) | Out-Null
$target = Read-S7Json $seedManifest
$seedSha256 = (Get-FileHash -LiteralPath $seedManifest).Hash
# Проверка full ZIP отдельно от обоих delta apply: восстановленные bytes и атрибуты.
$fullRoot = Join-Path $WorkDirectory 'full-roundtrip'
Expand-S7Full $zip $fullRoot $target
Invoke-S7Tool $java $toolArguments @('verify', '--root', $fullRoot, '--manifest', $seedManifest) | Out-Null
if ($state.previous -and $state.previous.releaseNumber -eq $state.releaseNumber -and
    $state.previous.treeSha256 -cne $target.treeSha256) { throw 'S7_RERUN_TREE_CHANGED' }
$jobs = @()
try {
    for ($index = 0; $index -lt $bases.Count; $index++) {
        $base = $bases[$index]
        $assetName = if ($index -eq 0) { 'CashPrediction.cpdelta' } else { "CashPrediction.from-$($base.releaseNumber).cpdelta" }
        $jobs += Start-Job -Name "S7-delta-$($base.releaseNumber)" -ScriptBlock {
            param($library, $java, $toolArguments, $base, $targetRoot, $seed, $zip, $patch, $work, $manifestArguments, $seedSha256)
            $ErrorActionPreference = 'Stop'
            . $library
            $null = New-Item -ItemType Directory -Path $work
            if (-not (New-S7VerifiedDelta $java $toolArguments $base $targetRoot $seed $seedSha256 $patch $work $manifestArguments)) {
                return 'S7_DELTA_LIMIT_SKIPPED'
            }
            $descriptorPath = Join-Path $work 'descriptor.json'
            Write-S7Json $descriptorPath ([ordered]@{
                algorithm = 'cashprediction-tree-delta'; algorithmVersion = 1
                baseReleaseNumber = [int]$base.releaseNumber; baseCommitSha = $base.commitSha
                baseTreeSha256 = $base.treeSha256; assetName = [IO.Path]::GetFileName($patch)
                sizeBytes = (Get-Item -LiteralPath $patch).Length
                sha256 = (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash.ToLowerInvariant()
            })
            $manifestPath = Join-Path $work 'roundtrip-manifest.json'
            Invoke-S7Tool $java $toolArguments ($manifestArguments + @('--delta', $descriptorPath, '--out', $manifestPath)) | Out-Null
            $roundtrip = Join-Path $work 'roundtrip'
            Invoke-S7Tool $java $toolArguments @('apply', '--base', $base.root,
                '--base-release', [string]$base.releaseNumber, '--base-commit', $base.commitSha,
                '--patch', $patch, '--manifest', $manifestPath, '--out', $roundtrip) | Out-Null
            Invoke-S7Tool $java $toolArguments @('verify', '--root', $roundtrip, '--manifest', $manifestPath) | Out-Null
            # Сравниваем также весь FileEntry[] независимо от успеха apply.
            $roundtripInventory = Join-Path $work 'roundtrip-inventory.json'
            Invoke-S7Tool $java $toolArguments @('inventory', '--root', $roundtrip, '--out', $roundtripInventory) | Out-Null
            $actual = Read-S7Json $roundtripInventory
            $expected = Read-S7Json $seed
            if ($actual.treeSha256 -cne $expected.treeSha256 -or
                (ConvertTo-Json -InputObject @($actual.files) -Depth 10 -Compress) -cne
                (ConvertTo-Json -InputObject @($expected.files) -Depth 10 -Compress)) { throw 'S7_ROUNDTRIP_MISMATCH' }
            return $descriptorPath
        } -ArgumentList (Join-Path $PSScriptRoot 'S7-Release.ps1'), $java, $toolArguments, $base,
            $ImageRoot, $seedManifest, $zip, (Join-Path $ArtifactDirectory $assetName),
            (Join-Path $WorkDirectory "delta-$index"), $manifestArguments, $seedSha256
    }
    $descriptors = @()
    if ($jobs.Count) { Wait-Job -Job $jobs | Out-Null }
    foreach ($job in $jobs) {
        $result = @(Receive-Job -Job $job -ErrorAction Stop)
        if ($job.State -eq 'Completed' -and $result.Count -eq 1 -and $result[0] -ceq 'S7_DELTA_LIMIT_SKIPPED') {
            continue
        }
        if ($job.State -ne 'Completed' -or $result.Count -ne 1 -or -not (Test-Path -LiteralPath $result[0] -PathType Leaf)) {
            throw "S7_DELTA_JOB_FAILED: $($job.Name)"
        }
        $descriptors += [string]$result[0]
    }
    # Сохраняем все пригодные direct дельты, даже если другая база превысила лимит.
    # Первое canonical имя получает ближайшая пригодная база; bytes/size/SHA не меняются.
    # Обе verified базы остаются в state для retention, независимо от deltaPatches.
    $descriptors = @($descriptors | Sort-Object { (Read-S7Json $_).baseReleaseNumber } -Descending)
    for ($index = 0; $index -lt $descriptors.Count; $index++) {
        $entry = Read-S7Json $descriptors[$index]
        $name = if ($index -eq 0) { 'CashPrediction.cpdelta' } else { "CashPrediction.from-$($entry.baseReleaseNumber).cpdelta" }
        Set-S7DeltaAssetName $ArtifactDirectory $descriptors[$index] $name
    }
    $finalArguments = $manifestArguments + @('--out', (Join-Path $ArtifactDirectory 'update.json'))
    foreach ($descriptor in $descriptors) { $finalArguments += @('--delta', $descriptor) }
    Invoke-S7Tool $java $toolArguments $finalArguments | Out-Null
} finally {
    foreach ($job in $jobs) {
        if ($job.State -in 'Running', 'NotStarted') { Stop-Job -Job $job }
        Remove-Job -Job $job -Force
    }
}
Write-S7Json (Join-Path $ArtifactDirectory 'release.json') ([ordered]@{
    schemaVersion = 1; releaseNumber = [int]$state.releaseNumber; commitSha = $state.commitSha
    publishedAtUtc = $published; assetName = 'CashPrediction-portable.zip'
    sizeBytes = (Get-Item -LiteralPath $zip).Length
    sha256 = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant(); note = $Note
})
Assert-S7Artifacts $ArtifactDirectory | Out-Null
