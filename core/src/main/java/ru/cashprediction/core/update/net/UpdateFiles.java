package ru.cashprediction.core.update.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;

/** Проверки собственных служебных путей и долговечная запись подготовки. */
final class UpdateFiles {
    private UpdateFiles() { }

    static void check(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || !cursor.toRealPath().equals(cursor.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("UNSAFE_UPDATE_PATH");
            }
        }
    }

    static void directory(Path path) throws IOException {
        check(path);
        Files.createDirectories(path);
        check(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("NOT_DIRECTORY");
    }

    static void writeAtomic(Path path, byte[] bytes) throws IOException {
        check(path);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        check(temporary);
        Files.deleteIfExists(temporary);
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void move(Path from, Path to) throws IOException {
        check(from);
        check(to);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("DESTINATION_EXISTS");
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    static void forceTree(Path root) throws IOException {
        check(root);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                check(path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                boolean readOnly = dos != null && dos.readAttributes().isReadOnly();
                var permissions = posix == null ? null : posix.readAttributes().permissions();
                try {
                    if (readOnly) dos.setReadOnly(false);
                    if (permissions != null && !permissions.contains(PosixFilePermission.OWNER_WRITE)) {
                        var writable = new HashSet<>(permissions);
                        writable.add(PosixFilePermission.OWNER_WRITE);
                        posix.setPermissions(writable);
                    }
                    try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        file.force(true);
                    }
                } finally {
                    try {
                        if (permissions != null) posix.setPermissions(permissions);
                    } finally {
                        if (readOnly) dos.setReadOnly(true);
                    }
                }
            }
        }
    }

    static void delete(Path path) throws IOException {
        check(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                check(item);
                DosFileAttributeView dos = Files.getFileAttributeView(item, DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                Files.delete(item);
            }
        }
    }
}
