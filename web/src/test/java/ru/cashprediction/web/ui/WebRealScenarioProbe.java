package ru.cashprediction.web.ui;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import ru.cashprediction.core.app.ClientKind;

/** Ручной прогон настоящего браузера через уже собранный JDK-стенд; не заменяет сравнение с эталонами. */
public final class WebRealScenarioProbe {
    private WebRealScenarioProbe() { }

    /** Копирует jar в отдельную папку и исполняет DOM-шаги с отправкой настоящих PNG перед результатом. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[2]).toAbsolutePath(); Files.createDirectories(output);
        Path web = Files.copy(Path.of(args[0]), output.resolve("web.jar"));
        Path core = Files.copy(Path.of(args[1]), output.resolve("core.jar"));
        Path script = output.resolve("shot-probe.cps");
        try (var source = WebRealScenarioProbe.class.getResourceAsStream("/ui/shot-probe.cps")) {
            Files.copy(Objects.requireNonNull(source), script);
        }
        String scenario = args.length > 3 ? args[3] : script.toString();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dcashprediction.web.port=0",
                "--module-path", web + ";" + core, "--module", "ru.cashprediction.web/ru.cashprediction.web.WebMain",
                "--home", output.resolve("home").toString(), "--registry", "memory", "--today", "2026-09-13",
                "--selftest", scenario, "--selftest-out", output.resolve("out").toString(),
                "--no-browser", "--no-window", "--test-api")
                .redirectError(output.resolve("stderr.log").toFile()).start();
        var address = new CompletableFuture<URI>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.inputReader(StandardCharsets.UTF_8);
                 var log = Files.newBufferedWriter(output.resolve("stdout.log"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.write(line); log.newLine(); log.flush();
                    if (line.startsWith("PARITY_URL ")) address.complete(URI.create(line.substring(11)));
                }
            } catch (Exception error) { address.completeExceptionally(error); }
        });
        try {
            URI url = address.get(15, TimeUnit.SECONDS);
            Class<?> launcher = Class.forName("ru.cashprediction.parity.browser.EdgeLauncher");
            Path executable = (Path) ((Optional<?>) launcher.getMethod("findBrowser").invoke(null)).orElseThrow();
            try (var browser = (AutoCloseable) launcher.getMethod("startWithViewport", Path.class, Path.class,
                    int.class, int.class, String.class, Duration.class).invoke(null, executable, output.resolve("browser"),
                    1200, 800, url.toString(), Duration.ofSeconds(20))) {
                Class<?> cdpType = Class.forName("ru.cashprediction.parity.browser.CdpClient");
                int port = (Integer) browser.getClass().getMethod("port").invoke(browser);
                try (var cdp = (AutoCloseable) cdpType.getMethod("connectToFirstPage", int.class, Duration.class)
                        .invoke(null, port, Duration.ofSeconds(20))) {
                    cdpType.getMethod("waitFor", String.class, Duration.class).invoke(cdp,
                            "!!window.cpParityTestApi", Duration.ofSeconds(20));
                    Class<?> apiType = Class.forName("ru.cashprediction.parity.driver.CdpTestApi");
                    Object api = apiType.getConstructor(cdpType).newInstance(cdp);
                    Class<?> driverType = Class.forName("ru.cashprediction.parity.driver.UiTestDriver");
                    Object driver = driverType.getConstructor(ClientKind.class,
                            Class.forName("ru.cashprediction.parity.driver.UiTestDriver$TestApi")).newInstance(ClientKind.WEB, api);
                    Class<?> bridgeType = Class.forName("ru.cashprediction.parity.driver.TestApiBridge");
                    Object sender = bridgeType.getMethod("http", URI.class).invoke(null, url);
                    Object bridge = bridgeType.getConstructor(driverType,
                            Class.forName("ru.cashprediction.parity.driver.TestApiBridge$Sender")).newInstance(driver, sender);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    Path journal = output.resolve("out/selftest.log");
                    while (true) {
                        String text = Files.exists(journal) ? Files.readString(journal) : "";
                        if (text.contains(" FAIL ")) throw new IllegalStateException(text);
                        if (text.contains("SELFTEST DONE")) break;
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("test.step timeout: " + text);
                        Object step = apiType.getMethod("takeStep").invoke(api);
                        if (step != null) bridgeType.getMethod("accept", Map.class).invoke(bridge, step);
                        else Thread.sleep(20);
                    }
                    System.out.println("REAL WEB SCENARIO OK " + output);
                }
            }
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS); reader.join(1000);
        }
    }
}
