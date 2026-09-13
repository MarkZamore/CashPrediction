package ru.cashprediction.fx.dialog;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Панель диалогов CashPrediction: заголовок с пиктограммой, форма, строка замечаний проверки,
 * раскрываемые подробности и кнопки, которые блокируются, пока форма невалидна.
 *
 * <p>Почему наследование, а не настройка обычной {@link DialogPane}: кнопки панель создаёт сама в
 * защищённом методе {@link #createButton(ButtonType)}, и только в нём можно один раз и для всех диалогов
 * привязать доступность «Сохранить»/«Готово» к признаку валидности формы. Так ни один редактор не может
 * «забыть» отключить OK, а пользователь не может сохранить сумму «12,3,4».</p>
 *
 * <p>Используется только в FX Application Thread.</p>
 */
// JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки, lookupButton) → Web: <dialog><form method="dialog">
public class AppDialogPane extends DialogPane {

    /** Стиль текста ошибки проверки. */
    private static final String ERROR_STYLE = "-fx-text-fill: #b3261e;";
    /** Стиль текста предупреждения проверки. */
    private static final String WARNING_STYLE = "-fx-text-fill: #8a5300;";
    /** Стиль подсказки без замечаний. */
    private static final String HINT_STYLE = "-fx-text-fill: #555555;";

    private final BooleanProperty formValid = new SimpleBooleanProperty(this, "formValid", true);
    private final BooleanProperty nextAllowed = new SimpleBooleanProperty(this, "nextAllowed", true);
    private final BooleanProperty backAllowed = new SimpleBooleanProperty(this, "backAllowed", true);
    private final Label problemLabel = new Label();

    /**
     * Создаёт панель.
     *
     * @param headerText текст заголовка (крупная строка над формой)
     * @param glyph      символ-пиктограмма заголовка, например «₽» или «✎»; {@code null} — без пиктограммы
     */
    public AppDialogPane(String headerText, String glyph) {
        setHeaderText(headerText);
        if (glyph != null) {
            Label icon = new Label(glyph);
            icon.setStyle("-fx-font-size: 26px; -fx-text-fill: #2e7d32; -fx-padding: 0 4 0 4;");
            setGraphic(icon);
        }
        problemLabel.setWrapText(true);
        problemLabel.setMaxWidth(Double.MAX_VALUE);
        // Пустая строка замечаний не должна занимать место под формой.
        problemLabel.managedProperty().bind(problemLabel.textProperty().isNotEmpty());
        problemLabel.visibleProperty().bind(problemLabel.textProperty().isNotEmpty());
        // Появившаяся строка замечаний не должна выталкивать кнопки за нижний край окна:
        // размер окна диалога JavaFX сам не пересчитывает, поэтому после раскладки окно подрастает.
        // Раньше рост запускался через Platform.runLater сразу после смены текста, но в этот момент раскладка
        // ещё не пересчитана: prefHeight возвращал старую высоту, и кнопки калькулятора цели уходили за край.
        // Теперь проверка форм только ставит флаг, а окно подрастает после ближайшего прохода раскладки сцены.
        problemLabel.textProperty().addListener((o, was, is) -> growRequested = true);
        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removePostLayoutPulseListener(afterLayout);
            }
            if (newScene != null) {
                newScene.addPostLayoutPulseListener(afterLayout);
            }
        });
        setPrefWidth(560);
    }

    /** Запрошен ли рост окна по содержимому после ближайшей раскладки. */
    private boolean growRequested;

    /**
     * Слушатель «раскладка сцены выполнена»: растит окно, только если об этом просили (изменилась форма).
     * Без флага окно подрастало бы после каждого прохода и не давало пользователю уменьшить его вручную.
     */
    private final Runnable afterLayout = () -> {
        if (growRequested) {
            growRequested = false;
            growWindowToContent();
        }
    };

    /**
     * Увеличивает высоту показанного окна, если содержимое панели в него не помещается (например, после
     * появления строки замечаний или после восстановления окна с сохранённой, меньшей высотой).
     * Окно только растёт: уменьшать размер, который пользователь выбрал сам, нельзя.
     */
    public void growWindowToContent() {
        Scene scene = getScene();
        Window window = scene == null ? null : scene.getWindow();
        if (window == null || !window.isShowing()) {
            return;
        }
        // Сравнивать нужно с высотой сцены, а не с getHeight() панели: панель раскладывается по своей
        // предпочтительной высоте даже тогда, когда она больше окна, и лишнее просто обрезается сценой
        // (проверено самотестом: pane=343, pref=343 при высоте содержимого окна около 320).
        double needed = prefHeight(scene.getWidth());
        double have = scene.getHeight();
        if (needed > have + 0.5) {
            window.setHeight(window.getHeight() + (needed - have));
        }
    }

    /**
     * Устанавливает форму диалога; под ней располагается строка замечаний проверки.
     *
     * @param form содержимое (обычно {@link FormGrid})
     */
    public void setForm(Node form) {
        VBox box = new VBox(10, form, problemLabel);
        VBox.setVgrow(form, Priority.ALWAYS);
        setContent(box);
    }

    /**
     * Показывает результат проверки формы и включает или блокирует подтверждающие кнопки.
     *
     * <p>Ошибки блокируют «Сохранить»/«Готово»; предупреждения только показываются (например,
     * «дата вне горизонта плана» — это допустимо, но пользователь должен знать).</p>
     *
     * @param errors   ошибки; пустой список — форма валидна
     * @param warnings предупреждения
     */
    public void showProblems(List<String> errors, List<String> warnings) {
        // Проверка идёт при каждом изменении поля; вместе с замечаниями могли измениться и строки результата
        // (калькулятор цели), поэтому после раскладки окно при необходимости подрастёт.
        growRequested = true;
        formValid.set(errors.isEmpty());
        if (!errors.isEmpty()) {
            problemLabel.setStyle(ERROR_STYLE);
            problemLabel.setText("✖ " + String.join("\n✖ ", errors));
        } else if (!warnings.isEmpty()) {
            problemLabel.setStyle(WARNING_STYLE);
            problemLabel.setText("⚠ " + String.join("\n⚠ ", warnings));
        } else {
            problemLabel.setText("");
        }
    }

    /**
     * Показывает спокойную подсказку в строке замечаний (когда ошибок и предупреждений нет).
     *
     * @param hint текст подсказки; пустая строка скрывает строку
     */
    public void showHint(String hint) {
        formValid.set(true);
        problemLabel.setStyle(HINT_STYLE);
        problemLabel.setText(hint == null ? "" : hint);
    }

    /**
     * Кладёт длинный текст в раскрываемую область «Подробности» (стек ошибки, диагностика, справка).
     *
     * @param text текст; показывается в TextArea только для чтения
     */
    public void setDetails(String text) {
        setExpandableContent(detailsArea(text));
    }

    /**
     * Создаёт область для длинного текста: только чтение, моноширинный шрифт, растягивается.
     *
     * @param text текст
     * @return область текста
     */
    public static TextArea detailsArea(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(18);
        area.setPrefColumnCount(80);
        area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");
        area.setMaxWidth(Double.MAX_VALUE);
        area.setMaxHeight(Double.MAX_VALUE);
        return area;
    }

    /**
     * Признак «форма валидна»: к нему привязаны кнопки с ролями OK_DONE, FINISH, YES и APPLY.
     *
     * @return свойство
     */
    public BooleanProperty formValidProperty() {
        return formValid;
    }

    /**
     * Признак «можно перейти на следующую страницу»: к нему привязана кнопка с ролью NEXT_FORWARD.
     *
     * @return свойство
     */
    public BooleanProperty nextAllowedProperty() {
        return nextAllowed;
    }

    /**
     * Признак «можно вернуться на предыдущую страницу»: к нему привязана кнопка с ролью BACK_PREVIOUS.
     *
     * @return свойство
     */
    public BooleanProperty backAllowedProperty() {
        return backAllowed;
    }

    /**
     * Создаёт кнопку и привязывает её доступность к валидности формы или странице мастера.
     *
     * <p>Вызывается самой {@link DialogPane} при изменении списка типов кнопок. К этому моменту поля
     * подкласса уже инициализированы: список кнопок в конструкторе {@code DialogPane} пуст.</p>
     *
     * @param buttonType тип кнопки
     * @return узел кнопки
     */
    @Override
    protected Node createButton(ButtonType buttonType) {
        Node node = super.createButton(buttonType);
        if (node instanceof Button button) {
            // ButtonBar выравнивает кнопки по ширине и при нехватке места обрезал подпись («Показать с доп. эконо…»):
            // минимальная ширина по тексту заставляет диалог расшириться, а не прятать смысл кнопки.
            button.setMinWidth(Region.USE_PREF_SIZE);
            ButtonData data = buttonType.getButtonData();
            switch (data) {
                case OK_DONE, FINISH, YES, APPLY -> button.disableProperty().bind(formValid.not());
                case NEXT_FORWARD -> button.disableProperty().bind(nextAllowed.not());
                case BACK_PREVIOUS -> button.disableProperty().bind(backAllowed.not());
                default -> {
                    // Отмена, «Сбросить» и прочие кнопки доступны всегда.
                }
            }
        }
        return node;
    }
}
