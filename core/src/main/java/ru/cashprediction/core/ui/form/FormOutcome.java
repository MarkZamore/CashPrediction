package ru.cashprediction.core.ui.form;

import java.util.Objects;
import ru.cashprediction.core.session.WindowState;

/**
 * Результат нажатия кнопки или выбора в предпросмотре (архитектура §3.5).
 */
public sealed interface FormOutcome
        permits FormOutcome.Stay, FormOutcome.Page, FormOutcome.Close, FormOutcome.OpenChild, FormOutcome.Apply {

    /**
     * Выполнить действие, не закрывая форму (калькулятор цели, §6.6: «Записать цель в план», «Показать с доп.
     * экономией»). Дополнение к архитектуре §3.5: без него немодальная форма не могла бы менять план.
     *
     * @param action       действие для контроллера (тип описан у формы, например {@code GoalCalculatorForm.SaveGoal})
     * @param fieldUpdates новые значения полей формы после действия (например, очистить «Откладывать ещё в месяц»)
     */
    record Apply(Object action, java.util.Map<String, String> fieldUpdates) implements FormOutcome {
        /** Проверяет поля и копирует карту. */
        public Apply {
            Objects.requireNonNull(action, "action");
            fieldUpdates = fieldUpdates == null ? java.util.Map.of() : java.util.Map.copyOf(fieldUpdates);
        }
    }

    /**
     * Остаться в форме. Если {@code problem} не {@link Problem#NONE}, он заменяет строку проблем до следующего
     * изменения поля (например, ошибка ядра при применении, §6.0 «Поведение»).
     *
     * @param problem проблема или {@link Problem#NONE}
     */
    record Stay(Problem problem) implements FormOutcome {
        /** Подставляет {@link Problem#NONE}. */
        public Stay {
            problem = problem == null ? Problem.NONE : problem;
        }
    }

    /**
     * Перейти на страницу мастера.
     *
     * @param page номер страницы
     */
    record Page(int page) implements FormOutcome {
    }

    /**
     * Закрыть форму.
     *
     * @param result результат для контроллера: {@code null} — отмена; иначе объект, описанный у каждой формы
     *               (например, {@code Plan} мастера или {@code UnaryOperator<Plan>} правки)
     */
    record Close(Object result) implements FormOutcome {
    }

    /**
     * Открыть дочернее окно, владелец — эта форма (например, корректировка из предпросмотра редактора правила).
     *
     * <p>Форма ещё не знает id нового окна, а {@code WindowState} не принимает пустой id, поэтому форма пишет в
     * {@code child} id {@link #PENDING_ID} и любого владельца; контроллер заменяет оба
     * ({@code child.withIds(recorder.nextWindowId(), parent.windowId())}) и открывает окно через
     * {@code FlowContext.openForm}.</p>
     *
     * @param child состояние дочернего окна (тип, контекст, начальные поля; id — {@link #PENDING_ID})
     */
    record OpenChild(WindowState child) implements FormOutcome {

        /** Временный id дочернего окна, который контроллер заменяет настоящим. */
        public static final String PENDING_ID = "pending";

        /** Проверяет поле. */
        public OpenChild {
            Objects.requireNonNull(child, "child");
        }
    }

    /** @return «остаться без изменений» */
    static FormOutcome stay() {
        return new Stay(Problem.NONE);
    }
}
