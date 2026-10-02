package ru.cashprediction.parity.check.census;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.launch.ClientLauncher;
import ru.cashprediction.parity.launch.ClientTarget;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;

/** Запускает отдельный сценарий настоящего выбора папки, которого нет среди 18 встроенных сценариев. */
public final class DirectoryChooserProbe {
    /** Имя пользовательского сценария, совпадающее с именем файла и метаданными всех дампов. */
    public static final String SCENARIO = "census-directory-chooser";
    private static final String PENDING = "directory-pending";
    private static final String CANCELLED = "directory-cancelled";

    private DirectoryChooserProbe() { }

    /** Строит сценарий по настоящему id команды и общей локализации кнопки сообщения. */
    public static String scriptText() {
        String id = CommandId.FILE_CASH_MEMORY.id();
        if (CommandId.byId(id).orElseThrow() != CommandId.FILE_CASH_MEMORY) throw new IllegalStateException("Invalid folder command: " + id);
        return "today 2026-09-13\nkey Esc\nmenu " + id + "\nanswer "
                + quoted(UiText.get("button.otherFolder")) + "\ndump " + PENDING
                + "\nchooser cancel\ndump " + CANCELLED + "\n";
    }

    /** Замораживает jar отдельного запуска; общая перепись передаёт свой снимок другой перегрузке. */
    public static Map<String, UiDump> collect(ReactorLayout layout, Path output, Duration timeout) throws Exception {
        return collect(ClientJarSnapshot.copy(ClientTarget.fx(layout), output), output, timeout);
    }

    /** Запускает пробу с теми же замороженными jar, что и 18 сценариев, не читая живые артефакты сборки. */
    public static Map<String, UiDump> collect(ClientTarget target, Path output, Duration timeout) throws Exception {
        if (!target.client().equals("fx") || !target.mainModule().equals(ClientTarget.FX_MODULE)
                || !target.mainClass().equals(ClientTarget.FX_MAIN))
            throw new IllegalArgumentException("Directory probe requires FX target");
        Files.createDirectories(output);
        Path run = Files.createTempDirectory(output, "directory-probe-");
        Path source = run.resolve(SCENARIO + ".cps");
        Files.writeString(source, scriptText());
        SelfTestScript script = SelfTestScript.load(source.toString());
        String node = RegistryNodeCleaner.newSelftestNode();
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        Path home = run.resolve("home");
        Path out = run.resolve("selftest-out");
        Files.createDirectories(home.resolve("CashMemory"));
        Files.createDirectories(out);
        var request = new LaunchRequest("fx", script.name(), home, node, LaunchRequest.PARITY_TODAY,
                source.toAbsolutePath().toString(), out, "core", List.of(), List.of());
        try {
            try (var client = ClientLauncher.launch(target, request)) {
                Path log = out.resolve("selftest.log");
                client.waitUntil(() -> done(log), timeout, SCENARIO + " SELFTEST DONE");
                validateLog(script, Files.readString(log));
                Map<String, UiDump> dumps = FxCensus.readScenario(out.resolve(script.name()), script);
                verifyDirectoryEvidence(dumps);
                return dumps;
            }
        } finally {
            RegistryNodeCleaner.delete(node);
            if (RegistryNodeCleaner.exists(node)) throw new IllegalStateException("Directory probe registry node survived: " + node);
            if (!before.equals(RegistryNodeCleaner.snapshotRealSessionNodes()))
                throw new IllegalStateException("Directory probe changed real session registry");
        }
    }

    /** Требует ровно один успешный результат каждой исходной строки и окончательную отметку DONE. */
    public static void validateLog(SelfTestScript script, String contents) {
        var actual = contents.lines().toList();
        if (actual.size() != script.lines().size() + 1 || !actual.getLast().equals("SELFTEST DONE"))
            throw new IllegalStateException("Incomplete script log: " + script.name() + "\n" + contents);
        for (int i = 0; i < script.lines().size(); i++) {
            var step = script.lines().get(i);
            String expected = "SELFTEST " + step.number() + " OK " + step.text();
            if (!actual.get(i).equals(expected)) throw new IllegalStateException("Script step failed or mismatched: "
                    + script.name() + ":" + step.number() + " expected=" + expected + " actual=" + actual.get(i));
        }
    }

    /** Требует настоящий запрос папки и положительную перепись класса до и после отмены выбора. */
    public static void verifyDirectoryEvidence(Map<String, UiDump> dumps) {
        if (!dumps.keySet().equals(java.util.Set.of(PENDING, CANCELLED)))
            throw new AssertionError("Directory probe dump steps mismatch: " + dumps.keySet());
        for (String step : List.of(PENDING, CANCELLED)) {
            UiDump dump = dumps.get(step);
            if (dump.schema() != UiDump.SCHEMA || !dump.client().equals("fx")
                    || !dump.scenario().equals(SCENARIO) || !dump.step().equals(step))
                throw new AssertionError("Directory probe identity mismatch: " + SCENARIO + "/" + step);
            int count = dump.classCensus().getOrDefault("DirectoryChooser", 0);
            int canonicalCount = dump.classCensus().getOrDefault("javafx.stage.DirectoryChooser", 0);
            if (count < 0 || canonicalCount < 0 || dump.classCensus().containsKey("DirectoryChooser")
                    && dump.classCensus().containsKey("javafx.stage.DirectoryChooser"))
                throw new AssertionError("Invalid DirectoryChooser census: " + SCENARIO + "/" + step);
            if (Math.max(count, canonicalCount) <= 0) throw new AssertionError("Not instantiated: javafx.stage.DirectoryChooser "
                    + SCENARIO + "/" + step);
            if (dump.chooserRequests().size() != 1 || !dump.chooserRequests().getFirst().kind().equals("directory"))
                throw new AssertionError("Missing directory request: " + SCENARIO + "/" + step);
            if (dump.counters().getOrDefault(CommandId.FILE_CASH_MEMORY.id(), 0) != 1)
                throw new AssertionError("Folder command did not fire exactly once: " + SCENARIO + "/" + step);
        }
    }

    /** Проверяет окончание журнала, пока процесс жив и не истёк срок ожидания. */
    private static boolean done(Path log) {
        try { return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (IOException failure) { return false; }
    }

    /** Экранирует локализованную подпись по грамматике cps, без собственных видимых текстов. */
    private static String quoted(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
