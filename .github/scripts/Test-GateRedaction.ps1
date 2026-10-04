# Без Maven и GUI: положительные секретные случаи и отрицательные обычные тексты.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Protect-GateText.ps1')
$secret = 'a' * 64
$token = 'b' * 48
$sensitive = @(
    "PARITY_URL http://127.0.0.1:8765/app.html?t=$token",
    "http://127.0.0.1:8765/?x=1&t=$token&y=2",
    "http://127.0.0.1:8765/?x=1&amp;t=$token&amp;y=2",
    "http://127.0.0.1:8765/?token=$token",
    "Authorization: Bearer $token"
)
foreach ($name in 'key','keyHex','clientProof','serverProof') {
    $sensitive += ('{"' + $name + '":"' + $secret + '"}')
    $sensitive += ('{&quot;' + $name + '&quot;:&quot;' + $secret + '&quot;}')
    $sensitive += ('{\"' + $name + '\":\"' + $secret + '\"}')
    $sensitive += ($name + '=' + $secret)
}
$sensitive += ('Key: ' + $secret)
foreach ($text in $sensitive) {
    $actual = Protect-GateText $text
    if ($actual.Contains($secret) -or $actual.Contains($token) -or -not $actual.Contains('[redacted]')) {
        throw 'Sensitive protocol fixture was not redacted.'
    }
}
foreach ($text in @('{"key":"Ctrl+S"}', 'key=ENTER', '{"key":"rent"}', 'monkey=' + $secret,
    '{"key":"123456"}', 'http://127.0.0.1/?today=2026-09-13&tab=table', 'treeSha256=' + $secret)) {
    if ((Protect-GateText $text) -cne $text) { throw 'Ordinary fixture changed by secret redaction.' }
}
if ((Protect-GateText ("http://localhost/?t=$token&tab=table")) -notlike '*&tab=table') {
    throw 'Non-secret URL parameters were lost.'
}
Write-Output 'Gate redaction fixtures: PASS'
