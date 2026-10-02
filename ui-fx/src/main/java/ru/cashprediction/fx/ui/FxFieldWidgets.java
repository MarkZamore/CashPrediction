package ru.cashprediction.fx.ui;

import java.util.*;
import java.util.function.BiConsumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import ru.cashprediction.core.ui.form.*;

/** Закрытый набор элементов формы; значение не проверяется клиентом. */
public final class FxFieldWidgets {
    /** Добавляет разделитель только непустой визуальной подписи формы (§6.0). */
    static String caption(String text) { return text.isEmpty() || text.endsWith(":") ? text : text + ":"; }

    /** Дамп хранит семантическую подпись без визуального разделителя, как Swing и Web. */
    static String semanticCaption(String text) { return text.endsWith(":") ? text.substring(0, text.length() - 1) : text; }
    final FieldSpec spec;
    final Node control;
    final HBox root;
    final Label label;
    private final BiConsumer<String, Boolean> changed;
    private final FormSession session;
    private final FxClassUsageProbe probe;
    private boolean updating;
    private List<Option> options;
    final List<FxFieldWidgets> radioPeers = new ArrayList<>();
    private ToggleGroup radioGroup = new ToggleGroup();

    /** Создаёт элемент и передаёт сырой ввод в сеанс ядра. */
    public FxFieldWidgets(FieldSpec spec, FormSession session, FxUiPort port) {
        this(spec, session, port, null);
    }

