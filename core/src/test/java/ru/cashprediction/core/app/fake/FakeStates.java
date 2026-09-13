package ru.cashprediction.core.app.fake;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Plan;

/**
 * Готовые снимки {@link AppState} для тестов моделей и форм, которым не нужен контроллер.
 */
public final class FakeStates {

    /** «Сегодня» эталонных сценариев (архитектура §6.3). */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private FakeStates() {
    }

    /**
     * Состояние с планом без файла, видом и настройками по умолчанию.
     *
     * @param profile    профиль клиента
     * @param plan       план
     * @param cashMemory папка CashMemory (не создаётся)
     * @return состояние ревизии 1
     */
    public static AppState withPlan(ClientProfile profile, Plan plan, Path cashMemory) {
        DocumentView document = new DocumentView(plan, null, false, false, "", false, "", null, "", List.of());
        return new AppState(1, profile, TODAY, cashMemory, null, document, ViewState.defaults(), "", false,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /**
     * Состояние с пустым планом «Мой план».
     *
     * @param profile    профиль клиента
     * @param cashMemory папка CashMemory (не создаётся)
     * @return состояние ревизии 1
     */
    public static AppState empty(ClientProfile profile, Path cashMemory) {
        return withPlan(profile, Plan.empty("Plan", TODAY), cashMemory);
    }
}
