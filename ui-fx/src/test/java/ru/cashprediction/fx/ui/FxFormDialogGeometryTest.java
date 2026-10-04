package ru.cashprediction.fx.ui;

import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.forms.ops.RuleEditorForm;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.token.DesignTokens;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет CSS настоящих форм на отдельно разрешённом стенде; обычный запуск не создаёт toolkit. */
@EnabledIfSystemProperty(named = "fx.formGeometryProof", matches = "true")
class FxFormDialogGeometryTest extends FxComputedFontTest {
    /** Все страницы мастера, CSV и редактор операций используют intrinsic-шапку и сохраняют логический глиф. */
    @Test void actualFormsMeasureSharedIconWithoutLegacyGlyphPadding() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents;
        check(new NewPlanWizardForm(), WindowType.NEW_PLAN_WIZARD, Map.of(), controller);
        check(new CsvExportForm(), WindowType.CSV_EXPORT, Map.of(), controller);
        check(new RuleEditorForm(), WindowType.RULE_EDITOR, Map.of("mode", "create", "kind", "INCOME"), controller);
    }); }

    private static void check(FormLogic logic, WindowType type, Map<String, String> values, AppController controller) {
        String id = "intrinsic-" + type.name();
        var context = new FormContext(id, "main", values, controller.state());
        var form = form(logic, type, id, values, controller);
        for (int page = 0; page < form.spec.pages().size(); page++) {
            var view = logic.evaluate(new FormState(page, logic.defaults(context)), context);
            form.update(view);
            var pane = form.dialog.getDialogPane();
            css(pane, form.spec.width(), pane.prefHeight(form.spec.width()));
            var heading = assertInstanceOf(FxFormHeading.class, pane.getHeader());
            assertEquals(Insets.EMPTY, form.glyph.getPadding());
            assertEquals(Insets.EMPTY, heading.getPadding());
            var graphic = assertInstanceOf(ImageView.class, form.glyph.getGraphic());
            assertEquals(DesignTokens.DIALOG_ICON_SIZE, graphic.getLayoutBounds().getHeight());
            assertEquals(form.spec.glyph(), form.glyph.getText());
            assertEquals(form.spec.glyph(), form.glyph.getAccessibleText());
            assertEquals(view.header(), form.header.getText());
            double contentHeight = List.of(form.glyph, form.header).stream()
                    .mapToDouble(node -> node.prefHeight(node.getWidth())).max().orElseThrow();
            assertEquals(Math.ceil(contentHeight) + 1, heading.getHeight(), 0.001);
            assertEquals(Region.USE_COMPUTED_SIZE, heading.getPrefHeight());
            assertEquals(8 * DesignTokens.SPACING, form.problem.getMinHeight());
        }
    }
}
