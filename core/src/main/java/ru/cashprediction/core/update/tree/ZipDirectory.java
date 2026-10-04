package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import ru.cashprediction.core.update.model.UpdateValidation;

/** Предварительный аудит центрального и локального каталогов ZIP до любых записей. */
final class ZipDirectory {
    /** Проверенные поля одной записи центрального каталога. */
    record Entry(String name, long size, long compressedSize, long crc, int method, long dataOffset) { }
    private ZipDirectory() { }

    static Map<String, Entry> inspect(Path path) throws IOException {
        path = SafeTree.absolute(path);
        if (!SafeTree.attributes(path).isRegularFile()) throw new IOException("ZIP_FILE_REQUIRED");
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long size = f.length();
            if (size < 22 || size > UpdateValidation.MAX_FILE) throw new IOException("ZIP_SIZE");
            int tailSize = (int) Math.min(size, 65557);
            byte[] tail = new byte[tailSize];
            f.seek(size - tailSize); f.readFully(tail);
            int end = -1;
            for (int i = tail.length - 22; i >= 0; i--)
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) { end = i; break; }
            if (end < 0) throw new IOException("ZIP_END");
            int count = u16(tail, end + 10);
            long directorySize = u32(tail, end + 12), directoryOffset = u32(tail, end + 16);
            long endOffset = size - tailSize + end;
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                    || u16(tail, end + 8) != count || count == 65535 || directorySize == 0xffffffffL
                    || directoryOffset == 0xffffffffL
                    || directoryOffset + directorySize != endOffset)
                throw new IOException("ZIP64_OR_MULTIDISK");
            Map<String, Entry> result = new LinkedHashMap<>();
            Map<String, String> nodes = new HashMap<>();
            Set<String> explicitNodes = new HashSet<>();
            Set<String> files = new HashSet<>();
            List<long[]> ranges = new ArrayList<>();
            long total = 0;
            f.seek(directoryOffset);
            for (int i = 0; i < count; i++) {
                byte[] h = new byte[46]; f.readFully(h);
                if (u32(h, 0) != 0x02014b50L) throw new IOException("ZIP_CENTRAL_HEADER");
                int flags = u16(h, 8), method = u16(h, 10);
                long compressed = u32(h, 20), expanded = u32(h, 24), offset = u32(h, 42);
                if (u16(h, 6) > 20 || (flags & ~(0x800 | 8 | 6)) != 0 || !(method == 0 || method == 8)
                        || method == 0 && (flags & 6) != 0
                        || u16(h, 34) != 0 || compressed == 0xffffffffL || expanded == 0xffffffffL
                        || offset == 0xffffffffL || expanded > UpdateValidation.MAX_FILE
                        || offset >= directoryOffset) throw new IOException("ZIP_UNSUPPORTED");
                if (method == 0 && compressed != expanded) throw new IOException("ZIP_STORED_SIZE");
                byte[] nameBytes = new byte[u16(h, 28)]; f.readFully(nameBytes);
                String name = utf8(nameBytes);
                boolean directory = name.endsWith("/");
                String node = directory ? name.substring(0, name.length() - 1) : name;
                UpdateValidation.path(node);
                if (!explicitNodes.add(UpdateValidation.collisionKey(node))) throw new IOException("ZIP_DUPLICATE");
                if (!directory) files.add(UpdateValidation.collisionKey(node));
                String prefix = node;
                while (true) {
                    String old = nodes.putIfAbsent(UpdateValidation.collisionKey(prefix), prefix);
                    if (old != null && !old.equals(prefix)) throw new IOException("ZIP_COLLISION");
                    int slash = prefix.lastIndexOf('/');
                    if (slash < 0) break;
                    prefix = prefix.substring(0, slash);
                }
                byte[] extra = new byte[u16(h, 30)]; f.readFully(extra); extras(extra);
                f.seek(f.getFilePointer() + u16(h, 32));
                long external = u32(h, 38);
                int type = (int) ((external >>> 16) & 0170000);
                // Проверяется Unix mode независимо от заявленной ОС создателя.
                if (!(type == 0 || type == 0100000 || type == 0040000)
                        || type == 0040000 && !directory || type == 0100000 && directory
                        || (external & 0x400) != 0 || (external & 0x10) != 0 && !directory
                        || directory && expanded != 0) throw new IOException("ZIP_LINK_OR_TYPE");
                total += expanded;
                if (total > UpdateValidation.MAX_TREE + UpdateValidation.MAX_JSON) throw new IOException("ZIP_EXPANSION");
                long resume = f.getFilePointer();
                f.seek(offset);
                byte[] local = new byte[30]; f.readFully(local);
                if (u32(local, 0) != 0x04034b50L || u16(local, 4) != u16(h, 6)
                        || u16(local, 6) != flags || u16(local, 8) != method
                        || u16(local, 10) != u16(h, 12) || u16(local, 12) != u16(h, 14)
                        || u16(local, 26) != nameBytes.length) throw new IOException("ZIP_LOCAL_HEADER");
                byte[] localName = new byte[nameBytes.length]; f.readFully(localName);
                if (!Arrays.equals(nameBytes, localName)) throw new IOException("ZIP_LOCAL_NAME");
                byte[] localExtra = new byte[u16(local, 28)]; f.readFully(localExtra); extras(localExtra);
                long data = f.getFilePointer(), dataEnd = data + compressed;
                if (dataEnd > directoryOffset) throw new IOException("ZIP_RANGE");
                if ((flags & 8) == 0) {
                    if (u32(local, 14) != u32(h, 16) || u32(local, 18) != compressed || u32(local, 22) != expanded)
                        throw new IOException("ZIP_LOCAL_SIZE");
                } else {
                    // При descriptor локальные поля могут быть нулевыми либо уже
                    // известными, но не могут описывать другое содержимое.
                    if (!zeroOrEqual(u32(local, 14), u32(h, 16))
                            || !zeroOrEqual(u32(local, 18), compressed)
                            || !zeroOrEqual(u32(local, 22), expanded))
                        throw new IOException("ZIP_LOCAL_SIZE");
                    f.seek(dataEnd);
                    byte[] descriptor = new byte[12]; f.readFully(descriptor);
                    // CRC unsigned descriptor сам может совпасть с сигнатурой.
                    if (!descriptorMatches(descriptor, u32(h, 16), compressed, expanded)
                            && u32(descriptor, 0) == 0x08074b50L) {
                        f.seek(dataEnd + 4); f.readFully(descriptor); dataEnd += 4;
                    }
                    if (!descriptorMatches(descriptor, u32(h, 16), compressed, expanded))
                        throw new IOException("ZIP_DESCRIPTOR");
                    dataEnd += 12;
                    if (dataEnd > directoryOffset) throw new IOException("ZIP_RANGE");
                }
                ranges.add(new long[] {offset, dataEnd});
                result.put(name, new Entry(name, expanded, compressed, u32(h, 16), method, data));
                f.seek(resume);
            }
            if (f.getFilePointer() != endOffset) throw new IOException("ZIP_DIRECTORY_SIZE");
            ranges.sort(Comparator.comparingLong(a -> a[0]));
            long previous = 0;
            for (long[] range : ranges) {
                if (range[0] != previous) throw new IOException("ZIP_OVERLAP_OR_HIDDEN_ENTRY");
                previous = range[1];
            }
            if (previous != directoryOffset) throw new IOException("ZIP_HIDDEN_DATA");
            for (String name : nodes.values()) {
                while (name.contains("/")) {
                    name = name.substring(0, name.lastIndexOf('/'));
                    if (files.contains(UpdateValidation.collisionKey(name))) throw new IOException("ZIP_FILE_DIRECTORY");
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (IllegalArgumentException e) { throw new IOException("INVALID_ZIP", e); }
    }

    static InputStream stream(ZipFile zip, Entry expected) throws IOException {
        ZipEntry actual = zip.getEntry(expected.name());
        if (actual == null || actual.getSize() != expected.size() || actual.getCompressedSize() != expected.compressedSize()
                || actual.getCrc() != expected.crc() || actual.getMethod() != expected.method())
            throw new IOException("ZIP_CHANGED");
        return new FilterInputStream(zip.getInputStream(actual)) {
            private final CRC32 crc = new CRC32();
            private long count;
            private boolean checked;
            /** Проверяет одиночный байт через общий счётчик и CRC. */
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            /** Проверяет фактические распакованные байты и конечный CRC. */
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n < 0) {
                    if (!checked && (count != expected.size() || crc.getValue() != expected.crc()))
                        throw new IOException("ZIP_CRC_OR_SIZE");
                    checked = true;
                } else {
                    count += n;
                    if (count > expected.size()) throw new IOException("ZIP_EXPANSION");
                    crc.update(b, off, n);
                }
                return n;
            }
        };
    }

    static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("INVALID_UTF8", e); }
    }

    private static void extras(byte[] bytes) throws IOException {
        for (int i = 0; i < bytes.length;) {
            if (i + 4 > bytes.length) throw new IOException("ZIP_EXTRA");
            int id = u16(bytes, i), size = u16(bytes, i + 2);
            // Дополнительное имя Unicode способно расходиться с проверенным UTF-8 именем.
            if (id == 1 || id == 0x7075 || i + 4 + size > bytes.length) throw new IOException("ZIP64_OR_EXTRA_NAME");
            i += 4 + size;
        }
    }

    private static boolean zeroOrEqual(long local, long central) {
        return local == 0 || local == central;
    }

    private static boolean descriptorMatches(byte[] descriptor, long crc, long compressed, long expanded) {
        return u32(descriptor, 0) == crc && u32(descriptor, 4) == compressed && u32(descriptor, 8) == expanded;
    }

    private static int u16(byte[] b, int i) { return (b[i] & 255) | (b[i + 1] & 255) << 8; }
    private static long u32(byte[] b, int i) { return Integer.toUnsignedLong(u16(b, i) | u16(b, i + 2) << 16); }
}
