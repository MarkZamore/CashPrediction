package ru.cashprediction.core.ui.selftest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.ui.dump.UiDump;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет нормализацию настоящих аргументов запуска до сохранения дампа. */
class SelfTestRunnerEnvironmentTest {
    @TempDir Path home;

    /** Случайный узел скрывается, но принадлежность снимка клиенту не теряется. */
    @Test void explicitEnvironmentPreservesClientSuffixAndNormalizesHome() throws Exception {
        String prefix = "ru/cashprediction/selftest/runner-environment";
        var environment = AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry-node", prefix), new Properties()));
        for (var kind : List.of(ClientKind.FX, ClientKind.SWING)) {
            String text = environment.cashMemory() + " " + prefix + "/" + kind.snapshotClient();
            UiDriver driver = new UiDriver() {
                /** Возвращает профиль проверяемого клиента. */
                public ClientKind client() { return kind; }
                /** Сценарий содержит только снятие дампа. */
                public void execute(SelfTestCommand command) { throw new AssertionError(command); }
                /** Стенд без очереди событий. */
                public void awaitIdle(Duration timeout) { }
                /** Возвращает исходное наблюдение с путём и суффиксом клиента. */
                public UiDump dump(String step) {
                    return new UiDump(1, kind.snapshotClient(), "", step, null, List.of(), null, null, null, null,
                            List.of(new UiDump.Segment("session", text, "", "text.muted", true)),
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
                }
                /** Снимок не используется этой проверкой. */
                public byte[] screenshot(String step) { throw new AssertionError(step); }
            };
            Path output = home.resolve(kind.snapshotClient());
            assertTrue(new SelfTestRunner(driver, output, environment)
                    .run(SelfTestScript.parse("environment", "dump state")).ok());
            String json = Files.readString(output.resolve("environment/state.json"));
            assertTrue(json.contains("<CashMemory> <node>/" + kind.snapshotClient()), json);
            assertFalse(json.contains(prefix), json);
            assertFalse(json.contains(home.toString().replace("\\", "\\\\")), json);
        }
    }
}
