package ru.cashprediction.core.app.flow;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.app.UiPort;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/**
 * Общий контекст потоков приложения (дополнение к архитектуре §3.7). Реализует {@code AppController}; потоки получают
 * контекст в конструкторе и не держат собственного изменяемого состояния приложения.
 *
 * <p><b>Заморожен на этапе S0.</b> Это единственный канал между контроллером (задача S2 core-app-file) и потоками
 * задач core-app-edit и core-app-session, которые пишутся параллельно. Ни одна задача S2 не меняет этот файл;
 * недостающая операция — вопрос к ведущему, который меняет контракт для всех сразу.</p>
 *
 * <p>Все методы — только в потоке контроллера. После изменения состояния поток вызывает {@link #refresh()} (или
 * изменение само вызывает его, если так написано у метода).</p>
 */
public interface FlowContext {

    /** @return порт клиента */
    UiPort port();

    /** @return окружение процесса */
    AppEnvironment environment();

    /** @return документ плана (изменения — через {@code EditFlow.edit}, чтобы была одна точка отмены и статусов) */
    PlanDocument document();

    /** @return свежий снимок состояния */
    AppState state();

    /** @return запись сеанса или {@code null}, пока {@code StartupFlow} не установил её ({@link #installRecorder}) */
    SessionRecorder recorder();

    /**
     * Устанавливает запись сеанса, созданную {@code StartupFlow} (ровно один раз за процесс). Контекст подписывается на
     * результаты записи ({@code SessionRecorder.addStatusListener}), кладёт их в {@code AppState.stores} и
     * перерисовывает строку состояния; статус записи становится {@link RecorderStatus#RECORDING} после
     * {@code recorder.start()}, который вызывает сам {@code StartupFlow}.
     *
     * @param recorder запись сеанса
     * @throws IllegalStateException если запись уже установлена
     */
    void installRecorder(SessionRecorder recorder);

    /**
     * Меняет состояние записи сеанса для строки состояния и меню «Восстановление» ({@code PENDING_RESTORE} пока
     * открыт диалог восстановления, {@code DISABLED_SECOND_INSTANCE} для второго экземпляра). Вызывает
     * {@link #refresh()}.
     *
     * @param status новое состояние
     */
    void setRecorderStatus(RecorderStatus status);

    /**
     * Запоминает последнюю проблему автосохранения (§6.31, решение L4: пропуск не бывает молчаливым) или снимает её.
     * Вызывает {@link #refresh()}.
     *
     * @param textOrEmpty готовый текст или пустая строка / {@code null}, когда проблема исчезла
     */
    void setAutosaveProblem(String textOrEmpty);

    /**
     * Меняет настройки приложения и планирует их запись через 700 мс ({@code SettingsKeeper}).
     *
     * @param change изменение
     */
    void updateSettings(UnaryOperator<AppSettings> change);

    /**
     * Меняет вид (режим, период, флажки, фильтр, «что-если»); вид и флажки попадают в настройки, «что-если» — только в
     * снимок. Вызывает {@link #refresh()}.
     *
     * @param change изменение
     */
    void updateView(UnaryOperator<ViewState> change);

    /**
     * Меняет выделение таблицы (без прокрутки; прокрутку просит {@code port.revealRow}).
     *
     * @param rowId id строки или пустая строка
     */
    void setSelection(String rowId);

    /**
     * Раскрывает или сворачивает группу «Прошедшие события».
     *
     * @param expanded раскрыта ли
     */
    void setPastExpanded(boolean expanded);

    /**
     * Меняет папку, из которой открываются планы (§6.22).
     *
     * @param folder папка или {@code null} — вернуться к CashMemory
     */
    void setPlansFolder(Path folder);

    /**
     * Показывает временное сообщение строки состояния на 10 с.
     *
     * @param level уровень
     * @param key   ключ {@code status.msg.*} или {@code status.hint.*}
     * @param args  аргументы подстановок
     */
    void status(StatusLevel level, String key, Object... args);

    /**
     * Устанавливает или снимает постоянное сообщение причины ({@code settingsFailed}, {@code forecastFailed}).
     *
     * @param causeId id причины
     * @param textOrNull готовый текст или {@code null}, чтобы снять сообщение
     */
    void persistentStatus(String causeId, String textOrNull);

