package ru.cashprediction.web.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.json.WebEffect;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;

/** Передаёт шаги настоящему DOM-драйверу; принимает его результаты, не строя дамп из моделей сервера. */
public final class WebSelfTestBridge implements AutoCloseable {
    private static final int MAX_SHOT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SHOT_BASE64 = ((MAX_SHOT_BYTES + 2) / 3) * 4;
    private final EffectLog log;
    private final SelfTestScript script;
    private final Path output;
    private final java.util.function.Supplier<java.time.LocalDate> today;
    private String tab;
    private int index;
    private boolean awaiting;
    private boolean dumpReceived;
    private boolean shotReceived;
    private boolean failed;
    private boolean closed;
    private Thread worker;
    private final StringBuilder journal = new StringBuilder();
    private final java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();

    /** Создаёт мост только для явно включённого тестового API. */
    public WebSelfTestBridge(EffectLog log, SelfTestScript script, Path output,
            java.util.function.Supplier<java.time.LocalDate> today) {
        this.log = log; this.script = script; this.output = output;
        this.today = java.util.Objects.requireNonNull(today, "today");
    }
    /** Начинает сценарий после первого bootstrap, другие вкладки не становятся драйверами. */
    public synchronized void connected(String tab) {
        if (this.tab != null || script == null || closed || failed) return;
        this.tab = tab; next();
    }
    /** Завершение сценария позволяет передать последний ответ перед остановкой тестового процесса. */
    public java.util.concurrent.CompletableFuture<Void> completion() { return completion; }
    /** Возвращает наличие сценария; один test-api без сценария не задерживает остановку. */
    public boolean hasScript() { return script != null; }
    /** Принимает номер текущего шага и дамп либо явный результат. */
    public synchronized void receive(String route, Map<String, Object> body) throws IOException {
        if (closed || !awaiting || failed || Json.requireLong(body, "n") != script.lines().get(index).number()) throw new IllegalArgumentException("n");
        SelfTestScript.Line line = script.lines().get(index);
        if (route.equals("/api/test/shot")) {
            if (shotReceived || !(line.command() instanceof SelfTestCommand.Shot shot)
                    || !body.keySet().equals(java.util.Set.of("n", "png", "dump")) || output == null)
                throw new IllegalArgumentException("shot");
            String encoded = Json.requireString(body, "png");
            if (encoded.length() > MAX_SHOT_BASE64) throw new IllegalArgumentException("png size");
            byte[] png = java.util.Base64.getDecoder().decode(encoded);
            validateShot(png);
            Map<String, Object> source = Json.asObject(body.get("dump"), "dump");
            if (!"web".equals(Json.requireString(source, "client")) || Json.requireLong(source, "schema") != 1)
                throw new IllegalArgumentException("dump identity");
            Map<String, Object> measured = new java.util.LinkedHashMap<>(source);
            measured.put("scenario", script.name()); measured.put("step", shot.step());
            Path directory = child(output, script.name()); Files.createDirectories(directory);
            // Исходные DOM-измерения относятся к тому же Shot; успешный ответ требует обоих файлов.
            Files.writeString(child(directory, shot.step() + ".raw.json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
            Files.write(child(directory, shot.step() + ".png"), png);
            shotReceived = true;
            return;
        }
        if (route.equals("/api/test/dump")) {
            if (dumpReceived || !(line.command() instanceof SelfTestCommand.Dump dump)) throw new IllegalArgumentException("dump");
            Map<String, Object> source = Json.asObject(body.get("dump"), "dump");
            if (!"web".equals(Json.requireString(source, "client")) || Json.requireLong(source, "schema") != 1)
                throw new IllegalArgumentException("dump identity");
            Map<String, Object> tree = new java.util.LinkedHashMap<>(source);
            // Только метки сценария принадлежат раннеру; содержимое виджетов приходит исключительно из DOM.
            tree.put("scenario", script.name()); tree.put("step", dump.step());
            if (output != null) {
                Path directory = child(output, script.name()); Files.createDirectories(directory);
                // Имя шага приходит из проверенного сценария, но дополнительно ограничено одним компонентом пути.
                Path target = child(directory, dump.step() + ".json");
                Files.writeString(target, JsonWriter.write(tree), StandardCharsets.UTF_8);
            }
            dumpReceived = true;
            return;
        }
        if (!route.equals("/api/test/result")) throw new IllegalArgumentException("route");
        if (!(body.get("ok") instanceof Boolean ok)) throw new IllegalArgumentException("ok");
        if (ok && line.command() instanceof SelfTestCommand.Dump && !dumpReceived) throw new IllegalArgumentException("dump missing");
        if (ok && line.command() instanceof SelfTestCommand.Shot && !shotReceived) throw new IllegalArgumentException("shot missing");
        if (!ok) {
            record(false, Json.string(body, "message", ""));
        } else {
            record(true, "");
        }
        awaiting = false; index++; next();
    }
    private synchronized void next() {
        if (closed) return;
        if (index >= script.lines().size()) {
            journal.append("SELFTEST DONE\n");
            try { writeJournal(); System.out.println("SELFTEST DONE"); completion.complete(null); }
            catch (IOException error) { completion.completeExceptionally(error); }
            return;
        }
        SelfTestCommand command = script.lines().get(index).command();
        if (command instanceof SelfTestCommand.Today expected) {
            java.time.LocalDate actual = today.get();
            if (!expected.date().equals(actual)) {
                IllegalStateException error = new IllegalStateException("today expected " + expected.date() + " but was " + actual);
                failed = true;
                try { record(false, error.getMessage()); } catch (IOException writeError) { error.addSuppressed(writeError); }
                completion.completeExceptionally(error);
                throw error;
            }
            try { record(true, ""); }
            catch (IOException error) { failed = true; completion.completeExceptionally(error); throw new IllegalStateException(error); }
            index++; next(); return;
        }
        if (command instanceof SelfTestCommand.Wait || command instanceof SelfTestCommand.Signal) {
            worker = new Thread(() -> {
                try {
                    if (command instanceof SelfTestCommand.Wait wait) Thread.sleep(wait.millis());
                    else if (command instanceof SelfTestCommand.Signal signal) {
                        if (output == null) throw new IOException("selftest-out");
                        Path path = child(output, signal.path()); Files.createDirectories(path.getParent()); Files.createFile(path);
                        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                        while (Files.exists(path)) {
                            if (System.nanoTime() >= deadline) throw new IOException("signal timeout");
                            Thread.sleep(20);
                        }
                    }
                    advanceInternal(true, "");
                } catch (Exception error) { advanceInternal(false, error.toString()); }
            }, "cashprediction-web-selftest");
            worker.setDaemon(true); worker.start(); return;
        }
        awaiting = true; dumpReceived = false; shotReceived = false;
        log.append(new WebEffect.TestStep(script.lines().get(index).number(), script.lines().get(index).text()));
    }
    private synchronized void advanceInternal(boolean ok, String message) {
        if (closed) return;
        try { record(ok, message); index++; next(); }
        catch (IOException error) { failed = true; completion.completeExceptionally(error); }
    }
    private void record(boolean ok, String message) throws IOException {
        var line = script.lines().get(index);
        String value = "SELFTEST " + line.number() + (ok ? " OK " : " FAIL ") + line.text()
                + (ok ? "" : ": " + message.replace('\n', ' ').replace('\r', ' '));
        journal.append(value).append('\n'); writeJournal(); System.out.println(value);
    }
    private void writeJournal() throws IOException {
        if (output != null) { Files.createDirectories(output); Files.writeString(output.resolve("selftest.log"), journal, StandardCharsets.UTF_8); }
    }
    /** Проверяет ограниченный PNG до выделения буфера пикселей, не создавая временных файлов ImageIO. */
    private static void validateShot(byte[] png) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (png.length > MAX_SHOT_BYTES || png.length < signature.length
                || !java.util.Arrays.equals(signature, java.util.Arrays.copyOf(png, signature.length)))
            throw new IllegalArgumentException("png signature or size");
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(png))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("png reader");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase("PNG") || reader.getWidth(0) != 1200 || reader.getHeight(0) != 800)
                    throw new IllegalArgumentException("png dimensions");
                var image = reader.read(0);
                if (image == null || image.getWidth() != 1200 || image.getHeight() != 800)
                    throw new IllegalArgumentException("png content");
            } finally { reader.dispose(); }
        } catch (IOException error) { throw new IllegalArgumentException("png content", error); }
    }
    private static Path child(Path parent, String name) {
        Path relative = Path.of(name), value = parent.resolve(relative).normalize();
        if (relative.isAbsolute() || relative.getNameCount() != 1 || !parent.equals(value.getParent())) throw new IllegalArgumentException("output path");
        return value;
    }
    /** Прерывает собственный поток ожидания при завершении сервера. */
    @Override public synchronized void close() {
        closed = true; awaiting = false;
        if (worker != null) worker.interrupt();
        if (!completion.isDone()) completion.completeExceptionally(new IllegalStateException("selftest stopped"));
    }
}
