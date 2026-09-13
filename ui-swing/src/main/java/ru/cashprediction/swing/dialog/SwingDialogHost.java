package ru.cashprediction.swing.dialog;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Показывает диалоги и сообщения по неблокирующей модели и связывает их с рекордером сессии
 * (Swing-реализация общего фасада {@code DialogHost} из раздела 2 плана).
 *
 * <p><b>Почему показ через {@code invokeLater}.</b> {@code setVisible(true)} у модального {@code JDialog}
 * не возвращается, пока окно открыто (крутится вложенный цикл событий). Если вызвать его прямо из обработчика,
 * текущее событие «зависнет» до закрытия диалога. Показ из свежего {@code invokeLater} гарантирует, что код,
 * открывший диалог, завершится сразу, а следующий диалог цепочки восстановления откроется уже внутри
 * вложенного цикла предыдущего.</p>
 *
 * <p><b>Регистрация.</b> Окно регистрируется в рекордере в {@code windowOpened} (реально показано) и снимается
 * с регистрации в {@code windowClosed}. Колбэк {@code onShown} тоже вызывается из {@code windowOpened} — так
 * {@code RestoreCoordinator} узнаёт, что можно открывать следующее окно. Перемещение и изменение размера
 * окна вызывают {@code touch()}.</p>
 *
 * <p>Хост также помнит открытые окна, чтобы найти окно-владельца по идентификатору ({@code w2}) и верхнее
 * модальное окно — владельца для вложенных диалогов и сообщений.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class SwingDialogHost {

    private final Supplier<Window> mainWindow;
    private final Supplier<SessionRecorder> recorder;
    /** Открытые (показанные и ещё не закрытые) окна в порядке открытия. */
    private final List<SwingHostedWindow> openWindows = new ArrayList<>();

    /**
     * Создаёт хост.
     *
     * @param mainWindow главное окно (может вернуть {@code null}, пока его нет)
     * @param recorder   рекордер сессии (может вернуть {@code null}, пока его нет)
     */
    public SwingDialogHost(Supplier<Window> mainWindow, Supplier<SessionRecorder> recorder) {
        this.mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    /**
     * Показывает окно без уведомления о показе.
     *
     * @param hosted окно
     */
    public void show(SwingHostedWindow hosted) {
        show(hosted, window -> { }, reason -> { });
    }

    /**
     * Показывает окно.
     *
     * @param hosted   окно (диалог или сообщение), полностью настроенное и, при восстановлении, с применённым состоянием
     * @param onShown  вызывается из {@code windowOpened}, когда окно реально показано
     * @param onFailed вызывается, если окно показать не удалось (текст причины)
     */
    public void show(SwingHostedWindow hosted, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        Objects.requireNonNull(hosted, "hosted");
        Window window = hosted.window();
        SessionRecorder rec = recorder.get();
        if (hosted.isRestorable() && hosted.windowId() == null && rec != null) {
            hosted.assignWindowId(rec.nextWindowId());
        }
        ComponentAdapter geometry = new ComponentAdapter() {
            /** Окно перемещено: положение попадёт в следующий снимок сессии. */
            @Override
            public void componentMoved(ComponentEvent e) {
                touch(rec, hosted);
            }

            /** Размер окна изменён: размер попадёт в следующий снимок сессии. */
            @Override
            public void componentResized(ComponentEvent e) {
                touch(rec, hosted);
            }
        };
        window.addWindowListener(new WindowAdapter() {
            private boolean opened;

            /** Окно показано на экране: момент регистрации окна и уведомления об открытии. */
            @Override
            public void windowOpened(WindowEvent e) {
                if (opened) {
                    return;
                }
                opened = true;
                openWindows.add(hosted);
                if (hosted.isRestorable() && rec != null && hosted.windowId() != null) {
                    rec.register(hosted);
                }
                window.addComponentListener(geometry);
                onShown.accept(hosted);
            }

            /** Окно закрыто и освобождено: снимаем регистрацию и уведомляем владельца. */
            @Override
            public void windowClosed(WindowEvent e) {
                openWindows.remove(hosted);
                window.removeComponentListener(geometry);
                window.removeWindowListener(this);
                if (rec != null && hosted.isRestorable()) {
                    rec.unregister(hosted);
                }
            }
        });
        SwingUtilities.invokeLater(() -> {
            try {
                hosted.prepareForShow();
            } catch (RuntimeException e) {
                window.dispose();
                onFailed.accept(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
                return;
            }
            // Для модального окна вызов не вернётся до его закрытия — это нормально: мы в собственном событии.
            window.setVisible(true);
        });
    }

    private static void touch(SessionRecorder rec, SwingHostedWindow hosted) {
        if (rec != null && hosted.isRestorable() && hosted.windowId() != null) {
            rec.touch();
        }
    }

    // ------------------------------------------------------------------ владельцы

    /**
     * Окно-владелец по идентификатору из снимка.
     *
     * @param ownerId {@code main} или идентификатор открытого окна {@code wN}
     * @return окно; главное окно, если идентификатор неизвестен (может быть {@code null} до создания главного окна)
     */
    public Window ownerWindow(String ownerId) {
        if (ownerId != null && !WindowState.MAIN_OWNER.equals(ownerId)) {
            for (SwingHostedWindow hosted : openWindows) {
                if (ownerId.equals(hosted.windowId())) {
                    return hosted.window();
                }
            }
        }
        return mainWindow.get();
    }

    /**
     * Верхнее открытое модальное окно — владелец для нового вложенного диалога или сообщения.
     *
     * <p>Владельцем нельзя брать главное окно, пока открыт модальный диалог: новое окно оказалось бы
     * «соседом» этого диалога, а не его ребёнком, и могло бы оказаться под ним.</p>
     *
     * @return верхнее модальное окно или главное окно
     */
    public Window activeOwner() {
        return topModal().map(SwingHostedWindow::window).orElseGet(mainWindow);
    }

    /**
     * Идентификатор владельца для снимка, согласованный с {@link #activeOwner()}.
     *
     * @return {@code wN} верхнего восстанавливаемого модального окна или {@code main}
     */
    public String activeOwnerId() {
        return topModal().map(SwingHostedWindow::windowId).orElse(WindowState.MAIN_OWNER);
    }

    private Optional<SwingHostedWindow> topModal() {
        for (int i = openWindows.size() - 1; i >= 0; i--) {
            SwingHostedWindow hosted = openWindows.get(i);
            if (hosted.modal() && hosted.window().isShowing()) {
                return Optional.of(hosted);
            }
        }
        return Optional.empty();
    }

    /**
     * Открытое окно заданного типа (например, единственный калькулятор цели).
     *
     * @param type тип окна
     * @return окно или пусто
     */
    public Optional<SwingHostedWindow> findOpen(WindowType type) {
        return openWindows.stream().filter(w -> w.windowType() == type).findFirst();
    }

    /**
     * Открытые окна в порядке открытия.
     *
     * @return неизменяемый список
     */
    public List<SwingHostedWindow> openWindows() {
        return List.copyOf(openWindows);
    }

    /**
     * Видна ли заметная часть прямоугольника хотя бы на одном экране (иначе восстановленное окно центрируется).
     *
     * @param bounds границы окна
     * @return {@code true}, если окно видно хотя бы на 80×40 пикселей
     */
    public static boolean isOnScreen(Rectangle bounds) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(bounds);
            if (visible.width >= 80 && visible.height >= 40) {
                return true;
            }
        }
        return false;
    }
}
