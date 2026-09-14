package ru.cashprediction.fx.popup;

import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.fx.action.FxActions;
import ru.cashprediction.fx.session.FieldValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Быстрая правка суммы события прямо у ячейки таблицы: маленькое всплывающее окно с одним полем.
 *
 * <p>Открывается двойным щелчком по сумме строки регулярной операции. Enter создаёт корректировку «изменить сумму»
 * (через {@link FxActions#quickEditAmount}), Esc или щелчок мимо окна закрывает его без изменений.</p>
 *
 * <p>Окно восстанавливаемое ({@link WindowType#QUICK_EDIT_POPUP}): контекст — {@code ruleId} и {@code originalDate},
 * поле — {@code amount} (недопечатанная сумма сохраняется как набрана). Поэтому класс сам реализует
 * {@link StatefulWindow}, регистрируется в рекордере при показе и снимается с регистрации при скрытии.</p>
 *
 * <p>{@code autoHide} у {@link Popup} не используется: он закрывает окно при потере фокуса главным окном, а при
 * восстановлении сессии поверх быстрой правки может открыться модальный диалог — правка должна пережить это.
 * Щелчок мимо окна отслеживается фильтром событий сцены главного окна.</p>
 *
 * <p>Только FX Application Thread; {@link #windowId()} можно читать из любого потока.</p>
 */
// JavaFX: Popup → Swing: PopupFactory.getSharedInstance().getPopup(owner, panel, x, y) → Web: тот же механизм с <form>
public final class QuickEditPopup extends Popup implements StatefulWindow {

    /** Идентификатор поля суммы в словаре окна. */
    public static final String FIELD_AMOUNT = "amount";

    private final FxActions actions;
    private final SessionRecorder recorder;
    private final OccurrenceKey key;
    private final TextField amount = new TextField();
    private final Label problem = new Label();
    private final EventHandler<MouseEvent> clickOutside = e -> hide();
    private volatile String windowId = "";
    private Window ownerWindow;

    /**
     * Создаёт окно быстрой правки.
     *
     * @param actions  фасад команд (применение суммы)
     * @param recorder рекордер сессии
     * @param key      событие правила: правило и номинальная дата
     * @param title    название операции для подписи
     * @param current  текущая сумма события (начальное значение поля)
     * @param currency валюта плана для подписи
     */
    public QuickEditPopup(FxActions actions, SessionRecorder recorder, OccurrenceKey key, String title, Money current,
                          String currency) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.key = Objects.requireNonNull(key, "key");

        Label caption = new Label("«" + title + "», " + DateFormats.ru(key.originalDate()) + " - новая сумма, " + currency);
        caption.setStyle("-fx-font-weight: bold;");
        amount.setText(current == null ? "" : current.abs().format());
        amount.setPrefColumnCount(14);
        amount.setStyle("-fx-alignment: CENTER-RIGHT;");
        problem.setStyle("-fx-text-fill: #b3261e; -fx-font-size: 11px;");
        // Пустая строка ошибки не занимает места: иначе под полем суммы оставался пустой промежуток.
        problem.managedProperty().bind(problem.textProperty().isNotEmpty());
        problem.visibleProperty().bind(problem.textProperty().isNotEmpty());
        Label hint = new Label("Enter - сохранить корректировку, Esc - закрыть");
        hint.setStyle("-fx-text-fill: #57606a; -fx-font-size: 11px;");

        VBox box = new VBox(6, caption, amount, problem, hint);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #ffffff; -fx-border-color: #8c959f; -fx-border-radius: 4;"
                + " -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 2);");
        getContent().add(box);

        // Каждое изменение поля — повод записать снимок: недопечатанная сумма восстановится после сбоя.
        amount.textProperty().addListener((o, a, b) -> {
            problem.setText("");
            recorder.touch();
        });
        amount.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                commit();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                hide();
            }
        });
        setHideOnEscape(true);
        setOnHidden(e -> {
            if (ownerWindow != null && ownerWindow.getScene() != null) {
                ownerWindow.getScene().removeEventFilter(MouseEvent.MOUSE_PRESSED, clickOutside);
            }
            recorder.unregister(this);
        });
    }

    /**
     * Показывает окно в экранных координатах.
     *
     * @param owner   главное окно
     * @param screenX левый край окна на экране
     * @param screenY верхний край окна на экране
     * @param restore состояние из снимка (при восстановлении) или {@code null}
     * @param onShown кому сообщить о показе; {@code null} — окно само регистрируется в рекордере
     */
    public void showAt(Window owner, double screenX, double screenY, WindowState restore, Consumer<StatefulWindow> onShown) {
        String restoredId = restore == null ? "" : restore.id();
        // Самотест открывает окно «как пользователь» с условным идентификатором w0 — ему нужен настоящий номер.
        windowId = restoredId.isBlank() || "w0".equals(restoredId) ? recorder.nextWindowId() : restoredId;
        if (restore != null && !restore.field(FIELD_AMOUNT).isEmpty()) {
            applyState(restore);
        }
        this.ownerWindow = owner;
        setOnShown(e -> {
            Scene scene = owner.getScene();
            if (scene != null) {
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, clickOutside);
            }
            amount.requestFocus();
            amount.selectAll();
            if (onShown != null) {
                onShown.accept(this);
            } else {
                recorder.register(this);
            }
        });
        show(owner, screenX, screenY);
    }

    /**
     * Enter: проверяет сумму и создаёт корректировку события.
     *
     * @return {@code true}, если сумма принята и окно закрыто
     */
    public boolean commit() {
        var parsed = FxActions.parsePositiveAmount(amount.getText());
        if (parsed.isEmpty()) {
            problem.setText("Введите сумму больше нуля, например 95 000,00");
            return false;
        }
        hide();
        actions.quickEditAmount(key, parsed.get());
        return true;
    }

    /**
     * Вписывает сумму в поле так, как её набрал бы пользователь (команда самотеста).
     *
     * @param canonical сумма в канонической форме {@code 95000,00} или произвольный текст
     */
    public void typeAmount(String canonical) {
        amount.setText(FieldValues.displayMoney(canonical));
    }

    /**
     * Событие, сумма которого правится.
     *
     * @return правило и номинальная дата
     */
    public OccurrenceKey occurrenceKey() {
        return key;
    }

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

    /** {@inheritDoc} Быстрая правка не блокирует главное окно. */
    @Override
    public boolean modal() {
        return false;
    }

    /** {@inheritDoc} Владелец — всегда главное окно (правка живёт у ячейки таблицы). */
    @Override
    public String ownerId() {
        return WindowState.MAIN_OWNER;
    }

    /** {@inheritDoc} */
    @Override
    public WindowState captureState() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put(WindowType.CONTEXT_RULE_ID, key.ruleId().value());
        context.put(WindowType.CONTEXT_ORIGINAL_DATE, DateFormats.iso(key.originalDate()));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_AMOUNT, FieldValues.canonicalMoney(amount.getText()));
        WindowBounds bounds = null;
        if (isShowing() && Double.isFinite(getX()) && Double.isFinite(getY()) && getWidth() >= 0 && getHeight() >= 0) {
            bounds = new WindowBounds(getX(), getY(), getWidth(), getHeight());
        }
        return new WindowState(windowId.isBlank() ? "w0" : windowId, WindowType.QUICK_EDIT_POPUP, false,
                WindowState.MAIN_OWNER, bounds, context, fields);
    }

    /** {@inheritDoc} Переносит в поле сумму из снимка (недопечатанный текст — как был набран). */
    @Override
    public void applyState(WindowState state) {
        if (state != null) {
            typeAmount(state.field(FIELD_AMOUNT));
        }
    }
}
