<# .SYNOPSIS Сохраняет проверенные опубликованные базы без изменения GitHub. #>
param(
    [Parameter(Mandatory)][int]$ReleaseNumber,
    [Parameter(Mandatory)][string]$CommitSha,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [Parameter(Mandatory)][string]$StateFile,
    [Parameter(Mandatory)][string]$ToolCommandFile
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"
$env:GH_REPO = $Repository
$command = Read-S7Json $ToolCommandFile
if ($ReleaseNumber -lt 1 -or $CommitSha -cnotmatch '^[0-9a-f]{40}$') { throw 'S7_TARGET_IDENTITY' }
if (Test-Path -LiteralPath $WorkDirectory) { throw 'S7_WORK_EXISTS' }
$null = New-Item -ItemType Directory -Path $WorkDirectory
$rows = @(Get-S7Releases $Repository)
$previous = $null
if (Test-GhApiResourceExists "repos/$Repository/releases/tags/latest") {
    $latestDirectory = Join-Path $WorkDirectory 'latest'
    try { $previous = Get-S7Base latest $latestDirectory $command.java $command.arguments }
    catch {
        # Частичная payload-first публикация может оставить старый pointer при новых bytes.
        # Только известная ошибка целостности разрешает использовать immutable archives.
        # Ошибки транспорта, permissions и неопределённый HTTP всегда останавливают job.
        if ($_.Exception.Message -notmatch '^S7_(BASE_INCOMPLETE|BASE_POINTER_MISMATCH|CONTAINER_MISMATCH|BASE_TREE_MISMATCH)$') { throw }
        Write-Host 'S7_LATEST_UNUSABLE_USING_IMMUTABLE_BASES'
        $releasePath = Join-Path $latestDirectory 'release.json'
        if (Test-Path -LiteralPath $releasePath -PathType Leaf) {
            $published = Read-S7ReleaseIdentity $releasePath
            if ($published.releaseNumber -gt $ReleaseNumber -or
                ($published.releaseNumber -eq $ReleaseNumber -and $published.commitSha -cne $CommitSha)) {
                throw 'S7_RELEASE_NOT_MONOTONIC'
            }
        }
    }
    if ($previous -and ($previous.releaseNumber -gt $ReleaseNumber -or
        ($previous.releaseNumber -eq $ReleaseNumber -and $previous.commitSha -cne $CommitSha))) {
        throw 'S7_RELEASE_NOT_MONOTONIC'
    }
    if ($previous -and $previous.commitSha -ceq $CommitSha -and $previous.releaseNumber -ne $ReleaseNumber) { throw 'S7_RERUN_NUMBER_CHANGED' }
}
$bases = [Collections.Generic.List[object]]::new()
if ($previous -and $previous.releaseNumber -lt $ReleaseNumber) { $bases.Add($previous) }
$archives = @($rows | Where-Object { -not $_.draft -and $_.tag_name -match '^update-base-[1-9][0-9]*$' } |
    Sort-Object { [int]($_.tag_name.Substring(12)) } -Descending)
foreach ($row in $archives) {
    $number = [int]$row.tag_name.Substring(12)
    if ($number -ge $ReleaseNumber) { continue }
    if ($bases.Count -ge 2 -and $number -lt ($bases | Measure-Object -Property releaseNumber -Minimum).Minimum) { break }
    $base = Get-S7Base $row.tag_name (Join-Path $WorkDirectory $row.tag_name) $command.java $command.arguments
    $sameNumber = @($bases | Where-Object { $_.releaseNumber -eq $base.releaseNumber })
    if ($sameNumber.Count) { Assert-S7SameBase $sameNumber[0] $base; continue }
    # Базы различаются полной identity; одинаковое содержимое разных релизов допустимо.
    $bases.Add($base)
    if ($bases.Count -gt 2) {
        $oldest = $bases | Sort-Object releaseNumber | Select-Object -First 1
        $null = $bases.Remove($oldest)
    }
}
Write-S7Json $StateFile ([ordered]@{
    releaseNumber = $ReleaseNumber; commitSha = $CommitSha; repository = $Repository
    previous = $previous; bases = @($bases | Sort-Object releaseNumber -Descending); toolCommand = $command
})
