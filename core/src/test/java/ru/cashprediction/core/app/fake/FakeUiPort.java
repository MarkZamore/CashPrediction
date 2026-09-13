package ru.cashprediction.core.app.fake;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.cashprediction.core.app.ChooserKind;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.MainGeometry;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.app.UiPort;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.session.UiExecutor;
import ru.cashprediction.core.ui.alert.AlertSession;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.menu.MenuNode;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.chart.ChartScene;

/**
 * Тестовый порт «ядро → клиент» (архитектура §3.6, §6.5): записывает каждый вызов порта и отвечает на сообщения и
 * выборы файлов по сценарию теста. Задачи выполняются на прямом {@link UiExecutor} (поток теста — поток интерфейса),
 * таймеры — на {@link ManualScheduler} с виртуальным временем.
 *
 * <p><b>Ответы.</b> {@link #answerAlert(String)} ставит id кнопки в общую очередь, {@link #answerAlert(String, String)} —
 * в очередь назначения (она важнее общей). Ответ приходит не внутри {@code showAlert}, а задачей планировщика —
 * после {@link #pump()} или {@code scheduler().advance(...)}, как в настоящем клиенте. Сообщение без заготовленного
 * ответа остаётся в {@link #pendingAlerts()}: тест отвечает на него {@link PendingAlert#press(String)}. Ответ приходит
 * ровно один раз: повторное нажатие — {@link IllegalStateException}. Выборы файлов и папок устроены так же
 * ({@link #chooseFileResult(Path)}, {@link #chooseDirectoryResult(Path)}; {@code null} — отмена); для клиента
 * {@link ChooserKind#SERVER_BROWSER} вызов {@code chooseFile} — ошибка контракта.</p>
 *
 * <p>Не потокобезопасен: только поток теста.</p>
 */
public final class FakeUiPort implements UiPort {

    /**
     * Записанный вызов порта.
     *
     * @param method имя метода порта ({@code showMain}, {@code render}, {@code openForm}, …)
     * @param args   аргументы вызова (для {@code render} — модель и копия множества частей)
     */
    public record Call(String method, List<Object> args) {
        /** Копирует аргументы (разрешая {@code null}). */
        public Call {
            args = java.util.Collections.unmodifiableList(new ArrayList<>(args));
        }

        /**
         * Аргумент по номеру.
         *
         * @param index номер
         * @param type  ожидаемый тип
         * @param <T>   тип
         * @return аргумент
         */
        public <T> T arg(int index, Class<T> type) {
            return type.cast(args.get(index));
        }
    }

    /**
     * Открытое сообщение, ожидающее ответа.
     *
     * @param spec     описание
     * @param session  сеанс восстанавливаемого сообщения или {@code null}
     * @param handle   ручка окна
     * @param onButton обработчик ответа
     * @param answered был ли уже ответ
     */
    public record PendingAlert(AlertSpec spec, AlertSession session, FakeWindowHandle handle, Consumer<String> onButton,
                               AtomicBoolean answered) {

        /**
         * Нажимает кнопку: закрывает окно и вызывает обработчик ядра ровно один раз.
         *
         * @param buttonId id кнопки из {@code spec.buttons()}
         * @throws IllegalArgumentException если такой кнопки нет или она отключена
         * @throws IllegalStateException    если ответ уже был
         */
        public void press(String buttonId) {
            boolean known = spec.buttons().stream().anyMatch(b -> b.id().equals(buttonId) && b.enabled());
            if (!known) {
                throw new IllegalArgumentException("No enabled button '" + buttonId + "' in alert " + spec.purpose());
            }
            if (!answered.compareAndSet(false, true)) {
                throw new IllegalStateException("Alert " + spec.purpose() + " already answered");
            }
            handle.close();
            onButton.accept(buttonId);
        }
    }

    /**
     * Открытая форма.
     *
     * @param session   сеанс формы
     * @param spec      раскладка
     * @param initial   начальная модель
     * @param placement положение
     * @param handle    ручка окна
     */
    public record OpenedForm(FormSession session, FormSpec spec, FormView initial, Placement placement,
                             FakeWindowHandle handle) {
    }

