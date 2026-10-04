<# .SYNOPSIS Filesystem fixtures canonical naming: без Java, GUI и настоящего GitHub. #>
param([Parameter(Mandatory)][string]$Library, [Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
. $Library
if (Test-Path -LiteralPath $Out) { throw 'NAME_OUT_EXISTS' }
$null = New-Item -ItemType Directory -Path $Out
<# .SYNOPSIS Создаёт только synthetic bytes и настоящий SHA для naming guard. #>
function New-NameFixture {
    param([string]$Case, [string]$Asset = 'CashPrediction.from-1.cpdelta')
    $directory = Join-Path $Out $Case; $null = New-Item -ItemType Directory -Path $directory
    $source = Join-Path $directory $Asset
    [IO.File]::WriteAllBytes($source,[byte[]](1,2,3,4))
    $entry = [pscustomobject]@{baseReleaseNumber=1;assetName=$Asset;sizeBytes=4;sha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant()}
    $descriptor = Join-Path $directory 'descriptor.json'; Write-S7Json $descriptor $entry
    return [pscustomobject]@{directory=$directory;descriptor=$descriptor;entry=$entry;source=$source}
}
<# .SYNOPSIS Проверяет точный отказ и сохранность исходных bytes при ошибке. #>
function Assert-NameRefusal {
    param([string]$Case,[string]$Code,$Fixture,[string]$Name)
    $before = (Get-FileHash -LiteralPath $Fixture.source).Hash
    $failure = $null
    try { Set-S7DeltaAssetName $Fixture.directory $Fixture.descriptor $Name } catch { $failure = $_ }
    if ($null -eq $failure -or $failure.Exception.Message -cne $Code -or
        (Get-FileHash -LiteralPath $Fixture.source).Hash -cne $before) { throw "NAME_REFUSAL_$Case" }
    Write-Output "PASS naming-negative=$Case code=$Code bytesPreserved=TRUE"
}
$f = New-NameFixture 'promotion'
Set-S7DeltaAssetName $f.directory $f.descriptor 'CashPrediction.cpdelta'
$entry = Read-S7Json $f.descriptor
if ((Test-Path -LiteralPath $f.source) -or $entry.assetName -cne 'CashPrediction.cpdelta' -or
    $entry.sha256 -cne $f.entry.sha256 -or $entry.sizeBytes -ne $f.entry.sizeBytes) { throw 'NAME_PROMOTION' }
Assert-S7Container (Join-Path $f.directory $entry.assetName) $entry
Write-Output 'PASS naming-promotion bytesSizeShaPreserved=TRUE'
$f = New-NameFixture 'unchanged' 'CashPrediction.cpdelta'
$pin = (Get-FileHash -LiteralPath $f.descriptor).Hash
Set-S7DeltaAssetName $f.directory $f.descriptor 'CashPrediction.cpdelta'
if ((Get-FileHash -LiteralPath $f.descriptor).Hash -cne $pin) { throw 'NAME_UNCHANGED' }
Write-Output 'PASS naming-unchanged descriptorShaPreserved=TRUE'
$f = New-NameFixture 'collision'
$destination = Join-Path $f.directory 'CashPrediction.cpdelta'
[IO.File]::WriteAllBytes($destination,[byte[]](9,8,7))
$pin = (Get-FileHash -LiteralPath $destination).Hash
Assert-NameRefusal 'collision' 'S7_PATCH_RENAME_COLLISION' $f 'CashPrediction.cpdelta'
if ((Get-FileHash -LiteralPath $destination).Hash -cne $pin) { throw 'NAME_OVERWRITE' }
$f = New-NameFixture 'corrupt'
[IO.File]::WriteAllBytes($f.source,[byte[]](4,3,2,1))
Assert-NameRefusal 'corrupt' 'S7_CONTAINER_MISMATCH' $f 'CashPrediction.cpdelta'
$f = New-NameFixture 'wrong-source' 'CashPrediction.from-2.cpdelta'
Assert-NameRefusal 'wrong-source' 'S7_PATCH_NAME' $f 'CashPrediction.cpdelta'
$f = New-NameFixture 'wrong-target'
Assert-NameRefusal 'wrong-target' 'S7_PATCH_NAME' $f 'CashPrediction.from-2.cpdelta'
$f = New-NameFixture 'traversal-target'
Assert-NameRefusal 'traversal-target' 'S7_PATCH_NAME' $f '../CashPrediction.cpdelta'
$f = New-NameFixture 'bad-number'
$entry = Read-S7Json $f.descriptor; $entry.baseReleaseNumber = '../2'; Write-S7Json $f.descriptor $entry
Assert-NameRefusal 'bad-number' 'S7_PATCH_NAME' $f 'CashPrediction.cpdelta'
Write-Output 'NAME_FIXTURES_PASS cases=8 syntheticBytes=TRUE native=NOT_EXECUTED'
