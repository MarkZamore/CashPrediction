package ru.cashprediction.swing.action;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingDialogHost;
import ru.cashprediction.swing.dialog.SwingRecoveryDialog;

/**
 * Фасад команд Swing-клиента: всё, что умеют меню, панель инструментов, контекстные меню, горячие клавиши и фабрика
 * окон восстановления, собрано здесь. Зеркало {@code FxActions} JavaFX-клиента — те же команды с теми же именами.
 *
 * <p>Команды разнесены по группам: {@link FileActions} (Файл), {@link EditActions} (Правка), {@link ToolActions}
 * (Инструменты), {@link RecoveryActions} (Восстановление), {@link HelpActions} (Справка). Методы, открывающие
 * восстанавливаемые окна, есть в двух вариантах: без запроса — команда пользователя (владелец — верхнее модальное
 * окно), и с {@link OpenRequest} — его передаёт фабрика окон {@code SwingWindowFactory} с состоянием из снимка.
 * Поэтому окно после сбоя открывается тем же кодом, что и вручную.</p>
 *
 * <p>Все диалоги показываются неблокирующе ({@link SwingDialogHost}): метод команды возвращается сразу, а результат
 * диалога применяется в колбэке. Только поток EDT.</p>
 */
public final class SwingActions {

    private final ActionShared shared;
    private final FileActions file;
    private final EditActions edit;
    private final ToolActions tools;
    private final RecoveryActions recovery;
    private final HelpActions help;

    /**
     * Создаёт фасад с собственным хостом диалогов (владелец — главное окно контекста).
     *
     * @param context контекст приложения
     */
    public SwingActions(SwingAppContext context) {
        this(context, new SwingDialogHost(context::owner, context::recorder));
    }

    /**
     * Создаёт фасад с заданным хостом диалогов.
     *
     * @param context контекст приложения
     * @param host    хост диалогов (общий с главным окном, чтобы оно знало открытые диалоги)
     */
    public SwingActions(SwingAppContext context, SwingDialogHost host) {
        this.shared = new ActionShared(Objects.requireNonNull(context, "context"), Objects.requireNonNull(host, "host"));
        this.file = new FileActions(shared);
        this.edit = new EditActions(shared);
        this.tools = new ToolActions(shared);
        this.recovery = new RecoveryActions(shared);
        this.help = new HelpActions(shared);
        shared.setFiles(file);
    }

    /**
     * Хост диалогов: показывает окна и связывает их с рекордером сессии.
     *
     * @return хост
     */
    public SwingDialogHost host() {
        return shared.host();
    }

    /**
     * Помощник коротких сообщений (информация, предупреждение, ошибка, подтверждение).
     *
     * @return помощник
     */
    public Alerts alerts() {
        return shared.alerts();
    }

    /**
     * Запрос открытия окна по команде пользователя: владелец — верхнее открытое модальное окно или главное окно.
     *
     * @return запрос
     */
    public OpenRequest interactive() {
        return OpenRequest.interactive(shared.host());
    }

    // ================================================================== Файл

    /** «Новый план…» (Ctrl+N): при несохранённых изменениях сначала спрашивает, сохранить ли их. */
    public void newPlan() {
        newPlan(interactive());
    }

    /**
     * Мастер «Новый план» (диалог 1).
     *
     * @param request запрос открытия (при восстановлении вопрос о несохранённых изменениях не задаётся)
     */
    public void newPlan(OpenRequest request) {
        if (request.isRestore()) {
            file.openWizard(request, null);
        } else {
            file.confirmDiscard(() -> file.openWizard(request, null));
        }
    }

    /** Мастер нового плана при первом запуске: отмена открывает пустой несохранённый «Мой план». */
    public void startupWizard() {
        file.openWizardOnStartup();
    }

    /** «Открыть…» (Ctrl+O): список планов папки и «Из файла…». */
    public void openPlan() {
        openPlan(interactive());
    }

    /**
     * Диалог 10 «Открыть план» ({@code ChoiceDialog}).
     *
     * @param request запрос открытия
     */
    public void openPlan(OpenRequest request) {
        if (request.isRestore()) {
            file.openPlanChooser(request);
        } else {
            file.confirmDiscard(() -> file.openPlanChooser(request));
        }
    }