    /** Первые байты любого PNG: так тесты проверяют, что ядро записало именно результат порта. */
    public static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final ClientProfile profile;
    private final UiExecutor executor = UiExecutor.direct();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<Call> calls = new ArrayList<>();
    private final Deque<String> alertAnswers = new ArrayDeque<>();
    private final Map<String, Deque<String>> alertAnswersByPurpose = new HashMap<>();
    private final Deque<Optional<Path>> fileResults = new ArrayDeque<>();
    private final Deque<Optional<Path>> directoryResults = new ArrayDeque<>();
    private final List<PendingAlert> pendingAlerts = new ArrayList<>();
    private final List<OpenedForm> forms = new ArrayList<>();
    private final List<Consumer<Optional<Path>>> pendingFileChoosers = new ArrayList<>();
    private MainGeometry geometry = MainGeometry.UNKNOWN;
    private String clipboard = "";
    private ExitKind exitKind;
    private int exitCode = -1;
    private int windowCounter;

    /**
     * Создаёт порт.
     *
     * @param profile профиль клиента, который изображает порт
     */
    public FakeUiPort(ClientProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    // ------------------------------------------------------------------ сценарий теста

    /**
     * Заготовить ответ следующему сообщению любого назначения.
     *
     * @param buttonId id кнопки
     * @return этот порт
     */
    public FakeUiPort answerAlert(String buttonId) {
        alertAnswers.addLast(buttonId);
        return this;
    }

    /**
     * Заготовить ответ следующему сообщению этого назначения.
     *
     * @param purpose  назначение ({@code unsavedChanges}, {@code deleteRule}, …)
     * @param buttonId id кнопки
     * @return этот порт
     */
    public FakeUiPort answerAlert(String purpose, String buttonId) {
        alertAnswersByPurpose.computeIfAbsent(purpose, p -> new ArrayDeque<>()).addLast(buttonId);
        return this;
    }

    /**
     * Заготовить результат следующего выбора файла.
     *
     * @param pathOrNull путь или {@code null} — отмена
     * @return этот порт
     */
    public FakeUiPort chooseFileResult(Path pathOrNull) {
        fileResults.addLast(Optional.ofNullable(pathOrNull));
        return this;
    }

    /**
     * Заготовить результат следующего выбора папки.
     *
     * @param pathOrNull папка или {@code null} — отмена
     * @return этот порт
     */
    public FakeUiPort chooseDirectoryResult(Path pathOrNull) {
        directoryResults.addLast(Optional.ofNullable(pathOrNull));
        return this;
    }

    /**
     * Задаёт геометрию главного окна, которую вернёт {@link #mainGeometry()}.
     *
     * @param newGeometry геометрия
     * @return этот порт
     */
    public FakeUiPort setMainGeometry(MainGeometry newGeometry) {
        geometry = Objects.requireNonNull(newGeometry, "newGeometry");
        return this;
    }

    /** Выполняет отложенные ответы и прочие задачи очереди планировщика. */
    public void pump() {
        scheduler.runPending();
    }

    // ------------------------------------------------------------------ наблюдение

    /** @return все вызовы по порядку */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /**
     * Вызовы одного метода.
     *
     * @param method имя метода
     * @return вызовы по порядку
     */
    public List<Call> calls(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).toList();
    }

    /** @return имена вызванных методов по порядку */
    public List<String> methods() {
        return calls.stream().map(Call::method).toList();
    }

    /** @return все показанные сообщения по порядку */
    public List<AlertSpec> alerts() {
        return calls("showAlert").stream().map(c -> c.arg(0, AlertSpec.class)).toList();
    }

    /** @return сообщения, на которые ещё не ответили */
    public List<PendingAlert> pendingAlerts() {
        return pendingAlerts.stream().filter(a -> !a.answered().get()).toList();
    }

    /** @return открытые формы по порядку */
    public List<OpenedForm> forms() {
        return List.copyOf(forms);
    }

    /** @return запросы выбора файлов по порядку */
    public List<FileChooserSpec> fileRequests() {
        return calls("chooseFile").stream().map(c -> c.arg(0, FileChooserSpec.class)).toList();
    }

    /** @return запросы выбора папок по порядку */
    public List<DirectoryChooserSpec> directoryRequests() {
        return calls("chooseDirectory").stream().map(c -> c.arg(0, DirectoryChooserSpec.class)).toList();
    }

    /**
     * Отвечает на ожидающий выбор файла, для которого не было заготовки.
     *
     * @param pathOrNull путь или {@code null} — отмена
     */
    public void completeFileChooser(Path pathOrNull) {
        if (pendingFileChoosers.isEmpty()) {
            throw new IllegalStateException("No pending file chooser");
        }
        pendingFileChoosers.removeFirst().accept(Optional.ofNullable(pathOrNull));
    }

