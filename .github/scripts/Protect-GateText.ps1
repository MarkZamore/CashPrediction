# Точечная редакция полей протокола; обычные клавиши и текстовые значения key сохраняются.
function Protect-GateText([string]$Text) {
    $Text = $Text -replace '(?i)((?:[?&]|&amp;)(?:t|token)=)[^&\s"<>]+', '$1[redacted]'
    $Text = $Text -replace '(?i)(Authorization\s*[:=]\s*(?:Bearer\s+)?)[^\s"<>]+', '$1[redacted]'
    $Text = $Text -replace '(?i)("(?:token|reconnectCredential)"\s*:\s*")[^"]+', '$1[redacted]'
    # Ключ reconnect и HMAC имеют ровно 64 hex-символа; короткий key не является секретом протокола.
    $names = '(?:key|keyHex|clientProof|serverProof)'
    $Text = $Text -replace ('(?i)("' + $names + '"\s*:\s*")[0-9a-f]{64}(?=")'), '$1[redacted]'
    $Text = $Text -replace ('(?i)(&quot;' + $names + '&quot;\s*:\s*&quot;)[0-9a-f]{64}(?=&quot;)'), '$1[redacted]'
    $Text = $Text -replace ('(?i)(\\"' + $names + '\\"\s*:\s*\\")[0-9a-f]{64}(?=\\")'), '$1[redacted]'
    $Text = $Text -replace ('(?i)(\b' + $names + '\s*[:=]\s*)[0-9a-f]{64}(?![0-9a-f])'), '$1[redacted]'
    return $Text
}
