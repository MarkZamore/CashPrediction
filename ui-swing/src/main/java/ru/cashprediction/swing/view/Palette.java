package ru.cashprediction.swing.view;

import java.awt.Color;

/**
 * Цвета таблицы, графика и сводки — те же смысловые цвета, что в JavaFX- и Web-клиентах:
 * доход зелёный, расход красный, баланс ниже нуля — светло-красный фон, ниже подушки — светло-жёлтый,
 * прошедшие события серые.
 *
 * <p>Класс только с константами.</p>
 */
public final class Palette {

    /** Текст дохода. */
    public static final Color INCOME = new Color(0x1B, 0x7F, 0x3B);
    /** Текст расхода. */
    public static final Color EXPENSE = new Color(0xB3, 0x26, 0x1E);
    /** Фон строки с отрицательным балансом. */
    public static final Color NEGATIVE_BG = new Color(0xFD, 0xE2, 0xE1);
    /** Фон строки с балансом ниже подушки безопасности. */
    public static final Color CUSHION_BG = new Color(0xFF, 0xF4, 0xCC);
    /** Текст прошедших событий. */
    public static final Color PAST = new Color(0x8A, 0x8A, 0x8A);
    /** Фон строки итогов месяца. */
    public static final Color TOTAL_BG = new Color(0xE8, 0xEE, 0xF7);
    /** Линия баланса на графике. */
    public static final Color BALANCE_LINE = new Color(0x1A, 0x5F, 0xB4);
    /** Заливка под линией баланса. */
    public static final Color BALANCE_FILL = new Color(0x1A, 0x5F, 0xB4, 0x22);
    /** Линия подушки безопасности. */
    public static final Color CUSHION_LINE = new Color(0xD9, 0x8E, 0x04);
    /** Линия цели. */
    public static final Color GOAL_LINE = new Color(0x2E, 0x8B, 0x57);
    /** Линия «сегодня». */
    public static final Color TODAY_LINE = new Color(0x6A, 0x1B, 0x9A);
    /** Сетка графика. */
    public static final Color GRID = new Color(0xE3, 0xE6, 0xEA);
    /** Нулевая линия графика. */
    public static final Color ZERO_LINE = new Color(0x55, 0x55, 0x55);
    /** Фон карточки сводки. */
    public static final Color CARD_BG = new Color(0xF7, 0xF9, 0xFC);
    /** Рамка карточек и всплывающих панелей. */
    public static final Color BORDER = new Color(0xC5, 0xCD, 0xD8);
    /** Фон всплывающих панелей. */
    public static final Color POPUP_BG = new Color(0xFF, 0xFF, 0xF5);

    private Palette() {
    }
}