    /** @return последний скопированный в буфер текст */
    public String clipboard() {
        return clipboard;
    }

    /** @return вид завершения или пусто, если {@code exit} не вызывался */
    public Optional<ExitKind> exitKind() {
        return Optional.ofNullable(exitKind);
    }

    /** @return код выхода или -1 */
    public int exitCode() {
        return exitCode;
    }

    /** @return планировщик с виртуальным временем */
    public ManualScheduler manualScheduler() {
        return scheduler;
    }

    // ------------------------------------------------------------------ UiPort

    @Override
    public ClientProfile profile() {
        return profile;
    }

    @Override
    public UiExecutor executor() {
        return executor;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public void showMain(MainScreenModel model, MainWindowState restored) {
        record("showMain", model, restored);
    }

    @Override
    public void render(MainScreenModel model, EnumSet<ScreenPart> changed) {
        record("render", model, EnumSet.copyOf(changed));
    }

    @Override
    public MainGeometry mainGeometry() {
        record("mainGeometry");
        return geometry;
    }

    @Override
    public WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement) {
        record("openForm", session, spec, initial, placement);
        FakeWindowHandle handle = new FakeWindowHandle("form" + (++windowCounter),
                placement == null ? null : placement.bounds());
        forms.add(new OpenedForm(session, spec, initial, placement, handle));
        return handle;
    }

    @Override
    public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        record("showAlert", spec, session);
        FakeWindowHandle handle = new FakeWindowHandle("alert" + (++windowCounter), null);
        PendingAlert pending = new PendingAlert(spec, session, handle, Objects.requireNonNull(onButton, "onButton"),
                new AtomicBoolean());
        pendingAlerts.add(pending);
        Deque<String> byPurpose = alertAnswersByPurpose.get(spec.purpose());
        String answer = byPurpose != null && !byPurpose.isEmpty() ? byPurpose.removeFirst() : alertAnswers.pollFirst();
        if (answer != null) {
            // Ответ приходит после возврата из showAlert, как у настоящего модального окна.
            scheduler.execute(() -> pending.press(answer));
        }
        return handle;
    }

    @Override
    public void showContextMenu(ContextTarget target, List<MenuNode> items) {
        record("showContextMenu", target, List.copyOf(items));
    }

    @Override
    public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        if (profile.chooser() == ChooserKind.SERVER_BROWSER) {
            throw new IllegalStateException("chooseFile must not be called for SERVER_BROWSER clients");
        }
        record("chooseFile", spec);
        Consumer<Optional<Path>> once = once(onResult);
        Optional<Path> result = fileResults.pollFirst();
        if (result != null) {
            scheduler.execute(() -> once.accept(result));
        } else {
            pendingFileChoosers.add(once);
        }
    }

    @Override
    public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        if (profile.chooser() == ChooserKind.SERVER_BROWSER) {
            throw new IllegalStateException("chooseDirectory must not be called for SERVER_BROWSER clients");
        }
        record("chooseDirectory", spec);
        Consumer<Optional<Path>> once = once(onResult);
        Optional<Path> result = directoryResults.pollFirst();
        if (result != null) {
            scheduler.execute(() -> once.accept(result));
        } else {
            pendingFileChoosers.add(once);
        }
    }

    @Override
    public byte[] renderChartPng(ChartScene scene) throws IOException {
        record("renderChartPng", scene);
        return PNG_SIGNATURE.clone();
    }

    @Override
    public void focus(FocusTarget target) {
        record("focus", target);
    }

    @Override
    public void revealRow(String rowId, RevealMode mode) {
        record("revealRow", rowId, mode);
    }

    @Override
    public void copyToClipboard(String text) {
        record("copyToClipboard", text);
        clipboard = text;
    }

    @Override
    public void exit(ExitKind kind, int code) {
        record("exit", kind, code);
        exitKind = kind;
        exitCode = code;
    }

    private void record(String method, Object... args) {
        calls.add(new Call(method, Arrays.asList(args)));
    }

    private static <T> Consumer<T> once(Consumer<T> target) {
        Objects.requireNonNull(target, "onResult");
        AtomicBoolean done = new AtomicBoolean();
        return value -> {
            if (!done.compareAndSet(false, true)) {
                throw new IllegalStateException("Chooser result delivered twice");
            }
            target.accept(value);
        };
    }
}
