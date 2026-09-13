package ru.cashprediction.core.ui.token;

import java.util.Locale;
import java.util.Optional;

/**
 * Цвета единственной светлой темы трёх клиентов (спецификация v2, §1.1).
 *
 * <p>Модели ядра ссылаются только на токены, а не на конкретные цвета: FX получает их как looked-up colors
 * ({@link TokenCss#fxLookups()}), Swing — как значения {@code UIManager} ({@link TokenCss#swingDefaults()}),
 * web — как CSS-переменные ({@link TokenCss#webCss()}). Дамп настоящего виджета переводит прочитанный цвет
 * обратно в токен через {@link #byArgb(int)}; цвет вне палитры — сам по себе расхождение.</p>
 *
 * <p><b>Совпадающие цвета.</b> {@code BG_SURFACE} и {@code BG_CARD} (#FFFFFF), {@code WARN} и {@code MARKER_MIXED}
 * (#8A5300), {@code LINE_TODAY} и {@code WHATIF} (#8250DF) имеют одинаковое значение. {@link #byArgb(int)}
 * возвращает первый объявленный токен, а {@link #canonical()} приводит любой токен к нему; эталонные дампы
 * пишутся через {@link #canonical()}, иначе сравнение цветов из модели и из виджета было бы неоднозначным.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum ColorToken {

    /** Фон панели сводки, строки состояния, тулбара. */
    BG_WINDOW("bg.window", "#F6F8FA"),
    /** Таблица, график, диалоги, всплывающие окна. */
    BG_SURFACE("bg.surface", "#FFFFFF"),
    /** Карточка сводки. */
    BG_CARD("bg.card", "#FFFFFF"),
    /** Заголовок таблицы, строка «Прошедшие события». */
    BG_ALT("bg.alt", "#F0F3F6"),
    /** Основной текст. */
    TEXT_PRIMARY("text.primary", "#1F2328"),
    /** Подписи, подзаголовки карточек, подсказки. */
    TEXT_MUTED("text.muted", "#57606A"),
    /** Прошедшие строки таблицы. */
    TEXT_PAST("text.past", "#8A8F98"),
    /** Рамки карточек, разделители. */
    BORDER("border", "#D0D7DE"),
    /** Рамка всплывающих окон и полей. */
    BORDER_STRONG("border.strong", "#8C959F"),
    /** Линия баланса, фокус, рамка карточки при наведении, основная кнопка. */
    ACCENT("accent", "#1F6FEB"),
    /** Фон выделенной строки, нажатая кнопка-переключатель. */
    ACCENT_WEAK("accent.weak", "#DDEBFF"),
    /** Доход, маркер дохода, положительный столбец, сообщение success. */
    INCOME("income", "#1B7F3B"),
    /** Расход, отрицательные значения, ошибки. */
    EXPENSE("expense", "#B3261E"),
    /** Фон строки с балансом меньше нуля. */
    NEGATIVE_BG("negative.bg", "#FDE2E1"),
    /** Фон строки ниже подушки. */
    CUSHION_BG("cushion.bg", "#FFF4C2"),
    /** Предупреждения, «ниже подушки», «несохранено». */
    WARN("warn", "#8A5300"),
    /** Строка итога месяца. */
    TOTAL_BG("total.bg", "#E8EEF6"),
    /** Сетка графика. */
    GRID("grid", "#E3E6EA"),
    /** Линия нуля: 1 px, сплошная. */
    LINE_ZERO("line.zero", "#6E7781"),
    /** Подушка: 1,5 px, штрих 6/4. */
    LINE_CUSHION("line.cushion", "#D4A72C"),
    /** Цель: 1,5 px, штрих 8/4. */
    LINE_GOAL("line.goal", "#2E8B57"),
    /** Сегодня: 1,5 px, штрих 4/4, вертикаль. */
    LINE_TODAY("line.today", "#8250DF"),
    /** Индикатор «что-если». */
    WHATIF("whatif", "#8250DF"),
    /** Маркер дня с доходом и расходом. */
    MARKER_MIXED("marker.mixed", "#8A5300"),
    /** Фон подсказок во всех клиентах. */
    TOOLTIP_BG("tooltip.bg", "#262C34"),
    /** Текст подсказок во всех клиентах. */
    TOOLTIP_TEXT("tooltip.text", "#F5F7FA"),
    /** Тень всплывающих окон rgba(0,0,0,.18): blur 12, смещение 0/4 ({@link DesignTokens#SHADOW_BLUR}). */
    SHADOW("shadow", "#000000", 0.18);

    private final String id;
    private final int argb;
    private final double opacity;

    ColorToken(String id, String rgbHex) {
        this(id, rgbHex, 1.0);
    }

    ColorToken(String id, String rgbHex, double opacity) {
        this.id = id;
        this.opacity = opacity;
        int alpha = (int) Math.round(opacity * 255);
        this.argb = (alpha << 24) | Integer.parseInt(rgbHex.substring(1), 16);
    }

    /**
     * Имя токена в спецификации.
     *
     * @return например {@code bg.window}
     */
    public String id() {
        return id;
    }

    /**
     * Цвет как 32-битное ARGB-число (формат {@code java.awt.Color.getRGB()} и {@code 0xAARRGGBB}).
     *
     * @return ARGB
     */
    public int argb() {
        return argb;
    }

    /**
     * Непрозрачность из спецификации.
     *
     * @return 1.0 для сплошных цветов, 0.18 для тени
     */
    public double opacity() {
        return opacity;
    }

    /**
     * Шестнадцатеричная запись: {@code #F6F8FA} для непрозрачного цвета, {@code #RRGGBBAA} для полупрозрачного.
     *
     * @return запись заглавными буквами
     */
    public String hex() {
        int alpha = argb >>> 24;
        String rgb = String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
        return alpha == 255 ? rgb : rgb + String.format(Locale.ROOT, "%02X", alpha);
    }

    /**
     * Значение для CSS (web и JavaFX): {@code #F6F8FA} или {@code rgba(0,0,0,0.18)}.
     *
     * @return CSS-цвет
     */
    public String css() {
        if (opacity >= 1.0) {
            return hex();
        }
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%s)", (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                stripZeros(opacity));
    }

    /**
     * Имя CSS-переменной web-клиента.
     *
     * @return например {@code --cp-bg-window}
     */
    public String cssVar() {
        return "--cp-" + id.replace('.', '-');
    }

    /**
     * Имя looked-up color в стилях JavaFX.
     *
     * @return например {@code -cp-bg-window}
     */
    public String fxLookup() {
        return "-cp-" + id.replace('.', '-');
    }

    /**
     * Первый объявленный токен с тем же значением (см. описание класса).
     *
     * @return канонический токен
     */
    public ColorToken canonical() {
        return byArgb(argb).orElse(this);
    }

    /**
     * Ищет токен по ARGB-значению, прочитанному с виджета.
     *
     * @param argb цвет {@code 0xAARRGGBB}
     * @return первый объявленный токен с этим значением или пусто, если цвета нет в палитре
     */
    public static Optional<ColorToken> byArgb(int argb) {
        for (ColorToken token : values()) {
            if (token.argb == argb) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /**
     * Ищет токен по имени спецификации.
     *
     * @param id имя, например {@code accent.weak}
     * @return токен или пусто
     */
    public static Optional<ColorToken> byId(String id) {
        for (ColorToken token : values()) {
            if (token.id.equals(id)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    private static String stripZeros(double value) {
        String text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
