<#
.SYNOPSIS
Проверяет структуру и CRC общих PNG без графики и изменения icon files.
.DESCRIPTION
Только чтение. Заголовок из 33 байт не является PNG: нужны корректные chunks,
IHDR, непустая совокупность IDAT, IEND, CRC и отсутствие trailing bytes. Это не raster decode/GUI proof.
#>
[CmdletBinding()]
param([string]$IconDirectory=(Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/ui/icons'))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

# Читает network-order число только после проверки доступного диапазона.
function Read-IconPngUInt32([byte[]]$Bytes,[int]$Offset) {
    if ($Offset -lt 0 -or $Offset -gt $Bytes.Length-4) {throw 'ICON_PNG_BOUNDS'}
    return [uint32](([uint64]$Bytes[$Offset] -shl 24) -bor ([uint64]$Bytes[$Offset+1] -shl 16) -bor
        ([uint64]$Bytes[$Offset+2] -shl 8) -bor [uint64]$Bytes[$Offset+3])
}

# CRC-32 PNG считает type+payload, не позволяя header-only fixture выглядеть валидным ресурсом.
function Get-IconPngCrc([byte[]]$Bytes,[int]$Offset,[int]$Length) {
    if ($Offset -lt 0 -or $Length -lt 0 -or [long]$Offset+$Length -gt $Bytes.Length) {throw 'ICON_PNG_BOUNDS'}
    $crc=[uint32]4294967295L
    for($i=$Offset;$i -lt $Offset+$Length;$i++) {
        $crc=$crc -bxor [uint32]$Bytes[$i]
        for($bit=0;$bit -lt 8;$bit++) {
            if($crc -band 1) {$crc=($crc -shr 1) -bxor [uint32]3988292384L} else {$crc=$crc -shr 1}
        }
    }
    return [uint32]($crc -bxor [uint32]4294967295L)
}

# Проверяет bounded PNG chunk census; неизвестный critical chunk явно не поддержан.
function Assert-IconPngStructure([byte[]]$Bytes) {
    if($Bytes.Length -lt 57 -or $Bytes.Length -gt 4194304 -or
        [Convert]::ToBase64String($Bytes,0,8) -cne 'iVBORw0KGgo=') {throw 'ICON_PNG_SIGNATURE_OR_SIZE'}
    $offset=8;$chunks=0;$header=$false;$data=$false;$dataBytes=0L;$dataEnded=$false;$palette=$false;$color=-1
    while($offset -lt $Bytes.Length) {
        if($Bytes.Length-$offset -lt 12 -or ++$chunks -gt 4096) {throw 'ICON_PNG_CHUNK_BOUNDS'}
        $length=Read-IconPngUInt32 $Bytes $offset
        if([long]$offset+12+$length -gt $Bytes.Length) {throw 'ICON_PNG_CHUNK_BOUNDS'}
        $type=[Text.Encoding]::ASCII.GetString($Bytes,$offset+4,4)
        if($type -cnotmatch '^[A-Za-z]{4}$' -or $type[2] -cmatch '[a-z]') {throw 'ICON_PNG_CHUNK_TYPE'}
        $crc=Get-IconPngCrc $Bytes ($offset+4) ([int]$length+4)
        if($crc -ne (Read-IconPngUInt32 $Bytes ($offset+8+[int]$length))) {throw 'ICON_PNG_CRC'}
        if(-not $header -and $type -cne 'IHDR') {throw 'ICON_PNG_IHDR_FIRST'}
        switch -CaseSensitive ($type) {
            'IHDR' {
                if($header -or $length -ne 13) {throw 'ICON_PNG_IHDR'}
                $width=Read-IconPngUInt32 $Bytes ($offset+8);$height=Read-IconPngUInt32 $Bytes ($offset+12)
                $depth=[int]$Bytes[$offset+16];$color=[int]$Bytes[$offset+17]
                $allowed=switch($color) {0 {@(1,2,4,8,16)} 2 {@(8,16)} 3 {@(1,2,4,8)} 4 {@(8,16)} 6 {@(8,16)} default {@()}}
                if($width -lt 1 -or $height -lt 1 -or $width -gt 4096 -or $height -gt 4096 -or $depth -notin $allowed -or
                    $Bytes[$offset+18] -ne 0 -or $Bytes[$offset+19] -ne 0 -or $Bytes[$offset+20] -gt 1) {throw 'ICON_PNG_IHDR_FIELDS'}
                $header=$true
            }
            'PLTE' {
                if($palette -or $data -or $length -lt 3 -or $length -gt 768 -or $length%3 -ne 0 -or $color -in @(0,4)) {throw 'ICON_PNG_PALETTE'}
                $palette=$true
            }
            'IDAT' {
                if($dataEnded -or ($color -eq 3 -and -not $palette)) {throw 'ICON_PNG_IDAT'}
                $data=$true;$dataBytes+=$length
            }
            'IEND' {
                if(-not $data -or $dataBytes -eq 0 -or $length -ne 0 -or $offset+12 -ne $Bytes.Length) {throw 'ICON_PNG_IEND_OR_TRAILING'}
                return
            }
            default {
                if($type[0] -cmatch '[A-Z]') {throw 'ICON_PNG_UNSUPPORTED_CRITICAL_CHUNK'}
                if($data) {$dataEnded=$true}
            }
        }
        $offset+=12+[int]$length
    }
    throw 'ICON_PNG_IEND_MISSING'
}

$root=[IO.Path]::GetFullPath($IconDirectory)
$cursor=$root
while($cursor) {
    $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'ICON_PAYLOAD_LINK'}
    $parent=[IO.Path]::GetDirectoryName($cursor);if($parent -eq $cursor){break};$cursor=$parent
}
$files=@(Get-ChildItem -LiteralPath $root -File -Filter '*.png')
if($files.Count -eq 0 -or $files.Count -gt 10000) {throw 'ICON_PAYLOAD_COUNT'}
foreach($file in $files) {
    if($file.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'ICON_PAYLOAD_LINK'}
    if($file.Length -gt 4194304) {throw 'ICON_PNG_SIGNATURE_OR_SIZE'}
    try {Assert-IconPngStructure ([IO.File]::ReadAllBytes($file.FullName))}
    catch {throw ('Invalid icon payload '+$file.Name+': '+$_.Exception.Message)}
}
Write-Output ('OK: '+$files.Count+' PNG chunk/CRC payloads; decode/GUI NOT RUN.')