    /**
     * Открывает форму. Один путь для меню, дочерних окон ({@code FormOutcome.OpenChild}) и восстановления после сбоя
     * ({@code CoreWindowFactory}).
     *
     * <p><b>Порядок:</b></p>
     * <ol>
     *   <li>id окна: {@code request.restored().windowId()}, если форма восстанавливается, иначе
     *       {@code recorder.nextWindowId()} (до установки записи — собственный счётчик {@code w1, w2, …});</li>
     *   <li>владелец: {@code placement.ownerId()}, если {@code placement} задан; иначе верхнее модальное окно или
     *       главное окно;</li>
     *   <li>модальность — {@code request.modal()} (калькулятор цели и быстрая правка — {@code false});</li>
     *   <li>создаётся {@code FormSession}; для {@code request.restored() != null} вызывается
     *       {@code session.applyState(restored)} (значения, страница, границы);</li>
     *   <li>окно добавляется в {@code OpenWindows} (ключ единственного экземпляра — имя {@code WindowType} для
     *       GOAL_CALCULATOR и QUICK_EDIT_POPUP), вызывается {@code port.openForm(session, spec, view, placement)} и
     *       {@code session.attach(handle)};</li>
     *   <li>при закрытии окно убирается из {@code OpenWindows}, вызывается {@code onResult}.</li>
     * </ol>
     *
     * @param request     что открыть: логика, тип окна, модальность, контекст, состояние из снимка
     * @param placement   владелец и положение ({@code Placement.underCell} для быстрой правки,
     *                    {@code Placement.restored} для восстановления) или {@code null} — по центру над верхним
     *                    модальным окном или главным окном
     * @param onResult    вызывается при закрытии с результатом {@code FormOutcome.Close} ({@code null} — отмена) и при
     *                    {@code FormOutcome.Apply} с действием; может быть {@code null}
     * @return сеанс открытой формы
     */
    FormSession openForm(FormRequest request, Placement placement, Consumer<Object> onResult);

    /**
     * Сеанс открытой формы по id окна (например, чтобы закрыть быструю правку при переходе в график, открытии плана
     * или изменении плана извне: {@code session(id).ifPresent(FormSession::closeRequested)}, §5.6.1).
     *
     * @param windowId id окна
     * @return сеанс или пусто, если окно закрыто или это сообщение
     */
    Optional<FormSession> session(String windowId);

    /**
     * Сеанс формы единственного экземпляра ({@code GOAL_CALCULATOR}, {@code QUICK_EDIT_POPUP}): повторный вызов
     * калькулятора цели поднимает окно {@code singleInstance(...).ifPresent(s -> s.handle().toFront())} (§6.6).
     *
     * @param singleInstanceKey имя {@code WindowType}
     * @return сеанс или пусто
     */
    Optional<FormSession> singleInstance(String singleInstanceKey);

    /**
     * Показывает сообщение (модальное, владелец — верхнее модальное окно или главное).
     *
     * <p>Если {@code spec.restorable()} (назначения {@code AlertCatalog.RESTORABLE_PURPOSES}), контекст сам создаёт
     * {@code AlertSession} с id из записи сеанса и передаёт её в {@code port.showAlert}; иначе сеанс не создаётся.
     * Сообщение попадает в {@code OpenWindows} до ответа. Может вызываться до показа главного окна
     * ({@code StartupFlow}: диалог восстановления, «уже запущен»).</p>
     *
     * @param spec     описание
     * @param onButton id нажатой кнопки, ровно один раз
     * @return ручка окна сообщения (например, чтобы обновить кнопки {@code updateAlert})
     */
    WindowHandle showAlert(AlertSpec spec, Consumer<String> onButton);

    /** Перестраивает модель экрана и вызывает {@code port.render} с изменившимися частями. */
    void refresh();

    /** @return поток «Файл» */
    FileFlow files();

    /** @return поток правки */
    EditFlow edits();

    /** @return поток «Вид» */
    ViewFlow views();

    /** @return поток «Инструменты» */
    ToolsFlow tools();

    /** @return поток «Восстановление» */
    RecoveryFlow recovery();

    /** @return поток «Справка» */
    HelpFlow help();

    /** @return поток выхода */
    ExitFlow exit();

    /** @return служба выбора файлов */
    FileChooserService choosers();

    /** @return автосохранение */
    AutosaveService autosave();

    /** @return запись настроек */
    SettingsKeeper settingsKeeper();

    /** @return проверка внешних изменений файла */
    ExternalChangeGuard externalChanges();
}
