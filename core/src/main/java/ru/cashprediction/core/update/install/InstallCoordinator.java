package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.net.UpdatePreparer;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Общий барьер входа в приложение и избрание скрытого установщика одной копии. */
public final class InstallCoordinator {
    private final Path root;
    private final Path updates;
    private final String client;
    private final String[] args;
    private final int release;
    private final HelperStarter starter;
    private final boolean portable;
    private boolean registered;

    /** Создаёт координатор; каталог пользовательских данных обязан принадлежать этому корню. */
    public InstallCoordinator(Path root, Path memory, String client, String[] args, int release) throws IOException {
        this(root, memory, client, args, release, PowerShellHelper::start, true);
    }

    InstallCoordinator(Path root, Path memory, String client, String[] args,
                       int release, HelperStarter starter) throws IOException {
        this(root, memory, client, args, release, starter, false);
    }

    InstallCoordinator(Path root, Path memory, String client, String[] args,
                       int release, HelperStarter starter, boolean portable) throws IOException {
        this.root = InstallFiles.root(root, memory);
        this.updates = this.root.resolve("CashMemory/Updates");
        RestartRequest.launcher(client);
        this.client = client;
        this.args = args.clone();
        this.release = release;
        this.starter = starter;
        this.portable = portable;
    }

    /** Регистрирует JVM до решения об установке; false требует завершить текущий запуск. */
    public synchronized boolean beforeUi() throws IOException {
        try (var ignored = InstallFiles.lock(updates)) {
            if (!registered) {
                ProcessLease.register(root, updates, client);
                registered = true;
            }
            Boolean recovered = recoverBeforeUi();
            if (recovered != null) return recovered;
            // Восстановление публикации Ready не использует сеть и само освобождает prepare.lock.
            // false не завершает приложение: решение установки принимает повторная проверка ниже.
            UpdatePreparer.recoverReady(root, release);
            return !schedule(true);
        }
    }

    /**
     * Возвращает null при отсутствии транзакции; завершённый журнал допускает UI только после проверки дерева.
     * При повреждённом текущем дереве false сохраняет запрет UI даже через fail-open обёртку lifecycle.
     * Ошибки при целом штатном образе передаются прежней обёртке без объявления транзакции завершённой.
     */
    private Boolean recoverBeforeUi() throws IOException {
        boolean active = Files.exists(updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS);
        boolean completed = !active && Files.exists(updates.resolve("completed-journal.json"), LinkOption.NOFOLLOW_LINKS);
        if (!active && !completed) return null;
        boolean terminalVerified = false;
        Map<String, Object> checkedJournal = null;
        try {
            Map<String, Object> journal = completed ? InstallJournal.readCompleted(root, updates) : InstallJournal.read(root, updates);
            checkedJournal = journal;
            if (InstallJournal.terminal(journal)) {
                verifyTerminalTree(journal);
                terminalVerified = true;
            }
            if (completed || "ROLLED_BACK".equals(journal.get("outcome"))) {
                starter.start(root, updates);
                return true;
            }
            request(journal);
            starter.start(root, updates);
            return false;
        } catch (IOException | RuntimeException failure) {
            // Отказ очистки не блокирует уже проверенную установленную или восстановленную версию.
            if (terminalVerified) return true;
            if (!currentImageUsable(checkedJournal)) return false;
            throw failure;
        }
    }

    /** Проверяет именно результат транзакции; не удаляет журнал, Backup, Bootstrap или запросы. */
    private void verifyTerminalTree(Map<String, Object> journal) throws IOException {
        if ("UPDATED".equals(journal.get("outcome"))) {
            UpdateManifest target = UpdateCodec.read(ru.cashprediction.core.json.JsonWriter.write(journal.get("target")));
            TreeDeltaEngine.verify(root, target.files(), target.treeSha256());
        } else {
            List<FileEntry> old = UpdateCodec.readFiles(journal.get("oldFiles"));
            TreeDeltaEngine.verify(root, old, (String) journal.get("oldTreeSha256"));
        }
        // Схема 1 действительно не имела portable-плана и cfg: её проверка дерева остаётся совместимой.
        if (Long.valueOf(2).equals(journal.get("schemaVersion"))) verifyStandardConfigs();
    }

