package ru.cashprediction.parity.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.model.UpdateValidation;

/**
 * JDK HTTP-фикстура для настоящего native runner: только supplied frozen bytes и loopback.
 * Не запускает exe, не генерирует launcher/runtime/helper и не подтверждает GUI-проверки.
 * Счётчик учитывает каждый вход в обработчик, включая повтор клиента после обрыва TCP.
 * bytes означает байты, успешно записанные сервером, а не подтверждение чтения клиентом.
 */
public final class NativeUpdateServer implements AutoCloseable {
    /** Ограниченные неисправности транспорта; offline-close обрывает каждый запрос до заголовков. */
    public enum Mode {
        VALID, CORRUPTDELTA, CORRUPTFULL, MALFORMEDMANIFEST, FIRST_TWO_503,
        OFFLINE_CLOSE, DELAYEDHEADERS, SLOWCHUNKS
    }

    /**
     * artifactDir содержит update.json и все названные в нём контейнеры.
     * ownedTempUuid - существующий пустой UUID-каталог внутри java.io.tmpdir.
     * delayMillis применяется к заголовкам манифеста либо между блоками контейнеров.
     * maxLifetimeMillis ограничивает жизнь отдельного процесса без команды stop.
     */
    public record Config(Path artifactDir, Path ownedTempUuid, Mode mode,
                         int delayMillis, int chunkBytes, int maxLifetimeMillis) {
        /** Проверяет числовые границы до открытия listener или создания файлов. */
        public Config {
            if (artifactDir == null || ownedTempUuid == null || mode == null
                    || delayMillis < 0 || delayMillis > 60_000
                    || chunkBytes < 1 || chunkBytes > 65_536
                    || maxLifetimeMillis < 1_000 || maxLifetimeMillis > 600_000) {
                throw new IllegalArgumentException("SERVER_CONFIG_LIMIT");
            }
        }

        /** Разбирает единственный ASCII аргумент Base64 строгого UTF-8 JSON без платформенной кодировки. */
        public static Config fromBase64(String encoded) throws IOException {
            if (encoded == null || encoded.length() > 65_536
                    || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) throw new IOException("CONFIG_BASE64");
            try {
                var m = UpdateCodec.object(UpdateCodec.parse(utf8(Base64.getDecoder().decode(encoded))));
                UpdateCodec.keys(m, "artifactDir", "ownedTempUuid", "mode", "delayMillis", "chunkBytes", "maxLifetimeMillis");
                Mode mode = switch (UpdateCodec.string(m, "mode")) {
                    case "valid" -> Mode.VALID;
                    case "corruptdelta" -> Mode.CORRUPTDELTA;
                    case "corruptfull" -> Mode.CORRUPTFULL;
                    case "malformedmanifest" -> Mode.MALFORMEDMANIFEST;
                    case "503-firsttwo" -> Mode.FIRST_TWO_503;
                    case "offline-close" -> Mode.OFFLINE_CLOSE;
                    case "delayedheaders" -> Mode.DELAYEDHEADERS;
                    case "slowchunks" -> Mode.SLOWCHUNKS;
                    default -> throw new IOException("CONFIG_MODE");
                };
                return new Config(Path.of(UpdateCodec.string(m, "artifactDir")),
                        Path.of(UpdateCodec.string(m, "ownedTempUuid")), mode,
                        UpdateCodec.integer(m, "delayMillis"), UpdateCodec.integer(m, "chunkBytes"),
                        UpdateCodec.integer(m, "maxLifetimeMillis"));
            } catch (IllegalArgumentException ex) {
                throw new IOException("CONFIG_INVALID", ex);
            }
        }
    }

    /** Завершённый запрос с UTC и монотонными метками относительно запуска сервера. */
    public record Trace(long id, String method, String path, String query,
                        String startedUtc, String finishedUtc, long startedNanos,
                        long finishedNanos, int status, long bytes, String outcome) { }

    /** Проверенный источник; байты больших контейнеров никогда не удерживаются целиком в heap. */
    private record Asset(Path file, long size, String sha256) { }

    private final Config config;
    private final Path owned;
    private final Map<String, Asset> assets;
    private final byte[] manifest;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final Thread controller;
    private final FileChannel journal;
    private final long origin = System.nanoTime();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final List<Trace> traces = new ArrayList<>();
    private final Map<HttpExchange, Boolean> active = new ConcurrentHashMap<>();
    private final Object state = new Object();
    private final Object publication = new Object();
    private final Object throttle = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile IOException failure;
    private volatile boolean cancelled;
    private boolean paused;
    private long requests;
    private long completed;
    private long transferred;

