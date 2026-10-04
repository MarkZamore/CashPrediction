<# Выбирает доказанную базу сравнения; сбой API или истории не разрешает пропуск проверок. #>
#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('CI','Release')][string]$Mode = 'CI',
    [string]$Repository = (Join-Path $PSScriptRoot '../..'),
    [string]$GithubRepository = $env:GITHUB_REPOSITORY,
    [string]$Branch = $env:GITHUB_REF_NAME,
    [string]$HeadSha = $env:GITHUB_SHA,
    [string]$WorkflowFile = 'ci.yml',
    [string]$GithubOutput = $env:GITHUB_OUTPUT,
    [switch]$ForceFull
)

# Проверяет локальную identity и достижимость без fetch или изменения checkout.
function Test-CiBaselineAncestor {
    param([string]$Directory, [string]$Candidate, [string]$Head)
    if ($Candidate -cnotmatch '^[0-9a-f]{40}$' -or $Head -cnotmatch '^[0-9a-f]{40}$') { return $false }
    $commit = & git -C $Directory rev-parse --verify "$Candidate`^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0 -or "$commit".Trim() -cne $Candidate) { return $false }
    & git -C $Directory merge-base --is-ancestor $Candidate $Head 2>$null
    return $LASTEXITCODE -eq 0
}

# Успешный push той же workflow/ветки является базой быстрых проверок, failed/cancelled - никогда.
function Find-CiSuccessfulBaseline {
    param([string]$Directory, [string]$Repo, [string]$BranchName, [string]$Head, [string]$Workflow)
    $encodedBranch = [Uri]::EscapeDataString($BranchName)
    $encodedWorkflow = [Uri]::EscapeDataString($Workflow)
    for ($page = 1; $page -le 3; $page++) {
        $response = @(Invoke-Gh api "repos/$Repo/actions/workflows/$encodedWorkflow/runs?branch=$encodedBranch&event=push&status=success&per_page=100&page=$page")
        if ($LASTEXITCODE -ne 0) { throw 'CI_BASE_API_FAILED' }
        $runResponse = $response -join "`n" | ConvertFrom-Json -NoEnumerate
        if ($null -eq $runResponse -or $runResponse.GetType().FullName -cne 'System.Management.Automation.PSCustomObject') { throw 'CI_BASE_API_SCHEMA' }
        $runs = $runResponse.workflow_runs
        if ($runs -isnot [array]) { throw 'CI_BASE_API_SCHEMA' }
        foreach ($run in $runs) {
            # PowerShell сравнение коллекций возвращает matching элементы: массив вместо scalar недопустим.
            if ($null -eq $run -or $run.GetType().FullName -cne 'System.Management.Automation.PSCustomObject' -or
                ($run.id -isnot [long] -and $run.id -isnot [int]) -or $run.id -le 0 -or
                $run.status -isnot [string] -or $run.conclusion -isnot [string] -or
                $run.event -isnot [string] -or $run.head_branch -isnot [string] -or
                $run.head_sha -isnot [string]) { throw 'CI_BASE_RUN_SCHEMA' }
            if ($run.status -cne 'completed' -or $run.conclusion -cne 'success' -or
                $run.event -cne 'push' -or $run.head_branch -cne $BranchName -or
                [string]$run.id -ceq $env:GITHUB_RUN_ID -or $run.head_sha -ceq $Head) { continue }
            if (Test-CiBaselineAncestor $Directory $run.head_sha $Head) { return [string]$run.head_sha }
        }
        if (@($runs).Count -lt 100) { break }
    }
    return ''
}

