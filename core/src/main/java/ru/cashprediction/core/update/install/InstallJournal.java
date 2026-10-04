package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Устойчивый журнал с исходным инвентарём, достаточным для автономного отката. */
final class InstallJournal {
    static final Set<String> PHASES = Set.of("PREPARED", "WAITING", "BOOTSTRAPPING", "BACKING_UP", "INSTALLING",
            "VERIFYING", "COMMITTED", "ROLLING_BACK");
    // Вместе со STABLE это те же десять непустых файлов, которые требует ReadyStore.
    private static final Set<String> REQUIRED_RUNTIME = Set.of("runtime/bin/jli.dll",
            "runtime/bin/server/jvm.dll", "runtime/lib/modules");
    private InstallJournal() { }

    static Map<String, Object> prepare(Path root, UpdateManifest target) throws IOException {
        List<FileEntry> old = TreeDeltaEngine.inventory(root);
        Map<String, Object> journal = new LinkedHashMap<>();
        journal.put("schemaVersion", 1);
        journal.put("installationRoot", root.toString());
        journal.put("transactionId", UUID.randomUUID().toString());
        journal.put("target", JsonParser.parseObject(UpdateCodec.write(target)));
        journal.put("phase", "PREPARED");
        journal.put("oldFiles", old.stream().map(InstallJournal::entry).toList());
        journal.put("oldTreeSha256", TreeDeltaEngine.treeHash(old));
        journal.put("operations", List.of());
        journal.put("outcome", "PENDING");
        return journal;
    }

    static Map<String, Object> preparePortable(Path root, UpdateManifest target) throws IOException {
        Map<String, Object> journal = prepare(root, target);
        List<FileEntry> old = UpdateCodec.readFiles(journal.get("oldFiles"));
        journal.put("bootstrap", PortableBootstrap.plan(root, target, old));
        journal.put("schemaVersion", 2);
        return journal;
    }

    static Map<String, Object> read(Path root, Path updates) throws IOException {
        return readFile(root, updates.resolve("install-journal.json"));
    }

    /** Читает завершённый журнал через ту же защиту путей и требует терминальный результат. */
    static Map<String, Object> readCompleted(Path root, Path updates) throws IOException {
        Map<String, Object> journal = readFile(root, updates.resolve("completed-journal.json"));
        if (!terminal(journal)) throw new IOException("JOURNAL_NOT_TERMINAL");
        return journal;
    }

