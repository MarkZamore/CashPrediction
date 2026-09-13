package ru.cashprediction.core.ui.token;

/**
 * Шрифты интерфейса (спецификация v2, §1.2, а также размеры 12 и 10 px из §5.3, §5.6.4 и §5.1).
 *
 * <p>Семейство — {@link DesignTokens#FONT_FAMILY}, для {@link #MONO} — {@link DesignTokens#MONO_FAMILY}.
 * Swing создаёт из токена составной шрифт ({@code StyleContext.getFont} → {@code FontUIResource}), чтобы глифы
 * {@link DesignTokens#GLYPHS} не превращались в квадраты; FX и web используют CSS-запись {@link #css()}.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum FontToken {

    /** 13 px, обычное: весь интерфейс. */
    BASE("font.base", 13, false, false),
    /** 11 px, обычное: подписи карточек, строка состояния, подсказки полей. */
    SMALL("font.small", 11, false, false),
    /** 16 px, жирное: значение карточки. */
    CARD("font.card", 16, true, false),
    /** 14 px, жирное: заголовок в диалоге, первая строка карточки дня. */
    HEADER("font.header", 14, true, false),
    /** 12 px, моноширинное: подробности, горячие клавиши, формат файла. */
    MONO("font.mono", 12, false, true),
    /** 12 px, обычное: легенда графика (§5.3) и всплывающие подсказки (§5.6.4). */
    LEGEND("font.legend", 12, false, false),
    /** 10 px, обычное: нижняя строка спарклайна «мин./макс.» (§5.1). */
    MICRO("font.micro", 10, false, false);

    private final String id;
    private final int sizePx;
    private final boolean bold;
    private final boolean mono;

    FontToken(String id, int sizePx, boolean bold, boolean mono) {
        this.id = id;
        this.sizePx = sizePx;
        this.bold = bold;
        this.mono = mono;
    }

    /** @return имя токена, например {@code font.base} */
    public String id() {
        return id;
    }

    /** @return размер в пикселях при масштабе 1,0 */
    public int sizePx() {
        return sizePx;
    }

    /** @return жирное ли начертание */
    public boolean bold() {
        return bold;
    }

    /** @return моноширинное ли семейство */
    public boolean mono() {
        return mono;
    }

    /** @return CSS-список семейств: {@link DesignTokens#FONT_FAMILY} или {@link DesignTokens#MONO_FAMILY} */
    public String family() {
        return mono ? DesignTokens.MONO_FAMILY : DesignTokens.FONT_FAMILY;
    }

    /** @return первое семейство списка для Swing: «Segoe UI» или «Consolas» */
    public String primaryFamily() {
        return mono ? DesignTokens.MONO_FAMILIES.getFirst() : DesignTokens.FONT_FAMILIES.getFirst();
    }

    /**
     * CSS-запись шрифта для свойства {@code font}.
     *
     * @return например {@code bold 16px "Segoe UI", "Tahoma", sans-serif}
     */
    public String css() {
        return (bold ? "bold " : "") + sizePx + "px " + family();
    }

    /** @return имя CSS-переменной размера, например {@code --cp-font-base-size} */
    public String cssVar() {
        return "--cp-" + id.replace('.', '-') + "-size";
    }
}