# Релиз сравнивается с опубликованным latest, а не с docs-only успешным workflow без публикации.
function Find-CiPublishedBaseline {
    param([string]$Directory, [string]$Repo, [string]$Head)
    $response = @(Invoke-Gh api "repos/$Repo/releases/tags/latest")
    if ($LASTEXITCODE -ne 0) { throw 'CI_RELEASE_BASE_API_FAILED' }
    $release = $response -join "`n" | ConvertFrom-Json -NoEnumerate
    if ($null -eq $release -or $release.GetType().FullName -cne 'System.Management.Automation.PSCustomObject') { throw 'CI_RELEASE_BASE_API_SCHEMA' }
    $assets = @($release.assets)
    $names = @($assets | ForEach-Object { $_.name })
    $uniqueNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $names) {
        if ($name -isnot [string] -or -not $uniqueNames.Add($name)) { throw 'CI_RELEASE_BASE_ASSET_SCHEMA' }
    }
    if ($release -isnot [pscustomobject] -or $release.assets -isnot [array] -or
        $release.tag_name -isnot [string] -or
        $release.draft -isnot [bool] -or $release.prerelease -isnot [bool] -or
        $release.draft -ne $false -or $release.prerelease -ne $false -or
        $release.tag_name -cne 'latest' -or
        $names -cnotcontains 'CashPrediction-portable.zip' -or $names -cnotcontains 'release.json') {
        throw 'CI_RELEASE_BASE_UNPROVEN'
    }
    $commit = & git -C $Directory rev-parse --verify 'refs/tags/latest^{commit}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'CI_RELEASE_BASE_TAG_MISSING' }
    $candidate = "$commit".Trim()
    # GitHub tag и checkout могут разойтись во время конкурентной публикации: безопасно пересобираем.
    $tagResponse = @(Invoke-Gh api "repos/$Repo/commits/latest")
    $tagIdentity = $tagResponse -join "`n" | ConvertFrom-Json -NoEnumerate
    if ($LASTEXITCODE -ne 0 -or $null -eq $tagIdentity -or
        $tagIdentity.GetType().FullName -cne 'System.Management.Automation.PSCustomObject' -or
        $tagIdentity.sha -isnot [string] -or $tagIdentity.sha -cne $candidate) {
        throw 'CI_RELEASE_BASE_TAG_CHANGED'
    }
    if (-not (Test-CiBaselineAncestor $Directory $candidate $Head)) { throw 'CI_RELEASE_BASE_NOT_ANCESTOR' }
    # Сам tag не подтверждает identity архива: проверяем опубликованный указатель, не скачивая runtime.
    $pointer = @($assets | Where-Object name -CEQ 'release.json')[0]
    $archive = @($assets | Where-Object name -CEQ 'CashPrediction-portable.zip')[0]
    if (($pointer.id -isnot [long] -and $pointer.id -isnot [int]) -or $pointer.id -le 0 -or
        ($pointer.size -isnot [long] -and $pointer.size -isnot [int]) -or
        ($archive.size -isnot [long] -and $archive.size -isnot [int]) -or
        $pointer.size -le 0 -or $pointer.size -gt 65536 -or $archive.size -le 0) {
        throw 'CI_RELEASE_BASE_POINTER_SCHEMA'
    }
    $metadataResponse = @(Invoke-Gh api "repos/$Repo/releases/assets/$($pointer.id)" -H 'Accept: application/octet-stream')
    if ($LASTEXITCODE -ne 0) { throw 'CI_RELEASE_BASE_POINTER_API_FAILED' }
    $metadataText = $metadataResponse -join "`n"
    if ([Text.Encoding]::UTF8.GetByteCount($metadataText) -gt 65536) { throw 'CI_RELEASE_BASE_POINTER_TOO_LARGE' }
    $metadata = $metadataText | ConvertFrom-Json -NoEnumerate
    $count = & git -C $Directory rev-list --count $candidate
    if ($LASTEXITCODE -ne 0 -or "$count".Trim() -notmatch '^[1-9][0-9]*$' -or
        $null -eq $metadata -or $metadata.GetType().FullName -cne 'System.Management.Automation.PSCustomObject' -or
        $metadata.commitSha -isnot [string] -or
        $metadata.assetName -isnot [string] -or $metadata.sha256 -isnot [string] -or
        ($metadata.schemaVersion -isnot [long] -and $metadata.schemaVersion -isnot [int]) -or
        $metadata.schemaVersion -ne 1 -or $metadata.commitSha -cne $candidate -or
        ($metadata.releaseNumber -isnot [long] -and $metadata.releaseNumber -isnot [int]) -or
        [string]$metadata.releaseNumber -cne "$count".Trim() -or
        $metadata.assetName -cne 'CashPrediction-portable.zip' -or
        ($metadata.sizeBytes -isnot [long] -and $metadata.sizeBytes -isnot [int]) -or
        $metadata.sizeBytes -ne $archive.size -or $metadata.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'CI_RELEASE_BASE_POINTER_IDENTITY'
    }
    return $candidate
}

if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    . (Join-Path $PSScriptRoot 'GhRetry.ps1')
    $base = ''
    $fallback = [bool]$ForceFull
    if (-not $fallback) {
        try {
            if ($GithubRepository -cnotmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or
                [string]::IsNullOrWhiteSpace($Branch) -or $HeadSha -cnotmatch '^[0-9a-f]{40}$') {
                throw 'CI_BASE_INPUT_INVALID'
            }
            $shallow = & git -C $Repository rev-parse --is-shallow-repository
            if ($LASTEXITCODE -ne 0 -or "$shallow".Trim() -cne 'false') { throw 'CI_BASE_SHALLOW_HISTORY' }
            $base = if ($Mode -eq 'Release') { Find-CiPublishedBaseline $Repository $GithubRepository $HeadSha }
                else { Find-CiSuccessfulBaseline $Repository $GithubRepository $Branch $HeadSha $WorkflowFile }
            if (-not $base) { throw 'CI_BASE_NO_SUCCESSFUL_ANCESTOR' }
        } catch {
            $fallback = $true
            Write-Warning "Full checks required: $($_.Exception.Message)"
        }
    }
    if ($GithubOutput) {
        # Явная строка не требует преобразования PowerShell object[] в IEnumerable<string> на runner.
        $outputLines = @("base_sha=$base", "force_full=$($fallback.ToString().ToLowerInvariant())")
        [IO.File]::AppendAllText($GithubOutput, ($outputLines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    }
    [pscustomobject]@{baseSha=$base;forceFull=$fallback} | ConvertTo-Json -Compress
}
