package ru.cashprediction.core.markdown;

import java.time.LocalDate;
import ru.cashprediction.core.model.*;

/** Независимый рукописный вход старого формата: новый писатель не создаёт исходную фикстуру. */
public final class PlanCellCodecFixtures {
    /** Дата разбора, не зависящая от часов машины. */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    /** Буквальные токены, труба и обратные косые черты в старом примечании. */
    public static final String NOTE = "literal <br> &lt; &#13; &amp; > | \\| C:\\Users\\Oscar\\notes\\n";
    /** Рукописный старый план со всеми тремя разновидностями примечаний. */
    public static final String V1 = """
            # План: Legacy

            ## Параметры
            - Формат: CashPrediction 1
            - Валюта: ₽
            - Начало: 2026-09-01
            - Горизонт: 12 месяцев
            - Начальный баланс: 0

            ## Регулярные операции
            | ID | Название | Тип | Сумма | Повтор | Заметка |
            |----|----------|-----|-------|--------|---------|
            | r1 | Rule | доход | 100 | ежемесячно 5 | literal <br> &lt; &#13; &amp; > \\| \\\\| C:\\Users\\Oscar\\notes\\n |

            ## Разовые операции
            | ID | Дата | Название | Тип | Сумма | Заметка |
            |----|------|----------|-----|-------|---------|
            | t1 | 2026-09-20 | Once | доход | 100 | literal <br> &lt; &#13; &amp; > \\| \\\\| C:\\Users\\Oscar\\notes\\n |

            ## Корректировки
            | Правило | Исходная дата | Действие | Новая сумма | Заметка |
            |---------|---------------|----------|-------------|---------|
            | r1 | 2026-09-05 | изменить | 200 | literal <br> &lt; &#13; &amp; > \\| \\\\| C:\\Users\\Oscar\\notes\\n |
            """;

    private PlanCellCodecFixtures() { }

    /** Создаёт план с заданными примечаниями для проверки нового кодека через все хранилища. */
    public static Plan withNotes(String note) {
        Plan base = PlanMarkdownReader.read(V1, "unused", TODAY).plan();
        RecurringRule r = base.rules().getFirst();
        OneTimeTransaction t = base.oneTimes().getFirst();
        Adjustment a = base.adjustments().getFirst();
        return base.withRules(java.util.List.of(new RecurringRule(r.id(), r.title(), r.kind(), r.amount(),
                r.category(), r.recurrence(), r.from(), r.until(), r.weekendPolicy(), r.enabled(), note)))
                .withOneTimes(java.util.List.of(new OneTimeTransaction(t.id(), t.date(), t.title(), t.kind(),
                        t.amount(), t.category(), note)))
                .withAdjustments(java.util.List.of(new Adjustment(a.key(), a.action(), note)));
    }
}
