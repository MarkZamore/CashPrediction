package ru.cashprediction.core.model;

import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;

/**
 * Тип денежной операции: поступление или трата.
 *
 * <p>Суммы операций в модели всегда положительные, знак задаёт именно тип. Так в файле плана
 * не бывает двусмысленности «расход -45 000» против «расход 45 000».</p>
 *
 * <p>Слово для файла ({@link #label()}) — грамматика формата ({@link FormatWords}), подпись для интерфейса
 * ({@link #title()}) — текст каталога ({@link Texts}). Оба ищутся лениво при вызове, а не в конструкторе enum:
 * отсутствующий ключ не должен ронять загрузку класса.</p>
 */
public enum Kind {
    /** Поступление денег: зарплата, аванс, премия. */
    INCOME(+1),
    /** Трата денег: аренда, продукты, кредит. */
    EXPENSE(-1);

    private final int sign;

    Kind(int sign) {
        this.sign = sign;
    }

    /** @return слово для файла плана, строчными: «доход» / «расход» */
    public String label() {
        return switch (this) {
            case INCOME -> FormatWords.get("plan.kind.income");
            case EXPENSE -> FormatWords.get("plan.kind.expense");
        };
    }

    /** @return подпись для интерфейса, с заглавной: «Доход» / «Расход» */
    public String title() {
        return switch (this) {
            case INCOME -> Texts.get("kind.title.income");
            case EXPENSE -> Texts.get("kind.title.expense");
        };
    }

    /** @return +1 для дохода, -1 для расхода */
    public int sign() {
        return sign;
    }

    /**
     * Превращает положительную сумму операции в изменение баланса.
     *
     * @param amount сумма операции (знак игнорируется)
     * @return {@code +amount} для дохода и {@code -amount} для расхода
     */
    public Money signed(Money amount) {
        Money abs = amount.abs();
        return sign > 0 ? abs : abs.negate();
    }
}
