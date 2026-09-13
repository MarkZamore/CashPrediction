package ru.cashprediction.parity.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.LaunchOptions;

/**
 * Командная строка {@link ClientLauncher} без запуска процессов: опции §6.3, module path и имена аргументов §3.8.
 *
 * <p>Главная проверка — «имена аргументов не придуманы»: аргументы разбираются настоящим
 * {@link LaunchOptions#parse(List, Properties)} ядра, и предупреждений о неизвестных аргументах быть не должно.</p>
 */
class ClientLauncherCommandTest {

    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "parity-layout", "reactor");
    private static final Path REPO = Path.of(System.getProperty("java.io.tmpdir"), "parity-layout", "m2");
    private static final String NODE = "ru/cashprediction/selftest/0b7c2f1e-4a1d-4c55-9b0e-2f3a4b5c6d7e";

    private static ReactorLayout layout() {
        return new ReactorLayout(ROOT, "1.0.0", REPO, "25.0.4", ROOT.resolve("ui-parity").resolve("target"));
    }

    @Test
    void swingCommandHasStandardOptionsModulePathAndScenarioArguments() {
        ReactorLayout layout = layout();
        LaunchRequest request = LaunchRequest.forScenario(layout.parityRoot(), "swing", "s02-sample-table", NODE);
        List<String> cmd = ClientLauncher.command(ClientTarget.swing(layout), request);

        assertEquals(ClientLauncher.javaExecutable().toString(), cmd.get(0));
        assertEquals(List.of("-Dglass.win.uiScale=1", "-Dsun.java2d.uiScale=1", "-Duser.language=ru", "-XX:-UsePerfData"),
                cmd.subList(1, 5));
        int mp = cmd.indexOf("--module-path");
        assertEquals(layout.moduleJar("ui-swing") + File.pathSeparator + layout.moduleJar("core"), cmd.get(mp + 1));
        int module = cmd.indexOf("--module");
        assertEquals("ru.cashprediction.swing/ru.cashprediction.swing.SwingMain", cmd.get(module + 1));

        Path home = layout.parityRoot().resolve("swing").resolve("s02-sample-table");
        assertEquals(List.of("--home", home.toString(), "--registry-node", NODE, "--today", "2026-09-13",
                        "--selftest", "s02-sample-table", "--selftest-out", home.resolve("selftest-out").toString()),
                cmd.subList(module + 2, cmd.size()));
        assertEquals(home.getParent(), request.logDirectory());
        // Прежний интерфейс не разбирает аргументы: изоляция доходит до него только системными свойствами (L12).
        assertEquals(List.of("-Dcashprediction.home=" + home, "-Dcashprediction.registry.node=" + NODE),
                cmd.subList(mp - 2, mp));
    }

    @Test
    void isolationPropertiesAgreeWithArgumentsForCoreLaunchOptions() {
        ReactorLayout layout = layout();
        LaunchRequest request = LaunchRequest.forScenario(layout.parityRoot(), "fx", "s03-chart", NODE);
        List<String> cmd = ClientLauncher.command(ClientTarget.fx(layout), request);
        Properties properties = new Properties();
        for (String option : cmd.subList(1, cmd.indexOf("--module-path"))) {
            if (option.startsWith("-D")) {
                int eq = option.indexOf('=');
                properties.setProperty(option.substring(2, eq), option.substring(eq + 1));
            }
        }

        LaunchOptions fromProperties = LaunchOptions.parse(List.of(), properties);
        LaunchOptions fromBoth = LaunchOptions.parse(ClientLauncher.applicationArguments(ClientTarget.fx(layout), request),
                properties);

        assertEquals(request.home(), fromProperties.home());
        assertEquals(NODE, fromProperties.registryNode());
        assertEquals(fromProperties.home(), fromBoth.home());
        assertEquals(fromProperties.registryNode(), fromBoth.registryNode());
        assertEquals(List.of(), fromBoth.warnings());
    }

    @Test
    void legacyCapableClientWithoutRegistryNodeIsRefusedUnlessCoreUi() {
        ReactorLayout layout = layout();
        LaunchRequest memory = new LaunchRequest("swing", "memory", ROOT.resolve("home"), null, null, null, null, null,
                List.of(ClientLauncher.ARG_REGISTRY, "memory"), List.of());

        for (ClientTarget target : List.of(ClientTarget.fx(layout), ClientTarget.swing(layout), ClientTarget.web(layout))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ClientLauncher.command(target, memory), target.client());
            assertTrue(e.getMessage().contains("legacy"), e.getMessage());
        }

        List<String> core = ClientLauncher.command(ClientTarget.swing(layout), memory.withUi("core"));
        assertTrue(core.contains("-Dcashprediction.home=" + memory.home()), core.toString());
        assertTrue(core.stream().noneMatch(option -> option.startsWith("-Dcashprediction.registry.node")), core.toString());
        List<String> dummy = ClientLauncher.command(ClientTarget.dummy(ROOT.resolve("d.jar"), ROOT.resolve("c.jar")), memory);
        assertTrue(dummy.contains("--registry"), "the dummy client parses core LaunchOptions and is not refused");
    }

    @Test
    void everyApplicationArgumentIsKnownToCoreLaunchOptions() {
        ReactorLayout layout = layout();
        LaunchRequest request = LaunchRequest.forScenario(layout.parityRoot(), "web", "s17-recovery-dialog", NODE)
                .withUi("core")
                .withExtraArguments(List.of(ClientLauncher.ARG_SELFTEST_RECOVERY, "xml"));
        List<String> args = ClientLauncher.applicationArguments(ClientTarget.web(layout), request);

        LaunchOptions options = LaunchOptions.parse(args, new Properties());

        assertEquals(List.of(), options.warnings(), "every launch argument must exist in architecture §3.8");
        assertEquals(request.home(), options.home());
        assertEquals(NODE, options.registryNode());
        assertEquals(LocalDate.of(2026, 9, 13), options.today());
        assertEquals("s17-recovery-dialog", options.selftest());
        assertEquals(request.selftestOut(), options.selftestOut());
        assertEquals(LaunchOptions.UiMode.CORE, options.ui());
        assertEquals(LaunchOptions.RecoveryAnswer.XML, options.selftestRecovery());
        assertTrue(options.testApi() && options.noBrowser() && options.noWindow(), "web runs headless with the test API");
    }

    @Test
    void registryMemoryArgumentIsKnownToCoreLaunchOptions() {
        LaunchRequest request = new LaunchRequest("swing", "memory", ROOT.resolve("home"), null, null, null, null, null,
                List.of(ClientLauncher.ARG_REGISTRY, "memory"), List.of());
        List<String> args = ClientLauncher.applicationArguments(ClientTarget.swing(layout()), request);

        LaunchOptions options = LaunchOptions.parse(args, new Properties());

        assertEquals(List.of(), options.warnings());
        assertTrue(options.registryMemory());
        assertEquals(List.of("--home", ROOT.resolve("home").toAbsolutePath().normalize().toString(), "--registry", "memory"),
                args);
    }

    @Test
    void fxModulePathUsesReactorJarsAndWindowsJavafxJars() {
        ReactorLayout layout = layout();
        ClientTarget fx = ClientTarget.fx(layout);

        Path openjfx = REPO.toAbsolutePath().normalize().resolve("org").resolve("openjfx");
        assertEquals(List.of(layout.moduleJar("ui-fx"), layout.moduleJar("core"),
                        openjfx.resolve("javafx-base/25.0.4/javafx-base-25.0.4-win.jar"),
                        openjfx.resolve("javafx-graphics/25.0.4/javafx-graphics-25.0.4-win.jar"),
                        openjfx.resolve("javafx-controls/25.0.4/javafx-controls-25.0.4-win.jar")),
                fx.modulePath());
        assertEquals(ROOT.toAbsolutePath().normalize().resolve("ui-fx/target/cashprediction-ui-fx-1.0.0.jar"),
                layout.moduleJar("ui-fx"));
        assertEquals("ru.cashprediction.fx/ru.cashprediction.fx.FxMain", fx.mainModule() + "/" + fx.mainClass());
    }

    @Test
    void dummyTargetResolvesTheCoreModuleThroughAddModules() {
        ClientTarget dummy = ClientTarget.dummy(ROOT.resolve("dummy.jar"), ROOT.resolve("core.jar"));
        LaunchRequest request = LaunchRequest.forScenario(layout().parityRoot(), "dummy", "launcher-smoke", NODE);

        List<String> cmd = ClientLauncher.command(dummy, request);

        int add = cmd.indexOf("--add-modules");
        assertEquals("ALL-MODULE-PATH", cmd.get(add + 1));
        assertTrue(add < cmd.indexOf("--module"), "--add-modules must precede --module");
        assertEquals(DummyClientJar.MODULE_NAME + "/" + DummyClientJar.MAIN_CLASS, cmd.get(cmd.indexOf("--module") + 1));
    }

    @Test
    void launchRefusesMissingModulePathEntries() {
        LaunchRequest request = LaunchRequest.forScenario(layout().parityRoot(), "swing", "missing", NODE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ClientLauncher.launch(ClientTarget.swing(layout()), request));

        assertTrue(e.getMessage().contains("cashprediction-ui-swing-1.0.0.jar"), e.getMessage());
    }

    @Test
    void missingVersionPropertyGivesAHelpfulMessage() {
        ReactorLayout noVersion = new ReactorLayout(ROOT, null, REPO, null, ROOT.resolve("target"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> noVersion.moduleJar("core"));

        assertTrue(e.getMessage().contains(ReactorLayout.PROP_VERSION), e.getMessage());
        assertThrows(IllegalStateException.class, noVersion::javafxJars);
    }
}
