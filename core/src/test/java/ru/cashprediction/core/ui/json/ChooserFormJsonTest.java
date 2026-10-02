package ru.cashprediction.core.ui.json;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.dump.UiDump;
import static org.junit.jupiter.api.Assertions.*;

/** Исходный запрос серверного выбора сохраняется в протоколе без сдвига общих идентификаторов. */
final class ChooserFormJsonTest {
    @TempDir Path root;

    /** Подтверждение, встроенное в FX chooser, не сдвигает общие номера в Swing-аналоге. */
    @Test void chooserReplacementConfirmationDoesNotConsumeCommonDialogId() throws Exception {
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory"));
        var port = new FakeUiPort(ClientProfile.swing());
        var controller = new AppController(port, environment);
        try {
            controller.start();
            port.forms().getLast().session().closeRequested();
            var existing = root.resolve("existing.md");
            java.nio.file.Files.writeString(existing, "fixture");
            port.chooseFileResult(existing);
            var selected = new java.util.concurrent.atomic.AtomicReference<java.util.Optional<Path>>();
            controller.choosers().chooseFile(new FileChooserSpec(FileChooserSpec.Purpose.SAVE_PLAN_AS,
                    FileChooserSpec.Mode.SAVE, "Save", "Plans", List.of("md"), root, "existing.md"), selected::set);
            port.pump();
            var confirmation = port.pendingAlerts().getLast();
            assertEquals("replaceFile", confirmation.spec().purpose());
            assertEquals("chooserConfirm1", controller.state().windows().windows().getLast().windowId());
            confirmation.press("replace");
            assertEquals(java.util.Optional.of(existing), selected.get());
            controller.showAlert(AlertCatalog.info("recordingOff"), ignored -> { });
            assertEquals("w2", controller.state().windows().windows().getLast().windowId());
        } finally { port.scheduler().shutdown(); }
    }

    /** Закрытый браузер не расходует номер диалога; старый конструктор не меняет JSON фикстуры. */
    @Test void transientChooserPreservesCommonSequenceAndCarriesExactRequest() {
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory"));
        var port = new FakeUiPort(ClientProfile.web());
        var controller = new AppController(port, environment);
        try {
            controller.start();
            port.forms().getLast().session().closeRequested();
            var request = new FileChooserSpec(FileChooserSpec.Purpose.OPEN_PLAN, FileChooserSpec.Mode.OPEN,
                    "Open", "Plans", List.of("md"), environment.cashMemory(), "");
            controller.choosers().chooseFile(request, ignored -> { });
            var form = port.forms().getLast();
            assertEquals("chooser1", form.session().windowId());
            assertFalse(form.spec().restorable());
            var metadata = new UiDump.ChooserRequest("file", "OPEN", "Open", "Plans", environment.cashMemory().toString(), "");
            var effect = new WebEffect.FormOpen(form.session().windowId(), form.placement().ownerId(), true,
                    form.placement(), form.spec(), form.initial(), metadata);
            var wire = (Map<?, ?>) JsonParser.parse(UiJson.write(effect));
            var window = (Map<?, ?>) wire.get("window");
            assertEquals(UiJson.toTree(metadata), window.get("chooserRequest"));
            var old = new WebEffect.FormOpen(effect.windowId(), effect.ownerId(), effect.modal(), effect.placement(), effect.spec(), effect.view());
            var oldWire = (Map<?, ?>) JsonParser.parse(UiJson.write(old));
            assertFalse(((Map<?, ?>) oldWire.get("window")).containsKey("chooserRequest"));
            form.session().closeRequested();
            controller.showAlert(AlertCatalog.info("recordingOff"), ignored -> { });
            assertEquals("w2", controller.state().windows().windows().getLast().windowId());
        } finally { port.scheduler().shutdown(); }
    }
}
