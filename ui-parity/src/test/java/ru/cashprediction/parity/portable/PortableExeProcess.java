package ru.cashprediction.parity.portable;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.browser.*;
import ru.cashprediction.parity.driver.*;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.process.ProcessTree;

/** Запускает именно EXE; отслеживает дочернюю JVM jpackage по пути собственной копии и времени рождения. */
final class PortableExeProcess implements AutoCloseable {
    private final Path copy;
    private final Instant started = Instant.now();
    private final Process launcher;
    private final Path stdout;
    private final Duration timeout;
    private BrowserSession browser;
    private CdpClient cdp;
    private CdpTestApi api;
    private TestApiBridge bridge;

    /** Использует аргументы общего launcher-контракта, но никогда не запускает модуль вместо EXE. */
    PortableExeProcess(Path copy, Path exe, LaunchRequest request, Duration timeout) throws Exception {
        this.copy = copy.toRealPath(); this.timeout = timeout;
        requireOwnedCopy(this.copy, exe.toRealPath());
        require(copyProcesses().isEmpty(), "Copy already has live processes");
        Path out = request.selftestOut();
        Files.createDirectories(out);
        stdout = out.resolve("client.stdout.log");
        var target = argumentTarget(this.copy, request.client());
        var command = new ArrayList<String>();
        command.add(exe.toString()); command.addAll(ClientLauncher.applicationArguments(target, request));
        var builder = new ProcessBuilder(command).directory(copy.toFile())
                .redirectOutput(stdout.toFile()).redirectError(out.resolve("client.stderr.log").toFile());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
        builder.environment().put("JAVA_TOOL_OPTIONS", String.join(" ", ClientLauncher.STANDARD_JVM_OPTIONS));
        launcher = builder.start();
        try {
            if (request.client().equals("web")) attach(out.resolve("browser-profile"));
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Описывает упакованные модули только для общего построения аргументов; запускается всегда EXE. */
    static ClientTarget argumentTarget(Path copy, String client) {
        return new ClientTarget(client, List.of(copy.resolve("app")), "portable", "portable", List.of(), List.of(),
                client.equals("web") ? List.of("--no-browser", "--no-window", "--test-api") : List.of());
    }

    /** Подтверждает принадлежность пути по границе каталогов, без текстовых префиксов. */
    static void requireOwnedCopy(Path copy, Path executable) {
        require(executable.startsWith(copy) && !executable.equals(copy), "Foreign executable path");
    }

    /** Не разрешает удалять узлы при оставшихся писателях собственной копии. */
    static void requireCopyStopped(Path copy) {
        try (var all = ProcessHandle.allProcesses()) {
            require(all.filter(p -> p.pid() != ProcessHandle.current().pid()).filter(ProcessHandle::isAlive).noneMatch(p -> p.info().command()
                    .map(s -> Path.of(s).toAbsolutePath().normalize().startsWith(copy)).orElse(false)), "Copy still has live processes");
        }
    }

    /** Выбирает только процессы с наблюдаемым командным путём внутри копии. */
    private List<ProcessHandle> copyProcesses() {
        try (var all = ProcessHandle.allProcesses()) {
            return all.filter(p -> p.pid() != ProcessHandle.current().pid()).filter(ProcessHandle::isAlive).filter(p -> p.info().command().map(s -> {
                try { return Path.of(s).toAbsolutePath().normalize().startsWith(copy); }
                catch (InvalidPathException e) { return false; }
            }).orElse(false)).toList();
        }
    }

    /** Проверяет живую JVM, а не уже завершившийся родительский launcher. */
    boolean ownsPid(long pid) {
        return copyProcesses().stream().anyMatch(p -> p.pid() == pid
                && p.info().startInstant().map(t -> !t.isBefore(started)).orElse(false));
    }

    /** Ожидает фактическое условие, исполняя Web test.step через настоящий DOM. */
    void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            require(System.nanoTime() < deadline, "Portable timeout: " + description);
            require(launcher.isAlive() || !copyProcesses().isEmpty(), "EXE exited before " + description);
            if (bridge != null) {
                var step = api.takeStep();
                if (step != null) bridge.accept(step); else Thread.sleep(20);
            } else Thread.sleep(50);
        }
    }

    /** Проверяет единственный loopback-handshake и подключает существующий браузерный мост. */
    private void attach(Path profile) throws Exception {
        await(() -> address() != null, "PARITY_URL");
        URI url = Objects.requireNonNull(address());
        var sender = TestApiBridge.http(url);
        Path executable = EdgeLauncher.findBrowser().orElseThrow(() -> new IllegalStateException("Real browser required"));
        // План ownership из сохранённого draft публикуется до блокирующего старта Edge/CDP.
        Path intent = profile.getParent().resolve("browser-owner.tmp");
        Files.writeString(intent, UiJson.write(Map.of("profile", profile.toString(), "executable", executable.toAbsolutePath().normalize().toString())));
        Files.move(intent, profile.getParent().resolve("browser-owner.json"), StandardCopyOption.ATOMIC_MOVE);
        browser = EdgeLauncher.startWithViewport(executable, profile, 1200, 800, url.toString(), timeout);
        cdp = CdpClient.connectToFirstPage(browser.port(), timeout);
        cdp.waitFor("document.readyState === 'complete' && !!window.cpParityTestApi", timeout);
        api = new CdpTestApi(cdp);
        bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, api), sender);
    }

    /** Читает только завершённую строку handshake, не принимая случайную ссылку из stdout. */
    private URI address() {
        try {
            String text = Files.readString(stdout);
            int end = text.lastIndexOf('\n');
            if (end < 0) return null;
            var lines = text.substring(0, end).lines().filter(s -> s.startsWith("PARITY_URL ")).toList();
            if (lines.isEmpty()) return null;
            require(lines.size() == 1, "Duplicate PARITY_URL");
            URI url = URI.create(lines.getFirst().substring(11).strip());
            TestApiBridge.http(url);
            return url;
        } catch (java.io.IOException e) { return null; }
    }

    /** Убивает только наблюдаемые процессы своей копии; отсутствие командного пути не считается доказательством. */
    void kill() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        if (launcher.isAlive()) ProcessTree.kill(launcher.toHandle(), Duration.ofSeconds(20)).requireClean();
        while (true) {
            var processes = copyProcesses();
            if (processes.isEmpty()) return;
            require(System.nanoTime() < deadline, "Portable processes survived");
            for (var process : processes) {
                require(process.info().startInstant().map(t -> !t.isBefore(started)).orElse(false), "Unproven process ownership");
                ProcessTree.kill(process, Duration.ofSeconds(5)).requireClean();
            }
        }
    }

    /** Освобождает браузер и EXE даже при ошибке одного cleanup. */
    @Override public void close() throws Exception {
        Exception failure = null;
        try { if (cdp != null) cdp.close(); } catch (Exception e) { failure = e; }
        try { if (browser != null) browser.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        try { kill(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }

    /** Нарушение контракта всегда завершает проверку ошибкой. */
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
