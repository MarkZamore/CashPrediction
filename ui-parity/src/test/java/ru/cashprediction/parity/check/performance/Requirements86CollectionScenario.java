package ru.cashprediction.parity.check.performance;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import ru.cashprediction.core.json.*;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;

/**
 * Единственный последовательный сбор согласованного опыта. MAIN предоставляет owned session factory
 * существующего test API, а не JSON timings. Неполный клиент/ключ/SHA останавливает весь опыт.
 * CLI не запускается в contract tests и никогда сам не утверждает native acceptance.
 */
public final class Requirements86CollectionScenario {
    private Requirements86CollectionScenario() { }

    /** Шов существующего launch/test API; реализация владеет своими процессами, CDP и selftest узлом. */
    public interface Sessions extends AutoCloseable {
        /** Запускает обычный клиент с новым home; не должен возвращать чужой уже живой процесс. */
        Session open(Client client, Path home) throws Exception;
        /** Закрывает также частично созданный ресурс, если open выбросил исключение. */
        @Override void close() throws Exception;
    }

    /** Подготовка фокуса/chooser вне timer, без замены measured OS-жеста direct intent. */
    public interface Session {
        /** Настоящий наблюдатель; timings и Expected ему не передаются. */
        Requirements86Collector.Transport transport();
        /** Подготавливает только незамеряемую часть опыта; запрос OPEN ещё не отправлен. */
        void prepare(Expected event) throws Exception;
        /** Проводит один UX-шаг обычными widgets; текст notes не является approval. */
        String ux(String step) throws Exception;
    }

    /** Одна клавиша или chord одного опыта; код Expected.input не вычисляется по наблюдению. */
    public record Gesture(Client client, long sequence, List<Integer> keys) {
        /** Замораживает независимо согласованный список физических клавиш. */
        public Gesture { keys = List.copyOf(keys); }
    }

    /** Фактический файл источника, fixture или frozen launch jar с внешним pin до запуска. */
    public record Pin(String path, String sha256) { }

    /** До запуска фиксируются все входы и contract, включая dirty исходники, не только git HEAD. */
    public record Inputs(String sourceHeadSha, List<Pin> pins, String contractPath, String keypressesPath, List<Gesture> gestures) {
        /** Замораживает исходные входы. */
        public Inputs { pins = List.copyOf(pins); gestures = List.copyOf(gestures); }
    }

