package ru.cashprediction.parity.e2e.interaction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;

/** Изолированный стенд настоящих экземпляров: отдельные журналы каждого запуска сохраняются после теста. */
final class InteractionSession implements AutoCloseable {
    final String client;
    final Path root;
    final Path home;
    final String node = RegistryNodeCleaner.newSelftestNode();
    final XmlSessionStore xml;
    final RegistrySessionStore registry;
    private final ReactorLayout layout = ReactorLayout.fromSystemProperties();
    private final List<LaunchedClient> processes = new ArrayList<>();
    private final ru.cashprediction.parity.registry.RegistryTreeSnapshot realBefore =
            RegistryNodeCleaner.snapshotRealSessionNodes();

    /** Создаёт уникальную папку; существующие результаты и пользовательские данные не удаляет. */
    InteractionSession(String client) throws IOException {
        assertTrue(client.equals("fx") || client.equals("swing"), "Only real desktop clients");
        this.client = client;
        root = Files.createTempDirectory(Files.createDirectories(layout.parityRoot()), "s4-interaction-" + client + "-");
        home = Files.createDirectories(root.resolve("home"));
        Path memory = Files.createDirectories(home.resolve("CashMemory"));
        xml = XmlSessionStore.inCashMemory(memory, client);
        registry = RegistrySessionStore.forClient(client, memory, node);
        if (!registry.isAvailable()) {
            RegistryNodeCleaner.delete(node);
            throw new AssertionError(registry.unavailableReason());
        }
    }

    /** Запускает внешний сценарий без автоматического ответа на восстановление. */
    LaunchedClient launch(String name, String script) throws IOException {
        SelfTestScript.parse(name, script); // Ошибка CPS должна обнаружиться до появления окон.
        Path folder = Files.createDirectories(root.resolve(name));
        Path cps = folder.resolve(name + ".cps");
        Files.writeString(cps, script);
        var request = new LaunchRequest(client, name, home, node, LaunchRequest.PARITY_TODAY,
                cps.toString(), folder.resolve("out"), List.of(), List.of());
        var launched = ClientLauncher.launch(client.equals("fx") ? ClientTarget.fx(layout) : ClientTarget.swing(layout), request);
        processes.add(launched);
        return launched;
    }

    /** Ожидает сигнал клиента и проверяет полный успешный префикс до блокирующей строки. */
    void barrier(LaunchedClient process, String name) throws IOException {
        process.waitForFile(process.request().selftestOut().resolve(name), Duration.ofSeconds(45));
        Path cps = Path.of(process.request().selftest());
        var script = SelfTestScript.load(cps.toString());
        String log = Files.readString(process.request().selftestOut().resolve("selftest.log"));
        StringBuilder expected = new StringBuilder();
        boolean found = false;
        for (var line : script.lines()) {
            if (line.text().equals("signal " + name)) { found = true; break; }
            expected.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
        }
        assertTrue(found, "Missing signal in script");
        assertEquals(expected.toString(), log, "Pre-crash prefix must be exact, with neither FAIL nor DONE");
        assertTrue(process.process().isAlive());
    }

    /** Разрешает продолжение только собственного сценария. */
    void release(LaunchedClient process, String name) throws IOException {
        Files.delete(process.request().selftestOut().resolve(name));
    }

