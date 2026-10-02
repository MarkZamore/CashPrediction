package ru.cashprediction.swing.ui;

import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.ui.form.*;

/** Специализированный аналог текстового диалога с общим каркасом формы. */
public final class SwingTextInput extends SwingFormDialog {
    /** Создаёт форму текстового ввода, сохраняя модель и валидацию ядра. */
    public SwingTextInput(SwingUiPort port, FormSession session, FormSpec spec, FormView view, Placement placement) {
        // JavaFX: TextInputDialog → Swing: SwingTextInput → Web: dialog с input
        super(port, session, spec, view, placement);
    }
}
