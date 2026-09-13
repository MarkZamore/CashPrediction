package ru.cashprediction.core.ui.dump;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Дамп интерфейса схемы 1 (архитектура §6.1): что видно на экране, прочитанное из настоящих виджетов клиента
 * ({@code FxUiDumper}, {@code SwingUiDumper}, {@code dump.js}) или из моделей ядра ({@code ModelUiDriver} — эталоны).
 * Цвета — имена токенов ({@code ColorToken.canonical().id()}); цвет вне палитры записывается как {@code #RRGGBB} и сам
 * по себе расхождение. JSON дампа — {@code UiJson.toTree(UiDump)}.
 *
 * <p><b>Пути §10.</b> Каждый путь закрытого списка допустимых различий (спецификация v2, §10) существует в схеме:
 * {@code frame/minSize} и {@code frame/titleBar} ({@link Frame}), {@code toolbar/wrap} ({@link Toolbar}),
 * {@code screens} (экраны web §6.30), {@code menuBar/…/accel}, {@code menuBar/…/tooltip}, {@code status/session},
 * {@code alerts[назначение]/content|buttons}, {@code windows[назначение]/buttons}, {@code chooserRequests}. Списки с
 * id (меню, окна, сообщения) нормализатор адресует по id или назначению, а не по индексу.</p>
 *
 * @param schema          версия схемы (1)
 * @param client          {@code fx}, {@code swing}, {@code web} или {@code model}
 * @param scenario        имя сценария
 * @param step            имя шага {@code dump <step>}
 * @param frame           окно и границы областей
 * @param menuBar         дерево строки меню
 * @param toolbar         тулбар
 * @param summary         карточки сводки
 * @param table           таблица
 * @param chart           график
 * @param status          сегменты строки состояния
 * @param contextMenus    открытые через настоящий путь событий и закрытые контекстные меню
 * @param windows         открытые формы
 * @param alerts          открытые сообщения
 * @param popups          открытые всплывающие окна
 * @param screens         экраны поверх страницы (web §6.30: нет связи, сервер остановлен, аварийная остановка); у
 *                        FX и Swing всегда пусто
 * @param chooserRequests запросы выбора файлов и папок
 * @param classCensus     число созданных экземпляров 23 классов JavaFX (только fx; иначе пусто)
 * @param counters        счётчики выполненных команд контроллера по id команды
 */
public record UiDump(int schema, String client, String scenario, String step, Frame frame, List<MenuItem> menuBar,
                     Toolbar toolbar, Summary summary, Table table, Chart chart, List<Segment> status,
                     List<ContextMenu> contextMenus, List<Window> windows, List<Alert> alerts, List<Popup> popups,
                     List<Screen> screens, List<ChooserRequest> chooserRequests, Map<String, Integer> classCensus,
                     Map<String, Integer> counters) {

    /** Текущая версия схемы. */
    public static final int SCHEMA = 1;

    /** Копирует коллекции и заменяет {@code null}. */
    public UiDump {
        client = Objects.requireNonNullElse(client, "");
        scenario = Objects.requireNonNullElse(scenario, "");
        step = Objects.requireNonNullElse(step, "");
        menuBar = copy(menuBar);
        status = copy(status);
        contextMenus = copy(contextMenus);
        windows = copy(windows);
        alerts = copy(alerts);
        popups = copy(popups);
        screens = copy(screens);
        chooserRequests = copy(chooserRequests);
        classCensus = classCensus == null ? Map.of() : Map.copyOf(classCensus);
        counters = counters == null ? Map.of() : Map.copyOf(counters);
    }

    /**
     * Прямоугольник в пикселях содержимого окна (ширины округляются нормализатором до 2 px).
     *
     * @param x      левый край
     * @param y      верхний край
     * @param width  ширина
     * @param height высота
     */
    public record Box(double x, double y, double width, double height) {
    }

    /**
     * Размер без положения.
     *
     * @param width  ширина
     * @param height высота
     */
    public record Size(double width, double height) {
    }

    /**
     * Главное окно.
     *
     * @param title         заголовок
     * @param titleBar      где показан заголовок: {@code os} — рамка окна ОС (FX, Swing), {@code tab} — вкладка
     *                      браузера (web; §10 №11)
     * @param minSize       минимальный размер окна (§2: 900×600) или {@code null}, если его нет (вкладка браузера)
     * @param contentWidth  ширина содержимого
     * @param contentHeight высота содержимого
     * @param regions       границы областей: {@code menuBar}, {@code toolbar}, {@code summary}, {@code table.header},
     *                      {@code center}, {@code status}
     */
    public record Frame(String title, String titleBar, Size minSize, double contentWidth, double contentHeight,
                        Map<String, Box> regions) {
        /** Заменяет {@code null} и копирует карту. */
        public Frame {
            title = Objects.requireNonNullElse(title, "");
            titleBar = Objects.requireNonNullElse(titleBar, "");
            regions = regions == null ? Map.of() : Map.copyOf(regions);
        }
    }

    /**
     * Узел меню.
     *
     * @param id         id узла ({@code cp.id} / {@code data-cp-id})
     * @param kind       вид узла ({@code Action}, {@code Check}, {@code Radio}, {@code Separator}, {@code Submenu},
     *                   {@code Slider}, {@code Spinner}, {@code Info})
     * @param text       текст (у Spinner — подпись слева)
     * @param accel      показываемый ускоритель или пустая строка
     * @param enabled    доступен ли
     * @param checked    отмечен ли (флажок, радио)
     * @param group      имя группы радио ({@code mode}, {@code period}, {@code store}) или пустая строка
     * @param value      значение Slider или Spinner как текст виджета («24», «5000») или пустая строка
     * @param valueLabel подпись текущего значения Slider («2 года», «15 лет») или пустая строка
     * @param tooltip    подсказка
     * @param children   дочерние узлы
     */
    public record MenuItem(String id, String kind, String text, String accel, boolean enabled, boolean checked,
                           String group, String value, String valueLabel, String tooltip, List<MenuItem> children) {
        /** Заменяет {@code null} и копирует список. */
        public MenuItem {
            group = Objects.requireNonNullElse(group, "");
            value = Objects.requireNonNullElse(value, "");
            valueLabel = Objects.requireNonNullElse(valueLabel, "");
            children = copy(children);
        }
    }

    /**
     * Тулбар.
     *
     * @param wrap  перенесён ли тулбар на вторую строку (web при ширине меньше 1200, §10 №15)
     * @param items элементы слева направо
     */
    public record Toolbar(boolean wrap, List<ToolbarItem> items) {
        /** Копирует список. */
        public Toolbar {
            items = copy(items);
        }
    }

    /**
     * Элемент тулбара.
     *
     * @param id       id
     * @param kind     вид ({@code SplitButton}, {@code Toggle}, {@code MenuButton}, {@code FilterField}, {@code Button},
     *                 {@code Separator}, {@code Spacer})
     * @param text     текст (у поля фильтра — введённый текст)
     * @param prompt   подсказка в пустом поле фильтра или пустая строка
     * @param tooltip  подсказка
     * @param enabled  доступен ли
     * @param selected нажат ли
     * @param color    цвет текста (токен)
     * @param bold     жирный ли
     * @param row      строка тулбара, в которой стоит элемент (0; 1 — после переноса)
     * @param bounds   границы
     * @param items    пункты выпадающего меню
     */
    public record ToolbarItem(String id, String kind, String text, String prompt, String tooltip, boolean enabled,
                              boolean selected, String color, boolean bold, int row, Box bounds, List<MenuItem> items) {
        /** Заменяет {@code null} и копирует список. */
        public ToolbarItem {
            prompt = Objects.requireNonNullElse(prompt, "");
            items = copy(items);
        }
    }

    /**
     * Панель сводки.
     *
     * @param visible         видна ли
     * @param cards           карточки
     * @param unavailableText текст «Сводка недоступна: …» или пустая строка
     */
    public record Summary(boolean visible, List<Card> cards, String unavailableText) {
        /** Копирует список. */
        public Summary {
            cards = copy(cards);
        }
    }

    /**
     * Карточка сводки.
     *
     * @param id           id
     * @param title        заголовок
     * @param value        значение
     * @param valueColor   цвет значения
     * @param caption      подпись
     * @param captionColor цвет подписи
     * @param tooltip      подсказка карточки
     * @param bounds       границы
     */
    public record Card(String id, String title, String value, String valueColor, String caption, String captionColor,
                       String tooltip, Box bounds) {
    }

    /**
     * Таблица.
     *
     * @param columns            заголовки колонок по порядку
     * @param rowCount           число строк
     * @param rows               первые 60 строк и выделенная
     * @param rowsDigest         SHA-256 текстов всех строк (проверка без выгрузки 200 000 строк)
     * @param placeholder        текст пустого состояния или пустая строка
     * @param placeholderButtons кнопки пустого состояния (§5.2: «Добавить доход…», «Открыть пример», «Очистить фильтр»)
     * @param selectedRowId      выделенная строка
     */
    public record Table(List<String> columns, int rowCount, List<Row> rows, String rowsDigest, String placeholder,
                        List<Button> placeholderButtons, String selectedRowId) {
        /** Копирует списки. */
        public Table {
            columns = copy(columns);
            rows = copy(rows);
            placeholderButtons = copy(placeholderButtons);
        }
    }

    /**
     * Строка таблицы.
     *
     * @param index      индекс
     * @param rowId      id строки
     * @param kind       вид строки
     * @param cells      тексты ячеек
     * @param background фон (токен) или пустая строка
     * @param styles     оформление ячеек по id колонки (§5.2: цвет, жирный, курсив, зачёркнутый); колонка без записи —
     *                   обычный текст цвета {@code text.primary}
     */
    public record Row(int index, String rowId, String kind, List<String> cells, String background,
                      Map<String, CellLook> styles) {
        /** Копирует коллекции. */
        public Row {
            cells = copy(cells);
            styles = styles == null ? Map.of() : Map.copyOf(styles);
        }
    }

    /**
     * Оформление текста ячейки, прочитанное из виджета.
     *
     * @param color  цвет текста (токен)
     * @param bold   жирный
     * @param italic курсив
     * @param strike зачёркнутый
     */
    public record CellLook(String color, boolean bold, boolean italic, boolean strike) {
    }

    /**
     * График.
     *
     * @param legend      тексты легенды
     * @param xLabels     подписи оси X
     * @param yLabels     подписи оси Y
     * @param lineLabels  подписи линий («0», «подушка 50 000», «сегодня», …)
     * @param markerCount число маркеров
     * @param barCount    число столбцов
     * @param notice      текст пустой сцены или уведомления
     */
    public record Chart(List<String> legend, List<String> xLabels, List<String> yLabels, List<String> lineLabels,
                        int markerCount, int barCount, String notice) {
        /** Копирует списки. */
        public Chart {
            legend = copy(legend);
            xLabels = copy(xLabels);
            yLabels = copy(yLabels);
            lineLabels = copy(lineLabels);
        }
    }

    /**
     * Сегмент строки состояния (время заменено на {@code <time>}).
     *
     * @param id      id
     * @param text    текст
     * @param tooltip подсказка
     * @param color   цвет (токен)
     * @param visible виден ли
     */
    public record Segment(String id, String text, String tooltip, String color, boolean visible) {
    }

    /**
     * Контекстное меню.
     *
     * @param target цель ({@code row:r1@2026-10-05}, {@code card:now}, {@code chart:inside}, …)
     * @param items  пункты
     */
    public record ContextMenu(String target, List<MenuItem> items) {
        /** Копирует список. */
        public ContextMenu {
            items = copy(items);
        }
    }

    /**
     * Открытая форма.
     *
     * @param id              id окна
     * @param type            тип окна
     * @param purpose         назначение
     * @param title           заголовок окна
     * @param header          заголовок в полосе
     * @param glyph           значок
     * @param modal           модальное ли
     * @param ownerId         владелец
     * @param page            номер текущей страницы (0 у обычной формы)
     * @param bounds          границы
     * @param sections        подписи разделов по порядку раскладки («Когда повторяется», «Результат»)
     * @param hints           тексты неизменных подсказок по порядку раскладки
     * @param fields          поля в порядке раскладки
     * @param preview         тексты элементов списка предпросмотра (§6.3)
     * @param previewSelected номер выбранного элемента предпросмотра или -1
     * @param results         строки результата (§6.6)
     * @param problem         строка проблем
     * @param buttons         кнопки в визуальном порядке слева направо (панель и кнопки внутри формы)
     * @param details         подробности
     * @param detailsLink     текст ссылки подробностей («Подробности ▸» / «Скрыть подробности ▾») или пустая строка
     * @param detailsExpanded раскрыты ли подробности
     */
    public record Window(String id, String type, String purpose, String title, String header, String glyph,
                         boolean modal, String ownerId, int page, Box bounds, List<String> sections, List<String> hints,
                         List<Field> fields, List<String> preview, int previewSelected, List<ResultText> results,
                         String problem, List<Button> buttons, String details, String detailsLink,
                         boolean detailsExpanded) {
        /** Копирует списки и заменяет {@code null}. */
        public Window {
            sections = copy(sections);
            hints = copy(hints);
            fields = copy(fields);
            preview = copy(preview);
            results = copy(results);
            buttons = copy(buttons);
            detailsLink = Objects.requireNonNullElse(detailsLink, "");
        }
    }

    /**
     * Строка результата формы.
     *
     * @param text  текст
     * @param color цвет (токен)
     */
    public record ResultText(String text, String color) {
    }

    /**
     * Поле формы.
     *
     * @param id       id
     * @param kind     вид поля ({@code FieldKind})
     * @param label    подпись (для CHECK — текст флажка)
     * @param text     текст виджета (для CHECK и RADIO — выбранное значение)
     * @param prompt   подсказка в пустом поле или пустая строка
     * @param tooltip  всплывающая подсказка или пустая строка
     * @param suffix   подпись справа от поля (валюта) или пустая строка
     * @param enabled  доступно ли
     * @param visible  видно ли
     * @param readOnly только чтение
     * @param options  тексты вариантов (у RADIO в нескольких строках — в порядке появления)
     */
    public record Field(String id, String kind, String label, String text, String prompt, String tooltip, String suffix,
                        boolean enabled, boolean visible, boolean readOnly, List<String> options) {
        /** Заменяет {@code null} и копирует список. */
        public Field {
            prompt = Objects.requireNonNullElse(prompt, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            suffix = Objects.requireNonNullElse(suffix, "");
            options = copy(options);
        }
    }

    /**
     * Кнопка формы, сообщения, экрана или пустого состояния.
     *
     * @param id         id
     * @param text       текст
     * @param tooltip    подсказка или пустая строка
     * @param enabled    доступна ли
     * @param isDefault  кнопка по умолчанию
     * @param x          координата левого края (для проверки визуального порядка)
     */
    public record Button(String id, String text, String tooltip, boolean enabled, boolean isDefault, double x) {
        /** Заменяет {@code null}. */
        public Button {
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Открытое сообщение.
     *
     * @param id              id
     * @param purpose         назначение
     * @param kind            тип
     * @param windowTitle     заголовок окна
     * @param glyph           значок полосы заголовка (⟲ у восстановления) или пустая строка — значок типа
     * @param minWidth        минимальная ширина (460, 720, 760)
     * @param header          заголовок-текст
     * @param content         содержимое
     * @param details         подробности
     * @param detailsLink     текст ссылки подробностей или пустая строка
     * @param detailsExpanded раскрыты ли подробности
     * @param buttons         кнопки
     */
    public record Alert(String id, String purpose, String kind, String windowTitle, String glyph, int minWidth,
                        String header, String content, String details, String detailsLink, boolean detailsExpanded,
                        List<Button> buttons) {
        /** Заменяет {@code null} и копирует список. */
        public Alert {
            glyph = Objects.requireNonNullElse(glyph, "");
            detailsLink = Objects.requireNonNullElse(detailsLink, "");
            buttons = copy(buttons);
        }
    }

    /**
     * Всплывающее окно.
     *
     * @param kind  вид: {@code dayCard}, {@code sparkline}, {@code quickEdit}, {@code calendar}, {@code tooltip}
     * @param lines тексты сверху вниз
     * @param bounds границы
     */
    public record Popup(String kind, List<String> lines, Box bounds) {
        /** Копирует список. */
        public Popup {
            lines = copy(lines);
        }
    }

    /**
     * Экран поверх страницы web-клиента (§6.30).
     *
     * @param kind    вид: {@code offline}, {@code stopped}, {@code crashed}
     * @param title   заголовок
     * @param text    текст
     * @param buttons кнопки («Повторить»)
     */
    public record Screen(String kind, String title, String text, List<Button> buttons) {
        /** Копирует список. */
        public Screen {
            buttons = copy(buttons);
        }
    }

    /**
     * Запрос выбора файла или папки (§10 №12: сравнивается только запрос).
     *
     * @param kind   {@code file} или {@code directory}
     * @param mode   {@code OPEN}/{@code SAVE} или пустая строка для папки
     * @param title  заголовок
     * @param filter описание фильтра
     * @param folder начальная папка (нормализованная)
     * @param name   начальное имя
     */
    public record ChooserRequest(String kind, String mode, String title, String filter, String folder, String name) {
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
