package ru.cashprediction.core.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.form.FieldChecks.MoneyRule;

/**
 * Общие тексты проверок полей (спецификация v2, §6.0) и тексты решения L1b.
 */
class FieldChecksTest {

    private static final String LABEL = "Сумма";

    @Test
    void requiredMoney() {
        assertEquals(Optional.of("Укажите сумму в поле «Сумма»"), FieldChecks.money(LABEL, "", MoneyRule.REQUIRED_POSITIVE));
        assertEquals(Optional.of("Укажите сумму в поле «Сумма»"), FieldChecks.money(LABEL, "  ", MoneyRule.NON_NEGATIVE));
        assertEquals(Optional.of("Укажите сумму в поле «Сумма»"), FieldChecks.money(LABEL, null, MoneyRule.ANY));
        assertEquals(Optional.empty(), FieldChecks.money(LABEL, "", MoneyRule.OPTIONAL_POSITIVE));
    }

    @Test
    void invalidAndTooBigMoney() {
        assertEquals(Optional.of("Поле «Сумма»: некорректная сумма «8о 000»"),
                FieldChecks.money(LABEL, "8о 000", MoneyRule.REQUIRED_POSITIVE));
        assertEquals(Optional.of("Поле «Сумма»: некорректная сумма «12 3»"),
                FieldChecks.money(LABEL, " 12 3 ", MoneyRule.ANY));
        assertEquals(Optional.of("Поле «Сумма»: слишком большая сумма"),
                FieldChecks.money(LABEL, "1 000 000 000 000", MoneyRule.REQUIRED_POSITIVE));
        assertEquals(Optional.of("Поле «Сумма»: слишком большая сумма"),
                FieldChecks.money(LABEL, "-99999999999999999999", MoneyRule.ANY));
        assertEquals(Optional.empty(), FieldChecks.money(LABEL, "999 999 999 999,99", MoneyRule.REQUIRED_POSITIVE));
    }

    @Test
    void signRules() {
        assertEquals(Optional.of("Поле «Сумма»: сумма должна быть больше нуля"),
                FieldChecks.money(LABEL, "0", MoneyRule.REQUIRED_POSITIVE));
        assertEquals(Optional.of("Поле «Сумма»: сумма должна быть больше нуля"),
                FieldChecks.money(LABEL, "-5", MoneyRule.OPTIONAL_POSITIVE));
        assertEquals(Optional.of("Поле «Сумма»: сумма не может быть отрицательной"),
                FieldChecks.money(LABEL, "-0,01", MoneyRule.NON_NEGATIVE));
        assertEquals(Optional.empty(), FieldChecks.money(LABEL, "0,00", MoneyRule.NON_NEGATIVE));
        assertEquals(Optional.empty(), FieldChecks.money(LABEL, "-1 200", MoneyRule.ANY));
        assertEquals(Optional.empty(), FieldChecks.money(LABEL, "80 000,5", MoneyRule.REQUIRED_POSITIVE));
    }

    @Test
    void moreThanTwoFractionDigitsExplainTheRule() {
        assertEquals(Optional.of("Поле «Сумма»: в сумме «1,234» больше двух цифр после запятой или точки. "
                        + "Если это тысячи, разделяйте разряды пробелом: «1 234»"),
                FieldChecks.money(LABEL, "1,234", MoneyRule.REQUIRED_POSITIVE));
        assertEquals(Optional.of("Поле «Сумма»: в сумме «12.345» больше двух цифр после запятой или точки. "
                        + "Если это тысячи, разделяйте разряды пробелом: «12 345»"),
                FieldChecks.money(LABEL, "12.345", MoneyRule.ANY));
        assertEquals(Optional.of("Поле «Сумма»: в сумме «0,005» больше двух цифр после запятой - копейки пишутся двумя "
                        + "цифрами, например 95 000,50"),
                FieldChecks.money(LABEL, "0,005", MoneyRule.REQUIRED_POSITIVE));
    }

    @Test
    void datesAndText() {
        assertEquals(Optional.of("Укажите дату в поле «Дата» (ДД.ММ.ГГГГ)"), FieldChecks.date("Дата", "", true));
        assertEquals(Optional.empty(), FieldChecks.date("Дата", " ", false));
        assertEquals(Optional.of("Поле «Дата»: некорректная дата (ДД.ММ.ГГГГ)"), FieldChecks.date("Дата", "31.02.2026", false));
        assertEquals(Optional.empty(), FieldChecks.date("Дата", "2026-10-05", true));
        assertEquals(Optional.of("Заполните поле «Название»"), FieldChecks.requiredText("Название", " "));
        assertEquals(Optional.empty(), FieldChecks.requiredText("Название", "Зарплата"));
    }

    @Test
    void firstReturnsFirstProblem() {
        assertEquals(Optional.of("b"), FieldChecks.first(Optional.empty(), Optional.of("b"), Optional.of("c")));
        assertEquals(Optional.empty(), FieldChecks.first(Optional.empty(), Optional.empty()));
    }
}
