package ru.cashprediction.parity.e2e.interaction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.text.UiText;

/** Настоящие клиенты восстанавливают несохранённый план и не затирают нечитаемый план до решения пользователя. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class UnsavedPlanRestoreE2ETest {
    /** Проверяет реальные правки, аварийное восстановление, отмену выхода и намеренный отказ от сохранения. */
    @ParameterizedTest @ValueSource(strings = {"fx", "swing"})
    void dirtyPlanSurvivesCrashAndCancelledExit(String client) throws Exception {
        try (var test = new InteractionSession(client)) {
            var a = test.launch("dirty", "key Esc\nsample\nmenu edit.planSettings\nfill last cushion=\"70001\"\nok last\n"
                    + "filtertype dirty-sentinel\nshot dirty\nsignal hold\n");
            test.barrier(a, "hold");
            var dirty = test.committed(a, s -> s.plan().dirty() && s.main().filterText().equals("dirty-sentinel"));
            assertFalse(dirty.plan().markdown().isBlank());
            test.crash(a);
            var b = test.launch("restore-cancel-discard", "shot recovery\n" + test.restoreXml(dirty)
                    + "shot restored\nexit\nshot exit-question\n"
                    + InteractionSession.answer(UiText.get("button.cancel"))
                    + "shot cancelled\nsignal inspect\nexit\n"
                    + InteractionSession.answer(UiText.get("button.dontSave")));
            test.barrier(b, "inspect");
            test.alert(b, "recovery", "crashRecovery");
            test.alert(b, "exit-question", "unsavedChanges");
            assertTrue(test.dump(b, "restored").toString().contains("dirty-sentinel"));
            test.committed(b, s -> s.plan().equals(dirty.plan()));
            assertTrue(test.xml.readMarker().orElseThrow().isRunning(), "Cancel must not mark session closed");
            test.release(b, "inspect");
            assertTrue(b.process().waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, b.process().exitValue());
            test.terminalPrefix(b);
            assertFalse(test.xml.readMarker().orElseThrow().isRunning());
            assertFalse(test.registry.readMarker().orElseThrow().isRunning());
            assertFalse(test.xml.load().orElseThrow().plan().dirty(), "Discarded dirty payload must not survive clean exit");
        }
    }

    /** Реальная ошибка записи в непустой каталог не закрывает клиент; повторное сохранение завершает выход. */
    @ParameterizedTest @ValueSource(strings = {"fx", "swing"})
    void failedExitSavePreservesDirtyDataAndRetrySaves(String client) throws Exception {
        try (var test = new InteractionSession(client)) {
            var app = test.launch("save-failure", "key Esc\nsample\nmenu edit.planSettings\nfill last cushion=\"70003\"\nok last\n"
                    + "filtertype exit-save-sentinel\nshot dirty\nsignal prepare\nexit\n"
                    + InteractionSession.answer(UiText.get("button.save")) + "shot write-error\n"
                    + InteractionSession.answer(UiText.get("button.ok")) + "shot alive-after-error\nsignal retry\nexit\n"
                    + InteractionSession.answer(UiText.get("button.save")));
            test.barrier(app, "prepare");
            var dirty = test.committed(app, s -> s.plan().dirty() && s.main().filterText().equals("exit-save-sentinel"));
            var plan = ru.cashprediction.core.markdown.PlanMarkdownReader.read(dirty.plan().markdown(), "fallback",
                    ru.cashprediction.parity.launch.LaunchRequest.PARITY_TODAY).plan();
            var target = new ru.cashprediction.core.io.PlanRepository(test.home.resolve("CashMemory")).pathFor(plan.name());
            assertTrue(target.toAbsolutePath().normalize().startsWith(test.home));
            Files.createDirectory(target);
            var blocker = target.resolve("test-owned-blocker");
            Files.writeString(blocker, "owned by interaction E2E");
            test.release(app, "prepare");
            test.barrier(app, "retry");
            // Каталог не является версией файла плана: нельзя предлагать его перезапись.
            // Проверяем немедленный отказ, сохранность данных и настоящее повторное сохранение.
            var failure = test.alert(app, "write-error", "err.save");
            assertFalse(failure.toString().contains("overwriteOnFirstSave"));
            test.committed(app, s -> s.plan().equals(dirty.plan()));
            assertTrue(test.xml.readMarker().orElseThrow().isRunning());
            assertEquals("owned by interaction E2E", Files.readString(blocker));
            // Только два точно созданных тестом объекта, без рекурсивного удаления данных приложения.
            Files.delete(blocker);
            Files.delete(target);
            test.release(app, "retry");
            assertTrue(app.process().waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, app.process().exitValue());
            test.terminalPrefix(app);
            assertEquals(dirty.plan().markdown(), Files.readString(target));
            assertFalse(test.xml.readMarker().orElseThrow().isRunning());
            assertFalse(test.registry.readMarker().orElseThrow().isRunning());
        }
    }

    /** Меняет только Markdown внутри корректно закодированного аварийного снимка и проверяет явное разрешение записи. */
    @ParameterizedTest @ValueSource(strings = {"fx", "swing"})
    void malformedDirtyPayloadIsAccessibleUntilIntentionalSkip(String client) throws Exception {
        try (var test = new InteractionSession(client)) {
            var a = test.launch("source", "key Esc\nsample\nfiltertype malformed-source\nshot source\nsignal hold\n");
            test.barrier(a, "hold");
            var source = test.committed(a, s -> s.main().filterText().equals("malformed-source"));
            var marker = test.xml.readMarker().orElseThrow();
            test.crash(a);
            String payload = "unreadable-plan-sentinel\nraw payload <&> кириллица\n";
            var malformed = new SessionSnapshot(source.schemaVersion(), source.savedAt(), source.client(), source.main(),
                    PlanState.dirty(payload), java.util.List.of());
            test.xml.save(malformed);
            test.registry.save(malformed);
            assertEquals(marker, test.xml.readMarker().orElseThrow());
            assertEquals(malformed, test.xml.load().orElseThrow());
            assertEquals(malformed, test.registry.load().orElseThrow());
            byte[] xml = Files.readAllBytes(test.xml.file());
            var b = test.launch("malformed-restore", "shot recovery\n" + test.restoreXml(malformed)
                    + "shot report\n" + InteractionSession.answer(UiText.get("button.ok"))
                    + "shot preserve\nsignal inspect\n"
                    + InteractionSession.answer(UiText.get("button.skip"))
                    + "shot resolved\nsignal resolved\n");
            test.barrier(b, "inspect");
            var report = test.alert(b, "report", "restoreReport");
            assertTrue(report.get("details").toString().contains(RestoreCoordinator.RECORDER_NOT_STARTED));
            assertEquals(payload, test.alert(b, "preserve", "recorderNotStarted").get("details"));
            assertArrayEquals(xml, Files.readAllBytes(test.xml.file()), "Unresolved recovery must not overwrite original encoding");
            assertEquals(malformed, test.registry.load().orElseThrow());
            assertEquals(marker, test.registry.readMarker().orElseThrow());
            test.release(b, "inspect");
            test.barrier(b, "resolved");
            test.committed(b, s -> !s.plan().markdown().equals(payload));
            assertEquals(b.pid(), test.xml.readMarker().orElseThrow().pid());
        }
    }
}
