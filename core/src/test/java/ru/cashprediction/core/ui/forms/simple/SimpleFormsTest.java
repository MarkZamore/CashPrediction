package ru.cashprediction.core.ui.forms.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;

/** Проверяет общие простые формы без участия клиента. */
class SimpleFormsTest {
    private FormContext context() { return new FormContext("w1", "main", Map.of(), FakeStates.empty(ClientProfile.swing(), Path.of("CashMemory"))); }
    @Test void customCurrencyRejectsPipeAndAcceptsCode() {
        var form = TextInputForms.customCurrency();
        assertTrue(form.evaluate(new FormState(0, Map.of("value", "A|B")), context()).problem().severity().name().equals("ERROR"));
        assertInstanceOf(FormOutcome.Close.class, form.onButton("apply", new FormState(0, Map.of("value", "CNY")), context()));
    }
    @Test void currencyReturnsCustomMarker() {
        var form = ChoiceForms.currency();
        FormOutcome.Close outcome = assertInstanceOf(FormOutcome.Close.class, form.onButton("choose", new FormState(0, Map.of("value", ChoiceForms.CUSTOM)), context()));
        assertEquals(ChoiceForms.CUSTOM, outcome.result());
    }
    @Test void csvUsesRussianExcelDefaults() {
        var form = new CsvExportForm();
        assertEquals(";", form.defaults(context()).get("separator"));
        assertEquals("true", form.defaults(context()).get("bom"));
    }
    @Test void fileBrowserAddsExtensionOnlyOnSave() throws Exception {
        Path folder = Files.createTempDirectory("cashprediction-simple-form-");
        FileBrowserForm form = new FileBrowserForm(new FileChooserSpec(FileChooserSpec.Purpose.EXPORT_CSV, FileChooserSpec.Mode.SAVE, "Экспорт", "CSV", java.util.List.of("csv"), folder, "план"), new FolderListing(folder));
        FormContext context = context();
        FormOutcome.Close outcome = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, Map.of("root", folder.getRoot().toString(), "path", folder.toString(), "value", "", "name", "план")), context));
        assertEquals(folder.resolve("план.csv"), outcome.result());
    }
}
