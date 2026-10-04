#requires -Version 7.0
<#
.SYNOPSIS
Отдельная post-run финализация frozen S7 evidence, без повторного native запуска.
.DESCRIPTION
MAIN передаёт RequestFile/Sha256 только ПОСЛЕ записи results и batch seals.
ExpectedSourceIdentity/ContextSha256 и base/target commits закрепляются независимо
до run, не извлекаются caller из неподтверждённого request ради этой проверки.
Context.sourcePins обязан включать этот wrapper и Test-NativeUpdateEvidenceSignoff.ps1.
FinalizerSha256 закрепляет fixed соседний finalizer; импортируются только AST functions
в отдельный module scope, без runner body и ambient imports. Existing validator
проверяет native proofs, источник, конфигурации и canonical план, а не helper PASS.
Без Signoff только preflight PENDING, даже при полном наборе; CLI не запускается.
Signoff требует ровно 612 уникальных canonical keys и полное accepted evidence;
только существующий pinned finalizer запускает real JDK verifier и публикует receipt.
Wrapper не создаёт requests/seals/proofs, не меняет rows, не объявляет mock native PASS.
Commit pins здесь относятся к образам B1/B2/target, не к HEAD грязного checkout.
Context SHA/source pins отдельно закрепляют фактический dirty source epoch.
#>
[CmdletBinding()]
param(
    [string]$RequestFile,
    [string]$RequestSha256,
    [string]$FinalizerSha256,
    [string]$ExpectedSourceIdentity,
    [string]$ExpectedContextSha256,
    [string[]]$ExpectedBaseCommit,
    [string]$ExpectedTargetCommit,
    [switch]$Signoff
)

# Читает только fixed соседний pinned helper; не исполняет его верхнее тело.
function New-PostRunSignoffModule([string]$HelperFile,[string]$HelperSha256) {
    if ($HelperSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        -not [IO.Path]::IsPathFullyQualified($HelperFile) -or [IO.Path]::GetFullPath($HelperFile) -cne $HelperFile) {throw 'POSTRUN_HELPER_PIN'}
    $cursor=$HelperFile
    while ($cursor) {
        $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'POSTRUN_HELPER_LINK'}
        $parent=[IO.Path]::GetDirectoryName($cursor);if ($parent -eq $cursor) {break};$cursor=$parent
    }
    $bytes=[IO.File]::ReadAllBytes($HelperFile)
    if ($bytes.Length -gt 8388608 -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $HelperSha256) {throw 'POSTRUN_HELPER_CHANGED'}
    $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'POSTRUN_HELPER_PARSE'}
    $definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($definition in $definitions) {
        if ($definition.Name -cnotmatch '^[A-Za-z][A-Za-z0-9-]*$' -or -not $names.Add($definition.Name)) {throw 'POSTRUN_HELPER_EXPORT'}
    }
    foreach ($name in 'Read-SignoffJson','Read-SignoffPin','Assert-SignoffFields','Test-SignoffInteger','Assert-SignoffContext','Get-SignoffPlan',
        'Get-SignoffCellKey','Merge-NativeEvidenceBatches','Invoke-NativeUpdateEvidenceSignoff') {
        if (-not $names.Contains($name)) {throw 'POSTRUN_HELPER_EXPORT'}
    }
    $entry=@($definitions | Where-Object {$_.Name -ceq 'Invoke-NativeUpdateEvidenceSignoff'})
    if ($entry.Count -ne 1 -or (@($entry[0].Parameters.Name.VariablePath.UserPath) -join ',') -cne 'RequestFile,RequestSha256,Signoff') {throw 'POSTRUN_HELPER_SIGNATURE'}
    if ($entry[0].Parameters[0].StaticType -ne [string] -or $entry[0].Parameters[1].StaticType -ne [string] -or
        $entry[0].Parameters[2].StaticType -ne [Management.Automation.SwitchParameter] -or
        @($entry[0].Parameters.Attributes | Where-Object {$_ -is [Management.Automation.Language.AttributeAst] -and $_.TypeName.FullName -match '(^|\.)Alias(Attribute)?$'}).Count) {throw 'POSTRUN_HELPER_SIGNATURE'}
    $body=($definitions.Extent.Text -join "`n")+"`nExport-ModuleMember -Function *"
    return New-Module -ScriptBlock ([scriptblock]::Create($body))
}

