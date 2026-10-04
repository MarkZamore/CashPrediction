package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.PlanSettingsForm;

/** По явному включению проверяет настоящий переход фокуса, снимки и восстановление числового поля. */
@EnabledIfSystemProperty(named = "cashprediction.swing.spinnerFocus", matches = "true")
@Isolated("Native desktop focus belongs to one test at a time")
class SwingSpinnerFocusTest {
    @TempDir Path directory;

    /** Ошибочный ввод остаётся в форме и снимке после фокуса, смены диапазона и восстановления окна. */
    @Test void invalidRawSurvivesFocusRangeChangeAndSnapshotRestoration() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture[] fixture = new Fixture[1];
        Robot robot = new Robot();
        try (InputTrace trace = new InputTrace()) {
            SwingUtilities.invokeAndWait(() -> fixture[0] = create(null, trace));
            for (String raw : new String[]{"invalid", "", "-", "601"}) {
                focus(robot, fixture[0].editor());
                SwingUtilities.invokeAndWait(() -> {
                    trace.stage("before-set", raw);
                    assertTrue(fixture[0].editor().isFocusOwner(), trace::diagnostic);
                    fixture[0].editor().setText(raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                    // Меняем диапазон до отложенной отправки документа: локальная правка ещё не попала в ядро.
                    fixture[0].session.fieldChanged("horizonKind", "YEARS", true, ++fixture[0].form.clientRev);
                    trace.stage("after-range-before-document-send", raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                });
                // Маркер EDT стоит после отложенной отправки документа и отделяет её от настоящего focusLost.
                SwingUtilities.invokeAndWait(() -> {
                    trace.stage("after-document-send-before-blur", raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                    assertEquals(raw, fixture[0].session.state().value("horizonValue"), trace::diagnostic);
                    assertEquals(fixture[0].form.clientRev, fixture[0].session.lastClientRev(), trace::diagnostic);
                    trace.assertNoNativeInput();
                });
                focus(robot, fixture[0].form.buttons.get("cancel"));
                WindowState[] snapshot = new WindowState[1];
                SwingUtilities.invokeAndWait(() -> {
                    trace.stage("after-blur", raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                    trace.assertNoNativeInput();
                    assertEquals(raw, fixture[0].session.state().value("horizonValue"), trace::diagnostic);
                    assertFalse(fixture[0].form.buttons.get("ok").isEnabled(), trace::diagnostic);
                    snapshot[0] = fixture[0].session.captureState();
                    assertEquals(raw, snapshot[0].fields().get("horizonValue"), trace::diagnostic);
                    fixture[0].port.exit(ExitKind.CLEAN, 0);
                    fixture[0] = create(snapshot[0], trace);
                    trace.stage("after-restore", raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                });
                focus(robot, fixture[0].editor());
                focus(robot, fixture[0].form.buttons.get("cancel"));
                SwingUtilities.invokeAndWait(() -> {
                    trace.stage("after-restored-blur", raw);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                    trace.assertNoNativeInput();
                    assertEquals(raw, fixture[0].session.captureState().fields().get("horizonValue"), trace::diagnostic);
                    assertFalse(fixture[0].form.buttons.get("ok").isEnabled(), trace::diagnostic);
                    fixture[0].session.fieldChanged("horizonKind", "MONTHS", true, ++fixture[0].form.clientRev);
                    assertEquals(raw, fixture[0].binding().raw(), trace::diagnostic);
                });
            }
        } finally {
            if (fixture[0] != null) SwingUtilities.invokeAndWait(() -> fixture[0].port.exit(ExitKind.CLEAN, 0));
        }
    }

    /** Корректное число подтверждается на потере фокуса, а ядро немедленно получает результат каждой стрелки. */
    @Test void validBlurAndArrowStepsReachCoreAndSnapshot() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture[] fixture = new Fixture[1];
        Robot robot = new Robot();
        try (InputTrace trace = new InputTrace()) {
            SwingUtilities.invokeAndWait(() -> fixture[0] = create(null, trace));
            focus(robot, fixture[0].editor());
            SwingUtilities.invokeAndWait(() -> fixture[0].editor().setText("24"));
            focus(robot, fixture[0].form.buttons.get("cancel"));
            SwingUtilities.invokeAndWait(() -> {
                trace.stage("valid-blur", "24");
                trace.assertNoNativeInput();
                assertEquals(24L, fixture[0].spinner().getValue(), trace::diagnostic);
                assertEquals("24", fixture[0].session.state().value("horizonValue"), trace::diagnostic);
            });
            focus(robot, fixture[0].editor());
            SwingUtilities.invokeAndWait(() -> {
                trace.stage("arrow-steps", "30");
                trace.assertNoNativeInput();
                fixture[0].editor().setText("30");
                SwingSpinnerEditorTest.arrow(fixture[0].spinner(), "Spinner.nextButton").doClick(0);
                assertEquals("31", fixture[0].binding().raw(), trace::diagnostic);
                assertEquals("31", fixture[0].session.state().value("horizonValue"), trace::diagnostic);
                SwingSpinnerEditorTest.arrow(fixture[0].spinner(), "Spinner.previousButton").doClick(0);
                assertEquals("30", fixture[0].binding().raw(), trace::diagnostic);
                assertEquals("30", fixture[0].session.captureState().fields().get("horizonValue"), trace::diagnostic);
            });
            focus(robot, fixture[0].form.buttons.get("cancel"));
            SwingUtilities.invokeAndWait(() -> {
                trace.assertNoNativeInput();
                trace.stage("after-arrow-blur", "30");
                assertEquals("30", fixture[0].session.state().value("horizonValue"), trace::diagnostic);
                assertTrue(fixture[0].form.buttons.get("ok").isEnabled(), trace::diagnostic);
            });
        } finally {
            if (fixture[0] != null) SwingUtilities.invokeAndWait(() -> fixture[0].port.exit(ExitKind.CLEAN, 0));
        }
    }

    /** Создаёт только форму с памятью вместо реестра; восстановление проходит через настоящий FormSession. */
    private Fixture create(WindowState restored, InputTrace trace) {
        SwingLook.install();
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory",
                "--today", "2026-09-13", "--selftest", "spinner-focus"));
        var port = new SwingUiPort(environment);
        var app = new AppController(port, environment); port.bind(app);
        var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class},
                (proxy, method, args) -> null);
        var session = new FormSession(WindowType.PLAN_SETTINGS, false, new PlanSettingsForm(),
                new FormContext("w1", "main", Map.of(), app.state()), host);
        if (restored != null) session.applyState(restored);
        // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
        var form = new SwingFormDialog(port, session, session.spec(), session.view(), Placement.centered("main"));
        // Тест переводит модальное окно в немодальное, чтобы управлять настоящим фокусом без вложенного цикла EDT.
        form.dialog.setModalityType(Dialog.ModalityType.MODELESS);
        session.attach(form); port.forms.put("w1", form);
        Fixture fixture = new Fixture(port, session, form);
        trace.watch(fixture);
        form.dialog.setVisible(true);
        return fixture;
    }

    /** Дожидается наблюдаемого владельца фокуса; искусственный FocusEvent не проверяет поведение NumberEditor. */
    private static void focus(Robot robot, Component target) throws Exception {
        robot.waitForIdle();
        SwingUtilities.invokeAndWait(() -> target.requestFocusInWindow());
        AtomicBoolean focused = new AtomicBoolean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            robot.waitForIdle();
            SwingUtilities.invokeAndWait(() -> focused.set(target.isFocusOwner()));
            if (focused.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        SwingUtilities.invokeAndWait(() -> fail("Focus did not reach " + target.getClass().getSimpleName()
                + "; owner=" + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
    }

    /** Записывает ввод и источник изменений, не поглощая события и не исправляя проверяемый текст. */
    private static final class InputTrace implements AWTEventListener, AutoCloseable {
        private final ArrayDeque<String> events = new ArrayDeque<>();
        private Fixture fixture;
        private String stage = "create";
        private String expected = "";
        private String nativeInput;

        /** Слушатель действует только во время теста и снимается даже при ошибке проверки. */
        InputTrace() {
            Toolkit.getDefaultToolkit().addAWTEventListener(this,
                    AWTEvent.KEY_EVENT_MASK | AWTEvent.INPUT_METHOD_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
        }

        /** Подключает наблюдение также к новому редактору после восстановления снимка. */
        void watch(Fixture next) {
            fixture = next;
            next.editor().getDocument().addDocumentListener(new DocumentListener() {
                /** Записывает вставку вместе со стеком, отличающим setText от клавиатуры и форматтера. */
                @Override public void insertUpdate(DocumentEvent event) { changed(event); }
                /** Записывает удаление без вмешательства в документ. */
                @Override public void removeUpdate(DocumentEvent event) { changed(event); }
                /** Записывает изменение атрибутов документа. */
                @Override public void changedUpdate(DocumentEvent event) { changed(event); }
                private void changed(DocumentEvent event) {
                    StringBuilder origin = new StringBuilder();
                    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                        if (frame.getClassName().startsWith("javax.swing.")
                                || frame.getClassName().startsWith("ru.cashprediction.")) {
                            if (origin.length() > 0) origin.append(" <- ");
                            origin.append(frame);
                            if (origin.length() > 6000) break;
                        }
                    }
                    AWTEvent current = java.awt.EventQueue.getCurrentEvent();
                    record("document " + event.getType() + " raw=" + codePoints(next.binding().raw())
                            + " currentEvent=" + (current == null ? "none" : current.getClass().getName() + "/" + current.getID())
                            + " origin=" + origin);
                }
            });
        }

        /** Указывает границу проверки, чтобы отчёт показывал первую фазу расхождения. */
        void stage(String next, String raw) { stage = next; expected = raw; record("stage " + next); }

        /** Отмечает посторонний ввод: этот тест использует Robot только для ожидания очереди событий. */
        @Override public void eventDispatched(AWTEvent event) {
            if (fixture == null || !(event.getSource() instanceof Component source)
                    || !(source == fixture.form.dialog || SwingUtilities.isDescendingFrom(source, fixture.form.dialog))) return;
            if (event instanceof KeyEvent key) {
                String detail = "key id=" + key.getID() + " code=" + key.getKeyCode()
                        + " char=" + codePoints(String.valueOf(key.getKeyChar())) + " when=" + key.getWhen();
                if (nativeInput == null) nativeInput = detail;
                record(detail);
            } else if (event instanceof InputMethodEvent input) {
                StringBuilder text = new StringBuilder();
                if (input.getText() != null) {
                    var iterator = input.getText();
                    for (char ch = iterator.first(); ch != java.text.CharacterIterator.DONE; ch = iterator.next()) text.append(ch);
                }
                String detail = "input-method id=" + input.getID() + " committed=" + input.getCommittedCharacterCount()
                        + " text=" + codePoints(text.toString());
                if (nativeInput == null && input.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED && !text.isEmpty()) nativeInput = detail;
                record(detail);
            } else if (event instanceof FocusEvent focus) {
                record("focus id=" + focus.getID() + " temporary=" + focus.isTemporary()
                        + " cause=" + focus.getCause() + " source=" + source.getClass().getSimpleName());
            }
        }

        /** Ограничивает объём отчёта, сохраняя последние переходы и первое свидетельство ввода отдельно. */
        private void record(String detail) {
            if (events.size() == 80) events.removeFirst();
            events.addLast(stage + ": " + detail);
        }

        /** Не допускает ложного успеха при вводе, случайно сохранившем ожидаемое значение. */
        void assertNoNativeInput() { assertNull(nativeInput, this::diagnostic); }

        /** Выводит кодовые точки, состояние модели, фокус и стек последнего изменения документа. */
        String diagnostic() {
            return "stage=" + stage + " expected=" + codePoints(expected)
                    + " raw=" + codePoints(fixture.binding().raw())
                    + " core=" + codePoints(fixture.session.state().value("horizonValue"))
                    + " clientRev=" + fixture.form.clientRev + " coreClientRev=" + fixture.session.lastClientRev()
                    + " number=" + fixture.spinner().getValue() + " editValid=" + fixture.editor().isEditValid()
                    + " nativeInput=" + nativeInput
                    + " focus=" + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                    + "\n" + String.join("\n", events);
        }

        /** Сохраняет непечатаемые символы и отличает U+FFFD от букв в отчёте любой кодировки. */
        private static String codePoints(String text) {
            return "length=" + text.length() + " [" + text.codePoints().mapToObj(cp -> String.format("U+%04X", cp))
                    .collect(java.util.stream.Collectors.joining(" ")) + "]";
        }

        /** Удаляет глобальный слушатель перед следующим тестом; обработанные события остаются неизменными. */
        @Override public void close() { Toolkit.getDefaultToolkit().removeAWTEventListener(this); }
    }

    /** Доступ к физическому редактору и общей модели без отдельного тестового адаптера. */
    private record Fixture(SwingUiPort port, FormSession session, SwingFormDialog form) {
        /** Возвращает привязку горизонта. */
        SwingFieldWidgets.Binding binding() { return form.fields.get("horizonValue").getFirst(); }
        /** Возвращает настоящий спиннер. */
        JSpinner spinner() { return (JSpinner) binding().input; }
        /** Возвращает поле текста стандартного числового редактора. */
        JFormattedTextField editor() { return ((JSpinner.DefaultEditor) spinner().getEditor()).getTextField(); }
    }
}
