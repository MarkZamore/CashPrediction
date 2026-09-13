package ru.cashprediction.swing.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.UIManager;

/**
 * Панель диалога: заголовок, содержимое, строка проверки, раскрываемая область «Подробнее» и панель кнопок.
 * Swing-аналог JavaFX {@code DialogPane}.
 *
 * <p>Раскладка сверху вниз: область заголовка ({@link #setHeaderText}), содержимое ({@link #setContent}),
 * строка с сообщением проверки формы ({@link #setValidationMessage}), переключатель «Подробнее ▸» и
 * раскрываемое содержимое ({@link #setExpandableContent}), панель кнопок ({@link #setButtonTypes}).
 * Кнопки расставляются по ролям в порядке Windows: прочие слева, затем «Назад», «Далее», «Готово»/«ОК»,
 * «Отмена». Кнопку конкретного типа можно найти через {@link #lookupButton} — например, чтобы отключить
 * «ОК», пока форма невалидна (это делает {@link SwingDialog}).</p>
 *
 * <p>Компонент используется только в потоке EDT.</p>
 */
// JavaFX: DialogPane → Swing: SwingDialogPane (JPanel: header/content/кнопки, lookupButton) → Web: <dialog><form method="dialog">
public final class SwingDialogPane extends JPanel {

    /** Цвет текста ошибки проверки. */
    private static final Color ERROR_COLOR = new Color(0xB0, 0x1E, 0x1E);
    /** Цвет текста предупреждения проверки (не блокирует «ОК»). */
    private static final Color WARNING_COLOR = new Color(0x8A, 0x5A, 0x00);

    private final JPanel headerPanel = new JPanel(new BorderLayout());
    private final JLabel headerLabel = new JLabel();
    private final JPanel contentHolder = new JPanel(new BorderLayout());
    private final JLabel validationLabel = new JLabel(" ");
    private final JButton detailsToggle = new JButton();
    private final JPanel detailsHolder = new JPanel(new BorderLayout());
    private final JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final Map<SwingButtonType, JButton> buttons = new LinkedHashMap<>();

    private String headerText = "";
    private JComponent expandableContent;
    private boolean expanded;
    private Consumer<SwingButtonType> onButton = type -> { };
    private Runnable onLayoutChanged = () -> { };

    /**
     * Создаёт пустую панель без кнопок.
     */
    public SwingDialogPane() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        // Заголовок как у JavaFX DialogPane: отдельная полоса с полужирным текстом и разделителем снизу.
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        headerPanel.add(headerLabel, BorderLayout.CENTER);
        headerPanel.add(new JSeparator(), BorderLayout.SOUTH);
        Color panelBackground = UIManager.getColor("Panel.background");
        if (panelBackground != null) {
            headerPanel.setBackground(panelBackground.brighter());
        }
        headerPanel.setVisible(false);