# Проверяет caller pins и полноту; настоящий finalizer остаётся единственной authority.
function Invoke-NativeUpdatePostRunSignoff(
    [string]$RequestFile,[string]$RequestSha256,[string]$FinalizerSha256,
    [string]$ExpectedSourceIdentity,[string]$ExpectedContextSha256,
    [string[]]$ExpectedBaseCommit,[string]$ExpectedTargetCommit,[switch]$Signoff
) {
    $ErrorActionPreference='Stop';Set-StrictMode -Version 3
    if ($RequestSha256 -cnotmatch '^[0-9a-f]{64}$' -or $ExpectedContextSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $ExpectedSourceIdentity -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'POSTRUN_CALLER_PIN'}
    if ($ExpectedTargetCommit -cnotmatch '^[0-9a-f]{40}$' -or @($ExpectedBaseCommit).Count -ne 2 -or
        @($ExpectedBaseCommit | Where-Object {$_ -cnotmatch '^[0-9a-f]{40}$'}).Count -or
        $ExpectedBaseCommit[0] -ceq $ExpectedBaseCommit[1] -or $ExpectedBaseCommit -ccontains $ExpectedTargetCommit) {throw 'POSTRUN_COMMIT_PIN'}
    $helper=Join-Path $PSScriptRoot 'Test-NativeUpdateEvidenceSignoff.ps1'
    $wrapper=Join-Path $PSScriptRoot 'Invoke-NativeUpdatePostRunSignoff.ps1'
    $module=New-PostRunSignoffModule $helper $FinalizerSha256
    try {
        return & $module {
            param($requestPath,$requestSha,$helper,$helperSha,$wrapper,$identity,$contextSha,$baseCommits,$targetCommit,$signoff)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            $seen=@{};$requestPin=[pscustomobject]@{path=$requestPath;sha256=$requestSha}
            $request=Read-SignoffJson $requestPin $seen
            Assert-SignoffFields $request @('schemaVersion','context','batches','verifier','outputDirectory')
            if (-not (Test-SignoffInteger $request.schemaVersion) -or $request.schemaVersion -ne 1) {throw 'POSTRUN_REQUEST_SCHEMA'}
            $config=Assert-SignoffContext $request.context $seen
            if ($request.context.sourceIdentity -cne $identity -or $config.contextSha256 -cne $contextSha) {throw 'POSTRUN_SOURCE_EPOCH'}
            $helperPin=@($request.context.sourcePins | Where-Object {$_.path -ceq $helper -and $_.sha256 -ceq $helperSha})
            $wrapperPin=@($request.context.sourcePins | Where-Object {$_.path -ceq $wrapper})
            if ($helperPin.Count -ne 1 -or $wrapperPin.Count -ne 1) {throw 'POSTRUN_SOURCE_INVENTORY'}
            [void](Read-SignoffPin $helperPin[0] $seen);[void](Read-SignoffPin $wrapperPin[0] $seen)
            if ($config.target.commitSha -cne $targetCommit -or $config.bases.Count -ne 2 -or
                $config.bases[0].commitSha -cne $baseCommits[0] -or $config.bases[1].commitSha -cne $baseCommits[1]) {throw 'POSTRUN_COMMIT_MISMATCH'}
            $plan=@(Get-SignoffPlan $request.context $seen)
            $keys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($row in $plan) {if (-not $keys.Add((Get-SignoffCellKey $row))) {throw 'POSTRUN_PLAN_DUPLICATE'}}
            if ($plan.Count -ne 612) {throw 'POSTRUN_PLAN_COUNT'}
            $assembly=Merge-NativeEvidenceBatches $request.context $request.batches $plan $config $seen
            # Не импортируем finalizer-return PASS как native execution. Все pins перепроверяются.
            foreach ($path in @($seen.Keys)) {[void](Read-SignoffPin ([pscustomobject]@{path=$path;sha256=$seen[$path]}) $null)}
            if ($assembly.missing.Count -or $assembly.rows.Count -ne 612) {
                if ($signoff) {throw 'POSTRUN_INCOMPLETE_612'}
                return [pscustomobject]@{status='PENDING';scope='POSTRUN_PREFLIGHT_ONLY';accepted=$assembly.rows.Count;
                    missing=@($assembly.missing);results=$null;nativeProductsExecuted=$false;finalizerInvoked=$false}
            }
            if (-not $signoff) {
                return [pscustomobject]@{status='PENDING';scope='POSTRUN_PREFLIGHT_ONLY';accepted=612;missing=@();results=$null;
                    nativeProductsExecuted=$false;finalizerInvoked=$false;gap='EXPLICIT_POSTRUN_SIGNOFF_REQUIRED'}
            }
            # Повторная валидация и real CLI находятся в existing pinned finalizer, не в wrapper.
            return Invoke-NativeUpdateEvidenceSignoff $requestPath $requestSha -Signoff
        } $RequestFile $RequestSha256 $helper $FinalizerSha256 $wrapper $ExpectedSourceIdentity $ExpectedContextSha256 $ExpectedBaseCommit $ExpectedTargetCommit ([bool]$Signoff)
    } finally {Remove-Module -ModuleInfo $module -Force -ErrorAction Stop}
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-NativeUpdatePostRunSignoff -RequestFile $RequestFile -RequestSha256 $RequestSha256 -FinalizerSha256 $FinalizerSha256 `
        -ExpectedSourceIdentity $ExpectedSourceIdentity -ExpectedContextSha256 $ExpectedContextSha256 `
        -ExpectedBaseCommit $ExpectedBaseCommit -ExpectedTargetCommit $ExpectedTargetCommit -Signoff:$Signoff
}
