package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import ru.cashprediction.core.update.model.*;

/** Проверка реальных путей без переходов через ссылки и потоковая работа с файлами. */
final class SafeTree {
    private SafeTree() { }

    static Path absolute(Path path) throws IOException {
        if (path == null) throw new IOException("NULL_PATH");
        Path p = path.toAbsolutePath();
        if (!p.equals(p.normalize())) throw new IOException("UNSAFE_DESTINATION");
        ancestors(p);
        return p;
    }

    static void ancestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try { attributes(current); }
            catch (NoSuchFileException e) { return; }
        }
    }

    static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || a.isOther()
                || !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("LINK_OR_REPARSE");
        return a;
    }

    static Path root(Path path) throws IOException {
        Path p = absolute(path);
        if (!attributes(p).isDirectory()) throw new IOException("DIRECTORY_REQUIRED");
        return p;
    }

    static Path vacant(Path path) throws IOException {
        Path p = absolute(path);
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("DESTINATION_EXISTS");
        if (p.getParent() == null || !attributes(p.getParent()).isDirectory())
            throw new IOException("DESTINATION_PARENT");
        return p;
    }

    static Path resolve(Path root, String relative) throws IOException {
        UpdateValidation.managedPath(relative);
        Path p = root.resolve(relative);
        ancestors(p);
        if (!p.startsWith(root)) throw new IOException("PATH_ESCAPE");
        return p;
    }

    static boolean readOnly(Path p) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) return dos.readAttributes().isReadOnly();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
        return Collections.disjoint(permissions, Set.of(PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
    }

    static void readOnly(Path p, boolean value) throws IOException {
        attributes(p);
        DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) dos.setReadOnly(value);
        else {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
            if (value) permissions.removeAll(Set.of(PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
            else permissions.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, permissions);
        }
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static String hash(Path file) throws IOException {
        file = absolute(file);
        BasicFileAttributes attrs = attributes(file);
        if (!attrs.isRegularFile()) throw new IOException("FILE_REQUIRED");
        if (attrs.size() > UpdateValidation.MAX_FILE) throw new IOException("FILE_SIZE");
        MessageDigest digest = digest();
        long count = 0;
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (int n; (n = in.read(buffer)) != -1;) {
                count += n;
                if (count > UpdateValidation.MAX_FILE) throw new IOException("FILE_SIZE");
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static FileEntry entry(Path root, Path file) throws IOException {
        BasicFileAttributes before = attributes(file);
        if (!before.isRegularFile() || before.size() > UpdateValidation.MAX_FILE) throw new IOException("FILE_REQUIRED");
        String name = root.relativize(file).toString().replace(File.separatorChar, '/');
        UpdateValidation.managedPath(name);
        String hash = hash(file);
        BasicFileAttributes after = attributes(file);
        if (before.size() != after.size() || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("FILE_CHANGED");
        return new FileEntry(name, after.size(), hash, readOnly(file));
    }

    static void copy(InputStream in, OutputStream out, FileEntry expected) throws IOException {
        MessageDigest md = digest();
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = in.read(buffer)) != -1;) {
            count += n;
            if (count > expected.sizeBytes()) throw new IOException("EXPANDED_SIZE");
            md.update(buffer, 0, n);
            out.write(buffer, 0, n);
        }
        if (count != expected.sizeBytes() || !HexFormat.of().formatHex(md.digest()).equals(expected.sha256()))
            throw new IOException("FILE_DIGEST");
    }

    static void write(Path destination, FileEntry f, InputStream in) throws IOException {
        Path p = resolve(destination, f.path());
        Files.createDirectories(p.getParent());
        ancestors(p.getParent());
        try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) { copy(in, out, f); }
        readOnly(p, f.readOnly());
    }

    // Удаляется только каталог, созданный текущей операцией; ссылки не обходятся.
    static void cleanup(Path path, IOException failure) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                /** Отказывает обходу подменённого каталога или его предка. */
                @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                    ancestors(p);
                    if (!attributes(p).isDirectory()) throw new IOException("DIRECTORY_REQUIRED");
                    return FileVisitResult.CONTINUE;
                }
                /** Удаляет файл собственного staging без обхода ссылки. */
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                    ancestors(p.getParent());
                    if (!a.isSymbolicLink() && !a.isOther()) readOnly(p, false);
                    Files.delete(p);
                    return FileVisitResult.CONTINUE;
                }
                /** Удаляет пустой каталог собственного staging. */
                @Override public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException {
                    if (e != null) throw e;
                    ancestors(p);
                    Files.delete(p);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { failure.addSuppressed(e); }
    }
}