    /** Читает pinned manifest; ни один supplied timestamp не становится измеренным Sample. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("USAGE INPUTS_JSON INPUTS_SHA256 NEW_TEMP_DIR SESSION_FACTORY_CLASS");
        Path manifest = Path.of(args[0]).toAbsolutePath().normalize();
        if (!sha(manifest).equals(args[1])) throw new AssertionError("foreign-input-manifest");
        Inputs inputs = Requirements86Acceptance.decode(Files.readString(manifest), Inputs.class);
        Contract contract = Requirements86Acceptance.decode(Files.readString(Path.of(inputs.contractPath())), Contract.class);
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!output.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
            throw new IllegalArgumentException("own-temp-required");
        validate(inputs, contract);
        requireHead(inputs);
        requireSourcePins(Path.of(System.getProperty("user.dir")), inputs);
        if (java.awt.GraphicsEnvironment.isHeadless()) throw new IllegalStateException("native-desktop-required");
        Object factory = Class.forName(args[3]).getConstructor().newInstance();
        if (!(factory instanceof Sessions sessions)) throw new IllegalArgumentException("session-factory-contract");
        run(inputs, contract, sessions, output, true);
    }

    /** Проверяет обязательные клиенты/все keys и SHA до любой factory или GUI side effect. */
    public static void validate(Inputs inputs, Contract contract) throws Exception {
        if (!inputs.sourceHeadSha().matches("[0-9a-f]{40}") || inputs.pins().isEmpty())
            throw new AssertionError("missing-source-provenance");
        var unique = new HashSet<Path>();
        boolean contractPinned = false, keysPinned = false;
        for (Pin pin : inputs.pins()) {
            Path path = Path.of(pin.path()).toAbsolutePath().normalize();
            if (!unique.add(path) || !pin.sha256().matches("[0-9a-f]{64}") || !pin.sha256().equals(sha(path)))
                throw new AssertionError("foreign-input-sha");
            if (path.equals(Path.of(inputs.contractPath()).toAbsolutePath().normalize())) contractPinned = true;
            if (path.equals(Path.of(inputs.keypressesPath()).toAbsolutePath().normalize())) keysPinned = true;
        }
        if (!contractPinned) throw new AssertionError("missing-contract-pin");
        if (!keysPinned) throw new AssertionError("missing-keypresses-pin");
        List<?> prefixes = (List<?>) JsonParser.parse(Files.readString(Path.of(inputs.keypressesPath())));
        if (prefixes.isEmpty()) throw new AssertionError("missing-ordinary-keys");
        if (!contract.events().keySet().equals(Set.of(Client.values())) || !contract.jars().keySet().equals(Set.of(Client.values())))
            throw new AssertionError("all-clients");
        var expected = new HashSet<String>();
        for (Client client : Client.values()) {
            var events = contract.events().get(client);
            if (events.size() != prefixes.size() + 1 || events.getFirst().operation() != Operation.OPEN
                    || !events.getFirst().input().isEmpty()) throw new AssertionError("missing-ordinary-keys");
            for (int index = 0; index < prefixes.size(); index++)
                if (events.get(index + 1).operation() != Operation.FILTER || !events.get(index + 1).input().equals(
                        Json.requireString(Json.asObject(prefixes.get(index), "prefix"), "input"))) throw new AssertionError("foreign-key-order");
            for (Expected event : events) expected.add(client + "/" + event.sequence());
            // Каждый реально запускаемый JAR и fixture обязан иметь pin actual bytes, не invented digest.
            for (String digest : contract.jars().get(client).values())
                if (inputs.pins().stream().noneMatch(pin -> pin.sha256().equals(digest))) throw new AssertionError("missing-jar-pin");
            for (Expected event : events)
                if (inputs.pins().stream().noneMatch(pin -> pin.sha256().equals(event.fixtureDigest()))) throw new AssertionError("missing-fixture-pin");
        }
        var actual = new HashSet<String>();
        for (Gesture gesture : inputs.gestures())
            if (gesture.keys().isEmpty() || gesture.keys().stream().anyMatch(key -> key == null || key <= 0)
                    || !actual.add(gesture.client() + "/" + gesture.sequence()))
                throw new AssertionError("invalid-or-duplicate-key");
        if (!actual.equals(expected)) throw new AssertionError("missing-or-foreign-key");
        if (Instant.now().isBefore(contract.notBefore()) || contract.maxAge().isNegative() || contract.maxAge().isZero()
                || java.time.Duration.between(contract.notBefore(), Instant.now()).compareTo(contract.maxAge()) > 0)
            throw new AssertionError("stale-input-contract");
    }