    /** Позволяет специализированному диалогу сохранить его настоящий редактор. */
    @SuppressWarnings("unchecked")
    public FxFieldWidgets(FieldSpec spec, FormSession session, FxUiPort port, Control preferred) {
        this.spec = spec; this.session = session; this.probe = port.probe; options = spec.options(); radioPeers.add(this);
        changed = (text, committed) -> { if (!updating) session.fieldChanged(spec.id(), text, committed, ++port.clientRevision); };
        label = new Label(caption(spec.label())); label.setMinWidth(150); label.setAlignment(Pos.CENTER_RIGHT);
        control = switch (spec.kind()) {
            case CHECK -> {
                CheckBox check = new CheckBox(spec.label()); check.setOnAction(e -> changed.accept(Boolean.toString(check.isSelected()), true)); yield check;
            }
            case RADIO -> {
                Pane pane = spec.orientation() == Orientation.VERTICAL ? new VBox(0) : new HBox(8);
                radio(pane, options); yield pane;
            }
            case CHOICE, EDITABLE_CHOICE -> {
                ComboBox<Option> combo = preferred instanceof ComboBox<?> c ? (ComboBox<Option>) c : new ComboBox<>(); combo.setEditable(spec.kind() == FieldKind.EDITABLE_CHOICE);
                combo.setConverter(converter()); combo.getItems().setAll(options);
                combo.setOnAction(e -> changed.accept(combo.isEditable() ? optionValue(combo.getEditor().getText()) : combo.getValue() == null ? "" : combo.getValue().value(), true));
                if (combo.isEditable()) combo.getEditor().textProperty().addListener((o, a, b) -> changed.accept(optionValue(b), false));
                yield combo;
            }
            case LIST, PREVIEW -> {
                ListView<String> list = new ListView<>(); list.setPrefHeight(Math.max(2, spec.textRows()) * 28);
                list.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
                    if (updating || b.intValue() < 0) return;
                    if (spec.kind() == FieldKind.PREVIEW) session.previewSelected(b.intValue(), false);
                    else if (b.intValue() < options.size()) changed.accept(options.get(b.intValue()).value(), true);
                });
                list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) {
                    int index = list.getSelectionModel().getSelectedIndex();
                    if (spec.kind() == FieldKind.PREVIEW) session.previewSelected(index, true); else session.fieldActivated(spec.id(), index);
                } }); yield list;
            }
            case BUTTON -> { Button b = new Button(spec.label()); b.setOnAction(e -> session.buttonPressed(spec.id())); yield b; }
            case RESULT_LINES -> new VBox(4);
            case SPINNER -> {
                Spinner<Long> spinner = new Spinner<>(); spinner.setEditable(true);
                SpinnerValueFactory<Long> factory = new SpinnerValueFactory<>() {
                    { setConverter(new javafx.util.converter.LongStringConverter()); setValue(spec.min()); }
                    /** Перемещает значение по диапазону виджета из спецификации. */
                    @Override public void decrement(int steps) { setValue(Math.max(spec.min(), getValue() - spec.step() * steps)); }
                    /** Перемещает значение по диапазону виджета из спецификации. */
                    @Override public void increment(int steps) { setValue(Math.min(spec.max(), getValue() + spec.step() * steps)); }
                };
                spinner.setValueFactory(factory); textEvents(spinner.getEditor());
                factory.valueProperty().addListener((o, a, b) -> changed.accept(b.toString(), true));
                spinner.getEditor().setOnAction(e -> { changed.accept(spinner.getEditor().getText(), true); session.fieldSubmitted(spec.id()); });
                yield spinner;
            }
            case MULTILINE -> {
                TextArea area = new TextArea(); area.setPromptText(spec.prompt()); area.setPrefColumnCount(Math.max(8, spec.columns())); area.setPrefRowCount(spec.textRows()); area.setWrapText(true);
                textEvents(area); yield area;
            }
            default -> {
                TextField field = preferred instanceof TextField t ? t : new TextField(); field.setPromptText(spec.prompt()); field.setPrefColumnCount(Math.max(8, spec.columns()));
                if (spec.kind() == FieldKind.MONEY || spec.kind() == FieldKind.SPINNER) field.setAlignment(Pos.CENTER_RIGHT);
                textEvents(field); field.setOnAction(e -> { changed.accept(field.getText(), true); session.fieldSubmitted(spec.id()); });
                yield field;
            }
        };
        FxStyles.id(control, spec.id());
        if (control instanceof TextArea area) {
            // Высота строк берётся из реального шрифта, рамка занимает по пикселю с каждой стороны.
            var sample = new javafx.scene.text.Text("Ag");
            sample.setFont(javafx.scene.text.Font.font(ru.cashprediction.core.ui.token.DesignTokens.FONT_FAMILY,
                    ru.cashprediction.core.ui.token.FontToken.BASE.sizePx()));
            area.setPrefHeight(Math.max(2, spec.textRows()) * Math.ceil(sample.getLayoutBounds().getHeight()) + 2);
        }
        if (control instanceof Region region) {
            region.setMinWidth(0);
            // Пиксельная ширина принадлежит общему контракту поля и не зависит от вида формы.
            if (spec.widthPx() > 0) {
                region.setMinWidth(spec.widthPx()); region.setPrefWidth(spec.widthPx()); region.setMaxWidth(spec.widthPx());
            }
            if (control instanceof Control && spec.kind() != FieldKind.MULTILINE
                    && spec.kind() != FieldKind.LIST && spec.kind() != FieldKind.PREVIEW) {
                // Однострочные редакторы имеют высоту §1.3, а не платформенный размер Modena.
                region.setMinHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
                region.setPrefHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
                region.setMaxHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            }
        }
        root = new HBox(10); root.setAlignment(Pos.CENTER_LEFT);
        if (!spec.wide() && spec.kind() != FieldKind.CHECK && !spec.label().isEmpty()) root.getChildren().add(label);
        root.getChildren().add(control); HBox.setHgrow(control, spec.widthPx() > 0 ? Priority.NEVER : Priority.ALWAYS);
        if (spec.kind() == FieldKind.DATE) {
            Button calendar = new Button("\u25a6"); calendar.setTooltip(FxStyles.tip(ru.cashprediction.core.ui.text.UiText.get("calendar.button.tip"), probe));
            calendar.setMinHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            calendar.setPrefHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            calendar.setMaxHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            calendar.setOnAction(e -> port.calendar(control, text(), v -> changed.accept(v, true))); root.getChildren().add(calendar);
        }
        if (!spec.suffix().isEmpty()) root.getChildren().add(new Label(spec.suffix()));
        if (control instanceof Control c && !spec.tooltip().isEmpty()) c.setTooltip(FxStyles.tip(spec.tooltip(), probe));
    }

    private void textEvents(TextInputControl text) {
        text.textProperty().addListener((o, a, b) -> changed.accept(b, false));
        text.focusedProperty().addListener((o, a, b) -> { if (!b) changed.accept(text.getText(), true); });
    }

    private void radio(Pane pane, List<Option> entries) {
        pane.getChildren().clear(); ToggleGroup group = radioGroup;
        for (Option option : entries) {
            RadioButton b = new RadioButton(option.text()); b.setUserData(option.value()); b.setToggleGroup(group);
            // Каждая строка радио-группы имеет общую высоту контрола; вертикальная группа не удваивает промежутки.
            b.setMinHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            b.setPrefHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            b.setMaxHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
            b.setOnAction(e -> changed.accept(option.value(), true)); pane.getChildren().add(b);
        }
    }

    private String optionValue(String display) { return options.stream().filter(o -> o.text().equals(display)).map(Option::value).findFirst().orElse(display); }

    /** Объединяет физические фрагменты одного логического радио-поля без изменения рядов формы. */
    public void attachRadioPeer(FxFieldWidgets peer) {
        radioPeers.add(peer); peer.radioGroup = radioGroup;
        for (Node node : ((Pane) peer.control).getChildren()) if (node instanceof RadioButton b) b.setToggleGroup(radioGroup);
    }

    /** Возвращает фактические переключатели в порядке физических рядов. */
    public List<RadioButton> radioButtons() {
        return radioPeers.stream().flatMap(p -> ((Pane) p.control).getChildren().stream()).filter(n -> n instanceof RadioButton).map(n -> (RadioButton) n).toList();
    }

    private StringConverter<Option> converter() {
        return new StringConverter<>() {
            /** Отдаёт готовую подпись варианта. */
            @Override public String toString(Option o) { return o == null ? "" : o.text(); }
            /** Сопоставляет подпись с каноническим значением без проверки. */
            @Override public Option fromString(String s) { return options.stream().filter(o -> o.text().equals(s)).findFirst().orElse(Option.of(s, s)); }
        };
    }

    /** Применяет видимость и значение; null сохраняет текущий сырой ввод. */
    @SuppressWarnings("unchecked")
    public void update(FieldView view, FormView form) {
        if (view == null) return;
        updating = true;
        try {
            root.setVisible(view.visible()); root.setManaged(view.visible()); control.setDisable(!view.enabled());
            if (view.label() != null) { label.setText(caption(view.label())); if (control instanceof CheckBox check) check.setText(view.label()); }
            if (view.options() != null && !view.options().equals(options)) {
                options = view.options();
                if (control instanceof ComboBox<?> combo) ((ComboBox<Option>) combo).getItems().setAll(options);
                if (spec.kind() == FieldKind.RADIO) radio((Pane) control, options);
            }
            if (control instanceof TextInputControl text) text.setEditable(!view.readOnly());
            if (view.tooltip() != null && control instanceof Control c) c.setTooltip(FxStyles.tip(view.tooltip(), probe));
            if (control instanceof ListView<?> l) {
                ListView<String> list = (ListView<String>) l;
                List<String> values = spec.kind() == FieldKind.PREVIEW ? form.preview().stream().map(PreviewItem::text).toList() : options.stream().map(Option::text).toList();
                if (!list.getItems().equals(values)) list.getItems().setAll(values);
            }
            if (view.value() != null && spec.kind() != FieldKind.PREVIEW) setText(view.value());
        } finally { updating = false; }
    }

    /** Читает фактическое значение виджета для снимка. */
    @SuppressWarnings("unchecked")
    public String text() {
        if (control instanceof TextInputControl t) return t.getText();
        if (control instanceof Spinner<?> s) return s.getEditor().getText();
        if (control instanceof CheckBox c) return Boolean.toString(c.isSelected());
        if (control instanceof ComboBox<?> c) { Option o = (Option) c.getValue(); return c.isEditable() ? c.getEditor().getText() : o == null ? "" : o.text(); }
        if (control instanceof ListView<?> l) return spec.kind() == FieldKind.PREVIEW ? "" : Objects.toString(l.getSelectionModel().getSelectedItem(), "");
        if (spec.kind() == FieldKind.RADIO) return radioGroup.getSelectedToggle() == null ? "" : Objects.toString(radioGroup.getSelectedToggle().getUserData(), "");
        return "";
    }

    /** Задаёт значение через виджет; в самотесте вызывает те же слушатели. */
    @SuppressWarnings("unchecked")
    public void setText(String value) {
        if (control instanceof TextInputControl t) t.setText(value);
        else if (control instanceof Spinner<?> s) {
            Spinner<Long> spinner = (Spinner<Long>) s;
            if (updating) FieldCodec.parseLong(value).ifPresent(v -> spinner.getValueFactory().setValue(v));
            spinner.getEditor().setText(value);
        }
        else if (control instanceof CheckBox c) { c.setSelected(Boolean.parseBoolean(value)); if (!updating) c.fireEvent(new javafx.event.ActionEvent()); }
        else if (control instanceof ComboBox<?> c) {
            ComboBox<Option> combo = (ComboBox<Option>) c;
            Option selected = options.stream().filter(o -> o.value().equals(value) || o.text().equals(value)).findFirst().orElse(Option.of(value, value));
            combo.setValue(selected); if (combo.isEditable()) combo.getEditor().setText(selected.text());
        } else if (spec.kind() == FieldKind.RADIO) {
            for (RadioButton r : radioButtons()) if (value.equals(r.getUserData()) || value.equals(r.getText())) { if (updating) r.setSelected(true); else r.fire(); }
        } else if (control instanceof ListView<?> list) {
            int index = -1; for (int i = 0; i < options.size(); i++) if (options.get(i).value().equals(value) || options.get(i).text().equals(value)) index = i;
            list.getSelectionModel().select(index);
        }
    }
}
