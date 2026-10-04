package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateManifest;

/** План сохранения обычных jpackage-лаунчеров с временным независимым runtime. */
final class PortableBootstrap {
    static final List<String> CONFIGS = List.of("app/CashPrediction.cfg",
            "app/CashPrediction-Swing.cfg", "app/CashPrediction-Web.cfg");
    static final Set<String> STABLE = Set.of("CashPrediction.exe", "CashPrediction-Swing.exe",
            "CashPrediction-Web.exe", "app/CashPrediction.cfg", "app/CashPrediction-Swing.cfg",
            "app/CashPrediction-Web.cfg", "app/.jpackage.xml");
    static final String RUNTIME = "$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\runtime";
    static final String MODULES = "$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\app";

    private PortableBootstrap() { }

    /** Файлы, без которых старый main-класс не запустит восстановление незавершённой установки. */
    static boolean protectedPayload(String path) {
        return path.startsWith("runtime/") || path.matches("app/cashprediction-(core|ui-fx|ui-swing|web)-[A-Za-z0-9_.-]+\\.jar");
    }

    /** Требует по одному непустому модулю каждой роли, а не четыре файла произвольных версий core. */
    static void requireModules(List<FileEntry> files) throws IOException {
        for (String role : List.of("core", "ui-fx", "ui-swing", "web")) {
            List<FileEntry> matching = files.stream().filter(file -> file.path().matches(
                    "app/cashprediction-" + role + "-[A-Za-z0-9_.-]+\\.jar")).toList();
            if (matching.size() != 1 || matching.getFirst().sizeBytes() == 0) {
                throw new IOException("BOOTSTRAP_MODULE_LAYOUT");
            }
        }
    }

    static Map<String, Object> plan(Path root, UpdateManifest target, List<FileEntry> old) throws IOException {
        if (Files.exists(root.resolve("CashMemory/Updates/Bootstrap"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("BOOTSTRAP_COLLISION");
        }
        for (String path : STABLE) {
            if (old.stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)
                    || target.files().stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)) {
                throw new IOException("BOOTSTRAP_IMAGE_LAYOUT");
            }
        }
        for (String path : List.of("runtime/bin/jli.dll", "runtime/bin/server/jvm.dll", "runtime/lib/modules")) {
            if (old.stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)
                    || target.files().stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)) {
                throw new IOException("BOOTSTRAP_RUNTIME_LAYOUT");
            }
        }
        List<Map<String, Object>> redirects = new ArrayList<>();
        List<Map<String, Object>> texts = new ArrayList<>();
        for (String path : CONFIGS) {
            FileEntry original = old.stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow();
            String oldText = InstallFiles.read(root.resolve(path), 65536);
            String targetText = InstallFiles.read(root.resolve("CashMemory/Updates/Ready/tree").resolve(path), 65536);
            if (!mainModule(oldText).equals(mainModule(targetText))) throw new IOException("BOOTSTRAP_MAIN_MODULE");
            if (externalModules(oldText)) requireModules(old);
            if (externalModules(targetText)) requireModules(target.files());
            String text = redirect(oldText);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            redirects.add(InstallJournal.entry(new FileEntry(path, bytes.length, sha(bytes), original.readOnly())));
            texts.add(Map.of("path", path, "text", text));
        }
        redirects.sort((a, b) -> java.util.Arrays.compareUnsigned(((String) a.get("path")).getBytes(StandardCharsets.UTF_8),
                ((String) b.get("path")).getBytes(StandardCharsets.UTF_8)));
        return Map.of("schemaVersion", 1, "state", "INITIAL", "publishState", "NONE", "redirectFiles", redirects, "cfgTexts", texts);
    }

    static String redirect(String original) throws IOException {
        mainModule(original);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>();
        String section = "";
        for (String line : original.split("\\r?\\n", -1)) {
            if (line.startsWith("[")) section = line;
            if (section.equals("[Application]") && line.startsWith("app.runtime=")) continue;
            lines.add(section.equals("[JavaOptions]") && line.equals("java-options=$APPDIR")
                    ? "java-options=" + MODULES : line);
            if (line.equals("[Application]")) lines.add("app.runtime=" + RUNTIME);
        }
        return String.join(newline, lines);
    }

    static String mainModule(String text) throws IOException {
        if (text.indexOf('\0') >= 0 || text.indexOf('\ufeff') >= 0 || text.contains("\r") && !text.contains("\r\n")) {
            throw new IOException("BOOTSTRAP_CFG_ENCODING");
        }
        String section = "";
        String module = null;
        int application = 0;
        int runtime = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.startsWith("[")) {
                section = line;
                if (line.equals("[Application]")) application++;
            }
            if (!section.equals("[Application]")) continue;
            if (line.startsWith("app.mainmodule=")) {
                if (module != null) throw new IOException("BOOTSTRAP_CFG_DUPLICATE");
                module = line.substring("app.mainmodule=".length());
            } else if (line.startsWith("app.runtime=")) {
                String value = line.substring("app.runtime=".length());
                if (++runtime > 1 || !(value.equals("$ROOTDIR/runtime") || value.equals("$ROOTDIR\\runtime"))) {
                    throw new IOException("BOOTSTRAP_CFG_RUNTIME");
                }
            } else if (line.startsWith("app.") && !line.startsWith("app.version=")) {
                throw new IOException("BOOTSTRAP_CFG_EXTERNAL_APPLICATION");
            }
        }
        if (application != 1 || module == null || !module.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) {
            throw new IOException("BOOTSTRAP_CFG_MODULE");
        }
        externalModules(text);
        return module;
    }

    /** Принимает только сгенерированный нами локальный module-path, не произвольный путь к коду. */
    static boolean externalModules(String text) throws IOException {
        String section = "";
        boolean awaitingPath = false;
        int paths = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.startsWith("[")) section = line;
            if (awaitingPath) {
                if (!section.equals("[JavaOptions]") || !line.equals("java-options=$APPDIR")) {
                    throw new IOException("BOOTSTRAP_CFG_MODULE_PATH");
                }
                awaitingPath = false;
                continue;
            }
            if (!section.equals("[JavaOptions]")) continue;
            if (line.equals("java-options=--module-path")) {
                if (++paths != 1) throw new IOException("BOOTSTRAP_CFG_MODULE_PATH");
                awaitingPath = true;
            } else if (line.startsWith("java-options=--module-path=") || line.startsWith("java-options=-p")
                    || line.equals("java-options=$APPDIR")) {
                throw new IOException("BOOTSTRAP_CFG_MODULE_PATH");
            }
        }
        if (awaitingPath) throw new IOException("BOOTSTRAP_CFG_MODULE_PATH");
        return paths == 1;
    }

    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