    /** Вызывает ровно один сбор; contract tests явно передают false и не повышают mock scope. */
    static void run(Inputs inputs, Contract contract, Sessions sessions, Path output, boolean requireNative) throws Exception {
        try (sessions; var collector = new Requirements86Collector(contract)) {
            validate(inputs, contract);
            if (requireNative) requireHead(inputs);
            if (requireNative) requireSourcePins(Path.of(System.getProperty("user.dir")), inputs);
            Files.createDirectory(output);
            try {
                for (Client client : Client.values()) {
                    Path home = output.resolve(client.name().toLowerCase(Locale.ROOT));
                    Files.createDirectory(home);
                    Session[] session = new Session[1];
                    collector.launch(client, () -> {
                        session[0] = Objects.requireNonNull(sessions.open(client, home), "missing-session");
                        var transport = Objects.requireNonNull(session[0].transport(), "missing-transport");
                        if (requireNative && !(transport instanceof Requirements86Collector.NativeDesktop)) {
                            try { transport.close(); } finally { throw new AssertionError("mock-not-native"); }
                        }
                        return transport;
                    });
                    for (Expected event : contract.events().get(client)) {
                        session[0].prepare(event);
                        validate(inputs, contract);
                        if (requireNative) requireHead(inputs);
                        Gesture gesture = inputs.gestures().stream().filter(g -> g.client() == client && g.sequence() == event.sequence()).findFirst().orElseThrow();
                        collector.collect(client, gesture.keys().stream().mapToInt(Integer::intValue).toArray());
                    }
                    for (String step : UX_STEPS) collector.observeUx(client, step, session[0].ux(step));
                    collector.release(client);
                }
                validate(inputs, contract);
                if (requireNative) requireHead(inputs);
                if (requireNative) requireSourcePins(Path.of(System.getProperty("user.dir")), inputs);
                collector.write(output.resolve("evidence"));
                Path evidence = output.resolve("evidence");
                Files.copy(Path.of(inputs.keypressesPath()), evidence.resolve("expected-keypresses.json"));
                String fixture = contract.events().get(Client.FX).getFirst().fixtureDigest();
                Pin plan = inputs.pins().stream().filter(pin -> pin.sha256().equals(fixture) && pin.path().endsWith(".md")).findFirst().orElseThrow();
                Files.createDirectory(evidence.resolve("CashMemory"));
                Files.copy(Path.of(plan.path()), evidence.resolve("CashMemory/S5-performance-weekly-600.md"));
                write(evidence.resolve("collection-inputs.json"), Requirements86Acceptance.encode(inputs));
                write(evidence.resolve("collection.json"), Map.of("status", "COLLECTED_NOT_ACCEPTED",
                        "sourceHeadSha", inputs.sourceHeadSha(), "inputsSha256", sha(evidence.resolve("collection-inputs.json")),
                        "nativeRequested", requireNative, "independentlyReviewed", false));
            } catch (Throwable failure) {
                try { collector.writeDiagnostic(output.resolve("failed-diagnostic"), failure); }
                catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
                throw failure;
            }
        }
    }

    /** Неизменённая матрица UX; наблюдение не равно независимой приёмке. */
    public static final List<String> UX_STEPS = List.of("savings-create", "goal-decision", "funding-gap", "compare-scenarios", "save-reopen");

    /** Хеширует actual ordinary bytes; links в пути не допускаются. */
    static String sha(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("input-link");
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("missing-input-file");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(absolute)));
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JsonWriter.write(value), StandardOpenOption.CREATE_NEW);
    }

    /** Только read-only Git: чужой source HEAD не приписывается текущему кандидату. */
    private static void requireHead(Inputs inputs) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
        String head = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || !head.equals(inputs.sourceHeadSha())) throw new AssertionError("foreign-source-head");
    }

    /** Dirty production source/resources и collector входят во внешний manifest; одного HEAD недостаточно. */
    static void requireSourcePins(Path sourceRoot, Inputs inputs) throws Exception {
        var pinned = new HashSet<Path>();
        for (Pin pin : inputs.pins()) pinned.add(Path.of(pin.path()).toAbsolutePath().normalize());
        var required = new HashSet<Path>();
        Path root = sourceRoot.toAbsolutePath().normalize();
        required.add(root.resolve("pom.xml"));
        for (String module : List.of("core", "ui-fx", "ui-swing", "web")) {
            required.add(root.resolve(module + "/pom.xml"));
            Path main = root.resolve(module + "/src/main");
            if (!Files.isDirectory(main)) throw new AssertionError("missing-source-root");
            try (var files = Files.walk(main)) { files.filter(Files::isRegularFile).forEach(required::add); }
        }
        Path performance = root.resolve("ui-parity/src/test/java/ru/cashprediction/parity/check/performance");
        try (var files = Files.list(performance)) {
            files.filter(path -> path.getFileName().toString().startsWith("Requirements86") || path.getFileName().toString().equals("DomainLatencyGate.java"))
                    .forEach(required::add);
        }
        if (!pinned.containsAll(required)) throw new AssertionError("missing-production-source-pin");
    }
}
