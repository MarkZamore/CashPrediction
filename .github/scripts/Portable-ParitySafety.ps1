# Ownership/fingerprints перенесены из сохранённого portable-parity.patch, без javac/build.
# Отпечаток имён и байтов; reparse points не принимаются даже внутри дерева.
function Get-PortableParityFingerprint([string]$Path) {
    $absolute = Resolve-PortableSafetyPath $Path
    if (-not (Test-Path -LiteralPath $absolute)) { throw 'Вход parity отсутствует.' }
    if (Test-Path -LiteralPath $absolute -PathType Leaf) { return (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash }
    Assert-PortableTreeHasNoLinks $absolute
    $rows = @(Get-ChildItem -LiteralPath $absolute -Recurse -Force | Sort-Object FullName | ForEach-Object {
        $name = $_.FullName.Substring($absolute.Length)
        if ($_.PSIsContainer) { 'D ' + $name } else { 'F ' + $name + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($rows -join "`n")))) }
    finally { $sha.Dispose() }
}

# Проверяет и исходные входы, и отдельные замороженные копии до/во время/после capture.
function Assert-PortableParityInputs($Inputs) {
    foreach ($entry in $Inputs.Entries) {
        if ((Get-PortableParityFingerprint $entry.Source) -cne $entry.Hash -or
            (Get-PortableParityFingerprint $entry.Frozen) -cne $entry.Hash) { throw 'Вход parity изменился во время capture.' }
    }
}

# Обходит только наблюдения стенда; содержимое живого browser-profile не является доказательством.
# Сам исключённый каталог всё равно проверяется на reparse point до отказа от входа в него.
function Get-PortableParityObservationFiles([string]$Root) {
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push((Resolve-PortableSafetyPath $Root))
    while ($pending.Count) {
        $current = Resolve-PortableSafetyPath $pending.Pop()
        foreach ($item in @(Get-ChildItem -LiteralPath $current -Force -ErrorAction Stop)) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Ссылочный элемент дерева запрещён.' }
            if ($item.PSIsContainer) {
                if ($item.Name -notin @('browser-profile','frozen')) { $pending.Push($item.FullName) }
            } else { $item }
        }
    }
}

# Удерживает проверенные handles браузера и потомков; PID сам по себе не даёт права kill.
function Update-PortableBrowserOwnership([string]$Copy, $Owned, [scriptblock]$Acquire = { param($Id) Open-PortableProcess $Id }) {
    $absolute = Resolve-PortableSafetyPath $Copy
    if (-not $ownedCopies.Contains($absolute) -or -not $parityRuns.ContainsKey($absolute) -or -not (Test-PortablePathContains $WorkDir $absolute)) { throw 'Чужой browser lifecycle.' }
    $base = $parityRuns[$absolute].Root
    if (-not (Test-PortablePathContains $WorkDir (Resolve-PortableSafetyPath $base)) -or $base -eq $WorkDir) { throw 'Foreign browser ownership root.' }
    if (-not (Test-Path -LiteralPath $base)) { return }
    $all = @(Get-CimInstance Win32_Process)
    foreach ($intent in @(Get-PortableParityObservationFiles $base | Where-Object Name -eq 'browser-owner.json')) {
        $plan = Get-Content -LiteralPath $intent.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $profile = Resolve-PortableSafetyPath $plan.profile
        $expected = Join-Path $intent.Directory.FullName 'browser-profile'
        if (-not $profile.Equals($expected, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-PortablePathContains $base $profile)) { throw 'Чужой browser profile.' }
        $executable = Resolve-PortableSafetyPath $plan.executable
        $argument = '(?:^|\s)(?:"--user-data-dir=' + [regex]::Escape($profile) + '"|--user-data-dir="' +
            [regex]::Escape($profile) + '"|--user-data-dir=' + [regex]::Escape($profile) + ')(?=\s|$)'
        $roots = @($all | Where-Object { $_.ExecutablePath -and $_.CommandLine -and
            $_.ExecutablePath.Equals($executable, [StringComparison]::OrdinalIgnoreCase) -and $_.CommandLine -match $argument -and
            $_.CreationDate.ToUniversalTime() -ge $intent.LastWriteTimeUtc.AddSeconds(-1) })
        $selected = [Collections.Generic.HashSet[uint32]]::new()
        foreach ($p in $roots) { $null = $selected.Add([uint32]$p.ProcessId) }
        do {
            $changed = $false
            foreach ($p in $all) {
                $parent = @($all | Where-Object { $_.ProcessId -eq $p.ParentProcessId })
                if ($selected.Contains([uint32]$p.ParentProcessId) -and $parent.Count -eq 1 -and
                    $p.CreationDate -ge $parent[0].CreationDate -and $selected.Add([uint32]$p.ProcessId)) { $changed = $true }
            }
        } while ($changed)
        foreach ($p in $all | Where-Object { $selected.Contains([uint32]$_.ProcessId) }) {
            $identity = "$($p.ProcessId):$($p.CreationDate.ToUniversalTime().Ticks)"
            if ($Owned.ContainsKey($identity)) { continue }
            $handle = $null; $registered = $false
            try {
                $handle = & $Acquire ([int]$p.ProcessId)
                $null = $handle.Handle
                if ($handle.HasExited) { continue }
                $expectedTime = $p.CreationDate.ToUniversalTime().Ticks
                $actualTime = $handle.StartTime.ToUniversalTime().Ticks
                if (($actualTime - ($actualTime % 10)) -ne ($expectedTime - ($expectedTime % 10)) -or
                    -not $handle.MainModule.FileName.Equals($p.ExecutablePath, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'Browser identity changed.'
                }
                $Owned[$identity] = $handle
                $registered = $true
            } catch [ArgumentException] { } # Процесс завершился до открытия handle.
            finally { if ($null -ne $handle -and -not $registered) { $handle.Dispose() } }
        }
    }
}

# После остановки bridge, до реестра/каталогов; удержанные handles переживают reparent.
function Stop-PortableBrowsers([string]$Copy, $Owned) {
    Update-PortableBrowserOwnership $Copy $Owned
    foreach ($handle in @($Owned.Values)) { if (-not $handle.HasExited) { $handle.Kill($true) } }
    foreach ($handle in @($Owned.Values)) {
        if (-not $handle.WaitForExit(20000)) { throw 'Собственный browser process выжил; run сохраняется.' }
    }
    Update-PortableBrowserOwnership $Copy $Owned
    if (@($Owned.Values | Where-Object { -not $_.HasExited }).Count) { throw 'Browser tree пережило cleanup; run сохраняется.' }
    foreach ($handle in @($Owned.Values)) { $handle.Dispose() }
    $Owned.Clear()
}
