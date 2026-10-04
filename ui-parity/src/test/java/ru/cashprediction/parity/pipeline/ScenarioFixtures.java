package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.markdown.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.forms.plan.PlanSettingsForm;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.process.ProcessTree;

/** Готовит только данные сценариев; никогда не строит дампы вместо настоящих виджетов. */
public final class ScenarioFixtures implements AutoCloseable {
    private final LaunchRequest request;
    private Process holder;
    private Thread external;
    private volatile Throwable failure;
    private volatile boolean closing;
    private volatile boolean externalAcknowledged;

    private ScenarioFixtures(LaunchRequest request) { this.request = request; }

    /** Готовит свежий CashMemory и ограниченный сроком наблюдатель внешнего изменения. */
    public static ScenarioFixtures prepare(LaunchRequest request, Duration timeout) throws Exception {
        if (request.registryNodePrefix() == null
                || !request.registryNodePrefix().startsWith("ru/cashprediction/selftest/"))
            throw new IllegalArgumentException("Fixture requires isolated selftest registry");
        var fixtures = new ScenarioFixtures(request);
        try {
            Files.createDirectories(request.home().resolve("CashMemory"));
            Files.createDirectories(request.selftestOut());
            if (request.scenario().equals("s17-recovery-dialog") || request.scenario().equals("s18-already-running"))
                fixtures.snapshot();
            if (request.scenario().equals("s14-save-conflicts")) fixtures.watchExternal(timeout);
            return fixtures;
        } catch (Exception e) { fixtures.close(); throw e; }
    }

    /** Проверяет, что ошибка подготовки или наблюдателя не была потеряна. */
    public void requireHealthy() {
        if (failure != null) throw new IllegalStateException("Scenario fixture failed", failure);
        if (holder != null && !holder.isAlive()) throw new IllegalStateException("Already-running holder exited");
    }

    /** Требует завершённый обмен сигналом перед признанием сценария собранным. */
    public void requireComplete() {
        requireHealthy();
        if (external != null && !externalAcknowledged) throw new IllegalStateException("external-change not acknowledged");
    }

    /** Записывает исходные файлы и настоящий маркер сеанса через штатные кодеки ядра. */
    private void snapshot() throws Exception {
        var env = AppEnvironment.from(LaunchOptions.parse("--home", request.home().toString(),
                "--registry-node", request.registryNodePrefix(), "--today", request.today().toString()));
        var plan = SamplePlan.create(request.today());
        Path file = env.cashMemory().resolve(plan.name() + ".md");
        Files.writeString(file, PlanMarkdownWriter.write(plan));
        SettingsMarkdown.save(env.cashMemory().resolve("settings.md"), AppSettings.defaults().withPlanOpened(file.toString()));
        ClientProfile profile = switch (request.client()) {
            case "fx" -> ClientProfile.fx("25.0.4");
            case "swing" -> ClientProfile.swing();
            case "web" -> ClientProfile.web();
            default -> throw new IllegalArgumentException(request.client());
        };
        List<WindowState> windows = List.of();
        if (request.scenario().equals("s17-recovery-dialog")) {
            var document = new DocumentView(plan, file, false, false, "", false, "", null, "", List.of());
            var app = new AppState(0, profile, request.today(), env.cashMemory(), env.cashMemory(), document,
                    ViewState.defaults(), "", false, AppSettings.defaults(), RecorderStatus.NOT_STARTED,
                    List.of(), null, null, "");
            Map<String, String> fields = new LinkedHashMap<>(new PlanSettingsForm().defaults(
                    new FormContext("w1", "main", Map.of(), app)));
            fields.put("startBalance", "bad");
            windows = List.of(new WindowState("w1", WindowType.PLAN_SETTINGS, true, "main", null,
                    Map.of("page", "0"), fields));
        }
        Instant noon = request.today().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        var main = new MainWindowState(null, false, "TABLE", file.getFileName().toString(), "M12", Map.of(), "", "");
        var snapshot = SessionSnapshot.of(noon, request.client(), main, PlanState.CLEAN, windows);
        SessionMarker marker = SessionMarker.running(0, noon, request.client());
        if (request.scenario().equals("s18-already-running")) {
            // Отдельная JVM имеет тот же java.exe, но чужой pid: CrashDetector проверяет оба условия.
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            String classes = Path.of(ScenarioFixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            var builder = new ProcessBuilder(java.toString(), "-XX:-UsePerfData",
                    "-Dcashprediction.home=" + request.home(), "-Dcashprediction.registry.node=" + request.registryNodePrefix(),
                    "-cp", classes, FixtureHolder.class.getName()).directory(request.home().toFile())
                    .redirectOutput(request.logDirectory().resolve("holder.out").toFile())
                    .redirectError(request.logDirectory().resolve("holder.err").toFile());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
            holder = builder.start();
            marker = SessionMarker.running(holder.pid(), holder.info().startInstant().orElseThrow(), request.client());
        }
        List<SessionStore> stores = request.client().equals("web") ? List.of(env.webStore())
                : List.of(env.registryStore(request.client()), env.xmlStore(request.client()));
        for (var store : stores) {
            // s17 проверяет выбор XML при отсутствии снимка реестра; маркер сбоя остаётся в обоих хранилищах.
            if (!request.scenario().equals("s17-recovery-dialog") || !store.id().equals("registry")) store.save(snapshot);
            store.markDirty(marker);
        }
    }

    /** Ждёт реальный signal раннера, меняет только mtime созданного сценарием плана и подтверждает удалением сигнала. */
    private void watchExternal(Duration timeout) {
        external = Thread.ofPlatform().name("parity-external-change").start(() -> {
            Path signal = request.selftestOut().resolve("external-change");
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (!Files.exists(signal)) {
                    if (closing) return;
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("external-change signal absent");
                    Thread.sleep(10);
                }
                Path file = request.home().resolve("CashMemory").resolve(SamplePlan.name() + ".md");
                Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
                Files.delete(signal);
                externalAcknowledged = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!closing) failure = e;
            } catch (Exception e) { failure = e; }
        });
    }

    /** Завершает вспомогательную JVM и наблюдатель, не меняя данные настоящих пользователей. */
    @Override public void close() {
        closing = true;
        if (external != null) {
            external.interrupt();
            try { external.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (external.isAlive()) throw new IllegalStateException("Fixture watcher survived");
        }
        if (holder != null) ProcessTree.kill(holder.toHandle(), Duration.ofSeconds(10)).requireClean();
    }

    /** Живой владелец маркера для s18; сам завершится, даже если стенд аварийно остановится. */
    public static final class FixtureHolder {
        private FixtureHolder() { }
        /** Держит pid до закрытия стендом, с аварийным ограничением в десять минут. */
        public static void main(String[] args) throws InterruptedException { Thread.sleep(Duration.ofMinutes(10)); }
    }
}
