package ru.cashprediction.core.session;

import java.util.List;
import java.util.Optional;

/**
 * Словарь восстанавливаемых окон — общий контракт трёх клиентов (JavaFX, Swing, Web).
 *
 * <p>Для каждого типа окна зафиксированы ключи контекста (что это за окно) и идентификаторы полей
 * (что пользователь ввёл). Благодаря единому словарю снимок, сделанный одним клиентом, понятен
 * любому другому, а тесты ядра проверяют восстановление без UI.</p>
 *
 * <p>Нативные {@code FileChooser}/{@code DirectoryChooser}/{@code JFileChooser} в словарь
 * намеренно не входят: их состояние недоступно программе, поэтому они не восстанавливаются.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum WindowType {

    /** Мастер создания плана; контекст {@code page} — номер страницы мастера. */
    NEW_PLAN_WIZARD("Новый план", true,
            List.of(WindowType.CONTEXT_PAGE),
            List.of("name", "currency", "startDate", "startBalance", "horizonKind", "horizonValue", "horizonUntil",
                    "cushion", "quickIncomeTitle", "quickIncomeAmount", "quickIncomeDay",
                    "quickExpenseTitle", "quickExpenseAmount", "quickExpenseDay")),

    /** Параметры плана: те же поля, что у мастера, без быстрых операций, плюс заметка и цель. */
    PLAN_SETTINGS("Параметры плана", true,
            List.of(),
            List.of("name", "currency", "startDate", "startBalance", "horizonKind", "horizonValue", "horizonUntil",
                    "cushion", "note", "goalTitle", "goalTarget", "goalDate")),

    /** Редактор регулярной операции; контекст {@code mode=create|edit}, {@code ruleId}. */
    RULE_EDITOR("Регулярная операция", true,
            List.of(WindowType.CONTEXT_MODE, WindowType.CONTEXT_RULE_ID),
            List.of("title", "kind", "amount", "category", "recurrenceKind", "dayOfMonth", "everyN", "weekday",
                    "monthDay", "fromEnabled", "from", "untilEnabled", "until", "weekendPolicy", "enabled", "note")),

    /** Редактор разовой операции; контекст {@code mode}, {@code txId}. */
    ONE_TIME_EDITOR("Разовая операция", true,
            List.of(WindowType.CONTEXT_MODE, WindowType.CONTEXT_TX_ID),
            List.of("date", "title", "kind", "amount", "category", "note")),

    /**
     * Корректировка конкретного повторения; контекст {@code ruleId}, {@code originalDate}.
     * Заголовок «Корректировка события» — по спецификации интерфейса v2 (§6.5); заголовки в снимок не пишутся,
     * поэтому смена текста не затрагивает сохранённые снимки.
     */
    ADJUSTMENT_EDITOR("Корректировка события", true,
            List.of(WindowType.CONTEXT_RULE_ID, WindowType.CONTEXT_ORIGINAL_DATE),
            List.of("action", "amount", "date", "note")),

    /** Калькулятор цели — единственный немодальный диалог: с ним удобно смотреть на таблицу. */
    GOAL_CALCULATOR("Калькулятор цели", false,
            List.of(),
            List.of("target", "byDateEnabled", "byDate", "extraSaving")),

    /** Ввод строки; контекст {@code purpose=rename|reconcile|customMonths|customCurrency}. */
    TEXT_INPUT("Ввод значения", true,
            List.of(WindowType.CONTEXT_PURPOSE),
            List.of("value")),

    /** Выбор из списка; контекст {@code purpose=currency|openPlan}. */
    CHOICE("Выбор значения", true,
            List.of(WindowType.CONTEXT_PURPOSE),
            List.of("value")),

    /** Подтверждение или сообщение; контекст {@code purpose}, {@code targetId}; полей нет, текст восстанавливается из контекста. */
    ALERT("Подтверждение", true,
            List.of(WindowType.CONTEXT_PURPOSE, WindowType.CONTEXT_TARGET_ID),
            List.of()),

    /** Параметры экспорта в CSV. */
    CSV_EXPORT("Экспорт в CSV", true,
            List.of(),
            List.of("separator", "bom", "range")),

    /** Всплывающее окно быстрой правки суммы повторения; немодальное; контекст {@code ruleId}, {@code originalDate}. */
    QUICK_EDIT_POPUP("Быстрая правка суммы", false,
            List.of(WindowType.CONTEXT_RULE_ID, WindowType.CONTEXT_ORIGINAL_DATE),
            List.of("amount"));

    /** Ключ контекста: номер страницы мастера. */
    public static final String CONTEXT_PAGE = "page";
    /** Ключ контекста: режим редактора ({@link #MODE_CREATE} или {@link #MODE_EDIT}). */
    public static final String CONTEXT_MODE = "mode";
    /** Ключ контекста: идентификатор регулярной операции. */
    public static final String CONTEXT_RULE_ID = "ruleId";
    /** Ключ контекста: идентификатор разовой операции. */
    public static final String CONTEXT_TX_ID = "txId";
    /** Ключ контекста: номинальная дата повторения (ISO). */
    public static final String CONTEXT_ORIGINAL_DATE = "originalDate";
    /** Ключ контекста: назначение универсального диалога. */
    public static final String CONTEXT_PURPOSE = "purpose";
    /** Ключ контекста: идентификатор объекта, к которому относится подтверждение. */
    public static final String CONTEXT_TARGET_ID = "targetId";
    /** Значение режима: создание нового объекта. */
    public static final String MODE_CREATE = "create";
    /** Значение режима: редактирование существующего объекта. */
    public static final String MODE_EDIT = "edit";

    /**
     * Ключи контекста, которые ссылаются на объект плана. Если такого объекта в восстановленном
     * плане нет, окно открывается в режиме создания (см. {@link RestoreCoordinator}).
     */
    public static final List<String> TARGET_CONTEXT_KEYS = List.of(CONTEXT_RULE_ID, CONTEXT_TX_ID, CONTEXT_TARGET_ID);

    private final String title;
    private final boolean defaultModal;
    private final List<String> contextKeys;
    private final List<String> fieldIds;

    WindowType(String title, boolean defaultModal, List<String> contextKeys, List<String> fieldIds) {
        this.title = title;
        this.defaultModal = defaultModal;
        this.contextKeys = contextKeys;
        this.fieldIds = fieldIds;
    }

    /**
     * Заголовок окна по-русски (используется и в сообщениях восстановления).
     *
     * @return например «Регулярная операция»
     */
    public String title() {
        return title;
    }

    /**
     * Модальность окна по умолчанию.
     *
     * @return {@code false} только для {@link #GOAL_CALCULATOR} и {@link #QUICK_EDIT_POPUP}
     */
    public boolean defaultModal() {
        return defaultModal;
    }

    /**
     * Ключи контекста окна.
     *
     * @return неизменяемый список ключей в порядке таблицы словаря
     */
    public List<String> contextKeys() {
        return contextKeys;
    }

    /**
     * Идентификаторы полей окна.
     *
     * @return неизменяемый список идентификаторов в порядке таблицы словаря
     */
    public List<String> fieldIds() {
        return fieldIds;
    }

    /**
     * Ищет тип по имени константы, не бросая исключение для неизвестных имён
     * (снимок мог записать более новый клиент).
     *
     * @param name имя константы, например {@code RULE_EDITOR}
     * @return тип или пусто
     */
    public static Optional<WindowType> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (WindowType type : values()) {
            if (type.name().equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
