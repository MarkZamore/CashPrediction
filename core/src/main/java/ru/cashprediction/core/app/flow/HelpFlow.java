package ru.cashprediction.core.app.flow;

import java.util.Objects;
import ru.cashprediction.core.io.AppInfo;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.command.HotkeyTable;

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
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        context.showAlert(AlertCatalog.about(AppInfo.displayVersion(), context.port().profile(),
                System.getProperty("java.version", ""), context.environment().cashMemory()), button -> { });
    }

    /** {@code help.hotkeys}: §6.19 ({@code HotkeyTable.text()}). */
    public void hotkeys() {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        context.showAlert(AlertCatalog.hotkeys(HotkeyTable.text()), button -> { });
    }

    /** {@code help.format}: §6.20 ({@code MarkdownFormat.userGuide()}). */
    public void format() {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        context.showAlert(AlertCatalog.fileFormat(MarkdownFormat.userGuide()), button -> { });
    }
}
