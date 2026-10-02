package ru.cashprediction.swing.ui;

import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.ui.form.*;

/** Специализированный аналог выбора с общей моделью поля CHOICE. */
public final class SwingChoice extends SwingFormDialog {
    /** Создаёт диалог выбора, не добавляя правила выбора в клиент. */
    public SwingChoice(SwingUiPort port, FormSession session, FormSpec spec, FormView view, Placement placement) {
        // JavaFX: ChoiceDialog<T> → Swing: SwingChoice → Web: dialog с select
        super(port, session, spec, view, placement);
    }
}
