package ru.cashprediction.core.ui.form;

import java.util.List;

/**
 * Изменяемое состояние поля в модели формы (архитектура §3.5).
 *
 * <p><b>Диапазон спиннера.</b> {@link #min()} и {@link #max()} заменяют {@code FieldSpec.min/max}, когда диапазон
 * зависит от других полей: «Горизонт прогноза» — 1..600 для месяцев и 1..50 для лет (§6.1, §6.2,
 * {@code HorizonFields.views}). Клиент меняет границы виджета, не трогая введённый текст; значение вне нового
 * диапазона остаётся в поле и даёт ошибку формы, а не молчаливую подрезку.</p>
 *
 * @param value    текст для виджета или {@code null} — оставить текст виджета как есть (правило эха: клиент
 *                 применяет значение, только если виджет всё ещё показывает текст, отправленный с этой ревизией)
 * @param visible  видно ли поле (скрытое поле не занимает места)
 * @param enabled  доступно ли (недоступное поле сохраняет введённое значение, §6.5)
 * @param readOnly только чтение (имя плана с файлом, §6.2: непрозрачность 0,75)
 * @param label    подпись или {@code null} — как в {@code FieldSpec} («Каждые N месяцев» / «Каждые N недель»)
 * @param options  варианты или {@code null} — как в {@code FieldSpec}
 * @param tooltip  подсказка или {@code null} — как в {@code FieldSpec}
 * @param min      минимум SPINNER или {@code null} — как в {@code FieldSpec}
 * @param max      максимум SPINNER или {@code null} — как в {@code FieldSpec}
 */
public record FieldView(String value, boolean visible, boolean enabled, boolean readOnly, String label,
                        List<Option> options, String tooltip, Long min, Long max) {

    /** Копирует список вариантов, если он задан, и проверяет диапазон. */
    public FieldView {
        options = options == null ? null : List.copyOf(options);
        if (min != null && max != null && min > max) {
            // Ошибка программиста в логике формы, пользователь её не видит: сообщение латиницей.
            throw new IllegalArgumentException("Spinner range min " + min + " > max " + max);
        }
    }

    /**
     * Поле без изменения диапазона (все прочие свойства как у полного конструктора).
     *
     * @param value    текст виджета или {@code null}
     * @param visible  видно ли поле
     * @param enabled  доступно ли
     * @param readOnly только чтение
     * @param label    подпись или {@code null}
     * @param options  варианты или {@code null}
     * @param tooltip  подсказка или {@code null}
     */
    public FieldView(String value, boolean visible, boolean enabled, boolean readOnly, String label,
                     List<Option> options, String tooltip) {
        this(value, visible, enabled, readOnly, label, options, tooltip, null, null);
    }

    /**
     * Видимое доступное поле со значением и свойствами из {@code FieldSpec}.
     *
     * @param value текст виджета или {@code null}
     * @return модель поля
     */
    public static FieldView of(String value) {
        return new FieldView(value, true, true, false, null, null, null, null, null);
    }

    /**
     * Копия с новым диапазоном спиннера.
     *
     * @param newMin минимум или {@code null}
     * @param newMax максимум или {@code null}
     * @return модель поля
     */
    public FieldView withRange(Long newMin, Long newMax) {
        return new FieldView(value, visible, enabled, readOnly, label, options, tooltip, newMin, newMax);
    }
}