    /** Принимает только фактически записываемые схемы 1 и 2 без угадывания недостающих полей. */
    private static Map<String, Object> readFile(Path root, Path journalPath) throws IOException {
        Map<String, Object> journal = InstallFiles.object(journalPath);
        try {
            long schema = UpdateCodec.number(journal, "schemaVersion");
            boolean portable = schema == 2;
            if (portable) {
                UpdateCodec.keys(journal, "schemaVersion", "installationRoot", "transactionId", "target", "phase",
                        "oldFiles", "oldTreeSha256", "operations", "outcome", "bootstrap");
            } else {
                UpdateCodec.keys(journal, "schemaVersion", "installationRoot", "transactionId", "target", "phase",
                        "oldFiles", "oldTreeSha256", "operations", "outcome");
            }
            if (!(portable || schema == 1)
                    || !root.toString().equals(journal.get("installationRoot"))
                    || !PHASES.contains(UpdateCodec.string(journal, "phase"))) throw new IOException("JOURNAL_IDENTITY");
            String transaction = UpdateCodec.string(journal, "transactionId");
            if (!UUID.fromString(transaction).toString().equals(transaction)) throw new IOException("JOURNAL_TRANSACTION_ID");
            validateOutcome(journal);
            UpdateManifest target = UpdateCodec.read(JsonWriter.write(journal.get("target")));
            List<FileEntry> old = UpdateCodec.readFiles(journal.get("oldFiles"));
            if (!TreeDeltaEngine.treeHash(old).equals(UpdateCodec.string(journal, "oldTreeSha256"))
                    || !TreeDeltaEngine.treeHash(target.files()).equals(target.treeSha256())) throw new IOException("JOURNAL_TREE_HASH");
            if (portable) validateBootstrap(journal, old, target);
            if (!(journal.get("operations") instanceof List<?> operations) || operations.size() > (portable ? 160000 : 80000)) {
                throw new IllegalArgumentException();
            }
            for (Object value : operations) {
                Map<String, Object> operation = Json.asObject(value, "operation");
                String kind = Json.requireString(operation, "kind");
                String path = Json.requireString(operation, "path");
                Set<String> kinds = portable ? Set.of("BACKUP", "INSTALL", "UNINSTALL", "RESTORE", "BOOT_COPY",
                        "BACKUP_COPY", "REDIRECT", "REPLACE", "RESTORE_REPLACE", "CFG_SWITCH")
                        : Set.of("BACKUP", "INSTALL", "UNINSTALL", "RESTORE");
                if (!kinds.contains(kind)
                        || !Set.of("BEFORE", "AFTER").contains(operation.get("state"))
                        || !operation.keySet().equals(Set.of("kind", "path", "state"))) throw new IllegalArgumentException();
                List<FileEntry> entries = Set.of("BACKUP", "RESTORE", "BOOT_COPY", "BACKUP_COPY", "REDIRECT", "RESTORE_REPLACE")
                        .contains(kind) ? old : target.files();
                if (entries.stream().noneMatch(file -> file.path().equals(path))) throw new IllegalArgumentException();
                if (kind.equals("BOOT_COPY") && !PortableBootstrap.protectedPayload(path)) throw new IllegalArgumentException();
                if (Set.of("BACKUP_COPY", "REPLACE", "RESTORE_REPLACE").contains(kind) && !PortableBootstrap.STABLE.contains(path)) {
                    throw new IllegalArgumentException();
                }
                if (Set.of("REDIRECT", "CFG_SWITCH").contains(kind) && !PortableBootstrap.CONFIGS.contains(path)) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException failure) {
            throw new IOException("JOURNAL_INVALID", failure);
        }
        return journal;
    }

    /** Повторяет пары phase/outcome автономного помощника, включая незавершённый откат PENDING. */
    private static void validateOutcome(Map<String, Object> journal) throws IOException {
        String phase = UpdateCodec.string(journal, "phase");
        String outcome = UpdateCodec.string(journal, "outcome");
        if (!Set.of("PENDING", "UPDATED", "ROLLED_BACK").contains(outcome)
                || outcome.equals("UPDATED") && !phase.equals("COMMITTED")
                || outcome.equals("ROLLED_BACK") && !phase.equals("ROLLING_BACK")
                || phase.equals("COMMITTED") && !outcome.equals("UPDATED")) throw new IOException("JOURNAL_OUTCOME_PHASE");
    }

    /** Проверяет терминальность уже проверенного журнала, не выдавая её за проверку файлов. */
    static boolean terminal(Map<String, Object> journal) {
        return "UPDATED".equals(journal.get("outcome")) && "COMMITTED".equals(journal.get("phase"))
                || "ROLLED_BACK".equals(journal.get("outcome")) && "ROLLING_BACK".equals(journal.get("phase"));
    }

    /** Требует весь непустой portable-инвентарь, а не только существование трёх exe. */
    static void requirePortableInventory(List<FileEntry> files) throws IOException {
        for (String path : PortableBootstrap.STABLE) {
            if (files.stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)) {
                throw new IOException("BOOTSTRAP_IMAGE_LAYOUT");
            }
        }
        for (String path : REQUIRED_RUNTIME) {
            if (files.stream().noneMatch(file -> file.path().equals(path) && file.sizeBytes() > 0)) {
                throw new IOException("BOOTSTRAP_RUNTIME_LAYOUT");
            }
        }
    }