    /** «Открыть из файла…»: сразу окно выбора файла (диалог 12). */
    public void openFromFile() {
        file.confirmDiscard(file::chooseFileAndLoad);
    }

    /**
     * Пункт подменю «Недавние».
     *
     * @param settingsName имя из настроек (имя файла в CashMemory или полный путь)
     */
    public void openRecent(String settingsName) {
        file.openRecent(settingsName);
    }

    /** «Открыть пример»: несохранённый план «Пример» ({@link SamplePlan#samplePlan(LocalDate)}). */
    public void openSample() {
        file.confirmDiscard(file::openSample);
    }

    /**
     * Читает план из файла без вопросов о несохранённых изменениях (запуск программы).
     *
     * @param path файл плана
     * @return {@code true}, если план открыт
     */
    public boolean loadPlan(Path path) {
        return file.loadPlan(path);
    }

    /**
     * Читает план при восстановлении: замечания уходят в отчёт восстановления, а не в модальные окна.
     *
     * @param path файл плана
     * @param warn приёмник предупреждений
     * @return {@code true}, если план открыт
     */
    public boolean loadPlanForRestore(Path path, Consumer<String> warn) {
        return file.loadPlan(path, Objects.requireNonNull(warn, "warn"));
    }

    /**
     * Запоминает время изменения файла открытого плана (для диалога «Файл изменён снаружи»).
     *
     * @param path файл или {@code null}
     */
    public void trackFile(Path path) {
        file.trackFile(path);
    }

    /** «Сохранить» (Ctrl+S). */
    public void save() {
        file.save(ok -> { });
    }

    /**
     * «Сохранить» с результатом.
     *
     * @param done {@code true}, если план сохранён
     */
    public void save(Consumer<Boolean> done) {
        file.save(done);
    }

    /** «Сохранить как…» (Ctrl+Shift+S). */
    public void saveAs() {
        file.saveAs(ok -> { });
    }

    /**
     * «Сохранить как…» с результатом.
     *
     * @param done {@code true}, если план сохранён
     */
    public void saveAs(Consumer<Boolean> done) {
        file.saveAs(done);
    }

    /** Автосохранение через секунду после правки (включается флажком «Автосохранение»). */
    public void autosave() {
        file.autosave();
    }

    /**
     * «Сохранить изменения в плане?» [Сохранить][Не сохранять][Отмена].
     *
     * @param proceed {@code true} — можно продолжать
     */
    public void askSaveChanges(Consumer<Boolean> proceed) {
        file.askSaveChanges(proceed);
    }

    /**
     * Подготовка к выходу: сначала снимок сессии ({@code recorder.saveNow()}), затем, если план изменён,
     * вопрос «Сохранить изменения в плане?». Запись настроек и {@code shutdownClean()} делает главное окно после
     * положительного ответа.
     *
     * @param proceed {@code true} — выходить можно, {@code false} — пользователь отменил выход
     */
    public void confirmExit(Consumer<Boolean> proceed) {
        // Снимок до вопроса: если во время вопроса процесс оборвётся, введённое не пропадёт.
        shared.context().recorder().saveNow();
        if (shared.context().document().isDirty()) {
            file.askSaveChanges(proceed);
        } else {
            proceed.accept(true);
        }
    }

    /** «Переименовать…» (F2). */
    public void rename() {
        rename(interactive());
    }

    /**
     * Диалог 9 «Переименовать» ({@code TextInputDialog}).
     *
     * @param request запрос открытия
     */
    public void rename(OpenRequest request) {
        file.rename(request);
    }

    /** «Экспорт CSV…» (Ctrl+Shift+C). */
    public void exportCsv() {
        exportCsv(interactive());
    }

    /**
     * Диалог 14 «Экспорт CSV».
     *
     * @param request запрос открытия
     */
    public void exportCsv(OpenRequest request) {
        file.exportCsv(request);
    }

    /** «Сохранить график PNG…» (только настольные клиенты). */
    public void saveChartPng() {
        file.saveChartPng();
    }

