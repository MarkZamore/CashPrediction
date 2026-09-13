<#
.SYNOPSIS
Включает кодовую страницу UTF-8 в манифесте лаунчеров jpackage, чтобы программа запускалась
из папки с любыми символами в пути.

.DESCRIPTION
Стандартный лаунчер Java ищет java.dll через ANSI-функции Windows. Если путь к папке программы
содержит символы вне системной кодовой страницы (например, китайские иероглифы на русской Windows
или кириллицу на английской), лаунчер завершается с ошибкой «could not find java.dll».

Windows 10 1903+ и Windows 11 позволяют приложению объявить в манифесте
<activeCodePage>UTF-8</activeCodePage>: тогда ANSI-функции этого процесса работают в UTF-8 и понимают
любой путь. Скрипт читает встроенный манифест exe (ресурс RT_MANIFEST), добавляет элемент в
windowsSettings и записывает манифест обратно функциями BeginUpdateResource/UpdateResource.
Внешние программы (mt.exe, rcedit) не нужны.

Запускается сборкой dist после jpackage и до упаковки в zip:
  powershell -NoProfile -ExecutionPolicy Bypass -File dist\scripts\Set-LauncherUtf8.ps1 -ImageDir dist\target\dist\CashPrediction
#>
param(
    [Parameter(Mandatory = $true)][string]$ImageDir
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class LauncherManifest {
    const uint LOAD_LIBRARY_AS_DATAFILE = 0x2;
    static readonly IntPtr RT_MANIFEST = new IntPtr(24);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr FindResourceEx(IntPtr module, IntPtr type, IntPtr name, ushort language);
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr LoadResource(IntPtr module, IntPtr info);
    [DllImport("kernel32.dll")] static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32.dll")] static extern uint SizeofResource(IntPtr module, IntPtr info);
    delegate bool EnumLangProc(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr param);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool EnumResourceLanguages(IntPtr module, IntPtr type, IntPtr name, EnumLangProc proc, IntPtr param);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool EndUpdateResource(IntPtr update, bool discard);

    // Возвращает (язык ресурса, текст манифеста) для манифеста с идентификатором 1.
    public static string Read(string file, out ushort language) {
        IntPtr module = LoadLibraryEx(file, IntPtr.Zero, LOAD_LIBRARY_AS_DATAFILE);
        if (module == IntPtr.Zero) throw new InvalidOperationException("Не удалось открыть " + file + ": " + Marshal.GetLastWin32Error());
        try {
            var languages = new List<ushort>();
            EnumResourceLanguages(module, RT_MANIFEST, new IntPtr(1), (m, t, n, lang, p) => { languages.Add(lang); return true; }, IntPtr.Zero);
            if (languages.Count != 1) throw new InvalidOperationException("Ожидался один манифест с id 1, найдено: " + languages.Count);
            language = languages[0];
            IntPtr info = FindResourceEx(module, RT_MANIFEST, new IntPtr(1), language);
            uint size = SizeofResource(module, info);
            byte[] bytes = new byte[size];
            Marshal.Copy(LockResource(LoadResource(module, info)), bytes, 0, (int)size);
            return Encoding.UTF8.GetString(bytes);
        } finally {
            FreeLibrary(module);
        }
    }

    // Записывает манифест обратно под тем же идентификатором и языком.
    public static void Write(string file, ushort language, string manifest) {
        byte[] bytes = Encoding.UTF8.GetBytes(manifest);
        IntPtr update = BeginUpdateResource(file, false);
        if (update == IntPtr.Zero) throw new InvalidOperationException("BeginUpdateResource: " + Marshal.GetLastWin32Error());
        if (!UpdateResource(update, RT_MANIFEST, new IntPtr(1), language, bytes, (uint)bytes.Length)) {
            int error = Marshal.GetLastWin32Error();
            EndUpdateResource(update, true);
            throw new InvalidOperationException("UpdateResource: " + error);
        }
        if (!EndUpdateResource(update, false)) throw new InvalidOperationException("EndUpdateResource: " + Marshal.GetLastWin32Error());
    }
}
"@

$element = '<ws2019:activeCodePage xmlns:ws2019="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</ws2019:activeCodePage>'
$launchers = @(Get-ChildItem -LiteralPath $ImageDir -Filter '*.exe' -File)
if ($launchers.Count -eq 0) { throw "В $ImageDir нет exe-лаунчеров." }

foreach ($exe in $launchers) {
    # jpackage делает лаунчеры доступными только для чтения; на время записи атрибут снимается.
    $readOnly = $exe.IsReadOnly
    if ($readOnly) { $exe.IsReadOnly = $false }
    try {
        $language = [uint16]0
        $manifest = [LauncherManifest]::Read($exe.FullName, [ref]$language)
        if ($manifest -match 'activeCodePage') {
            Write-Host "$($exe.Name): UTF-8 уже включён."
            continue
        }
        # Элемент добавляется в существующий windowsSettings, где лаунчер уже объявляет поддержку DPI.
        $patched = [regex]::Replace($manifest, '</asmv3:windowsSettings>', $element + '</asmv3:windowsSettings>', 1)
        if ($patched -eq $manifest) { throw "$($exe.Name): в манифесте нет asmv3:windowsSettings, структура лаунчера изменилась." }
        [LauncherManifest]::Write($exe.FullName, $language, $patched)
        $check = [LauncherManifest]::Read($exe.FullName, [ref]$language)
        if ($check -notmatch 'activeCodePage[^>]*>UTF-8<') { throw "$($exe.Name): манифест не обновился." }
        Write-Host "$($exe.Name): кодовая страница UTF-8 включена."
    } finally {
        if ($readOnly) { (Get-Item -LiteralPath $exe.FullName).IsReadOnly = $true }
    }
}