        contentHolder.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        validationLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 4, 12));
        validationLabel.setForeground(ERROR_COLOR);

        // «Подробнее» оформлен как ссылка: плоская кнопка без рамки, как Hyperlink в JavaFX DialogPane.
        detailsToggle.setBorderPainted(false);
        detailsToggle.setContentAreaFilled(false);
        detailsToggle.setFocusPainted(false);
        detailsToggle.setForeground(new Color(0x1A, 0x5F, 0xB4));
        detailsToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detailsToggle.addActionListener(e -> setExpanded(!expanded));
        detailsToggle.setVisible(false);
        detailsHolder.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        detailsHolder.setVisible(false);

        JPanel detailsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        detailsRow.add(detailsToggle);

        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        middle.add(contentHolder);
        middle.add(leftAligned(validationLabel));
        middle.add(detailsRow);
        middle.add(detailsHolder);
        for (java.awt.Component c : middle.getComponents()) {
            ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
        }

        buttonBar.setBorder(BorderFactory.createEmptyBorder(6, 12, 0, 12));

        add(headerPanel, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(buttonBar, BorderLayout.SOUTH);
        updateDetailsToggleText();
    }

    private static JComponent leftAligned(JComponent component) {
        JPanel row = new JPanel(new BorderLayout());
        row.add(component, BorderLayout.WEST);
        return row;
    }

    // ------------------------------------------------------------------ заголовок и содержимое

    /**
     * Задаёт текст заголовка; пустой текст скрывает область заголовка.
     *
     * @param text текст (может быть многострочным)
     */
    public void setHeaderText(String text) {
        headerText = text == null ? "" : text;
        headerLabel.setText(SwingText.htmlWrapped(headerText, 420));
        headerPanel.setVisible(!headerText.isBlank());
        onLayoutChanged.run();
    }

    /**
     * Текст заголовка.
     *
     * @return текст; пустая строка, если заголовка нет
     */
    public String getHeaderText() {
        return headerText;
    }

    /**
     * Задаёт основное содержимое (форму).
     *
     * @param content компонент содержимого
     */
    public void setContent(JComponent content) {
        contentHolder.removeAll();
        if (content != null) {
            contentHolder.add(content, BorderLayout.CENTER);
        }
        contentHolder.revalidate();
        onLayoutChanged.run();
    }

    /**
     * Показывает сообщение проверки формы под содержимым.
     *
     * @param message текст; пустой — скрыть
     * @param error   {@code true} — ошибка (красный), {@code false} — предупреждение (коричневый)
     */
    public void setValidationMessage(String message, boolean error) {
        boolean empty = message == null || message.isBlank();
        validationText = empty ? "" : message;
        // Неразрывный пробел держит высоту строки, чтобы окно не прыгало при появлении сообщения.
        validationLabel.setText(empty ? " " : SwingText.htmlWrapped(message, 420));
        validationLabel.setForeground(error ? ERROR_COLOR : WARNING_COLOR);
    }

    /** Текст последнего сообщения проверки (без HTML-разметки). */
    private String validationText = "";

    /**
     * Текст сообщения проверки, которое сейчас показано под формой.
     *
     * @return текст без разметки; пустая строка, если сообщения нет
     */
    public String getValidationText() {
        return validationText;
    }

    // ------------------------------------------------------------------ «Подробнее»

    /**
     * Задаёт раскрываемое содержимое («Подробнее»): стек ошибки, XML снимка, текст справки.
     *
     * @param content компонент или {@code null}, чтобы убрать область
     */
    public void setExpandableContent(JComponent content) {
        expandableContent = content;
        detailsHolder.removeAll();
        if (content != null) {
            detailsHolder.add(content, BorderLayout.CENTER);
        }
        detailsToggle.setVisible(content != null);
        setExpanded(expanded && content != null);
    }

    /**
     * Раскрыто ли содержимое «Подробнее».
     *
     * @return {@code true}, если раскрыто
     */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Раскрывает или сворачивает область «Подробнее».
     *
     * @param value {@code true} — раскрыть
     */
    public void setExpanded(boolean value) {
        expanded = value && expandableContent != null;
        detailsHolder.setVisible(expanded);
        updateDetailsToggleText();
        revalidate();
        onLayoutChanged.run();
    }

    private void updateDetailsToggleText() {
        detailsToggle.setText(expanded ? "Скрыть подробности ▾" : "Подробнее ▸");
    }

    // ------------------------------------------------------------------ кнопки

    /**
     * Задаёт набор кнопок; прежние кнопки удаляются.
     *
     * @param types типы кнопок в любом порядке: расстановка определяется ролями
     */
    public void setButtonTypes(SwingButtonType... types) {
        buttons.clear();
        buttonBar.removeAll();
        List<SwingButtonType> ordered = new ArrayList<>();
        // Порядок Windows: прочие действия, «Назад», «Далее», «Готово»/«ОК», «Отмена».
        addWithRole(ordered, types, SwingButtonType.Role.OTHER);
        addWithRole(ordered, types, SwingButtonType.Role.BACK_PREVIOUS);
        addWithRole(ordered, types, SwingButtonType.Role.NEXT_FORWARD);
        addWithRole(ordered, types, SwingButtonType.Role.FINISH);
        addWithRole(ordered, types, SwingButtonType.Role.OK_DONE);
        addWithRole(ordered, types, SwingButtonType.Role.CANCEL_CLOSE);
        boolean otherPlaced = false;
        for (SwingButtonType type : ordered) {
            if (type.role() != SwingButtonType.Role.OTHER && !otherPlaced) {
                // Отступ отделяет «прочие» кнопки от основных, как ButtonBar в JavaFX.
                buttonBar.add(Box.createHorizontalStrut(18));
                otherPlaced = true;
            }
            JButton button = new JButton(type.text());
            button.addActionListener(e -> onButton.accept(type));
            buttons.put(type, button);
            buttonBar.add(button);
        }
        buttonBar.revalidate();
        onLayoutChanged.run();
    }

    private static void addWithRole(List<SwingButtonType> target, SwingButtonType[] types, SwingButtonType.Role role) {
        for (SwingButtonType type : types) {
            if (type.role() == role && !target.contains(type)) {
                target.add(type);
            }
        }
    }

    /**
     * Типы кнопок в порядке расстановки.
     *
     * @return неизменяемый список
     */
    public List<SwingButtonType> getButtonTypes() {
        return List.copyOf(buttons.keySet());
    }

    /**
     * Находит кнопку по типу (аналог {@code DialogPane.lookupButton}).
     *
     * @param type тип кнопки
     * @return кнопка или {@code null}, если такого типа в панели нет
     */
    public JButton lookupButton(SwingButtonType type) {
        return buttons.get(type);
    }

    /**
     * Первая подтверждающая кнопка ({@code OK_DONE}/{@code FINISH}) — кнопка по умолчанию для Enter.
     *
     * @return тип кнопки или пусто
     */
    public Optional<SwingButtonType> defaultButtonType() {
        return buttons.keySet().stream().filter(SwingButtonType::isDefaultButton).findFirst();
    }

    /**
     * Первая кнопка отмены ({@code CANCEL_CLOSE}) — срабатывает на Esc и крестик окна.
     *
     * @return тип кнопки или пусто
     */
    public Optional<SwingButtonType> cancelButtonType() {
        return buttons.keySet().stream().filter(SwingButtonType::isCancelButton).findFirst();
    }

    /**
     * Задаёт обработчик нажатия любой кнопки.
     *
     * @param handler получатель типа нажатой кнопки
     */
    public void setOnButton(Consumer<SwingButtonType> handler) {
        onButton = handler == null ? type -> { } : handler;
    }

    /**
     * Задаёт действие при изменении раскладки (диалог перестраивает размер окна).
     *
     * @param action действие
     */
    public void setOnLayoutChanged(Runnable action) {
        onLayoutChanged = action == null ? () -> { } : action;
    }
}
