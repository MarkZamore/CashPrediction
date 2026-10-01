package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет маршрутизацию каталога, канонизацию прежних полей и точные причины пропуска. */
class FormCatalogTest {
    @TempDir Path home;

    /** Каждая обычная форма получает исходную модальность, владельца, страницу и геометрию. */
    @Test void allNonAlertTypesAndPurposes() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.fx("25"));
        for (WindowType type : WindowType.values()) {
            if (type == WindowType.ALERT) continue;
            Map<String, String> context = switch (type) {
                case TEXT_INPUT -> Map.of("purpose", "rename");
                case CHOICE -> Map.of("purpose", "currency");
                case RULE_EDITOR -> Map.of("mode", "edit", "ruleId", "r1");
                case ONE_TIME_EDITOR -> Map.of("mode", "edit", "txId", "t1");
                case ADJUSTMENT_EDITOR, QUICK_EDIT_POPUP -> Map.of("ruleId", "r1", "originalDate", "2026-10-05");
                case NEW_PLAN_WIZARD -> Map.of("page", "2");
                default -> Map.of();
            };
            WindowState state = new WindowState("w7", type, type.defaultModal(), "w3",
                    new WindowBounds(200, 100, 580, 400), context, Map.of());
            FormRequest request = FormCatalog.forRestore(state, fake.state());
            assertEquals(state, request.restored());
            assertEquals(state.modal(), request.modal());
            FormContext formContext = new FormContext(state.id(), state.ownerId(), request.context(), fake.state());
            assertEquals(type, request.logic().spec(formContext).windowType());
        }
        for (String purpose : Set.of("rename", "reconcile", "customMonths", "customCurrency")) {
            FormRequest request = FormCatalog.forRestore(window(WindowType.TEXT_INPUT, Map.of("purpose", purpose), Map.of()), fake.state());
            assertEquals(purpose, request.logic().spec(new FormContext("w1", "main", request.context(), fake.state())).purpose());
        }
        assertNotNull(FormCatalog.forRestore(window(WindowType.CHOICE, Map.of("purpose", "openPlan"), Map.of()), fake.state()));
    }

    /** Прежние подписи и суммы проходят FieldCodec, неизвестное поле и некорректный текст сохраняются. */
    @Test void acceptsLegacyFields() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        Map<String, String> fields = Map.of("amount", "95 000", "kind", "income", "extraField", "raw text");
        WindowState state = window(WindowType.RULE_EDITOR, Map.of("mode", "create"), fields);
        FormRequest request = FormCatalog.forRestore(state, fake.state());
        for (var entry : fields.entrySet()) assertEquals(FieldCodec.acceptLegacy(state.type(), entry.getKey(), entry.getValue()),
                request.restored().fields().get(entry.getKey()));
        assertEquals("95000,00", request.restored().fields().get("amount"));
        WindowState invalid = window(WindowType.RULE_EDITOR, Map.of("mode", "create"), Map.of("amount", "bad amount"));
        assertEquals("bad amount", FormCatalog.forRestore(invalid, fake.state()).restored().fields().get("amount"));
    }

    /** Восстановленный горизонт использует проверку общего потока, включая переполнение ввода. */
    @Test void restoredCustomMonthsValidatesLikeViewFlow() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        FormRequest request = FormCatalog.forRestore(window(WindowType.TEXT_INPUT,
                Map.of("purpose", "customMonths"), Map.of()), fake.state());
        FormContext context = new FormContext("w1", "main", request.context(), fake.state());
        for (String invalid : java.util.List.of("0", "601", "bad", "999999999999999999999999999")) {
            FormState state = new FormState(0, Map.of("value", invalid));
            FormView view = request.logic().evaluate(state, context);
            assertEquals(Problem.Severity.ERROR, view.problem().severity(), invalid);
            assertEquals(ButtonView.DISABLED, view.buttons().get("apply"), invalid);
            assertInstanceOf(FormOutcome.Stay.class, request.logic().onButton("apply", state, context));
        }
        FormState valid = new FormState(0, Map.of("value", "600"));
        assertEquals(Problem.NONE, request.logic().evaluate(valid, context).problem());
        assertEquals(600, assertInstanceOf(FormOutcome.Close.class,
                request.logic().onButton("apply", valid, context)).result());
    }

    /** Все назначения подтверждений восстанавливают цель и стандартную кнопку из общего каталога. */
    @Test void allAlertPurposesAreCatalogued() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.fx("25"));
        fake.document.replace(fake.document.plan().withStart(CaptureContext.TODAY.minusMonths(1),
                fake.document.plan().startBalance()), null, true, java.util.List.of());
        fake.document.setViewState(fake.document.viewState().withWhatIf(
                ru.cashprediction.core.forecast.WhatIf.ofPercent(-10, 10, ru.cashprediction.core.model.Money.ZERO)));
        for (String purpose : Set.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf", "clearSnapshots")) {
            String target = "deleteRule".equals(purpose) ? "r1" : "deleteOneTime".equals(purpose) ? "t1" : "";
            WindowState state = window(WindowType.ALERT, Map.of("purpose", purpose, "targetId", target), Map.of());
            FormRequest request = FormCatalog.forRestore(state, fake.state());
            assertEquals(state, request.restored());
            FormSpec spec = request.logic().spec(new FormContext("w1", "main", request.context(), fake.state()));
            assertEquals(WindowType.ALERT, spec.windowType());
            assertEquals(purpose, spec.purpose());
            assertTrue(spec.restorable());
            assertTrue(spec.buttons().stream().anyMatch(button -> button.id().equals(spec.defaultButtonId())));
        }
        assertEquals(UiText.get("restore.warn.targetGone"),
                assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                        window(WindowType.ALERT, Map.of("purpose", "deleteRule", "targetId", "gone"), Map.of()), fake.state())).getMessage());
    }

    /** Неизвестное назначение, исчезнувшая цель и скрытая строка различаются точными текстами. */
    @Test void rejectsWithSpecifiedWarnings() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        assertEquals(UiText.get("restore.warn.unknownPurpose", "future"),
                assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                        window(WindowType.TEXT_INPUT, Map.of("purpose", "future"), Map.of()), fake.state())).getMessage());
        assertEquals(UiText.get("restore.warn.targetGone"),
                assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                        window(WindowType.RULE_EDITOR, Map.of("mode", "edit", "ruleId", "gone"), Map.of()), fake.state())).getMessage());
        assertEquals(UiText.get("restore.warn.noContext"),
                assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                        window(WindowType.ADJUSTMENT_EDITOR, Map.of(), Map.of()), fake.state())).getMessage());
        fake.document.setViewState(fake.document.viewState().withShowIncome(false));
        assertEquals(UiText.get("restore.warn.rowHidden", "r1", "05.10.2026"),
                assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(window(WindowType.QUICK_EDIT_POPUP,
                        Map.of("ruleId", "r1", "originalDate", "2026-10-05"), Map.of()), fake.state())).getMessage());
    }

    /** Создаёт окно с обычными идентификаторами и без привязки к инструменту интерфейса. */
    private static WindowState window(WindowType type, Map<String, String> context, Map<String, String> fields) {
        return new WindowState("w1", type, type.defaultModal(), "main", null, context, fields);
    }
}
