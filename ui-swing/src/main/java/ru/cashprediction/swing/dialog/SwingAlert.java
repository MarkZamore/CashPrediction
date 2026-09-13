package ru.cashprediction.swing.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Сообщение пользователю: информация, предупреждение, ошибка или подтверждение. Swing-аналог JavaFX {@code Alert}.
 *
 * <p>Построено на {@code new JOptionPane(...).createDialog(...)}, а не на статических
 * {@code JOptionPane.showMessageDialog/showConfirmDialog}: статические методы блокируют вызывающий код до закрытия
 * окна, а все окна клиента показываются по неблокирующей модели ({@link SwingDialogHost}). Выбор пользователя
 * ловится слушателем свойства {@link JOptionPane#VALUE_PROPERTY} и передаётся в колбэк {@link #setOnResult}
 * после закрытия окна.</p>
 *
 * <p>Как у {@code Alert}, есть заголовок (полужирный), основной текст, набор кнопок {@link SwingButtonType} и
 * раскрываемая область «Подробнее» ({@link #setDetails}) для стека ошибки, XML снимка или справки.</p>
 *
 * <p>Сообщение может быть восстанавливаемым ({@link #makeRestorable}): тогда оно попадает в снимок как окно
 * {@link WindowType#ALERT} с контекстом {@code purpose}/{@code targetId} и после сбоя открывается с тем же текстом
 * (например, подтверждение удаления правила).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Alert → Swing: SwingAlert (JOptionPane.createDialog, выбор через VALUE_PROPERTY) → Web: <dialog class="alert">
public final class SwingAlert implements SwingHostedWindow {

    /** Вид сообщения (аналог {@code Alert.AlertType}). */
    public enum AlertType {
        /** Информация. */
        INFORMATION(JOptionPane.INFORMATION_MESSAGE),
        /** Предупреждение. */
        WARNING(JOptionPane.WARNING_MESSAGE),
        /** Ошибка. */
        ERROR(JOptionPane.ERROR_MESSAGE),
        /** Вопрос с подтверждением. */
        CONFIRMATION(JOptionPane.QUESTION_MESSAGE);

        private final int messageType;

        AlertType(int messageType) {
            this.messageType = messageType;
        }
    }

    private final JOptionPane optionPane;
    private final JDialog dialog;
    private final SwingButtonType[] buttons;
    private final JPanel detailsHolder = new JPanel(new BorderLayout());
    private final JButton detailsToggle = new JButton("Подробнее ▸");
    private final Map<String, String> context = new LinkedHashMap<>();

    private Consumer<Optional<SwingButtonType>> onResult = result -> { };
    private WindowType type;
    private String ownerId = WindowState.MAIN_OWNER;
    private String windowId;
    private boolean boundsRestored;
    private boolean closed;

    /**
     * Создаёт сообщение.
     *
     * @param owner   окно-владелец; {@code null} — общий скрытый фрейм Swing
     * @param kind    вид сообщения
     * @param title   заголовок окна
     * @param header  полужирный заголовок текста; пустой — без заголовка
     * @param content основной текст
     * @param buttons кнопки; если не заданы — «ОК» и «Отмена» для подтверждения, «ОК» для остальных
     */
    public SwingAlert(Window owner, AlertType kind, String title, String header, String content, SwingButtonType... buttons) {
        Objects.requireNonNull(kind, "kind");
        this.buttons = buttons.length > 0 ? buttons.clone()
                : kind == AlertType.CONFIRMATION
                ? new SwingButtonType[] {SwingButtonType.OK, SwingButtonType.CANCEL}
                : new SwingButtonType[] {SwingButtonType.OK};

        JPanel message = new JPanel(new BorderLayout(0, 6));
        StringBuilder html = new StringBuilder("<html><div style='width:380px'>");
        if (header != null && !header.isBlank()) {
            html.append("<b>").append(SwingText.escape(header).replace("\n", "<br>")).append("</b>");
            if (content != null && !content.isBlank()) {
                html.append("<br><br>");
            }
        }
        html.append(SwingText.escape(content).replace("\n", "<br>")).append("</div></html>");
        message.add(new JLabel(html.toString()), BorderLayout.NORTH);

        // «Подробнее» — ссылка-переключатель, как expandableContent у JavaFX Alert.
        detailsToggle.setBorderPainted(false);
        detailsToggle.setContentAreaFilled(false);
        detailsToggle.setFocusPainted(false);
        detailsToggle.setForeground(new Color(0x1A, 0x5F, 0xB4));
        detailsToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detailsToggle.setHorizontalAlignment(JButton.LEFT);
        detailsToggle.setVisible(false);
        detailsToggle.addActionListener(e -> setExpanded(!detailsHolder.isVisible()));
        detailsHolder.setVisible(false);
        detailsHolder.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JPanel details = new JPanel(new BorderLayout());
        details.add(detailsToggle, BorderLayout.NORTH);
        details.add(detailsHolder, BorderLayout.CENTER);
        message.add(details, BorderLayout.CENTER);

        SwingButtonType initial = Arrays.stream(this.buttons).filter(SwingButtonType::isDefaultButton).findFirst()
                .orElse(this.buttons[0]);
        // JavaFX: ButtonType → Swing: SwingButtonType как опции JOptionPane → Web: <button value>
        optionPane = new JOptionPane(message, kind.messageType, JOptionPane.DEFAULT_OPTION, null, this.buttons, initial);
        dialog = optionPane.createDialog(owner, title);
        // createDialog делает окно APPLICATION_MODAL; как и остальные диалоги клиента, блокируем только «документ».
        dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        dialog.setResizable(true);
        if (owner == null) {
            dialog.setIconImages(SwingIcons.appIcons());
        }
        optionPane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, event -> {
            Object value = event.getNewValue();
            if (value == JOptionPane.UNINITIALIZED_VALUE) {
                // JOptionPane сбрасывает значение при показе окна — это не выбор пользователя.
                return;
            }
            finish(value instanceof SwingButtonType chosen ? chosen : cancelType());
        });
        dialog.addWindowListener(new WindowAdapter() {
            /** Окно закрыто без выбора кнопки (например, программно): результатом считается отмена. */
            @Override
            public void windowClosed(WindowEvent e) {
                // Окно закрыто без выбора (например, программно) — считаем отменой.
                finish(cancelType());
            }
        });
    }

    private SwingButtonType cancelType() {
        return Arrays.stream(buttons).filter(SwingButtonType::isCancelButton).findFirst().orElse(null);
    }

    private void finish(SwingButtonType chosen) {
        if (closed) {
            return;
        }
        closed = true;
        dialog.dispose();
        SwingUtilities.invokeLater(() -> onResult.accept(Optional.ofNullable(chosen)));
    }

    // ------------------------------------------------------------------ настройка

    /**
     * Задаёт содержимое области «Подробнее».
     *
     * @param content компонент (обычно {@link SwingText#readOnlyArea})
     */
    public void setDetails(JComponent content) {
        detailsHolder.removeAll();
        if (content != null) {
            detailsHolder.add(content, BorderLayout.CENTER);
        }
        detailsToggle.setVisible(content != null);
    }

    /**
     * Задаёт текст области «Подробнее» (моноширинный, только для чтения).
     *
     * @param text текст
     */
    public void setDetailsText(String text) {
        setDetails(SwingText.readOnlyArea(text, 14, 70));
    }

    /**
     * Раскрывает или сворачивает «Подробнее».
     *
     * @param expanded {@code true} — раскрыть
     */
    public void setExpanded(boolean expanded) {
        detailsHolder.setVisible(expanded && detailsHolder.getComponentCount() > 0);
        detailsToggle.setText(detailsHolder.isVisible() ? "Скрыть подробности ▾" : "Подробнее ▸");
        if (dialog.isShowing()) {
            dialog.pack();
        }
    }

    /**
     * Получатель выбранной кнопки. Вызывается один раз после закрытия окна.
     *
     * @param consumer получатель: кнопка или пусто, если окно закрыто без кнопки отмены
     */
    public void setOnResult(Consumer<Optional<SwingButtonType>> consumer) {
        onResult = consumer == null ? result -> { } : consumer;
    }

    /**
     * Делает сообщение восстанавливаемым окном {@link WindowType#ALERT}.
     *
     * @param newOwnerId владелец для снимка ({@code main} или {@code wN})
     * @param purpose    назначение ({@code deleteRule}, {@code deleteOneTime}, ...)
     * @param targetId   объект плана, к которому относится сообщение; пустой — нет
     */
    public void makeRestorable(String newOwnerId, String purpose, String targetId) {
        type = WindowType.ALERT;
        ownerId = newOwnerId == null || newOwnerId.isBlank() ? WindowState.MAIN_OWNER : newOwnerId;
        context.put(WindowType.CONTEXT_PURPOSE, Objects.requireNonNullElse(purpose, ""));
        context.put(WindowType.CONTEXT_TARGET_ID, Objects.requireNonNullElse(targetId, ""));
    }

    /**
     * Кнопка сообщения по типу (например, чтобы добавить подсказку к недоступному действию).
     *
     * @param buttonType тип кнопки
     * @return кнопка или пусто, если она ещё не создана интерфейсом {@code JOptionPane}
     */
    public Optional<JButton> lookupButton(SwingButtonType buttonType) {
        return findButton(optionPane, buttonType.text());
    }

    /**
     * Выбирает кнопку программно, как щелчок пользователя. Средство самотеста и автоответов.
     *
     * @param buttonType тип кнопки из набора сообщения
     * @return {@code true}, если такая кнопка есть и сообщение закрыто
     */
    public boolean press(SwingButtonType buttonType) {
        if (closed || !Arrays.asList(buttons).contains(buttonType)) {
            return false;
        }
        // Через значение JOptionPane: сработает тот же слушатель VALUE_PROPERTY, что и при щелчке.
        optionPane.setValue(buttonType);
        return true;
    }

    /**
     * Нажимает подтверждающую кнопку (или первую кнопку, если подтверждающей нет).
     *
     * @return {@code true}, если сообщение закрыто
     */
    public boolean pressDefault() {
        SwingButtonType chosen = Arrays.stream(buttons).filter(SwingButtonType::isDefaultButton).findFirst().orElse(buttons[0]);
        return press(chosen);
    }

    /**
     * Нажимает кнопку отмены; если её нет — закрывает сообщение без выбора.
     *
     * @return {@code true}, если сообщение закрыто
     */
    public boolean pressCancel() {
        SwingButtonType cancel = cancelType();
        if (cancel != null) {
            return press(cancel);
        }
        if (closed) {
            return false;
        }
        finish(null);
        return true;
    }

    /**
     * Закрыто ли сообщение.
     *
     * @return {@code true} после выбора кнопки или закрытия окна
     */
    public boolean isClosed() {
        return closed;
    }

    private static Optional<JButton> findButton(java.awt.Container container, String text) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return Optional.of(button);
            }
            if (child instanceof java.awt.Container nested) {
                Optional<JButton> found = findButton(nested, text);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ SwingHostedWindow

    /** {@inheritDoc} */
    @Override
    public JDialog window() {
        return dialog;
    }

    /** {@inheritDoc} */
    @Override
    public void assignWindowId(String id) {
        if (windowId == null) {
            windowId = id;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void prepareForShow() {
        dialog.pack();
        if (boundsRestored && SwingDialogHost.isOnScreen(dialog.getBounds())) {
            return;
        }
        dialog.setLocationRelativeTo(dialog.getOwner());
    }

    /** {@inheritDoc} */
    @Override
    public String windowId() {
        return windowId;
    }

    /** {@inheritDoc} */
    @Override
    public WindowType windowType() {
        return type;
    }

    /** {@inheritDoc} */
    @Override
    public boolean modal() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public String ownerId() {
        return ownerId;
    }

    /** {@inheritDoc} */
    @Override
    public WindowState captureState() {
        if (type == null || windowId == null) {
            return null;
        }
        Rectangle r = dialog.getBounds();
        WindowBounds bounds = dialog.isShowing() && r.width > 0 ? new WindowBounds(r.x, r.y, r.width, r.height) : null;
        return new WindowState(windowId, type, true, ownerId, bounds, context, Map.of());
    }

    /** {@inheritDoc} */
    @Override
    public void applyState(WindowState state) {
        windowId = state.id();
        context.putAll(state.context());
        WindowBounds b = state.bounds();
        if (b != null) {
            // Размер сообщения определяется текстом, восстанавливаем только положение.
            dialog.setLocation((int) Math.round(b.x()), (int) Math.round(b.y()));
            boundsRestored = true;
        }
    }
}