    /** Проверяет журнал выхода: допустим обрыв только на последней команде, которая завершает процесс. */
    void terminalPrefix(LaunchedClient process) throws IOException {
        var lines = SelfTestScript.load(process.request().selftest()).lines();
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < lines.size() - 1; i++) {
            var line = lines.get(i);
            prefix.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
        }
        var last = lines.getLast();
        String completed = prefix + "SELFTEST " + last.number() + " OK " + last.text() + "\n";
        String actual = Files.readString(process.request().selftestOut().resolve("selftest.log"));
        assertTrue(actual.equals(prefix.toString()) || actual.equals(completed) || actual.equals(completed + "SELFTEST DONE\n"),
                "Unexpected exit journal: " + actual);
    }

    /** Читает исходные измерения shot из папки сценария; нормализованный дамп не допускается. */
    Map<String, Object> dump(LaunchedClient process, String name) throws IOException {
        String scenario = SelfTestScript.load(process.request().selftest()).name();
        Path directory = process.request().selftestOut().resolve(scenario);
        assertTrue(Files.isRegularFile(directory.resolve(name + ".png")), "Missing paired actual screenshot: " + directory);
        var value = Json.asObject(JsonParser.parse(Files.readString(directory.resolve(name + ".raw.json"))), "raw shot");
        assertEquals(client, value.get("client"));
        assertEquals(scenario, value.get("scenario"));
        assertEquals(name, value.get("step"));
        return value;
    }

    /** Проверяет назначение единственного актуального диалога в реальном дампе. */
    Map<String, Object> alert(LaunchedClient process, String step, String purpose) throws IOException {
        Object values = dump(process, step).get("alerts");
        assertInstanceOf(List.class, values);
        List<?> alerts = (List<?>) values;
        assertEquals(1, alerts.size(), "Expected exactly one visible alert");
        Map<String, Object> alert = Json.asObject(alerts.getFirst(), "alert");
        assertEquals(purpose, alert.get("purpose"));
        return alert;
    }

    /** Ожидает атомарно записанный снимок с нужным содержимым в обоих независимых хранилищах. */
    SessionSnapshot committed(LaunchedClient process, Predicate<SessionSnapshot> predicate) {
        var confirmed = new java.util.concurrent.atomic.AtomicReference<SessionSnapshot>();
        process.waitUntil(() -> {
            try {
                var x = xml.load(); var r = registry.load();
                if (x.isPresent() && r.isPresent() && x.get().equals(r.get()) && predicate.test(x.get())) {
                    confirmed.set(x.get());
                    return true;
                }
                return false;
            } catch (Exception transientRead) { return false; }
        }, Duration.ofSeconds(30), "matching committed XML and registry snapshots");
        try {
            // Возвращаем именно пару, прошедшую сравнение; повторное чтение могло бы увидеть следующий commit.
            var snapshot = java.util.Objects.requireNonNull(confirmed.get(), "Missing confirmed snapshot pair");
            Path evidence = Files.createDirectories(root.resolve(process.request().scenario()).resolve("committed"));
            // Первичные снимки сохраняются до любых тестовых повреждений и повторного запуска.
            String stamp = snapshot.savedAt().toEpochMilli() + "-" + System.nanoTime();
            // У снимка есть Instant: используем специализированный кодек с проверкой обратного чтения,
            // а не UiJson, предназначенный для моделей интерфейса.
            var codec = new ru.cashprediction.core.session.codec.JsonSnapshotCodec();
            String encoded = codec.encode(snapshot);
            assertEquals(snapshot, codec.decode(encoded), "Evidence codec must preserve the confirmed snapshot exactly");
            Files.writeString(evidence.resolve(stamp + "-before.snapshot.json"), encoded);
            Files.copy(xml.file(), evidence.resolve(stamp + ".xml"));
            Files.writeString(evidence.resolve(stamp + "-registry.txt"), RegistryNodeCleaner.snapshot(node).toString());
            return snapshot;
        }
        catch (Exception e) { throw new AssertionError(e); }
    }

    /** Формирует подпись реальной кнопки выбора XML по времени сохранённого снимка. */
    String restoreXml(SessionSnapshot snapshot) {
        return answer(UiText.get("button.restoreXml", UiFormats.time(snapshot.savedAt().atZone(ZoneId.systemDefault()).toLocalTime())));
    }

    /** Экранирует пользовательскую подпись в языке CPS. */
    static String answer(String caption) { return "answer " + quote(caption) + "\n"; }

    /** Экранирует строку CPS, не изменяя содержимое поля. */
    static String quote(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** Завершает только собственное дерево, проверяя PID и время рождения корня. */
    void crash(LaunchedClient process) throws InterruptedException {
        assertTrue(processes.contains(process), "Foreign process");
        assertTrue(process.process().isAlive(), "Already exited before forced crash");
        assertTrue(process.process().toHandle().info().startInstant().isPresent(), "Missing process identity");
        process.kill().requireClean();
        // ProcessTree ждёт ProcessHandle.onExit; объект Process обновляется отдельным reaper-потоком.
        // Не считаем снимок погибшего процесса аварийным до подтверждённого завершения обоих API.
        assertTrue(process.process().waitFor(5, java.util.concurrent.TimeUnit.SECONDS),
                "Process object did not confirm exit after clean tree kill");
        assertFalse(process.process().isAlive(), "Java Process still alive after confirmed exit");
        assertFalse(process.process().toHandle().isAlive(), "ProcessHandle still alive after confirmed exit");
    }

    /** Завершает все собственные процессы и удаляет только UUID-узел, сохраняя первичные журналы. */
    @Override public void close() {
        Throwable failure = null;
        for (int i = processes.size() - 1; i >= 0; i--) {
            try { processes.get(i).close(); } catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        try {
            RegistryNodeCleaner.delete(node);
            assertFalse(RegistryNodeCleaner.exists(node));
            assertEquals(List.of(), realBefore.differences(RegistryNodeCleaner.snapshotRealSessionNodes()), "Real registry changed");
        } catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw new AssertionError("Cleanup failed; evidence: " + root, failure);
    }
}
