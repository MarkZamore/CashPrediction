package ru.cashprediction.core.update.lifecycle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.io.AppInfo;
import ru.cashprediction.core.update.install.InstallCoordinator;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.net.UpdatePreparer;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Тихий общий жизненный цикл обновлений для трёх способов запуска приложения. */
public final class UpdateLifecycle implements UpdateSessionLifecycle {
    private static final URI MANIFEST = URI.create(
            "https://github.com/MarkZamore/CashPrediction/releases/latest/download/update.json");
    private final Path root;
    private final InstallCoordinator coordinator;
    private final URI selftestManifest;
    private UpdatePreparer preparer;
    private Thread worker;
    private boolean entered;
    private boolean ready;
    private boolean closed;

    private UpdateLifecycle(Path root, InstallCoordinator coordinator, URI selftestManifest) {
        this.root = root;
        this.coordinator = coordinator;
        this.selftestManifest = selftestManifest;
    }

    /** Создаёт lifecycle; сборка разработчика полностью инертна и не пишет файлов. */
    public static UpdateLifecycle create(Path installationRoot, Path cashMemory, String client, String[] args) {
        InstallCoordinator coordinator = null;
        if (System.getProperty("os.name", "").startsWith("Windows") && AppInfo.release() > 0
                && AppInfo.commit().matches("[0-9a-f]{40}")) {
            try {
                coordinator = new InstallCoordinator(installationRoot, cashMemory, client, args, AppInfo.release());
            } catch (IOException | RuntimeException ignored) {
                // Обновления не препятствуют запуску исправной установленной версии.
            }
        }
        return new UpdateLifecycle(installationRoot, coordinator,
                selftestManifest(installationRoot, args, System.getProperties()));
    }

    /**
     * Тестовый адрес не является пользовательской настройкой обновления.
     * Он допускается только вместе с явным test-api, home этой копии и UUID-узлом selftest.
     * Неверный или неавторизованный override никогда не меняет production endpoint.
     */
    static URI selftestManifest(Path root, String[] args, Properties properties) {
        String value = properties.getProperty("cashprediction.update.selftest.manifest");
        if (value == null) return null;
        try {
            LaunchOptions options = LaunchOptions.parse(java.util.Arrays.asList(args), properties);
            if (!options.testApi() || options.home() == null || !options.home().isAbsolute()
                    || !options.home().equals(root.toAbsolutePath()) || !options.home().equals(options.home().normalize())
                    || options.registryNode() == null
                    || !options.registryNode().matches("ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                return null;
            }
            URI endpoint = URI.create(value);
            if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                    || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !"/update.json".equals(endpoint.getRawPath())) return null;
            return endpoint;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Проверяет локальное восстановление до UI; false означает переданный запрос перезапуска. */
    @Override public synchronized boolean beforeUi() {
        if (closed) return false;
        if (entered) return ready;
        entered = true;
        if (coordinator == null) return ready = true;
        try { return ready = coordinator.beforeUi(); }
        catch (IOException | RuntimeException ignored) {
            // Отказ регистрации или установщика не блокирует приложение; census помощника страхует lease.
            return ready = true;
        }
    }

    /** Запускает ровно один фоновый проход после готовности основного интерфейса. */
    @Override public synchronized void afterUiReady() {
        if (closed || !entered || !ready || coordinator == null || worker != null) return;
        worker = new Thread(this::prepare, "cashprediction-update");
        worker.setDaemon(true);
        worker.start();
    }

    private void prepare() {
        try {
            String hash = TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(root));
            synchronized (this) {
                if (closed) return;
                InstalledVersion installed = new InstalledVersion(AppInfo.release(), AppInfo.commit(), hash);
                preparer = selftestManifest == null ? new UpdatePreparer(root, installed, MANIFEST)
                        : UpdatePreparer.forSelftest(root, installed, selftestManifest);
            }
            preparer.prepare();
        } catch (IOException | RuntimeException ignored) {
            // Ошибка сети и подготовки не имеет видимого пользователю уведомления.
        }
    }

    /** Отменяет подготовку и планирует установку без перезапуска на обычном выходе. */
    @Override public void close() {
        UpdatePreparer download;
        synchronized (this) {
            if (closed) return;
            closed = true;
            download = preparer;
        }
        if (download != null) {
            try { download.close(); } catch (Exception ignored) { }
        }
        if (coordinator != null && entered && ready) {
            try { coordinator.normalExit(); } catch (IOException | RuntimeException ignored) { }
        }
    }
}
