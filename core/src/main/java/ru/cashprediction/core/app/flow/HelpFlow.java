package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Меню «Справка» (спецификация v2, §3.6, §6.18–§6.20).
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class HelpFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public HelpFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** {@code help.about}: §6.18 ({@code AlertCatalog.about}). */
    public void about() {
        throw new UnsupportedOperationException("S2: core-app-edit — HelpFlow.about");
    }

    /** {@code help.hotkeys}: §6.19 ({@code HotkeyTable.text()}). */
    public void hotkeys() {
        throw new UnsupportedOperationException("S2: core-app-edit — HelpFlow.hotkeys");
    }

    /** {@code help.format}: §6.20 ({@code MarkdownFormat.userGuide()}). */
    public void format() {
        throw new UnsupportedOperationException("S2: core-app-edit — HelpFlow.format");
    }
}