    /** Проверяет полный план без привязки к промежуточному состоянию исходного дерева при аварии. */
    private static void validateBootstrap(Map<String, Object> journal, List<FileEntry> old, UpdateManifest target) throws IOException {
        Map<String, Object> bootstrap = Json.asObject(journal.get("bootstrap"), "bootstrap");
        if (!bootstrap.keySet().equals(Set.of("schemaVersion", "state", "publishState", "redirectFiles", "cfgTexts"))
                || !Long.valueOf(1).equals(bootstrap.get("schemaVersion"))
                || !Set.of("INITIAL", "COPYING", "COPIED", "ACTIVE", "RESTORED", "CLEANED").contains(bootstrap.get("state"))
                || !Set.of("NONE", "BEFORE", "AFTER").contains(bootstrap.get("publishState"))) {
            throw new IOException("BOOTSTRAP_JOURNAL");
        }
        List<FileEntry> redirects = UpdateCodec.readFiles(bootstrap.get("redirectFiles"));
        if (redirects.size() != 3 || !redirects.stream().map(FileEntry::path).collect(java.util.stream.Collectors.toSet())
                .equals(Set.copyOf(PortableBootstrap.CONFIGS))) throw new IOException("BOOTSTRAP_CONFIGS");
        requirePortableInventory(old);
        requirePortableInventory(target.files());
        if (!(bootstrap.get("cfgTexts") instanceof List<?> texts) || texts.size() != 3) throw new IOException("BOOTSTRAP_TEXTS");
        Set<String> seen = new java.util.HashSet<>();
        for (Object value : texts) {
            Map<String, Object> text = Json.asObject(value, "cfg");
            String path = Json.requireString(text, "path");
            String content = UpdateCodec.string(text, "text");
            if (!text.keySet().equals(Set.of("path", "text")) || !seen.add(path)) throw new IOException("BOOTSTRAP_TEXTS");
            FileEntry redirect = redirects.stream().filter(file -> file.path().equals(path)).findFirst()
                    .orElseThrow(() -> new IOException("BOOTSTRAP_TEXT_PATH"));
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65536 || bytes.length != redirect.sizeBytes() || !PortableBootstrap.sha(bytes).equals(redirect.sha256())
                    || old.stream().noneMatch(file -> file.path().equals(path) && file.readOnly() == redirect.readOnly())) {
                throw new IOException("BOOTSTRAP_TEXT_IDENTITY");
            }
            validateRedirectText(content);
            if (content.contains("java-options=" + PortableBootstrap.MODULES)) PortableBootstrap.requireModules(old);
        }
        if (target.files().stream().anyMatch(file -> file.path().startsWith("app/")
                && PortableBootstrap.protectedPayload(file.path()))) PortableBootstrap.requireModules(target.files());
    }

    /** Разбирает runtime как единственное свойство Application и переиспользует грамматику исходного cfg. */
    private static void validateRedirectText(String content) throws IOException {
        if (content.replace("\r\n", "\n").indexOf('\r') >= 0) throw new IOException("BOOTSTRAP_CFG_ENCODING");
        String section = "";
        int runtime = 0;
        List<String> normalized = new ArrayList<>();
        for (String line : content.split("\\r?\\n", -1)) {
            String stripped = line.strip();
            if ((stripped.toLowerCase(Locale.ROOT).startsWith("app.") || stripped.startsWith("["))
                    && !stripped.equals(line)) throw new IOException("BOOTSTRAP_CFG_GRAMMAR");
            if (line.toLowerCase(Locale.ROOT).startsWith("app.") && !line.startsWith("app.")) {
                throw new IOException("BOOTSTRAP_CFG_GRAMMAR");
            }
            if (line.startsWith("[")) section = line;
            if (line.startsWith("app.runtime=")) {
                if (!section.equals("[Application]") || !line.equals("app.runtime=" + PortableBootstrap.RUNTIME)
                        || ++runtime != 1) throw new IOException("BOOTSTRAP_CFG_RUNTIME");
                line = "app.runtime=$ROOTDIR/runtime";
            }
            if (section.equals("[JavaOptions]") && line.equals("java-options=" + PortableBootstrap.MODULES)) {
                line = "java-options=$APPDIR";
            }
            normalized.add(line);
        }
        if (runtime != 1) throw new IOException("BOOTSTRAP_CFG_RUNTIME");
        PortableBootstrap.mainModule(String.join("\n", normalized));
    }

    static void write(Path updates, Map<String, Object> journal) throws IOException {
        InstallFiles.write(updates.resolve("install-journal.json"), JsonWriter.write(journal));
    }

    static Map<String, Object> entry(FileEntry file) {
        return Map.of("path", file.path(), "sizeBytes", file.sizeBytes(),
                "sha256", file.sha256(), "readOnly", file.readOnly());
    }
}
