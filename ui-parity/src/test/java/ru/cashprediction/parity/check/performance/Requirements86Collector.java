package ru.cashprediction.parity.check.performance;

import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.List;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import ru.cashprediction.core.ui.selftest.UiDriver;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;

/**
 * Собирает собственные timestamps вокруг OS-ввода и наблюдений test API.
 * Время завершения экранного захвата является консервативной верхней границей,
 * не выдуманной меткой первого кадра. Импорт Sample/JSON timings отсутствует.
 * Механический транспорт никогда не получает production scope.
 */
public final class Requirements86Collector implements AutoCloseable {
    private final Contract contract;
    private final Instant started = Instant.now();
    private final String clock = UUID.randomUUID().toString();
    private final EnumMap<Client, Transport> transports = new EnumMap<>(Client.class);
    private final EnumMap<Client, Launch> launches = new EnumMap<>(Client.class);
    private final EnumMap<Client, List<Sample>> samples = new EnumMap<>(Client.class);
    private final List<Map<String, Object>> frames = new ArrayList<>(), inputs = new ArrayList<>(), ux = new ArrayList<>();
    private boolean closed, failed;
    private final Set<Client> released = EnumSet.noneOf(Client.class);

    /** Фактические байты/строки/поколение; временные метки не принимает даже транспорт. */
    public record Observation(Client client, long revision, String fixtureDigest, String input,
                              List<Row> rows, byte[] screenPng, String rawDump) {
        /** Замораживает исходные наблюдения. */
        public Observation { rows = List.copyOf(rows); screenPng = screenPng.clone(); }
        /** Не отдаёт изменяемый оригинал. */
        @Override public byte[] screenPng() { return screenPng.clone(); }
    }

    /** Закрываемый порт событий и наблюдений; настоящий production тип фиксирован ниже. */
    public interface Transport extends AutoCloseable {
        /** Снимает полный фактический манифест используемых launch jar. */
        Map<String, String> jars() throws Exception;
        /** Посылает ровно один заранее описанный OS-жест, не команду модели. */
        void gesture(int[] keys) throws Exception;
        /** Читает actual widgets и экран без ожидания selftest settle. */
        Observation observe(String step) throws Exception;
    }

    /** Промежуточный unstable/старый кадр: повторяется только чтение, никогда OS-жест. */
    public static final class PendingFrame extends Exception {
        /** Сохраняет причину отсутствия корректного presented acknowledgement. */
        public PendingFrame(String reason) { super(reason); }
    }

    /** Фиксирует независимый контракт до присоединения клиентов. */
    public Requirements86Collector(Contract contract) { this.contract = Objects.requireNonNull(contract); }

