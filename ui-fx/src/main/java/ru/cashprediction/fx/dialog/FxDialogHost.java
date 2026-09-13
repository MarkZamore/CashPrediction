package ru.cashprediction.fx.dialog;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowState;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Показывает диалоги без блокировки UI-потока и ведёт их учёт в рекордере сессии.
 *
 * <p><b>Почему не {@code showAndWait()}.</b> {@code showAndWait()} запускает вложенный цикл событий и
 * возвращает управление только после закрытия окна. Координатор восстановления открывает окна по цепочке:
 * следующее — после сообщения «предыдущее показано». С блокирующим показом второй вложенный модальный диалог
 * (например, «Корректировка» поверх «Регулярной операции») не открылся бы никогда. Поэтому здесь
 * {@code dialog.show()}, а результат приходит колбэком из события скрытия окна.</p>
 *
 * <p>Порядок для восстанавливаемого окна ({@link FxRestorableDialog}):</p>
 * <ol>
 *   <li>владелец и модальность (WINDOW_MODAL блокирует только цепочку владельцев — немодальный калькулятор
 *       цели остаётся доступным);</li>
 *   <li>идентификатор: из снимка при восстановлении, иначе {@code recorder.nextWindowId()};</li>
 *   <li>при восстановлении — {@code applyState} (поля и геометрия) <b>до</b> показа;</li>
 *   <li>показ; в событии «показано» — регистрация в рекордере (при восстановлении регистрирует координатор
 *       в колбэке {@code onShown});</li>
 *   <li>перемещение и изменение размера окна — {@code recorder.touch()};</li>
 *   <li>в событии «скрыто» — снятие с регистрации и передача результата.</li>
 * </ol>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: Dialog.show() + resultProperty → Swing: SwingDialogHost (JDialog DOCUMENT_MODAL через invokeLater) → Web: <dialog>.showModal() + Promise
public final class FxDialogHost {

    /** Путь к значку приложения в ресурсах модуля. */
    private static final String ICON_RESOURCE = "/ru/cashprediction/fx/icon.png";

    private static Image cachedIcon;

    private final Supplier<Window> mainWindow;
    private final Supplier<SessionRecorder> recorder;
    /** Открытые сейчас диалоги с назначенными идентификаторами — для поиска владельцев вложенных окон. */
    private final Map<String, Dialog<?>> openDialogs = new LinkedHashMap<>();

    /**
     * Создаёт хост.
     *
     * @param mainWindow главное окно (может ещё не быть показано — тогда модальные окна модальны для приложения)
     * @param recorder   рекордер сессии текущего сеанса
     */
    public FxDialogHost(Supplier<Window> mainWindow, Supplier<SessionRecorder> recorder) {
        this.mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    /**
     * Показывает диалог и возвращает результат колбэком после закрытия.
     *
     * @param dialog   диалог (ещё не показанный)
     * @param request  владелец и, при восстановлении, состояние из снимка
     * @param onResult получатель результата; пусто — диалог отменён или закрыт крестиком
     * @param <R>      тип результата
     */
    public <R> void open(Dialog<R> dialog, OpenRequest request, Consumer<Optional<R>> onResult) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onResult, "onResult");
        FxRestorableDialog restorable = dialog instanceof FxRestorableDialog r ? r : null;
        SessionRecorder rec = recorder.get();
        try {
            Window owner = windowFor(request.ownerId());
            boolean modal = restorable == null || restorable.modal();
            if (owner != null) {
                dialog.initOwner(owner);
            }
            dialog.initModality(!modal ? Modality.NONE : owner != null ? Modality.WINDOW_MODAL : Modality.APPLICATION_MODAL);
            applyIcon(dialog);

            String id;
            if (restorable != null) {
                id = request.isRestore() ? request.restore().id() : rec.nextWindowId();
                String effectiveOwner = owner != null && owner != mainWindow.get()
                        ? request.ownerId() : WindowState.MAIN_OWNER;
                restorable.stateSupport().attach(id, effectiveOwner, rec::touch);
                if (request.isRestore()) {
                    restorable.applyState(request.restore());
                }
            } else {
                id = null;
            }

            dialog.setOnShown(e -> {
                if (restorable != null) {
                    restorable.stateSupport().reapplyPositionAfterShow();
                }
                // Восстановленная высота могла быть меньше нужной (в снимке окно ещё без строки замечаний).
                if (dialog.getDialogPane() instanceof AppDialogPane pane) {
                    pane.growWindowToContent();
                }
                // Высокий диалог на ноутбуке с масштабом 200 % не должен уходить заголовком или кнопками за край экрана.
                fitToScreen(dialog);
                // У окон с раскрываемыми подробностями JavaFX пишет по-английски «Show Details»: переводим.
                Dialogs.localizeDetailsButton(dialog.getDialogPane());
                if (restorable == null) {
                    return;
                }
                openDialogs.put(id, dialog);
                // Перемещение и изменение размера входят в снимок: окно вернётся туда, где было.
                dialog.xProperty().addListener((o, a, b) -> rec.touch());
                dialog.yProperty().addListener((o, a, b) -> rec.touch());
                dialog.widthProperty().addListener((o, a, b) -> rec.touch());
                dialog.heightProperty().addListener((o, a, b) -> rec.touch());
                if (request.isRestore()) {
                    // Координатор сам регистрирует окно и открывает следующее. runLater — чтобы следующее окно
                    // открывалось уже после того, как это окно полностью показано и получило фокус.
                    Platform.runLater(() -> request.onShown().accept(restorable));
                } else {
                    rec.register(restorable);
                }
            });
            dialog.setOnHidden(e -> {
                if (restorable != null) {
                    openDialogs.remove(id);
                    rec.unregister(restorable);
                }
                onResult.accept(Optional.ofNullable(dialog.getResult()));
            });
            dialog.show();
        } catch (RuntimeException e) {
            if (request.isRestore()) {
                request.fail(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            } else {
                throw e;
            }
        }
    }

