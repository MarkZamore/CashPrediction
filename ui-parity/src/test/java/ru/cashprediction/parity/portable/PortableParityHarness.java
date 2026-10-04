package ru.cashprediction.parity.portable;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.selftest.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.pipeline.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static ru.cashprediction.parity.portable.PortableExeProcess.require;

/** Стенд S5 для настоящих портативных EXE: полные s02 goldens, затем принудительная гибель и восстановление. */
public final class PortableParityHarness {
    private static final String SCENARIO = "s02-sample-table";
    private static final List<String> CLIENTS = List.of("fx", "swing", "web");
    private static final Map<String, String> EXES = Map.of("fx", "CashPrediction.exe",
            "swing", "CashPrediction-Swing.exe", "web", "CashPrediction-Web.exe");
    private final Path copy;
    private final Path output;
    private final Path project;
    private final String tag;
    private final String nonce;
    private final Duration timeout;
    private final List<String> nodes;
    private final byte[] baselineSettings;

    /** Проверяет изолированные пути и узлы до запуска процессов. */
    private PortableParityHarness(String[] args) throws Exception {
        require(args.length == 12, "Expected copy output project tag nonce timeout and six nodes");
        require(System.getProperty("os.name", "").startsWith("Windows"), "Portable EXE requires Windows");
        copy = Path.of(args[0]).toRealPath(); output = Path.of(args[1]).toRealPath(); project = Path.of(args[2]).toRealPath();
        require(!output.startsWith(copy) && !copy.startsWith(output), "Output overlaps portable copy");
        require(!copy.startsWith(project), "Must launch an isolated copied distribution");
        for (String client : CLIENTS) require(!Files.exists(output.resolve(client)), "Output must be fresh");
        require(UiDump.class.getModule().isNamed() && UiDump.class.getModule().getName().equals("ru.cashprediction.core"),
                "Core must come from packaged module");
        require(ModuleLayer.boot().findModule("java.net.http").isPresent(), "Packaged HTTP module missing");
        tag = args[3]; nonce = UUID.fromString(args[4]).toString();
        require(Set.of("ascii", "cyrillic", "unicode").contains(tag), "Unknown path class");
        requirePathClass(copy, tag);
        int seconds = Integer.parseInt(args[5]); require(seconds > 0 && seconds <= 600, "Invalid timeout");
        timeout = Duration.ofSeconds(seconds);
        nodes = Arrays.stream(args).skip(6).map(RegistryNodeCleaner::requireSelftestNode).toList();
        require(new HashSet<>(nodes).size() == 6, "Registry nodes must be distinct");
        for (String node : nodes) require(!RegistryNodeCleaner.exists(node), "Registry node already exists");
        for (String client : CLIENTS) require(Files.isRegularFile(copy.resolve(EXES.get(client))), "Actual EXE absent");
        Path settings = copy.resolve("CashMemory/settings.md");
        baselineSettings = Files.exists(settings) ? Files.readAllBytes(settings) : null;
    }

    /** Вход PowerShell: ненулевой код при любой недостающей проверке; proof публикуется только после cleanup. */
    public static void main(String[] args) throws Exception {
        new PortableParityHarness(args).run();
    }

