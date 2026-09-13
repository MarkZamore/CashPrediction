package ru.cashprediction.fx.action;

import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.fx.dialog.FxDialogHost;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.dialog.RecoveryChoice;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Фасад команд JavaFX-клиента: всё, что умеют меню, панель инструментов, контекстные меню, фабрика окон
 * восстановления и самотест, собрано здесь.
 *
 * <p>Команды разнесены по группам: {@link FileCommands} (Файл), {@link EditCommands} (Правка),
 * {@link ToolsCommands} (Инструменты), {@link SessionCommands} (Восстановление), {@link HelpCommands} (Справка).
 * Методы, открывающие восстанавливаемые окна, принимают {@link OpenRequest}: пункт меню передаёт
 * {@code OpenRequest.fromMain()}, фабрика окон — состояние из снимка. Поэтому окно после сбоя открывается тем же
 * кодом, что и вручную.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class FxActions {

    private final CommandSupport support;
    private final FileCommands file;
    private final EditCommands edit;
    private final ToolsCommands tools;
    private final SessionCommands session;
    private final HelpCommands help;

    /**
     * Создаёт фасад.
     *
     * @param context контекст приложения (главное окно, документ, рекордер, настройки)
     * @param host    хост диалогов
     */
    public FxActions(FxAppContext context, FxDialogHost host) {
        this.support = new CommandSupport(context, host);
        this.file = new FileCommands(support);
        this.edit = new EditCommands(support);
        this.tools = new ToolsCommands(support);
        this.session = new SessionCommands(support);
        this.help = new HelpCommands(support);
    }

    // ================================================================== Файл

    /**
     * «Новый план…» (Ctrl+N): при несохранённых изменениях сначала спрашивает, сохранить ли их.
     *
     * @param request запрос открытия (при восстановлении вопрос не задаётся)
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

    /**
     * «Открыть…» (Ctrl+O): список планов папки и «Из файла…».
     *
     * @param request запрос открытия
     */
    public void openPlan(OpenRequest request) {
        if (request.isRestore()) {
            file.openPlan(request);
        } else {
            file.confirmDiscard(() -> file.openPlan(request));
        }
    }

    /** «Открыть из файла…»: сразу окно выбора файла. */
    public void openFromFile() {
        file.confirmDiscard(file::chooseFileAndLoad);
    }

    /**
     * Пункт подменю «Недавние».
     *
     * @param settingsName имя из настроек (имя файла в CashMemory или полный путь)
     */
    public void openRecent(String settingsName) {
        file.confirmDiscard(() -> file.openRecent(settingsName));
    }

    /** «Открыть пример». */
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
        return file.loadPlan(path, null);
    }

    /**
     * Читает план при восстановлении: замечания уходят в отчёт восстановления, а не в модальные окна.
     *
     * @param path файл плана
     * @param warn приёмник предупреждений
     * @return {@code true}, если план открыт
     */
    public boolean loadPlanForRestore(Path path, Consumer<String> warn) {
        return file.loadPlan(path, warn);
    }

    /**
     * Запоминает время изменения файла открытого плана (для диалога «Файл изменён снаружи»).
     *
     * @param path файл или {@code null}
     */
    public void trackFile(Path path) {
        file.trackFile(path);
    }

    /**
     * «Сохранить» (Ctrl+S).
     *
     * @param done {@code true}, если план сохранён
     */
    public void save(Consumer<Boolean> done) {
        file.save(done);
    }

    /**
     * «Сохранить как…» (Ctrl+Shift+S).
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
     * «Переименовать…» (F2).
     *
     * @param request запрос открытия
     */
    public void rename(OpenRequest request) {
        file.rename(request);
    }

    /**
     * «Экспорт CSV…» (Ctrl+Shift+C).
     *
     * @param request запрос открытия
     */
    public void exportCsv(OpenRequest request) {
        file.exportCsv(request);
    }

    /** «Папка CashMemory…» (диалог 13: путь и выбор другой папки планов на время сеанса). */
    public void chooseCashMemoryFolder() {
        file.chooseCashMemoryFolder();
    }

    /**
     * «Сохранить график PNG…» (только настольные клиенты).
     *
     * @param chart узел графика
     */
    public void saveChartPng(javafx.scene.Node chart) {
        file.saveChartPng(chart);
    }

    /**
     * Первая половина выхода (закрытие главного окна, «Файл → Выход»): {@code recorder.saveNow()} и, если план
     * изменён, «Сохранить изменения в плане?». После {@code true} оболочка пишет настройки, вызывает
     * {@code recorder.shutdownClean()} и завершает программу.
     *
     * @param proceed {@code true} — выходить; {@code false} — пользователь нажал «Отмена»
     */
    public void confirmExit(Consumer<Boolean> proceed) {
        file.confirmExit(proceed);
    }

    // ================================================================== Правка

    /**
     * «Добавить доход…» (Ctrl+I) / «Добавить расход…» (Ctrl+E).
     *
     * @param kind    тип операции
     * @param request запрос открытия
     */
    public void addRule(Kind kind, OpenRequest request) {
        edit.addRule(kind, request);
    }

    /**
     * Изменение регулярной операции.
     *
     * @param id      идентификатор правила
     * @param request запрос открытия
     */
    public void editRule(RuleId id, OpenRequest request) {
        edit.editRule(id, request);
    }

    /**
     * Удаление регулярной операции с подтверждением.
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
        edit.disableRule(id);
    }

    /**
     * «Разовая операция…» (Ctrl+T).
     *
     * @param date    дата или {@code null}
     * @param kind    тип операции
     * @param request запрос открытия
     */
    public void addOneTime(LocalDate date, Kind kind, OpenRequest request) {
        edit.addOneTime(date, kind, request);
    }

    /**
     * Изменение разовой операции.
     *
     * @param id      идентификатор операции
     * @param request запрос открытия
     */
    public void editOneTime(TxId id, OpenRequest request) {
        edit.editOneTime(id, request);
    }

    /**
     * Удаление разовой операции с подтверждением.
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
     * @param key     событие
     * @param request запрос открытия
     */
    public void adjustOccurrence(OccurrenceKey key, OpenRequest request) {
        edit.adjustOccurrence(key, request);
    }

    /**
     * «Пропустить» событие (контекстное меню строки): корректировка «пропустить» без диалога.
     *
     * @param key событие правила
     */
    public void skipOccurrence(OccurrenceKey key) {
        edit.skipOccurrence(key);
    }

    /**
     * «Вернуть как по правилу»: удаляет корректировку события.
     *
     * @param key событие правила
     */
    public void resetOccurrence(OccurrenceKey key) {
        edit.resetOccurrence(key);
    }

    /**
     * Применяет быструю правку суммы.
     *
     * @param key    событие
     * @param amount новая сумма
     */
    public void quickEditAmount(OccurrenceKey key, Money amount) {
        edit.quickEditAmount(key, amount);
    }

    /**
     * Разбирает сумму быстрой правки: положительная и не больше предела.
     *
     * @param text текст поля
     * @return сумма или пусто
     */
    public static Optional<Money> parsePositiveAmount(String text) {
        return EditCommands.positiveAmount(text);
    }

    /**
     * «Изменить…» (Enter) для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void editRow(String rowId) {
        edit.editRow(rowId);
    }

    /**
     * «Удалить…» (Delete) для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    public void deleteRow(String rowId) {
        edit.deleteRow(rowId);
    }

    /**
     * «Скорректировать событие…» (Ctrl+J) для строки таблицы.
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

    /** «Отменить» (Ctrl+Z). */
    public void undo() {
        support.document().undo();
    }

    /** «Повторить» (Ctrl+Y). */
    public void redo() {
        support.document().redo();
    }

    /**
     * «Параметры плана…».
     *
     * @param request запрос открытия
     */
    public void planSettings(OpenRequest request) {
        edit.planSettings(request);
    }

    /**
     * «Актуализировать на сегодня…».
     *
     * @param request запрос открытия
     */
    public void actualize(OpenRequest request) {
        edit.actualize(request);
    }

    /**
     * «Сверить баланс…».
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

    /**
     * «Горизонт в месяцах…» (ввод числа).
     *
     * @param request запрос открытия
     */
    public void customMonths(OpenRequest request) {
        edit.customMonths(request);
    }

    // ================================================================== Инструменты

    /**
     * «Калькулятор цели…» (Ctrl+G).
     *
     * @param request запрос открытия
     */
    public void goalCalculator(OpenRequest request) {
        tools.goalCalculator(request);
    }

    /**
     * «Что-если → Применить к плану…».
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

    /** «Проверить план». */
    public void validatePlan() {
        tools.validatePlan();
    }

    /** «Очистить неиспользуемые корректировки». */
    public void removeOrphanAdjustments() {
        tools.removeOrphanAdjustments();
    }

    /**
     * «Валюта…».
     *
     * @param request запрос открытия
     */
    public void currency(OpenRequest request) {
        tools.currency(request);
    }

    /**
     * Ввод своей валюты.
     *
     * @param request запрос открытия
     */
    public void customCurrency(OpenRequest request) {
        tools.customCurrency(request);
    }

    // ================================================================== Восстановление

    /** «Сделать снимок сейчас». */
    public void snapshotNow() {
        session.snapshotNow();
    }

    /** «Показать последний снимок…». */
    public void showLastSnapshot() {
        session.showLastSnapshot();
    }

    /**
     * «Очистить снимки…».
     *
     * @param request запрос открытия
     */
    public void clearSnapshots(OpenRequest request) {
        session.clearSnapshots(request);
    }

    /** «Симулировать сбой → Аварийное завершение процесса». */
    public void simulateHalt() {
        session.simulateHalt();
    }

    /** «Симулировать сбой → Необработанное исключение». */
    public void simulateException() {
        session.simulateException();
    }

    /**
     * Диалог 17 «Восстановление» при старте, до главного окна (результат колбэком, без блокировки).
     *
     * @param detection результат {@code CrashDetector.detect} со статусом {@code CRASHED}
     * @param choice    выбор пользователя; крестик окна — {@link RecoveryChoice#NONE}
     */
    public void recovery(CrashDetector.Detection detection, Consumer<RecoveryChoice> choice) {
        session.recovery(detection, choice);
    }

    /**
     * Загружает снимок из хранилища, выбранного в диалоге восстановления.
     *
     * @param choice выбор пользователя
     * @return снимок или пусто («Не восстанавливать» или снимка нет)
     * @throws SessionStoreException снимок повреждён или хранилище не читается
     */
    public Optional<SessionSnapshot> loadSnapshot(RecoveryChoice choice) throws SessionStoreException {
        return session.loadSnapshot(choice);
    }

    /**
     * Завершает восстановление: при {@code RECORDER_NOT_STARTED} предлагает сохранить план из снимка в файл и
     * запускает запись сеанса; прочие предупреждения отчёта показывает сообщением.
     *
     * @param snapshot снимок, из которого восстанавливались
     * @param report   отчёт координатора
     * @param done     вызывается после ответов пользователя (может быть {@code null})
     */
    public void restoreFinished(SessionSnapshot snapshot, RestoreReport report, Runnable done) {
        session.restoreFinished(snapshot, report, done);
    }

    /**
     * «CashPrediction уже запущен. Открыть без восстановления и без записи сессии?» При согласии рекордер
     * отключается.
     *
     * @param openAnyway {@code true} — обычный запуск, {@code false} — выйти
     */
    public void alreadyRunning(Consumer<Boolean> openAnyway) {
        session.alreadyRunning(openAnyway);
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
        support.error(header, message);
    }

    /**
     * Показывает сообщение об ошибке со стеком (диалог 16).
     *
     * @param header что не получилось
     * @param error  исключение
     */
    public void showError(String header, Throwable error) {
        support.error(header, error);
    }

    /**
     * Показывает сообщение об ошибке со стеком (диалог 16) с общим заголовком. Для исключений, которые
     * программа ожидает и переживает; необработанные исключения обрабатывает {@code FxCrashHooks}.
     *
     * @param error исключение
     */
    public void showError(Throwable error) {
        support.error("Операция не выполнена", error);
    }

    /**
     * Показывает информационное сообщение.
     *
     * @param header  крупный текст
     * @param content пояснение
     */
    public void showInfo(String header, String content) {
        support.info(header, content);
    }
}