    /**
     * Показывает невосстанавливаемый диалог (сообщение, подтверждение выхода) поверх окна-владельца.
     *
     * @param dialog   диалог
     * @param ownerId  владелец ({@code main} или идентификатор окна)
     * @param onResult получатель результата
     * @param <R>      тип результата
     */
    public <R> void show(Dialog<R> dialog, String ownerId, Consumer<Optional<R>> onResult) {
        open(dialog, OpenRequest.ownedBy(ownerId), onResult);
    }

    /**
     * Находит окно JavaFX по идентификатору владельца.
     *
     * @param ownerId {@code main} или идентификатор открытого диалога
     * @return окно-владелец; главное окно, если диалог не найден; {@code null}, если и главное окно не показано
     */
    public Window windowFor(String ownerId) {
        if (ownerId != null && !WindowState.MAIN_OWNER.equals(ownerId)) {
            Dialog<?> ownerDialog = openDialogs.get(ownerId);
            if (ownerDialog != null) {
                Scene scene = ownerDialog.getDialogPane().getScene();
                if (scene != null && scene.getWindow() != null && scene.getWindow().isShowing()) {
                    return scene.getWindow();
                }
            }
        }
        Window main = mainWindow.get();
        return main != null && main.isShowing() ? main : null;
    }

    /**
     * Главное окно, если оно показано.
     *
     * @return окно или {@code null}
     */
    public Window mainWindow() {
        Window main = mainWindow.get();
        return main != null && main.isShowing() ? main : null;
    }

    /**
     * Значок приложения для окон диалогов (иначе в панели задач Windows у диалога была бы чашка кофе).
     *
     * @return изображение или {@code null}, если ресурс не найден
     */
    public static Image appIcon() {
        if (cachedIcon == null) {
            try (InputStream in = FxDialogHost.class.getResourceAsStream(ICON_RESOURCE)) {
                if (in != null) {
                    cachedIcon = new Image(in);
                }
            } catch (Exception e) {
                // Значок — украшение; без него диалог всё равно работает.
            }
        }
        return cachedIcon;
    }

    /**
     * Вписывает показанный диалог в видимую область экрана (без панели задач): уменьшает слишком большое окно
     * и сдвигает его так, чтобы заголовок и кнопки были доступны. Окно, которое и так помещается, не трогается —
     * иначе каждое открытие давало бы лишний {@code touch()} рекордеру.
     *
     * @param dialog показанный диалог
     */
    public static void fitToScreen(Dialog<?> dialog) {
        double x = dialog.getX();
        double y = dialog.getY();
        double w = dialog.getWidth();
        double h = dialog.getHeight();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) {
            return;
        }
        List<Screen> screens = Screen.getScreensForRectangle(x, y, w, h);
        Rectangle2D visual = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        if (w > visual.getWidth()) {
            w = visual.getWidth();
            dialog.setWidth(w);
        }
        if (h > visual.getHeight()) {
            h = visual.getHeight();
            dialog.setHeight(h);
        }
        double fittedX = Math.clamp(x, visual.getMinX(), visual.getMaxX() - w);
        double fittedY = Math.clamp(y, visual.getMinY(), visual.getMaxY() - h);
        if (fittedX != x) {
            dialog.setX(fittedX);
        }
        if (fittedY != y) {
            dialog.setY(fittedY);
        }
    }

    private static void applyIcon(Dialog<?> dialog) {
        Image icon = appIcon();
        Scene scene = dialog.getDialogPane().getScene();
        if (icon != null && scene != null && scene.getWindow() instanceof Stage stage && stage.getIcons().isEmpty()) {
            stage.getIcons().add(icon);
        }
    }
}
