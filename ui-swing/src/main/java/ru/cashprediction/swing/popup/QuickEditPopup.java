package ru.cashprediction.swing.popup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.swing.action.SwingActions;
import ru.cashprediction.swing.view.Palette;

/**
 * Быстрая правка суммы события у ячейки таблицы (двойной щелчок по сумме) — Swing-аналог JavaFX {@code Popup}
 * ({@code QuickEditPopup}).
 *
 * <p>Окно создаётся фабрикой {@code PopupFactory.getSharedInstance().getPopup(owner, panel, x, y)}: Swing сам
 * выбирает лёгкое всплывающее окно внутри главного окна или тяжёлое окно, если панель не помещается. В панели одно
 * поле суммы: Enter применяет корректировку «изменить сумму» ({@code ChangeAmount}), Esc закрывает без изменений;
 * щелчок в другом месте главного окна тоже закрывает панель (аналог {@code autoHide}).</p>
 *
 * <p><b>Сессия.</b> Панель — восстанавливаемое окно {@link WindowType#QUICK_EDIT_POPUP}: контекст {@code ruleId} и
 * {@code originalDate}, поле {@code amount} (корректная сумма — в виде {@code formatPlain}, недописанная — как
 * набрана). Каждое изменение текста вызывает {@code touch()} рекордера. Регистрирует окно тот, кто его открыл;
 * при закрытии панель сама сообщает об этом через {@code onClosed} (главное окно снимает её с регистрации).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Popup → Swing: PopupFactory.getSharedInstance().getPopup(owner, panel, x, y) → Web: тот же механизм с <form>
public final class QuickEditPopup implements StatefulWindow {

    /** Идентификатор поля суммы в снимке. */
    public static final String FIELD_AMOUNT = "amount";

    private final JComponent owner;
    private final String windowId;
    private final String ownerId;
    private final Map<String, String> context;
    private final Consumer<Money> onApply;
    private final Runnable onTouch;
    private final Consumer<QuickEditPopup> onClosed;
    private final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final JTextField amountField = new JTextField(12);
    private final JLabel hint = new JLabel("Enter - применить, Esc - закрыть");

    private Popup popup;
    private boolean closed;
    /** Текст меняется программно (открытие или восстановление): это не ввод пользователя. */
    private boolean quiet;

    /**
     * Создаёт быструю правку.
     *
     * @param owner    компонент-владелец (таблица прогноза)
     * @param state    состояние окна: идентификатор, владелец, контекст события
     * @param row      событие правила, сумму которого правят
     * @param currency валюта плана
     * @param onApply  применить новую сумму (корректировка через фасад команд)
     * @param onTouch  сообщить рекордеру об изменении
     * @param onClosed панель закрыта (снять с регистрации)
     */
    public QuickEditPopup(JComponent owner, WindowState state, ForecastRow row, String currency, Consumer<Money> onApply,
                          Runnable onTouch, Consumer<QuickEditPopup> onClosed) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.windowId = state.id();
        this.ownerId = state.ownerId();
        this.context = new LinkedHashMap<>(state.context());
        this.onApply = Objects.requireNonNull(onApply, "onApply");
        this.onTouch = Objects.requireNonNull(onTouch, "onTouch");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");

        JLabel title = new JLabel("«" + row.title() + "», " + DateFormats.ru(row.originalDate()) + " (" + currency + ")");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        hint.setForeground(Palette.PAST);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        panel.setBackground(Palette.POPUP_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.BALANCE_LINE),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        panel.add(title, BorderLayout.NORTH);
        panel.add(amountField, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        amountField.setToolTipText("Новая сумма только этого события; правило не меняется (корректировка «изменить сумму»)");

        String saved = state.field(FIELD_AMOUNT);
        quiet = true;
        amountField.setText(saved.isBlank() ? row.amount().abs().formatPlain() : saved);
        quiet = false;
        amountField.selectAll();

        amountField.getDocument().addDocumentListener(new DocumentListener() {
            /** Символы вставлены в поле: обрабатывается как любое изменение текста. */
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            /** Символы удалены из поля: обрабатывается как любое изменение текста. */
            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            /** Изменились атрибуты текста (у простых полей не приходит): обрабатывается единообразно. */
            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        amountField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "quickApply");
        amountField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "quickCancel");
        amountField.getActionMap().put("quickApply", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                pressOk();
            }
        });
        amountField.getActionMap().put("quickCancel", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });
        amountField.addFocusListener(new FocusAdapter() {
            /** Поле потеряло фокус: ввод завершается. */
            @Override
            public void focusLost(FocusEvent e) {
                // Закрываемся только если пользователь щёлкнул в другое место того же окна. Потеря фокуса при
                // переключении на другое приложение (временная или без «противоположного» компонента) не закрывает
                // панель: введённое не должно пропадать, и снимок сессии должен её сохранить.
                Component opposite = e.getOppositeComponent();
                if (!e.isTemporary() && opposite != null
                        && SwingUtilities.getWindowAncestor(opposite) == SwingUtilities.getWindowAncestor(owner)
                        && !SwingUtilities.isDescendingFrom(opposite, panel)) {
                    cancel();
                }
            }
        });
    }

    private void changed() {
        if (quiet) {
            return;
        }
        String error = validationError();
        hint.setText(error == null ? "Enter - применить, Esc - закрыть" : error);
        hint.setForeground(error == null ? Palette.PAST : Palette.EXPENSE);
        onTouch.run();
    }

    private String validationError() {
        return SwingActions.parsePositiveAmount(amountField.getText()).isPresent() ? null : "Введите сумму больше нуля";
    }

    /**
     * Показывает панель в точке экрана (обычно — левый верхний угол ячейки суммы).
     *
     * @param screenLocation точка на экране
     */
    public void showAt(Point screenLocation) {
        if (closed) {
            return;
        }
        panel.setPreferredSize(null);
        popup = PopupFactory.getSharedInstance().getPopup(owner, panel, screenLocation.x, screenLocation.y);
        popup.show();
        amountField.requestFocusInWindow();
    }

    /**
     * Показана ли панель.
     *
     * @return {@code true}, пока панель не закрыта
     */
    public boolean isShowing() {
        return popup != null && !closed;
    }

    /**
     * Текущий текст суммы.
     *
     * @return текст как набран
     */
    public String amountText() {
        return amountField.getText();
    }

    /**
     * Заполняет поле суммы как пользовательский ввод (слушатели срабатывают). Средство самотеста.
     *
     * @param fields {@code amount} → сумма
     * @return неизвестные идентификаторы полей
     */
    public List<String> fill(Map<String, String> fields) {
        List<String> unknown = new ArrayList<>();
        fields.forEach((id, value) -> {
            if (FIELD_AMOUNT.equals(id)) {
                amountField.setText(value == null ? "" : value);
            } else {
                unknown.add(id);
            }
        });
        return unknown;
    }

    /**
     * Enter: применяет сумму и закрывает панель.
     *
     * @return {@code null}, если сумма применена; иначе причина по-русски (панель остаётся открытой)
     */
    public String pressOk() {
        if (closed) {
            return "окно уже закрыто";
        }
        Optional<Money> amount = SwingActions.parsePositiveAmount(amountField.getText());
        if (amount.isEmpty()) {
            hint.setText("Введите сумму больше нуля");
            hint.setForeground(Palette.EXPENSE);
            return "некорректная сумма «" + amountField.getText() + "»";
        }
        close();
        onApply.accept(amount.get());
        return null;
    }

    /** Esc: закрывает панель без изменений. */
    public void cancel() {
        close();
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (popup != null) {
            popup.hide();
        }
        onClosed.accept(this);
    }

    // ------------------------------------------------------------------ StatefulWindow

    /** {@inheritDoc} */
    @Override
    public String windowId() {
        return windowId;
    }

    /** {@inheritDoc} */
    @Override
    public WindowType windowType() {
        return WindowType.QUICK_EDIT_POPUP;
    }

    /** {@inheritDoc} */
    @Override
    public boolean modal() {
        return WindowType.QUICK_EDIT_POPUP.defaultModal();
    }

    /** {@inheritDoc} */
    @Override
    public String ownerId() {
        return ownerId;
    }

    /** {@inheritDoc} */
    @Override
    public WindowState captureState() {
        // Корректная сумма — в канонической форме «95000,00», недописанная — как набрана.
        String text = amountField.getText();
        String canonical = SwingActions.parsePositiveAmount(text).map(Money::formatPlain).orElse(text);
        return new WindowState(windowId, WindowType.QUICK_EDIT_POPUP, modal(), ownerId, null, context,
                Map.of(FIELD_AMOUNT, canonical));
    }

    /** {@inheritDoc} */
    @Override
    public void applyState(WindowState state) {
        quiet = true;
        try {
            String value = state.field(FIELD_AMOUNT);
            if (!value.isBlank()) {
                amountField.setText(value);
            }
        } finally {
            quiet = false;
        }
    }
}
