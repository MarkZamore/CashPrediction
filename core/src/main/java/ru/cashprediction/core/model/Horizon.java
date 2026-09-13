package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Горизонт прогноза: на какой срок вперёд от даты начала плана строится таблица и график.
 *
 * <p>Горизонт — часть плана и хранится в его файле. От него отличается «период отображения»
 * ({@code ViewState.period}): тот лишь сужает видимую часть таблицы и в файл плана не пишется.</p>
 *
 * <p>Sealed-интерфейс с тремя вариантами позволяет разбирать их через {@code switch} с проверкой
 * полноты на этапе компиляции.</p>
 */
public sealed interface Horizon permits Horizon.Months, Horizon.Years, Horizon.Until {

    /** Максимум месяцев: 50 лет. Больше не нужно человеку и опасно для производительности графика. */
    int MAX_MONTHS = 600;

    /**
     * Последний день прогноза (включительно).
     *
     * @param start дата начала плана
     * @return последний день горизонта
     */
    LocalDate endDate(LocalDate start);

    /**
     * Текст для файла плана и прежних клиентов: «12 месяцев», «2 года», «до 2027-12-31». Слова берутся из грамматики
     * формата ({@link FormatWords}), потому что именно этот текст пишет {@code PlanMarkdownWriter}; новый интерфейс
     * подписывает горизонт через {@code UiFormats}.
     *
     * @return описание горизонта
     */
    String label();

    /**
     * Приблизительная длина горизонта в месяцах: для слайдера меню и проверок.
     *
     * @param start дата начала плана
     * @return число полных месяцев, не меньше 1
     */
    default long approximateMonths(LocalDate start) {
        return Math.max(1, ChronoUnit.MONTHS.between(start, endDate(start).plusDays(1)));
    }

    /**
     * Горизонт в месяцах: 12 месяцев от 01.09.2026 заканчиваются 31.08.2027.
     *
     * @param count число месяцев, 1..600
     */
    record Months(int count) implements Horizon {
        /** Проверяет диапазон. */
        public Months {
            if (count < 1 || count > MAX_MONTHS) {
                throw new IllegalArgumentException(Texts.get("horizon.error.monthsRange", MAX_MONTHS));
            }
        }

        @Override
        public LocalDate endDate(LocalDate start) {
            return start.plusMonths(count).minusDays(1);
        }

        @Override
        public String label() {
            return RuText.count(count, FormatWords.get("plan.unit.month.one"), FormatWords.get("plan.unit.month.few"),
                    FormatWords.get("plan.unit.month.many"));
        }
    }

    /**
     * Горизонт в годах.
     *
     * @param count число лет, 1..50
     */
    record Years(int count) implements Horizon {
        /** Проверяет диапазон. */
        public Years {
            if (count < 1 || count > MAX_MONTHS / 12) {
                throw new IllegalArgumentException(Texts.get("horizon.error.yearsRange", MAX_MONTHS / 12));
            }
        }

        @Override
        public LocalDate endDate(LocalDate start) {
            return start.plusYears(count).minusDays(1);
        }

        @Override
        public String label() {
            return RuText.count(count, FormatWords.get("plan.unit.year.one"), FormatWords.get("plan.unit.year.few"),
                    FormatWords.get("plan.unit.year.many"));
        }
    }

    /**
     * Горизонт до конкретной даты включительно.
     *
     * @param end последний день прогноза
     */
    record Until(LocalDate end) implements Horizon {
        /** Проверяет обязательное поле. */
        public Until {
            Objects.requireNonNull(end, "end");
        }

        /** Возвращает {@code end}, но не раньше даты начала плана (иначе прогноз был бы пустым). */
        @Override
        public LocalDate endDate(LocalDate start) {
            return end.isBefore(start) ? start : end;
        }

        @Override
        public String label() {
            return FormatWords.get("plan.horizon.until") + " " + DateFormats.iso(end);
        }
    }
}
