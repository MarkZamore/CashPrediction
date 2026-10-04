package ru.cashprediction.parity.update;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Локальный HTTP-источник с ограниченным ожиданием и журналом реальных запросов. */
public final class LocalUpdateServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, Path> files = new ConcurrentHashMap<>();
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** Ответ фикстуры; gate позволяет остановить тело загрузки до отмены клиента. */
    public record Reply(int status, byte[] body, CountDownLatch gate) {
        /** Защищает исходные байты от последующего изменения вызывающим кодом. */
        public Reply { body = body.clone(); }
        /** Возвращает независимую копию тела. */
        @Override public byte[] body() { return body.clone(); }
    }

    /** Привязывает сервер только к IPv4 loopback и порту, выбранному ОС. */
    public LocalUpdateServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Reply reply = replies.getOrDefault(path, new Reply(404, new byte[0], null));
            Path file = files.get(path);
            trace.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + (file == null ? reply.status() : 200));
            try (exchange) {
                if (file != null) {
                    FixtureAuthority.noLinks(file);
                    if (Files.size(file) > 512L * 1024 * 1024) throw new IOException("FIXTURE_CONTAINER_LIMIT");
                    exchange.sendResponseHeaders(200, Files.size(file));
                    Files.copy(file, exchange.getResponseBody());
                    return;
                }
                byte[] body = reply.body();
                exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
                if (body.length != 0) {
                    if (reply.gate() == null) exchange.getResponseBody().write(body);
                    else {
                        exchange.getResponseBody().write(body, 0, 1); exchange.getResponseBody().flush();
                        if (reply.gate().await(5, TimeUnit.SECONDS)) exchange.getResponseBody().write(body, 1, body.length - 1);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    /** Назначает ответ единственному абсолютному пути без query и fragment. */
    public void put(String path, Reply reply) {
        if (!path.matches("/[A-Za-z0-9._-]+")) throw new IllegalArgumentException("FIXTURE_PATH");
        if (reply.body().length > 8 * 1024 * 1024) throw new IllegalArgumentException("FIXTURE_BODY_LIMIT");
        files.remove(path);
        replies.put(path, reply);
    }

    /** Передаёт большой контейнер потоком, не удерживая runtime в памяти сервера. */
    public void putFile(String path, Path file) throws IOException {
        if (!path.matches("/[A-Za-z0-9._-]+")) throw new IllegalArgumentException("FIXTURE_PATH");
        FixtureAuthority.noLinks(file);
        if (!Files.isRegularFile(file) || Files.size(file) > 512L * 1024 * 1024) throw new IOException("FIXTURE_CONTAINER_LIMIT");
        replies.remove(path); files.put(path, file);
    }

    /** Возвращает URI манифеста для явно внедрённого тестового транспорта. */
    public URI manifestUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/update.json"); }

    /** Возвращает неизменяемый журнал, включая query для проверки cache nonce. */
    public List<String> trace() { return List.copyOf(trace); }

    /** Считает только запросы указанного пути, независимо от cache nonce. */
    public long count(String path) {
        return trace.stream().filter(line -> line.startsWith("GET " + path + " ")
                || line.startsWith("GET " + path + "?")).count();
    }

    /** Останавливает listener и все собственные обработчики за ограниченное время. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(6, TimeUnit.SECONDS)) throw new IllegalStateException("SERVER_WORKERS_ALIVE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SERVER_CLOSE_INTERRUPTED", ex);
        }
    }
}
