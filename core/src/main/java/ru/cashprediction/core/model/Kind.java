package ru.cashprediction.core.model;

/**
 * Тип денежной операции: поступление или трата.
 *
 * <p>Суммы операций в модели всегда положительные, знак задаёт именно тип. Так в файле плана
 * не бывает двусмысленности «расход -45 000» против «расход 45 000».</p>
 */
public enum Kind {
    /** Поступление денег: зарплата, аванс, премия. */
    INCOME("доход", "Доход", +1),
    /** Трата денег: аренда, продукты, кредит. */
    EXPENSE("расход", "Расход", -1);

    private final String label;
    private final String title;
    private final int sign;

    Kind(String label, String title, int sign) {
        this.label = label;
        this.title = title;
        this.sign = sign;
    }

    /** @return слово для файла плана, строчными: «доход» / «расход» */
    public String label() {
        return label;
    }

    /** @return подпись для интерфейса, с заглавной: «Доход» / «Расход» */
    public String title() {
        return title;
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