    /** «Папка CashMemory…» (диалог 13, {@code DirectoryChooser}). */
    public void chooseCashMemoryFolder() {
        file.cashMemoryFolder();
    }

    /** Синоним {@link #chooseCashMemoryFolder()} с именем команды JavaFX-клиента. */
    public void cashMemoryFolder() {
        file.cashMemoryFolder();
    }

    /**
     * Папка, из которой «Открыть…» показывает планы.
     *
     * @return CashMemory или папка, выбранная на этот сеанс
     */
    public Path plansFolder() {
        return shared.plansFolder();
    }

    // ================================================================== Правка

    /**
     * «Добавить доход…» (Ctrl+I) / «Добавить расход…» (Ctrl+E).
     *
     * @param kind тип операции
     */
    public void addRule(Kind kind) {
        addRule(kind, interactive());
    }

    /**
     * Диалог 3 «Регулярная операция» в режиме создания.
     *
     * @param kind    тип операции
     * @param request запрос открытия
     */
    public void addRule(Kind kind, OpenRequest request) {
        edit.openRuleEditor(request, "", kind);
    }

    /**
     * Изменение регулярной операции.
     *
     * @param id идентификатор правила
     */
    public void editRule(RuleId id) {
        editRule(id, interactive());
    }

    /**
     * Диалог 3 «Регулярная операция» в режиме изменения.
     *
     * @param id      идентификатор правила
     * @param request запрос открытия
     */
    public void editRule(RuleId id, OpenRequest request) {
        edit.openRuleEditor(request, id.value(), null);
    }

    /**
     * Удаление регулярной операции с подтверждением.
     *
     * @param id идентификатор правила
     */
    public void deleteRule(RuleId id) {
        edit.deleteRule(id, interactive());
    }

    /**
     * Диалог 11 «Удаление правила».
     *
     * @param id      идентификатор правила
     * @param request запрос открытия
     */
    public void deleteRule(RuleId id, OpenRequest request) {
        edit.deleteRule(id, request);
    }

    /**
     * «Отключить правило».
     *
     * @param id идентификатор правила
     */
    public void disableRule(RuleId id) {
        edit.setRuleEnabled(id, false);
    }

    /**
     * Включает или отключает правило.
     *
     * @param id      идентификатор правила
     * @param enabled новое состояние
     */
    public void setRuleEnabled(RuleId id, boolean enabled) {
        edit.setRuleEnabled(id, enabled);
    }

    /** «Разовая операция…» (Ctrl+T). */
    public void addOneTime() {
        addOneTime(null, null, interactive());
    }

    /**
     * «Добавить разовую на эту дату…».
     *
     * @param date дата или {@code null}
     * @param kind тип операции или {@code null}
     */
    public void addOneTime(LocalDate date, Kind kind) {
        addOneTime(date, kind, interactive());
    }

    /**
     * Диалог 4 «Разовая операция» в режиме создания.
     *
     * @param date    дата или {@code null} (сегодня или начало плана)
     * @param kind    тип операции или {@code null}
     * @param request запрос открытия
     */
    public void addOneTime(LocalDate date, Kind kind, OpenRequest request) {
        edit.openOneTimeEditor(request, "", date, kind);
    }

    /**
     * Изменение разовой операции.
     *
     * @param id идентификатор операции
     */
    public void editOneTime(TxId id) {
        editOneTime(id, interactive());
    }

    /**
     * Диалог 4 «Разовая операция» в режиме изменения.
     *
     * @param id      идентификатор операции
     * @param request запрос открытия
     */
    public void editOneTime(TxId id, OpenRequest request) {
        edit.openOneTimeEditor(request, id.value(), null, null);
    }

    /**
     * Удаление разовой операции с подтверждением.
     *
     * @param id идентификатор операции
     */
    public void deleteOneTime(TxId id) {
        edit.deleteOneTime(id, interactive());
    }

    /**
     * Диалог 11 «Удаление разовой операции».
     *
     * @param id      идентификатор операции
     * @param request запрос открытия
     */
    public void deleteOneTime(TxId id, OpenRequest request) {
        edit.deleteOneTime(id, request);
    }

    /**
     * «Скорректировать событие…».
     *
     * @param key событие
     */
    public void adjustOccurrence(OccurrenceKey key) {
        adjustOccurrence(key, interactive());
    }

