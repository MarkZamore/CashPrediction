<# .SYNOPSIS Архивирует проверенные базы и публикует подготовленный пакет S7. #>
param(
    [Parameter(Mandatory)][string]$StateFile,
    [Parameter(Mandatory)][string]$ArtifactDirectory,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [Parameter(Mandatory)][string]$Title,
    [Parameter(Mandatory)][string]$Notes,
    [Parameter(Mandatory)][string]$ApprovedCommitSha
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"
$state = Read-S7Json $StateFile
if ($ApprovedCommitSha -cne $state.commitSha -or $ApprovedCommitSha -cnotmatch '^[0-9a-f]{40}$') {
    throw 'S7_RELEASE_MATRIX_NOT_APPROVED'
}
$env:GH_REPO = $state.repository
$update = Assert-S7Artifacts $ArtifactDirectory
if ($update.commitSha -cne $state.commitSha -or $update.releaseNumber -ne $state.releaseNumber) { throw 'S7_STATE_TARGET_MISMATCH' }
Assert-S7DeltaBases $update @($state.bases)
if (Test-Path -LiteralPath $WorkDirectory) { throw 'S7_PUBLISH_WORK_EXISTS' }
$null = New-Item -ItemType Directory -Path $WorkDirectory
$keep = @()
foreach ($base in $state.bases) {
    # Для обеих retained баз проверяем readback, даже когда тег уже существует.
    $keep += Save-S7Base $base $state.repository $WorkDirectory $state.toolCommand.java $state.toolCommand.arguments
}
Publish-S7Latest $ArtifactDirectory $state.repository $state.commitSha $Title $Notes $keep
