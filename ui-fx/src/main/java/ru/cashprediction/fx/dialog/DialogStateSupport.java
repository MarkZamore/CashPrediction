package ru.cashprediction.fx.dialog;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Screen;
import javafx.stage.Window;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.fx.session.FxFieldBinder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Общая «начинка» восстанавливаемого диалога: идентификатор окна, владелец, контекст, привязка полей,
 * снятие и применение состояния.
 *
 * <p>Нужна потому, что восстанавливаемые окна наследуются от разных классов JavaFX: собственные редакторы —
 * от {@link Dialog}, а подтверждения и ввод строки — от {@code Alert}, {@code TextInputDialog} и
 * {@code ChoiceDialog}. Множественного наследования нет, поэтому каждый такой класс держит экземпляр
 * этой поддержки и реализует {@link FxRestorableDialog} через неё.</p>
 *
 * <p>Идентификатор и владелец назначаются {@link FxDialogHost} непосредственно перед показом. Поля
 * {@code windowId}/{@code ownerId} volatile: интерфейс {@code StatefulWindow} разрешает читать их из
 * любого потока. Остальное — только FX Application Thread.</p>
 */
public final class DialogStateSupport {

    private final Dialog<?> dialog;
    private final WindowType type;
    private final FxFieldBinder binder;
    private final Map<String, String> context = new LinkedHashMap<>();
    private final Runnable onFieldsChanged;

    private volatile String windowId = "";
    private volatile String ownerId = WindowState.MAIN_OWNER;
    private Runnable touch = () -> { };
    private boolean active;
    private WindowBounds restoredBounds;

    /**
     * Создаёт поддержку для диалога.
     *
     * @param dialog          диалог
     * @param type            тип окна из словаря
     * @param onFieldsChanged перепроверка формы после изменения поля (вызывается только после {@link #activate()})
     */
    public DialogStateSupport(Dialog<?> dialog, WindowType type, Runnable onFieldsChanged) {
        this.dialog = Objects.requireNonNull(dialog, "dialog");
        this.type = Objects.requireNonNull(type, "type");
        this.onFieldsChanged = Objects.requireNonNull(onFieldsChanged, "onFieldsChanged");
        this.binder = new FxFieldBinder(this::fieldChanged);
    }

    /**
     * Привязка полей формы к идентификаторам словаря.
     *
     * @return связыватель полей
     */
    public FxFieldBinder binder() {
        return binder;
    }

    /**
     * Включает реакцию на изменения полей. Вызывается в конце конструктора диалога: пока форма строится,
     * начальные значения тоже «меняют» поля, но проверять недостроенную форму нельзя.
     */
    public void activate() {
        active = true;
        onFieldsChanged.run();
    }

    /**
     * Назначает идентификатор, владельца и способ сообщать рекордеру об изменениях.
     *
     * @param id      идентификатор окна ({@code w1}, ...)
     * @param owner   владелец ({@code main} или идентификатор окна)
     * @param toucher обычно {@code recorder::touch}
     */
    public void attach(String id, String owner, Runnable toucher) {
        this.windowId = Objects.requireNonNull(id, "id");
        this.ownerId = owner == null || owner.isBlank() ? WindowState.MAIN_OWNER : owner;
        this.touch = Objects.requireNonNullElse(toucher, () -> { });
    }

    /**
     * Записывает значение контекста (например, {@code mode=edit}, {@code page=1}).
     *
     * @param key   ключ контекста
     * @param value значение; {@code null} удаляет ключ
     */
    public void putContext(String key, String value) {
        if (value == null) {
            context.remove(key);
        } else {
            context.put(key, value);
        }
    }

    /**
     * Сообщает рекордеру об изменении, не связанном с полем (смена страницы мастера, перемещение окна).
     */
    public void touch() {
        touch.run();
    }

    /** @return идентификатор окна; пустая строка до назначения */
    public String windowId() {
        return windowId;
    }

    /** @return владелец окна */
    public String ownerId() {
        return ownerId;
    }

    /** @return тип окна из словаря */
    public WindowType type() {
        return type;
    }

    /** @return модальность по словарю: немодальны только калькулятор цели и быстрая правка */
    public boolean modal() {
        return type.defaultModal();
    }

    /**
     * Окно JavaFX, в котором показан диалог.
     *
     * @return окно или {@code null}, если диалог ещё не показан
     */
    public Window window() {
        Scene scene = dialog.getDialogPane().getScene();
        return scene == null ? null : scene.getWindow();
    }

    /**
     * Снимает состояние окна для снимка сессии.
     *
     * @return состояние: геометрия, контекст и поля в канонической форме
     */
    public WindowState capture() {
        return new WindowState(windowId.isBlank() ? "w0" : windowId, type, modal(), ownerId, bounds(),
                Collections.unmodifiableMap(new LinkedHashMap<>(context)), binder.capture());
    }

    /**
     * Применяет состояние из снимка до показа: значения полей и геометрию. Контекст (режим, страница)
     * диалог учитывает сам при создании.
     *
     * @param state состояние
     */
    public void apply(WindowState state) {
        if (state == null) {
            return;
        }
        binder.apply(state.fields());
        applyBounds(state.bounds());
    }

    /**
     * Повторно ставит окно в сохранённое положение после показа: {@code Dialog} при первом показе
     * может отцентрировать себя относительно владельца.
     */
    public void reapplyPositionAfterShow() {
        WindowBounds b = restoredBounds;
        if (b != null && onSomeScreen(b)) {
            dialog.setX(b.x());
            dialog.setY(b.y());
        }
    }

    // ------------------------------------------------------------------ служебное

    private void fieldChanged() {
        if (!active) {
            return;
        }
        onFieldsChanged.run();
        touch.run();
    }

    private WindowBounds bounds() {
        double x = dialog.getX();
        double y = dialog.getY();
        double w = dialog.getWidth();
        double h = dialog.getHeight();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h) || w < 0 || h < 0) {
            return null;
        }
        return new WindowBounds(x, y, w, h);
    }

    private void applyBounds(WindowBounds b) {
        if (b == null) {
            return;
        }
        restoredBounds = b;
        if (b.width() >= 200 && b.height() >= 120) {
            dialog.setWidth(b.width());
            dialog.setHeight(b.height());
        }
        // Монитор, на котором окно было при сбое, мог быть отключён: тогда пусть JavaFX поставит окно сам.
        if (onSomeScreen(b)) {
            dialog.setX(b.x());
            dialog.setY(b.y());
        } else {
            restoredBounds = null;
        }
    }

    private static boolean onSomeScreen(WindowBounds b) {
        return !Screen.getScreensForRectangle(b.x(), b.y(), Math.max(1, b.width()), Math.max(1, b.height())).isEmpty();
    }
}
