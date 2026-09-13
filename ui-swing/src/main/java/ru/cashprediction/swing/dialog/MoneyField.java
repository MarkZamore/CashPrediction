package ru.cashprediction.swing.dialog;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Optional;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Money;

/**
 * Поле ввода суммы: принимает «80 000,00», «80000.5», «1 200» и т. п. ({@link Money#parse}).
 *
 * <p>Текст не форматируется при каждом нажатии, иначе курсор прыгал бы; при потере фокуса корректная сумма
 * приводится к виду «80 000,00». Неверный текст остаётся как набран — это ошибка формы, которую показывает
 * диалог, а для снимка сессии он сохраняется дословно ({@link #canonicalValue()}).</p>
 *
 * <p>Компонент используется только в потоке EDT.</p>
 */
public final class MoneyField extends JTextField {

    /**
     * Создаёт пустое поле суммы.
     */
    public MoneyField() {
        super(12);
        setHorizontalAlignment(SwingConstants.RIGHT);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setToolTipText("Сумма, например 80 000,00");
        addFocusListener(new FocusAdapter() {
            /** Поле потеряло фокус: ввод завершается. */
            @Override
            public void focusLost(FocusEvent e) {
                // Приводим корректную сумму к единому виду; некорректный текст не трогаем.
                value().ifPresent(money -> {
                    String formatted = money.format();
                    if (!formatted.equals(getText())) {
                        setText(formatted);
                    }
                });
            }
        });
    }

    /**
     * Сумма из поля.
     *
     * @return сумма или пусто, если поле пустое или текст не разбирается
     */
    public Optional<Money> value() {
        if (getText().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Money.parse(getText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Записывает сумму в виде «80 000,00».
     *
     * @param money сумма; {@code null} очищает поле
     */
    public void setValue(Money money) {
        setText(money == null ? "" : money.format());
    }

    /**
     * Пусто ли поле.
     *
     * @return {@code true}, если текст пустой
     */
    public boolean isBlank() {
        return getText().isBlank();
    }

    /**
     * Текст ошибки для формы.
     *
     * @param fieldTitle   название поля («Сумма»)
     * @param required     обязательно ли поле
     * @param positiveOnly должна ли сумма быть больше нуля (суммы операций), иначе допускается любой знак
     * @return текст ошибки или {@code null}, если значение допустимо
     */
    public String validationError(String fieldTitle, boolean required, boolean positiveOnly) {
        if (isBlank()) {
            return required ? "Укажите поле «" + fieldTitle + "»" : null;
        }
        Optional<Money> money = value();
        if (money.isEmpty()) {
            return "Поле «" + fieldTitle + "»: некорректная сумма";
        }
        if (positiveOnly && !money.get().isPositive()) {
            return "Поле «" + fieldTitle + "»: сумма должна быть больше нуля";
        }
        if (money.get().abs().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
            return "Поле «" + fieldTitle + "»: слишком большая сумма";
        }
        return null;
    }

    /**
     * Значение для снимка сессии: {@link Money#formatPlain()} («95000,00») или текст как набран.
     *
     * @return каноническое значение
     */
    public String canonicalValue() {
        if (isBlank()) {
            return "";
        }
        return value().map(Money::formatPlain).orElse(getText());
    }

    /**
     * Восстанавливает значение из снимка: корректная сумма показывается как «95 000,00», иной текст — как есть.
     *
     * @param canonical значение из снимка
     */
    public void applyCanonical(String canonical) {
        String value = canonical == null ? "" : canonical;
        try {
            setText(value.isBlank() ? "" : Money.parse(value).format());
        } catch (IllegalArgumentException e) {
            setText(value);
        }
    }
}