    /**
     * Диалог 5 «Корректировка события».
     *
     * @param key     событие: правило и номинальная дата
     * @param request запрос открытия
     */
    public void adjustOccurrence(OccurrenceKey key, OpenRequest request) {
        edit.openAdjustment(request, key.ruleId().value(), key.originalDate());
    }

    /**
     * «Вернуть как по правилу»: удаляет корректировку события.
     *
     * @param key событие
     */
    public void resetOccurrence(OccurrenceKey key) {
        edit.resetOccurrence(key);
    }

    /**
     * «Пропустить» событие без диалога.
     *
     * @param key событие
     */
    public void skipOccurrence(OccurrenceKey key) {
        edit.skipOccurrence(key);
    }

    /**
     * Быстрая правка суммы события (двойной щелчок по сумме).
     *
     * @param key событие
     */
    public void quickEdit(OccurrenceKey key) {
        edit.quickEdit(interactive(), key.ruleId().value(), key.originalDate());
    }

    /**
     * Быстрая правка суммы события по запросу (восстановление {@code QUICK_EDIT_POPUP}).
     *
     * @param key     событие
     * @param request запрос открытия
     */
    public void quickEdit(OccurrenceKey key, OpenRequest request) {
        edit.quickEdit(request, key.ruleId().value(), key.originalDate());
    }

    /**
     * Применяет сумму из быстрой правки.
     *
     * @param key    событие
     * @param amount новая сумма (больше нуля)
     */
    public void quickEditAmount(OccurrenceKey key, Money amount) {
        edit.applyQuickAmount(key, amount);
    }

