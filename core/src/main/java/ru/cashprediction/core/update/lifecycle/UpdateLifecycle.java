package ru.cashprediction.core.update.lifecycle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Optional;
import ru.cashprediction.core.update.model.UpdateProblem;
import ru.cashprediction.core.update.model.UpdateProblem.Code;
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
    private StartupResult startupResult;
    private Stage stage = Stage.NEW;
    private UpdateProblem problem;

    private UpdateLifecycle(Path root, InstallCoordinator coordinator, URI selftestManifest) {
        this.root = root;
        this.coordinator = coordinator;
        this.selftestManifest = selftestManifest;
    }

    /** Создаёт lifecycle; сборка разработчика полностью инертна и не пишет файлов. */
    public static UpdateLifecycle create(Path installationRoot, Path cashMemory, String client, String[] args) {
        InstallCoordinator coordinator = null;
        UpdateProblem initializationFailure = null;
        if (System.getProperty("os.name", "").startsWith("Windows") && AppInfo.release() > 0
                && AppInfo.commit().matches("[0-9a-f]{40}")) {
            try {
                coordinator = new InstallCoordinator(installationRoot, cashMemory, client, args, AppInfo.release());
            } catch (IOException | RuntimeException failure) {
                initializationFailure = problem(Code.INITIALIZATION_FAILED, failure);
                // Обновления не препятствуют запуску исправной установленной версии.
            }
        }
        UpdateLifecycle lifecycle = new UpdateLifecycle(installationRoot, coordinator,
                selftestManifest(installationRoot, args, System.getProperties()));
        lifecycle.problem = initializationFailure;
        return lifecycle;
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
        return beforeUiResult().allowed();
    }

    /** Возвращает то же решение на повторе, сохраняя точку и диагностику ожидаемого отказа. */
    @Override public synchronized StartupResult beforeUiResult() {
        if (closed) return new StartupResult(Decision.CLOSED, Optional.ofNullable(problem));
        if (entered) return startupResult;
        entered = true;
        stage = coordinator == null ? Stage.INACTIVE : Stage.ENTERED;
        if (coordinator == null) ready = true;
        else {
            try { ready = coordinator.beforeUi(); }
            catch (IOException | RuntimeException failure) {
                // Существующая политика fail-open относится к исправной текущей версии; native барьер не ослабляется.
                problem = problem(Code.BARRIER_FAILED, failure);
                ready = true;
            }
        }
        startupResult = new StartupResult(ready ? Decision.ALLOWED : Decision.BLOCKED, Optional.ofNullable(problem));
        return startupResult;
    }

    /** Возвращает неизменяемый снимок без выдачи worker/preparer/coordinator или исходного исключения. */
    @Override public synchronized Status status() {
        return new Status(stage, Optional.ofNullable(problem));
    }

    /** Запускает ровно один фоновый проход после готовности основного интерфейса. */
    @Override public synchronized void afterUiReady() {
        if (closed || !entered || !ready || coordinator == null || worker != null) return;
        stage = Stage.PREPARING;
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
            boolean prepared = preparer.prepare();
            synchronized (this) {
                if (!closed) {
                    stage = prepared ? Stage.PREPARED : Stage.NOT_PREPARED;
                    preparer.lastProblem().ifPresent(value -> problem = value);
                }
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                if (!closed) {
                    stage = Stage.NOT_PREPARED;
                    problem = problem(Code.PREPARATION_FAILED, failure);
                }
            }
            // Диагностика доступна службе, но пользовательское уведомление не появляется.
        }
    }

    /** Отменяет подготовку и планирует установку без перезапуска на обычном выходе. */
    @Override public void close() {
        UpdatePreparer download;
        synchronized (this) {
            if (closed) return;
            closed = true;
            stage = Stage.CLOSED;
            download = preparer;
        }
        if (download != null) {
            try { download.close(); }
            catch (Exception failure) { recordProblem(Code.CANCELLATION_FAILED, failure); }
        }
        if (coordinator != null && entered && ready) {
            try { coordinator.normalExit(); }
            catch (IOException | RuntimeException failure) { recordProblem(Code.EXIT_FAILED, failure); }
        }
    }
    /** Запоминает технический отказ, не меняя уже закрытое состояние. */
    private synchronized void recordProblem(Code code, Exception failure) { problem = problem(code, failure); }

    /** Переводит локальное исключение в данные без потери исходной диагностики. */
    private static UpdateProblem problem(Code code, Exception failure) {
        String message = failure.getMessage();
        return new UpdateProblem(code, message == null || message.isBlank() ? failure.getClass().getSimpleName() : message);
    }
}
