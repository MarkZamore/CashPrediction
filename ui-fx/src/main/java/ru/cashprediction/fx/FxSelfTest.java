package ru.cashprediction.fx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.event.Event;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import javafx.util.Duration;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.fx.dialog.FxRestorableDialog;
import ru.cashprediction.fx.popup.QuickEditPopup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Самотест JavaFX-клиента — служебный режим для разработчиков и автоматической проверки, а не функция для
 * пользователя.
 *
 * <p>Проверяющий скрипт не должен отправлять нажатия клавиш: они попали бы в активное окно, которым может
 * оказаться чужое окно пользователя. Вместо этого приложение само выполняет команды из файла сценария,
 * воздействуя на свои настоящие элементы управления.</p>
 *
 * <p><b>Включение</b> (только системными свойствами, без них класс не создаётся):</p>
 * <ul>
 *   <li>{@code cashprediction.selftest=<файл сценария в UTF-8>} — включает режим;</li>
 *   <li>{@code cashprediction.selftest.log=<файл журнала>} — строки дописываются: {@code SELFTEST <n> OK <команда>},
 *       {@code SELFTEST <n> FAIL <команда>: <причина>}, в конце {@code SELFTEST DONE}; служебные сведения —
 *       {@code SELFTEST INFO ...}. Без свойства журнал идёт в стандартный вывод;</li>
 *   <li>{@code cashprediction.selftest.recovery=registry|xml|none|already-ok} — автоматический ответ в диалоге
 *       восстановления или в вопросе «уже запущен» (диалог всё равно создаётся и около секунды виден на экране).</li>
 * </ul>
 *
 * <p><b>Выполнение.</b> Сценарий начинается после показа главного окна (и после восстановления сессии, если оно было).
 * Команды выполняются по одной в UI-потоке с паузой около 300 мс; ожидание построено на таймерах JavaFX,
 * UI-поток никогда не блокируется. Неизвестная команда или ошибка дают строку FAIL, и сценарий продолжается.</p>
 *
 * <p><b>Команды</b> (по одной в строке; пустые строки и строки с {@code #} пропускаются; значения с пробелами
 * заключаются в двойные кавычки):</p>
 * <pre>
 * wait &lt;мс&gt;
 * sample                                   Файл → Открыть пример
 * open &lt;WindowType&gt; [ключ=значение ...]     открыть окно как команду пользователя (owner=wN — владелец)
 * fill &lt;windowId&gt; &lt;поле&gt;=&lt;значение&gt; ...    вписать значения в настоящие поля окна (канонические формы)
 * ok &lt;windowId&gt; / cancel &lt;windowId&gt;         нажать кнопку подтверждения / отмены
 *                                          (windowId {@code last} — последнее зарегистрированное окно)
 * view TABLE|CHART ; period M3|M6|M12|M24|ALL ; filter &lt;ключ&gt;=true|false (filterText=... — текст фильтра)
 * select &lt;rowId&gt;                          выделить строку таблицы, например r1@2026-10-05
 * quickedit &lt;rowId&gt; &lt;сумма&gt;               открыть быструю правку и вписать сумму, не подтверждая
 * save                                     Ctrl+S
 * snapshot                                 recorder.saveNow()
 * dump &lt;путь&gt;                              состояние приложения в JSON (UTF-8)
 * menus &lt;путь&gt;                             отчёт о меню и 23 классах JavaFX (FAIL, если чего-то нет)
 * menu "Меню/Подменю/Пункт"                выбрать пункт строки меню, как пользователь
 * answer &lt;текст кнопки&gt;                    нажать кнопку в любом открытом диалоге (и в невосстанавливаемом)
 * open ... owner=last                      владелец — последнее зарегистрированное окно
 * signal &lt;путь&gt;                            создать файл и ждать (до 60 с), пока его удалит внешний процесс
 * crash                                    Runtime.halt(3) без сохранения
 * throw                                    необработанное исключение в UI-потоке
 * exit                                     корректный выход без вопросов (несохранённые правки отбрасываются)
 * </pre>
 *
 * <p>Только FX Application Thread (кроме записи журнала, которая синхронизирована).</p>
 */
public final class FxSelfTest {

    /** Свойство: путь к сценарию. */
    public static final String PROPERTY_SCRIPT = "cashprediction.selftest";
    /** Свойство: путь к журналу. */
    public static final String PROPERTY_LOG = "cashprediction.selftest.log";
    /** Свойство: автоматический ответ при запуске. */
    public static final String PROPERTY_RECOVERY = "cashprediction.selftest.recovery";

    /** Пауза между командами. */
    private static final Duration STEP = Duration.millis(300);
    /** Сколько диалог восстановления виден на экране до автоматического ответа. */
    private static final Duration ANSWER_DELAY = Duration.millis(3000);
    /** Наибольшее ожидание удаления файла-сигнала. */
    private static final long SIGNAL_TIMEOUT_MS = 60_000;
    /** Код завершения команды {@code crash} (как у «Симулировать сбой»). */
    private static final int HALT_CRASH = 3;

    private final Path script;
    private final Path log;
    private final String recoveryAnswer;
    private final List<String> commands = new ArrayList<>();
    private AppController app;
    private int next;
    private boolean started;

    private FxSelfTest(Path script, Path log, String recoveryAnswer) {
        this.script = script;
        this.log = log;
        this.recoveryAnswer = recoveryAnswer;
    }

    /**
     * Создаёт самотест по системным свойствам.
     *
     * @return самотест или {@code null}, если свойство {@value #PROPERTY_SCRIPT} не задано
     */
    public static FxSelfTest fromSystemProperties() {
        String script = System.getProperty(PROPERTY_SCRIPT, "");
        if (script.isBlank()) {
            return null;
        }
        String log = System.getProperty(PROPERTY_LOG, "");
        String answer = System.getProperty(PROPERTY_RECOVERY, "").strip();
        return new FxSelfTest(Path.of(script), log.isBlank() ? null : Path.of(log), answer.isEmpty() ? null : answer);
    }

    // ================================================================== ответы при запуске

    /** Автоматически отвечает в диалоге «Восстановление» (если задано {@value #PROPERTY_RECOVERY}). */
    public void answerRecovery() {
        if (recoveryAnswer == null) {
            return;
        }
        later(ANSWER_DELAY, () -> {
            String prefix = switch (recoveryAnswer) {
                case "registry" -> "Из реестра Windows";
                case "xml" -> "Из XML-файла";
                default -> "Не восстанавливать";
            };
            if (pressDialogButton(text -> text.startsWith(prefix))) {
                info("recovery answered: " + recoveryAnswer);
            } else {
                // Кнопка хранилища отключена (снимка нет или он повреждён): иначе запуск так и остался бы ждать.
                info("recovery button unavailable: " + recoveryAnswer + ", pressing «Не восстанавливать»");
                pressDialogButton(text -> text.startsWith("Не восстанавливать"));
            }
        });
    }

    /** Автоматически отвечает на вопрос «CashPrediction уже запущен…». */
    public void answerAlreadyRunning() {
        if (recoveryAnswer == null) {
            return;
        }
        later(ANSWER_DELAY, () -> {
            boolean open = "already-ok".equals(recoveryAnswer);
            boolean pressed = pressDialogButton(text -> text.equals(open ? "Открыть без восстановления" : "Выйти"));
            info("already-running answered: " + (open ? "open" : "exit") + (pressed ? "" : " (button not found)"));
        });
    }

    // ================================================================== сценарий

    /**
     * Запускает сценарий (один раз): после показа главного окна и восстановления сессии.
     *
     * @param controller контроллер приложения
     */
    public void start(AppController controller) {
        if (started) {
            return;
        }
        started = true;
        this.app = Objects.requireNonNull(controller, "controller");
        try {
            List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.startsWith("﻿") ? raw.substring(1) : raw;
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    commands.add(line);
                }
            }
        } catch (IOException e) {
            write("SELFTEST 0 FAIL read " + script + ": " + e.getMessage());
            write("SELFTEST DONE");
            return;
        }
        info("started at " + LocalTime.now().withNano(0) + ", commands: " + commands.size());
        later(STEP, this::runNext);
    }

    private void runNext() {
        if (next >= commands.size()) {
            write("SELFTEST DONE");
            return;
        }
        int number = next + 1;
        String line = commands.get(next++);
        List<String> tokens;
        try {
            tokens = tokenize(line);
        } catch (IllegalArgumentException e) {
            fail(number, line, e.getMessage());
            later(STEP, this::runNext);
            return;
        }
        String name = tokens.getFirst();
        List<String> args = tokens.subList(1, tokens.size());
        try {
            switch (name) {
                case "wait" -> {
                    long ms = Long.parseLong(single(args, "wait <мс>"));
                    later(Duration.millis(Math.max(0, ms)), () -> {
                        ok(number, line);
                        later(STEP, this::runNext);
                    });
                    return;
                }
                case "signal" -> {
                    signal(number, line, Path.of(single(args, "signal <путь>")));
                    return;
                }
                case "crash" -> {
                    ok(number, line);
                    doneIfLast();
                    Runtime.getRuntime().halt(HALT_CRASH);
                    return;
                }
                case "throw" -> {
                    ok(number, line);
                    doneIfLast();
                    Platform.runLater(() -> {
                        throw new IllegalStateException("Самотест: необработанное исключение в UI-потоке (команда throw)");
                    });
                    return;
                }
                case "exit" -> {
                    ok(number, line);
                    doneIfLast();
                    app.finishExit();
                    return;
                }
                default -> execute(name, args);
            }
            ok(number, line);
        } catch (Exception e) {
            fail(number, line, Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
        later(STEP, this::runNext);
    }

    private void execute(String name, List<String> args) throws IOException {
        switch (name) {
            case "sample" -> app.actions().openSample();
            case "open" -> open(args);
            case "fill" -> fill(args);
            case "ok" -> press(single(args, "ok <windowId>"), true);
            case "cancel" -> press(single(args, "cancel <windowId>"), false);
            case "view" -> app.window().controls().selectMode(ViewMode.valueOf(single(args, "view TABLE|CHART")));
            case "period" -> app.window().controls().selectPeriod(PeriodChoice.valueOf(single(args, "period M3|M6|M12|M24|ALL")));
            case "filter" -> filter(args);
            case "select" -> {
                String rowId = single(args, "select <rowId>");
                if (!app.window().table().select(rowId)) {
                    throw new IllegalArgumentException("строки " + rowId + " нет в таблице");
                }
            }
            case "quickedit" -> {
                if (args.size() != 2) {
                    throw new IllegalArgumentException("ожидается quickedit <rowId> <сумма>");
                }
                QuickEditPopup popup = app.window().table().openQuickEdit(args.get(0), null, null);
                popup.typeAmount(args.get(1));
            }
            case "save" -> app.actions().save(saved -> info("save finished: " + saved));
            case "snapshot" -> app.recorder().saveNow();
            case "dump" -> dump(Path.of(single(args, "dump <путь>")));
            case "menus" -> menus(Path.of(single(args, "menus <путь>")));
            case "menu" -> menu(single(args, "menu \"Меню/Подменю/Пункт\""));
            case "answer" -> {
                String text = single(args, "answer <текст кнопки>");
                if (!pressDialogButton(text::equals)) {
                    throw new IllegalArgumentException("ни в одном открытом диалоге нет доступной кнопки «" + text + "»");
                }
            }
            default -> throw new IllegalArgumentException("неизвестная команда");
        }
    }

    // ================================================================== команды

    private void open(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("ожидается open <WindowType> [ключ=значение ...]");
        }
        WindowType type = WindowType.fromName(args.getFirst())
                .orElseThrow(() -> new IllegalArgumentException("неизвестный тип окна " + args.getFirst()));
        Map<String, String> context = pairs(args.subList(1, args.size()));
        String owner = Optional.ofNullable(context.remove("owner")).orElse(WindowState.MAIN_OWNER);
        if ("last".equals(owner)) {
            // Вложенное окно поверх последнего открытого (например, корректировка поверх редактора правила).
            owner = window("last").windowId();
        }
        app.factory().openCommand(type, context, owner);
    }

    private void fill(List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("ожидается fill <windowId> <поле>=<значение> ...");
        }
        StatefulWindow window = window(args.getFirst());
        Map<String, String> values = pairs(args.subList(1, args.size()));
        Set<String> known = window.captureState().fields().keySet();
        for (String field : values.keySet()) {
            if (!known.contains(field)) {
                throw new IllegalArgumentException("у окна " + window.windowType() + " нет поля «" + field + "»; есть: " + known);
            }
        }
        if (window instanceof FxRestorableDialog dialog) {
            // Значения ставятся в настоящие элементы формы: срабатывают их слушатели, проверка формы и touch().
            dialog.stateSupport().binder().apply(values);
        } else if (window instanceof QuickEditPopup popup) {
            popup.typeAmount(values.get(QuickEditPopup.FIELD_AMOUNT));
        } else {
            throw new IllegalArgumentException("окно " + window.windowId() + " не поддерживает fill");
        }
    }

    private void press(String windowId, boolean confirm) {
        StatefulWindow window = window(windowId);
        if (window instanceof QuickEditPopup popup) {
            if (confirm) {
                if (!popup.commit()) {
                    throw new IllegalArgumentException("сумма не принята");
                }
            } else {
                popup.hide();
            }
            return;
        }
        if (!(window instanceof Dialog<?> dialog)) {
            throw new IllegalArgumentException("окно " + windowId + " не является диалогом");
        }
        DialogPane pane = dialog.getDialogPane();
        Button button = findButton(pane, confirm);
        if (button == null) {
            throw new IllegalArgumentException("у окна " + windowId + " нет кнопки " + (confirm ? "подтверждения" : "отмены")
                    + "; кнопки: " + pane.getButtonTypes().stream().map(ButtonType::getText).toList());
        }
        if (button.isDisabled()) {
            throw new IllegalArgumentException("кнопка «" + button.getText() + "» недоступна: форма заполнена с ошибками");
        }
        button.fire();
    }

    private static Button findButton(DialogPane pane, boolean confirm) {
        Set<ButtonData> wanted = confirm
                ? Set.of(ButtonData.OK_DONE, ButtonData.FINISH, ButtonData.YES, ButtonData.APPLY)
                : Set.of(ButtonData.CANCEL_CLOSE, ButtonData.NO);
        // Сначала «главная» роль: у «Сохранить изменения?» есть и CANCEL_CLOSE («Отмена»), и NO («Не сохранять»).
        ButtonData primary = confirm ? ButtonData.OK_DONE : ButtonData.CANCEL_CLOSE;
        for (int pass = 0; pass < 2; pass++) {
            for (ButtonType type : pane.getButtonTypes()) {
                ButtonData data = type.getButtonData();
                boolean match = pass == 0 ? data == primary : wanted.contains(data);
                if (match && pane.lookupButton(type) instanceof Button button) {
                    return button;
                }
            }
        }
        return null;
    }

    private void filter(List<String> args) {
        Map<String, String> values = pairs(args);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("ожидается filter <ключ>=true|false");
        }
        values.forEach((key, value) -> {
            if ("filterText".equals(key)) {
                app.window().controls().filterField().setText(value);
                return;
            }
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalArgumentException("значение флага " + key + " должно быть true или false");
            }
            app.window().controls().setFlag(key, Boolean.parseBoolean(value));
        });
    }

    private void dump(Path path) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", app.window().stage().getTitle());
        SessionSnapshot main = SessionSnapshot.of(Instant.now(), AppController.CLIENT, app.captureMain(), PlanState.CLEAN, List.of());
        root.put("main", new JsonSnapshotCodec().toJsonObject(main).get("main"));

        Plan plan = app.document().plan();
        Map<String, Object> planMap = new LinkedHashMap<>();
        planMap.put("name", plan.name());
        planMap.put("dirty", app.document().isDirty());
        planMap.put("rules", plan.rules().stream().map(PlanJson::rule).toList());
        planMap.put("oneTimes", plan.oneTimes().stream().map(PlanJson::oneTime).toList());
        planMap.put("adjustments", plan.adjustments().stream().map(PlanJson::adjustment).toList());
        root.put("plan", planMap);

        SessionRecorder recorder = app.recorder();
        List<Object> windows = new ArrayList<>();
        for (StatefulWindow window : recorder.registeredWindows()) {
            WindowState state = window.captureState();
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", state.id());
            w.put("type", state.type() == null ? null : state.type().name());
            w.put("modal", state.modal());
            w.put("ownerId", state.ownerId());
            w.put("context", state.context());
            w.put("fields", state.fields());
            if (state.bounds() != null) {
                w.put("bounds", List.of(state.bounds().x(), state.bounds().y(), state.bounds().width(), state.bounds().height()));
            }
            if (window instanceof Dialog<?> dialog) {
                // Для проверки раскладки: помещается ли содержимое панели в окно (иначе кнопки уходят за край).
                DialogPane pane = dialog.getDialogPane();
                w.put("paneHeight", Math.round(pane.getHeight()));
                w.put("panePrefHeight", Math.round(pane.prefHeight(pane.getWidth())));
            }
            windows.add(w);
        }
        root.put("windows", windows);
        root.put("recorderStarted", recorder.isStarted());
        root.put("restoreWarnings", List.copyOf(app.restoreWarnings()));
        List<Object> statuses = new ArrayList<>();
        for (StoreStatus status : app.storeStatuses()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storeId", status.storeId());
            s.put("ok", status.ok());
            s.put("savedAt", status.savedAt() == null ? null : status.savedAt().toString());
            s.put("message", status.message());
            statuses.add(s);
        }
        root.put("storeStatuses", statuses);

        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        Files.writeString(path, JsonWriter.writePretty(root) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Команда {@code menus}: текстовый отчёт о меню, панели инструментов, контекстных меню и 23 классах JavaFX.
     * Если какого-то класса нет или у пункта меню нет обработчика, команда завершается строкой FAIL.
     */
    private void menus(Path path) throws IOException {
        FxSelfTestMenus.Report report = FxSelfTestMenus.build(app);
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        Files.writeString(path, report.text(), StandardCharsets.UTF_8);
        info("menus: классов JavaFX найдено " + report.present() + " из " + FxSelfTestMenus.REQUIRED.size()
                + ", пунктов без обработчика " + report.unwired().size());
        if (!report.missing().isEmpty() || !report.unwired().isEmpty()) {
            throw new IllegalStateException("не найдены классы " + report.missing() + "; не подключены " + report.unwired());
        }
    }

    /**
     * Команда {@code menu}: выбирает пункт строки меню по пути «Меню/Подменю/Пункт» так, как это сделал бы пользователь.
     *
     * <p>Каждое меню на пути получает {@code ON_SHOWING}, как при настоящем открытии (перестраиваются «Недавние»,
     * переносятся общие пункты). Сегмент пути сравнивается с началом подписи, поэтому многоточие можно не писать.
     * {@code fire()} у флажка и переключателя JavaFX не меняет отметку (её меняет обработка щелчка в меню),
     * поэтому отметка ставится здесь явно, а затем вызывается действие пункта.</p>
     *
     * @param path путь, например {@code Файл/Выход} или {@code Восстановление/Симулировать сбой/Необработанное исключение}
     */
    private void menu(String path) {
        String[] parts = path.split("/");
        List<MenuItem> level = new ArrayList<>(app.window().menuBar().getMenus());
        MenuItem found = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            List<MenuItem> current = level;
            found = current.stream().filter(item -> item.getText() != null && item.getText().startsWith(part)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("нет пункта «" + part + "»; есть: "
                            + current.stream().map(MenuItem::getText).toList()));
            if (found instanceof Menu menu) {
                Event.fireEvent(menu, new Event(Menu.ON_SHOWING));
                level = new ArrayList<>(menu.getItems());
            } else if (i < parts.length - 1) {
                throw new IllegalArgumentException("«" + part + "» - пункт, а не подменю");
            }
        }
        if (found == null || found instanceof Menu) {
            throw new IllegalArgumentException("«" + path + "» - подменю, а не пункт");
        }
        if (found.isDisable()) {
            throw new IllegalArgumentException("пункт «" + path + "» недоступен");
        }
        switch (found) {
            case RadioMenuItem radio -> {
                radio.setSelected(true);
                radio.fire();
            }
            case CheckMenuItem check -> {
                check.setSelected(!check.isSelected());
                check.fire();
            }
            default -> found.fire();
        }
    }

    private void signal(int number, String line, Path path) {
        try {
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            Files.writeString(path, "SELFTEST " + number + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail(number, line, "не удалось создать файл-сигнал: " + e.getMessage());
            later(STEP, this::runNext);
            return;
        }
        long deadline = System.currentTimeMillis() + SIGNAL_TIMEOUT_MS;
        pollSignal(number, line, path, deadline);
    }

    private void pollSignal(int number, String line, Path path, long deadline) {
        if (!Files.exists(path)) {
            ok(number, line);
            later(STEP, this::runNext);
        } else if (System.currentTimeMillis() > deadline) {
            fail(number, line, "файл-сигнал не удалён за 60 с");
            later(STEP, this::runNext);
        } else {
            later(Duration.millis(200), () -> pollSignal(number, line, path, deadline));
        }
    }

    // ================================================================== служебное

    private StatefulWindow window(String id) {
        List<StatefulWindow> open = List.copyOf(app.recorder().registeredWindows());
        if ("last".equals(id) && !open.isEmpty()) {
            // Последнее зарегистрированное окно: сценарию не нужно угадывать номер, выданный рекордером.
            return open.getLast();
        }
        for (StatefulWindow window : open) {
            if (window.windowId().equals(id)) {
                return window;
            }
        }
        throw new IllegalArgumentException("окна " + id + " нет среди открытых: "
                + app.recorder().registeredWindows().stream().map(StatefulWindow::windowId).toList());
    }

    private static boolean pressDialogButton(Predicate<String> text) {
        for (Window window : List.copyOf(Window.getWindows())) {
            Scene scene = window.getScene();
            if (!window.isShowing() || scene == null || scene.getRoot() == null) {
                continue;
            }
            Node found = scene.getRoot() instanceof DialogPane direct ? direct : scene.getRoot().lookup(".dialog-pane");
            if (!(found instanceof DialogPane pane)) {
                continue;
            }
            for (ButtonType type : pane.getButtonTypes()) {
                if (text.test(type.getText()) && pane.lookupButton(type) instanceof Button button && !button.isDisabled()) {
                    button.fire();
                    return true;
                }
            }
        }
        return false;
    }

    private static String single(List<String> args, String usage) {
        if (args.size() != 1) {
            throw new IllegalArgumentException("ожидается " + usage);
        }
        return args.getFirst();
    }

    private static Map<String, String> pairs(List<String> args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("ожидается ключ=значение, получено «" + arg + "»");
            }
            map.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        return map;
    }

    /**
     * Разбивает строку команды на слова; двойные кавычки объединяют слова с пробелами и сами удаляются.
     *
     * @param line строка команды
     * @return слова (не пусто)
     * @throws IllegalArgumentException при незакрытой кавычке
     */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("незакрытая кавычка");
        }
        if (any) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("пустая команда");
        }
        return tokens;
    }

    private void doneIfLast() {
        if (next >= commands.size()) {
            write("SELFTEST DONE");
        }
    }

    private static void later(Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    private void ok(int number, String line) {
        write("SELFTEST " + number + " OK " + line);
    }

    private void fail(int number, String line, String reason) {
        write("SELFTEST " + number + " FAIL " + line + ": " + reason.replace('\n', ' '));
    }

    private void info(String text) {
        write("SELFTEST INFO " + text);
    }

    private synchronized void write(String text) {
        if (log == null) {
            System.out.println(text);
            return;
        }
        try {
            Path parent = log.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(log, text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println(text + " (журнал недоступен: " + e.getMessage() + ")");
        }
    }
}
