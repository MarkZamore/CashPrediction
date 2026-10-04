package ru.cashprediction.core.update.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/**
 * Хранилище проверенного дерева: Ready/tree и Ready/update.json.
 * Журнал prepare-journal.json содержит целевой манифест, записанный до двух rename.
 * Все операции вызываются только под prepare.lock; журнал установки не затрагивается.
 */
final class ReadyStore {
    static final int JSON_LIMIT = 8 * 1024 * 1024;
    // Совпадает с обязательным inventory PortableBootstrap: core и три клиента
    // встроены jlink в runtime/lib/modules, отдельных app/*.jar здесь нет.
    private static final Set<String> PORTABLE_FILES = Set.of("CashPrediction.exe", "CashPrediction-Swing.exe",
            "CashPrediction-Web.exe", "app/CashPrediction.cfg", "app/CashPrediction-Swing.cfg",
            "app/CashPrediction-Web.cfg", "app/.jpackage.xml", "runtime/bin/jli.dll",
            "runtime/bin/server/jvm.dll", "runtime/lib/modules");
    private final Path updates;
    private final int currentRelease;

    ReadyStore(Path updates, int currentRelease) {
        this.updates = updates;
        this.currentRelease = currentRelease;
    }

    Path staging() { return updates.resolve("Staging"); }
    private Path ready() { return updates.resolve("Ready"); }
    private Path previous() { return updates.resolve("PreviousReady"); }
    private Path journal() { return updates.resolve("prepare-journal.json"); }

    UpdateManifest recover() throws IOException {
        UpdateManifest validReady = valid(ready());
        UpdateManifest target = readOrNull(journal());
        UpdateManifest candidate = target == null ? null : valid(staging());
        if (candidate != null && candidate.equals(target)
                && (validReady == null || candidate.releaseNumber() > validReady.releaseNumber())) {
            // Восстанавливается только кандидат, обозначенный долговечным журналом.
            if (Files.exists(ready(), LinkOption.NOFOLLOW_LINKS)) {
                if (validReady == null) UpdateFiles.delete(ready());
                else {
                    UpdateFiles.delete(previous());
                    UpdateFiles.move(ready(), previous());
                }
            }
            UpdateFiles.move(staging(), ready());
            validReady = valid(ready());
        }
        if (validReady == null) {
            UpdateManifest backup = valid(previous());
            if (backup != null) {
                UpdateFiles.delete(ready());
                UpdateFiles.move(previous(), ready());
                validReady = backup;
            }
        }
        if (validReady != null) UpdateFiles.delete(previous());
        else UpdateFiles.delete(ready());
        UpdateFiles.delete(staging());
        UpdateFiles.delete(journal());
        UpdateFiles.delete(updates.resolve("prepare-journal.json.tmp"));
        return validReady;
    }

    void publish(UpdateManifest target) throws IOException {
        requirePortableInventory(target);
        UpdateFiles.forceTree(staging().resolve("tree"));
        TreeDeltaEngine.verify(staging().resolve("tree"), target.files(), target.treeSha256());
        byte[] json = UpdateCodec.write(target).getBytes(StandardCharsets.UTF_8);
        if (json.length > JSON_LIMIT) throw new IOException("MANIFEST_TOO_LARGE");
        UpdateFiles.writeAtomic(staging().resolve("update.json"), json);
        // Journal commit предшествует изменениям Ready; незавершённый swap повторяем.
        UpdateFiles.writeAtomic(journal(), json);
        UpdateFiles.delete(previous());
        if (Files.exists(ready(), LinkOption.NOFOLLOW_LINKS)) UpdateFiles.move(ready(), previous());
        try {
            UpdateFiles.move(staging(), ready());
        } catch (IOException ex) {
            if (!Files.exists(ready(), LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(previous(), LinkOption.NOFOLLOW_LINKS)) {
                UpdateFiles.move(previous(), ready());
            }
            throw ex;
        }
        // Ошибка cleanup не превращает уже опубликованное проверенное дерево в неуспех.
        try {
            UpdateFiles.delete(previous());
            UpdateFiles.delete(journal());
        } catch (IOException ignored) {
            // Следующая сессия проверит Ready и завершит уборку под тем же lock.
        }
    }

    private UpdateManifest valid(Path directory) throws IOException {
        UpdateFiles.check(directory);
        UpdateManifest manifest = readOrNull(directory.resolve("update.json"));
        if (manifest == null || manifest.releaseNumber() <= currentRelease) return null;
        try {
            UpdatePreparer.validateAssets(manifest);
            requirePortableInventory(manifest);
            TreeDeltaEngine.verify(directory.resolve("tree"), manifest.files(), manifest.treeSha256());
            return manifest;
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    static void requirePortableInventory(UpdateManifest manifest) throws IOException {
        Set<String> nonempty = new HashSet<>();
        for (var file : manifest.files()) {
            if (file.sizeBytes() > 0) nonempty.add(file.path());
        }
        if (!nonempty.containsAll(PORTABLE_FILES)) throw new IOException("INCOMPLETE_PORTABLE_TARGET");
    }

    private UpdateManifest readOrNull(Path path) throws IOException {
        UpdateFiles.check(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return UpdateCodec.read(readUtf8(path));
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    static String readUtf8(Path path) throws IOException {
        UpdateFiles.check(path);
        if (Files.size(path) > JSON_LIMIT) throw new IOException("MANIFEST_TOO_LARGE");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(JSON_LIMIT + 1);
            if (bytes.length > JSON_LIMIT) throw new IOException("MANIFEST_TOO_LARGE");
            return decode(bytes);
        }
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