    /**
     * Разбирает сумму быстрой правки: положительная и не больше предела.
     *
     * @param text текст поля
     * @return сумма или пусто
     */
    public static Optional<Money> parsePositiveAmount(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Money money = Money.parse(text);
            return money.isPositive() && money.compareTo(PlanValidator.MAX_AMOUNT) <= 0 ? Optional.of(money) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** «Изменить…» (Enter) для выделенной строки. */
    public void editSelected() {
        edit.editSelectedCommand();
    }

    /** «Удалить…» (Delete) для выделенной строки. */
    public void deleteSelected() {
        edit.deleteSelectedCommand();
    }

    /** «Скорректировать событие…» (Ctrl+J) для выделенной строки. */
    public void adjustSelected() {
        edit.adjustSelectedCommand();
    }

    /** «Вернуть как по правилу» для выделенной строки. */
    public void resetSelected() {
        edit.resetSelectedCommand();
    }

    /**
     * «Изменить…» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void editRow(String rowId) {
        edit.editRow(rowId);
    }

    /**
     * «Удалить…» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void deleteRow(String rowId) {
        edit.deleteRow(rowId);
    }

    /**
     * «Скорректировать событие…» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void adjustRow(String rowId) {
        edit.adjustRow(rowId);
    }

    /**
     * «Вернуть как по правилу» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void resetRow(String rowId) {
        edit.resetRow(rowId);
    }

    /**
     * «Пропустить» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void skipRow(String rowId) {
        edit.skipRow(rowId);
    }

    /**
     * «Копировать»: строка таблицы в буфер обмена.
     *
     * @param rowId идентификатор строки
     */
    public void copyRow(String rowId) {
        edit.findRow(rowId).ifPresent(edit::copyRow);
    }

    /** «Отменить» (Ctrl+Z). */
    public void undo() {
        edit.undo();
    }

    /** «Повторить» (Ctrl+Y). */
    public void redo() {
        edit.redo();
    }

    /** «Параметры плана…». */
    public void planSettings() {
        planSettings(interactive());
    }

    /**
     * Диалог 2 «Параметры плана».
     *
     * @param request запрос открытия
     */
    public void planSettings(OpenRequest request) {
        edit.planSettings(request);
    }

    /** «Актуализировать на сегодня…». */
    public void actualize() {
        actualize(interactive());
    }

    /**
     * Подтверждение актуализации ({@code ALERT}, назначение {@code actualize}).
     *
     * @param request запрос открытия
     */
    public void actualize(OpenRequest request) {
        edit.actualize(request);
    }

    /** «Сверить баланс…» (проверяет, что сегодня внутри горизонта). */
    public void reconcile() {
        edit.reconcileCommand();
    }

    /**
     * Диалог 7 «Сверить баланс» ({@code TextInputDialog}).
     *
     * @param request запрос открытия
     */
    public void reconcile(OpenRequest request) {
        edit.reconcile(request);
    }

    /**
     * Горизонт плана в месяцах (слайдер в меню «Вид»).
     *
     * @param months 1..600
     */
    public void setHorizonMonths(int months) {
        edit.setHorizonMonths(months);
    }

    /** «Горизонт в месяцах…» (ввод числа). */
    public void customMonths() {
        customMonths(interactive());
    }

    /**
     * Ввод горизонта в месяцах ({@code TextInputDialog}, назначение {@code customMonths}).
     *
     * @param request запрос открытия
     */
    public void customMonths(OpenRequest request) {
        edit.customHorizon(request);
    }

    // ================================================================== Инструменты

    /** «Калькулятор цели…» (Ctrl+G). */
    public void goalCalculator() {
        goalCalculator(interactive());
    }

    /**
     * Диалог 6 «Калькулятор цели» (немодальный, единственный).
     *
     * @param request запрос открытия
     */
    public void goalCalculator(OpenRequest request) {
        tools.goalCalculator(request);
    }

    /**
     * Включено ли «Доходы −10 %».
     *
     * @return {@code true}, если включено
     */
    public boolean isIncomeWhatIf() {
        return tools.isIncomeWhatIf();
    }

    /**
     * Включено ли «Расходы +10 %».
     *
     * @return {@code true}, если включено
     */
    public boolean isExpenseWhatIf() {
        return tools.isExpenseWhatIf();
    }

    /**
     * «Что-если → Доходы −10 %».
     *
     * @param on включить
     */
    public void setIncomeWhatIf(boolean on) {
        tools.setIncomeWhatIf(on);
    }

    /**
     * «Что-если → Расходы +10 %».
     *
     * @param on включить
     */
    public void setExpenseWhatIf(boolean on) {
        tools.setExpenseWhatIf(on);
    }

    /**
     * «Что-если → Откладывать доп.».
     *
     * @param saving сумма в месяц (0 — выключено)
     */
    public void setExtraSaving(Money saving) {
        tools.setExtraSaving(saving);
    }

    /** «Что-если → Применить к плану…». */
    public void applyWhatIf() {
        applyWhatIf(interactive());
    }

    /**
     * Подтверждение «Применить к плану» ({@code ALERT}, назначение {@code applyWhatIf}).
     *
     * @param request запрос открытия
     */
    public void applyWhatIf(OpenRequest request) {
        tools.applyWhatIf(request);
    }

    /** «Что-если → Сбросить». */
    public void resetWhatIf() {
        tools.resetWhatIf();
    }

    /** «Проверить план» (диалог 15 «Диагностика»). */
    public void validatePlan() {
        tools.validatePlanCommand();
    }

    /** Синоним {@link #validatePlan()}: диалог 15 «Диагностика». */
    public void diagnostics() {
        tools.validatePlanCommand();
    }

    /** «Очистить неиспользуемые корректировки». */
    public void removeOrphanAdjustments() {
        tools.removeOrphansCommand();
    }

    /** «Валюта…». */
    public void currency() {
        currency(interactive());
    }

    /**
     * Диалог 8 «Валюта» ({@code ChoiceDialog}).
     *
     * @param request запрос открытия
     */
    public void currency(OpenRequest request) {
        tools.currency(request);
    }

    /**
     * Ввод своей валюты ({@code TextInputDialog}).
     *
     * @param request запрос открытия
     */
    public void customCurrency(OpenRequest request) {
        tools.customCurrency(request);
    }

    // ================================================================== Восстановление

    /**
     * «Хранилище по умолчанию: Реестр Windows / XML-файл».
     *
     * @param kind хранилище
     */
    public void setDefaultStore(RecoveryStoreKind kind) {
        recovery.setDefaultStore(kind);
    }

    /** «Сделать снимок сейчас». */
    public void snapshotNow() {
        recovery.snapshotNow();
    }

    /** «Показать последний снимок…». */
    public void showLastSnapshot() {
        recovery.showLastSnapshot();
    }

    /** «Очистить снимки» (с подтверждением). */
    public void clearSnapshots() {
        clearSnapshots(interactive());
    }

    /**
     * Подтверждение «Очистить снимки» ({@code ALERT}, назначение {@code clearSnapshots}).
     *
     * @param request запрос открытия
     */
    public void clearSnapshots(OpenRequest request) {
        recovery.clearSnapshots(request);
    }

    /** «Симулировать сбой → Аварийное завершение процесса»: подтверждение, затем {@code halt(3)} без сохранения. */
    public void simulateHalt() {
        recovery.simulateHalt();
    }

    /** Синоним {@link #simulateHalt()}: подтверждение аварийного завершения. */
    public void confirmCrash() {
        recovery.simulateHalt();
    }

    /** «Симулировать сбой → Необработанное исключение». */
    public void simulateException() {
        recovery.simulateException();
    }

    /**
     * Диалог 17 «Восстановление» до главного окна.
     *
     * @param detection результат {@code CrashDetector.detect}
     * @param stores    хранилища клиента в порядке «реестр, XML»
     * @param preferred хранилище по умолчанию из настроек
     * @param onChoice  выбор пользователя (хранилище или «Не восстанавливать»)
     * @return показанный диалог (самотест отвечает на него программно)
     */
    public SwingRecoveryDialog showRecoveryDialog(CrashDetector.Detection detection, List<SessionStore> stores,
                                                  RecoveryStoreKind preferred, Consumer<SwingRecoveryDialog.Choice> onChoice) {
        return recovery.showRecoveryDialog(detection, stores, preferred, onChoice);
    }

    /**
     * Восстанавливает сессию из выбранного хранилища.
     *
     * @param store        хранилище
     * @param target       главное окно
     * @param factory      фабрика окон
     * @param onLoadFailed что сделать, если снимок не читается (обычно — обычный запуск)
     * @param done         отчёт восстановления
     */
    public void restore(SessionStore store, RestoreTarget target, WindowFactory factory, Runnable onLoadFailed,
                        Consumer<RestoreReport> done) {
        recovery.restore(store, target, factory, onLoadFailed, done);
    }

    /**
     * «Не восстанавливать»: очищает хранилища клиента до запуска записи.
     *
     * @param stores хранилища клиента
     */
    public void startFresh(List<SessionStore> stores) {
        recovery.startFresh(stores);
    }

    /**
     * «CashPrediction уже запущен. Открыть без восстановления и без записи сессии?».
     *
     * @param onAnswer {@code true} — открыть с отключённым рекордером, {@code false} — выйти
     * @return показанное сообщение (самотест отвечает на него программно)
     */
    public SwingAlert confirmAlreadyRunning(Consumer<Boolean> onAnswer) {
        return recovery.confirmAlreadyRunning(onAnswer);
    }

    /**
     * Показывает замечания отчёта восстановления, если они есть.
     *
     * @param report отчёт
     */
    public void showRestoreReport(RestoreReport report) {
        recovery.showRestoreReport(report);
    }

    // ================================================================== Справка

    /** «О программе» (F1). */
    public void about() {
        help.about();
    }

    /** «Горячие клавиши». */
    public void hotkeys() {
        help.hotkeys();
    }

    /** «Формат файла .md». */
    public void formatHelp() {
        help.formatHelp();
    }

    // ================================================================== сообщения

    /**
     * Показывает сообщение об ошибке (диалог 16).
     *
     * @param header  что не получилось
     * @param message почему
     */
    public void showError(String header, String message) {
        shared.alerts().error(header, message);
    }

    /**
     * Показывает сообщение об ошибке со стеком (диалог 16).
     *
     * @param header что не получилось
     * @param error  исключение
     */
    public void showError(String header, Throwable error) {
        shared.alerts().error(header, error);
    }

    /**
     * Показывает информационное сообщение.
     *
     * @param header  крупный текст
     * @param content пояснение
     */
    public void showInfo(String header, String content) {
        shared.alerts().info(header, content);
    }
}
