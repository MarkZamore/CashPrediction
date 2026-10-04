<# Короткий автоматический CI: явные тесты границ вместо полной локальной приёмки. #>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Modules = 'core,update-tool,ui-fx,ui-swing,web,repository-doc-audits',
    [string]$Repository = (Join-Path $PSScriptRoot '../..'),
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
# Каждый модуль имеет непустой конечный набор существующих тестов без Robot и bootstrap sweep.
$catalog = [ordered]@{
    core = @('StartupDataBoundaryIntegrationTest','UpdateLifecycleOutcomeContractTest','ReconnectCredentialsTest','UpdateCodecTest','TreeDeltaEngineTest','SearchTextTest')
    'update-tool' = @('UpdateToolTransportTest')
    'ui-fx' = @('NoCyrillicLiteralsTest','NoDashesInFxUiTest','FxUpdateSessionTest')
    'ui-swing' = @('NoCyrillicLiteralsTest','NoDashesInSwingUiTest','SwingUpdateSessionTest')
    web = @('NoDashesInWebUiTest','SharedHttpContractTest','WebUpdateSessionTest')
    'repository-doc-audits' = @('DeveloperDocumentationTest','NoDashesInDocumentsTest','RepositoryDocumentsTest','ServiceBoundaryContractsTest','UiSpecCopyTest')
}
if ([string]::IsNullOrWhiteSpace($Modules)) { throw 'CI_SMOKE_EMPTY_MODULES' }
$selected = $Modules.Split(',')
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$tests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($module in $selected) {
    if ($module -cnotin $catalog.Keys -or -not $seen.Add($module)) { throw "CI_SMOKE_MODULE: $module" }
    foreach ($test in $catalog[$module]) {
        $matches = @(Get-ChildItem -LiteralPath (Join-Path $Repository "$module/src/test/java") -Recurse -File -Filter "$test.java")
        if ($matches.Count -ne 1) { throw "CI_SMOKE_TEST_MISSING_OR_AMBIGUOUS: $module/$test" }
        $null = $tests.Add($test)
    }
}
$selector = (@($tests) | Sort-Object) -join ','
$arguments = @('-B','-ntp','-pl',($selected -join ','),"-Dtest=$selector",'-Dsurefire.failIfNoSpecifiedTests=true','test')
Write-Host ('CI smoke: ' + ($selected -join ',') + '; full GUI/recovery/update matrix requires local acceptance or manual full_checks.')
if ($PlanOnly) { return [pscustomobject]@{modules=$selected;tests=$selector;arguments=$arguments;executed=$false} }
Push-Location -LiteralPath $Repository
try {
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw 'CI_SMOKE_FAILED' }
} finally { Pop-Location }
