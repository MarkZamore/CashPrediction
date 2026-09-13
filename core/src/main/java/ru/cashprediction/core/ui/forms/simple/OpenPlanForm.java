package ru.cashprediction.core.ui.forms.simple;

import java.util.List;
import java.util.Map;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.10 «Открыть план» (представление {@code LIST_CHOICE}, JavaFX {@code Dialog<R>} со списком; тип окна CHOICE,
 * назначение {@code openPlan}, ширина 560).
 *
 * <p>Заголовок «Планы в папке «{folder}»» или «В папке «{folder}» пока нет планов»; поле {@code value} «План» — список
 * 8 строк, элементы «{имя}   изменён dd.MM.yyyy HH:mm», текущий план с пометкой « (открыт)», последний элемент
 * «Из файла…»; по умолчанию текущий или первый; двойной щелчок = «Открыть». Кнопки [Открыть] [Отмена].</p>
 *
 * <p>Результат: {@code Close(Path файл плана)} или {@code Close(}{@link #FROM_FILE}{@code )} — тогда контроллер
 * показывает выбор файла «Открыть план из файла».</p>
 */
public final class OpenPlanForm implements FormLogic {

    /** Назначение окна. */
    public static final String PURPOSE = "openPlan";

    /** Результат «Из файла…». */
    public static final String FROM_FILE = "fromFile";

    private final List<PlanFileInfo> plans;

    /**
     * Создаёт форму для списка планов папки.
     *
     * @param plans планы папки, отсортированные для показа
     */
    public OpenPlanForm(List<PlanFileInfo> plans) {
        this.plans = List.copyOf(plans);
    }

    /** @return планы, из которых выбирает пользователь */
    public List<PlanFileInfo> plans() {
        return plans;
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — OpenPlanForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — OpenPlanForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — OpenPlanForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — OpenPlanForm.onButton");
    }
}
