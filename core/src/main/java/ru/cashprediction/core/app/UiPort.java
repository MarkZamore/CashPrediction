package ru.cashprediction.core.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
 * Порт «ядро → клиент» (архитектура §3.6): всё, что ядро просит сделать инструмент интерфейса. Реализации:
 * {@code FxUiPort} (JavaFX), {@code SwingUiPort} (Swing), {@code WebUiPort} (эффекты web-протокола §5) и
 * тестовый {@code FakeUiPort}.
 *
 * <p><b>Потоки (архитектура §3.9).</b> Ядро вызывает методы порта только в потоке контроллера
 * ({@link #executor()}: FX Application Thread, Swing EDT или web {@code ControllerThread}). Все обратные вызовы
 * ({@code onButton}, {@code onResult}) клиент выполняет в том же потоке и ровно один раз.</p>
 *
 * <p><b>Порт ничего не решает:</b> тексты, доступность, отметки, цвета и порядок уже в моделях.</p>
 */
public interface UiPort {

    /** @return вид клиента, способ выбора файлов и прочие различия §10 */
    ClientProfile profile();

    /** @return исполнитель потока контроллера; {@code isUiThread()} проверяется на входе каждого намерения */
    UiExecutor executor();

    /** @return планировщик таймеров контроллера (автосохранение, настройки, статус 10 с); задачи возвращаются в поток контроллера */
    Scheduler scheduler();

    /**
     * Показать главное окно первый раз.
     *
     * @param model    полная модель экрана
     * @param restored состояние главного окна из снимка (границы, развёрнутость) или {@code null} при обычном запуске;
     *                 границы применяются, если окно ≥ 400×300 и видно на экране (§2)
     */
    void showMain(MainScreenModel model, MainWindowState restored);

    /**
     * Перерисовать изменившиеся части главного окна. Контроллер хеширует части и передаёт только изменившиеся.
     *
     * @param model   новая модель экрана
     * @param changed изменившиеся части (не пусто)
     */
    void render(MainScreenModel model, EnumSet<ScreenPart> changed);

    /** @return текущие границы и развёрнутость главного окна (для снимка сеанса) */
    MainGeometry mainGeometry();

    /**
     * Открыть окно формы. Клиент строит виджеты по {@code spec}, заполняет их из {@code initial}, привязывает каждый
     * виджет к id модели, показывает окно и вызывает {@code session.shown()}; события виджетов передаёт в
     * {@code session} (изменение поля, кнопка, предпросмотр, границы, закрытие крестиком → {@code closeRequested()}).
     *
     * @param session   сеанс формы в ядре
     * @param spec      неизменная раскладка формы
     * @param initial   начальная модель
     * @param placement владелец и положение
     * @return ручка открытого окна
     */
    WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement);

    /**
     * Показать сообщение (JavaFX {@code Alert} → Swing {@code SwingAlert} → Web {@code <dialog>}).
     *
     * <p>Может быть вызван до {@link #showMain}: диалог восстановления после сбоя (§6.29) и «уже запущен» (§6.28)
     * показываются раньше главного окна. Desktop — владелец не задан (окно по центру экрана); web — сообщение
     * поверх неактивной страницы (эффект {@code inert}).</p>
     *
     * @param spec      описание сообщения
     * @param session   сеанс для восстанавливаемых назначений (deleteRule, deleteOneTime, actualize, applyWhatIf,
     *                  clearSnapshots) или {@code null}; клиент вызывает у него {@code shown()} и {@code closed()}
     * @param onButton  вызывается ровно один раз с id нажатой кнопки; крестик и Esc — id кнопки роли CANCEL
     * @return ручка открытого окна
     */
    WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton);

    /**
     * Показать контекстное меню, открытое с клавиатуры (Shift+F10 / Menu, команда {@code ui.contextMenu}, §5.2, §7).
     * Меню по правой кнопке клиент показывает сам после запроса {@code UiIntents.contextMenu}; этот метод нужен, когда
     * меню открывает ядро. JavaFX {@code ContextMenu} (событие {@code ContextMenuEvent} с клавиатуры) → Swing
     * {@code JPopupMenu} → Web эффект {@code contextMenu}.
     *
     * <p>Положение: для строки — у левого нижнего края ячейки «Операция» выделенной строки (строка сначала
     * прокручивается в видимую область); для карточки — у левого нижнего края карточки. Выбранный пункт клиент
     * передаёт в {@code UiIntents.command} с источником {@code CONTEXT_MENU}.</p>
     *
     * @param target объект меню
     * @param items  пункты (не пусто)
     */
    void showContextMenu(ContextTarget target, List<MenuNode> items);

    /**
     * Показать нативный или Swing-выбор файла. Не вызывается для клиентов {@link ChooserKind#SERVER_BROWSER}.
     *
     * @param spec     запрос
     * @param onResult вызывается ровно один раз: выбранный путь как есть (без дописанного расширения) или пусто при отмене
     */
    void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult);

    /**
     * Показать выбор папки. Не вызывается для клиентов {@link ChooserKind#SERVER_BROWSER}.
     *
     * @param spec     запрос
     * @param onResult вызывается ровно один раз: папка или пусто при отмене
     */
    void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult);

    /**
     * Нарисовать сцену графика в PNG (1200×700, светлые цвета; FX — внеэкранный Canvas, Swing и web-сервер —
     * Java2D). Синхронно в потоке контроллера; запись файла выполняет ядро.
     *
     * @param scene сцена
     * @return байты PNG
     * @throws IOException если изображение не закодировано
     */
    byte[] renderChartPng(ChartScene scene) throws IOException;

    /**
     * Перевести фокус.
     *
     * @param target цель
     */
    void focus(FocusTarget target);

    /**
     * Показать строку таблицы.
     *
     * @param rowId id строки текущей модели таблицы
     * @param mode  прокрутить наверх или выделить и прокрутить
     */
    void revealRow(String rowId, RevealMode mode);

    /**
     * Скопировать текст в буфер обмена (web: {@code navigator.clipboard.writeText} внутри жеста пользователя).
     *
     * @param text текст
     */
    void copyToClipboard(String text);

    /**
     * Завершить процесс или сервер. После вызова ядро больше не обращается к порту.
     *
     * @param kind вид завершения
     * @param code код выхода процесса (0 — обычный, 2 — необработанная ошибка, 3 — симуляция сбоя)
     */
    void exit(ExitKind kind, int code);
}
