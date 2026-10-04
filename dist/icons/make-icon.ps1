# Генерирует общие application.png и application.ico в ресурсах core без внешних программ.
#
# Зачем скрипт, а не готовый файл: иконку можно пересоздать и поменять цвета в одном месте.
# Как работает: System.Drawing рисует картинку в нескольких размерах, каждый размер
# сохраняется как PNG, затем PNG упаковываются в контейнер ICO. Windows Vista и новее
# понимают PNG внутри ICO, jpackage принимает такой файл как --icon.
#
# Запуск: powershell -ExecutionPolicy Bypass -File dist\icons\make-icon.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$iconDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/ui/icons'))
$outPath = Join-Path $iconDir 'application.ico'
$pngPath = Join-Path $iconDir 'application.png'

# Рисует иконку заданного размера и возвращает байты PNG.
function New-IconPng([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $s = $size / 256.0
    # Фон: скруглённый квадрат глубокого сине-зелёного цвета.
    $radius = [single](48 * $s)
    $rect = New-Object System.Drawing.RectangleF([single](8 * $s), [single](8 * $s), [single](240 * $s), [single](240 * $s))
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, [System.Drawing.Color]::FromArgb(255, 18, 110, 96), [System.Drawing.Color]::FromArgb(255, 10, 62, 80), 90)
    $g.FillPath($bg, $path)

    # Столбики: история баланса.
    $barBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(110, 255, 255, 255))
    $bars = @(@(52, 150), @(92, 128), @(132, 112), @(172, 84))
    foreach ($b in $bars) {
        $g.FillRectangle($barBrush, [single]($b[0] * $s), [single]($b[1] * $s), [single](28 * $s), [single]((200 - $b[1]) * $s))
    }

    # Линия прогноза: растущий баланс.
    $penWidth = [Math]::Max(1.5, 14 * $s)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 255, 214, 90), [single]$penWidth)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $pts = @(
        (New-Object System.Drawing.PointF([single](44 * $s), [single](176 * $s))),
        (New-Object System.Drawing.PointF([single](100 * $s), [single](132 * $s))),
        (New-Object System.Drawing.PointF([single](140 * $s), [single](148 * $s))),
        (New-Object System.Drawing.PointF([single](206 * $s), [single](64 * $s)))
    )
    $g.DrawLines($pen, $pts)
    # Точка на конце линии: прогнозируемое значение.
    $dot = [single](20 * $s)
    $dotBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 255, 214, 90))
    $g.FillEllipse($dotBrush, [single](206 * $s - $dot), [single](64 * $s - $dot), [single]($dot * 2), [single]($dot * 2))

    $dotBrush.Dispose()
    $pen.Dispose()
    $barBrush.Dispose()
    $bg.Dispose()
    $path.Dispose()
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $bytes = $ms.ToArray()
    $ms.Dispose()
    return ,$bytes
}

$images = @()
foreach ($size in $sizes) { $images += ,(New-IconPng $size) }

# Контейнер ICO: заголовок ICONDIR (6 байт), затем по записи ICONDIRENTRY (16 байт) на каждый размер, затем данные PNG.
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($out)
$w.Write([UInt16]0)                 # зарезервировано
$w.Write([UInt16]1)                 # тип 1 = иконка
$w.Write([UInt16]$sizes.Count)      # число изображений
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]
    $dim = if ($sz -ge 256) { 0 } else { $sz }   # 0 в ICO означает 256
    $w.Write([byte]$dim); $w.Write([byte]$dim)
    $w.Write([byte]0); $w.Write([byte]0)          # палитра не используется
    $w.Write([UInt16]1); $w.Write([UInt16]32)     # плоскости, бит на пиксель
    $w.Write([UInt32]$images[$i].Length)
    $w.Write([UInt32]$offset)
    $offset += $images[$i].Length
}
foreach ($img in $images) { $w.Write($img) }
$w.Flush()
[System.IO.Directory]::CreateDirectory($iconDir) | Out-Null
[System.IO.File]::WriteAllBytes($outPath, $out.ToArray())
$w.Dispose()
$out.Dispose()

# Тот же PNG 256x256 для заголовков и favicon всех клиентов; его байты уже входят в ICO.
[System.IO.File]::WriteAllBytes($pngPath, $images[$sizes.Count - 1])
Write-Output ("ICO written: " + $outPath + " (" + (Get-Item $outPath).Length + " bytes)")