    /**
     * Проверяет манифест, tree digest, отсутствие ссылок/алиасов, размеры и SHA всех контейнеров.
     * Только после проверки занимает пустой ownedTempUuid и слушает 127.0.0.1:0.
     */
    public NativeUpdateServer(Config config) throws IOException {
        this.config = config;
        Path root = safeDirectory(config.artifactDir());
        owned = safeDirectory(config.ownedTempUuid());
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!owned.startsWith(temp) || owned.equals(temp) || owned.startsWith(root) || root.startsWith(owned)
                || !owned.getFileName().toString().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("OWNED_TEMP_UUID_REQUIRED");
        }
        try (var entries = Files.list(owned)) {
            if (entries.findAny().isPresent()) throw new IOException("OWNED_DIRECTORY_NOT_EMPTY");
        }
        Path metadata = root.resolve("update.json");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
                || Files.size(metadata) > UpdateValidation.MAX_JSON) throw new IOException("MANIFEST_LIMIT");
        try (var in = Files.newInputStream(metadata, LinkOption.NOFOLLOW_LINKS)) {
            manifest = in.readNBytes(UpdateValidation.MAX_JSON + 1);
        }
        if (manifest.length > UpdateValidation.MAX_JSON) throw new IOException("MANIFEST_LIMIT");
        UpdateManifest parsed = UpdateCodec.read(utf8(manifest));
        if (!FixtureAuthority.treeHash(parsed.files()).equals(parsed.treeSha256())) throw new IOException("FIXTURE_TREE_DIGEST");
        Map<String, Asset> checked = new LinkedHashMap<>();
        checked.put("/update.json", new Asset(metadata, manifest.length, FixtureAuthority.sha(manifest)));
        addAsset(checked, root, parsed.assetName(), parsed.sizeBytes(), parsed.sha256());
        for (var delta : parsed.deltaPatches()) addAsset(checked, root, delta.assetName(), delta.sizeBytes(), delta.sha256());
        var sources = new ArrayList<>(checked.values());
        for (int i = 0; i < sources.size(); i++) for (int j = 0; j < i; j++) {
            if (Files.isSameFile(sources.get(i).file(), sources.get(j).file())) throw new IOException("FIXTURE_FILE_ALIAS");
        }
        assets = Map.copyOf(checked);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "native-update-http-" + owned.getFileName());
            thread.setDaemon(false); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        journal = FileChannel.open(owned.resolve("server-trace.jsonl"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        HttpServer listener = null;
        try {
            listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            server = listener;
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("schemaVersion", 1); receipt.put("pid", ProcessHandle.current().pid());
            receipt.put("startedUtc", Instant.now().toString()); receipt.put("mode", config.mode().name());
            receipt.put("manifestUri", manifestUri().toASCIIString()); receipt.put("stopPath", stopPath().toString());
            receipt.put("cancelPath", owned.resolve("server.cancel").toString());
            receipt.put("artifactDir", root.toString()); receipt.put("manifestSha256", FixtureAuthority.sha(manifest));
            receipt.put("fixtures", checked.entrySet().stream().map(e -> Map.of("path", e.getKey(),
                    "size", e.getValue().size(), "sha256", e.getValue().sha256())).toList());
            synchronized (state) { stats(false); }
            server.start();
            durable("server-receipt.json", JsonWriter.write(receipt));
        } catch (IOException | RuntimeException ex) {
            if (listener != null) listener.stop(0);
            workers.shutdownNow(); journal.close(); throw ex;
        }
        controller = new Thread(this::control, "native-update-control-" + owned.getFileName());
        controller.start();
    }

    /** Возвращает зафиксированный seam URI без query, fragment и произвольных маршрутов. */
    public URI manifestUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/update.json"); }

    /** Возвращает единственный допустимый файл остановки; содержимое не интерпретируется. */
    public Path stopPath() { return owned.resolve("server.stop"); }

    /** Считает входы обработчика по raw path; query cache nonce не влияет на ключ. */
    public long count(String path) { synchronized (state) { return counters.getOrDefault(path, 0L); } }

    /** Возвращает копию завершённых запросов в порядке их идентификаторов. */
    public List<Trace> trace() {
        synchronized (state) { return traces.stream().sorted(java.util.Comparator.comparingLong(Trace::id)).toList(); }
    }

    /** Возвращает число ещё открытых HTTP exchanges, включая задержанные заголовки. */
    public int activeRequests() { return active.size(); }

    /** Задерживает последующие заголовки и блоки до releaseTransfers, отмены или закрытия. */
    public void pauseTransfers() { synchronized (throttle) { paused = true; } }

    /** Разрешает продолжение передачи без изменения заданного режима неисправности. */
    public void releaseTransfers() { synchronized (throttle) { paused = false; throttle.notifyAll(); } }

    /** Обрывает активные TCP exchanges и делает последующие загрузки отменёнными. */
    public void cancelTransfers() {
        cancelled = true; releaseTransfers();
        active.keySet().forEach(HttpExchange::close);
    }

    /** Сообщает ошибку durable журнала либо остановки фонового контроллера. */
    public void requireHealthy() throws IOException { if (failure != null) throw failure; }

    /** CLI: один Base64 UTF-8 JSON аргумент; stop/cancel берутся только из pinned receipt. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("ONE_BASE64_CONFIG_REQUIRED");
        try (NativeUpdateServer fixture = new NativeUpdateServer(Config.fromBase64(args[0]))) {
            fixture.terminated.await();
            fixture.requireHealthy();
        }
    }

    private void handle(HttpExchange exchange) {
        long id = 0, ordinal = 0, started = 0, bytes = 0;
        String utc = "";
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        int status = 0;
        String outcome = "CLOSED";
        active.put(exchange, Boolean.TRUE);
        try (exchange) {
            synchronized (state) {
                id = ++requests;
                started = System.nanoTime() - origin;
                utc = Instant.now().toString();
                ordinal = counters.merge(path.length() <= 2048 ? path : "<oversize>", 1L, Long::sum);
                try {
                    append(Map.of("event", "START", "id", id, "path", bounded(path), "query", bounded(query),
                            "method", exchange.getRequestMethod(), "utc", utc, "nanos", started));
                    stats(false);
                } catch (IOException ex) { failure = ex; throw ex; }
            }
            if (closing.get() || cancelled || config.mode() == Mode.OFFLINE_CLOSE) return;
            Asset source = assets.get(path);
            boolean metadata = path.equals("/update.json");
            int selected;
            if (path.length() > 2048) selected = 414;
            else if (!exchange.getRequestMethod().equals("GET")) selected = 405;
            else if (source == null || !validQuery(query, metadata)) selected = 404;
            else if (config.mode() == Mode.FIRST_TWO_503 && metadata && ordinal <= 2) selected = 503;
            else selected = 200;
            if (selected != 200) {
                exchange.sendResponseHeaders(selected, -1); status = selected; outcome = "COMPLETE"; return;
            }
            // Frozen источник повторно проверяется до заголовков: изменение не становится fault-фикстурой.
            FixtureAuthority.noLinks(source.file());
            if (Files.size(source.file()) != source.size() || !requestHash(source.file()).equals(source.sha256())) {
                exchange.sendResponseHeaders(409, -1); status = 409; outcome = "FIXTURE_CHANGED"; return;
            }
            delay(metadata && config.mode() == Mode.DELAYEDHEADERS ? config.delayMillis() : 0);
            byte[] malformed = { '{' };
            long length = metadata && config.mode() == Mode.MALFORMEDMANIFEST ? malformed.length : source.size();
            exchange.getResponseHeaders().set("Content-Type", metadata ? "application/json; charset=utf-8" : "application/octet-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, length); status = 200;
            if (metadata) {
                byte[] body = config.mode() == Mode.MALFORMEDMANIFEST ? malformed : manifest;
                exchange.getResponseBody().write(body); bytes = body.length;
            } else {
                boolean corrupt = config.mode() == Mode.CORRUPTFULL && path.endsWith(".zip")
                        || config.mode() == Mode.CORRUPTDELTA && path.endsWith(".cpdelta");
                try (var input = java.nio.channels.Channels.newInputStream(FileChannel.open(source.file(),
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    byte[] buffer = new byte[config.chunkBytes()]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        delay(bytes > 0 && config.mode() == Mode.SLOWCHUNKS ? config.delayMillis() : 0);
                        if (corrupt && bytes == 0) buffer[0] ^= 1;
                        exchange.getResponseBody().write(buffer, 0, n); bytes += n;
                        exchange.getResponseBody().flush();
                    }
                }
            }
            outcome = "COMPLETE";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); outcome = "CANCELLED";
        } catch (IOException ex) {
            outcome = closing.get() || cancelled ? "CANCELLED" : "IO_FAILURE";
        } finally {
            boolean interrupted = Thread.interrupted();
            active.remove(exchange);
            synchronized (state) {
                Trace entry = new Trace(id, exchange.getRequestMethod(), bounded(path), bounded(query), utc,
                        Instant.now().toString(), started, System.nanoTime() - origin, status, bytes,
                        cancelled ? "CANCELLED" : outcome);
                traces.add(entry);
                completed++;
                transferred += bytes;
                try {
                    append(traceObject(entry)); stats(false);
                } catch (IOException ex) { failure = ex; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private String requestHash(Path path) throws IOException {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(65_536);
                long total = 0; int n;
                while ((n = channel.read(buffer)) != -1) {
                    if (cancelled || closing.get()) throw new IOException("TRANSFER_CANCELLED");
                    total += n;
                    if (total > UpdateValidation.MAX_FILE) throw new IOException("FIXTURE_FILE_LIMIT");
                    buffer.flip(); hash.update(buffer); buffer.clear();
                }
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void delay(int millis) throws InterruptedException, IOException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (throttle) {
            while (paused || System.nanoTime() < end) {
                if (cancelled || closing.get()) throw new IOException("TRANSFER_CANCELLED");
                long left = end - System.nanoTime();
                throttle.wait(paused ? 100 : Math.max(1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(left))));
            }
        }
        if (cancelled || closing.get()) throw new IOException("TRANSFER_CANCELLED");
    }

    private void control() {
        try {
            while (!closing.get()) {
                if (signal("server.cancel")) cancelTransfers();
                long total; synchronized (state) { total = requests; }
                if (signal("server.stop") || total >= 4096 || failure != null
                        || System.nanoTime() - origin >= TimeUnit.MILLISECONDS.toNanos(config.maxLifetimeMillis())) {
                    close(); return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            failure = ex;
            try { close(); } catch (IOException closeFailure) {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
        }
    }

    private boolean signal(String name) throws IOException {
        Path file = owned.resolve(name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        FixtureAuthority.noLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 0) throw new IOException("CONTROL_FILE_INVALID");
        return true;
    }

    private static boolean validQuery(String query, boolean metadata) {
        return query == null || metadata && query.matches("cache=[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    private static void addAsset(Map<String, Asset> assets, Path root, String name, long size, String sha) throws IOException {
        UpdateValidation.path(name);
        if (name.contains("/") || assets.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("/" + name))) {
            throw new IOException("FIXTURE_DUPLICATE_PATH");
        }
        Path file = root.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != size
                || !FixtureAuthority.sha(file).equals(sha)) throw new IOException("FIXTURE_CONTAINER_DIGEST");
        assets.put("/" + name, new Asset(file, size, sha));
    }

    private static Path safeDirectory(Path supplied) throws IOException {
        Path absolute = supplied.toAbsolutePath();
        if (!supplied.isAbsolute() || !absolute.equals(absolute.normalize())) throw new IOException("CONFIG_UNSAFE_PATH");
        for (Path ancestor = absolute; ancestor != null; ancestor = ancestor.getParent()) {
            var a = Files.readAttributes(ancestor, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) throw new IOException("CONFIG_TREE_UNSAFE");
        }
        // Ограниченный обход без перехода по ссылкам исключает безграничную фикстуру.
        try (var paths = Files.walk(absolute)) {
            long count = 0, size = 0;
            var it = paths.iterator();
            var names = new java.util.HashSet<String>();
            while (it.hasNext()) {
                Path path = it.next();
                var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (++count > 20_000 || a.isSymbolicLink() || a.isOther()) throw new IOException("CONFIG_TREE_UNSAFE");
                size += a.isRegularFile() ? a.size() : 0;
                if (size > UpdateValidation.MAX_TREE || a.size() > UpdateValidation.MAX_FILE
                        || !names.add(absolute.relativize(path).toString().toUpperCase(Locale.ROOT))) throw new IOException("CONFIG_TREE_LIMIT");
            }
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CONFIG_DIRECTORY_REQUIRED");
        return absolute;
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String bounded(String s) { return s == null ? "" : s.substring(0, Math.min(2048, s.length())); }

    private static Map<String, Object> traceObject(Trace t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "FINISH"); m.put("id", t.id()); m.put("method", t.method()); m.put("path", t.path());
        m.put("query", t.query()); m.put("startedUtc", t.startedUtc()); m.put("finishedUtc", t.finishedUtc());
        m.put("startedNanos", t.startedNanos()); m.put("finishedNanos", t.finishedNanos());
        m.put("status", t.status()); m.put("bytes", t.bytes()); m.put("outcome", t.outcome()); return m;
    }

    private void append(Map<String, Object> value) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(JsonWriter.write(value) + "\n");
        while (bytes.hasRemaining()) journal.write(bytes);
        journal.force(true);
    }

    private void stats(boolean closed) throws IOException {
        durable("server-stats.json", JsonWriter.write(Map.of("schemaVersion", 1, "requests", requests,
                "completed", completed, "bytes", transferred, "counts", new LinkedHashMap<>(counters),
                "active", active.size(), "closed", closed, "cancelled", cancelled,
                "healthy", failure == null)));
    }

    private void durable(String name, String text) throws IOException {
        synchronized (publication) {
            FixtureAuthority.noLinks(owned);
            Path target = owned.resolve(name);
            // Один writer и уникальный staging: старый tmp не блокирует следующую квитанцию.
            Path temporary = owned.resolve(name + "." + java.util.UUID.randomUUID() + ".tmp");
            boolean created = false;
            try {
                try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    created = true;
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                    while (bytes.hasRemaining()) out.write(bytes);
                    out.force(true);
                }
                replaceForcedFile(temporary, target);
            } catch (IOException | RuntimeException ex) {
                if (created) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                }
                throw ex;
            }
        }
    }

    private static void replaceForcedFile(Path temporary, Path target) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException ex) {
                    // ATOMIC_MOVE вправе игнорировать REPLACE_EXISTING; явно заменяем существующий файл.
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.FileSystemException ex) {
                // Windows reader без FILE_SHARE_DELETE временно запрещает rename конкретной квитанции.
                // Повторяем только этот move, не запись/force; постоянный отказ остаётся исходной ошибкой.
                String file = ex.getFile(), other = ex.getOtherFile();
                boolean pinned = (temporary.toString().equals(file) || target.toString().equals(file))
                        && (other == null || temporary.toString().equals(other) || target.toString().equals(other));
                if (!System.getProperty("os.name", "").startsWith("Windows") || !pinned
                        || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || System.nanoTime() >= deadline) throw ex;
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException("PUBLICATION_RETRY_INTERRUPTED", interrupted);
                    failure.addSuppressed(ex); throw failure;
                }
            }
        }
    }

    /** Закрывает listener, клиентские TCP exchanges и собственные потоки; receipts сохраняются. */
    @Override public void close() throws IOException {
        if (closing.compareAndSet(false, true)) {
            try {
                releaseTransfers(); active.keySet().forEach(HttpExchange::close);
                // Мягкий shutdown даёт обработчикам дописать forced журнал без interrupt общего channel.
                server.stop(0); workers.shutdown();
                if (Thread.currentThread() != controller) controller.interrupt();
                if (!workers.awaitTermination(6, TimeUnit.SECONDS)) {
                    workers.shutdownNow(); throw new IOException("SERVER_WORKERS_ALIVE");
                }
                synchronized (state) { stats(true); journal.close(); }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); failure = new IOException("SERVER_CLOSE_INTERRUPTED", ex);
            } catch (IOException ex) { failure = ex; }
            finally {
                try { journal.close(); } catch (IOException ex) { failure = ex; }
                terminated.countDown();
            }
        } else if (Thread.currentThread() == controller) return;
        try {
            if (!terminated.await(7, TimeUnit.SECONDS)) throw new IOException("SERVER_CLOSE_TIMEOUT");
            if (Thread.currentThread() != controller) {
                controller.join(1_000);
                if (controller.isAlive()) throw new IOException("SERVER_CONTROLLER_ALIVE");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IOException("SERVER_CLOSE_INTERRUPTED", ex);
        }
        requireHealthy();
    }
}
