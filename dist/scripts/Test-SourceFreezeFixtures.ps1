<#
.SYNOPSIS
Проверяет закрепление состава исходников без сборки и без финального архива.
#>
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Pack-Source.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'FREEZE_PARSE' }
foreach ($name in @('Test-ExcludedDirectory', 'Test-IncludedFile', 'Get-SourceFreezeInventory', 'Assert-SourceFreezeInventory')) {
    $definitions = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $true))
    if ($definitions.Count -ne 1) { throw 'FREEZE_FUNCTION' }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}

# Проверяет отказ именно guards, не смешивая его с ошибкой подготовки данных.
function Assert-FreezeRejected($Before, $After) {
    $rejected = $false
    try { Assert-SourceFreezeInventory $Before $After } catch { $rejected = $true }
    if (-not $rejected) { throw 'FREEZE_ACCEPTED_MUTATION' }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('cp-source-freeze-' + [guid]::NewGuid().ToString())
$resolved = [IO.Path]::GetFullPath($fixture)
$null = New-Item -ItemType Directory -Path $resolved
try {
    $file = Join-Path $resolved 'pom.xml'
    [IO.File]::WriteAllText($file, 'one')
    $before = Get-SourceFreezeInventory $resolved
    Assert-SourceFreezeInventory $before (Get-SourceFreezeInventory $resolved)
    $added = Join-Path $resolved 'core/src/main/java/New.java'
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($added))
    [IO.File]::WriteAllText($added, 'new')
    Assert-FreezeRejected $before (Get-SourceFreezeInventory $resolved)
    Remove-Item -LiteralPath $added
    [IO.File]::WriteAllText($file, 'two')
    Assert-FreezeRejected $before (Get-SourceFreezeInventory $resolved)
    [IO.File]::WriteAllText($added, 'one')
    Remove-Item -LiteralPath $file
    Assert-FreezeRejected $before (Get-SourceFreezeInventory $resolved)
    [IO.File]::WriteAllText($file, 'one')
    Remove-Item -LiteralPath $added
    [IO.File]::WriteAllText((Join-Path $resolved 'AGENTS.md'), 'excluded')
    Assert-SourceFreezeInventory $before (Get-SourceFreezeInventory $resolved)
    Write-Host 'PASS: 5 source freeze checks (unchanged, added, changed, replaced, excluded).'
} finally {
    if ([IO.Path]::GetDirectoryName($resolved) -cne [IO.Path]::GetTempPath().TrimEnd([char[]]'\/') -or
        [IO.Path]::GetFileName($resolved) -notmatch '^cp-source-freeze-[0-9a-f-]{36}$' -or
        (Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'FREEZE_CLEANUP_SCOPE'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
