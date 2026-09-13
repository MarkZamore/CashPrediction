package ru.cashprediction.core.ui.token;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Размеры, задержки, семейства шрифтов и закрытый набор значков интерфейса (спецификация v2, §1.2–§1.4,
 * числа из §2–§6), общие для трёх клиентов.
 *
 * <p>Все размеры — в пикселях при масштабе 1,0, задержки — в миллисекундах. Клиенты берут числа только отсюда
 * (через модели ядра или {@link TokenCss}), чтобы раскладка совпадала до пикселя, который проверяет
 * визуальное сравнение (±4 px).</p>
 *
 * <p>Класс констант, потокобезопасен.</p>
 */
public final class DesignTokens {

    /** CSS-список основного семейства шрифтов. */
    public static final String FONT_FAMILY = "\"Segoe UI\", \"Tahoma\", sans-serif";
    /** CSS-список моноширинного семейства. */
    public static final String MONO_FAMILY = "\"Consolas\", \"Cascadia Mono\", monospace";
    /** Основные семейства по порядку предпочтения (для Swing). */
    public static final List<String> FONT_FAMILIES = List.of("Segoe UI", "Tahoma");
    /** Моноширинные семейства по порядку предпочтения (для Swing). */
    public static final List<String> MONO_FAMILIES = List.of("Consolas", "Cascadia Mono");

    /** Шаг сетки отступов. */
    public static final int SPACING = 4;
    /** Высота строки таблицы. */
    public static final int ROW_HEIGHT = 26;
    /** Высота заголовка таблицы. */
    public static final int HEADER_HEIGHT = 28;
    /** Высота кнопок, полей, списков и кнопок тулбара. */
    public static final int CONTROL_HEIGHT = 28;
    /** Высота тулбара. */
    public static final int TOOLBAR_HEIGHT = 36;
    /** Внутренний отступ кнопки тулбара по вертикали. */
    public static final int TOOLBAR_BUTTON_PAD_V = 4;
    /** Внутренний отступ кнопки тулбара по горизонтали. */
    public static final int TOOLBAR_BUTTON_PAD_H = 10;
    /** Высота строки состояния. */
    public static final int STATUS_HEIGHT = 24;
    /** Минимальная ширина кнопки диалога. */
    public static final int BUTTON_MIN_WIDTH = 88;
    /** Радиус скругления карточек, всплывающих окон, кнопок. */
    public static final int RADIUS = 6;
    /** Радиус окна быстрой правки суммы (§5.6.1). */
    public static final int QUICK_EDIT_RADIUS = 4;

    /** Ширина главного окна по умолчанию. */
    public static final int MAIN_DEFAULT_WIDTH = 1200;
    /** Высота главного окна по умолчанию. */
    public static final int MAIN_DEFAULT_HEIGHT = 800;
    /** Минимальная ширина главного окна. */
    public static final int MAIN_MIN_WIDTH = 900;
    /** Минимальная высота главного окна. */
    public static final int MAIN_MIN_HEIGHT = 600;
    /** Границы из снимка применяются, только если окно не уже этого (§2). */
    public static final int RESTORE_MIN_WIDTH = 400;
    /** Границы из снимка применяются, только если окно не ниже этого (§2). */
    public static final int RESTORE_MIN_HEIGHT = 300;

    /** Минимальная ширина карточки сводки. */
    public static final int CARD_MIN_WIDTH = 118;
    /** Отступ карточки по вертикали. */
    public static final int CARD_PAD_V = 6;
    /** Отступ карточки по горизонтали. */
    public static final int CARD_PAD_H = 10;
    /** Ширина графика во всплывающем окне карточки. */
    public static final int SPARK_WIDTH = 240;
    /** Высота графика во всплывающем окне карточки. */
    public static final int SPARK_HEIGHT = 60;
    /** Зазор между карточкой и её всплывающим окном. */
    public static final int CARD_POPUP_GAP = 4;

    /** Минимальная ширина подписи в сетке формы. */
    public static final int FORM_LABEL_MIN_WIDTH = 150;
    /** Промежуток сетки формы по горизонтали. */
    public static final int FORM_HGAP = 10;
    /** Промежуток сетки формы по вертикали. */
    public static final int FORM_VGAP = 8;
    /** Размер значка в полосе заголовка диалога. */
    public static final int DIALOG_ICON_SIZE = 26;
    /** Размер круга со значком типа сообщения в web. */
    public static final int WEB_ALERT_ICON_SIZE = 30;
    /** Колонки поля подробностей (моноширинное, только чтение). */
    public static final int DETAILS_COLUMNS = 80;
    /** Строки поля подробностей. */
    public static final int DETAILS_ROWS = 16;
    /** Ширина колонки предпросмотра в редакторе правила. */
    public static final int RULE_PREVIEW_WIDTH = 300;
    /** Координата y немодального окна web-клиента (у правого края). */
    public static final int WEB_MODELESS_Y = 96;
    /** Видимые строки списка «Открыть план». */
    public static final int OPEN_PLAN_LIST_ROWS = 8;
    /** Видимые строки списка окна «Выбор файла». */
    public static final int FILE_BROWSER_LIST_ROWS = 12;