    /**
     * Снимает launch boundary перед supplied launch factory; при отказе закрывает полученный ресурс.
     * MAIN передаёт factory, запускающий обычный клиент, а не уже живой чужой процесс.
     */
    public synchronized void launch(Client client, java.util.concurrent.Callable<? extends Transport> factory) throws Exception {
        live();
        if (transports.containsKey(client)) throw new IllegalStateException("duplicate-client");
        Stamp origin = stamp();
        Transport transport;
        try { transport = Objects.requireNonNull(factory.call(), "missing-transport"); }
        catch (Throwable error) { failed = true; throw error; }
        try {
            if (!contract.jars().get(client).equals(transport.jars())) throw new AssertionError("foreign-sha");
            transports.put(client, transport); samples.put(client, new ArrayList<>());
            launches.put(client, new Launch(contract.runId(), client, origin));
        } catch (Throwable error) {
            failed = true;
            try { transport.close(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /**
     * Измеряет полный интервал до согласованного наблюдения. Не принимает готовые timings.
     * keys - физическая клавиша/модификаторы одного жеста; подготовка фокуса/очистка вне опыта.
     * Любая ошибка делает сессию непригодной: повтором нельзя удалить медленный/ошибочный sample.
     */
    public synchronized Sample collect(Client client, int[] keys) throws Exception {
        live();
        try {
            if (released.contains(client)) throw new IllegalStateException("released-client");
            Transport transport = Objects.requireNonNull(transports.get(client), "missing-client");
            var list = samples.get(client);
            Expected expected = contract.events().get(client).get(list.size());
            if (keys.length == 0) throw new AssertionError("missing-physical-gesture");
            Map<String, String> jars = transport.jars();
            if (!contract.jars().get(client).equals(jars)) throw new AssertionError("foreign-sha");
            if (transport instanceof NativeDesktop) {
                try { requireUncompletedInput(transport.observe("before-" + expected.correlation()), expected); }
                catch (PendingFrame pending) { /* Незавершённый OPEN допустим; согласованного старого результата нет. */ }
            }
            Stamp requested = stamp();
            transport.gesture(keys.clone());
            Observation observed;
            // Опрашиваем без settle и без повторения жеста; ранний старый кадр не считается paint.
            long deadline = requested.nanos() + (expected.operation() == Operation.OPEN ? 2_000_000_000L : 100_000_000L);
            do {
                if (System.nanoTime() - deadline >= 0) throw new AssertionError("observation-timeout");
                try { observed = Objects.requireNonNull(transport.observe(expected.correlation()), "missing-observation"); }
                catch (PendingFrame pending) { Thread.onSpinWait(); continue; }
                if (observed.client() != client) throw new AssertionError("foreign-or-incorrect-observation");
                if (observed.fixtureDigest().equals(expected.fixtureDigest()) && observed.revision() == expected.revision() && observed.rows().equals(expected.rows())
                        && observed.input().equals(expected.input())) break;
                if (System.nanoTime() - deadline >= 0) throw new AssertionError("observation-timeout");
                Thread.onSpinWait();
            } while (true);
            Stamp presented = stamp();
            if (observed.client() != client || observed.revision() != expected.revision()
                    || !observed.rows().equals(expected.rows())) throw new AssertionError("foreign-or-incorrect-observation");
            if (observed.rawDump() == null || observed.rawDump().isBlank()
                    || ImageIO.read(new java.io.ByteArrayInputStream(observed.screenPng())) == null)
                throw new AssertionError("missing-presented-frame");
            if (!jars.equals(transport.jars())) throw new AssertionError("changed-sha");
            Paint paint = new Paint(contract.runId(), client, expected.sequence(), expected.correlation(),
                    observed.revision(), observed.fixtureDigest(), observed.rows(), presented);
            Sample sample = new Sample(contract.runId(), client, expected.sequence(), expected.correlation(),
                    expected.operation(), expected.input(), requested, Instant.now(), jars, Outcome.PAINTED, paint);
            list.add(sample);
            inputs.add(Map.of("client", client.name(), "sequence", expected.sequence(), "keys", Arrays.stream(keys).boxed().toList(),
                    "requestedAt", Map.of("clockId", clock, "nanos", requested.nanos())));
            frames.add(Map.of("client", client.name(), "sequence", expected.sequence(), "pngBase64", Base64.getEncoder().encodeToString(observed.screenPng()),
                    "pngSha256", sha(observed.screenPng()), "rawDump", observed.rawDump(), "presentedAtUpperBoundNanos", presented.nanos(),
                    "revision", observed.revision(), "fixtureDigest", observed.fixtureDigest(), "input", observed.input()));
            return sample;
        } catch (Throwable error) { failed = true; throw error; }
    }

    /** Сохраняет UX observations со снимком и дампом, а не самостоятельно выставленный ACCEPTED. */
    public synchronized void observeUx(Client client, String step, String reviewerNotes) throws Exception {
        live();
        if (!Set.of("savings-create", "goal-decision", "funding-gap", "compare-scenarios", "save-reopen").contains(step)
                || reviewerNotes == null || reviewerNotes.isBlank()) throw new IllegalArgumentException("ux-step-notes");
        if (ux.stream().anyMatch(cell -> cell.get("client").equals(client.name()) && cell.get("step").equals(step)))
            throw new IllegalStateException("duplicate-ux");
        Observation observed = transports.get(client).observe("ux-" + step);
        if (observed == null || observed.client() != client || observed.rawDump() == null || observed.rawDump().isBlank()
                || ImageIO.read(new java.io.ByteArrayInputStream(observed.screenPng())) == null) {
            failed = true; throw new AssertionError("missing-ux-frame");
        }
        ux.add(Map.of("client", client.name(), "step", step, "status", "OBSERVED_NOT_REVIEWED", "notes", reviewerNotes,
                "pngBase64", Base64.getEncoder().encodeToString(observed.screenPng()), "rawDump", observed.rawDump()));
    }

    /** Возвращает полный immutable run; budgets и полноту проверяет исключительно existing gate. */
    public synchronized Run finish() {
        live();
        Run run = new Run(contract.runId(), started, Instant.now(), launches, samples);
        try { DomainLatencyGate.verify(contract, run, Instant.now()); return run; }
        catch (Throwable error) { failed = true; throw error; }
    }

    /** Закрывает законченный клиент до следующего desktop slot, сохраняя его samples и scope. */
    public synchronized void release(Client client) throws Exception {
        live();
        if (!transports.containsKey(client)) { failed = true; throw new IllegalStateException("missing-client"); }
        if (released.add(client)) {
            try { transports.get(client).close(); }
            catch (Exception error) { failed = true; throw error; }
        }
    }

    /** Записывает новый собственный evidence без перезаписи и без выдачи независимого approval. */
    public synchronized void write(Path newDirectory) throws Exception {
        Run run = finish();
        boolean mock = transports.values().stream().anyMatch(t -> !(t instanceof NativeDesktop));
        Files.createDirectory(newDirectory);
        writeJson(newDirectory.resolve("contract.json"), Requirements86Acceptance.encode(contract));
        writeJson(newDirectory.resolve("run.json"), Requirements86Acceptance.encode(run));
        writeJson(newDirectory.resolve("input-transcript.json"), inputs);
        writeJson(newDirectory.resolve("presented-frames.json"), frames);
        writeJson(newDirectory.resolve("ux-observations.json"), ux);
        writeJson(newDirectory.resolve("collector.json"), Map.of("runId", contract.runId(), "mock", mock,
                "scope", mock ? "HEADLESS_MOCK_MECHANICS" : "PRODUCTION_OS_ROBOT_SCREEN_UPPER_BOUND",
                "timingBoundary", "OS_SEND_TO_SCREEN_BRACKET_COMPLETE_UPPER_BOUND",
                "independentlyReviewed", false, "status", "COLLECTED_NOT_ACCEPTED"));
    }

    /** Сохраняет partial/error evidence отдельно; FAILED_DIAGNOSTIC никогда не принимается consumer. */
    public synchronized void writeDiagnostic(Path newDirectory, Throwable cause) throws Exception {
        if (closed) throw new IllegalStateException("collector-closed");
        Files.createDirectory(newDirectory);
        Run partial = new Run(contract.runId(), started, Instant.now(), launches, samples);
        writeJson(newDirectory.resolve("run.json"), Requirements86Acceptance.encode(partial));
        writeJson(newDirectory.resolve("input-transcript.json"), inputs);
        writeJson(newDirectory.resolve("presented-frames.json"), frames);
        writeJson(newDirectory.resolve("collector.json"), Map.of("runId", contract.runId(), "status", "FAILED_DIAGNOSTIC",
                "cause", Objects.requireNonNull(cause).toString(), "independentlyReviewed", false));
    }

    /** Проверяет production bytes против Run перед existing gate; approval остаётся внешним. */
    public static void verifyEvidence(Path root, Run run) throws Exception {
        verifyTransportIntegrity(root, run, true);
        var cells = jsonList(root.resolve("ux-observations.json")); var unique = new HashSet<String>();
        for (Object value : cells) {
            var cell = object(value);
            if (!unique.add(cell.get("client") + "/" + cell.get("step")) || !"OBSERVED_NOT_REVIEWED".equals(cell.get("status"))
                    || !(cell.get("notes") instanceof String notes) || notes.isBlank()
                    || !(cell.get("rawDump") instanceof String dump) || dump.isBlank()
                    || ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode((String) cell.get("pngBase64")))) == null)
                throw new AssertionError("invalid-ux-observation");
        }
        var expected = new HashSet<String>();
        for (Client client : Client.values()) for (String step : List.of("savings-create", "goal-decision", "funding-gap", "compare-scenarios", "save-reopen"))
            expected.add(client + "/" + step);
        if (!unique.equals(expected)) throw new AssertionError("missing-ux-observations");
    }

    /** Механические tests могут читать mock bytes, production consumer не может повышать их scope. */
    static void verifyTransportIntegrity(Path root, Run run, boolean requireNative) throws Exception {
        var receipt = object(ru.cashprediction.core.json.JsonParser.parse(Files.readString(root.resolve("collector.json"))));
        if (!run.runId().equals(receipt.get("runId")) || !"OS_SEND_TO_SCREEN_BRACKET_COMPLETE_UPPER_BOUND".equals(receipt.get("timingBoundary")))
            throw new AssertionError("foreign-collector-run");
        if (!"COLLECTED_NOT_ACCEPTED".equals(receipt.get("status"))) throw new AssertionError("failed-collector-run");
        if (requireNative && (!Boolean.FALSE.equals(receipt.get("mock"))
                || !"PRODUCTION_OS_ROBOT_SCREEN_UPPER_BOUND".equals(receipt.get("scope")))) throw new AssertionError("mock-not-native");
        var input = jsonList(root.resolve("input-transcript.json")); var frames = jsonList(root.resolve("presented-frames.json"));
        int count = run.samples().values().stream().mapToInt(List::size).sum();
        if (input.size() != count || frames.size() != count) throw new AssertionError("missing-timings");
        var seen = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            var in = object(input.get(i)); var frame = object(frames.get(i));
            String clientName = (String) in.get("client"); long sequence = number(in.get("sequence"));
            if (!seen.add(clientName + "/" + sequence)) throw new AssertionError("duplicate-timing");
            Sample sample = run.samples().get(Client.valueOf(clientName)).stream().filter(s -> s.sequence() == sequence).findFirst().orElseThrow();
            Stamp request = Requirements86Acceptance.decode(ru.cashprediction.core.json.JsonWriter.write(in.get("requestedAt")), Stamp.class);
            if (!sample.requestedAt().equals(request) || !clientName.equals(frame.get("client")) || number(frame.get("sequence")) != sequence
                    || number(frame.get("presentedAtUpperBoundNanos")) != sample.paint().paintedAt().nanos()
                    || number(frame.get("revision")) != sample.paint().revision()
                    || !sample.paint().fixtureDigest().equals(frame.get("fixtureDigest")) || !sample.input().equals(frame.get("input")))
                throw new AssertionError("invented-or-foreign-timing");
            byte[] png = Base64.getDecoder().decode((String) frame.get("pngBase64"));
            if (!sha(png).equals(frame.get("pngSha256")) || ImageIO.read(new java.io.ByteArrayInputStream(png)) == null
                    || !(frame.get("rawDump") instanceof String dump) || dump.isBlank()
                    || !(in.get("keys") instanceof List<?> keys) || keys.isEmpty()) throw new AssertionError("missing-presented-frame");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static List<?> jsonList(Path path) throws Exception { return (List<?>) ru.cashprediction.core.json.JsonParser.parse(Files.readString(path)); }
    private static long number(Object value) { return new java.math.BigDecimal(Objects.requireNonNull(value, "missing-timing").toString()).longValueExact(); }

    /** Закрывает все собственные порты даже после ошибки; ошибки cleanup не скрываются. */
    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception failure = null;
        var resources = new ArrayList<Transport>();
        transports.forEach((client, transport) -> { if (!released.contains(client)) resources.add(transport); });
        Collections.reverse(resources);
        for (Transport resource : resources) try { resource.close(); }
        catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        transports.clear();
        if (failure != null) throw failure;
    }

    private Stamp stamp() { return new Stamp(clock, System.nanoTime()); }

    /** Старый уже правильный кадр до OS-ввода не может стать новым быстрым paint acknowledgement. */
    static void requireUncompletedInput(Observation before, Expected expected) {
        if (before == null) throw new AssertionError("missing-before-input");
        if (before.revision() == expected.revision() && before.fixtureDigest().equals(expected.fixtureDigest())
                && before.input().equals(expected.input()) && before.rows().equals(expected.rows()))
            throw new AssertionError("already-completed-before-input");
    }
    private void live() { if (closed || failed) throw new IllegalStateException("closed-or-failed-collector"); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void writeJson(Path file, Object value) throws Exception {
        Files.writeString(file, ru.cashprediction.core.json.JsonWriter.write(value), StandardOpenOption.CREATE_NEW);
    }

    /**
     * Настоящий OS Robot и обычный UiDriver test API без execute/awaitIdle/selftest.
     * revisionReader читает фактическую revision отрисованной таблицы в UI-потоке либо CDP,
     * не берёт её из Expected. ownedLifecycle закрывает собственные process tree/CDP/registry.
     * Rectangle должен быть независимо проверенным видимым client viewport без перекрытий.
     */
    public static final class NativeDesktop implements Transport {
        private final Robot robot;
        private final UiDriver driver;
        private final LongSupplier revisionReader;
        private final Rectangle viewport;
        private final Map<String, Path> jarPaths;
        private final AutoCloseable ownedLifecycle;
        private final Supplier<Path> loadedPlan;
        private final Supplier<String> filterReader;
        private boolean closed;

        /** Создаёт native порт только при наличии настоящего рабочего стола. */
        public NativeDesktop(UiDriver driver, LongSupplier revisionReader, Rectangle viewport,
                             Map<String, Path> launchJarPaths, Supplier<Path> loadedPlan,
                             Supplier<String> filterReader, AutoCloseable ownedLifecycle) throws Exception {
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("native-desktop-required");
            this.driver = Objects.requireNonNull(driver); this.revisionReader = Objects.requireNonNull(revisionReader);
            String driverType = switch (driver.client()) {
                case FX -> "ru.cashprediction.fx.ui.FxUiDriver";
                case SWING -> "ru.cashprediction.swing.ui.SwingUiDriver";
                case WEB -> "ru.cashprediction.parity.driver.UiTestDriver";
            };
            if (!driver.getClass().getName().equals(driverType)) throw new IllegalArgumentException("foreign-native-driver");
            if (driver.client() == ru.cashprediction.core.app.ClientKind.WEB) {
                var apiField = driver.getClass().getDeclaredField("api"); apiField.setAccessible(true);
                if (!apiField.get(driver).getClass().getName().equals("ru.cashprediction.parity.driver.CdpTestApi"))
                    throw new IllegalArgumentException("foreign-native-web-api");
            }
            this.viewport = new Rectangle(viewport); this.jarPaths = Map.copyOf(launchJarPaths);
            this.ownedLifecycle = Objects.requireNonNull(ownedLifecycle);
            this.loadedPlan = Objects.requireNonNull(loadedPlan); this.filterReader = Objects.requireNonNull(filterReader);
            if (viewport.width <= 0 || viewport.height <= 0 || jarPaths.isEmpty()) throw new IllegalArgumentException("native-inputs");
            for (Class<?> type : driver.client() == ru.cashprediction.core.app.ClientKind.WEB
                    ? List.of(ru.cashprediction.core.app.AppController.class) : List.of(driver.getClass(), ru.cashprediction.core.app.AppController.class)) {
                Path loaded = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                if (!Files.isRegularFile(loaded) || jarPaths.values().stream().noneMatch(path -> path.toAbsolutePath().normalize().equals(loaded)))
                    throw new IllegalArgumentException("unbound-loaded-client-jar");
            }
            robot = new Robot(); robot.setAutoDelay(0); robot.setAutoWaitForIdle(false);
        }

        /** Снимает SHA launch jars; передавать paths из frozen фактического launch manifest. */
        @Override public Map<String, String> jars() throws Exception {
            open(); var result = new TreeMap<String, String>();
            for (var entry : jarPaths.entrySet()) {
                if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("jar-not-ordinary");
                result.put(entry.getKey(), sha(Files.readAllBytes(entry.getValue())));
            }
            return Map.copyOf(result);
        }

        /** Отправляет physical keyboard chord; освобождает все уже нажатые клавиши при сбое. */
        @Override public void gesture(int[] keys) {
            open(); int pressed = 0;
            try { for (int key : keys) { robot.keyPress(key); pressed++; } }
            finally { while (pressed > 0) robot.keyRelease(keys[--pressed]); }
        }

        /** Согласует две actual widget reads с двумя экранными кадрами; snapshot модели не годится. */
        @Override public Observation observe(String step) throws Exception {
            open();
            long revision = revisionReader.getAsLong();
            UiDump first = driver.dump(step);
            BufferedImage screen = robot.createScreenCapture(viewport);
            UiDump second = driver.dump(step);
            BufferedImage confirmation = robot.createScreenCapture(viewport);
            if (revision != revisionReader.getAsLong() || !first.equals(second)) throw new PendingFrame("unstable-widget-bracket");
            if (!Arrays.equals(screen.getRGB(0, 0, screen.getWidth(), screen.getHeight(), null, 0, screen.getWidth()),
                    confirmation.getRGB(0, 0, confirmation.getWidth(), confirmation.getHeight(), null, 0, confirmation.getWidth())))
                throw new PendingFrame("unstable-presented-frame");
            String client = driver.client().name().toLowerCase(Locale.ROOT);
            if (!first.client().equals(client) || first.schema() != UiDump.SCHEMA || !first.step().equals(step)
                    || first.table() == null || !first.screens().isEmpty()) throw new AssertionError("foreign-widget-api");
            var bytes = new ByteArrayOutputStream(); ImageIO.write(confirmation, "png", bytes);
            Path plan = loadedPlan.get();
            if (plan == null) throw new PendingFrame("not-yet-loaded-plan");
            if (!Files.isRegularFile(plan, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("foreign-loaded-plan");
            return new Observation(Client.valueOf(driver.client().name()), revision, sha(Files.readAllBytes(plan)), filterReader.get(),
                    first.table().rows().stream().map(row -> new Row(row.rowId(), row.cells())).toList(), bytes.toByteArray(), UiJson.write(first));
        }

        /** Закрывает только переданную owned lifecycle; не трогает чужие процессы/registry. */
        @Override public void close() throws Exception { if (!closed) { closed = true; ownedLifecycle.close(); } }
        private void open() { if (closed) throw new IllegalStateException("native-port-closed"); }
    }
}