    /** Проверяет локальные признаки целого текущего образа только для выбора безопасного fail-open. */
    private boolean currentImageUsable(Map<String, Object> checkedJournal) {
        try {
            List<FileEntry> files = TreeDeltaEngine.inventory(root);
            if (checkedJournal != null) {
                // Известный журнал не позволяет считать целым набор непустых, но повреждённых файлов.
                List<FileEntry> old = UpdateCodec.readFiles(checkedJournal.get("oldFiles"));
                UpdateManifest target = UpdateCodec.read(ru.cashprediction.core.json.JsonWriter.write(checkedJournal.get("target")));
                if (!files.equals(old) && !files.equals(target.files())) return false;
            }
            if (portable) {
                InstallJournal.requirePortableInventory(files);
                verifyStandardConfigs();
            } else {
                // Старые непортативные fixtures и транзакции схемы 1 не требуют отсутствовавших cfg.
                for (String clientId : List.of("fx", "swing", "web")) {
                    String launcher = RestartRequest.launcher(clientId);
                    if (files.stream().noneMatch(file -> file.path().equals(launcher) && file.sizeBytes() > 0)) return false;
                }
                if (files.stream().noneMatch(file -> file.path().startsWith("app/") && file.sizeBytes() > 0)
                        || files.stream().noneMatch(file -> file.path().startsWith("runtime/") && file.sizeBytes() > 0)) return false;
            }
            return true;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Требует штатный runtime в cfg перед разрешением завершённой portable-транзакции. */
    private void verifyStandardConfigs() throws IOException {
        for (String path : PortableBootstrap.CONFIGS) {
            PortableBootstrap.mainModule(InstallFiles.read(root.resolve(path), 65536));
        }
    }

    /** Планирует обновление на выходе; запрос перезапуска при этом не создаётся. */
    public synchronized void normalExit() throws IOException {
        if (!registered) return;
        try (var ignored = InstallFiles.lock(updates)) {
            if (!Files.exists(updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS)) schedule(false);
        }
    }

    private boolean schedule(boolean restart) throws IOException {
        // Тот же файл блокировки обязан использовать сетевой подготовитель W2.
        Path downloadLock = updates.resolve("prepare.lock");
        InstallFiles.guard(downloadLock);
        try (FileChannel channel = FileChannel.open(downloadLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { return false; }
            if (lock == null) return false;
            try (lock) {
                Path ready = updates.resolve("Ready");
                Path manifest = ready.resolve("update.json");
                if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return false;
                UpdateManifest target = UpdateCodec.read(InstallFiles.read(manifest, 8 * 1024 * 1024));
                if (target.releaseNumber() <= release || skipped(target.commitSha())) return false;
                if (Files.exists(updates.resolve("last-install.json"), LinkOption.NOFOLLOW_LINKS)) {
                    Map<String, Object> previous = InstallFiles.object(updates.resolve("last-install.json"));
                    if ("ROLLED_BACK".equals(previous.get("outcome"))
                            && target.commitSha().equals(previous.get("targetCommitSha"))) return false;
                }
                TreeDeltaEngine.verify(ready.resolve("tree"), target.files(), target.treeSha256());
                if (Files.exists(updates.resolve("Backup"), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ORPHAN_BACKUP");
                }
                Map<String, Object> journal = portable ? InstallJournal.preparePortable(root, target) : InstallJournal.prepare(root, target);
                PowerShellHelper.publish(updates);
                InstallJournal.write(updates, journal);
                if (restart) request(journal);
                starter.start(root, updates);
                return true;
            }
        }
    }

    private boolean skipped(String sha) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--updated-from") && i + 1 < args.length && args[i + 1].equals(sha)) return true;
            if (args[i].equals("--updated-from=" + sha)) return true;
        }
        return false;
    }

    private void request(Map<String, Object> journal) throws IOException {
        UpdateManifest target = UpdateCodec.read(ru.cashprediction.core.json.JsonWriter.write(journal.get("target")));
        RestartRequest.write(updates, UUID.fromString((String) journal.get("transactionId")), root,
                client, args, target.commitSha());
    }

    /** Шов теста: запуск процесса отделён от durable подготовки. */
    @FunctionalInterface
    interface HelperStarter {
        /** Запускает независимый от JVM процесс в проверенном служебном каталоге. */
        void start(Path root, Path updates) throws IOException;
    }
}
