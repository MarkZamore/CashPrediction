<# Настраивает экран одноразового Windows-runner, не подменяя последующую Java/Robot приёмку. #>
#requires -Version 7.0
[CmdletBinding()]
param([switch]$ValidateInteropOnly)
$ErrorActionPreference = 'Stop'

# Только объявление Win32 ABI: режимы читает и применяет Windows, не тестовый двойник.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace CashPrediction.Ci {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DisplayMode {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public ushort SpecVersion, DriverVersion, Size, DriverExtra;
        public uint Fields;
        public int PositionX, PositionY;
        public uint Orientation, FixedOutput;
        public short Color, Duplex, YResolution, TTOption, Collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string FormName;
        public ushort LogPixels;
        public uint BitsPerPel, Width, Height, DisplayFlags, Frequency;
        public uint IcmMethod, IcmIntent, MediaType, DitherType, Reserved1, Reserved2;
        public uint PanningWidth, PanningHeight;
        public static DisplayMode Empty() {
            return new DisplayMode { Size = (ushort)Marshal.SizeOf<DisplayMode>() };
        }
    }
    public static class Desktop {
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool EnumDisplaySettings(string device, int number, ref DisplayMode mode);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        public static extern int ChangeDisplaySettingsEx(string device, ref DisplayMode mode,
            IntPtr window, uint flags, IntPtr parameter);
        [DllImport("user32.dll")]
        public static extern int GetSystemMetrics(int index);
    }
}
'@

# В тестовом режиме проверяется лишь компиляция ABI, без чтения/изменения рабочего стола разработчика.
$abiSize = [Runtime.InteropServices.Marshal]::SizeOf([type][CashPrediction.Ci.DisplayMode])
if ($abiSize -ne 220) { throw "Unexpected DEVMODEW ABI size: $abiSize" }
if ($ValidateInteropOnly) { Write-Host "Desktop interop compiled: DEVMODEW=$abiSize; no native calls"; return }
if (-not $IsWindows -or $env:GITHUB_ACTIONS -cne 'true') {
    throw 'Desktop provisioning is restricted to an ephemeral GitHub Actions Windows runner.'
}

$before = [CashPrediction.Ci.DisplayMode]::Empty()
if (-not [CashPrediction.Ci.Desktop]::EnumDisplaySettings($null, -1, [ref]$before)) {
    throw 'Cannot read current primary display mode.'
}
Write-Host "Desktop before: $($before.Width)x$($before.Height), $($before.BitsPerPel)bpp, $($before.Frequency)Hz"
$modes = @()
for ($index = 0; $index -lt 4096; $index++) {
    $mode = [CashPrediction.Ci.DisplayMode]::Empty()
    if (-not [CashPrediction.Ci.Desktop]::EnumDisplaySettings($null, $index, [ref]$mode)) { break }
    if ($mode.Width -ge 1920 -and $mode.Height -ge 1080 -and $mode.BitsPerPel -ge 32) { $modes += $mode }
}
if ($index -eq 4096) { throw 'Display mode enumeration exceeded its safety bound.' }
if ($modes.Count -eq 0) { throw 'Windows exposes no supported primary mode of at least 1920x1080 at 32bpp.' }
# Берём наименьший подходящий режим; частоту оставляем из реально перечисленного Windows режима.
[CashPrediction.Ci.DisplayMode]$target = $modes | Sort-Object @{Expression={ [long]$_.Width * $_.Height }}, Width, Height,
    @{Expression='Frequency';Descending=$true} | Select-Object -First 1
$target.DriverExtra = 0
$target.Fields = 0x00040000 -bor 0x00080000 -bor 0x00100000 -bor 0x00200000 -bor 0x00400000
$testResult = [CashPrediction.Ci.Desktop]::ChangeDisplaySettingsEx($null, [ref]$target, [IntPtr]::Zero, 2, [IntPtr]::Zero)
if ($testResult -ne 0) { throw "Windows rejected candidate display mode: code=$testResult" }
# Без CDS_UPDATEREGISTRY: меняется только текущая сессия одноразового runner.
$applyResult = [CashPrediction.Ci.Desktop]::ChangeDisplaySettingsEx($null, [ref]$target, [IntPtr]::Zero, 0, [IntPtr]::Zero)
if ($applyResult -ne 0) { throw "Windows did not apply display mode: code=$applyResult" }
$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $actual = [CashPrediction.Ci.DisplayMode]::Empty()
    $read = [CashPrediction.Ci.Desktop]::EnumDisplaySettings($null, -1, [ref]$actual)
    if ($read -and $actual.Width -eq $target.Width -and $actual.Height -eq $target.Height) { break }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)
if (-not $read -or $actual.Width -ne $target.Width -or $actual.Height -ne $target.Height) {
    throw 'Primary desktop mode did not converge to the requested mode.'
}
Write-Host "Desktop after: $($actual.Width)x$($actual.Height); process metrics=$([CashPrediction.Ci.Desktop]::GetSystemMetrics(0))x$([CashPrediction.Ci.Desktop]::GetSystemMetrics(1))"
# Физический режим Win32 не доказывает логическую ширину JDK при масштабировании DPI.
& java (Join-Path $PSScriptRoot 'CiDesktopProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'JDK logical desktop qualification failed before compilation.' }
Write-Host 'Native Java/Robot desktop qualification remains mandatory; mode provisioning is not acceptance.'
