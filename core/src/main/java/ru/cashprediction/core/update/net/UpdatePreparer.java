package ru.cashprediction.core.update.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.update.model.UpdateProblem;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.cashprediction.core.update.model.DeltaPatch;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/**
 * Один тихий проход подготовки обновления, без установки и пользовательских уведомлений.
 * Вызывать prepare из фонового потока. Повторный вызов не выполняет запросов.
 * close отменяет HTTP, ждёт очистки собственных временных файлов и освобождения lock.
 * Готовое состояние хранится в CashMemory/Updates/Ready/{tree,update.json}.
 */
public final class UpdatePreparer implements AutoCloseable {
    private static final URI PRODUCTION = URI.create(
            "https://github.com/MarkZamore/CashPrediction/releases/latest/download/update.json");
    private static final Set<String> HOSTS = Set.of("github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private static final long CONTAINER_LIMIT = 512L * 1024 * 1024;
    private final Path installationRoot;
    private final InstalledVersion current;
    private final URI manifestUri;
    private final HttpClient client;
    private final boolean test;
    private final boolean ownsClient;
    private final Duration timeout;
    private final Duration firstBackoff;
    private final Duration secondBackoff;
    private final Object state = new Object();
    private boolean started;
    private boolean closed;
    private boolean result;
    private Thread worker;
    private RequestSlot activeRequest;
    private UpdateProblem lastProblem;

    /**
     * Создаёт подготовитель с фиксированным production endpoint.
     * @param installationRoot корень переносимой копии
     * @param current идентичность установленного релиза
     * @param manifestUri только фиксированный URI update.json из контракта S7
     */
    public UpdatePreparer(Path installationRoot, InstalledVersion current, URI manifestUri) {
        this(installationRoot, current, manifestUri, productionClient(manifestUri),
                Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(2), false, true);
    }

    /**
     * Создаёт подготовитель для настоящей portable-проверки на локальном HTTP-сервере.
     * Обычный lifecycle не вызывает этот маршрут без явного изолированного самотеста.
     * Проверки контейнеров, путей, хешей, блокировок и публикации Ready остаются прежними.
     * @param installationRoot корень тестовой переносимой копии
     * @param current идентичность установленного релиза
     * @param manifestUri исключительно loopback URI без query
     * @return подготовитель, владеющий своим HTTP-клиентом
     */
    public static UpdatePreparer forSelftest(Path installationRoot, InstalledVersion current, URI manifestUri) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        try {
            return new UpdatePreparer(installationRoot, current, manifestUri, client,
                    Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(2), true, true);
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    /** Тестовый seam: только loopback, явный транспорт и интервалы, без CLI override. */
    UpdatePreparer(Path installationRoot, InstalledVersion current, URI manifestUri,
            HttpClient client, Duration timeout, Duration firstBackoff, Duration secondBackoff) {
        this(installationRoot, current, manifestUri, client, timeout, firstBackoff, secondBackoff, true, false);
    }

    private UpdatePreparer(Path installationRoot, InstalledVersion current, URI manifestUri,
            HttpClient client, Duration timeout, Duration firstBackoff, Duration secondBackoff,
            boolean test, boolean ownsClient) {
        this.installationRoot = Objects.requireNonNull(installationRoot).toAbsolutePath().normalize();
        this.current = Objects.requireNonNull(current);
        this.manifestUri = Objects.requireNonNull(manifestUri);
        this.client = Objects.requireNonNull(client);
        this.test = test;
        this.ownsClient = ownsClient;
        this.timeout = positive(timeout);
        this.firstBackoff = nonnegative(firstBackoff);
        this.secondBackoff = nonnegative(secondBackoff);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("AUTOMATIC_REDIRECTS_FORBIDDEN");
        }
        if ((!test && !PRODUCTION.equals(manifestUri))
                || (test && (!loopback(manifestUri) || manifestUri.getQuery() != null))) {
            throw new IllegalArgumentException("FIXED_MANIFEST_URI_REQUIRED");
        }
    }

    /**
     * Восстанавливает прерванную публикацию Ready до открытия интерфейса, без сети.
     * Вызывать перед чтением Ready и до захвата prepare.lock самим вызывающим кодом.
     * Метод самостоятельно пытается захватить prepare.lock и освобождает его до возврата.
     * При занятой блокировке или наличии install-journal.json служебные деревья не меняются.
     * Читаются только фиксированные Ready, PreviousReady, Staging и prepare-journal.json
     * внутри CashMemory/Updates; пути из журнала никогда не определяют источник rename.
     * W3 обязан повторно проверить Ready под своей блокировкой перед планированием установки.
     *
     * @param installationRoot корень переносимой копии без сегментов точки и перехода к родителю
     * @param currentRelease номер установленной версии; при номере меньше единицы метод инертен
     * @return true только при наличии проверенного Ready новее установленной версии;
     *         false при отсутствии Ready, занятой блокировке или активной установке
     * @throws IOException при небезопасном пути, ошибке диска или отказе восстановления
     */
    public static boolean recoverReady(Path installationRoot, int currentRelease) throws IOException {
        if (currentRelease <= 0) return false;
        Path root = Objects.requireNonNull(installationRoot).toAbsolutePath();
        if (!root.equals(root.normalize())) throw new IOException("UNSAFE_INSTALLATION_ROOT");
        UpdateFiles.check(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("NOT_INSTALLATION_ROOT");
        Path updates = root.resolve("CashMemory/Updates");
        UpdateFiles.check(updates);
        if (!Files.exists(updates, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(updates, LinkOption.NOFOLLOW_LINKS)) throw new IOException("NOT_DIRECTORY");
        Path lockPath = updates.resolve("prepare.lock");
        UpdateFiles.check(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { return false; }
            if (lock == null) return false;
            try (lock) {
                if (Files.exists(updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS)) return false;
                return new ReadyStore(updates, currentRelease).recover() != null;
            }
        }
    }

    /**
     * Выполняет единственный проход, максимум три попытки манифеста и один fallback.
     * @return true только если имеется проверенный Ready; ошибки сети дают false
     */
    public boolean prepare() {
        synchronized (state) {
            if (closed) return false;
            if (started) return worker == null && result;
            started = true;
            worker = Thread.currentThread();
        }
        boolean ready = false;
        try {
            if (current.releaseNumber() <= 0 || !hex(current.commitSha(), 40)
                    || (!test && !System.getProperty("os.name", "").startsWith("Windows"))) return false;
            UpdateFiles.check(installationRoot);
            Path updates = installationRoot.resolve("CashMemory/Updates");
            UpdateFiles.directory(updates);
            Path lockPath = updates.resolve("prepare.lock");
            UpdateFiles.check(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException ex) { return false; }
                if (lock == null) return false;
                try (lock) {
                    checkCancelled();
                    // Никогда не трогать Ready во время уже запланированной установки.
                    if (Files.exists(updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS)) return false;
                    ReadyStore store = new ReadyStore(updates, current.releaseNumber());
                    UpdateManifest cached = store.recover();
                    ready = cached != null;
                    UpdateFiles.delete(updates.resolve("payload.download"));
                    Path deltaBase = updates.resolve("DeltaBase");
                    UpdateFiles.delete(deltaBase);
                    try {
                        UpdateManifest target = manifest();
                        if (target == null || target.releaseNumber() <= current.releaseNumber()
                                || (cached != null && target.releaseNumber() <= cached.releaseNumber())) return ready;
                        Path stage = store.staging();
                        UpdateFiles.directory(stage);
                        Path payload = updates.resolve("payload.download");
                        Path tree = stage.resolve("tree");
                        DeltaPatch delta = select(target);
                        boolean applied = false;
                        if (delta != null) {
                            try {
                                download(asset(delta.assetName()), payload, delta.sizeBytes(), delta.sha256());
                                checkCancelled();
                                // Destination не может лежать внутри baseRoot движка.
                                // Снимок базы и Staging - соседние собственные каталоги в Updates.
                                List<FileEntry> baseFiles = snapshotBase(deltaBase);
                                TreeDeltaEngine.apply(deltaBase, current, payload, target, tree);
                                checkCancelled();
                                TreeDeltaEngine.verify(installationRoot, baseFiles, current.treeSha256());
                                ReadyStore.requirePortableInventory(target);
                                TreeDeltaEngine.verify(tree, target.files(), target.treeSha256());
                                applied = true;
                            } catch (IOException | IllegalArgumentException ex) {
                                checkCancelled();
                                UpdateFiles.delete(tree);
                                UpdateFiles.delete(payload);
                            } finally {
                                UpdateFiles.delete(deltaBase);
                            }
                        }
                        if (!applied) {
                            download(asset(target.assetName()), payload, target.sizeBytes(), target.sha256());
                            checkCancelled();
                            TreeDeltaEngine.extractFull(payload, target, tree);
                        }
                        TreeDeltaEngine.verify(tree, target.files(), target.treeSha256());
                        ReadyStore.requirePortableInventory(target);
                        // Общий монитор задаёт порядок close и публикации; после отмены Ready не появляется.
                        synchronized (state) {
                            checkCancelled();
                            if (Files.exists(updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS)) return ready;
                            store.publish(target);
                            ready = true;
                        }
                    } finally {
                        UpdateFiles.delete(deltaBase);
                        UpdateFiles.delete(updates.resolve("payload.download"));
                        // После journal commit кандидат нужен recovery, удалять его нельзя.
                        if (!Files.exists(updates.resolve("prepare-journal.json"), LinkOption.NOFOLLOW_LINKS)) {
                            UpdateFiles.delete(store.staging());
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            recordProblem(ex);
            // Технический отказ не препятствует текущему приложению; valid cache сохраняется.
        } finally {
            synchronized (state) {
                result = !closed && ready;
                worker = null;
                state.notifyAll();
            }
        }
        synchronized (state) { return result; }
    }

    /**
     * Возвращает data-only диагностику отказа после действующей политики повторов.
     * Отсутствие более нового релиза, занятая блокировка и явная отмена сами по себе не являются отказом.
     * @return машинная причина с исходной диагностикой, если она возникла
     */
    public Optional<UpdateProblem> lastProblem() {
        synchronized (state) { return Optional.ofNullable(lastProblem); }
    }

    /** Сохраняет отказ внутри владельца без зависимости от UI или lifecycle пакета. */
    private void recordProblem(Exception failure) {
        synchronized (state) {
            if (closed) return;
            String message = failure.getMessage();
            lastProblem = new UpdateProblem(UpdateProblem.Code.PREPARATION_FAILED,
                    message == null || message.isBlank() ? failure.getClass().getSimpleName() : message);
        }
    }

    private UpdateManifest manifest() throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled();
            try {
                URI uri = URI.create(manifestUri + "?cache=" + UUID.randomUUID());
                byte[] json = request(uri, null, ReadyStore.JSON_LIMIT, -1, null);
                UpdateManifest target = UpdateCodec.read(ReadyStore.decode(json));
                validateAssets(target);
                ReadyStore.requirePortableInventory(target);
                return target;
            } catch (IOException | IllegalArgumentException ex) {
                checkCancelled();
                if (attempt < 2) pause(attempt == 0 ? firstBackoff : secondBackoff);
                else recordProblem(ex);
            }
        }
        return null;
    }

    private DeltaPatch select(UpdateManifest target) throws IOException {
        checkCancelled();
        List<DeltaPatch> candidates = target.deltaPatches().stream().filter(delta ->
                delta.baseReleaseNumber() == current.releaseNumber()
                && delta.baseCommitSha().equalsIgnoreCase(current.commitSha())
                && delta.baseTreeSha256().equalsIgnoreCase(current.treeSha256())).toList();
        if (candidates.isEmpty()) return null;
        try {
            String actual = TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(installationRoot));
            checkCancelled();
            return actual.equalsIgnoreCase(current.treeSha256()) ? candidates.getFirst() : null;
        } catch (IOException | IllegalArgumentException ex) {
            checkCancelled();
            return null;
        }
    }

    private List<FileEntry> snapshotBase(Path destination) throws IOException {
        checkCancelled();
        List<FileEntry> files = TreeDeltaEngine.inventory(installationRoot);
        TreeDeltaEngine.verify(installationRoot, files, current.treeSha256());
        UpdateFiles.directory(destination);
        byte[] buffer = new byte[64 * 1024];
        for (FileEntry file : files) {
            checkCancelled();
            // Пути уже проверены inventory, проверка ancestors повторяется перед открытием.
            Path source = installationRoot.resolve(file.path());
            Path target = destination.resolve(file.path());
            UpdateFiles.check(source);
            UpdateFiles.directory(target.getParent());
            UpdateFiles.check(target);
            long count = 0;
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                for (int bytes; (bytes = input.read(buffer)) != -1;) {
                    checkCancelled();
                    count += bytes;
                    if (count > file.sizeBytes()) throw new IOException("DELTA_BASE_CHANGED");
                    ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, bytes);
                    while (chunk.hasRemaining()) output.write(chunk);
                }
                if (count != file.sizeBytes()) throw new IOException("DELTA_BASE_CHANGED");
                output.force(true);
            }
            DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dos != null) dos.setReadOnly(file.readOnly());
            else {
                var permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
                if (file.readOnly()) permissions.removeAll(Set.of(PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
                else permissions.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(target, permissions);
            }
        }
        checkCancelled();
        TreeDeltaEngine.verify(destination, files, current.treeSha256());
        TreeDeltaEngine.verify(installationRoot, files, current.treeSha256());
        return files;
    }

    static void validateAssets(UpdateManifest manifest) throws IOException {
        if (!"CashPrediction-portable.zip".equals(manifest.assetName())) throw new IOException("FULL_ASSET_NAME");
        if (manifest.sizeBytes() <= 0 || manifest.sizeBytes() > CONTAINER_LIMIT
                || !hex(manifest.sha256(), 64)) throw new IOException("FULL_CONTAINER_IDENTITY");
        if (manifest.deltaPatches().size() > 2) throw new IOException("TOO_MANY_DELTAS");
        for (DeltaPatch delta : manifest.deltaPatches()) {
            String numbered = "CashPrediction.from-" + delta.baseReleaseNumber() + ".cpdelta";
            if (!"CashPrediction.cpdelta".equals(delta.assetName()) && !numbered.equals(delta.assetName())) {
                throw new IOException("DELTA_ASSET_NAME");
            }
            if (delta.sizeBytes() <= 0 || delta.sizeBytes() > CONTAINER_LIMIT || !hex(delta.sha256(), 64)) {
                throw new IOException("DELTA_CONTAINER_IDENTITY");
            }
        }
    }

    private URI asset(String name) {
        return test ? manifestUri.resolve(name) : PRODUCTION.resolve(name);
    }

    private void download(URI uri, Path destination, long size, String sha) throws IOException {
        UpdateFiles.delete(destination);
        request(uri, destination, CONTAINER_LIMIT, size, sha);
    }

    private byte[] request(URI uri, Path destination, long limit, long size, String sha) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int redirects = 0; ; redirects++) {
            checkCancelled();
            validateUri(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("HTTP_DEADLINE");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("Cache-Control", "no-cache, no-store").header("User-Agent", "CashPrediction-Updater/1")
                    .GET().build();
            RequestSlot slot = new RequestSlot();
            synchronized (state) {
                checkCancelled();
                activeRequest = slot;
            }
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> {
                BoundedBody body = new BoundedBody(info.statusCode() == 200 ? destination : null,
                        limit, size, sha, info.statusCode() != 200,
                        info.headers().firstValueAsLong("Content-Length").orElse(-1));
                synchronized (state) {
                    slot.body = body;
                    if (closed || slot.cancelled) body.cancel();
                }
                return body;
            });
            synchronized (state) {
                slot.future = future;
                if (closed || slot.cancelled) future.cancel(true);
            }
            HttpResponse<byte[]> response;
            try {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                response = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("CANCELLED", ex);
            } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException ex) {
                throw new IOException("HTTP_FAILED", ex);
            } finally {
                synchronized (state) {
                    slot.cancel();
                    if (activeRequest == slot) activeRequest = null;
                }
            }
            int status = response.statusCode();
            if (status == 200) return response.body();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                throw new IOException("HTTP_STATUS_" + status);
            }
            if (redirects >= 5) throw new IOException("REDIRECT_LIMIT");
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("REDIRECT_LOCATION"));
            try { uri = uri.resolve(location); }
            catch (IllegalArgumentException ex) { throw new IOException("REDIRECT_URI", ex); }
        }
    }

    private void validateUri(URI uri) throws IOException {
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !(test ? sameLoopback(uri) : "https".equals(uri.getScheme())
                    && HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    && (uri.getPort() == -1 || uri.getPort() == 443))) {
            throw new IOException("BLOCKED_HTTP_URI");
        }
    }

    private boolean sameLoopback(URI uri) {
        return loopback(uri) && uri.getHost().equals(manifestUri.getHost()) && uri.getPort() == manifestUri.getPort();
    }

    private static boolean loopback(URI uri) {
        return uri.getHost() != null && "http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "[::1]", "::1").contains(uri.getHost())
                && uri.getUserInfo() == null && uri.getFragment() == null;
    }

    private void checkCancelled() throws IOException {
        synchronized (state) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
        }
    }

    private void pause(Duration duration) throws IOException {
        synchronized (state) {
            checkCancelled();
            long until = System.nanoTime() + duration.toNanos();
            while (until - System.nanoTime() > 0) {
                try { TimeUnit.NANOSECONDS.timedWait(state, until - System.nanoTime()); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("CANCELLED", ex);
                }
                checkCancelled();
            }
        }
    }

    /** Отменяет проход и возвращается после очистки загрузки и освобождения блокировки. */
    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (state) {
            closed = true;
            if (activeRequest != null) activeRequest.cancel();
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            state.notifyAll();
            while (worker != null && worker != Thread.currentThread()) {
                try { state.wait(); }
                catch (InterruptedException ex) { interrupted = true; }
            }
        }
        if (ownsClient) client.shutdownNow();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean hex(String value, int length) {
        return value != null && value.length() == length && value.matches("[0-9a-fA-F]+");
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("INVALID_TIMEOUT");
        return value;
    }

    private static Duration nonnegative(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("INVALID_BACKOFF");
        return value;
    }

    private static HttpClient productionClient(URI uri) {
        if (!PRODUCTION.equals(uri)) throw new IllegalArgumentException("FIXED_MANIFEST_URI_REQUIRED");
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** Состояние одного запроса: поздний handler не может открыть файл после отмены. */
    private static final class RequestSlot {
        private CompletableFuture<?> future;
        private BoundedBody body;
        private boolean cancelled;

        void cancel() {
            cancelled = true;
            if (body != null) body.cancel();
            if (future != null) future.cancel(true);
        }
    }

    /** Ограниченный подписчик HTTP: закрытие отменяет subscription, включая медленный body. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
        private final ByteArrayOutputStream memory = new ByteArrayOutputStream();
        private final Path destination;
        private final long limit;
        private final long expected;
        private final String sha;
        private final boolean discard;
        private final long contentLength;
        private final MessageDigest digest;
        private Flow.Subscription subscription;
        private FileChannel output;
        private long count;

        BoundedBody(Path destination, long limit, long expected, String sha, boolean discard, long contentLength) {
            this.destination = destination;
            this.limit = limit;
            this.expected = expected;
            this.sha = sha;
            this.discard = discard;
            this.contentLength = contentLength;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        }

        /** Возвращает завершение ограниченного потока. */
        @Override public CompletionStage<byte[]> getBody() { return completion; }

        /** Подписывается с ограничением demand и проверяет длину до записи. */
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (completion.isDone() || discard) {
                value.cancel();
                if (discard) completion.complete(new byte[0]);
                return;
            }
            try {
                if (contentLength > limit || (expected >= 0 && contentLength >= 0 && contentLength != expected)) {
                    throw new IOException("HTTP_LENGTH");
                }
                if (destination != null) {
                    UpdateFiles.check(destination);
                    output = FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                }
                value.request(1);
            } catch (IOException ex) { fail(ex); }
        }

        /** Пишет порцию в файл, не удерживая контейнер в памяти. */
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (completion.isDone()) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    int bytes = buffer.remaining();
                    count += bytes;
                    if (count > limit || (expected >= 0 && count > expected)) throw new IOException("HTTP_OVERFLOW");
                    digest.update(buffer.asReadOnlyBuffer());
                    if (output != null) {
                        while (buffer.hasRemaining()) output.write(buffer);
                    } else {
                        byte[] copy = new byte[bytes];
                        buffer.get(copy);
                        memory.writeBytes(copy);
                    }
                }
                subscription.request(1);
            } catch (IOException ex) { fail(ex); }
        }

        /** Завершает ошибочный поток и закрывает файл. */
        @Override public synchronized void onError(Throwable error) { fail(error); }

        /** Проверяет идентичность контейнера и принудительно записывает его на диск. */
        @Override public synchronized void onComplete() {
            if (completion.isDone()) return;
            try {
                if (expected >= 0 && count != expected) throw new IOException("HTTP_TRUNCATED");
                if (sha != null && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha)) {
                    throw new IOException("HTTP_HASH");
                }
                if (output != null) { output.force(true); output.close(); output = null; }
                completion.complete(memory.toByteArray());
            } catch (IOException ex) { fail(ex); }
        }

        synchronized void cancel() {
            if (!completion.isDone()) fail(new IOException("CANCELLED"));
        }

        private void fail(Throwable error) {
            if (subscription != null) subscription.cancel();
            if (output != null) {
                try { output.close(); } catch (IOException ex) { error.addSuppressed(ex); }
                output = null;
            }
            completion.completeExceptionally(error);
        }
    }
}
