package ru.cashprediction.core.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.FocusScope;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.menu.MenuNode;
import ru.cashprediction.core.ui.view.chart.ChartHover;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.ui.view.popup.CalendarModel;
import ru.cashprediction.core.ui.view.popup.DayCardModel;
import ru.cashprediction.core.ui.view.popup.SparklineModel;

/**
 * Намерения «клиент → ядро» главного окна (архитектура §3.6). Реализует {@code AppController}; все клиенты и
 * web-API вызывают только этот интерфейс (события окон форм идут в {@code FormSession}). Web-протокол (§5)
 * отображает каждый метод на тип {@code intent} или {@code query} один к одному.
 *
 * <p><b>Потоки.</b> Все методы вызываются в потоке контроллера ({@code UiPort.executor().isUiThread()} проверяется
 * на входе). <b>Модальность.</b> Пока открыто модальное окно, намерения главного окна игнорируются: команды
 * источников MENU, TOOLBAR, CONTEXT_MENU, HOTKEY, MAIN, SELFTEST, а также {@link #key}, {@link #selectRow},
 * {@link #activateRow}, {@link #filterText}, {@link #sliderCommit}, {@link #spinnerCommit}, {@link #menuHover}.
 * Команды источника {@code InvokeSource.FORM} выполняются, если их форма — верхнее модальное окно (см.
 * {@link InvokeSource}). Запросы ({@code contextMenu}, {@code tableTooltip} и др.), {@link #mainGeometry},
 * {@link #closeMainRequested} и {@link #uncaught} отвечают как обычно.</p>
 */
public interface UiIntents {

    /**
     * Выполнить команду: проверка модальности (с учётом {@link InvokeSource}), затем {@code CommandAvailability};
     * отключённая команда из горячей клавиши даёт подсказку {@code status.hint.*}, из прочих источников — ничего.
     *
     * @param id     команда
     * @param args   аргументы (строка, дата, карточка, пункт «Недавние»; для {@code InvokeSource.FORM} в
     *               {@code key} — id окна формы)
     * @param source откуда пришла команда
     */
    void command(CommandId id, CommandArgs args, InvokeSource source);

    /**
     * Нажатие клавиши, пойманное единым диспетчером клиента до обработки инструментом.
     *
     * <p><b>Фильтр.</b> Перед отправкой Enter из поля фильтра ({@code filter.focusTable}) клиент сначала отправляет
     * ещё не переданный текст поля через {@link #filterText} без задержки, чтобы ядро применило именно видимый текст.</p>
     *
     * <p><b>Enter и Esc в окнах форм</b> сюда не приходят: клиент передаёт их в {@code FormSession}
     * ({@code fieldSubmitted}, {@code closeRequested}).</p>
     *
     * @param chord   сочетание по физической клавише
     * @param scope   область фокуса
     * @param focusId уточнение фокуса: id карточки для {@code FocusScope.CARD} (нужно Shift+F10 / Menu), id окна для
     *                {@code TEXT_INPUT} и {@code POPUP}; иначе пустая строка
     * @return {@code true}, если ядро обработало нажатие (клиент поглощает событие); {@code false} — инструмент
     *         обрабатывает клавишу сам (стрелки таблицы, ввод текста)
     */
    boolean key(KeyChord chord, FocusScope scope, String focusId);

    /**
     * Пользователь выделил строку (мышью или стрелками). Выделение хранится по rowId и попадает в снимок.
     *
     * @param rowId id строки или пустая строка — выделения нет
     */
    void selectRow(String rowId);

    /**
     * Щелчок по строке таблицы (одиночный щелчок по PAST_HEADER переключает группу, двойной по сумме — быстрая
     * правка, двойной по прочим ячейкам — «Изменить…», §5.2 «Мышь и клавиатура»).
     *
     * @param rowId    id строки
     * @param columnId id колонки под указателем
     * @param how      одиночный или двойной щелчок
     */
    void activateRow(String rowId, String columnId, Activation how);

    /**
     * Текст поля фильтра изменился (клиент уже выдержал задержку 300 мс). Перед Enter в поле фильтра клиент вызывает
     * метод без задержки (см. {@link #key}).
     *
     * @param text текст поля
     */
    void filterText(String text);

    /**
     * Слайдер горизонта отпущен ({@code view.horizonSlider}); план меняется один раз.
     *
     * @param itemId id узла меню слайдера
     * @param value  значение 1..120
     */
    void sliderCommit(String itemId, int value);

    /**
     * Значение спиннера «что-если» применено (клиент выдержал 600 мс после последнего изменения).
     *
     * @param itemId id узла меню спиннера
     * @param value  значение 0..10 000 000
     */
    void spinnerCommit(String itemId, long value);

    /**
     * Границы или развёрнутость главного окна изменились (для снимка сеанса).
     *
     * @param bounds    границы в нормальном состоянии
     * @param maximized развёрнуто ли
     */
    void mainGeometry(WindowBounds bounds, boolean maximized);

    /**
     * Указатель над пунктом меню: подсказка пункта показывается в сегменте «Сообщение» строки состояния (§3).
     *
     * @param itemIdOrNull id узла меню или {@code null}, когда указатель ушёл с меню
     */
    void menuHover(String itemIdOrNull);

    /** Крестик или Alt+F4 главного окна: тот же путь, что «Файл → Выход» (§6.32). */
    void closeMainRequested();

    /**
     * Необработанное исключение в потоке интерфейса клиента (§6.33).
     *
     * @param thread поток
     * @param error  исключение
     */
    void uncaught(Thread thread, Throwable error);

    /**
     * Запрос: пункты контекстного меню объекта. Правая кнопка по строке сначала выделяет её ({@link #selectRow}).
     *
     * @param target объект
     * @return пункты; пустой список — меню не показывать
     */
    List<MenuNode> contextMenu(ContextTarget target);

    /**
     * Запрос: подсказка ячейки таблицы.
     *
     * @param revision ревизия модели таблицы, для которой спрашивает клиент
     * @param index    индекс строки
     * @param columnId id колонки
     * @return текст подсказки; пустая строка — нет подсказки или ревизия устарела
     */
    String tableTooltip(long revision, int index, String columnId);

    /**
     * Запрос: сцена графика для размера области рисования.
     *
     * @param width  ширина в пикселях
     * @param height высота в пикселях
     * @return сцена
     */
    ChartScene chartScene(double width, double height);

    /**
     * Запрос: наведение на график (§5.3 «Наведение»): пунктирная вертикаль, точка на линии баланса и карточка дня у
     * указателя. Все координаты считает ядро ({@code ChartModel.hover}); клиент только рисует.
     *
     * @param revision ревизия графика, по сцене которой водит указатель
     * @param x        координата x указателя
     * @param y        координата y указателя
     * @param width    ширина области рисования
     * @param height   высота области рисования
     * @return наведение или пусто: указатель вне области построения, данных нет или ревизия устарела
     */
    Optional<ChartHover> chartHover(long revision, double x, double y, double width, double height);

    /**
     * Запрос: карточка дня (наведение на график, §5.6.2).
     *
     * @param date день
     * @return модель карточки
     */
    DayCardModel dayCard(LocalDate date);

    /**
     * Запрос: всплывающее окно карточки сводки (§5.1).
     *
     * @param cardId id карточки
     * @return модель спарклайна
     */
    SparklineModel sparkline(String cardId);

    /**
     * Запрос: календарь поля даты (§5.6.5).
     *
     * @param month    показываемый месяц
     * @param selected выбранная дата или {@code null}
     * @return модель календаря
     */
    CalendarModel calendar(YearMonth month, LocalDate selected);
}