    /** Ширина поля фильтра тулбара. */
    public static final int FILTER_WIDTH = 220;
    /** Ширина слайдера горизонта в меню. */
    public static final int SLIDER_WIDTH = 240;
    /** Ширина поля спиннера «что-если» в меню. */
    public static final int SPINNER_FIELD_WIDTH = 120;
    /** Ширина денежного поля быстрой правки. */
    public static final int QUICK_EDIT_FIELD_WIDTH = 140;
    /** Минимальная ширина карточки дня. */
    public static final int DAY_CARD_MIN_WIDTH = 200;
    /** Максимальная ширина карточки дня. */
    public static final int DAY_CARD_MAX_WIDTH = 320;
    /** Максимальная ширина подсказки. */
    public static final int TOOLTIP_MAX_WIDTH = 420;
    /** Размытие тени всплывающих окон. */
    public static final int SHADOW_BLUR = 12;
    /** Смещение тени вниз. */
    public static final int SHADOW_OFFSET_Y = 4;

    /** Поле графика слева. */
    public static final int CHART_MARGIN_LEFT = 80;
    /** Поле графика справа. */
    public static final int CHART_MARGIN_RIGHT = 24;
    /** Поле графика сверху (легенда). */
    public static final int CHART_MARGIN_TOP = 28;
    /** Поле графика снизу. */
    public static final int CHART_MARGIN_BOTTOM = 32;
    /** Ширина PNG графика. */
    public static final int CHART_PNG_WIDTH = 1200;
    /** Высота PNG графика. */
    public static final int CHART_PNG_HEIGHT = 700;
    /** Непрозрачность заливки под линией баланса. */
    public static final double CHART_FILL_OPACITY = 0.10;
    /** Непрозрачность столбцов итогов месяцев. */
    public static final double CHART_BARS_OPACITY = 0.45;
    /** Непрозрачность имени плана «только чтение» в параметрах плана. */
    public static final double READ_ONLY_OPACITY = 0.75;

    /** Задержка подсказки. */
    public static final int TOOLTIP_DELAY_MS = 600;
    /** Время показа подсказки. */
    public static final int TOOLTIP_DISMISS_MS = 20_000;
    /** Задержка всплывающего окна карточки. */
    public static final int CARD_POPUP_DELAY_MS = 350;
    /** Задержка применения фильтра. */
    public static final int FILTER_DEBOUNCE_MS = 300;
    /** Задержка применения спиннера «что-если». */
    public static final int WHAT_IF_SPINNER_DELAY_MS = 600;
    /** Задержка записи настроек. */
    public static final int SETTINGS_DELAY_MS = 700;
    /** Задержка автосохранения. */
    public static final int AUTOSAVE_DELAY_MS = 1_000;
    /** Время показа статусного сообщения. */
    public static final int STATUS_MESSAGE_MS = 10_000;
    /** Задержка обновления предпросмотра дат правила. */
    public static final int RULE_PREVIEW_DELAY_MS = 250;
    /** Web: проверка полей формы не чаще этого интервала. */
    public static final int WEB_VALIDATION_THROTTLE_MS = 120;

    /**
     * Закрытый набор значков интерфейса (§1.4): действия, отметки, значки заголовков диалогов и типов сообщений.
     * Эмодзи запрещены; тест каталога проверяет, что тексты не содержат других символов вне
     * {@link #isAllowedInText(int)}, а Swing-тест — что шрифт рисует каждый значок.
     */
    public static final Set<String> GLYPHS = orderedSet(
            // Действия.
            "↶", "↷", "▾", "▸", "✕", "✓", "✗", "●", "◀", "▶", "▦", "↑",
            // Отметки таблицы.
            "✎", "→", "⇄", "≡", "Δ",
            // Значки заголовков диалогов.
            "₽", "⚙", "↻", "◎", "⇩", "⟲",
            // Типы сообщений в web.
            "ℹ", "⚠", "✖", "?");

    /**
     * Типографские символы, допустимые в текстах помимо ASCII и кириллицы: кавычки, тире, многоточие, знаки валют,
     * неразрывные пробелы, стрелки кнопок «‹ Назад» / «Далее ›», знак умножения «×» и типографский минус «−».
     */
    public static final Set<String> TEXT_SYMBOLS = orderedSet(
            "«", "»", "„", "“", "”", "‘", "’", "—", "–", "…", "·", "×", "−", "‹", "›", "№", "°",
            "€", "₸", "₺", " ", " ");

    private DesignTokens() {
    }

    /**
     * Ширина контента диалога по его виду.
     *
     * @param width вид диалога
     * @return ширина в пикселях
     */
    public static int dialogWidth(DialogWidth width) {
        return width.px();
    }

    /**
     * Можно ли использовать символ в тексте интерфейса: печатный ASCII, перевод строки, табуляция, кириллица,
     * {@link #TEXT_SYMBOLS} и {@link #GLYPHS}. Всё остальное (эмодзи, латиница с диакритикой, управляющие
     * символы) — ошибка каталога.
     *
     * @param codePoint кодовая точка
     * @return {@code true}, если символ разрешён
     */
    public static boolean isAllowedInText(int codePoint) {
        if (codePoint == '\n' || codePoint == '\t' || (codePoint >= 0x20 && codePoint <= 0x7E)) {
            return true;
        }
        if (codePoint >= 0x0400 && codePoint <= 0x04FF) {
            return true;
        }
        String symbol = new String(Character.toChars(codePoint));
        return TEXT_SYMBOLS.contains(symbol) || GLYPHS.contains(symbol);
    }

    private static Set<String> orderedSet(String... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(items)));
    }
}
