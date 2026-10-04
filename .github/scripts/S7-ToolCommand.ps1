<# .SYNOPSIS Создаёт команду существующего W4 CLI без повторной сборки. #>
param(
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$ToolJar,
    [Parameter(Mandatory)][string]$CoreJar,
    [Parameter(Mandatory)][string]$Out
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/S7-Release.ps1"
foreach ($path in $Java, $ToolJar, $CoreJar) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "S7_TOOL_BUILD_MISSING: $path" }
}
$archive = [IO.Compression.ZipFile]::OpenRead($ToolJar)
try {
    $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
    # Имя сверено с фактическим UpdateTool.java W4; manifest Main-Class не обязателен.
    $mainClass = 'ru.cashprediction.updatetool.UpdateTool'
    if ($entry) {
        if ($entry.Length -gt 65536) { throw 'S7_TOOL_MANIFEST_LIMIT' }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.UTF8Encoding]::new($false, $true))
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $manifest = $manifest -replace '\r?\n ', ''
        if ($manifest -match '(?m)^Main-Class: (?<main>[^\r\n]+)\r?$' -and $Matches.main -cne $mainClass) {
            throw 'S7_TOOL_MAIN_CLASS_CONFLICT'
        }
    }
} finally { $archive.Dispose() }
Write-S7Json $Out ([ordered]@{
    java = (Resolve-Path -LiteralPath $Java).Path
    arguments = @('--module-path', ((Resolve-Path -LiteralPath $ToolJar).Path + ';' +
        (Resolve-Path -LiteralPath $CoreJar).Path), '--module', ('ru.cashprediction.updatetool/' + $mainClass))
})
