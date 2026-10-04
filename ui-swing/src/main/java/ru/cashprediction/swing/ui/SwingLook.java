package ru.cashprediction.swing.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.StyleContext;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.token.TokenCss;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Оформление нового рендерера: Metal, токены ядра и составные шрифты. */
public final class SwingLook {
    private SwingLook() { }

    /** Устанавливает общую светлую тему до создания первого виджета. */
    public static void install() {
        JComponent.setDefaultLocale(Locale.forLanguageTag("ru"));
        UIManager.put("swing.boldMetal", false);
        javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(new javax.swing.plaf.metal.DefaultMetalTheme());
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            throw new IllegalStateException("Metal", e);
        }
        List<Object> keys = new ArrayList<>(UIManager.getDefaults().keySet());
        for (Object key : keys) {
            if (key.toString().endsWith(".font")) UIManager.put(key, new FontUIResource(font(FontToken.BASE)));
        }
        TokenCss.swingDefaults().forEach((key, value) -> UIManager.put(key,
                value instanceof FontToken f ? new FontUIResource(font(f))
                        : value instanceof Integer n ? new ColorUIResource(new Color(n, true)) : value));
        UIManager.put("OptionPane.okButtonText", UiText.get("button.ok"));
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        UIManager.put("OptionPane.informationIcon", SwingIcons.icon("ℹ", ColorToken.ACCENT, DesignTokens.ALERT_ICON_SIZE));
        UIManager.put("OptionPane.warningIcon", SwingIcons.icon("⚠", ColorToken.WARN, DesignTokens.ALERT_ICON_SIZE));
        UIManager.put("OptionPane.errorIcon", SwingIcons.icon("✖", ColorToken.EXPENSE, DesignTokens.ALERT_ICON_SIZE));
        UIManager.put("OptionPane.questionIcon", SwingIcons.icon("?", ColorToken.ACCENT, DesignTokens.ALERT_ICON_SIZE));
        // JavaFX: Menu → Swing: JMenu → Web: div[role=menuitem] + div[role=menu]
        UIManager.put("Menu.arrowIcon", SwingIcons.icon("▸", DesignTokens.INLINE_ICON_SIZE));
        // JavaFX: CheckMenuItem / RadioMenuItem → Swing: JCheckBoxMenuItem / JRadioButtonMenuItem → Web: div[role=menuitemcheckbox] / div[role=menuitemradio]
        UIManager.put("CheckBoxMenuItem.checkIcon", SwingIcons.menuMark("✓"));
        UIManager.put("RadioButtonMenuItem.checkIcon", SwingIcons.menuMark("●"));
        UIManager.put("CheckBox.icon", SwingIcons.controlMark(false));
        UIManager.put("RadioButton.icon", SwingIcons.controlMark(true));
        // JavaFX: FileChooser / DirectoryChooser → Swing: JFileChooser → Web: FILE_BROWSER
        for (String key : List.of("FileView.directoryIcon", "FileView.hardDriveIcon", "FileView.floppyDriveIcon",
                "FileChooser.homeFolderIcon", "FileChooser.newFolderIcon"))
            UIManager.put(key, SwingIcons.icon("folder", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        UIManager.put("FileView.computerIcon", SwingIcons.icon("application", DesignTokens.INLINE_ICON_SIZE));
        for (String key : List.of("FileView.fileIcon", "FileChooser.listViewIcon", "FileChooser.detailsViewIcon"))
            UIManager.put(key, SwingIcons.icon("≡", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        UIManager.put("FileChooser.upFolderIcon", SwingIcons.icon("↑", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        UIManager.put("Table.ascendingSortIcon", SwingIcons.icon("↑", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        UIManager.put("Table.descendingSortIcon", SwingIcons.icon("▾", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE));
        for (String key : List.of("Button.foreground", "ToggleButton.foreground", "TextField.foreground", "FormattedTextField.foreground", "ComboBox.foreground", "Panel.foreground")) UIManager.put(key, new ColorUIResource(color(ColorToken.TEXT_PRIMARY)));
        UIManager.put("OptionPane.cancelButtonText", UiText.get("button.cancel"));
        UIManager.put("FileChooser.openButtonText", UiText.get("button.open"));
        UIManager.put("FileChooser.saveButtonText", UiText.get("button.save"));
        UIManager.put("FileChooser.cancelButtonText", UiText.get("button.cancel"));
        UIManager.put("FileChooser.fileNameLabelText", UiText.get("dialog.file.name") + ":");
        UIManager.put("FileChooser.upFolderToolTipText", UiText.get("dialog.file.up.tip"));
        ToolTipManager.sharedInstance().setInitialDelay(600);
        ToolTipManager.sharedInstance().setDismissDelay(20_000);
        UIManager.put("ToolTip.font", new FontUIResource(font(FontToken.LEGEND)));
        UIManager.put("ToolTip.background", new ColorUIResource(color(ColorToken.TOOLTIP_BG)));
        UIManager.put("ToolTip.foreground", new ColorUIResource(color(ColorToken.TOOLTIP_TEXT)));
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        // MetalToolTipUI добавляет ещё три пикселя с каждой стороны текста.
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createEmptyBorder(8, 5, 8, 5));
    }

    /** Возвращает составной шрифт, способный использовать системную подстановку глифов. */
    public static Font font(FontToken token) {
        return StyleContext.getDefaultStyleContext().getFont(token.primaryFamily(),
                token.bold() ? Font.BOLD : Font.PLAIN, token.sizePx());
    }

    /** Убирает нативные Metal-отступы из общей ширины кнопки диалога. */
    static void dialogButton(JButton button) {
        SwingIcons.decorate(button);
        button.setFont(font(FontToken.BASE));
        // LineBorder не включает AbstractButton.margin: реальный padding должен входить в составную рамку.
        button.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(color(ColorToken.BORDER)),
                javax.swing.BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        button.setMargin(new java.awt.Insets(4, 10, 4, 10));
        int width = Math.max(ru.cashprediction.core.ui.token.DesignTokens.BUTTON_MIN_WIDTH, button.getPreferredSize().width);
        button.setMinimumSize(new java.awt.Dimension(width, 28));
        button.setPreferredSize(new java.awt.Dimension(width, 28));
        button.setMaximumSize(new java.awt.Dimension(width, 28));
    }

    /** Переводит цвет ядра в цвет AWT, сохраняя прозрачность. */
    public static Color color(ColorToken token) {
        return new Color((token == null ? ColorToken.TEXT_PRIMARY : token).argb(), true);
    }

    /** Создаёт метку с заданными ядром текстом, цветом и шрифтом. */
    public static JLabel label(String text, ColorToken color, FontToken font) {
        JLabel label = new JLabel(text);
        label.setForeground(color(color));
        label.setFont(font(font));
        return label;
    }

    /** Назначает стабильный идентификатор реальному виджету. */
    public static <T extends JComponent> T id(T widget, String id) {
        widget.putClientProperty("cp.id", id);
        return widget;
    }

    /** Подсказка хранит исходный текст для дампа, а переносы рисуются через HTML Swing. */
    public static void tooltip(JComponent widget, String text) {
        widget.putClientProperty("cp.tooltip", text == null ? "" : text);
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        widget.setToolTipText(text == null || text.isEmpty() ? null : tooltipHtml(text));
    }

    /** Ограничивает длинную подсказку, но не растягивает короткий текст до максимальных 420 пикселей. */
    static String tooltipHtml(String text) {
        if (text == null || text.isEmpty()) return null;
        JLabel measure = label("", ColorToken.TOOLTIP_TEXT, FontToken.LEGEND);
        int width = text.lines().mapToInt(line -> measure.getFontMetrics(measure.getFont()).stringWidth(line)).max().orElse(1);
        return html(text, Math.max(1, Math.min(420 - 16, width)));
    }

    /** Декорирует только локализованные строки флагов таблицы, сохраняя название и пользовательскую заметку. */
    static String tableTooltipHtml(String text) {
        return tableTooltipHtml(text, text == null ? "" : text.split("\n", -1)[0]);
    }

    /** Рисует только явные позиции ядра, экранируя остальные фрагменты как обычный текст. */
    static String tableTooltipHtml(ru.cashprediction.core.ui.view.table.DecoratedTooltip value) {
        String html = tooltipHtml(value.text());
        if (html == null) return null;
        int contentStart = html.indexOf('>', html.indexOf("<div")) + 1;
        StringBuilder content = new StringBuilder();
        int offset = 0;
        for (var position : value.iconPositions()) {
            content.append(escape(value.text().substring(offset, position.offset())).replace("\n", "<br>"));
            content.append(SwingIcons.htmlImage(position.key(), ColorToken.TOOLTIP_TEXT));
            offset = position.offset() + position.key().length();
        }
        content.append(escape(value.text().substring(offset)).replace("\n", "<br>"));
        return html.substring(0, contentStart) + content + "</div></html>";
    }

    /** Название из ячейки защищает также многострочный пользовательский текст от подстановки значков. */
    static String tableTooltipHtml(String text, String title) {
        if (text == null || text.isEmpty()) return null;
        int titleLines = title != null && text.startsWith(title + "\n") ? title.split("\n", -1).length : 1;
        String html = tooltipHtml(text);
        int contentStart = html.indexOf('>', html.indexOf("<div")) + 1;
        StringBuilder content = new StringBuilder();
        String notePrefix = UiText.get("table.tip.note", "");
        String[] lines = text.split("\n", -1);
        boolean note = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) content.append("<br>");
            String painted = escape(lines[i]);
            if (lines[i].startsWith(notePrefix)) note = true;
            if (!note && i >= titleLines) for (String key : List.of("table.tip.amountChanged", "table.tip.moved", "table.tip.shifted",
                    "table.tip.skipped", "table.tip.whatIfAmount")) {
                if (!lines[i].equals(UiText.get(key))) continue;
                String glyph = lines[i].substring(0, 1);
                painted = SwingIcons.htmlImage(glyph, ColorToken.TOOLTIP_TEXT) + escape(lines[i].substring(1));
            }
            content.append(painted);
        }
        return html.substring(0, contentStart) + content + "</div></html>";
    }

    /** Экранирует готовый текст ядра для многострочной метки Swing. */
    public static String html(String text, int width) {
        // Swing HTML переводит CSS px через коэффициент 1,3; pt сохраняет размеры логических пикселей AWT.
        return "<html><div style='width:" + width + "pt'>" + escape(text).replace("\n", "<br>") + "</div></html>";
    }

    /** Экранирует пользовательский текст без интерпретации разметки. */
    public static String escape(String text) {
        return (text == null ? "" : text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
