package ru.cashprediction.fx.ui;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.menu.MenuNode;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет штатный редактор spinner без второго debounce поверх общей задержки ядра. */
@EnabledIfSystemProperty(named = "fx.spinnerCommitProof", matches = "true")
class FxMenuSpinnerCommitTest {
    /** Action редактора сразу отправляет значение ядру, которое владеет общей задержкой применения. */
    @Test void editorActionReachesIntentWithoutClientDebounce() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        var committed = new CompletableFuture<Long>();
        try {
            var action = new FutureTask<Void>(() -> {
                UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(),
                        new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
                            if (method.getName().equals("spinnerCommit")) committed.complete((Long) args[1]);
                            return null;
                        });
                FxMenus menus = new FxMenus(intents, new FxClassUsageProbe());
                var model = new MenuNode.Spinner("whatIf.extra", ru.cashprediction.core.ui.command.CommandId.WHAT_IF_EXTRA,
                        "extra", 0, 100000, 1000, 0, "", 120, 600);
                // JavaFX: CustomMenuItem → Swing: JPanel с JSpinner → Web: input[type=number]
                var item = menus.item(model, InvokeSource.MENU);
                @SuppressWarnings("unchecked") Spinner<Long> spinner = (Spinner<Long>) item.getProperties().get("cp.control");
                spinner.getEditor().setText("5000");
                spinner.getEditor().fireEvent(new javafx.event.ActionEvent());
                assertEquals(5000L, spinner.getValue());
                assertTrue(committed.isDone(), "Core debounce must not be preceded by a client timer");
                assertEquals(5000L, committed.join());
                return null;
            });
            Platform.runLater(action);
            action.get(5, TimeUnit.SECONDS);
            assertEquals(5000L, committed.get(5, TimeUnit.SECONDS));
        } finally { Platform.exit(); }
    }
}