    /** Проверяет фактические символы пути, а не только переданный label трёх классов. */
    static void requirePathClass(Path path, String tag) {
        String parent = path.getParent().getFileName().toString();
        boolean matches = switch (tag) {
            case "ascii" -> path.toString().codePoints().allMatch(c -> c <= 127);
            case "cyrillic" -> parent.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC)
                    && parent.codePoints().anyMatch(Character::isWhitespace);
            case "unicode" -> parent.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK)
                    && parent.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
            default -> false;
        };
        require(matches, "Actual path does not match requested path class");
    }

    /** Использует существующую политику нормализации, golden-проекции и допусков без изменения ScenarioCollector. */
    private void run() throws Exception {
        var realBefore = RegistryNodeCleaner.snapshotRealSessionNodes();
        try {
            Path goldens = project.resolve("Golden");
            ParityPipeline.run(goldens, output.resolve("comparison"), CLIENTS, List.of(SCENARIO),
                    AllowedDiffs.parse(Files.readString(goldens.resolve("allowed-diffs.json"))), false,
                    this::collect).requireSuccess();
            for (int i = 0; i < CLIENTS.size(); i++) crashRestore(CLIENTS.get(i), nodes.get(i + 3));
        } finally {
            // collect/crashRestore закрывают процессы до удаления своих узлов.
            // Внешний PowerShell дополнительно контролирует процессы копии и очистку после таймаута стенда.
            PortableExeProcess.requireCopyStopped(copy);
            for (String node : nodes) {
                RegistryNodeCleaner.delete(node);
                require(!RegistryNodeCleaner.exists(node), "Registry node survived cleanup");
            }
            require(realBefore.equals(RegistryNodeCleaner.snapshotRealSessionNodes()), "Real session registry changed");
        }
        for (String client : CLIENTS) {
            String proof = "schema=1\nnonce=" + nonce + "\npathClass=" + tag + "\nclient=" + client
                    + "\nexeSHA256=" + sha(copy.resolve(EXES.get(client))) + "\nscenario=" + SCENARIO
                    + "\ncheckpoints=5\ncrash=true\nrestore=true\nregistryClean=true\nprocessesClean=true\n";
            Files.writeString(output.resolve(client + ".proof"), proof, StandardOpenOption.CREATE_NEW);
        }
        System.out.println("S5 actual EXE parity and crash/restore: " + tag + " / fx,swing,web");
    }

    /** Собирает все команды и checkpoints s02, не выдавая DONE с пропущенными шагами за успех. */
    private ParityPipeline.Collection collect(String client, String scenario, Path unused) throws Exception {
        require(SCENARIO.equals(scenario) && CLIENTS.contains(client), "Unexpected scenario request");
        PortableExeProcess.requireCopyStopped(copy);
        restoreBaselineSettings(copy, baselineSettings);
        String node = nodes.get(CLIENTS.indexOf(client));
        Path out = output.resolve(client).resolve("sample");
        var request = request(client, scenario, scenario, out, node, "none");
        try (var process = new PortableExeProcess(copy, copy.resolve(EXES.get(client)), request, timeout)) {
            process.await(() -> done(out), "SELFTEST DONE");
            validateLog(SelfTestScript.load(scenario), Files.readString(out.resolve("selftest.log")), true);
            var expected = new TreeSet<String>();
            for (var line : SelfTestScript.load(scenario).lines())
                if (line.command() instanceof SelfTestCommand.Dump dump) expected.add(dump.step());
            require(expected.size() == 5, "Unexpected s02 checkpoint contract");
            var actual = new TreeSet<String>();
            try (var files = Files.list(out.resolve(scenario))) {
                for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    UiDump dump = DumpTrees.read(Files.readString(file));
                    require(client.equals(dump.client()) && scenario.equals(dump.scenario()), "Foreign dump identity");
                    require(file.getFileName().toString().equals(dump.step() + ".json"), "Foreign dump step");
                    actual.add(dump.step());
                }
            }
            require(actual.equals(expected), "Missing or extra s02 checkpoints");
        }
        var result = new ParityPipeline.Collection(out.resolve(scenario), copy.resolve("CashMemory"), node);
        return result;
    }

    /** Проверяет фактический снимок с невалидным вводом до смерти и свежий снимок восстановленного процесса. */
    private void crashRestore(String client, String node) throws Exception {
        Path root = output.resolve(client).resolve("recovery"); Files.createDirectories(root);
        // Ввод и сравнения сохранённого draft: actual меню, фильтр и выделение тоже обязаны восстановиться.
        String beforeText = "today 2026-09-13\nkey Esc\nsample\nsize 1200 800\nperiod ALL\n"
                + "menu whatIf.income\nmenu whatIf.expense\nspinner whatIf.extra 7319\n"
                + "filtertype \"" + UiText.get("sample.rule.salary") + "\"\n"
                + "rowclick past@group\nselect r1@2026-10-05\nview CHART\n"
                + "menu tools.goal\nfill last target=\"450731\" extraSaving=\"bad731\"\n"
                + "shot observed\nsignal committed\n";
        String afterText = "shot observed\nsignal inspected\n";
        Path beforeScript = root.resolve("before.cps"), afterScript = root.resolve("after.cps");
        Files.writeString(beforeScript, beforeText, StandardOpenOption.CREATE_NEW);
        Files.writeString(afterScript, afterText, StandardOpenOption.CREATE_NEW);
        var env = AppEnvironment.from(LaunchOptions.parse("--home", copy.toString(), "--registry-node", node,
                "--today", LaunchRequest.PARITY_TODAY.toString()));
        SessionStore store = client.equals("web") ? MarkdownSessionStore.inCashMemory(copy.resolve("CashMemory")) : env.registryStore(client);
        require(store.isAvailable(), "Required recovery store unavailable");
        SessionSnapshot saved;
        SessionMarker killedMarker;
        UiDump before;
        Path beforeOut = root.resolve("before-out"), afterOut = root.resolve("after-out");
        try (var process = new PortableExeProcess(copy, copy.resolve(EXES.get(client)),
                request(client, "before", beforeScript.toString(), beforeOut, node, "none"), timeout)) {
            process.await(() -> Files.isRegularFile(beforeOut.resolve("committed")), "pre-crash barrier");
            validateLog(SelfTestScript.parse("before", beforeText), Files.readString(beforeOut.resolve("selftest.log")), false);
            before = observed(beforeOut, "before", client);
            PortableRestoreChecks.seeded(before);
            process.await(() -> committed(store, client) && ownedMarker(store, process) && storesAgree(env, client, store), "committed invalid fields and owned marker");
            saved = store.load().orElseThrow();
            killedMarker = store.readMarker().orElseThrow();
            if (!client.equals("web")) require(saved.equals(env.xmlStore(client).load().orElseThrow()), "Registry/XML snapshot mismatch");
            process.kill();
            require(store.readMarker().orElseThrow().isRunning(), "Forced death incorrectly marked clean");
            require(killedMarker.equals(store.readMarker().orElseThrow()), "Forced death rewrote running marker");
            require(saved.equals(store.load().orElseThrow()), "Committed state changed after death");
        }
        try (var process = new PortableExeProcess(copy, copy.resolve(EXES.get(client)),
                request(client, "after", afterScript.toString(), afterOut, node, "registry"), timeout)) {
            process.await(() -> Files.isRegularFile(afterOut.resolve("inspected")), "restored UI barrier");
            validateLog(SelfTestScript.parse("after", afterText), Files.readString(afterOut.resolve("selftest.log")), false);
            UiDump restored = observed(afterOut, "after", client);
            PortableRestoreChecks.seeded(restored);
            PortableRestoreChecks.rawEqual(before, restored);
            process.await(() -> committed(store, client) && fresh(store, saved) && ownedMarker(store, process)
                    && storesAgree(env, client, store), "fresh restored snapshot");
            require(store.readMarker().orElseThrow().startedAt().isAfter(killedMarker.startedAt()), "Restored process marker is not fresh");
            SessionSnapshot after = store.load().orElseThrow();
            PortableRestoreChecks.snapshotEqual(saved, after);
            if (!client.equals("web")) require(after.equals(env.xmlStore(client).load().orElseThrow()), "Restored registry/XML mismatch");
        }
    }

    /** Не принимает старый savedAt за запись после восстановления. */
    private static boolean fresh(SessionStore store, SessionSnapshot old) {
        try { return store.load().orElseThrow().savedAt().isAfter(old.savedAt()); } catch (Exception e) { return false; }
    }

    /** Ожидает фиксацию обоих desktop-хранилищ, не принимая промежуточное состояние фоновой записи. */
    private static boolean storesAgree(AppEnvironment environment, String client, SessionStore selected) {
        try { return client.equals("web") || selected.load().orElseThrow().equals(environment.xmlStore(client).load().orElseThrow()); }
        catch (Exception e) { return false; }
    }

    /** Проверяет running-маркер именно текущей JVM внутри копии. */
    private static boolean ownedMarker(SessionStore store, PortableExeProcess process) {
        try { var marker = store.readMarker().orElseThrow(); return marker.isRunning() && process.ownsPid(marker.pid()); }
        catch (Exception e) { return false; }
    }

    /** Проверяет содержимое снимка, а не только существование файла. */
    private static boolean committed(SessionStore store, String client) {
        try {
            var snapshot = store.load().orElseThrow();
            return snapshot.client().equals(client) && PortableRestoreChecks.seeded(snapshot);
        } catch (Exception e) { return false; }
    }

    /** Читает дамп с обязательной идентичностью клиента, сценария и контрольной точки. */
    private static UiDump observed(Path out, String scenario, String client) throws Exception {
        UiDump dump = DumpTrees.read(Files.readString(out.resolve(scenario).resolve("observed.raw.json")));
        require(dump.client().equals(client) && dump.scenario().equals(scenario) && dump.step().equals("observed"), "Recovery dump identity");
        return dump;
    }

    /** Строгий журнал повторяет правило ScenarioCollector: каждая строка сценария должна быть OK. */
    static void validateLog(SelfTestScript script, String contents, boolean done) {
        var expected = script.lines(); var actual = contents.lines().toList();
        int count = done ? expected.size() : expected.size() - 1;
        require(actual.size() == count + (done ? 1 : 0), "Incomplete or extra selftest log");
        for (int i = 0; i < count; i++) require(actual.get(i).equals("SELFTEST " + expected.get(i).number()
                + " OK " + expected.get(i).text()), "Failed, skipped or reordered selftest step");
        if (done) require(actual.getLast().equals("SELFTEST DONE"), "SELFTEST DONE missing");
        else require(expected.getLast().command() instanceof SelfTestCommand.Signal, "Expected blocking signal");
    }

    /** Наблюдает DONE только из собственного нового output. */
    private static boolean done(Path out) {
        try { return Files.readString(out.resolve("selftest.log")).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (java.io.IOException e) { return false; }
    }

    /** Собирает только существующие аргументы запуска; Web выбирает server через штатный RecoveryAnswer. */
    private LaunchRequest request(String client, String scenario, String script, Path out, String node, String recovery) {
        return new LaunchRequest(client, scenario, copy, node, LaunchRequest.PARITY_TODAY, script, out,
                List.of("--selftest-recovery", recovery), List.of());
    }

    /** Возвращает общий settings.md к одинаковому исходному состоянию перед каждым клиентом тестовой копии. */
    static void restoreBaselineSettings(Path copy, byte[] baseline) throws Exception {
        Path realCopy = copy.toRealPath();
        Path memory = realCopy.resolve("CashMemory");
        require(Files.isDirectory(memory) && memory.toRealPath().startsWith(realCopy), "Foreign CashMemory");
        Path settings = memory.resolve("settings.md");
        if (Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)
                    && settings.toRealPath().startsWith(memory.toRealPath()), "Foreign settings");
        }
        if (baseline == null) Files.deleteIfExists(settings);
        else Files.write(settings, baseline);
    }

    /** Связывает proof с содержимым фактически запущенного EXE. */
    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
