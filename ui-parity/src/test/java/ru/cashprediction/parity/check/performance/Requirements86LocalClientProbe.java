package ru.cashprediction.parity.check.performance;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.selftest.UiDriver;
import ru.cashprediction.core.ui.view.table.TableModel;

/** Читает actual FX/Swing port и widget generation, не получает Expected/Contract. */
public final class Requirements86LocalClientProbe {
    private Requirements86LocalClientProbe() { }

    /**
     * Подключается к обычному уже созданному driver внутри factory collector.launch.
     * Reflection ограничен двумя конкретными production driver; другие реализации отвергаются.
     * MAIN передаёт owned lifecycle с закрытием port/process/session node, не чужие ресурсы.
     */
    public static Requirements86Collector.NativeDesktop attach(UiDriver driver, Map<String, Path> launchJars,
                                                               Rectangle reviewedViewport, AutoCloseable ownedLifecycle) throws Exception {
        ClientKind client = driver.client();
        String type = switch (client) {
            case FX -> "ru.cashprediction.fx.ui.FxUiDriver";
            case SWING -> "ru.cashprediction.swing.ui.SwingUiDriver";
            default -> throw new UnsupportedOperationException("local-probe-fx-swing-only");
        };
        if (!driver.getClass().getName().equals(type)) throw new IllegalArgumentException("foreign-driver");
        Object port = field(driver, "port");
        AppController controller = client == ClientKind.SWING ? (AppController) field(driver, "controller")
                : (AppController) field(port, "intents");
        return new Requirements86Collector.NativeDesktop(driver,
                () -> ui(client, () -> {
                    Object table = field(field(port, client == ClientKind.FX ? "main" : "frame"), "table");
                    Object model = client == ClientKind.FX ? field(table, "model")
                            : field(table, "adapter").getClass().getMethod("source").invoke(field(table, "adapter"));
                    return ((TableModel) model).revision();
                }),
                reviewedViewport, launchJars,
                () -> ui(client, () -> controller.state().document().file()),
                () -> ui(client, () -> {
                    Object main = field(port, client == ClientKind.FX ? "main" : "frame");
                    Object toolbar = field(main, "toolbar");
                    Object filter = client == ClientKind.FX ? ((Map<?, ?>) field(toolbar, "widgets")).get("tb.filter")
                            : toolbar.getClass().getMethod("filter").invoke(toolbar);
                    return (String) filter.getClass().getMethod("getText").invoke(filter);
                }), ownedLifecycle);
    }

    private static Object field(Object instance, String name) throws Exception {
        var member = instance.getClass().getDeclaredField(name); member.setAccessible(true); return member.get(instance);
    }

    /** Читает metadata только в UI-потоке; никакой старт FX или GUI здесь не выполняется. */
    private static <T> T ui(ClientKind client, Callable<T> read) {
        try {
            boolean current = client == ClientKind.SWING ? SwingUtilities.isEventDispatchThread()
                    : (boolean) Class.forName("javafx.application.Platform").getMethod("isFxApplicationThread").invoke(null);
            if (current) return read.call();
            var task = new FutureTask<T>(read);
            if (client == ClientKind.SWING) SwingUtilities.invokeLater(task);
            else Class.forName("javafx.application.Platform").getMethod("runLater", Runnable.class).invoke(null, task);
            try { return task.get(2, TimeUnit.SECONDS); }
            catch (Exception failure) { task.cancel(false); throw failure; }
        } catch (Exception failure) { throw new IllegalStateException("actual-client-state-unavailable", failure); }
    }
}
