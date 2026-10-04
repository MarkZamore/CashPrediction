package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.process.ProcessTree;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import ru.cashprediction.parity.registry.RegistryTreeSnapshot;

/** Один настоящий архив в real stores; общий holder отключает recorder у двух последовательных клиентов. */
final class ControlledSnapshotFixture implements AutoCloseable {
    final Path output;
    final Path home;
    final String node;
    final AppEnvironment environment;
    final SessionSnapshot snapshot;
    final Process holder;
    private final byte[] xml;
    private final RegistryTreeSnapshot seededRegistry;
    private final RegistryTreeSnapshot realSessions;

    private ControlledSnapshotFixture(Path output, String node, AppEnvironment environment,
                                      SessionSnapshot snapshot, Process holder, byte[] xml,
                                      RegistryTreeSnapshot seededRegistry, RegistryTreeSnapshot realSessions) {
        this.output = output; this.home = output.resolve("home"); this.node = node; this.environment = environment;
        this.snapshot = snapshot; this.holder = holder; this.xml = xml;
        this.seededRegistry = seededRegistry; this.realSessions = realSessions;
    }

    /** Готовит real stores ДО GUI; XML обоих клиентов содержит один канонический FX-архив, не их живые сеансы. */
    static ControlledSnapshotFixture open(Path output, boolean prepared) throws Exception {
        Files.createDirectories(output);
        Path home = output.resolve("home");
        String node = RegistryNodeCleaner.newSelftestNode();
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        Process holder = null;
        try {
            var env = AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry-node", node,
                    "--today", LaunchRequest.PARITY_TODAY.toString()));
            Files.createDirectories(env.cashMemory());
            var plan = SamplePlan.create(LaunchRequest.PARITY_TODAY);
            Path file = env.cashMemory().resolve(plan.name() + ".md");
            Files.writeString(file, PlanMarkdownWriter.write(plan));
            SettingsMarkdown.save(env.cashMemory().resolve("settings.md"), AppSettings.defaults().withPlanOpened(file.toString()));
            var main = new MainWindowState(new WindowBounds(100, 100, 1200, 800), false, "TABLE",
                    file.getFileName().toString(), "M12", Map.of(), "", "");
            var snapshot = prepared ? SessionSnapshot.of(Instant.parse("2026-09-13T12:00:00Z"), "fx", main,
                    PlanState.dirty(PlanMarkdownWriter.write(plan)), List.of()) : null;
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            Path classes = Path.of(ControlledSnapshotFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var builder = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", classes.toString(), Holder.class.getName())
                    .redirectOutput(output.resolve("holder.stdout.log").toFile())
                    .redirectError(output.resolve("holder.stderr.log").toFile());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
            holder = builder.start();
            Instant started = holder.info().startInstant().orElseThrow();
            var archiveMarker = SessionMarker.running(holder.pid(), started, "fx");
            for (String client : List.of("fx", "swing")) {
                var registry = env.registryStore(client);
                if (snapshot != null) registry.save(snapshot);
                // CrashDetector учитывает только маркер своего клиента; marker не входит в registry details payload.
                registry.markDirty(SessionMarker.running(holder.pid(), started, client));
                var archive = new XmlSessionStore(env.xmlStore(client).file(), "fx");
                if (snapshot != null) archive.save(snapshot);
                archive.markDirty(archiveMarker);
            }
            byte[] xml = Files.readAllBytes(env.xmlStore("fx").file());
            var result = new ControlledSnapshotFixture(output, node, env, snapshot, holder, xml,
                    RegistryNodeCleaner.snapshot(node), before);
            result.requireUnchanged();
            result.requireAlreadyRunning();
            return result;
        } catch (Exception | AssertionError failure) {
            try { if (holder != null) ProcessTree.kill(holder.toHandle(), Duration.ofSeconds(10)).requireClean(); }
            finally { RegistryNodeCleaner.delete(node); }
            throw failure;
        }
    }

    /** Проверяет настоящий ProcessProbe и собственные markers, а не подставляет статус запуска. */
    void requireAlreadyRunning() {
        for (String client : List.of("fx", "swing")) {
            var detection = CrashDetector.detect(List.of(environment.registryStore(client), environment.xmlStore(client)), client);
            if (detection.status() != CrashDetector.Status.ALREADY_RUNNING)
                throw new AssertionError("Real holder did not produce ALREADY_RUNNING: " + client);
        }
    }

    /** Подтверждает неизменность всех данных без удаления или перезаписи во время работы UI. */
    void requireUnchanged() throws Exception {
        if (!holder.isAlive()) throw new AssertionError("Fixture holder exited");
        for (String client : List.of("fx", "swing")) {
            if (!java.util.Arrays.equals(xml, Files.readAllBytes(environment.xmlStore(client).file())))
                throw new AssertionError("Archived XML changed: " + client);
            if (!java.util.Objects.equals(snapshot, environment.registryStore(client).load().orElse(null))
                    || !java.util.Objects.equals(snapshot, environment.xmlStore(client).load().orElse(null)))
                throw new AssertionError("Real store snapshot changed: " + client);
        }
        if (!seededRegistry.equals(RegistryNodeCleaner.snapshot(node))) throw new AssertionError("Registry fixture changed");
    }

    /** Сначала проверяет fixture, затем всегда завершает только holder и удаляет только собственный узел. */
    @Override public void close() throws Exception {
        try { requireUnchanged(); }
        finally {
            try { ProcessTree.kill(holder.toHandle(), Duration.ofSeconds(10)).requireClean(); }
            finally {
                RegistryNodeCleaner.delete(node);
                if (RegistryNodeCleaner.exists(node)) throw new AssertionError("Fixture node survived");
                if (!realSessions.equals(RegistryNodeCleaner.snapshotRealSessionNodes())) throw new AssertionError("Real session changed");
            }
        }
    }

    /** Ограниченная JVM для настоящего pid; не запускает клиент и не пишет stores. */
    public static final class Holder {
        private Holder() { }
        /** Самостоятельно завершится через три минуты даже при аварии harness. */
        public static void main(String[] args) throws InterruptedException { Thread.sleep(Duration.ofMinutes(3)); }
    }
}
